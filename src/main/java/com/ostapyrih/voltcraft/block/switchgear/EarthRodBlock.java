package com.ostapyrih.voltcraft.block.switchgear;

import com.ostapyrih.voltcraft.block.AbstractGridBlock;
import com.ostapyrih.voltcraft.block.entity.switchgear.EarthBlockEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;
import org.jetbrains.annotations.Nullable;

/**
 * Driven protective-earth (ground reference) rod.
 *
 * <p>Host block for {@link EarthBlockEntity}: the kernel adapter declares its single
 * terminal straight down ({@code pos.down()}), so the rod must sit on conductive
 * ground with free space below for the cable connection. No per-wire ticking;
 * the ticker only forwards the (no-op) discrete earth tick on the logical server.</p>
 */
public class EarthRodBlock extends AbstractGridBlock {

    private static final VoxelShape ROD_SHAPE = Block.createCuboidShape(6.0, 0.0, 6.0, 10.0, 16.0, 10.0);

    public EarthRodBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return ROD_SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return ROD_SHAPE;
    }

    @Override
    public boolean canConnect(BlockView world, BlockPos pos, Direction side, BlockState state) {
        return true;
    }

    /**
     * The rod only drives into natural ground: dirt/grass families, stone,
     * or deepslate. Checked against the block below the placement position.
     */
    @Override
    public boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        BlockState below = world.getBlockState(pos.down());
        return below.isIn(BlockTags.DIRT)
            || below.isIn(BlockTags.STONE_ORE_REPLACEABLES)
            || below.isOf(Blocks.STONE)
            || below.isOf(Blocks.DEEPSLATE)
            || below.isOf(Blocks.DIRT)
            || below.isOf(Blocks.GRASS_BLOCK);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockState def = this.getDefaultState();
        if (!this.canPlaceAt(def, ctx.getWorld(), ctx.getBlockPos())) {
            return null;
        }
        return def;
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new EarthBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (world.isClient()) return null;
        return (w, pos, st, be) -> {
            if (be instanceof EarthBlockEntity earth && w instanceof ServerWorld sw) {
                earth.tickElectrical(sw);
            }
        };
    }
}
