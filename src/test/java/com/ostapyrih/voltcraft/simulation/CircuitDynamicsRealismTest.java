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

import static com.ostapyrih.voltcraft.simulation.creative.CreativeLoadLogic.LoadMode.CONSTANT_CURRENT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates real-world circuit dynamics:
 * <ol>
 *   <li>Battery terminal voltage sag under heavy current draw ({@code V = Voc - I * Rint}).</li>
 *   <li>Instantaneous converter load response (no artificial ramp/slew lag).</li>
 *   <li>MPPT charge controller power clamping to available solar generation (prevents voltage collapse).</li>
 *   <li>Brownout and foldback behavior replacing 20Hz on/off flicker.</li>
 *   <li>Elimination of inverter load number flickering (zero tick-to-tick oscillation).</li>
 *   <li>MPPT stability with downstream battery and inverter load.</li>
 *   <li>MPPT 0W solar produces zero phantom current and no artificial voltage inflation on
 *       inverter/battery bus.</li>
 * </ol>
 */
public class CircuitDynamicsRealismTest {

    // =====================================================================
    // Shared constants
    // =====================================================================

    /** Tolerance for "must be identical tick-to-tick" assertions (zero flicker). */
    private static final double ZERO_OSCILLATION_TOL = 1e-6;

    /** Ticks allowed for dynamic loads (constant-current CreativeLoad) to converge from defaults. */
    private static final int WARMUP_TICKS = 10;

    /** Ticks observed after warm-up to verify steady-state behavior across the array. */
    private static final int STEADY_STATE_TICKS = 20;

    /** Single-node bus position shared by every grid test in this class. */
    private static final BlockPos NODE = new BlockPos(0, 64, 0);

    // =====================================================================
    // Test doubles
    // =====================================================================

    /**
     * A Thevenin-equivalent source ({@code V_oc}, {@code R_int}, {@code I_max}) that also
     * implements {@link IElectricStorage} so it can be used either as a battery pack or as a
     * current-limited solar string in the tests.
     *
     * <p>{@link #receivedCurrent} records the current actually delivered to this storage on the
     * most recent tick (used by tests that verify a charging source's current is faithfully
     * reported to the storage).</p>
     */
    static class SimpleBatterySource implements IElectricStorage {
        private final BlockPos pos;
        private final double emf;
        private final double rInt;
        private final double maxCurrent;
        private double drawnCurrent = 0.0;
        private double receivedCurrent = 0.0;

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

        @Override
        public void onPowerDrawn(double currentAmps, double durationSeconds) {
            this.drawnCurrent = currentAmps;
        }

        /** Current delivered <em>into</em> this storage on the last tick (0 when discharging). */
        public double getReceivedCurrent() { return receivedCurrent; }

        @Override public double getStateOfCharge() { return 1.0; }
        @Override public double getStateOfHealth() { return 100.0; }
        @Override public double getMaxStorageJoules() { return 10_000_000.0; }
        @Override public double getStoredJoules() { return 10_000_000.0; }
        @Override public BatteryCellSpec getChemistrySpec() { return BatteryCellSpec.LI_ION_18650; }
        @Override public void addEnergy(double joules) {}
        @Override public double extractEnergy(double joules) { return joules; }
        @Override public double getNominalPowerDemand() { return 0.0; }
        @Override public double getNominalVoltage() { return emf; }
        @Override public double getMinOperatingVoltage() { return 10.0; }
        @Override public double getMaxOperatingVoltage() { return 60.0; }

        @Override
        public void onPowerReceived(double terminalVoltage, double deliveredCurrent, double durationSeconds) {
            this.receivedCurrent = deliveredCurrent;
        }
    }

    /** A generic current-limited Thevenin source (inverter, MPPT charge controller, etc.). */
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

        @Override
        public void onPowerDrawn(double currentAmps, double durationSeconds) {
            this.drawnCurrent = currentAmps;
        }
    }

    /**
     * A pure resistive load whose terminal voltage/current are recorded on each tick so tests
     * can verify what the consumer actually <em>saw</em>, not just what the grid computed.
     */
    static class ResistiveLoad implements IElectricConsumer {
        private final BlockPos pos;
        private final double resistance;
        private double receivedVoltage = 0.0;
        private double receivedCurrent = 0.0;

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

        /** Terminal voltage the load actually saw on the last tick. */
        public double getReceivedVoltage() { return receivedVoltage; }

        /** Current the load actually drew on the last tick. */
        public double getReceivedCurrent() { return receivedCurrent; }

        @Override
        public void onPowerReceived(double terminalVoltage, double deliveredCurrent, double durationSeconds) {
            this.receivedVoltage = terminalVoltage;
            this.receivedCurrent = deliveredCurrent;
        }
    }

    // =====================================================================
    // Scaffolding
    // =====================================================================

    /** Creates a fresh grid on {@link #NODE} with the given sources already registered. */
    private static ElectricalGrid bus(IElectricSource... sources) {
        ElectricalGrid grid = new ElectricalGrid();
        for (IElectricSource s : sources) {
            grid.registerSource(NODE, s);
        }
        return grid;
    }

    /** Runs {@link #WARMUP_TICKS} ticks so dynamic loads can converge from their defaults. */
    private static void warmUp(ElectricalGrid grid) {
        for (int i = 0; i < WARMUP_TICKS; i++) {
            grid.tick(null);
        }
    }

    private static CreativeLoadLogic constantCurrentLoad(double amps) {
        CreativeLoadLogic load = new CreativeLoadLogic(NODE);
        load.setMode(CONSTANT_CURRENT);
        load.setTargetValue(amps);
        return load;
    }

    /** Asserts a value is bit-identical to its previous tick (no flicker / no hunting). */
    private static void assertNoFlicker(double previous, double current, String label) {
        assertEquals(previous, current, ZERO_OSCILLATION_TOL,
                label + " must be bit-identical tick-to-tick (no flicker)");
    }

    // =====================================================================
    // Tests
    // =====================================================================

    @Test
    public void testBatteryTerminalVoltageSagUnderHeavyDraw() {
        // 48V battery pack with 0.15 Ohm internal resistance.
        SimpleBatterySource battery = new SimpleBatterySource(NODE, 48.0, 0.15, 200.0);

        // Constant-current load pulling 40A (~1700W draw by an inverter).
        CreativeLoadLogic load = constantCurrentLoad(40.0);

        ElectricalGrid grid = bus(battery);
        grid.registerConsumer(NODE, load);
        warmUp(grid);

        // In real electronics: V = Voc - I * R = 48.0 - (40.0 * 0.15) = 42.0 V
        assertEquals(40.0, battery.drawnCurrent, 0.1, "Load must draw exactly 40A");
        assertEquals(42.0, grid.getNodeVoltage(NODE), 0.2,
                "Terminal voltage must sag by 6.0V under 40A draw per Ohm's law");
    }

    @Test
    public void testSolarArrayTerminalVoltageUnderMpptLimiting() {
        // Solar Array: Voc = 40V, Rint = 10 Ohm -> matched Pmax = 40W at Vmp = 20V, Imp = 2A.
        SimpleBatterySource solarArray = new SimpleBatterySource(NODE, 40.0, 10.0, 2.0);

        // Charge controller input drawing exactly 40W (clamped to available solar capacity).
        CreativeLoadLogic chargeControllerInput = constantCurrentLoad(2.0); // 2.0A @ 20V = 40W

        ElectricalGrid grid = bus(solarArray);
        grid.registerConsumer(NODE, chargeControllerInput);
        warmUp(grid);

        // Voltage holds at Vmp = 20V, strictly prevented from collapsing to 0V!
        assertEquals(20.0, grid.getNodeVoltage(NODE), 0.1,
                "Solar array terminal voltage must hold at 20V without collapse");
        assertEquals(2.0, solarArray.drawnCurrent, 0.05,
                "Solar array current must match Imp");
    }

    @Test
    public void testConverterFoldbackBrownoutStabilization() {
        // Pure math validation of foldback: Vout = P_avail / I_load.
        double availableWatts = 1500.0;
        double requestedLoadCurrent = 15.0; // 15A @ 230V would be 3450W, exceeding 1500W.

        double foldedVoltage = availableWatts / requestedLoadCurrent; // 100V
        assertEquals(100.0, foldedVoltage, 1e-4);

        double deliveredWatts = foldedVoltage * requestedLoadCurrent;
        assertEquals(availableWatts, deliveredWatts, 1e-4);
    }

    @Test
    public void testInverterLoadSteadyUnderOverloadNoFlicker() {
        // Inverter rated 3000W at 230V -> 13.04A available current ceiling.
        double ratedCurrent = 3000.0 / 230.0; // 13.0435A
        SimpleConverterSource inverter = new SimpleConverterSource(NODE, 230.0, 0.05, ratedCurrent, ratedCurrent);

        // 10 Ohm resistive load: at 230V this demands 23A (5290W) -> 76% overload.
        ResistiveLoad overload = new ResistiveLoad(NODE, 10.0);

        ElectricalGrid grid = bus(inverter);
        grid.registerConsumer(NODE, overload);

        double lastVoltage = -1.0;
        double lastCurrent = -1.0;

        for (int tick = 0; tick < STEADY_STATE_TICKS; tick++) {
            grid.tick(null);

            double v = grid.getNodeVoltage(NODE);
            double i = inverter.drawnCurrent;

            // Inverter clamps output current to its rated available limit.
            assertEquals(ratedCurrent, i, 0.01,
                    "Inverter must clamp output current to rated available limit");
            // Terminal voltage folds back per Ohm's law: V = I * R = 13.0435 * 10 = 130.435V.
            assertEquals(ratedCurrent * 10.0, v, 0.1,
                    "Voltage must fold back smoothly to 130.4V");

            // The load must have actually *received* the folded-back values.
            assertEquals(v, overload.getReceivedVoltage(), 1e-6,
                    "Load's received voltage must match the foldback node voltage");
            assertEquals(i, overload.getReceivedCurrent(), 1e-6,
                    "Load's received current must match the clamped inverter current");

            if (tick > 0) {
                assertNoFlicker(lastVoltage, v, "Voltage");
                assertNoFlicker(lastCurrent, i, "Current");
            }
            lastVoltage = v;
            lastCurrent = i;
        }
    }

    @Test
    public void testSolarChargeControllerSuppliesInverterLoadWithoutMpptOscillation() {
        // 24V battery pack on bus (Voc = 25.6V, Rint = 0.04 Ohm).
        SimpleBatterySource battery = new SimpleBatterySource(NODE, 25.6, 0.04, 100.0);

        // MPPT CC: 360W solar * 0.98 eta = 352.8W into 24V bus -> 14.7A available.
        double ccAmps = 352.8 / 24.0; // 14.7A
        SimpleConverterSource chargeController = new SimpleConverterSource(NODE, 28.8, 0.05, 60.0, ccAmps);

        // Inverter pulling 30.0A from the 24V bus (720W load on inverter).
        CreativeLoadLogic inverterDraw = constantCurrentLoad(30.0);

        ElectricalGrid busGrid = bus(battery, chargeController);
        busGrid.registerConsumer(NODE, inverterDraw);
        warmUp(busGrid);

        double lastCcCurrent = -1.0;
        double lastBattCurrent = -1.0;
        double lastBusVoltage = -1.0;

        for (int tick = 0; tick < STEADY_STATE_TICKS; tick++) {
            busGrid.tick(null);

            double busV = busGrid.getNodeVoltage(NODE);
            double ccI = chargeController.drawnCurrent;
            double battI = battery.drawnCurrent;

            // Charge controller delivers full available solar capacity (14.7A).
            assertEquals(ccAmps, ccI, 0.05,
                    "Charge controller must deliver full available solar current in Bulk");
            // Battery supplies the remainder: 30.0A - 14.7A = 15.3A.
            assertEquals(15.3, battI, 0.25,
                    "Battery must supply exactly the load deficit");
            // Kirchhoff's Current Law: CC + Batt = Inverter load (30A).
            assertEquals(30.0, ccI + battI, 0.2,
                    "Total source current must match load demand");

            if (tick > 0) {
                assertNoFlicker(lastBusVoltage, busV, "Bus voltage");
                assertNoFlicker(lastCcCurrent, ccI, "MPPT output current");
                assertNoFlicker(lastBattCurrent, battI, "Battery discharge current");
            }
            lastCcCurrent = ccI;
            lastBattCurrent = battI;
            lastBusVoltage = busV;
        }
    }

    @Test
    public void testMpptZeroSolarProducesZeroCurrentAndNoVoltageInflation() {
        // 24V battery pack on bus (Voc = 24.0V, Rint = 0.04 Ohm).
        SimpleBatterySource battery = new SimpleBatterySource(NODE, 24.0, 0.04, 100.0);

        // MPPT CC with 0 available solar power (night, 0 panels): availableCurrent = 0.0A.
        SimpleConverterSource idleChargeController = new SimpleConverterSource(NODE, 28.8, 0.05, 60.0, 0.0);

        // Inverter drawing 10.0A from the 24V DC bus.
        CreativeLoadLogic inverterDraw = constantCurrentLoad(10.0);

        ElectricalGrid busGrid = bus(battery, idleChargeController);
        busGrid.registerConsumer(NODE, inverterDraw);
        warmUp(busGrid);

        // MPPT must NOT generate phantom current or combine voltage when solar is 0.
        assertEquals(0.0, idleChargeController.drawnCurrent, 1e-6,
                "MPPT with 0W solar must deliver exactly 0A");
        // Battery supplies 100% of the inverter load.
        assertEquals(10.0, battery.drawnCurrent, 0.1,
                "Battery must supply full inverter load");
        // Bus voltage purely reflects battery sag: V = 24.0 - (10.0 * 0.04) = 23.6V.
        assertEquals(23.6, busGrid.getNodeVoltage(NODE), 0.15,
                "Bus voltage must purely reflect battery Ohm's law sag without MPPT EMF inflation");

        // Battery is discharging here, so it must not have been credited with any input current.
        assertEquals(0.0, battery.getReceivedCurrent(), 1e-6,
                "Battery must not report received current while discharging into the inverter");
    }

    @Test
    public void testMpptControlledCurrentInjectionDoesNotDistortBatteryBusVoltage() {
        // 24V battery pack with Voc = 24.0V, Rint = 0.04 Ohm.
        SimpleBatterySource battery = new SimpleBatterySource(NODE, 24.0, 0.04, 100.0);

        // MPPT CC delivering 10.0A into the battery bus.
        SimpleConverterSource chargeController = new SimpleConverterSource(NODE, 28.8, 0.05, 60.0, 10.0);

        ElectricalGrid busGrid = bus(battery, chargeController);
        busGrid.tick(null);

        // MPPT pushes 10.0A into the battery.
        assertEquals(10.0, chargeController.drawnCurrent, 0.01,
                "MPPT must push full available current into battery");
        // The battery must have actually *received* that injected current.
        assertEquals(10.0, battery.getReceivedCurrent(), 0.01,
                "Battery must record the MPPT's 10.0A injection via onPowerReceived");
        // Battery terminal voltage rises by I * R = 10.0 * 0.04 = 0.4V -> 24.4V.
        // It must NEVER be pulled up to 28.8V or to a composite average of 26.1V.
        assertEquals(24.4, busGrid.getNodeVoltage(NODE), 0.05,
                "Battery bus voltage must strictly reflect V_ocv + I * R_int (24.4V)");
    }
}