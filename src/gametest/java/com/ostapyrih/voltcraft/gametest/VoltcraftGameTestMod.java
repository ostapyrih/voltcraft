package com.ostapyrih.voltcraft.gametest;

import com.ostapyrih.voltcraft.gametest.command.VoltcraftSimulationTestCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ModInitializer for the voltcraft-test environment.
 * Registers test-only commands so they never exist in production jars.
 */
public class VoltcraftGameTestMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("voltcraft-test");

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing VoltCraft GameTest & Simulation Test Suite...");
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                CommandManager.literal("voltcraft")
                    .then(VoltcraftSimulationTestCommand.register())
            );
        });
    }
}
