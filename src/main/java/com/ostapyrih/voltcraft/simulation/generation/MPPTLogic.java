package com.ostapyrih.voltcraft.simulation.generation;

/**
 * Maximum Power Point Tracking (MPPT) logic using Perturb & Observe (P&O) algorithm
 * with 3-stage battery charging (Bulk, Absorption, Float).
 */
public class MPPTLogic {

    public enum ChargeStage {
        BULK,
        ABSORPTION,
        FLOAT
    }

    private ChargeStage stage = ChargeStage.BULK;
    private double targetInputVoltage = 36.0;
    private double previousPower = 0.0;
    private double previousVoltage = 36.0;
    private double stepSize = 0.2; // V perturbation step
    private int absorptionTicks = 0;
    private boolean initialized = false;

    // Configurable battery bank nominal voltage (12, 24, or 48V)
    private double batteryBankVoltage = 24.0;

    public MPPTLogic() {
        this(24.0);
    }

    public MPPTLogic(double batteryBankVoltage) {
        setBatteryBankVoltage(batteryBankVoltage);
    }

    public void setBatteryBankVoltage(double voltage) {
        this.batteryBankVoltage = voltage;
        if (voltage <= 14.0) {
            this.batteryBankVoltage = 12.0;
        } else if (voltage <= 30.0) {
            this.batteryBankVoltage = 24.0;
        } else {
            this.batteryBankVoltage = 48.0;
        }
    }

    public double getBatteryBankVoltage() {
        return batteryBankVoltage;
    }

    /**
     * Rail-settled predicate for gating P&amp;O adaptation. The tracker may only
     * trust a perturbation while the rail sits inside the servo band AND demand
     * is steady; during ramps or collapse the power deltas come from demand
     * swings, and adapting on them walks the target away from the true MPP.
     *
     * @param inputVoltage measured rail voltage in Volts
     * @param minVin converter minimum input voltage in Volts
     * @param servoTarget servo park voltage in Volts
     * @param needWatts current input demand estimate in Watts
     * @param lastDemandWatts demand served last tick in Watts (negative = uninitialized)
     * @return true if a perturbation measurement would be meaningful
     */
    public static boolean isRailSettled(
        double inputVoltage,
        double minVin,
        double servoTarget,
        double needWatts,
        double lastDemandWatts
    ) {
        if (inputVoltage < minVin * 0.95 || inputVoltage > servoTarget + 0.5) {
            return false;
        }
        if (lastDemandWatts < 0.0) {
            return false;
        }
        return Math.abs(needWatts - lastDemandWatts) <= Math.max(2.0, 0.05 * Math.max(1.0, needWatts));
    }

    public ChargeStage getStage() {
        return stage;
    }

    public double getAbsorptionVoltage() {
        return (batteryBankVoltage / 12.0) * 14.4;
    }

    public double getFloatVoltage() {
        return (batteryBankVoltage / 12.0) * 13.6;
    }

    /**
     * Executes one tick of MPPT Perturb &amp; Observe tracking.
     *
     * @param inputVoltage Measured PV array voltage
     * @param inputCurrent Measured PV array current
     * @param batteryVoltage Measured downstream battery voltage
     * @return Target output EMF to apply to the downstream battery/load
     */
    public double step(double inputVoltage, double inputCurrent, double batteryVoltage) {
        return step(inputVoltage, inputCurrent, batteryVoltage, true);
    }

    /**
     * Gated variant: when {@code adaptTarget} is false (input rail ramping or
     * collapsed), the tracking target is frozen but the charge-stage machine still
     * runs. Adapting P&amp;O while demand swings would misattribute demand-driven
     * power changes to the voltage perturbation and walk the target away from the
     * true maximum-power point.
     *
     * @param inputVoltage Measured PV array voltage
     * @param inputCurrent Measured PV array current
     * @param batteryVoltage Measured downstream battery voltage
     * @param adaptTarget Whether the rail is settled enough to trust a perturbation
     * @return Target output EMF to apply to the downstream battery/load
     */
    public double step(double inputVoltage, double inputCurrent, double batteryVoltage, boolean adaptTarget) {
        double currentPower = inputVoltage * inputCurrent;

        if (!initialized) {
            this.previousPower = currentPower;
            this.previousVoltage = inputVoltage;
            this.initialized = true;
        } else if (adaptTarget) {
            double deltaP = currentPower - previousPower;
            double deltaV = inputVoltage - previousVoltage;

            // P&O Algorithm: Perturb PV operating voltage to seek MPP
            if (Math.abs(deltaP) > 0.05) {
                if (deltaP > 0.0) {
                    if (deltaV >= 0.0) {
                        targetInputVoltage += stepSize;
                    } else {
                        targetInputVoltage -= stepSize;
                    }
                } else {
                    if (deltaV >= 0.0) {
                        targetInputVoltage -= stepSize;
                    } else {
                        targetInputVoltage += stepSize;
                    }
                }
            }

            this.previousPower = currentPower;
            this.previousVoltage = inputVoltage;
        } else {
            // Rail unsettled: track the baseline without perturbing, so re-entry
            // compares against fresh measurements instead of a stale jump.
            this.previousPower = currentPower;
            this.previousVoltage = inputVoltage;
        }

        // Clamp target input voltage to sane limits (10V to 150V)
        targetInputVoltage = Math.max(10.0, Math.min(150.0, targetInputVoltage));

        // 3-Stage Charging State Machine
        double absorptionV = getAbsorptionVoltage();
        double floatV = getFloatVoltage();

        switch (stage) {
            case BULK -> {
                if (batteryVoltage >= absorptionV - 0.1) {
                    this.stage = ChargeStage.ABSORPTION;
                    this.absorptionTicks = 0;
                    return absorptionV;
                }
                return absorptionV;
            }
            case ABSORPTION -> {
                this.absorptionTicks++;
                if (absorptionTicks > 1200 || inputCurrent < 0.2) {
                    this.stage = ChargeStage.FLOAT;
                    return floatV;
                }
                return absorptionV;
            }
            case FLOAT -> {
                if (batteryVoltage < floatV - 1.0) {
                    this.stage = ChargeStage.BULK;
                    return absorptionV;
                }
                return floatV;
            }
        }

        return absorptionV;
    }

    public double getTargetInputVoltage() {
        return targetInputVoltage;
    }
}
