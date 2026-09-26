package com.ostapyrih.voltcraft.screen.handler;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.block.entity.conversion.AbstractPowerConverterBlockEntity;
import com.ostapyrih.voltcraft.screen.VoltcraftScreenHandlers;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ArrayPropertyDelegate;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * ScreenHandler for power converters, inverters, transformers, rectifiers, and MPPT charge controllers.
 * Synchronizes real-time telemetry (input/output V, I, P, efficiency, temp, THD, status, ports)
 * and handles voltage step adjustments, preset selections, and trip resets.
 */
public class ConverterScreenHandler extends ScreenHandler {

    public static final int PROP_INPUT_VOLTAGE_X10 = 0;
    public static final int PROP_INPUT_CURRENT_LOW = 1;
    public static final int PROP_INPUT_CURRENT_HIGH = 2;
    public static final int PROP_INPUT_POWER_LOW = 3;
    public static final int PROP_INPUT_POWER_HIGH = 4;

    public static final int PROP_OUTPUT_VOLTAGE_X10 = 5;
    public static final int PROP_OUTPUT_CURRENT_LOW = 6;
    public static final int PROP_OUTPUT_CURRENT_HIGH = 7;
    public static final int PROP_OUTPUT_POWER_LOW = 8;
    public static final int PROP_OUTPUT_POWER_HIGH = 9;

    public static final int PROP_TARGET_VOLTAGE_X10 = 10;
    public static final int PROP_TEMPERATURE_X10 = 11;
    public static final int PROP_EFFICIENCY_X10 = 12;
    public static final int PROP_THD_X10 = 13;
    public static final int PROP_STATE_ORDINAL = 14;
    public static final int PROP_FLAGS = 15; // bit 0: tripped, bit 1: configurable, bit 2: grid-tie
    public static final int PROP_INPUT_PORT_DIR = 16;
    public static final int PROP_OUTPUT_PORT_DIR = 17;
    public static final int PROP_TYPE_KIND = 18; // 0: DC-DC, 1: Inverter, 2: Transformer, 3: Rectifier, 4: Charge Controller
    public static final int PROP_NOMINAL_INPUT_VOLTAGE_X10 = 19;

    public static final int PROPERTY_COUNT = 20;

    public static final int TYPE_DC_DC = 0;
    public static final int TYPE_INVERTER = 1;
    public static final int TYPE_TRANSFORMER = 2;
    public static final int TYPE_RECTIFIER = 3;
    public static final int TYPE_CHARGE_CONTROLLER = 4;

    // Button IDs for onButtonClick
    public static final int BUTTON_DEC_10V = 0;
    public static final int BUTTON_DEC_1V = 1;
    public static final int BUTTON_INC_1V = 2;
    public static final int BUTTON_INC_10V = 3;
    public static final int BUTTON_RESET_TRIP = 4;

    public static final int BUTTON_PRESET_5V = 10;
    public static final int BUTTON_PRESET_12V = 11;
    public static final int BUTTON_PRESET_24V = 12;
    public static final int BUTTON_PRESET_48V = 13;
    public static final int BUTTON_PRESET_120V = 14;
    public static final int BUTTON_PRESET_230V = 15;

    public static final int BUTTON_INVERTER_IN_12V = 20;
    public static final int BUTTON_INVERTER_IN_24V = 21;
    public static final int BUTTON_INVERTER_IN_48V = 22;

    private final BlockPos pos;
    private final PropertyDelegate propertyDelegate;

    public ConverterScreenHandler(int syncId, PlayerInventory playerInventory, BlockPos pos) {
        this(syncId, playerInventory, pos, new ArrayPropertyDelegate(PROPERTY_COUNT));
    }

    public ConverterScreenHandler(int syncId, PlayerInventory playerInventory, BlockPos pos, PropertyDelegate propertyDelegate) {
        super(VoltcraftScreenHandlers.CONVERTER_SCREEN_HANDLER, syncId);
        this.pos = pos;
        this.propertyDelegate = propertyDelegate;
        this.addProperties(propertyDelegate);

        // Pre-populate client-side delegate from world block entity immediately upon creation
        if (playerInventory != null && playerInventory.player != null && playerInventory.player.getEntityWorld() != null) {
            net.minecraft.block.entity.BlockEntity be = playerInventory.player.getEntityWorld().getBlockEntity(pos);
            if (be instanceof AbstractPowerConverterBlockEntity converter) {
                propertyDelegate.set(PROP_TYPE_KIND, converter.getTypeKind());
                propertyDelegate.set(PROP_FLAGS, (converter.isOutputConfigurable() ? 2 : 0) | (converter.isGridTie() ? 4 : 0));
                propertyDelegate.set(PROP_TARGET_VOLTAGE_X10, (int) Math.round(converter.getTargetOutputVoltage() * 10.0));
                propertyDelegate.set(PROP_NOMINAL_INPUT_VOLTAGE_X10, (int) Math.round(converter.getNominalInputVoltage() * 10.0));
            }
        }
    }

    public static int packLow(int val) {
        return val & 0xFFFF;
    }

    public static int packHigh(int val) {
        return (val >> 16) & 0xFFFF;
    }

    public static int unpack(int low, int high) {
        return (high << 16) | (low & 0xFFFF);
    }

    public BlockPos getPos() {
        return pos;
    }

    // ==================== Telemetry Getters ====================

    public double getInputVoltage() {
        return propertyDelegate.get(PROP_INPUT_VOLTAGE_X10) / 10.0;
    }

    public double getInputCurrent() {
        return unpack(propertyDelegate.get(PROP_INPUT_CURRENT_LOW), propertyDelegate.get(PROP_INPUT_CURRENT_HIGH)) / 100.0;
    }

    public double getInputPower() {
        return unpack(propertyDelegate.get(PROP_INPUT_POWER_LOW), propertyDelegate.get(PROP_INPUT_POWER_HIGH)) / 10.0;
    }

    public double getOutputVoltage() {
        return propertyDelegate.get(PROP_OUTPUT_VOLTAGE_X10) / 10.0;
    }

    public double getOutputCurrent() {
        return unpack(propertyDelegate.get(PROP_OUTPUT_CURRENT_LOW), propertyDelegate.get(PROP_OUTPUT_CURRENT_HIGH)) / 100.0;
    }

    public double getOutputPower() {
        return unpack(propertyDelegate.get(PROP_OUTPUT_POWER_LOW), propertyDelegate.get(PROP_OUTPUT_POWER_HIGH)) / 10.0;
    }

    public double getTargetVoltage() {
        return propertyDelegate.get(PROP_TARGET_VOLTAGE_X10) / 10.0;
    }

    public double getNominalInputVoltage() {
        return propertyDelegate.get(PROP_NOMINAL_INPUT_VOLTAGE_X10) / 10.0;
    }

    public double getTemperature() {
        return propertyDelegate.get(PROP_TEMPERATURE_X10) / 10.0;
    }

    public double getEfficiency() {
        return propertyDelegate.get(PROP_EFFICIENCY_X10) / 10.0;
    }

    public double getTHD() {
        return propertyDelegate.get(PROP_THD_X10) / 10.0;
    }

    public ElectricalState getElectricalState() {
        int ord = propertyDelegate.get(PROP_STATE_ORDINAL);
        ElectricalState[] values = ElectricalState.values();
        return (ord >= 0 && ord < values.length) ? values[ord] : ElectricalState.OFF;
    }

    public boolean isTripped() {
        return (propertyDelegate.get(PROP_FLAGS) & 1) != 0;
    }

    public boolean isConfigurable() {
        return (propertyDelegate.get(PROP_FLAGS) & 2) != 0;
    }

    public boolean isGridTie() {
        return (propertyDelegate.get(PROP_FLAGS) & 4) != 0;
    }

    public Direction getInputPortDirection() {
        int ord = propertyDelegate.get(PROP_INPUT_PORT_DIR);
        Direction[] values = Direction.values();
        return (ord >= 0 && ord < values.length) ? values[ord] : Direction.NORTH;
    }

    public Direction getOutputPortDirection() {
        int ord = propertyDelegate.get(PROP_OUTPUT_PORT_DIR);
        Direction[] values = Direction.values();
        return (ord >= 0 && ord < values.length) ? values[ord] : Direction.SOUTH;
    }

    public int getTypeKind() {
        return propertyDelegate.get(PROP_TYPE_KIND);
    }

    // ==================== Button Interactions ====================

    @Override
    public boolean onButtonClick(PlayerEntity player, int id) {
        if (!isConfigurable() && id != BUTTON_RESET_TRIP) {
            return false;
        }

        double currentTarget = getTargetVoltage();
        double updated = currentTarget;

        switch (id) {
            case BUTTON_DEC_10V -> updated -= 10.0;
            case BUTTON_DEC_1V -> updated -= 1.0;
            case BUTTON_INC_1V -> updated += 1.0;
            case BUTTON_INC_10V -> updated += 10.0;
            case BUTTON_PRESET_5V -> updated = 5.0;
            case BUTTON_PRESET_12V -> updated = 12.0;
            case BUTTON_PRESET_24V -> updated = 24.0;
            case BUTTON_PRESET_48V -> updated = 48.0;
            case BUTTON_PRESET_120V -> updated = 120.0;
            case BUTTON_PRESET_230V -> updated = 230.0;
            case BUTTON_INVERTER_IN_12V -> {
                propertyDelegate.set(PROP_NOMINAL_INPUT_VOLTAGE_X10, 120);
                return true;
            }
            case BUTTON_INVERTER_IN_24V -> {
                propertyDelegate.set(PROP_NOMINAL_INPUT_VOLTAGE_X10, 240);
                return true;
            }
            case BUTTON_INVERTER_IN_48V -> {
                propertyDelegate.set(PROP_NOMINAL_INPUT_VOLTAGE_X10, 480);
                return true;
            }
            case BUTTON_RESET_TRIP -> {
                propertyDelegate.set(PROP_FLAGS, propertyDelegate.get(PROP_FLAGS) & ~1);
                return true;
            }
            default -> {
                return false;
            }
        }

        updated = Math.clamp(updated, 1.0, 600.0);
        propertyDelegate.set(PROP_TARGET_VOLTAGE_X10, (int) Math.round(updated * 10.0));
        return true;
    }

    public void setTargetVoltageLocal(double voltage) {
        double clamped = Math.clamp(voltage, 1.0, 600.0);
        propertyDelegate.set(PROP_TARGET_VOLTAGE_X10, (int) Math.round(clamped * 10.0));
    }

    public void setNominalInputVoltageLocal(double voltage) {
        double clamped = Math.clamp(voltage, 1.0, 600.0);
        propertyDelegate.set(PROP_NOMINAL_INPUT_VOLTAGE_X10, (int) Math.round(clamped * 10.0));
    }

    public void setTargetVoltageFromPacket(double voltage) {
        setTargetVoltageLocal(voltage);
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }
}
