package com.ostapyrih.voltcraft.block.storage;

import com.ostapyrih.voltcraft.block.AbstractGridBlock;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.block.entity.storage.BatteryBlockEntity;
import com.ostapyrih.voltcraft.simulation.chemistry.BatteryChemistry;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
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
 * Stationary electrochemical battery storage block (BESS).
 * Houses series/parallel chemistry stacks and interfaces directly with the electrical grid.
 */
public class BatteryBlock extends AbstractGridBlock {

    public static final EnumProperty<Direction> FACING = Properties.HORIZONTAL_FACING;

    private final BatteryChemistry chemistry;
    private final int seriesCount;
    private final int parallelCount;

    public BatteryBlock(Settings settings, BatteryChemistry chemistry, int seriesCount, int parallelCount) {
        super(settings);
        this.chemistry = chemistry;
        this.seriesCount = seriesCount;
        this.parallelCount = parallelCount;
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
        return new BatteryBlockEntity(pos, state, chemistry, seriesCount, parallelCount);
    }

    // No ticker: the storage has no per-block tick work. Grid participation is
    // discovered centrally by ElectricalGrid.refreshParticipants.

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
