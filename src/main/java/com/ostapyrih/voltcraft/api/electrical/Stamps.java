package com.ostapyrih.voltcraft.api.electrical;

import java.util.Objects;

/**
 * Linear stamping helpers for nodal analysis ({@code Y * V = I}).
 *
 * <h2>Sign convention (frozen)</h2>
 * <ul>
 *   <li>{@code admittance(Y,a,b,g)}: {@code Y[a][a]+=g; Y[b][b]+=g; Y[a][b]-=g; Y[b][a]-=g}.</li>
 *   <li>{@code draw(I,a,b,i)}: {@code I[a]-=i; I[b]+=i}. Positive {@code i} is current
 *       flowing from terminal {@code a} to terminal {@code b}.</li>
 *   <li>{@code thevenin(Y,I,a,b,g,emf)}: voltage source with series admittance {@code g},
 *       EMF {@code emf}, positive terminal {@code a}. Equivalent Norton form: admittance
 *       {@code g} between {@code a} and {@code b}, plus current source {@code g*emf}
 *       injected into node {@code a} (and drawn from node {@code b}).</li>
 *   <li>{@code powerInto(Vt,It,from,to)}: returns
 *       {@code sum_{k=from}^{to-1} Re(Vt[k]*conj(It[k]))}. Positive when the element
 *       consumes power. Throws {@link IllegalArgumentException} for invalid ranges or
 *       length mismatch.</li>
 * </ul>
 */
public final class Stamps {
    private Stamps() {
    }

    /**
     * Fallback-region tracking flag (Phase 2 escalation-resolution amendment).
     *
     * <p>Set when a stamp takes its numerical-stability fallback path instead of
     * its physical linearization (see the per-method fallback conditions below).
     * Backed by a {@link ThreadLocal} so concurrent solves on different threads
     * do not interfere. The kernel ({@code ElectricalKernel}) owns the protocol:
     * it calls {@link #clearFallbackFlag()} before each final-system build and
     * reads {@link #isFallbackFlagSet()} afterwards. Regular (non-fallback)
     * branches neither set nor clear the flag.</p>
     */
    private static final ThreadLocal<Boolean> FALLBACK_FLAG = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Clears the fallback flag. Called by the kernel before each final-system build. */
    public static void clearFallbackFlag() { FALLBACK_FLAG.set(Boolean.FALSE); }

    /** Returns true if any stamp marked the fallback path since the last clear. */
    public static boolean isFallbackFlagSet() { return Boolean.TRUE.equals(FALLBACK_FLAG.get()); }

    /** Marks the fallback path as taken. Called by stamps in their else/floor branches. */
    private static void markFallback() { FALLBACK_FLAG.set(Boolean.TRUE); }

    /**
     * Stamps a series admittance between nodes {@code a} and {@code b}.
     */
    public static void admittance(Complex[][] y, int a, int b, Complex g) {
        Objects.requireNonNull(y, "y");
        Objects.requireNonNull(g, "g");
        checkNode(y.length, a);
        checkNode(y.length, b);
        y[a][a] = y[a][a].add(g);
        y[b][b] = y[b][b].add(g);
        y[a][b] = y[a][b].sub(g);
        y[b][a] = y[b][a].sub(g);
    }

    /**
     * Draws current {@code i} from node {@code a} to node {@code b}:
     * {@code I[a]-=i; I[b]+=i}.
     */
    public static void draw(Complex[] in, int a, int b, Complex current) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(current, "current");
        checkNode(in.length, a);
        checkNode(in.length, b);
        in[a] = in[a].sub(current);
        in[b] = in[b].add(current);
    }

    /**
     * Convenience overload accepting a real current value.
     */
    public static void draw(Complex[] in, int a, int b, double current) {
        draw(in, a, b, new Complex(current, 0.0));
    }

    /**
     * Stamps a Thevenin source: series admittance {@code g} with EMF {@code emf},
     * positive terminal {@code a}. Norton equivalent: admittance {@code g} between
     * {@code a} and {@code b}, current source {@code g*emf} injected into node
     * {@code a}.
     */
    public static void thevenin(Complex[][] y, Complex[] in, int a, int b, Complex g, Complex emf) {
        Objects.requireNonNull(y, "y");
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(g, "g");
        Objects.requireNonNull(emf, "emf");
        checkNode(y.length, a);
        checkNode(y.length, b);
        if (in.length != y.length) {
            throw new IllegalArgumentException(
                    "Y/I size mismatch: Y=" + y.length + ", I=" + in.length);
        }
        admittance(y, a, b, g);
        Complex norton = g.mul(emf);
        in[a] = in[a].add(norton);
        in[b] = in[b].sub(norton);
    }

    /**
     * Returns power consumed by an element over a terminal range:
     * {@code sum_{k=from}^{to-1} Re(Vt[k]*conj(It[k]))}.
     *
     * @throws IllegalArgumentException for invalid ranges or length mismatch
     */
    public static double powerInto(Complex[] vt, Complex[] it, int from, int to) {
        Objects.requireNonNull(vt, "vt");
        Objects.requireNonNull(it, "it");
        if (vt.length != it.length) {
            throw new IllegalArgumentException(
                    "Vt/It length mismatch: Vt=" + vt.length + ", It=" + it.length);
        }
        if (from < 0 || to < 0 || from > to || to > vt.length) {
            throw new IllegalArgumentException(
                    "Invalid range [" + from + "," + to + ") for length " + vt.length);
        }
        double power = 0.0;
        for (int k = from; k < to; k++) {
            Complex v = vt[k];
            Complex c = it[k];
            Objects.requireNonNull(v, "vt[" + k + "]");
            Objects.requireNonNull(c, "it[" + k + "]");
            power += v.re * c.re + v.im * c.im;
        }
        return power;
    }

    private static void checkNode(int n, int node) {
        if (node < 0 || node >= n) {
            throw new IllegalArgumentException("Node index out of range: " + node + " (n=" + n + ")");
        }
    }

    /**
     * Stamps a DC-only constant-power load between nodes {@code a} and {@code b}.
     *
     * <p>Linearization of {@code I(v)=P/v} around {@code v0}:
     * {@code I(v) ≈ P/v0 + (−P/v0²)·(v−v0) = (−P/v0²)·v + 2P/v0},
     * so {@code g = −P/v0²} (negative differential conductance) and
     * {@code iEq = 2P/v0}, stamped as {@code admittance(g) + draw(iEq)}.
     * Below {@code vMin}, resistive fallback {@code R = vMin²/P} for
     * numerical stability (stamped as {@code admittance(P/vMin²)} with zero
     * parallel current).</p>
     *
     * <p><b>Fallback condition:</b> the flag (see {@link #isFallbackFlagSet()})
     * is marked when {@code v0 < vMin || v0 <= 1e-6} (the else branch). The
     * regular branch does not mark (and does not clear; the kernel clears).</p>
     *
     * @throws IllegalArgumentException if {@code omega != 0.0} (DC-only) or if
     *         {@code P} / {@code vMin} are non-finite or {@code <= 0.0}
     */
    public static void constantPower(Complex[][] y, Complex[] in, int a, int b, Complex[] v,
                                     double p, double vMin, double omega) {
        if (omega != 0.0) {
            throw new IllegalArgumentException("constantPower is DC-only: omega=" + omega);
        }
        if (!Double.isFinite(p) || p <= 0.0 || !Double.isFinite(vMin) || vMin <= 0.0) {
            throw new IllegalArgumentException(
                    "constantPower requires finite P>0 and vMin>0: P=" + p + ", vMin=" + vMin);
        }
        Objects.requireNonNull(y, "y");
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(v, "v");
        double v0 = v[a].sub(v[b]).real();
        double g;
        double iEq;
        if (v0 >= vMin && v0 > 1e-6) {
            g = -p / (v0 * v0);
            iEq = 2.0 * p / v0;
        } else {
            markFallback();
            g = p / (vMin * vMin);
            iEq = 0.0;
        }
        admittance(y, a, b, new Complex(g, 0.0));
        draw(in, a, b, iEq);
    }

    /**
     * Stamps a DC-only constant-current load drawing {@code iSet} from node
     * {@code a} toward node {@code b} (via {@code draw(I,a,b,iSet)}) while the
     * terminal voltage is at or above {@code vMin}. Below {@code vMin}, stamps
     * a resistive fallback admittance {@code iSet/vMin} with zero parallel
     * current.
     *
     * <p><b>Fallback condition:</b> the flag (see {@link #isFallbackFlagSet()})
     * is marked when {@code v0 < vMin} (the else branch). The regular branch
     * does not mark (and does not clear; the kernel clears).</p>
     *
     * @throws IllegalArgumentException if {@code omega != 0.0} (DC-only) or if
     *         {@code iSet} / {@code vMin} are non-finite or {@code <= 0.0}
     */
    public static void constantCurrent(Complex[][] y, Complex[] in, int a, int b, Complex[] v,
                                       double iSet, double vMin, double omega) {
        if (omega != 0.0) {
            throw new IllegalArgumentException("constantCurrent is DC-only: omega=" + omega);
        }
        if (!Double.isFinite(iSet) || iSet <= 0.0 || !Double.isFinite(vMin) || vMin <= 0.0) {
            throw new IllegalArgumentException(
                    "constantCurrent requires finite Iset>0 and vMin>0: Iset=" + iSet + ", vMin=" + vMin);
        }
        Objects.requireNonNull(y, "y");
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(v, "v");
        double v0 = v[a].sub(v[b]).real();
        if (v0 >= vMin) {
            draw(in, a, b, iSet);
        } else {
            markFallback();
            admittance(y, a, b, new Complex(iSet / vMin, 0.0));
        }
    }

    /**
     * Stamps a DC-only smoothed one-way Thevenin element between nodes {@code a}
     * and {@code b}. With {@code sourceOnly=true} the element delivers power
     * like a voltage source of EMF {@code emf} for {@code v &lt; emf} and blocks
     * reverse current; with {@code sourceOnly=false} it sinks current like a
     * load for {@code v &gt; emf} and blocks below. The knee is smoothed by a
     * softplus of softness {@code s} volts; the stamp is the Newton
     * linearization {@code iEq = i0 − dIdv·v0} as
     * {@code admittance(dIdv) + draw(iEq)}.
     *
     * <p><b>Fallback condition:</b> this element has no {@code vMin}; its
     * numerical crutch is the derivative floor {@code 1e-9·g} in
     * {@code dIdv = max(g·sigmoid(x/s), 1e-9·g)}. The flag (see
     * {@link #isFallbackFlagSet()}) is marked when the floor is active, i.e.
     * when {@code g·sigmoid(x/s) < 1e-9·g}.</p>
     *
     * @throws IllegalArgumentException if {@code omega != 0.0} (DC-only), if
     *         {@code g} / {@code s} are non-finite or {@code <= 0.0}, or if
     *         {@code emf} is non-finite (defensive; the spec constrains only
     *         {@code g} and {@code s})
     */
    public static void oneWayThevenin(Complex[][] y, Complex[] in, int a, int b, Complex[] v,
                                      double g, double emf, boolean sourceOnly, double s, double omega) {
        if (omega != 0.0) {
            throw new IllegalArgumentException("oneWayThevenin is DC-only: omega=" + omega);
        }
        if (!Double.isFinite(g) || g <= 0.0 || !Double.isFinite(s) || s <= 0.0) {
            throw new IllegalArgumentException(
                    "oneWayThevenin requires finite g>0 and s>0: g=" + g + ", s=" + s);
        }
        if (!Double.isFinite(emf)) {
            throw new IllegalArgumentException("oneWayThevenin requires finite emf: " + emf);
        }
        Objects.requireNonNull(y, "y");
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(v, "v");
        double v0 = v[a].sub(v[b]).real();
        double x = sourceOnly ? (emf - v0) : (v0 - emf);
        double sgn = sourceOnly ? -1.0 : 1.0;
        double i0 = sgn * g * (softplus(x, s) - s * Math.log(2.0));
        double dIdv = Math.max(g * sigmoid(x / s), 1e-9 * g);
        if (g * sigmoid(x / s) < 1e-9 * g) {
            markFallback();
        }
        double iEq = i0 - dIdv * v0;
        admittance(y, a, b, new Complex(dIdv, 0.0));
        draw(in, a, b, iEq);
    }

    /**
     * Stable softplus: {@code s*ln(1+exp(x/s))}. Avoids overflow for large
     * {@code x/s} and underflow churn for very negative {@code x/s}.
     */
    private static double softplus(double x, double s) {
        double z = x / s;
        if (z > 50.0) {
            return x;
        }
        if (z < -50.0) {
            return s * Math.exp(z);
        }
        return s * Math.log1p(Math.exp(z));
    }

    /**
     * Logistic sigmoid with overflow guards: direct form for {@code z >= 0},
     * {@code exp(z)} form otherwise.
     */
    private static double sigmoid(double z) {
        if (z >= 0.0) {
            return 1.0 / (1.0 + Math.exp(-z));
        }
        double e = Math.exp(z);
        return e / (1.0 + e);
    }
}
