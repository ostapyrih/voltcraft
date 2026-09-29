package com.ostapyrih.voltcraft.simulation.grid;

import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase E conditional-approval regression (items 1–3 only).
 *
 * <p>World-free: real {@link BlockPos} plus a local {@link KernelAttachedBlock}
 * double; no server, no registries, no block-entity classes.
 *
 * <p>Item 3 answer: (a) reroute through the remaining topology after a melt
 * break is CORRECT; (b) a melt break that fails to remove the path is a BUG.
 * Evidence: {@link #meltReroutesThroughRemainingPath} (parallel lane A removed,
 * load still fed via lane B) versus {@link #meltOfSoleFeedOpensCircuit}
 * (only feed removed, load current ~0 — a lingering conductor would keep
 * feeding the load and fail that assert). Direct {@code removeCable} +
 * rebuild exercises the same rebuild boundary the queued-break path
 * ({@code applyPendingBreaks} into {@code knownCables}) uses; the re-conduct
 * question is purely about post-rebuild topology either way.
 */
class PhaseEConditionTest {

    /** Stateless linear Thevenin source, positive at {@code terminals[0]}. */
    static final class TestThevenin implements ElectricalElement {
        private final Complex g;
        private final Complex emf;

        TestThevenin(double voltage, double seriesResistance) {
            this.g = new Complex(1.0 / seriesResistance, 0.0);
            this.emf = new Complex(voltage, 0.0);
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
            Stamps.thevenin(y, in, terminals[0], terminals[1], g, emf);
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
        }
    }

    /** Stateless linear resistor, 2 terminals. */
    static final class TestResistor implements ElectricalElement {
        private final double resistance;

        TestResistor(double resistance) {
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

    /** Single-terminal ground shunt (mirrors the production earth stamp, 1000 S). */
    static final class TestEarth implements ElectricalElement {
        @Override
        public int terminalCount() {
            return 1;
        }

        @Override
        public int stateCount() {
            return 0;
        }

        @Override
        public void stamp(Complex[][] y, Complex[] in, int[] terminals, Complex[] v,
                          double[] state, double omega) {
            int t = terminals[0];
            y[t][t] = y[t][t].add(new Complex(1000.0, 0.0));
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
        }
    }

    /** Test-only {@link KernelAttachedBlock} double. tickElectrical is a no-op. */
    static final class AdapterBlock implements KernelAttachedBlock {
        private final BlockPos pos;
        private final BlockPos[] terminals;
        private final ElectricalElement element;
        private final boolean active;
        private final boolean ac;
        private double[] state;

        AdapterBlock(BlockPos pos, ElectricalElement element, boolean active, boolean ac,
                     BlockPos... terminals) {
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
            // No-op double: null-safe by construction.
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

    /** Current entering terminal 0 of a block after a fresh observation solve. */
    private static double terminalZeroCurrent(IslandContext island, BlockPos bePos) {
        Integer idx = island.elementIndex().get(bePos.toImmutable());
        assertNotNull(idx, "block must be indexed in its island: " + bePos);
        assertTrue(island.kernel().solve().converged(), "island must solve");
        return island.kernel().terminalCurrents(idx)[0].re;
    }

    // ---- item 1: putAttachedBlock seeds an island with no cables at all ----

    @Test
    void attachedBlockSeedsIslandWithoutCables() {
        GridManager manager = new GridManager();
        manager.clearTopology();

        BlockPos bePos = pos(5, 0, 5);
        BlockPos t0 = pos(0, 0, 0);
        BlockPos t1 = pos(2, 0, 0);
        manager.putAttachedBlock(new AdapterBlock(bePos, new TestResistor(10.0), false, false, t0, t1));
        manager.rebuildIslands();

        assertEquals(1, manager.getIslands().size(), "BE alone must form one island");
        IslandContext island = manager.getIslands().get(0);
        assertEquals(2, island.nodeCount());
        assertTrue(island.nodeIndex().containsKey(t0));
        assertTrue(island.nodeIndex().containsKey(t1));
        assertTrue(island.elementIndex().containsKey(bePos.toImmutable()));
        assertTrue(island.kernel().solve().converged());

        manager.removeAttachedBlock(bePos);
        manager.rebuildIslands();
        assertTrue(manager.getIslands().isEmpty(), "removal must drop the island");
        assertNull(manager.getIslandAt(t0));
    }

    // ---- item 2a: partial-terminal block is deferred, cable net survives ----

    @Test
    void partialTerminalBlockDeferredWhileChunkUnloaded() {
        GridManager manager = new GridManager();
        manager.clearTopology();

        // BE in chunk (0,0) with T0 on the chunk-(0,0) cable net and T1 in chunk (1,0).
        BlockPos bePos = pos(15, 64, 0);
        BlockPos t0 = pos(14, 64, 0);
        BlockPos t1 = pos(16, 64, 0);
        manager.putCable(pos(13, 64, 0), ConductorType.INSULATED_COPPER);
        manager.putCable(pos(14, 64, 0), ConductorType.INSULATED_COPPER);
        manager.putAttachedBlock(
            new AdapterBlock(bePos, new TestResistor(10.0), false, false, t0, t1));
        manager.onChunkUnload(1, 0);
        // Must not throw (used to NPE auto-unboxing the unloaded terminal index).
        manager.rebuildIslands();

        IslandContext island = manager.getIslandAt(pos(13, 64, 0));
        assertNotNull(island, "loaded cable net must survive without the BE");
        assertEquals(2, island.nodeCount());
        assertFalse(island.elementIndex().containsKey(bePos.toImmutable()),
            "partial-terminal BE must be excluded from every island");
        assertNull(manager.getIslandAt(t1), "unloaded terminal belongs to no island");
    }

    // ---- item 2b: deferred block joins fully mapped once its chunk loads ----

    @Test
    void partialTerminalBlockJoinsAfterChunkReload() {
        GridManager manager = new GridManager();
        manager.clearTopology();

        BlockPos bePos = pos(15, 64, 0);
        BlockPos t0 = pos(14, 64, 0);
        BlockPos t1 = pos(16, 64, 0);
        manager.putCable(pos(13, 64, 0), ConductorType.INSULATED_COPPER);
        manager.putCable(pos(14, 64, 0), ConductorType.INSULATED_COPPER);
        manager.putAttachedBlock(
            new AdapterBlock(bePos, new TestResistor(10.0), false, false, t0, t1));
        manager.onChunkUnload(1, 0);
        manager.rebuildIslands();
        assertFalse(manager.getIslandAt(pos(13, 64, 0)).elementIndex()
            .containsKey(bePos.toImmutable()));

        manager.onChunkLoad(1, 0);
        manager.rebuildIslands();

        assertEquals(1, manager.getIslands().size(), "ownership unions both terminals");
        IslandContext island = manager.getIslands().get(0);
        assertEquals(3, island.nodeCount());
        Integer idx = island.elementIndex().get(bePos.toImmutable());
        assertNotNull(idx, "BE must be indexed after reload");
        // Sorted node order is 13 < 14 < 16, so T0 -> 1 and T1 -> 2.
        int[] mapped = island.terminalIndices().get(idx);
        assertEquals(1, mapped[0]);
        assertEquals(2, mapped[1]);
        assertTrue(island.kernel().solve().converged());
    }

    // ---- item 3a: melt of one parallel lane reroutes (correct re-conduct) ----

    @Test
    void meltReroutesThroughRemainingPath() {
        // 12 V source west, 10 ohm load east, earth return both ends, two parallel
        // nichrome lanes (A: z=0 via x=1..3; B: z=1 with end links). Nichrome
        // (0.7333 ohm/branch) magnifies the redistribution beyond solver noise.
        GridManager manager = new GridManager();
        manager.clearTopology();
        BlockPos loadBe = pos(6, 0, 2);
        manager.putAttachedBlock(new AdapterBlock(pos(2, 0, 5),
            new TestThevenin(12.0, 0.05), true, false, pos(0, 0, 0), pos(0, 0, 4)));
        manager.putAttachedBlock(new AdapterBlock(pos(0, 0, 6), new TestEarth(), false, false, pos(0, 0, 4)));
        manager.putAttachedBlock(new AdapterBlock(loadBe,
            new TestResistor(10.0), false, false, pos(4, 0, 0), pos(4, 0, 4)));
        manager.putAttachedBlock(new AdapterBlock(pos(4, 0, 6), new TestEarth(), false, false, pos(4, 0, 4)));
        BlockPos[] laneA = {pos(1, 0, 0), pos(2, 0, 0), pos(3, 0, 0)};
        BlockPos[] laneB = {pos(0, 0, 1), pos(1, 0, 1), pos(2, 0, 1), pos(3, 0, 1), pos(4, 0, 1)};
        for (BlockPos cable : laneA) {
            manager.putCable(cable, ConductorType.NICHROME_HEATING);
        }
        for (BlockPos cable : laneB) {
            manager.putCable(cable, ConductorType.NICHROME_HEATING);
        }
        manager.rebuildIslands();

        assertEquals(1, manager.getIslands().size());
        assertEquals(12, manager.getIslands().get(0).nodeCount());
        double preBreak = terminalZeroCurrent(manager.getIslandAt(pos(0, 0, 0)), loadBe);
        assertTrue(preBreak > 0.5 && preBreak < 2.0, "sane fed load current, got " + preBreak);

        // Melt lane A: queue the break exactly as the thermal path does (remove from
        // the cable index, rebuild at the boundary).
        for (BlockPos cable : laneA) {
            manager.removeCable(cable);
        }
        manager.rebuildIslands();

        assertEquals(1, manager.getIslands().size(), "lane B keeps one island");
        double postBreak = terminalZeroCurrent(manager.getIslandAt(pos(0, 0, 0)), loadBe);
        assertTrue(postBreak > 0.01, "load still fed via lane B, got " + postBreak);
        assertTrue(postBreak < preBreak,
            "higher feed resistance must reduce load current: pre=" + preBreak + " post=" + postBreak);
    }

    // ---- item 3b: melt of the ONLY feed opens the circuit (bug contrast) ----

    @Test
    void meltOfSoleFeedOpensCircuit() {
        // Single nichrome link (1,0,0) is the only S+ -> L+ path. After its melt
        // break the load island must go dark: a lingering melted conductor would
        // keep feeding the load and fail the ~0 assert.
        GridManager manager = new GridManager();
        manager.clearTopology();
        BlockPos loadBe = pos(6, 0, 2);
        manager.putAttachedBlock(new AdapterBlock(pos(2, 0, 5),
            new TestThevenin(12.0, 0.05), true, false, pos(0, 0, 0), pos(0, 0, 2)));
        manager.putAttachedBlock(new AdapterBlock(pos(0, 0, 6), new TestEarth(), false, false, pos(0, 0, 2)));
        manager.putAttachedBlock(new AdapterBlock(loadBe,
            new TestResistor(10.0), false, false, pos(2, 0, 0), pos(2, 0, 2)));
        manager.putAttachedBlock(new AdapterBlock(pos(2, 0, 6), new TestEarth(), false, false, pos(2, 0, 2)));
        manager.putCable(pos(1, 0, 0), ConductorType.NICHROME_HEATING);
        manager.rebuildIslands();

        assertEquals(1, manager.getIslands().size());
        double preBreak = terminalZeroCurrent(manager.getIslandAt(pos(0, 0, 0)), loadBe);
        assertTrue(preBreak > 0.5, "single feed must power the load, got " + preBreak);

        manager.removeCable(pos(1, 0, 0));
        manager.rebuildIslands();

        assertTrue(manager.getKnownCablePositions().isEmpty(), "break must clear the cable");
        assertEquals(2, manager.getIslands().size(), "source and load islands split");
        IslandContext loadIsland = manager.getIslandAt(pos(2, 0, 0));
        assertNotNull(loadIsland);
        assertTrue(loadIsland.conductors().isEmpty(), "no cable branch may survive the break");
        double postBreak = terminalZeroCurrent(loadIsland, loadBe);
        assertTrue(Math.abs(postBreak) < 1e-6,
            "open load current must vanish (lingering conductor = bug), got " + postBreak);
    }
}
