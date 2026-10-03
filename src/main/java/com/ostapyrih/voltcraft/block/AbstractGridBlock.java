package com.ostapyrih.voltcraft.block;

import com.ostapyrih.voltcraft.api.grid.IElectricalConnectable;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.simulation.grid.GridManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;

/**
 * Base for every block that participates in the electrical graph (panels, batteries,
 * converters, cables, switchgear). Handles grid registration lifecycle once so subclasses
 * only describe shape, connection sides, and conductor type.
 *
 * <p>Extends plain {@link Block} (not {@code BlockWithEntity}) so passive blocks without
 * block entities (cables, busbars, switches) need no codec or entity boilerplate.
 * Blocks with entities implement {@link BlockEntityProvider} via this base, which
 * returns {@code null} by default.
 */
public abstract class AbstractGridBlock extends Block implements IElectricalConnectable, BlockEntityProvider {

    protected AbstractGridBlock(Settings settings) {
        super(settings);
    }

    /** Default: connect on all sides. Override to restrict (e.g. solar panels exclude UP). */
    @Override
    public boolean canConnect(BlockView world, BlockPos pos, Direction side, BlockState state) {
        return true;
    }

    /** Default: endpoint, not a pass-through. Override {@code true} for cables and busbars. */
    @Override
    public boolean isThroughConductor() {
        return false;
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.fullCube();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.fullCube();
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return null;
    }

    @Override
    protected void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        if (!world.isClient() && !state.isOf(oldState.getBlock()) && shouldSeedNode(state)) {
            GridManager.get((ServerWorld) world).onConductorPlaced(
                (ServerWorld) world, pos, getPlacementConductorType());
        }
        if (!world.isClient() && world instanceof ServerWorld sw) {
            sw.scheduleBlockTick(pos, this, 1);
            BlockEntity be = sw.getBlockEntity(pos);
            if (be instanceof KernelAttachedBlock kab) {
                GridManager.get(sw).putAttachedBlock(kab);
            }
        }
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);
        if (!world.isClient() && world instanceof ServerWorld sw) {
            BlockEntity be = sw.getBlockEntity(pos);
            if (be instanceof KernelAttachedBlock kab) {
                GridManager.get(sw).putAttachedBlock(kab);
            }
        }
    }

    @Override
    protected void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        super.scheduledTick(state, world, pos, random);
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof KernelAttachedBlock kab) {
            GridManager.get(world).putAttachedBlock(kab);
        }
    }

    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos, boolean moved) {
        if (!state.isOf(world.getBlockState(pos).getBlock()) && shouldSeedNode(state)) {
            GridManager.get(world).onConductorRemoved(world, pos);
        }
        // Item 1: unconditional detach; safe no-op when no BE was registered.
        GridManager.get(world).removeAttachedBlock(pos);
        super.onStateReplaced(state, world, pos, moved);
    }

    /**
     * Whether this state holds a grid node. Only passive conductors (cables, busbars,
     * junction boxes) and closed switchgear hold a node at pos. Devices with attached block
     * entities (batteries, generators, loads, converters) communicate solely via their
     * external terminals; seeding a node at their center would short their own terminals.
     */
    protected boolean shouldSeedNode(BlockState state) {
        return false;
    }

    /**
     * Conductor type used when a cable block reports its placement to the island index.
     * Only the thermal spec and ampacity matter for a block's own node; the graph edges
     * between adjacent blocks are derived from adjacency at island rebuild.
     * Override to use a heavier gauge.
     */
    protected ConductorType getPlacementConductorType() {
        return ConductorType.INSULATED_COPPER;
    }
}
