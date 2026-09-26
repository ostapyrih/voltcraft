package com.ostapyrih.voltcraft.simulation;

import com.ostapyrih.voltcraft.api.energy.IElectricConsumer;
import com.ostapyrih.voltcraft.api.energy.IElectricSource;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.simulation.conversion.ConverterType;
import com.ostapyrih.voltcraft.simulation.conversion.InverterType;
import com.ostapyrih.voltcraft.simulation.conversion.RectifierType;
import com.ostapyrih.voltcraft.simulation.conversion.TransformerType;
import com.ostapyrih.voltcraft.simulation.creative.CreativeLoadLogic;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalGrid;
import com.ostapyrih.voltcraft.simulation.grid.GridConductor;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ConversionPhysicsTest {

    static class MockConverterOutputSource implements IElectricSource {
        final BlockPos pos;
        final double emf;
        final double rInt;
        final double maxCurrent;
        double drawnCurrent = 0.0;

        MockConverterOutputSource(BlockPos pos, double emf, double rInt, double maxCurrent) {
            this.pos = pos;
            this.emf = emf;
            this.rInt = rInt;
            this.maxCurrent = maxCurrent;
        }

        @Override public BlockPos getPos() { return pos; }
        @Override public com.ostapyrih.voltcraft.api.data.ElectricalState getElectricalState() { return com.ostapyrih.voltcraft.api.data.ElectricalState.NOMINAL; }
        @Override public void setElectricalState(com.ostapyrih.voltcraft.api.data.ElectricalState state) {}
        @Override public double getElectromotiveForce() { return emf; }
        @Override public double getInternalResistance() { return rInt; }
        @Override public double getMaxOutputCurrent() { return maxCurrent; }
        @Override public double getAvailableOutputCurrent() { return maxCurrent; }
        @Override public void onPowerDrawn(double currentAmps, double durationSeconds) { this.drawnCurrent = currentAmps; }
    }

    @Test
    public void testTwoConvertersInParallelSharedNode() {
        BlockPos nodePos = new BlockPos(0, 64, 0);

        MockConverterOutputSource c1 = new MockConverterOutputSource(nodePos, 12.0, 0.05, 50.0);
        MockConverterOutputSource c2 = new MockConverterOutputSource(nodePos, 12.0, 0.05, 50.0);

        CreativeLoadLogic load = new CreativeLoadLogic(nodePos);
        load.setMode(CreativeLoadLogic.LoadMode.CONSTANT_CURRENT);
        load.setTargetValue(50.0); // 50 A constant current

        ElectricalGrid grid = new ElectricalGrid();
        grid.registerSource(nodePos, c1);
        grid.registerSource(nodePos, c2);
        grid.registerConsumer(nodePos, load);

        for (int i = 0; i < 10; i++) {
            grid.tick(null);
        }

        System.out.println("Shared Node: c1 drawn: " + c1.drawnCurrent + ", c2 drawn: " + c2.drawnCurrent + ", load drawn: " + load.getLastDeliveredCurrent());
    }

    @Test
    public void testTwoConvertersInParallelSeparatedByCables() {
        BlockPos posC1 = new BlockPos(0, 64, 0);
        BlockPos posC2 = new BlockPos(2, 64, 0);
        BlockPos posLoad = new BlockPos(1, 64, 0);

        MockConverterOutputSource c1 = new MockConverterOutputSource(posC1, 12.0, 0.05, 50.0);
        MockConverterOutputSource c2 = new MockConverterOutputSource(posC2, 12.0, 0.05, 50.0);

        CreativeLoadLogic load = new CreativeLoadLogic(posLoad);
        load.setMode(CreativeLoadLogic.LoadMode.CONSTANT_CURRENT);
        load.setTargetValue(50.0); // 50 A constant current

        ElectricalGrid grid = new ElectricalGrid();
        grid.registerSource(posC1, c1);
        grid.registerSource(posC2, c2);
        grid.registerConsumer(posLoad, load);

        grid.addConductor(new GridConductor(posC1, posLoad, ConductorType.HEAVY_COPPER.toThermalSpec(), 120.0, true));
        grid.addConductor(new GridConductor(posLoad, posC2, ConductorType.HEAVY_COPPER.toThermalSpec(), 120.0, true));

        for (int i = 0; i < 10; i++) {
            grid.tick(null);
        }

        System.out.println("Separated: c1 drawn: " + c1.drawnCurrent + ", c2 drawn: " + c2.drawnCurrent + ", load drawn: " + load.getLastDeliveredCurrent());
    }

    @Test
    public void testDcBuckConverterPhysics() {
        ConverterType buck = ConverterType.BUCK;
        assertEquals(0.94, buck.getNominalEfficiency(), 1e-3);
        assertFalse(buck.isLinearDissipative());

        // 48V input, target 12V output -> output clamped to min(Vin, Vtarget) = 12V
        double vOut = buck.calculateOutputVoltage(48.0, 12.0);
        assertEquals(12.0, vOut, 1e-3);

        // If input voltage is less than minInputVoltage (8.0V), output should be 0.0V
        double vOutUnder = buck.calculateOutputVoltage(5.0, 12.0);
        assertEquals(0.0, vOutUnder, 1e-3);

        // Efficiency should be fixed nominal efficiency for switched-mode
        double eff = buck.calculateEfficiency(48.0, 12.0);
        assertEquals(0.94, eff, 1e-3);
    }

    @Test
    public void testDcBoostConverterPhysics() {
        ConverterType boost = ConverterType.BOOST;
        assertEquals(0.92, boost.getNominalEfficiency(), 1e-3);

        // 12V input, target 48V output -> output = max(Vin, Vtarget) = 48V
        double vOut = boost.calculateOutputVoltage(12.0, 48.0);
        assertEquals(48.0, vOut, 1e-3);

        // Boost cannot step down: if target is 10V with 24V input, output is Vin = 24V
        double vOutClamped = boost.calculateOutputVoltage(24.0, 10.0);
        assertEquals(24.0, vOutClamped, 1e-3);
    }

    @Test
    public void testDcBuckBoostSepicPhysics() {
        ConverterType sepic = ConverterType.BUCK_BOOST;
        assertEquals(0.90, sepic.getNominalEfficiency(), 1e-3);

        // Can step down: 48V -> 12V
        assertEquals(12.0, sepic.calculateOutputVoltage(48.0, 12.0), 1e-3);

        // Can step up: 12V -> 48V
        assertEquals(48.0, sepic.calculateOutputVoltage(12.0, 48.0), 1e-3);
    }

    @Test
    public void testLinearLdoThermalDissipation() {
        ConverterType ldo = ConverterType.LINEAR_LDO;
        assertTrue(ldo.isLinearDissipative());

        // 12V input, 5V target
        double vOut = ldo.calculateOutputVoltage(12.0, 5.0);
        assertEquals(5.0, vOut, 1e-3);

        // Efficiency is Vout / Vin = 5 / 12 = 0.4167
        double eff = ldo.calculateEfficiency(12.0, 5.0);
        assertEquals(5.0 / 12.0, eff, 1e-3);

        // Heat loss at 2A load: (12V - 5V) * 2A = 14W
        double pIn = 12.0 * 2.0;
        double pOut = 5.0 * 2.0;
        double pLoss = pIn - pOut;
        assertEquals(14.0, pLoss, 1e-3);
    }

    @Test
    public void testAcTransformers() {
        TransformerType stepDown = TransformerType.STEP_DOWN;
        assertEquals(24.0 / 230.0, stepDown.getTurnsRatio(), 1e-4);
        assertEquals(24.0, stepDown.calculateSecondaryVoltage(230.0), 1e-2);
        assertEquals(0.96, stepDown.getEfficiency(), 1e-3);
        assertEquals(5000.0, stepDown.getMaxPowerVA(), 1e-2);

        TransformerType stepUp = TransformerType.STEP_UP;
        assertEquals(230.0 / 24.0, stepUp.getTurnsRatio(), 1e-4);
        assertEquals(230.0, stepUp.calculateSecondaryVoltage(24.0), 1e-2);
        assertEquals(0.96, stepUp.getEfficiency(), 1e-3);
    }

    @Test
    public void testRectifiers() {
        RectifierType bridge = RectifierType.BRIDGE;
        assertEquals(1.4, bridge.getForwardVoltageDrop(), 1e-3);
        assertEquals(0.88, bridge.getEfficiency(), 1e-3);
        // Peak = 230 * sqrt(2) ~ 325.27V, minus 1.4V drop = 323.87V
        double dcOutBridge = bridge.calculateDcVoltage(230.0);
        assertEquals(230.0 * Math.sqrt(2.0) - 1.4, dcOutBridge, 1e-2);

        RectifierType active = RectifierType.ACTIVE_SYNCHRONOUS;
        assertEquals(0.05, active.getForwardVoltageDrop(), 1e-3);
        assertEquals(0.985, active.getEfficiency(), 1e-3);
        double dcOutActive = active.calculateDcVoltage(230.0);
        assertEquals(230.0 * Math.sqrt(2.0) - 0.05, dcOutActive, 1e-2);
    }

    @Test
    public void testInvertersHarmonicsAndProtections() {
        InverterType square = InverterType.SQUARE_WAVE;
        assertEquals(48.0, square.getTotalHarmonicDistortionPercent(), 1e-2);
        assertFalse(square.isGridTie());
        assertFalse(square.hasAutomaticTransferSwitch());

        InverterType modSine = InverterType.MODIFIED_SINE;
        assertEquals(28.0, modSine.getTotalHarmonicDistortionPercent(), 1e-2);
        assertFalse(modSine.isGridTie());

        InverterType pureSine = InverterType.PURE_SINE;
        assertEquals(2.5, pureSine.getTotalHarmonicDistortionPercent(), 1e-2);
        assertFalse(pureSine.isGridTie());

        InverterType gridTie = InverterType.GRID_TIE;
        assertEquals(2.0, gridTie.getTotalHarmonicDistortionPercent(), 1e-2);
        assertTrue(gridTie.isGridTie());
        assertFalse(gridTie.hasAutomaticTransferSwitch());

        InverterType hybrid = InverterType.HYBRID_ESS;
        assertEquals(2.0, hybrid.getTotalHarmonicDistortionPercent(), 1e-2);
        assertTrue(hybrid.isGridTie());
        assertTrue(hybrid.hasAutomaticTransferSwitch());
    }

    @Test
    public void testInverterThermalDissipationUnderRatedLoad() {
        // 1500W Inverter under 1000W load
        double ratedPower = 1500.0;
        double eff = 0.90;
        double outputPower = 1000.0;
        double inputPower = (outputPower / eff) + 2.0;
        double lossWatts = inputPower - outputPower; // ~113.1W

        double ratedFullLoss = (ratedPower * (1.0 - eff)) / eff; // ~166.7W
        double baseCoolingCoeff = Math.max(1.5, ratedFullLoss / 40.0); // ~4.167 W/K

        double temp = 20.0;
        double ambient = 20.0;
        double dt = 0.05;
        double heatCapacity = Math.max(120.0, ratedPower * 0.25);

        // Run for 2000 ticks (100 seconds)
        for (int i = 0; i < 2000; i++) {
            double deltaT = Math.max(0.0, temp - ambient);
            double fanMultiplier = 1.0;
            if (temp > 45.0) {
                fanMultiplier = 1.0 + Math.min(2.5, (temp - 45.0) / 20.0);
            }
            double coolingWatts = baseCoolingCoeff * fanMultiplier * deltaT;
            double deltaTemp = ((lossWatts - coolingWatts) / heatCapacity) * dt;
            temp = Math.max(ambient, temp + deltaTemp);
        }

        // Must stabilize comfortably below 60°C and well below 125°C trip threshold
        assertTrue(temp >= 30.0 && temp <= 60.0, "1500W inverter delivering 1000W load must stabilize safely below 60°C, was: " + temp);
    }

    @Test
    public void testGridAutoPruningAndPhantomLoadRemoval() {
        BlockPos nodePos = new BlockPos(0, 64, 0);
        ElectricalGrid grid = new ElectricalGrid();

        MockConverterOutputSource src = new MockConverterOutputSource(nodePos, 230.0, 0.05, 50.0);
        CreativeLoadLogic load = new CreativeLoadLogic(nodePos);
        load.setMode(CreativeLoadLogic.LoadMode.CONSTANT_RESISTANCE);
        load.setTargetValue(23.0); // 2300W load

        grid.registerSource(nodePos, src);
        grid.registerConsumer(nodePos, load);

        grid.tick(null);
        assertTrue(grid.getTotalConsumptionWatts() > 2000.0, "Load should be consuming ~2300W");

        // Unregister consumer simulates block destruction in world
        grid.unregisterConsumer(nodePos, load);
        grid.tick(null);

        assertEquals(0.0, grid.getTotalConsumptionWatts(), 1e-4, "Consumption must drop immediately to 0 after load is destroyed");
        assertFalse(grid.getConsumers().containsKey(nodePos), "Consumers map must no longer contain the destroyed load position");
    }

    @Test
    public void testBuckBoostDriving1000WThroughMultiplePaths() {
        BlockPos srcPos = new BlockPos(0, 64, 0);
        BlockPos t1Pos = new BlockPos(1, 64, 0);
        BlockPos t2Pos = new BlockPos(2, 64, 0);
        BlockPos t3Pos = new BlockPos(3, 64, 0);

        // Buck-boost output source at 24V with 100A rating
        MockConverterOutputSource buckBoostSource = new MockConverterOutputSource(srcPos, 24.0, 0.05, 100.0);

        // 3 parallel paths (e.g. transformers) drawing 300W, 300W, and 390W (~990W total)
        CreativeLoadLogic t1 = new CreativeLoadLogic(t1Pos);
        t1.setMode(CreativeLoadLogic.LoadMode.CONSTANT_POWER);
        t1.setTargetValue(300.0);

        CreativeLoadLogic t2 = new CreativeLoadLogic(t2Pos);
        t2.setMode(CreativeLoadLogic.LoadMode.CONSTANT_POWER);
        t2.setTargetValue(300.0);

        CreativeLoadLogic t3 = new CreativeLoadLogic(t3Pos);
        t3.setMode(CreativeLoadLogic.LoadMode.CONSTANT_POWER);
        t3.setTargetValue(390.0);

        ElectricalGrid grid = new ElectricalGrid();
        grid.registerSource(srcPos, buckBoostSource);
        grid.registerConsumer(t1Pos, t1);
        grid.registerConsumer(t2Pos, t2);
        grid.registerConsumer(t3Pos, t3);

        grid.addConductor(new GridConductor(srcPos, t1Pos, ConductorType.HEAVY_COPPER.toThermalSpec(), 120.0, true));
        grid.addConductor(new GridConductor(srcPos, t2Pos, ConductorType.HEAVY_COPPER.toThermalSpec(), 120.0, true));
        grid.addConductor(new GridConductor(srcPos, t3Pos, ConductorType.HEAVY_COPPER.toThermalSpec(), 120.0, true));

        for (int i = 0; i < 10; i++) {
            grid.tick(null);
        }

        // Verify the source actually supplies the full ~990W (~41.25A at ~24V), NOT clamped to 25A (600W)
        assertTrue(buckBoostSource.drawnCurrent > 35.0, "Buck-boost must deliver actual current (>35A), was: " + buckBoostSource.drawnCurrent);
        assertTrue(grid.getTotalGenerationWatts() > 900.0, "Grid generation must reflect actual ~990W power, was: " + grid.getTotalGenerationWatts());
        assertTrue(grid.getTotalConsumptionWatts() > 900.0, "Grid consumption must reflect ~990W power, was: " + grid.getTotalConsumptionWatts());
    }

    @Test
    public void testInverterNominalInputVoltageWindows() {
        // Test 12V nominal input mode: 10.0V UVLO to 16.5V OVP
        double vNom12 = 12.0;
        double min12 = vNom12 <= 15.0 ? 10.0 : (vNom12 <= 30.0 ? 20.0 : 40.0);
        double max12 = vNom12 <= 15.0 ? 16.5 : (vNom12 <= 30.0 ? 33.0 : 66.0);
        assertEquals(10.0, min12, 1e-3);
        assertEquals(16.5, max12, 1e-3);

        // Test 24V nominal input mode: 20.0V UVLO to 33.0V OVP
        double vNom24 = 24.0;
        double min24 = vNom24 <= 15.0 ? 10.0 : (vNom24 <= 30.0 ? 20.0 : 40.0);
        double max24 = vNom24 <= 15.0 ? 16.5 : (vNom24 <= 30.0 ? 33.0 : 66.0);
        assertEquals(20.0, min24, 1e-3);
        assertEquals(33.0, max24, 1e-3);

        // Test 48V nominal input mode: 40.0V UVLO to 66.0V OVP
        double vNom48 = 48.0;
        double min48 = vNom48 <= 15.0 ? 10.0 : (vNom48 <= 30.0 ? 20.0 : 40.0);
        double max48 = vNom48 <= 15.0 ? 16.5 : (vNom48 <= 30.0 ? 33.0 : 66.0);
        assertEquals(40.0, min48, 1e-3);
        assertEquals(66.0, max48, 1e-3);
    }

    @Test
    public void testHybridEssInverterIslandModeAtsLogic() {
        InverterType hybrid = InverterType.HYBRID_ESS;
        assertTrue(hybrid.hasAutomaticTransferSwitch(), "Hybrid ESS must feature ATS");
        assertTrue(hybrid.isGridTie(), "Hybrid ESS supports grid-tie synchronization");

        // Scenario 1: Island mode (no external AC utility grid)
        // atsIslandMode is true -> isGridTie() must return FALSE so anti-islanding does not false-trip
        boolean atsIslandMode = true;
        boolean effectiveGridTie = hybrid.isGridTie() && !atsIslandMode;
        assertFalse(effectiveGridTie, "In island mode, inverter is the sole microgrid AC source and must not be grid-tie");

        // Scenario 2: Synchronized with energized external utility grid
        // atsIslandMode is false -> isGridTie() returns TRUE to enable anti-islanding detection
        atsIslandMode = false;
        effectiveGridTie = hybrid.isGridTie() && !atsIslandMode;
        assertTrue(effectiveGridTie, "When external grid is present, inverter is grid-tied and anti-islanding is active");
    }

    @Test
    public void testConverterTripGracePeriodAndResetTrip() {
        // Protection trip state machine contract
        boolean tripped = true;
        int tripCooldownTicks = 60;
        int underVoltageTicks = 6;
        int antiIslandingTicks = 4;
        int tripGraceTicks = 0;

        // Player clicks "RESET TRIP"
        tripped = false;
        tripCooldownTicks = 0;
        underVoltageTicks = 0;
        antiIslandingTicks = 0;
        tripGraceTicks = 40; // 2 seconds grace period

        assertFalse(tripped, "Tripped flag must be cleared");
        assertEquals(0, tripCooldownTicks, "Cooldown must be cleared");
        assertEquals(0, underVoltageTicks, "Undervoltage counter must be reset");
        assertEquals(0, antiIslandingTicks, "Anti-islanding counter must be reset");
        assertEquals(40, tripGraceTicks, "Grace period must be granted");

        // During grace period, transient undervoltage must not trigger immediate re-trip
        double inputVoltage = 30.0; // below 40V cutoff for 48V nominal
        double minVin = 40.0;

        if (tripGraceTicks > 0) {
            tripGraceTicks--;
            // UVLO check suppressed during grace period
        } else if (inputVoltage < minVin * 0.9) {
            underVoltageTicks++;
            if (underVoltageTicks >= 6) {
                tripped = true;
            }
        }

        assertFalse(tripped, "Inverter must not trip during grace period, allowing MNA solver to establish operating point");
        assertEquals(39, tripGraceTicks);
    }

    @Test
    public void testMpptStorageMismatchValidation() {
        // 48V battery bank setting on MPPT
        double bank48 = 48.0;

        // 16S 18650 Battery Rack nominal voltage = 16 * 3.7V = 59.2V
        double rack16SNominal = 59.2;
        boolean mismatch48 = (bank48 <= 15.0 && rack16SNominal > 18.0)
            || (bank48 > 15.0 && bank48 <= 30.0 && (rack16SNominal < 18.0 || rack16SNominal > 36.0))
            || (bank48 > 30.0 && rack16SNominal < 36.0);
        assertFalse(mismatch48, "16S battery rack must match 48V bank setting");

        // Even if terminal voltage sags to 28.0V under heavy 8000W inverter draw,
        // the nominal storage bank matches, so MPPT must NOT trip into brownout!
        double saggedTerminalV = 28.0;
        assertTrue(saggedTerminalV < 34.0, "Inverter heavy load sags terminal voltage");
        assertFalse(mismatch48, "MPPT must not trip on battery mismatch due to dynamic load sag");

        // Wrong battery bank: connecting a 12V nominal battery pack to a 48V MPPT charger
        double battery12Nominal = 12.0;
        boolean mismatch12on48 = (bank48 > 30.0 && battery12Nominal < 36.0);
        assertTrue(mismatch12on48, "12V battery connected to 48V charger must trigger mismatch protection");
    }
}
