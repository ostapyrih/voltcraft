package com.ostapyrih.voltcraft.api.electrical;

/**
 * A lumped electrical element stamped into the nodal system.
 *
 * <p>Elements hold no persistent state themselves and perform no state
 * serialization. The {@link com.ostapyrih.voltcraft.simulation.grid.ElectricalKernel
 * kernel} owns all element state ({@code double[]} slots sized by
 * {@link #stateCount()}) and passes the relevant slice into
 * {@link #stamp} on every build.</p>
 */
public interface ElectricalElement {
    /** Number of terminals (entries of the terminals index array). */
    int terminalCount();

    /** Number of kernel-owned real state slots. */
    int stateCount();

    /**
     * Stamps this element's contribution into the nodal system
     * {@code Y * V = I}.
     *
     * @param y nodal admittance matrix (accumulate into)
     * @param i nodal current injection vector (accumulate into)
     * @param terminals nodal indices, length {@link #terminalCount()}
     * @param v current voltage iterate, length = node count
     * @param state kernel-owned state slice, length {@link #stateCount()}
     * @param omega angular frequency in rad/s
     */
    void stamp(Complex[][] y, Complex[] i, int[] terminals, Complex[] v, double[] state, double omega);

    /**
     * Computes state derivatives for future transient integration.
     *
     * @param dxdt output derivative vector (same length as {@code state})
     * @param state current state slice
     * @param vt terminal voltages
     * @param it terminal currents (positive into the element)
     */
    void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it);
}
