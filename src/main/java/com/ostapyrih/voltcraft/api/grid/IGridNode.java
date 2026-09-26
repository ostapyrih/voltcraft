package com.ostapyrih.voltcraft.api.grid;

import net.minecraft.util.math.BlockPos;

/**
 * Represents a single topological node (connection point) within an electrical network.
 */
public interface IGridNode {
    /**
     * @return World position of this node.
     */
    BlockPos getPosition();

    /**
     * @return Unique ID or index within the grid graph.
     */
    int getNodeId();

    /**
     * @return Current calculated electrical potential (voltage) relative to ground (V).
     */
    double getVoltage();

    /**
     * Updates the calculated nodal voltage.
     *
     * @param voltage Volts (V)
     */
    void setVoltage(double voltage);

    /**
     * @return True if this node is considered the ground reference (0V).
     */
    boolean isGround();
}
