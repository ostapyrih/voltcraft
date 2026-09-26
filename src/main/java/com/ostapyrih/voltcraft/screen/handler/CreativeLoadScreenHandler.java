package com.ostapyrih.voltcraft.screen.handler;

import com.ostapyrih.voltcraft.block.entity.creative.CreativeLoadBlockEntity;
import com.ostapyrih.voltcraft.screen.VoltcraftScreenHandlers;
import com.ostapyrih.voltcraft.simulation.creative.CreativeLoadLogic.LoadMode;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ArrayPropertyDelegate;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;

/**
 * ScreenHandler for the Creative Electrical Load.
 * Synchronizes real-time telemetry (voltage, current, power, equivalent resistance, energy)
 * and controls load mode, setpoint values, toggling, and presets.
 */
public class CreativeLoadScreenHandler extends ScreenHandler {

    public static final int PROP_MODE_ORDINAL = 0;
    public static final int PROP_TARGET_VALUE_LOW = 1;
    public static final int PROP_TARGET_VALUE_HIGH = 2;
    public static final int PROP_ENABLED = 3;
    public static final int PROP_VOLTAGE_X10 = 4;
    public static final int PROP_CURRENT_LOW = 5;
    public static final int PROP_CURRENT_HIGH = 6;
    public static final int PROP_POWER_LOW = 7;
    public static final int PROP_POWER_HIGH = 8;
    public static final int PROP_RESISTANCE_LOW = 9;
    public static final int PROP_RESISTANCE_HIGH = 10;
    public static final int PROP_ENERGY_LOW = 11;
    public static final int PROP_ENERGY_HIGH = 12;

    public static final int PROPERTY_COUNT = 13;

    // Button IDs
    public static final int BUTTON_MODE_RESISTANCE = 0;
    public static final int BUTTON_MODE_POWER = 1;
    public static final int BUTTON_MODE_CURRENT = 2;

    public static final int BUTTON_DEC_LARGE = 10;
    public static final int BUTTON_DEC_SMALL = 11;
    public static final int BUTTON_INC_SMALL = 12;
    public static final int BUTTON_INC_LARGE = 13;

    public static final int BUTTON_PRESET_1 = 20;
    public static final int BUTTON_PRESET_2 = 21;
    public static final int BUTTON_PRESET_3 = 22;
    public static final int BUTTON_PRESET_4 = 23;
    public static final int BUTTON_PRESET_5 = 24;

    public static final int BUTTON_TOGGLE_LOAD = 30;
    public static final int BUTTON_RESET_ENERGY = 31;

    private final BlockPos pos;
    private final PropertyDelegate propertyDelegate;

    public CreativeLoadScreenHandler(int syncId, PlayerInventory playerInventory, BlockPos pos) {
        this(syncId, playerInventory, pos, new ArrayPropertyDelegate(PROPERTY_COUNT));
    }

    public CreativeLoadScreenHandler(int syncId, PlayerInventory playerInventory, BlockPos pos, PropertyDelegate propertyDelegate) {
        super(VoltcraftScreenHandlers.CREATIVE_LOAD_SCREEN_HANDLER, syncId);
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

    public LoadMode getMode() {
        int ord = propertyDelegate.get(PROP_MODE_ORDINAL);
        LoadMode[] values = LoadMode.values();
        return (ord >= 0 && ord < values.length) ? values[ord] : LoadMode.CONSTANT_RESISTANCE;
    }

    public double getTargetValue() {
        return unpack(propertyDelegate.get(PROP_TARGET_VALUE_LOW), propertyDelegate.get(PROP_TARGET_VALUE_HIGH)) / 100.0;
    }

    public boolean isEnabled() {
        return propertyDelegate.get(PROP_ENABLED) != 0;
    }

    public double getTerminalVoltage() {
        return propertyDelegate.get(PROP_VOLTAGE_X10) / 10.0;
    }

    public double getDrawnCurrent() {
        return unpack(propertyDelegate.get(PROP_CURRENT_LOW), propertyDelegate.get(PROP_CURRENT_HIGH)) / 100.0;
    }

    public double getDissipatedPower() {
        return unpack(propertyDelegate.get(PROP_POWER_LOW), propertyDelegate.get(PROP_POWER_HIGH)) / 10.0;
    }

    public double getEquivalentResistance() {
        return unpack(propertyDelegate.get(PROP_RESISTANCE_LOW), propertyDelegate.get(PROP_RESISTANCE_HIGH)) / 100.0;
    }

    public double getTotalEnergyKwh() {
        int kJ = unpack(propertyDelegate.get(PROP_ENERGY_LOW), propertyDelegate.get(PROP_ENERGY_HIGH));
        return kJ / 3600.0;
    }

    public String getTargetDisplay() {
        return switch (getMode()) {
            case CONSTANT_RESISTANCE -> String.format("%.2f Ω", getTargetValue());
            case CONSTANT_POWER -> String.format("%.1f W", getTargetValue());
            case CONSTANT_CURRENT -> String.format("%.2f A", getTargetValue());
        };
    }

    @Override
    public boolean onButtonClick(PlayerEntity player, int id) {
        BlockEntity be = player.getEntityWorld().getBlockEntity(pos);
        if (!(be instanceof CreativeLoadBlockEntity load)) {
            return false;
        }

        switch (id) {
            case BUTTON_MODE_RESISTANCE -> {
                load.setMode(LoadMode.CONSTANT_RESISTANCE);
                load.setTargetValue(10.0);
            }
            case BUTTON_MODE_POWER -> {
                load.setMode(LoadMode.CONSTANT_POWER);
                load.setTargetValue(1000.0);
            }
            case BUTTON_MODE_CURRENT -> {
                load.setMode(LoadMode.CONSTANT_CURRENT);
                load.setTargetValue(10.0);
            }
            case BUTTON_TOGGLE_LOAD -> load.toggleEnabled();
            case BUTTON_RESET_ENERGY -> load.resetEnergy();

            case BUTTON_DEC_LARGE -> {
                double delta = load.getMode() == LoadMode.CONSTANT_POWER ? 100.0 : 10.0;
                load.setTargetValue(Math.max(0.1, load.getTargetValue() - delta));
            }
            case BUTTON_DEC_SMALL -> {
                double delta = load.getMode() == LoadMode.CONSTANT_POWER ? 10.0 : 1.0;
                load.setTargetValue(Math.max(0.1, load.getTargetValue() - delta));
            }
            case BUTTON_INC_SMALL -> {
                double delta = load.getMode() == LoadMode.CONSTANT_POWER ? 10.0 : 1.0;
                load.setTargetValue(load.getTargetValue() + delta);
            }
            case BUTTON_INC_LARGE -> {
                double delta = load.getMode() == LoadMode.CONSTANT_POWER ? 100.0 : 10.0;
                load.setTargetValue(load.getTargetValue() + delta);
            }

            // Presets
            case BUTTON_PRESET_1 -> {
                double v = switch (load.getMode()) {
                    case CONSTANT_RESISTANCE -> 1.0;
                    case CONSTANT_POWER -> 100.0;
                    case CONSTANT_CURRENT -> 1.0;
                };
                load.setTargetValue(v);
            }
            case BUTTON_PRESET_2 -> {
                double v = switch (load.getMode()) {
                    case CONSTANT_RESISTANCE -> 5.0;
                    case CONSTANT_POWER -> 500.0;
                    case CONSTANT_CURRENT -> 5.0;
                };
                load.setTargetValue(v);
            }
            case BUTTON_PRESET_3 -> {
                double v = switch (load.getMode()) {
                    case CONSTANT_RESISTANCE -> 10.0;
                    case CONSTANT_POWER -> 1000.0;
                    case CONSTANT_CURRENT -> 10.0;
                };
                load.setTargetValue(v);
            }
            case BUTTON_PRESET_4 -> {
                double v = switch (load.getMode()) {
                    case CONSTANT_RESISTANCE -> 50.0;
                    case CONSTANT_POWER -> 2500.0;
                    case CONSTANT_CURRENT -> 25.0;
                };
                load.setTargetValue(v);
            }
            case BUTTON_PRESET_5 -> {
                double v = switch (load.getMode()) {
                    case CONSTANT_RESISTANCE -> 100.0;
                    case CONSTANT_POWER -> 5000.0;
                    case CONSTANT_CURRENT -> 50.0;
                };
                load.setTargetValue(v);
            }
            default -> {
                return false;
            }
        }
        return true;
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
