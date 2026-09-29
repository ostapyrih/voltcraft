package com.ostapyrih.voltcraft.simulation.electrical;

import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

public final class CreativeGeneratorElement implements ElectricalElement {
    public static final int[][] TERMINAL_OFFSETS = {{0, 0, -1}, {0, 0, 1}};
    public static final double MIN_RESISTANCE_OHM = 1e-4;
    public static final int TELE_V = 0;
    public static final int TELE_I = 1;

    private final BooleanSupplier enabled;
    private final DoubleSupplier electromotiveForce;
    private final DoubleSupplier internalResistance;
    private final double[] telemetryCell;

    public CreativeGeneratorElement(BooleanSupplier enabled, DoubleSupplier electromotiveForce,
                                    DoubleSupplier internalResistance, double[] telemetryCell) {
        this.enabled = Objects.requireNonNull(enabled, "enabled");
        this.electromotiveForce = Objects.requireNonNull(electromotiveForce, "electromotiveForce");
        this.internalResistance = Objects.requireNonNull(internalResistance, "internalResistance");
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
        return 0;
    }

    @Override
    public void stamp(Complex[][] y, Complex[] in, int[] terminals, Complex[] v,
                      double[] state, double omega) {
        if (!enabled.getAsBoolean()) {
            return;
        }
        double emf = electromotiveForce.getAsDouble();
        if (!(emf > 0.0)) {
            return;
        }
        double r = Math.max(MIN_RESISTANCE_OHM, internalResistance.getAsDouble());
        // South-positive source polarity: terminals[1] (south) is positive.
        Stamps.thevenin(y, in, terminals[1], terminals[0],
            new Complex(1.0 / r, 0.0), new Complex(emf, 0.0));
    }

    @Override
    public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
        double intoNeg = 0.0;
        if (it.length > 0 && it[0] != null) {
            intoNeg = it[0].re;
        }
        double terminalV = 0.0;
        if (vt.length > 1 && vt[0] != null && vt[1] != null) {
            terminalV = vt[1].re - vt[0].re;
        }
        telemetryCell[TELE_V] = terminalV;
        telemetryCell[TELE_I] = intoNeg;
    }

    public static boolean isActiveSource(boolean enabled, double electromotiveForce) {
        return enabled && electromotiveForce > 0.0;
    }

    public static boolean isACSource(double frequencyHz) {
        return frequencyHz > 0.001;
    }

    public static double[] newStateArray() {
        return new double[0];
    }

    public static double[] snapshotState(double[] live) {
        Objects.requireNonNull(live, "live");
        if (live.length != 0) {
            throw new IllegalArgumentException(
                "CreativeGeneratorBlockEntity holds 0 states, got " + live.length);
        }
        return live.clone();
    }

    public static void assignState(double[] dst, double[] src) {
        if (dst == null || dst.length != 0 || src == null || src.length != 0) {
            throw new IllegalArgumentException(
                "CreativeGeneratorBlockEntity holds 0 states, got dst="
                    + (dst == null ? "null" : dst.length) + " src=" + (src == null ? "null" : src.length));
        }
    }
}
