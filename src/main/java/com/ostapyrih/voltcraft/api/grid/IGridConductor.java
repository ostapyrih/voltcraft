package com.ostapyrih.voltcraft.api.grid;

import net.minecraft.util.math.BlockPos;

/**
 * Represents an edge (conductor, busbar, or cable) linking two nodes in an electrical grid.
 */
public interface IGridConductor {
    BlockPos getStartPos();

    BlockPos getEndPos();

    /**
     * @return Electrical resistance at reference temperature (Ohms).
     */
    double getBaseResistance();

    /**
     * @return Dynamic temperature-dependent resistance: R(T) = R0 * (1 + alpha * (T - T0)).
     */
    double getEffectiveResistance();

    /**
     * @return Maximum continuous current rating before thermal degradation (Amperes).
     */
    double getMaxAmpacity();

    /**
     * @return Current flowing through this branch (Amperes).
     */
    double getCurrent();

    void setCurrent(double currentAmps);

    /**
     * @return Current conductor temperature in degrees Celsius.
     */
    double getTemperature();

    void setTemperature(double tempCelsius);

    /**
     * @return Maximum insulation breakdown temperature (degrees Celsius).
     */
    double getMaxInsulationTemp();

    /**
     * @return Metal melting temperature where the wire vaporizes (degrees Celsius).
     */
    double getMeltingTemp();

    /**
     * @return True if conductor has intact insulation protecting against contact shock.
     */
    boolean isInsulated();
}
