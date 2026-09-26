package com.ostapyrih.voltcraft.simulation.solver;

/**
 * Conductor thermal equilibrium physics solver.
 * Models Joule heating (P = I^2 * R(T)), convective cooling, insulation breakdown, and arc flash ignition.
 */
public class ThermalEquilibrium {

    /** Default ambient temperature in degrees Celsius (20°C). */
    public static final double T_AMBIENT = 20.0;

    /**
     * Physical parameters of a conductor segment.
     *
     * @param baseResistance Resistance at reference temperature (Ohms)
     * @param tempCoefficient Temperature coefficient alpha (1/°C, e.g. 0.00393 for copper)
     * @param heatCapacity Conductor segment thermal capacity in Joules / °C
     * @param coolingRate Heat transfer dissipation coefficient h*A (Watts / °C)
     * @param maxInsulationTemp Temperature where insulation melts (°C, typically 120°C)
     * @param meltingTemp Temperature where conductor metal melts/vaporizes (°C, 1085°C for Cu)
     */
    public record ThermalSpec(
        double baseResistance,
        double tempCoefficient,
        double heatCapacity,
        double coolingRate,
        double maxInsulationTemp,
        double meltingTemp
    ) {
        public static final ThermalSpec COPPER_2_5_MM2 = new ThermalSpec(
            0.0068, 0.00393, 8.5, 0.15, 120.0, 1085.0
        );

        public static final ThermalSpec COPPER_HEAVY_16_MM2 = new ThermalSpec(
            0.0011, 0.00393, 55.0, 0.55, 140.0, 1085.0
        );

        public static final ThermalSpec ALUMINUM_25_MM2 = new ThermalSpec(
            0.0011, 0.00429, 60.0, 0.65, 105.0, 660.0
        );

        public static final ThermalSpec NICHROME_HEATER = new ThermalSpec(
            2.50, 0.0004, 15.0, 0.08, 300.0, 1400.0
        );
    }

    public enum ThermalStatus {
        SAFE,
        HOT_WARNING,
        INSULATION_MELTING,
        CONDUCTOR_MELTED
    }

    public record StepResult(double newTemperature, double newResistance, double jouleLossWatts, ThermalStatus status) {}

    /**
     * Steps the thermal state of a conductor forward by dtSeconds.
     *
     * @param currentTemperature Current temperature in °C
     * @param currentAmps Electric current through conductor (A)
     * @param ambientTemp Ambient world temperature (°C)
     * @param dtSeconds Timestep (e.g. 0.05 seconds for 1 Minecraft tick)
     * @param spec Conductor thermal specification
     */
    public static StepResult step(
        double currentTemperature,
        double currentAmps,
        double ambientTemp,
        double dtSeconds,
        ThermalSpec spec
    ) {
        // 1. Calculate temperature-dependent resistance
        double deltaTFromRef = currentTemperature - T_AMBIENT;
        double rEffective = spec.baseResistance * (1.0 + spec.tempCoefficient * deltaTFromRef);
        if (rEffective <= 0.0) {
            rEffective = 1e-6;
        }

        // 2. Joule heating generation: P_gen = I^2 * R
        double pGen = currentAmps * currentAmps * rEffective;

        // 3. Convective/radiative heat dissipation: P_loss = h * A * (T - T_amb)
        double deltaTAmbient = currentTemperature - ambientTemp;
        double pDissipated = spec.coolingRate * deltaTAmbient;

        // 4. Net thermal change: dT = (P_gen - P_dissipated) / C_th * dt
        double netPower = pGen - pDissipated;
        double deltaT = (netPower / spec.heatCapacity) * dtSeconds;
        double nextTemp = currentTemperature + deltaT;

        // Temperature cannot drop below ambient
        if (nextTemp < ambientTemp) {
            nextTemp = ambientTemp;
        }

        // 5. Evaluate thermal status
        ThermalStatus status;
        if (nextTemp >= spec.meltingTemp) {
            status = ThermalStatus.CONDUCTOR_MELTED;
        } else if (nextTemp >= spec.maxInsulationTemp) {
            status = ThermalStatus.INSULATION_MELTING;
        } else if (nextTemp >= spec.maxInsulationTemp * 0.75) {
            status = ThermalStatus.HOT_WARNING;
        } else {
            status = ThermalStatus.SAFE;
        }

        return new StepResult(nextTemp, rEffective, pGen, status);
    }
}
