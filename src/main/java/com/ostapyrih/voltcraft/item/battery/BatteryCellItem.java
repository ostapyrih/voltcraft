package com.ostapyrih.voltcraft.item.battery;

import com.ostapyrih.voltcraft.component.VoltcraftDataComponents;
import com.ostapyrih.voltcraft.simulation.chemistry.BatteryChemistry;
import com.ostapyrih.voltcraft.simulation.chemistry.BatterySimulation;
import net.minecraft.component.type.TooltipDisplayComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;

import java.util.function.Consumer;

/**
 * Portable electrochemical battery cell carried in inventories or inserted into tools and racks.
 * Stores SoC, SOH, temperature, and chemistry via Minecraft 1.21 DataComponentTypes.
 */
public class BatteryCellItem extends Item {

    private final BatteryChemistry chemistry;

    public BatteryCellItem(Settings settings, BatteryChemistry chemistry) {
        super(settings.maxCount(16));
        this.chemistry = chemistry;
    }

    public BatteryChemistry getChemistry() {
        return chemistry;
    }

    public static double getCharge(ItemStack stack) {
        Double val = stack.get(VoltcraftDataComponents.BATTERY_CHARGE);
        return val != null ? Math.max(0.0, Math.min(1.0, val)) : 1.0;
    }

    public static void setCharge(ItemStack stack, double charge) {
        stack.set(VoltcraftDataComponents.BATTERY_CHARGE, Math.max(0.0, Math.min(1.0, charge)));
    }

    public static double getHealth(ItemStack stack) {
        Double val = stack.get(VoltcraftDataComponents.BATTERY_HEALTH);
        return val != null ? Math.max(0.0, Math.min(1.0, val)) : 1.0;
    }

    public static void setHealth(ItemStack stack, double health) {
        stack.set(VoltcraftDataComponents.BATTERY_HEALTH, Math.max(0.0, Math.min(1.0, health)));
    }

    public static double getTemperature(ItemStack stack) {
        Double val = stack.get(VoltcraftDataComponents.BATTERY_TEMPERATURE);
        return val != null ? val : 20.0;
    }

    public static void setTemperature(ItemStack stack, double tempCelsius) {
        stack.set(VoltcraftDataComponents.BATTERY_TEMPERATURE, tempCelsius);
    }

    public static double getTerminalVoltage(ItemStack stack, BatteryChemistry chemistry) {
        double charge = getCharge(stack);
        if (charge <= 0.001) {
            return 0.0;
        }
        return BatterySimulation.getOpenCircuitVoltage(chemistry, charge);
    }

    @Override
    public boolean isItemBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getItemBarStep(ItemStack stack) {
        double charge = getCharge(stack);
        return (int) Math.round(charge * 13.0);
    }

    @Override
    public int getItemBarColor(ItemStack stack) {
        double charge = getCharge(stack);
        if (charge > 0.6) {
            return 0x00FF00; // Green
        } else if (charge > 0.2) {
            return 0xFFFF00; // Yellow
        } else {
            return 0xFF0000; // Red
        }
    }

    @Override
    public void inventoryTick(ItemStack stack, ServerWorld world, Entity entity, EquipmentSlot slot) {
        super.inventoryTick(stack, world, entity, slot);

        double temp = getTemperature(stack);

        // Passive cooling towards ambient 20°C
        if (temp > 20.0) {
            temp = Math.max(20.0, temp - 0.25);
            setTemperature(stack, temp);
        }

        // Thermal runaway safety check
        if (temp >= chemistry.getThermalRunawayTempCelsius()) {
            if (entity instanceof LivingEntity living) {
                living.setOnFireFor(5.0f);
                living.damage(world, world.getDamageSources().onFire(), 4.0f);
            }
            world.createExplosion(null, entity.getX(), entity.getY(), entity.getZ(), 1.5f, World.ExplosionSourceType.MOB);
            stack.decrement(1);
        }
    }

    @Override
    public void appendTooltip(
        ItemStack stack,
        TooltipContext context,
        TooltipDisplayComponent displayComponent,
        Consumer<Text> textConsumer,
        TooltipType type
    ) {
        super.appendTooltip(stack, context, displayComponent, textConsumer, type);

        double charge = getCharge(stack);
        double health = getHealth(stack);
        double temp = getTemperature(stack);
        double v = charge <= 0.001 ? 0.0 : BatterySimulation.getOpenCircuitVoltage(chemistry, charge);

        textConsumer.accept(Text.literal("Chemistry: " + chemistry.getDisplayName()).formatted(Formatting.GRAY));
        textConsumer.accept(Text.literal(String.format("Voltage: %.2fV / %.2fV nom", v, chemistry.getNominalVoltage())).formatted(Formatting.AQUA));
        textConsumer.accept(Text.literal(String.format("Capacity: %.0f mAh", chemistry.getCapacityMilliAmpHours())).formatted(Formatting.YELLOW));
        textConsumer.accept(Text.literal(String.format("State of Charge: %.1f%%", charge * 100.0)).formatted(Formatting.GREEN));
        textConsumer.accept(Text.literal(String.format("Health: %.1f%%", health * 100.0)).formatted(Formatting.DARK_GREEN));

        Formatting tempFormat = temp > 60.0 ? Formatting.RED : (temp > 40.0 ? Formatting.GOLD : Formatting.DARK_AQUA);
        textConsumer.accept(Text.literal(String.format("Temperature: %.1f°C", temp)).formatted(tempFormat));

        if (!chemistry.isRechargeable()) {
            textConsumer.accept(Text.literal("Primary Cell (Single-Use)").formatted(Formatting.ITALIC, Formatting.DARK_GRAY));
        }
    }
}
