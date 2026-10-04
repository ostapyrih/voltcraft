package com.ostapyrih.voltcraft.gametest.cases;

import com.ostapyrih.voltcraft.block.VoltcraftBlocks;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.block.entity.generation.ChargeControllerBlockEntity;
import com.ostapyrih.voltcraft.block.entity.storage.BatteryBlockEntity;
import com.ostapyrih.voltcraft.block.generation.SolarPanelBlock;
import com.ostapyrih.voltcraft.gametest.framework.GameTestCircuitBuilder;
import com.ostapyrih.voltcraft.simulation.grid.GridManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Parallel-solar bug traps for the MPPT charge controller.
 *
 * <p>User symptom (2026-10): MPPT only works with the panel standing tight
 * (touching its input node); panels wired in parallel further away are ignored.
 * Prime suspect: {@code ChargeControllerBlockEntity.getUpstreamAvailableSolarWatts()}
 * looks the island up by block pos ({@code gm.getIslandAt(pos)}), but island
 * node keys are cable/terminal positions, never block positions, so the island
 * branch is always null and only the 6-neighbour fallback around the input port
 * (the tight panel) is ever counted.
 *
 * <p>These tests do NOT fix anything, they only catch the bug:
 * <ul>
 *   <li>control (tight only) must PASS even with the bug (harness sanity),</li>
 *   <li>parallel far panel must FAIL with the bug (available solar ~400W not ~800W),</li>
 *   <li>far panel alone after tight removal must FAIL with the bug (available solar ~0W).</li>
 * </ul>
 *
 * <p>Second-panel wiring (verified 6-adjacency clean: plus and minus cable nets
 * are nowhere Manhattan-adjacent, so no short):
 * <pre>
 *   FAR panel (6,1,1) NORTH: (-) (6,1,0), (+) (6,1,2)
 *   minus: (6,1,0) -> (5,1,0) -> existing (4,1,0) input-minus rail
 *   plus:  (6,1,2) -> (6,2,2) -> (6,3,2) -> (5,3,2) -> (4,3,2)
 *          -> (3,3,2) -> (2,3,2) -> (2,2,2) -> existing (2,1,2) input-plus node
 *   The y=3 bridge crosses the x=4 minus column at height 3 (Manhattan distance
 *   2 from the y=1 minus rail, so no cable-cable branch forms).
 * </pre>
 */
public class SolarMpptParallelGameTests {

    public static final BlockPos FAR_PANEL_POS = new BlockPos(6, 1, 1);
    private static final BlockPos FAR_MINUS_TERM = new BlockPos(6, 1, 0);
    private static final BlockPos FAR_MINUS_BRIDGE = new BlockPos(5, 1, 0);
    private static final BlockPos FAR_PLUS_TERM = new BlockPos(6, 1, 2);
    private static final BlockPos[] FAR_PLUS_BRIDGE = {
        new BlockPos(6, 2, 2),
        new BlockPos(6, 3, 2),
        new BlockPos(5, 3, 2),
        new BlockPos(4, 3, 2),
        new BlockPos(3, 3, 2),
        new BlockPos(2, 3, 2),
        new BlockPos(2, 2, 2),
    };

    /** Places a second MONO panel in parallel, far from the MPPT input node. Call synchronously after buildCircuit. */
    public static void addFarParallelPanel(TestContext context) {
        // Sky access for the far panel (mirror the builder's clear above the tight panel).
        BlockPos absFar = context.getAbsolutePos(FAR_PANEL_POS);
        for (int y = absFar.getY() + 1; y <= 320; y++) {
            context.getWorld().setBlockState(
                new BlockPos(absFar.getX(), y, absFar.getZ()),
                Blocks.AIR.getDefaultState(), 2);
        }

        context.setBlockState(FAR_PANEL_POS, VoltcraftBlocks.SOLAR_PANEL_MONOCRYSTALLINE.getDefaultState()
            .with(SolarPanelBlock.FACING, Direction.NORTH));

        GameTestCircuitBuilder.setCable(context, FAR_MINUS_TERM, ConductorType.HEAVY_COPPER);
        GameTestCircuitBuilder.setCable(context, FAR_MINUS_BRIDGE, ConductorType.HEAVY_COPPER);
        GameTestCircuitBuilder.setCable(context, FAR_PLUS_TERM, ConductorType.HEAVY_COPPER);
        for (BlockPos p : FAR_PLUS_BRIDGE) {
            GameTestCircuitBuilder.setCable(context, p, ConductorType.HEAVY_COPPER);
        }

        GameTestCircuitBuilder.registerAttached(context, FAR_PANEL_POS);
        // BE materialization right after placement is not guaranteed; retry like the other rigs.
        for (int t = 1; t <= 5; t++) {
            final int tick = t;
            context.runAtTick(tick, () -> GameTestCircuitBuilder.registerAttached(context, FAR_PANEL_POS));
        }
        GridManager.get(context.getWorld()).rebuildIslands();
    }

    private static void logBus(TestContext context, String tag) {
        var mppt = GameTestCircuitBuilder.getMppt(context);
        var bat = GameTestCircuitBuilder.getBattery(context);
        var load = GameTestCircuitBuilder.getLoad(context);
        var far = context.getWorld().getBlockEntity(context.getAbsolutePos(FAR_PANEL_POS));
        System.out.println("GT PARALLEL " + tag
            + ": avail=" + (mppt == null ? "null" : String.format("%.1f", mppt.getUpstreamAvailableSolarWatts()))
            + " Vin=" + (mppt == null ? "null" : String.format("%.2f", mppt.getInputVoltage()))
            + " Pin=" + (mppt == null ? "null" : String.format("%.1f", mppt.getInputPowerWatts()))
            + " Pout=" + (mppt == null ? "null" : String.format("%.1f", mppt.getOutputPowerWatts()))
            + " tripped=" + (mppt == null ? "null" : mppt.isTripped())
            + " loadP=" + (load == null ? "null" : String.format("%.1f", load.getLastDeliveredPower()))
            + " soc=" + (bat == null ? "null" : String.format("%.3f", bat.getStateOfCharge()))
            + " farBE=" + (far == null ? "null" : far.getClass().getSimpleName()));
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", skyAccess = true, maxTicks = 80)
    public void testSingleTightPanelControl(TestContext context) {
        GameTestCircuitBuilder.buildCircuit(context);
        GameTestCircuitBuilder.setBatterySoc(context, 0.5);
        GameTestCircuitBuilder.setLoad(context, 200.0);
        context.runAtTick(5, () -> {
            GameTestCircuitBuilder.setBatterySoc(context, 0.5);
            GameTestCircuitBuilder.setLoad(context, 200.0);
        });

        context.runAtTick(60, () -> {
            logBus(context, "control");
            ChargeControllerBlockEntity mppt = GameTestCircuitBuilder.getMppt(context);
            BatteryBlockEntity bat = GameTestCircuitBuilder.getBattery(context);
            var load = GameTestCircuitBuilder.getLoad(context);
            context.assertTrue(mppt != null && bat != null && load != null, "MPPT, battery and load must exist");
            context.assertFalse(mppt.isTripped(), "Control rig must not trip (harness short would trip here)");
            context.assertFalse(bat.isBmsOpen(), "Control BMS must stay closed (harness short would open it)");
            context.assertTrue(mppt.getUpstreamAvailableSolarWatts() > 300.0,
                "Control: single tight panel must report ~400W available, got: " + mppt.getUpstreamAvailableSolarWatts());
            context.assertTrue(mppt.getInputVoltage() > 20.0,
                "Control: MPPT must see solar (>20V), got: " + mppt.getInputVoltage());
            context.assertTrue(load.getLastDeliveredPower() > 150.0,
                "Control: 200W load must be fed from the tight panel, got: " + load.getLastDeliveredPower());
            context.complete();
        });
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", skyAccess = true, maxTicks = 100)
    public void testParallelFarPanelDoublesAvailableSolar(TestContext context) {
        GameTestCircuitBuilder.buildCircuit(context);
        addFarParallelPanel(context);
        // Discharged bank + 500W load: single 400W panel caps at ~392W out,
        // two in parallel should hold ~784W. Demands >500W to separate them.
        GameTestCircuitBuilder.setBatterySoc(context, 0.5);
        GameTestCircuitBuilder.setLoad(context, 500.0);
        context.runAtTick(5, () -> {
            GameTestCircuitBuilder.setBatterySoc(context, 0.5);
            GameTestCircuitBuilder.setLoad(context, 500.0);
            GameTestCircuitBuilder.registerAttached(context, FAR_PANEL_POS);
        });

        context.runAtTick(70, () -> {
            logBus(context, "parallel");
            ChargeControllerBlockEntity mppt = GameTestCircuitBuilder.getMppt(context);
            BatteryBlockEntity bat = GameTestCircuitBuilder.getBattery(context);
            var load = GameTestCircuitBuilder.getLoad(context);
            var farBe = context.getWorld().getBlockEntity(context.getAbsolutePos(FAR_PANEL_POS));
            context.assertTrue(mppt != null && bat != null && load != null
                && farBe instanceof com.ostapyrih.voltcraft.block.entity.generation.SolarPanelBlockEntity,
                "MPPT, battery, load and the FAR panel BE must all exist (farBE=" + farBe + ")");
            context.assertFalse(mppt.isTripped(), "MPPT must not trip with two panels in parallel");
            context.assertFalse(bat.isBmsOpen(), "BMS must stay closed with two panels in parallel");
            context.assertTrue(mppt.getUpstreamAvailableSolarWatts() > 600.0,
                "BUG: MPPT ignores the far parallel panel (counts only the tight one): "
                    + "available solar=" + mppt.getUpstreamAvailableSolarWatts()
                    + "W, expected ~800W from 2x MONO-400W");
            context.assertTrue(mppt.getOutputPowerWatts() > 500.0,
                "BUG: MPPT output capped at single-panel power, far panel contributes nothing: Pout="
                    + mppt.getOutputPowerWatts() + "W, expected >500W from 2 panels into 500W load + charging");
            context.assertTrue(load.getLastDeliveredPower() > 400.0,
                "500W load must stay fed by two panels, got: " + load.getLastDeliveredPower());
            context.complete();
        });
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", skyAccess = true, maxTicks = 100)
    public void testFarPanelKeepsFeedingAfterTightRemoved(TestContext context) {
        GameTestCircuitBuilder.buildCircuit(context);
        addFarParallelPanel(context);
        GameTestCircuitBuilder.setBatterySoc(context, 0.5);
        GameTestCircuitBuilder.setLoad(context, 200.0);
        context.runAtTick(5, () -> {
            GameTestCircuitBuilder.setBatterySoc(context, 0.5);
            GameTestCircuitBuilder.setLoad(context, 200.0);
            GameTestCircuitBuilder.registerAttached(context, FAR_PANEL_POS);
        });

        // Remove the TIGHT panel at tick 10: the far panel alone (~400W) must keep the bus alive.
        context.runAtTick(10, () -> {
            context.getWorld().breakBlock(
                context.getAbsolutePos(GameTestCircuitBuilder.SOLAR_POS), false);
        });

        context.runAtTick(70, () -> {
            logBus(context, "far-alone");
            ChargeControllerBlockEntity mppt = GameTestCircuitBuilder.getMppt(context);
            var load = GameTestCircuitBuilder.getLoad(context);
            context.assertTrue(mppt != null && load != null, "MPPT and load must exist after tight removal");
            context.assertFalse(mppt.isTripped(), "MPPT must not trip when only the far panel remains");
            context.assertTrue(mppt.getUpstreamAvailableSolarWatts() > 300.0,
                "BUG: MPPT reports ~0W after the tight panel is gone, far parallel panel ignored: available="
                    + mppt.getUpstreamAvailableSolarWatts() + "W, expected ~400W from the remaining far panel");
            context.assertTrue(mppt.getInputVoltage() > 20.0,
                "Far panel must hold the input rail (>20V) after tight removal, got: " + mppt.getInputVoltage());
            context.assertTrue(mppt.getOutputPowerWatts() > 50.0,
                "MPPT must keep charging/feeding from the far panel alone, got: " + mppt.getOutputPowerWatts());
            context.assertTrue(load.getLastDeliveredPower() > 150.0,
                "200W load must stay fed by the far panel alone, got: " + load.getLastDeliveredPower());
            context.complete();
        });
    }
}
