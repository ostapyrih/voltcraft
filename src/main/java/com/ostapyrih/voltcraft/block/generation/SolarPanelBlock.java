package com.ostapyrih.voltcraft.block.generation;

import com.ostapyrih.voltcraft.block.entity.generation.SolarPanelBlockEntity;
import com.ostapyrih.voltcraft.simulation.generation.SolarPanelType;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Photovoltaic solar panel block with slab-style 6-pixel height.
 * Top surface absorbs solar irradiance, bottom/rear faces provide DC terminal connection.
 */
public class SolarPanelBlock extends BlockWithEntity {

    public static final EnumProperty<Direction> FACING = Properties.HORIZONTAL_FACING;
    protected static final VoxelShape SLAB_SHAPE = Block.createCuboidShape(0.0, 0.0, 0.0, 16.0, 6.0, 16.0);

    private final SolarPanelType panelType;

    public SolarPanelBlock(Settings settings, SolarPanelType panelType) {
        super(settings);
        this.panelType = panelType;
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    public SolarPanelType getPanelType() {
        return panelType;
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                createSettingsCodec()
            ).apply(instance, s -> new SolarPanelBlock(s, panelType))
        );
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
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SLAB_SHAPE;
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new SolarPanelBlockEntity(pos, state, panelType);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (world.isClient()) return null;
        return (w, pos, st, be) -> {
            if (be instanceof SolarPanelBlockEntity spbe && w instanceof ServerWorld sw) {
                spbe.tick(sw);
            }
        };
    }
}
