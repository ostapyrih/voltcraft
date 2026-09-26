package com.ostapyrih.voltcraft.simulation;

import com.ostapyrih.voltcraft.simulation.conversion.InputPowerRegulator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the converter input servo behaves like real electronics on a starved
 * rail: it trims its current draw until the source holds steady and then freezes —
 * a 40W panel delivers a steady ~40W instead of strobing telemetry on/off.
 *
 * <p>Each loop iteration mimics one entity-tick + grid-tick round trip: power
 * demand is the load need capped by servo current, resistance is stamped from
 * last tick's voltage, the Thevenin source is re-solved, then the servo retunes.
 */
public class InputPowerRegulatorTest {

    private static final double MIN_VIN = 15.0;
    // MPPT tracking voltage for the test panel: below open-circuit (as on real
    // hardware) so the servo can park the rail there with nonzero draw.
    private static final double TARGET = 25.0;

    /** One round trip, returning {voltage, capAmps, deliveredWatts}. */
    private static double[] roundTrip(double voc, double rint, double lastV, double needWatts, double capAmps, double capMaxAmps) {
        double demand = Math.min(Math.max(0.0, needWatts), Math.max(0.0, capAmps) * Math.max(1.0, lastV));
        double r = demand <= 1e-9 ? 1e9 : (lastV * lastV) / Math.max(1e-6, demand);
        double v = voc * r / (r + rint);
        double cap = InputPowerRegulator.updateCurrentCap(capAmps, capMaxAmps, v, MIN_VIN, TARGET);
        double delivered = (v * v) / r;
        return new double[] { v, cap, delivered };
    }

    @Test
    public void testStarvedRailConvergesToSteadyDraw() {
        // 40W-class source: Voc=40V, Rint=10 ohm => matched Pmax = 40W.
        double voc = 40.0;
        double rint = 10.0;
        double capMaxAmps = 30.0;
        double need = 500.0; // Load wants far more than the source can ever give.

        double v = voc;
        double cap = 0.0; // Servo always starts discharged (soft-start ramp).
        double[] tailV = new double[100];
        double[] tailP = new double[100];

        for (int i = 0; i < 400; i++) {
            double[] out = roundTrip(voc, rint, v, need, cap, capMaxAmps);
            v = out[0];
            cap = out[1];
            if (i >= 300) {
                tailV[i - 300] = v;
                tailP[i - 300] = out[2];
            }
        }

        double minV = Double.MAX_VALUE, maxV = 0.0, minP = Double.MAX_VALUE, maxP = 0.0;
        for (int i = 0; i < 100; i++) {
            minV = Math.min(minV, tailV[i]);
            maxV = Math.max(maxV, tailV[i]);
            minP = Math.min(minP, tailP[i]);
            maxP = Math.max(maxP, tailP[i]);
        }

        // Servo parks the rail at its target and freezes: last 100 ticks bit-steady.
        assertEquals(minV, maxV, 1e-9, "Rail voltage must freeze, not flicker");
        assertEquals(minP, maxP, 1e-9, "Delivered power must freeze, not flicker");
        assertTrue(minV > 1.0, "Rail must never collapse once regulated, was: " + minV);
        assertTrue(minV >= MIN_VIN * 0.99 && minV <= TARGET + 1e-9,
            "Rail must park at/below the servo target, was: " + minV);
        assertTrue(minP > 25.0 && minP <= 41.0,
            "40W source must deliver near-maximum steady power, delivered: " + minP);
    }

    @Test
    public void testHealthyRailUsesFullPower() {
        // Stiff source: rail never sags, full 500W demand must flow untouched.
        double voc = 40.0;
        double rint = 0.01;
        double capMaxAmps = 30.0;

        double v = voc;
        double cap = 0.0;
        double delivered = 0.0;
        for (int i = 0; i < 400; i++) {
            double[] out = roundTrip(voc, rint, v, 500.0, cap, capMaxAmps);
            v = out[0];
            cap = out[1];
            delivered = out[2];
        }

        assertEquals(500.0, delivered, 5.0, "Stiff rail must pass full demand");
        assertEquals(capMaxAmps, cap, 1e-9, "Cap must rest at the ceiling on a healthy rail");
    }

    @Test
    public void testRegulatorBranches() {
        // Rail gone: draw nothing.
        assertEquals(0.0, InputPowerRegulator.updateCurrentCap(20.0, 30.0, 0.5, MIN_VIN, TARGET), 1e-9);
        // Deep sag: back off with a 0.15A sensing probe floor.
        assertEquals(17.0, InputPowerRegulator.updateCurrentCap(20.0, 30.0, 10.0, MIN_VIN, TARGET), 1e-9);
        assertEquals(0.15, InputPowerRegulator.updateCurrentCap(0.0, 30.0, 10.0, MIN_VIN, TARGET), 1e-9);
        // Proportional band: linear response toward the target (gentle near the
        // setpoint, up to -50% deep below it so collapses shed load in time).
        assertEquals(2.26 * 0.64, InputPowerRegulator.updateCurrentCap(2.26, 30.0, 16.0, MIN_VIN, TARGET), 1e-9);
        assertEquals(2.26, InputPowerRegulator.updateCurrentCap(2.26, 30.0, TARGET, MIN_VIN, TARGET), 1e-9);
        double over = InputPowerRegulator.updateCurrentCap(2.0, 30.0, 45.0, MIN_VIN, TARGET);
        assertEquals(2.0 * 1.05, over, 1e-9); // (1 + 20/25) clamped to 1.05x
        // Healthy rail: grow toward the ceiling, never past it.
        assertEquals(30.0, InputPowerRegulator.updateCurrentCap(30.0, 30.0, 45.0, MIN_VIN, TARGET), 1e-9);
        double grown = InputPowerRegulator.updateCurrentCap(10.0, 30.0, 45.0, MIN_VIN, TARGET);
        assertTrue(grown > 10.0 && grown <= 30.0, "Healthy rail must grow the cap, was: " + grown);
        // Zero cap on a live rail re-probes instead of deadlocking.
        assertEquals(0.15, InputPowerRegulator.updateCurrentCap(0.0, 30.0, 45.0, MIN_VIN, TARGET), 1e-9);
    }

    @Test
    public void testOutputHungerDetector() {
        // No EMF or no grid: never hungry.
        assertFalse(InputPowerRegulator.isOutputHungry(0.0, true, 0.0));
        assertFalse(InputPowerRegulator.isOutputHungry(28.8, false, 0.0));
        // Terminal inside 90% of EMF: satisfied. Fixed threshold converges —
        // delivery-vs-ceiling comparison would 2-cycle here in every steady
        // state above a fraction of rating.
        assertFalse(InputPowerRegulator.isOutputHungry(28.8, true, 28.5));
        assertFalse(InputPowerRegulator.isOutputHungry(28.8, true, 27.9));
        assertFalse(InputPowerRegulator.isOutputHungry(230.0, true, 229.5));
        assertFalse(InputPowerRegulator.isOutputHungry(230.0, true, 210.0));
        // Terminal sagging below 90% EMF: hungry.
        assertTrue(InputPowerRegulator.isOutputHungry(28.8, true, 24.5));
        assertTrue(InputPowerRegulator.isOutputHungry(28.8, true, 25.5));
        assertTrue(InputPowerRegulator.isOutputHungry(230.0, true, 200.0));
    }

    @Test
    public void testDemandSlewRate() {
        // Uninitialized: ramps from zero (1W first step, never jumps).
        assertEquals(1.0, InputPowerRegulator.slewDemandUp(-1.0, 50.0), 1e-9);
        // Growth bounded at +10% plus 1W under the servo.
        assertEquals(111.0, InputPowerRegulator.slewDemandUp(100.0, 1000.0), 1e-9);
        assertEquals(221.0, InputPowerRegulator.slewDemandUp(200.0, 500.0), 1e-9);
        // Never negative, never above the servo.
        assertEquals(0.0, InputPowerRegulator.slewDemandUp(0.0, -5.0), 1e-9);
        assertEquals(0.0, InputPowerRegulator.slewDemandUp(0.0, -5.0), 1e-9);
    }
}
