package com.ostapyrih.voltcraft.api.grid;

/**
 * Electrical grid event notification types.
 */
public enum GridEventType {
    OVERCURRENT_WARNING,
    INSULATION_MELTED,
    FUSE_BLOWN,
    BREAKER_TRIPPED,
    ARC_FLASH,
    SHORT_CIRCUIT,
    SUPERCONDUCTOR_QUENCH,
    GRID_MERGED,
    GRID_SPLIT
}
