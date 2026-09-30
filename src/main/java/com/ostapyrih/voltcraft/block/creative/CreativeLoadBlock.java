package com.ostapyrih.voltcraft.block.creative;

import com.ostapyrih.voltcraft.block.AbstractGridBlock;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.block.entity.creative.CreativeLoadBlockEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Creative-only electrical load block.
 * Connects directly to the electrical grid, drawing configurable resistance, power, or current.
 * Right-click opens the interactive telemetry and configuration dashboard.
 */
public class CreativeLoadBlock extends AbstractGridBlock {

    public static final EnumProperty<Direction> FACING = Properties.HORIZONTAL_FACING;

    public CreativeLoadBlock(Settings settings) {
        super(settings);
        setDefaultState(this.stateManager.getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    public boolean canConnect(BlockView world, BlockPos pos, Direction side, BlockState state) {
        return true;
    }

    /**
     * Creative test loads join adjacent cable grids in either placement order, so
     * they stay through-conductors (as before the consolidation).
     */
    @Override
    public boolean isThroughConductor() {
        return true;
    }

    @Override
    protected ConductorType getPlacementConductorType() {
        return ConductorType.HEAVY_COPPER;
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new CreativeLoadBlockEntity(pos, state);
    }

    // No ticker: no per-block tick work. Grid participation is discovered
    // centrally by ElectricalGrid.refreshParticipants.

    @Override
    protected ActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!world.isClient()) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof CreativeLoadBlockEntity load) {
                // Redstone items toggle load ON/OFF
                if (stack.isOf(Items.REDSTONE) || stack.isOf(Items.LEVER) ||
                    stack.isOf(Items.REDSTONE_TORCH) || stack.isOf(Items.REDSTONE_BLOCK)) {
                    load.toggleEnabled();
                    player.sendMessage(Text.literal(String.format("[Creative Load] Electrical Load: %s",
                        load.isEnabled() ? "ENABLED (ON)" : "DISABLED (OFF)"))
                        .formatted(load.isEnabled() ? Formatting.GREEN : Formatting.RED), true);
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
            if (be instanceof CreativeLoadBlockEntity load) {
                player.openHandledScreen(load);
            }
        }
        return ActionResult.SUCCESS;
    }
}
