package com.ostapyrih.voltcraft.screen.handler;

import com.ostapyrih.voltcraft.block.entity.creative.CreativeGeneratorBlockEntity;
import com.ostapyrih.voltcraft.screen.VoltcraftScreenHandlers;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ArrayPropertyDelegate;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;

/**
 * ScreenHandler for the Creative Power Generator.
 * Handles synchronizing live output telemetry and controlling EMF voltage, current limit,
 * waveform frequency, internal resistance, and generator state.
 */
public class CreativeGeneratorScreenHandler extends ScreenHandler {

    public static final int PROP_VOLTAGE_LOW = 0;
    public static final int PROP_VOLTAGE_HIGH = 1;
    public static final int PROP_CURRENT_LIMIT_LOW = 2;
    public static final int PROP_CURRENT_LIMIT_HIGH = 3;
    public static final int PROP_R_INT_LOW = 4;
    public static final int PROP_R_INT_HIGH = 5;
    public static final int PROP_FREQUENCY_X10 = 6;
    public static final int PROP_ENABLED = 7;
    public static final int PROP_OUT_CURRENT_LOW = 8;
    public static final int PROP_OUT_CURRENT_HIGH = 9;
    public static final int PROP_OUT_POWER_LOW = 10;
    public static final int PROP_OUT_POWER_HIGH = 11;
    public static final int PROP_ENERGY_LOW = 12;
    public static final int PROP_ENERGY_HIGH = 13;

    public static final int PROPERTY_COUNT = 14;

    // Button IDs
    public static final int BUTTON_DEC_10V = 0;
    public static final int BUTTON_DEC_1V = 1;
    public static final int BUTTON_INC_1V = 2;
    public static final int BUTTON_INC_10V = 3;

    public static final int BUTTON_V_5V = 10;
    public static final int BUTTON_V_12V = 11;
    public static final int BUTTON_V_24V = 12;
    public static final int BUTTON_V_48V = 13;
    public static final int BUTTON_V_120V = 14;
    public static final int BUTTON_V_230V = 15;
    public static final int BUTTON_V_400V = 16;

    public static final int BUTTON_TOGGLE_POWER = 20;
    public static final int BUTTON_CYCLE_FREQ = 21;
    public static final int BUTTON_CYCLE_R_INT = 22;
    public static final int BUTTON_CYCLE_CURRENT_LIMIT = 23;
    public static final int BUTTON_RESET_ENERGY = 24;

    private final BlockPos pos;
    private final PropertyDelegate propertyDelegate;

    public CreativeGeneratorScreenHandler(int syncId, PlayerInventory playerInventory, BlockPos pos) {
        this(syncId, playerInventory, pos, new ArrayPropertyDelegate(PROPERTY_COUNT));
    }

    public CreativeGeneratorScreenHandler(int syncId, PlayerInventory playerInventory, BlockPos pos, PropertyDelegate propertyDelegate) {
        super(VoltcraftScreenHandlers.CREATIVE_GENERATOR_SCREEN_HANDLER, syncId);
        this.pos = pos;
        this.propertyDelegate = propertyDelegate;
        this.addProperties(propertyDelegate);
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

    public double getVoltage() {
        return unpack(propertyDelegate.get(PROP_VOLTAGE_LOW), propertyDelegate.get(PROP_VOLTAGE_HIGH)) / 10.0;
    }

    public double getMaxCurrent() {
        return unpack(propertyDelegate.get(PROP_CURRENT_LIMIT_LOW), propertyDelegate.get(PROP_CURRENT_LIMIT_HIGH)) / 10.0;
    }

    public double getInternalResistance() {
        int raw = unpack(propertyDelegate.get(PROP_R_INT_LOW), propertyDelegate.get(PROP_R_INT_HIGH));
        return Math.max(1e-5, raw / 10000.0);
    }

    public double getFrequency() {
        return propertyDelegate.get(PROP_FREQUENCY_X10) / 10.0;
    }

    public String getFrequencyDisplay() {
        double f = getFrequency();
        if (f <= 0.001) return "DC";
        return String.format("AC %.0f Hz", f);
    }

    public boolean isEnabled() {
        return propertyDelegate.get(PROP_ENABLED) != 0;
    }

    public double getDeliveredCurrent() {
        return unpack(propertyDelegate.get(PROP_OUT_CURRENT_LOW), propertyDelegate.get(PROP_OUT_CURRENT_HIGH)) / 100.0;
    }

    public double getDeliveredPower() {
        return unpack(propertyDelegate.get(PROP_OUT_POWER_LOW), propertyDelegate.get(PROP_OUT_POWER_HIGH)) / 10.0;
    }

    public double getTotalEnergyKwh() {
        int kJ = unpack(propertyDelegate.get(PROP_ENERGY_LOW), propertyDelegate.get(PROP_ENERGY_HIGH));
        return kJ / 3600.0;
    }

    @Override
    public boolean onButtonClick(PlayerEntity player, int id) {
        BlockEntity be = player.getEntityWorld().getBlockEntity(pos);
        if (!(be instanceof CreativeGeneratorBlockEntity gen)) {
            return false;
        }

        switch (id) {
            case BUTTON_DEC_10V -> gen.setVoltage(Math.max(0.0, gen.getVoltage() - 10.0));
            case BUTTON_DEC_1V -> gen.setVoltage(Math.max(0.0, gen.getVoltage() - 1.0));
            case BUTTON_INC_1V -> gen.setVoltage(gen.getVoltage() + 1.0);
            case BUTTON_INC_10V -> gen.setVoltage(gen.getVoltage() + 10.0);
            case BUTTON_V_5V -> gen.setVoltage(5.0);
            case BUTTON_V_12V -> gen.setVoltage(12.0);
            case BUTTON_V_24V -> gen.setVoltage(24.0);
            case BUTTON_V_48V -> gen.setVoltage(48.0);
            case BUTTON_V_120V -> gen.setVoltage(120.0);
            case BUTTON_V_230V -> gen.setVoltage(230.0);
            case BUTTON_V_400V -> gen.setVoltage(400.0);
            case BUTTON_TOGGLE_POWER -> gen.toggleEnabled();
            case BUTTON_CYCLE_FREQ -> gen.cycleFrequency();
            case BUTTON_CYCLE_R_INT -> gen.cycleInternalResistance();
            case BUTTON_CYCLE_CURRENT_LIMIT -> gen.cycleCurrentLimit();
            case BUTTON_RESET_ENERGY -> gen.resetEnergy();
            default -> {
                return false;
            }
        }
        return true;
    }

    public void setVoltageFromPacket(double voltage) {
        // Will be applied via server network receiver
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
