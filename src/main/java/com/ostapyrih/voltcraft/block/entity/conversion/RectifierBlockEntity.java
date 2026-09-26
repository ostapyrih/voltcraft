package com.ostapyrih.voltcraft.block.entity.conversion;

import com.ostapyrih.voltcraft.block.conversion.RectifierBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.simulation.conversion.RectifierType;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/**
 * Block entity for AC-DC rectifiers (Bridge Rectifier, Active Synchronous Rectifier).
 */
public class RectifierBlockEntity extends AbstractPowerConverterBlockEntity {

    private final RectifierType rectifierType;

    public RectifierBlockEntity(BlockPos pos, BlockState state, RectifierType rectifierType) {
        super(VoltcraftBlockEntityTypes.RECTIFIER_BLOCK_ENTITY, pos, state);
        this.rectifierType = rectifierType;
        this.targetOutputVoltage = 48.0;
    }

    public RectifierBlockEntity(BlockPos pos, BlockState state) {
        this(
            pos,
            state,
            state.getBlock() instanceof RectifierBlock rb ? rb.getRectifierType() : RectifierType.BRIDGE
        );
    }

    public RectifierType getRectifierType() {
        return rectifierType;
    }

    @Override
    public boolean acceptsInputFrequency(double frequencyHz) {
        return true; // Rectifiers accept both AC and DC input
    }

    @Override
    public double getOutputFrequency() {
        return 0.0; // Rectified output is DC
    }

    @Override
    public int getTypeKind() {
        return 3; // Rectifier
    }

    @Override
    public double getEfficiency() {
        return rectifierType.getEfficiency();
    }

    @Override
    public double getMinInputVoltage() {
        return 4.0;
    }

    @Override
    public double getMaxInputVoltage() {
        return 400.0;
    }

    @Override
    public double getMaxOutputCurrent() {
        return rectifierType.getMaxCurrentAmps();
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
        return rectifierType.calculateDcVoltage(inputVoltage);
    }
}
