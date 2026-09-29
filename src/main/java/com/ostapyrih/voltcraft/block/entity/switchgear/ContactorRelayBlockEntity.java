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
 * Electromagnetically actuated contactor/relay kernel adapter.
 *
 * <p>Electrically identical to the knife switch (closed: series admittance
 * {@code 1 / R_CLOSED_OHM}; open: no stamp), but the discrete intent differs: contacts
 * close on coil power/redstone instead of a manual blade. Coil drive wiring is not
 * yet connected; the BE owns the {@code closed} boolean field plus NBT only.</p>
 *
 * <p>Structural note: all decision logic lives in the static nested
 * {@link ContactorElement} with the contact flag supplier-injected (see
 * {@code EarthBlockEntity} for why: unit-test runtimes cannot initialize
 * {@code BlockEntity} subclasses).</p>
 *
 * <ul>
 *   <li>Terminals: two adjacent positions, east/west.</li>
 *   <li>States: zero kernel states; {@code closed} is a BE boolean field (item 9/13).</li>
 *   <li>{@link #tickElectrical(ServerWorld)}: no automatic logic (item 10).</li>
 * </ul>
 *
 * <p>Defaults: {@code DEFAULT_CLOSED = false}, matching
 * {@code ContactorRelayBlock}'s default block state ({@code CLOSED = false}).</p>
 *
 * <p>NBT keys (all new; no predecessor BE): {@code "stateArray"}
 * ({@code Codec.DOUBLE.listOf()}, always empty), {@code "closed"} (boolean).</p>
 */
public class ContactorRelayBlockEntity extends BlockEntity implements KernelAttachedBlock {

    /** Closed-contact series resistance in ohms (stamped as {@code 1/R} admittance). */
    public static final double R_CLOSED_OHM = 0.001;

    /** NBT key for the kernel state slice (always empty for the contactor). */
    public static final String KEY_STATE_ARRAY = "stateArray";
    /** NBT key for the contact flag. */
    public static final String KEY_CLOSED = "closed";

    /**
     * Static kernel element + state/NBT helpers. Fully unit-testable without any
     * registry, world, or block-entity instance.
     */
    public static final class ContactorElement implements ElectricalElement {
        /** Terminal offsets: east / west of the BE position. */
        public static final int[][] TERMINAL_OFFSETS = {{1, 0, 0}, {-1, 0, 0}};
        /** BE default: contacts open (coil unpowered). */
        public static final boolean DEFAULT_CLOSED = false;

        private final BooleanSupplier closed;

        public ContactorElement(BooleanSupplier closed) {
            this.closed = Objects.requireNonNull(closed, "closed");
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
            if (closed.getAsBoolean()) {
                Stamps.admittance(y, terminals[0], terminals[1],
                    new Complex(1.0 / R_CLOSED_OHM, 0.0));
            }
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
            // No states: no-op.
        }

        /** Validates a kernel state slice for the contactor (must be non-null, length 0). */
        public static void checkState(double[] state) {
            if (state == null || state.length != 0) {
                throw new IllegalArgumentException(
                    "ContactorRelayBlockEntity holds 0 states, got length " + (state == null ? "null" : state.length));
            }
        }

        /** Fresh empty snapshot (defensive: a new array per call). */
        public static double[] snapshotState() {
            return new double[0];
        }

        /** Writes the empty state slice plus the contact flag. */
        public static void writeNbt(WriteView view, boolean closed) {
            view.put(KEY_STATE_ARRAY, Codec.DOUBLE.listOf(), List.of());
            view.putBoolean(KEY_CLOSED, closed);
        }

        /** Reads the contact flag (defaults to {@link #DEFAULT_CLOSED} when absent). */
        public static boolean readNbtClosed(ReadView view) {
            view.read(KEY_STATE_ARRAY, Codec.DOUBLE.listOf()).orElse(List.of());
            return view.getBoolean(KEY_CLOSED, DEFAULT_CLOSED);
        }
    }

    private boolean closed = ContactorElement.DEFAULT_CLOSED;

    private final ElectricalElement element = new ContactorElement(this::isClosed);

    public ContactorRelayBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public ContactorRelayBlockEntity(BlockPos pos, BlockState state) {
        this(VoltcraftBlockEntityTypes.CONTACTOR_RELAY_BLOCK_ENTITY, pos, state);
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Coil-drive stub (redstone/coil wiring is not yet connected).
     */
    public void setClosed(boolean closed) {
        this.closed = closed;
        markDirty();
    }

    @Override
    public ElectricalElement getElement() {
        return element;
    }

    @Override
    public BlockPos[] getTerminalPositions() {
        int[][] o = ContactorElement.TERMINAL_OFFSETS;
        BlockPos[] out = new BlockPos[o.length];
        for (int k = 0; k < o.length; k++) {
            out[k] = pos.add(o[k][0], o[k][1], o[k][2]);
        }
        return out;
    }

    @Override
    public double[] getStateArray() {
        return ContactorElement.snapshotState();
    }

    @Override
    public void setStateArray(double[] state) {
        ContactorElement.checkState(state);
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        // No automatic logic: contacts only move via coil drive. Null world safe.
    }

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     */
    public void writeStateData(WriteView view) {
        Objects.requireNonNull(view, "view");
        ContactorElement.writeNbt(view, closed);
    }

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     */
    public void readStateData(ReadView view) {
        Objects.requireNonNull(view, "view");
        this.closed = ContactorElement.readNbtClosed(view);
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
