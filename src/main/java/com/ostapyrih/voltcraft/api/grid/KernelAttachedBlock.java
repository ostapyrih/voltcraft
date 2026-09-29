package com.ostapyrih.voltcraft.api.grid;

import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Server-side contract for a block entity whose electrical behavior is simulated
 * centrally by the kernel-owned island topology ({@code GridManager}).
 *
 * <p>Phase A scope: topology and discovery only. No production block entity
 * implements this interface yet; only test doubles do.</p>
 */
public interface KernelAttachedBlock {
    ElectricalElement getElement();

    BlockPos[] getTerminalPositions();

    double[] getStateArray();

    void setStateArray(double[] state);

    BlockPos getPos();

    void tickElectrical(ServerWorld world);

    default boolean isActiveSource() {
        return false;
    }

    default boolean isACSource() {
        return false;
    }
}
