package com.ostapyrih.voltcraft.block.entity.conversion;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.energy.IElectricConsumer;
import com.ostapyrih.voltcraft.api.energy.IElectricConverter;
import com.ostapyrih.voltcraft.api.energy.IElectricSource;
import com.ostapyrih.voltcraft.block.conversion.AbstractPowerConverterBlock;
import com.ostapyrih.voltcraft.screen.handler.ConverterScreenHandler;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalGrid;
import com.ostapyrih.voltcraft.simulation.grid.GridManager;
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

/**
 * Base block entity for all multi-port conversion hardware (DC-DC converters, AC transformers, rectifiers, inverters).
 * Bridges an upstream power network (as an IElectricConsumer) and downstream network (as an IElectricSource)
 * without shorting them into a single through-conductor.
 */
public abstract class AbstractPowerConverterBlockEntity extends BlockEntity implements IElectricConverter, ExtendedScreenHandlerFactory<BlockPos> {

    // --- Simulation timestep / shared thresholds ---
    private static final double TICK_DELTA_SECONDS = 0.05;
    private static final double DEAD_RAIL_VOLTAGE = 1.0;
    private static final double CASCADE_MIN_VOLTAGE = 0.5;
    private static final double MIN_EFFICIENCY_FLOOR = 0.1;
    private static final double IDLE_DRAW_WATTS = 2.0;
    private static final int DEFAULT_TRIP_GRACE_TICKS = 40; // 2-second grace so circuit solves/voltages establish before re-tripping

    // --- Protection trips ---
    private static final double ANTI_ISLANDING_MIN_GRID_VOLTAGE = 20.0;
    private static final int ANTI_ISLANDING_TRIP_TICKS = 4; // 200ms sustained unpowered grid
    private static final double WAVEFORM_CHECK_MIN_VOLTAGE = 2.0;
    private static final double UVLO_TRIP_MARGIN = 0.9;
    private static final int UVLO_TRIP_TICKS = 6; // 300ms sustained undervoltage under overload
    private static final double OVERVOLTAGE_TRIP_MARGIN = 1.3;
    private static final double THERMAL_TRIP_TEMPERATURE_C = 125.0;

    // --- Output throttling / brownout classification ---
    private static final double THROTTLE_ONSET_MARGIN = 1.10; // throttle engages below 110% of minVin
    private static final double MIN_THROTTLE = 0.1;
    private static final double BROWNOUT_CURRENT_THRESHOLD = 0.1;
    private static final double BROWNOUT_SAG_MARGIN = 0.90;
    private static final double BROWNOUT_THROTTLE_THRESHOLD = 0.95;

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
    private static final double SOURCE_INTERNAL_RESISTANCE_OHM = 0.05; // 50 mOhm source impedance
    private static final double MIN_VOLTAGE_FOR_RESISTANCE = 1.0;
    private static final double MIN_RESISTANCE_OHM = 1e-4;

    protected final InputConsumer inputConsumer = new InputConsumer();
    protected final OutputSource outputSource = new OutputSource();

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
    protected int underVoltageTicks = 0;
    protected int tripCooldownTicks = 0;
    protected int tripGraceTicks = 0;

    protected double inputCurrentCap = 0.0;
    protected boolean outputHungry = false;
    protected double lastDemandWatts = UNINITIALIZED_DEMAND_WATTS;

    protected ElectricalState reportedState = ElectricalState.OFF;

    @Override
    public IElectricSource getOutputEndpoint() { return outputSource; }

    @Override
    public IElectricConsumer getInputEndpoint() { return inputConsumer; }

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
        this.underVoltageTicks = 0;
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

    @Override
    public boolean isInputPort(Direction side) {
        return side == getInputPortDirection();
    }

    @Override
    public boolean isOutputPort(Direction side) {
        return side == getOutputPortDirection();
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public ElectricalState getElectricalState() {
        return reportedState;
    }

    @Override
    public void setElectricalState(ElectricalState state) {
        // Managed by internal state transitions
    }

    public abstract double getMinInputVoltage();
    public abstract double getMaxInputVoltage();
    public abstract double getMaxOutputCurrent();
    public abstract double getNominalOutputVoltage();
    public abstract boolean isGridTie();
    public abstract double getTotalHarmonicDistortion();

    @Override
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
        this.tripCooldownTicks = 0;
        this.underVoltageTicks = 0;
        this.antiIslandingTicks = 0;
        this.tripGraceTicks = DEFAULT_TRIP_GRACE_TICKS;
        this.inputCurrentCap = 0.0;
        this.outputHungry = false;
        this.lastDemandWatts = UNINITIALIZED_DEMAND_WATTS;
        markDirty();
    }

    // ---------------------------------------------------------------------
    // Tick pipeline
    // ---------------------------------------------------------------------

    /**
     * Executes once per tick from server block entity ticker.
     */
    public void tick(ServerWorld world) {
        Direction inDir = getInputPortDirection();
        Direction outDir = getOutputPortDirection();
        BlockPos inPos = pos.offset(inDir);
        BlockPos outPos = pos.offset(outDir);

        GridManager gridManager = GridManager.get(world);
        ElectricalGrid inGrid = gridManager.getGridAt(inPos);
        ElectricalGrid outGrid = gridManager.getGridAt(outPos);

        double inFreq = updateInputMeasurement(world, inGrid, inPos, inDir);
        handleOutputDisconnectionSafety(world, outGrid, outPos, outDir);

        if (tripGraceTicks > 0) {
            tripGraceTicks--;
        }

        checkAntiIslanding(outGrid, outPos);
        checkWaveformCompatibility(inFreq);
        checkUndervoltageLockout();

        updateOutputVoltageAndState(outGrid, outPos);
        updateThermalModel();
        bookkeepOutputPower(outGrid, outPos);
    }

    /**
     * Measures input rail voltage/frequency from the attached grid, or from a directly-cascaded
     * upstream converter placed block-to-block with no wire between them. Returns the input frequency.
     */
    private double updateInputMeasurement(ServerWorld world, ElectricalGrid inGrid, BlockPos inPos, Direction inDir) {
        if (inGrid != null) {
            this.inputVoltage = inGrid.getNodeVoltage(inPos);
            return inGrid.getFrequency();
        }

        // Direct cascade check: if placed directly against another converter's output port
        BlockEntity be = world.getBlockEntity(inPos);
        if (be instanceof AbstractPowerConverterBlockEntity upstreamConverter
            && upstreamConverter.getOutputPortDirection() == inDir.getOpposite()) {
            return measureFromCascadedUpstream(upstreamConverter);
        }

        this.inputVoltage = 0.0;
        this.inputCurrentAmps = 0.0;
        this.inputPowerWatts = 0.0;
        return 0.0;
    }

    private double measureFromCascadedUpstream(AbstractPowerConverterBlockEntity upstreamConverter) {
        this.inputVoltage = upstreamConverter.getOutputVoltage();
        // Uses this converter's own (possibly overridden) demand calculation, so a subclass with
        // custom demand logic (e.g. solar-availability-driven) behaves the same whether it is fed
        // through a grid or wired block-to-block against another converter.
        double powerNeeded = calculateInputPowerDemand();
        double currentDrawn = this.inputVoltage > CASCADE_MIN_VOLTAGE
            ? Math.min(upstreamConverter.getMaxOutputCurrent(), powerNeeded / this.inputVoltage)
            : 0.0;
        this.inputCurrentAmps = currentDrawn;
        this.inputPowerWatts = this.inputVoltage * currentDrawn;
        upstreamConverter.outputSource.onPowerDrawn(currentDrawn, TICK_DELTA_SECONDS);
        return upstreamConverter.getOutputFrequency();
    }

    /** Immediately zeroes output metrics if the output port has no grid and no cascaded downstream converter. */
    private void handleOutputDisconnectionSafety(ServerWorld world, ElectricalGrid outGrid, BlockPos outPos, Direction outDir) {
        if (outGrid != null) {
            return;
        }
        BlockEntity downBe = world.getBlockEntity(outPos);
        boolean cascadedToDownstreamConverter = downBe instanceof AbstractPowerConverterBlockEntity downConv
            && downConv.getInputPortDirection() == outDir.getOpposite();
        if (!cascadedToDownstreamConverter) {
            this.outputCurrentAmps = 0.0;
            this.outputPowerWatts = 0.0;
            this.actualOutputVoltage = 0.0;
        }
    }

    /** Anti-Islanding Protection check for Grid-Tie Inverters: trips offline if the output grid goes dead. */
    private void checkAntiIslanding(ElectricalGrid outGrid, BlockPos outPos) {
        if (!isGridTie() || tripGraceTicks != 0) {
            return;
        }
        double outGridV = outGrid != null ? outGrid.getNodeVoltage(outPos) : 0.0;
        if (outGridV < ANTI_ISLANDING_MIN_GRID_VOLTAGE) {
            antiIslandingTicks++;
            if (antiIslandingTicks >= ANTI_ISLANDING_TRIP_TICKS) {
                this.tripped = true;
            }
        } else {
            antiIslandingTicks = 0;
        }
    }

    /** Trips if the input rail's frequency doesn't match what this converter accepts. */
    private void checkWaveformCompatibility(double inFreq) {
        if (tripGraceTicks == 0 && !acceptsInputFrequency(inFreq) && this.inputVoltage > WAVEFORM_CHECK_MIN_VOLTAGE) {
            this.tripped = true;
        }
    }

    /**
     * Undervoltage Lockout (UVLO): latched protection trip upon sustained undervoltage.
     * Requires manual reset or setting adjustment.
     */
    private void checkUndervoltageLockout() {
        if (tripped) {
            return;
        }
        double minVin = getMinInputVoltage();
        if (tripGraceTicks == 0 && inputVoltage < minVin * UVLO_TRIP_MARGIN && inputVoltage > DEAD_RAIL_VOLTAGE) {
            underVoltageTicks++;
            if (underVoltageTicks >= UVLO_TRIP_TICKS) {
                this.tripped = true;
                this.underVoltageTicks = 0;
            }
        } else if (inputVoltage >= minVin) {
            underVoltageTicks = 0;
        }
    }

    /** Computes this tick's output EMF and electrical state, or forces a hard shutdown when out of bounds. */
    private void updateOutputVoltageAndState(ElectricalGrid outGrid, BlockPos outPos) {
        double minVin = getMinInputVoltage();
        double maxVin = getMaxInputVoltage();
        boolean hardOff = tripped || inputVoltage <= DEAD_RAIL_VOLTAGE || inputVoltage > maxVin * OVERVOLTAGE_TRIP_MARGIN;

        if (hardOff) {
            this.outputVoltageEmf = 0.0;
            this.actualOutputVoltage = 0.0;
            this.outputCurrentAmps = 0.0;
            this.outputPowerWatts = 0.0;
            this.reportedState = (tripped || inputVoltage <= DEAD_RAIL_VOLTAGE) ? ElectricalState.OFF : ElectricalState.SURGE;
            return;
        }

        double rawTarget = computeOutputVoltage(inputVoltage);
        this.outputVoltageEmf = rawTarget;

        // Throttle engages only near battery cutoff, NOT during normal discharge.
        double throttle = computeThrottleFactor(minVin);

        // Real physical circuit response: terminal voltage sags per Ohm's law and solver clamping.
        // Check terminal voltage under load for brownout classification without mutating EMF.
        double actualOutV = outGrid != null ? outGrid.getNodeVoltage(outPos) : outputVoltageEmf;
        this.actualOutputVoltage = actualOutV;

        if (outputCurrentAmps > BROWNOUT_CURRENT_THRESHOLD && actualOutV < rawTarget * BROWNOUT_SAG_MARGIN) {
            reportedState = ElectricalState.BROWNOUT;
        } else if (inputVoltage < minVin || throttle < BROWNOUT_THROTTLE_THRESHOLD) {
            reportedState = ElectricalState.BROWNOUT;
        } else {
            reportedState = ElectricalState.NOMINAL;
        }
    }

    /**
     * Shared by {@link #updateOutputVoltageAndState} and {@link #calculateAvailableOutputCurrent}:
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

    /** Thermal accumulation and convective/fan cooling; trips offline at the thermal limit to protect components. */
    private void updateThermalModel() {
        double lossWatts = Math.max(0.0, inputPowerWatts - outputPowerWatts);
        double deltaAboveAmbient = Math.max(0.0, temperatureCelsius - AMBIENT_TEMPERATURE_C);

        double ratedPower = Math.max(MIN_RATED_POWER_W, getMaxOutputCurrent() * getNominalOutputVoltage());
        double eff = Math.max(MIN_EFFICIENCY_FLOOR, getEfficiency());
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

        if (this.temperatureCelsius >= THERMAL_TRIP_TEMPERATURE_C && !tripped) {
            this.tripped = true;
        }
    }

    /** Output power bookkeeping from measured terminal values, run unconditionally after all physics settles. */
    private void bookkeepOutputPower(ElectricalGrid outGrid, BlockPos outPos) {
        if (outGrid != null) {
            this.actualOutputVoltage = outGrid.getNodeVoltage(outPos);
            this.outputPowerWatts = Math.max(0.0, actualOutputVoltage * outputCurrentAmps);
        } else {
            this.actualOutputVoltage = outputVoltageEmf;
            this.outputPowerWatts = outputVoltageEmf * outputCurrentAmps;
        }
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

    // ==================== Sub-component IElectricConsumer ====================

    public class InputConsumer implements IElectricConsumer {

        public boolean isRemoved() {
            return AbstractPowerConverterBlockEntity.this.isRemoved();
        }

        @Override
        public BlockPos getPos() {
            return AbstractPowerConverterBlockEntity.this.pos;
        }

        @Override
        public ElectricalState getElectricalState() {
            return AbstractPowerConverterBlockEntity.this.getElectricalState();
        }

        @Override
        public void setElectricalState(ElectricalState state) {}

        @Override
        public double getNominalPowerDemand() {
            double demand = calculateInputPowerDemand();
            lastDemandWatts = demand;
            return demand;
        }

        @Override
        public double getNominalVoltage() {
            return (getMinInputVoltage() + getMaxInputVoltage()) * 0.5;
        }

        @Override
        public double getMinOperatingVoltage() {
            return AbstractPowerConverterBlockEntity.this.getMinInputVoltage();
        }

        @Override
        public double getMaxOperatingVoltage() {
            return AbstractPowerConverterBlockEntity.this.getMaxInputVoltage();
        }

        @Override
        public double getEquivalentResistance() {
            double p = getNominalPowerDemand();
            if (p <= 0.0) return Double.POSITIVE_INFINITY;
            double v = Math.max(MIN_VOLTAGE_FOR_RESISTANCE, inputVoltage);
            return Math.max(MIN_RESISTANCE_OHM, (v * v) / p);
        }

        @Override
        public void onPowerReceived(double terminalVoltage, double deliveredCurrent, double durationSeconds) {
            inputVoltage = terminalVoltage;
            inputCurrentAmps = deliveredCurrent;
            inputPowerWatts = terminalVoltage * deliveredCurrent;
        }
    }

    // ==================== Sub-component IElectricSource ====================

    public class OutputSource implements IElectricSource {

        public boolean isRemoved() {
            return AbstractPowerConverterBlockEntity.this.isRemoved();
        }

        public boolean isCurrentRegulated() {
            return AbstractPowerConverterBlockEntity.this.isOutputCurrentRegulated();
        }

        @Override
        public BlockPos getPos() {
            return AbstractPowerConverterBlockEntity.this.pos;
        }

        @Override
        public ElectricalState getElectricalState() {
            return AbstractPowerConverterBlockEntity.this.getElectricalState();
        }

        @Override
        public void setElectricalState(ElectricalState state) {}

        @Override
        public double getElectromotiveForce() {
            return outputVoltageEmf;
        }

        @Override
        public double getInternalResistance() {
            return SOURCE_INTERNAL_RESISTANCE_OHM;
        }

        @Override
        public double getMaxOutputCurrent() {
            return AbstractPowerConverterBlockEntity.this.getMaxOutputCurrent();
        }

        @Override
        public double getAvailableOutputCurrent() {
            return calculateAvailableOutputCurrent();
        }

        @Override
        public double getFrequency() {
            return AbstractPowerConverterBlockEntity.this.getOutputFrequency();
        }

        @Override
        public void onPowerDrawn(double currentAmps, double durationSeconds) {
            double rated = Math.max(0.0, AbstractPowerConverterBlockEntity.this.getMaxOutputCurrent());
            outputCurrentAmps = Math.clamp(currentAmps, 0.0, rated);
        }
    }

    // ==================== Serialization ====================

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        this.targetOutputVoltage = view.getDouble("target_voltage", DEFAULT_TARGET_OUTPUT_VOLTAGE);
        this.temperatureCelsius = view.getDouble("temperature", DEFAULT_TEMPERATURE_C);
        this.tripped = view.getBoolean("tripped", false);
        this.tripGraceTicks = DEFAULT_TRIP_GRACE_TICKS;
        this.inputCurrentCap = 0.0;
        this.outputHungry = false;
        this.lastDemandWatts = UNINITIALIZED_DEMAND_WATTS;
        this.reportedState = ElectricalState.OFF;
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        view.putDouble("target_voltage", this.targetOutputVoltage);
        view.putDouble("temperature", this.temperatureCelsius);
        view.putBoolean("tripped", this.tripped);
    }
}