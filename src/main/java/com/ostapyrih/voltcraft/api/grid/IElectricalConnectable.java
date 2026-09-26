package com.ostapyrih.voltcraft.api.grid;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;

/**
 * Interface implemented by blocks or entities that participate in topological grid connections.
 */
public interface IElectricalConnectable {
    /**
     * Determines whether this block can connect to an electrical line from the specified direction.
     */
    boolean canConnect(BlockView world, BlockPos pos, Direction side, BlockState state);

    /**
     * Determines whether electrical current conducts freely through this block to its other sides.
     * True for cables, busbars, closed switches, and battery banks.
     * False for multi-port conversion devices (inverters, transformers, DC-DC converters, rectifiers)
     * which couple two isolated circuits without through-conduction.
     */
    default boolean isThroughConductor() {
        return true;
    }
}
