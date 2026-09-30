package com.ostapyrih.voltcraft.simulation.grid;

import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.Conductor;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalKernel.KernelSolveResult;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State-integration and conductor-thermal tests (tests 20-25): RK2
 * element-state integration, SoC conservation, cable heating/cooling exactness,
 * failed-solve gating, and derivatives-call purity.
 *
 * <p>Fixtures are local static nested classes mirroring the linear/nonlinear
 * resistor/source patterns; {@code LinearCircuitTest} and
 * {@code NonlinearElementTest} are untouched. {@code setOmega(0)} is used
 * wherever a nonlinear (DC-only) stamp is involved; the purely linear tests
 * are omega-agnostic.</p>
 */
class StateIntegrationTest {

    // ---- Test fixtures (static nested classes, linear/nonlinear patterns) ----

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
     * Legacy reference-anchor fixture, now a no-op stamp (no writes) so it
     * truly does nothing. Kept to avoid compile churn; not used in element lists.
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
            // No-op: the per-island reference node (node 0) is the architecture.
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

    /**
     * Zero-terminal exponential decay: {@code dx/dt = -x}. No electrical
     * stamp; exercises element-state integration without any network
     * coupling.
     */
    static final class TestExpDecay implements ElectricalElement {
        @Override
        public int terminalCount() {
            return 0;
        }

        @Override
        public int stateCount() {
            return 1;
        }

        @Override
        public void stamp(Complex[][] y, Complex[] in, int[] terminals, Complex[] v,
                          double[] state, double omega) {
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
            dxdt[0] = -state[0];
        }
    }

    /**
     * State-of-charge element: stamps a 1 A constant-current draw
     * (DC-only, hence the kernel must run at {@code omega = 0}) and
     * integrates {@code dSoC/dt = -It[0]/Q} from the terminal current the
     * kernel passes in. Records every observed {@code It[0]} so the test
     * can verify the kernel fed {@code +1 A} (entering the element) on
     * both RK2 stages of every tick.
     */
    static final class TestSoC implements ElectricalElement {
        private final double capacity;
        private final double vMin;
        final List<Double> seenIt = new ArrayList<>();

        TestSoC(double capacity, double vMin) {
            this.capacity = capacity;
            this.vMin = vMin;
        }

        @Override
        public int terminalCount() {
            return 2;
        }

        @Override
        public int stateCount() {
            return 1;
        }

        @Override
        public void stamp(Complex[][] y, Complex[] in, int[] terminals, Complex[] v,
                          double[] state, double omega) {
            Stamps.constantCurrent(y, in, terminals[0], terminals[1], v, 1.0, vMin, omega);
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
            seenIt.add(it[0].re);
            dxdt[0] = -it[0].re / capacity;
        }
    }

    /**
     * Synthetic non-convergence fixture: injects a NaN current so the
     * linear solve returns a non-finite iterate and the Newton loop breaks
     * with {@code converged == false}. A synthetic stamp is the deterministic way
     * to exercise the failed-solve path.
     */
    static final class TestNan implements ElectricalElement {
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
            in[t] = in[t].add(new Complex(Double.NaN, 0.0));
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
        }
    }

    /**
     * Derivatives-purity spy: stamps a linear 10 ohm resistor (so the
     * network converges) with a benign {@code dxdt[0] = 0}, and retains
     * the input array references plus content snapshots from both RK2
     * calls so the test can verify the kernel never mutated itstate
     * buffers and fed identical operating-point values to both stages.
     */
    static final class TestPuritySpy implements ElectricalElement {
        private final double resistance;
        int calls;
        boolean dxdtAliasedState;
        Complex[] vtRef1;
        Complex[] itRef1;
        double[] stateRef1;
        Complex[] vtSnap1;
        Complex[] itSnap1;
        double[] stateSnap1;
        Complex[] vtSnap2;
        Complex[] itSnap2;
        double[] stateSnap2;

        TestPuritySpy(double resistance) {
            this.resistance = resistance;
        }

        @Override
        public int terminalCount() {
            return 2;
        }

        @Override
        public int stateCount() {
            return 1;
        }

        @Override
        public void stamp(Complex[][] y, Complex[] in, int[] terminals, Complex[] v,
                          double[] state, double omega) {
            Stamps.admittance(y, terminals[0], terminals[1],
                    new Complex(1.0 / resistance, 0.0));
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
            if (dxdt == state) {
                dxdtAliasedState = true;
            }
            calls++;
            if (calls == 1) {
                vtRef1 = vt;
                itRef1 = it;
                stateRef1 = state;
                vtSnap1 = clone(vt);
                itSnap1 = clone(it);
                stateSnap1 = state.clone();
            } else if (calls == 2) {
                vtSnap2 = clone(vt);
                itSnap2 = clone(it);
                stateSnap2 = state.clone();
            }
            dxdt[0] = 0.0;
        }

        private static Complex[] clone(Complex[] a) {
            Complex[] out = new Complex[a.length];
            for (int k = 0; k < a.length; k++) {
                out[k] = new Complex(a[k].re, a[k].im);
            }
            return out;
        }
    }

    // ---- Tests 20-25 ----

    @Test
    void test20SingleElementIntegration() {
        // dx/dt = -x, x(0) = 10, 10 ticks at DT = 0.05 cover t = 0.5 s:
        // analytic 10*e^-0.5 = 6.065306... RK2 must land within 1%.
        ElectricalKernel k = new ElectricalKernel();
        k.setNodeCount(1);
        k.setElements(new ArrayList<>(List.of(new TestExpDecay())),
                new ArrayList<>(List.of(new int[]{})));
        k.setConductors(List.of());
        k.setElementState(0, new double[]{10.0});
        for (int t = 0; t < 10; t++) {
            k.tick();
        }
        double got = k.getElementState(0)[0];
        double expected = 10.0 * Math.exp(-0.5);
        System.out.println("test20 got=" + got + " expected=" + expected);
        assertEquals(expected, got, Math.abs(expected) * 0.01, "exp-decay after 10 ticks");
    }

    @Test
    void test21SocConservation() {
        // Q = 3600 C, 1 A discharge for 1 s (20 ticks at DT = 0.05):
        // SoC drop = 20*DT*1/Q = 1/3600. Rail ~11.99 V >> vMin = 0.1,
        // so the CC stamp stays on its physical branch and It[0] = +1 A.
        double q = 3600.0;
        TestSoC soc = new TestSoC(q, 0.1);
        ElectricalKernel k = new ElectricalKernel();
        k.setNodeCount(2);
        k.setElements(
                new ArrayList<>(List.of(new TestThevenin(12.0, 0.01), soc)),
                new ArrayList<>(List.of(new int[]{0, 1}, new int[]{0, 1})));
        k.setConductors(List.of());
        k.setOmega(0.0);
        k.setElementState(1, new double[]{1.0});
        for (int t = 0; t < 20; t++) {
            k.tick();
        }
        double drop = 1.0 - k.getElementState(1)[0];
        double expectedDrop = 20.0 * GridConstants.DT / q;
        assertEquals(40, soc.seenIt.size(), "two derivative calls per tick over 20 ticks");
        double maxDev = 0.0;
        for (double it : soc.seenIt) {
            maxDev = Math.max(maxDev, Math.abs(it - 1.0));
        }
        System.out.println("test21 drop=" + drop + " expectedDrop=" + expectedDrop
                + " maxItDev=" + maxDev);
        assertEquals(expectedDrop, drop, 1e-9, "SoC drop after 1 s at 1 A");
        assertTrue(maxDev < 1e-9, "every It[0] must equal +1 A, maxDev=" + maxDev);
    }

    @Test
    void test22CableThermalExact() {
        // Two-node loop: Thevenin 0.1 V / Rint 1e-6 across {0,1} in
        // parallel with the heater conductor (R = 0.001, C = 1, k = 0).
        // Nominal heater current ~100 A (dV ~0.1 V, P ~10 W, 0.5 K/tick).
        // Deviation note: an exact 100 A series loop is not attainable with
        // the available primitives (a zero-R return would need a conductor
        // below the 1e-4 clamp or an ideal wire), so the Thevenin-direct
        // topology is used and expectations are computed from the actual
        // solved dV. Cooling is 0, so RK2 is exact: T = 20 + n*DT*P.
        // Melting is 22: after 4 ticks T ~21.996 (strict > is false),
        // after 5 ticks T ~22.495 (melted).
        TestConductor c = new TestConductor(0, 1, 0.001, 20.0, 1.0, 0.0, 22.0);
        ElectricalKernel k = new ElectricalKernel();
        k.setNodeCount(2);
        k.setElements(
                new ArrayList<>(List.of(new TestThevenin(0.1, 1e-6))),
                new ArrayList<>(List.of(new int[]{0, 1})));
        k.setConductors(new ArrayList<>(List.of(c)));
        for (int t = 0; t < 4; t++) {
            k.tick();
        }
        double t4 = c.temperature();
        List<Conductor> melted4 = k.findMeltedConductors();
        k.tick();
        double t5 = c.temperature();
        List<Conductor> melted5 = k.findMeltedConductors();
        Complex[] v = k.getLastSolution();
        double p = v[0].sub(v[1]).magnitudeSquared() / 0.001;
        double expected4 = 20.0 + 4.0 * GridConstants.DT * p;
        double expected5 = 20.0 + 5.0 * GridConstants.DT * p;
        System.out.println("test22 t4=" + t4 + " expected4=" + expected4
                + " melted4=" + melted4.size() + " t5=" + t5 + " expected5=" + expected5
                + " melted5=" + melted5.size() + " p=" + p);
        assertEquals(expected4, t4, 1e-9, "temperature after 4 ticks");
        assertEquals(expected5, t5, 1e-9, "temperature after 5 ticks");
        assertTrue(t4 < 22.0, "T after 4 ticks must be below melting, t4=" + t4);
        assertTrue(melted4.isEmpty(), "nothing melted at tick 4 (strict >)");
        assertTrue(t5 > 22.0, "T after 5 ticks must exceed melting, t5=" + t5);
        assertEquals(1, melted5.size(), "exactly one melted conductor at tick 5");
        assertTrue(melted5.get(0) == c, "melted conductor must be the heater");
    }

    @Test
    void test23CableCoolingExact() {
        // Same heater topology as test22 but with cooling k = 1 and a far
        // melting point. Heating ODE dT/dt = P - (T-20) gives
        // T = 20 + P*(1-e^-t) (P ~9.98, NOT the cooling-free 25 the naive
        // estimate gives). After 10 heating ticks the source and earth are
        // stripped (setElements to empty; the conductor instance keeps its
        // temperature), so V = 0, P = 0, and Newton cooling
        // T = 20 + (Theat-20)*e^-t applies over 20 ticks (t = 1 s).
        TestConductor c = new TestConductor(0, 1, 0.001, 20.0, 1.0, 1.0, 1000.0);
        ElectricalKernel k = new ElectricalKernel();
        k.setNodeCount(2);
        k.setElements(
                new ArrayList<>(List.of(new TestThevenin(0.1, 1e-6))),
                new ArrayList<>(List.of(new int[]{0, 1})));
        k.setConductors(new ArrayList<>(List.of(c)));
        k.setOmega(0.0);
        for (int t = 0; t < 10; t++) {
            k.tick();
        }
        double theat = c.temperature();
        Complex[] vh = k.getLastSolution();
        double p = vh[0].sub(vh[1]).magnitudeSquared() / 0.001;
        double expectedHeat = 20.0 + p * (1.0 - Math.exp(-10.0 * GridConstants.DT));
        System.out.println("test23 theat=" + theat + " expectedHeat=" + expectedHeat
                + " p=" + p);
        assertEquals(expectedHeat, theat, 0.05, "temperature after 10 heating ticks");
        k.setElements(new ArrayList<>(), new ArrayList<>());
        List<Double> trace = new ArrayList<>();
        for (int t = 0; t < 20; t++) {
            k.tick();
            trace.add(c.temperature());
        }
        double tcool = c.temperature();
        double expectedCool = 20.0 + (theat - 20.0) * Math.exp(-20.0 * GridConstants.DT);
        System.out.println("test23 tcool=" + tcool + " expectedCool=" + expectedCool);
        for (int i = 1; i < trace.size(); i++) {
            assertTrue(trace.get(i) < trace.get(i - 1),
                    "cooling must decrease monotonically at step " + i);
        }
        assertEquals(expectedCool, tcool, 0.05, "temperature after 20 cooling ticks");
    }

    @Test
    void test24FailedSolveDoesNotIntegrate() {
        // NaN injection at node 1 (non-reference; node 0 is the reference and its
        // injection is masked to zero) forces solve() to break with converged == false;
        // the tick must then leave element state and conductor temperature
        // byte-identical.
        TestConductor c = new TestConductor(0, 1, 1.0, 50.0, 100.0, 1.0, 1085.0);
        TestExpDecay decay = new TestExpDecay();
        ElectricalKernel k = new ElectricalKernel();
        k.setNodeCount(2);
        k.setElements(
                new ArrayList<>(List.of(new TestNan(), decay)),
                new ArrayList<>(List.of(new int[]{1}, new int[]{})));
        k.setConductors(new ArrayList<>(List.of(c)));
        k.setOmega(0.0);
        k.setElementState(1, new double[]{7.5});
        KernelSolveResult r = k.solve();
        System.out.println("test24 converged=" + r.converged()
                + " singular=" + r.singular() + " residual=" + r.residual());
        assertFalse(r.converged(), "NaN injection must fail the solve");
        double[] before = k.getElementState(1);
        double tempBefore = c.temperature();
        k.tick();
        assertArrayEquals(before, k.getElementState(1), 0.0,
                "element state must be byte-identical after a failed tick");
        assertEquals(tempBefore, c.temperature(), 0.0,
                "conductor temperature must be unchanged after a failed tick");
    }

    @Test
    void test25DerivativesPurity() {
        // One tick must call derivatives exactly twice with identical
        // operating-point values; the kernel must not mutate the buffers it
        // handed out, must not alias dxdt with state, and (with dxdt = 0)
        // must leave kernel state exactly unchanged.
        TestPuritySpy spy = new TestPuritySpy(10.0);
        ElectricalKernel k = new ElectricalKernel();
        k.setNodeCount(2);
        k.setElements(
                new ArrayList<>(List.of(new TestThevenin(12.0, 0.01), spy)),
                new ArrayList<>(List.of(new int[]{0, 1}, new int[]{0, 1})));
        k.setConductors(List.of());
        k.setElementState(1, new double[]{3.25});
        k.tick();
        assertEquals(2, spy.calls, "one tick must evaluate derivatives exactly twice");
        assertFalse(spy.dxdtAliasedState, "kernel must not alias dxdt with state");
        assertArrayEquals(spy.vtSnap1, spy.vtRef1,
                "kernel must not mutate the Vt buffer after the k1 call");
        assertArrayEquals(spy.itSnap1, spy.itRef1,
                "kernel must not mutate the It buffer after the k1 call");
        assertArrayEquals(spy.stateSnap1, spy.stateRef1, 0.0,
                "kernel must not mutate kernel state outside the final update");
        assertArrayEquals(spy.vtSnap1, spy.vtSnap2,
                "both RK2 stages must see the same terminal voltages");
        assertArrayEquals(spy.itSnap1, spy.itSnap2,
                "both RK2 stages must see the same terminal currents");
        assertArrayEquals(spy.stateSnap1, spy.stateSnap2, 0.0,
                "midpoint must equal state when k1 is zero (copy, not alias)");
        assertArrayEquals(new double[]{3.25}, k.getElementState(1), 0.0,
                "zero derivatives must leave kernel state exactly unchanged");
        Complex[] vlast = k.getLastSolution();
        assertEquals(vlast[0].re, spy.vtSnap1[0].re, 0.0, "Vt[0] matches solved node 0");
        assertEquals(vlast[1].re, spy.vtSnap1[1].re, 0.0, "Vt[1] matches solved node 1");
        System.out.println("test25 calls=" + spy.calls
                + " vt0=" + spy.vtSnap1[0] + " vt1=" + spy.vtSnap1[1]
                + " it0=" + spy.itSnap1[0]);
    }
}
