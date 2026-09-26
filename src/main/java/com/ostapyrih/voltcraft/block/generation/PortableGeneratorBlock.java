package com.ostapyrih.voltcraft.block.generation;

import com.ostapyrih.voltcraft.api.grid.IElectricalConnectable;
import com.ostapyrih.voltcraft.block.entity.generation.PortableGeneratorBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.FuelRegistry;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsage;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * 1.8-2.2 kW Portable Inverter Generator block.
 * Consumes furnace fuels, runs with an eco-throttle load-adaptive 4-stroke engine model,
 * and supplies 230V 50Hz pure sine wave AC power from its front socket.
 */
public class PortableGeneratorBlock extends BlockWithEntity implements IElectricalConnectable {

    public static final EnumProperty<Direction> FACING = Properties.HORIZONTAL_FACING;
    public static final BooleanProperty RUNNING = BooleanProperty.of("running");
    public static final MapCodec<PortableGeneratorBlock> CODEC = createCodec(PortableGeneratorBlock::new);

    protected static final VoxelShape SHAPE = Block.createCuboidShape(1.0, 0.0, 1.0, 15.0, 14.0, 15.0);

    public PortableGeneratorBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState()
            .with(FACING, Direction.NORTH)
            .with(RUNNING, false)
        );
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, RUNNING);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE;
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    public boolean canConnect(BlockView world, BlockPos pos, Direction side, BlockState state) {
        Direction facing = state.get(FACING);
        // Connects via front AC output socket
        return side == facing;
    }

    @Override
    public boolean isThroughConductor() {
        return false;
    }

    @Override
    protected ActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        FuelRegistry fuelRegistry = world.getFuelRegistry();
        if (fuelRegistry.isFuel(stack)) {
            if (!world.isClient()) {
                BlockEntity be = world.getBlockEntity(pos);
                if (be instanceof PortableGeneratorBlockEntity gen) {
                    int burnTicks = fuelRegistry.getFuelTicks(stack);
                    gen.addFuel(burnTicks);

                    world.playSound(null, pos, SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.BLOCKS, 0.7f, 0.9f);
                    player.sendMessage(Text.literal("Added fuel: +" + (burnTicks / 20) + "s burn time.").formatted(Formatting.GOLD), true);

                    if (!player.getAbilities().creativeMode) {
                        ItemStack recipeRemainder = stack.getRecipeRemainder();
                        stack.decrement(1);
                        if (!recipeRemainder.isEmpty()) {
                            ItemUsage.exchangeStack(stack, player, recipeRemainder);
                        }
                    }
                }
            }
            return ActionResult.SUCCESS;
        }

        return super.onUseWithItem(stack, state, world, pos, player, hand, hit);
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (!world.isClient()) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof PortableGeneratorBlockEntity gen) {
                int fuelSec = (int) Math.round(gen.getRemainingFuelTicks() / 20.0);
                double loadW = gen.getLastDeliveredPowerWatts();
                boolean running = gen.isRunning();
                player.sendMessage(
                    Text.literal(String.format("Generator %s | Fuel: %ds | Output: %.0f W / 1800 W @ 230V AC",
                        running ? "RUNNING" : "STOPPED (EMPTY)", fuelSec, loadW)
                    ).formatted(running ? Formatting.GREEN : Formatting.GRAY),
                    false
                );
            }
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
        if (state.get(RUNNING)) {
            Direction facing = state.get(FACING);
            Direction exhaustDir = facing.rotateYClockwise(); // Exhaust pipe on side

            double px = pos.getX() + 0.5 + exhaustDir.getOffsetX() * 0.45;
            double py = pos.getY() + 0.65;
            double pz = pos.getZ() + 0.5 + exhaustDir.getOffsetZ() * 0.45;

            world.addParticleClient(ParticleTypes.SMOKE, px, py, pz,
                exhaustDir.getOffsetX() * 0.05, 0.05, exhaustDir.getOffsetZ() * 0.05);

            if (random.nextInt(6) == 0) {
                world.playSoundClient(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    SoundEvents.BLOCK_FURNACE_FIRE_CRACKLE, SoundCategory.BLOCKS, 0.4f, 1.2f, false);
            }
        }
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new PortableGeneratorBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (world.isClient()) return null;
        return (w, pos, st, be) -> {
            if (be instanceof PortableGeneratorBlockEntity gen && w instanceof ServerWorld sw) {
                gen.tick(sw);
            }
        };
    }
}
