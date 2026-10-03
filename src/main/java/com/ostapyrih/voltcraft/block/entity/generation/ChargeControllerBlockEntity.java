package com.ostapyrih.voltcraft.block.entity.generation;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.block.entity.conversion.AbstractPowerConverterBlockEntity;
import com.ostapyrih.voltcraft.block.entity.storage.BatteryBlockEntity;
import com.ostapyrih.voltcraft.block.entity.storage.BatteryRackBlockEntity;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.screen.handler.ConverterScreenHandler;
import com.ostapyrih.voltcraft.simulation.electrical.ConverterElement;
import com.ostapyrih.voltcraft.simulation.generation.MPPTLogic;
import com.ostapyrih.voltcraft.simulation.grid.GridManager;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalTickDedupe;
import com.ostapyrih.voltcraft.simulation.grid.IslandContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * MPPT solar charge controller (Perturb &amp; Observe) with multi-stage battery charging.
 * Snaps to 12/24/48 V banks with mismatch protection; load is limited to upstream
 * solar availability. Reuses the shared converter 4-terminal kernel pattern.
 */
public class ChargeControllerBlockEntity extends AbstractPowerConverterBlockEntity {

    // --- Fixed converter ratings ---
    private static final double DEFAULT_BANK_VOLTAGE = 24.0;
    private static final double MIN_INPUT_VOLTAGE = 15.0; // Minimum input to start MPPT tracking
    private static final double MAX_INPUT_VOLTAGE = 150.0; // 150V Max PV open-circuit rating
    private static final double MAX_OUTPUT_CURRENT_A = 60.0; // 60A charge controller rating
    private static final double EFFICIENCY = 0.98; // 98% MPPT synchronous buck efficiency
    private static final int TRIP_GRACE_TICKS = 40;
    private static final double DEAD_RAIL_VOLTAGE = 1.0;
    /**
     * Charge-controller output series resistance: the shared soft value.
     * Kept deliberately soft: with a near-zero stamp every millivolt of
     * lagged staging error becomes tens of amps, and post-reconnect restrikes
     * hit kiloamps; the soft stamp keeps transients to tens of amps (safe for
     * wires) and gives the current hug volts of authority. Exact terminal
     * voltage comes from the compensation below, not from stiffness.
     */
    private static final double CHARGER_SOURCE_R_OHM = ConverterElement.SOURCE_R_OHM;
    /**
     * Terminal-side wiring headroom (volts) added to the stage target once
     * output current exceeds a trickle. The stage machine regulates chemistry
     * voltage, but the battery sits behind game-scale wiring: without
     * headroom the drops eat the charge margin on a nearly-full bank. Gated
     * on current so a resting full bank still sees pure absorption (no
     * overcharge push), ramping in over 0-5 A.
     */
    private static final double WIRING_HEADROOM_V = 0.3;
    /** Output current over which the full headroom applies. */
    private static final double WIRING_HEADROOM_FULL_A = 5.0;
    /**
     * Lagged cable-compensation gain (ohms): the staged target additionally
     * rides up with previously delivered current. Loop gain
     * ({@code R_COMP / R_src < 1}) is a contraction by construction, so it
     * converges geometrically instead of hunting. Capped.
     */
    private static final double CABLE_COMP_R_OHM = 0.03;
    /** Ceiling for the compensation term above the headroom-adjusted target. */
    private static final double CABLE_COMP_MAX_V = 0.8;

    // --- Battery bank classification (12V / 24V / 48V) ---
    private static final double BANK_12V_MAX = 15.0;
    private static final double BANK_24V_MAX = 30.0;

    // --- Live terminal-voltage sanity ranges per bank (isBatteryVoltageMismatch) ---
    private static final double SAFE_12V_MIN = 8.0;
    private static final double SAFE_12V_MAX = 17.0;
    private static final double SAFE_24V_MIN = 17.0;
    private static final double SAFE_24V_MAX = 34.0;
    private static final double SAFE_48V_MIN = 34.0;
    private static final double SAFE_48V_MAX = 68.0;

    // --- Storage nominal-voltage mismatch thresholds (isBatteryStorageMismatch) ---
    private static final double STORAGE_NOMINAL_12V_LIMIT = 18.0;
    private static final double STORAGE_NOMINAL_24V_LIMIT = 36.0;

    private final MPPTLogic mpptLogic = new MPPTLogic();

    /**
     * Constant-current mode latch for CC/CV output regulation. While clear
     * the charger regulates constant voltage (compensated absorption/float
     * target); when output current exceeds what the sun can sustain
     * the charger switches to constant current (staged hugging the rail at
     * exactly the solar current) instead of letting the decoupled output
     * stamp draw over-unity power from nowhere. Releases with a deadband
     * (current falls well below the ceiling, or input demand shows real
     * headroom after a cleared fault). Transient, recomputed live.
     */
    private boolean ccMode = false;
    /**
     * Headroom-release debounce: input demand lags output power by a tick,
     * so a single sub-solar demand reading during ramp-up is not structural
     * headroom. Release on demand headroom only after it persists this many
     * consecutive ticks (a cleared fault shows it steadily; a ramp shows it
     * once). Transient.
     */
    private static final int CC_HEADROOM_RELEASE_TICKS = 5;
    private int ccHeadroomTicks = 0;
    /**
     * Battery-mismatch trip debounce: a transient sag through the mismatch
     * window (cable churn, brownout hiccup) must ride through; only a
     * sustained mismatch (genuinely wrong bank) latches the trip. Transient.
     */
    private static final int MISMATCH_TRIP_TICKS = 20;
    private int mismatchTicks = 0;

    public ChargeControllerBlockEntity(BlockPos pos, BlockState state) {
        super(VoltcraftBlockEntityTypes.CHARGE_CONTROLLER_BLOCK_ENTITY, pos, state);
        this.targetOutputVoltage = DEFAULT_BANK_VOLTAGE;
        mpptLogic.setBatteryBankVoltage(DEFAULT_BANK_VOLTAGE);
    }

    public MPPTLogic getMpptLogic() {
        return mpptLogic;
    }

    @Override
    public boolean acceptsInputFrequency(double frequencyHz) {
        return frequencyHz <= 0.001; // DC only from solar panels
    }

    @Override
    public double getOutputFrequency() {
        return 0.0; // DC output to battery
    }

    @Override
    public boolean isOutputConfigurable() {
        return true;
    }

    @Override
    public int getTypeKind() {
        return ConverterScreenHandler.TYPE_CHARGE_CONTROLLER;
    }

    @Override
    public double getEfficiency() {
        return EFFICIENCY;
    }

    @Override
    public double getMinInputVoltage() {
        return MIN_INPUT_VOLTAGE;
    }

    @Override
    public double getMaxInputVoltage() {
        return MAX_INPUT_VOLTAGE;
    }

    @Override
    public double getMaxOutputCurrent() {
        return MAX_OUTPUT_CURRENT_A;
    }

    @Override
    protected double getOutputSourceResistance() {
        return CHARGER_SOURCE_R_OHM;
    }

    @Override
    public double getNominalOutputVoltage() {
        return targetOutputVoltage;
    }

    @Override
    public boolean isGridTie() {
        return false;
    }

    @Override
    public double getTotalHarmonicDistortion() {
        return 0.0;
    }

    @Override
    public void setTargetOutputVoltage(double target) {
        double bank = classifyBank(target);
        super.setTargetOutputVoltage(bank);
        mpptLogic.setBatteryBankVoltage(bank);
        this.tripGraceTicks = TRIP_GRACE_TICKS;
        markDirty();
    }

    private static double classifyBank(double target) {
        if (target <= BANK_12V_MAX) return 12.0;
        if (target <= BANK_24V_MAX) return 24.0;
        return 48.0;
    }

    private BlockPos getOutputPos() {
        return pos.offset(getOutputPortDirection());
    }

    public boolean hasDownstreamStorage() {
        BlockEntity out = world instanceof ServerWorld sw ? sw.getBlockEntity(getOutputPos()) : null;
        if (out instanceof BatteryBlockEntity || out instanceof BatteryRackBlockEntity) {
            return true;
        }
        return getDownstreamRailVoltage() > DEAD_RAIL_VOLTAGE;
    }

    public double getDownstreamRailVoltage() {
        return actualOutputVoltage;
    }

    public boolean isBatteryStorageMismatch() {
        BlockEntity out = world instanceof ServerWorld sw ? sw.getBlockEntity(getOutputPos()) : null;
        if (out instanceof BatteryBlockEntity battery) {
            double nominal = battery.getChemistry().getNominalVoltage()
                * Math.max(1, battery.getSeriesCount());
            return isNominalVoltageMismatched(nominal);
        }
        return false;
    }

    private boolean isNominalVoltageMismatched(double nom) {
        double bank = targetOutputVoltage;
        if (bank <= BANK_12V_MAX) {
            return nom > STORAGE_NOMINAL_12V_LIMIT;
        }
        if (bank <= BANK_24V_MAX) {
            return nom < STORAGE_NOMINAL_12V_LIMIT || nom > STORAGE_NOMINAL_24V_LIMIT;
        }
        return nom < STORAGE_NOMINAL_24V_LIMIT;
    }

    public boolean isBatteryVoltageMismatch(double battV) {
        if (isBatteryStorageMismatch()) {
            return true;
        }
        if (battV <= DEAD_RAIL_VOLTAGE) {
            return false;
        }
        double bank = targetOutputVoltage;
        if (bank <= BANK_12V_MAX) {
            return battV < SAFE_12V_MIN || battV > SAFE_12V_MAX;
        } else if (bank <= BANK_24V_MAX) {
            return battV < SAFE_24V_MIN || battV > SAFE_24V_MAX;
        } else {
            return battV < SAFE_48V_MIN || battV > SAFE_48V_MAX;
        }
    }

    @Override
    public boolean isOutputCurrentRegulated() {
        return !tripped && hasDownstreamStorage() && mpptLogic.getStage() == MPPTLogic.ChargeStage.BULK
            && getUpstreamAvailableSolarWatts() > 0.0;
    }

    public double getUpstreamAvailableSolarWatts() {
        if (world instanceof ServerWorld sw) {
            GridManager gm = GridManager.get(sw);
            IslandContext island = gm.getIslandAt(pos);
            if (island != null) {
                double total = 0.0;
                for (KernelAttachedBlock kab : island.blocks()) {
                    if (kab instanceof SolarPanelBlockEntity sp) {
                        total += sp.getPeakPowerAvailable();
                    }
                }
                if (total > 0.0) {
                    return total;
                }
            }

            Direction inDir = getInputPortDirection();
            BlockPos inPos = pos.offset(inDir);

            // Direct attachment: panel block entity at the input port position.
            BlockEntity be = sw.getBlockEntity(inPos);
            if (be instanceof SolarPanelBlockEntity sp) {
                return sp.getPeakPowerAvailable();
            }

            // Panel replacement tolerance: all 6 neighbors of the input position.
            for (Direction dir : Direction.values()) {
                BlockPos neighborPos = inPos.offset(dir);
                BlockEntity neighborBe = sw.getBlockEntity(neighborPos);
                if (neighborBe instanceof SolarPanelBlockEntity sp) {
                    return sp.getPeakPowerAvailable();
                }
            }
        }
        if (inputVoltage > DEAD_RAIL_VOLTAGE && inputPowerWatts > 0.0) {
            return inputPowerWatts;
        }
        return 0.0;
    }

    @Override
    protected double computeOutputVoltage(double inputVoltage) {
        mpptLogic.setBatteryBankVoltage(targetOutputVoltage);
        if (tripped || inputVoltage < getMinInputVoltage()) {
            return 0.0;
        }

        if (hasDownstreamStorage() && isBatteryVoltageMismatch(actualOutputVoltage)) {
            return 0.0;
        }

        double railV = getDownstreamRailVoltage();
        double battV = railV > DEAD_RAIL_VOLTAGE ? railV : targetOutputVoltage;
        boolean settled = MPPTLogic.isRailSettled(inputVoltage, getMinInputVoltage(),
                mpptLogic.getTargetInputVoltage(), calculateInputPowerDemand(), lastDemandWatts);
        mpptLogic.step(inputVoltage, inputCurrentAmps, battV, settled);

        double targetV;
        if (mpptLogic.getStage() == MPPTLogic.ChargeStage.BULK
                || mpptLogic.getStage() == MPPTLogic.ChargeStage.ABSORPTION) {
            targetV = mpptLogic.getAbsorptionVoltage();
        } else {
            targetV = mpptLogic.getFloatVoltage();
        }
        // Compensated charge voltage: headroom (gated on delivered current so
        // rest sees pure chemistry voltage) plus lagged cable compensation
        // (contraction, converges). Lifts the battery to absorption through
        // bus drops without touching the pinned stage-machine voltages.
        double iPrev = Math.max(0.0, outputCurrentAmps);
        targetV += WIRING_HEADROOM_V * Math.min(1.0, iPrev / WIRING_HEADROOM_FULL_A)
            + Math.min(iPrev * CABLE_COMP_R_OHM, CABLE_COMP_MAX_V);

        // Limit output EMF according to available upstream solar power to prevent overloading solar panels.
        // CC/CV regulation: in CV mode the full compensated target is staged.
        // Once output current exceeds what the sun can sustain, CC mode stages
        // the rail-hugging EMF that sustains exactly the solar current, so
        // output power tracks input power instead of drawing over-unity from
        // the decoupled stamp. The rail-hug only applies on a formed rail:
        // while the bus is dead the charger stages raw target to bootstrap it
        // instead of pinning near zero-volt telemetry (foldback trap).
        double availSolar = getUpstreamAvailableSolarWatts();
        // Current ceiling from the solar power divided by the voltage we
        // regulate to (the stage target), not by lagged bus telemetry: on a
        // rising bus (battery charging) a lagged denominator systemically
        // overshoots the sun (Pout = V_now * Psun / V_prev > Psun).
        double iCap = solarCurrentCeiling(availSolar, targetV);
        // Raw (signed) output power: while the output sinks (restrike
        // transient after a topology change) demand collapses to idle, which
        // must NOT read as "headroom" and release the limiter mid-fault.
        double rawPOut = getRawOutputPowerWatts();
        if (!ccMode && iCap > 0.0 && rawPOut > 0.0 && outputCurrentAmps > iCap) {
            ccMode = true;
            ccHeadroomTicks = 0;
        } else if (ccMode) {
            if (outputCurrentAmps < 0.8 * iCap) {
                ccMode = false;
                ccHeadroomTicks = 0;
            } else if (rawPOut > 0.0 && stagedInputDemandWatts < 0.95 * availSolar) {
                if (++ccHeadroomTicks >= CC_HEADROOM_RELEASE_TICKS) {
                    ccMode = false;
                    ccHeadroomTicks = 0;
                }
            } else {
                ccHeadroomTicks = 0;
            }
        }
        if (ccMode && actualOutputVoltage > DEAD_RAIL_VOLTAGE) {
            double vMaxFoldback = actualOutputVoltage + iCap * getOutputSourceResistance();
            targetV = Math.min(targetV, vMaxFoldback);
        } else {
            // Ideal-diode OR-ing: never stage below the live bus (a hotter
            // bank must leave the charger idling at ~0 A, not backfeed pack
            // current through the output while telemetry reports an honest
            // 0 W at ~0 A instead of 0 W at several amps).
            targetV = Math.max(targetV, actualOutputVoltage);
        }

        if (actualOutputVoltage <= DEAD_RAIL_VOLTAGE && outputCurrentAmps > getMaxOutputCurrent()) {
            // Bolted fault signature (dead bus, rail current above rating):
            // open the output instead of feeding a dead short. Clears by
            // itself once the fault does (current falls, bootstrap resumes).
            targetV = 0.0;
        }

        return targetV;
    }

    /**
     * Output current the sun can sustain at the regulated voltage: solar
     * headroom over the stage target, capped by the controller rating. Zero
     * when the sun is down. Keyed on the target (not lagged bus telemetry,
     * which overshoots on a rising bus).
     */
    private double solarCurrentCeiling(double availSolarWatts, double targetVoltage) {
        if (!(availSolarWatts > 0.0)) {
            return 0.0;
        }
        double pOutMax = availSolarWatts * getEfficiency();
        return Math.min(getMaxOutputCurrent(), pOutMax / Math.max(1.0, targetVoltage));
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        if (!ElectricalTickDedupe.claim(this, world)) {
            return;
        }
        super.doTickElectrical(world);
        double availSolar = getUpstreamAvailableSolarWatts();
        if (availSolar > 0.0) {
            this.stagedInputDemandWatts = Math.min(this.stagedInputDemandWatts, availSolar);
        }
        if (hasDownstreamStorage() && isBatteryVoltageMismatch(actualOutputVoltage)) {
            if (++mismatchTicks >= MISMATCH_TRIP_TICKS && tripGraceTicks == 0) {
                this.tripped = true;
                this.reportedState = ElectricalState.SURGE;
            }
        } else {
            mismatchTicks = 0;
        }
    }

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        mpptLogic.setBatteryBankVoltage(this.targetOutputVoltage);
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
    }
}