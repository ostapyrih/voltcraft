package com.ostapyrih.voltcraft.api.grid;

/**
 * Opt-in callback for grid participants that cache topology state (grid UUIDs, node sets)
 * and must invalidate that cache whenever the grid they are attached to mutates in place
 * without its UUID changing (node added/removed, conductor added/removed, merge, split).
 * Without this, participants only re-sync on UUID change, which never fires for e.g.
 * panel replacement or bank switching.
 */
public interface IGridTopologyListener {
    void onGridTopologyChanged();
}
