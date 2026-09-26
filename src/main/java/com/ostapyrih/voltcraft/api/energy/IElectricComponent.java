package com.ostapyrih.voltcraft.api.energy;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import net.minecraft.util.math.BlockPos;

/**
 * Base interface for any grid-connectable block or block entity.
 */
public interface IElectricComponent {
    /**
     * @return World position of the component.
     */
    BlockPos getPos();

    /**
     * @return Current operational state (OFF, NOMINAL, BROWNOUT, SURGE, DESTROYED).
     */
    ElectricalState getElectricalState();

    /**
     * Sets the operational state resulting from grid solver evaluation.
     */
    void setElectricalState(ElectricalState state);
}
