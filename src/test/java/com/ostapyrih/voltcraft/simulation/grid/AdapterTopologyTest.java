package com.ostapyrih.voltcraft.simulation.grid;

import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.Conductor;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Island-topology adapter tests. Drives {@link GridManager} purely through its
 * world-free index methods with the real {@link BlockPos} class; no server,
 * no world, no ticks against live chunks.
 */
class AdapterTopologyTest {

    /** Stateless linear resistor, 2 terminals. */
    static final class ResistorElement implements ElectricalElement {
        private final double resistance;

        ResistorElement(double resistance) {
            this.resistance = resistance;
        }

        @Override
        public int terminalCount() {
            return 2;
        }

        @Override
        public int stateCount() {
            return 0;
        }

        @Override
        public void stamp(Complex[][] y, Complex[] in, int[] terminals, Complex[] v,
                          double[] state, double omega) {
            Stamps.admittance(y, terminals[0], terminals[1], new Complex(1.0 / resistance, 0.0));
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
        }
    }

    /** Test-only {@link KernelAttachedBlock} double. tickElectrical is a no-op. */
    static final class TestBlock implements KernelAttachedBlock {
        private final BlockPos pos;
        private final BlockPos[] terminals;
        private final ElectricalElement element;
        private final boolean active;
        private final boolean ac;
        private double[] state;

        TestBlock(BlockPos pos, ElectricalElement element, boolean active, boolean ac, BlockPos... terminals) {
            this.pos = pos.toImmutable();
            this.element = element;
            this.active = active;
            this.ac = ac;
            this.terminals = new BlockPos[terminals.length];
            for (int i = 0; i < terminals.length; i++) {
                this.terminals[i] = terminals[i].toImmutable();
            }
            this.state = new double[element.stateCount()];
        }

        @Override
        public ElectricalElement getElement() {
            return element;
        }

        @Override
        public BlockPos[] getTerminalPositions() {
            return terminals.clone();
        }

        @Override
        public double[] getStateArray() {
            return state.clone();
        }

        @Override
        public void setStateArray(double[] state) {
            this.state = state != null ? state.clone() : new double[element.stateCount()];
        }

        @Override
        public BlockPos getPos() {
            return pos;
        }

        @Override
        public void tickElectrical(ServerWorld world) {
            // No-op double: this suite covers topology only, no electrical ticking.
        }

        @Override
        public boolean isActiveSource() {
            return active;
        }

        @Override
        public boolean isACSource() {
            return ac;
        }
    }

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static void putChain(GridManager manager, int x0, int x1, ConductorType type) {
        for (int x = x0; x <= x1; x++) {
            manager.putCable(pos(x, 0, 0), type);
        }
    }

    private static IslandContext singleIsland(GridManager manager) {
        assertEquals(1, manager.getIslands().size(), "expected exactly one island");
        return manager.getIslands().get(0);
    }

    // ---- two separate networks (no shared terminals) -> two islands ----

    @Test
    void twoSeparateNetworksAreTwoIslands() {
        GridManager manager = new GridManager();
        putChain(manager, 0, 1, ConductorType.INSULATED_COPPER);
        putChain(manager, 100, 101, ConductorType.INSULATED_COPPER);
        manager.rebuildIslands();

        assertEquals(2, manager.getIslands().size());
        IslandContext first = manager.getIslandAt(pos(0, 0, 0));
        IslandContext second = manager.getIslandAt(pos(100, 0, 0));
        assertNotNull(first);
        assertNotNull(second);
        assertTrue(first != second);
        assertEquals(2, first.nodeCount());
        assertEquals(2, second.nodeCount());
        assertNull(manager.getIslandAt(pos(50, 0, 0)));
    }

    // ---- multi-terminal element bridges terminals into one island without cable ----

    @Test
    void multiTerminalElementBridgesTerminalsWithoutCable() {
        GridManager manager = new GridManager();
        // Terminals are two apart: no adjacency, no cable, no conductor between them.
        TestBlock block = new TestBlock(pos(50, 0, 0), new ResistorElement(10.0),
            false, false, pos(50, 0, 1), pos(50, 0, -1));
        manager.putAttachedBlock(block);
        manager.rebuildIslands();

        IslandContext island = singleIsland(manager);
        assertEquals(2, island.nodeCount());
        assertEquals(2, island.nodeIndex().size());
        assertTrue(island.nodeIndex().containsKey(pos(50, 0, 1)));
        assertTrue(island.nodeIndex().containsKey(pos(50, 0, -1)));
        // Block-entity position itself is NOT a node.
        assertFalse(island.nodeIndex().containsKey(pos(50, 0, 0)));
        assertEquals(0, island.conductors().size());
        assertEquals(1, island.elements().size());
        assertEquals(Map.of(pos(50, 0, 0), 0), island.elementIndex());
        int[] terminals = island.terminalIndices().get(0);
        assertEquals(2, terminals.length);
        assertTrue(terminals[0] != terminals[1]);
    }

    // ---- node indices for 5-cable chain + 2 terminals ----

    @Test
    void nodeIndicesForChainPlusTerminals() {
        GridManager manager = new GridManager();
        putChain(manager, 0, 4, ConductorType.INSULATED_COPPER);
        manager.putCable(pos(5, 0, 0), ConductorType.INSULATED_COPPER);
        manager.putCable(pos(2, 0, 1), ConductorType.INSULATED_COPPER);
        TestBlock block = new TestBlock(pos(2, 0, 5), new ResistorElement(10.0),
            false, false, pos(5, 0, 0), pos(2, 0, 1));
        manager.putAttachedBlock(block);
        manager.rebuildIslands();

        IslandContext island = singleIsland(manager);
        assertEquals(7, island.nodeCount());
        assertEquals(7, island.nodeIndex().size());

        Map<BlockPos, Integer> expected = new HashMap<>();
        expected.put(pos(0, 0, 0), 0);
        expected.put(pos(1, 0, 0), 1);
        expected.put(pos(2, 0, 0), 2);
        expected.put(pos(2, 0, 1), 3);
        expected.put(pos(3, 0, 0), 4);
        expected.put(pos(4, 0, 0), 5);
        expected.put(pos(5, 0, 0), 6);
        assertEquals(expected, island.nodeIndex());
        assertFalse(island.nodeIndex().containsKey(pos(2, 0, 5)));

        // Deterministic: identical mapping after a second rebuild.
        manager.markTopologyDirty();
        manager.rebuildIslands();
        assertEquals(expected, singleIsland(manager).nodeIndex());
    }

    // ---- conductor list for T-junction (count + resistances) ----

    @Test
    void tJunctionConductorList() {
        GridManager manager = new GridManager();
        ConductorType type = ConductorType.INSULATED_COPPER;
        manager.putCable(pos(0, 0, 0), type);
        manager.putCable(pos(1, 0, 0), type);
        manager.putCable(pos(2, 0, 0), type);
        manager.putCable(pos(1, 0, 1), type);
        manager.putCable(pos(1, 0, -1), type);
        TestBlock block = new TestBlock(pos(7, 0, 7), new ResistorElement(10.0),
            false, false, pos(1, 0, -1), pos(1, 0, -2));
        manager.putAttachedBlock(block);
        manager.rebuildIslands();

        IslandContext island = singleIsland(manager);
        assertEquals(6, island.nodeCount());
        // 4 cable-cable + 1 terminal-terminal; no diagonals.
        assertEquals(5, island.conductors().size());

        double cableR = type.getBaseResistance();
        int cableCount = 0;
        int terminalCount = 0;
        for (Conductor conductor : island.conductors()) {
            if (Math.abs(conductor.resistance() - cableR) < 1e-12) {
                cableCount++;
            } else if (Math.abs(conductor.resistance() - CableConductorAdapter.TERMINAL_LINK_R_OHM) < 1e-15) {
                terminalCount++;
            } else {
                throw new AssertionError("unexpected conductor resistance: " + conductor.resistance());
            }
        }
        assertEquals(4, cableCount);
        assertEquals(1, terminalCount);
    }

    // ---- deterministic element index survives shuffled discovery order ----

    @Test
    void deterministicElementIndexSurvivesShuffledDiscoveryOrder() {
        GridManager first = new GridManager();
        first.putCable(pos(0, 0, 0), ConductorType.INSULATED_COPPER);
        first.putCable(pos(1, 0, 0), ConductorType.INSULATED_COPPER);
        first.putCable(pos(0, 1, 0), ConductorType.INSULATED_COPPER);
        first.putCable(pos(1, 1, 0), ConductorType.INSULATED_COPPER);
        first.putAttachedBlock(new TestBlock(pos(0, 5, 0), new ResistorElement(10.0),
            false, false, pos(0, 1, 0), pos(0, 2, 0)));
        first.putAttachedBlock(new TestBlock(pos(9, 5, 9), new ResistorElement(10.0),
            false, false, pos(1, 1, 0), pos(1, 2, 0)));
        first.rebuildIslands();

        GridManager second = new GridManager();
        second.putAttachedBlock(new TestBlock(pos(9, 5, 9), new ResistorElement(10.0),
            false, false, pos(1, 1, 0), pos(1, 2, 0)));
        second.putCable(pos(1, 0, 0), ConductorType.INSULATED_COPPER);
        second.putCable(pos(0, 0, 0), ConductorType.INSULATED_COPPER);
        second.putCable(pos(1, 1, 0), ConductorType.INSULATED_COPPER);
        second.putCable(pos(0, 1, 0), ConductorType.INSULATED_COPPER);
        second.putAttachedBlock(new TestBlock(pos(0, 5, 0), new ResistorElement(10.0),
            false, false, pos(0, 1, 0), pos(0, 2, 0)));
        second.rebuildIslands();

        Map<BlockPos, Integer> expected = Map.of(pos(0, 5, 0), 0, pos(9, 5, 9), 1);
        assertEquals(1, first.getIslands().size());
        assertEquals(1, second.getIslands().size());
        assertEquals(expected, first.getIslands().get(0).elementIndex());
        assertEquals(expected, second.getIslands().get(0).elementIndex());
    }

    // ---- omega per island: DC island + AC island + inactive island ----

    @Test
    void omegaPerIslandIsDeterminedIndependently() {
        GridManager manager = new GridManager();
        manager.putCable(pos(0, 0, 0), ConductorType.INSULATED_COPPER);
        manager.putAttachedBlock(new TestBlock(pos(0, 9, 0), new ResistorElement(10.0),
            true, false, pos(0, 0, 0), pos(0, 0, 1)));
        manager.putCable(pos(200, 0, 0), ConductorType.INSULATED_COPPER);
        manager.putAttachedBlock(new TestBlock(pos(200, 9, 0), new ResistorElement(10.0),
            true, true, pos(200, 0, 0), pos(200, 0, 1)));
        manager.putCable(pos(400, 0, 0), ConductorType.INSULATED_COPPER);
        manager.putAttachedBlock(new TestBlock(pos(400, 9, 0), new ResistorElement(10.0),
            false, false, pos(400, 0, 0), pos(400, 0, 1)));
        manager.putCable(pos(600, 0, 0), ConductorType.INSULATED_COPPER);
        manager.rebuildIslands();

        assertEquals(4, manager.getIslands().size());
        assertEquals(0.0, manager.getIslandAt(pos(0, 0, 0)).omega());
        assertEquals(GridConstants.AC_OMEGA_RAD_PER_S, manager.getIslandAt(pos(200, 0, 0)).omega());
        assertEquals(0.0, manager.getIslandAt(pos(400, 0, 0)).omega());
        assertEquals(0.0, manager.getIslandAt(pos(600, 0, 0)).omega());
    }

    // ---- topology index updated on place/break events ----

    @Test
    void topologyIndexUpdatedOnPlaceAndBreak() {
        GridManager manager = new GridManager();
        assertTrue(manager.isTopologyDirty());

        manager.putCable(pos(0, 0, 0), ConductorType.INSULATED_COPPER);
        assertTrue(manager.isTopologyDirty());
        manager.rebuildIslands();
        assertFalse(manager.isTopologyDirty());
        assertEquals(1, singleIsland(manager).nodeCount());

        manager.putCable(pos(1, 0, 0), ConductorType.INSULATED_COPPER);
        assertTrue(manager.isTopologyDirty());
        manager.rebuildIslands();
        assertFalse(manager.isTopologyDirty());
        IslandContext grown = singleIsland(manager);
        assertEquals(2, grown.nodeCount());
        assertEquals(1, grown.conductors().size());

        manager.removeCable(pos(0, 0, 0));
        assertTrue(manager.isTopologyDirty());
        manager.rebuildIslands();
        assertFalse(manager.isTopologyDirty());
        IslandContext shrunk = singleIsland(manager);
        assertEquals(1, shrunk.nodeCount());
        assertEquals(0, shrunk.conductors().size());
        assertNotNull(manager.getIslandAt(pos(1, 0, 0)));
        assertNull(manager.getIslandAt(pos(0, 0, 0)));
    }

    // ---- non-empty topology, zero elements: tick completes finite and non-singular ----

    @Test
    void nonEmptyTopologyWithZeroElementsTicksCleanly() {
        GridManager manager = new GridManager();
        putChain(manager, 0, 4, ConductorType.INSULATED_COPPER);
        manager.rebuildIslands();

        IslandContext island = singleIsland(manager);
        assertEquals(5, island.nodeCount());
        assertEquals(4, island.conductors().size());
        assertEquals(0, island.elements().size());
        assertTrue(island.elementIndex().isEmpty());

        island.kernel().tick();
        ElectricalKernel.KernelSolveResult result = island.kernel().solve();
        assertFalse(result.singular());
        assertEquals(5, result.voltage().length);
        for (Complex v : result.voltage()) {
            assertTrue(v.isFinite(), "non-finite voltage: " + v);
        }

        // Full manager tick with a null world must also complete without throwing.
        manager.tick(null);
        assertEquals(1, manager.getIslands().size());
        assertEquals(5, manager.getIslands().get(0).nodeCount());
    }

    // ---- chunk unload removes nodes; island rebuilt from remaining ----

    @Test
    void chunkUnloadRemovesNodesAndRebuildsFromRemaining() {
        GridManager manager = new GridManager();
        manager.putCable(pos(0, 0, 0), ConductorType.INSULATED_COPPER);
        manager.putCable(pos(1, 0, 0), ConductorType.INSULATED_COPPER);
        manager.putCable(pos(32, 0, 0), ConductorType.INSULATED_COPPER);
        manager.putCable(pos(33, 0, 0), ConductorType.INSULATED_COPPER);
        manager.rebuildIslands();
        assertEquals(2, manager.getIslands().size());

        manager.onChunkUnload(2, 0);
        assertTrue(manager.isTopologyDirty());
        manager.rebuildIslands();

        assertEquals(1, manager.getIslands().size());
        IslandContext remaining = singleIsland(manager);
        assertEquals(2, remaining.nodeCount());
        assertTrue(remaining.nodeIndex().containsKey(pos(0, 0, 0)));
        assertTrue(remaining.nodeIndex().containsKey(pos(1, 0, 0)));
        assertNull(manager.getIslandAt(pos(32, 0, 0)));
        // The discovery index retains the unloaded cable for persistence; it just
        // does not participate until its chunk loads again.
        assertTrue(manager.getKnownCablePositions().contains(pos(32, 0, 0)));

        manager.onChunkLoad(2, 0);
        manager.rebuildIslands();
        assertEquals(2, manager.getIslands().size());
    }

    // ---- cable coincident with terminal is a single shared node ----

    @Test
    void cableCoincidentWithTerminalIsOneNode() {
        GridManager manager = new GridManager();
        manager.putCable(pos(3, 0, 3), ConductorType.INSULATED_COPPER);
        manager.putAttachedBlock(new TestBlock(pos(3, 4, 3), new ResistorElement(10.0),
            false, false, pos(3, 0, 3), pos(4, 0, 3)));
        manager.rebuildIslands();

        IslandContext island = singleIsland(manager);
        assertEquals(2, island.nodeCount());
        List<int[]> terminals = new ArrayList<>(island.terminalIndices());
        assertEquals(1, terminals.size());
        assertEquals(2, terminals.get(0).length);
    }

    @Test
    void airGapBetweenCableAndTerminalDoesNotFormConnection() {
        GridManager manager = new GridManager();
        // Machine at (0, 0, 0) with terminals at (0, 0, 1) and (0, 0, -1)
        BlockPos machinePos = pos(0, 0, 0);
        BlockPos termPos = pos(0, 0, 1);
        BlockPos termNeg = pos(0, 0, -1);
        manager.putAttachedBlock(new TestBlock(machinePos, new ResistorElement(10.0),
            false, false, termPos, termNeg));

        // Cables placed at (0, 0, 2) and (0, 0, 3) - leaving (0, 0, 1) as empty air!
        manager.putCable(pos(0, 0, 2), ConductorType.INSULATED_COPPER);
        manager.putCable(pos(0, 0, 3), ConductorType.INSULATED_COPPER);
        manager.rebuildIslands();

        // The cables at (0, 0, 2) and (0, 0, 3) form an island of 2 nodes.
        // It must NOT include the machine terminal at (0, 0, 1) through air!
        IslandContext cableIsland = manager.getIslandAt(pos(0, 0, 2));
        assertNotNull(cableIsland);
        assertEquals(2, cableIsland.nodeCount());
        assertFalse(cableIsland.nodeIndex().containsKey(termPos));
        assertEquals(0, cableIsland.elements().size());

        // Now place the missing cable at (0, 0, 1) (the terminal pos)
        manager.putCable(pos(0, 0, 1), ConductorType.INSULATED_COPPER);
        manager.rebuildIslands();

        // Now the cable island includes (0, 0, 1) and connects to the machine element!
        cableIsland = manager.getIslandAt(pos(0, 0, 1));
        assertNotNull(cableIsland);
        assertTrue(cableIsland.nodeIndex().containsKey(termPos));
        assertEquals(1, cableIsland.elements().size());
    }
}
