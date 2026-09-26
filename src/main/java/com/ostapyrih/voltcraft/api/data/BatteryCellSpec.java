package com.ostapyrih.voltcraft.api.data;

/**
 * Electrochemical specification record for an individual cell chemistry.
 *
 * @param chemistryName Identifier name of the chemistry
 * @param nominalVoltage Volts (V)
 * @param cutoffVoltage Minimum discharge cutoff voltage (V)
 * @param fullChargeVoltage Maximum terminal charge voltage (V)
 * @param capacityMilliAmpHours Rated capacity (mAh)
 * @param maxDischargeCRate Maximum continuous discharge rate (C)
 * @param internalResistanceOhms Internal series resistance (Ohms)
 * @param rechargeable Whether the cell can be recharged
 */
public record BatteryCellSpec(
    String chemistryName,
    double nominalVoltage,
    double cutoffVoltage,
    double fullChargeVoltage,
    double capacityMilliAmpHours,
    double maxDischargeCRate,
    double internalResistanceOhms,
    boolean rechargeable
) {
    public static final BatteryCellSpec LI_ION_18650 = new BatteryCellSpec(
        "LiCoO2", 3.7, 2.8, 4.2, 3000.0, 5.0, 0.025, true
    );

    public static final BatteryCellSpec LI_ION_21700 = new BatteryCellSpec(
        "NMC", 3.7, 2.7, 4.2, 5000.0, 10.0, 0.015, true
    );

    public static final BatteryCellSpec ALKALINE = new BatteryCellSpec(
        "Zn-MnO2", 1.5, 0.9, 1.6, 2000.0, 1.0, 0.150, false
    );

    public static final BatteryCellSpec ZINC_CARBON = new BatteryCellSpec(
        "Zn-C", 1.5, 0.8, 1.55, 800.0, 0.5, 0.350, false
    );

    public static final BatteryCellSpec COIN_CR2032 = new BatteryCellSpec(
        "Li-MnO2", 3.0, 2.0, 3.2, 220.0, 0.2, 10.0, false
    );

    public static final BatteryCellSpec NIMH = new BatteryCellSpec(
        "NiMH", 1.2, 1.0, 1.45, 2500.0, 3.0, 0.030, true
    );

    public static final BatteryCellSpec NICD = new BatteryCellSpec(
        "NiCd", 1.2, 0.9, 1.4, 1200.0, 5.0, 0.020, true
    );
}
