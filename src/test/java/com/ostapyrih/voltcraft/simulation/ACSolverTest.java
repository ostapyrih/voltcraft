package com.ostapyrih.voltcraft.simulation;

import com.ostapyrih.voltcraft.api.data.Phasor;
import com.ostapyrih.voltcraft.simulation.solver.ACSolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ACSolverTest {

    @Test
    public void testImpedanceAndCurrent() {
        // Pure resistive load: 230V RMS, 50 Hz, 10 Ohms
        Phasor voltage = new Phasor(230.0, 0.0);
        Phasor impedance = ACSolver.calculateImpedance(10.0, 0.0, 0.0, 50.0);

        assertEquals(10.0, impedance.magnitude(), 1e-4);
        assertEquals(0.0, impedance.phaseAngleRad(), 1e-4);

        Phasor current = ACSolver.calculateCurrent(voltage, impedance);
        assertEquals(23.0, current.magnitude(), 1e-4, "Current should be 23A RMS");
        assertEquals(0.0, current.phaseAngleRad(), 1e-4);

        ACSolver.ACPowerResult power = ACSolver.calculatePower(voltage, current);
        assertEquals(5290.0, power.realPowerWatts(), 1e-2, "Real power P = 230 * 23 = 5290W");
        assertEquals(0.0, power.reactivePowerVAR(), 1e-2);
        assertEquals(1.0, power.powerFactor(), 1e-4);
    }

    @Test
    public void testTHDCalculation() {
        // Fundamental = 230V, 3rd harmonic = 23V (10%), 5th harmonic = 11.5V (5%)
        // Expected THD = sqrt(23^2 + 11.5^2) / 230 * 100 = sqrt(529 + 132.25) / 230 * 100 = 25.71 / 230 * 100 = 11.18%
        double v1 = 230.0;
        double[] harmonics = new double[]{ 23.0, 11.5 };

        double thd = ACSolver.calculateTHDPercent(v1, harmonics);
        assertEquals(11.18, thd, 0.1, "THD calculation should match mathematical formula");
    }
}
