package com.ostapyrih.voltcraft.simulation.grid;

import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.simulation.chemistry.BatteryChemistry;
import com.ostapyrih.voltcraft.simulation.electrical.BatteryElement;
import com.ostapyrih.voltcraft.simulation.electrical.ConverterElement;
import com.ostapyrih.voltcraft.simulation.electrical.CreativeLoadElement;
import com.ostapyrih.voltcraft.simulation.electrical.SolarElement;
import com.ostapyrih.voltcraft.simulation.generation.MPPTLogic;
import com.ostapyrih.voltcraft.simulation.generation.SolarIrradianceSimulation;
import com.ostapyrih.voltcraft.simulation.generation.SolarPanelType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test reproducing the exact player circuit:
 * Solar Panel (400W STC) -> Cables -> MPPT Charge Controller -> Cables -> 12V Battery + Load (variable 50W-2500W).
 * Covers:
 * 1. MPPT charging battery (Bulk -> Absorption -> Float) under solar.
 * 2. Parallel load variations (0W, 50W, 300W, 1000W, 2500W).
 * 3. MPPT power foldback: output never exceeds solar power (380W delivered max).
 * 4. Parallel battery discharge covering the deficit under heavy load.
 * 5. Sequential disconnection and reconnection of each individual terminal:
 *    - Solar (+) disconnected
 *    - Solar (-) disconnected
 *    - Battery (+) disconnected
 *    - Battery (-) disconnected
 *    - Load disconnected
 */
public class SolarMpptBatteryLoadSystemTest {

    static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /** Mock Solar Panel block entity matching SolarPanelBlockEntity logic */
    static class SolarPanelModel implements KernelAttachedBlock {
        final BlockPos pos;
        final Direction facing;
        final double[] telemetry = new double[2];
        final double[] stateArray = SolarElement.newStateArray();
        double emf = 48.0;
        double rInt = 0.8;
        double peakPower = 400.0;
        double irradiance = 1000.0;
        final SolarElement element;

        SolarPanelModel(BlockPos pos, Direction facing) {
            this.pos = pos;
            this.facing = facing;
            this.element = new SolarElement(() -> emf, () -> rInt, () -> irradiance, telemetry);
        }

        void setSunlight(double wM2) {
            this.irradiance = wM2;
            SolarIrradianceSimulation.SolarOutput out = SolarIrradianceSimulation.computeSolarOutput(
                SolarPanelType.MONOCRYSTALLINE_PERC, wM2, 25.0
            );
            this.emf = out.electromotiveForce();
            this.rInt = out.internalResistanceOhms();
            this.peakPower = out.peakPowerAvailableWatts();
        }

        @Override
        public BlockPos getPos() { return pos; }

        @Override
        public BlockPos[] getTerminalPositions() {
            // [FRONT (-), BACK (+)]
            return SolarElement.resolveTerminals(pos, facing);
        }

        @Override
        public ElectricalElement getElement() { return element; }

        @Override
        public double[] getStateArray() { return stateArray.clone(); }

        @Override
        public void setStateArray(double[] s) { System.arraycopy(s, 0, stateArray, 0, s.length); }

        @Override
        public void tickElectrical(ServerWorld world) {
            // Nothing extra needed
        }

        @Override
        public boolean isActiveSource() { return emf > 0.0; }

        @Override
        public boolean isACSource() { return false; }
    }

    /** Mock MPPT block entity matching ChargeControllerBlockEntity logic */
    static class MPPTModel implements KernelAttachedBlock {
        final BlockPos pos;
        final Direction facing;
        final double[] telemetry = new double[ConverterElement.TELE_LEN];
        final double[] stateArray = ConverterElement.newStateArray();

        boolean tripped = false;
        double targetOutputVoltage = 12.0; // 12V bank preset
        double stagedInputDemandWatts = 0.0;
        double stagedOutputEmf = 0.0;
        boolean ccMode = false;
        int ccHeadroomTicks = 0;

        double inputVoltage = 0.0;
        double inputCurrentAmps = 0.0;
        double inputPowerWatts = 0.0;
        double actualOutputVoltage = 0.0;
        double outputPowerWatts = 0.0;
        double outputCurrentAmps = 0.0;
        double lastDemandWatts = -1.0;

        final MPPTLogic mpptLogic = new MPPTLogic(12.0);
        final ConverterElement element;

        // Reference to solar panels in the grid
        final List<SolarPanelModel> solarPanels = new ArrayList<>();

        MPPTModel(BlockPos pos, Direction facing) {
            this.pos = pos;
            this.facing = facing;
            this.element = new ConverterElement(
                () -> tripped,
                () -> stagedInputDemandWatts,
                () -> stagedOutputEmf,
                () -> 48.0,
                telemetry
            );
        }

        double getAvailableSolarWatts() {
            if (!solarPanels.isEmpty()) {
                double total = 0.0;
                for (SolarPanelModel sp : solarPanels) {
                    total += sp.peakPower;
                }
                if (total > 0.0) {
                    return total;
                }
            }
            // Production fallback from ChargeControllerBlockEntity.java:230-233
            if (inputVoltage > 1.0 && inputPowerWatts > 0.0) {
                return inputPowerWatts;
            }
            return 0.0;
        }

        @Override
        public BlockPos getPos() { return pos; }

        @Override
        public BlockPos[] getTerminalPositions() {
            // [BACK+, LEFT-, FRONT+, RIGHT-]
            return ConverterElement.resolveConverterTerminals(pos, facing);
        }

        @Override
        public ElectricalElement getElement() { return element; }

        @Override
        public double[] getStateArray() { return stateArray.clone(); }

        @Override
        public void setStateArray(double[] s) { System.arraycopy(s, 0, stateArray, 0, s.length); }

        @Override
        public void tickElectrical(ServerWorld world) {
            double telePOut = telemetry[ConverterElement.TELE_P_OUT];
            double teleVIn = telemetry[ConverterElement.TELE_V_IN];
            double teleVOut = telemetry[ConverterElement.TELE_V_OUT];
            double teleIOut = telemetry[ConverterElement.TELE_I_OUT];

            double prevDemandWatts = stagedInputDemandWatts;
            this.inputVoltage = teleVIn;
            this.inputPowerWatts = Math.max(0.0, prevDemandWatts);
            this.inputCurrentAmps = teleVIn > 1.0 ? Math.max(0.0, prevDemandWatts) / teleVIn : 0.0;
            this.outputPowerWatts = Math.max(0.0, Double.isFinite(telePOut) ? telePOut : 0.0);
            this.actualOutputVoltage = teleVOut;
            this.outputCurrentAmps = Math.max(0.0, Double.isFinite(teleIOut) ? teleIOut : 0.0);

            // Compute output voltage using ChargeControllerBlockEntity algorithm
            mpptLogic.setBatteryBankVoltage(targetOutputVoltage);
            double rawTarget = 0.0;
            if (!tripped && teleVIn >= 15.0) {
                double battV = teleVOut > 1.0 ? teleVOut : targetOutputVoltage;
                boolean settled = MPPTLogic.isRailSettled(inputVoltage, 15.0,
                    mpptLogic.getTargetInputVoltage(), stagedInputDemandWatts, lastDemandWatts);
                mpptLogic.step(inputVoltage, inputCurrentAmps, battV, settled);

                if (mpptLogic.getStage() == MPPTLogic.ChargeStage.BULK
                    || mpptLogic.getStage() == MPPTLogic.ChargeStage.ABSORPTION) {
                    rawTarget = mpptLogic.getAbsorptionVoltage();
                } else {
                    rawTarget = mpptLogic.getFloatVoltage();
                }
                // Compensated charge voltage: mirrors ChargeControllerBlockEntity.
                double iPrev = Math.max(0.0, outputCurrentAmps);
                rawTarget += 0.3 * Math.min(1.0, iPrev / 5.0)
                    + Math.min(iPrev * 0.03, 0.8);

                // Solar CC/CV regulation: mirrors ChargeControllerBlockEntity.
                // CV stages the full charge target; CC (output current above
                // what the sun sustains) hugs the rail at exactly the solar
                // current so output tracks input. Rail-hug only on a formed
                // rail so a dead bus bootstraps instead of pinning near zero.
                double availSolar = getAvailableSolarWatts();
                double iCap = availSolar > 0.0
                    ? Math.min(30.0, availSolar * 0.95 / Math.max(1.0, rawTarget))
                    : 0.0;
                if (!ccMode && iCap > 0.0 && telemetry[ConverterElement.TELE_P_OUT] > 0.0
                    && outputCurrentAmps > iCap) {
                    ccMode = true;
                    ccHeadroomTicks = 0;
                } else if (ccMode) {
                    if (outputCurrentAmps < 0.8 * iCap) {
                        ccMode = false;
                        ccHeadroomTicks = 0;
                    } else if (telemetry[ConverterElement.TELE_P_OUT] > 0.0
                        && stagedInputDemandWatts < 0.95 * availSolar) {
                        if (++ccHeadroomTicks >= 5) {
                            ccMode = false;
                            ccHeadroomTicks = 0;
                        }
                    } else {
                        ccHeadroomTicks = 0;
                    }
                }
                if (ccMode && actualOutputVoltage > 1.0) {
                    double vMaxFoldback = actualOutputVoltage + iCap * ConverterElement.SOURCE_R_OHM;
                    rawTarget = Math.min(rawTarget, vMaxFoldback);
                } else {
                    // Ideal-diode OR-ing: mirrors ChargeControllerBlockEntity.
                    rawTarget = Math.max(rawTarget, actualOutputVoltage);
                }
                if (actualOutputVoltage <= 1.0 && outputCurrentAmps > 30.0) {
                    rawTarget = 0.0;
                }
            }

            this.stagedOutputEmf = ConverterElement.stageEmf(rawTarget, tripped, teleVIn, 150.0);
            this.stagedInputDemandWatts = ConverterElement.stageDemandWatts(telePOut, 0.95, tripped, teleVIn);

            // Staged demand clamp: exactly matching ChargeControllerBlockEntity.java:276-279
            double availSolar = getAvailableSolarWatts();
            if (availSolar > 0.0) {
                this.stagedInputDemandWatts = Math.min(this.stagedInputDemandWatts, availSolar);
            }
            this.lastDemandWatts = stagedInputDemandWatts;
            if (false) {
                System.out.println("MPPT t: Vin=" + String.format("%.1f", inputVoltage)
                    + " Vout=" + String.format("%.2f", actualOutputVoltage)
                    + " Pout=" + String.format("%.0f", outputPowerWatts)
                    + " Iout=" + String.format("%.1f", outputCurrentAmps)
                    + " dem=" + String.format("%.0f", stagedInputDemandWatts)
                    + " emf=" + String.format("%.2f", stagedOutputEmf)
                    + " cc=" + ccMode + " stage=" + mpptLogic.getStage());
            }

            // Output current foldback (hiccup, non-latching), mirroring
            // AbstractPowerConverterBlockEntity: sag EMF to current-limit instead
            // of latching a trip; recovers automatically once overload clears.
            // Gated on a formed rail so an unformed bus stages raw target and
            // can bootstrap instead of pinning near zero. UVLO is a
            // non-latching brownout (output gates on minVin via
            // stageEmf), so the rail recovers by itself.
            double maxOut = 30.0;
            if (!tripped && teleVOut > 1.0 && teleIOut > maxOut) {
                double currentLimitEmf = Math.max(0.0, teleVOut)
                    + maxOut * ConverterElement.SOURCE_R_OHM;
                if (stagedOutputEmf > currentLimitEmf) {
                    stagedOutputEmf = currentLimitEmf;
                }
            }
            boolean next = ConverterElement.tripNext(tripped, 25.0, false, false, false);
            if (next != tripped) {
                tripped = next;
            }
        }

        @Override
        public boolean isActiveSource() {
            return ConverterElement.isActiveSource(tripped, stagedOutputEmf);
        }

        @Override
        public boolean isACSource() { return false; }
    }

    /** Mock Battery matching BatteryBlockEntity logic */
    static class BatteryModel implements KernelAttachedBlock {
        final BlockPos pos;
        final Direction facing;
        final double[] telemetry = new double[]{Double.NaN, Double.NaN};
        final double[] stateArray = new double[]{0.8, 25.0, 1.0}; // 80% SoC
        boolean bmsOpen = false;
        final BatteryChemistry chemistry;
        final int seriesCount;
        final int parallelCount;
        final BatteryElement element;

        BatteryModel(BlockPos pos, Direction facing, BatteryChemistry chemistry, int seriesCount, int parallelCount) {
            this.pos = pos;
            this.facing = facing;
            this.chemistry = chemistry;
            this.seriesCount = seriesCount;
            this.parallelCount = parallelCount;
            this.element = new BatteryElement(chemistry, seriesCount, parallelCount, () -> bmsOpen, telemetry);
        }

        BatteryModel(BlockPos pos, Direction facing) {
            this(pos, facing, BatteryChemistry.LIFEPO4, 4, 1);
        }

        @Override
        public BlockPos getPos() { return pos; }

        @Override
        public BlockPos[] getTerminalPositions() {
            return BatteryElement.resolveTerminals(pos, facing);
        }

        @Override
        public ElectricalElement getElement() { return element; }

        @Override
        public double[] getStateArray() { return stateArray.clone(); }

        @Override
        public void setStateArray(double[] s) { System.arraycopy(s, 0, stateArray, 0, s.length); }

        @Override
        public void tickElectrical(ServerWorld world) {
            if (!Double.isFinite(telemetry[BatteryElement.TELE_V])) {
                return;
            }
            // Mirrors BatteryBlockEntity: healthy charge forces closed so
            // load-induced sag and pre-bootstrap artifacts never latch.
            double minPackV = BatteryElement.packMinVoltage(chemistry, seriesCount);
            double recoverV = minPackV + Math.max(1, seriesCount)
                * BatteryElement.BMS_RECOVERY_HYST_V_PER_CELL;
            double emf = BatteryElement.packEmf(chemistry, seriesCount,
                stateArray[BatteryElement.STATE_SOC]);
            double tempC = stateArray[BatteryElement.STATE_TEMP];
            boolean next;
            if (tempC > BatteryElement.BMS_OVERTEMP_OPEN_C) {
                next = true;
            } else if (emf > recoverV && tempC < BatteryElement.BMS_OVERTEMP_CLOSE_C) {
                next = false;
            } else {
                next = BatteryElement.bmsNext(bmsOpen, telemetry[BatteryElement.TELE_V],
                    tempC, minPackV, seriesCount);
            }
            if (next != bmsOpen) {
                bmsOpen = next;
            }
        }

        @Override
        public boolean isActiveSource() { return !bmsOpen; }

        @Override
        public boolean isACSource() { return false; }

        double getTerminalVoltage() { return telemetry[BatteryElement.TELE_V]; }
        double getTerminalCurrent() { return telemetry[BatteryElement.TELE_I]; }
    }

    /** Mock Load matching CreativeLoadBlockEntity logic */
    static class LoadModel implements KernelAttachedBlock {
        final BlockPos pos;
        final Direction facing;
        final double[] telemetry = new double[2];
        boolean enabled = false;
        double targetWatts = 2500.0;
        boolean droppedOut = false;
        final CreativeLoadElement element;

        LoadModel(BlockPos pos, Direction facing) {
            this.pos = pos;
            this.facing = facing;
            this.element = new CreativeLoadElement(
                () -> CreativeLoadElement.MODE_POWER,
                () -> targetWatts,
                () -> enabled && !droppedOut,
                () -> 12.0,
                telemetry
            );
        }

        @Override
        public BlockPos getPos() { return pos; }

        @Override
        public BlockPos[] getTerminalPositions() {
            return CreativeLoadElement.resolveTerminals(pos, facing);
        }

        @Override
        public ElectricalElement getElement() { return element; }

        @Override
        public double[] getStateArray() { return new double[0]; }

        @Override
        public void setStateArray(double[] s) {}

        @Override
        public void tickElectrical(ServerWorld world) {
            // Mirrors CreativeLoadBlockEntity brownout dropout (6 V / 10 V).
            double teleV = telemetry[CreativeLoadElement.TELE_V];
            if (!droppedOut && teleV < 6.0) {
                droppedOut = true;
            } else if (droppedOut && teleV > 10.0) {
                droppedOut = false;
            }
        }

        @Override
        public boolean isActiveSource() { return false; }

        @Override
        public boolean isACSource() { return false; }

        double getPowerDrawn() {
            return telemetry[CreativeLoadElement.TELE_V] * telemetry[CreativeLoadElement.TELE_I];
        }
    }

    private GridManager manager;
    private SolarPanelModel solar;
    private MPPTModel mppt;
    private BatteryModel battery;
    private LoadModel load;

    @BeforeEach
    void setup() {
        manager = new GridManager();

        // 1. Solar Panel at (0, 64, 0), facing NORTH.
        // FRONT(-) is (0, 64, -1), BACK(+) is (0, 64, 1).
        solar = new SolarPanelModel(pos(0, 64, 0), Direction.NORTH);

        // 2. MPPT at (0, 64, 6), facing SOUTH.
        // BACK+ (In+) is NORTH -> (0, 64, 5)
        // LEFT- (In-) is EAST -> (1, 64, 6)
        // FRONT+ (Out+) is SOUTH -> (0, 64, 7)
        // RIGHT- (Out-) is WEST -> (-1, 64, 6)
        mppt = new MPPTModel(pos(0, 64, 6), Direction.SOUTH);

        mppt.solarPanels.add(solar);

        // 3. Battery at (0, 64, 12), facing NORTH.
        // FRONT(-) is (0, 64, 11), BACK(+) is (0, 64, 13).
        battery = new BatteryModel(pos(0, 64, 12), Direction.NORTH);

        // 4. Load at (4, 64, 12), facing NORTH.
        // FRONT(-) is (4, 64, 11), BACK(+) is (4, 64, 13).
        load = new LoadModel(pos(4, 64, 12), Direction.NORTH);

        manager.putAttachedBlock(solar);
        manager.putAttachedBlock(mppt);
        manager.putAttachedBlock(battery);
        manager.putAttachedBlock(load);
    }

    void connectFullCircuit() {
        // 1. Solar(+) -> MPPT In(+): (0, 64, 1) through (0, 64, 5)
        for (int z = 1; z <= 5; z++) {
            manager.putCable(pos(0, 64, z), ConductorType.INSULATED_COPPER);
        }

        // 2. Solar(-) -> MPPT In(-):
        // (0, 64, -1) -> (3, 64, -1) -> (3, 64, 6) -> (1, 64, 6)
        for (int x = 0; x <= 3; x++) {
            manager.putCable(pos(x, 64, -1), ConductorType.INSULATED_COPPER);
        }
        for (int z = -1; z <= 6; z++) {
            manager.putCable(pos(3, 64, z), ConductorType.INSULATED_COPPER);
        }
        for (int x = 1; x <= 3; x++) {
            manager.putCable(pos(x, 64, 6), ConductorType.INSULATED_COPPER);
        }

        // 3. MPPT Out(-) -> Bat(-) and Load(-):
        // Out(-) is at (-1, 64, 6). Bat(-) is at (0, 64, 11). Load(-) is at (4, 64, 11).
        // Route: (-1, 64, 6) -> (-2, 64, 6) -> (-2, 64, 11) -> (4, 64, 11)
        manager.putCable(pos(-1, 64, 6), ConductorType.HEAVY_COPPER);
        for (int z = 6; z <= 11; z++) {
            manager.putCable(pos(-2, 64, z), ConductorType.HEAVY_COPPER);
        }
        for (int x = -2; x <= 4; x++) {
            manager.putCable(pos(x, 64, 11), ConductorType.HEAVY_COPPER);
        }

        // 4. MPPT Out(+) -> Bat(+) and Load(+):
        // Out(+) is at (0, 64, 7). Bat(+) is at (0, 64, 13). Load(+) is at (4, 64, 13).
        // Route: (0, 64, 7) -> (0, 64, 8) -> (6, 64, 8) -> (6, 64, 13) -> (0, 64, 13)
        manager.putCable(pos(0, 64, 7), ConductorType.HEAVY_COPPER);
        for (int x = 0; x <= 6; x++) {
            manager.putCable(pos(x, 64, 8), ConductorType.HEAVY_COPPER);
        }
        for (int z = 8; z <= 13; z++) {
            manager.putCable(pos(6, 64, z), ConductorType.HEAVY_COPPER);
        }
        for (int x = 0; x <= 6; x++) {
            manager.putCable(pos(x, 64, 13), ConductorType.HEAVY_COPPER);
        }
    }

    void stepTicks(int count) {
        for (int i = 0; i < count; i++) {
            manager.tick(null);
        }
    }

    @Test
    @DisplayName("1. Baseline: MPPT charges battery at ~14.4V with ~380W when load is off")
    void testBaselineChargingWithoutLoad() {
        connectFullCircuit();
        load.enabled = false;
        for (int t = 1; t <= 10; t++) {
            manager.tick(null);
            System.out.println("TICK " + t + ": Vin=" + mppt.inputVoltage
                + ", Vout=" + mppt.actualOutputVoltage
                + ", Pin=" + mppt.inputPowerWatts
                + ", Pout=" + mppt.outputPowerWatts
                + ", StagedDemand=" + mppt.stagedInputDemandWatts
                + ", StagedEmf=" + mppt.stagedOutputEmf
                + ", BatV=" + battery.getTerminalVoltage()
                + ", BatI=" + battery.getTerminalCurrent());
        }

        assertTrue(mppt.inputVoltage > 30.0, "MPPT input voltage should see solar (~40V), was: " + mppt.inputVoltage);
        assertTrue(mppt.outputPowerWatts > 50.0 && mppt.outputPowerWatts <= 400.0,
            "MPPT output power should be ~100-380W, was: " + mppt.outputPowerWatts);
        assertTrue(battery.getTerminalVoltage() > 13.5,
            "Battery should be charging (>13.5V), was: " + battery.getTerminalVoltage());
        assertTrue(battery.getTerminalCurrent() < -5.0,
            "Battery current should be negative (charging), was: " + battery.getTerminalCurrent());
    }

    @Test
    @DisplayName("2. Load variations: 50W, 300W, 1000W, 2500W and toggle OFF without freeze")
    void testLoadVariationsAndToggleWithoutFreeze() {
        connectFullCircuit();
        stepTicks(5);

        // A. 50W load
        load.enabled = true;
        load.targetWatts = 50.0;
        stepTicks(5);
        assertEquals(50.0, load.getPowerDrawn(), 5.0, "50W load power drawn");
        assertTrue(mppt.outputPowerWatts <= 385.0, "MPPT must not exceed solar limit under 50W load");

        // B. 300W load
        load.targetWatts = 300.0;
        stepTicks(5);
        assertEquals(300.0, load.getPowerDrawn(), 15.0, "300W load power drawn");
        assertTrue(mppt.outputPowerWatts <= 385.0, "MPPT must not exceed solar limit under 300W load");

        // C. 2500W load: MPPT outputs max solar (~380W), battery provides remainder (~2120W)
        load.targetWatts = 2500.0;
        stepTicks(5);
        assertTrue(load.getPowerDrawn() > 2000.0, "2500W load should be powered: " + load.getPowerDrawn());
        assertTrue(mppt.outputPowerWatts <= 385.0, "MPPT output must not exceed solar limit: " + mppt.outputPowerWatts);
        // Battery must be discharging (negative terminal current is positive on discharge)
        assertTrue(battery.getTerminalCurrent() > 100.0, "Battery should heavily discharge (>100A)");

        // D. Toggle load OFF: must not freeze, MPPT returns to charging battery
        load.enabled = false;
        stepTicks(5);
        assertTrue(mppt.outputPowerWatts > 50.0 && mppt.outputPowerWatts <= 400.0,
            "MPPT must resume charging battery, was: " + mppt.outputPowerWatts);
        assertEquals(0.0, load.getPowerDrawn(), 1e-3, "Load must be 0W when disabled");
    }

    @Test
    @DisplayName("3. Disconnecting Solar (+) wire stops solar input without phantom current")
    void testDisconnectSolarPlusWire() {
        connectFullCircuit();
        stepTicks(5);
        assertTrue(mppt.inputVoltage > 30.0);

        // Remove Solar (+) cable at (0, 64, 1)
        manager.removeCable(pos(0, 64, 1));
        stepTicks(5);

        assertEquals(0.0, mppt.inputVoltage, 1e-6, "Input voltage must be 0V when (+) is broken");
        assertEquals(0.0, mppt.inputPowerWatts, 1e-6, "Input power must be 0W when (+) is broken");
        assertEquals(0.0, mppt.outputPowerWatts, 1e-6, "MPPT output power must be 0W without solar");

        // Reconnect Solar (+)
        manager.putCable(pos(0, 64, 1), ConductorType.INSULATED_COPPER);
        stepTicks(5);
        assertTrue(mppt.inputVoltage > 30.0, "MPPT should resume upon reconnecting (+)");
    }

    @Test
    @DisplayName("4. Disconnecting Solar (-) wire stops solar input without phantom current")
    void testDisconnectSolarMinusWire() {
        connectFullCircuit();
        stepTicks(5);
        assertTrue(mppt.inputVoltage > 30.0);

        // Remove Solar (-) cable at (1, 64, 6)
        manager.removeCable(pos(1, 64, 6));
        stepTicks(5);

        assertEquals(0.0, mppt.inputVoltage, 1e-6, "Input voltage must be 0V when (-) is broken");
        assertEquals(0.0, mppt.inputPowerWatts, 1e-6, "Input power must be 0W when (-) is broken");
        assertEquals(0.0, mppt.outputPowerWatts, 1e-6, "MPPT output power must be 0W without return path");

        // Reconnect Solar (-)
        manager.putCable(pos(1, 64, 6), ConductorType.INSULATED_COPPER);
        stepTicks(5);
        assertTrue(mppt.inputVoltage > 30.0, "MPPT should resume upon reconnecting (-)");
    }

    @Test
    @DisplayName("5. Disconnecting Battery (+) wire: load runs on solar or turns off without crash")
    void testDisconnectBatteryPlusWire() {
        connectFullCircuit();
        stepTicks(5);

        // Disconnect battery positive cable at (0, 64, 13)
        manager.removeCable(pos(0, 64, 13));
        stepTicks(5);

        // Battery current must be 0A
        assertEquals(0.0, battery.getTerminalCurrent(), 1e-6, "Battery disconnected from circuit must draw 0A");

        // Reconnect battery
        manager.putCable(pos(0, 64, 13), ConductorType.HEAVY_COPPER);
        stepTicks(5);
        assertTrue(mppt.outputPowerWatts > 50.0, "MPPT resumes charging battery upon reconnect: " + mppt.outputPowerWatts);
    }

    @Test
    @DisplayName("6. Faithful reproduction: Battery under 2500W load -> connect MPPT -> disconnect load -> reconnect load -> sequential terminal disconnects")
    void testExactUserScenario() {
        // Step 1: Battery connected to 2500W Load ONLY (no MPPT yet)
        // Battery (-) at (0, 64, 11) -> Load (-) at (4, 64, 11)
        for (int x = 0; x <= 4; x++) {
            manager.putCable(pos(x, 64, 11), ConductorType.HEAVY_COPPER);
        }
        // Battery (+) at (0, 64, 13) -> Load (+) at (4, 64, 13)
        for (int x = 0; x <= 4; x++) {
            manager.putCable(pos(x, 64, 13), ConductorType.HEAVY_COPPER);
        }
        load.enabled = true;
        load.targetWatts = 2500.0;
        stepTicks(5);
        System.out.println("STEP 1: LoadP=" + load.getPowerDrawn()
            + ", BatV=" + battery.getTerminalVoltage()
            + ", BatI=" + battery.getTerminalCurrent());
        // Verify load is drawing ~2500W purely from battery
        assertTrue(load.getPowerDrawn() > 2000.0, "Load should draw ~2500W from battery: " + load.getPowerDrawn());
        assertTrue(battery.getTerminalCurrent() > 100.0, "Battery should supply all load current: " + battery.getTerminalCurrent());

        // Step 2: Connect Solar Panel (400W) and MPPT to the running system
        // Solar(+) -> MPPT In(+)
        for (int z = 1; z <= 5; z++) {
            manager.putCable(pos(0, 64, z), ConductorType.INSULATED_COPPER);
        }
        // Solar(-) -> MPPT In(-)
        for (int x = 0; x <= 3; x++) {
            manager.putCable(pos(x, 64, -1), ConductorType.INSULATED_COPPER);
        }
        for (int z = -1; z <= 6; z++) {
            manager.putCable(pos(3, 64, z), ConductorType.INSULATED_COPPER);
        }
        for (int x = 1; x <= 3; x++) {
            manager.putCable(pos(x, 64, 6), ConductorType.INSULATED_COPPER);
        }
        // MPPT Out(-) -> Bus(-)
        manager.putCable(pos(-1, 64, 6), ConductorType.HEAVY_COPPER);
        for (int z = 6; z <= 11; z++) {
            manager.putCable(pos(-2, 64, z), ConductorType.HEAVY_COPPER);
        }
        manager.putCable(pos(-1, 64, 11), ConductorType.HEAVY_COPPER);

        // MPPT Out(+) -> Bus(+)
        manager.putCable(pos(0, 64, 7), ConductorType.HEAVY_COPPER);
        for (int x = 0; x <= 6; x++) {
            manager.putCable(pos(x, 64, 8), ConductorType.HEAVY_COPPER);
        }
        for (int z = 8; z <= 13; z++) {
            manager.putCable(pos(6, 64, z), ConductorType.HEAVY_COPPER);
        }
        for (int x = 5; x <= 6; x++) {
            manager.putCable(pos(x, 64, 13), ConductorType.HEAVY_COPPER);
        }

        stepTicks(5);

        // MPPT must NOT output more than solar panel peak (400W)
        assertTrue(mppt.outputPowerWatts <= 385.0, "MPPT output must be capped by solar limit (~380W): " + mppt.outputPowerWatts);
        assertTrue(mppt.outputPowerWatts > 200.0, "MPPT should contribute solar power: " + mppt.outputPowerWatts);
        assertTrue(load.getPowerDrawn() > 2000.0, "Load remains powered: " + load.getPowerDrawn());

        // Step 3: Turn OFF Load
        load.enabled = false;
        stepTicks(5);
        assertEquals(0.0, load.getPowerDrawn(), 1e-3, "Load is 0W");
        assertTrue(mppt.outputPowerWatts > 50.0 && mppt.outputPowerWatts <= 385.0,
            "MPPT charges battery with available power: " + mppt.outputPowerWatts);
        assertTrue(battery.getTerminalCurrent() < -5.0, "Battery is charging");

        // Step 4: Turn ON Load again (2500W) -> must NOT freeze, load must resume!
        load.enabled = true;
        stepTicks(5);
        assertTrue(load.getPowerDrawn() > 2000.0, "Load must resume drawing power after toggle: " + load.getPowerDrawn());
        assertTrue(mppt.outputPowerWatts <= 385.0, "MPPT still capped by solar limit: " + mppt.outputPowerWatts);

        // Step 5: Disconnect Solar (+) terminal
        manager.removeCable(pos(0, 64, 1));
        stepTicks(5);
        assertEquals(0.0, mppt.inputVoltage, 1e-6, "Input V is 0 without Solar (+)");
        assertEquals(0.0, mppt.outputPowerWatts, 1e-6, "MPPT Pout is 0 without Solar (+)");
        assertTrue(load.getPowerDrawn() > 2000.0, "Battery continues powering load without Solar");

        // Reconnect Solar (+)
        manager.putCable(pos(0, 64, 1), ConductorType.INSULATED_COPPER);
        stepTicks(5);
        assertTrue(mppt.outputPowerWatts > 200.0, "MPPT resumes contributing solar power");

        // Step 6: Disconnect Solar (-) terminal
        manager.removeCable(pos(1, 64, 6));
        stepTicks(5);
        assertEquals(0.0, mppt.inputVoltage, 1e-6, "Input V is 0 without Solar (-)");
        assertEquals(0.0, mppt.outputPowerWatts, 1e-6, "MPPT Pout is 0 without Solar (-)");
        assertTrue(load.getPowerDrawn() > 2000.0, "Battery continues powering load without Solar (-)");

        // Reconnect Solar (-)
        manager.putCable(pos(1, 64, 6), ConductorType.INSULATED_COPPER);
        stepTicks(5);
        assertTrue(mppt.outputPowerWatts > 200.0, "MPPT resumes contributing solar power");
    }

    @Test
    @DisplayName("7. Test Case: Breaking solar panel block must stop charging completely")
    void testBreakingSolarPanelStopsCharging() {
        connectFullCircuit();
        stepTicks(5);
        assertTrue(mppt.outputPowerWatts > 50.0, "Initially charging from solar");
        assertTrue(battery.getTerminalCurrent() < -1.0, "Battery is charging");

        // Player breaks the solar panel block
        manager.removeAttachedBlock(pos(0, 64, 0));
        mppt.solarPanels.clear();

        stepTicks(5);
        System.out.println("SOLAR BROKEN: Vin=" + mppt.inputVoltage + ", Pin=" + mppt.inputPowerWatts
            + ", Vout=" + mppt.actualOutputVoltage + ", Pout=" + mppt.outputPowerWatts
            + ", BatI=" + battery.getTerminalCurrent());

        // When solar panel is broken, MPPT must NOT continue charging!
        assertEquals(0.0, mppt.outputPowerWatts, 0.05,
            "TEST FAILURE: MPPT continues charging even after solar panel block is destroyed! Output: " + mppt.outputPowerWatts);
        assertTrue(battery.getTerminalCurrent() >= -0.01,
            "TEST FAILURE: Battery is still being charged after solar panel was broken! Current: " + battery.getTerminalCurrent());
    }

    @Test
    @DisplayName("8. Test Case: 500W load cable disconnect and reconnect must resume load and keep MPPT active")
    void test500WLoadCableBrokenAndReconnected() {
        connectFullCircuit();
        // Step 1: 500W load active
        load.enabled = true;
        load.targetWatts = 500.0;
        stepTicks(5);
        System.out.println("STEP 1 (500W Active): LoadP=" + load.getPowerDrawn()
            + ", MPPT Tripped=" + mppt.tripped
            + ", MPPT Iout=" + mppt.outputCurrentAmps
            + ", MPPT Pout=" + mppt.outputPowerWatts);

        // Step 2: Break load cable
        BlockPos loadCable = pos(4, 64, 11);
        manager.removeCable(loadCable);
        stepTicks(5);
        System.out.println("STEP 2 (Cable Broken): LoadP=" + load.getPowerDrawn()
            + ", MPPT Tripped=" + mppt.tripped
            + ", BatV=" + battery.getTerminalVoltage()
            + ", BatI=" + battery.getTerminalCurrent());

        // Check stale telemetry when cable is broken
        boolean staleTelemetry = (load.getPowerDrawn() > 10.0);
        System.out.println("STALE TELEMETRY CHECK: Load power after breaking cable = " + load.getPowerDrawn()
            + " (Stale telemetry present=" + staleTelemetry + ", Bat discharge=" + battery.getTerminalCurrent() + " A)");

        // Step 3: Place load cable back
        manager.putCable(loadCable, ConductorType.HEAVY_COPPER);
        stepTicks(5);

        System.out.println("LOAD RECONNECTED: LoadP=" + load.getPowerDrawn()
            + ", MPPT Tripped=" + mppt.tripped
            + ", MPPT Pout=" + mppt.outputPowerWatts
            + ", BatV=" + battery.getTerminalVoltage());

        assertFalse(mppt.tripped, "TEST FAILURE: MPPT tripped permanently when load cable was broken/reconnected!");
        assertTrue(load.getPowerDrawn() > 400.0, "TEST FAILURE: Load failed to resume drawing 500W after cable was reconnected! Got: " + load.getPowerDrawn());
    }

    @Test
    @DisplayName("9. Test Case: MPPT cable disconnect/reconnect must auto-recover and resume charging")
    void testMpptCableDisconnectAndReconnect() {
        connectFullCircuit();
        stepTicks(5);
        assertTrue(mppt.outputPowerWatts > 50.0, "Initially charging");

        // Disconnect MPPT input cable for 6 ticks (triggers UVLO counter >= 5 ticks)
        BlockPos inCable = pos(0, 64, 5);
        manager.removeCable(inCable);
        stepTicks(6);

        // Reconnect MPPT input cable
        manager.putCable(inCable, ConductorType.INSULATED_COPPER);
        stepTicks(5);

        System.out.println("MPPT RECONNECTED: Tripped=" + mppt.tripped
            + ", Vin=" + mppt.inputVoltage
            + ", Pout=" + mppt.outputPowerWatts);

        assertFalse(mppt.tripped, "TEST FAILURE: MPPT latched tripped permanently on temporary input cable loss!");
        assertTrue(mppt.outputPowerWatts > 50.0, "TEST FAILURE: MPPT did not recover and resume charging after reconnect! Got: " + mppt.outputPowerWatts);
    }

    @Test
    @DisplayName("10. Test Case: Hot-swapping Battery block while MPPT and 500W load are running")
    void testHotSwappingBatteryBlockUnderLoad() {
        connectFullCircuit();
        load.enabled = true;
        load.targetWatts = 500.0;
        stepTicks(5);
        assertEquals(500.0, load.getPowerDrawn(), 15.0, "Load is initially 500W");

        // Player breaks the battery block
        manager.removeAttachedBlock(battery.pos);
        stepTicks(5);
        System.out.println("BATTERY REMOVED: LoadP=" + load.getPowerDrawn()
            + ", MPPT Tripped=" + mppt.tripped
            + ", MPPT Vout=" + mppt.actualOutputVoltage
            + ", MPPT Pout=" + mppt.outputPowerWatts);

        // Player places a new battery back in the same slot
        battery = new BatteryModel(pos(0, 64, 12), Direction.NORTH);
        manager.putAttachedBlock(battery);
        stepTicks(5);

        System.out.println("BATTERY REPLACED: LoadP=" + load.getPowerDrawn()
            + ", MPPT Tripped=" + mppt.tripped
            + ", BatV=" + battery.getTerminalVoltage()
            + ", BatI=" + battery.getTerminalCurrent());

        assertFalse(mppt.tripped, "BUG DETECTED: MPPT tripped permanently when battery was hot-swapped!");
        assertTrue(battery.getTerminalVoltage() > 11.0, "Battery terminal voltage must be active (>11V), got: " + battery.getTerminalVoltage());
        assertTrue(load.getPowerDrawn() > 400.0, "BUG DETECTED: Load failed to resume 500W after battery was replaced! Got: " + load.getPowerDrawn());
    }

    @Test
    @DisplayName("11. Test Case: Hot-swapping MPPT block while Solar and Battery are connected")
    void testHotSwappingMpptBlock() {
        connectFullCircuit();
        stepTicks(5);
        assertTrue(mppt.outputPowerWatts > 50.0, "Initially charging");

        // Player breaks MPPT block
        manager.removeAttachedBlock(mppt.pos);
        stepTicks(5);

        // Player places new MPPT block
        mppt = new MPPTModel(pos(0, 64, 6), Direction.SOUTH);
        mppt.solarPanels.add(solar);
        manager.putAttachedBlock(mppt);
        stepTicks(5);

        System.out.println("MPPT REPLACED: Tripped=" + mppt.tripped
            + ", Vin=" + mppt.inputVoltage
            + ", Vout=" + mppt.actualOutputVoltage
            + ", Pout=" + mppt.outputPowerWatts
            + ", BatI=" + battery.getTerminalCurrent());

        assertFalse(mppt.tripped, "New MPPT should not be tripped");
        assertTrue(mppt.outputPowerWatts > 50.0, "BUG DETECTED: Newly placed MPPT failed to start charging battery! Got: " + mppt.outputPowerWatts);
        assertTrue(battery.getTerminalCurrent() < -1.0, "Battery should be receiving charge from new MPPT");
    }

    @Test
    @DisplayName("12. Test Case: Asymmetrical battery terminal disconnect (+ then -) and reconnect under 500W load")
    void testAsymmetricalSequentialBatteryCableDisconnectReconnectUnderLoad() {
        connectFullCircuit();
        load.enabled = true;
        load.targetWatts = 500.0;
        stepTicks(5);
        assertEquals(500.0, load.getPowerDrawn(), 15.0, "Load initially 500W");

        // Step A: Disconnect Battery (+) cable only (leaving Battery (-) cable connected)
        BlockPos batPlusCable = pos(0, 64, 13);
        manager.removeCable(batPlusCable);
        stepTicks(5);
        System.out.println("STEP A (Bat+ Disconnected): BatI=" + battery.getTerminalCurrent()
            + ", BatV=" + battery.getTerminalVoltage()
            + ", LoadP=" + load.getPowerDrawn());

        // Step B: Disconnect Battery (-) cable as well
        BlockPos batMinusCable = pos(0, 64, 11);
        manager.removeCable(batMinusCable);
        stepTicks(5);
        System.out.println("STEP B (Both Bat Disconnected): BatI=" + battery.getTerminalCurrent()
            + ", LoadP=" + load.getPowerDrawn());

        // Step C: Reconnect Battery (+) cable ONLY (one wire connected, one open)
        manager.putCable(batPlusCable, ConductorType.HEAVY_COPPER);
        stepTicks(5);
        System.out.println("STEP C (Only Bat+ Reconnected): BatI=" + battery.getTerminalCurrent()
            + ", LoadP=" + load.getPowerDrawn());

        // Step D: Reconnect Battery (-) cable (both wires restored)
        manager.putCable(batMinusCable, ConductorType.HEAVY_COPPER);
        stepTicks(5);
        System.out.println("STEP D (Both Bat Reconnected): BatI=" + battery.getTerminalCurrent()
            + ", BatV=" + battery.getTerminalVoltage()
            + ", LoadP=" + load.getPowerDrawn()
            + ", MPPT Tripped=" + mppt.tripped);

        assertFalse(mppt.tripped, "BUG DETECTED: MPPT tripped permanently during battery cable reconnect!");
        assertTrue(load.getPowerDrawn() > 400.0, "BUG DETECTED: Load failed to recover 500W after battery reconnect! Got: " + load.getPowerDrawn());
    }

    @Test
    @DisplayName("13. Test Case: Hot-swapping Solar Panel block while MPPT and Battery are live")
    void testHotSwappingSolarPanelBlock() {
        connectFullCircuit();
        stepTicks(5);
        assertTrue(mppt.outputPowerWatts > 50.0, "Initially charging");

        // Player breaks Solar Panel block
        manager.removeAttachedBlock(solar.pos);
        mppt.solarPanels.clear();
        stepTicks(5);

        System.out.println("SOLAR BROKEN: MPPT Vin=" + mppt.inputVoltage
            + ", MPPT Pout=" + mppt.outputPowerWatts
            + ", BatI=" + battery.getTerminalCurrent());

        // Player places a new Solar Panel at the same position
        solar = new SolarPanelModel(pos(0, 64, 0), Direction.NORTH);
        mppt.solarPanels.add(solar);
        manager.putAttachedBlock(solar);
        stepTicks(5);

        System.out.println("SOLAR REPLACED: MPPT Vin=" + mppt.inputVoltage
            + ", MPPT Pout=" + mppt.outputPowerWatts
            + ", BatI=" + battery.getTerminalCurrent());

        assertTrue(mppt.outputPowerWatts > 50.0, "BUG DETECTED: MPPT failed to resume charging after new solar panel placed! Got: " + mppt.outputPowerWatts);
        assertTrue(battery.getTerminalCurrent() < -1.0, "Battery should be charging from new solar panel");
    }

    @Test
    @DisplayName("14. Test Case: Load mode switching (Power <-> Resistance) surviving cable churn")
    void testLoadModeSwitchingUnderCableChurn() {
        connectFullCircuit();
        load.enabled = true;
        load.targetWatts = 500.0;
        stepTicks(5);
        assertEquals(500.0, load.getPowerDrawn(), 15.0, "Constant Power 500W active");

        // Disconnect load cable
        BlockPos loadCable = pos(4, 64, 11);
        manager.removeCable(loadCable);
        stepTicks(5);

        // Reconnect load cable
        manager.putCable(loadCable, ConductorType.HEAVY_COPPER);
        stepTicks(5);
        assertTrue(load.getPowerDrawn() > 400.0, "Load resumes 500W after cable reconnect");

        // Switch to 1000W load
        load.targetWatts = 1000.0;
        stepTicks(5);
        System.out.println("LOAD 1000W: LoadP=" + load.getPowerDrawn()
            + ", BatV=" + battery.getTerminalVoltage()
            + ", BatI=" + battery.getTerminalCurrent()
            + ", MPPT Pout=" + mppt.outputPowerWatts);

        assertTrue(load.getPowerDrawn() > 900.0, "Load should draw ~1000W: " + load.getPowerDrawn());
    }

    @Test
    @DisplayName("15. Test Case: Cold-start 1000W+ load without tripping Battery BMS vs 100W soft-start ramp-up")
    void testBatteryColdStartHeavyLoadVsSoftStartRampUp() {
        // --- CASE A: 100W Soft-Start then ramp to 1000W ---
        GridManager softManager = new GridManager();
        BatteryModel softBattery = new BatteryModel(pos(0, 64, 12), Direction.NORTH, BatteryChemistry.LEAD_ACID, 6, 1);
        LoadModel softLoad = new LoadModel(pos(2, 64, 12), Direction.NORTH);
        softLoad.enabled = true;
        softLoad.targetWatts = 100.0;

        softManager.putAttachedBlock(softBattery);
        softManager.putAttachedBlock(softLoad);

        // Battery at (0, 64, 12), Load at (3, 64, 12)
        // Terminals: Bat(-) at (0, 64, 11), Bat(+) at (0, 64, 13)
        // Load(-) at (3, 64, 11), Load(+) at (3, 64, 13)
        for (int x = 0; x <= 3; x++) {
            softManager.putCable(pos(x, 64, 11), ConductorType.INSULATED_COPPER);
            softManager.putCable(pos(x, 64, 13), ConductorType.INSULATED_COPPER);
        }

        for (int i = 0; i < 5; i++) {
            softManager.tick(null);
            System.out.println("SOFT TICK " + i + ": Islands=" + softManager.getIslands().size());
            for (int isl = 0; isl < softManager.getIslands().size(); isl++) {
                IslandContext c = softManager.getIslands().get(isl);
                System.out.println("  Island " + isl + ": nodes=" + c.nodeIndex().keySet() + ", blocks=" + c.blocks().size());
            }
            System.out.println("  BatV=" + softBattery.getTerminalVoltage()
                + ", BatI=" + softBattery.getTerminalCurrent()
                + ", LoadP=" + softLoad.getPowerDrawn()
                + ", BMS Open=" + softBattery.bmsOpen);
        }

        assertFalse(softBattery.bmsOpen, "BMS must remain closed at 100W load");
        assertEquals(100.0, softLoad.getPowerDrawn(), 5.0, "Load draws 100W");

        // Now ramp up load to 1000W in the same running circuit
        softLoad.targetWatts = 1000.0;
        for (int i = 0; i < 5; i++) {
            softManager.tick(null);
            System.out.println("RAMP 1000W TICK " + i + ": BatV=" + softBattery.getTerminalVoltage()
                + ", BatI=" + softBattery.getTerminalCurrent()
                + ", LoadP=" + softLoad.getPowerDrawn()
                + ", BMS Open=" + softBattery.bmsOpen);
        }

        assertFalse(softBattery.bmsOpen, "BMS must remain closed when ramped from 100W to 1000W");
        assertTrue(softLoad.getPowerDrawn() > 900.0, "Load draws ~1000W when ramped");

        // Now ramp further to 1300W
        softLoad.targetWatts = 1300.0;
        for (int i = 0; i < 5; i++) {
            softManager.tick(null);
            System.out.println("RAMP 1300W TICK " + i + ": BatV=" + softBattery.getTerminalVoltage()
                + ", BatI=" + softBattery.getTerminalCurrent()
                + ", LoadP=" + softLoad.getPowerDrawn()
                + ", BMS Open=" + softBattery.bmsOpen);
        }

        // --- CASE B: Cold Start directly at 1000W ---
        // NOTE: heavy-gauge bus, mirroring the in-engine rig. Thin insulated
        // wire (6.8 mOhm/segment) caps this run at ~930 W of maximum power
        // transfer, so 1000 W is physically undeliverable there no matter the
        // numerics; the rig under test uses heavy copper.
        GridManager cold1000Manager = new GridManager();
        BatteryModel coldBattery1000 = new BatteryModel(pos(0, 64, 12), Direction.NORTH, BatteryChemistry.LEAD_ACID, 6, 1);
        LoadModel coldLoad1000 = new LoadModel(pos(3, 64, 12), Direction.NORTH);
        coldLoad1000.enabled = true;
        coldLoad1000.targetWatts = 1000.0;

        cold1000Manager.putAttachedBlock(coldBattery1000);
        cold1000Manager.putAttachedBlock(coldLoad1000);

        for (int x = 0; x <= 3; x++) {
            cold1000Manager.putCable(pos(x, 64, 11), ConductorType.HEAVY_COPPER);
            cold1000Manager.putCable(pos(x, 64, 13), ConductorType.HEAVY_COPPER);
        }

        for (int i = 0; i < 5; i++) {
            cold1000Manager.tick(null);
        }

        System.out.println("COLD START 1000W: LoadP=" + coldLoad1000.getPowerDrawn()
            + ", BatV=" + coldBattery1000.getTerminalVoltage()
            + ", BatI=" + coldBattery1000.getTerminalCurrent()
            + ", BMS Open=" + coldBattery1000.bmsOpen);

        // --- CASE C: Cold Start directly at 1500W+ (triggers full BMS protection shutdown) ---
        GridManager cold1500Manager = new GridManager();
        BatteryModel coldBattery1500 = new BatteryModel(pos(0, 64, 12), Direction.NORTH, BatteryChemistry.LEAD_ACID, 6, 1);
        LoadModel coldLoad1500 = new LoadModel(pos(3, 64, 12), Direction.NORTH);
        coldLoad1500.enabled = true;
        coldLoad1500.targetWatts = 1500.0;

        cold1500Manager.putAttachedBlock(coldBattery1500);
        cold1500Manager.putAttachedBlock(coldLoad1500);

        for (int x = 0; x <= 3; x++) {
            cold1500Manager.putCable(pos(x, 64, 11), ConductorType.HEAVY_COPPER);
            cold1500Manager.putCable(pos(x, 64, 13), ConductorType.HEAVY_COPPER);
        }

        for (int i = 0; i < 5; i++) {
            cold1500Manager.tick(null);
        }

        System.out.println("COLD START 1500W: LoadP=" + coldLoad1500.getPowerDrawn()
            + ", BatV=" + coldBattery1500.getTerminalVoltage()
            + ", BatI=" + coldBattery1500.getTerminalCurrent()
            + ", BMS Open=" + coldBattery1500.bmsOpen);

        // Assert desired behavior: cold start should deliver 1000W and not trip BMS into protection
        assertEquals(1000.0, coldLoad1000.getPowerDrawn(), 50.0,
            "BUG DETECTED: Cold start at 1000W collapsed to fallback (" + coldLoad1000.getPowerDrawn() + " W) drawing 268A!");
        assertFalse(coldBattery1500.bmsOpen,
            "BUG DETECTED: Battery BMS tripped into protection on cold heavy load connection!");
    }

    @Test
    @DisplayName("16. Test Case: Sequential cable disconnect and reconnect across entire working circuit under 500W load")
    void testSequentialCableChurnAcrossWorkingCircuitUnderLoad() {
        connectFullCircuit();
        load.enabled = true;
        load.targetWatts = 500.0;
        stepTicks(10);

        System.out.println("--- BASELINE ACTIVE CIRCUIT ---");
        System.out.println("LoadP=" + load.getPowerDrawn()
            + ", MPPT Pout=" + mppt.outputPowerWatts
            + ", MPPT Tripped=" + mppt.tripped
            + ", BatV=" + battery.getTerminalVoltage()
            + ", BatI=" + battery.getTerminalCurrent());

        assertEquals(500.0, load.getPowerDrawn(), 15.0, "Initial load must be ~500W");
        assertTrue(mppt.outputPowerWatts > 50.0, "MPPT must initially deliver power");

        // --- STAGE 1: Disconnect & Reconnect MPPT Out(+) cable ---
        BlockPos mpptOutPlus = pos(0, 64, 7);
        manager.removeCable(mpptOutPlus);
        stepTicks(5);
        System.out.println("STAGE 1 (MPPT Out+ Broken): LoadP=" + load.getPowerDrawn()
            + ", BatI=" + battery.getTerminalCurrent()
            + ", MPPT Iout=" + mppt.outputCurrentAmps
            + ", MPPT Tripped=" + mppt.tripped);

        // Load must be carried entirely by battery
        assertTrue(load.getPowerDrawn() > 400.0, "Battery should carry load when MPPT Out+ is disconnected");
        assertTrue(battery.getTerminalCurrent() > 30.0, "Battery should discharge heavily to carry 500W");

        // Reconnect MPPT Out(+)
        manager.putCable(mpptOutPlus, ConductorType.HEAVY_COPPER);
        stepTicks(5);
        System.out.println("STAGE 1 (MPPT Out+ Restored): LoadP=" + load.getPowerDrawn()
            + ", MPPT Pout=" + mppt.outputPowerWatts
            + ", MPPT Tripped=" + mppt.tripped);

        assertFalse(mppt.tripped, "BUG DETECTED: MPPT tripped permanently when Out(+) cable was reconnected!");
        assertTrue(mppt.outputPowerWatts > 50.0, "MPPT must resume delivering power after Out(+) reconnected");

        // --- STAGE 2: Disconnect & Reconnect MPPT Out(-) cable ---
        BlockPos mpptOutMinus = pos(-1, 64, 6);
        manager.removeCable(mpptOutMinus);
        stepTicks(5);
        System.out.println("STAGE 2 (MPPT Out- Broken): LoadP=" + load.getPowerDrawn()
            + ", MPPT Tripped=" + mppt.tripped);

        manager.putCable(mpptOutMinus, ConductorType.HEAVY_COPPER);
        stepTicks(5);
        System.out.println("STAGE 2 (MPPT Out- Restored): LoadP=" + load.getPowerDrawn()
            + ", MPPT Pout=" + mppt.outputPowerWatts
            + ", MPPT Tripped=" + mppt.tripped);

        assertFalse(mppt.tripped, "BUG DETECTED: MPPT tripped permanently when Out(-) cable was reconnected!");

        // --- STAGE 3: Disconnect & Reconnect Load(+) cable ---
        BlockPos loadPlus = pos(4, 64, 13);
        manager.removeCable(loadPlus);
        stepTicks(5);
        System.out.println("STAGE 3 (Load+ Broken): LoadP=" + load.getPowerDrawn()
            + ", BatI=" + battery.getTerminalCurrent()
            + ", MPPT Pout=" + mppt.outputPowerWatts);

        // If load cable is broken, real power delivered must be 0W
        boolean loadShowsPowerThroughAir = (load.getPowerDrawn() > 10.0);
        System.out.println("STAGE 3: Stale load telemetry bug present=" + loadShowsPowerThroughAir);

        // Reconnect Load(+)
        manager.putCable(loadPlus, ConductorType.HEAVY_COPPER);
        stepTicks(5);
        System.out.println("STAGE 3 (Load+ Restored): LoadP=" + load.getPowerDrawn()
            + ", MPPT Tripped=" + mppt.tripped);

        assertTrue(load.getPowerDrawn() > 400.0, "BUG DETECTED: Load failed to resume 500W after Load(+) cable restored! Got: " + load.getPowerDrawn());

        // --- STAGE 4: Disconnect & Reconnect Battery(+) cable under 500W load ---
        BlockPos batPlus = pos(0, 64, 13);
        manager.removeCable(batPlus);
        stepTicks(5);
        System.out.println("STAGE 4 (Bat+ Broken under 500W): LoadP=" + load.getPowerDrawn()
            + ", MPPT Pout=" + mppt.outputPowerWatts
            + ", MPPT Tripped=" + mppt.tripped);

        // Reconnect Battery(+)
        manager.putCable(batPlus, ConductorType.HEAVY_COPPER);
        stepTicks(5);
        System.out.println("STAGE 4 (Bat+ Restored): LoadP=" + load.getPowerDrawn()
            + ", BatV=" + battery.getTerminalVoltage()
            + ", MPPT Tripped=" + mppt.tripped);

        assertFalse(mppt.tripped, "BUG DETECTED: MPPT tripped permanently when Battery(+) cable was reconnected!");
        assertTrue(load.getPowerDrawn() > 400.0, "BUG DETECTED: Load failed to recover 500W after Battery(+) restored!");

        // --- STAGE 5: Disconnect & Reconnect Solar(+) cable under 500W load ---
        BlockPos solarPlus = pos(0, 64, 3);
        manager.removeCable(solarPlus);
        stepTicks(5);
        System.out.println("STAGE 5 (Solar+ Broken): LoadP=" + load.getPowerDrawn()
            + ", MPPT Vin=" + mppt.inputVoltage
            + ", MPPT Pout=" + mppt.outputPowerWatts
            + ", BatI=" + battery.getTerminalCurrent());

        assertEquals(0.0, mppt.inputVoltage, 1e-6, "MPPT Vin should be 0 without solar");
        assertTrue(load.getPowerDrawn() > 400.0, "Battery carries load without solar");

        // Reconnect Solar(+)
        manager.putCable(solarPlus, ConductorType.INSULATED_COPPER);
        stepTicks(5);
        System.out.println("STAGE 5 (Solar+ Restored): LoadP=" + load.getPowerDrawn()
            + ", MPPT Vin=" + mppt.inputVoltage
            + ", MPPT Pout=" + mppt.outputPowerWatts);

        assertTrue(mppt.inputVoltage > 30.0, "Solar input voltage should be restored");
        assertTrue(mppt.outputPowerWatts > 50.0, "BUG DETECTED: MPPT failed to resume solar output after Solar(+) restored!");
    }

    @Test
    @DisplayName("17. Test Case: Bus segment disconnect dividing circuit into separate islands and reuniting them under load")
    void testBusSegmentDisconnectIslandSplitAndReunite() {
        connectFullCircuit();
        load.enabled = true;
        load.targetWatts = 500.0;
        stepTicks(10);
        assertEquals(500.0, load.getPowerDrawn(), 15.0, "Circuit fully running at 500W");

        // Sectionalize the MINUS rail between Battery/MPPT and Load: cutting
        // pos(2, 64, 11) leaves the load (+) lead live off the ring bus but
        // with no return, so the open-ported load draws nothing, while the
        // MPPT -> battery loop (output (+) via the ring, output (-) via the
        // intact left minus segment) keeps charging in the source island.
        // NOTE: cutting the (+) rail instead would strand Battery(+) on a
        // stub and stop charging; a single (+) cut on this ring bus does not
        // island anything at all (feeds around), so minus-rail sectionalizing
        // is the physically meaningful split here.
        BlockPos busMidMinus = pos(2, 64, 11);
        manager.removeCable(busMidMinus);
        stepTicks(5);

        System.out.println("BUS CUT (Split Islands): LoadP=" + load.getPowerDrawn()
            + ", BatV=" + battery.getTerminalVoltage()
            + ", BatI=" + battery.getTerminalCurrent()
            + ", MPPT Pout=" + mppt.outputPowerWatts
            + ", MPPT Tripped=" + mppt.tripped);

        // In Island A: MPPT charges battery (battery current negative)
        assertTrue(battery.getTerminalCurrent() < -1.0, "Battery should be charging from MPPT in Island A");
        assertFalse(mppt.tripped, "MPPT should not trip when load branch is separated");

        // Reconnect bus segment pos(2, 64, 11) to reunite islands
        manager.putCable(busMidMinus, ConductorType.HEAVY_COPPER);
        stepTicks(5);

        System.out.println("BUS RESTORED (Reunited): LoadP=" + load.getPowerDrawn()
            + ", BatV=" + battery.getTerminalVoltage()
            + ", BatI=" + battery.getTerminalCurrent()
            + ", MPPT Pout=" + mppt.outputPowerWatts
            + ", MPPT Tripped=" + mppt.tripped);

        assertFalse(mppt.tripped, "BUG DETECTED: MPPT tripped permanently when bus segment was restored!");
        assertTrue(load.getPowerDrawn() > 400.0, "BUG DETECTED: Load failed to resume 500W after bus was reunited! Got: " + load.getPowerDrawn());
    }

    @Test
    @DisplayName("19. Test Case: Single-wire load disconnect (-) vs (+) must both read ~0W, not freeze at 500W")
    void testSingleWireLoadDisconnectMinusVsPlus() {
        connectFullCircuit();
        load.enabled = true;
        load.targetWatts = 500.0;
        stepTicks(5);
        assertEquals(500.0, load.getPowerDrawn(), 15.0, "Precondition: load initially 500W");

        // --- CUT A: load (-) cable at its own terminal -> open circuit, must read ~0W.
        manager.removeCable(pos(4, 64, 11));
        stepTicks(5);
        System.out.println("T19 MINUS-CUT: LoadP=" + load.getPowerDrawn()
            + ", BatV=" + battery.getTerminalVoltage()
            + ", BatI=" + battery.getTerminalCurrent()
            + ", MPPT Pout=" + mppt.outputPowerWatts
            + ", MPPT Iout=" + mppt.outputCurrentAmps);
        assertTrue(load.getPowerDrawn() < 5.0,
            "BUG DETECTED: load (-) wire cut, but the load still reports ~500W (frozen telemetry / phantom current)! Got: "
                + load.getPowerDrawn());

        // --- Restore, back to 500W.
        manager.putCable(pos(4, 64, 11), ConductorType.HEAVY_COPPER);
        stepTicks(5);
        assertEquals(500.0, load.getPowerDrawn(), 15.0, "Load must resume 500W after (-) restore");

        // --- CUT B: load (+) cable at its own terminal -> open circuit, must read ~0W.
        manager.removeCable(pos(4, 64, 13));
        stepTicks(5);
        System.out.println("T19 PLUS-CUT: LoadP=" + load.getPowerDrawn()
            + ", BatV=" + battery.getTerminalVoltage()
            + ", BatI=" + battery.getTerminalCurrent()
            + ", MPPT Pout=" + mppt.outputPowerWatts
            + ", MPPT Iout=" + mppt.outputCurrentAmps);
        assertTrue(load.getPowerDrawn() < 5.0,
            "BUG DETECTED: load (+) wire cut, but the load still reports power! Got: "
                + load.getPowerDrawn());

        // --- Restore, back to 500W, MPPT healthy.
        manager.putCable(pos(4, 64, 13), ConductorType.HEAVY_COPPER);
        stepTicks(5);
        assertFalse(mppt.tripped, "MPPT must not trip across single-wire load churn");
        assertTrue(load.getPowerDrawn() > 400.0,
            "Load must resume 500W after (+) restore, got: " + load.getPowerDrawn());
    }

    @Test
    @DisplayName("18. Test Case: Nearly-full battery + 100W load -> MPPT must cover load + keep charging (~140W), not sag to ~40W")
    void testNearlyFullBatteryLightLoadMpptCoversLoadAndCharging() {
        // Mirror the player rig: 6S lead-acid bank, nearly full (97% SoC).
        manager.removeAttachedBlock(battery.pos);
        battery = new BatteryModel(pos(0, 64, 12), Direction.NORTH, BatteryChemistry.LEAD_ACID, 6, 1);
        manager.putAttachedBlock(battery);
        connectFullCircuit();
        battery.stateArray[BatteryElement.STATE_SOC] = 0.97;
        load.enabled = true;
        load.targetWatts = 100.0;
        for (int t = 1; t <= 10; t++) {
            manager.tick(null);
            System.out.println("T18 TICK " + t + ": LoadP=" + load.getPowerDrawn()
                + ", MPPT Pout=" + mppt.outputPowerWatts
                + ", Vout=" + mppt.actualOutputVoltage
                + ", Stage=" + mppt.mpptLogic.getStage()
                + ", BatV=" + battery.getTerminalVoltage()
                + ", BatI=" + battery.getTerminalCurrent()
                + ", SoC=" + battery.stateArray[BatteryElement.STATE_SOC]);
        }

        assertEquals(100.0, load.getPowerDrawn(), 10.0, "Precondition: load draws ~100W");
        // Spec: 100W load + ~40W absorption top-up ~= 140W from MPPT while solar
        // has ~380W headroom. MPPT must load-follow, not collapse to ~40W and let
        // the nearly-full battery drain into the load.
        assertTrue(mppt.outputPowerWatts >= 120.0,
            "BUG DETECTED: MPPT sags to ~40W under 100W load on a nearly-full battery instead of ~140W (load + charging)! Got: "
                + mppt.outputPowerWatts);
        assertTrue(battery.getTerminalCurrent() < 0.0,
            "BUG DETECTED: Battery discharges into a 100W load despite ~380W of solar headroom! BatI: "
                + battery.getTerminalCurrent());
    }

    @Test
    @DisplayName("20. Test Case: hotter bank on the output bus must not backfeed the MPPT")
    void testHotterBankDoesNotBackfeedMppt() {
        // Player rig: a second, fuller bank (7S lead, OCV ~16.7V) holds the
        // 12V-bank MPPT output bus ABOVE the charge target (~14.7V). A buck
        // charger must idle (ideal-diode OR-ing), not sink pack current
        // backwards while the GUI reports an honest 0W at ~0A (not 0W at 7+A).
        manager.removeAttachedBlock(battery.pos);
        battery = new BatteryModel(pos(0, 64, 12), Direction.NORTH, BatteryChemistry.LEAD_ACID, 7, 1);
        manager.putAttachedBlock(battery);
        connectFullCircuit();
        battery.stateArray[BatteryElement.STATE_SOC] = 0.97;
        load.enabled = true;
        load.targetWatts = 100.0;
        stepTicks(10);
        System.out.println("T20 BACKFEED: MPPT Iout=" + mppt.outputCurrentAmps
            + ", Pout=" + mppt.outputPowerWatts
            + ", Vout=" + mppt.actualOutputVoltage
            + ", BatV=" + battery.getTerminalVoltage()
            + ", BatI=" + battery.getTerminalCurrent()
            + ", LoadP=" + load.getPowerDrawn());
        assertTrue(mppt.outputCurrentAmps < 1.0,
            "BUG DETECTED: hotter bank backfeeds the MPPT output, charger sinks pack current! Iout: "
                + mppt.outputCurrentAmps);
        assertTrue(mppt.outputPowerWatts < 15.0,
            "MPPT must idle honestly near 0W when the bus outranks its target, got: "
                + mppt.outputPowerWatts);
        assertFalse(mppt.tripped, "MPPT must not trip on a hotter bus, it must idle");
    }

    @Test
    @DisplayName("21. Test Case: MPPT output-minus disconnect must stop MPPT contribution")
    void testMpptOutputMinusDisconnect() {
        connectFullCircuit();
        load.enabled = true;
        load.targetWatts = 500.0;
        stepTicks(5);
        assertEquals(500.0, load.getPowerDrawn(), 15.0, "Precondition: load initially 500W");

        // Cut MPPT Out(-) at its own terminal: output loop opens, MPPT must
        // contribute nothing (no sneak current through the other leg).
        manager.removeCable(pos(-1, 64, 6));
        stepTicks(5);
        System.out.println("T21 OUT-MINUS-CUT: MPPT Pout=" + mppt.outputPowerWatts
            + ", Iout=" + mppt.outputCurrentAmps
            + ", LoadP=" + load.getPowerDrawn()
            + ", BatI=" + battery.getTerminalCurrent());
        assertTrue(mppt.outputPowerWatts < 5.0,
            "BUG DETECTED: MPPT output-minus cut, but the charger still delivers power! Got: "
                + mppt.outputPowerWatts);
        assertTrue(load.getPowerDrawn() > 400.0,
            "Battery must carry the 500W load alone while MPPT output is open, got: "
                + load.getPowerDrawn());

        manager.putCable(pos(-1, 64, 6), ConductorType.HEAVY_COPPER);
        stepTicks(5);
        assertFalse(mppt.tripped, "MPPT must not trip across output-minus churn");
        assertTrue(mppt.outputPowerWatts > 50.0,
            "MPPT must resume after output-minus restore, got: " + mppt.outputPowerWatts);
    }

    @Test
    @DisplayName("22. Test Case: MPPT input-minus disconnect must stop the charger")
    void testMpptInputMinusDisconnect() {
        connectFullCircuit();
        stepTicks(5);
        assertTrue(mppt.outputPowerWatts > 50.0, "Precondition: charging");

        // Cut solar return at (1, 64, 6): input loop opens, charger must go
        // fully dark (no input, no output, no phantom).
        manager.removeCable(pos(1, 64, 6));
        stepTicks(5);
        System.out.println("T22 IN-MINUS-CUT: Vin=" + mppt.inputVoltage
            + ", Pin=" + mppt.inputPowerWatts
            + ", Pout=" + mppt.outputPowerWatts);
        assertEquals(0.0, mppt.inputVoltage, 1e-6, "Input voltage must be 0V without return");
        assertEquals(0.0, mppt.outputPowerWatts, 1e-6, "Output must be 0W without solar return");

        manager.putCable(pos(1, 64, 6), ConductorType.INSULATED_COPPER);
        stepTicks(5);
        assertFalse(mppt.tripped, "MPPT must not trip across input-minus churn");
        assertTrue(mppt.outputPowerWatts > 50.0,
            "MPPT must resume after input-minus restore, got: " + mppt.outputPowerWatts);
    }
}

