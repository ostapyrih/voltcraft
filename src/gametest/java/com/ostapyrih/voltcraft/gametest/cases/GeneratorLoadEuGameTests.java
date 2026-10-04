package com.ostapyrih.voltcraft.gametest.cases;

import com.ostapyrih.voltcraft.block.VoltcraftBlocks;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.block.entity.conversion.EuConverterBlockEntity;
import com.ostapyrih.voltcraft.block.entity.creative.CreativeLoadBlockEntity;
import com.ostapyrih.voltcraft.block.entity.generation.PortableGeneratorBlockEntity;
import com.ostapyrih.voltcraft.block.generation.PortableGeneratorBlock;
import com.ostapyrih.voltcraft.gametest.framework.GameTestGenCircuitBuilder;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.Direction;

/**
 * Native Minecraft Fabric GameTests for Generator -&gt; Load / EU-converter circuits.
 * These run on a real headless server: real blocks are placed, real block entities
 * tick, fuel burns, cables break and reconnect. Telemetry is read from the live
 * block entities, so every number below is measured in-engine, not simulated.
 */
public class GeneratorLoadEuGameTests {

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 80)
    public void testGenDirectLoadBaseline(TestContext context) {
        GameTestGenCircuitBuilder.buildPlant(context);
        GameTestGenCircuitBuilder.setLoadWatts(context, GameTestGenCircuitBuilder.LOAD2_POS, 500.0);
        GameTestGenCircuitBuilder.addFuel(context, 1_000_000);

        context.runAtTick(40, () -> {
            PortableGeneratorBlockEntity gen = GameTestGenCircuitBuilder.getGen(context);
            CreativeLoadBlockEntity load2 = GameTestGenCircuitBuilder.getLoad2(context);
            context.assertTrue(gen != null && load2 != null, "Generator and LOAD2 must exist");
            context.assertTrue(gen.isRunning(), "Generator must be running on fuel");
            double p = load2.getLastDeliveredPower();
            context.assertTrue(p > 400.0 && p < 600.0,
                "Direct 500W branch must draw ~500W in-engine, got: " + p);
            context.complete();
        });
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 80)
    public void testGenTwoDirectLoads(TestContext context) {
        GameTestGenCircuitBuilder.buildPlant(context);
        // NOTE: the EU bridge shares this bus and legitimately draws ~805W
        // charge demand on a live feed (empty internal battery), so the two
        // direct branches are sized 500W + 400W: 900W direct + ~805W EU charge
        // stays within the 1800W rating. (500W + 1000W direct would be a
        // genuine 2300W overload next to the charging bridge.)
        GameTestGenCircuitBuilder.setLoadWatts(context, GameTestGenCircuitBuilder.LOAD2_POS, 500.0);
        GameTestGenCircuitBuilder.setLoadWatts(context, GameTestGenCircuitBuilder.LOAD3_POS, 400.0);
        GameTestGenCircuitBuilder.addFuel(context, 1_000_000);

        context.runAtTick(40, () -> {
            PortableGeneratorBlockEntity gen = GameTestGenCircuitBuilder.getGen(context);
            CreativeLoadBlockEntity load2 = GameTestGenCircuitBuilder.getLoad2(context);
            CreativeLoadBlockEntity load3 = GameTestGenCircuitBuilder.getLoad3(context);
            context.assertTrue(gen != null && load2 != null && load3 != null, "All blocks must exist");
            double p2 = load2.getLastDeliveredPower();
            double p3 = load3.getLastDeliveredPower();
            context.assertTrue(p2 > 400.0, "500W branch must stay powered next to 400W, got: " + p2);
            context.assertTrue(p3 > 320.0, "400W branch must stay powered next to 500W, got: " + p3);
            context.assertTrue(gen.getLastDeliveredPowerWatts() < 1800.0,
                "Total must stay within the 1800W rating, got: " + gen.getLastDeliveredPowerWatts());
            context.complete();
        });
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 80)
    public void testEuBridgeChargesOnLiveFeed(TestContext context) {
        GameTestGenCircuitBuilder.buildPlant(context);
        GameTestGenCircuitBuilder.setLoadWatts(context, GameTestGenCircuitBuilder.LOAD_POS, 500.0);
        GameTestGenCircuitBuilder.addFuel(context, 1_000_000);

        context.runAtTick(40, () -> {
            EuConverterBlockEntity eu = GameTestGenCircuitBuilder.getEu(context);
            CreativeLoadBlockEntity load = GameTestGenCircuitBuilder.getLoad(context);
            PortableGeneratorBlockEntity gen = GameTestGenCircuitBuilder.getGen(context);
            context.assertTrue(eu != null && load != null && gen != null, "EU bridge, LOAD and GEN must exist");
            context.assertFalse(eu.isTripped(), "EU bridge must not trip on a healthy 230V feed");
            // Spec: empty internal battery on a live 230V/50Hz feed -> ~805W
            // charge demand (32 EU/t * 25W + 5W quiescent), live input telemetry,
            // and a filling internal battery.
            context.assertTrue(eu.getInputPowerWatts() > 700.0 && eu.getInputPowerWatts() < 900.0,
                "EU bridge must draw ~805W charge demand on a live feed, got: " + eu.getInputPowerWatts());
            context.assertTrue(eu.getInputVoltage() > 207.0,
                "EU input telemetry must see a live ~230V feed (above the 207V brownout floor), got: "
                    + eu.getInputVoltage());
            context.assertTrue(eu.getTotalEuGenerated() > 500L,
                "Internal EU battery must charge on a live feed (~32 EU/t * 40 ticks ~= 1280 EU), generated: "
                    + eu.getTotalEuGenerated());
            context.assertTrue(eu.energyStorage.amount > 100L,
                "BUG: converted energy never lands in the buffer (counter clobbered every tick): stored="
                    + eu.energyStorage.amount + " total=" + eu.getTotalEuGenerated());
            context.assertTrue(load.getLastDeliveredPower() < 1.0,
                "Load behind the open EU output pair must stay dark, got: " + load.getLastDeliveredPower());
            context.complete();
        });
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 80)
    public void testGenOverloadBeyondRating(TestContext context) {
        GameTestGenCircuitBuilder.buildPlant(context);
        // 1500W + 1000W direct + ~805W EU charge = ~3300W demand on an
        // 1800W rated / 2200W surge generator.
        GameTestGenCircuitBuilder.setLoadWatts(context, GameTestGenCircuitBuilder.LOAD2_POS, 1500.0);
        GameTestGenCircuitBuilder.setLoadWatts(context, GameTestGenCircuitBuilder.LOAD3_POS, 1000.0);
        GameTestGenCircuitBuilder.addFuel(context, 1_000_000);

        context.runAtTick(40, () -> {
            PortableGeneratorBlockEntity gen = GameTestGenCircuitBuilder.getGen(context);
            CreativeLoadBlockEntity load2 = GameTestGenCircuitBuilder.getLoad2(context);
            CreativeLoadBlockEntity load3 = GameTestGenCircuitBuilder.getLoad3(context);
            EuConverterBlockEntity eu = GameTestGenCircuitBuilder.getEu(context);
            context.assertTrue(gen != null && load2 != null && load3 != null && eu != null, "All blocks must exist");
            context.assertTrue(gen.isRunning(), "Generator must be running on fuel");
            // Spec: ~3300W demand must NOT be served silently at nominal voltage.
            context.assertTrue(gen.getLastDeliveredPowerWatts() <= 2200.0,
                "Generator must cap output at the 2200W surge rating under ~3300W demand, got: "
                    + gen.getLastDeliveredPowerWatts());
            context.assertTrue(eu.getInputVoltage() < 207.0,
                "Overloaded bus must sag into the EU brownout window (<207V), got: " + eu.getInputVoltage());
            context.complete();
        });
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 80)
    public void testGenLoadPlusEuCharging(TestContext context) {
        GameTestGenCircuitBuilder.buildPlant(context);
        // 500W direct branch + EU bridge charging (~805W) -> generator carries ~1300W.
        GameTestGenCircuitBuilder.setLoadWatts(context, GameTestGenCircuitBuilder.LOAD2_POS, 500.0);
        GameTestGenCircuitBuilder.addFuel(context, 1_000_000);

        context.runAtTick(40, () -> {
            PortableGeneratorBlockEntity gen = GameTestGenCircuitBuilder.getGen(context);
            CreativeLoadBlockEntity load2 = GameTestGenCircuitBuilder.getLoad2(context);
            EuConverterBlockEntity eu = GameTestGenCircuitBuilder.getEu(context);
            context.assertTrue(gen != null && load2 != null && eu != null, "All blocks must exist");
            context.assertTrue(load2.getLastDeliveredPower() > 400.0,
                "Direct 500W branch must stay powered next to the charging EU bridge, got: "
                    + load2.getLastDeliveredPower());
            context.assertTrue(eu.getInputPowerWatts() > 700.0 && eu.getInputPowerWatts() < 900.0,
                "EU bridge must draw ~805W charge demand next to the direct load, got: "
                    + eu.getInputPowerWatts());
            double total = gen.getLastDeliveredPowerWatts();
            context.assertTrue(total > 1100.0 && total < 1500.0,
                "Generator must carry 500W direct + ~805W EU charge (~1300W total), got: " + total);
            context.complete();
        });
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 100)
    public void testDirectFeedBreakAndReconnect(TestContext context) {
        GameTestGenCircuitBuilder.buildPlant(context);
        GameTestGenCircuitBuilder.setLoadWatts(context, GameTestGenCircuitBuilder.LOAD2_POS, 500.0);
        GameTestGenCircuitBuilder.addFuel(context, 1_000_000);

        // Break the direct (+) rail at its pure-cable mid node (1,1,2).
        // NOTE: breaking LOAD2_PLUS_CABLE (0,1,2) alone does NOT darken the
        // branch: the tap keeps LOAD2's terminal node, bridged to (1,1,2)
        // through a terminal link. Only a cut at a pure-cable node severs
        // the bus, so that is what this fault-injection test uses.
        context.runAtTick(20, () -> GameTestGenCircuitBuilder.breakCable(
            context, GameTestGenCircuitBuilder.DIRECT_PLUS_MID));

        context.runAtTick(40, () -> {
            CreativeLoadBlockEntity load2 = GameTestGenCircuitBuilder.getLoad2(context);
            context.assertTrue(load2 != null, "LOAD2 must exist");
            context.assertTrue(load2.getLastDeliveredPower() < 5.0,
                "Branch must go dark while the pure-cable rail mid is cut, got: "
                    + load2.getLastDeliveredPower());
        });

        // Reconnect at tick 60.
        context.runAtTick(60, () -> GameTestGenCircuitBuilder.restoreCable(
            context, GameTestGenCircuitBuilder.DIRECT_PLUS_MID, ConductorType.INSULATED_COPPER));

        context.runAtTick(85, () -> {
            CreativeLoadBlockEntity load2 = GameTestGenCircuitBuilder.getLoad2(context);
            context.assertTrue(load2 != null && load2.getLastDeliveredPower() > 400.0,
                "Branch must resume after reconnect, got: "
                    + (load2 == null ? "null" : load2.getLastDeliveredPower()));
            context.complete();
        });
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 100)
    public void testEuInputMinusBreakAndReconnect(TestContext context) {
        GameTestGenCircuitBuilder.buildPlant(context);
        GameTestGenCircuitBuilder.setLoadWatts(context, GameTestGenCircuitBuilder.LOAD2_POS, 500.0);
        GameTestGenCircuitBuilder.addFuel(context, 1_000_000);

        // Break EU In(-) tap at tick 20: EU input opens, direct branch must not care.
        context.runAtTick(20, () -> GameTestGenCircuitBuilder.breakCable(
            context, GameTestGenCircuitBuilder.EU_IN_MINUS_TAP));

        context.runAtTick(40, () -> {
            EuConverterBlockEntity eu = GameTestGenCircuitBuilder.getEu(context);
            CreativeLoadBlockEntity load2 = GameTestGenCircuitBuilder.getLoad2(context);
            context.assertTrue(eu != null && load2 != null, "Blocks must exist");
            context.assertFalse(eu.isTripped(), "EU bridge must not trip on input wire loss");
            context.assertTrue(eu.getInputVoltage() < 10.0,
                "EU input must go dark without its return leg, got: " + eu.getInputVoltage());
            context.assertTrue(eu.getInputPowerWatts() < 10.0,
                "EU bridge must shed charge demand on an open input, got: " + eu.getInputPowerWatts());
            context.assertTrue(load2.getLastDeliveredPower() > 400.0,
                "Direct branch must be unaffected by the EU feed cut, got: " + load2.getLastDeliveredPower());
        });

        context.runAtTick(60, () -> GameTestGenCircuitBuilder.restoreCable(
            context, GameTestGenCircuitBuilder.EU_IN_MINUS_TAP, ConductorType.INSULATED_COPPER));

        context.runAtTick(85, () -> {
            EuConverterBlockEntity eu = GameTestGenCircuitBuilder.getEu(context);
            context.assertTrue(eu != null && !eu.isTripped(), "EU bridge must stay healthy after reconnect");
            context.assertTrue(eu.getInputPowerWatts() > 700.0 && eu.getInputPowerWatts() < 900.0,
                "EU bridge must resume ~805W charge demand after reconnect, got: "
                    + (eu == null ? "null" : eu.getInputPowerWatts()));
            context.complete();
        });
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 220)
    public void testFuelExhaustion(TestContext context) {
        GameTestGenCircuitBuilder.buildPlant(context);
        GameTestGenCircuitBuilder.setLoadWatts(context, GameTestGenCircuitBuilder.LOAD2_POS, 500.0);
        GameTestGenCircuitBuilder.addFuel(context, 25);

        context.runAtTick(30, () -> {
            PortableGeneratorBlockEntity gen = GameTestGenCircuitBuilder.getGen(context);
            context.assertTrue(gen != null && gen.isRunning(), "Generator must be running early on");
        });

        context.runAtTick(180, () -> {
            PortableGeneratorBlockEntity gen = GameTestGenCircuitBuilder.getGen(context);
            CreativeLoadBlockEntity load2 = GameTestGenCircuitBuilder.getLoad2(context);
            context.assertTrue(gen != null && load2 != null, "Blocks must exist");
            context.assertFalse(gen.isRunning(), "Tank seeded with 25 ticks must run dry");
            context.assertTrue(load2.getLastDeliveredPower() < 5.0,
                "Load must go dark on a dry tank, got: " + load2.getLastDeliveredPower());
            context.complete();
        });
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 140)
    public void testGeneratorHotSwap(TestContext context) {
        GameTestGenCircuitBuilder.buildPlant(context);
        GameTestGenCircuitBuilder.setLoadWatts(context, GameTestGenCircuitBuilder.LOAD2_POS, 500.0);
        GameTestGenCircuitBuilder.addFuel(context, 1_000_000);

        // Break the generator block at tick 20.
        context.runAtTick(20, () -> context.getWorld().breakBlock(
            context.getAbsolutePos(GameTestGenCircuitBuilder.GEN_POS), false));

        context.runAtTick(45, () -> {
            CreativeLoadBlockEntity load2 = GameTestGenCircuitBuilder.getLoad2(context);
            context.assertTrue(load2 != null, "LOAD2 must exist");
            context.assertTrue(load2.getLastDeliveredPower() < 5.0,
                "Load must go dark without the generator, got: " + load2.getLastDeliveredPower());
        });

        // Place a fresh fueled generator at tick 70.
        context.runAtTick(70, () -> {
            context.setBlockState(GameTestGenCircuitBuilder.GEN_POS,
                VoltcraftBlocks.GENERATOR_PORTABLE_INVERTER.getDefaultState()
                    .with(PortableGeneratorBlock.FACING, Direction.NORTH));
            GameTestGenCircuitBuilder.addFuel(context, 1_000_000);
        });

        context.runAtTick(115, () -> {
            CreativeLoadBlockEntity load2 = GameTestGenCircuitBuilder.getLoad2(context);
            context.assertTrue(load2 != null && load2.getLastDeliveredPower() > 400.0,
                "Load must resume after generator replacement, got: "
                    + (load2 == null ? "null" : load2.getLastDeliveredPower()));
            context.complete();
        });
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 170)
    public void testSequentialChurn(TestContext context) {
        GameTestGenCircuitBuilder.buildPlant(context);
        GameTestGenCircuitBuilder.setLoadWatts(context, GameTestGenCircuitBuilder.LOAD2_POS, 500.0);
        GameTestGenCircuitBuilder.addFuel(context, 1_000_000);

        // 1. Break EU Out(+) tap at tick 20 (EU output already open: expect no effect).
        context.runAtTick(20, () -> GameTestGenCircuitBuilder.breakCable(
            context, GameTestGenCircuitBuilder.EU_OUT_PLUS));
        context.runAtTick(30, () -> {
            CreativeLoadBlockEntity load2 = GameTestGenCircuitBuilder.getLoad2(context);
            context.assertTrue(load2 != null && load2.getLastDeliveredPower() > 400.0,
                "Open EU output cut must not disturb the direct branch, got: "
                    + (load2 == null ? "null" : load2.getLastDeliveredPower()));
        });
        context.runAtTick(35, () -> GameTestGenCircuitBuilder.restoreCable(
            context, GameTestGenCircuitBuilder.EU_OUT_PLUS, ConductorType.HEAVY_COPPER));

        // 2. Break direct (+) rail mid at tick 50 (both direct branches go dark).
        context.runAtTick(50, () -> GameTestGenCircuitBuilder.breakCable(
            context, GameTestGenCircuitBuilder.DIRECT_PLUS_MID));
        context.runAtTick(57, () -> {
            CreativeLoadBlockEntity load2 = GameTestGenCircuitBuilder.getLoad2(context);
            context.assertTrue(load2 != null && load2.getLastDeliveredPower() < 5.0,
                "Direct branch must go dark while the rail mid is cut, got: "
                    + (load2 == null ? "null" : load2.getLastDeliveredPower()));
        });
        context.runAtTick(65, () -> GameTestGenCircuitBuilder.restoreCable(
            context, GameTestGenCircuitBuilder.DIRECT_PLUS_MID, ConductorType.INSULATED_COPPER));
        context.runAtTick(72, () -> {
            CreativeLoadBlockEntity load2 = GameTestGenCircuitBuilder.getLoad2(context);
            context.assertTrue(load2 != null && load2.getLastDeliveredPower() > 400.0,
                "Direct branch must recover after rail-mid restore, got: "
                    + (load2 == null ? "null" : load2.getLastDeliveredPower()));
        });

        // 3. Break EU In(-) tap at tick 80.
        context.runAtTick(80, () -> GameTestGenCircuitBuilder.breakCable(
            context, GameTestGenCircuitBuilder.EU_IN_MINUS_TAP));
        context.runAtTick(87, () -> {
            EuConverterBlockEntity eu = GameTestGenCircuitBuilder.getEu(context);
            CreativeLoadBlockEntity load2 = GameTestGenCircuitBuilder.getLoad2(context);
            context.assertTrue(eu != null && load2 != null, "Blocks must exist during EU feed cut");
            context.assertTrue(eu.getInputPowerWatts() < 10.0,
                "EU bridge must shed demand while its input is cut, got: " + eu.getInputPowerWatts());
            context.assertTrue(load2.getLastDeliveredPower() > 400.0,
                "Direct branch must ride through the EU feed cut, got: " + load2.getLastDeliveredPower());
        });
        context.runAtTick(95, () -> GameTestGenCircuitBuilder.restoreCable(
            context, GameTestGenCircuitBuilder.EU_IN_MINUS_TAP, ConductorType.INSULATED_COPPER));

        context.runAtTick(140, () -> {
            PortableGeneratorBlockEntity gen = GameTestGenCircuitBuilder.getGen(context);
            CreativeLoadBlockEntity load2 = GameTestGenCircuitBuilder.getLoad2(context);
            EuConverterBlockEntity eu = GameTestGenCircuitBuilder.getEu(context);
            context.assertTrue(gen != null && load2 != null && eu != null, "Blocks must exist");
            context.assertTrue(load2.getLastDeliveredPower() > 400.0,
                "Direct branch must recover after churn, got: " + load2.getLastDeliveredPower());
            context.assertFalse(eu.isTripped(), "EU bridge must stay healthy after churn");
            context.assertTrue(gen.getLastDeliveredPowerWatts() < 1800.0,
                "Generator must stay within rating, got: " + gen.getLastDeliveredPowerWatts());
            context.complete();
        });
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 80)
    public void testGenEuOverloadCooks(TestContext context) {
        GameTestGenCircuitBuilder.buildPlant(context);
        // 1500W + 1000W direct + ~805W EU charge = ~3300W demand on an
        // 1800W/2200W generator. The AVR sags the bus into the EU brownout
        // window (<207V): a correct bridge sheds charge demand to ~0 instead
        // of cooking. Regression guard for the brownout derate.
        GameTestGenCircuitBuilder.setLoadWatts(context, GameTestGenCircuitBuilder.LOAD2_POS, 1500.0);
        GameTestGenCircuitBuilder.setLoadWatts(context, GameTestGenCircuitBuilder.LOAD3_POS, 1000.0);
        GameTestGenCircuitBuilder.addFuel(context, 1_000_000);

        context.runAtTick(60, () -> {
            PortableGeneratorBlockEntity gen = GameTestGenCircuitBuilder.getGen(context);
            EuConverterBlockEntity eu = GameTestGenCircuitBuilder.getEu(context);
            context.assertTrue(gen != null && eu != null, "Generator and EU bridge must exist");
            context.assertTrue(gen.getLastDeliveredPowerWatts() <= 2200.0,
                "Generator must cap output at surge even with the EU bridge aboard, got: "
                    + gen.getLastDeliveredPowerWatts());
            context.assertTrue(eu.getTotalEuGenerated() < 200L,
                "Browned-out bridge must accumulate next to nothing (trickle before the sag is fine), generated: "
                    + eu.getTotalEuGenerated());
            context.assertTrue(eu.getInputPowerWatts() < 100.0,
                "Regression: EU bridge must shed charge demand on a brownout bus, got input="
                    + eu.getInputPowerWatts()
                    + "W at Vin=" + eu.getInputVoltage() + "V, stored=" + eu.energyStorage.amount
                    + "E, temp=" + eu.getTemperatureCelsius() + "C");
            context.complete();
        });
    }
}
