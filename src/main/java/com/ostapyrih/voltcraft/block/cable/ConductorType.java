package com.ostapyrih.voltcraft.block.cable;

import com.ostapyrih.voltcraft.simulation.solver.ThermalEquilibrium;

/**
 * Conductor material physical specifications.
 * Defines resistivity, thermal capacity, cooling rates, melting limits, and safety insulation.
 */
public enum ConductorType {
    BARE_COPPER(
        "cable_copper_bare",
        0.0068, 0.00393, 8.5, 0.18, 1085.0, 1085.0, 32.0, false, true
    ),
    INSULATED_COPPER(
        "cable_copper_insulated",
        0.0068, 0.00393, 9.5, 0.14, 120.0, 1085.0, 32.0, true, false
    ),
    HEAVY_COPPER(
        "cable_copper_heavy",
        0.0011, 0.00393, 55.0, 0.55, 250.0, 1085.0, 120.0, true, false
    ),
    ALUMINUM_TRANSMISSION(
        "cable_aluminum_transmission",
        0.0026, 0.00429, 25.0, 0.35, 660.0, 660.0, 64.0, false, true
    ),
    SILVER_PRECISION(
        "cable_silver_precision",
        0.0063, 0.00380, 7.0, 0.16, 150.0, 961.0, 48.0, true, false
    ),
    GOLD_BUS(
        "cable_gold_bus",
        0.0097, 0.00340, 6.0, 0.15, 180.0, 1064.0, 40.0, true, false
    ),
    STEEL_FENCE(
        "cable_steel_fence",
        0.0388, 0.00500, 11.0, 0.20, 1538.0, 1538.0, 16.0, false, true
    ),
    NICHROME_HEATING(
        "cable_nichrome_heating",
        0.7333, 0.00040, 12.0, 0.10, 1400.0, 1400.0, 8.0, false, true
    ),
    SUPERCONDUCTOR_CONDUIT(
        "conduit_superconductor",
        1e-7, 0.0, 100.0, 1.0, 200.0, 1000.0, 100000.0, true, false
    );

    private final String id;
    private final double baseResistance;
    private final double tempCoefficient;
    private final double heatCapacity;
    private final double coolingRate;
    private final double maxInsulationTemp;
    private final double meltingTemp;
    private final double maxAmpacity;
    private final boolean insulated;
    private final boolean shockHazard;

    ConductorType(
        String id,
        double baseResistance,
        double tempCoefficient,
        double heatCapacity,
        double coolingRate,
        double maxInsulationTemp,
        double meltingTemp,
        double maxAmpacity,
        boolean insulated,
        boolean shockHazard
    ) {
        this.id = id;
        this.baseResistance = baseResistance;
        this.tempCoefficient = tempCoefficient;
        this.heatCapacity = heatCapacity;
        this.coolingRate = coolingRate;
        this.maxInsulationTemp = maxInsulationTemp;
        this.meltingTemp = meltingTemp;
        this.maxAmpacity = maxAmpacity;
        this.insulated = insulated;
        this.shockHazard = shockHazard;
    }

    public String getId() {
        return id;
    }

    public double getBaseResistance() {
        return baseResistance;
    }

    public double getTempCoefficient() {
        return tempCoefficient;
    }

    public double getHeatCapacity() {
        return heatCapacity;
    }

    public double getCoolingRate() {
        return coolingRate;
    }

    public double getMaxInsulationTemp() {
        return maxInsulationTemp;
    }

    public double getMeltingTemp() {
        return meltingTemp;
    }

    public double getMaxAmpacity() {
        return maxAmpacity;
    }

    public boolean isInsulated() {
        return insulated;
    }

    public boolean hasShockHazard() {
        return shockHazard;
    }

    public ThermalEquilibrium.ThermalSpec toThermalSpec() {
        return new ThermalEquilibrium.ThermalSpec(
            baseResistance,
            tempCoefficient,
            heatCapacity,
            coolingRate,
            maxInsulationTemp,
            meltingTemp
        );
    }
}
