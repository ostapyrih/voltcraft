package com.ostapyrih.voltcraft.simulation.grid;

import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import net.minecraft.server.world.ServerWorld;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Exactly-once discrete phase per game tick.
 *
 * <p>Every grid block entity is ticked twice per server tick by construction:
 * once by its vanilla block-entity ticker during the world tick and once by
 * the kernel pre-tick at {@code END_WORLD_TICK}. Both entries call
 * {@code tickElectrical}, which advances counters (MPPT absorption timeouts
 * and debounce windows, mismatch-trip delays, thermal models, EU conversion
 * bookkeeping, energy meters). Without dedupe every discrete rate runs at
 * double speed and every debounce window is halved, so absorption/float and
 * protection timing depend on which entry point runs first.</p>
 *
 * <p>Call {@link #claim} first in every {@code tickElectrical}; a second call
 * for the same block in the same world tick returns false and the caller must
 * return immediately. Keyed by block-entity identity in a weak map, so
 * unloaded chunks evaporate without bookkeeping. World-free harnesses (null
 * world) always claim successfully, keeping unit-test timing unchanged.</p>
 */
public final class ElectricalTickDedupe {
    private static final Map<KernelAttachedBlock, Long> LAST_TICK =
        Collections.synchronizedMap(new WeakHashMap<>());

    private ElectricalTickDedupe() {
    }

    /**
     * Claims this tick's discrete phase for a block.
     *
     * @param block block requesting its discrete update
     * @param world server world (null in world-free tests: always granted)
     * @return true if this is the block's first discrete update this tick
     */
    public static boolean claim(KernelAttachedBlock block, ServerWorld world) {
        if (block == null) {
            return false;
        }
        if (world == null) {
            return true;
        }
        long time = world.getTime();
        synchronized (LAST_TICK) {
            Long last = LAST_TICK.get(block);
            if (last != null && last.longValue() == time) {
                return false;
            }
            LAST_TICK.put(block, time);
            return true;
        }
    }
}
