package com.ostapyrih.voltcraft.simulation.generation;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * High-fidelity celestial irradiance model.
 * Computes solar flux (W/m²), angle of incidence, atmospheric attenuation, and panel electrical output.
 */
public class SolarIrradianceSimulation {

    public static final double STC_IRRADIANCE = 1000.0; // W/m² standard test conditions
    public static final double THERMAL_VOLTAGE = 0.026;  // V (kT/q at 300K)

    public record SolarOutput(
        double irradianceWattsPerM2,
        double electromotiveForce,
        double maxCurrentAmps,
        double internalResistanceOhms,
        double peakPowerAvailableWatts
    ) {}

    /**
     * Calculates the solar irradiance incident on a horizontal surface at the given position and world state.
     */
    public static double calculateIrradiance(ServerWorld world, BlockPos pos, SolarPanelType panelType) {
        if (world == null) return 0.0;

        // Check vertical sky clearance
        if (!world.isSkyVisible(pos.up())) {
            return 0.0;
        }

        // Celestial time: 0 = sunrise, 6000 = solar noon, 12000 = sunset, 18000 = midnight
        long timeOfDay = (world.getTimeOfDay() % 24000L + 24000L) % 24000L;
        double angleRad = ((timeOfDay - 6000.0) / 24000.0) * 2.0 * Math.PI;
        double cosZenith = Math.cos(angleRad);

        if (cosZenith <= 0.0) {
            return 0.0; // Sun is below the horizon (night)
        }

        // Atmospheric air mass attenuation
        double airMass = 1.0 / Math.max(0.1, cosZenith);
        double beamDirect = STC_IRRADIANCE * Math.pow(0.7, Math.pow(airMass, 0.678)) * cosZenith;

        // Weather attenuation
        boolean isRaining = world.isRaining();
        boolean isThundering = world.isThundering();
        double weatherFactor = panelType.getWeatherFactor(isRaining, isThundering);

        return Math.max(0.0, Math.min(STC_IRRADIANCE * 1.2, beamDirect * weatherFactor));
    }

    /**
     * Computes the electrical output parameters (EMF, Max Current, Internal Resistance) for a solar panel.
     */
    public static SolarOutput computeSolarOutput(SolarPanelType type, double irradiance, double ambientTempCelsius) {
        if (irradiance < 1.0) {
            return new SolarOutput(0.0, 0.0, 0.0, 1000.0, 0.0);
        }

        double gRatio = irradiance / STC_IRRADIANCE;

        // Temperature coefficient adjustment
        double tempDelta = ambientTempCelsius - 25.0;
        double tempDerating = 1.0 + (type.getTempCoefficient() * tempDelta);

        // Current scales linearly with irradiance
        double imp = type.getImp() * gRatio * tempDerating;

        // Voltage scales logarithmically with irradiance
        double voc = Math.max(0.0, (type.getVoc() + (THERMAL_VOLTAGE * Math.log(gRatio))) * tempDerating);
        double vmp = Math.max(0.0, (type.getVmp() + (THERMAL_VOLTAGE * Math.log(gRatio))) * tempDerating);

        // Equivalent Thevenin resistance: R_th = (Voc - Vmp) / Imp
        double rInt = Math.max(0.05, (voc - vmp) / Math.max(0.01, imp));
        double peakW = vmp * imp;

        return new SolarOutput(irradiance, voc, imp, rInt, peakW);
    }
}
