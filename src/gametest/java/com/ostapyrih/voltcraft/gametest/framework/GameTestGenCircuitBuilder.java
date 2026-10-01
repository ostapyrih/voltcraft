package com.ostapyrih.voltcraft.gametest.framework;

import com.ostapyrih.voltcraft.block.VoltcraftBlocks;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.block.conversion.EuConverterBlock;
import com.ostapyrih.voltcraft.block.creative.CreativeLoadBlock;
import com.ostapyrih.voltcraft.block.entity.conversion.EuConverterBlockEntity;
import com.ostapyrih.voltcraft.block.entity.creative.CreativeLoadBlockEntity;
import com.ostapyrih.voltcraft.block.entity.generation.PortableGeneratorBlockEntity;
import com.ostapyrih.voltcraft.block.generation.PortableGeneratorBlock;
import com.ostapyrih.voltcraft.simulation.creative.CreativeLoadLogic;
import com.ostapyrih.voltcraft.simulation.grid.GridManager;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Reusable test circuit builder for generator GameTests.
 *
 * <p>Layout inside the 8x8x8 boundary (y=1 plane):
 * <pre>
 *   GEN (2,1,1) NORTH terminals: (-) (2,1,0), (+) (2,1,2)
 *   EU  (2,1,3) SOUTH: In+ (2,1,2, shared node with GEN+), In- (3,1,3),
 *                       Out+ (2,1,4), Out- (1,1,3)
 *   LOAD  (5,1,6) NORTH behind the EU output bus (dark by design: the EU
 *         output pair is reserved open, energy leaves via the TR API)
 *   LOAD2 (0,1,1) NORTH, direct across the west rails: (-) (0,1,0), (+) (0,1,2)
 *   LOAD3 (1,1,1) NORTH, second direct branch mid-rail: (-) (1,1,0), (+) (1,1,2)
 * </pre>
 *
 * <p>West rails carry the direct branches: (+) row z=2 from the GEN+ tap (2,1,2)
 * through (1,1,2) to (0,1,2); (-) row z=0 from the GEN- tap (2,1,0) through
 * (1,1,0) to (0,1,0). Both loads sit between their terminal cables, so no block
 * ever shares a position with a cable. The direct branches stay west of x=2:
 * the EU input-minus route runs at x=3, so an east-side direct rail would join
 * it into a near-short loop across the generator.
 */
public class GameTestGenCircuitBuilder {

    public static final BlockPos GEN_POS = new BlockPos(2, 1, 1);
    public static final BlockPos EU_POS = new BlockPos(2, 1, 3);
    public static final BlockPos LOAD_POS = new BlockPos(5, 1, 6);
    public static final BlockPos LOAD2_POS = new BlockPos(0, 1, 1);
    public static final BlockPos LOAD3_POS = new BlockPos(1, 1, 1);

    // Key cable positions for fault injection:
    public static final BlockPos EU_IN_MINUS_TAP = new BlockPos(3, 1, 3);
    public static final BlockPos EU_OUT_PLUS = new BlockPos(2, 1, 4);
    public static final BlockPos LOAD2_PLUS_CABLE = new BlockPos(0, 1, 2);
    public static final BlockPos DIRECT_PLUS_MID = new BlockPos(1, 1, 2);

    public static void buildPlant(TestContext context) {
        ServerWorld world = context.getWorld();
        // Lay stone floor at Y=0, clear the 8x8x8 volume.
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                context.setBlockState(new BlockPos(x, 0, z), Blocks.SMOOTH_STONE);
                for (int y = 1; y < 8; y++) {
                    context.setBlockState(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }

        // 1. Portable generator at (2,1,1) facing NORTH (fuel added by each test).
        context.setBlockState(GEN_POS, VoltcraftBlocks.GENERATOR_PORTABLE_INVERTER.getDefaultState()
            .with(PortableGeneratorBlock.FACING, Direction.NORTH));

        // 2. EU converter at (2,1,3) facing SOUTH (input from the north side).
        context.setBlockState(EU_POS, VoltcraftBlocks.CONVERTER_EU.getDefaultState()
            .with(EuConverterBlock.FACING, Direction.SOUTH));

        // 3. Creative load behind the EU output at (5,1,6) facing NORTH.
        context.setBlockState(LOAD_POS, VoltcraftBlocks.CREATIVE_LOAD.getDefaultState()
            .with(CreativeLoadBlock.FACING, Direction.NORTH));

        // 4. Direct load at the west rail end, (0,1,1) facing NORTH:
        // terminals (-) (0,1,0) and (+) (0,1,2) sit on the rail cables.
        context.setBlockState(LOAD2_POS, VoltcraftBlocks.CREATIVE_LOAD.getDefaultState()
            .with(CreativeLoadBlock.FACING, Direction.NORTH));

        // 5. Second direct load mid-rail at (1,1,1) facing NORTH:
        // terminals (-) (1,1,0) and (+) (1,1,2) sit on the rail cables.
        context.setBlockState(LOAD3_POS, VoltcraftBlocks.CREATIVE_LOAD.getDefaultState()
            .with(CreativeLoadBlock.FACING, Direction.NORTH));

        // Generator (-) -> EU In(-): (2,1,0) -> (3,1,0) -> (4,1,0) -> (4,1,1)
        // -> (4,1,2) -> (4,1,3) -> (3,1,3). (Generator (+) meets EU In(+) at the
        // shared terminal node (2,1,2): no cable needed there.)
        setCable(context, new BlockPos(2, 1, 0), ConductorType.INSULATED_COPPER);
        setCable(context, new BlockPos(3, 1, 0), ConductorType.INSULATED_COPPER);
        setCable(context, new BlockPos(4, 1, 0), ConductorType.INSULATED_COPPER);
        setCable(context, new BlockPos(4, 1, 1), ConductorType.INSULATED_COPPER);
        setCable(context, new BlockPos(4, 1, 2), ConductorType.INSULATED_COPPER);
        setCable(context, new BlockPos(4, 1, 3), ConductorType.INSULATED_COPPER);
        setCable(context, EU_IN_MINUS_TAP, ConductorType.INSULATED_COPPER);

        // West rails for the direct branches. (+) row z=2 leaves the GEN+ tap
        // (2,1,2) westward; (-) row z=0 leaves the GEN- tap (2,1,0) westward.
        // Both rails stay west of x=2 so they can never join the EU
        // input-minus route at x=3 into a loop across the generator.
        setCable(context, new BlockPos(2, 1, 2), ConductorType.INSULATED_COPPER);
        setCable(context, new BlockPos(1, 1, 2), ConductorType.INSULATED_COPPER);
        setCable(context, new BlockPos(0, 1, 2), ConductorType.INSULATED_COPPER);
        setCable(context, new BlockPos(1, 1, 0), ConductorType.INSULATED_COPPER);
        setCable(context, new BlockPos(0, 1, 0), ConductorType.INSULATED_COPPER);

        // EU Out(-) -> load row (-): (1,1,3) -> (1,1,4) -> (1,1,5) -> (2..5,1,5).
        setCable(context, new BlockPos(1, 1, 3), ConductorType.HEAVY_COPPER);
        setCable(context, new BlockPos(1, 1, 4), ConductorType.HEAVY_COPPER);
        setCable(context, new BlockPos(1, 1, 5), ConductorType.HEAVY_COPPER);
        for (int x = 2; x <= 5; x++) {
            setCable(context, new BlockPos(x, 1, 5), ConductorType.HEAVY_COPPER);
        }

        // EU Out(+) -> load row (+): (2,1,4) -> (0,1,4) -> (0,1,5..7) -> (1..5,1,7).
        setCable(context, EU_OUT_PLUS, ConductorType.HEAVY_COPPER);
        setCable(context, new BlockPos(0, 1, 4), ConductorType.HEAVY_COPPER);
        for (int z = 5; z <= 7; z++) {
            setCable(context, new BlockPos(0, 1, z), ConductorType.HEAVY_COPPER);
        }
        for (int x = 1; x <= 5; x++) {
            setCable(context, new BlockPos(x, 1, 7), ConductorType.HEAVY_COPPER);
        }

        // All loads start disabled with 0W target; each test enables its own.
        // (Fresh CreativeLoad defaults to enabled 10-ohm resistive, which would
        // overload the generator bus before any test configures it.)
        setLoadWatts(context, LOAD_POS, 0.0);
        setLoadWatts(context, LOAD2_POS, 0.0);
        setLoadWatts(context, LOAD3_POS, 0.0);

        // Explicit grid registration (placement hooks are not guaranteed to fire
        // inside the gametest world before the first island rebuild).
        GridManager grids = GridManager.get(world);
        registerAttached(context, grids, GEN_POS);
        registerAttached(context, grids, EU_POS);
        registerAttached(context, grids, LOAD_POS);
        registerAttached(context, grids, LOAD2_POS);
        registerAttached(context, grids, LOAD3_POS);
        grids.rebuildIslands();
    }

    private static void registerAttached(TestContext context, GridManager grids, BlockPos localPos) {
        var be = context.getWorld().getBlockEntity(context.getAbsolutePos(localPos));
        if (be instanceof com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock kab) {
            grids.putAttachedBlock(kab);
        }
    }

    public static void setCable(TestContext context, BlockPos localPos, ConductorType type) {
        Block visual = type == ConductorType.INSULATED_COPPER
            ? VoltcraftBlocks.CABLE_COPPER_INSULATED
            : VoltcraftBlocks.CABLE_COPPER_HEAVY;
        context.setBlockState(localPos, visual);
        GridManager.get(context.getWorld()).onConductorPlaced(context.getWorld(), context.getAbsolutePos(localPos), type);
    }

    public static void breakCable(TestContext context, BlockPos localPos) {
        context.setBlockState(localPos, Blocks.AIR);
        GridManager.get(context.getWorld()).removeCable(context.getAbsolutePos(localPos));
    }

    public static void restoreCable(TestContext context, BlockPos localPos, ConductorType type) {
        setCable(context, localPos, type);
    }

    public static void setLoadWatts(TestContext context, BlockPos localPos, double watts) {
        var be = context.getWorld().getBlockEntity(context.getAbsolutePos(localPos));
        if (be instanceof CreativeLoadBlockEntity load) {
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

    public static void addFuel(TestContext context, int ticks) {
        var be = context.getWorld().getBlockEntity(context.getAbsolutePos(GEN_POS));
        if (be instanceof PortableGeneratorBlockEntity gen) {
            gen.addFuel(ticks);
        }
    }

    public static PortableGeneratorBlockEntity getGen(TestContext context) {
        return context.getWorld().getBlockEntity(context.getAbsolutePos(GEN_POS)) instanceof PortableGeneratorBlockEntity gen ? gen : null;
    }

    public static EuConverterBlockEntity getEu(TestContext context) {
        return context.getWorld().getBlockEntity(context.getAbsolutePos(EU_POS)) instanceof EuConverterBlockEntity eu ? eu : null;
    }

    public static CreativeLoadBlockEntity getLoad(TestContext context) {
        return context.getWorld().getBlockEntity(context.getAbsolutePos(LOAD_POS)) instanceof CreativeLoadBlockEntity load ? load : null;
    }

    public static CreativeLoadBlockEntity getLoad2(TestContext context) {
        return context.getWorld().getBlockEntity(context.getAbsolutePos(LOAD2_POS)) instanceof CreativeLoadBlockEntity load ? load : null;
    }

    public static CreativeLoadBlockEntity getLoad3(TestContext context) {
        return context.getWorld().getBlockEntity(context.getAbsolutePos(LOAD3_POS)) instanceof CreativeLoadBlockEntity load ? load : null;
    }
}
