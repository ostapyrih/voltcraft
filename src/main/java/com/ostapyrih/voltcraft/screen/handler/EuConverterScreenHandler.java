package com.ostapyrih.voltcraft.screen.handler;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.screen.VoltcraftScreenHandlers;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ArrayPropertyDelegate;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;

/**
 * ScreenHandler synchronizing live telemetry between the energy bridge BlockEntity and client screen.
 * Property IDs preserved for client/server protocol compatibility.
 */
public class EuConverterScreenHandler extends ScreenHandler {

    public static final int PROP_INPUT_VOLTAGE_X10 = 0;
    public static final int PROP_INPUT_CURRENT_X100 = 1;
    public static final int PROP_INPUT_POWER_X10 = 2;
    public static final int PROP_INPUT_FREQ_X10 = 3;
    public static final int PROP_EU_STORED_LOW = 4;
    public static final int PROP_EU_STORED_HIGH = 5;
    public static final int PROP_EU_CAPACITY_LOW = 6;
    public static final int PROP_EU_CAPACITY_HIGH = 7;
    public static final int PROP_EU_RATE_X10 = 8;
    public static final int PROP_TEMPERATURE_X10 = 9;
    public static final int PROP_STATE_ORDINAL = 10;
    public static final int PROP_FLAGS = 11; // bit 0: tripped, bit 1: AC valid
    public static final int PROP_TOTAL_EU_LOW = 12;
    public static final int PROP_TOTAL_EU_HIGH = 13;

    public static final int PROPERTY_COUNT = 14;

    public static final int BUTTON_RESET_TRIP = 0;

    private final BlockPos pos;
    private final PropertyDelegate propertyDelegate;

    public EuConverterScreenHandler(int syncId, PlayerInventory playerInventory, BlockPos pos) {
        this(syncId, playerInventory, pos, new ArrayPropertyDelegate(PROPERTY_COUNT));
    }

    public EuConverterScreenHandler(int syncId, PlayerInventory playerInventory, BlockPos pos, PropertyDelegate propertyDelegate) {
        super(VoltcraftScreenHandlers.EU_CONVERTER_SCREEN_HANDLER, syncId);
        this.pos = pos;
        this.propertyDelegate = propertyDelegate;
        this.addProperties(propertyDelegate);
    }

    public static int packLow(long val) {
        return (int) (val & 0xFFFF);
    }

    public static int packHigh(long val) {
        return (int) ((val >> 16) & 0xFFFF);
    }

    public static long unpack(int low, int high) {
        return (((long) high & 0xFFFF) << 16) | ((long) low & 0xFFFF);
    }

    public BlockPos getPos() {
        return pos;
    }

    public double getInputVoltage() {
        return propertyDelegate.get(PROP_INPUT_VOLTAGE_X10) / 10.0;
    }

    public double getInputCurrent() {
        return propertyDelegate.get(PROP_INPUT_CURRENT_X100) / 100.0;
    }

    public double getInputPower() {
        return propertyDelegate.get(PROP_INPUT_POWER_X10) / 10.0;
    }

    public double getInputFrequency() {
        return propertyDelegate.get(PROP_INPUT_FREQ_X10) / 10.0;
    }

    public long getStoredEu() {
        return unpack(propertyDelegate.get(PROP_EU_STORED_LOW), propertyDelegate.get(PROP_EU_STORED_HIGH));
    }

    public long getCapacityEu() {
        return unpack(propertyDelegate.get(PROP_EU_CAPACITY_LOW), propertyDelegate.get(PROP_EU_CAPACITY_HIGH));
    }

    public double getEuOutputRate() {
        return propertyDelegate.get(PROP_EU_RATE_X10) / 10.0;
    }

    public long getTotalEuGenerated() {
        return unpack(propertyDelegate.get(PROP_TOTAL_EU_LOW), propertyDelegate.get(PROP_TOTAL_EU_HIGH));
    }

    public double getTemperature() {
        return propertyDelegate.get(PROP_TEMPERATURE_X10) / 10.0;
    }

    public ElectricalState getElectricalState() {
        int ord = propertyDelegate.get(PROP_STATE_ORDINAL);
        ElectricalState[] values = ElectricalState.values();
        return (ord >= 0 && ord < values.length) ? values[ord] : ElectricalState.OFF;
    }

    public boolean isTripped() {
        return (propertyDelegate.get(PROP_FLAGS) & 1) != 0;
    }

    public boolean isAcValid() {
        return (propertyDelegate.get(PROP_FLAGS) & 2) != 0;
    }

    @Override
    public boolean onButtonClick(PlayerEntity player, int id) {
        if (id == BUTTON_RESET_TRIP) {
            propertyDelegate.set(PROP_FLAGS, propertyDelegate.get(PROP_FLAGS) & ~1);
            return true;
        }
        return false;
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
