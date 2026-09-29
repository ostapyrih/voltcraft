package com.ostapyrih.voltcraft.block.entity.switchgear;

import com.mojang.serialization.Codec;
import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Resettable circuit-breaker kernel adapter (Phase B: manual trip/reset only).
 *
 * <p>Structural note: all decision logic lives in the static nested
 * {@link BreakerElement} with the trip flag supplier-injected (see
 * {@code EarthBlockEntity} for why: unit-test runtimes cannot initialize
 * {@code BlockEntity} subclasses). Production wires {@code this::isTripped};
 * tests inject their own cell.</p>
 *
 * <ul>
 *   <li>Terminals: two adjacent positions, east/west.</li>
 *   <li>States: zero kernel states; {@code tripped} is a BE boolean field (item 9/13).
 *       Conducting exactly when {@code !tripped}: untripped stamps series admittance
 *       {@code 1 / R_CLOSED_OHM}, tripped stamps nothing (open circuit).</li>
 *   <li>{@link #tickElectrical(ServerWorld)}: manual-reset stub only, no automatic
 *       trip in Phase B (item 10). Overcurrent auto-trip is Phase C/D scope.</li>
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
        /** Terminal offsets: east / west of the BE position. */
        public static final int[][] TERMINAL_OFFSETS = {{1, 0, 0}, {-1, 0, 0}};
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
     * Manual trip/set stub (Block-use and Phase C/D auto-trip wiring are out of scope).
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
        int[][] o = BreakerElement.TERMINAL_OFFSETS;
        BlockPos[] out = new BlockPos[o.length];
        for (int k = 0; k < o.length; k++) {
            out[k] = pos.add(o[k][0], o[k][1], o[k][2]);
        }
        return out;
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
        // Phase B: manual reset only; no auto-trip. Null world safe.
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
