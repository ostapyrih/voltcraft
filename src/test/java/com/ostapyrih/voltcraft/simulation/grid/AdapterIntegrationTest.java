package com.ostapyrih.voltcraft.simulation.grid;

import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.block.entity.conversion.AbstractPowerConverterBlockEntity.ConverterElement;
import com.ostapyrih.voltcraft.block.entity.creative.CreativeGeneratorBlockEntity.CreativeGeneratorElement;
import com.ostapyrih.voltcraft.block.entity.creative.CreativeLoadBlockEntity.CreativeLoadElement;
import com.ostapyrih.voltcraft.block.entity.generation.SolarPanelBlockEntity.SolarElement;
import com.ostapyrih.voltcraft.block.entity.storage.BatteryBlockEntity.BatteryElement;
import com.ostapyrih.voltcraft.block.entity.switchgear.EarthBlockEntity.EarthElement;
import com.ostapyrih.voltcraft.simulation.chemistry.BatteryChemistry;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalKernel.KernelSolveResult;
import com.ostapyrih.voltcraft.simulation.solver.ComplexNodalSolver;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adapter integration tests: the kernel island subsystem end to end, after deletion of the
 * legacy dual-grid stack ({@code ElectricalGrid}, MNA/AC solvers, {@code api/energy}).
 *
 * <p>Pure Java + kernel + production adapter elements, no server, no registries. The same
 * hard environment constraint as in the switchgear/source/converter suites applies:
 * {@code BlockEntity.&lt;clinit&gt;}
 * touches {@code Registries}, so no test loads an outer {@code BlockEntity} subclass.
 * Production static nested elements ({@code BatteryElement}, {@code SolarElement},
 * {@code ConverterElement}, {@code CreativeGeneratorElement},
 * {@code CreativeLoadElement}, {@code EarthElement}) initialize independently of their
 * enclosing BE classes and are driven directly; island mechanics go through a local
 * {@link KernelAttachedBlock} double ({@link AdapterBlock}) backed by real elements.</p>
 *
 * <p>The solar → charge-controller → battery → inverter → AC-load chain reuses the
 * converter 4-terminal pattern for both bridges (charge controller on the DC island,
 * inverter on the AC island): input pair draws staged demand, output pair stamps staged
 * EMF, coupled across islands only through the discrete
 * {@code stageDemandWatts}/{@code stageEmf} helpers — never a shared solve.</p>
 */
class AdapterIntegrationTest {

    // ---- local fixtures ----

    /** Stateless linear Thevenin source, 2 terminals (positive at {@code a}). */
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

    /** Stateless constant-power sink with configurable knee (fallback probe), 2 terminals. */
    static final class TestConstantPower implements ElectricalElement {
        private final double powerWatts;
        private final double vMin;

        TestConstantPower(double powerWatts, double vMin) {
            this.powerWatts = powerWatts;
            this.vMin = vMin;
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
            Stamps.constantPower(y, in, terminals[0], terminals[1], v, powerWatts, vMin, omega);
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
        }
    }

    /** Test-only {@link KernelAttachedBlock} double with BE-owned state (commit/rollback target). */
    static final class AdapterBlock implements KernelAttachedBlock {
        private final BlockPos pos;
        private final BlockPos[] terminals;
        private final ElectricalElement element;
        private final boolean active;
        private final boolean ac;
        private double[] beState;

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
            this.beState = new double[element.stateCount()];
        }

        void seedState(double... values) {
            if (values == null || values.length != element.stateCount()) {
                throw new IllegalArgumentException(
                    "seed length " + (values == null ? "null" : values.length)
                        + " != stateCount " + element.stateCount());
            }
            this.beState = values.clone();
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
            return beState.clone();
        }

        @Override
        public void setStateArray(double[] state) {
            if (state == null || state.length != element.stateCount()) {
                throw new IllegalArgumentException(
                    "state length " + (state == null ? "null" : state.length)
                        + " != stateCount " + element.stateCount());
            }
            this.beState = state.clone();
        }

        @Override
        public BlockPos getPos() {
            return pos;
        }

        @Override
        public void tickElectrical(ServerWorld world) {
            // No-op double: null-safe by construction (never dereferences the world).
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

    private static double[] stampConductance(ElectricalElement el, int[] terminals, int n) {
        Complex[][] y = ComplexNodalSolver.zeroMatrix(n);
        Complex[] inj = ComplexNodalSolver.zeroVector(n);
        el.stamp(y, inj, terminals, ComplexNodalSolver.zeroVector(n), new double[el.stateCount()], 0.0);
        return new double[]{y[0][0].re, inj[0].re, inj[1].re};
    }

    // ---- 1. solar + charge-controller + battery + DC load (DC island) ----

    @Test
    void solarChargeControllerBatteryChainSolves() {
        // DC island, nodes {0,1} input rail / {2,3} output rail.
        // Solar 40 V (0.1 ohm array) feeds the charge-controller input (staged 100 W
        // demand); the controller output (staged 28.4 V absorption EMF for an 8s LiFePO4
        // bank) ties to the battery positive rail with a 10 ohm DC load in parallel.
        // Staging note: the demand keeps the zero-start Newton clear of the CP fallback
        // attractor (fallback point Vs*Rf/(Rint+Rf) ~= 3.6 V exceeds the 1 V knee).
        double[] solarTele = new double[2];
        SolarElement solar = new SolarElement(() -> 40.0, () -> 0.1, () -> 1000.0, solarTele);

        boolean[] ccTripped = {false};
        double[] ccDemand = {100.0};
        double[] ccEmf = {28.4};
        double[] ccVnom = {40.0};
        double[] ccTele = new double[ConverterElement.TELE_LEN];
        ConverterElement chargeController = new ConverterElement(
            () -> ccTripped[0], () -> ccDemand[0], () -> ccEmf[0], () -> ccVnom[0], ccTele);

        boolean[] bms = {false};
        double[] battTele = new double[2];
        BatteryElement battery = new BatteryElement(BatteryChemistry.LIFEPO4, 8, 1,
            () -> bms[0], battTele);

        ElectricalKernel dc = new ElectricalKernel();
        dc.setNodeCount(4);
        dc.setOmega(0.0);
        // Polarity: solar + sits at node 1 (west-positive source convention), so the controller input pair is
        // wired (1,0) with in+ on the high side; output +/battery + share node 2.
        dc.setElements(
            List.of(solar, chargeController, battery, new TestResistor(10.0),
                new EarthElement(), new EarthElement()),
            List.of(new int[]{0, 1}, new int[]{1, 0, 2, 3}, new int[]{3, 2},
                new int[]{2, 3}, new int[]{1}, new int[]{3}));
        dc.setConductors(List.of());
        dc.setElementState(2, new double[]{0.5, 25.0, 1.0});

        KernelSolveResult solved = dc.solve();
        assertTrue(solved.converged(), "DC chain must converge, residual=" + solved.residual());
        assertFalse(solved.singular());
        assertFalse(solved.fallbackActive());

        // Output rail sits between the bank EMF (~25.6 V at soc 0.5) and absorption (28.4 V).
        double vRail = solved.voltage()[2].re - solved.voltage()[3].re;
        assertTrue(vRail > 20.0 && vRail < 30.0, "output rail=" + vRail);
        // Battery charges: current leaves the negative terminal into the rail load.
        Complex[] battIt = dc.terminalCurrents(2);
        assertTrue(battIt[0].re < 0.0, "battery must charge, It[0]=" + battIt[0]);

        dc.tick();
        // Controller delivers into the rail; solar delivers into the input pair.
        assertTrue(ccTele[ConverterElement.TELE_P_OUT] > 0.0,
            "controller output power=" + ccTele[ConverterElement.TELE_P_OUT]);
        assertTrue(solarTele[SolarElement.TELE_I] > 0.0,
            "solar delivered current=" + solarTele[SolarElement.TELE_I]);

        // Converter-bridge staging link (the only cross-island coupling): next demand
        // follows delivered/eta + idle, next EMF passes the discrete gate.
        double teleVIn = ccTele[ConverterElement.TELE_V_IN];
        assertEquals(ccTele[ConverterElement.TELE_P_OUT] / 0.98 + 2.0,
            ConverterElement.stageDemandWatts(ccTele[ConverterElement.TELE_P_OUT],
                0.98, false, teleVIn), 1e-9);
        assertEquals(28.4, ConverterElement.stageEmf(28.4, false, teleVIn, 150.0), 0.0);
    }

    // ---- 1b. inverter + AC load (AC island, converter bridge) ----

    @Test
    void inverterAcIslandSolves() {
        // AC island, nodes {0,1} DC bus / {2,3} AC output. A stiff 48 V feed stands in
        // for the DC bus; the inverter input draws staged demand (AC resistive fallback),
        // the output stamps staged 230 V into a 2 kW (26.45 ohm) AC load.
        boolean[] tripped = {false};
        double[] demand = {1200.0};
        double[] emf = {230.0};
        double[] vnom = {48.0};
        double[] tele = new double[ConverterElement.TELE_LEN];
        ConverterElement inverter = new ConverterElement(
            () -> tripped[0], () -> demand[0], () -> emf[0], () -> vnom[0], tele);
        assertTrue(ConverterElement.isACOutput(ConverterElement.TYPE_KIND_INVERTER));

        ElectricalKernel ac = new ElectricalKernel();
        ac.setNodeCount(4);
        ac.setOmega(GridConstants.AC_OMEGA_RAD_PER_S);
        ac.setElements(
            List.of(new TestThevenin(48.0, 0.05), inverter, new TestResistor(26.45),
                new EarthElement(), new EarthElement()),
            List.of(new int[]{0, 1}, new int[]{0, 1, 2, 3}, new int[]{2, 3},
                new int[]{1}, new int[]{3}));
        ac.setConductors(List.of());

        KernelSolveResult solved = ac.solve();
        assertTrue(solved.converged(), "AC island must converge, residual=" + solved.residual());
        assertFalse(solved.singular());
        assertFalse(solved.fallbackActive());

        // 230 V / 0.05 ohm into 26.45 ohm: 230*26.45/26.5 == 229.57 V, no imaginary content.
        double vLoad = solved.voltage()[2].re - solved.voltage()[3].re;
        assertEquals(230.0 * 26.45 / 26.5, vLoad, 0.5);
        assertEquals(0.0, solved.voltage()[2].im, 1e-12);
        assertEquals(0.0, solved.voltage()[3].im, 1e-12);

        ac.tick();
        double pOut = tele[ConverterElement.TELE_P_OUT];
        assertTrue(pOut > 1900.0 && pOut < 2100.0, "delivered output power=" + pOut);
        // Bridge back toward the DC side: demand = delivered/eff + idle.
        assertEquals(pOut / 0.96 + 2.0,
            ConverterElement.stageDemandWatts(pOut, 0.96, false,
                tele[ConverterElement.TELE_V_IN]), 1e-9);
    }

    // ---- 2. battery series adds voltage, parallel adds capacity ----

    @Test
    void batterySeriesAddsVoltageParallelAddsCapacity() {
        // Voltage adds: 8s EMF is exactly twice 4s EMF (power-of-two scaling is exact).
        double emf4 = BatteryElement.packEmf(BatteryChemistry.LIFEPO4, 4, 1.0);
        double emf8 = BatteryElement.packEmf(BatteryChemistry.LIFEPO4, 8, 1.0);
        assertEquals(14.6, emf4, 1e-9);
        assertEquals(2.0 * emf4, emf8, 0.0);

        // Capacity adds: 2p coulombs are exactly twice 1p.
        double q1 = BatteryElement.packCapacityCoulombs(BatteryChemistry.LIFEPO4, 1);
        double q2 = BatteryElement.packCapacityCoulombs(BatteryChemistry.LIFEPO4, 2);
        assertEquals(2.0 * q1, q2, 0.0);

        // Resistance halves: R = cellR * series / parallel.
        double r1 = BatteryElement.packResistance(BatteryChemistry.LIFEPO4, 4, 1, 1.0, 25.0, 1.0);
        double r2 = BatteryElement.packResistance(BatteryChemistry.LIFEPO4, 4, 2, 1.0, 25.0, 1.0);
        assertEquals(0.0024, r1, 1e-12);
        assertEquals(r1 / 2.0, r2, 0.0);
    }

    // ---- 3. two independent islands simultaneously ----

    @Test
    void twoIslandsSolveIndependently() {
        boolean[] bmsA = {false};
        BatteryElement battA = new BatteryElement(BatteryChemistry.LIFEPO4, 4, 1,
            () -> bmsA[0], new double[2]);
        ElectricalKernel islandA = new ElectricalKernel();
        islandA.setNodeCount(2);
        islandA.setOmega(0.0);
        islandA.setElements(
            List.of(battA, new TestResistor(10.0), new EarthElement()),
            List.of(new int[]{0, 1}, new int[]{0, 1}, new int[]{0}));
        islandA.setConductors(List.of());
        islandA.setElementState(0, new double[]{1.0, 25.0, 1.0});

        boolean[] bmsB = {false};
        BatteryElement battB = new BatteryElement(BatteryChemistry.LIFEPO4, 4, 1,
            () -> bmsB[0], new double[2]);
        ElectricalKernel islandB = new ElectricalKernel();
        islandB.setNodeCount(2);
        islandB.setOmega(0.0);
        islandB.setElements(
            List.of(battB, new TestResistor(10.0), new EarthElement()),
            List.of(new int[]{0, 1}, new int[]{0, 1}, new int[]{0}));
        islandB.setConductors(List.of());
        islandB.setElementState(0, new double[]{1.0, 25.0, 1.0});

        assertTrue(islandA.solve().converged());
        KernelSolveResult solvedB = islandB.solve();
        assertTrue(solvedB.converged());
        double vB0 = solvedB.voltage()[0].re - solvedB.voltage()[1].re;

        // Perturb island A (half-discharged pack), re-solve both.
        islandA.setElementState(0, new double[]{0.5, 25.0, 1.0});
        KernelSolveResult solvedA2 = islandA.solve();
        assertTrue(solvedA2.converged());
        double vA2 = solvedA2.voltage()[0].re - solvedA2.voltage()[1].re;
        assertTrue(Math.abs(vA2 - vB0) > 0.5, "perturbation must move A: A=" + vA2 + " B=" + vB0);

        KernelSolveResult solvedB2 = islandB.solve();
        assertTrue(solvedB2.converged());
        assertEquals(vB0, solvedB2.voltage()[0].re - solvedB2.voltage()[1].re, 0.0);
    }

    // ---- 4. short circuit melts, queued break rebuilds, island recovers ----

    @Test
    void shortCircuitMeltsAndRebuildRecovers() {
        GridManager manager = new GridManager();
        manager.clearTopology();

        // Ground-fault loop: 48 V creative source (+) grounded at (-2,0,0), cable
        // A(0,0,0)-B(1,0,0) in series, return grounded at (2,0,0). Terminals never
        // coincide on the melt branches, so both stay true cable branches.
        boolean[] enabled = {true};
        double[] emf = {48.0};
        double[] rInt = {0.05};
        double[] genTele = new double[2];
        CreativeGeneratorElement source = new CreativeGeneratorElement(
            () -> enabled[0], () -> emf[0], () -> rInt[0], genTele);
        AdapterBlock sourceBlock = new AdapterBlock(pos(-1, 0, 0), source,
            true, false, pos(0, 0, 0), pos(-2, 0, 0));
        AdapterBlock earthWest = new AdapterBlock(pos(-2, 0, 1), new EarthElement(),
            false, false, pos(-2, 0, 0));
        AdapterBlock earthEast = new AdapterBlock(pos(2, 1, 0), new EarthElement(),
            false, false, pos(2, 0, 0));

        manager.putCable(pos(0, 0, 0), ConductorType.INSULATED_COPPER);
        manager.putCable(pos(1, 0, 0), ConductorType.INSULATED_COPPER);
        manager.putAttachedBlock(sourceBlock);
        manager.putAttachedBlock(earthWest);
        manager.putAttachedBlock(earthEast);
        manager.rebuildIslands();

        assertEquals(1, manager.getIslands().size());
        assertEquals(4, manager.getIslands().get(0).nodeCount());

        // ~730 A through 0.0068 ohm cable (~3.6 kW, cap 9.5 J/K) melts within ~60 ticks.
        int ticks = 0;
        while (manager.getPendingBreaks().isEmpty() && ticks < 300) {
            manager.tick(null);
            ticks++;
        }
        assertFalse(manager.getPendingBreaks().isEmpty(),
            "ground fault must melt a cable within 300 ticks");
        assertTrue(ticks < 300, "melt took " + ticks + " ticks");
        assertTrue(manager.getPendingBreaks().contains(pos(0, 0, 0))
            || manager.getPendingBreaks().contains(pos(1, 0, 0)),
            "break candidate must be a fault cable, got " + manager.getPendingBreaks());

        // Melt scan fired on the live kernel before the rebuild boundary.
        IslandContext faulted = manager.getIslandAt(pos(0, 0, 0));
        assertNotNull(faulted);
        assertFalse(faulted.kernel().findMeltedConductors().isEmpty());

        // Next boundary applies every queued break and rebuilds: fault cable(s) gone,
        // every rebuilt island solves (open-circuited stubs converge trivially).
        manager.tick(null);
        assertTrue(manager.getPendingBreaks().isEmpty());
        assertTrue(manager.getKnownCablePositions().size() < 2,
            "at least one fault cable must break, left " + manager.getKnownCablePositions());
        assertFalse(manager.getIslands().isEmpty());
        for (IslandContext island : manager.getIslands()) {
            KernelSolveResult recovered = island.kernel().solve();
            assertTrue(recovered.converged(),
                "rebuilt island must solve, residual=" + recovered.residual());
        }
    }

    // ---- 5. BMS trip + hysteresis auto-recovery ----

    @Test
    void bmsTripAndHysteresisRecovery() {
        // 4s LiFePO4: pack floor 4*2.5 == 10.0 V, recovery 10.0 + 4*0.05 == 10.2 V.
        double minPackV = BatteryElement.packMinVoltage(BatteryChemistry.LIFEPO4, 4);
        assertEquals(10.0, minPackV, 1e-12);

        assertTrue(BatteryElement.bmsNext(false, 9.9, 25.0, minPackV, 4),
            "undervoltage opens the BMS");
        assertTrue(BatteryElement.bmsNext(true, 10.1, 25.0, minPackV, 4),
            "hysteresis holds the BMS open just above the floor");
        assertFalse(BatteryElement.bmsNext(true, 10.3, 25.0, minPackV, 4),
            "BMS recloses above floor + hysteresis");

        assertTrue(BatteryElement.bmsNext(false, 12.0, 61.0, minPackV, 4),
            "overtemperature opens the BMS");
        assertTrue(BatteryElement.bmsNext(true, 12.0, 58.0, minPackV, 4),
            "hysteresis holds the BMS open above 55 C");
        assertFalse(BatteryElement.bmsNext(true, 12.0, 54.0, minPackV, 4),
            "BMS recloses below 55 C");

        // Open BMS stamps an open circuit at kernel level.
        boolean[] bms = {true};
        BatteryElement open = new BatteryElement(BatteryChemistry.LIFEPO4, 4, 1,
            () -> bms[0], new double[2]);
        double[] y = stampConductance(open, new int[]{0, 1}, 2);
        assertEquals(0.0, y[0], 0.0);
        assertEquals(0.0, y[1], 0.0);
        assertEquals(0.0, y[2], 0.0);
    }

    // ---- 6. fallback overload persists nothing in the BE ----

    @Test
    void fallbackOverloadDiscardsKernelState() {
        // 2 kW constant-power knee at 20 V on a 14.6 V pack: no physical root, Newton
        // converges onto the resistive fallback point below vMin with fallbackActive set.
        boolean[] bms = {false};
        double[] battTele = new double[2];
        BatteryElement battery = new BatteryElement(BatteryChemistry.LIFEPO4, 4, 1,
            () -> bms[0], battTele);
        ElectricalKernel kernel = new ElectricalKernel();
        kernel.setNodeCount(2);
        kernel.setOmega(0.0);
        kernel.setElements(
            List.of(battery, new TestConstantPower(2000.0, 20.0), new EarthElement()),
            List.of(new int[]{0, 1}, new int[]{1, 0}, new int[]{0}));
        kernel.setConductors(List.of());

        double[] beState = {1.0, 25.0, 1.0};
        kernel.setElementState(0, beState);
        kernel.tick();

        KernelSolveResult observed = kernel.solve();
        assertTrue(observed.converged());
        assertFalse(observed.singular());
        assertTrue(observed.fallbackActive(), "overload must report fallbackActive");
        double vLoad = observed.voltage()[1].re - observed.voltage()[0].re;
        assertTrue(vLoad < 20.0, "fallback point sits below the knee, V=" + vLoad);

        // The kernel integrated (SoC moved) but the BE snapshot is untouched: no commit.
        double kernelSoc = kernel.getElementState(0)[0];
        assertTrue(kernelSoc < 1.0, "kernel integrated, soc=" + kernelSoc);
        assertArrayEquals(new double[]{1.0, 25.0, 1.0}, beState, 0.0);

        // Rollback: re-sync the kernel from the BE; nothing of the fallback persists.
        kernel.setElementState(0, beState);
        assertArrayEquals(beState, kernel.getElementState(0), 0.0);
    }

    // ---- 7. chunk unload/reload preserves BE state, kernel re-seeded ----

    @Test
    void chunkUnloadReloadPreservesBatteryState() {
        GridManager manager = new GridManager();
        manager.clearTopology();

        // Gentle loop (~20 A on nichrome, far from melting in 3 ticks): 4s LiFePO4
        // pack grounded west, return grounded east of cable B.
        boolean[] bms = {false};
        double[] battTele = new double[2];
        BatteryElement battery = new BatteryElement(BatteryChemistry.LIFEPO4, 4, 1,
            () -> bms[0], battTele);
        AdapterBlock batteryBlock = new AdapterBlock(pos(3, 0, 0), battery,
            true, false, pos(4, 0, 0), pos(2, 0, 0));
        batteryBlock.seedState(1.0, 25.0, 1.0);
        AdapterBlock earthWest = new AdapterBlock(pos(2, 0, 1), new EarthElement(),
            false, false, pos(2, 0, 0));
        AdapterBlock earthEast = new AdapterBlock(pos(6, 1, 0), new EarthElement(),
            false, false, pos(6, 0, 0));

        manager.putCable(pos(4, 0, 0), ConductorType.NICHROME_HEATING);
        manager.putCable(pos(5, 0, 0), ConductorType.NICHROME_HEATING);
        manager.putAttachedBlock(batteryBlock);
        manager.putAttachedBlock(earthWest);
        manager.putAttachedBlock(earthEast);
        manager.rebuildIslands();

        assertEquals(1, manager.getIslands().size());
        IslandContext island = manager.getIslands().get(0);
        int battIdx = island.elementIndex().get(pos(3, 0, 0));
        // Rebuild seeds the kernel from the BE snapshot.
        assertArrayEquals(new double[]{1.0, 25.0, 1.0}, island.kernel().getElementState(battIdx), 0.0);

        for (int t = 0; t < 3; t++) {
            manager.tick(null);
        }
        double[] committed = batteryBlock.getStateArray();
        assertTrue(committed[0] < 1.0, "pack must discharge, soc=" + committed[0]);

        // Unload the chunk holding every cable, block, and terminal: island gone,
        // BE snapshot untouched across the gap.
        manager.onChunkUnload(0, 0);
        manager.tick(null);
        assertTrue(manager.getIslands().isEmpty());
        assertArrayEquals(committed, batteryBlock.getStateArray(), 0.0);

        // Reload: island returns with the kernel re-seeded from the BE (index
        // remove/re-add). Rebuild first for the exact re-seed assert, then tick for
        // the commit-consistency assert (tick advances both together).
        manager.onChunkLoad(0, 0);
        manager.rebuildIslands();
        assertEquals(1, manager.getIslands().size());
        IslandContext reloaded = manager.getIslands().get(0);
        int reloadedIdx = reloaded.elementIndex().get(pos(3, 0, 0));
        assertArrayEquals(committed, reloaded.kernel().getElementState(reloadedIdx), 0.0);
        manager.tick(null);
        assertArrayEquals(batteryBlock.getStateArray(),
            reloaded.kernel().getElementState(reloadedIdx), 0.0);
        assertTrue(reloaded.kernel().solve().converged());
    }

    // ---- 8. migrated creative elements stamp and classify ----

    @Test
    void creativeGeneratorStampsThevenin() {
        boolean[] enabled = {true};
        double[] emf = {48.0};
        double[] rInt = {0.1};
        double[] tele = new double[2];
        CreativeGeneratorElement gen = new CreativeGeneratorElement(
            () -> enabled[0], () -> emf[0], () -> rInt[0], tele);
        assertEquals(2, gen.terminalCount());
        assertEquals(0, gen.stateCount());

        // Thevenin 48 V / 0.1 ohm, positive at terminals[1]: g == 10 S, Norton 480 A.
        Complex[][] y = ComplexNodalSolver.zeroMatrix(2);
        Complex[] inj = ComplexNodalSolver.zeroVector(2);
        gen.stamp(y, inj, new int[]{0, 1}, ComplexNodalSolver.zeroVector(2), new double[0], 0.0);
        assertEquals(10.0, y[1][1].re, 1e-12);
        assertEquals(-10.0, y[0][1].re, 1e-12);
        assertEquals(480.0, inj[1].re, 1e-9);
        assertEquals(-480.0, inj[0].re, 1e-9);

        // Disabled stamps an open circuit.
        enabled[0] = false;
        Complex[][] y2 = ComplexNodalSolver.zeroMatrix(2);
        Complex[] inj2 = ComplexNodalSolver.zeroVector(2);
        gen.stamp(y2, inj2, new int[]{0, 1}, ComplexNodalSolver.zeroVector(2), new double[0], 0.0);
        assertEquals(0.0, y2[1][1].re, 0.0);
        assertEquals(0.0, inj2[1].re, 0.0);

        assertTrue(CreativeGeneratorElement.isActiveSource(true, 48.0));
        assertFalse(CreativeGeneratorElement.isActiveSource(false, 48.0));
        assertFalse(CreativeGeneratorElement.isActiveSource(true, 0.0));
        assertTrue(CreativeGeneratorElement.isACSource(50.0));
        assertFalse(CreativeGeneratorElement.isACSource(0.0));

        assertEquals(0, CreativeGeneratorElement.newStateArray().length);
        assertThrows(IllegalArgumentException.class,
            () -> CreativeGeneratorElement.assignState(new double[1], new double[0]));
    }

    @Test
    void creativeLoadStampsModes() {
        int[] mode = {CreativeLoadElement.MODE_RESISTANCE};
        double[] target = {10.0};
        boolean[] enabled = {true};
        double[] vnom = {230.0};
        double[] tele = new double[2];
        CreativeLoadElement load = new CreativeLoadElement(
            () -> mode[0], () -> target[0], () -> enabled[0], () -> vnom[0], tele);
        assertEquals(2, load.terminalCount());
        assertEquals(0, load.stateCount());
        assertFalse(CreativeLoadElement.isActiveSource());

        // Resistive 10 ohm: g == 0.1 S, omega-independent.
        double[] r = stampConductance(load, new int[]{0, 1}, 2);
        assertEquals(0.1, r[0], 1e-12);

        // Constant-power 100 W on DC at 48 V: g == -100/48^2, iEq == 2*100/48 from T0.
        mode[0] = CreativeLoadElement.MODE_POWER;
        target[0] = 100.0;
        Complex[][] y = ComplexNodalSolver.zeroMatrix(2);
        Complex[] inj = ComplexNodalSolver.zeroVector(2);
        Complex[] v = {new Complex(48.0, 0.0), new Complex(0.0, 0.0)};
        load.stamp(y, inj, new int[]{0, 1}, v, new double[0], 0.0);
        assertEquals(-100.0 / (48.0 * 48.0), y[0][0].re, 1e-12);
        assertEquals(-2.0 * 100.0 / 48.0, inj[0].re, 1e-12);

        // Constant-current 2 A on DC: nodal [-2, +2].
        mode[0] = CreativeLoadElement.MODE_CURRENT;
        target[0] = 2.0;
        Complex[][] y3 = ComplexNodalSolver.zeroMatrix(2);
        Complex[] inj3 = ComplexNodalSolver.zeroVector(2);
        load.stamp(y3, inj3, new int[]{0, 1}, v, new double[0], 0.0);
        assertEquals(-2.0, inj3[0].re, 1e-12);
        assertEquals(2.0, inj3[1].re, 1e-12);

        // AC island: power mode falls back to R = Vnom^2/P with no parallel current.
        mode[0] = CreativeLoadElement.MODE_POWER;
        target[0] = 100.0;
        Complex[][] y4 = ComplexNodalSolver.zeroMatrix(2);
        Complex[] inj4 = ComplexNodalSolver.zeroVector(2);
        load.stamp(y4, inj4, new int[]{0, 1}, v, new double[0],
            GridConstants.AC_OMEGA_RAD_PER_S);
        assertEquals(100.0 / (230.0 * 230.0), y4[0][0].re, 1e-12);
        assertEquals(0.0, inj4[0].re, 0.0);

        // Disabled stamps an open circuit; terminal offsets are adjacent east/west.
        enabled[0] = false;
        double[] open = stampConductance(load, new int[]{0, 1}, 2);
        assertEquals(0.0, open[0], 0.0);
        assertEquals(1, Math.abs(CreativeLoadElement.TERMINAL_OFFSETS[0][0]));
        assertEquals(-CreativeLoadElement.TERMINAL_OFFSETS[0][0],
            CreativeLoadElement.TERMINAL_OFFSETS[1][0]);
    }
}
