package com.ostapyrih.voltcraft.api.energy;

import net.minecraft.util.math.Direction;

/**
 * Multi-port energy conversion device (Inverter, DC-DC converter, Transformer, Rectifier).
 */
public interface IElectricConverter extends IElectricComponent {
    /**
     * @param side Facing direction
     * @return True if this side is an electrical input port
     */
    boolean isInputPort(Direction side);

    /**
     * @param side Facing direction
     * @return True if this side is an electrical output port
     */
    boolean isOutputPort(Direction side);

    /**
     * @return Conversion efficiency ratio [0.0, 1.0].
     */
    double getEfficiency();

    /**
     * @return Target regulated output voltage in Volts.
     */
    double getTargetOutputVoltage();

    /**
     * @return The source endpoint exposed on the output port, or {@code null} if none.
     * Used by the grid refresh pass to discover converter outputs without
     * special-casing concrete classes.
     */
    default com.ostapyrih.voltcraft.api.energy.IElectricSource getOutputEndpoint() { return null; }

    /**
     * @return The consumer endpoint exposed on the input port, or {@code null} if none.
     * Used by the grid refresh pass to discover converter inputs without
     * special-casing concrete classes.
     */
    default com.ostapyrih.voltcraft.api.energy.IElectricConsumer getInputEndpoint() { return null; }
}
