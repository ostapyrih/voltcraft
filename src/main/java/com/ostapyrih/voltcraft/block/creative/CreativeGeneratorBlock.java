package com.ostapyrih.voltcraft.block.creative;

import com.ostapyrih.voltcraft.api.grid.IElectricalConnectable;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.block.entity.creative.CreativeGeneratorBlockEntity;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalGrid;
import com.ostapyrih.voltcraft.simulation.grid.GridManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Creative-only power generator block.
 * Connects directly to the electrical grid, supplying an adjustable EMF and current capacity.
 * Right-click opens the interactive telemetry and configuration dashboard.
 */
public class CreativeGeneratorBlock extends Block implements BlockEntityProvider, IElectricalConnectable {

    public CreativeGeneratorBlock(Settings settings) {
        super(settings);
    }

    @Override
    public boolean canConnect(BlockView world, BlockPos pos, Direction side, BlockState state) {
        return true;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.fullCube();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.fullCube();
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new CreativeGeneratorBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return world.isClient() ? null : (w, p, s, be) -> {
            if (be instanceof CreativeGeneratorBlockEntity gen) {
                gen.tick((ServerWorld) w);
            }
        };
    }

    @Override
    protected void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        if (!world.isClient() && !state.isOf(oldState.getBlock())) {
            GridManager.get((ServerWorld) world).onConductorPlaced((ServerWorld) world, pos, ConductorType.HEAVY_COPPER);
            ElectricalGrid grid = GridManager.get((ServerWorld) world).getGridAt(pos);
            if (grid != null) {
                BlockEntity be = world.getBlockEntity(pos);
                if (be instanceof CreativeGeneratorBlockEntity gen) {
                    grid.registerSource(pos, gen);
                }
            }
        }
    }

    @Override
    protected ActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!world.isClient()) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof CreativeGeneratorBlockEntity gen) {
                // Redstone items toggle output ON/OFF
                if (stack.isOf(Items.REDSTONE) || stack.isOf(Items.LEVER) ||
                    stack.isOf(Items.REDSTONE_TORCH) || stack.isOf(Items.REDSTONE_BLOCK)) {
                    gen.toggleEnabled();
                    player.sendMessage(Text.literal(String.format("[Creative Generator] Power Output: %s",
                        gen.isEnabled() ? "ENABLED (ON)" : "DISABLED (OFF)"))
                        .formatted(gen.isEnabled() ? Formatting.GREEN : Formatting.RED), true);
                    return ActionResult.SUCCESS;
                }
            }
        }
        return super.onUseWithItem(stack, state, world, pos, player, hand, hit);
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (!world.isClient()) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof CreativeGeneratorBlockEntity gen) {
                player.openHandledScreen(gen);
            }
        }
        return ActionResult.SUCCESS;
    }

    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos, boolean moved) {
        if (!state.isOf(world.getBlockState(pos).getBlock())) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof CreativeGeneratorBlockEntity gen) {
                gen.onRemovedFromWorld();
            }
            ElectricalGrid grid = GridManager.get(world).getGridAt(pos);
            if (grid != null) {
                grid.unregisterSource(pos);
            }
            GridManager.get(world).onConductorRemoved(world, pos);
        }
        super.onStateReplaced(state, world, pos, moved);
    }
}
