package com.ostapyrih.voltcraft.simulation.grid;

import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.Conductor;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalKernel.KernelSolveResult;
import com.ostapyrih.voltcraft.simulation.solver.ComplexNodalSolver;
import com.ostapyrih.voltcraft.simulation.solver.ComplexNodalSolver.SolveResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 verification + documentation tests (tests 26-33).
 *
 * <p>Fixtures are local static nested classes mirroring the Phase 1/2/3
 * patterns; no other test or main file is touched. {@code setOmega(0)} is
 * used wherever a nonlinear (DC-only) stamp is involved; purely linear
 * tests are omega-agnostic and leave the default omega untouched.</p>
 */
class Phase4VerificationTest {

    // ---- Test fixtures (static nested classes, Phase 1/2/3 patterns) ----

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

    /**
     * Zero-terminal exponential decay: {@code dx/dt = -x}. No electrical
     * stamp; exercises element-state integration without network coupling.
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
     * Warm-start spy for test 33: single-terminal element with a no-op
     * stamp that records the voltage it was first stamped at. Because the
     * kernel's first {@code buildSystem} call in {@code solve()} uses the
     * warm-start vector directly, the recorded value is the warm-start
     * vector entry for this element's node.
     */
    static final class SpyElement implements ElectricalElement {
        boolean armed = true;
        Complex firstSeen;
        int stampCalls;

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
            stampCalls++;
            if (armed) {
                armed = false;
                firstSeen = v[terminals[0]];
            }
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
        }
    }

    // ---- Tests 26-33 ----

    @Test
    void test26LongRun200Ticks() {
        // Thevenin 12 V / 0.5 ohm across {0,2}; 10 ohm load {1,2};
        // 0.05 ohm conductor {0,1}; earth on 2; exp-decay state x(0) = 10.
        // Purely linear network: every tick must converge and integrate.
        TestConductor c = new TestConductor(0, 1, 0.05);
        ElectricalKernel k = new ElectricalKernel();
        k.setNodeCount(3);
        k.setElements(
                new ArrayList<>(List.of(new TestThevenin(12.0, 0.5), new TestResistor(10.0),
                        new TestEarth(), new TestExpDecay())),
                new ArrayList<>(List.of(new int[]{0, 2}, new int[]{1, 2}, new int[]{2}, new int[]{})));
        k.setConductors(new ArrayList<>(List.of(c)));
        k.setElementState(3, new double[]{10.0});
        int ticks = 200;
        for (int t = 0; t < ticks; t++) {
            k.tick();
            for (Complex v : k.getLastSolution()) {
                assertTrue(v.isFinite(), "tick " + t + ": voltage must be finite: " + v);
            }
        }
        for (Complex v : k.getLastSolution()) {
            assertTrue(v.isFinite(), "final voltage must be finite: " + v);
        }
        for (int idx = 0; idx < 4; idx++) {
            for (double s : k.getElementState(idx)) {
                assertTrue(Double.isFinite(s), "element " + idx + " state must be finite: " + s);
            }
        }
        assertTrue(Double.isFinite(c.temperature()),
                "conductor temperature must be finite: " + c.temperature());
        KernelSolveResult r = k.solve();
        System.out.println("Phase4 test26 ticks=" + ticks + " residual=" + r.residual()
                + " converged=" + r.converged() + " singular=" + r.singular()
                + " iterations=" + r.newtonIterations()
                + " decayState=" + k.getElementState(3)[0]
                + " conductorTemp=" + c.temperature());
        assertTrue(r.converged(), "final solve must converge, residual=" + r.residual());
        assertTrue(r.residual() < GridConstants.NEWTON_TOL * 10.0,
                "final residual must be < NEWTON_TOL*10 (=1e-5), got " + r.residual());
    }

    @Test
    void test27PerformanceDiagnostic() {
        // Chain/tree of 100 nodes: 99 series conductors (i,i+1), 1 Thevenin
        // source {0,99}, 49 resistor loads {i,99} (i = 1..49), earth on 99.
        // Node count = 100, elements = 51, conductors = 99.
        int nodes = 100;
        List<Conductor> conductors = new ArrayList<>();
        for (int i = 0; i < nodes - 1; i++) {
            conductors.add(new TestConductor(i, i + 1, 0.1));
        }
        List<ElectricalElement> elements = new ArrayList<>();
        List<int[]> terms = new ArrayList<>();
        elements.add(new TestThevenin(12.0, 0.5));
        terms.add(new int[]{0, nodes - 1});
        for (int i = 1; i <= 49; i++) {
            elements.add(new TestResistor(10.0 + i));
            terms.add(new int[]{i, nodes - 1});
        }
        elements.add(new TestEarth());
        terms.add(new int[]{nodes - 1});
        ElectricalKernel k = new ElectricalKernel();
        k.setNodeCount(nodes);
        k.setElements(new ArrayList<>(elements), new ArrayList<>(terms));
        k.setConductors(new ArrayList<>(conductors));
        long t0 = System.nanoTime();
        k.tick();
        long t1 = System.nanoTime();
        double ms = (t1 - t0) / 1e6;
        System.out.println("Phase4 test27 nodes=" + nodes + " elements=" + elements.size()
                + " conductors=" + conductors.size() + " tickMs=" + ms);
        for (Complex v : k.getLastSolution()) {
            assertTrue(v.isFinite(), "voltage must be finite: " + v);
        }
    }

    @Test
    void test28ConvergenceRobustness() {
        // Constrained deterministic generator, seed 0x5EED (= 24205).
        // Regime: source V in [1,48], Rint in [0.01,5], R_load in [1,1000],
        // P in [1,200] subject to CP feasibility P <= 0.8*Vs^2/(4*Rint),
        // vMin = 0.1, conductor R in [1e-4,1] (linear-uniform), random tree
        // over n = 3 + rng.nextInt(8) nodes with one conductor per tree
        // edge, one Thevenin + one resistor XOR one CP load across the
        // source terminals, earth on the last node, ZERO warm start.
        // Feasibility guard: when CP is used and 0.8*Vs^2/(4*Rint) < 1 no
        // P in [1,200] can satisfy the bound, so Vs/Rint are resampled
        // first; then P itself is resampled until the bound holds.
        // WARM-START NOTE (measured): a Vs warm start traps Newton in a
        // fallback/physical branch limit cycle (40 exhausted iterations,
        // residual ~0.4-0.8) on tree-loaded CP trials, while the same
        // networks converge in 2 iterations from zeros; every observed
        // Vs-start miss was re-solved from zeros with converged=true.
        // The spec permits either ("source V (or zeros?...)"), so zeros
        // are used. Zero-start CP trials converge onto the resistive
        // fallback operating point (fallbackActive tallied below); the
        // count assertion is converged && !singular per spec.
        Random rng = new Random(0x5EED);
        int trials = 100;
        int ok = 0;
        int fallbackCount = 0;
        int failPrinted = 0;
        for (int t = 0; t < trials; t++) {
            int n = 3 + rng.nextInt(8);
            double vs = 1.0 + rng.nextDouble() * 47.0;
            double rint = 0.01 + rng.nextDouble() * 4.99;
            double rLoad = 1.0 + rng.nextDouble() * 999.0;
            boolean useCP = rng.nextBoolean();
            double p = 0.0;
            if (useCP) {
                double bound = 0.8 * vs * vs / (4.0 * rint);
                int guard = 0;
                while (bound < 1.0 && guard++ < 1000) {
                    vs = 1.0 + rng.nextDouble() * 47.0;
                    rint = 0.01 + rng.nextDouble() * 4.99;
                    bound = 0.8 * vs * vs / (4.0 * rint);
                }
                p = 1.0 + rng.nextDouble() * 199.0;
                guard = 0;
                while (p > bound && guard++ < 10000) {
                    p = 1.0 + rng.nextDouble() * 199.0;
                }
            }
            List<ElectricalElement> elements = new ArrayList<>();
            List<int[]> terms = new ArrayList<>();
            elements.add(new TestThevenin(vs, rint));
            terms.add(new int[]{0, n - 1});
            if (useCP) {
                // CP-only: the feasibility bound then characterizes the full
                // load (a parallel resistor would add unmodeled loading).
                elements.add(new TestConstantPower(p, 0.1));
                terms.add(new int[]{0, n - 1});
            } else {
                elements.add(new TestResistor(rLoad));
                terms.add(new int[]{0, n - 1});
            }
            elements.add(new TestEarth());
            terms.add(new int[]{n - 1});
            List<Conductor> conductors = new ArrayList<>();
            for (int j = 1; j < n; j++) {
                int parent = rng.nextInt(j);
                double rc = 1e-4 + rng.nextDouble() * (1.0 - 1e-4);
                conductors.add(new TestConductor(parent, j, rc));
            }
            ElectricalKernel k = new ElectricalKernel();
            k.setNodeCount(n);
            k.setElements(new ArrayList<>(elements), new ArrayList<>(terms));
            k.setConductors(new ArrayList<>(conductors));
            k.setOmega(0.0);
            Complex[] init = new Complex[n];
            for (int i = 0; i < n; i++) {
                init[i] = Complex.ZERO;
            }
            k.setInitialVoltage(init);
            KernelSolveResult r = k.solve();
            if (r.converged() && !r.singular()) {
                ok++;
                if (r.fallbackActive()) {
                    fallbackCount++;
                }
            } else if (failPrinted < 5) {
                failPrinted++;
                StringBuilder sb = new StringBuilder();
                sb.append("Phase4 test28 MISS trial=").append(t).append(" n=").append(n)
                        .append(" vs=").append(vs).append(" rint=").append(rint)
                        .append(" rLoad=").append(rLoad).append(" useCP=").append(useCP)
                        .append(" p=").append(p).append(" converged=").append(r.converged())
                        .append(" singular=").append(r.singular())
                        .append(" residual=").append(r.residual())
                        .append(" iters=").append(r.newtonIterations()).append(" tree=");
                for (Conductor cc : conductors) {
                    sb.append("(").append(cc.nodeA()).append(",").append(cc.nodeB())
                            .append(":").append(cc.resistance()).append(")");
                }
                System.out.println(sb);
            }
        }
        double pct = 100.0 * ok / trials;
        System.out.println("Phase4 test28 seed=0x5EED(24205) trials=" + trials
                + " regime: Vs in [1,48], Rint in [0.01,5], Rload in [1,1000],"
                + " P in [1,200] with P<=0.8*Vs^2/(4*Rint), vMin=0.1,"
                + " conductor R in [1e-4,1] linear-uniform, random tree 3-10 nodes,"
                + " one resistor XOR one CP load across the source terminals,"
                + " earth on last node, zero warm start;"
                + " converged=" + ok + "/" + trials + " (" + pct + "%)"
                + " fallbackActive=" + fallbackCount);
        assertTrue(ok >= 99, "expected >=99% convergence, got " + ok + "/" + trials);
    }

    @Test
    void test29SignConventionAudit() {
        // 3-node series loop: source {0,2}, resistor {1,2}, return
        // conductor {0,1}, earth on 2.
        // DEVIATION NOTE: the literal placement "source {0,1} + resistor
        // {1,2} + earth, no return path" leaves node 0 floating (an open
        // circuit whose only return is the GMIN-scale ground leakage), so
        // no series current flows and the sign assertions cannot hold
        // (measured: source It[0] ~ -1.2e-8, load It[0] ~ -1.2e-8, both
        // leakage-scale). The loop-closed variant above is the minimal
        // true series network on 3 nodes. Source terminal 0 (positive)
        // must show It[0] < 0 (delivering, negative entering at the
        // positive terminal); load terminal 0 (at node 1) must show
        // It[0] > 0 (consuming).
        ElectricalKernel k = new ElectricalKernel();
        k.setNodeCount(3);
        k.setElements(
                new ArrayList<>(List.of(new TestThevenin(12.0, 0.5), new TestResistor(10.0),
                        new TestEarth())),
                new ArrayList<>(List.of(new int[]{0, 2}, new int[]{1, 2}, new int[]{2})));
        k.setConductors(new ArrayList<>(List.of(new TestConductor(0, 1, 0.05))));
        KernelSolveResult r = k.solve();
        assertTrue(r.converged(), "expected convergence, residual=" + r.residual());
        Complex[] itSrc = k.terminalCurrents(0);
        Complex[] itLoad = k.terminalCurrents(1);
        System.out.println("Phase4 test29 srcIt0=" + itSrc[0] + " srcIt1=" + itSrc[1]
                + " loadIt0=" + itLoad[0] + " loadIt1=" + itLoad[1]);
        assertTrue(itSrc[0].re < 0.0, "source must deliver (It[0] < 0), got " + itSrc[0]);
        assertTrue(itLoad[0].re > 0.0, "load must consume (It[0] > 0), got " + itLoad[0]);
    }

    @Test
    void test30BacktrackingOnLinear() {
        // Pure linear network (no nonlinear elements): source 12 V / 0.5
        // across {0,2}, two 10 ohm resistors {0,1} and {1,2}, two
        // conductors {0,1} and {1,2}, earth on 2. Must converge in
        // well under 5 Newton iterations.
        ElectricalKernel k = new ElectricalKernel();
        k.setNodeCount(3);
        k.setElements(
                new ArrayList<>(List.of(new TestThevenin(12.0, 0.5), new TestResistor(10.0),
                        new TestResistor(10.0), new TestEarth())),
                new ArrayList<>(List.of(new int[]{0, 2}, new int[]{0, 1},
                        new int[]{1, 2}, new int[]{2})));
        k.setConductors(new ArrayList<>(List.of(
                new TestConductor(0, 1, 0.05), new TestConductor(1, 2, 0.05))));
        KernelSolveResult r = k.solve();
        System.out.println("Phase4 test30 iterations=" + r.newtonIterations()
                + " residual=" + r.residual() + " converged=" + r.converged()
                + " singular=" + r.singular());
        assertTrue(r.converged(), "expected convergence, residual=" + r.residual());
        assertFalse(r.singular(), "expected no singularity");
        assertTrue(r.newtonIterations() < 5,
                "linear network must converge in <5 Newton iterations, got "
                        + r.newtonIterations());
    }

    @Test
    void test31NonlinearDcOnlyRegression() {
        // All three nonlinear primitives reject any nonzero omega (AC).
        // Duplicates Phase 2 test 17 at kernel-docs level; kept as the
        // doc-level regression for DC-only enforcement.
        double ac = 314.159;
        Complex[][] y = ComplexNodalSolver.zeroMatrix(2);
        Complex[] inj = ComplexNodalSolver.zeroVector(2);
        Complex[] vv = {new Complex(1.0, 0.0), Complex.ZERO};
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.constantPower(y, inj, 0, 1, vv, 10.0, 0.1, ac));
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.constantCurrent(y, inj, 0, 1, vv, 5.0, 0.1, ac));
        assertThrows(IllegalArgumentException.class,
                () -> Stamps.oneWayThevenin(y, inj, 0, 1, vv, 2.0, 12.0, true, 0.1, ac));
    }

    @Test
    void test32SingularNotConverged() {
        // Solver-level invariant: a singular system never reports converged,
        // and the returned voltage carries no NaN/Inf.
        SolveResult r = ComplexNodalSolver.solve(
                ComplexNodalSolver.zeroMatrix(3), ComplexNodalSolver.zeroVector(3));
        System.out.println("Phase4 test32 singular=" + r.singular()
                + " converged=" + r.converged() + " residual=" + r.residual());
        assertTrue(r.singular(), "zero system must be singular");
        assertFalse(r.converged(), "singular system must not converge");
        for (Complex c : r.voltage()) {
            assertTrue(Double.isFinite(c.re) && Double.isFinite(c.im),
                    "voltage must contain no NaN/Inf: " + c);
        }
    }

    @Test
    void test33InitialVoltageNullClearsOverride() {
        // Method: a spy element records the voltage vector passed to its
        // first stamp call, which is the warm-start vector (the kernel's
        // first buildSystem call uses it directly). Solve once to establish
        // lastSolution L; arm the spy; install a far (all-100 V) override;
        // clear it with setInitialVoltage(null); solve again. The spy must
        // observe L (within 1e-12), not the discarded 100 V override, and
        // the converged result must equal the pre-override solve.
        SpyElement spy = new SpyElement();
        ElectricalKernel k = new ElectricalKernel();
        k.setNodeCount(2);
        k.setElements(
                new ArrayList<>(List.of(new TestThevenin(12.0, 0.5), new TestEarth(), spy)),
                new ArrayList<>(List.of(new int[]{0, 1}, new int[]{1}, new int[]{0})));
        k.setConductors(List.of());
        KernelSolveResult r1 = k.solve();
        assertTrue(r1.converged(), "expected convergence, residual=" + r1.residual());
        Complex[] last = k.getLastSolution();
        spy.armed = true;
        spy.firstSeen = null;
        spy.stampCalls = 0;
        k.setInitialVoltage(new Complex[]{new Complex(100.0, 0.0), new Complex(100.0, 0.0)});
        k.setInitialVoltage(null);
        KernelSolveResult r2 = k.solve();
        assertTrue(r2.converged(), "expected convergence, residual=" + r2.residual());
        assertTrue(spy.stampCalls > 0, "spy must observe at least one stamp call");
        double err = spy.firstSeen.sub(last[0]).magnitude();
        double distToOverride = spy.firstSeen.sub(new Complex(100.0, 0.0)).magnitude();
        System.out.println("Phase4 test33 last0=" + last[0] + " firstSeen=" + spy.firstSeen
                + " err=" + err + " distToOverride=" + distToOverride);
        assertTrue(err < 1e-12,
                "warm start must equal lastSolution after null-clear, err=" + err);
        assertTrue(distToOverride > 1.0,
                "warm start must not be the discarded 100 V override: " + spy.firstSeen);
        assertEquals(r1.voltage()[0].re, r2.voltage()[0].re, 1e-12, "V0 repeatability");
        assertEquals(r1.voltage()[1].re, r2.voltage()[1].re, 1e-12, "V1 repeatability");
    }
}
