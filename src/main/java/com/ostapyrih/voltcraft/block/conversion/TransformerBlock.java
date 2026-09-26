package com.ostapyrih.voltcraft.block.conversion;

import com.ostapyrih.voltcraft.block.entity.conversion.TransformerBlockEntity;
import com.ostapyrih.voltcraft.simulation.conversion.TransformerType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Block for laminated-core AC transformers (Step-Down, Step-Up).
 */
public class TransformerBlock extends AbstractPowerConverterBlock {

    private final TransformerType transformerType;

    public TransformerBlock(Settings settings, TransformerType transformerType) {
        super(settings);
        this.transformerType = transformerType;
    }

    public TransformerType getTransformerType() {
        return transformerType;
    }

    @Override
    public String getConverterDisplayName() {
        return transformerType.getDisplayName();
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new TransformerBlockEntity(pos, state, transformerType);
    }
}
