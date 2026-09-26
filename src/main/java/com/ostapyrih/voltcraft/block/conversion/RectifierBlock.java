package com.ostapyrih.voltcraft.block.conversion;

import com.ostapyrih.voltcraft.block.entity.conversion.RectifierBlockEntity;
import com.ostapyrih.voltcraft.simulation.conversion.RectifierType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Block for AC-DC rectifiers (Full-Wave Bridge, Active Synchronous).
 */
public class RectifierBlock extends AbstractPowerConverterBlock {

    private final RectifierType rectifierType;

    public RectifierBlock(Settings settings, RectifierType rectifierType) {
        super(settings);
        this.rectifierType = rectifierType;
    }

    public RectifierType getRectifierType() {
        return rectifierType;
    }

    @Override
    public String getConverterDisplayName() {
        return rectifierType.getDisplayName();
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new RectifierBlockEntity(pos, state, rectifierType);
    }
}
