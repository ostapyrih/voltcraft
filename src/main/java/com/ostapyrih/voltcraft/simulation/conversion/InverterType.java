package com.ostapyrih.voltcraft.simulation.conversion;

/**
 * Specifications and physical operating parameters for DC-AC inverters.
 */
public enum InverterType {
    SQUARE_WAVE(
        "Square Wave Inverter",
        48.0, // High harmonic distortion THD %
        0.90,
        1500.0,
        230.0,
        false,
        false
    ),
    MODIFIED_SINE(
        "Modified Sine Wave Inverter",
        28.0, // Stepped approximation THD %
        0.92,
        3000.0,
        230.0,
        false,
        false
    ),
    PURE_SINE(
        "Pure Sine Wave SPWM Inverter",
        2.5, // High fidelity LC filtered THD %
        0.96,
        5000.0,
        230.0,
        false,
        false
    ),
    GRID_TIE(
        "Synchronous Grid-Tie Inverter",
        2.0,
        0.97,
        6000.0,
        230.0,
        true, // Synchronizes and injects to AC grid with anti-islanding
        false
    ),
    HYBRID_ESS(
        "Hybrid ESS Multi-Mode Inverter",
        2.0,
        0.96,
        8000.0,
        230.0,
        true,
        true // Automatic Transfer Switch (ATS) between grid and islanded battery backup
    );

    private final String displayName;
    private final double totalHarmonicDistortionPercent;
    private final double efficiency;
    private final double maxPowerWatts;
    private final double nominalOutputVoltage;
    private final boolean isGridTie;
    private final boolean hasAutomaticTransferSwitch;

    InverterType(
        String displayName,
        double totalHarmonicDistortionPercent,
        double efficiency,
        double maxPowerWatts,
        double nominalOutputVoltage,
        boolean isGridTie,
        boolean hasAutomaticTransferSwitch
    ) {
        this.displayName = displayName;
        this.totalHarmonicDistortionPercent = totalHarmonicDistortionPercent;
        this.efficiency = efficiency;
        this.maxPowerWatts = maxPowerWatts;
        this.nominalOutputVoltage = nominalOutputVoltage;
        this.isGridTie = isGridTie;
        this.hasAutomaticTransferSwitch = hasAutomaticTransferSwitch;
    }

    public String getDisplayName() {
        return displayName;
    }

    public double getTotalHarmonicDistortionPercent() {
        return totalHarmonicDistortionPercent;
    }

    public double getEfficiency() {
        return efficiency;
    }

    public double getMaxPowerWatts() {
        return maxPowerWatts;
    }

    public double getNominalOutputVoltage() {
        return nominalOutputVoltage;
    }

    public boolean isGridTie() {
        return isGridTie;
    }

    public boolean hasAutomaticTransferSwitch() {
        return hasAutomaticTransferSwitch;
    }
}
