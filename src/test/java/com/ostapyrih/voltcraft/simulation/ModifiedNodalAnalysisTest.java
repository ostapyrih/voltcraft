package com.ostapyrih.voltcraft.simulation;

import com.ostapyrih.voltcraft.simulation.solver.ModifiedNodalAnalysis;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ModifiedNodalAnalysisTest {

    @Test
    public void testSimpleOhmCircuit() {
        // Circuit: 12V DC source across a 4 Ohm resistor.
        // Node 0: Ground (0V)
        // Node 1: Positive terminal (12V)
        // Expected current: I = V / R = 12 / 4 = 3 Amperes.
        ModifiedNodalAnalysis.Circuit circuit = new ModifiedNodalAnalysis.Circuit(1);
        circuit.addVoltageSource(1, 0, 12.0);
        circuit.addResistor(1, 0, 4.0);

        ModifiedNodalAnalysis.Solution solution = ModifiedNodalAnalysis.solve(circuit);

        assertEquals(12.0, solution.getNodeVoltage(1), 1e-4, "Node 1 should be at 12V");
        assertEquals(0.0, solution.getNodeVoltage(0), 1e-4, "Ground should be at 0V");
        assertEquals(-3.0, solution.voltageSourceCurrents()[0], 1e-4, "Source should deliver 3A");
    }

    @Test
    public void testVoltageDivider() {
        // Circuit: 24V DC source connected to R1 (10 Ohm) and R2 (10 Ohm) in series to ground.
        // Node 1: 24V source
        // Node 2: Divider midpoint
        // Node 0: Ground
        // Expected: Node 1 = 24V, Node 2 = 12V, Current = 24 / 20 = 1.2A.
        ModifiedNodalAnalysis.Circuit circuit = new ModifiedNodalAnalysis.Circuit(2);
        circuit.addVoltageSource(1, 0, 24.0);
        circuit.addResistor(1, 2, 10.0);
        circuit.addResistor(2, 0, 10.0);

        ModifiedNodalAnalysis.Solution solution = ModifiedNodalAnalysis.solve(circuit);

        assertEquals(24.0, solution.getNodeVoltage(1), 1e-4);
        assertEquals(12.0, solution.getNodeVoltage(2), 1e-4);
        assertEquals(-1.2, solution.voltageSourceCurrents()[0], 1e-4);
    }

    @Test
    public void testParallelResistors() {
        // Circuit: 10V DC source connected to two parallel 100 Ohm resistors.
        // Equivalent R = 50 Ohms. Total I = 10 / 50 = 0.2A.
        ModifiedNodalAnalysis.Circuit circuit = new ModifiedNodalAnalysis.Circuit(1);
        circuit.addVoltageSource(1, 0, 10.0);
        circuit.addResistor(1, 0, 100.0);
        circuit.addResistor(1, 0, 100.0);

        ModifiedNodalAnalysis.Solution solution = ModifiedNodalAnalysis.solve(circuit);

        assertEquals(10.0, solution.getNodeVoltage(1), 1e-4);
        assertEquals(-0.2, solution.voltageSourceCurrents()[0], 1e-4);
    }
}
