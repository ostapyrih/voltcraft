package com.ostapyrih.voltcraft.block.entity.conversion;

import com.ostapyrih.voltcraft.block.conversion.ConverterBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.simulation.conversion.ConverterType;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/**
 * Block entity for DC-DC switched-mode converters (Buck, Boost, Buck-Boost) and linear LDO regulators.
 */
public class ConverterBlockEntity extends AbstractPowerConverterBlockEntity {

    private final ConverterType converterType;

    public ConverterBlockEntity(BlockPos pos, BlockState state, ConverterType converterType) {
        super(VoltcraftBlockEntityTypes.CONVERTER_BLOCK_ENTITY, pos, state);
        this.converterType = converterType;
        this.targetOutputVoltage = converterType.getDefaultTargetVoltage();
    }

    public ConverterBlockEntity(BlockPos pos, BlockState state) {
        this(
            pos,
            state,
            state.getBlock() instanceof ConverterBlock cb ? cb.getConverterType() : ConverterType.BUCK
        );
    }

    public ConverterType getConverterType() {
        return converterType;
    }

    @Override
    public boolean acceptsInputFrequency(double frequencyHz) {
        return frequencyHz <= 0.001; // DC only
    }

    @Override
    public double getOutputFrequency() {
        return 0.0; // DC output
    }

    @Override
    public boolean isOutputConfigurable() {
        return true;
    }

    @Override
    public int getTypeKind() {
        return 0; // DC-DC
    }

    @Override
    public double getEfficiency() {
        return converterType.calculateEfficiency(inputVoltage, outputVoltageEmf);
    }

    @Override
    public double getMinInputVoltage() {
        return converterType.getMinInputVoltage();
    }

    @Override
    public double getMaxInputVoltage() {
        return converterType.getMaxInputVoltage();
    }

    @Override
    public double getMaxOutputCurrent() {
        return converterType.getMaxCurrentAmps();
    }

    @Override
    public double getNominalOutputVoltage() {
        return targetOutputVoltage;
    }

    @Override
    public boolean isGridTie() {
        return false;
    }

    @Override
    public double getTotalHarmonicDistortion() {
        return 0.0; // DC output has 0% THD
    }

    @Override
    protected double computeOutputVoltage(double inputVoltage) {
        return converterType.calculateOutputVoltage(inputVoltage, targetOutputVoltage);
    }
}
