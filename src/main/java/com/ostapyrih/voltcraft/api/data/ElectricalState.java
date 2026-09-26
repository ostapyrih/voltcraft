package com.ostapyrih.voltcraft.api.data;

/**
 * Operational state machine for any electrical node or component.
 */
public enum ElectricalState {
    /** Component is disconnected or unpowered. */
    OFF,
    /** Normal operating conditions within allowable voltage and current thresholds. */
    NOMINAL,
    /** Supply voltage is lower than minimum operating threshold (V < Vmin). */
    BROWNOUT,
    /** Supply voltage or current exceeds max tolerance (V > Vmax). */
    SURGE,
    /** Component has suffered catastrophic failure (blown fuse, melted core, vaporized conductor). */
    DESTROYED
}
