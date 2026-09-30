package com.ostapyrih.voltcraft.gametest.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.ostapyrih.voltcraft.block.VoltcraftBlocks;
import com.ostapyrih.voltcraft.block.conversion.AbstractPowerConverterBlock;
import com.ostapyrih.voltcraft.block.creative.CreativeLoadBlock;
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
                .executes(ctx -> executeSuite(ctx.getSource(), "all"))
                .then(CommandManager.literal("all").executes(ctx -> executeSuite(ctx.getSource(), "all")))
                .then(CommandManager.literal("baseline").executes(ctx -> executeSuite(ctx.getSource(), "baseline")))
                .then(CommandManager.literal("cases").executes(ctx -> executeSuite(ctx.getSource(), "cases")))
            )
            .then(CommandManager.literal("suite")
                .executes(ctx -> executeSuite(ctx.getSource(), "all"))
            )
            .then(CommandManager.literal("case")
                .then(CommandManager.literal("solar_cutoff").executes(ctx -> executeCase(ctx.getSource(), 1)))
                .then(CommandManager.literal("load_disconnect").executes(ctx -> executeCase(ctx.getSource(), 2)))
                .then(CommandManager.literal("mppt_recovery").executes(ctx -> executeCase(ctx.getSource(), 3)))
                .then(CommandManager.literal("battery_swap").executes(ctx -> executeCase(ctx.getSource(), 4)))
                .then(CommandManager.literal("mppt_swap").executes(ctx -> executeCase(ctx.getSource(), 5)))
                .then(CommandManager.literal("solar_swap").executes(ctx -> executeCase(ctx.getSource(), 6)))
                .then(CommandManager.literal("heavy_load").executes(ctx -> executeCase(ctx.getSource(), 7)))
                .then(CommandManager.literal("cable_churn").executes(ctx -> executeCase(ctx.getSource(), 8)))
                .then(CommandManager.literal("all").executes(ctx -> executeSuite(ctx.getSource(), "cases")))
            )
            // Backward compatibility alias for 'bugs'
            .then(CommandManager.literal("bugs")
                .executes(ctx -> executeSuite(ctx.getSource(), "cases"))
            );
    }

    private static int executeSuite(ServerCommandSource source, String type) {
        ServerWorld world = source.getWorld();
        Consumer<String> log = msg -> {
            System.out.println("[VoltcraftTest] " + msg);
            source.sendFeedback(() -> Text.literal(msg).formatted(Formatting.GOLD), false);
        };
        if ("baseline".equalsIgnoreCase(type)) {
            runFullAutomatedTest(world, log);
        } else if ("cases".equalsIgnoreCase(type)) {
            runAllTestCases(world, log);
        } else {
            runFullAutomatedTest(world, log);
            runAllTestCases(world, log);
        }
        return 1;
    }

    private static int executeCase(ServerCommandSource source, int caseNum) {
        ServerWorld world = source.getWorld();
        Consumer<String> log = msg -> {
            System.out.println("[VoltcraftTest] " + msg);
            source.sendFeedback(() -> Text.literal(msg).formatted(Formatting.GOLD), false);
        };
        switch (caseNum) {
            case 1 -> runTestCaseSolarCutoff(world, log);
            case 2 -> runTestCaseLoadCableDisconnect(world, log);
            case 3 -> runTestCaseMpptRecovery(world, log);
            case 4 -> runTestCaseBatteryHotSwap(world, log);
            case 5 -> runTestCaseMpptHotSwap(world, log);
            case 6 -> runTestCaseSolarHotSwap(world, log);
            case 7 -> runTestCaseHeavyLoadRampAndColdStart(world, log);
            case 8 -> runTestCaseCableChurn(world, log);
            default -> runAllTestCases(world, log);
        }
        return 1;
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
            log.accept(String.format("BATTERY: V=%.2fV, I=%.2fA, EMF=%.2fV, SoC=%.1f%%, Temp=%.1fC, BMSOpen=%b, Chem=%s",
                bat.getLastTerminalVoltage(), bat.getLastCurrentAmps(), bat.getElectromotiveForce(),
                bat.getStateOfCharge() * 100.0, bat.getTemperatureCelsius(), bat.isBmsOpen(), bat.getChemistry().getDisplayName()));
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

    public static void runTestCaseSolarCutoff(ServerWorld world, Consumer<String> log) {
        log.accept("=== TEST CASE 1: Solar Panel Break & Charging Cutoff ===");
        buildCircuit(world, log);
        stepTicks(world, 10);
        log.accept("Initial charging state:");
        printStatus(world, log);

        log.accept("Breaking Solar Panel block at " + SOLAR_POS.toShortString() + " with world.breakBlock...");
        world.breakBlock(SOLAR_POS, false);
        stepTicks(world, 10);
        log.accept("Status after Solar Panel block broken (expect: Vin=0, Pin=0, Pout=0, no phantom charge):");
        printStatus(world, log);
    }

    public static void runTestCaseLoadCableDisconnect(ServerWorld world, Consumer<String> log) {
        log.accept("=== TEST CASE 2: Load Cable Disconnect & Stale Telemetry Check ===");
        buildCircuit(world, log);
        setLoad(world, 500.0, log);
        stepTicks(world, 10);
        log.accept("Status with 500W load active:");
        printStatus(world, log);

        BlockPos loadCablePos = new BlockPos(104, 64, 111);
        log.accept("Breaking load cable at " + loadCablePos.toShortString() + " with world.breakBlock...");
        world.breakBlock(loadCablePos, false);
        stepTicks(world, 10);
        log.accept("Status after load cable broken (expect: load power drops to 0W, not frozen at 500W):");
        printStatus(world, log);

        log.accept("Reconnecting load cable at " + loadCablePos.toShortString() + " with setBlockState...");
        world.setBlockState(loadCablePos, VoltcraftBlocks.CABLE_COPPER_HEAVY.getDefaultState(), 3);
        stepTicks(world, 10);
        log.accept("Status after load cable reconnected (expect: load resumes 500W, MPPT healthy):");
        printStatus(world, log);
    }

    public static void runTestCaseMpptRecovery(ServerWorld world, Consumer<String> log) {
        log.accept("=== TEST CASE 3: MPPT Cable Disconnect & Auto-Recovery ===");
        buildCircuit(world, log);
        stepTicks(world, 10);
        BlockPos mpptCablePos = new BlockPos(100, 64, 105);
        log.accept("Breaking MPPT input cable at " + mpptCablePos.toShortString() + "...");
        world.breakBlock(mpptCablePos, false);
        stepTicks(world, 10);
        log.accept("Status after MPPT input cable broken:");
        printStatus(world, log);

        log.accept("Reconnecting MPPT input cable at " + mpptCablePos.toShortString() + "...");
        world.setBlockState(mpptCablePos, VoltcraftBlocks.CABLE_COPPER_HEAVY.getDefaultState(), 3);
        stepTicks(world, 10);
        log.accept("Status after MPPT input cable reconnected (expect: MPPT auto-recovers, Tripped=false):");
        printStatus(world, log);
    }

    public static void runTestCaseBatteryHotSwap(ServerWorld world, Consumer<String> log) {
        log.accept("=== TEST CASE 4: Battery Hot-Swap Under 500W Load ===");
        buildCircuit(world, log);
        setLoad(world, 500.0, log);
        stepTicks(world, 10);
        log.accept("Status before battery broken:");
        printStatus(world, log);

        log.accept("Breaking Battery block with world.breakBlock...");
        world.breakBlock(BATTERY_POS, false);
        stepTicks(world, 10);
        log.accept("Status after Battery block broken (MPPT alone under 500W load, expect no over-unity):");
        printStatus(world, log);

        log.accept("Placing new Battery block with setBlockState...");
        world.setBlockState(BATTERY_POS, VoltcraftBlocks.BATTERY_BLOCK_LEAD_ACID.getDefaultState()
            .with(BatteryBlock.FACING, Direction.NORTH), 3);
        stepTicks(world, 10);
        log.accept("Status after Battery placed back (expect: MPPT resumes charging, Tripped=false):");
        printStatus(world, log);
    }

    public static void runTestCaseMpptHotSwap(ServerWorld world, Consumer<String> log) {
        log.accept("=== TEST CASE 5: MPPT Hot-Swap While Live ===");
        buildCircuit(world, log);
        stepTicks(world, 10);
        log.accept("Breaking MPPT block with world.breakBlock...");
        world.breakBlock(MPPT_POS, false);
        stepTicks(world, 10);
        log.accept("Placing new MPPT block with setBlockState...");
        world.setBlockState(MPPT_POS, VoltcraftBlocks.CHARGE_CONTROLLER_MPPT.getDefaultState()
            .with(AbstractPowerConverterBlock.FACING, Direction.SOUTH), 3);
        if (world.getBlockEntity(MPPT_POS) instanceof ChargeControllerBlockEntity cc) {
            cc.setTargetOutputVoltage(12.0);
        }
        stepTicks(world, 10);
        log.accept("Status after new MPPT placed (expect: discovers solar and charges battery):");
        printStatus(world, log);
    }

    public static void runTestCaseSolarHotSwap(ServerWorld world, Consumer<String> log) {
        log.accept("=== TEST CASE 6: Solar Panel Hot-Swap While Live ===");
        buildCircuit(world, log);
        stepTicks(world, 10);
        log.accept("Breaking Solar Panel block with world.breakBlock...");
        world.breakBlock(SOLAR_POS, false);
        stepTicks(world, 10);
        log.accept("Status with Solar Panel broken:");
        printStatus(world, log);

        log.accept("Placing new Solar Panel with setBlockState...");
        world.setBlockState(SOLAR_POS, VoltcraftBlocks.SOLAR_PANEL_MONOCRYSTALLINE.getDefaultState()
            .with(SolarPanelBlock.FACING, Direction.NORTH), 3);
        stepTicks(world, 10);
        log.accept("Status after new Solar Panel placed (expect: solar charging resumes):");
        printStatus(world, log);
    }

    public static void runTestCaseHeavyLoadRampAndColdStart(ServerWorld world, Consumer<String> log) {
        log.accept("=== TEST CASE 7: Heavy Load Soft-Start Ramp vs Cold-Start (100W -> 1000W -> 2500W) ===");
        buildCircuit(world, log);
        stepTicks(world, 10);
        log.accept("Soft-starting load at 100W...");
        setLoad(world, 100.0, log);
        stepTicks(world, 10);
        printStatus(world, log);

        log.accept("Ramping load to 1000W...");
        setLoad(world, 1000.0, log);
        stepTicks(world, 10);
        printStatus(world, log);

        log.accept("Testing Cold Start directly at 2500W...");
        buildCircuit(world, log);
        setLoad(world, 2500.0, log);
        stepTicks(world, 10);
        log.accept("Status after Cold Start at 2500W (did Battery trip into protection?):");
        printStatus(world, log);
    }

    public static void runTestCaseCableChurn(ServerWorld world, Consumer<String> log) {
        log.accept("=== TEST CASE 8: Active Circuit Cable Churn (Disconnect/Reconnect Across Working Circuit) ===");
        buildCircuit(world, log);
        setLoad(world, 500.0, log);
        stepTicks(world, 10);
        log.accept("--- 1. Baseline state with 500W load active ---");
        printStatus(world, log);

        // A. MPPT Out(+) disconnect & reconnect
        BlockPos mpptOutPlus = new BlockPos(100, 64, 107);
        log.accept("--- 2. Disconnecting MPPT Out(+) cable at " + mpptOutPlus.toShortString() + " (Battery should carry load alone) ---");
        world.breakBlock(mpptOutPlus, false);
        stepTicks(world, 10);
        printStatus(world, log);

        log.accept("--- 3. Reconnecting MPPT Out(+) cable at " + mpptOutPlus.toShortString() + " ---");
        setCable(world, mpptOutPlus);
        stepTicks(world, 10);
        printStatus(world, log);

        // B. MPPT Out(-) disconnect & reconnect
        BlockPos mpptOutMinus = new BlockPos(99, 64, 106);
        log.accept("--- 4. Disconnecting MPPT Out(-) cable at " + mpptOutMinus.toShortString() + " ---");
        world.breakBlock(mpptOutMinus, false);
        stepTicks(world, 10);
        printStatus(world, log);

        log.accept("--- 5. Reconnecting MPPT Out(-) cable at " + mpptOutMinus.toShortString() + " ---");
        setCable(world, mpptOutMinus);
        stepTicks(world, 10);
        printStatus(world, log);

        // C. Bus segment between Battery and Load (splits into 2 islands)
        BlockPos busMidPlus = new BlockPos(102, 64, 113);
        log.accept("--- 6. Disconnecting Bus (+) cable between Battery and Load at " + busMidPlus.toShortString() + " ---");
        world.breakBlock(busMidPlus, false);
        stepTicks(world, 10);
        printStatus(world, log);

        log.accept("--- 7. Reconnecting Bus (+) cable at " + busMidPlus.toShortString() + " ---");
        setCable(world, busMidPlus);
        stepTicks(world, 10);
        printStatus(world, log);

        // D. Battery(+) disconnect & reconnect under 500W load
        BlockPos batPlusCable = new BlockPos(100, 64, 113);
        log.accept("--- 8. Disconnecting Battery (+) cable at " + batPlusCable.toShortString() + " under 500W load ---");
        world.breakBlock(batPlusCable, false);
        stepTicks(world, 10);
        printStatus(world, log);

        log.accept("--- 9. Reconnecting Battery (+) cable at " + batPlusCable.toShortString() + " ---");
        setCable(world, batPlusCable);
        stepTicks(world, 10);
        printStatus(world, log);

        // E. Solar(+) disconnect & reconnect under 500W load
        BlockPos solarPlusCable = new BlockPos(100, 64, 103);
        log.accept("--- 10. Disconnecting Solar (+) cable at " + solarPlusCable.toShortString() + " ---");
        world.breakBlock(solarPlusCable, false);
        stepTicks(world, 10);
        printStatus(world, log);

        log.accept("--- 11. Reconnecting Solar (+) cable at " + solarPlusCable.toShortString() + " ---");
        setCable(world, solarPlusCable);
        stepTicks(world, 10);
        printStatus(world, log);

        log.accept("=== TEST CASE 8 FINISHED ===");
    }

    public static void runAllTestCases(ServerWorld world, Consumer<String> log) {
        log.accept("=== RUNNING FULL VOLTCRAFT SIMULATION TEST SUITE ===");
        runTestCaseSolarCutoff(world, log);
        runTestCaseLoadCableDisconnect(world, log);
        runTestCaseMpptRecovery(world, log);
        runTestCaseBatteryHotSwap(world, log);
        runTestCaseMpptHotSwap(world, log);
        runTestCaseSolarHotSwap(world, log);
        runTestCaseHeavyLoadRampAndColdStart(world, log);
        runTestCaseCableChurn(world, log);
        log.accept("=== VOLTCRAFT TEST SUITE EXECUTION FINISHED ===");
    }

    public static void runUserReportedBugsTest(ServerWorld world, Consumer<String> log) {
        runAllTestCases(world, log);
    }

    private static void stepTicks(ServerWorld world, int count) {
        for (int i = 0; i < count; i++) {
            GridManager.get(world).tick(world);
        }
    }
}
