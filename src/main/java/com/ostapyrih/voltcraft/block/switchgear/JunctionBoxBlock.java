package com.ostapyrih.voltcraft.block.switchgear;

import com.ostapyrih.voltcraft.block.AbstractGridBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

/**
 * 6-sided internal terminal junction block that routes cables cleanly.
 */
public class JunctionBoxBlock extends AbstractGridBlock {

    private static final VoxelShape BOX_SHAPE = Block.createCuboidShape(2.0, 2.0, 2.0, 14.0, 14.0, 14.0);

    public JunctionBoxBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.fullCube();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return BOX_SHAPE;
    }

    @Override
    public boolean isThroughConductor() {
        return true;
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return null; // Passive junction: no block entity.
    }
}
