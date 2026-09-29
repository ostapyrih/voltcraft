package com.ostapyrih.voltcraft.block.entity.switchgear;

import com.mojang.serialization.Codec;
import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.block.switchgear.CopperBusbarBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.Objects;

/**
 * High-ampacity (500 A) copper busbar kernel adapter.
 *
 * <p>Structural note: all decision logic lives in the static nested
 * {@link BusbarElement} (see {@code EarthBlockEntity} for why: unit-test runtimes
 * cannot initialize {@code BlockEntity} subclasses).</p>
 *
 * <ul>
 *   <li>Terminals: two adjacent positions, FACING-relative {@code [FRONT, BACK]}
 *       ({@code TERMINAL_OFFSETS = {{0,0,-1},{0,0,1}}} is the canonical NORTH
 *       orientation).</li>
 *   <li>States: zero kernel states; always closed (no discrete flag).</li>
 *   <li>Element stamp: unconditional low-impedance series admittance
 *       {@code 1 / R_BUSBAR_OHM} (heavier gauge than branch switches).</li>
 *   <li>{@link #tickElectrical(ServerWorld)}: no-op (item 10).</li>
 * </ul>
 *
 * <p>NBT keys (all new; no predecessor BE): {@code "stateArray"}
 * ({@code Codec.DOUBLE.listOf()}, always empty). No discrete flag to persist.</p>
 */
public class CopperBusbarBlockEntity extends BlockEntity implements KernelAttachedBlock {

    /** Busbar series resistance in ohms (stamped as {@code 1/R} admittance). */
    public static final double R_BUSBAR_OHM = 0.0005;

    /** NBT key for the kernel state slice (always empty for the busbar). */
    public static final String KEY_STATE_ARRAY = "stateArray";

    /**
     * Static kernel element + state/NBT helpers. Fully unit-testable without any
     * registry, world, or block-entity instance.
     */
    public static final class BusbarElement implements ElectricalElement {
        /**
         * Canonical NORTH-orientation terminal offsets: {@code [0]=north (FRONT),
         * [1]=south (BACK)}. Source of truth only for the default facing; the
         * runtime layout is FACING-relative via {@link #resolveTerminals}.
         */
        public static final int[][] TERMINAL_OFFSETS = {{0, 0, -1}, {0, 0, 1}};

        /**
         * FACING-relative terminal resolution (pure, null-world-safe):
         * {@code [FRONT, BACK]} = {@code [pos.offset(facing),
         * pos.offset(facing.getOpposite())]}. A null facing degrades to
         * {@link Direction#NORTH}.
         */
        public static BlockPos[] resolveTerminals(BlockPos pos, Direction facing) {
            Direction f = facing != null ? facing : Direction.NORTH;
            return new BlockPos[]{pos.offset(f), pos.offset(f.getOpposite())};
        }

        @Override
        public int terminalCount() {
            return 2;
        }

        @Override
        public int stateCount() {
            return 0;
        }

        @Override
        public void stamp(Complex[][] y, Complex[] in, int[] terminals, Complex[] v,
                          double[] state, double omega) {
            Stamps.admittance(y, terminals[0], terminals[1],
                new Complex(1.0 / R_BUSBAR_OHM, 0.0));
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
            // No states: no-op.
        }

        /** Validates a kernel state slice for the busbar (must be non-null, length 0). */
        public static void checkState(double[] state) {
            if (state == null || state.length != 0) {
                throw new IllegalArgumentException(
                    "CopperBusbarBlockEntity holds 0 states, got length " + (state == null ? "null" : state.length));
            }
        }

        /** Fresh empty snapshot (defensive: a new array per call). */
        public static double[] snapshotState() {
            return new double[0];
        }

        /** Writes the (empty) state slice. */
        public static void writeNbt(WriteView view) {
            view.put(KEY_STATE_ARRAY, Codec.DOUBLE.listOf(), List.of());
        }

        /** Reads and discards the state slice (nothing to restore for 0 states). */
        public static void readNbt(ReadView view) {
            view.read(KEY_STATE_ARRAY, Codec.DOUBLE.listOf()).orElse(List.of());
        }
    }

    private final ElectricalElement element = new BusbarElement();

    public CopperBusbarBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public CopperBusbarBlockEntity(BlockPos pos, BlockState state) {
        this(VoltcraftBlockEntityTypes.COPPER_BUSBAR_BLOCK_ENTITY, pos, state);
    }

    @Override
    public ElectricalElement getElement() {
        return element;
    }

    @Override
    public BlockPos[] getTerminalPositions() {
        return BusbarElement.resolveTerminals(pos, readFacing());
    }

    /**
     * Reads {@code FACING} from the cached state with a {@link Direction#NORTH}
     * fallback (null-world / test-double / wrong-block safe: only {@code pos} plus
     * the cached state are read, never the world).
     */
    private Direction readFacing() {
        try {
            BlockState cached = getCachedState();
            if (cached != null && cached.contains(CopperBusbarBlock.FACING)) {
                Direction facing = cached.get(CopperBusbarBlock.FACING);
                if (facing != null) {
                    return facing;
                }
            }
        } catch (Exception ignored) {
            // Fall through to NORTH.
        }
        return Direction.NORTH;
    }

    @Override
    public double[] getStateArray() {
        return BusbarElement.snapshotState();
    }

    @Override
    public void setStateArray(double[] state) {
        BusbarElement.checkState(state);
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        // Static interconnect: nothing to do. Null world safe.
    }

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     */
    public void writeStateData(WriteView view) {
        Objects.requireNonNull(view, "view");
        BusbarElement.writeNbt(view);
    }

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     */
    public void readStateData(ReadView view) {
        Objects.requireNonNull(view, "view");
        BusbarElement.readNbt(view);
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        writeStateData(view);
    }

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        readStateData(view);
    }
}
