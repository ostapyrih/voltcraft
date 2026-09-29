package com.ostapyrih.voltcraft.block.entity.generation;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.block.entity.conversion.AbstractPowerConverterBlockEntity;
import com.ostapyrih.voltcraft.block.entity.storage.BatteryBlockEntity;
import com.ostapyrih.voltcraft.block.entity.storage.BatteryRackBlockEntity;
import com.ostapyrih.voltcraft.screen.handler.ConverterScreenHandler;
import com.ostapyrih.voltcraft.simulation.generation.MPPTLogic;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Block entity for the MPPT Solar Charge Controller.
 * Executes Maximum Power Point Tracking (Perturb &amp; Observe) and multi-stage battery charging.
 * Limits load to what is available from upstream solar generation to prevent solar voltage drop.
 * Synchronizes with 12V, 24V, and 48V battery banks with active mismatch protection.
 *
 * <p>Phase E: reuses the shared converter 4-terminal kernel pattern inherited from
 * {@link AbstractPowerConverterBlockEntity} (input pair east/west, output pair
 * north/south; tripped plus demand/EMF staging via the parent statics). Legacy
 * grid-graph queries (old-grid source scans) are replaced
 * by previous-tick kernel telemetry (one-tick delay, item 12) plus direct neighbor
 * block-entity inspection when a world is available. {@link #computeOutputVoltage}
 * keeps the MPPT/bank staging; the legacy-only demand/current overrides are deleted.</p>
 */
public class ChargeControllerBlockEntity extends AbstractPowerConverterBlockEntity {

    // --- Fixed converter ratings ---
    private static final double DEFAULT_BANK_VOLTAGE = 24.0;
    private static final double MIN_INPUT_VOLTAGE = 15.0; // Minimum input to start MPPT tracking
    private static final double MAX_INPUT_VOLTAGE = 150.0; // 150V Max PV open-circuit rating
    private static final double MAX_OUTPUT_CURRENT_A = 60.0; // 60A charge controller rating
    private static final double EFFICIENCY = 0.98; // 98% MPPT synchronous buck efficiency
    private static final double MIN_EFFICIENCY_FLOOR = 0.1; // guards against div-by-near-zero
    private static final double FLOAT_IDLE_DRAW_W = 2.0; // controller's own housekeeping draw in Float
    private static final int TRIP_GRACE_TICKS = 40;
    private static final double DEAD_RAIL_VOLTAGE = 1.0;

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

    /** Snaps a raw requested voltage to the nearest supported nominal bank: 12V, 24V, or 48V. */
    private static double classifyBank(double target) {
        if (target <= BANK_12V_MAX) return 12.0;
        if (target <= BANK_24V_MAX) return 24.0;
        return 48.0;
    }

    private BlockPos getOutputPos() {
        return pos.offset(getOutputPortDirection());
    }

    /**
     * Phase E replacement for the legacy output-grid storage scan: storage presence is
     * observed via the directly attached output-port block entity when the world is
     * available, else via a live previous-tick output rail (telemetry, one-tick delay).
     */
    public boolean hasDownstreamStorage() {
        BlockEntity out = world instanceof ServerWorld sw ? sw.getBlockEntity(getOutputPos()) : null;
        if (out instanceof BatteryBlockEntity || out instanceof BatteryRackBlockEntity) {
            return true;
        }
        return getDownstreamRailVoltage() > DEAD_RAIL_VOLTAGE;
    }

    /**
     * Previous-tick output-rail voltage from kernel telemetry (one-tick delay, item 12).
     */
    public double getDownstreamRailVoltage() {
        return actualOutputVoltage;
    }

    /**
     * Phase E replacement for the legacy output-grid nominal scan: checks the directly
     * attached output-port battery's pack nominal against the bank rating. Rack and
     * telemetry-only rails fall through to the live-voltage sanity check in
     * {@link #isBatteryVoltageMismatch(double)}.
     */
    public boolean isBatteryStorageMismatch() {
        BlockEntity out = world instanceof ServerWorld sw ? sw.getBlockEntity(getOutputPos()) : null;
        if (out instanceof BatteryBlockEntity battery) {
            double nominal = battery.getChemistry().getNominalVoltage()
                * Math.max(1, battery.getSeriesCount());
            return isNominalVoltageMismatched(nominal);
        }
        return false;
    }

    /** Checks a connected battery's nominal voltage rating against the standard bank rating. */
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
        if (hasDownstreamStorage()) {
            return isBatteryStorageMismatch();
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

    /**
     * Queries upstream generation capacity from connected solar panels.
     *
     * <p>Phase E: the legacy input-grid source scan is replaced by direct neighbor
     * block-entity inspection (input port, then all 6 neighbors of the input position
     * to tolerate panel replacement without cable reconnect), with a fallback to
     * whatever this converter demonstrably drew last tick (telemetry, one-tick delay).</p>
     */
    public double getUpstreamAvailableSolarWatts() {
        if (world instanceof ServerWorld sw) {
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

        boolean hasStorage = hasDownstreamStorage();

        if (hasStorage) {
            if (isBatteryStorageMismatch()) {
                return 0.0;
            }
            double railV = getDownstreamRailVoltage();
            double battV = railV > DEAD_RAIL_VOLTAGE ? railV : targetOutputVoltage;
            boolean settled = MPPTLogic.isRailSettled(inputVoltage, getMinInputVoltage(),
                    mpptLogic.getTargetInputVoltage(), calculateInputPowerDemand(), lastDemandWatts);
            mpptLogic.step(inputVoltage, inputCurrentAmps, battV, settled);

            if (mpptLogic.getStage() == MPPTLogic.ChargeStage.BULK
                    || mpptLogic.getStage() == MPPTLogic.ChargeStage.ABSORPTION) {
                return mpptLogic.getAbsorptionVoltage();
            } else {
                return mpptLogic.getFloatVoltage();
            }
        }

        // Direct standalone output without battery (powering inverters or DC loads directly)
        return targetOutputVoltage;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        // Kernel discrete phase: demand/EMF staging plus the bank-mismatch protection trip.
        // The world argument is never dereferenced here (neighbor sensing runs inside the
        // null-safe helpers above): null-safe by construction.
        super.tickElectrical(world);
        if (hasDownstreamStorage() && isBatteryStorageMismatch()) {
            if (tripGraceTicks == 0) {
                this.tripped = true;
                this.reportedState = ElectricalState.SURGE;
            }
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