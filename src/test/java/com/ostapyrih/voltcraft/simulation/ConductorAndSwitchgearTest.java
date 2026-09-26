package com.ostapyrih.voltcraft.simulation;

import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalGrid;
import com.ostapyrih.voltcraft.simulation.grid.GridConductor;
import com.ostapyrih.voltcraft.simulation.grid.GridTopologyHelper;
import com.ostapyrih.voltcraft.simulation.solver.ThermalEquilibrium;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ConductorAndSwitchgearTest {

    @Test
    public void testAllConductorPhysicalSpecifications() {
        for (ConductorType type : ConductorType.values()) {
            assertNotNull(type.getId());
            assertTrue(type.getBaseResistance() >= 0.0, "Resistance must be non-negative: " + type.name());
            assertTrue(type.getHeatCapacity() > 0.0, "Heat capacity must be positive: " + type.name());
            assertTrue(type.getCoolingRate() > 0.0, "Cooling rate must be positive: " + type.name());
            assertTrue(type.getMeltingTemp() > 0.0, "Melting temp must be positive: " + type.name());
            assertTrue(type.getMaxAmpacity() > 0.0, "Max ampacity must be positive: " + type.name());

            ThermalEquilibrium.ThermalSpec spec = type.toThermalSpec();
            assertEquals(type.getBaseResistance(), spec.baseResistance(), 1e-9);
            assertEquals(type.getMeltingTemp(), spec.meltingTemp(), 1e-9);
        }

        // Specific physical constants validation from the wiki matrix
        assertTrue(ConductorType.BARE_COPPER.hasShockHazard());
        assertFalse(ConductorType.INSULATED_COPPER.hasShockHazard());
        assertTrue(ConductorType.INSULATED_COPPER.isInsulated());
        assertEquals(120.0, ConductorType.INSULATED_COPPER.getMaxInsulationTemp(), 1e-5);
        assertEquals(1085.0, ConductorType.BARE_COPPER.getMeltingTemp(), 1e-5);
        assertEquals(660.0, ConductorType.ALUMINUM_TRANSMISSION.getMeltingTemp(), 1e-5);
        assertEquals(1538.0, ConductorType.STEEL_FENCE.getMeltingTemp(), 1e-5);
        assertEquals(1400.0, ConductorType.NICHROME_HEATING.getMeltingTemp(), 1e-5);
        assertTrue(ConductorType.SUPERCONDUCTOR_CONDUIT.getBaseResistance() < 1e-5);
    }

    @Test
    public void testThermalEquilibriumUnderRatedAmpacity() {
        ConductorType type = ConductorType.INSULATED_COPPER;
        ThermalEquilibrium.ThermalSpec spec = type.toThermalSpec();

        double temp = 20.0;
        double current = 25.0; // below 32A max ampacity
        double ambient = 20.0;
        double dt = 0.05;

        for (int i = 0; i < 200; i++) {
            ThermalEquilibrium.StepResult step = ThermalEquilibrium.step(temp, current, ambient, dt, spec);
            temp = step.newTemperature();
            assertEquals(ThermalEquilibrium.ThermalStatus.SAFE, step.status());
        }

        assertTrue(temp < type.getMaxInsulationTemp(), "Conductor under rated ampacity must remain below insulation melting threshold");
    }

    @Test
    public void testThermalVaporizationUnderSevereOvercurrent() {
        ConductorType type = ConductorType.BARE_COPPER;
        ThermalEquilibrium.ThermalSpec spec = type.toThermalSpec();

        double temp = 20.0;
        double shortCircuitCurrent = 500.0; // Severe 500A short circuit
        double ambient = 20.0;
        double dt = 0.05;

        boolean melted = false;
        for (int i = 0; i < 100; i++) {
            ThermalEquilibrium.StepResult step = ThermalEquilibrium.step(temp, shortCircuitCurrent, ambient, dt, spec);
            temp = step.newTemperature();
            if (step.status() == ThermalEquilibrium.ThermalStatus.CONDUCTOR_MELTED) {
                melted = true;
                break;
            }
        }

        assertTrue(melted, "Conductor under 500A fault current must reach melting temperature");
        assertTrue(temp >= type.getMeltingTemp());
    }

    @Test
    public void testKnifeSwitchDisconnectTopologicalSplit() {
        // Build a grid of 3 nodes: A (0,0,0) - Switch (1,0,0) - B (2,0,0)
        BlockPos posA = new BlockPos(0, 64, 0);
        BlockPos posSwitch = new BlockPos(1, 64, 0);
        BlockPos posB = new BlockPos(2, 64, 0);

        ElectricalGrid grid = new ElectricalGrid();
        grid.addNode(posA, true); // Ground at A
        grid.addNode(posSwitch, false);
        grid.addNode(posB, false);

        GridConductor c1 = new GridConductor(posA, posSwitch, ConductorType.BARE_COPPER.toThermalSpec(), 32.0, false);
        GridConductor c2 = new GridConductor(posSwitch, posB, ConductorType.BARE_COPPER.toThermalSpec(), 32.0, false);
        grid.addConductor(c1);
        grid.addConductor(c2);

        assertEquals(3, grid.getNodePositions().size());
        assertEquals(2, grid.getConductors().size());

        // Opening the Knife Switch removes posSwitch from active topology
        List<ElectricalGrid> partitions = GridTopologyHelper.handleNodeRemoval(grid, posSwitch);

        // Grid must cleanly split into 2 disjoint networks: {posA} and {posB}
        assertEquals(2, partitions.size(), "Opening switch in series must split grid into 2 independent components");
        assertTrue(partitions.get(0).contains(posA) || partitions.get(1).contains(posA));
        assertTrue(partitions.get(0).contains(posB) || partitions.get(1).contains(posB));
        assertFalse(partitions.get(0).contains(posSwitch));
        assertFalse(partitions.get(1).contains(posSwitch));
    }

    @Test
    public void testSwitchClosingTopologicalMerge() {
        BlockPos posA = new BlockPos(0, 64, 0);
        BlockPos posB = new BlockPos(2, 64, 0);
        BlockPos posSwitch = new BlockPos(1, 64, 0);

        ElectricalGrid gridA = new ElectricalGrid();
        gridA.addNode(posA, false);

        ElectricalGrid gridB = new ElectricalGrid();
        gridB.addNode(posB, false);

        // When switch is closed, merge secondary into primary
        ElectricalGrid merged = GridTopologyHelper.mergeGrids(gridA, gridB);
        merged.addNode(posSwitch, false);
        merged.addConductor(new GridConductor(posA, posSwitch, ConductorType.HEAVY_COPPER.toThermalSpec(), 100.0, true));
        merged.addConductor(new GridConductor(posSwitch, posB, ConductorType.HEAVY_COPPER.toThermalSpec(), 100.0, true));

        assertEquals(3, merged.getNodePositions().size());
        assertEquals(2, merged.getConductors().size());
        assertTrue(merged.contains(posA));
        assertTrue(merged.contains(posSwitch));
        assertTrue(merged.contains(posB));
    }

    @Test
    public void testGridConductorThermalDestructionInGridTick() {
        BlockPos posA = new BlockPos(0, 64, 0);
        BlockPos posB = new BlockPos(1, 64, 0);

        ElectricalGrid grid = new ElectricalGrid();
        grid.addNode(posA, true);
        grid.addNode(posB, false);

        // Branch with low melting temperature
        ThermalEquilibrium.ThermalSpec delicateSpec = new ThermalEquilibrium.ThermalSpec(
            0.1, 0.004, 0.01, 0.001, 100.0, 150.0
        );
        GridConductor delicateConductor = new GridConductor(posA, posB, delicateSpec, 10.0, false);
        delicateConductor.setTemperature(160.0); // Already above melting temp

        grid.addConductor(delicateConductor);
        assertEquals(1, grid.getConductors().size());

        // Tick simulation (world is null in pure unit test)
        assertDoesNotThrow(() -> grid.tick(null));

        // Melted conductor must be purged from active conductors in grid
        assertEquals(0, grid.getConductors().size(), "Vaporized conductor must be removed from grid conductors");
    }

    @Test
    public void testConcurrentModificationResilienceUnderConductorMelting() {
        // Multi-conductor mesh where multiple conductors exceed melting temp simultaneously
        ElectricalGrid grid = new ElectricalGrid();
        ThermalEquilibrium.ThermalSpec fragileSpec = new ThermalEquilibrium.ThermalSpec(
            0.05, 0.004, 0.01, 0.001, 100.0, 120.0
        );

        for (int i = 0; i < 10; i++) {
            BlockPos pA = new BlockPos(i, 64, 0);
            BlockPos pB = new BlockPos(i + 1, 64, 0);
            grid.addNode(pA, i == 0);
            grid.addNode(pB, false);

            GridConductor c = new GridConductor(pA, pB, fragileSpec, 10.0, false);
            c.setTemperature(200.0); // All 10 conductors are in melted state!
            grid.addConductor(c);
        }

        assertEquals(10, grid.getConductors().size());

        // Tick must process all 10 melting events without ConcurrentModificationException
        assertDoesNotThrow(() -> grid.tick(null));

        assertEquals(0, grid.getConductors().size(), "All melted conductors must be purged safely");
    }
}
