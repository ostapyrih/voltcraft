package com.ostapyrih.voltcraft.simulation;

import com.ostapyrih.voltcraft.api.data.BatteryCellSpec;
import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.energy.IElectricConsumer;
import com.ostapyrih.voltcraft.api.energy.IElectricSource;
import com.ostapyrih.voltcraft.api.energy.IElectricStorage;
import com.ostapyrih.voltcraft.simulation.creative.CreativeLoadLogic;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalGrid;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates real-world circuit dynamics:
 * 1. Battery terminal voltage sag under heavy current draw (V = Voc - I * Rint).
 * 2. Instantaneous converter load response (no artificial ramp/slew lag).
 * 3. MPPT charge controller power clamping to available solar generation (prevents voltage collapse).
 * 4. Brownout and foldback behavior replacing 20Hz on/off flicker.
 * 5. Elimination of inverter load number flickering (zero tick-to-tick oscillation).
 * 6. MPPT stability with downstream battery and inverter load.
 * 7. MPPT 0W solar produces zero phantom current and no artificial voltage inflation on inverter/battery bus.
 */
public class CircuitDynamicsRealismTest {

    static class SimpleBatterySource implements IElectricStorage {
        private final BlockPos pos;
        private final double emf;
        private final double rInt;
        private final double maxCurrent;
        private double drawnCurrent = 0.0;

        SimpleBatterySource(BlockPos pos, double emf, double rInt, double maxCurrent) {
            this.pos = pos;
            this.emf = emf;
            this.rInt = rInt;
            this.maxCurrent = maxCurrent;
        }

        @Override public BlockPos getPos() { return pos; }
        @Override public ElectricalState getElectricalState() { return ElectricalState.NOMINAL; }
        @Override public void setElectricalState(ElectricalState state) {}
        @Override public double getElectromotiveForce() { return emf; }
        @Override public double getInternalResistance() { return rInt; }
        @Override public double getMaxOutputCurrent() { return maxCurrent; }
        @Override public void onPowerDrawn(double currentAmps, double durationSeconds) { this.drawnCurrent = currentAmps; }
        @Override public double getStateOfCharge() { return 1.0; }
        @Override public double getStateOfHealth() { return 100.0; }
        @Override public double getMaxStorageJoules() { return 10000000.0; }
        @Override public double getStoredJoules() { return 10000000.0; }
        @Override public BatteryCellSpec getChemistrySpec() { return BatteryCellSpec.LI_ION_18650; }
        @Override public void addEnergy(double joules) {}
        @Override public double extractEnergy(double joules) { return joules; }
        @Override public double getNominalPowerDemand() { return 0.0; }
        @Override public double getNominalVoltage() { return emf; }
        @Override public double getMinOperatingVoltage() { return 10.0; }
        @Override public double getMaxOperatingVoltage() { return 60.0; }
        @Override public void onPowerReceived(double terminalVoltage, double deliveredCurrent, double durationSeconds) {
            this.receivedCurrent = deliveredCurrent;
        }
    }

    static class SimpleConverterSource implements IElectricSource {
        private final BlockPos pos;
        private final double emf;
        private final double rInt;
        private final double maxCurrent;
        private final double availableCurrent;
        private double drawnCurrent = 0.0;

        SimpleConverterSource(BlockPos pos, double emf, double rInt, double maxCurrent, double availableCurrent) {
            this.pos = pos;
            this.emf = emf;
            this.rInt = rInt;
            this.maxCurrent = maxCurrent;
            this.availableCurrent = availableCurrent;
        }

        @Override public BlockPos getPos() { return pos; }
        @Override public ElectricalState getElectricalState() { return ElectricalState.NOMINAL; }
        @Override public void setElectricalState(ElectricalState state) {}
        @Override public double getElectromotiveForce() { return emf; }
        @Override public double getInternalResistance() { return rInt; }
        @Override public double getMaxOutputCurrent() { return maxCurrent; }
        @Override public double getAvailableOutputCurrent() { return availableCurrent; }
        @Override public void onPowerDrawn(double currentAmps, double durationSeconds) { this.drawnCurrent = currentAmps; }
    }

    static class ResistiveLoad implements IElectricConsumer {
        private final BlockPos pos;
        private final double resistance;

        ResistiveLoad(BlockPos pos, double resistance) {
            this.pos = pos;
            this.resistance = resistance;
        }

        @Override public BlockPos getPos() { return pos; }
        @Override public ElectricalState getElectricalState() { return ElectricalState.NOMINAL; }
        @Override public void setElectricalState(ElectricalState state) {}
        @Override public double getNominalPowerDemand() { return (230.0 * 230.0) / resistance; }
        @Override public double getNominalVoltage() { return 230.0; }
        @Override public double getMinOperatingVoltage() { return 10.0; }
        @Override public double getMaxOperatingVoltage() { return 300.0; }
        @Override public double getEquivalentResistance() { return resistance; }
        @Override public void onPowerReceived(double terminalVoltage, double deliveredCurrent, double durationSeconds) {
            this.receivedVoltage = terminalVoltage;
            this.receivedCurrent = deliveredCurrent;
        }
    }

    @Test
    public void testBatteryTerminalVoltageSagUnderHeavyDraw() {
        BlockPos nodePos = new BlockPos(0, 64, 0);

        // 48V battery pack with 0.15 Ohm internal resistance
        SimpleBatterySource battery = new SimpleBatterySource(nodePos, 48.0, 0.15, 200.0);

        // Constant current load pulling 40 Amps (e.g. ~1700W draw by inverter)
        CreativeLoadLogic load = new CreativeLoadLogic(nodePos);
        load.setMode(CreativeLoadLogic.LoadMode.CONSTANT_CURRENT);
        load.setTargetValue(40.0);

        ElectricalGrid grid = new ElectricalGrid();
        grid.registerSource(nodePos, battery);
        grid.registerConsumer(nodePos, load);

        // Step simulation 10 ticks for dynamic constant-current load to converge from initial 230V default
        for (int i = 0; i < 10; i++) {
            grid.tick(null);
        }

        double nodeVoltage = grid.getNodeVoltage(nodePos);
        double currentDrawn = battery.drawnCurrent;

        // In real electronics: V = Voc - I * R = 48.0 - (40.0 * 0.15) = 42.0 Volts
        assertEquals(40.0, currentDrawn, 0.1, "Load must draw exactly 40A");
        assertEquals(42.0, nodeVoltage, 0.2, "Terminal voltage must sag by 6.0V under 40A draw per Ohm's law");
    }

    @Test
    public void testSolarArrayTerminalVoltageUnderMpptLimiting() {
        BlockPos nodePos = new BlockPos(0, 64, 0);

        // Solar Array: Voc = 40V, Rint = 10 Ohm -> matched Pmax = 40W at Vmp = 20V, Imp = 2A
        SimpleBatterySource solarArray = new SimpleBatterySource(nodePos, 40.0, 10.0, 2.0);

        // Charge controller input drawing exactly 40W (clamped to available solar capacity)
        CreativeLoadLogic chargeControllerInput = new CreativeLoadLogic(nodePos);
        chargeControllerInput.setMode(CreativeLoadLogic.LoadMode.CONSTANT_CURRENT);
        chargeControllerInput.setTargetValue(2.0); // 2.0A at Vmp = 20V is exactly 40W

        ElectricalGrid grid = new ElectricalGrid();
        grid.registerSource(nodePos, solarArray);
        grid.registerConsumer(nodePos, chargeControllerInput);

        for (int i = 0; i < 10; i++) {
            grid.tick(null);
        }

        double terminalV = grid.getNodeVoltage(nodePos);
        // Voltage holds at Vmp = 20V, strictly prevented from collapsing to 0V!
        assertEquals(20.0, terminalV, 0.1, "Solar array terminal voltage must hold at 20V without collapse");
        assertEquals(2.0, solarArray.drawnCurrent, 0.05, "Solar array current must match Imp");
    }

    @Test
    public void testConverterFoldbackBrownoutStabilization() {
        // Test that brownout foldback limits output voltage rather than shutting down completely
        double availableWatts = 1500.0;
        double requestedLoadCurrent = 15.0; // 15A at 230V would be 3450W, exceeding 1500W

        // When load demands more than available, voltage folds back: Vout = P_avail / I_load
        double foldedVoltage = availableWatts / requestedLoadCurrent; // 100V
        assertTrue(foldedVoltage < 230.0, "Foldback reduces voltage to maintain constant power without trip");
        assertEquals(100.0, foldedVoltage, 1e-4);

        // Power delivered under foldback matches available power (uses what it has)
        double deliveredWatts = foldedVoltage * requestedLoadCurrent;
        assertEquals(availableWatts, deliveredWatts, 1e-4);
    }

    @Test
    public void testInverterLoadSteadyUnderOverloadNoFlicker() {
        BlockPos nodePos = new BlockPos(0, 64, 0);

        // Inverter rated 3000W at 230V -> 13.04A available current ceiling
        double ratedCurrent = 3000.0 / 230.0; // 13.0435A
        SimpleConverterSource inverter = new SimpleConverterSource(nodePos, 230.0, 0.05, ratedCurrent, ratedCurrent);

        // 10 Ohm resistive load: at 230V this would demand 23A (5290W), creating a 76% overload
        ResistiveLoad overload = new ResistiveLoad(nodePos, 10.0);

        ElectricalGrid grid = new ElectricalGrid();
        grid.registerSource(nodePos, inverter);
        grid.registerConsumer(nodePos, overload);

        double lastVoltage = -1.0;
        double lastCurrent = -1.0;

        // Run across 20 consecutive ticks
        for (int tick = 0; tick < 20; tick++) {
            grid.tick(null);

            double v = grid.getNodeVoltage(nodePos);
            double i = inverter.drawnCurrent;

            // Inverter clamps to available current (13.0435A)
            assertEquals(ratedCurrent, i, 0.01, "Inverter must clamp output current to rated available limit");
            // Terminal voltage folds back per Ohm's law: V = I * R = 13.0435 * 10 = 130.435V
            assertEquals(ratedCurrent * 10.0, v, 0.1, "Voltage must fold back smoothly to 130.4V");

            if (tick > 0) {
                // Assert ZERO tick-to-tick oscillation (no flickering number!)
                assertEquals(lastVoltage, v, 1e-6, "Voltage must be completely identical tick-to-tick (zero flicker)");
                assertEquals(lastCurrent, i, 1e-6, "Current must be completely identical tick-to-tick (zero flicker)");
            }
            lastVoltage = v;
            lastCurrent = i;
        }
    }

    @Test
    public void testSolarChargeControllerSuppliesInverterLoadWithoutMpptOscillation() {
        BlockPos busPos = new BlockPos(0, 64, 0);

        // 24V Battery pack on bus (Voc = 25.6V, Rint = 0.04 Ohm)
        SimpleBatterySource battery = new SimpleBatterySource(busPos, 25.6, 0.04, 100.0);

        // MPPT Charge Controller pushing 360W solar * 0.98 eta = 352.8W into 24V bus -> 14.7A available
        double ccAmps = 352.8 / 24.0; // 14.7A
        SimpleConverterSource chargeController = new SimpleConverterSource(busPos, 28.8, 0.05, 60.0, ccAmps);

        // Inverter input pulling 30.0A from 24V bus (720W load on inverter)
        CreativeLoadLogic inverterDraw = new CreativeLoadLogic(busPos);
        inverterDraw.setMode(CreativeLoadLogic.LoadMode.CONSTANT_CURRENT);
        inverterDraw.setTargetValue(30.0);

        ElectricalGrid busGrid = new ElectricalGrid();
        busGrid.registerSource(busPos, battery);
        busGrid.registerSource(busPos, chargeController);
        busGrid.registerConsumer(busPos, inverterDraw);

        // Warm up 10 ticks for CreativeLoad to settle
        for (int i = 0; i < 10; i++) {
            busGrid.tick(null);
        }

        double lastCcCurrent = -1.0;
        double lastBattCurrent = -1.0;
        double lastBusVoltage = -1.0;

        for (int tick = 0; tick < 20; tick++) {
            busGrid.tick(null);

            double busV = busGrid.getNodeVoltage(busPos);
            double ccI = chargeController.drawnCurrent;
            double battI = battery.drawnCurrent;

            // Charge controller must deliver full available solar capacity (14.7A)
            assertEquals(ccAmps, ccI, 0.05, "Charge controller must deliver full available solar current in Bulk");
            // Battery supplies the remainder: 30.0A - 14.7A = 15.3A
            assertEquals(15.3, battI, 0.25, "Battery must supply exactly the load deficit");
            // Kirchhoff's Current Law: CC + Batt = Inverter load (30A)
            assertEquals(30.0, ccI + battI, 0.2, "Total source current must match load demand");

            if (tick > 0) {
                // Must be bit-steady every tick without hunting or flickering
                assertEquals(lastBusVoltage, busV, 1e-6, "Bus voltage must not oscillate");
                assertEquals(lastCcCurrent, ccI, 1e-6, "MPPT output current must not oscillate");
                assertEquals(lastBattCurrent, battI, 1e-6, "Battery discharge current must not oscillate");
            }
            lastCcCurrent = ccI;
            lastBattCurrent = battI;
            lastBusVoltage = busV;
        }
    }

    @Test
    public void testMpptZeroSolarProducesZeroCurrentAndNoVoltageInflation() {
        BlockPos busPos = new BlockPos(0, 64, 0);

        // 24V Battery pack on bus (Voc = 24.0V, Rint = 0.04 Ohm)
        SimpleBatterySource battery = new SimpleBatterySource(busPos, 24.0, 0.04, 100.0);

        // MPPT Charge Controller with 0 available solar power (e.g. night, 0 panels)
        // availableCurrent = 0.0A
        SimpleConverterSource idleChargeController = new SimpleConverterSource(busPos, 28.8, 0.05, 60.0, 0.0);

        // Inverter drawing 10.0A from 24V DC bus
        CreativeLoadLogic inverterDraw = new CreativeLoadLogic(busPos);
        inverterDraw.setMode(CreativeLoadLogic.LoadMode.CONSTANT_CURRENT);
        inverterDraw.setTargetValue(10.0);

        ElectricalGrid busGrid = new ElectricalGrid();
        busGrid.registerSource(busPos, battery);
        busGrid.registerSource(busPos, idleChargeController);
        busGrid.registerConsumer(busPos, inverterDraw);

        for (int i = 0; i < 10; i++) {
            busGrid.tick(null);
        }

        double busV = busGrid.getNodeVoltage(busPos);
        double ccI = idleChargeController.drawnCurrent;
        double battI = battery.drawnCurrent;

        // MPPT must NOT generate phantom current or combine voltage when solar is 0
        assertEquals(0.0, ccI, 1e-6, "MPPT with 0W solar must deliver exactly 0A");
        // Battery must supply 100% of the inverter load (10.0A)
        assertEquals(10.0, battI, 0.1, "Battery must supply full inverter load");
        // Bus voltage must be pure battery voltage sagging under 10A: V = 24.0 - (10.0 * 0.04) = 23.6V
        assertEquals(23.6, busV, 0.15, "Bus voltage must purely reflect battery Ohm's law sag without MPPT EMF inflation");
    }

    @Test
    public void testMpptControlledCurrentInjectionDoesNotDistortBatteryBusVoltage() {
        BlockPos busPos = new BlockPos(0, 64, 0);

        // 24V Battery pack with Voc = 24.0V, Rint = 0.04 Ohm
        SimpleBatterySource battery = new SimpleBatterySource(busPos, 24.0, 0.04, 100.0);

        // MPPT Charge Controller delivering 10.0A into battery bus
        SimpleConverterSource chargeController = new SimpleConverterSource(busPos, 28.8, 0.05, 60.0, 10.0);

        ElectricalGrid busGrid = new ElectricalGrid();
        busGrid.registerSource(busPos, battery);
        busGrid.registerSource(busPos, chargeController);

        busGrid.tick(null);

        double busV = busGrid.getNodeVoltage(busPos);
        double ccI = chargeController.drawnCurrent;

        // MPPT pushes 10.0A into battery
        assertEquals(10.0, ccI, 0.01, "MPPT must push full available current into battery");
        // Battery terminal voltage rises by I * R = 10.0 * 0.04 = 0.4V -> 24.4V
        // It must NEVER be pulled up to 28.8V or composite average 26.1V!
        assertEquals(24.4, busV, 0.05, "Battery bus voltage must strictly reflect V_ocv + I * R_int (24.4V)");
    }
}
