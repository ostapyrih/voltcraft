package com.ostapyrih.voltcraft.simulation.solver;

import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;

import java.util.Objects;

/**
 * Dense complex nodal solver ({@code Y * V = I}) via Gaussian elimination
 * with max-magnitude partial pivoting.
 *
 * <p>Frozen pivot semantics: after the max-magnitude pivot search, a pivot is
 * singular when its magnitude is exactly zero or non-finite. No tolerances are
 * used. An elimination factor {@code f} that is exactly {@code 0.0} performs no
 * row update and is not counted; any other {@code f} performs one counted row
 * update. {@link SolveResult#linearEliminationSteps} counts only actual
 * Gaussian-elimination row updates.</p>
 *
 * <p>Caller arrays are never mutated; the system is solved on defensive copies.</p>
 */
public final class ComplexNodalSolver {
    private ComplexNodalSolver() {
    }

    /**
     * Result of one linear solve.
     *
     * @param voltage nodal voltages (last iterate; finite unless the input system was non-finite)
     * @param converged {@code !singular && isFinite(residual) && residual < LINEAR_RESIDUAL_TOL}
     * @param singular true if any pivot was exactly zero or non-finite after pivoting
     * @param residual normalized residual {@code ||YV-I|| / max(||I||,||YV||,1)}
     * @param linearEliminationSteps number of actual elimination row updates performed
     */
    public record SolveResult(Complex[] voltage, boolean converged, boolean singular,
                              double residual, int linearEliminationSteps) {
    }

    /**
     * Solves {@code Y * V = I}.
     *
     * @param y nodal admittance matrix (not mutated)
     * @param injections nodal current vector (not mutated)
     * @return solve result
     */
    public static SolveResult solve(Complex[][] y, Complex[] injections) {
        Objects.requireNonNull(y, "y");
        Objects.requireNonNull(injections, "injections");
        int n = y.length;
        if (injections.length != n) {
            throw new IllegalArgumentException(
                    "Y/I size mismatch: Y=" + n + ", I=" + injections.length);
        }
        for (int r = 0; r < n; r++) {
            Objects.requireNonNull(y[r], "y[" + r + "]");
            if (y[r].length != n) {
                throw new IllegalArgumentException(
                        "Y must be square: row " + r + " has length " + y[r].length + " (n=" + n + ")");
            }
        }

        // Defensive copies: never mutate caller arrays.
        Complex[][] a = new Complex[n][n];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                a[r][c] = Objects.requireNonNull(y[r][c], "y[" + r + "][" + c + "]");
            }
        }
        Complex[] b = new Complex[n];
        for (int r = 0; r < n; r++) {
            b[r] = Objects.requireNonNull(injections[r], "injections[" + r + "]");
        }

        boolean singular = false;
        int linearEliminationSteps = 0;

        // Forward elimination with max-magnitude partial pivoting.
        for (int col = 0; col < n; col++) {
            // Pivot search (does NOT count toward linearEliminationSteps).
            int pivotRow = col;
            double pivotMag = a[col][col].magnitude();
            for (int r = col + 1; r < n; r++) {
                double mag = a[r][col].magnitude();
                if (mag > pivotMag) {
                    pivotMag = mag;
                    pivotRow = r;
                }
            }
            double pivotMagnitude = a[pivotRow][col].magnitude();
            if (pivotMagnitude == 0.0 || !Double.isFinite(pivotMagnitude)) {
                singular = true;
                // Zero elimination row updates for this column; do not divide by pivot.
                continue;
            }
            // Swap pivot row into position (does NOT count).
            if (pivotRow != col) {
                Complex[] tmpRow = a[col];
                a[col] = a[pivotRow];
                a[pivotRow] = tmpRow;
                Complex tmpB = b[col];
                b[col] = b[pivotRow];
                b[pivotRow] = tmpB;
            }
            Complex pivot = a[col][col];
            for (int r = col + 1; r < n; r++) {
                Complex f = a[r][col].div(pivot);
                if (f.re == 0.0 && f.im == 0.0) {
                    continue;
                }
                for (int c = col; c < n; c++) {
                    a[r][c] = a[r][c].sub(f.mul(a[col][c]));
                }
                b[r] = b[r].sub(f.mul(b[col]));
                linearEliminationSteps++;
            }
        }

        // Back-substitution. Must not divide by zero/non-finite pivots:
        // such rows yield x[i] = ZERO and iteration continues.
        Complex[] x = new Complex[n];
        for (int i = n - 1; i >= 0; i--) {
            Complex sum = b[i];
            for (int j = i + 1; j < n; j++) {
                sum = sum.sub(a[i][j].mul(x[j]));
            }
            double diagMag = a[i][i].magnitude();
            if (diagMag == 0.0 || !Double.isFinite(diagMag)) {
                x[i] = Complex.ZERO;
                continue;
            }
            x[i] = sum.div(a[i][i]);
        }

        double residual = normalizedResidual(y, injections, x);
        boolean converged = !singular && Double.isFinite(residual)
                && residual < GridConstants.LINEAR_RESIDUAL_TOL;
        return new SolveResult(x, converged, singular, residual, linearEliminationSteps);
    }

    /**
     * Computes the normalized residual {@code ||YV-I|| / max(||I||,||YV||,1)}
     * using Euclidean norms over complex magnitudes.
     */
    public static double normalizedResidual(Complex[][] y, Complex[] injections, Complex[] v) {
        Objects.requireNonNull(y, "y");
        Objects.requireNonNull(injections, "injections");
        Objects.requireNonNull(v, "v");
        int n = y.length;
        if (injections.length != n || v.length != n) {
            throw new IllegalArgumentException(
                    "Size mismatch: Y=" + n + ", I=" + injections.length + ", V=" + v.length);
        }
        double numSq = 0.0;
        double normISq = 0.0;
        double normYVSq = 0.0;
        for (int r = 0; r < n; r++) {
            Complex yv = Complex.ZERO;
            for (int c = 0; c < n; c++) {
                yv = yv.add(y[r][c].mul(v[c]));
            }
            Complex mismatch = yv.sub(injections[r]);
            double m = mismatch.magnitude();
            numSq += m * m;
            double mi = injections[r].magnitude();
            normISq += mi * mi;
            double myv = yv.magnitude();
            normYVSq += myv * myv;
        }
        double denom = Math.max(Math.sqrt(normISq), Math.sqrt(normYVSq));
        denom = Math.max(denom, 1.0);
        return Math.sqrt(numSq) / denom;
    }

    /** Returns an {@code n x n} zero matrix. */
    public static Complex[][] zeroMatrix(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0: " + n);
        }
        Complex[][] m = new Complex[n][n];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                m[r][c] = Complex.ZERO;
            }
        }
        return m;
    }

    /** Returns a zero vector of length {@code n}. */
    public static Complex[] zeroVector(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0: " + n);
        }
        Complex[] v = new Complex[n];
        for (int k = 0; k < n; k++) {
            v[k] = Complex.ZERO;
        }
        return v;
    }
}
