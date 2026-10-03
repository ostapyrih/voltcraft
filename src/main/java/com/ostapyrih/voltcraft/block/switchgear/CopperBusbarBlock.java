package com.ostapyrih.voltcraft.block.switchgear;

import com.ostapyrih.voltcraft.block.AbstractGridBlock;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

/**
 * High-ampacity (500A) low-impedance copper busbar block for distribution panels.
 */
public class CopperBusbarBlock extends AbstractGridBlock {

    public static final EnumProperty<Direction> FACING = Properties.HORIZONTAL_FACING;
    private static final VoxelShape SHAPE_NS = Block.createCuboidShape(6.0, 6.0, 0.0, 10.0, 10.0, 16.0);
    private static final VoxelShape SHAPE_EW = Block.createCuboidShape(0.0, 6.0, 6.0, 16.0, 10.0, 10.0);

    public CopperBusbarBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return this.getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing());
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.fullCube();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        Direction dir = state.get(FACING);
        return (dir == Direction.NORTH || dir == Direction.SOUTH) ? SHAPE_NS : SHAPE_EW;
    }

    @Override
    public boolean isThroughConductor() {
        return true;
    }

    @Override
    protected boolean shouldSeedNode(BlockState state) {
        return true;
    }

    @Override
    protected ConductorType getPlacementConductorType() {
        return ConductorType.HEAVY_COPPER;
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return null; // Passive busbar: no block entity.
    }
}
