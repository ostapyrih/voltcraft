package com.ostapyrih.voltcraft.block.generation;

import com.ostapyrih.voltcraft.api.grid.IElectricalConnectable;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.block.entity.generation.SolarPanelBlockEntity;
import com.ostapyrih.voltcraft.simulation.generation.SolarPanelType;
import com.ostapyrih.voltcraft.simulation.grid.GridManager;
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
 * Participates in the electrical grid as an endpoint source, exactly like {@code BatteryBlock}:
 * implements {@link IElectricalConnectable} so that a cable placed next to a panel merges the
 * two grids, and drives {@code onConductorPlaced} / {@code onConductorRemoved} on lifecycle
 * events so topology stays consistent through placement, destruction, and chunk reloads.
 */
public class SolarPanelBlock extends BlockWithEntity implements IElectricalConnectable {

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
            instance.group(createSettingsCodec())
                .apply(instance, s -> new SolarPanelBlock(s, panelType))
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

    // ==================== IElectricalConnectable ====================

    /**
     * Panels connect on every face except the sky-facing surface (which is the PV absorber).
     * This mirrors how a real panel exposes its DC terminals on the underside and edges.
     */
    @Override
    public boolean canConnect(BlockView world, BlockPos pos, Direction side, BlockState state) {
        return side != Direction.UP;
    }

    /**
     * Panels are electrically continuous endpoints: the panel's own node hosts its Thevenin
     * source, and any cable that terminates on the panel should join that node. Returning
     * {@code true} here lets {@code GridManager.onConductorPlaced} see the panel as a valid
     * neighbour when a cable is placed adjacent to it, which is what triggers the grid merge.
     */
    @Override
    public boolean isThroughConductor() {
        return true;
    }

    // ==================== Lifecycle ====================

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

    @Override
    protected void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        if (!world.isClient() && !state.isOf(oldState.getBlock())) {
            GridManager.get((ServerWorld) world).onConductorPlaced((ServerWorld) world, pos, ConductorType.INSULATED_COPPER);
        }
    }

    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos, boolean moved) {
        if (!state.isOf(world.getBlockState(pos).getBlock())) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof SolarPanelBlockEntity spbe) {
                spbe.onRemovedFromWorld();
            }
            GridManager.get(world).onConductorRemoved(world, pos);
        }
        super.onStateReplaced(state, world, pos, moved);
    }
}