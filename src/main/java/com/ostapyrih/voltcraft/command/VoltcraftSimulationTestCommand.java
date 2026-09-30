package com.ostapyrih.voltcraft.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.ostapyrih.voltcraft.block.VoltcraftBlocks;
import com.ostapyrih.voltcraft.block.cable.CableBlock;
import com.ostapyrih.voltcraft.block.conversion.AbstractPowerConverterBlock;
import com.ostapyrih.voltcraft.block.creative.CreativeLoadBlock;
import com.ostapyrih.voltcraft.block.entity.conversion.AbstractPowerConverterBlockEntity;
import com.ostapyrih.voltcraft.block.entity.creative.CreativeLoadBlockEntity;
import com.ostapyrih.voltcraft.block.entity.generation.ChargeControllerBlockEntity;
import com.ostapyrih.voltcraft.block.entity.generation.SolarPanelBlockEntity;
import com.ostapyrih.voltcraft.block.entity.storage.BatteryBlockEntity;
import com.ostapyrih.voltcraft.block.generation.SolarPanelBlock;
import com.ostapyrih.voltcraft.block.storage.BatteryBlock;
import com.ostapyrih.voltcraft.simulation.creative.CreativeLoadLogic;
import com.ostapyrih.voltcraft.simulation.grid.GridManager;
import net.minecraft.block.Blocks;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.function.Consumer;

/**
 * Live in-world integration test for full Solar -> MPPT -> Battery -> Load circuit.
 */
public class VoltcraftSimulationTestCommand {

    public static final BlockPos ORIGIN = new BlockPos(100, 64, 100);

    public static final BlockPos SOLAR_POS = ORIGIN.add(0, 0, 0);       // (100, 64, 100)
    public static final BlockPos MPPT_POS = ORIGIN.add(0, 0, 6);        // (100, 64, 106)
    public static final BlockPos BATTERY_POS = ORIGIN.add(0, 0, 12);    // (100, 64, 112)
    public static final BlockPos LOAD_POS = ORIGIN.add(4, 0, 12);       // (104, 64, 112)

    public static LiteralArgumentBuilder<ServerCommandSource> register() {
        return CommandManager.literal("test")
            .then(CommandManager.literal("setup")
                .executes(ctx -> {
                    ServerWorld world = ctx.getSource().getWorld();
                    buildCircuit(world, msg -> ctx.getSource().sendFeedback(() -> Text.literal(msg).formatted(Formatting.GREEN), false));
                    return 1;
                })
            )
            .then(CommandManager.literal("clear")
                .executes(ctx -> {
                    ServerWorld world = ctx.getSource().getWorld();
                    clearCircuit(world, msg -> ctx.getSource().sendFeedback(() -> Text.literal(msg).formatted(Formatting.YELLOW), false));
                    return 1;
                })
            )
            .then(CommandManager.literal("status")
                .executes(ctx -> {
                    ServerWorld world = ctx.getSource().getWorld();
                    printStatus(world, msg -> ctx.getSource().sendFeedback(() -> Text.literal(msg).formatted(Formatting.AQUA), false));
                    return 1;
                })
            )
            .then(CommandManager.literal("load")
                .then(CommandManager.argument("watts", DoubleArgumentType.doubleArg(0.0, 100_000.0))
                    .executes(ctx -> {
                        double watts = DoubleArgumentType.getDouble(ctx, "watts");
                        ServerWorld world = ctx.getSource().getWorld();
                        setLoad(world, watts, msg -> ctx.getSource().sendFeedback(() -> Text.literal(msg).formatted(Formatting.GREEN), false));
                        return 1;
                    })
                )
            )
            .then(CommandManager.literal("run")
                .executes(ctx -> {
                    ServerWorld world = ctx.getSource().getWorld();
                    runFullAutomatedTest(world, msg -> {
                        System.out.println("[VoltcraftTest] " + msg);
                        ctx.getSource().sendFeedback(() -> Text.literal(msg).formatted(Formatting.GOLD), false);
                    });
                    return 1;
                })
            );
    }

    public static void buildCircuit(ServerWorld world, Consumer<String> log) {
        // Force load chunk (6, 6) containing (100, 100)
        world.setChunkForced(ORIGIN.getX() >> 4, ORIGIN.getZ() >> 4, true);
        world.setChunkForced((ORIGIN.getX() + 16) >> 4, (ORIGIN.getZ() + 16) >> 4, true);

        // Clear platform
        for (int x = ORIGIN.getX() - 5; x <= ORIGIN.getX() + 10; x++) {
            for (int z = ORIGIN.getZ() - 5; z <= ORIGIN.getZ() + 20; z++) {
                world.setBlockState(new BlockPos(x, ORIGIN.getY() - 1, z), Blocks.BEDROCK.getDefaultState(), 3);
                for (int y = ORIGIN.getY(); y <= ORIGIN.getY() + 3; y++) {
                    world.setBlockState(new BlockPos(x, y, z), Blocks.AIR.getDefaultState(), 3);
                }
            }
        }

        // Set daytime and clear weather for 100% solar irradiance
        world.setTimeOfDay(6000);
        world.setWeather(0, 100000, false, false);

        // 1. Solar Panel at (100, 64, 100) facing NORTH
        world.setBlockState(SOLAR_POS, VoltcraftBlocks.SOLAR_PANEL_MONOCRYSTALLINE.getDefaultState()
            .with(SolarPanelBlock.FACING, Direction.NORTH), 3);

        // 2. MPPT at (100, 64, 106) facing SOUTH
        world.setBlockState(MPPT_POS, VoltcraftBlocks.CHARGE_CONTROLLER_MPPT.getDefaultState()
            .with(AbstractPowerConverterBlock.FACING, Direction.SOUTH), 3);
        if (world.getBlockEntity(MPPT_POS) instanceof ChargeControllerBlockEntity mppt) {
            mppt.setTargetOutputVoltage(12.0); // 12V rail
        }

        // 3. Battery at (100, 64, 112) facing NORTH (12V Lead-Acid)
        world.setBlockState(BATTERY_POS, VoltcraftBlocks.BATTERY_BLOCK_LEAD_ACID.getDefaultState()
            .with(BatteryBlock.FACING, Direction.NORTH), 3);

        // 4. Creative Load at (104, 64, 112) facing NORTH
        world.setBlockState(LOAD_POS, VoltcraftBlocks.CREATIVE_LOAD.getDefaultState()
            .with(CreativeLoadBlock.FACING, Direction.NORTH), 3);
        if (world.getBlockEntity(LOAD_POS) instanceof CreativeLoadBlockEntity load) {
            load.setMode(CreativeLoadLogic.LoadMode.CONSTANT_POWER);
            load.setTargetValue(0.0);
            load.setEnabled(false);
        }

        // Cable routing:
        // A. Solar(+) at (100, 64, 101) -> MPPT In(+) at (100, 64, 105)
        for (int z = 101; z <= 105; z++) {
            setCable(world, new BlockPos(100, 64, z));
        }

        // B. Solar(-) at (100, 64, 99) -> MPPT In(-) at (101, 64, 106)
        for (int x = 100; x <= 103; x++) setCable(world, new BlockPos(x, 64, 99));
        for (int z = 99; z <= 106; z++) setCable(world, new BlockPos(103, 64, z));
        for (int x = 101; x <= 103; x++) setCable(world, new BlockPos(x, 64, 106));

        // C. MPPT Out(-) at (99, 64, 106) -> Bat(-) at (100, 64, 111) and Load(-) at (104, 64, 111)
        setCable(world, new BlockPos(99, 64, 106));
        for (int z = 106; z <= 111; z++) setCable(world, new BlockPos(98, 64, z));
        for (int x = 98; x <= 104; x++) setCable(world, new BlockPos(x, 64, 111));

        // D. MPPT Out(+) at (100, 64, 107) -> Bat(+) at (100, 64, 113) and Load(+) at (104, 64, 113)
        setCable(world, new BlockPos(100, 64, 107));
        for (int x = 100; x <= 106; x++) setCable(world, new BlockPos(x, 64, 108));
        for (int z = 108; z <= 113; z++) setCable(world, new BlockPos(106, 64, z));
        for (int x = 100; x <= 106; x++) setCable(world, new BlockPos(x, 64, 113));

        // Rebuild islands
        GridManager.get(world).rebuildIslands();
        log.accept("Built live test circuit at " + ORIGIN.toShortString());
    }

    private static void setCable(ServerWorld world, BlockPos pos) {
        world.setBlockState(pos, VoltcraftBlocks.CABLE_COPPER_HEAVY.getDefaultState(), 3);
        GridManager.get(world).onConductorPlaced(world, pos, com.ostapyrih.voltcraft.block.cable.ConductorType.HEAVY_COPPER);
    }

    public static void clearCircuit(ServerWorld world, Consumer<String> log) {
        for (int x = ORIGIN.getX() - 5; x <= ORIGIN.getX() + 10; x++) {
            for (int z = ORIGIN.getZ() - 5; z <= ORIGIN.getZ() + 20; z++) {
                for (int y = ORIGIN.getY(); y <= ORIGIN.getY() + 3; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    world.setBlockState(p, Blocks.AIR.getDefaultState(), 3);
                    GridManager.get(world).removeCable(p);
                    GridManager.get(world).removeAttachedBlock(p);
                }
            }
        }
        GridManager.get(world).rebuildIslands();
        log.accept("Cleared test circuit.");
    }

    public static void setLoad(ServerWorld world, double watts, Consumer<String> log) {
        if (world.getBlockEntity(LOAD_POS) instanceof CreativeLoadBlockEntity load) {
            if (watts <= 0.0) {
                load.setEnabled(false);
                load.setTargetValue(0.0);
            } else {
                load.setMode(CreativeLoadLogic.LoadMode.CONSTANT_POWER);
                load.setTargetValue(watts);
                load.setEnabled(true);
            }
            log.accept("Creative Load set to " + watts + " W (enabled=" + load.isEnabled() + ")");
        } else {
            log.accept("No Creative Load found at " + LOAD_POS.toShortString());
        }
    }

    public static void printStatus(ServerWorld world, Consumer<String> log) {
        // Step tick once to ensure latest state
        GridManager.get(world).tick(world);

        SolarPanelBlockEntity solar = world.getBlockEntity(SOLAR_POS) instanceof SolarPanelBlockEntity sp ? sp : null;
        ChargeControllerBlockEntity mppt = world.getBlockEntity(MPPT_POS) instanceof ChargeControllerBlockEntity cc ? cc : null;
        BatteryBlockEntity bat = world.getBlockEntity(BATTERY_POS) instanceof BatteryBlockEntity bb ? bb : null;
        CreativeLoadBlockEntity load = world.getBlockEntity(LOAD_POS) instanceof CreativeLoadBlockEntity cl ? cl : null;

        log.accept("=== VoltCraft Live Circuit Telemetry ===");
        if (solar != null) {
            log.accept(String.format("SOLAR: Peak=%.1fW, EMF=%.2fV, I=%.2fA",
                solar.getPeakPowerAvailable(), solar.getElectromotiveForce(), solar.getLastDrawnCurrent()));
        } else {
            log.accept("SOLAR: Not found");
        }
        if (mppt != null) {
            log.accept(String.format("MPPT: Vin=%.2fV, Iin=%.2fA, Pin=%.1fW | Vout=%.2fV, Iout=%.2fA, Pout=%.1fW | Tripped=%b",
                mppt.getInputVoltage(), mppt.getInputVoltage() > 1.0 ? mppt.getInputPowerWatts() / mppt.getInputVoltage() : 0.0,
                mppt.getInputPowerWatts(), mppt.getOutputVoltage(), mppt.getOutputCurrentAmps(), mppt.getOutputPowerWatts(), mppt.isTripped()));
        } else {
            log.accept("MPPT: Not found");
        }
        if (bat != null) {
            log.accept(String.format("BATTERY: V=%.2fV, SoC=%.1f%%, Chem=%s",
                bat.getLastTerminalVoltage(), bat.getStateOfCharge() * 100.0, bat.getChemistry().getDisplayName()));
        } else {
            log.accept("BATTERY: Not found");
        }
        if (load != null) {
            log.accept(String.format("LOAD: Mode=%s, Target=%.1f, Enabled=%b, MeasuredV=%.2fV, DeliveredP=%.1fW",
                load.getMode().name(), load.getTargetValue(), load.isEnabled(), load.getLastMeasuredVoltage(), load.getLastDeliveredPower()));
        } else {
            log.accept("LOAD: Not found");
        }
    }

    public static void runFullAutomatedTest(ServerWorld world, Consumer<String> log) {
        log.accept("=== STARTING IN-GAME INTEGRATION TEST ===");
        buildCircuit(world, log);

        // Step 1: Baseline charging (Load OFF)
        stepTicks(world, 10);
        log.accept("--- Step 1: Baseline Charging (Load OFF) ---");
        printStatus(world, log);

        // Step 2: 50W Load
        setLoad(world, 50.0, log);
        stepTicks(world, 10);
        log.accept("--- Step 2: 50W Load Active ---");
        printStatus(world, log);

        // Step 3: 300W Load
        setLoad(world, 300.0, log);
        stepTicks(world, 10);
        log.accept("--- Step 3: 300W Load Active ---");
        printStatus(world, log);

        // Step 4: 2500W Heavy Load (Battery + MPPT together)
        setLoad(world, 2500.0, log);
        stepTicks(world, 10);
        log.accept("--- Step 4: 2500W Heavy Load Active ---");
        printStatus(world, log);

        // Step 5: Toggle Load OFF
        setLoad(world, 0.0, log);
        stepTicks(world, 10);
        log.accept("--- Step 5: Load Toggled OFF ---");
        printStatus(world, log);

        // Step 6: Disconnect Solar (+) cable
        BlockPos solarPlusCable = new BlockPos(100, 64, 101);
        world.setBlockState(solarPlusCable, Blocks.AIR.getDefaultState(), 3);
        GridManager.get(world).removeCable(solarPlusCable);
        stepTicks(world, 10);
        log.accept("--- Step 6: Solar (+) Disconnected ---");
        printStatus(world, log);

        // Reconnect Solar (+)
        setCable(world, solarPlusCable);
        stepTicks(world, 10);
        log.accept("--- Step 7: Solar (+) Reconnected ---");
        printStatus(world, log);

        log.accept("=== IN-GAME INTEGRATION TEST COMPLETED SUCCESSFULLY ===");
    }

    private static void stepTicks(ServerWorld world, int count) {
        for (int i = 0; i < count; i++) {
            GridManager.get(world).tick(world);
        }
    }
}
