package com.ostapyrih.voltcraft.block.entity.switchgear;

import com.mojang.serialization.Codec;
import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Sacrificial cartridge-fuse kernel adapter (item 14, exact).
 *
 * <p>Structural note: all decision logic lives in the static nested
 * {@link FuseElement} with the blown flag supplier-injected and telemetry written
 * into an injected cell, because unit-test runtimes cannot initialize
 * {@code BlockEntity} subclasses at all ({@code BlockEntity.&lt;clinit&gt;} touches
 * {@code Registries}). Production wires {@code this::isBlown} plus the BE-owned
 * telemetry cell; tests inject their own cells. The outer BE still owns the
 * {@code double[2]} state array, the {@code blown} boolean field (item 9/13), and
 * the telemetry cell — the nested class is a pure function of its inputs.</p>
 *
 * <ul>
 *   <li>Terminals: two adjacent positions, east/west.</li>
 *   <li>States: exactly 2 kernel-owned reals, {@code [temperatureC, integrity]}.
 *       Integrity decays {@code 1 -> 0}. Defensive copies on
 *       {@link #getStateArray()} (clone) / {@link #setStateArray(double[])} (copy
 *       into BE-owned storage, validated) via {@link FuseElement#snapshotState} /
 *       {@link FuseElement#assignState} (item 7). Fresh fuses start at
 *       {@code [AMBIENT_C, 1.0]}.</li>
 *   <li>Discrete flag: {@code blown} is a BE boolean field persisted in NBT, never
 *       inside the state array. The element reads it at stamp time: intact stamps
 *       series admittance {@code 1 / R_FUSE_OHM}, blown stamps nothing.</li>
 *   <li>Derivatives (needs {@code It}): element current {@code I} is
 *       {@code it[0].magnitude()} (magnitude form is AC-safe; for DC it equals the
 *       real part). {@code dx[0] = (I^2*R - COOLING*(T - AMBIENT)) / C} and
 *       {@code dx[1] = -overload^2 * K} with {@code overload = max(0, I - RATED)}
 *       (zero when {@code I <= RATED}).</li>
 *   <li>Telemetry cache: {@code derivatives} stores {@code I} into the injected
 *       telemetry cell. Write-only, never read by stamp/derivative control flow
 *       (kernel item 28); meaningful only after {@code kernel.tick()} (item 12).
 *       Consequence: a <b>one-tick delay</b> between overcurrent flow and the
 *       thermal/integrity response, plus another tick before the discrete phase
 *       latches {@code blown}.</li>
 *   <li>{@link #tickElectrical(ServerWorld)} performs the discrete blow check only
 *       (item 10) via {@link FuseElement#blowCheck}: it never mutates the state
 *       array and never touches the kernel. Null world safe.</li>
 * </ul>
 *
 * <p>Integration contract: the kernel zero-initializes element state on topology
 * rebuild, which would read as {@code integrity = 0} (instantly blown). The topology
 * owner ({@code GridManager}) must seed kernel state from the BE after every
 * {@code setElements}, and copy kernel state back into the BE after every tick
 * before the discrete phase.</p>
 *
 * <p>Documented simplifications: fuse resistance stays constant while
 * integrity decays (no pre-blow resistance rise); temperature is telemetry only
 * (no thermal trip, no melting); fuse replacement is a BE stub
 * ({@link #replaceFuse()}).</p>
 *
 * <p>NBT keys (all new; no predecessor BE): {@code "stateArray"}
 * ({@code Codec.DOUBLE.listOf()}, {@code [temperatureC, integrity]}),
 * {@code "blown"} (boolean). Telemetry is transient and not persisted. Reads restore
 * state only when the stored list has exactly 2 finite entries (forward-tolerant).</p>
 */
public class FuseBoxBlockEntity extends BlockEntity implements KernelAttachedBlock {

    /** Fuse series resistance in ohms (stamped as {@code 1/R} admittance while intact). */
    public static final double R_FUSE_OHM = 0.005;
    /** Rated current in amps; integrity decays only above this threshold. */
    public static final double RATED_CURRENT_A = 30.0;
    /** Fuse thermal capacitance in J/K for the temperature state. */
    public static final double THERMAL_CAPACITY_J_PER_K = 10.0;
    /** Linear cooling coefficient in W/K toward ambient. */
    public static final double COOLING_COEFF_W_PER_K = 0.5;
    /** Integrity decay coefficient in 1/(A^2 s). */
    public static final double INTEGRITY_DECAY_K = 1e-6;

    /** State index of the fuse temperature in Celsius. */
    public static final int STATE_TEMP = 0;
    /** State index of the fuse integrity ({@code 1} intact {@code ->} {@code 0} destroyed). */
    public static final int STATE_INTEGRITY = 1;

    /** NBT key for the kernel state slice {@code [temperatureC, integrity]}. */
    public static final String KEY_STATE_ARRAY = "stateArray";
    /** NBT key for the blown flag. */
    public static final String KEY_BLOWN = "blown";

    /**
     * Static kernel element + state/NBT helpers. Fully unit-testable without any
     * registry, world, or block-entity instance.
     */
    public static final class FuseElement implements ElectricalElement {
        /** Terminal offsets: east / west of the BE position. */
        public static final int[][] TERMINAL_OFFSETS = {{1, 0, 0}, {-1, 0, 0}};

        private final BooleanSupplier blown;
        private final double[] telemetryCell;

        /**
         * @param blown supplier for the BE-owned blown flag (read at stamp time only)
         * @param telemetryCell BE/test-owned write-only current cache, length {@code >= 1}
         */
        public FuseElement(BooleanSupplier blown, double[] telemetryCell) {
            this.blown = Objects.requireNonNull(blown, "blown");
            Objects.requireNonNull(telemetryCell, "telemetryCell");
            if (telemetryCell.length < 1) {
                throw new IllegalArgumentException("telemetryCell needs length >= 1");
            }
            this.telemetryCell = telemetryCell;
        }

        @Override
        public int terminalCount() {
            return 2;
        }

        @Override
        public int stateCount() {
            return 2;
        }

        @Override
        public void stamp(Complex[][] y, Complex[] in, int[] terminals, Complex[] v,
                          double[] state, double omega) {
            if (!blown.getAsBoolean()) {
                Stamps.admittance(y, terminals[0], terminals[1],
                    new Complex(1.0 / R_FUSE_OHM, 0.0));
            }
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
            double current = 0.0;
            if (it.length > 0 && it[0] != null) {
                current = it[0].magnitude();
            }
            // Telemetry-only cache (item 28): write-only, never read by control flow.
            telemetryCell[0] = current;
            double heatingW = current * current * R_FUSE_OHM;
            double coolingW = COOLING_COEFF_W_PER_K * (state[STATE_TEMP] - GridConstants.AMBIENT_C);
            dxdt[STATE_TEMP] = (heatingW - coolingW) / THERMAL_CAPACITY_J_PER_K;
            double overload = Math.max(0.0, current - RATED_CURRENT_A);
            dxdt[STATE_INTEGRITY] = -overload * overload * INTEGRITY_DECAY_K;
        }

        /** Fresh-fuse kernel state {@code [AMBIENT_C, 1.0]} (defensive: a new array per call). */
        public static double[] newStateArray() {
            return new double[]{GridConstants.AMBIENT_C, 1.0};
        }

        /** Defensive snapshot: returns a clone, never the live array. */
        public static double[] snapshotState(double[] live) {
            Objects.requireNonNull(live, "live");
            return live.clone();
        }

        /**
         * Copies {@code src} into BE-owned {@code dst} (defensive: never retains the
         * kernel array by reference). Both must be non-null, length 2.
         */
        public static void assignState(double[] dst, double[] src) {
            if (dst == null || dst.length != 2 || src == null || src.length != 2) {
                throw new IllegalArgumentException(
                    "FuseBoxBlockEntity holds 2 states, got dst="
                        + (dst == null ? "null" : dst.length) + " src=" + (src == null ? "null" : src.length));
            }
            dst[STATE_TEMP] = src[STATE_TEMP];
            dst[STATE_INTEGRITY] = src[STATE_INTEGRITY];
        }

        /**
         * Discrete blow transition (pure): latched once integrity is depleted.
         * The BE calls this in its discrete phase; it never mutates state.
         */
        public static boolean blowCheck(double integrity, boolean alreadyBlown) {
            return alreadyBlown || integrity <= 0.0;
        }

        /** Writes the state slice plus the blown flag. */
        public static void writeNbt(WriteView view, double temperatureC, double integrity, boolean blown) {
            List<Double> boxed = new ArrayList<>(2);
            boxed.add(temperatureC);
            boxed.add(integrity);
            view.put(KEY_STATE_ARRAY, Codec.DOUBLE.listOf(), boxed);
            view.putBoolean(KEY_BLOWN, blown);
        }

        /**
         * Reads the state slice; returns fresh-fuse defaults unless the stored list
         * has exactly 2 finite entries (forward-tolerant).
         */
        public static double[] readNbtState(ReadView view) {
            List<Double> list = view.read(KEY_STATE_ARRAY, Codec.DOUBLE.listOf()).orElse(List.of());
            if (list.size() == 2 && list.get(0) != null && list.get(1) != null
                    && Double.isFinite(list.get(0)) && Double.isFinite(list.get(1))) {
                return new double[]{list.get(0), list.get(1)};
            }
            return newStateArray();
        }

        /** Reads the blown flag (defaults to intact when absent). */
        public static boolean readNbtBlown(ReadView view) {
            return view.getBoolean(KEY_BLOWN, false);
        }
    }

    private final double[] stateArray = FuseElement.newStateArray();
    private final double[] telemetryCell = new double[1];
    private boolean blown;

    private final ElectricalElement element = new FuseElement(this::isBlown, telemetryCell);

    public FuseBoxBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public FuseBoxBlockEntity(BlockPos pos, BlockState state) {
        this(VoltcraftBlockEntityTypes.FUSE_BOX_BLOCK_ENTITY, pos, state);
    }

    public boolean isBlown() {
        return blown;
    }

    public boolean isClosed() {
        return !blown;
    }

    /**
     * Fuse-replacement stub (Block-use wiring with the fuse-alloy ingot is not yet connected):
     * restores an intact fuse at ambient temperature.
     */
    public void replaceFuse() {
        this.blown = false;
        this.stateArray[STATE_TEMP] = GridConstants.AMBIENT_C;
        this.stateArray[STATE_INTEGRITY] = 1.0;
        markDirty();
    }

    /**
     * Telemetry only: element current cached by the last {@code derivatives} call.
     * Valid only after {@code kernel.tick()} (item 12).
     */
    public double getLastCurrentAmps() {
        return telemetryCell[0];
    }

    public double getTemperatureCelsius() {
        return stateArray[STATE_TEMP];
    }

    public double getIntegrity() {
        return stateArray[STATE_INTEGRITY];
    }

    @Override
    public ElectricalElement getElement() {
        return element;
    }

    @Override
    public BlockPos[] getTerminalPositions() {
        int[][] o = FuseElement.TERMINAL_OFFSETS;
        BlockPos[] out = new BlockPos[o.length];
        for (int k = 0; k < o.length; k++) {
            out[k] = pos.add(o[k][0], o[k][1], o[k][2]);
        }
        return out;
    }

    @Override
    public double[] getStateArray() {
        return FuseElement.snapshotState(stateArray);
    }

    @Override
    public void setStateArray(double[] state) {
        FuseElement.assignState(stateArray, state);
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        // Discrete blow check only: never mutates the state array, never touches the kernel.
        if (FuseElement.blowCheck(stateArray[STATE_INTEGRITY], blown) && !blown) {
            blown = true;
            markDirty();
        }
    }

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     */
    public void writeStateData(WriteView view) {
        Objects.requireNonNull(view, "view");
        FuseElement.writeNbt(view, stateArray[STATE_TEMP], stateArray[STATE_INTEGRITY], blown);
    }

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     */
    public void readStateData(ReadView view) {
        Objects.requireNonNull(view, "view");
        FuseElement.assignState(stateArray, FuseElement.readNbtState(view));
        this.blown = FuseElement.readNbtBlown(view);
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
