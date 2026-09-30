package com.ostapyrih.voltcraft.block.conversion;

import com.ostapyrih.voltcraft.block.AbstractGridBlock;
import com.ostapyrih.voltcraft.block.entity.conversion.AbstractPowerConverterBlockEntity;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Base block for all multi-port conversion hardware (DC-DC converters, AC transformers, rectifiers, inverters).
 * Directional: Back face is Electrical Input (Primary), Front face is Electrical Output (Secondary).
 */
public abstract class AbstractPowerConverterBlock extends AbstractGridBlock {

    public static final EnumProperty<Direction> FACING = Properties.HORIZONTAL_FACING;

    public AbstractPowerConverterBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return this.getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    public boolean canConnect(BlockView world, BlockPos pos, Direction side, BlockState state) {
        // Connects to all 4 horizontal ports (Input: Back+, Left-; Output: Front+, Right-)
        return side.getAxis().isHorizontal();
    }

    @Override
    public boolean isThroughConductor() {
        return false; // Multi-port converter isolated boundary: do not conduct through directly
    }

    /**
     * Converters never hold a grid node of their own: the input and output ports couple
     * two isolated networks through the endpoints discovered per-tick by
     * {@code ElectricalGrid.refreshParticipants}. Seeding a node here would link
     * conductors from the input side to the output side straight through the converter
     * position and short the isolation the conversion model depends on.
     */
    @Override
    protected boolean shouldSeedNode(BlockState state) {
        return false;
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (!world.isClient()) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof AbstractPowerConverterBlockEntity converter) {
                if (player.isSneaking() && converter.isTripped()) {
                    converter.resetTrip();
                    player.sendMessage(Text.literal("Protection trip reset. Converter re-armed.").formatted(Formatting.GREEN), false);
                    return ActionResult.SUCCESS;
                }
                player.openHandledScreen(converter);
            }
        }
        return ActionResult.SUCCESS;
    }

    public abstract String getConverterDisplayName();

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return world.isClient() ? null : (w, p, s, be) -> {
            if (be instanceof AbstractPowerConverterBlockEntity converter) {
                converter.tickElectrical((ServerWorld) w);
            }
        };
    }
}
