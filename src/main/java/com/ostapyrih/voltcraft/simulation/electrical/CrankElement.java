package com.ostapyrih.voltcraft.simulation.electrical;

import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.mojang.serialization.Codec;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CrankElement implements ElectricalElement {
        public static final int STATE_SPEED = 0;
        public static final int STATE_ENERGY = 1;

        public static final String KEY_STATE_ARRAY = "stateArray";
    public static final int[][] TERMINAL_OFFSETS = {{0, 0, -1}, {0, 0, 1}};

    /** FACING-relative: {@code [FRONT (-), BACK (+)]}; null facing degrades to NORTH. */
    public static BlockPos[] resolveTerminals(BlockPos pos, Direction facing) {
        Direction f = facing != null ? facing : Direction.NORTH;
        return new BlockPos[]{pos.offset(f), pos.offset(f.getOpposite())};
    }
    public static final double EMF_AT_FULL_SPEED = 13.8;
    public static final double WINDING_RESISTANCE_OHM = 0.15;
    public static final double FRICTION_K_PER_S = 0.61;
    public static final double RATED_POWER_W = 100.0;
    public static final double LOAD_TORQUE_K_PER_S = 1.0;
    public static final double CRANK_STROKE = 0.35;
    public static final int TELE_V = 0;
    public static final int TELE_I = 1;

    private final double[] telemetryCell;

    public CrankElement(double[] telemetryCell) {
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
        // South-positive source polarity: terminals[1] (south) is positive.
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
        telemetryCell[TELE_V] = terminalV;
        telemetryCell[TELE_I] = intoPos;
        double speed = Math.max(0.0, Math.min(1.0, state[STATE_SPEED]));
        double deliveredP = Math.max(0.0, terminalV) * Math.max(0.0, intoPos);
        dxdt[STATE_SPEED] = -FRICTION_K_PER_S * speed
            - (deliveredP / RATED_POWER_W) * LOAD_TORQUE_K_PER_S;
        dxdt[STATE_ENERGY] = deliveredP;
    }

    public static double emfForSpeed(double speed) {
        return Math.max(0.0, Math.min(1.0, speed)) * EMF_AT_FULL_SPEED;
    }

    public static boolean isActiveSource(double flywheelSpeed) {
        return flywheelSpeed > 0.0;
    }

    public static double[] newStateArray() {
        return new double[]{0.0, 0.0};
    }

    public static double[] snapshotState(double[] live) {
        Objects.requireNonNull(live, "live");
        return live.clone();
    }

    public static void assignState(double[] dst, double[] src) {
        if (dst == null || dst.length != 2 || src == null || src.length != 2) {
            throw new IllegalArgumentException(
                "HandCrankGeneratorBlockEntity holds 2 states, got dst="
                    + (dst == null ? "null" : dst.length) + " src=" + (src == null ? "null" : src.length));
        }
        dst[STATE_SPEED] = Math.max(0.0, Math.min(1.0, src[STATE_SPEED]));
        dst[STATE_ENERGY] = Math.max(0.0, src[STATE_ENERGY]);
    }

    public static double crankNext(double speed) {
        return Math.min(1.0, Math.max(0.0, speed) + CRANK_STROKE);
    }

    public static void writeNbt(WriteView view, double flywheelSpeed, double totalEnergyJoules) {
        List<Double> boxed = new ArrayList<>(2);
        boxed.add(flywheelSpeed);
        boxed.add(totalEnergyJoules);
        view.put(KEY_STATE_ARRAY, Codec.DOUBLE.listOf(), boxed);
    }

    public static double[] readNbtState(ReadView view) {
        List<Double> list = view.read(KEY_STATE_ARRAY, Codec.DOUBLE.listOf()).orElse(List.of());
        if (list.size() != 2) {
            return newStateArray();
        }
        return new double[]{list.get(0), list.get(1)};
    }
}
