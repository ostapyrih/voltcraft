package com.ostapyrih.voltcraft.block.conversion;

import com.ostapyrih.voltcraft.block.entity.conversion.InverterBlockEntity;
import com.ostapyrih.voltcraft.simulation.conversion.InverterType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Block for DC-AC inverters (Square Wave, Modified Sine, Pure Sine SPWM, Grid-Tie, Hybrid ESS).
 */
public class InverterBlock extends AbstractPowerConverterBlock {

    private final InverterType inverterType;

    public InverterBlock(Settings settings, InverterType inverterType) {
        super(settings);
        this.inverterType = inverterType;
    }

    public InverterType getInverterType() {
        return inverterType;
    }

    @Override
    public String getConverterDisplayName() {
        return inverterType.getDisplayName();
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new InverterBlockEntity(pos, state, inverterType);
    }
}
