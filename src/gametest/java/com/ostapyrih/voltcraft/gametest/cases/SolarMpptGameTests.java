package com.ostapyrih.voltcraft.gametest.cases;

import com.ostapyrih.voltcraft.block.entity.generation.ChargeControllerBlockEntity;
import com.ostapyrih.voltcraft.block.entity.storage.BatteryBlockEntity;
import com.ostapyrih.voltcraft.gametest.framework.GameTestCircuitBuilder;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.test.TestContext;

/**
 * Native Minecraft Fabric GameTests for the Solar -> MPPT -> Battery -> Load circuit.
 * These tests run inside real world chunks on a headless Minecraft server,
 * executing physical block break/place actions and validating simulation behavior.
 */
public class SolarMpptGameTests {

    @GameTest(structure = "fabric-gametest-api-v1:empty", skyAccess = true, maxTicks = 60)
    public void testBaselineCharging(TestContext context) {
        GameTestCircuitBuilder.buildCircuit(context);

        context.runAtTick(15, () -> {
            ChargeControllerBlockEntity mppt = GameTestCircuitBuilder.getMppt(context);
            BatteryBlockEntity bat = GameTestCircuitBuilder.getBattery(context);

            context.assertTrue(mppt != null, "MPPT block entity must exist");
            context.assertTrue(bat != null, "Battery block entity must exist");
            context.assertFalse(mppt.isTripped(), "MPPT should not be tripped during baseline charging");
            context.assertTrue(mppt.getInputVoltage() > 20.0, "MPPT input voltage should see solar (>20V), got: " + mppt.getInputVoltage());
            context.complete();
        });
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", skyAccess = true, maxTicks = 60)
    public void testSolarCutoff(TestContext context) {
        GameTestCircuitBuilder.buildCircuit(context);

        // Break solar panel at tick 10
        context.runAtTick(10, () -> {
            context.getWorld().breakBlock(context.getAbsolutePos(GameTestCircuitBuilder.SOLAR_POS), false);
        });

        // Verify charging has cut off without phantom current
        context.runAtTick(25, () -> {
            ChargeControllerBlockEntity mppt = GameTestCircuitBuilder.getMppt(context);
            context.assertTrue(mppt != null, "MPPT must exist");
            context.assertTrue(mppt.getInputPowerWatts() < 1.0, "Input power must drop to 0W when solar panel is destroyed, got: " + mppt.getInputPowerWatts());
            context.complete();
        });
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", skyAccess = true, maxTicks = 80)
    public void testLoadCableDisconnectAndReconnect(TestContext context) {
        GameTestCircuitBuilder.buildCircuit(context);
        GameTestCircuitBuilder.setLoad(context, 500.0);

        // Break load positive cable at tick 15
        context.runAtTick(15, () -> {
            GameTestCircuitBuilder.breakCable(context, GameTestCircuitBuilder.LOAD_PLUS_CABLE);
        });

        // Restore load cable at tick 30
        context.runAtTick(30, () -> {
            GameTestCircuitBuilder.restoreCable(context, GameTestCircuitBuilder.LOAD_PLUS_CABLE);
        });

        // Check if load resumed and MPPT didn't trip
        context.runAtTick(50, () -> {
            ChargeControllerBlockEntity mppt = GameTestCircuitBuilder.getMppt(context);
            context.assertTrue(mppt != null, "MPPT must exist");
            context.assertFalse(mppt.isTripped(), "MPPT must not be tripped after load cable reconnect");
            context.complete();
        });
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", skyAccess = true, maxTicks = 80)
    public void testMpptAutoRecovery(TestContext context) {
        GameTestCircuitBuilder.buildCircuit(context);

        // Disconnect solar input wire at tick 10
        context.runAtTick(10, () -> {
            GameTestCircuitBuilder.breakCable(context, GameTestCircuitBuilder.SOLAR_PLUS_CABLE);
        });

        // Reconnect solar input wire at tick 30 (after UVLO counter has expired)
        context.runAtTick(30, () -> {
            GameTestCircuitBuilder.restoreCable(context, GameTestCircuitBuilder.SOLAR_PLUS_CABLE);
        });

        // Check if MPPT recovers without latching tripped forever
        context.runAtTick(50, () -> {
            ChargeControllerBlockEntity mppt = GameTestCircuitBuilder.getMppt(context);
            context.assertTrue(mppt != null, "MPPT must exist");
            context.assertFalse(mppt.isTripped(), "MPPT must auto-recover and not remain tripped after solar cable reconnect");
            context.complete();
        });
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", skyAccess = true, maxTicks = 120)
    public void testActiveCircuitCableChurn(TestContext context) {
        GameTestCircuitBuilder.buildCircuit(context);
        GameTestCircuitBuilder.setLoad(context, 500.0);

        // 1. Break MPPT Out(+) at tick 15
        context.runAtTick(15, () -> {
            GameTestCircuitBuilder.breakCable(context, GameTestCircuitBuilder.MPPT_OUT_PLUS);
        });

        // 2. Restore MPPT Out(+) at tick 30
        context.runAtTick(30, () -> {
            GameTestCircuitBuilder.restoreCable(context, GameTestCircuitBuilder.MPPT_OUT_PLUS);
        });

        // 3. Break Battery(+) at tick 45
        context.runAtTick(45, () -> {
            GameTestCircuitBuilder.breakCable(context, GameTestCircuitBuilder.BATTERY_PLUS_CABLE);
        });

        // 4. Restore Battery(+) at tick 60
        context.runAtTick(60, () -> {
            GameTestCircuitBuilder.restoreCable(context, GameTestCircuitBuilder.BATTERY_PLUS_CABLE);
        });

        // 5. Final check at tick 85
        context.runAtTick(85, () -> {
            ChargeControllerBlockEntity mppt = GameTestCircuitBuilder.getMppt(context);
            BatteryBlockEntity bat = GameTestCircuitBuilder.getBattery(context);
            context.assertTrue(mppt != null && bat != null, "Components must exist");
            context.assertFalse(mppt.isTripped(), "MPPT should be healthy after cable churn cycle");
            context.assertFalse(bat.isBmsOpen(), "Battery BMS should not be in protection mode");
            context.complete();
        });
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", skyAccess = true, maxTicks = 80)
    public void testNearlyFullBatteryLightLoad(TestContext context) {
        GameTestCircuitBuilder.buildCircuit(context);
        // Nearly-full bank (97% SoC) + 100W load on full sun. Applied both
        // synchronously and at tick 5: BE availability right after placement
        // is not guaranteed, and a silently-skipped setup would make this
        // test pass vacuously (load dark, MPPT legitimately idle).
        GameTestCircuitBuilder.setBatterySoc(context, 0.97);
        GameTestCircuitBuilder.setLoad(context, 100.0);

        // Nearly-full bank (97% SoC) + 100W load on full sun.
        context.runAtTick(5, () -> {
            GameTestCircuitBuilder.setBatterySoc(context, 0.97);
            GameTestCircuitBuilder.setLoad(context, 100.0);
        });

        java.util.function.IntConsumer busWatch = (tick) -> {
            var mppt = GameTestCircuitBuilder.getMppt(context);
            var bat = GameTestCircuitBuilder.getBattery(context);
            var load = GameTestCircuitBuilder.getLoad(context);
            System.out.println("GT DIAG T18 t" + tick
                + ": Vout=" + (mppt == null ? "null" : String.format("%.2f", mppt.getOutputVoltage()))
                + " Pout=" + (mppt == null ? "null" : String.format("%.1f", mppt.getOutputPowerWatts()))
                + " tripped=" + (mppt == null ? "null" : mppt.isTripped())
                + " bms=" + (bat == null ? "null" : bat.isBmsOpen())
                + " batV=" + (bat == null ? "null" : String.format("%.2f", bat.getLastTerminalVoltage()))
                + " batI=" + (bat == null ? "null" : String.format("%.2f", bat.getLastCurrentAmps()))
                + " loadP=" + (load == null ? "null" : String.format("%.1f", load.getLastDeliveredPower()))
                + " soc=" + (bat == null ? "null" : String.format("%.4f", bat.getStateOfCharge())));
        };
        context.runAtTick(30, () -> busWatch.accept(30));

        context.runAtTick(50, () -> {
            ChargeControllerBlockEntity mppt = GameTestCircuitBuilder.getMppt(context);
            BatteryBlockEntity bat = GameTestCircuitBuilder.getBattery(context);
            var load = GameTestCircuitBuilder.getLoad(context);
            System.out.println("GT DIAG T18 t50: soc=" + (bat == null ? "null" : bat.getStateOfCharge())
                + " loadP=" + (load == null ? "null" : load.getLastDeliveredPower())
                + " mpptVin=" + (mppt == null ? "null" : mppt.getInputVoltage())
                + " mpptVout=" + (mppt == null ? "null" : mppt.getOutputVoltage())
                + " mpptPout=" + (mppt == null ? "null" : mppt.getOutputPowerWatts())
                + " tripped=" + (mppt == null ? "null" : mppt.isTripped())
                + " batV=" + (bat == null ? "null" : bat.getLastTerminalVoltage())
                + " batI=" + (bat == null ? "null" : bat.getLastCurrentAmps()));
            context.assertTrue(mppt != null && bat != null, "MPPT and battery must exist");
            context.assertFalse(mppt.isTripped(), "MPPT must not trip under a 100W load");
            double pout = mppt.getOutputPowerWatts();
            double loadP = load == null ? Double.NaN : load.getLastDeliveredPower();
            context.assertFalse(bat.isBmsOpen(),
                "BUG: BMS sits open (false undervoltage trip on unsolved 0V telemetry at tick 1, "
                    + "then unrecoverable: battery EMF=0 collapses the bus and MPPT foldback traps it at ~0V)");
            context.assertTrue(loadP > 90.0 && loadP < 110.0,
                "The enabled 100W load must actually be fed from the bus, got: " + loadP);
            context.assertTrue(pout >= 120.0,
                "BUG (user symptom): MPPT must cover the 100W load + keep charging (~140W) on a nearly-full "
                    + "battery with solar headroom, got: " + pout);
            context.assertTrue(pout >= 120.0,
                "MPPT must cover the 100W load + keep charging (~140W) on a nearly-full battery with solar headroom, got: " + pout);
            context.complete();
        });
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", skyAccess = true, maxTicks = 60)
    public void testHeavyLoadColdStartVsSoftStart(TestContext context) {
        GameTestCircuitBuilder.buildCircuit(context);

        // Cold start directly at 1000W at tick 10
        context.runAtTick(10, () -> {
            GameTestCircuitBuilder.setLoad(context, 1000.0);
        });

        context.runAtTick(30, () -> {
            BatteryBlockEntity bat = GameTestCircuitBuilder.getBattery(context);
            context.assertTrue(bat != null, "Battery must exist");
            context.assertFalse(bat.isBmsOpen(), "Battery BMS must not trip into protection on 1000W load");
            context.complete();
        });
    }
}
