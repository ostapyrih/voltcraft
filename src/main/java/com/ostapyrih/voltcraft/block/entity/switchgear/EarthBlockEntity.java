package com.ostapyrih.voltcraft.block.entity.switchgear;

import com.mojang.serialization.Codec;
import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
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

/**
 * Protective-earth (ground reference) kernel adapter.
 *
 * <p>Adapter contract (frozen kernel items): the kernel is the sole authority.
 * This block entity declares its terminals plus state; the element only stamps.</p>
 *
 * <p>Structural note: all decision logic lives in the static nested
 * {@link EarthElement} (formatter-independent of any world/registry state) because
 * unit-test runtimes cannot initialize {@code BlockEntity} subclasses at all
 * ({@code BlockEntity.&lt;clinit&gt;} touches {@code Registries}, which needs a
 * bootstrapped server plus Fabric access-wideners). The outer BE is thin glue:
 * BE-owned fields, one-line delegates, vanilla NBT overrides. Static-nested
 * classes initialize independently of the enclosing class, so they are fully
 * unit-testable.</p>
 *
 * <ul>
 *   <li>Terminals (item 4): exactly one <b>adjacent</b> world position, straight down
 *       ({@code TERMINAL_OFFSETS = {{0,-1,0}}}, i.e. {@code pos.down()}). Tests use the
 *       declared offsets; {@code GridManager} maps them to kernel nodes.</li>
 *   <li>States (item 7/9): zero kernel states. {@link #getStateArray()} returns a fresh
 *       {@code double[0]}; {@link #setStateArray(double[])} validates length 0 via
 *       {@link EarthElement#checkState}.</li>
 *   <li>Element stamp: shunt conductance to implicit ground,
 *       {@code Y[t][t] += G_EARTH_S} with zero current injection. The constant matches
 *       the linear-test {@code TestEarth} precedent ({@code 1000 S ~= 0.001 ohm}).</li>
 *   <li>Tick order (item 10): {@link #tickElectrical(ServerWorld)} is a no-op.</li>
 * </ul>
 *
 * <p>NBT keys (all new; no predecessor BE exists for earth):
 * {@code "stateArray"} ({@code Codec.DOUBLE.listOf()}, always empty here).</p>
 */
public class EarthBlockEntity extends BlockEntity implements KernelAttachedBlock {

    /** Shunt conductance to implicit ground in Siemens ({@code ~= 0.001 ohm}). */
    public static final double G_EARTH_S = 1000.0;

    /** NBT key for the kernel state slice (always empty for earth). */
    public static final String KEY_STATE_ARRAY = "stateArray";

    /**
     * Static kernel element + state/NBT helpers. Fully unit-testable without any
     * registry, world, or block-entity instance.
     */
    public static final class EarthElement implements ElectricalElement {
        /** Terminal offsets relative to the BE position; single ground-rod terminal. */
        public static final int[][] TERMINAL_OFFSETS = {{0, -1, 0}};

        @Override
        public int terminalCount() {
            return 1;
        }

        @Override
        public int stateCount() {
            return 0;
        }

        @Override
        public void stamp(Complex[][] y, Complex[] in, int[] terminals, Complex[] v,
                          double[] state, double omega) {
            int t = terminals[0];
            y[t][t] = y[t][t].add(new Complex(G_EARTH_S, 0.0));
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
            // No states: no-op.
        }

        /** Validates a kernel state slice for earth (must be non-null, length 0). */
        public static void checkState(double[] state) {
            if (state == null || state.length != 0) {
                throw new IllegalArgumentException(
                    "EarthBlockEntity holds 0 states, got length " + (state == null ? "null" : state.length));
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

    private final ElectricalElement element = new EarthElement();

    public EarthBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public EarthBlockEntity(BlockPos pos, BlockState state) {
        this(VoltcraftBlockEntityTypes.EARTH_BLOCK_ENTITY, pos, state);
    }

    @Override
    public ElectricalElement getElement() {
        return element;
    }

    @Override
    public BlockPos[] getTerminalPositions() {
        int[][] o = EarthElement.TERMINAL_OFFSETS;
        BlockPos[] out = new BlockPos[o.length];
        for (int k = 0; k < o.length; k++) {
            out[k] = pos.add(o[k][0], o[k][1], o[k][2]);
        }
        return out;
    }

    @Override
    public double[] getStateArray() {
        return EarthElement.snapshotState();
    }

    @Override
    public void setStateArray(double[] state) {
        EarthElement.checkState(state);
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        // Static ground reference: no discrete transitions. Null world safe (no dereference).
    }

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     */
    public void writeStateData(WriteView view) {
        Objects.requireNonNull(view, "view");
        EarthElement.writeNbt(view);
    }

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     */
    public void readStateData(ReadView view) {
        Objects.requireNonNull(view, "view");
        EarthElement.readNbt(view);
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
