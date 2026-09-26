package com.ostapyrih.voltcraft.simulation.conversion;

/**
 * Specifications and physical operating parameters for laminated-core AC transformers.
 */
public enum TransformerType {
    STEP_DOWN(
        "AC Step-Down Transformer",
        24.0 / 230.0, // Turns ratio a = Ns / Np
        0.96,
        5000.0,
        230.0,
        24.0
    ),
    STEP_UP(
        "AC Step-Up Transformer",
        230.0 / 24.0, // Turns ratio a = Ns / Np
        0.96,
        5000.0,
        24.0,
        230.0
    );

    private final String displayName;
    private final double turnsRatio;
    private final double efficiency;
    private final double maxPowerVA;
    private final double nominalPrimaryVoltage;
    private final double nominalSecondaryVoltage;

    TransformerType(
        String displayName,
        double turnsRatio,
        double efficiency,
        double maxPowerVA,
        double nominalPrimaryVoltage,
        double nominalSecondaryVoltage
    ) {
        this.displayName = displayName;
        this.turnsRatio = turnsRatio;
        this.efficiency = efficiency;
        this.maxPowerVA = maxPowerVA;
        this.nominalPrimaryVoltage = nominalPrimaryVoltage;
        this.nominalSecondaryVoltage = nominalSecondaryVoltage;
    }

    public String getDisplayName() {
        return displayName;
    }

    public double getTurnsRatio() {
        return turnsRatio;
    }

    public double getEfficiency() {
        return efficiency;
    }

    public double getMaxPowerVA() {
        return maxPowerVA;
    }

    public double getNominalPrimaryVoltage() {
        return nominalPrimaryVoltage;
    }

    public double getNominalSecondaryVoltage() {
        return nominalSecondaryVoltage;
    }

    public double calculateSecondaryVoltage(double primaryVoltage) {
        return primaryVoltage * turnsRatio;
    }
}
