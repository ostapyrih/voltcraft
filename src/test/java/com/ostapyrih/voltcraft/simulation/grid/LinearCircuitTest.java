package com.ostapyrih.voltcraft.simulation.grid;

import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.Conductor;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.simulation.solver.ComplexNodalSolver;
import com.ostapyrih.voltcraft.simulation.solver.ComplexNodalSolver.SolveResult;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalKernel.KernelSolveResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Linear circuit kernel tests (tests 1-9): complex arithmetic, resistive
 * networks (Ohm's law, dividers, parallel loads), floating and reference-node
 * topologies, singular and disconnected topologies, and solver elimination
 * steps.
 */
class LinearCircuitTest {

    // ---- Test fixtures (static nested classes) ----

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

    // ---- Helpers ----

    private static ElectricalKernel kernel(int nodes, List<ElectricalElement> elements,
                                           List<int[]> terminals) {
        ElectricalKernel k = new ElectricalKernel();
        k.setNodeCount(nodes);
        k.setElements(new ArrayList<>(elements), new ArrayList<>(terminals));
        k.setConductors(List.of());
        return k;
    }

    // ---- Tests 1-9 ----

    @Test
    void test1ComplexArithmetic() {
        Complex z = new Complex(3.0, 4.0);
        Complex one = z.div(z);
        assertEquals(1.0, one.re, 1e-12);
        assertEquals(0.0, one.im, 1e-12);

        Complex norm = z.mul(z.conj());
        assertEquals(25.0, norm.re, 1e-12);
        assertEquals(0.0, norm.im, 1e-12);
        assertEquals(z.magnitudeSquared(), norm.re, 1e-12);

        double m = 5.0;
        double phi = Math.atan2(4.0, 3.0);
        Complex p = Complex.polar(m, phi);
        assertEquals(m, p.magnitude(), 1e-12);
        assertEquals(phi, p.phase(), 1e-12);
    }

    @Test
    void test2OhmsLaw() {
        // Nodes: 0 = reference (V = 0), 1 = return rail.
        // Thevenin 12V/0.01 in parallel with 10 ohm load.
        ElectricalKernel k = kernel(2,
                List.of(new TestThevenin(12.0, 0.01), new TestResistor(10.0)),
                List.of(new int[]{0, 1}, new int[]{0, 1}));
        KernelSolveResult r = k.solve();
        assertTrue(r.converged(), "expected convergence, residual=" + r.residual());
        double expectedV = 12.0 * 10.0 / (10.0 + 0.01);
        double loadV = r.voltage()[0].sub(r.voltage()[1]).magnitude();
        assertEquals(expectedV, loadV, 1e-3, "load voltage");
        double loadI = loadV / 10.0;
        assertEquals(expectedV / 10.0, loadI, 1e-3, "load current");
    }

    @Test
    void test3SeriesDivider() {
        // Thevenin 12V/0.01 across {0,2}; R1=10 {0,1}; R2=10 {1,2}; node 0 is reference.
        ElectricalKernel k = kernel(3,
                List.of(new TestThevenin(12.0, 0.01), new TestResistor(10.0),
                        new TestResistor(10.0)),
                List.of(new int[]{0, 2}, new int[]{0, 1}, new int[]{1, 2}));
        KernelSolveResult r = k.solve();
        assertTrue(r.converged(), "expected convergence, residual=" + r.residual());
        double expectedTotal = 12.0 * 20.0 / 20.01;
        double v02 = r.voltage()[0].sub(r.voltage()[2]).magnitude();
        double v12 = r.voltage()[1].sub(r.voltage()[2]).magnitude();
        assertEquals(expectedTotal, v02, 1e-3, "total series voltage");
        assertEquals(expectedTotal / 2.0, v12, 1e-3, "divider midpoint voltage");
    }

    @Test
    void test4ParallelLoads() {
        // Thevenin 12V/0.01 across {0,1}; two 10 ohm loads in parallel; node 0 is reference.
        ElectricalKernel k = kernel(2,
                List.of(new TestThevenin(12.0, 0.01), new TestResistor(10.0),
                        new TestResistor(10.0)),
                List.of(new int[]{0, 1}, new int[]{0, 1}, new int[]{0, 1}));
        KernelSolveResult r = k.solve();
        assertTrue(r.converged(), "expected convergence, residual=" + r.residual());
        double v = r.voltage()[0].sub(r.voltage()[1]).magnitude();
        double expectedV = 12.0 * 5.0 / 5.01;
        assertEquals(expectedV, v, 1e-3, "parallel rail voltage");
        double iTotal = v / 10.0 + v / 10.0;
        assertEquals(12.0 / 5.01, iTotal, 1e-3, "total current");
    }

    @Test
    void test5FloatingNetwork() {
        // Floating Thevenin 12V/0.01 between 0 and 1, no earth.
        // Differential voltage must equal the EMF to 1e-8 relative.
        ElectricalKernel k = kernel(2,
                List.of(new TestThevenin(12.0, 0.01)),
                List.of(new int[]{0, 1}));
        KernelSolveResult r = k.solve();
        double diff = r.voltage()[0].sub(r.voltage()[1]).magnitude();
        double relErr = Math.abs(diff - 12.0) / 12.0;
        assertTrue(relErr < 1e-8, "floating V0-V1=" + diff + " relErr=" + relErr);
    }

    @Test
    void test6EarthReference() {
        ElectricalKernel k = kernel(2,
                List.of(new TestThevenin(12.0, 0.01), new TestResistor(10.0)),
                List.of(new int[]{0, 1}, new int[]{0, 1}));
        KernelSolveResult r = k.solve();
        assertTrue(r.converged(), "expected convergence, residual=" + r.residual());
        assertEquals(0.0, r.voltage()[0].re, 1e-9, "reference node voltage=" + r.voltage()[0]);
        assertEquals(0.0, r.voltage()[0].im, 1e-9);
        assertTrue(r.voltage()[0].magnitude() < 1e-9,
                "reference node magnitude=" + r.voltage()[0].magnitude());
    }

    @Test
    void test7SingularDetection() {
        SolveResult r = ComplexNodalSolver.solve(
                ComplexNodalSolver.zeroMatrix(3), ComplexNodalSolver.zeroVector(3));
        assertTrue(r.singular(), "zero system must be singular");
        assertFalse(r.converged(), "singular system must not converge");
        for (Complex c : r.voltage()) {
            assertTrue(Double.isFinite(c.re) && Double.isFinite(c.im),
                    "voltage must contain no NaN/Inf: " + c);
        }
    }

    @Test
    void test8DisconnectedNode() {
        // Source + load on nodes 0-1, node 2 isolated (safety-tied to zero).
        ElectricalKernel k = kernel(3,
                List.of(new TestThevenin(12.0, 0.01), new TestResistor(10.0)),
                List.of(new int[]{0, 1}, new int[]{0, 1}));
        KernelSolveResult r = k.solve();
        assertTrue(r.converged(), "expected convergence, residual=" + r.residual());
        for (Complex c : r.voltage()) {
            assertTrue(Objects.requireNonNull(c).isFinite(), "voltage must be finite: " + c);
        }
        assertEquals(0.0, r.voltage()[0].re, 1e-9, "reference node must be zero");
        assertEquals(0.0, r.voltage()[2].magnitude(), 1e-9, "isolated node must sit at zero");
    }

    @Test
    void test9LinearEliminationSteps() {
        Complex[][] y = {
                {new Complex(2, 0), new Complex(1, 0), new Complex(1, 0)},
                {new Complex(1, 0), new Complex(2, 0), new Complex(1, 0)},
                {new Complex(1, 0), new Complex(1, 0), new Complex(2, 0)},
        };
        Complex[] inj = {new Complex(4, 0), new Complex(5, 0), new Complex(6, 0)};
        SolveResult r = ComplexNodalSolver.solve(y, inj);
        System.out.println("test9 matrix=[[2,1,1],[1,2,1],[1,1,2]] steps="
                + r.linearEliminationSteps());
        assertEquals(3, r.linearEliminationSteps(),
                "dense 3x3 with no zero sub-pivot factors must take 3 row updates");
        assertTrue(r.converged(), "dense system must converge");
    }
}
