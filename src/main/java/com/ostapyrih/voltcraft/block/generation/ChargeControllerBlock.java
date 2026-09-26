package com.ostapyrih.voltcraft.block.generation;

import com.ostapyrih.voltcraft.block.conversion.AbstractPowerConverterBlock;
import com.ostapyrih.voltcraft.block.entity.generation.ChargeControllerBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Solar MPPT Charge Controller block.
 * Input (Back): Solar PV array string (15 - 150V DC).
 * Output (Front): Regulated DC Battery bus (12V, 24V, or 48V nominal).
 */
public class ChargeControllerBlock extends AbstractPowerConverterBlock {

    public static final MapCodec<ChargeControllerBlock> CODEC = createCodec(ChargeControllerBlock::new);

    public ChargeControllerBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected MapCodec<? extends AbstractPowerConverterBlock> getCodec() {
        return CODEC;
    }

    @Override
    public String getConverterDisplayName() {
        return "MPPT Solar Charge Controller";
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new ChargeControllerBlockEntity(pos, state);
    }
}
