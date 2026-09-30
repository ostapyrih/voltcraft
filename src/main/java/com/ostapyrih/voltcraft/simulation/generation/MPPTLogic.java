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

    // --- P&O tuning ---
    private static final double DEFAULT_TARGET_INPUT_VOLTAGE = 36.0;
    private static final double DEFAULT_STEP_SIZE = 0.2; // V perturbation step
    private static final double MIN_TARGET_INPUT_VOLTAGE = 10.0;
    private static final double MAX_TARGET_INPUT_VOLTAGE = 150.0;
    private static final double POWER_DELTA_DEADBAND_W = 0.05;

    // --- Rail-settled gating (see isRailSettled) ---
    private static final double RAIL_SETTLE_MIN_VIN_MARGIN = 0.95;
    private static final double RAIL_SETTLE_SERVO_MARGIN_V = 0.5;
    private static final double DEMAND_DEADBAND_MIN_W = 2.0;
    private static final double DEMAND_DEADBAND_RELATIVE = 0.05;

    // --- 3-stage charging ---
    private static final double ABSORPTION_VOLTAGE_PER_12V = 14.4;
    private static final double FLOAT_VOLTAGE_PER_12V = 13.6;
    private static final double BULK_TO_ABSORPTION_MARGIN_V = 0.1;
    private static final int ABSORPTION_TIMEOUT_TICKS = 1200;
    private static final double ABSORPTION_EXIT_CURRENT_A = 0.2;
    private static final double FLOAT_TO_BULK_DROP_V = 0.5; // V per 12V bank

    private ChargeStage stage = ChargeStage.BULK;
    private double targetInputVoltage = DEFAULT_TARGET_INPUT_VOLTAGE;
    private double previousPower = 0.0;
    private double previousVoltage = DEFAULT_TARGET_INPUT_VOLTAGE;
    private double stepSize = DEFAULT_STEP_SIZE;
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

    /** Snaps any input to the nearest supported nominal bank voltage: 12V, 24V, or 48V. */
    public void setBatteryBankVoltage(double voltage) {
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
        if (inputVoltage < minVin * RAIL_SETTLE_MIN_VIN_MARGIN || inputVoltage > servoTarget + RAIL_SETTLE_SERVO_MARGIN_V) {
            return false;
        }
        if (lastDemandWatts < 0.0) {
            return false;
        }
        double deadband = Math.max(DEMAND_DEADBAND_MIN_W, DEMAND_DEADBAND_RELATIVE * Math.max(1.0, needWatts));
        return Math.abs(needWatts - lastDemandWatts) <= deadband;
    }

    public ChargeStage getStage() {
        return stage;
    }

    public double getAbsorptionVoltage() {
        return (batteryBankVoltage / 12.0) * ABSORPTION_VOLTAGE_PER_12V;
    }

    public double getFloatVoltage() {
        return (batteryBankVoltage / 12.0) * FLOAT_VOLTAGE_PER_12V;
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
            initialized = true;
        } else if (adaptTarget) {
            perturbAndObserve(currentPower, inputVoltage);
        }
        // else: rail unsettled — target is frozen; previousPower/previousVoltage still
        // get refreshed below so re-entry compares against fresh measurements instead
        // of a stale jump.

        this.previousPower = currentPower;
        this.previousVoltage = inputVoltage;
        this.targetInputVoltage = clamp(targetInputVoltage, MIN_TARGET_INPUT_VOLTAGE, MAX_TARGET_INPUT_VOLTAGE);

        return runChargeStateMachine(inputCurrent, batteryVoltage);
    }

    /**
     * Classic Perturb & Observe: nudge {@link #targetInputVoltage} one step in whichever
     * direction the last perturbation increased power. Skipped entirely (target frozen)
     * whenever the power delta is inside the noise deadband.
     */
    private void perturbAndObserve(double currentPower, double inputVoltage) {
        double deltaP = currentPower - previousPower;
        double deltaV = inputVoltage - previousVoltage;

        if (Math.abs(deltaP) <= POWER_DELTA_DEADBAND_W) {
            return;
        }

        // deltaP and deltaV moving the same way means the last step helped; keep
        // going that way. Opposite signs mean it hurt; reverse direction.
        boolean sameSign = (deltaP > 0.0) == (deltaV >= 0.0);
        targetInputVoltage += sameSign ? stepSize : -stepSize;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Runs the Bulk -> Absorption -> Float charging state machine and returns this tick's target output EMF. */
    private double runChargeStateMachine(double inputCurrent, double batteryVoltage) {
        double absorptionV = getAbsorptionVoltage();
        double floatV = getFloatVoltage();

        switch (stage) {
            case BULK -> {
                double margin = (batteryBankVoltage / 12.0) * BULK_TO_ABSORPTION_MARGIN_V;
                if (batteryVoltage >= absorptionV - margin
                        && inputCurrent >= ABSORPTION_EXIT_CURRENT_A) {
                    this.stage = ChargeStage.ABSORPTION;
                    this.absorptionTicks = 0;
                }
                return absorptionV;
            }
            case ABSORPTION -> {
                this.absorptionTicks++;
                if (absorptionTicks > ABSORPTION_TIMEOUT_TICKS || inputCurrent < ABSORPTION_EXIT_CURRENT_A) {
                    this.stage = ChargeStage.FLOAT;
                    return floatV;
                }
                return absorptionV;
            }
            case FLOAT -> {
                double dropLimit = (batteryBankVoltage / 12.0) * FLOAT_TO_BULK_DROP_V;
                if (batteryVoltage < floatV - dropLimit) {
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