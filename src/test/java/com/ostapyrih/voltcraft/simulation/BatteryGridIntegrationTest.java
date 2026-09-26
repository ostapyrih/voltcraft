package com.ostapyrih.voltcraft.simulation;

import com.ostapyrih.voltcraft.api.data.BatteryCellSpec;
import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.energy.IElectricConsumer;
import com.ostapyrih.voltcraft.api.energy.IElectricSource;
import com.ostapyrih.voltcraft.api.energy.IElectricStorage;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.item.battery.BatteryCellItem;
import com.ostapyrih.voltcraft.simulation.chemistry.BatteryChemistry;
import com.ostapyrih.voltcraft.simulation.chemistry.BatterySimulation;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalGrid;
import com.ostapyrih.voltcraft.simulation.grid.GridConductor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class BatteryGridIntegrationTest {

    static class MockBatteryStorage implements IElectricStorage {
        final BlockPos pos;
        final BatteryChemistry chemistry;
        final int seriesCount;
        final int parallelCount;

        double stateOfCharge = 1.0;
        double stateOfHealth = 1.0;
        double temperatureCelsius = 20.0;
        ElectricalState state = ElectricalState.NOMINAL;
        boolean exploded = false;

        MockBatteryStorage(BlockPos pos, BatteryChemistry chemistry, int seriesCount, int parallelCount) {
            this.pos = pos;
            this.chemistry = chemistry;
            this.seriesCount = seriesCount;
            this.parallelCount = parallelCount;
        }

        @Override public BlockPos getPos() { return pos; }
        @Override public ElectricalState getElectricalState() { return state; }
        @Override public void setElectricalState(ElectricalState s) { this.state = s; }
        @Override public double getStateOfCharge() { return stateOfCharge; }
        @Override public double getStateOfHealth() { return stateOfHealth * 100.0; }
        @Override public double getMaxStorageJoules() {
            return chemistry.getCapacityAmpHours() * parallelCount * seriesCount * chemistry.getNominalVoltage() * 3600.0;
        }
        @Override public double getStoredJoules() { return getMaxStorageJoules() * stateOfCharge; }
        @Override public BatteryCellSpec getChemistrySpec() { return BatteryCellSpec.LI_ION_18650; }
        @Override public void addEnergy(double joules) {}
        @Override public double extractEnergy(double joules) { return 0; }

        @Override
        public double getElectromotiveForce() {
            if (stateOfCharge <= 0.001) return 0.0;
            return seriesCount * BatterySimulation.getOpenCircuitVoltage(chemistry, stateOfCharge);
        }

        @Override
        public double getInternalResistance() {
            double cellR = BatterySimulation.getInternalResistance(chemistry, stateOfCharge, temperatureCelsius, stateOfHealth);
            return (cellR * seriesCount) / (double) parallelCount;
        }

        @Override
        public double getMaxOutputCurrent() {
            if (stateOfCharge <= 0.001) return 0.0;
            return parallelCount * chemistry.getMaxDischargeCurrentAmps();
        }

        @Override
        public void onPowerDrawn(double currentAmps, double durationSeconds) {
            if (currentAmps <= 0.0 || stateOfCharge <= 0.001) {
                if (temperatureCelsius > 20.0) {
                    double cooling = 0.5 * (temperatureCelsius - 20.0);
                    this.temperatureCelsius = Math.max(20.0, this.temperatureCelsius - (cooling * durationSeconds));
                }
                return;
            }

            double cellCurrent = currentAmps / (double) parallelCount;
            BatterySimulation.SimulationStepResult result = BatterySimulation.step(
                chemistry,
                cellCurrent,
                durationSeconds,
                stateOfCharge,
                temperatureCelsius,
                stateOfHealth,
                20.0
            );

            this.stateOfCharge = result.newSoc();
            this.temperatureCelsius = result.newTemperatureCelsius();
            this.stateOfHealth = result.newHealth();

            if (result.thermalRunaway()) {
                this.state = ElectricalState.DESTROYED;
                this.exploded = true;
            }
        }

        @Override public double getNominalPowerDemand() { return 0; }
        @Override public double getNominalVoltage() { return seriesCount * chemistry.getNominalVoltage(); }
        @Override public double getMinOperatingVoltage() { return seriesCount * chemistry.getCutoffVoltage(); }
        @Override public double getMaxOperatingVoltage() { return seriesCount * chemistry.getFullChargeVoltage(); }
        @Override public double getFrequency() { return 0.0; }
        @Override public void onPowerReceived(double v, double i, double dt) {
            onPowerReceived(v, i, dt, 0.0);
        }
        @Override public void onPowerReceived(double v, double i, double dt, double frequencyHz) {
            if (i <= 0.0 || state == ElectricalState.DESTROYED) return;
            if (frequencyHz > 0.001) {
                double cellCurrent = i / (double) parallelCount;
                double cellR = BatterySimulation.getInternalResistance(chemistry, stateOfCharge, temperatureCelsius, stateOfHealth);
                double heatW = (cellCurrent * cellCurrent * cellR) * seriesCount * parallelCount;
                double mass = chemistry.getCapacityAmpHours() <= 5.0 ? 40.0 : (chemistry.getCapacityAmpHours() * 20.0);
                this.temperatureCelsius += (heatW / Math.max(10.0, mass * seriesCount * parallelCount)) * dt;
                if (this.temperatureCelsius >= chemistry.getThermalRunawayTempCelsius()) {
                    this.state = ElectricalState.DESTROYED;
                    this.exploded = true;
                }
                return;
            }

            // Thermodynamic check: applied charging voltage must strictly exceed EMF
            if (v <= getElectromotiveForce()) {
                return;
            }

            // Overvoltage check
            if (v > getMaxOperatingVoltage() * 1.05) {
                double overvoltage = v - getMaxOperatingVoltage();
                double cellOvervoltage = overvoltage / (double) seriesCount;
                double cellCurrent = i / (double) parallelCount;
                double heatW = (cellOvervoltage * cellCurrent) * seriesCount * parallelCount;
                double mass = chemistry.getCapacityAmpHours() <= 5.0 ? 40.0 : (chemistry.getCapacityAmpHours() * 20.0);
                this.temperatureCelsius += (heatW / Math.max(10.0, mass * seriesCount * parallelCount)) * dt;
                if (this.temperatureCelsius >= chemistry.getThermalRunawayTempCelsius()) {
                    this.state = ElectricalState.DESTROYED;
                    this.exploded = true;
                }
                return;
            }

            double cellCurrent = -(i / (double) parallelCount);
            BatterySimulation.SimulationStepResult result = BatterySimulation.step(
                chemistry, cellCurrent, dt, stateOfCharge, temperatureCelsius, stateOfHealth, 20.0
            );
            this.stateOfCharge = result.newSoc();
            this.temperatureCelsius = result.newTemperatureCelsius();
            this.stateOfHealth = result.newHealth();
            if (result.thermalRunaway()) {
                this.state = ElectricalState.DESTROYED;
                this.exploded = true;
            }
        }
    }

    static class MockSource implements IElectricSource {
        final BlockPos pos;
        final double emf;
        final double freq;
        final double rInt;
        MockSource(BlockPos pos, double emf, double freq, double rInt) {
            this.pos = pos;
            this.emf = emf;
            this.freq = freq;
            this.rInt = rInt;
        }
        @Override public BlockPos getPos() { return pos; }
        @Override public ElectricalState getElectricalState() { return ElectricalState.NOMINAL; }
        @Override public void setElectricalState(ElectricalState s) {}
        @Override public double getElectromotiveForce() { return emf; }
        @Override public double getInternalResistance() { return rInt; }
        @Override public double getMaxOutputCurrent() { return 1000.0; }
        @Override public double getFrequency() { return freq; }
        @Override public void onPowerDrawn(double currentAmps, double durationSeconds) {}
    }

    static class MockResistiveLoad implements IElectricConsumer {
        final BlockPos pos;
        final double resistance;
        double receivedV = 0.0;
        double receivedI = 0.0;
        ElectricalState state = ElectricalState.NOMINAL;

        MockResistiveLoad(BlockPos pos, double resistance) {
            this.pos = pos;
            this.resistance = resistance;
        }

        @Override public BlockPos getPos() { return pos; }
        @Override public ElectricalState getElectricalState() { return state; }
        @Override public void setElectricalState(ElectricalState s) { this.state = s; }
        @Override public double getNominalPowerDemand() { return (48.0 * 48.0) / resistance; }
        @Override public double getNominalVoltage() { return 48.0; }
        @Override public double getMinOperatingVoltage() { return 30.0; }
        @Override public double getMaxOperatingVoltage() { return 60.0; }
        @Override public double getEquivalentResistance() { return resistance; }
        @Override
        public void onPowerReceived(double terminalVoltage, double deliveredCurrent, double durationSeconds) {
            this.receivedV = terminalVoltage;
            this.receivedI = deliveredCurrent;
        }
    }

    @Test
    public void testUnloadedBatteryRemainsStableAndCool() {
        BlockPos batteryPos = new BlockPos(10, 64, 10);
        MockBatteryStorage battery = new MockBatteryStorage(batteryPos, BatteryChemistry.LIFEPO4, 15, 1);

        ElectricalGrid grid = new ElectricalGrid();
        grid.registerSource(batteryPos, battery);

        for (int i = 0; i < 200; i++) {
            grid.tick(null);
        }

        assertFalse(battery.exploded, "Unloaded battery must never explode");
        assertEquals(1.0, battery.getStateOfCharge(), 1e-6, "Unloaded battery must not self-discharge");
        assertEquals(20.0, battery.temperatureCelsius, 1e-4, "Unloaded battery must remain at 20C ambient");
        assertEquals(0.0, grid.getTotalGenerationWatts(), 1e-6, "Grid generation under no-load must be 0W");
        assertEquals(battery.getElectromotiveForce(), grid.getNodeVoltage(batteryPos), 1e-3);
    }

    @Test
    public void testHotBatteryPassivelyCoolsDown() {
        BlockPos batteryPos = new BlockPos(10, 64, 10);
        MockBatteryStorage battery = new MockBatteryStorage(batteryPos, BatteryChemistry.LIFEPO4, 15, 1);
        battery.temperatureCelsius = 80.0;

        ElectricalGrid grid = new ElectricalGrid();
        grid.registerSource(batteryPos, battery);

        for (int i = 0; i < 200; i++) {
            grid.tick(null);
        }

        assertTrue(battery.temperatureCelsius < 80.0, "Idle battery must passively cool down");
        assertFalse(battery.exploded);
    }

    @Test
    public void testLoadedBatteryDischargesCorrectPhysicalCurrent() {
        BlockPos batteryPos = new BlockPos(0, 64, 0);
        BlockPos loadPos = new BlockPos(1, 64, 0);

        MockBatteryStorage battery = new MockBatteryStorage(batteryPos, BatteryChemistry.LIFEPO4, 15, 1);
        MockResistiveLoad load = new MockResistiveLoad(loadPos, 10.0);

        ElectricalGrid grid = new ElectricalGrid();
        grid.registerSource(batteryPos, battery);
        grid.registerConsumer(loadPos, load);
        grid.addConductor(new GridConductor(batteryPos, loadPos, ConductorType.HEAVY_COPPER.toThermalSpec(), 100.0, true));

        double initialSoc = battery.getStateOfCharge();

        for (int i = 0; i < 100; i++) {
            grid.tick(null);
        }

        assertTrue(load.receivedI > 4.5 && load.receivedI < 5.5, "Expected ~5.4A through 10 Ohm load, got: " + load.receivedI);
        assertTrue(battery.getStateOfCharge() < initialSoc, "Battery must discharge under load");
        assertTrue(battery.getStateOfCharge() > 0.99, "5s under 5A should discharge only ~0.007% of 100Ah");
        assertTrue(battery.temperatureCelsius > 20.0 && battery.temperatureCelsius < 25.0,
            "Battery should only slightly warm under ~5A (0.05C rate), got: " + battery.temperatureCelsius);
        assertFalse(battery.exploded);
    }

    @Test
    public void testDischargedBatteryStopsDeliveringPowerAndWattsDropToZero() {
        BlockPos batteryPos = new BlockPos(0, 64, 0);
        BlockPos loadPos = new BlockPos(1, 64, 0);

        MockBatteryStorage battery = new MockBatteryStorage(batteryPos, BatteryChemistry.LI_ION_18650, 16, 1);
        MockResistiveLoad load = new MockResistiveLoad(loadPos, 10.0);

        ElectricalGrid grid = new ElectricalGrid();
        grid.registerSource(batteryPos, battery);
        grid.registerConsumer(loadPos, load);
        grid.addConductor(new GridConductor(batteryPos, loadPos, ConductorType.HEAVY_COPPER.toThermalSpec(), 100.0, true));

        // When charged, battery drives load
        grid.tick(null);
        assertTrue(grid.getTotalGenerationWatts() > 100.0, "Charged battery must generate watts");
        assertTrue(load.receivedI > 0.0);

        // When completely discharged (SoC = 0.0)
        battery.stateOfCharge = 0.0;
        assertEquals(0.0, battery.getElectromotiveForce(), 1e-6, "Discharged battery EMF must be strictly 0.0V");
        assertEquals(0.0, battery.getMaxOutputCurrent(), 1e-6, "Discharged battery max current must be 0.0A");

        grid.tick(null);

        assertEquals(0.0, grid.getTotalGenerationWatts(), 1e-6, "Grid generation watts must drop strictly to 0W after discharge");
        assertEquals(0.0, load.receivedV, 1e-6, "Load terminal voltage must drop to 0V");
        assertEquals(0.0, load.receivedI, 1e-6, "Load current must drop to 0A");
    }

    @Test
    public void testBatteryDirectAcConnectionDoesNotChargeAndTriggersThermalRunaway() {
        BlockPos batteryPos = new BlockPos(0, 64, 0);
        BlockPos acSourcePos = new BlockPos(1, 64, 0);

        MockBatteryStorage battery = new MockBatteryStorage(batteryPos, BatteryChemistry.LI_ION_18650, 16, 1);
        battery.stateOfCharge = 0.5;

        MockSource acSource = new MockSource(acSourcePos, 230.0, 50.0, 0.05);

        ElectricalGrid grid = new ElectricalGrid();
        grid.registerSource(batteryPos, battery);
        grid.registerSource(acSourcePos, acSource);
        grid.addConductor(new GridConductor(batteryPos, acSourcePos, ConductorType.HEAVY_COPPER.toThermalSpec(), 100.0, true));

        grid.tick(null);

        assertEquals(50.0, grid.getFrequency(), 1e-3, "Grid operating frequency must be detected as 50Hz AC");
        assertEquals(0.5, battery.getStateOfCharge(), 1e-6, "Battery SoC must NOT increase when exposed to AC (0 net chemical charge)");
        assertTrue(battery.temperatureCelsius > 20.0, "Battery must experience severe AC Joule heating");

        for (int i = 0; i < 50; i++) {
            grid.tick(null);
            if (battery.exploded) break;
        }

        assertTrue(battery.exploded, "Battery must explode/trigger thermal runaway from continuous unrectified AC heating");
        assertEquals(ElectricalState.DESTROYED, battery.getElectricalState());
    }

    @Test
    public void testBatteryDirectDcConnectionSafelyCharges() {
        BlockPos batteryPos = new BlockPos(0, 64, 0);
        BlockPos dcSourcePos = new BlockPos(1, 64, 0);

        MockBatteryStorage battery = new MockBatteryStorage(batteryPos, BatteryChemistry.LI_ION_18650, 16, 1);
        battery.stateOfCharge = 0.5;

        MockSource dcCharger = new MockSource(dcSourcePos, 67.2, 0.0, 1.0);

        ElectricalGrid grid = new ElectricalGrid();
        grid.registerSource(batteryPos, battery);
        grid.registerSource(dcSourcePos, dcCharger);
        grid.addConductor(new GridConductor(batteryPos, dcSourcePos, ConductorType.HEAVY_COPPER.toThermalSpec(), 100.0, true));

        grid.tick(null);

        assertEquals(0.0, grid.getFrequency(), 1e-3, "Grid operating frequency must be 0Hz (DC)");
        assertTrue(battery.getStateOfCharge() > 0.5, "Battery must safely charge when connected to a DC source");
        assertFalse(battery.exploded, "Battery must not explode under controlled DC charging");
    }

    @Test
    public void testBatteryRejectsChargingWhenVoltageBelowEmf() {
        BlockPos batteryPos = new BlockPos(0, 64, 0);
        BlockPos dcSourcePos = new BlockPos(1, 64, 0);

        MockBatteryStorage battery = new MockBatteryStorage(batteryPos, BatteryChemistry.LI_ION_18650, 16, 1);
        battery.stateOfCharge = 0.8;
        double initialSoc = battery.getStateOfCharge();
        double packEmf = battery.getElectromotiveForce(); // ~63V

        // Direct call to onPowerReceived with voltage less than EMF must be rejected (0 charge gain)
        battery.onPowerReceived(packEmf - 5.0, 10.0, 1.0, 0.0);
        assertEquals(initialSoc, battery.getStateOfCharge(), 1e-6, "onPowerReceived must reject charging when voltage is below EMF");

        // In grid: charger voltage (50V) lower than battery EMF (~63V)
        MockSource lowCharger = new MockSource(dcSourcePos, 50.0, 0.0, 1.0);

        ElectricalGrid grid = new ElectricalGrid();
        grid.registerSource(batteryPos, battery);
        grid.registerSource(dcSourcePos, lowCharger);
        grid.addConductor(new GridConductor(batteryPos, dcSourcePos, ConductorType.HEAVY_COPPER.toThermalSpec(), 100.0, true));

        grid.tick(null);

        // In the grid, the battery discharges into the low-voltage source, never charges
        assertTrue(battery.getStateOfCharge() <= initialSoc, "Battery SoC must never increase when charger voltage is below battery EMF");
    }

    @Test
    public void testBatteryOvervoltageTriggersThermalRunaway() {
        BlockPos batteryPos = new BlockPos(0, 64, 0);
        BlockPos dcSourcePos = new BlockPos(1, 64, 0);

        MockBatteryStorage battery = new MockBatteryStorage(batteryPos, BatteryChemistry.LI_ION_18650, 16, 1);
        battery.stateOfCharge = 0.5;

        // Extreme overvoltage (150V into ~67V max pack)
        MockSource highCharger = new MockSource(dcSourcePos, 150.0, 0.0, 0.1);

        ElectricalGrid grid = new ElectricalGrid();
        grid.registerSource(batteryPos, battery);
        grid.registerSource(dcSourcePos, highCharger);
        grid.addConductor(new GridConductor(batteryPos, dcSourcePos, ConductorType.HEAVY_COPPER.toThermalSpec(), 500.0, true));

        for (int i = 0; i < 200; i++) {
            grid.tick(null);
            if (battery.exploded) break;
        }

        assertTrue(battery.exploded, "Extreme overvoltage charging must trigger thermal runaway and destroy the battery");
        assertEquals(ElectricalState.DESTROYED, battery.getElectricalState());
    }
}
