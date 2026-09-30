package com.ostapyrih.voltcraft.block.entity.creative;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.screen.handler.CreativeLoadScreenHandler;
import com.ostapyrih.voltcraft.simulation.creative.CreativeLoadLogic;
import com.ostapyrih.voltcraft.simulation.creative.CreativeLoadLogic.LoadMode;
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
import com.ostapyrih.voltcraft.block.creative.CreativeLoadBlock;

import com.ostapyrih.voltcraft.simulation.electrical.CreativeLoadElement;

/**
 * Creative-only test load (constant resistance / power / current).
 * Disabled or non-positive targets stamp open circuit; holds no kernel state.
 * Sign convention is T0-referenced: {@code V = Vt[0] - Vt[1]}.
 */
public class CreativeLoadBlockEntity extends BlockEntity implements KernelAttachedBlock, ExtendedScreenHandlerFactory<BlockPos> {


    private final CreativeLoadLogic logic;
    private final double[] telemetryCell = new double[2];
    private final double[] stateArray = CreativeLoadElement.newStateArray();
    private final ElectricalElement element;

    private final PropertyDelegate propertyDelegate = new PropertyDelegate() {
        @Override
        public int get(int index) {
            int targetVal = (int) Math.round(logic.getTargetValue() * 100.0);
            int v = (int) Math.round(logic.getLastMeasuredVoltage() * 10.0);
            int i = (int) Math.round(logic.getLastDeliveredCurrent() * 100.0);
            int p = (int) Math.round(logic.getLastDeliveredPower() * 10.0);
            double rVal = getEquivalentResistance();
            if (Double.isInfinite(rVal) || Double.isNaN(rVal)) rVal = 99999.0;
            int rEq = (int) Math.round(Math.min(99999.0, rVal) * 100.0);
            int kJ = (int) Math.round(logic.getTotalEnergyConsumedJoules() / 1000.0);

            return switch (index) {
                case CreativeLoadScreenHandler.PROP_MODE_ORDINAL -> logic.getMode().ordinal();
                case CreativeLoadScreenHandler.PROP_TARGET_VALUE_LOW -> CreativeLoadScreenHandler.packLow(targetVal);
                case CreativeLoadScreenHandler.PROP_TARGET_VALUE_HIGH -> CreativeLoadScreenHandler.packHigh(targetVal);
                case CreativeLoadScreenHandler.PROP_ENABLED -> logic.isEnabled() ? 1 : 0;
                case CreativeLoadScreenHandler.PROP_VOLTAGE_X10 -> v;
                case CreativeLoadScreenHandler.PROP_CURRENT_LOW -> CreativeLoadScreenHandler.packLow(i);
                case CreativeLoadScreenHandler.PROP_CURRENT_HIGH -> CreativeLoadScreenHandler.packHigh(i);
                case CreativeLoadScreenHandler.PROP_POWER_LOW -> CreativeLoadScreenHandler.packLow(p);
                case CreativeLoadScreenHandler.PROP_POWER_HIGH -> CreativeLoadScreenHandler.packHigh(p);
                case CreativeLoadScreenHandler.PROP_RESISTANCE_LOW -> CreativeLoadScreenHandler.packLow(rEq);
                case CreativeLoadScreenHandler.PROP_RESISTANCE_HIGH -> CreativeLoadScreenHandler.packHigh(rEq);
                case CreativeLoadScreenHandler.PROP_ENERGY_LOW -> CreativeLoadScreenHandler.packLow(kJ);
                case CreativeLoadScreenHandler.PROP_ENERGY_HIGH -> CreativeLoadScreenHandler.packHigh(kJ);
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {}

        @Override
        public int size() {
            return CreativeLoadScreenHandler.PROPERTY_COUNT;
        }
    };

    public CreativeLoadBlockEntity(BlockPos pos, BlockState state) {
        super(VoltcraftBlockEntityTypes.CREATIVE_LOAD_BLOCK_ENTITY, pos, state);
        this.logic = new CreativeLoadLogic(pos);
        this.element = new CreativeLoadElement(
            () -> logic.getMode().ordinal(), () -> logic.getTargetValue(),
            () -> logic.isEnabled(), () -> logic.getNominalVoltage(), telemetryCell);
    }

    public PropertyDelegate getPropertyDelegate() {
        return propertyDelegate;
    }

    @Override
    public Text getDisplayName() {
        return Text.translatable(getCachedState().getBlock().getTranslationKey());
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
        return new CreativeLoadScreenHandler(syncId, playerInventory, this.pos, this.propertyDelegate);
    }

    @Override
    public BlockPos getScreenOpeningData(ServerPlayerEntity player) {
        return this.pos;
    }

    public CreativeLoadLogic getLogic() {
        return logic;
    }

    public LoadMode getMode() {
        return logic.getMode();
    }

    public void setMode(LoadMode mode) {
        logic.setMode(mode);
        markDirty();
    }

    public double getTargetValue() {
        return logic.getTargetValue();
    }

    public void setTargetValue(double targetValue) {
        logic.setTargetValue(targetValue);
        markDirty();
    }

    public boolean isEnabled() {
        return logic.isEnabled();
    }

    public void setEnabled(boolean enabled) {
        logic.setEnabled(enabled);
        markDirty();
    }

    public void toggleEnabled() {
        logic.toggleEnabled();
        markDirty();
    }

    public double getLastMeasuredVoltage() {
        return logic.getLastMeasuredVoltage();
    }

    public double getLastDeliveredCurrent() {
        return logic.getLastDeliveredCurrent();
    }

    public double getLastDeliveredPower() {
        return logic.getLastDeliveredPower();
    }

    public double getTotalEnergyConsumedJoules() {
        return logic.getTotalEnergyConsumedJoules();
    }

    public void resetEnergy() {
        logic.resetEnergy();
        markDirty();
    }

    public String getTargetDisplay() {
        return logic.getTargetDisplay();
    }

    public LoadMode cycleMode() {
        LoadMode m = logic.cycleMode();
        markDirty();
        return m;
    }

    public double cycleTargetValue() {
        double val = logic.cycleTargetValue();
        markDirty();
        return val;
    }

    // ==================== KernelAttachedBlock ====================

    @Override
    public ElectricalElement getElement() {
        return element;
    }

    @Override
    public BlockPos[] getTerminalPositions() {
        return CreativeLoadElement.resolveTerminals(pos, readFacing());
    }

    private Direction readFacing() {
        try {
            BlockState cached = getCachedState();
            if (cached != null && cached.contains(CreativeLoadBlock.FACING)) {
                Direction facing = cached.get(CreativeLoadBlock.FACING);
                if (facing != null) {
                    return facing;
                }
            }
        } catch (Exception ignored) {
        }
        return Direction.NORTH;
    }

    @Override
    public double[] getStateArray() {
        return CreativeLoadElement.snapshotState(stateArray);
    }

    @Override
    public void setStateArray(double[] state) {
        CreativeLoadElement.assignState(stateArray, state);
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public boolean isActiveSource() {
        return CreativeLoadElement.isActiveSource();
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        logic.onPowerReceived(telemetryCell[CreativeLoadElement.TELE_V],
            telemetryCell[CreativeLoadElement.TELE_I], GridConstants.DT);
    }

    // ==================== Config hooks (plain methods, no grid role) ====================

    public ElectricalState getElectricalState() {
        return logic.getElectricalState();
    }

    public void setElectricalState(ElectricalState state) {
        logic.setElectricalState(state);
    }

    public double getNominalPowerDemand() {
        return logic.getNominalPowerDemand();
    }

    public double getNominalVoltage() {
        return logic.getNominalVoltage();
    }

    public double getMinOperatingVoltage() {
        return logic.getMinOperatingVoltage();
    }

    public double getMaxOperatingVoltage() {
        return logic.getMaxOperatingVoltage();
    }

    public double getEquivalentResistance() {
        return logic.getEquivalentResistance();
    }

    public void onPowerReceived(double terminalVoltage, double deliveredCurrent, double durationSeconds) {
        logic.onPowerReceived(terminalVoltage, deliveredCurrent, durationSeconds);
    }

    // ==================== Serialization ====================

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        int modeOrdinal = view.getInt("mode", 0);
        if (modeOrdinal >= 0 && modeOrdinal < LoadMode.values().length) {
            logic.setMode(LoadMode.values()[modeOrdinal]);
        }
        logic.setTargetValue(view.getDouble("target_value", 10.0));
        logic.setEnabled(view.getBoolean("enabled", true));
        logic.setTotalEnergyConsumedJoules(view.getDouble("total_energy", 0.0));
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        view.putInt("mode", logic.getMode().ordinal());
        view.putDouble("target_value", logic.getTargetValue());
        view.putBoolean("enabled", logic.isEnabled());
        view.putDouble("total_energy", logic.getTotalEnergyConsumedJoules());
    }
}
