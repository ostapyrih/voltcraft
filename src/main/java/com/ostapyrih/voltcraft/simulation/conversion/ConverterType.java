package com.ostapyrih.voltcraft.simulation.conversion;

/**
 * Specifications and physical operating parameters for DC-DC switched-mode and linear regulators.
 */
public enum ConverterType {
    BUCK(
        "Buck Step-Down Converter",
        0.94,
        100.0,
        12.0,
        8.0,
        60.0,
        false
    ),
    BOOST(
        "Boost Step-Up Converter",
        0.92,
        60.0,
        48.0,
        10.0,
        40.0,
        false
    ),
    BUCK_BOOST(
        "Universal Buck-Boost / SEPIC Converter",
        0.90,
        100.0,
        24.0,
        8.0,
        60.0,
        false
    ),
    LINEAR_LDO(
        "Linear LDO Voltage Regulator",
        0.50, // Dynamic efficiency = Vout / Vin
        20.0,
        5.0,
        6.0,
        35.0,
        true
    );

    private final String displayName;
    private final double nominalEfficiency;
    private final double maxCurrentAmps;
    private final double defaultTargetVoltage;
    private final double minInputVoltage;
    private final double maxInputVoltage;
    private final boolean isLinearDissipative;

    ConverterType(
        String displayName,
        double nominalEfficiency,
        double maxCurrentAmps,
        double defaultTargetVoltage,
        double minInputVoltage,
        double maxInputVoltage,
        boolean isLinearDissipative
    ) {
        this.displayName = displayName;
        this.nominalEfficiency = nominalEfficiency;
        this.maxCurrentAmps = maxCurrentAmps;
        this.defaultTargetVoltage = defaultTargetVoltage;
        this.minInputVoltage = minInputVoltage;
        this.maxInputVoltage = maxInputVoltage;
        this.isLinearDissipative = isLinearDissipative;
    }

    public String getDisplayName() {
        return displayName;
    }

    public double getNominalEfficiency() {
        return nominalEfficiency;
    }

    public double getMaxCurrentAmps() {
        return maxCurrentAmps;
    }

    public double getDefaultTargetVoltage() {
        return defaultTargetVoltage;
    }

    public double getMinInputVoltage() {
        return minInputVoltage;
    }

    public double getMaxInputVoltage() {
        return maxInputVoltage;
    }

    public boolean isLinearDissipative() {
        return isLinearDissipative;
    }

    /**
     * Calculates conversion output voltage given actual input voltage and setpoint.
     */
    public double calculateOutputVoltage(double inputVoltage, double setpoint) {
        if (inputVoltage < minInputVoltage) return 0.0;
        return switch (this) {
            case BUCK -> Math.min(inputVoltage, setpoint);
            case BOOST -> Math.max(inputVoltage, setpoint);
            case BUCK_BOOST -> setpoint;
            case LINEAR_LDO -> inputVoltage >= setpoint ? setpoint : 0.0;
        };
    }

    /**
     * Calculates efficiency based on input and output voltage.
     */
    public double calculateEfficiency(double inputVoltage, double outputVoltage) {
        if (isLinearDissipative) {
            if (inputVoltage <= 0.0 || outputVoltage <= 0.0) return 0.0;
            return Math.min(1.0, outputVoltage / inputVoltage);
        }
        return nominalEfficiency;
    }
}
