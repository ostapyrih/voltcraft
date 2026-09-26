package com.ostapyrih.voltcraft.simulation.conversion;

/**
 * Specifications and physical operating parameters for AC-DC rectifiers.
 */
public enum RectifierType {
    BRIDGE(
        "Full-Wave Bridge Rectifier",
        1.40, // Forward diode drop (2 x 0.7V silicon diodes in series)
        0.88,
        32.0
    ),
    ACTIVE_SYNCHRONOUS(
        "Active Synchronous Rectifier",
        0.05, // Ultra-low MOSFET Rds(on) drop
        0.985,
        64.0
    );

    private final String displayName;
    private final double forwardVoltageDrop;
    private final double efficiency;
    private final double maxCurrentAmps;

    RectifierType(
        String displayName,
        double forwardVoltageDrop,
        double efficiency,
        double maxCurrentAmps
    ) {
        this.displayName = displayName;
        this.forwardVoltageDrop = forwardVoltageDrop;
        this.efficiency = efficiency;
        this.maxCurrentAmps = maxCurrentAmps;
    }

    public String getDisplayName() {
        return displayName;
    }

    public double getForwardVoltageDrop() {
        return forwardVoltageDrop;
    }

    public double getEfficiency() {
        return efficiency;
    }

    public double getMaxCurrentAmps() {
        return maxCurrentAmps;
    }

    /**
     * Converts input RMS AC voltage to rectified DC bus voltage.
     * Peak AC voltage is Vrms * sqrt(2), minus rectifier forward diode drops.
     */
    public double calculateDcVoltage(double acRmsVoltage) {
        if (acRmsVoltage <= 0.0) return 0.0;
        double peak = acRmsVoltage * Math.sqrt(2.0);
        return Math.max(0.0, peak - forwardVoltageDrop);
    }
}
