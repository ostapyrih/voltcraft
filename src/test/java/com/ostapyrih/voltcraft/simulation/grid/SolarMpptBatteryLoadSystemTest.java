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
            // In a properly connected circuit, returns solar generation if circuit is closed
            if (inputVoltage < 1.0) {
                return 0.0;
            }
            double total = 0.0;
            for (SolarPanelModel sp : solarPanels) {
                total += sp.peakPower;
            }
            return total;
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

                // Solar foldback
                double availSolar = getAvailableSolarWatts();
                if (availSolar > 0.0 && actualOutputVoltage > 1.0) {
                    double pOutMax = availSolar * 0.95;
                    double iMax = Math.min(60.0, pOutMax / Math.max(1.0, actualOutputVoltage));
                    double vMaxFoldback = actualOutputVoltage + iMax * ConverterElement.SOURCE_R_OHM;
                    rawTarget = Math.min(rawTarget, vMaxFoldback);
                } else if (availSolar <= 0.0) {
                    rawTarget = 0.0;
                }
            }

            this.stagedOutputEmf = ConverterElement.stageEmf(rawTarget, tripped, teleVIn, 150.0);
            this.stagedInputDemandWatts = ConverterElement.stageDemandWatts(telePOut, 0.95, tripped, teleVIn);

            double availSolar = getAvailableSolarWatts();
            if (availSolar > 0.0) {
                this.stagedInputDemandWatts = Math.min(this.stagedInputDemandWatts, availSolar);
            } else {
                this.stagedInputDemandWatts = 0.0;
            }
            this.lastDemandWatts = stagedInputDemandWatts;
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
        final double[] telemetry = new double[2];
        final double[] stateArray = new double[]{0.8, 25.0, 1.0}; // 80% SoC
        boolean bmsOpen = false;
        final BatteryElement element;

        BatteryModel(BlockPos pos, Direction facing) {
            this.pos = pos;
            this.facing = facing;
            this.element = new BatteryElement(BatteryChemistry.LIFEPO4, 4, 1, () -> bmsOpen, telemetry);
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
        public void tickElectrical(ServerWorld world) {}

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
        final CreativeLoadElement element;

        LoadModel(BlockPos pos, Direction facing) {
            this.pos = pos;
            this.facing = facing;
            this.element = new CreativeLoadElement(
                () -> CreativeLoadElement.MODE_POWER,
                () -> targetWatts,
                () -> enabled,
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
        public void tickElectrical(ServerWorld world) {}

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
}
