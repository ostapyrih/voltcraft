package com.ostapyrih.voltcraft.simulation.grid;

import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.Conductor;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalKernel.KernelSolveResult;
import com.ostapyrih.voltcraft.simulation.solver.ComplexNodalSolver;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2 nonlinear load tests (tests 10-19).
 *
 * <p>All kernel solves run at {@code omega = 0.0} (DC): the nonlinear
 * primitives reject any nonzero omega. Fixtures mirror the Phase 1 patterns
 * and are defined locally; {@code Phase1LinearTest} is untouched.</p>
 */
class Phase2NonlinearTest {

    // ---- Test fixtures (static nested classes, Phase 1 patterns) ----

    /** Linear resistor between two terminals. */
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
            Stamps.admittance(y, terminals[0], terminals[1],
                    new Complex(1.0 / resistance, 0.0));
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
        }
    }

    /** Thevenin source: EMF with series resistance, positive terminal first. */
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

    /**
     * Earth reference: single-terminal 1000 S shunt to implicit ground.
     * Adds {@code g} to {@code Y[t][t]} with zero current injection.
     */
    static final class TestEarth implements ElectricalElement {
        private final double shunt;

        TestEarth() {
            this(1000.0);
        }

        TestEarth(double shunt) {
            this.shunt = shunt;
        }

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
            y[t][t] = y[t][t].add(new Complex(shunt, 0.0));
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
        }
    }

    /** Simple mutable conductor implementation. */
    static final class TestConductor implements Conductor {
        private final int nodeA;
        private final int nodeB;
        private final double resistance;
        private double temperature;
        private final double heatCapacity;
        private final double coolingCoeff;
        private final double meltingTemp;

        TestConductor(int nodeA, int nodeB, double resistance) {
            this(nodeA, nodeB, resistance, 20.0, 100.0, 1.0, 1085.0);
        }

        TestConductor(int nodeA, int nodeB, double resistance, double temperature,
                      double heatCapacity, double coolingCoeff, double meltingTemp) {
            this.nodeA = nodeA;
            this.nodeB = nodeB;
            this.resistance = resistance;
            this.temperature = temperature;
            this.heatCapacity = heatCapacity;
            this.coolingCoeff = coolingCoeff;
            this.meltingTemp = meltingTemp;
        }

        @Override
        public int nodeA() {
            return nodeA;
        }

        @Override
        public int nodeB() {
            return nodeB;
        }

        @Override
        public double resistance() {
            return resistance;
        }

        @Override
        public double temperature() {
            return temperature;
        }

        @Override
        public void setTemperature(double celsius) {
            this.temperature = celsius;
        }

        @Override
        public double heatCapacity() {
            return heatCapacity;
        }

        @Override
        public double coolingCoeff() {
            return coolingCoeff;
        }

        @Override
        public double meltingTemp() {
            return meltingTemp;
        }
    }

    /** Constant-power load between two terminals (DC-only). */
    static final class TestConstantPower implements ElectricalElement {
        private final double p;
        private final double vMin;

        TestConstantPower(double p, double vMin) {
            this.p = p;
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
            Stamps.constantPower(y, in, terminals[0], terminals[1], v, p, vMin, omega);
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
        }
    }

    /** Constant-current load between two terminals (DC-only). */
    static final class TestConstantCurrent implements ElectricalElement {
        private final double iSet;
        private final double vMin;

        TestConstantCurrent(double iSet, double vMin) {
            this.iSet = iSet;
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
            Stamps.constantCurrent(y, in, terminals[0], terminals[1], v, iSet, vMin, omega);
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
        }
    }

    // ---- Helpers ----

    /** DC kernel (omega = 0): nonlinear primitives reject AC omega. */
    private static ElectricalKernel kernel(int nodes, List<ElectricalElement> elements,
                                           List<int[]> terminals) {
        ElectricalKernel k = new ElectricalKernel();
        k.setNodeCount(nodes);
        k.setElements(new ArrayList<>(elements), new ArrayList<>(terminals));
        k.setConductors(List.of());
        k.setOmega(0.0);
        return k;
    }

    // ---- Tests 10-19 ----

    @Test
    void test10ConstantCurrentConvergence() {
        // Topology: CC load in parallel with the source. Node 0 = high rail,
        // node 1 = ground return (earthed). Thevenin 12V/Rint 0.5 across
        // {0,1}; CC 5A load across {0,1}; earth on 1.
        // Expected rail: V = emf − I*Rint = 12 − 5*0.5 = 9.5 V.
        ElectricalKernel k = kernel(2,
                List.of(new TestThevenin(12.0, 0.5), new TestConstantCurrent(5.0, 0.1),
                        new TestEarth()),
                List.of(new int[]{0, 1}, new int[]{0, 1}, new int[]{1}));
        KernelSolveResult r = k.solve();
        assertTrue(r.converged(), "expected convergence, residual=" + r.residual());
        double loadV = r.voltage()[0].sub(r.voltage()[1]).magnitude();
        assertEquals(9.5, loadV, 1e-3, "CC-loaded rail voltage");
    }

    @Test
    void test11ConstantPowerConvergence() {
        // Same parallel topology with a 25 W constant-power load.
        // V² − 12V + 25*0.5 = 0 → upper root (12+√94)/2 ≈ 10.847679857 V.
        // Warm-started at a 12 V differential so Newton takes the upper root.
        ElectricalKernel k = kernel(2,
                List.of(new TestThevenin(12.0, 0.5), new TestConstantPower(25.0, 0.1),
                        new TestEarth()),
                List.of(new int[]{0, 1}, new int[]{0, 1}, new int[]{1}));
        k.setInitialVoltage(new Complex[]{new Complex(12.0, 0.0), Complex.ZERO});
        KernelSolveResult r = k.solve();
        assertTrue(r.converged(), "expected convergence, residual=" + r.residual());
        System.out.println("Phase2 test11 iterations=" + r.newtonIterations());
        assertTrue(r.newtonIterations() <= 20,
                "expected <= 20 Newton iterations, got " + r.newtonIterations());
        double loadV = r.voltage()[0].sub(r.voltage()[1]).magnitude();
        assertEquals(10.847679857, loadV, 1e-3, "CP-loaded rail voltage");
    }

    @Test
    void test12ConstantPowerAboveSourceCapability() {
        // 1000 W demanded from a 12 V / 0.5 Ω source (Pmax = emf²/4R = 72 W):
        // no physical operating point exists. The solver converges onto the
        // resistive fallback (numerical stability, not physical accuracy), so
        // the result must report converged==true with fallbackActive==true and
        // V_load below vMin. Voltages must stay finite with |current| < 1e6.
        ElectricalKernel k = kernel(2,
                List.of(new TestThevenin(12.0, 0.5), new TestConstantPower(1000.0, 0.1),
                        new TestEarth()),
                List.of(new int[]{0, 1}, new int[]{0, 1}, new int[]{1}));
        k.setInitialVoltage(new Complex[]{new Complex(12.0, 0.0), Complex.ZERO});
        KernelSolveResult r = k.solve();
        double vLoad = r.voltage()[0].sub(r.voltage()[1]).re;
        System.out.println("Phase2 test12 converged=" + r.converged()
                + " singular=" + r.singular() + " fallbackActive=" + r.fallbackActive()
                + " residual=" + r.residual() + " iterations=" + r.newtonIterations()
                + " vLoad=" + vLoad);
        assertTrue(r.converged(), "expected fallback convergence, residual=" + r.residual());
        assertTrue(!r.singular(), "expected no singularity");
        assertTrue(r.fallbackActive(), "overloaded CP must report fallbackActive==true");
        assertTrue(vLoad < 0.1, "fallback operating point must sit below vMin, vLoad=" + vLoad);
        for (Complex c : r.voltage()) {
            assertTrue(c.isFinite(), "voltage must be finite: " + c);
            assertTrue(Math.abs(c.re) < 1e6 && Math.abs(c.im) < 1e6,
                    "voltage magnitude must be < 1e6: " + c);
        }
        // Current proxies (documented): source current via the Rint drop
        // |I| = |emf − Vload| / Rint, and CP-branch current via the same
        // piecewise law the stamp uses (P/Vload above vMin, else Vload·P/vMin²).
        double iSrc = (12.0 - vLoad) / 0.5;
        double iCp = (vLoad >= 0.1 && vLoad > 1e-6)
                ? 1000.0 / vLoad
                : vLoad * 1000.0 / (0.1 * 0.1);
        System.out.println("Phase2 test12 vLoad=" + vLoad + " iSrc=" + iSrc + " iCp=" + iCp);
        assertTrue(Double.isFinite(iSrc) && Math.abs(iSrc) < 1e6, "source current=" + iSrc);
        assertTrue(Double.isFinite(iCp) && Math.abs(iCp) < 1e6, "CP current=" + iCp);
    }

    @Test
    void test12bNormalOperatingPointNoFallback() {
        // Same topology as test11 (25 W on 12 V / 0.5 Ω): a genuine physical
        // operating point exists, so the solve must converge with no fallback.
        ElectricalKernel k = kernel(2,
                List.of(new TestThevenin(12.0, 0.5), new TestConstantPower(25.0, 0.1),
                        new TestEarth()),
                List.of(new int[]{0, 1}, new int[]{0, 1}, new int[]{1}));
        k.setInitialVoltage(new Complex[]{new Complex(12.0, 0.0), Complex.ZERO});
        KernelSolveResult r = k.solve();
        double vLoad = r.voltage()[0].sub(r.voltage()[1]).re;
        System.out.println("Phase2 test12b converged=" + r.converged()
                + " singular=" + r.singular() + " fallbackActive=" + r.fallbackActive()
                + " residual=" + r.residual() + " iterations=" + r.newtonIterations()
                + " vLoad=" + vLoad);
        assertTrue(r.converged(), "expected convergence, residual=" + r.residual());
        assertTrue(!r.singular(), "expected no singularity");
        assertTrue(!r.fallbackActive(), "normal 25 W operating point must not touch fallback");
    }

    @Test
    void test13BacktrackingDeterministicFixture() {
        // Vsource=12, Rint=0.5, P=50, vMin=0.1, warm start at 12 V differential.
        // Upper root (12+√44)/2 ≈ 9.3166 V; must converge within 20 iterations.
        ElectricalKernel k = kernel(2,
                List.of(new TestThevenin(12.0, 0.5), new TestConstantPower(50.0, 0.1),
                        new TestEarth()),
                List.of(new int[]{0, 1}, new int[]{0, 1}, new int[]{1}));
        k.setInitialVoltage(new Complex[]{new Complex(12.0, 0.0), Complex.ZERO});
        KernelSolveResult r = k.solve();
        System.out.println("Phase2 test13 iterations=" + r.newtonIterations()
                + " residual=" + r.residual());
        assertTrue(r.converged(), "expected convergence, residual=" + r.residual());
        assertTrue(r.newtonIterations() <= 20,
                "expected <= 20 Newton iterations, got " + r.newtonIterations());
    }

    @Test
    void test14PowerBalanceIncludingGmin() {
        // Source (12 V / 0.5 Ω) across {0,2}; 0.1 Ω conductor {0,1};
        // 10 Ω load {1,2}; earth on 2. All linear: Tellegen balance
        // P_source = P_load + P_conductor + P_GMIN must hold to 1e-4 relative.
        ElectricalKernel k = kernel(3,
                List.of(new TestThevenin(12.0, 0.5), new TestResistor(10.0), new TestEarth()),
                List.of(new int[]{0, 2}, new int[]{1, 2}, new int[]{2}));
        k.setConductors(List.of(new TestConductor(0, 1, 0.1)));
        KernelSolveResult r = k.solve();
        assertTrue(r.converged(), "expected convergence, residual=" + r.residual());
        Complex[] v = r.voltage();

        Complex dropSrc = v[0].sub(v[2]);
        Complex itSrcA = new Complex(2.0, 0.0).mul(dropSrc.sub(new Complex(12.0, 0.0)));
        Complex[] vtSrc = {v[0], v[2]};
        Complex[] itSrc = {itSrcA, itSrcA.neg()};
        double pSourceDelivered = -Stamps.powerInto(vtSrc, itSrc, 0, 2);

        Complex dropLoad = v[1].sub(v[2]);
        Complex itLoadA = dropLoad.div(new Complex(10.0, 0.0));
        double pLoad = Stamps.powerInto(
                new Complex[]{v[1], v[2]}, new Complex[]{itLoadA, itLoadA.neg()}, 0, 2);

        double pConductor = v[0].sub(v[1]).magnitudeSquared() / 0.1;
        double pGmin = GridConstants.GMIN
                * (v[0].magnitudeSquared() + v[1].magnitudeSquared() + v[2].magnitudeSquared());

        double pDissipated = pLoad + pConductor + pGmin;
        double relErr = Math.abs(pSourceDelivered - pDissipated) / Math.max(pSourceDelivered, 1e-12);
        System.out.println("Phase2 test14 pSource=" + pSourceDelivered
                + " pLoad=" + pLoad + " pCond=" + pConductor + " pGmin=" + pGmin);
        assertTrue(pSourceDelivered > 0.0, "source must deliver power");
        assertTrue(relErr < 1e-4, "power balance relErr=" + relErr);
    }

    @Test
    void test15FloatingConstantCurrent() {
        // CC 5 A element alone between two floating nodes (no earth, no source).
        // Below-vMin resistive fallback plus GMIN pins both nodes to ~0, so the
        // differential must vanish. No reference EMF exists for a current
        // source, so the bound is absolute (cf. Phase 1 test 5 uses EMF-relative).
        ElectricalKernel k = kernel(2,
                List.of(new TestConstantCurrent(5.0, 0.1)),
                List.of(new int[]{0, 1}));
        KernelSolveResult r = k.solve();
        for (Complex c : r.voltage()) {
            assertTrue(c.isFinite(), "voltage must be finite: " + c);
        }
        double diff = r.voltage()[0].sub(r.voltage()[1]).magnitude();
        assertTrue(diff < 1e-8, "floating CC differential V0-V1=" + diff);
    }

    @Test
    void test16OneWayTheveninSignAudit() {
        // Stamp-level audit with g = 2 S (Rint 0.5), emf = 12 V, s = 0.1 V.
        // sourceOnly=true, forward (v = 0): i0 ≈ −g·(emf − s·ln2) < 0, i.e. the
        // element delivers power (negative entering-element current at a).
        Complex[][] y = ComplexNodalSolver.zeroMatrix(2);
        Complex[] inj = ComplexNodalSolver.zeroVector(2);
        Complex[] vv = {Complex.ZERO, Complex.ZERO};
        Stamps.oneWayThevenin(y, inj, 0, 1, vv, 2.0, 12.0, true, 0.1, 0.0);
        double dIdvFwd = y[0][0].re;
        double iEqFwd = -inj[0].re; // draw: I[a] −= iEq, so iEq = −I[a]; v = 0 → i0 = iEq
        assertTrue(iEqFwd < -20.0 && iEqFwd > -24.0,
                "forward delivery current i0=" + iEqFwd + " (expected ≈ −23.86)");
        assertTrue(dIdvFwd > 0.0, "forward derivative must be positive: " + dIdvFwd);
        assertEquals(2.0, dIdvFwd, 1e-6, "forward derivative ≈ g");

        // sourceOnly=true, reverse (v = 24): blocked, |i0| ≈ g·s·ln2 ≈ 0.139.
        Complex[][] y2 = ComplexNodalSolver.zeroMatrix(2);
        Complex[] inj2 = ComplexNodalSolver.zeroVector(2);
        Complex[] vv2 = {new Complex(24.0, 0.0), Complex.ZERO};
        Stamps.oneWayThevenin(y2, inj2, 0, 1, vv2, 2.0, 12.0, true, 0.1, 0.0);
        double dIdvRev = y2[0][0].re;
        double iEqRev = -inj2[0].re;
        double i0Rev = iEqRev + dIdvRev * 24.0; // recover i0 = iEq + dIdv·v
        assertTrue(Math.abs(i0Rev) < 0.2, "reverse blocked current |i0|=" + Math.abs(i0Rev));
        assertTrue(i0Rev > 0.0, "reverse leakage sign must stay positive: " + i0Rev);
        assertTrue(dIdvRev > 0.0, "reverse derivative must stay positive: " + dIdvRev);
        assertTrue(dIdvRev < 1e-6, "reverse derivative ≈ 1e-9·g clamp: " + dIdvRev);

        // sourceOnly=false (load orientation), above EMF (v = 24): sinking.
        Complex[][] y3 = ComplexNodalSolver.zeroMatrix(2);
        Complex[] inj3 = ComplexNodalSolver.zeroVector(2);
        Stamps.oneWayThevenin(y3, inj3, 0, 1, vv2, 2.0, 12.0, false, 0.1, 0.0);
        double iEqSink = -inj3[0].re;
        double i0Sink = iEqSink + y3[0][0].re * 24.0;
        assertTrue(i0Sink > 20.0 && i0Sink < 24.0,
                "load-direction sink current i0=" + i0Sink + " (expected ≈ +23.86)");
        assertTrue(y3[0][0].re > 0.0, "sink derivative must be positive");
    }

    @Test
    void test17NonlinearDcOnlyEnforcement() {
        double ac = 314.159;
        Complex[][] y = ComplexNodalSolver.zeroMatrix(2);
        Complex[] inj = ComplexNodalSolver.zeroVector(2);
        Complex[] vv = {new Complex(12.0, 0.0), Complex.ZERO};
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.constantPower(y, inj, 0, 1, vv, 25.0, 0.1, ac));
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.constantCurrent(y, inj, 0, 1, vv, 5.0, 0.1, ac));
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.oneWayThevenin(y, inj, 0, 1, vv, 2.0, 12.0, true, 0.1, ac));
    }

    @Test
    void test18ConstantCurrentSignAudit() {
        // CC 5 A with v = 12 ≥ vMin: pure current source stamp. Nodal vector
        // must read I = [−5, +5] (draw: I[a] −= i, I[b] += i), so element
        // terminal currents It = Yl·Vt − Il are It[0] = +5, It[1] = −5:
        // +5 A enters the element at a (drawn from node a toward node b).
        Complex[][] y = ComplexNodalSolver.zeroMatrix(2);
        Complex[] inj = ComplexNodalSolver.zeroVector(2);
        Complex[] vv = {new Complex(12.0, 0.0), Complex.ZERO};
        Stamps.constantCurrent(y, inj, 0, 1, vv, 5.0, 0.1, 0.0);
        assertEquals(-5.0, inj[0].re, 0.0, "nodal injection at a");
        assertEquals(5.0, inj[1].re, 0.0, "nodal injection at b");
        assertEquals(0.0, y[0][0].re, 0.0, "pure current source stamps no admittance");
        double itA = -inj[0].re; // It = Yl·Vt − Il with Yl = 0
        double itB = -inj[1].re;
        assertEquals(5.0, itA, 0.0, "element terminal current at a");
        assertEquals(-5.0, itB, 0.0, "element terminal current at b");
    }

    @Test
    void test19InputValidation() {
        Complex[][] y = ComplexNodalSolver.zeroMatrix(2);
        Complex[] inj = ComplexNodalSolver.zeroVector(2);
        Complex[] vv = {new Complex(12.0, 0.0), Complex.ZERO};
        // constantPower: P <= 0, vMin <= 0, non-finite, omega != 0.
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.constantPower(y, inj, 0, 1, vv, 0.0, 0.1, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.constantPower(y, inj, 0, 1, vv, -25.0, 0.1, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.constantPower(y, inj, 0, 1, vv, 25.0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.constantPower(y, inj, 0, 1, vv, 25.0, -0.1, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.constantPower(y, inj, 0, 1, vv, Double.NaN, 0.1, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.constantPower(y, inj, 0, 1, vv, Double.POSITIVE_INFINITY, 0.1, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.constantPower(y, inj, 0, 1, vv, 25.0, Double.NaN, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.constantPower(y, inj, 0, 1, vv, 25.0, 0.1, 314.159));
        // constantCurrent: Iset <= 0, vMin <= 0, non-finite, omega != 0.
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.constantCurrent(y, inj, 0, 1, vv, 0.0, 0.1, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.constantCurrent(y, inj, 0, 1, vv, -5.0, 0.1, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.constantCurrent(y, inj, 0, 1, vv, 5.0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.constantCurrent(y, inj, 0, 1, vv, 5.0, -0.1, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.constantCurrent(y, inj, 0, 1, vv, Double.NaN, 0.1, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.constantCurrent(y, inj, 0, 1, vv, 5.0, 0.1, 314.159));
        // oneWayThevenin: g <= 0, s <= 0, non-finite, omega != 0.
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.oneWayThevenin(y, inj, 0, 1, vv, 0.0, 12.0, true, 0.1, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.oneWayThevenin(y, inj, 0, 1, vv, -2.0, 12.0, true, 0.1, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.oneWayThevenin(y, inj, 0, 1, vv, 2.0, 12.0, true, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.oneWayThevenin(y, inj, 0, 1, vv, 2.0, 12.0, true, -0.1, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.oneWayThevenin(y, inj, 0, 1, vv, Double.NaN, 12.0, true, 0.1, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.oneWayThevenin(y, inj, 0, 1, vv, 2.0, 12.0, true, Double.POSITIVE_INFINITY, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.oneWayThevenin(y, inj, 0, 1, vv, 2.0, 12.0, true, 0.1, 314.159));
    }
}
