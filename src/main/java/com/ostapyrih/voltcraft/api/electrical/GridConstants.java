package com.ostapyrih.voltcraft.api.electrical;

/**
 * Frozen simulation constants for the electrical kernel and solver.
 */
public final class GridConstants {
    private GridConstants() {
    }

    /** Fixed simulation timestep in seconds. */
    public static final double DT = 0.05;
    /** Small shunt conductance to ground applied to every nodal diagonal (S). Real and power-relevant. */
    public static final double GMIN = 1e-9;
    /** Newton step convergence tolerance on max voltage delta. */
    public static final double NEWTON_TOL = 1e-6;
    /** Maximum Newton iterations per solve. */
    public static final int NEWTON_MAX_ITER = 40;
    /** Maximum Newton voltage step magnitude per iteration (V). */
    public static final double NEWTON_MAX_STEP = 50.0;
    /** Residual floor below which backtracking accepts a trial immediately. */
    public static final double NEWTON_RESIDUAL_FLOOR = 1e-10;
    /** Linear solver convergence tolerance on the normalized residual. */
    public static final double LINEAR_RESIDUAL_TOL = 1e-9;
    /** Ambient temperature in Celsius. */
    public static final double AMBIENT_C = 20.0;
    /** Grid AC frequency in Hz. */
    public static final double AC_FREQUENCY_HZ = 50.0;
    /** Grid AC angular frequency in rad/s. */
    public static final double AC_OMEGA_RAD_PER_S = 2.0 * Math.PI * 50.0;
}
