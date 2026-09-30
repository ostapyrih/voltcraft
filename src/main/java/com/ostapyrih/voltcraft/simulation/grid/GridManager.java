package com.ostapyrih.voltcraft.simulation.grid;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.Conductor;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.cable.CableBlock;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative grid manager: kernel-owned island topology.
 *
 * <p>Discovery index ({@code knownCables} + {@code attachedBlocks}) is rebuilt into
 * {@link IslandContext} islands only when dirty. Each island owns its node-index map,
 * element/terminal/conductor lists, node count, kernel instance, omega, and fallback flag.</p>
 *
 * <p>Persistence is cables-only (no voltages, no states): {@link #toSaveData} /
 * {@link #fromSaveData} round-trip {@code knownCables}.</p>
 *
 * <p>Node rules: the node set is cable positions union declared terminal
 * positions of {@link KernelAttachedBlock}s. A cable and a terminal at the same
 * position are the same node. A block-entity position is NOT a node unless a
 * cable or terminal sits there. Conductors form only between 6-Manhattan
 * adjacent nodes (no diagonals), purely mechanical with no semantic filtering:
 * cable-to-cable and cable-to-terminal use {@code cableR} (per-type base
 * resistance, averaged for mixed gauges, else
 * {@link CableConductorAdapter#DEFAULT_CABLE_R_OHM}); terminal-to-terminal uses
 * {@link CableConductorAdapter#TERMINAL_LINK_R_OHM}. If either endpoint of a pair
 * is a cable node (including a node that is both cable and terminal), the pair is
 * a cable conductor; the terminal link applies only to pure terminal-to-terminal pairs.</p>
 *
 * <p>Island connectivity = topology conductors OR same-block terminal
 * ownership: all terminals of one block land in the same island even with no
 * cable between them. Implemented with union-find (BFS-equivalent) over the
 * sorted node set; islands are ordered by their minimum node position, node
 * indices follow the sorted node order, and element indices follow the sorted
 * block-entity positions — all deterministic regardless of discovery order.
 * Zero-terminal elements are island-agnostic and are hosted by exactly one island
 * (the first in order) so they tick once per server tick.</p>
 *
 * <p>Index lifecycle: entries arrive via place/break hooks and chunk-load scans
 * (which imply a loaded chunk, so they mark it loaded); chunk unload clears the
 * loaded flag and drops attached-block references of that chunk (block entities are
 * re-registered by the next chunk-load scan), while cable entries are retained for
 * persistence. Rebuilds include only positions whose own chunk is currently loaded,
 * so unloaded chunks contribute nothing. No full scan runs per tick. Melts and
 * topology mutations are queued ({@code pendingBreaks}, dirty flag) and applied
 * at the next rebuild boundary only, never mid-tick.</p>
 */
public class GridManager extends PersistentState {

    /** Deterministic position order used for every index map and island ordering.
     * Node {@code 0} in each island (the lowest-position node) is the kernel reference (V = 0). */
    public static final Comparator<BlockPos> POS_ORDER = Comparator
        .comparingInt(BlockPos::getX)
        .thenComparingInt(BlockPos::getY)
        .thenComparingInt(BlockPos::getZ);

    /** Only +X/+Y/+Z are scanned so each unordered adjacent pair is visited once. */
    private static final Direction[] POSITIVE_DIRECTIONS = {Direction.EAST, Direction.UP, Direction.SOUTH};

    public static final String PERSISTENCE_KEY = "voltcraft_power_grids";

    /**
     * Pure-Java chunk key. {@code net.minecraft.util.math.ChunkPos} cannot be
     * used here: its static initializer requires bootstrapped registries, which
     * unit tests do not have. Production {@link ChunkPos} values are converted
     * at the boundary ({@link #onChunkLoad(ChunkPos)}).
     */
    public record ChunkKey(int x, int z) {
        static ChunkKey of(BlockPos pos) {
            return new ChunkKey(pos.getX() >> 4, pos.getZ() >> 4);
        }
    }

    // ---------------------------------------------------------------------
    // Persistence: cables only
    // ---------------------------------------------------------------------

    public record CableSaveData(BlockPos pos, String type) {
        public static final Codec<CableSaveData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("pos").forGetter(CableSaveData::pos),
            Codec.STRING.optionalFieldOf("type", ConductorType.INSULATED_COPPER.name()).forGetter(CableSaveData::type)
        ).apply(instance, CableSaveData::new));
    }

    public record GridManagerSaveData(List<CableSaveData> cables) {
        public static final Codec<GridManagerSaveData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            CableSaveData.CODEC.listOf().fieldOf("cables").forGetter(GridManagerSaveData::cables)
        ).apply(instance, GridManagerSaveData::new));
    }

    // ---------------------------------------------------------------------
    // Island topology index (kernel-owned; the only subsystem)
    // ---------------------------------------------------------------------

    private final Set<ChunkKey> loadedChunks = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<BlockPos, ConductorType> knownCables = new ConcurrentHashMap<>();
    private final Map<BlockPos, KernelAttachedBlock> attachedBlocks = new ConcurrentHashMap<>();
    private final List<IslandContext> islands = new ArrayList<>();
    private final Set<BlockPos> pendingBreaks = new LinkedHashSet<>();
    private volatile boolean topologyDirty = true;
    private boolean seeded = false;
    private CableScanner cableScanner = GridManager::defaultScan;

    /** World-scan hook, replaceable in tests (mockable seed source). */
    @FunctionalInterface
    public interface CableScanner {
        Map<BlockPos, ConductorType> scan(ServerWorld world, Set<ChunkKey> chunks);
    }

    public static final Codec<GridManager> CODEC = GridManagerSaveData.CODEC.xmap(
        GridManager::fromSaveData,
        GridManager::toSaveData
    );

    public static final PersistentStateType<GridManager> TYPE = new PersistentStateType<>(
        PERSISTENCE_KEY,
        GridManager::new,
        CODEC,
        DataFixTypes.LEVEL
    );

    public GridManager() {}

    // ---------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------

    public GridManagerSaveData toSaveData() {
        List<CableSaveData> list = new ArrayList<>();
        List<Map.Entry<BlockPos, ConductorType>> entries = new ArrayList<>(knownCables.entrySet());
        entries.sort(Map.Entry.comparingByKey(POS_ORDER));
        for (Map.Entry<BlockPos, ConductorType> entry : entries) {
            list.add(new CableSaveData(entry.getKey().toImmutable(), entry.getValue().name()));
        }
        return new GridManagerSaveData(list);
    }

    public static GridManager fromSaveData(GridManagerSaveData data) {
        GridManager manager = new GridManager();
        if (data == null || data.cables() == null) {
            return manager;
        }
        for (CableSaveData cable : data.cables()) {
            if (cable == null || cable.pos() == null) {
                continue;
            }
            manager.putCableRaw(cable.pos().toImmutable(), parseConductorType(cable.type()));
        }
        manager.topologyDirty = true;
        return manager;
    }

    private static ConductorType parseConductorType(String name) {
        if (name == null) {
            return ConductorType.INSULATED_COPPER;
        }
        try {
            return ConductorType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ConductorType.INSULATED_COPPER;
        }
    }

    // ---------------------------------------------------------------------
    // Lifecycle wiring
    // ---------------------------------------------------------------------

    public static GridManager get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(TYPE);
    }

    public static void initialize() {
        // Register server tick event: centralized kernel-island tick execution
        ServerTickEvents.END_WORLD_TICK.register(world -> get(world).tick(world));

        // Chunk boundaries: load scans the chunk for cables and attached block entities,
        // unload drops the chunk's attached-block references.
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> get(world).onChunkLoad(world, chunk));
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> get(world).onChunkUnload(chunk.getPos()));
    }

    public void tick(ServerWorld world) {
        // Kernel island path. Tick order: rebuild if dirty -> per-island kernel.tick() ->
        // fallback branch stub -> melted scan (converged solutions only) -> queue break + dirty.
        // Mutations queued during island ticks apply at the next rebuild boundary only.
        if (!seeded && world != null) {
            // Seed only once there is something to seed from; an empty loaded set
            // would otherwise consume the one-shot seed for nothing.
            if (!loadedChunks.isEmpty()) {
                seedFromLoadedChunks(world);
                seeded = true;
            }
        } else if (!seeded) {
            seeded = true;
        }
        if (topologyDirty) {
            applyPendingBreaks(world);
            rebuildIslands();
            topologyDirty = false;
        }
        for (IslandContext island : islands) {
            tickIsland(island, world);
        }
    }

    private void tickIsland(IslandContext island, ServerWorld world) {
        // Block pre-tick seam: each attached block runs its discrete phase (staging, trips, bookkeeping).
        for (KernelAttachedBlock block : island.blocks()) {
            block.tickElectrical(world);
        }
        ElectricalKernel.KernelSolveResult observed = island.kernel().tick();
        island.setFallbackActive(observed.fallbackActive());
        if (observed.converged() && !observed.singular()) {
            syncKernelStatesToBlocks(island);
            // Melt detection only on a valid operating point: a diverged or singular
            // solve produces meaningless currents and must not destroy cables.
            for (Conductor conductor : island.kernel().findMeltedConductors()) {
                if (conductor instanceof CableConductorAdapter adapter
                    && !adapter.isTerminalLink()
                    && adapter.breakCandidate() != null) {
                    pendingBreaks.add(adapter.breakCandidate());
                }
            }
        }
        // Else discard branch stub: kernel.tick() already gates integration on
        // converged && !singular, so failed operating points change no state.
        if (!pendingBreaks.isEmpty()) {
            markTopologyDirty();
        }
    }

    private static void syncKernelStatesToBlocks(IslandContext island) {
        List<KernelAttachedBlock> blocks = island.blocks();
        for (int i = 0; i < blocks.size(); i++) {
            blocks.get(i).setStateArray(island.kernel().getElementState(i));
        }
    }

    // ---------------------------------------------------------------------
    // Island discovery index
    // ---------------------------------------------------------------------

    /**
     * Records a cable observation; implies its chunk is loaded. Marks dirty
     * (topology and persistence). World-free so tests can drive the index directly.
     */
    public void putCable(BlockPos pos, ConductorType type) {
        BlockPos imm = pos.toImmutable();
        knownCables.put(imm, type != null ? type : ConductorType.INSULATED_COPPER);
        loadedChunks.add(ChunkKey.of(imm));
        markTopologyDirty();
        markDirty();
    }

    /** Records a cable without touching chunk state (used when restoring persisted data). */
    void putCableRaw(BlockPos pos, ConductorType type) {
        knownCables.put(pos.toImmutable(), type != null ? type : ConductorType.INSULATED_COPPER);
        markTopologyDirty();
    }

    public void removeCable(BlockPos pos) {
        knownCables.remove(pos);
        markTopologyDirty();
        markDirty();
    }

    /** Records a kernel-attached block; implies its chunk and its terminals' chunks are loaded. Marks dirty. */
    public void putAttachedBlock(KernelAttachedBlock block) {
        if (block == null || block.getPos() == null) {
            return;
        }
        BlockPos imm = block.getPos().toImmutable();
        attachedBlocks.put(imm, block);
        loadedChunks.add(ChunkKey.of(imm));
        BlockPos[] terminals = block.getTerminalPositions();
        if (terminals != null) {
            for (BlockPos terminal : terminals) {
                if (terminal != null) {
                    loadedChunks.add(ChunkKey.of(terminal));
                }
            }
        }
        markTopologyDirty();
    }

    public void removeAttachedBlock(BlockPos pos) {
        attachedBlocks.remove(pos);
        markTopologyDirty();
    }

    /**
     * Production chunk-load hook: marks the chunk loaded, then scans it for cables and
     * kernel-attached block entities so chunks that were not part of the initial seed
     * are discovered.
     */
    public void onChunkLoad(ServerWorld world, WorldChunk chunk) {
        if (chunk == null) {
            return;
        }
        ChunkPos cp = chunk.getPos();
        loadedChunks.add(new ChunkKey(cp.x, cp.z));
        Map<BlockPos, ConductorType> found = new HashMap<>();
        try {
            scanChunkInto(chunk, found);
        } catch (Exception ignored) {
            // A chunk that cannot be scanned contributes nothing this time.
        }
        for (Map.Entry<BlockPos, ConductorType> entry : found.entrySet()) {
            putCableRaw(entry.getKey(), entry.getValue());
        }
        try {
            Map<BlockPos, BlockEntity> entities = chunk.getBlockEntities();
            if (entities != null) {
                for (BlockEntity be : new ArrayList<>(entities.values())) {
                    if (be instanceof KernelAttachedBlock kab) {
                        putAttachedBlock(kab);
                    }
                }
            }
        } catch (Exception ignored) {
            // See above.
        }
        markTopologyDirty();
    }

    public void onChunkLoad(ChunkPos chunkPos) {
        onChunkLoad(chunkPos.x, chunkPos.z);
    }

    /**
     * Production chunk-unload hook: drops attached-block references that belong to the
     * chunk (stale block entities are re-registered on the next load scan), then clears
     * the loaded flag. Cable entries are retained for persistence.
     */
    public void onChunkUnload(ChunkPos chunkPos) {
        ChunkKey key = new ChunkKey(chunkPos.x, chunkPos.z);
        attachedBlocks.keySet().removeIf(pos -> ChunkKey.of(pos).equals(key));
        onChunkUnload(chunkPos.x, chunkPos.z);
    }

    /** World-free overload so tests can drive chunk boundaries without Minecraft registries. */
    public void onChunkLoad(int chunkX, int chunkZ) {
        loadedChunks.add(new ChunkKey(chunkX, chunkZ));
        markTopologyDirty();
    }

    /** World-free overload so tests can drive chunk boundaries without Minecraft registries. */
    public void onChunkUnload(int chunkX, int chunkZ) {
        loadedChunks.remove(new ChunkKey(chunkX, chunkZ));
        markTopologyDirty();
    }

    public void setCableScanner(CableScanner scanner) {
        this.cableScanner = scanner != null ? scanner : GridManager::defaultScan;
    }

    /**
     * Seeds the cable index from a full scan of the currently loaded chunks,
     * then seeds kernel-attached block entities from the same chunks.
     * Runs at most once per manager lifetime (first tick with a non-empty loaded set).
     */
    public void seedFromLoadedChunks(ServerWorld world) {
        if (world == null || loadedChunks.isEmpty()) {
            return;
        }
        Map<BlockPos, ConductorType> found = cableScanner.scan(world, new HashSet<>(loadedChunks));
        if (found == null) {
            return;
        }
        for (Map.Entry<BlockPos, ConductorType> entry : found.entrySet()) {
            if (entry.getKey() != null) {
                putCableRaw(entry.getKey().toImmutable(), entry.getValue());
            }
        }
        seedAttachedBlocks(world);
        markTopologyDirty();
        markDirty();
    }

    /**
     * Production seed path for {@link KernelAttachedBlock} block entities. Walks only
     * chunks already in the loaded set (never touches unloaded chunks) and registers
     * every attached BE found via {@code WorldChunk.getBlockEntities()}. Null/world-safe:
     * a null world, an empty loaded set, or an unloadable chunk simply seeds nothing.
     * {@link #putAttachedBlock} re-marks each BE's chunk loaded, so the set only
     * grows by chunks that genuinely hold attached blocks.
     */
    public void seedAttachedBlocks(ServerWorld world) {
        if (world == null || loadedChunks.isEmpty()) {
            return;
        }
        for (ChunkKey chunk : new HashSet<>(loadedChunks)) {
            if (chunk == null) {
                continue;
            }
            WorldChunk worldChunk;
            try {
                worldChunk = world.getChunk(chunk.x(), chunk.z());
            } catch (Exception e) {
                continue;
            }
            if (worldChunk == null) {
                continue;
            }
            Map<BlockPos, BlockEntity> entities;
            try {
                entities = worldChunk.getBlockEntities();
            } catch (Exception e) {
                continue;
            }
            if (entities == null) {
                continue;
            }
            for (BlockEntity be : new ArrayList<>(entities.values())) {
                if (be instanceof KernelAttachedBlock kab) {
                    putAttachedBlock(kab);
                }
            }
        }
        markTopologyDirty();
    }

    private static Map<BlockPos, ConductorType> defaultScan(ServerWorld world, Set<ChunkKey> chunks) {
        Map<BlockPos, ConductorType> found = new HashMap<>();
        for (ChunkKey chunk : chunks) {
            scanChunkInto(world.getChunk(chunk.x(), chunk.z()), found);
        }
        return found;
    }

    /** Section walk skips empty sections; far cheaper than a full height-column scan. */
    private static void scanChunkInto(WorldChunk worldChunk, Map<BlockPos, ConductorType> found) {
        ChunkPos cp = worldChunk.getPos();
        net.minecraft.world.chunk.ChunkSection[] sections = worldChunk.getSectionArray();
        int bottomCoord = worldChunk.getBottomSectionCoord();
        for (int s = 0; s < sections.length; s++) {
            net.minecraft.world.chunk.ChunkSection section = sections[s];
            if (section.isEmpty()) {
                continue;
            }
            int baseY = (bottomCoord + s) * 16;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        if (section.getBlockState(x, y, z).getBlock() instanceof CableBlock cable) {
                            found.put(new BlockPos(cp.x * 16 + x, baseY + y,
                                cp.z * 16 + z).toImmutable(), cable.getConductorType());
                        }
                    }
                }
            }
        }
    }

    public boolean isTopologyDirty() {
        return topologyDirty;
    }

    public void markTopologyDirty() {
        this.topologyDirty = true;
    }

    /** Test/reset helper: clears the index, islands, queues, and seed state. */
    public void clearTopology() {
        knownCables.clear();
        attachedBlocks.clear();
        islands.clear();
        pendingBreaks.clear();
        topologyDirty = true;
        seeded = true;
    }

    public Set<BlockPos> getKnownCablePositions() {
        return Collections.unmodifiableSet(new HashSet<>(knownCables.keySet()));
    }

    public Set<BlockPos> getPendingBreaks() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(pendingBreaks));
    }

    public List<IslandContext> getIslands() {
        return Collections.unmodifiableList(islands);
    }

    public IslandContext getIslandAt(BlockPos pos) {
        for (IslandContext island : islands) {
            if (island.nodeIndex().containsKey(pos)) {
                return island;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Island rebuild (BFS-equivalent connectivity via union-find)
    // ---------------------------------------------------------------------

    /**
     * Rebuilds every island from the current discovery index. Only positions in
     * loaded chunks participate; unloaded chunks contribute nothing.
     * Clears the dirty flag on completion (tick relies on this).
     */
    public void rebuildIslands() {
        Map<BlockPos, ConductorType> cables = activeCables();
        Map<BlockPos, KernelAttachedBlock> blocks = activeBlocks();

        TreeSet<BlockPos> nodeSet = new TreeSet<>(POS_ORDER);
        nodeSet.addAll(cables.keySet());
        Map<BlockPos, List<KernelAttachedBlock>> terminalOwners = new HashMap<>();
        for (KernelAttachedBlock block : blocks.values()) {
            BlockPos[] terminals = block.getTerminalPositions();
            if (terminals == null) {
                continue;
            }
            for (BlockPos terminal : terminals) {
                if (terminal == null || !isChunkLoaded(terminal)) {
                    continue;
                }
                BlockPos imm = terminal.toImmutable();
                nodeSet.add(imm);
                terminalOwners.computeIfAbsent(imm, k -> new ArrayList<>()).add(block);
            }
        }

        List<BlockPos> nodes = new ArrayList<>(nodeSet);
        Map<BlockPos, Integer> nodeIndex = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            nodeIndex.put(nodes.get(i), i);
        }

        int[] parent = new int[nodes.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }

        List<Branch> branches = buildBranches(nodes, nodeIndex, cables, terminalOwners);
        for (Branch branch : branches) {
            union(parent, nodeIndex.get(branch.a()), nodeIndex.get(branch.b()));
        }
        // Same-block terminal ownership: every terminal of one block shares an island.
        // Only fully-loaded blocks union. A block with any terminal missing from the
        // node index (null, unloaded chunk, undiscovered) is deferred entirely until
        // its chunks load, keeping the union symmetric with the buildIsland candidacy
        // filter below.
        for (KernelAttachedBlock block : blocks.values()) {
            BlockPos[] terminals = block.getTerminalPositions();
            if (terminals == null || terminals.length < 2) {
                continue;
            }
            boolean fullyIndexed = true;
            for (BlockPos terminal : terminals) {
                if (terminal == null || !nodeIndex.containsKey(terminal.toImmutable())) {
                    fullyIndexed = false;
                    break;
                }
            }
            if (!fullyIndexed) {
                continue;
            }
            Integer first = null;
            for (BlockPos terminal : terminals) {
                Integer idx = nodeIndex.get(terminal.toImmutable());
                if (idx == null) {
                    continue;
                }
                if (first == null) {
                    first = idx;
                } else {
                    union(parent, first, idx);
                }
            }
        }

        Map<Integer, List<BlockPos>> groups = new TreeMap<>();
        for (BlockPos node : nodes) {
            int root = find(parent, nodeIndex.get(node));
            groups.computeIfAbsent(root, k -> new ArrayList<>()).add(node);
        }
        List<List<BlockPos>> orderedGroups = new ArrayList<>(groups.values());
        // Deterministic island order: sort by each group's minimum (already sorted) head.
        orderedGroups.sort((a, b) -> POS_ORDER.compare(a.get(0), b.get(0)));

        List<IslandContext> rebuilt = new ArrayList<>();
        for (int g = 0; g < orderedGroups.size(); g++) {
            // Zero-terminal elements are island-agnostic: host them in exactly one island
            // (the first) so each ticks once and its state is not overwritten by copies.
            rebuilt.add(buildIsland(orderedGroups.get(g), branches, blocks, g == 0));
        }
        islands.clear();
        islands.addAll(rebuilt);
        topologyDirty = false;
    }

    private Map<BlockPos, ConductorType> activeCables() {
        Map<BlockPos, ConductorType> active = new HashMap<>();
        for (Map.Entry<BlockPos, ConductorType> entry : knownCables.entrySet()) {
            if (isChunkLoaded(entry.getKey())) {
                active.put(entry.getKey(), entry.getValue());
            }
        }
        return active;
    }

    private Map<BlockPos, KernelAttachedBlock> activeBlocks() {
        Map<BlockPos, KernelAttachedBlock> active = new HashMap<>();
        for (Map.Entry<BlockPos, KernelAttachedBlock> entry : attachedBlocks.entrySet()) {
            if (isChunkLoaded(entry.getKey())) {
                active.put(entry.getKey(), entry.getValue());
            }
        }
        return active;
    }

    private record Branch(BlockPos a, BlockPos b, double resistance, ConductorType hint,
                          boolean terminalLink, BlockPos cableBreak) {
    }

    private static List<Branch> buildBranches(
        List<BlockPos> nodes,
        Map<BlockPos, Integer> nodeIndex,
        Map<BlockPos, ConductorType> cables,
        Map<BlockPos, List<KernelAttachedBlock>> terminalOwners
    ) {
        List<Branch> branches = new ArrayList<>();
        for (BlockPos pos : nodes) {
            for (Direction dir : POSITIVE_DIRECTIONS) {
                BlockPos neighbor = pos.offset(dir);
                if (!nodeIndex.containsKey(neighbor)) {
                    continue;
                }
                ConductorType posType = cables.get(pos);
                ConductorType neighborType = cables.get(neighbor);
                if (posType != null && neighborType != null) {
                    double resistance = (posType.getBaseResistance() + neighborType.getBaseResistance()) / 2.0;
                    branches.add(new Branch(pos, neighbor, resistance, posType, false, pos));
                } else if (terminalOwners.containsKey(pos) && terminalOwners.containsKey(neighbor)) {
                    // Pure terminal-to-terminal pair (neither endpoint is a cable).
                    branches.add(new Branch(pos, neighbor,
                        CableConductorAdapter.TERMINAL_LINK_R_OHM, null, true, null));
                }
            }
        }
        return branches;
    }

    private IslandContext buildIsland(
        List<BlockPos> groupNodes,
        List<Branch> branches,
        Map<BlockPos, KernelAttachedBlock> blocks,
        boolean hostsZeroTerminalElements
    ) {
        Set<BlockPos> groupSet = new HashSet<>(groupNodes);
        Map<BlockPos, Integer> localNodeIndex = new HashMap<>();
        for (int i = 0; i < groupNodes.size(); i++) {
            localNodeIndex.put(groupNodes.get(i), i);
        }

        // Blocks whose terminals touch this island, ordered by block position.
        // Partial-terminal NPE guard: a block participates in an island IFF its BE is
        // loaded AND every declared terminal is present in this island's group set.
        // The group set holds only loaded terminals, so the contains check covers both
        // chunk-loaded and indexed. Blocks with a terminal in an unloaded chunk are
        // excluded from ALL islands this rebuild (deferred until their chunks load)
        // instead of auto-unboxing a missing node index to an NPE. Null terminal sets
        // are skipped; 0-terminal elements form no nodes and are island-agnostic, so
        // only the hosting island (the first) takes them.
        List<KernelAttachedBlock> islandBlocks = new ArrayList<>();
        for (KernelAttachedBlock block : blocks.values()) {
            BlockPos[] terminals = block.getTerminalPositions();
            if (terminals == null) {
                continue;
            }
            if (terminals.length == 0) {
                if (hostsZeroTerminalElements) {
                    islandBlocks.add(block);
                }
                continue;
            }
            boolean allPresent = true;
            for (BlockPos terminal : terminals) {
                if (terminal == null || !groupSet.contains(terminal.toImmutable())) {
                    allPresent = false;
                    break;
                }
            }
            if (!allPresent) {
                continue;
            }
            islandBlocks.add(block);
        }
        islandBlocks.sort(Comparator.comparing(KernelAttachedBlock::getPos, POS_ORDER));

        List<ElectricalElement> elements = new ArrayList<>();
        List<int[]> terminalIndices = new ArrayList<>();
        Map<BlockPos, Integer> elementIndex = new HashMap<>();
        for (int i = 0; i < islandBlocks.size(); i++) {
            KernelAttachedBlock block = islandBlocks.get(i);
            elementIndex.put(block.getPos().toImmutable(), i);
            elements.add(block.getElement());
            BlockPos[] terminals = block.getTerminalPositions();
            int[] mapped = new int[terminals == null ? 0 : terminals.length];
            for (int j = 0; j < mapped.length; j++) {
                mapped[j] = localNodeIndex.get(terminals[j].toImmutable());
            }
            terminalIndices.add(mapped);
        }

        List<Conductor> conductors = new ArrayList<>();
        for (Branch branch : branches) {
            if (!groupSet.contains(branch.a()) || !groupSet.contains(branch.b())) {
                continue;
            }
            int nodeA = localNodeIndex.get(branch.a());
            int nodeB = localNodeIndex.get(branch.b());
            if (branch.terminalLink()) {
                conductors.add(CableConductorAdapter.terminalLink(nodeA, nodeB, branch.a(), branch.b()));
            } else {
                conductors.add(CableConductorAdapter.cableLink(nodeA, nodeB, branch.resistance(),
                    branch.hint(), branch.a(), branch.b(), branch.cableBreak()));
            }
        }

        double omega = resolveOmega(islandBlocks);

        ElectricalKernel kernel = new ElectricalKernel();
        kernel.setNodeCount(groupNodes.size());
        kernel.setElements(elements, terminalIndices);
        kernel.setConductors(conductors);
        kernel.setOmega(omega);
        // Sync stub: restore kernel-owned state slots from block snapshots so a
        // rebuild does not silently zero stateful elements (state slots restore from BE snapshots above).
        for (int i = 0; i < islandBlocks.size(); i++) {
            double[] snapshot = islandBlocks.get(i).getStateArray();
            int expected = elements.get(i).stateCount();
            if (snapshot != null && snapshot.length == expected && expected > 0) {
                kernel.setElementState(i, snapshot);
            }
        }

        return new IslandContext(kernel, elementIndex, localNodeIndex, islandBlocks,
            elements, terminalIndices, conductors, omega, groupNodes.size(), false);
    }

    /**
     * Omega policy: no active source, or all-DC actives, gives {@code 0};
     * at least one active AC source gives {@link GridConstants#AC_OMEGA_RAD_PER_S}.
     * Conductors and passives never determine omega.
     */
    private static double resolveOmega(List<KernelAttachedBlock> islandBlocks) {
        for (KernelAttachedBlock block : islandBlocks) {
            if (block.isActiveSource() && block.isACSource()) {
                return GridConstants.AC_OMEGA_RAD_PER_S;
            }
        }
        return 0.0;
    }

    private static int find(int[] parent, int x) {
        int root = x;
        while (parent[root] != root) {
            root = parent[root];
        }
        while (parent[x] != root) {
            int next = parent[x];
            parent[x] = root;
            x = next;
        }
        return root;
    }

    private static void union(int[] parent, int a, int b) {
        int rootA = find(parent, a);
        int rootB = find(parent, b);
        if (rootA != rootB) {
            parent[Math.max(rootA, rootB)] = Math.min(rootA, rootB);
        }
    }

    private void applyPendingBreaks(ServerWorld world) {
        if (pendingBreaks.isEmpty()) {
            return;
        }
        for (BlockPos pos : new ArrayList<>(pendingBreaks)) {
            knownCables.remove(pos);
            // Never force-load a chunk just to break a block in it.
            if (world != null
                && world.isChunkLoaded(pos)
                && world.getBlockState(pos).getBlock() instanceof CableBlock) {
                world.breakBlock(pos, false);
            }
        }
        pendingBreaks.clear();
        // Melted cables were removed from the persisted index.
        markDirty();
    }

    public Set<ChunkKey> getLoadedChunks() {
        return Collections.unmodifiableSet(loadedChunks);
    }

    public boolean isChunkLoaded(BlockPos pos) {
        return loadedChunks.contains(ChunkKey.of(pos));
    }

    // ---------------------------------------------------------------------
    // Placement / removal (island index updates)
    // ---------------------------------------------------------------------

    /**
     * Records a cable placement in the island discovery index. Only actual cable blocks
     * enter the cable index (other grid blocks join islands through their attached-block
     * terminals once discovered); connectivity is derived from adjacency at rebuild,
     * so no manual neighbor linking is needed.
     */
    public void onConductorPlaced(ServerWorld world, BlockPos pos, ConductorType type) {
        if (world != null && world.getBlockState(pos).getBlock() instanceof CableBlock) {
            putCable(pos, type); // marks topology + persistence dirty
        }
    }

    /**
     * Drops a cable index entry on removal. Mutations apply at the next rebuild boundary
     * through the normal dirty flag.
     */
    public void onConductorRemoved(ServerWorld world, BlockPos pos) {
        removeCable(pos); // marks topology + persistence dirty
    }
}