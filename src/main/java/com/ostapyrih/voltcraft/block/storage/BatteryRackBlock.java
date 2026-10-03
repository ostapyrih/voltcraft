package com.ostapyrih.voltcraft.block.storage;

import com.ostapyrih.voltcraft.block.AbstractGridBlock;
import com.ostapyrih.voltcraft.block.VoltcraftBlocks;
import com.ostapyrih.voltcraft.block.cable.CableBlock;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.block.entity.storage.BatteryRackBlockEntity;
import com.ostapyrih.voltcraft.item.battery.BatteryCellItem;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Modular Battery Rack Block.
 * Contains 16 internal bays for inserting and extracting individual portable battery cells
 * (18650 Li-Ion, 21700 Li-Ion, NiMH, NiCd, etc.).
 * Supports reconfigurable Series or Parallel internal busbar topology.
 */
public class BatteryRackBlock extends AbstractGridBlock {

    public static final EnumProperty<Direction> FACING = Properties.HORIZONTAL_FACING;

    public BatteryRackBlock(Settings settings) {
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
        Direction facing = state.get(FACING);
        return side == facing || side == facing.getOpposite();
    }

    @Override
    public boolean isThroughConductor() {
        return false;
    }

    @Override
    protected ConductorType getPlacementConductorType() {
        return ConductorType.HEAVY_COPPER;
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new BatteryRackBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return world.isClient() ? null : (w, p, s, be) -> {
            if (be instanceof BatteryRackBlockEntity rack) {
                rack.tick((ServerWorld) w);
            }
        };
    }

    @Override
    protected ActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!world.isClient()) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof BatteryRackBlockEntity rack) {
                // Reconfigure busbars between Series and Parallel using Copper Busbar or Cables
                if (stack.isOf(VoltcraftBlocks.COPPER_BUSBAR.asItem()) ||
                    (stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof CableBlock)) {
                    rack.toggleWiringMode();
                    player.sendMessage(Text.literal("Reconfigured Internal Busbars to: " + rack.getWiringMode().name() + " Mode").formatted(Formatting.AQUA), true);
                    return ActionResult.SUCCESS;
                }

                if (stack.getItem() instanceof BatteryCellItem) {
                    // Try to insert cell into first empty slot
                    for (int i = 0; i < rack.size(); i++) {
                        if (rack.getStack(i).isEmpty()) {
                            ItemStack inserted = stack.split(1);
                            rack.setStack(i, inserted);
                            player.sendMessage(Text.literal(String.format("Slotted %s into Bay %d/16", inserted.getName().getString(), i + 1)).formatted(Formatting.GREEN), true);
                            return ActionResult.SUCCESS;
                        }
                    }
                    player.sendMessage(Text.literal("Battery Rack is full (16/16 bays occupied)!").formatted(Formatting.RED), true);
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
            if (be instanceof BatteryRackBlockEntity rack) {
                // Sneak-click with empty hand extracts the last cell
                if (player.isSneaking() && player.getMainHandStack().isEmpty()) {
                    for (int i = rack.size() - 1; i >= 0; i--) {
                        ItemStack slotted = rack.getStack(i);
                        if (!slotted.isEmpty()) {
                            ItemStack removed = rack.removeStack(i);
                            player.giveItemStack(removed);
                            player.sendMessage(Text.literal(String.format("Extracted %s from Bay %d/16", removed.getName().getString(), i + 1)).formatted(Formatting.YELLOW), true);
                            return ActionResult.SUCCESS;
                        }
                    }
                    player.sendMessage(Text.literal("Battery Rack is empty!").formatted(Formatting.GRAY), true);
                    return ActionResult.SUCCESS;
                }

                // Regular right click displays rack telemetry
                int count = rack.getSlottedCellCount();
                double soc = rack.getStateOfCharge() * 100.0;
                double v = rack.getElectromotiveForce();
                double maxJ = rack.getMaxStorageJoules();
                double avgT = rack.getAverageTemperatureCelsius();
                double maxT = rack.getMaxCellTemperatureCelsius();

                Formatting tempColor = maxT > 70.0 ? Formatting.RED : (maxT > 45.0 ? Formatting.YELLOW : Formatting.GREEN);

                player.sendMessage(Text.literal("--- Modular Battery Rack (16 Bays) ---").formatted(Formatting.GOLD), false);
                player.sendMessage(Text.literal(String.format("Occupied Bays: %d / 16", count)).formatted(Formatting.WHITE), false);
                player.sendMessage(Text.literal(String.format("Wiring Configuration: %s (Click with Copper Busbar to toggle)", rack.getWiringMode().name())).formatted(Formatting.YELLOW), false);
                player.sendMessage(Text.literal(String.format("Rack EMF: %.2fV", v)).formatted(Formatting.AQUA), false);
                player.sendMessage(Text.literal(String.format("Average SoC: %.1f%%", soc)).formatted(Formatting.GREEN), false);
                player.sendMessage(Text.literal(String.format("Temperature: %.1f°C (Max: %.1f°C)", avgT, maxT)).formatted(tempColor), false);
                player.sendMessage(Text.literal(String.format("Capacity: %.1f kJ (%.2f Wh)", maxJ / 1000.0, maxJ / 3600.0)).formatted(Formatting.YELLOW), false);
                player.sendMessage(Text.literal("Sneak + Right-Click with empty hand to extract cells.").formatted(Formatting.GRAY, Formatting.ITALIC), false);
            }
        }
        return ActionResult.SUCCESS;
    }

    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos, boolean moved) {
        if (!state.isOf(world.getBlockState(pos).getBlock())) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof BatteryRackBlockEntity rack) {
                ItemScatterer.spawn(world, pos, rack);
            }
        }
        super.onStateReplaced(state, world, pos, moved);
    }
}
