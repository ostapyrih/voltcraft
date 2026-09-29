package com.ostapyrih.voltcraft.block.entity.conversion;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.conversion.EuConverterBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.screen.handler.EuConverterScreenHandler;
import com.ostapyrih.voltcraft.simulation.electrical.ConverterElement;
import com.ostapyrih.voltcraft.simulation.conversion.EuConverterLogic;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
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
import org.jetbrains.annotations.Nullable;
import team.reborn.energy.api.EnergyStorage;
import team.reborn.energy.api.EnergyStorageUtil;
import team.reborn.energy.api.base.SimpleEnergyStorage;

/**
 * 230 V AC to E rotary energy bridge (25 W continuous to 1 E/t).
 * Grid-side input draws staged demand; the output pair stays reserved open because
 * energy leaves through the TeamReborn storage API. Reuses the shared converter element.
 */
public class EuConverterBlockEntity extends BlockEntity implements KernelAttachedBlock, ExtendedScreenHandlerFactory<BlockPos> {

    public static final double NOMINAL_VOLTAGE = EuConverterLogic.NOMINAL_VOLTAGE;
    public static final double MIN_OPERATING_VOLTAGE = EuConverterLogic.MIN_OPERATING_VOLTAGE;
    public static final double MAX_OPERATING_VOLTAGE = EuConverterLogic.MAX_OPERATING_VOLTAGE;
    public static final double MIN_AC_FREQUENCY_HZ = EuConverterLogic.MIN_AC_FREQUENCY_HZ;
    public static final double WATTS_PER_EU_TICK = EuConverterLogic.WATTS_PER_EU_TICK;

    public final EuConverterLogic logic;
    public final SimpleEnergyStorage energyStorage;

    protected final PropertyDelegate propertyDelegate = new PropertyDelegate() {
        @Override
        public int get(int index) {
            return switch (index) {
                case EuConverterScreenHandler.PROP_INPUT_VOLTAGE_X10 -> (int) Math.round(logic.getInputVoltage() * 10.0);
                case EuConverterScreenHandler.PROP_INPUT_CURRENT_X100 -> (int) Math.round(logic.getInputCurrentAmps() * 100.0);
                case EuConverterScreenHandler.PROP_INPUT_POWER_X10 -> (int) Math.round(logic.getInputPowerWatts() * 10.0);
                case EuConverterScreenHandler.PROP_INPUT_FREQ_X10 -> (int) Math.round(logic.getInputFrequency() * 10.0);
                case EuConverterScreenHandler.PROP_EU_STORED_LOW -> EuConverterScreenHandler.packLow(energyStorage.amount);
                case EuConverterScreenHandler.PROP_EU_STORED_HIGH -> EuConverterScreenHandler.packHigh(energyStorage.amount);
                case EuConverterScreenHandler.PROP_EU_CAPACITY_LOW -> EuConverterScreenHandler.packLow(energyStorage.capacity);
                case EuConverterScreenHandler.PROP_EU_CAPACITY_HIGH -> EuConverterScreenHandler.packHigh(energyStorage.capacity);
                case EuConverterScreenHandler.PROP_EU_RATE_X10 -> (int) Math.round(logic.getCurrentEuOutputRate() * 10.0);
                case EuConverterScreenHandler.PROP_TEMPERATURE_X10 -> (int) Math.round(logic.getTemperatureCelsius() * 10.0);
                case EuConverterScreenHandler.PROP_STATE_ORDINAL -> logic.getElectricalState().ordinal();
                case EuConverterScreenHandler.PROP_FLAGS -> {
                    int flags = 0;
                    if (logic.isTripped()) flags |= 1;
                    if (logic.isAcOperatingValid()) flags |= 2;
                    yield flags;
                }
                case EuConverterScreenHandler.PROP_TOTAL_EU_LOW -> EuConverterScreenHandler.packLow(logic.getTotalEuGenerated());
                case EuConverterScreenHandler.PROP_TOTAL_EU_HIGH -> EuConverterScreenHandler.packHigh(logic.getTotalEuGenerated());
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            if (index == EuConverterScreenHandler.PROP_FLAGS) {
                if ((value & 1) == 0 && logic.isTripped()) {
                    resetTrip();
                }
            }
        }

        @Override
        public int size() {
            return EuConverterScreenHandler.PROPERTY_COUNT;
        }
    };

    public EuConverterBlockEntity(BlockPos pos, BlockState state) {
        super(VoltcraftBlockEntityTypes.EU_CONVERTER_BLOCK_ENTITY, pos, state);
        this.logic = new EuConverterLogic(pos);
        this.energyStorage = new SimpleEnergyStorage(EuConverterLogic.DEFAULT_CAPACITY, 0L, EuConverterLogic.MAX_EXTRACT_RATE) {
            @Override
            protected void onFinalCommit() {
                super.onFinalCommit();
                logic.setStoredEu(this.amount);
                markDirty();
            }
        };
    }

    public Direction getInputPortDirection() {
        if (getCachedState().contains(EuConverterBlock.FACING)) {
            return getCachedState().get(EuConverterBlock.FACING).getOpposite();
        }
        return Direction.NORTH;
    }

    @Nullable
    public EnergyStorage getEnergyStorage(@Nullable Direction side) {
        // Rear side is the 230V AC input; all other sides expose E storage.
        if (side != null && side == getInputPortDirection()) {
            return null;
        }
        return this.energyStorage;
    }

    public boolean isAcOperatingValid() {
        return logic.isAcOperatingValid();
    }

    public boolean isTripped() {
        return logic.isTripped();
    }

    public void resetTrip() {
        logic.resetTrip();
        markDirty();
    }

    public ElectricalState getElectricalState() {
        return logic.getElectricalState();
    }

    private final double[] kernelState =
        ConverterElement.newStateArray();
    private final double[] telemetryCell =
        new double[ConverterElement.TELE_LEN];
    private double stagedInputDemandWatts = 0.0;
    private final ElectricalElement element =
        new ConverterElement(
            this::isTripped, this::getStagedInputDemandWatts, () -> 0.0,
            () -> NOMINAL_VOLTAGE, telemetryCell);

    private double getStagedInputDemandWatts() {
        return stagedInputDemandWatts;
    }

    @Override
    public ElectricalElement getElement() {
        return element;
    }

    @Override
    public BlockPos[] getTerminalPositions() {
        return ConverterElement
            .resolveConverterTerminals(pos, readFacing());
    }

    private Direction readFacing() {
        try {
            BlockState cached = getCachedState();
            if (cached != null && cached.contains(EuConverterBlock.FACING)) {
                Direction facing = cached.get(EuConverterBlock.FACING);
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
        return ConverterElement.snapshotState(kernelState);
    }

    @Override
    public void setStateArray(double[] state) {
        ConverterElement.assignState(kernelState, state);
    }

    @Override
    public boolean isActiveSource() {
        // The output pair is reserved open: this bridge never sources the grid.
        return false;
    }

    @Override
    public boolean isACSource() {
        // No grid output waveform: the output pair is reserved open.
        return ConverterElement.isACOutput(getTypeKind());
    }

    public int getTypeKind() {
        return ConverterElement.TYPE_KIND_EU;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        double teleVIn = telemetryCell[ConverterElement.TELE_V_IN];
        double base = logic.getNominalPowerDemand();
        if (!Double.isFinite(base) || base < 0.0) {
            base = 0.0;
        }
        this.stagedInputDemandWatts = (logic.isTripped()
            || !(teleVIn > ConverterElement.DEAD_RAIL_VOLTS))
            ? 0.0 : base;
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    public void setElectricalState(ElectricalState state) {
        logic.setElectricalState(state);
    }

    public boolean isInputPort(Direction side) {
        return side == getInputPortDirection();
    }

    public boolean isOutputPort(Direction side) {
        return false;
    }

    public double getEfficiency() {
        return 0.95;
    }

    public double getTargetOutputVoltage() {
        return NOMINAL_VOLTAGE;
    }

    public double getInputVoltage() {
        return logic.getInputVoltage();
    }

    public double getInputCurrentAmps() {
        return logic.getInputCurrentAmps();
    }

    public double getInputPowerWatts() {
        return logic.getInputPowerWatts();
    }

    public double getInputFrequency() {
        return logic.getInputFrequency();
    }

    public double getCurrentEuOutputRate() {
        return logic.getCurrentEuOutputRate();
    }

    public long getTotalEuGenerated() {
        return logic.getTotalEuGenerated();
    }

    public double getTemperatureCelsius() {
        return logic.getTemperatureCelsius();
    }

    @Override
    public Text getDisplayName() {
        return Text.translatable("block.voltcraft.converter_eu");
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
        return new EuConverterScreenHandler(syncId, playerInventory, this.pos, this.propertyDelegate);
    }

    @Override
    public BlockPos getScreenOpeningData(ServerPlayerEntity player) {
        return this.pos;
    }

    public void tick(ServerWorld world) {
        Direction inDir = getInputPortDirection();

        long movedThisTick = 0;
        for (Direction dir : Direction.values()) {
            if (dir == inDir) continue;
            BlockPos targetPos = pos.offset(dir);
            EnergyStorage target = EnergyStorage.SIDED.find(world, targetPos, dir.getOpposite());
            if (target != null && target.supportsInsertion()) {
                long moved = EnergyStorageUtil.move(this.energyStorage, target, EuConverterLogic.MAX_EXTRACT_RATE, null);
                movedThisTick += moved;
            }
        }

        logic.setStoredEu(this.energyStorage.amount);
        logic.updatePowerDemand(movedThisTick);
        logic.updateThermal(0.05);

        if (logic.isTripped() && !this.logic.isTripped()) {
            markDirty();
        }
    }

    // ==================== Serialization ====================\

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        this.energyStorage.amount = view.getLong("stored_eu", 0L);
        this.logic.setStoredEu(this.energyStorage.amount);
        this.logic.setTotalEuGenerated(view.getLong("total_eu", 0L));
        this.logic.setTemperatureCelsius(view.getDouble("temperature", 20.0));
        this.logic.setTripped(view.getBoolean("tripped", false));
        ConverterElement.assignState(
            kernelState, ConverterElement.readNbtState(view));
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        view.putLong("stored_eu", this.energyStorage.amount);
        view.putLong("total_eu", this.logic.getTotalEuGenerated());
        view.putDouble("temperature", this.logic.getTemperatureCelsius());
        view.putBoolean("tripped", this.logic.isTripped());
        ConverterElement.writeNbt(view, logic.isTripped());
    }
}
