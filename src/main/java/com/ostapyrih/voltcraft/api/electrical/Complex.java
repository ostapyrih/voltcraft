package com.ostapyrih.voltcraft.api.electrical;

import java.util.Objects;

/**
 * Immutable complex number for AC phasor / nodal analysis.
 *
 * <p>Value type: all operations return new instances. Division follows raw
 * IEEE-754 double semantics with no zero guard (may yield Inf/NaN).</p>
 */
public final class Complex {
    /** Additive identity. */
    public static final Complex ZERO = new Complex(0.0, 0.0);
    /** Multiplicative identity. */
    public static final Complex ONE = new Complex(1.0, 0.0);

    /** Real part. */
    public final double re;
    /** Imaginary part. */
    public final double im;

    /**
     * Creates a complex number.
     *
     * @param re real part
     * @param im imaginary part
     */
    public Complex(double re, double im) {
        this.re = re;
        this.im = im;
    }

    /** Returns the real part. */
    public double real() {
        return re;
    }

    /** Returns the imaginary part. */
    public double imag() {
        return im;
    }

    /** Returns {@code this + other}. */
    public Complex add(Complex other) {
        Objects.requireNonNull(other, "other");
        return new Complex(re + other.re, im + other.im);
    }

    /** Returns {@code this - other}. */
    public Complex sub(Complex other) {
        Objects.requireNonNull(other, "other");
        return new Complex(re - other.re, im - other.im);
    }

    /** Returns {@code this * other}. */
    public Complex mul(Complex other) {
        Objects.requireNonNull(other, "other");
        return new Complex(re * other.re - im * other.im, re * other.im + im * other.re);
    }

    /**
     * Returns {@code this / other} using raw IEEE-754 double semantics.
     * No zero guard: division by zero yields Inf/NaN per Java semantics.
     */
    public Complex div(Complex other) {
        Objects.requireNonNull(other, "other");
        double denom = other.re * other.re + other.im * other.im;
        return new Complex(
                (re * other.re + im * other.im) / denom,
                (im * other.re - re * other.im) / denom);
    }

    /** Returns the complex conjugate. */
    public Complex conj() {
        return new Complex(re, -im);
    }

    /** Returns the negation. */
    public Complex neg() {
        return new Complex(-re, -im);
    }

    /** Returns the magnitude {@code |this|}. */
    public double magnitude() {
        return Math.hypot(re, im);
    }

    /** Returns the squared magnitude. */
    public double magnitudeSquared() {
        return re * re + im * im;
    }

    /** Returns the phase angle in radians ({@code atan2(im, re)}). */
    public double phase() {
        return Math.atan2(im, re);
    }

    /** Returns true when both parts are finite. */
    public boolean isFinite() {
        return Double.isFinite(re) && Double.isFinite(im);
    }

    /**
     * Constructs a complex number from polar coordinates.
     *
     * @param magnitude magnitude (radius)
     * @param phase phase angle in radians
     * @return {@code magnitude * e^(i*phase)}
     */
    public static Complex polar(double magnitude, double phase) {
        return new Complex(magnitude * Math.cos(phase), magnitude * Math.sin(phase));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Complex other = (Complex) o;
        return Double.compare(re, other.re) == 0 && Double.compare(im, other.im) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(re, im);
    }

    @Override
    public String toString() {
        return re + (im < 0.0 || Double.isNaN(im) ? "" : "+") + im + "i";
    }
}
