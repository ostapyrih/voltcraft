package com.ostapyrih.voltcraft.simulation.generation;

/**
 * Photovoltaic technologies with realistic electrical parameters at Standard Test Conditions (STC: 1000 W/m², 25°C, AM 1.5).
 */
public enum SolarPanelType {
    MONOCRYSTALLINE_PERC(
        "Monocrystalline PERC",
        400.0,  // Peak power (W)
        40.0,   // Vmp (V)
        10.0,   // Imp (A)
        48.0,   // Voc (V)
        10.8,   // Isc (A)
        0.215,  // Efficiency (21.5%)
        -0.0035 // Temp coefficient Pmax (%/°C)
    ),
    POLYCRYSTALLINE(
        "Polycrystalline",
        300.0,
        32.0,
        9.38,
        38.5,
        10.1,
        0.170,
        -0.0039
    ),
    THIN_FILM_CDTE(
        "Thin-Film CdTe",
        250.0,
        70.0,
        3.57,
        88.0,
        4.0,
        0.150,
        -0.0025 // Superior high-temp and low-light coefficient
    ),
    CONCENTRATOR_CPV(
        "Concentrator CPV",
        700.0,
        50.0,
        14.0,
        60.0,
        15.0,
        0.380,  // Ultra-high multi-junction efficiency
        -0.0015
    );

    private final String displayName;
    private final double peakPowerWatts;
    private final double vmp;
    private final double imp;
    private final double voc;
    private final double isc;
    private final double efficiency;
    private final double tempCoefficient;

    SolarPanelType(String displayName, double peakPowerWatts, double vmp, double imp, double voc, double isc, double efficiency, double tempCoefficient) {
        this.displayName = displayName;
        this.peakPowerWatts = peakPowerWatts;
        this.vmp = vmp;
        this.imp = imp;
        this.voc = voc;
        this.isc = isc;
        this.efficiency = efficiency;
        this.tempCoefficient = tempCoefficient;
    }

    public String getDisplayName() {
        return displayName;
    }

    public double getPeakPowerWatts() {
        return peakPowerWatts;
    }

    public double getVmp() {
        return vmp;
    }

    public double getImp() {
        return imp;
    }

    public double getVoc() {
        return voc;
    }

    public double getIsc() {
        return isc;
    }

    public double getEfficiency() {
        return efficiency;
    }

    public double getTempCoefficient() {
        return tempCoefficient;
    }

    /**
     * Calculates the solar irradiance weather factor for this panel type.
     * CPV requires direct beam radiation (0 in clouds/rain).
     * Thin-film has enhanced diffuse absorption.
     */
    public double getWeatherFactor(boolean isRaining, boolean isThundering) {
        if (this == CONCENTRATOR_CPV) {
            return (isRaining || isThundering) ? 0.0 : 1.0;
        }
        if (isThundering) {
            return this == THIN_FILM_CDTE ? 0.20 : 0.10;
        }
        if (isRaining) {
            return this == THIN_FILM_CDTE ? 0.40 : 0.25;
        }
        return 1.0;
    }
}
