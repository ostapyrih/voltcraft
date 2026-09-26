package com.ostapyrih.voltcraft.simulation.grid;

import com.ostapyrih.voltcraft.api.energy.IElectricConsumer;
import com.ostapyrih.voltcraft.api.energy.IElectricSource;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Graph traversal and topological management algorithms for electrical grids.
 * Provides Breadth-First Search (BFS) component discovery, grid merging, and partition splitting.
 */
public class GridTopologyHelper {

    /**
     * Splits an existing grid into 1 or more disjoint grids after a node (e.g. wire block) is destroyed.
     *
     * @param originalGrid The grid that lost a node
     * @param removedPos The position that was removed
     * @return List of resulting independent grids (at least 1 if remaining nodes exist, or empty if empty)
     */
    public static List<ElectricalGrid> handleNodeRemoval(ElectricalGrid originalGrid, BlockPos removedPos) {
        originalGrid.removeNode(removedPos);

        Set<BlockPos> remainingNodes = new HashSet<>(originalGrid.getNodePositions());
        if (remainingNodes.isEmpty()) {
            return Collections.emptyList();
        }

        // Snapshot existing conductors BEFORE any partition modifications
        List<GridConductor> remainingConductors = new ArrayList<>(originalGrid.getConductors());

        // Build adjacency map of remaining nodes from remaining conductors
        Map<BlockPos, Set<BlockPos>> adj = new HashMap<>();
        for (BlockPos p : remainingNodes) {
            adj.put(p, new HashSet<>());
        }

        for (GridConductor c : remainingConductors) {
            if (remainingNodes.contains(c.getStartPos()) && remainingNodes.contains(c.getEndPos())) {
                adj.get(c.getStartPos()).add(c.getEndPos());
                adj.get(c.getEndPos()).add(c.getStartPos());
            }
        }

        // Discover connected components using BFS
        List<Set<BlockPos>> components = new ArrayList<>();
        Set<BlockPos> unvisited = new HashSet<>(remainingNodes);

        while (!unvisited.isEmpty()) {
            BlockPos start = unvisited.iterator().next();
            Set<BlockPos> component = new HashSet<>();
            Queue<BlockPos> queue = new ArrayDeque<>();

            queue.add(start);
            unvisited.remove(start);

            while (!queue.isEmpty()) {
                BlockPos curr = queue.poll();
                component.add(curr);

                for (BlockPos neighbor : adj.getOrDefault(curr, Collections.emptySet())) {
                    if (unvisited.remove(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
            components.add(component);
        }

        // If only 1 component remains, no topological split occurred
        if (components.size() <= 1) {
            return List.of(originalGrid);
        }

        // Multiple disjoint partitions created: build new ElectricalGrids
        Map<BlockPos, List<IElectricSource>> allSources = new HashMap<>();
        for (Map.Entry<BlockPos, List<IElectricSource>> entry : originalGrid.getSources().entrySet()) {
            allSources.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        Map<BlockPos, List<IElectricConsumer>> allConsumers = new HashMap<>();
        for (Map.Entry<BlockPos, List<IElectricConsumer>> entry : originalGrid.getConsumers().entrySet()) {
            allConsumers.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        List<ElectricalGrid> result = new ArrayList<>();

        for (int i = 0; i < components.size(); i++) {
            Set<BlockPos> compNodes = components.get(i);
            ElectricalGrid newGrid;
            if (i == 0) {
                // Reuse original grid for first component
                newGrid = originalGrid;
                // Remove nodes not in compNodes
                Set<BlockPos> toRemove = new HashSet<>(originalGrid.getNodePositions());
                toRemove.removeAll(compNodes);
                for (BlockPos p : toRemove) {
                    newGrid.removeNode(p);
                }
            } else {
                newGrid = new ElectricalGrid();
                for (BlockPos p : compNodes) {
                    newGrid.addNode(p, false);
                }
                for (GridConductor c : remainingConductors) {
                    if (compNodes.contains(c.getStartPos()) && compNodes.contains(c.getEndPos())) {
                        newGrid.addConductor(c);
                    }
                }
                // Migrate active sources and consumers to the new partition grid
                for (BlockPos p : compNodes) {
                    List<IElectricSource> srcs = allSources.get(p);
                    if (srcs != null) {
                        for (IElectricSource src : srcs) {
                            newGrid.registerSource(p, src);
                        }
                    }
                    List<IElectricConsumer> cons = allConsumers.get(p);
                    if (cons != null) {
                        for (IElectricConsumer c : cons) {
                            newGrid.registerConsumer(p, c);
                        }
                    }
                }
            }
            result.add(newGrid);
        }

        return result;
    }

    /**
     * Merges secondaryGrid into primaryGrid when a bridging conductor is placed between them.
     */
    public static ElectricalGrid mergeGrids(ElectricalGrid primaryGrid, ElectricalGrid secondaryGrid) {
        if (primaryGrid == secondaryGrid) {
            return primaryGrid;
        }

        for (BlockPos pos : secondaryGrid.getNodePositions()) {
            primaryGrid.addNode(pos, false);
        }

        for (GridConductor c : secondaryGrid.getConductors()) {
            primaryGrid.addConductor(c);
        }

        // Migrate all sources and consumers from secondaryGrid into primaryGrid
        for (Map.Entry<BlockPos, List<IElectricSource>> entry : secondaryGrid.getSources().entrySet()) {
            for (IElectricSource src : entry.getValue()) {
                primaryGrid.registerSource(entry.getKey(), src);
            }
        }
        for (Map.Entry<BlockPos, List<IElectricConsumer>> entry : secondaryGrid.getConsumers().entrySet()) {
            for (IElectricConsumer cons : entry.getValue()) {
                primaryGrid.registerConsumer(entry.getKey(), cons);
            }
        }

        return primaryGrid;
    }
}
