package com.ostapyrih.voltcraft.simulation.solver;

import com.ostapyrih.voltcraft.api.data.Phasor;

/**
 * AC power phasor mathematics and harmonic distortion solver.
 */
public class ACSolver {

    public static final double DEFAULT_FREQUENCY_HZ = 50.0;
    public static final double OMEGA = 2.0 * Math.PI * DEFAULT_FREQUENCY_HZ;

    /**
     * Calculates complex impedance Z = R + j*(X_L - X_C).
     *
     * @param resistanceOhms Pure resistance R (Ohms)
     * @param inductanceHenries Inductance L (H)
     * @param capacitanceFarads Capacitance C (F)
     * @param frequencyHz AC Frequency (Hz)
     * @return Phasor representing impedance in polar form (magnitude |Z| in Ohms, phase angle in radians)
     */
    public static Phasor calculateImpedance(
        double resistanceOhms,
        double inductanceHenries,
        double capacitanceFarads,
        double frequencyHz
    ) {
        double omega = 2.0 * Math.PI * frequencyHz;
        double xL = omega * inductanceHenries;
        double xC = capacitanceFarads > 0.0 ? (1.0 / (omega * capacitanceFarads)) : 0.0;
        double netReactance = xL - xC;

        return Phasor.fromRectangular(resistanceOhms, netReactance);
    }

    /**
     * Calculates RMS AC current from voltage phasor and impedance phasor: I = V / Z.
     */
    public static Phasor calculateCurrent(Phasor voltage, Phasor impedance) {
        if (impedance.magnitude() <= 0.0) {
            return Phasor.ZERO;
        }
        double currentMag = voltage.magnitude() / impedance.magnitude();
        double currentAngle = voltage.phaseAngleRad() - impedance.phaseAngleRad();
        return new Phasor(currentMag, currentAngle);
    }

    /**
     * Power triangle result.
     *
     * @param realPowerWatts Real power P = V * I * cos(phi)
     * @param reactivePowerVAR Reactive power Q = V * I * sin(phi)
     * @param apparentPowerVA Apparent power S = V * I
     * @param powerFactor cos(phi)
     */
    public record ACPowerResult(
        double realPowerWatts,
        double reactivePowerVAR,
        double apparentPowerVA,
        double powerFactor
    ) {}

    public static ACPowerResult calculatePower(Phasor voltage, Phasor current) {
        double vRms = voltage.magnitude();
        double iRms = current.magnitude();
        double apparentPower = vRms * iRms;

        double phi = voltage.phaseAngleRad() - current.phaseAngleRad();
        double powerFactor = Math.cos(phi);
        double realPower = apparentPower * powerFactor;
        double reactivePower = apparentPower * Math.sin(phi);

        return new ACPowerResult(realPower, reactivePower, apparentPower, powerFactor);
    }

    /**
     * Computes Total Harmonic Distortion (THD) percentage from harmonic amplitudes.
     * THD = sqrt(sum(V_n^2 for n >= 2)) / V_1 * 100%
     */
    public static double calculateTHDPercent(double fundamentalRms, double[] harmonicRmsAmplitudes) {
        if (fundamentalRms <= 0.0 || harmonicRmsAmplitudes == null || harmonicRmsAmplitudes.length == 0) {
            return 0.0;
        }
        double sumSq = 0.0;
        for (double h : harmonicRmsAmplitudes) {
            sumSq += h * h;
        }
        return (Math.sqrt(sumSq) / fundamentalRms) * 100.0;
    }
}
