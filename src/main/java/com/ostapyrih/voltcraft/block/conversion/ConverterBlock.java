package com.ostapyrih.voltcraft.block.conversion;

import com.ostapyrih.voltcraft.block.entity.conversion.ConverterBlockEntity;
import com.ostapyrih.voltcraft.simulation.conversion.ConverterType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Block for DC-DC switched-mode converters (Buck, Boost, Buck-Boost) and linear LDO regulators.
 */
public class ConverterBlock extends AbstractPowerConverterBlock {

    private final ConverterType converterType;

    public ConverterBlock(Settings settings, ConverterType converterType) {
        super(settings);
        this.converterType = converterType;
    }

    public ConverterType getConverterType() {
        return converterType;
    }

    @Override
    public String getConverterDisplayName() {
        return converterType.getDisplayName();
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new ConverterBlockEntity(pos, state, converterType);
    }
}
