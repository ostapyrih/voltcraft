package com.ostapyrih.voltcraft.simulation;

import com.ostapyrih.voltcraft.simulation.solver.ThermalEquilibrium;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ThermalEquilibriumTest {

    @Test
    public void testRatedCurrentEquilibrium() {
        // Rated current (16A) through 2.5mm2 copper cable
        ThermalEquilibrium.ThermalSpec spec = ThermalEquilibrium.ThermalSpec.COPPER_2_5_MM2;
        double temp = 20.0;
        double current = 16.0; // within safe 32A ampacity
        double ambient = 20.0;
        double dt = 0.05; // 1 tick

        // Step through 200 ticks (10 seconds)
        for (int i = 0; i < 200; i++) {
            ThermalEquilibrium.StepResult result = ThermalEquilibrium.step(temp, current, ambient, dt, spec);
            temp = result.newTemperature();
            assertEquals(ThermalEquilibrium.ThermalStatus.SAFE, result.status());
        }

        // Temperature should rise modestly above ambient but stay well below insulation melting (120°C)
        assertTrue(temp > 20.0, "Wire should warm up");
        assertTrue(temp < 80.0, "Wire at rated current should remain safely below 80°C");
    }

    @Test
    public void testOvercurrentMelting() {
        // Extreme overcurrent (120A) through 2.5mm2 copper cable (rated 32A)
        ThermalEquilibrium.ThermalSpec spec = ThermalEquilibrium.ThermalSpec.COPPER_2_5_MM2;
        double temp = 20.0;
        double overcurrent = 120.0;
        double ambient = 20.0;
        double dt = 0.05;

        boolean reachedMelting = false;
        // Step for up to 600 ticks (30 seconds)
        for (int i = 0; i < 600; i++) {
            ThermalEquilibrium.StepResult result = ThermalEquilibrium.step(temp, overcurrent, ambient, dt, spec);
            temp = result.newTemperature();
            if (result.status() == ThermalEquilibrium.ThermalStatus.INSULATION_MELTING ||
                result.status() == ThermalEquilibrium.ThermalStatus.CONDUCTOR_MELTED) {
                reachedMelting = true;
                break;
            }
        }

        assertTrue(reachedMelting, "Sustained 120A overcurrent must trigger insulation breakdown or conductor melt");
    }

    @Test
    public void testConvectiveCooling() {
        // Wire heated to 100°C with 0A current should cool down toward ambient (20°C)
        ThermalEquilibrium.ThermalSpec spec = ThermalEquilibrium.ThermalSpec.COPPER_2_5_MM2;
        double temp = 100.0;
        double current = 0.0;
        double ambient = 20.0;
        double dt = 0.05;

        for (int i = 0; i < 200; i++) {
            ThermalEquilibrium.StepResult result = ThermalEquilibrium.step(temp, current, ambient, dt, spec);
            temp = result.newTemperature();
        }

        assertTrue(temp < 100.0, "Wire must cool down in absence of current");
        assertTrue(temp >= ambient, "Wire cannot cool below ambient");
    }
}
