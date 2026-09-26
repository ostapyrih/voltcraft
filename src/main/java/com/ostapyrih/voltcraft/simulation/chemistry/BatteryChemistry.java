package com.ostapyrih.voltcraft.simulation.chemistry;

/**
 * Enumeration of electrochemical cell chemistries and their physical operating parameters.
 */
public enum BatteryChemistry {
    LI_ION_18650("Li-Ion 18650", 3.7, 2.8, 4.2, 3000.0, 5.0, 0.025, true, 150.0, 1000),
    LI_ION_21700("Li-Ion 21700 NMC", 3.7, 2.7, 4.2, 5000.0, 10.0, 0.015, true, 150.0, 1200),
    LIFEPO4("LiFePO4", 3.2, 2.5, 3.65, 100000.0, 3.0, 0.0006, true, 270.0, 4000),
    LEAD_ACID("Lead-Acid (SLA)", 2.0, 1.75, 2.40, 120000.0, 1.5, 0.0015, true, 70.0, 500),
    ALKALINE("Alkaline (Zn-MnO2)", 1.5, 0.8, 1.6, 2000.0, 1.0, 0.150, false, 85.0, 1),
    ZINC_CARBON("Zinc-Carbon", 1.5, 0.9, 1.55, 800.0, 0.5, 0.350, false, 60.0, 1),
    COIN_CR2032("CR2032 (Li-MnO2)", 3.0, 2.0, 3.3, 220.0, 0.2, 10.0, false, 70.0, 1),
    LITHIUM_THIONYL("Li-SOCl2", 3.6, 3.0, 3.7, 1500.0, 0.5, 5.0, false, 120.0, 1),
    NICD("NiCd", 1.2, 0.9, 1.45, 1200.0, 5.0, 0.020, true, 80.0, 1000),
    NIMH("NiMH", 1.2, 1.0, 1.42, 2500.0, 3.0, 0.030, true, 65.0, 800),
    LTO("Lithium-Titanate (LTO)", 2.4, 1.5, 2.8, 60000.0, 10.0, 0.0008, true, 200.0, 15000);

    private final String displayName;
    private final double nominalVoltage;
    private final double cutoffVoltage;
    private final double fullChargeVoltage;
    private final double capacityMilliAmpHours;
    private final double maxDischargeCRate;
    private final double internalResistanceOhms;
    private final boolean rechargeable;
    private final double thermalRunawayTempCelsius;
    private final int cycleLife;

    BatteryChemistry(
        String displayName,
        double nominalVoltage,
        double cutoffVoltage,
        double fullChargeVoltage,
        double capacityMilliAmpHours,
        double maxDischargeCRate,
        double internalResistanceOhms,
        boolean rechargeable,
        double thermalRunawayTempCelsius,
        int cycleLife
    ) {
        this.displayName = displayName;
        this.nominalVoltage = nominalVoltage;
        this.cutoffVoltage = cutoffVoltage;
        this.fullChargeVoltage = fullChargeVoltage;
        this.capacityMilliAmpHours = capacityMilliAmpHours;
        this.maxDischargeCRate = maxDischargeCRate;
        this.internalResistanceOhms = internalResistanceOhms;
        this.rechargeable = rechargeable;
        this.thermalRunawayTempCelsius = thermalRunawayTempCelsius;
        this.cycleLife = cycleLife;
    }

    public String getDisplayName() {
        return displayName;
    }

    public double getNominalVoltage() {
        return nominalVoltage;
    }

    public double getCutoffVoltage() {
        return cutoffVoltage;
    }

    public double getFullChargeVoltage() {
        return fullChargeVoltage;
    }

    public double getCapacityMilliAmpHours() {
        return capacityMilliAmpHours;
    }

    public double getCapacityAmpHours() {
        return capacityMilliAmpHours / 1000.0;
    }

    public double getMaxDischargeCRate() {
        return maxDischargeCRate;
    }

    public double getMaxDischargeCurrentAmps() {
        return getCapacityAmpHours() * maxDischargeCRate;
    }

    public double getInternalResistanceOhms() {
        return internalResistanceOhms;
    }

    public boolean isRechargeable() {
        return rechargeable;
    }

    public double getThermalRunawayTempCelsius() {
        return thermalRunawayTempCelsius;
    }

    public int getCycleLife() {
        return cycleLife;
    }
}
