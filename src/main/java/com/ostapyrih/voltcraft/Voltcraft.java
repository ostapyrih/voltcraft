package com.ostapyrih.voltcraft;

import com.ostapyrih.voltcraft.block.VoltcraftBlocks;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.block.entity.conversion.AbstractPowerConverterBlockEntity;
import com.ostapyrih.voltcraft.block.entity.creative.CreativeGeneratorBlockEntity;
import com.ostapyrih.voltcraft.block.entity.creative.CreativeLoadBlockEntity;
import com.ostapyrih.voltcraft.command.VoltcraftCreativeCommands;
import com.ostapyrih.voltcraft.component.VoltcraftDataComponents;
import com.ostapyrih.voltcraft.item.VoltcraftItemGroups;
import com.ostapyrih.voltcraft.item.VoltcraftItems;
import com.ostapyrih.voltcraft.network.ResetConverterTripPayload;
import com.ostapyrih.voltcraft.network.SetConverterInputVoltagePayload;
import com.ostapyrih.voltcraft.network.SetConverterVoltagePayload;
import com.ostapyrih.voltcraft.network.SetCreativeGeneratorPayload;
import com.ostapyrih.voltcraft.network.SetCreativeLoadPayload;
import com.ostapyrih.voltcraft.screen.VoltcraftScreenHandlers;
import com.ostapyrih.voltcraft.screen.handler.ConverterScreenHandler;
import com.ostapyrih.voltcraft.screen.handler.CreativeGeneratorScreenHandler;
import com.ostapyrih.voltcraft.screen.handler.CreativeLoadScreenHandler;
import com.ostapyrih.voltcraft.simulation.grid.GridManager;
import com.ostapyrih.voltcraft.world.VoltcraftBiomeModifications;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import team.reborn.energy.api.EnergyStorage;

public class Voltcraft implements ModInitializer {
    public static final String MOD_ID = "voltcraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing VoltCraft Materials, Ores & Components...");
        VoltcraftDataComponents.initialize();
        VoltcraftBlocks.initialize();
        VoltcraftBlockEntityTypes.initialize();
        VoltcraftItems.initialize();
        VoltcraftItemGroups.initialize();
        VoltcraftBiomeModifications.initialize();

        LOGGER.info("Registering VoltCraft Energy API integrations...");
        EnergyStorage.SIDED.registerForBlockEntity((be, side) -> be.getEnergyStorage(side), VoltcraftBlockEntityTypes.EU_CONVERTER_BLOCK_ENTITY);

        LOGGER.info("Initializing VoltCraft Screens & Handlers...");
        VoltcraftScreenHandlers.initialize();

        LOGGER.info("Registering VoltCraft Network Payloads...");
        PayloadTypeRegistry.playC2S().register(SetConverterVoltagePayload.ID, SetConverterVoltagePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SetConverterVoltagePayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayerEntity player = context.player();
                if (player.currentScreenHandler instanceof ConverterScreenHandler handler && handler.getPos().equals(payload.pos())) {
                    if (player.getEntityWorld().getBlockEntity(payload.pos()) instanceof AbstractPowerConverterBlockEntity converter) {
                        if (converter.isOutputConfigurable()) {
                            double v = Math.max(1.0, Math.min(600.0, Math.round(payload.targetVoltage() * 10.0) / 10.0));
                            converter.setTargetOutputVoltage(v);
                            handler.setTargetVoltageLocal(v);
                        }
                    }
                }
            });
        });

        PayloadTypeRegistry.playC2S().register(SetConverterInputVoltagePayload.ID, SetConverterInputVoltagePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SetConverterInputVoltagePayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayerEntity player = context.player();
                if (player.currentScreenHandler instanceof ConverterScreenHandler handler && handler.getPos().equals(payload.pos())) {
                    if (player.getEntityWorld().getBlockEntity(payload.pos()) instanceof AbstractPowerConverterBlockEntity converter) {
                        converter.setNominalInputVoltage(payload.nominalInputVoltage());
                        handler.setNominalInputVoltageLocal(payload.nominalInputVoltage());
                    }
                }
            });
        });

        PayloadTypeRegistry.playC2S().register(ResetConverterTripPayload.ID, ResetConverterTripPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ResetConverterTripPayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayerEntity player = context.player();
                if (player.currentScreenHandler instanceof ConverterScreenHandler handler && handler.getPos().equals(payload.pos())) {
                    if (player.getEntityWorld().getBlockEntity(payload.pos()) instanceof AbstractPowerConverterBlockEntity converter) {
                        converter.resetTrip();
                    }
                }
            });
        });

        PayloadTypeRegistry.playC2S().register(SetCreativeGeneratorPayload.ID, SetCreativeGeneratorPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SetCreativeGeneratorPayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayerEntity player = context.player();
                if (player.currentScreenHandler instanceof CreativeGeneratorScreenHandler handler && handler.getPos().equals(payload.pos())) {
                    if (player.getEntityWorld().getBlockEntity(payload.pos()) instanceof CreativeGeneratorBlockEntity gen) {
                        double v = Math.max(0.0, Math.min(10000.0, Math.round(payload.voltage() * 10.0) / 10.0));
                        gen.setVoltage(v);
                    }
                }
            });
        });

        PayloadTypeRegistry.playC2S().register(SetCreativeLoadPayload.ID, SetCreativeLoadPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SetCreativeLoadPayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayerEntity player = context.player();
                if (player.currentScreenHandler instanceof CreativeLoadScreenHandler handler && handler.getPos().equals(payload.pos())) {
                    if (player.getEntityWorld().getBlockEntity(payload.pos()) instanceof CreativeLoadBlockEntity load) {
                        double v = Math.max(0.1, Math.min(100000.0, Math.round(payload.targetValue() * 10.0) / 10.0));
                        load.setTargetValue(v);
                    }
                }
            });
        });

        LOGGER.info("Initializing VoltCraft Physics Simulation Engine...");
        GridManager.initialize();

        LOGGER.info("Registering VoltCraft Commands...");
        VoltcraftCreativeCommands.register();

        LOGGER.info("VoltCraft initialized successfully.");
    }
}
