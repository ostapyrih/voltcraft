package com.ostapyrih.voltcraft.block.storage;

import com.ostapyrih.voltcraft.api.grid.IElectricalConnectable;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.block.entity.storage.BatteryBlockEntity;
import com.ostapyrih.voltcraft.simulation.chemistry.BatteryChemistry;
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
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Stationary electrochemical battery storage block (BESS).
 * Houses series/parallel chemistry stacks and interfaces directly with the electrical grid.
 */
public class BatteryBlock extends Block implements BlockEntityProvider, IElectricalConnectable {

    private final BatteryChemistry chemistry;
    private final int seriesCount;
    private final int parallelCount;

    public BatteryBlock(Settings settings, BatteryChemistry chemistry, int seriesCount, int parallelCount) {
        super(settings);
        this.chemistry = chemistry;
        this.seriesCount = seriesCount;
        this.parallelCount = parallelCount;
    }

    public BatteryChemistry getChemistry() {
        return chemistry;
    }

    public int getSeriesCount() {
        return seriesCount;
    }

    public int getParallelCount() {
        return parallelCount;
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
        return new BatteryBlockEntity(pos, state, chemistry, seriesCount, parallelCount);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return world.isClient() ? null : (w, p, s, be) -> {
            if (be instanceof BatteryBlockEntity bbe) {
                bbe.tick((ServerWorld) w);
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
                if (be instanceof BatteryBlockEntity bbe) {
                    grid.registerSource(pos, bbe);
                }
            }
        }
    }

    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos, boolean moved) {
        if (!state.isOf(world.getBlockState(pos).getBlock())) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof BatteryBlockEntity bbe) {
                bbe.onRemovedFromWorld();
            }
            ElectricalGrid grid = GridManager.get(world).getGridAt(pos);
            if (grid != null) {
                grid.unregisterSource(pos);
            }
            GridManager.get(world).onConductorRemoved(world, pos);
        }
        super.onStateReplaced(state, world, pos, moved);
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (!world.isClient()) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof BatteryBlockEntity battery) {
                double soc = battery.getStateOfCharge() * 100.0;
                double soh = battery.getStateOfHealth();
                double v = battery.getElectromotiveForce();
                double temp = battery.getTemperatureCelsius();

                player.sendMessage(Text.literal("--- " + chemistry.getDisplayName() + " Bank ---").formatted(Formatting.GOLD), false);
                player.sendMessage(Text.literal(String.format("Voltage: %.2fV (Nominal: %.2fV)", v, seriesCount * chemistry.getNominalVoltage())).formatted(Formatting.AQUA), false);
                player.sendMessage(Text.literal(String.format("State of Charge (SoC): %.1f%%", soc)).formatted(Formatting.GREEN), false);
                player.sendMessage(Text.literal(String.format("State of Health (SOH): %.1f%%", soh)).formatted(Formatting.DARK_GREEN), false);
                player.sendMessage(Text.literal(String.format("Core Temperature: %.1f°C", temp)).formatted(temp > 60.0 ? Formatting.RED : Formatting.YELLOW), false);
            }
        }
        return ActionResult.SUCCESS;
    }
}
