package com.ostapyrih.voltcraft.screen;

import com.ostapyrih.voltcraft.Voltcraft;
import com.ostapyrih.voltcraft.screen.handler.ConverterScreenHandler;
import com.ostapyrih.voltcraft.screen.handler.CreativeGeneratorScreenHandler;
import com.ostapyrih.voltcraft.screen.handler.CreativeLoadScreenHandler;
import com.ostapyrih.voltcraft.screen.handler.EuConverterScreenHandler;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * ScreenHandlerType registry for VoltCraft graphical interfaces.
 */
public class VoltcraftScreenHandlers {

    public static final ScreenHandlerType<ConverterScreenHandler> CONVERTER_SCREEN_HANDLER =
        Registry.register(
            Registries.SCREEN_HANDLER,
            Identifier.of(Voltcraft.MOD_ID, "converter"),
            new ExtendedScreenHandlerType<>(ConverterScreenHandler::new, BlockPos.PACKET_CODEC)
        );

    public static final ScreenHandlerType<EuConverterScreenHandler> EU_CONVERTER_SCREEN_HANDLER =
        Registry.register(
            Registries.SCREEN_HANDLER,
            Identifier.of(Voltcraft.MOD_ID, "converter_eu"),
            new ExtendedScreenHandlerType<>(EuConverterScreenHandler::new, BlockPos.PACKET_CODEC)
        );

    public static final ScreenHandlerType<CreativeGeneratorScreenHandler> CREATIVE_GENERATOR_SCREEN_HANDLER =
        Registry.register(
            Registries.SCREEN_HANDLER,
            Identifier.of(Voltcraft.MOD_ID, "creative_generator"),
            new ExtendedScreenHandlerType<>(CreativeGeneratorScreenHandler::new, BlockPos.PACKET_CODEC)
        );

    public static final ScreenHandlerType<CreativeLoadScreenHandler> CREATIVE_LOAD_SCREEN_HANDLER =
        Registry.register(
            Registries.SCREEN_HANDLER,
            Identifier.of(Voltcraft.MOD_ID, "creative_load"),
            new ExtendedScreenHandlerType<>(CreativeLoadScreenHandler::new, BlockPos.PACKET_CODEC)
        );

    public static void initialize() {
        // Triggers static field registration
    }
}
