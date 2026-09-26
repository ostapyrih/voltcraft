package com.ostapyrih.voltcraft.api.energy;

/**
 * Electrical power source (generator, dynamo, solar array, or discharging battery).
 */
public interface IElectricSource extends IElectricComponent {
    /**
     * @return Open-circuit electromotive force (EMF) in Volts.
     */
    double getElectromotiveForce();

    /**
     * @return Internal source resistance in Ohms.
     */
    double getInternalResistance();

    /**
     * @return Maximum continuous current the source can deliver (Amperes).
     */
    double getMaxOutputCurrent();

    /**
     * @return Current the source can sustain right now (Amperes). Defaults to the
     *         hardware rating; power-limited converters fed by weak sources report
     *         less, so a shared node divides load correctly instead of the solver
     *         inventing energy the upstream cannot supply.
     */
    default double getAvailableOutputCurrent() {
        return getMaxOutputCurrent();
    }

    /**
     * @return Output AC frequency in Hertz (Hz). 0.0 indicates Direct Current (DC).
     */
    default double getFrequency() {
        return 0.0;
    }

    /**
     * @return True if this source delivers an alternating current waveform.
     */
    default boolean isAlternatingCurrent() {
        return getFrequency() > 0.001;
    }

    /**
     * Draws power from the source.
     *
     * @param currentAmps Current delivered to the circuit (A)
     * @param durationSeconds Time elapsed in seconds (e.g., 0.05s per tick)
     */
    void onPowerDrawn(double currentAmps, double durationSeconds);
}
