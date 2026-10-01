package com.ostapyrih.voltcraft.gametest.framework;

import com.ostapyrih.voltcraft.block.VoltcraftBlocks;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.block.conversion.AbstractPowerConverterBlock;
import com.ostapyrih.voltcraft.block.creative.CreativeLoadBlock;
import com.ostapyrih.voltcraft.block.entity.creative.CreativeLoadBlockEntity;
import com.ostapyrih.voltcraft.block.entity.generation.ChargeControllerBlockEntity;
import com.ostapyrih.voltcraft.block.entity.generation.SolarPanelBlockEntity;
import com.ostapyrih.voltcraft.block.entity.storage.BatteryBlockEntity;
import com.ostapyrih.voltcraft.block.generation.SolarPanelBlock;
import com.ostapyrih.voltcraft.block.storage.BatteryBlock;
import com.ostapyrih.voltcraft.simulation.creative.CreativeLoadLogic;
import com.ostapyrih.voltcraft.simulation.electrical.BatteryElement;
import com.ostapyrih.voltcraft.simulation.grid.GridManager;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Reusable test circuit builder for Fabric GameTests.
 * Constructs and manipulates a complete Solar -> MPPT -> Battery -> Load circuit
 * within an 8x8x8 GameTest structure boundary.
 */
public class GameTestCircuitBuilder {

    public static final BlockPos SOLAR_POS = new BlockPos(2, 1, 1);
    public static final BlockPos MPPT_POS = new BlockPos(2, 1, 3);
    public static final BlockPos BATTERY_POS = new BlockPos(2, 1, 6);
    public static final BlockPos LOAD_POS = new BlockPos(5, 1, 6);

    // Key cable positions for fault injection:
    public static final BlockPos SOLAR_PLUS_CABLE = new BlockPos(2, 1, 2);
    public static final BlockPos SOLAR_MINUS_CABLE = new BlockPos(4, 1, 2);
    public static final BlockPos MPPT_OUT_PLUS = new BlockPos(2, 1, 4);
    public static final BlockPos MPPT_OUT_MINUS = new BlockPos(1, 1, 3);
    public static final BlockPos BATTERY_PLUS_CABLE = new BlockPos(2, 1, 7);
    public static final BlockPos BATTERY_MINUS_CABLE = new BlockPos(2, 1, 5);
    public static final BlockPos LOAD_PLUS_CABLE = new BlockPos(5, 1, 7);
    public static final BlockPos LOAD_MINUS_CABLE = new BlockPos(5, 1, 5);
    public static final BlockPos BUS_MID_PLUS = new BlockPos(4, 1, 7);
    public static final BlockPos BUS_MID_MINUS = new BlockPos(4, 1, 5);

    public static void buildCircuit(TestContext context) {
        ServerWorld world = context.getWorld();
        world.setTimeOfDay(6000);
        world.setWeather(0, 100000, false, false);

        BlockPos absSolar = context.getAbsolutePos(SOLAR_POS);
        for (int y = absSolar.getY() + 1; y <= 320; y++) {
            world.setBlockState(new BlockPos(absSolar.getX(), y, absSolar.getZ()), Blocks.AIR.getDefaultState(), 2);
        }

        // Lay stone floor at Y=0
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                context.setBlockState(new BlockPos(x, 0, z), Blocks.SMOOTH_STONE);
                for (int y = 1; y < 8; y++) {
                    context.setBlockState(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }

        // 1. Solar Panel at (2, 1, 1) facing NORTH
        context.setBlockState(SOLAR_POS, VoltcraftBlocks.SOLAR_PANEL_MONOCRYSTALLINE.getDefaultState()
            .with(SolarPanelBlock.FACING, Direction.NORTH));

        // 2. MPPT at (2, 1, 3) facing SOUTH
        context.setBlockState(MPPT_POS, VoltcraftBlocks.CHARGE_CONTROLLER_MPPT.getDefaultState()
            .with(AbstractPowerConverterBlock.FACING, Direction.SOUTH));
        if (getMppt(context) != null) {
            getMppt(context).setTargetOutputVoltage(12.0);
        }

        // 3. Battery at (2, 1, 6) facing NORTH
        context.setBlockState(BATTERY_POS, VoltcraftBlocks.BATTERY_BLOCK_LEAD_ACID.getDefaultState()
            .with(BatteryBlock.FACING, Direction.NORTH));

        // 4. Creative Load at (5, 1, 6) facing NORTH
        context.setBlockState(LOAD_POS, VoltcraftBlocks.CREATIVE_LOAD.getDefaultState()
            .with(CreativeLoadBlock.FACING, Direction.NORTH));
        if (getLoad(context) != null) {
            getLoad(context).setMode(CreativeLoadLogic.LoadMode.CONSTANT_POWER);
            getLoad(context).setTargetValue(0.0);
            getLoad(context).setEnabled(false);
        }

        // Cables:
        // A. Solar(+) -> MPPT In(+) at (2, 1, 2)
        setCable(context, SOLAR_PLUS_CABLE, ConductorType.HEAVY_COPPER);

        // B. Solar(-) -> MPPT In(-) at (2, 1, 0) -> (3, 1, 0) -> (4, 1, 0) -> (4, 1, 1..3) -> (3, 1, 3)
        setCable(context, new BlockPos(2, 1, 0), ConductorType.HEAVY_COPPER);
        setCable(context, new BlockPos(3, 1, 0), ConductorType.HEAVY_COPPER);
        setCable(context, new BlockPos(4, 1, 0), ConductorType.HEAVY_COPPER);
        setCable(context, new BlockPos(4, 1, 1), ConductorType.HEAVY_COPPER);
        setCable(context, SOLAR_MINUS_CABLE, ConductorType.HEAVY_COPPER);
        setCable(context, new BlockPos(4, 1, 3), ConductorType.HEAVY_COPPER);
        setCable(context, new BlockPos(3, 1, 3), ConductorType.HEAVY_COPPER);

        // C. MPPT Out(-) -> Bat(-) and Load(-)
        // (1, 1, 3) -> (1, 1, 4) -> (1, 1, 5) -> (2..5, 1, 5)
        setCable(context, MPPT_OUT_MINUS, ConductorType.HEAVY_COPPER);
        setCable(context, new BlockPos(1, 1, 4), ConductorType.HEAVY_COPPER);
        setCable(context, new BlockPos(1, 1, 5), ConductorType.HEAVY_COPPER);
        for (int x = 2; x <= 5; x++) {
            setCable(context, new BlockPos(x, 1, 5), ConductorType.HEAVY_COPPER);
        }

        // D. MPPT Out(+) -> Bat(+) and Load(+)
        // (2, 1, 4) -> (0, 1, 4) -> (0, 1, 5..7) -> (1..5, 1, 7)
        setCable(context, MPPT_OUT_PLUS, ConductorType.HEAVY_COPPER);
        setCable(context, new BlockPos(0, 1, 4), ConductorType.HEAVY_COPPER);
        for (int z = 5; z <= 7; z++) {
            setCable(context, new BlockPos(0, 1, z), ConductorType.HEAVY_COPPER);
        }
        for (int x = 1; x <= 5; x++) {
            setCable(context, new BlockPos(x, 1, 7), ConductorType.HEAVY_COPPER);
        }

        GridManager grids = GridManager.get(context.getWorld());
        for (BlockPos p : new BlockPos[]{SOLAR_POS, MPPT_POS, BATTERY_POS, LOAD_POS}) {
            var be = context.getWorld().getBlockEntity(context.getAbsolutePos(p));
            if (be instanceof com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock kab) {
                grids.putAttachedBlock(kab);
            }
        }
        grids.rebuildIslands();
    }

    public static void setCable(TestContext context, BlockPos localPos, ConductorType type) {
        context.setBlockState(localPos, VoltcraftBlocks.CABLE_COPPER_HEAVY);
        GridManager.get(context.getWorld()).onConductorPlaced(context.getWorld(), context.getAbsolutePos(localPos), type);
    }

    public static void breakCable(TestContext context, BlockPos localPos) {
        context.setBlockState(localPos, Blocks.AIR);
        GridManager.get(context.getWorld()).removeCable(context.getAbsolutePos(localPos));
    }

    public static void restoreCable(TestContext context, BlockPos localPos) {
        setCable(context, localPos, ConductorType.HEAVY_COPPER);
    }

    public static void setBatterySoc(TestContext context, double soc) {
        BatteryBlockEntity bat = getBattery(context);
        if (bat != null) {
            double[] s = bat.getStateArray();
            s[BatteryElement.STATE_SOC] = Math.max(0.0, Math.min(1.0, soc));
            bat.setStateArray(s);
        }
    }

    public static void setLoad(TestContext context, double watts) {        CreativeLoadBlockEntity load = getLoad(context);
        if (load != null) {
            if (watts <= 0.0) {
                load.setEnabled(false);
                load.setTargetValue(0.0);
            } else {
                load.setMode(CreativeLoadLogic.LoadMode.CONSTANT_POWER);
                load.setTargetValue(watts);
                load.setEnabled(true);
            }
        }
    }

    public static SolarPanelBlockEntity getSolar(TestContext context) {
        return context.getWorld().getBlockEntity(context.getAbsolutePos(SOLAR_POS)) instanceof SolarPanelBlockEntity sp ? sp : null;
    }

    public static ChargeControllerBlockEntity getMppt(TestContext context) {
        return context.getWorld().getBlockEntity(context.getAbsolutePos(MPPT_POS)) instanceof ChargeControllerBlockEntity cc ? cc : null;
    }

    public static BatteryBlockEntity getBattery(TestContext context) {
        return context.getWorld().getBlockEntity(context.getAbsolutePos(BATTERY_POS)) instanceof BatteryBlockEntity bb ? bb : null;
    }

    public static CreativeLoadBlockEntity getLoad(TestContext context) {
        return context.getWorld().getBlockEntity(context.getAbsolutePos(LOAD_POS)) instanceof CreativeLoadBlockEntity cl ? cl : null;
    }
}
