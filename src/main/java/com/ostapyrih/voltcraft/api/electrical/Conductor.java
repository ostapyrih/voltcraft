package com.ostapyrih.voltcraft.api.electrical;

/**
 * A resistive conductor branch between two nodal indices.
 *
 * <p>Conductors are stamped as linear admittances by the kernel. The kernel
 * owns no thermal integration in Phase 1; temperature is carried on the
 * conductor instance itself.</p>
 */
public interface Conductor {
    /** First nodal index. */
    int nodeA();

    /** Second nodal index. */
    int nodeB();

    /** Ohmic resistance in ohms. Must be finite and &gt; 0. */
    double resistance();

    /** Current temperature in Celsius. */
    double temperature();

    /** Sets the current temperature in Celsius. */
    void setTemperature(double celsius);

    /** Thermal mass in J/K. Must be finite and &gt; 0. */
    double heatCapacity();

    /** Cooling coefficient in W/K. Must be finite and &gt;= 0. */
    double coolingCoeff();

    /** Melting temperature in Celsius. Must be finite. */
    double meltingTemp();
}
