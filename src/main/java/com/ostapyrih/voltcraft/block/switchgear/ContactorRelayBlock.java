package com.ostapyrih.voltcraft.block.switchgear;

import com.ostapyrih.voltcraft.api.grid.IElectricalConnectable;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.simulation.grid.GridManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.block.WireOrientation;

/**
 * 100A redstone-driven electromagnetic power contactor relay.
 * Applying redstone power energizes the coil and closes the contact.
 */
public class ContactorRelayBlock extends Block implements IElectricalConnectable {

    public static final BooleanProperty POWERED = Properties.POWERED;
    public static final BooleanProperty CLOSED = BooleanProperty.of("closed");

    private static final VoxelShape SHAPE = Block.createCuboidShape(2.0, 0.0, 2.0, 14.0, 12.0, 14.0);

    public ContactorRelayBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState()
            .with(POWERED, false)
            .with(CLOSED, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(POWERED, CLOSED);
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.fullCube();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE;
    }

    @Override
    public boolean canConnect(BlockView world, BlockPos pos, Direction side, BlockState state) {
        return state.get(CLOSED);
    }

    @Override
    protected void neighborUpdate(
        BlockState state,
        World world,
        BlockPos pos,
        Block sourceBlock,
        WireOrientation wireOrientation,
        boolean notify
    ) {
        super.neighborUpdate(state, world, pos, sourceBlock, wireOrientation, notify);
        boolean hasPower = world.isReceivingRedstonePower(pos);
        if (state.get(POWERED) != hasPower) {
            world.setBlockState(pos, state.with(POWERED, hasPower).with(CLOSED, hasPower), Block.NOTIFY_ALL);
            world.playSound(
                null,
                pos,
                hasPower ? SoundEvents.BLOCK_PISTON_EXTEND : SoundEvents.BLOCK_PISTON_CONTRACT,
                SoundCategory.BLOCKS,
                0.8f,
                1.4f
            );

            if (!world.isClient()) {
                ServerWorld serverWorld = (ServerWorld) world;
                if (hasPower) {
                    GridManager.get(serverWorld).onConductorPlaced(serverWorld, pos, ConductorType.HEAVY_COPPER);
                } else {
                    GridManager.get(serverWorld).onConductorRemoved(serverWorld, pos);
                }
            }
        }
    }

    @Override
    protected void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        if (!world.isClient() && !state.isOf(oldState.getBlock()) && state.get(CLOSED)) {
            GridManager.get((ServerWorld) world).onConductorPlaced((ServerWorld) world, pos, ConductorType.HEAVY_COPPER);
        }
    }

    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos, boolean moved) {
        if (!state.isOf(world.getBlockState(pos).getBlock()) && state.get(CLOSED)) {
            GridManager.get(world).onConductorRemoved(world, pos);
        }
        super.onStateReplaced(state, world, pos, moved);
    }
}
