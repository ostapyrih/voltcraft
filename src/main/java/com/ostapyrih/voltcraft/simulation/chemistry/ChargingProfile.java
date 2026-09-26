package com.ostapyrih.voltcraft.simulation.chemistry;

/**
 * State machine managing multi-stage battery charging protocols:
 * Constant Current (CC), Constant Voltage (CV), Float, and thermal cutoff.
 */
public class ChargingProfile {

    public enum ChargeStage {
        IDLE,
        CONSTANT_CURRENT,
        CONSTANT_VOLTAGE,
        FLOAT,
        COMPLETED,
        THERMAL_CUTOFF
    }

    private final BatteryChemistry chemistry;
    private ChargeStage stage = ChargeStage.IDLE;
    private double targetCurrentAmps;
    private double targetVoltage;

    public ChargingProfile(BatteryChemistry chemistry) {
        this.chemistry = chemistry;
        reset();
    }

    public void reset() {
        this.stage = ChargeStage.CONSTANT_CURRENT;
        this.targetCurrentAmps = chemistry.getCapacityAmpHours() * 0.5; // 0.5C default charging rate
        this.targetVoltage = chemistry.getFullChargeVoltage();
    }

    public ChargeStage getStage() {
        return stage;
    }

    public double getTargetCurrentAmps() {
        return targetCurrentAmps;
    }

    public double getTargetVoltage() {
        return targetVoltage;
    }

    /**
     * Ticks the charge controller state machine.
     *
     * @param terminalVoltage Current cell terminal voltage
     * @param actualCurrent Current flowing into battery (positive when charging)
     * @param tempCelsius Current cell temperature
     * @return Commanded charging current in Amperes
     */
    public double update(double terminalVoltage, double actualCurrent, double tempCelsius) {
        // 1. Safety check
        if (tempCelsius >= chemistry.getThermalRunawayTempCelsius() * 0.8) {
            stage = ChargeStage.THERMAL_CUTOFF;
            return 0.0;
        }

        switch (stage) {
            case CONSTANT_CURRENT:
                // When voltage reaches full charge cutoff, switch to CV
                if (terminalVoltage >= chemistry.getFullChargeVoltage()) {
                    stage = ChargeStage.CONSTANT_VOLTAGE;
                }
                return targetCurrentAmps;

            case CONSTANT_VOLTAGE:
                // In CV mode, current tapers down. When it drops below 0.05C, charge is complete
                double terminationCurrent = chemistry.getCapacityAmpHours() * 0.05;
                if (actualCurrent <= terminationCurrent && actualCurrent >= 0.0) {
                    if (chemistry == BatteryChemistry.LEAD_ACID) {
                        stage = ChargeStage.FLOAT;
                        return chemistry.getCapacityAmpHours() * 0.01;
                    } else {
                        stage = ChargeStage.COMPLETED;
                        return 0.0;
                    }
                }
                // Reduce commanded current as voltage sits at max
                return Math.max(0.0, actualCurrent);

            case FLOAT:
                // Lead-acid float charge maintenance (trickle charge ~2.25V)
                return chemistry.getCapacityAmpHours() * 0.01;

            case COMPLETED:
            case THERMAL_CUTOFF:
            default:
                return 0.0;
        }
    }
}
