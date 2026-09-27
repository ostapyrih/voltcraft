package com.ostapyrih.voltcraft.block.entity.conversion;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.energy.IElectricConsumer;
import com.ostapyrih.voltcraft.api.grid.IGridTopologyListener;
import com.ostapyrih.voltcraft.block.conversion.EuConverterBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.screen.handler.EuConverterScreenHandler;
import com.ostapyrih.voltcraft.simulation.conversion.EuConverterLogic;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalGrid;
import com.ostapyrih.voltcraft.simulation.grid.GridManager;
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

import java.util.List;
import java.util.UUID;

/**
 * Block entity for the 230V AC to E Rotary Energy Bridge.
 * Acts as an IElectricConsumer strictly accepting 230V AC (207V-253V, >=40Hz),
 * converting 25 Watts continuous power into 1 E/t of standard Fabric energy.
 * Exposes a TeamReborn EnergyStorage capability (1 E bridged 1:1 with FE by interop mods)
 * for any energy consumer, and actively pushes E to adjacent energy receivers.
 */
public class EuConverterBlockEntity extends BlockEntity implements ExtendedScreenHandlerFactory<BlockPos> {

    public static final double NOMINAL_VOLTAGE = EuConverterLogic.NOMINAL_VOLTAGE;
    public static final double MIN_OPERATING_VOLTAGE = EuConverterLogic.MIN_OPERATING_VOLTAGE;
    public static final double MAX_OPERATING_VOLTAGE = EuConverterLogic.MAX_OPERATING_VOLTAGE;
    public static final double MIN_AC_FREQUENCY_HZ = EuConverterLogic.MIN_AC_FREQUENCY_HZ;
    public static final double WATTS_PER_EU_TICK = EuConverterLogic.WATTS_PER_EU_TICK;

    public final EuConverterLogic logic;
    public final SimpleEnergyStorage energyStorage;
    public final InputConsumer inputConsumer;

    private UUID lastInputGridId = null;

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
        this.inputConsumer = new InputConsumer();
    }

    public Direction getInputPortDirection() {
        if (getCachedState().contains(EuConverterBlock.FACING)) {
            return getCachedState().get(EuConverterBlock.FACING).getOpposite();
        }
        return Direction.NORTH;
    }

    @Nullable
    public EnergyStorage getEnergyStorage(@Nullable Direction side) {
        // Rear side is reserved for 230V AC input. All other sides expose E storage.
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

    @Override
    public void markRemoved() {
        super.markRemoved();
        onRemovedFromWorld();
    }

    public void onRemovedFromWorld() {
        if (world instanceof ServerWorld sw) {
            GridManager gm = GridManager.get(sw);
            BlockPos inPos = pos.offset(getInputPortDirection());
            for (ElectricalGrid g : gm.getAllGrids()) {
                g.unregisterConsumer(pos, inputConsumer);
                g.unregisterConsumer(inPos, inputConsumer);
            }
        }
    }

    /**
     * Executes once per tick from server block entity ticker.
     */
    public void tick(ServerWorld world) {
        Direction inDir = getInputPortDirection();
        BlockPos inPos = pos.offset(inDir);

        GridManager gridManager = GridManager.get(world);
        ElectricalGrid inGrid = gridManager.getGridAt(inPos);

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

        // Push available E to neighboring energy blocks / cables (except input side)
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

        // Update logic simulation
        logic.setStoredEu(this.energyStorage.amount);
        logic.updatePowerDemand(movedThisTick);
        logic.updateThermal(0.05);

        if (logic.isTripped() && !this.logic.isTripped()) {
            markDirty();
        }
    }

    // ==================== Sub-component IElectricConsumer ====================\

    public class InputConsumer implements IElectricConsumer, IGridTopologyListener {

        public boolean isRemoved() {
            return EuConverterBlockEntity.this.isRemoved();
        }

        @Override
        public BlockPos getPos() {
            return EuConverterBlockEntity.this.pos;
        }

        @Override
        public ElectricalState getElectricalState() {
            return logic.getElectricalState();
        }

        @Override
        public void setElectricalState(ElectricalState state) {
            logic.setElectricalState(state);
        }

        @Override
        public double getNominalPowerDemand() {
            return logic.getNominalPowerDemand();
        }

        @Override
        public double getNominalVoltage() {
            return logic.getNominalVoltage();
        }

        @Override
        public double getMinOperatingVoltage() {
            return logic.getMinOperatingVoltage();
        }

        @Override
        public double getMaxOperatingVoltage() {
            return logic.getMaxOperatingVoltage();
        }

        @Override
        public double getEquivalentResistance() {
            return logic.getEquivalentResistance();
        }

        @Override
        public void onPowerReceived(double terminalVoltage, double deliveredCurrent, double durationSeconds) {
            logic.onPowerReceived(terminalVoltage, deliveredCurrent, durationSeconds);
            syncStorage();
        }

        @Override
        public void onPowerReceived(double terminalVoltage, double deliveredCurrent, double durationSeconds, double frequencyHz) {
            logic.onPowerReceived(terminalVoltage, deliveredCurrent, durationSeconds, frequencyHz);
            syncStorage();
        }

        private void syncStorage() {
            energyStorage.amount = logic.getStoredEu();
            markDirty();
        }

        @Override
        public void onGridTopologyChanged() {
            EuConverterBlockEntity.this.lastInputGridId = null;
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
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        view.putLong("stored_eu", this.energyStorage.amount);
        view.putLong("total_eu", this.logic.getTotalEuGenerated());
        view.putDouble("temperature", this.logic.getTemperatureCelsius());
        view.putBoolean("tripped", this.logic.isTripped());
    }
}
