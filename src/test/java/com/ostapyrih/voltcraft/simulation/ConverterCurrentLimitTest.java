package com.ostapyrih.voltcraft.simulation;

import com.ostapyrih.voltcraft.api.data.BatteryCellSpec;
import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.energy.IElectricConsumer;
import com.ostapyrih.voltcraft.api.energy.IElectricSource;
import com.ostapyrih.voltcraft.api.energy.IElectricStorage;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalGrid;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves a current-limited converter output (e.g. an MPPT charger fed by weak
 * sun) shares a battery node like real hardware: it pushes exactly what its
 * upstream can sustain, the node stays battery-held, and the battery covers the
 * deficit — instead of the solver splitting the node voltage with the battery
 * and conjuring unbounded charger current.
 */
public class ConverterCurrentLimitTest {

    /** Converter-like source with a settable available-current ceiling (grid auto-detects limiting). */
    static class LimitedSource implements IElectricSource {
        final BlockPos pos;
        final double emf;
        final double rInt;
        final double rated;
        double avail;
        double lastDrawn = Double.NaN;

        LimitedSource(BlockPos pos, double emf, double rInt, double rated, double avail) {
            this.pos = pos;
            this.emf = emf;
            this.rInt = rInt;
            this.rated = rated;
            this.avail = avail;
        }

        @Override public BlockPos getPos() { return pos; }
        @Override public ElectricalState getElectricalState() { return ElectricalState.NOMINAL; }
        @Override public void setElectricalState(ElectricalState s) {}
        @Override public double getElectromotiveForce() { return emf; }
        @Override public double getInternalResistance() { return rInt; }
        @Override public double getMaxOutputCurrent() { return rated; }
        @Override public double getAvailableOutputCurrent() { return avail; }
        @Override public void onPowerDrawn(double currentAmps, double durationSeconds) { this.lastDrawn = currentAmps; }
    }

    /** Fixed-EMF battery mock that records discharge and charge separately. */
    static class BatteryMock implements IElectricStorage {
        final BlockPos pos;
        final double emf;
        final double rInt;
        double lastDrawn = Double.NaN;
        double lastChargeV = Double.NaN;
        double lastChargeI = 0.0;

        BatteryMock(BlockPos pos, double emf, double rInt) {
            this.pos = pos;
            this.emf = emf;
            this.rInt = rInt;
        }

        @Override public BlockPos getPos() { return pos; }
        @Override public ElectricalState getElectricalState() { return ElectricalState.NOMINAL; }
        @Override public void setElectricalState(ElectricalState s) {}
        @Override public double getElectromotiveForce() { return emf; }
        @Override public double getInternalResistance() { return rInt; }
        @Override public double getMaxOutputCurrent() { return 1000.0; }
        @Override public void onPowerDrawn(double currentAmps, double durationSeconds) { this.lastDrawn = currentAmps; }
        @Override public double getNominalPowerDemand() { return 0.0; }
        @Override public double getNominalVoltage() { return emf; }
        @Override public double getMinOperatingVoltage() { return 0.0; }
        @Override public double getMaxOperatingVoltage() { return 1_000_000.0; }
        @Override public double getEquivalentResistance() { return Double.POSITIVE_INFINITY; }
        @Override public void onPowerReceived(double v, double i, double dt) { onPowerReceived(v, i, dt, 0.0); }
        @Override public void onPowerReceived(double v, double i, double dt, double freq) {
            this.lastChargeV = v;
            this.lastChargeI = i;
        }
        @Override public double getStateOfCharge() { return 0.6; }
        @Override public double getStateOfHealth() { return 100.0; }
        @Override public double getMaxStorageJoules() { return 1e7; }
        @Override public double getStoredJoules() { return 6e6; }
        @Override public BatteryCellSpec getChemistrySpec() { return BatteryCellSpec.LI_ION_18650; }
        @Override public void addEnergy(double joules) {}
        @Override public double extractEnergy(double joules) { return 0.0; }
    }

    static class ResistiveLoad implements IElectricConsumer {
        final BlockPos pos;
        final double resistance;
        double lastV = 0.0;
        double lastI = 0.0;

        ResistiveLoad(BlockPos pos, double resistance) {
            this.pos = pos;
            this.resistance = resistance;
        }

        @Override public BlockPos getPos() { return pos; }
        @Override public ElectricalState getElectricalState() { return ElectricalState.NOMINAL; }
        @Override public void setElectricalState(ElectricalState s) {}
        @Override public double getNominalPowerDemand() { return 0.0; }
        @Override public double getNominalVoltage() { return 24.0; }
        @Override public double getMinOperatingVoltage() { return 0.0; }
        @Override public double getMaxOperatingVoltage() { return 1_000_000.0; }
        @Override public double getEquivalentResistance() { return resistance; }
        @Override public void onPowerReceived(double v, double i, double dt) {
            this.lastV = v;
            this.lastI = i;
        }
    }

    @Test
    public void testWeakChargerSharesBatteryNodeWithoutDividingIt() {
        BlockPos node = new BlockPos(0, 64, 0);
        // 24V-class battery node, ~450W inverter-class load, and a charger whose
        // weak sun only sustains 3.4A at 28.8V (~98W) against a 60A hardware rating.
        BatteryMock battery = new BatteryMock(node, 24.5, 0.03);
        LimitedSource charger = new LimitedSource(node, 28.8, 0.05, 60.0, 3.4);
        ResistiveLoad load = new ResistiveLoad(node, 1.3);

        ElectricalGrid grid = new ElectricalGrid();
        grid.registerSource(node, battery);
        grid.registerSource(node, charger);
        grid.registerConsumer(node, load);

        for (int i = 0; i < 5; i++) {
            grid.tick(null);
        }

        // Charger pushes exactly its available current — not ~50A of phantom power
        // a stiff 28.8V/0.05-ohm Thevenin stamp would dump into this node.
        assertEquals(3.4, charger.lastDrawn, 1e-9, "Charger must deliver only its available current");
        // Node stays battery-held (~24.1V), never split toward the charger setpoint (~26.3V).
        assertTrue(load.lastV > 23.5 && load.lastV < 24.6,
            "Node must stay battery-held, was: " + load.lastV + "V");
        // Battery covers the deficit by discharging.
        assertTrue(battery.lastDrawn > 5.0, "Battery must discharge the deficit, drew: " + battery.lastDrawn + "A");
        assertEquals(0.0, battery.lastChargeI, 1e-9, "Battery must not charge while covering a deficit");
    }

    @Test
    public void testStrongChargerHoldsAbsorptionAndChargesBattery() {
        BlockPos node = new BlockPos(0, 64, 0);
        // Same node, but full sun and a nearly-full battery: the charger stays a
        // Thevenin source, the node floats up to the absorption neighborhood, the
        // charger tapers below its rating, and the surplus charges the battery.
        BatteryMock battery = new BatteryMock(node, 28.0, 0.03);
        LimitedSource charger = new LimitedSource(node, 28.8, 0.05, 60.0, 60.0);
        ResistiveLoad load = new ResistiveLoad(node, 10.0);

        ElectricalGrid grid = new ElectricalGrid();
        grid.registerSource(node, battery);
        grid.registerSource(node, charger);
        grid.registerConsumer(node, load);

        for (int i = 0; i < 5; i++) {
            grid.tick(null);
        }

        assertTrue(load.lastV > 28.0 && load.lastV < 28.8,
            "Node must float in the absorption neighborhood, was: " + load.lastV + "V");
        assertTrue(charger.lastDrawn > 5.0 && charger.lastDrawn < 30.0,
            "Charger tapers below rating near full charge, drew: " + charger.lastDrawn + "A");
        assertTrue(battery.lastChargeI > 0.0, "Surplus must charge the battery");
    }

    @Test
    public void testChargerReverseBlockedWhenBatteryHoldsRailAboveSetpoint() {
        BlockPos node = new BlockPos(0, 64, 0);
        // Full 28.4V battery holding the rail over a 27.2V floated charger: the
        // charger output diode blocks, it must deliver nothing, not sink current.
        BatteryMock battery = new BatteryMock(node, 28.4, 0.03);
        LimitedSource charger = new LimitedSource(node, 27.2, 0.05, 60.0, 3.4);
        ResistiveLoad load = new ResistiveLoad(node, 100.0);

        ElectricalGrid grid = new ElectricalGrid();
        grid.registerSource(node, battery);
        grid.registerSource(node, charger);
        grid.registerConsumer(node, load);

        for (int i = 0; i < 5; i++) {
            grid.tick(null);
        }

        assertTrue(load.lastV > 28.0, "Battery must hold the rail, was: " + load.lastV + "V");
        assertEquals(0.0, charger.lastDrawn, 1e-9, "Charger must be reverse-blocked above its setpoint");
    }
}
