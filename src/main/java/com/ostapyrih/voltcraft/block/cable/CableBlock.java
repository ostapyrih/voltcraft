package com.ostapyrih.voltcraft.block.cable;

import com.ostapyrih.voltcraft.api.grid.IElectricalConnectable;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalGrid;
import com.ostapyrih.voltcraft.simulation.grid.GridManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.Waterloggable;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCollisionHandler;
import net.minecraft.entity.LivingEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;
import net.minecraft.world.tick.ScheduledTickView;

/**
 * Passive topological electrical cable block.
 * Strict No-Wire-Ticking Law: Zero BlockEntity or per-block ticking.
 * Graph operations (admittance, Joule heating, failures) are executed centrally by ElectricalGrid.
 */
public class CableBlock extends Block implements Waterloggable, IElectricalConnectable {

    public static final BooleanProperty NORTH = Properties.NORTH;
    public static final BooleanProperty SOUTH = Properties.SOUTH;
    public static final BooleanProperty EAST = Properties.EAST;
    public static final BooleanProperty WEST = Properties.WEST;
    public static final BooleanProperty UP = Properties.UP;
    public static final BooleanProperty DOWN = Properties.DOWN;
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;

    private static final VoxelShape CORE_SHAPE = Block.createCuboidShape(5.5, 5.5, 5.5, 10.5, 10.5, 10.5);
    private static final VoxelShape UP_SHAPE = Block.createCuboidShape(5.5, 10.5, 5.5, 10.5, 16.0, 10.5);
    private static final VoxelShape DOWN_SHAPE = Block.createCuboidShape(5.5, 0.0, 5.5, 10.5, 5.5, 10.5);
    private static final VoxelShape NORTH_SHAPE = Block.createCuboidShape(5.5, 5.5, 0.0, 10.5, 10.5, 5.5);
    private static final VoxelShape SOUTH_SHAPE = Block.createCuboidShape(5.5, 5.5, 10.5, 10.5, 10.5, 16.0);
    private static final VoxelShape WEST_SHAPE = Block.createCuboidShape(0.0, 5.5, 5.5, 5.5, 10.5, 10.5);
    private static final VoxelShape EAST_SHAPE = Block.createCuboidShape(10.5, 5.5, 5.5, 16.0, 10.5, 10.5);

    private static final VoxelShape[] SHAPE_CACHE = new VoxelShape[64];

    static {
        for (int i = 0; i < 64; i++) {
            VoxelShape shape = CORE_SHAPE;
            if ((i & 1) != 0) shape = VoxelShapes.union(shape, UP_SHAPE);
            if ((i & 2) != 0) shape = VoxelShapes.union(shape, DOWN_SHAPE);
            if ((i & 4) != 0) shape = VoxelShapes.union(shape, NORTH_SHAPE);
            if ((i & 8) != 0) shape = VoxelShapes.union(shape, SOUTH_SHAPE);
            if ((i & 16) != 0) shape = VoxelShapes.union(shape, WEST_SHAPE);
            if ((i & 32) != 0) shape = VoxelShapes.union(shape, EAST_SHAPE);
            SHAPE_CACHE[i] = shape;
        }
    }

    private final ConductorType conductorType;

    public CableBlock(ConductorType conductorType, Settings settings) {
        super(settings);
        this.conductorType = conductorType;
        this.setDefaultState(this.stateManager.getDefaultState()
            .with(NORTH, false)
            .with(SOUTH, false)
            .with(EAST, false)
            .with(WEST, false)
            .with(UP, false)
            .with(DOWN, false)
            .with(WATERLOGGED, false));
    }

    public ConductorType getConductorType() {
        return conductorType;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(NORTH, SOUTH, EAST, WEST, UP, DOWN, WATERLOGGED);
    }

    @Override
    public boolean canConnect(BlockView world, BlockPos pos, Direction side, BlockState state) {
        return true;
    }

    private boolean connectsTo(BlockView world, BlockPos pos, Direction side) {
        BlockPos neighborPos = pos.offset(side);
        BlockState neighborState = world.getBlockState(neighborPos);
        if (neighborState.getBlock() instanceof IElectricalConnectable connectable) {
            return connectable.canConnect(world, neighborPos, side.getOpposite(), neighborState);
        }
        return false;
    }

    private int getShapeIndex(BlockState state) {
        int idx = 0;
        if (state.get(UP)) idx |= 1;
        if (state.get(DOWN)) idx |= 2;
        if (state.get(NORTH)) idx |= 4;
        if (state.get(SOUTH)) idx |= 8;
        if (state.get(WEST)) idx |= 16;
        if (state.get(EAST)) idx |= 32;
        return idx;
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.fullCube();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE_CACHE[getShapeIndex(state)];
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        World world = ctx.getWorld();
        BlockPos pos = ctx.getBlockPos();
        FluidState fluid = world.getFluidState(pos);

        return this.getDefaultState()
            .with(UP, connectsTo(world, pos, Direction.UP))
            .with(DOWN, connectsTo(world, pos, Direction.DOWN))
            .with(NORTH, connectsTo(world, pos, Direction.NORTH))
            .with(SOUTH, connectsTo(world, pos, Direction.SOUTH))
            .with(WEST, connectsTo(world, pos, Direction.WEST))
            .with(EAST, connectsTo(world, pos, Direction.EAST))
            .with(WATERLOGGED, fluid.getFluid() == Fluids.WATER);
    }

    @Override
    protected BlockState getStateForNeighborUpdate(
        BlockState state,
        WorldView world,
        ScheduledTickView tickView,
        BlockPos pos,
        Direction direction,
        BlockPos neighborPos,
        BlockState neighborState,
        Random random
    ) {
        if (state.get(WATERLOGGED)) {
            tickView.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
        }

        return state
            .with(UP, connectsTo(world, pos, Direction.UP))
            .with(DOWN, connectsTo(world, pos, Direction.DOWN))
            .with(NORTH, connectsTo(world, pos, Direction.NORTH))
            .with(SOUTH, connectsTo(world, pos, Direction.SOUTH))
            .with(WEST, connectsTo(world, pos, Direction.WEST))
            .with(EAST, connectsTo(world, pos, Direction.EAST));
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.get(WATERLOGGED) ? Fluids.WATER.getStill(false) : super.getFluidState(state);
    }

    @Override
    protected void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        if (!world.isClient() && !state.isOf(oldState.getBlock())) {
            GridManager.get((ServerWorld) world).onConductorPlaced((ServerWorld) world, pos, conductorType);
        }
    }

    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos, boolean moved) {
        if (!state.isOf(world.getBlockState(pos).getBlock())) {
            GridManager.get(world).onConductorRemoved(world, pos);
        }
        super.onStateReplaced(state, world, pos, moved);
    }

    @Override
    protected void onEntityCollision(
        BlockState state,
        World world,
        BlockPos pos,
        Entity entity,
        EntityCollisionHandler handler,
        boolean bl
    ) {
        super.onEntityCollision(state, world, pos, entity, handler, bl);
        if (world.isClient() || conductorType.isInsulated() || !(entity instanceof LivingEntity living)) {
            return;
        }

        ServerWorld serverWorld = (ServerWorld) world;
        ElectricalGrid grid = GridManager.get(serverWorld).getGridAt(pos);
        if (grid != null) {
            double voltage = grid.getNodeVoltage(pos);
            if (voltage >= 50.0) {
                float damage = (float) Math.max(1.0, voltage / 25.0);
                living.damage(serverWorld, serverWorld.getDamageSources().lightningBolt(), damage);

                if (conductorType == ConductorType.STEEL_FENCE) {
                    double dx = living.getX() - (pos.getX() + 0.5);
                    double dz = living.getZ() - (pos.getZ() + 0.5);
                    living.takeKnockback(1.2, -dx, -dz);
                }
            }
        }
    }
}
