package com.ostapyrih.voltcraft.api.grid;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Collection;
import java.util.UUID;

/**
 * Central electrical grid interface.
 * Represents an independent connected component of conductors, sources, and loads.
 */
public interface IElectricalGrid {
    /**
     * @return Globally unique identifier for this power network.
     */
    UUID getGridId();

    /**
     * Centralized network tick. Executed once per grid per server tick.
     * Evaluates nodal voltages, branch currents, thermal accumulation, and failure trips.
     */
    void tick(ServerWorld world);

    /**
     * @return All block positions that are members of this network.
     */
    Collection<BlockPos> getNodePositions();

    /**
     * @return Voltage at the specified node position in Volts, or 0.0 if not found.
     */
    double getNodeVoltage(BlockPos pos);

    /**
     * @return Total power generated across this grid (Watts).
     */
    double getTotalGenerationWatts();

    /**
     * @return Total power consumed across this grid (Watts).
     */
    double getTotalConsumptionWatts();

    /**
     * @return Operating AC frequency of this grid in Hz (0.0 for DC).
     */
    default double getFrequency() {
        return 0.0;
    }

    /**
     * Checks if a given coordinate is part of this grid.
     */
    boolean contains(BlockPos pos);

    /**
     * Returns true if all active nodes in loaded chunks are stable.
     */
    boolean isStable();
}
