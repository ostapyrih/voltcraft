package com.ostapyrih.voltcraft.api.energy;

import com.ostapyrih.voltcraft.api.data.BatteryCellSpec;

/**
 * Electrochemical energy storage component (stationary BESS or battery rack).
 */
public interface IElectricStorage extends IElectricSource, IElectricConsumer {
    /**
     * @return State of Charge (fraction between 0.0 and 1.0).
     */
    double getStateOfCharge();

    /**
     * @return State of Health (percentage between 0.0% and 100.0%).
     */
    double getStateOfHealth();

    /**
     * @return Total nominal energy storage capacity in Joules (Watt-seconds).
     */
    double getMaxStorageJoules();

    /**
     * @return Current stored energy in Joules.
     */
    double getStoredJoules();

    /**
     * @return Chemistry specification governing this storage system.
     */
    BatteryCellSpec getChemistrySpec();

    /**
     * Injects charge into the battery.
     *
     * @param joules Energy added in Joules
     */
    void addEnergy(double joules);

    /**
     * Extracts energy from the battery.
     *
     * @param joules Energy removed in Joules
     * @return Actual energy delivered
     */
    double extractEnergy(double joules);
}
