package com.ostapyrih.voltcraft.simulation.chemistry;

/**
 * High-fidelity electrochemistry simulation engine.
 * Computes non-linear Open-Circuit Voltage (OCV) curves, dynamic internal resistance,
 * Joule heating, convective heat dissipation, and cycle degradation.
 */
public class BatterySimulation {

    public record SimulationStepResult(
        double newSoc,
        double terminalVoltage,
        double newTemperatureCelsius,
        double newHealth,
        boolean thermalRunaway
    ) {}

    /**
     * Calculates the Open-Circuit Voltage (OCV) based on chemistry and State of Charge (SoC).
     *
     * @param chem The battery chemistry
     * @param soc  Normalized State of Charge in range [0.0, 1.0]
     * @return Open-circuit electromotive force in Volts
     */
    public static double getOpenCircuitVoltage(BatteryChemistry chem, double soc) {
        double s = Math.max(0.0, Math.min(1.0, soc));
        double vMin = chem.getCutoffVoltage();
        double vMax = chem.getFullChargeVoltage();
        double deltaV = vMax - vMin;

        return switch (chem) {
            case LI_ION_18650, LI_ION_21700 ->
                // Characteristic lithium-ion curve with flat 3.7V mid-plateau and steep cutoff knees
                vMin + deltaV * (0.12 * s + 0.72 * Math.pow(s, 0.4) + 0.16 * Math.pow(s, 4.0));

            case LIFEPO4 ->
                // Extremely flat 3.25V-3.30V olivine plateau with very steep shoulders at 95% and 5%
                vMin + deltaV * (0.05 * s + 0.80 * Math.pow(s, 0.30) + 0.15 * Math.pow(s, 6.0));

            case LEAD_ACID ->
                // Lead-acid has a nearly linear OCV-SoC relationship
                vMin + deltaV * s;

            case NICD, NIMH ->
                // Nickel chemistries feature a long 1.22V plateau with rapid discharge knee below 15%
                vMin + deltaV * (0.15 * s + 0.75 * Math.pow(s, 0.25) + 0.10 * Math.pow(s, 5.0));

            case LTO ->
                // Lithium titanate: very linear ramp between 2.2V and 2.5V
                vMin + deltaV * (0.20 * s + 0.60 * Math.pow(s, 0.5) + 0.20 * Math.pow(s, 2.0));

            default ->
                // Default smooth cubic curve
                vMin + deltaV * (s * s * (3.0 - 2.0 * s));
        };
    }

    /**
     * Computes temperature and health-adjusted internal resistance R_int.
     * Cold temperatures and cell degradation significantly increase impedance.
     */
    public static double getInternalResistance(BatteryChemistry chem, double soc, double tempCelsius, double health) {
        double baseR = chem.getInternalResistanceOhms();

        // Temperature factor: Arrhenius approximation (doubles at -10°C, slightly decreases up to 40°C)
        double tempK = tempCelsius + 273.15;
        double tempFactor = Math.exp(1200.0 * (1.0 / tempK - 1.0 / 298.15));
        tempFactor = Math.max(0.6, Math.min(4.0, tempFactor));

        // State of Health factor: degraded cells feature higher resistance (up to 2.5x at end of life)
        double h = Math.max(0.1, Math.min(1.0, health));
        double healthFactor = 1.0 + (1.0 - h) * 1.5;

        // Low SoC penalty: internal resistance rises sharply when nearly fully discharged
        double socFactor = 1.0;
        if (soc < 0.1) {
            socFactor += (0.1 - soc) * 5.0;
        }

        return baseR * tempFactor * healthFactor * socFactor;
    }

    /**
     * Steps the electrochemical simulation by dtSeconds under load current.
     *
     * @param chem The chemistry
     * @param currentAmps Positive for discharge, negative for charge
     * @param dtSeconds Timestep in seconds (e.g. 0.05s per tick)
     * @param currentSoc Current SoC [0.0, 1.0]
     * @param tempCelsius Current core temperature
     * @param health Current State of Health [0.0, 1.0]
     * @param ambientTemp Ambient environment temperature
     * @return Updated simulation state
     */
    public static SimulationStepResult step(
        BatteryChemistry chem,
        double currentAmps,
        double dtSeconds,
        double currentSoc,
        double tempCelsius,
        double health,
        double ambientTemp
    ) {
        double ocv = getOpenCircuitVoltage(chem, currentSoc);
        double rInt = getInternalResistance(chem, currentSoc, tempCelsius, health);

        // Terminal voltage under current flow: V_term = OCV - I * R_int
        double terminalV = ocv - (currentAmps * rInt);

        // Fractional charge delta: deltaQ = (I * dt) / (3600 * Q_Ah)
        double capacityAh = chem.getCapacityAmpHours() * Math.max(0.1, health);
        double deltaSoc = (currentAmps * dtSeconds) / (3600.0 * Math.max(1e-4, capacityAh));

        // Charging efficiency (~95% for Li-Ion, 75% for Lead-Acid)
        if (currentAmps < 0.0) {
            deltaSoc *= (chem == BatteryChemistry.LEAD_ACID ? 0.75 : 0.95);
        }

        double newSoc = Math.max(0.0, Math.min(1.0, currentSoc - deltaSoc));

        // Joule heat generation: P = I^2 * R_int
        double joulePowerWatts = currentAmps * currentAmps * rInt;

        // Convective heat dissipation: P_cool = h * (T_cell - T_amb)
        // Natural convective cooling scales with cell size and metallic enclosure surface area
        double hCool = Math.max(0.05, chem.getCapacityAmpHours() * 0.02); // W / K
        double coolingWatts = hCool * (tempCelsius - ambientTemp);

        // Effective thermal mass in Joules/K (scales with physical mass and capacity)
        double thermalMass = chem.getCapacityAmpHours() <= 5.0 ? 40.0 : (chem.getCapacityAmpHours() * 20.0);
        double deltaTemp = ((joulePowerWatts - coolingWatts) * dtSeconds) / thermalMass;
        double newTemp = Math.max(ambientTemp, tempCelsius + deltaTemp);

        // Cycle degradation
        double cycleDebit = (Math.abs(deltaSoc) / 2.0) / (double) chem.getCycleLife();
        double newHealth = Math.max(0.0, health - cycleDebit);

        // Thermal runaway check
        boolean runaway = newTemp >= chem.getThermalRunawayTempCelsius();

        return new SimulationStepResult(newSoc, terminalV, newTemp, newHealth, runaway);
    }
}
