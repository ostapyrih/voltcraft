package com.ostapyrih.voltcraft.block.entity.generation;

import com.mojang.serialization.Codec;
import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 100W Hand-Crank Dynamo kernel adapter.
 * Converts mechanical flywheel inertia into 12V DC power.
 *
 * <p>Structural note: all kernel decision logic lives in the static nested
 * {@link CrankElement} with telemetry written into an injected cell, because unit-test
 * runtimes cannot initialize {@code BlockEntity} subclasses at all
 * ({@code BlockEntity.&lt;clinit&gt;} touches {@code Registries}). Production wires the
 * BE-owned telemetry cell; tests inject their own. The outer BE still owns the
 * {@code double[2]} state array and the telemetry cell — the nested class is a pure
 * function of its inputs. (Deviation note: the legacy file described AC-capable output
 * in prose fragments, but the legacy implementation stamped strictly DC
 * {@code getFrequency() == 0.0}; this adapter keeps DC per spec.)</p>
 *
 * <ul>
 *   <li>Terminals (item 4): two adjacent positions, east/west. Phase-C source polarity
 *       convention: {@code terminals[1]} (west) is positive, so {@code It[0]} is
 *       positive while the dynamo delivers.</li>
 *   <li>States (item 7): exactly 2 kernel-owned reals, {@code [flywheelSpeed,
 *       totalEnergyJoules]} with speed normalized to {@code [0,1]}. Defensive copies on
 *       {@link #getStateArray()} (clone) / {@link #setStateArray(double[])} (copy into
 *       BE-owned storage, validated; speed clamped to {@code [0,1]}, energy floored at
 *       0). Fresh dynamos start at {@code [0.0, 0.0]}. The topology owner
 *       ({@code GridManager}) must seed kernel state from the BE after every
 *       {@code setElements} and copy kernel state back into the BE after every tick
 *       before the discrete phase.</li>
 *   <li>Element stamp: Thevenin with {@code EMF = EMF_AT_FULL_SPEED * speed}
 *       ({@code 13.8 V} open-circuit at full crank, 12 V nominal) and {@code 0.15 ohm}
 *       winding resistance; open circuit when {@code speed <= 0}.</li>
 *   <li>Derivatives: {@code dspeed/dt = -FRICTION_K * speed - (P / RATED_W) * LOAD_K}
 *       with {@code FRICTION_K = 0.61 /s} (continuous equivalent of the legacy
 *       {@code 0.97}-per-tick spindown: {@code 0.97 = e^(-0.61 * 0.05)}) and
 *       {@code LOAD_K = 1.0 /s} at the {@code 100 W} rating (continuous equivalent of
 *       the legacy back-EMF damping of {@code 0.05} speed per tick at 100 W);
 *       {@code dEnergy/dt = max(0, V) * max(0, I)} delivered power with
 *       {@code V = Vt[1]-Vt[0]}, {@code I = It[0]}.</li>
 *   <li>Telemetry cache (item 12): {@code derivatives} stores terminal voltage and
 *       delivered current into the injected cell. Write-only, never read by
 *       stamp/derivative control flow; meaningful only after {@code kernel.tick()}.</li>
 *   <li>{@link #tickElectrical(ServerWorld)} is a null-safe no-op (item 10): crank input
 *       arrives via {@link #crank()}/{@link #addSpeed(double)} (player interaction,
 *       outside the discrete phase), spindown and energy integration run in kernel
 *       derivatives. It never mutates the state array and never touches the kernel.</li>
 * </ul>
 *
 * <p>Fallback rollback contract (item 11): on {@code fallbackActive} the BE state array
 * is authoritative — re-sync the kernel from the BE before the next solve and skip the
 * commit; otherwise commit kernel state into the BE before the discrete phase.
 * Per-island semantics. The kernel integrates even on fallback; that state is discarded.</p>
 *
 * <p>NBT keys: {@code "stateArray"} ({@code Codec.DOUBLE.listOf()},
 * {@code [flywheelSpeed, totalEnergyJoules]}), plus preserved legacy mirrors
 * {@code "flywheel_speed"} and {@code "total_energy_joules"}. Reads prefer
 * {@code stateArray} with exactly 2 finite entries, else the legacy pair when both are
 * finite, else fresh defaults (forward-tolerant). Telemetry is transient and not
 * persisted.</p>
 */
public class HandCrankGeneratorBlockEntity extends BlockEntity implements KernelAttachedBlock {

    /** State index of the normalized flywheel speed in {@code [0,1]}. */
    public static final int STATE_SPEED = 0;
    /** State index of the lifetime delivered energy in joules. */
    public static final int STATE_ENERGY = 1;

    /** NBT key for the kernel state slice {@code [flywheelSpeed, totalEnergyJoules]}. */
    public static final String KEY_STATE_ARRAY = "stateArray";
    /** Preserved legacy NBT mirror of the flywheel speed. */
    public static final String KEY_SPEED_LEGACY = "flywheel_speed";
    /** Preserved legacy NBT mirror of the lifetime energy counter. */
    public static final String KEY_ENERGY_LEGACY = "total_energy_joules";

    /**
     * Static kernel element + state/NBT helpers. Fully unit-testable without any
     * registry, world, or block-entity instance.
     */
    public static final class CrankElement implements ElectricalElement {
        /** Terminal offsets: east / west of the BE position. */
        public static final int[][] TERMINAL_OFFSETS = {{1, 0, 0}, {-1, 0, 0}};
        /** Open-circuit EMF in volts at full flywheel speed (12 V nominal). */
        public static final double EMF_AT_FULL_SPEED = 13.8;
        /** Internal winding resistance in ohms. */
        public static final double WINDING_RESISTANCE_OHM = 0.15;
        /** Mechanical friction decay rate in 1/s. */
        public static final double FRICTION_K_PER_S = 0.61;
        /** Nameplate electrical rating in watts. */
        public static final double RATED_POWER_W = 100.0;
        /** Electromagnetic load-torque rate in 1/s at rated power. */
        public static final double LOAD_TORQUE_K_PER_S = 1.0;
        /** Single crank stroke in normalized speed units. */
        public static final double CRANK_STROKE = 0.35;
        /** Telemetry cell index of the terminal voltage in volts. */
        public static final int TELE_V = 0;
        /** Telemetry cell index of the delivered current in amps (positive while generating). */
        public static final int TELE_I = 1;

        private final double[] telemetryCell;

        /**
         * @param telemetryCell BE/test-owned write-only cache {@code [terminalV, deliveredI]},
         *        length {@code >= 2}
         */
        public CrankElement(double[] telemetryCell) {
            Objects.requireNonNull(telemetryCell, "telemetryCell");
            if (telemetryCell.length < 2) {
                throw new IllegalArgumentException("telemetryCell needs length >= 2");
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
            double speed = state[STATE_SPEED];
            if (!(speed > 0.0)) {
                return;
            }
            // Phase-C polarity: terminals[1] (west) is positive.
            Stamps.thevenin(y, in, terminals[1], terminals[0],
                new Complex(1.0 / WINDING_RESISTANCE_OHM, 0.0),
                new Complex(emfForSpeed(speed), 0.0));
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
            double intoPos = 0.0;
            if (it.length > 0 && it[0] != null) {
                intoPos = it[0].re;
            }
            double terminalV = 0.0;
            if (vt.length > 1 && vt[0] != null && vt[1] != null) {
                terminalV = vt[1].re - vt[0].re;
            }
            // Telemetry-only cache (item 28): write-only, never read by control flow.
            telemetryCell[TELE_V] = terminalV;
            telemetryCell[TELE_I] = intoPos;
            double speed = Math.max(0.0, Math.min(1.0, state[STATE_SPEED]));
            double deliveredP = Math.max(0.0, terminalV) * Math.max(0.0, intoPos);
            dxdt[STATE_SPEED] = -FRICTION_K_PER_S * speed
                - (deliveredP / RATED_POWER_W) * LOAD_TORQUE_K_PER_S;
            dxdt[STATE_ENERGY] = deliveredP;
        }

        /** Open-circuit EMF in volts for a normalized flywheel speed. */
        public static double emfForSpeed(double speed) {
            return Math.max(0.0, Math.min(1.0, speed)) * EMF_AT_FULL_SPEED;
        }

        /** Source classification: active whenever the flywheel turns. */
        public static boolean isActiveSource(double flywheelSpeed) {
            return flywheelSpeed > 0.0;
        }

        /** Fresh-dynamo kernel state {@code [0.0, 0.0]} (defensive: a new array per call). */
        public static double[] newStateArray() {
            return new double[]{0.0, 0.0};
        }

        /** Defensive snapshot: returns a clone, never the live array. */
        public static double[] snapshotState(double[] live) {
            Objects.requireNonNull(live, "live");
            return live.clone();
        }

        /**
         * Copies {@code src} into BE-owned {@code dst} (defensive: never retains the
         * kernel array by reference). Both must be non-null, length 2. Speed is clamped
         * to {@code [0,1]}; energy is floored at 0.
         */
        public static void assignState(double[] dst, double[] src) {
            if (dst == null || dst.length != 2 || src == null || src.length != 2) {
                throw new IllegalArgumentException(
                    "HandCrankGeneratorBlockEntity holds 2 states, got dst="
                        + (dst == null ? "null" : dst.length) + " src=" + (src == null ? "null" : src.length));
            }
            dst[STATE_SPEED] = Math.max(0.0, Math.min(1.0, src[STATE_SPEED]));
            dst[STATE_ENERGY] = Math.max(0.0, src[STATE_ENERGY]);
        }

        /** Pure crank transition: one stroke adds {@code CRANK_STROKE}, capped at 1. */
        public static double crankNext(double speed) {
            return Math.min(1.0, Math.max(0.0, speed) + CRANK_STROKE);
        }

        /** Writes the state slice plus the preserved legacy mirrors. */
        public static void writeNbt(WriteView view, double flywheelSpeed, double totalEnergyJoules) {
            List<Double> boxed = new ArrayList<>(2);
            boxed.add(flywheelSpeed);
            boxed.add(totalEnergyJoules);
            view.put(KEY_STATE_ARRAY, Codec.DOUBLE.listOf(), boxed);
            view.putDouble(KEY_SPEED_LEGACY, flywheelSpeed);
            view.putDouble(KEY_ENERGY_LEGACY, totalEnergyJoules);
        }

        /**
         * Reads the state slice; prefers {@code stateArray} with exactly 2 finite entries,
         * else the legacy pair when both are finite, else fresh defaults (forward-tolerant).
         */
        public static double[] readNbtState(ReadView view) {
            List<Double> list = view.read(KEY_STATE_ARRAY, Codec.DOUBLE.listOf()).orElse(List.of());
            if (list.size() == 2 && list.get(0) != null && list.get(1) != null
                    && Double.isFinite(list.get(0)) && Double.isFinite(list.get(1))) {
                return new double[]{list.get(0), list.get(1)};
            }
            double speed = view.getDouble(KEY_SPEED_LEGACY, Double.NaN);
            double energy = view.getDouble(KEY_ENERGY_LEGACY, Double.NaN);
            if (Double.isFinite(speed) && Double.isFinite(energy)) {
                return new double[]{speed, energy};
            }
            return newStateArray();
        }
    }

    private final double[] stateArray = CrankElement.newStateArray();
    private final double[] telemetryCell = new double[2];

    private final ElectricalElement element;

    public HandCrankGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(VoltcraftBlockEntityTypes.HAND_CRANK_GENERATOR_BLOCK_ENTITY, pos, state);
        this.element = new CrankElement(telemetryCell);
    }

    /**
     * Called when a player right-clicks the dynamo (player interaction, outside the
     * discrete phase): adds one stroke to the BE-owned flywheel state.
     */
    public void crank() {
        this.stateArray[STATE_SPEED] = CrankElement.crankNext(this.stateArray[STATE_SPEED]);
        markDirty();
    }

    /** Adds normalized speed (clamped to {@code [0,1]}); crank-input stub. */
    public void addSpeed(double delta) {
        this.stateArray[STATE_SPEED] =
            Math.max(0.0, Math.min(1.0, this.stateArray[STATE_SPEED] + delta));
        markDirty();
    }

    public double getFlywheelSpeed() {
        return stateArray[STATE_SPEED];
    }

    /**
     * Telemetry only: delivered current cached by the last {@code derivatives} call.
     * Valid only after {@code kernel.tick()} (item 12).
     */
    public double getLastDrawnCurrent() {
        return telemetryCell[CrankElement.TELE_I];
    }

    public double getTotalEnergyJoules() {
        return stateArray[STATE_ENERGY];
    }

    /**
     * Legacy block-ticker entry point (kept for Block association); the discrete phase
     * is a no-op — spindown runs in kernel derivatives.
     */
    public void tick(ServerWorld world) {
        tickElectrical(world);
    }

    @Override
    public ElectricalElement getElement() {
        return element;
    }

    @Override
    public BlockPos[] getTerminalPositions() {
        int[][] o = CrankElement.TERMINAL_OFFSETS;
        BlockPos[] out = new BlockPos[o.length];
        for (int k = 0; k < o.length; k++) {
            out[k] = pos.add(o[k][0], o[k][1], o[k][2]);
        }
        return out;
    }

    @Override
    public double[] getStateArray() {
        return CrankElement.snapshotState(stateArray);
    }

    @Override
    public void setStateArray(double[] state) {
        CrankElement.assignState(stateArray, state);
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public boolean isActiveSource() {
        return CrankElement.isActiveSource(stateArray[STATE_SPEED]);
    }

    @Override
    public boolean isACSource() {
        return false;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        // Crank input arrives via crank()/addSpeed; spindown and energy integration run in
        // kernel derivatives. Discrete phase is intentionally empty. Null world safe.
    }

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     */
    public void writeStateData(WriteView view) {
        Objects.requireNonNull(view, "view");
        CrankElement.writeNbt(view, stateArray[STATE_SPEED], stateArray[STATE_ENERGY]);
    }

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     */
    public void readStateData(ReadView view) {
        Objects.requireNonNull(view, "view");
        CrankElement.assignState(stateArray, CrankElement.readNbtState(view));
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
