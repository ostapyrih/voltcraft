package com.ostapyrih.voltcraft.block.entity.conversion;

import com.ostapyrih.voltcraft.block.conversion.TransformerBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.simulation.conversion.TransformerType;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import com.ostapyrih.voltcraft.simulation.electrical.ConverterElement;

/**
 * Block entity for laminated-core AC transformers (Step-Down, Step-Up).
 */
public class TransformerBlockEntity extends AbstractPowerConverterBlockEntity {

    private final TransformerType transformerType;

    public TransformerBlockEntity(BlockPos pos, BlockState state, TransformerType transformerType) {
        super(VoltcraftBlockEntityTypes.TRANSFORMER_BLOCK_ENTITY, pos, state);
        this.transformerType = transformerType;
        this.targetOutputVoltage = transformerType.getNominalSecondaryVoltage();
    }

    public TransformerBlockEntity(BlockPos pos, BlockState state) {
        this(
            pos,
            state,
            state.getBlock() instanceof TransformerBlock tb ? tb.getTransformerType() : TransformerType.STEP_DOWN
        );
    }

    public TransformerType getTransformerType() {
        return transformerType;
    }

    @Override
    public boolean acceptsInputFrequency(double frequencyHz) {
        return frequencyHz > 0.001; // Magnetic induction requires alternating current (AC)
    }

    @Override
    public double getOutputFrequency() {
        return 50.0; // Transformers output AC
    }

    @Override
    public int getTypeKind() {
        return 2; // Transformer
    }

    @Override
    public boolean isACSource() {
        return ConverterElement.isACOutput(getTypeKind());
    }

    @Override
    public double getEfficiency() {
        return transformerType.getEfficiency();
    }

    @Override
    public double getMinInputVoltage() {
        return 5.0; // Minimal excitation threshold
    }

    @Override
    public double getMaxInputVoltage() {
        return transformerType.getNominalPrimaryVoltage() * 1.5;
    }

    @Override
    public double getMaxOutputCurrent() {
        return transformerType.getMaxPowerVA() / Math.max(1.0, transformerType.getNominalSecondaryVoltage());
    }

    @Override
    public double getNominalOutputVoltage() {
        return transformerType.getNominalSecondaryVoltage();
    }

    @Override
    public boolean isGridTie() {
        return false;
    }

    @Override
    public double getTotalHarmonicDistortion() {
        return 1.2; // Linear magnetic transformer core introduces minimal distortion
    }

    @Override
    protected double computeOutputVoltage(double inputVoltage) {
        return transformerType.calculateSecondaryVoltage(inputVoltage);
    }
}
