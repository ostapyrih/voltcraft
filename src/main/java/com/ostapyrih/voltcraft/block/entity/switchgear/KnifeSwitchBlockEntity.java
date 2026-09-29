package com.ostapyrih.voltcraft.block.entity.switchgear;

import com.mojang.serialization.Codec;
import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.block.switchgear.KnifeSwitchBlock;
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
 * Manual 100 A disconnect knife-switch kernel adapter.
 *
 * <p>Structural note: all decision logic lives in the static nested
 * {@link SwitchElement} with the blade flag supplier-injected, because unit-test
 * runtimes cannot initialize {@code BlockEntity} subclasses at all
 * ({@code BlockEntity.&lt;clinit&gt;} touches {@code Registries}). Production wires
 * {@code this::isClosed}; tests inject their own cell. The outer BE only owns the
 * {@code closed} boolean field (item 9/13: never inside the state array).</p>
 *
 * <ul>
 *   <li>Terminals (item 4): two <b>adjacent</b> world positions, FACING-relative
 *       {@code [FRONT, BACK]} ({@code TERMINAL_OFFSETS = {{0,0,-1},{0,0,1}}} is the
 *       canonical NORTH orientation).</li>
 *   <li>States: zero kernel states (defensive-copy rules via nested helpers).</li>
 *   <li>Element stamp: when closed, series admittance {@code g = 1 / R_CLOSED_OHM};
 *       when open, no stamp (open circuit). Single element instance per BE.</li>
 *   <li>{@link #tickElectrical(ServerWorld)} performs no automatic logic (item 10):
 *       the blade moves via player block-use. The BE exposes the {@link #setClosed}
 *       stub plus {@code markDirty()}; Block-use wiring is not yet connected.</li>
 * </ul>
 *
 * <p>Defaults: {@code DEFAULT_CLOSED = true} (conducting). Note this differs from
 * {@code KnifeSwitchBlock}'s default block state ({@code OPEN = true}); BE/block
 * synchronization is future wiring scope.</p>
 *
 * <p>NBT keys (all new; no predecessor BE): {@code "stateArray"}
 * ({@code Codec.DOUBLE.listOf()}, always empty), {@code "closed"} (boolean).</p>
 */
public class KnifeSwitchBlockEntity extends BlockEntity implements KernelAttachedBlock {

    /** Closed-blade series resistance in ohms (stamped as {@code 1/R} admittance). */
    public static final double R_CLOSED_OHM = 0.001;

    /** NBT key for the kernel state slice (always empty for the switch). */
    public static final String KEY_STATE_ARRAY = "stateArray";
    /** NBT key for the blade flag. */
    public static final String KEY_CLOSED = "closed";

    /**
     * Static kernel element + state/NBT helpers. Fully unit-testable without any
     * registry, world, or block-entity instance.
     */
    public static final class SwitchElement implements ElectricalElement {
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
        /** BE default: blade closed (conducting). */
        public static final boolean DEFAULT_CLOSED = true;

        private final BooleanSupplier closed;

        public SwitchElement(BooleanSupplier closed) {
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

        /** Validates a kernel state slice for the switch (must be non-null, length 0). */
        public static void checkState(double[] state) {
            if (state == null || state.length != 0) {
                throw new IllegalArgumentException(
                    "KnifeSwitchBlockEntity holds 0 states, got length " + (state == null ? "null" : state.length));
            }
        }

        /** Fresh empty snapshot (defensive: a new array per call). */
        public static double[] snapshotState() {
            return new double[0];
        }

        /** Writes the empty state slice plus the blade flag. */
        public static void writeNbt(WriteView view, boolean closed) {
            view.put(KEY_STATE_ARRAY, Codec.DOUBLE.listOf(), List.of());
            view.putBoolean(KEY_CLOSED, closed);
        }

        /** Reads the blade flag (defaults to {@link #DEFAULT_CLOSED} when absent). */
        public static boolean readNbtClosed(ReadView view) {
            view.read(KEY_STATE_ARRAY, Codec.DOUBLE.listOf()).orElse(List.of());
            return view.getBoolean(KEY_CLOSED, DEFAULT_CLOSED);
        }
    }

    private boolean closed = SwitchElement.DEFAULT_CLOSED;

    private final ElectricalElement element = new SwitchElement(this::isClosed);

    public KnifeSwitchBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public KnifeSwitchBlockEntity(BlockPos pos, BlockState state) {
        this(VoltcraftBlockEntityTypes.KNIFE_SWITCH_BLOCK_ENTITY, pos, state);
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Player-toggle stub (Block-use wiring is not yet connected).
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
        return SwitchElement.resolveTerminals(pos, readFacing());
    }

    /**
     * Reads {@code FACING} from the cached state with a {@link Direction#NORTH}
     * fallback (null-world / test-double / wrong-block safe: only {@code pos} plus
     * the cached state are read, never the world).
     */
    private Direction readFacing() {
        try {
            BlockState cached = getCachedState();
            if (cached != null && cached.contains(KnifeSwitchBlock.FACING)) {
                Direction facing = cached.get(KnifeSwitchBlock.FACING);
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
        return SwitchElement.snapshotState();
    }

    @Override
    public void setStateArray(double[] state) {
        SwitchElement.checkState(state);
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        // No automatic logic: the blade only moves via player interaction. Null world safe.
    }

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     */
    public void writeStateData(WriteView view) {
        Objects.requireNonNull(view, "view");
        SwitchElement.writeNbt(view, closed);
    }

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     */
    public void readStateData(ReadView view) {
        Objects.requireNonNull(view, "view");
        this.closed = SwitchElement.readNbtClosed(view);
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
