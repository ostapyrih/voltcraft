package com.ostapyrih.voltcraft.block.entity.conversion;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.energy.IElectricConsumer;
import com.ostapyrih.voltcraft.api.energy.IElectricConverter;
import com.ostapyrih.voltcraft.api.energy.IElectricSource;
import com.ostapyrih.voltcraft.block.conversion.AbstractPowerConverterBlock;
import com.ostapyrih.voltcraft.screen.handler.ConverterScreenHandler;
import com.ostapyrih.voltcraft.simulation.conversion.InputPowerRegulator;
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

import java.util.List;
import java.util.UUID;

/**
 * Base block entity for all multi-port conversion hardware (DC-DC converters, AC transformers, rectifiers, inverters).
 * Bridges an upstream power network (as an IElectricConsumer) and downstream network (as an IElectricSource)
 * without shorting them into a single through-conductor.
 */
public abstract class AbstractPowerConverterBlockEntity extends BlockEntity implements IElectricConverter, ExtendedScreenHandlerFactory<BlockPos> {

    protected final InputConsumer inputConsumer = new InputConsumer();
    protected final OutputSource outputSource = new OutputSource();

    protected double inputVoltage = 0.0;
    protected double inputCurrentAmps = 0.0;
    protected double inputPowerWatts = 0.0;

    protected double outputVoltageEmf = 0.0;
    protected double actualOutputVoltage = 0.0;
    protected double outputCurrentAmps = 0.0;
    protected double outputPowerWatts = 0.0;

    protected double targetOutputVoltage = 12.0;
    protected double temperatureCelsius = 20.0;
    protected boolean tripped = false;
    protected int antiIslandingTicks = 0;
    protected int underVoltageTicks = 0;
    protected int tripCooldownTicks = 0;
    protected int tripGraceTicks = 0;

    protected double inputCurrentCap = 0.0;
    protected boolean outputHungry = false;
    protected double lastDemandWatts = -1.0;

    protected ElectricalState reportedState = ElectricalState.OFF;

    private UUID lastInputGridId = null;
    private UUID lastOutputGridId = null;

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
        return 48.0;
    }

    public double getServoTargetVoltage() {
        return getMinInputVoltage() * 1.15 + 0.5;
    }

    public void setNominalInputVoltage(double voltage) {
        this.tripGraceTicks = 40;
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
        this.tripGraceTicks = 40; // 2-second grace period (40 ticks) so circuit solves and voltages establish without instant re-tripping
        this.inputCurrentCap = 0.0;
        this.outputHungry = false;
        this.lastDemandWatts = -1.0;
        markDirty();
    }

    @Override
    public void markRemoved() {
        super.markRemoved();
        onRemovedFromWorld();
    }

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

        // Synchronize input grid attachment
        UUID inGridId = inGrid != null ? inGrid.getGridId() : null;
        if (!java.util.Objects.equals(inGridId, lastInputGridId)) {
            if (lastInputGridId != null) {
                for (ElectricalGrid g : gridManager.getAllGrids()) {
                    if (g.getGridId().equals(lastInputGridId)) {
                        g.unregisterConsumer(inPos, inputConsumer);
                        break;
                    }
                }
            }
            if (inGrid != null) {
                inGrid.registerConsumer(inPos, inputConsumer);
            }
            lastInputGridId = inGridId;
        } else if (inGrid != null) {
            List<IElectricConsumer> list = inGrid.getConsumers().get(inPos);
            if (list == null || !list.contains(inputConsumer)) {
                inGrid.registerConsumer(inPos, inputConsumer);
            }
        }

        // Synchronize output grid attachment
        UUID outGridId = outGrid != null ? outGrid.getGridId() : null;
        if (!java.util.Objects.equals(outGridId, lastOutputGridId)) {
            if (lastOutputGridId != null) {
                for (ElectricalGrid g : gridManager.getAllGrids()) {
                    if (g.getGridId().equals(lastOutputGridId)) {
                        g.unregisterSource(outPos, outputSource);
                        break;
                    }
                }
            }
            if (outGrid != null) {
                outGrid.registerSource(outPos, outputSource);
            }
            lastOutputGridId = outGridId;
        } else if (outGrid != null) {
            List<IElectricSource> list = outGrid.getSources().get(outPos);
            if (list == null || !list.contains(outputSource)) {
                outGrid.registerSource(outPos, outputSource);
            }
        }

        // Measure input node voltage and frequency from grid, or direct block-to-block cascade
        double inFreq = 0.0;
        if (inGrid != null) {
            this.inputVoltage = inGrid.getNodeVoltage(inPos);
            inFreq = inGrid.getFrequency();
        } else {
            // Direct cascade check: if placed directly against another converter's output port
            BlockEntity be = world.getBlockEntity(inPos);
            if (be instanceof AbstractPowerConverterBlockEntity upstreamConverter) {
                if (upstreamConverter.getOutputPortDirection() == inDir.getOpposite()) {
                    this.inputVoltage = upstreamConverter.getOutputVoltage();
                    inFreq = upstreamConverter.getOutputFrequency();
                    double eta = Math.max(0.1, getEfficiency());
                    double powerNeeded = (outputPowerWatts / eta) + (tripped ? 0.0 : 2.0);
                    double currentDrawn = this.inputVoltage > 0.5 ? Math.min(upstreamConverter.getMaxOutputCurrent(), powerNeeded / this.inputVoltage) : 0.0;
                    this.inputCurrentAmps = currentDrawn;
                    this.inputPowerWatts = this.inputVoltage * currentDrawn;
                    upstreamConverter.outputSource.onPowerDrawn(currentDrawn, 0.05);
                } else {
                    this.inputVoltage = 0.0;
                    this.inputCurrentAmps = 0.0;
                    this.inputPowerWatts = 0.0;
                }
            } else {
                this.inputVoltage = 0.0;
                this.inputCurrentAmps = 0.0;
                this.inputPowerWatts = 0.0;
            }
        }

        // Disconnection safety: immediately reset output metrics if output port is disconnected
        if (outGrid == null) {
            BlockEntity downBe = world.getBlockEntity(outPos);
            if (!(downBe instanceof AbstractPowerConverterBlockEntity downConv && downConv.getInputPortDirection() == outDir.getOpposite())) {
                this.outputCurrentAmps = 0.0;
                this.outputPowerWatts = 0.0;
                this.actualOutputVoltage = 0.0;
            }
        }

        if (tripGraceTicks > 0) {
            tripGraceTicks--;
        }

        // Anti-Islanding Protection check for Grid-Tie Inverters
        if (isGridTie() && tripGraceTicks == 0) {
            double outGridV = outGrid != null ? outGrid.getNodeVoltage(outPos) : 0.0;
            if (outGridV < 20.0) {
                antiIslandingTicks++;
                if (antiIslandingTicks >= 4) { // 200ms sustained unpowered grid
                    this.tripped = true; // Trip offline within 4 ticks
                }
            } else {
                antiIslandingTicks = 0;
            }
        }

        // Waveform/frequency incompatibility check
        if (tripGraceTicks == 0 && !acceptsInputFrequency(inFreq) && this.inputVoltage > 2.0) {
            this.tripped = true;
        }

        double minVin = getMinInputVoltage();
        double maxVin = getMaxInputVoltage();

        // Undervoltage Lockout (UVLO):
        // Latched protection trip upon sustained undervoltage. Requires manual reset or setting adjustment.
        if (!tripped) {
            if (tripGraceTicks == 0 && inputVoltage < minVin * 0.9 && inputVoltage > 1.0) {
                underVoltageTicks++;
                if (underVoltageTicks >= 6) { // 300ms sustained undervoltage under overload
                    this.tripped = true;
                    this.underVoltageTicks = 0;
                }
            } else if (inputVoltage >= minVin) {
                underVoltageTicks = 0;
            }
        }

        boolean hardOff = tripped || inputVoltage <= 1.0 || inputVoltage > maxVin * 1.3;
        if (hardOff) {
            this.outputVoltageEmf = 0.0;
            this.actualOutputVoltage = 0.0;
            this.outputCurrentAmps = 0.0;
            this.outputPowerWatts = 0.0;
            if (tripped || inputVoltage <= 1.0) {
                reportedState = ElectricalState.OFF;
            } else {
                reportedState = ElectricalState.SURGE;
            }
        } else {
            double rawTarget = computeOutputVoltage(inputVoltage);
            this.outputVoltageEmf = rawTarget;

            // Throttle engages only near battery cutoff (below 110% of minVin), NOT during normal discharge
            double throttleVin = minVin * 1.10;
            double throttle = 1.0;
            if (inputVoltage < throttleVin) {
                throttle = Math.clamp((inputVoltage - minVin) / Math.max(0.1, throttleVin - minVin), 0.1, 1.0);
            }

            // Real physical circuit response: terminal voltage sags per Ohm's law and solver clamping.
            // Check terminal voltage under load for brownout classification without mutating EMF.
            double actualOutV = outGrid != null ? outGrid.getNodeVoltage(outPos) : outputVoltageEmf;
            this.actualOutputVoltage = actualOutV;

            if (outputCurrentAmps > 0.1 && actualOutV < rawTarget * 0.90) {
                reportedState = ElectricalState.BROWNOUT;
            } else if (inputVoltage < minVin || throttle < 0.95) {
                reportedState = ElectricalState.BROWNOUT;
            } else {
                reportedState = ElectricalState.NOMINAL;
            }
        }

        // Thermal accumulation and convective/fan cooling
        double dt = 0.05;
        double lossWatts = Math.max(0.0, inputPowerWatts - outputPowerWatts);
        double ambient = 20.0;
        double deltaT = Math.max(0.0, temperatureCelsius - ambient);

        double ratedPower = Math.max(100.0, getMaxOutputCurrent() * getNominalOutputVoltage());
        double eff = Math.max(0.1, getEfficiency());
        double ratedFullLoss = (ratedPower * (1.0 - eff)) / eff;

        double baseCoolingCoeff = Math.max(1.5, ratedFullLoss / 40.0);

        double fanMultiplier = 1.0;
        if (temperatureCelsius > 45.0) {
            fanMultiplier = 1.0 + Math.min(2.5, (temperatureCelsius - 45.0) / 20.0);
        }

        double coolingWatts = baseCoolingCoeff * fanMultiplier * deltaT;
        double heatCapacity = Math.max(120.0, ratedPower * 0.25);
        double deltaTemp = ((lossWatts - coolingWatts) / heatCapacity) * dt;
        this.temperatureCelsius = Math.max(ambient, this.temperatureCelsius + deltaTemp);

        // Thermal hazard check (trips offline at 125°C to protect components)
        if (this.temperatureCelsius >= 125.0 && !tripped) {
            this.tripped = true;
        }

        // Output power bookkeeping from measured terminal values
        if (outGrid != null) {
            this.actualOutputVoltage = outGrid.getNodeVoltage(outPos);
            this.outputPowerWatts = Math.max(0.0, actualOutputVoltage * outputCurrentAmps);
        } else {
            this.actualOutputVoltage = outputVoltageEmf;
            this.outputPowerWatts = outputVoltageEmf * outputCurrentAmps;
        }
    }

    /**
     * Subclasses calculate regulated output EMF based on conversion topology.
     */
    protected abstract double computeOutputVoltage(double inputVoltage);

    /**
     * Calculates input power demand in Watts.
     */
    protected double calculateInputPowerDemand() {
        if (tripped || inputVoltage <= 1.0) {
            return 0.0;
        }
        double eta = Math.max(0.1, getEfficiency());
        return (outputPowerWatts / eta) + (tripped ? 0.0 : 2.0);
    }

    /**
     * Calculates available output current in Amperes.
     */
    protected double calculateAvailableOutputCurrent() {
        if (tripped || inputVoltage <= 1.0) return 0.0;
        double rated = Math.max(0.0, getMaxOutputCurrent());
        double minVin = getMinInputVoltage();
        double throttleVin = minVin * 1.10;
        double throttle = 1.0;
        if (inputVoltage < throttleVin) {
            throttle = Math.clamp((inputVoltage - minVin) / Math.max(0.1, throttleVin - minVin), 0.1, 1.0);
        }
        return rated * throttle;
    }

    public void onRemovedFromWorld() {
        if (world instanceof ServerWorld sw) {
            GridManager gm = GridManager.get(sw);
            BlockPos inPos = pos.offset(getInputPortDirection());
            BlockPos outPos = pos.offset(getOutputPortDirection());

            for (ElectricalGrid g : gm.getAllGrids()) {
                g.unregisterConsumer(pos, inputConsumer);
                g.unregisterConsumer(inPos, inputConsumer);
                g.unregisterSource(pos, outputSource);
                g.unregisterSource(outPos, outputSource);
            }
        }
        this.outputCurrentAmps = 0.0;
        this.outputPowerWatts = 0.0;
        this.actualOutputVoltage = 0.0;
        this.inputCurrentAmps = 0.0;
        this.inputPowerWatts = 0.0;
        this.outputVoltageEmf = 0.0;
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
            double v = Math.max(1.0, inputVoltage);
            return Math.max(1e-4, (v * v) / p);
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
            return 0.05; // 50 mOhm source impedance
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
            double clamped = Math.min(Math.max(0.0, currentAmps), rated);
            outputCurrentAmps = Math.max(0.0, clamped);
        }
    }

    // ==================== Serialization ====================

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        this.targetOutputVoltage = view.getDouble("target_voltage", 12.0);
        this.temperatureCelsius = view.getDouble("temperature", 20.0);
        this.tripped = view.getBoolean("tripped", false);
        this.tripGraceTicks = 40;
        this.inputCurrentCap = 0.0;
        this.outputHungry = false;
        this.lastDemandWatts = -1.0;
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
