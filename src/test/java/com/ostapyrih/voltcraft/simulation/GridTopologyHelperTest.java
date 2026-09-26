package com.ostapyrih.voltcraft.simulation;

import com.ostapyrih.voltcraft.simulation.grid.ElectricalGrid;
import com.ostapyrih.voltcraft.simulation.grid.GridConductor;
import com.ostapyrih.voltcraft.simulation.grid.GridTopologyHelper;
import com.ostapyrih.voltcraft.simulation.solver.ThermalEquilibrium;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class GridTopologyHelperTest {

    @Test
    public void testGraphSplitOnBridgeRemoval() {
        // Linear chain of 3 nodes: PosA <-> PosB <-> PosC
        // When PosB is destroyed, it should partition into two independent grids: {PosA} and {PosC}
        BlockPos posA = new BlockPos(0, 64, 0);
        BlockPos posB = new BlockPos(1, 64, 0);
        BlockPos posC = new BlockPos(2, 64, 0);

        ElectricalGrid grid = new ElectricalGrid();
        ThermalEquilibrium.ThermalSpec spec = ThermalEquilibrium.ThermalSpec.COPPER_2_5_MM2;

        grid.addConductor(new GridConductor(posA, posB, spec, 32.0, true));
        grid.addConductor(new GridConductor(posB, posC, spec, 32.0, true));

        assertEquals(3, grid.getNodePositions().size());

        // Remove middle node (PosB)
        List<ElectricalGrid> resultingGrids = GridTopologyHelper.handleNodeRemoval(grid, posB);

        assertEquals(2, resultingGrids.size(), "Destroying bridge node must split graph into 2 partitions");

        boolean foundA = false;
        boolean foundC = false;
        for (ElectricalGrid g : resultingGrids) {
            if (g.contains(posA)) {
                foundA = true;
                assertFalse(g.contains(posC), "Partition containing PosA must not contain PosC");
            }
            if (g.contains(posC)) {
                foundC = true;
                assertFalse(g.contains(posA), "Partition containing PosC must not contain PosA");
            }
        }
        assertTrue(foundA && foundC, "Both isolated partitions must be preserved");
    }

    @Test
    public void testGridMerge() {
        BlockPos posA = new BlockPos(0, 64, 0);
        BlockPos posB = new BlockPos(1, 64, 0);
        BlockPos posC = new BlockPos(2, 64, 0);

        ElectricalGrid grid1 = new ElectricalGrid();
        grid1.addNode(posA, false);

        ElectricalGrid grid2 = new ElectricalGrid();
        grid2.addNode(posB, false);
        grid2.addNode(posC, false);

        ElectricalGrid merged = GridTopologyHelper.mergeGrids(grid1, grid2);

        assertEquals(3, merged.getNodePositions().size());
        assertTrue(merged.contains(posA));
        assertTrue(merged.contains(posB));
        assertTrue(merged.contains(posC));
    }

    @Test
    public void testGraphSplitPreservesConductorsInAllPartitions() {
        // Chain of 5 nodes: PosA <-> PosB <-> PosC <-> PosD <-> PosE
        // When PosC is destroyed, Partition 1 ({PosA, PosB}) must retain conductor (PosA, PosB)
        // and Partition 2 ({PosD, PosE}) must retain conductor (PosD, PosE) without losing conductors!
        BlockPos posA = new BlockPos(0, 64, 0);
        BlockPos posB = new BlockPos(1, 64, 0);
        BlockPos posC = new BlockPos(2, 64, 0);
        BlockPos posD = new BlockPos(3, 64, 0);
        BlockPos posE = new BlockPos(4, 64, 0);

        ElectricalGrid grid = new ElectricalGrid();
        ThermalEquilibrium.ThermalSpec spec = ThermalEquilibrium.ThermalSpec.COPPER_2_5_MM2;

        grid.addConductor(new GridConductor(posA, posB, spec, 32.0, true));
        grid.addConductor(new GridConductor(posB, posC, spec, 32.0, true));
        grid.addConductor(new GridConductor(posC, posD, spec, 32.0, true));
        grid.addConductor(new GridConductor(posD, posE, spec, 32.0, true));

        assertEquals(5, grid.getNodePositions().size());
        assertEquals(4, grid.getConductors().size());

        List<ElectricalGrid> resultingGrids = GridTopologyHelper.handleNodeRemoval(grid, posC);
        assertEquals(2, resultingGrids.size());

        for (ElectricalGrid g : resultingGrids) {
            assertEquals(2, g.getNodePositions().size(), "Each partition must contain exactly 2 nodes");
            assertEquals(1, g.getConductors().size(), "Each partition MUST preserve its internal conductor, not losing signal!");
        }
    }
}