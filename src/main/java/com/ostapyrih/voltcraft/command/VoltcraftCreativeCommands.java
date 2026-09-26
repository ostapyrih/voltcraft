package com.ostapyrih.voltcraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.ostapyrih.voltcraft.block.entity.creative.CreativeGeneratorBlockEntity;
import com.ostapyrih.voltcraft.block.entity.creative.CreativeLoadBlockEntity;
import com.ostapyrih.voltcraft.simulation.creative.CreativeLoadLogic;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

/**
 * Commands for configuring targeted Creative Generator and Creative Load blocks with arbitrary precision.
 */
public class VoltcraftCreativeCommands {

    public static void register() {
        CommandRegistrationCallback.EVENT.register(VoltcraftCreativeCommands::registerCommands);
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher,
                                         CommandRegistryAccess registryAccess,
                                         CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(
            CommandManager.literal("voltcraft")
                .requires(ServerCommandSource::isExecutedByPlayer)
                .then(CommandManager.literal("generator")
                    .then(CommandManager.argument("voltage", DoubleArgumentType.doubleArg(0.0, 1_000_000.0))
                        .executes(ctx -> configureGenerator(ctx, DoubleArgumentType.getDouble(ctx, "voltage"), null, null))
                        .then(CommandManager.argument("max_current", DoubleArgumentType.doubleArg(0.0, 1_000_000.0))
                            .executes(ctx -> configureGenerator(ctx, DoubleArgumentType.getDouble(ctx, "voltage"), DoubleArgumentType.getDouble(ctx, "max_current"), null))
                            .then(CommandManager.argument("frequency", DoubleArgumentType.doubleArg(0.0, 10_000.0))
                                .executes(ctx -> configureGenerator(ctx, DoubleArgumentType.getDouble(ctx, "voltage"), DoubleArgumentType.getDouble(ctx, "max_current"), DoubleArgumentType.getDouble(ctx, "frequency")))
                            )
                        )
                    )
                )
                .then(CommandManager.literal("load")
                    .then(CommandManager.literal("resistance")
                        .then(CommandManager.argument("ohms", DoubleArgumentType.doubleArg(0.0001, 10_000_000.0))
                            .executes(ctx -> configureLoad(ctx, CreativeLoadLogic.LoadMode.CONSTANT_RESISTANCE, DoubleArgumentType.getDouble(ctx, "ohms")))
                        )
                    )
                    .then(CommandManager.literal("power")
                        .then(CommandManager.argument("watts", DoubleArgumentType.doubleArg(0.0, 100_000_000.0))
                            .executes(ctx -> configureLoad(ctx, CreativeLoadLogic.LoadMode.CONSTANT_POWER, DoubleArgumentType.getDouble(ctx, "watts")))
                        )
                    )
                    .then(CommandManager.literal("current")
                        .then(CommandManager.argument("amps", DoubleArgumentType.doubleArg(0.0, 1_000_000.0))
                            .executes(ctx -> configureLoad(ctx, CreativeLoadLogic.LoadMode.CONSTANT_CURRENT, DoubleArgumentType.getDouble(ctx, "amps")))
                        )
                    )
                )
        );
    }

    private static int configureGenerator(CommandContext<ServerCommandSource> ctx, double voltage, Double maxCurrent, Double frequency) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendError(Text.literal("Command must be run by a player targeting a block."));
            return 0;
        }

        HitResult hit = player.raycast(8.0, 0.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            ctx.getSource().sendError(Text.literal("You must be looking at a Creative Generator block!"));
            return 0;
        }

        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        BlockEntity be = ctx.getSource().getWorld().getBlockEntity(pos);
        if (!(be instanceof CreativeGeneratorBlockEntity gen)) {
            ctx.getSource().sendError(Text.literal("Targeted block is not a Creative Generator!"));
            return 0;
        }

        gen.setVoltage(voltage);
        if (maxCurrent != null) {
            gen.setMaxCurrent(maxCurrent);
        }
        if (frequency != null) {
            gen.setFrequency(frequency);
        }

        ctx.getSource().sendFeedback(() -> Text.literal(String.format(
            "Configured Creative Generator at %d, %d, %d -> Voltage: %.2f V, Current Limit: %.1f A, Mode: %s",
            pos.getX(), pos.getY(), pos.getZ(), gen.getVoltage(), gen.getMaxOutputCurrent(), gen.getFrequencyDisplay()
        )).formatted(Formatting.GREEN), false);

        return 1;
    }

    private static int configureLoad(CommandContext<ServerCommandSource> ctx, CreativeLoadLogic.LoadMode mode, double value) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendError(Text.literal("Command must be run by a player targeting a block."));
            return 0;
        }

        HitResult hit = player.raycast(8.0, 0.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            ctx.getSource().sendError(Text.literal("You must be looking at a Creative Load block!"));
            return 0;
        }

        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        BlockEntity be = ctx.getSource().getWorld().getBlockEntity(pos);
        if (!(be instanceof CreativeLoadBlockEntity load)) {
            ctx.getSource().sendError(Text.literal("Targeted block is not a Creative Load!"));
            return 0;
        }

        load.setMode(mode);
        load.setTargetValue(value);

        ctx.getSource().sendFeedback(() -> Text.literal(String.format(
            "Configured Creative Load at %d, %d, %d -> Mode: %s, Setpoint: %s",
            pos.getX(), pos.getY(), pos.getZ(), load.getMode().getDisplayName(), load.getTargetDisplay()
        )).formatted(Formatting.GREEN), false);

        return 1;
    }
}
