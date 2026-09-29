package com.ostapyrih.voltcraft.block.entity.switchgear;

import com.mojang.serialization.Codec;
import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.block.switchgear.CircuitBreakerBlock;
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
import java.util.function.BooleanSupplier;

/**
 * Resettable circuit-breaker kernel adapter (manual trip/reset only).
 *
 * <p>Structural note: all decision logic lives in the static nested
 * {@link BreakerElement} with the trip flag supplier-injected (see
 * {@code EarthBlockEntity} for why: unit-test runtimes cannot initialize
 * {@code BlockEntity} subclasses). Production wires {@code this::isTripped};
 * tests inject their own cell.</p>
 *
 * <ul>
 *   <li>Terminals: two adjacent positions, FACING-relative {@code [FRONT, BACK]}
 *       ({@code TERMINAL_OFFSETS = {{0,0,-1},{0,0,1}}} is the canonical NORTH
 *       orientation).</li>
 *   <li>States: zero kernel states; {@code tripped} is a BE boolean field (item 9/13).
 *       Conducting exactly when {@code !tripped}: untripped stamps series admittance
 *       {@code 1 / R_CLOSED_OHM}, tripped stamps nothing (open circuit).</li>
 *   <li>{@link #tickElectrical(ServerWorld)}: manual-reset stub only, no automatic
 *       trip (item 10). Overcurrent auto-trip is deferred to a later change.</li>
 * </ul>
 *
 * <p>Defaults: {@code DEFAULT_TRIPPED = false}, matching
 * {@code CircuitBreakerBlock}'s default block state ({@code TRIPPED = false}).</p>
 *
 * <p>NBT keys (all new; no predecessor BE): {@code "stateArray"}
 * ({@code Codec.DOUBLE.listOf()}, always empty), {@code "tripped"} (boolean).</p>
 */
public class CircuitBreakerBlockEntity extends BlockEntity implements KernelAttachedBlock {

    /** Closed-contact series resistance in ohms (stamped as {@code 1/R} admittance). */
    public static final double R_CLOSED_OHM = 0.001;

    /** NBT key for the kernel state slice (always empty for the breaker). */
    public static final String KEY_STATE_ARRAY = "stateArray";
    /** NBT key for the trip flag. */
    public static final String KEY_TRIPPED = "tripped";

    /**
     * Static kernel element + state/NBT helpers. Fully unit-testable without any
     * registry, world, or block-entity instance.
     */
    public static final class BreakerElement implements ElectricalElement {
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
        /** BE default: untripped (conducting). */
        public static final boolean DEFAULT_TRIPPED = false;

        private final BooleanSupplier tripped;

        public BreakerElement(BooleanSupplier tripped) {
            this.tripped = Objects.requireNonNull(tripped, "tripped");
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
            if (!tripped.getAsBoolean()) {
                Stamps.admittance(y, terminals[0], terminals[1],
                    new Complex(1.0 / R_CLOSED_OHM, 0.0));
            }
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
            // No states: no-op.
        }

        /** Validates a kernel state slice for the breaker (must be non-null, length 0). */
        public static void checkState(double[] state) {
            if (state == null || state.length != 0) {
                throw new IllegalArgumentException(
                    "CircuitBreakerBlockEntity holds 0 states, got length " + (state == null ? "null" : state.length));
            }
        }

        /** Fresh empty snapshot (defensive: a new array per call). */
        public static double[] snapshotState() {
            return new double[0];
        }

        /** Writes the empty state slice plus the trip flag. */
        public static void writeNbt(WriteView view, boolean tripped) {
            view.put(KEY_STATE_ARRAY, Codec.DOUBLE.listOf(), List.of());
            view.putBoolean(KEY_TRIPPED, tripped);
        }

        /** Reads the trip flag (defaults to {@link #DEFAULT_TRIPPED} when absent). */
        public static boolean readNbtTripped(ReadView view) {
            view.read(KEY_STATE_ARRAY, Codec.DOUBLE.listOf()).orElse(List.of());
            return view.getBoolean(KEY_TRIPPED, DEFAULT_TRIPPED);
        }
    }

    private boolean tripped = BreakerElement.DEFAULT_TRIPPED;

    private final ElectricalElement element = new BreakerElement(this::isTripped);

    public CircuitBreakerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public CircuitBreakerBlockEntity(BlockPos pos, BlockState state) {
        this(VoltcraftBlockEntityTypes.CIRCUIT_BREAKER_BLOCK_ENTITY, pos, state);
    }

    public boolean isTripped() {
        return tripped;
    }

    public boolean isClosed() {
        return !tripped;
    }

    /**
     * Manual trip/set stub (Block-use and auto-trip wiring are out of scope).
     */
    public void setTripped(boolean tripped) {
        this.tripped = tripped;
        markDirty();
    }

    /**
     * Manual reset stub: clears a latched trip.
     */
    public void reset() {
        setTripped(false);
    }

    @Override
    public ElectricalElement getElement() {
        return element;
    }

    @Override
    public BlockPos[] getTerminalPositions() {
        return BreakerElement.resolveTerminals(pos, readFacing());
    }

    /**
     * Reads {@code FACING} from the cached state with a {@link Direction#NORTH}
     * fallback (null-world / test-double / wrong-block safe: only {@code pos} plus
     * the cached state are read, never the world).
     */
    private Direction readFacing() {
        try {
            BlockState cached = getCachedState();
            if (cached != null && cached.contains(CircuitBreakerBlock.FACING)) {
                Direction facing = cached.get(CircuitBreakerBlock.FACING);
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
        return BreakerElement.snapshotState();
    }

    @Override
    public void setStateArray(double[] state) {
        BreakerElement.checkState(state);
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        // Manual reset only; no auto-trip. Null world safe.
    }

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     */
    public void writeStateData(WriteView view) {
        Objects.requireNonNull(view, "view");
        BreakerElement.writeNbt(view, tripped);
    }

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     */
    public void readStateData(ReadView view) {
        Objects.requireNonNull(view, "view");
        this.tripped = BreakerElement.readNbtTripped(view);
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
