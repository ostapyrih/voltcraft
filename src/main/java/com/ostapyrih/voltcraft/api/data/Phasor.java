package com.ostapyrih.voltcraft.api.data;

/**
 * Representation of an alternating current (AC) sinusoidal quantity as a complex phasor.
 *
 * @param magnitude RMS amplitude of the voltage or current
 * @param phaseAngleRad phase angle in radians
 */
public record Phasor(double magnitude, double phaseAngleRad) {

    public static final Phasor ZERO = new Phasor(0.0, 0.0);

    public double real() {
        return magnitude * Math.cos(phaseAngleRad);
    }

    public double imaginary() {
        return magnitude * Math.sin(phaseAngleRad);
    }

    public static Phasor fromRectangular(double real, double imag) {
        double mag = Math.hypot(real, imag);
        double angle = Math.atan2(imag, real);
        return new Phasor(mag, angle);
    }

    public Phasor add(Phasor other) {
        return fromRectangular(this.real() + other.real(), this.imaginary() + other.imaginary());
    }

    public Phasor subtract(Phasor other) {
        return fromRectangular(this.real() - other.real(), this.imaginary() - other.imaginary());
    }

    public Phasor multiply(double scalar) {
        return new Phasor(this.magnitude * scalar, this.phaseAngleRad);
    }
}
