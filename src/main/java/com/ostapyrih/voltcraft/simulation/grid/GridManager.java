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

    public GridManagerSaveData toSaveData() {
        List<GridSaveData> list = new ArrayList<>();
        for (ElectricalGrid g : grids.values()) {
            List<ConductorSaveData> conds = new ArrayList<>();
            for (GridConductor c : g.getConductors()) {
                ThermalEquilibrium.ThermalSpec ts = c.getThermalSpec();
                conds.add(new ConductorSaveData(
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
                ));
            }
            list.add(new GridSaveData(g.getGridId().toString(), new ArrayList<>(g.getNodePositions()), conds));
        }
        return new GridManagerSaveData(list);
    }

    public static GridManager fromSaveData(GridManagerSaveData data) {
        GridManager mgr = new GridManager();
        if (data != null && data.grids() != null) {
            for (GridSaveData gd : data.grids()) {
                UUID gridId;
                try {
                    gridId = UUID.fromString(gd.id());
                } catch (Exception e) {
                    gridId = UUID.randomUUID();
                }
                ElectricalGrid g = new ElectricalGrid(gridId);
                for (BlockPos p : gd.nodes()) {
                    g.addNode(p, false);
                }
                for (ConductorSaveData cd : gd.conductors()) {
                    ThermalEquilibrium.ThermalSpec ts = new ThermalEquilibrium.ThermalSpec(
                        cd.baseResistance(),
                        cd.tempCoefficient(),
                        cd.heatCapacity(),
                        cd.coolingRate(),
                        cd.maxInsulationTemp(),
                        cd.meltingTemp()
                    );
                    g.addConductor(new GridConductor(cd.start(), cd.end(), ts, cd.maxAmpacity(), cd.insulated()));
                }
                mgr.grids.put(g.getGridId(), g);
                for (BlockPos p : g.getNodePositions()) {
                    mgr.posToGridMap.put(p, g.getGridId());
                }
            }
        }
        return mgr;
    }

    public static GridManager get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(TYPE);
    }

    public static void initialize() {
        // Register server tick event: centralized tick execution (1 aggregated tick per grid)
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            GridManager manager = get(world);
            manager.tick(world);
        });

        // Track chunk loaded/unloaded boundaries
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            GridManager manager = get(world);
            manager.loadedChunks.add(chunk.getPos());
        });

        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            GridManager manager = get(world);
            manager.loadedChunks.remove(chunk.getPos());
        });
    }

    public void tick(ServerWorld world) {
        List<ElectricalGrid> activeGrids = new ArrayList<>(grids.values());
        for (ElectricalGrid grid : activeGrids) {
            if (grids.containsKey(grid.getGridId())) {
                grid.tick(world);
            }
        }
    }

    public ElectricalGrid getGridAt(BlockPos pos) {
        UUID id = posToGridMap.get(pos);
        if (id == null) return null;
        return grids.get(id);
    }

    public void registerGrid(ElectricalGrid grid) {
        grids.put(grid.getGridId(), grid);
        for (BlockPos pos : grid.getNodePositions()) {
            posToGridMap.put(pos, grid.getGridId());
        }
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

    public Collection<ElectricalGrid> getAllGrids() {
        return Collections.unmodifiableCollection(grids.values());
    }

    public boolean isChunkLoaded(BlockPos pos) {
        return loadedChunks.contains(new ChunkPos(pos));
    }

    /**
     * Handles conductor or switch placement in the world, linking adjacent nodes and merging grids if necessary.
     */
    public void onConductorPlaced(ServerWorld world, BlockPos pos, ConductorType type) {
        List<BlockPos> connectedNeighbors = new ArrayList<>();
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.offset(dir);
            BlockState neighborState = world.getBlockState(neighborPos);
            if (neighborState.getBlock() instanceof IElectricalConnectable connectable) {
                if (connectable.canConnect(world, neighborPos, dir.getOpposite(), neighborState)) {
                    if (connectable.isThroughConductor()) {
                        connectedNeighbors.add(neighborPos);
                    }
                }
            }
        }

        Set<UUID> touchingGridIds = new HashSet<>();
        for (BlockPos neighbor : connectedNeighbors) {
            UUID gridId = posToGridMap.get(neighbor);
            if (gridId != null && grids.containsKey(gridId)) {
                touchingGridIds.add(gridId);
            }
        }

        ElectricalGrid targetGrid;
        if (touchingGridIds.isEmpty()) {
            targetGrid = new ElectricalGrid();
            targetGrid.addNode(pos, false);
            registerGrid(targetGrid);
        } else {
            Iterator<UUID> iter = touchingGridIds.iterator();
            targetGrid = grids.get(iter.next());
            while (iter.hasNext()) {
                ElectricalGrid secondary = grids.get(iter.next());
                if (secondary != null && secondary != targetGrid) {
                    GridTopologyHelper.mergeGrids(targetGrid, secondary);
                    for (BlockPos p : secondary.getNodePositions()) {
                        posToGridMap.put(p, targetGrid.getGridId());
                    }
                    grids.remove(secondary.getGridId());
                }
            }
            targetGrid.addNode(pos, false);
            posToGridMap.put(pos, targetGrid.getGridId());
        }

        for (BlockPos neighbor : connectedNeighbors) {
            if (targetGrid.contains(neighbor)) {
                boolean exists = false;
                for (GridConductor gc : targetGrid.getConductors()) {
                    if ((gc.getStartPos().equals(pos) && gc.getEndPos().equals(neighbor)) ||
                        (gc.getStartPos().equals(neighbor) && gc.getEndPos().equals(pos))) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    GridConductor branch = new GridConductor(
                        pos,
                        neighbor,
                        type.toThermalSpec(),
                        type.getMaxAmpacity(),
                        type.isInsulated()
                    );
                    targetGrid.addConductor(branch);
                }
            }
        }
        markDirty();
    }

    /**
     * Handles conductor or switch destruction/removal in the world, splitting disjoint partitions if a bridge was cut.
     */
    public void onConductorRemoved(ServerWorld world, BlockPos pos) {
        UUID gridId = posToGridMap.remove(pos);
        if (gridId == null) return;
        ElectricalGrid grid = grids.get(gridId);
        if (grid == null) return;

        List<ElectricalGrid> resultingGrids = GridTopologyHelper.handleNodeRemoval(grid, pos);
        if (resultingGrids.isEmpty()) {
            unregisterGrid(gridId);
        } else if (resultingGrids.size() == 1) {
            markDirty();
        } else {
            unregisterGrid(gridId);
            for (ElectricalGrid g : resultingGrids) {
                registerGrid(g);
            }
        }
    }
}
