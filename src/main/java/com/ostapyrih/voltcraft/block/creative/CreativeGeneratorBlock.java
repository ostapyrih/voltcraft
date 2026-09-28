package com.ostapyrih.voltcraft.block.creative;

import com.ostapyrih.voltcraft.block.AbstractGridBlock;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.block.entity.creative.CreativeGeneratorBlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
 * Creative-only power generator block.
 * Connects directly to the electrical grid, supplying an adjustable EMF and current capacity.
 * Right-click opens the interactive telemetry and configuration dashboard.
 */
public class CreativeGeneratorBlock extends AbstractGridBlock {

    public CreativeGeneratorBlock(Settings settings) {
        super(settings);
    }

    @Override
    public boolean canConnect(BlockView world, BlockPos pos, Direction side, BlockState state) {
        return true;
    }

    /**
     * Creative test sources join adjacent cable grids in either placement order, so
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
        return new CreativeGeneratorBlockEntity(pos, state);
    }

    // No ticker: no per-block tick work. Grid participation is discovered
    // centrally by ElectricalGrid.refreshParticipants.

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
}
