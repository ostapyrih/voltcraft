package com.ostapyrih.voltcraft.simulation.grid;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.ostapyrih.voltcraft.api.grid.IElectricalConnectable;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.simulation.solver.ThermalEquilibrium;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative grid manager.
 * Persists networks via Minecraft 1.21 PersistentState and handles chunk load/unload events.
 */
public class GridManager extends PersistentState {

    public static final String PERSISTENCE_KEY = "voltcraft_power_grids";

    public record ConductorSaveData(
        BlockPos start,
        BlockPos end,
        double baseResistance,
        double tempCoefficient,
        double heatCapacity,
        double coolingRate,
        double maxInsulationTemp,
        double meltingTemp,
        double maxAmpacity,
        boolean insulated
    ) {
        public static final Codec<ConductorSaveData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("start").forGetter(ConductorSaveData::start),
            BlockPos.CODEC.fieldOf("end").forGetter(ConductorSaveData::end),
            Codec.DOUBLE.fieldOf("r_base").forGetter(ConductorSaveData::baseResistance),
            Codec.DOUBLE.fieldOf("temp_coeff").forGetter(ConductorSaveData::tempCoefficient),
            Codec.DOUBLE.fieldOf("heat_cap").forGetter(ConductorSaveData::heatCapacity),
            Codec.DOUBLE.fieldOf("cooling").forGetter(ConductorSaveData::coolingRate),
            Codec.DOUBLE.fieldOf("max_ins_temp").forGetter(ConductorSaveData::maxInsulationTemp),
            Codec.DOUBLE.fieldOf("melt_temp").forGetter(ConductorSaveData::meltingTemp),
            Codec.DOUBLE.fieldOf("max_ampacity").forGetter(ConductorSaveData::maxAmpacity),
            Codec.BOOL.fieldOf("insulated").forGetter(ConductorSaveData::insulated)
        ).apply(instance, ConductorSaveData::new));
    }

    public record GridSaveData(
        String id,
        List<BlockPos> nodes,
        List<ConductorSaveData> conductors
    ) {
        public static final Codec<GridSaveData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(GridSaveData::id),
            BlockPos.CODEC.listOf().fieldOf("nodes").forGetter(GridSaveData::nodes),
            ConductorSaveData.CODEC.listOf().fieldOf("conductors").forGetter(GridSaveData::conductors)
        ).apply(instance, GridSaveData::new));
    }

    public record GridManagerSaveData(List<GridSaveData> grids) {
        public static final Codec<GridManagerSaveData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            GridSaveData.CODEC.listOf().fieldOf("grids").forGetter(GridManagerSaveData::grids)
        ).apply(instance, GridManagerSaveData::new));
    }

    private final Map<UUID, ElectricalGrid> grids = new ConcurrentHashMap<>();
    private final Map<BlockPos, UUID> posToGridMap = new ConcurrentHashMap<>();
    private final Set<ChunkPos> loadedChunks = Collections.newSetFromMap(new ConcurrentHashMap<>());

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
        List<GridSaveData> list = new ArrayList<>();
        for (ElectricalGrid grid : grids.values()) {
            list.add(toGridSaveData(grid));
        }
        return new GridManagerSaveData(list);
    }

    private static GridSaveData toGridSaveData(ElectricalGrid grid) {
        List<ConductorSaveData> conductors = new ArrayList<>();
        for (GridConductor c : grid.getConductors()) {
            conductors.add(toConductorSaveData(c));
        }
        return new GridSaveData(grid.getGridId().toString(), new ArrayList<>(grid.getNodePositions()), conductors);
    }

    private static ConductorSaveData toConductorSaveData(GridConductor c) {
        ThermalEquilibrium.ThermalSpec ts = c.getThermalSpec();
        return new ConductorSaveData(
            c.getStartPos(),
            c.getEndPos(),
            ts.baseResistance(),
            ts.tempCoefficient(),
            ts.heatCapacity(),
            ts.coolingRate(),
            ts.maxInsulationTemp(),
            ts.meltingTemp(),
            c.getMaxAmpacity(),
            c.isInsulated()
        );
    }

    public static GridManager fromSaveData(GridManagerSaveData data) {
        GridManager manager = new GridManager();
        if (data == null || data.grids() == null) {
            return manager;
        }
        for (GridSaveData gridData : data.grids()) {
            ElectricalGrid grid = gridFromSaveData(gridData);
            manager.grids.put(grid.getGridId(), grid);
            manager.mapNodesToGrid(grid);
        }
        return manager;
    }

    private static ElectricalGrid gridFromSaveData(GridSaveData gridData) {
        ElectricalGrid grid = new ElectricalGrid(parseGridIdOrRandom(gridData.id()));
        for (BlockPos p : gridData.nodes()) {
            grid.addNode(p, false);
        }
        for (ConductorSaveData cd : gridData.conductors()) {
            grid.addConductor(conductorFromSaveData(cd));
        }
        return grid;
    }

    private static GridConductor conductorFromSaveData(ConductorSaveData cd) {
        ThermalEquilibrium.ThermalSpec spec = new ThermalEquilibrium.ThermalSpec(
            cd.baseResistance(),
            cd.tempCoefficient(),
            cd.heatCapacity(),
            cd.coolingRate(),
            cd.maxInsulationTemp(),
            cd.meltingTemp()
        );
        return new GridConductor(cd.start(), cd.end(), spec, cd.maxAmpacity(), cd.insulated());
    }

    private static UUID parseGridIdOrRandom(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException | NullPointerException e) {
            return UUID.randomUUID();
        }
    }

    // ---------------------------------------------------------------------
    // Lifecycle wiring
    // ---------------------------------------------------------------------

    public static GridManager get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(TYPE);
    }

    public static void initialize() {
        // Register server tick event: centralized tick execution (1 aggregated tick per grid)
        ServerTickEvents.END_WORLD_TICK.register(world -> get(world).tick(world));

        // Track chunk loaded/unloaded boundaries
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> get(world).loadedChunks.add(chunk.getPos()));
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> get(world).loadedChunks.remove(chunk.getPos()));
    }

    public void tick(ServerWorld world) {
        List<ElectricalGrid> activeGrids = new ArrayList<>(grids.values());
        for (ElectricalGrid grid : activeGrids) {
            // A grid may have been unregistered by an earlier grid's tick this same pass
            // (e.g. a merge/split triggered by a block update) — skip it if so.
            if (grids.containsKey(grid.getGridId())) {
                grid.tick(world);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Registry
    // ---------------------------------------------------------------------

    public ElectricalGrid getGridAt(BlockPos pos) {
        UUID id = posToGridMap.get(pos);
        return id != null ? grids.get(id) : null;
    }

    public void registerGrid(ElectricalGrid grid) {
        grids.put(grid.getGridId(), grid);
        mapNodesToGrid(grid);
        markDirty();
    }

    public void unregisterGrid(UUID gridId) {
        ElectricalGrid grid = grids.remove(gridId);
        if (grid != null) {
            for (BlockPos pos : grid.getNodePositions()) {
                posToGridMap.remove(pos);
            }
        }
        markDirty();
    }

    private void mapNodesToGrid(ElectricalGrid grid) {
        for (BlockPos pos : grid.getNodePositions()) {
            posToGridMap.put(pos, grid.getGridId());
        }
    }

    public Collection<ElectricalGrid> getAllGrids() {
        return Collections.unmodifiableCollection(grids.values());
    }

    public boolean isChunkLoaded(BlockPos pos) {
        return loadedChunks.contains(new ChunkPos(pos));
    }

    // ---------------------------------------------------------------------
    // Placement / removal
    // ---------------------------------------------------------------------

    /**
     * Handles conductor or switch placement in the world, linking adjacent nodes and merging grids if necessary.
     */
    public void onConductorPlaced(ServerWorld world, BlockPos pos, ConductorType type) {
        List<BlockPos> connectedNeighbors = findThroughConnectableNeighbors(world, pos);
        ElectricalGrid targetGrid = resolveTargetGridForPlacement(pos, connectedNeighbors);
        linkNeighborsWithConductors(targetGrid, pos, connectedNeighbors, type);
        markDirty();
    }

    private List<BlockPos> findThroughConnectableNeighbors(ServerWorld world, BlockPos pos) {
        List<BlockPos> connected = new ArrayList<>();
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.offset(dir);
            BlockState neighborState = world.getBlockState(neighborPos);
            if (neighborState.getBlock() instanceof IElectricalConnectable connectable
                && connectable.canConnect(world, neighborPos, dir.getOpposite(), neighborState)
                && connectable.isThroughConductor()) {
                connected.add(neighborPos);
            }
        }
        return connected;
    }

    /**
     * Finds or creates the grid the new node at {@code pos} should join. If it touches
     * multiple existing grids, they are merged into one first.
     */
    private ElectricalGrid resolveTargetGridForPlacement(BlockPos pos, List<BlockPos> connectedNeighbors) {
        Set<UUID> touchingGridIds = new HashSet<>();
        for (BlockPos neighbor : connectedNeighbors) {
            UUID gridId = posToGridMap.get(neighbor);
            if (gridId != null && grids.containsKey(gridId)) {
                touchingGridIds.add(gridId);
            }
        }

        if (touchingGridIds.isEmpty()) {
            ElectricalGrid newGrid = new ElectricalGrid();
            newGrid.addNode(pos, false);
            registerGrid(newGrid);
            return newGrid;
        }

        Iterator<UUID> iter = touchingGridIds.iterator();
        ElectricalGrid targetGrid = grids.get(iter.next());
        while (iter.hasNext()) {
            ElectricalGrid secondary = grids.get(iter.next());
            if (secondary != null && secondary != targetGrid) {
                mergeIntoTarget(targetGrid, secondary);
            }
        }

        targetGrid.addNode(pos, false);
        posToGridMap.put(pos, targetGrid.getGridId());
        return targetGrid;
    }

    private void mergeIntoTarget(ElectricalGrid targetGrid, ElectricalGrid secondary) {
        GridTopologyHelper.mergeGrids(targetGrid, secondary);
        for (BlockPos p : secondary.getNodePositions()) {
            posToGridMap.put(p, targetGrid.getGridId());
        }
        grids.remove(secondary.getGridId());
    }

    private void linkNeighborsWithConductors(
        ElectricalGrid targetGrid,
        BlockPos pos,
        List<BlockPos> connectedNeighbors,
        ConductorType type
    ) {
        for (BlockPos neighbor : connectedNeighbors) {
            if (targetGrid.contains(neighbor) && !conductorExistsBetween(targetGrid, pos, neighbor)) {
                targetGrid.addConductor(new GridConductor(
                    pos,
                    neighbor,
                    type.toThermalSpec(),
                    type.getMaxAmpacity(),
                    type.isInsulated()
                ));
            }
        }
    }

    private static boolean conductorExistsBetween(ElectricalGrid grid, BlockPos a, BlockPos b) {
        for (GridConductor gc : grid.getConductors()) {
            boolean matches = (gc.getStartPos().equals(a) && gc.getEndPos().equals(b))
                || (gc.getStartPos().equals(b) && gc.getEndPos().equals(a));
            if (matches) {
                return true;
            }
        }
        return false;
    }

    /**
     * Handles conductor or switch destruction/removal in the world, splitting disjoint partitions if a bridge was cut.
     */
    public void onConductorRemoved(ServerWorld world, BlockPos pos) {
        UUID gridId = posToGridMap.remove(pos);
        if (gridId == null) {
            return;
        }
        ElectricalGrid grid = grids.get(gridId);
        if (grid == null) {
            return;
        }

        List<ElectricalGrid> resultingGrids = GridTopologyHelper.handleNodeRemoval(grid, pos);
        if (resultingGrids.isEmpty()) {
            unregisterGrid(gridId);
            return;
        }
        if (resultingGrids.size() > 1) {
            unregisterGrid(gridId);
            for (ElectricalGrid g : resultingGrids) {
                registerGrid(g);
            }
        }
        markDirty();
    }
}