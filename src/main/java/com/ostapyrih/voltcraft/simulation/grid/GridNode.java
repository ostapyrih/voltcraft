package com.ostapyrih.voltcraft.simulation.grid;

import com.ostapyrih.voltcraft.api.grid.IGridNode;
import net.minecraft.util.math.BlockPos;

/**
 * Concrete grid node located at a discrete BlockPos in the world.
 */
public class GridNode implements IGridNode {
    private final BlockPos position;
    private final int nodeId;
    private final boolean ground;
    private double voltage;

    public GridNode(BlockPos position, int nodeId, boolean ground) {
        this.position = position.toImmutable();
        this.nodeId = nodeId;
        this.ground = ground;
        this.voltage = 0.0;
    }

    @Override
    public BlockPos getPosition() {
        return position;
    }

    @Override
    public int getNodeId() {
        return nodeId;
    }

    @Override
    public double getVoltage() {
        return voltage;
    }

    @Override
    public void setVoltage(double voltage) {
        this.voltage = voltage;
    }

    @Override
    public boolean isGround() {
        return ground;
    }
}
