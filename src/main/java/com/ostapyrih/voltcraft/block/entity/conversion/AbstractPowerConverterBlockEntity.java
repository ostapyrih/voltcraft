package com.ostapyrih.voltcraft.block.entity.conversion;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.conversion.AbstractPowerConverterBlock;
import com.ostapyrih.voltcraft.screen.handler.ConverterScreenHandler;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import com.ostapyrih.voltcraft.simulation.electrical.ConverterElement;

/**
 * Base block entity for multi-port conversion hardware (DC-DC converters, transformers,
 * rectifiers, inverters): input side draws staged demand, output side sources staged EMF,
 * with no galvanic coupling between the pairs. Converters hold no kernel state;
 * trips and demand/EMF staging run in the discrete phase.
 */
public abstract class AbstractPowerConverterBlockEntity extends BlockEntity implements KernelAttachedBlock, ExtendedScreenHandlerFactory<BlockPos> {

    // --- Simulation timestep / shared thresholds ---
    private static final double TICK_DELTA_SECONDS = 0.05;
    private static final double DEAD_RAIL_VOLTAGE = 1.0;
    private static final double MIN_EFFICIENCY_FLOOR = 0.1;
    private static final double IDLE_DRAW_WATTS = 2.0;
    private static final int DEFAULT_TRIP_GRACE_TICKS = 40; // 2-second grace so circuit solves/voltages establish before re-tripping

    // --- Output throttling / brownout classification ---
    private static final double THROTTLE_ONSET_MARGIN = 1.10; // throttle engages below 110% of minVin
    private static final double MIN_THROTTLE = 0.1;

    // --- Thermal model ---
    private static final double AMBIENT_TEMPERATURE_C = 20.0;
    private static final double MIN_RATED_POWER_W = 100.0;
    private static final double MIN_BASE_COOLING_COEFF = 1.5;
    private static final double COOLING_COEFF_DIVISOR = 40.0;
    private static final double FAN_ONSET_TEMPERATURE_C = 45.0;
    private static final double FAN_MAX_BONUS = 2.5;
    private static final double FAN_RAMP_RANGE_C = 20.0;
    private static final double MIN_HEAT_CAPACITY = 120.0;
    private static final double HEAT_CAPACITY_FACTOR = 0.25;

    // --- Misc defaults ---
    private static final double DEFAULT_TARGET_OUTPUT_VOLTAGE = 12.0;
    private static final double DEFAULT_TEMPERATURE_C = 20.0;
    private static final double UNINITIALIZED_DEMAND_WATTS = -1.0;
    private static final double NOMINAL_INPUT_VOLTAGE_DEFAULT = 48.0;
    private static final double SERVO_TARGET_MULTIPLIER = 1.15;
    private static final double SERVO_TARGET_OFFSET_V = 0.5;

    protected double inputVoltage = 0.0;
    protected double inputCurrentAmps = 0.0;
    protected double inputPowerWatts = 0.0;

    protected double outputVoltageEmf = 0.0;
    protected double actualOutputVoltage = 0.0;
    protected double outputCurrentAmps = 0.0;
    protected double outputPowerWatts = 0.0;

    protected double targetOutputVoltage = DEFAULT_TARGET_OUTPUT_VOLTAGE;
    protected double temperatureCelsius = DEFAULT_TEMPERATURE_C;
    protected boolean tripped = false;
    protected int antiIslandingTicks = 0;
    protected int tripGraceTicks = 0;

    protected double lastDemandWatts = UNINITIALIZED_DEMAND_WATTS;

    protected ElectricalState reportedState = ElectricalState.OFF;

    // --- Kernel path: BE-owned adapter state ---
    private final double[] stateArray = ConverterElement.newStateArray();
    private final double[] telemetryCell = new double[ConverterElement.TELE_LEN];
    private final ElectricalElement element = new ConverterElement(
        this::isTripped, this::getStagedInputDemandWatts, this::getStagedOutputEmf,
        this::getStagingNominalInputVoltage, telemetryCell);
    /** Staged input demand in watts, consumed read-only by the stamp. */
    protected double stagedInputDemandWatts = 0.0;
    /** Staged output EMF in volts, consumed read-only by the stamp. */
    protected double stagedOutputEmf = 0.0;


    protected final PropertyDelegate propertyDelegate = new PropertyDelegate() {
        @Override
        public int get(int index) {
            int inCurr = (int) Math.round(inputCurrentAmps * 100.0);
            int inPow = (int) Math.round(inputPowerWatts * 10.0);
            int outCurr = (int) Math.round(outputCurrentAmps * 100.0);
            int outPow = (int) Math.round(outputPowerWatts * 10.0);
            double dispOutV = actualOutputVoltage > 0.1 ? actualOutputVoltage : outputVoltageEmf;

            return switch (index) {
                case ConverterScreenHandler.PROP_INPUT_VOLTAGE_X10 -> (int) Math.round(inputVoltage * 10.0);
                case ConverterScreenHandler.PROP_INPUT_CURRENT_LOW -> ConverterScreenHandler.packLow(inCurr);
                case ConverterScreenHandler.PROP_INPUT_CURRENT_HIGH -> ConverterScreenHandler.packHigh(inCurr);
                case ConverterScreenHandler.PROP_INPUT_POWER_LOW -> ConverterScreenHandler.packLow(inPow);
                case ConverterScreenHandler.PROP_INPUT_POWER_HIGH -> ConverterScreenHandler.packHigh(inPow);

                case ConverterScreenHandler.PROP_OUTPUT_VOLTAGE_X10 -> (int) Math.round(dispOutV * 10.0);
                case ConverterScreenHandler.PROP_OUTPUT_CURRENT_LOW -> ConverterScreenHandler.packLow(outCurr);
                case ConverterScreenHandler.PROP_OUTPUT_CURRENT_HIGH -> ConverterScreenHandler.packHigh(outCurr);
                case ConverterScreenHandler.PROP_OUTPUT_POWER_LOW -> ConverterScreenHandler.packLow(outPow);
                case ConverterScreenHandler.PROP_OUTPUT_POWER_HIGH -> ConverterScreenHandler.packHigh(outPow);

                case ConverterScreenHandler.PROP_TARGET_VOLTAGE_X10 -> (int) Math.round(targetOutputVoltage * 10.0);
                case ConverterScreenHandler.PROP_TEMPERATURE_X10 -> (int) Math.round(temperatureCelsius * 10.0);
                case ConverterScreenHandler.PROP_EFFICIENCY_X10 -> (int) Math.round(getEfficiency() * 1000.0);
                case ConverterScreenHandler.PROP_THD_X10 -> (int) Math.round(getTotalHarmonicDistortion() * 10.0);
                case ConverterScreenHandler.PROP_STATE_ORDINAL -> getElectricalState().ordinal();
                case ConverterScreenHandler.PROP_FLAGS -> {
                    int flags = 0;
                    if (tripped) flags |= 1;
                    if (isOutputConfigurable()) flags |= 2;
                    if (isGridTie()) flags |= 4;
                    yield flags;
                }
                case ConverterScreenHandler.PROP_INPUT_PORT_DIR -> getInputPortDirection().ordinal();
                case ConverterScreenHandler.PROP_OUTPUT_PORT_DIR -> getOutputPortDirection().ordinal();
                case ConverterScreenHandler.PROP_TYPE_KIND -> getTypeKind();
                case ConverterScreenHandler.PROP_NOMINAL_INPUT_VOLTAGE_X10 -> (int) Math.round(getNominalInputVoltage() * 10.0);
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            if (index == ConverterScreenHandler.PROP_TARGET_VOLTAGE_X10) {
                setTargetOutputVoltage(value / 10.0);
                markDirty();
            } else if (index == ConverterScreenHandler.PROP_NOMINAL_INPUT_VOLTAGE_X10) {
                setNominalInputVoltage(value / 10.0);
                markDirty();
            } else if (index == ConverterScreenHandler.PROP_FLAGS) {
                if ((value & 1) == 0 && tripped) {
                    resetTrip();
                }
            }
        }

        @Override
        public int size() {
            return ConverterScreenHandler.PROPERTY_COUNT;
        }
    };

    public AbstractPowerConverterBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public PropertyDelegate getPropertyDelegate() {
        return propertyDelegate;
    }

    public boolean isOutputConfigurable() {
        return false;
    }

    public int getTypeKind() {
        return 0;
    }

    public double getNominalInputVoltage() {
        return NOMINAL_INPUT_VOLTAGE_DEFAULT;
    }

    public double getServoTargetVoltage() {
        return getMinInputVoltage() * SERVO_TARGET_MULTIPLIER + SERVO_TARGET_OFFSET_V;
    }

    public void setNominalInputVoltage(double voltage) {
        this.tripGraceTicks = DEFAULT_TRIP_GRACE_TICKS;
    }

    public double getOutputFrequency() {
        return 0.0;
    }

    public boolean acceptsInputFrequency(double frequencyHz) {
        return true;
    }

    public boolean isOutputCurrentRegulated() {
        return false;
    }

    @Override
    public Text getDisplayName() {
        return Text.translatable(getCachedState().getBlock().getTranslationKey());
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
        return new ConverterScreenHandler(syncId, playerInventory, this.pos, this.propertyDelegate);
    }

    @Override
    public BlockPos getScreenOpeningData(ServerPlayerEntity player) {
        return this.pos;
    }

    public Direction getInputPortDirection() {
        if (getCachedState().contains(AbstractPowerConverterBlock.FACING)) {
            return getCachedState().get(AbstractPowerConverterBlock.FACING).getOpposite();
        }
        return Direction.NORTH;
    }

    public Direction getOutputPortDirection() {
        if (getCachedState().contains(AbstractPowerConverterBlock.FACING)) {
            return getCachedState().get(AbstractPowerConverterBlock.FACING);
        }
        return Direction.SOUTH;
    }

    public boolean isInputPort(Direction side) {
        return side == getInputPortDirection();
    }

    public boolean isOutputPort(Direction side) {
        return side == getOutputPortDirection();
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    /**
     * Staged input demand in watts from the last {@link #tickElectrical} pass (GUI cache).
     */
    protected double getStagedInputDemandWatts() {
        return stagedInputDemandWatts;
    }

    /**
     * Staged output EMF in volts from the last {@link #tickElectrical} pass (GUI cache).
     */
    protected double getStagedOutputEmf() {
        return stagedOutputEmf;
    }

    /**
     * Nominal input voltage used only for the AC resistive-fallback stamp.
     */
    protected double getStagingNominalInputVoltage() {
        return (getMinInputVoltage() + getMaxInputVoltage()) * 0.5;
    }

    @Override
    public ElectricalElement getElement() {
        return element;
    }

    @Override
    public BlockPos[] getTerminalPositions() {
        return ConverterElement.resolveConverterTerminals(pos, readFacing());
    }

    /**
     * Reads {@code FACING} from the cached state with a {@link Direction#NORTH}
     * fallback (null-world / test-double / wrong-block safe: only {@code pos} plus
     * the cached state are read, never the world).
     */
    private Direction readFacing() {
        try {
            BlockState cached = getCachedState();
            if (cached != null && cached.contains(AbstractPowerConverterBlock.FACING)) {
                Direction facing = cached.get(AbstractPowerConverterBlock.FACING);
                if (facing != null) {
                    return facing;
                }
            }
        } catch (Exception ignored) {
            // Fall through to NORTH.
        }
        return Direction.NORTH;
    }

    @Override
    public double[] getStateArray() {
        return ConverterElement.snapshotState(stateArray);
    }

    @Override
    public void setStateArray(double[] state) {
        ConverterElement.assignState(stateArray, state);
    }

    @Override
    public boolean isActiveSource() {
        return ConverterElement.isActiveSource(tripped, stagedOutputEmf);
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        double telePOut = telemetryCell[ConverterElement.TELE_P_OUT];
        double teleVIn = telemetryCell[ConverterElement.TELE_V_IN];
        double teleVOut = telemetryCell[ConverterElement.TELE_V_OUT];
        double teleIOut = telemetryCell[ConverterElement.TELE_I_OUT];

        // Snapshot the demand that fed the just-solved tick before re-staging.
        double prevDemandWatts = stagedInputDemandWatts;

        // GUI-visible caches follow previous-tick telemetry (Item 12 staging).
        this.inputVoltage = teleVIn;
        this.inputPowerWatts = Math.max(0.0, prevDemandWatts);
        this.inputCurrentAmps = teleVIn > ConverterElement.DEAD_RAIL_VOLTS
            ? Math.max(0.0, prevDemandWatts) / Math.max(1.0, teleVIn) : 0.0;
        this.outputPowerWatts = Math.max(0.0, Double.isFinite(telePOut) ? telePOut : 0.0);
        this.actualOutputVoltage = teleVOut;
        this.outputCurrentAmps = Math.max(0.0, Double.isFinite(teleIOut) ? teleIOut : 0.0);

        double minVin = getMinInputVoltage();
        double maxVin = getMaxInputVoltage();
        double rawTarget = computeOutputVoltage(teleVIn);
        this.stagedOutputEmf = ConverterElement.stageEmf(rawTarget, tripped, teleVIn, maxVin);
        this.outputVoltageEmf = stagedOutputEmf;
        double eff = getEfficiency();
        this.stagedInputDemandWatts = ConverterElement.stageDemandWatts(
            telePOut, eff, tripped, teleVIn);

        updateKernelThermal(prevDemandWatts, telePOut, eff);

        if (tripGraceTicks > 0) {
            tripGraceTicks--;
        }
        boolean grace = tripGraceTicks > 0;
        double maxOut = Math.max(0.0, getMaxOutputCurrent());
        // Output current foldback (hiccup, non-latching): when the previous-tick
        // output current exceeds the rating, sag the staged EMF so the next solve
        // is current-limited instead of latching a permanent trip. Recovers
        // automatically once the overload clears (cable churn, hot-swap, inrush).
        if (!tripped && maxOut > 0.0 && teleIOut > maxOut) {
            double currentLimitEmf = Math.max(0.0, teleVOut)
                + maxOut * ConverterElement.SOURCE_R_OHM;
            if (stagedOutputEmf > currentLimitEmf) {
                stagedOutputEmf = currentLimitEmf;
                outputVoltageEmf = stagedOutputEmf;
            }
        }
        boolean islandCond = !grace && isGridTie() && teleVOut < ConverterElement.ANTI_ISLAND_MIN_VOLTS;
        antiIslandingTicks = ConverterElement.advanceCounter(antiIslandingTicks, islandCond);
        boolean islandNow = antiIslandingTicks >= ConverterElement.ANTI_ISLAND_TRIP_TICKS;
        // UVLO is a non-latching brownout: output staging already gates on the
        // input rail (stageEmf/computeOutputVoltage return 0 below minVin) and the
        // reported state drops to BROWNOUT/OFF, so the rail recovers by itself.
        // Only overtemperature and grid-tie anti-islanding latch a trip.
        boolean next = ConverterElement.tripNext(tripped, temperatureCelsius,
            false, islandNow, false);
        if (next != tripped) {
            tripped = next;
            markDirty();
        }
        updateKernelReportedState(teleVIn, minVin, maxVin);
    }

    private void updateKernelThermal(double prevDemandWatts, double telePOutWatts, double efficiency) {
        double delivered = Double.isFinite(telePOutWatts) ? Math.max(0.0, telePOutWatts) : 0.0;
        double lossWatts = Math.max(0.0, Math.max(0.0, prevDemandWatts) - delivered);
        double deltaAboveAmbient = Math.max(0.0, temperatureCelsius - AMBIENT_TEMPERATURE_C);

        double ratedPower = Math.max(MIN_RATED_POWER_W, getMaxOutputCurrent() * getNominalOutputVoltage());
        double eff = Double.isFinite(efficiency) ? efficiency : MIN_EFFICIENCY_FLOOR;
        eff = Math.max(MIN_EFFICIENCY_FLOOR, eff);
        double ratedFullLoss = (ratedPower * (1.0 - eff)) / eff;
        double baseCoolingCoeff = Math.max(MIN_BASE_COOLING_COEFF, ratedFullLoss / COOLING_COEFF_DIVISOR);

        double fanMultiplier = 1.0;
        if (temperatureCelsius > FAN_ONSET_TEMPERATURE_C) {
            fanMultiplier = 1.0 + Math.min(FAN_MAX_BONUS, (temperatureCelsius - FAN_ONSET_TEMPERATURE_C) / FAN_RAMP_RANGE_C);
        }

        double coolingWatts = baseCoolingCoeff * fanMultiplier * deltaAboveAmbient;
        double heatCapacity = Math.max(MIN_HEAT_CAPACITY, ratedPower * HEAT_CAPACITY_FACTOR);
        double deltaTemp = ((lossWatts - coolingWatts) / heatCapacity) * TICK_DELTA_SECONDS;
        this.temperatureCelsius = Math.max(AMBIENT_TEMPERATURE_C, this.temperatureCelsius + deltaTemp);
    }

    private void updateKernelReportedState(double teleVIn, double minVin, double maxVin) {
        if (tripped || !(teleVIn > ConverterElement.DEAD_RAIL_VOLTS)) {
            reportedState = ElectricalState.OFF;
        } else if (Double.isFinite(maxVin) && teleVIn > maxVin * ConverterElement.OVERVOLTAGE_MARGIN) {
            reportedState = ElectricalState.SURGE;
        } else if (teleVIn < minVin) {
            reportedState = ElectricalState.BROWNOUT;
        } else {
            reportedState = ElectricalState.NOMINAL;
        }
    }

    public void writeStateData(WriteView view) {
        ConverterElement.writeNbt(view, tripped);
    }

    public void readStateData(ReadView view) {
        ConverterElement.assignState(stateArray, ConverterElement.readNbtState(view));
        this.tripped = ConverterElement.readNbtBlown(view);
    }

    public ElectricalState getElectricalState() {
        return reportedState;
    }

    public void setElectricalState(ElectricalState state) {
        // Managed by internal state transitions
    }

    public abstract double getEfficiency();

    public abstract double getMinInputVoltage();
    public abstract double getMaxInputVoltage();
    public abstract double getMaxOutputCurrent();
    public abstract double getNominalOutputVoltage();
    public abstract boolean isGridTie();
    public abstract double getTotalHarmonicDistortion();

    public double getTargetOutputVoltage() {
        return targetOutputVoltage;
    }

    public void setTargetOutputVoltage(double target) {
        this.targetOutputVoltage = target;
        markDirty();
    }

    public double getInputVoltage() {
        return inputVoltage;
    }

    public double getInputPowerWatts() {
        return inputPowerWatts;
    }

    public double getOutputVoltage() {
        return actualOutputVoltage > 0.1 ? actualOutputVoltage : outputVoltageEmf;
    }

    public double getOutputCurrentAmps() {
        return outputCurrentAmps;
    }

    public double getOutputPowerWatts() {
        return outputPowerWatts;
    }

    public double getTemperatureCelsius() {
        return temperatureCelsius;
    }

    public boolean isTripped() {
        return tripped;
    }

    public void resetTrip() {
        this.tripped = false;
        this.antiIslandingTicks = 0;
        this.tripGraceTicks = DEFAULT_TRIP_GRACE_TICKS;
        this.lastDemandWatts = UNINITIALIZED_DEMAND_WATTS;
        markDirty();
    }


    /**
     * Shared by {@link #calculateAvailableOutputCurrent}:
     * full authority (1.0) above 110% of minVin, linearly ramping down to {@link #MIN_THROTTLE} as
     * the input rail approaches its cutoff.
     */
    private double computeThrottleFactor(double minVin) {
        double throttleVin = minVin * THROTTLE_ONSET_MARGIN;
        if (inputVoltage >= throttleVin) {
            return 1.0;
        }
        return Math.clamp((inputVoltage - minVin) / Math.max(0.1, throttleVin - minVin), MIN_THROTTLE, 1.0);
    }



    // ---------------------------------------------------------------------
    // Subclass hooks
    // ---------------------------------------------------------------------

    /**
     * Subclasses calculate regulated output EMF based on conversion topology.
     */
    protected abstract double computeOutputVoltage(double inputVoltage);

    /**
     * Calculates input power demand in Watts.
     */
    protected double calculateInputPowerDemand() {
        if (tripped || inputVoltage <= DEAD_RAIL_VOLTAGE) {
            return 0.0;
        }
        double eta = Math.max(MIN_EFFICIENCY_FLOOR, getEfficiency());
        return (outputPowerWatts / eta) + IDLE_DRAW_WATTS;
    }

    /**
     * Calculates available output current in Amperes.
     */
    protected double calculateAvailableOutputCurrent() {
        if (tripped || inputVoltage <= DEAD_RAIL_VOLTAGE) return 0.0;
        double rated = Math.max(0.0, getMaxOutputCurrent());
        double throttle = computeThrottleFactor(getMinInputVoltage());
        return rated * throttle;
    }

    // ==================== Serialization ====================

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        this.targetOutputVoltage = view.getDouble("target_voltage", DEFAULT_TARGET_OUTPUT_VOLTAGE);
        this.temperatureCelsius = view.getDouble("temperature", DEFAULT_TEMPERATURE_C);
        this.tripped = view.getBoolean("tripped", false);
        this.tripGraceTicks = DEFAULT_TRIP_GRACE_TICKS;
        this.lastDemandWatts = UNINITIALIZED_DEMAND_WATTS;
        this.reportedState = ElectricalState.OFF;
        readStateData(view);
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        view.putDouble("target_voltage", this.targetOutputVoltage);
        view.putDouble("temperature", this.temperatureCelsius);
        view.putBoolean("tripped", this.tripped);
        writeStateData(view);
    }
}