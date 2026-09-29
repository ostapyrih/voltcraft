package com.ostapyrih.voltcraft.simulation.electrical;

import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.mojang.serialization.Codec;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class FuseElement implements ElectricalElement {
        public static final double R_FUSE_OHM = 0.005;
        public static final double RATED_CURRENT_A = 30.0;
        public static final double THERMAL_CAPACITY_J_PER_K = 10.0;
        public static final double COOLING_COEFF_W_PER_K = 0.5;
        public static final double INTEGRITY_DECAY_K = 1e-6;

        public static final int STATE_TEMP = 0;
        public static final int STATE_INTEGRITY = 1;

        public static final String KEY_STATE_ARRAY = "stateArray";
        public static final String KEY_BLOWN = "blown";
    public static final int[][] TERMINAL_OFFSETS = {{0, 0, -1}, {0, 0, 1}};

    /** FACING-relative: {@code [FRONT, BACK]}; null facing degrades to NORTH. */
    public static BlockPos[] resolveTerminals(BlockPos pos, Direction facing) {
        Direction f = facing != null ? facing : Direction.NORTH;
        return new BlockPos[]{pos.offset(f), pos.offset(f.getOpposite())};
    }

    private final BooleanSupplier blown;
    private final double[] telemetryCell;

    public FuseElement(BooleanSupplier blown, double[] telemetryCell) {
        this.blown = Objects.requireNonNull(blown, "blown");
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
        telemetryCell[0] = current;
        double heatingW = current * current * R_FUSE_OHM;
        double coolingW = COOLING_COEFF_W_PER_K * (state[STATE_TEMP] - GridConstants.AMBIENT_C);
        dxdt[STATE_TEMP] = (heatingW - coolingW) / THERMAL_CAPACITY_J_PER_K;
        double overload = Math.max(0.0, current - RATED_CURRENT_A);
        dxdt[STATE_INTEGRITY] = -overload * overload * INTEGRITY_DECAY_K;
    }

    public static double[] newStateArray() {
        return new double[]{GridConstants.AMBIENT_C, 1.0};
    }

    public static double[] snapshotState(double[] live) {
        Objects.requireNonNull(live, "live");
        return live.clone();
    }

    public static void assignState(double[] dst, double[] src) {
        if (dst == null || dst.length != 2 || src == null || src.length != 2) {
            throw new IllegalArgumentException(
                "FuseBoxBlockEntity holds 2 states, got dst="
                    + (dst == null ? "null" : dst.length) + " src=" + (src == null ? "null" : src.length));
        }
        dst[STATE_TEMP] = src[STATE_TEMP];
        dst[STATE_INTEGRITY] = src[STATE_INTEGRITY];
    }

    public static boolean blowCheck(double integrity, boolean alreadyBlown) {
        return alreadyBlown || integrity <= 0.0;
    }

    public static void writeNbt(WriteView view, double temperatureC, double integrity, boolean blown) {
        List<Double> boxed = new ArrayList<>(2);
        boxed.add(temperatureC);
        boxed.add(integrity);
        view.put(KEY_STATE_ARRAY, Codec.DOUBLE.listOf(), boxed);
        view.putBoolean(KEY_BLOWN, blown);
    }

    public static double[] readNbtState(ReadView view) {
        List<Double> list = view.read(KEY_STATE_ARRAY, Codec.DOUBLE.listOf()).orElse(List.of());
        if (list.size() != 2) {
            return newStateArray();
        }
        return new double[]{list.get(0), list.get(1)};
    }

    public static boolean readNbtBlown(ReadView view) {
        return view.getBoolean(KEY_BLOWN, false);
    }
}
