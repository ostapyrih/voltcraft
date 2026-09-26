package com.ostapyrih.voltcraft.simulation;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.energy.IElectricConsumer;
import com.ostapyrih.voltcraft.api.energy.IElectricSource;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalGrid;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for starved-source stability: a source that cannot meet demand
 * must current-limit and droop gracefully, and a disabled (EMF=0) source must look
 * like an open circuit instead of shorting the grid and strobing loads on/off.
 */
public class StarvedSourceStabilityTest {

    static class RecorderSource implements IElectricSource {
        final BlockPos pos;
        final double emf;
        final double rInt;
        final double maxI;
        double lastDrawn = Double.NaN;

        RecorderSource(BlockPos pos, double emf, double rInt, double maxI) {
            this.pos = pos;
            this.emf = emf;
            this.rInt = rInt;
            this.maxI = maxI;
        }

        @Override public BlockPos getPos() { return pos; }
        @Override public ElectricalState getElectricalState() { return ElectricalState.NOMINAL; }
        @Override public void setElectricalState(ElectricalState s) {}
        @Override public double getElectromotiveForce() { return emf; }
        @Override public double getInternalResistance() { return rInt; }
        @Override public double getMaxOutputCurrent() { return maxI; }
        @Override public void onPowerDrawn(double currentAmps, double durationSeconds) { this.lastDrawn = currentAmps; }
    }

    static class ResistiveLoad implements IElectricConsumer {
        final BlockPos pos;
        final double resistance;
        double lastV = 0.0;

        ResistiveLoad(BlockPos pos, double resistance) {
            this.pos = pos;
            this.resistance = resistance;
        }

        @Override public BlockPos getPos() { return pos; }
        @Override public ElectricalState getElectricalState() { return ElectricalState.NOMINAL; }
        @Override public void setElectricalState(ElectricalState s) {}
        @Override public double getNominalPowerDemand() { return 0.0; }
        @Override public double getNominalVoltage() { return 48.0; }
        @Override public double getMinOperatingVoltage() { return 0.0; }
        @Override public double getMaxOperatingVoltage() { return 1_000_000.0; }
        @Override public double getEquivalentResistance() { return resistance; }
        @Override public void onPowerReceived(double v, double i, double dt) { this.lastV = v; }
    }

    @Test
    public void testDisabledSourceIsOpenCircuitNotShort() {
        BlockPos node = new BlockPos(0, 64, 0);
        // Healthy 48V source plus a disabled converter-like output (EMF=0, 50mOhm)
        // on the same node, with a 24-ohm load.
        RecorderSource battery = new RecorderSource(node, 48.0, 0.5, 1000.0);
        RecorderSource disabledConverter = new RecorderSource(node, 0.0, 0.05, 50.0);
        ResistiveLoad load = new ResistiveLoad(node, 24.0);

        ElectricalGrid grid = new ElectricalGrid();
        grid.registerSource(node, battery);
        grid.registerSource(node, disabledConverter);
        grid.registerConsumer(node, load);

        for (int i = 0; i < 5; i++) {
            grid.tick(null);
        }

        // Open-circuit disabled source: rail stays near 48V (24/(24+0.5) divider).
        // A 0.05-ohm shunt to ground would have collapsed it to ~4V and strobed loads.
        assertTrue(load.lastV > 40.0,
            "Disabled source must not drag the rail down, load saw: " + load.lastV + "V");
    }

    @Test
    public void testOverloadedSourceCurrentLimitsInsteadOfSourcingUnboundedCurrent() {
        BlockPos node = new BlockPos(0, 64, 0);
        // Stiff 48V source (960A short-circuit) rated for 10A continuous,
        // slammed with a 0.5-ohm overload.
        RecorderSource weakSolar = new RecorderSource(node, 48.0, 0.05, 10.0);
        ResistiveLoad overload = new ResistiveLoad(node, 0.5);

        ElectricalGrid grid = new ElectricalGrid();
        grid.registerSource(node, weakSolar);
        grid.registerConsumer(node, overload);

        grid.tick(null);

        assertTrue(weakSolar.lastDrawn <= 10.0 + 1e-6,
            "Source draw must clamp to rated current, drew: " + weakSolar.lastDrawn + "A");
    }

    @Test
    public void testOverloadedGridSettlesToStableVoltage() {
        BlockPos node = new BlockPos(0, 64, 0);
        RecorderSource weakSolar = new RecorderSource(node, 48.0, 0.05, 10.0);
        ResistiveLoad overload = new ResistiveLoad(node, 0.5);

        ElectricalGrid grid = new ElectricalGrid();
        grid.registerSource(node, weakSolar);
        grid.registerConsumer(node, overload);

        double first = 0.0;
        double maxDeviation = 0.0;
        for (int i = 0; i < 10; i++) {
            grid.tick(null);
            double v = grid.getNodeVoltage(node);
            assertTrue(Double.isFinite(v) && v >= 0.0, "Rail voltage must stay finite, got: " + v);
            if (i == 0) {
                first = v;
            } else {
                maxDeviation = Math.max(maxDeviation, Math.abs(v - first));
            }
        }

        // Pure-resistive MNA has no state: the rail must sit at one stable droop
        // point, never strobe between ticks.
        assertEquals(0.0, maxDeviation, 1e-9, "Overloaded rail must not oscillate between ticks");
    }
}
