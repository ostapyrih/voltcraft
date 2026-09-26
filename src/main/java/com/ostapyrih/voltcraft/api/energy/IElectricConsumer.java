package com.ostapyrih.voltcraft.api.energy;

/**
 * Electrical power load/consumer (furnaces, motors, lights, electrolysis cells).
 */
public interface IElectricConsumer extends IElectricComponent {
    /**
     * @return Nominal power consumption demand in Watts.
     */
    double getNominalPowerDemand();

    /**
     * @return Nominal operating voltage in Volts.
     */
    double getNominalVoltage();

    /**
     * @return Minimum threshold voltage before brownout occurs (V).
     */
    double getMinOperatingVoltage();

    /**
     * @return Maximum threshold voltage before surge/burnout occurs (V).
     */
    double getMaxOperatingVoltage();

    /**
     * @return Equivalent load impedance in Ohms.
     */
    default double getEquivalentResistance() {
        double v = getNominalVoltage();
        double p = getNominalPowerDemand();
        if (p <= 0.0) return Double.POSITIVE_INFINITY;
        return (v * v) / p;
    }

    /**
     * Delivers electrical power to the consumer.
     *
     * @param terminalVoltage Measured terminal voltage across the consumer (V)
     * @param deliveredCurrent Delivered current (A)
     * @param durationSeconds Duration of this step in seconds
     */
    void onPowerReceived(double terminalVoltage, double deliveredCurrent, double durationSeconds);

    /**
     * Delivers electrical power to the consumer with grid waveform frequency information.
     *
     * @param terminalVoltage Measured terminal voltage across the consumer (V)
     * @param deliveredCurrent Delivered current (A)
     * @param durationSeconds Duration of this step in seconds
     * @param frequencyHz AC Frequency in Hz (0.0 indicates DC)
     */
    default void onPowerReceived(double terminalVoltage, double deliveredCurrent, double durationSeconds, double frequencyHz) {
        onPowerReceived(terminalVoltage, deliveredCurrent, durationSeconds);
    }
}
