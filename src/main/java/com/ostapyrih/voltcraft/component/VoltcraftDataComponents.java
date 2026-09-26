package com.ostapyrih.voltcraft.component;

import com.mojang.serialization.Codec;
import com.ostapyrih.voltcraft.Voltcraft;
import net.minecraft.component.ComponentType;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

import java.util.function.UnaryOperator;

/**
 * Registry of VoltCraft custom Minecraft 1.21 DataComponentTypes.
 */
public class VoltcraftDataComponents {

    public static final ComponentType<Double> BATTERY_CHARGE = register("battery_charge",
        builder -> builder.codec(Codec.DOUBLE).packetCodec(PacketCodecs.DOUBLE.cast())
    );

    public static final ComponentType<Double> BATTERY_HEALTH = register("battery_health",
        builder -> builder.codec(Codec.DOUBLE).packetCodec(PacketCodecs.DOUBLE.cast())
    );

    public static final ComponentType<Double> BATTERY_TEMPERATURE = register("battery_temperature",
        builder -> builder.codec(Codec.DOUBLE).packetCodec(PacketCodecs.DOUBLE.cast())
    );

    public static final ComponentType<String> BATTERY_CELL_CHEMISTRY = register("battery_cell_chemistry",
        builder -> builder.codec(Codec.STRING).packetCodec(PacketCodecs.STRING.cast())
    );

    public static final ComponentType<BatteryBayData> BATTERY_BAY = register("battery_bay",
        builder -> builder.codec(BatteryBayData.CODEC).packetCodec(BatteryBayData.PACKET_CODEC.cast())
    );

    private static <T> ComponentType<T> register(String name, UnaryOperator<ComponentType.Builder<T>> builderOperator) {
        ComponentType.Builder<T> builder = ComponentType.builder();
        return Registry.register(
            Registries.DATA_COMPONENT_TYPE,
            Identifier.of(Voltcraft.MOD_ID, name),
            builderOperator.apply(builder).build()
        );
    }

    public static void initialize() {
        // Loads class and registers static component types
    }
}
