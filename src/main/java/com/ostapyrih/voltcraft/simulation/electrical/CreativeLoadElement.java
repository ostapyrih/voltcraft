package com.ostapyrih.voltcraft.simulation.electrical;

import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

public final class CreativeLoadElement implements ElectricalElement {
    public static final int[][] TERMINAL_OFFSETS = {{0, 0, -1}, {0, 0, 1}};

    /** FACING-relative: {@code [FRONT (-), BACK (+)]}; null facing degrades to NORTH. */
    public static BlockPos[] resolveTerminals(BlockPos pos, Direction facing) {
        Direction f = facing != null ? facing : Direction.NORTH;
        return new BlockPos[]{pos.offset(f), pos.offset(f.getOpposite())};
    }

    public static final int MODE_RESISTANCE = 0;
    public static final int MODE_POWER = 1;
    public static final int MODE_CURRENT = 2;
    public static final double MIN_RESISTANCE_OHM = 1e-4;
    public static final double CP_VMIN_VOLTS = 1.0;
    public static final int TELE_V = 0;
    public static final int TELE_I = 1;

    private final IntSupplier modeOrdinal;
    private final DoubleSupplier targetValue;
    private final BooleanSupplier enabled;
    private final DoubleSupplier nominalVoltage;
    private final double[] telemetryCell;

    public CreativeLoadElement(IntSupplier modeOrdinal, DoubleSupplier targetValue,
                               BooleanSupplier enabled, DoubleSupplier nominalVoltage,
                               double[] telemetryCell) {
        this.modeOrdinal = Objects.requireNonNull(modeOrdinal, "modeOrdinal");
        this.targetValue = Objects.requireNonNull(targetValue, "targetValue");
        this.enabled = Objects.requireNonNull(enabled, "enabled");
        this.nominalVoltage = Objects.requireNonNull(nominalVoltage, "nominalVoltage");
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
    public boolean requiresReturnPath() {
        return true;
    }

    @Override
    public void stamp(Complex[][] y, Complex[] in, int[] terminals, Complex[] v,
                      double[] state, double omega) {
        if (!enabled.getAsBoolean()) {
            return;
        }
        double target = targetValue.getAsDouble();
        if (!Double.isFinite(target) || target <= 0.0) {
            return;
        }
        int mode = modeOrdinal.getAsInt();
        if (mode == MODE_RESISTANCE) {
            double r = Math.max(MIN_RESISTANCE_OHM, target);
            Stamps.admittance(y, terminals[1], terminals[0], new Complex(1.0 / r, 0.0));
            return;
        }
        if (omega == 0.0) {
            if (mode == MODE_POWER) {
                Stamps.constantPower(y, in, terminals[1], terminals[0], v,
                    target, CP_VMIN_VOLTS, 0.0);
            } else if (mode == MODE_CURRENT) {
                Stamps.constantCurrent(y, in, terminals[1], terminals[0], v,
                    target, CP_VMIN_VOLTS, 0.0);
            }
            // Unknown modes stamp nothing (open circuit).
        } else {
            // AC approximation: constant-power/current have no AC linearization, so the
            // load draws resistively at nominal voltage (R = Vnom^2/P, R = Vnom/I).
            double vnom = nominalVoltage.getAsDouble();
            if (Double.isFinite(vnom) && vnom > 0.0) {
                double r;
                if (mode == MODE_POWER) {
                    r = (vnom * vnom) / target;
                } else if (mode == MODE_CURRENT) {
                    r = vnom / target;
                } else {
                    return;
                }
                if (Double.isFinite(r) && r > 0.0) {
                    r = Math.max(MIN_RESISTANCE_OHM, r);
                    Stamps.admittance(y, terminals[1], terminals[0], new Complex(1.0 / r, 0.0));
                }
            }
        }
    }

    @Override
    public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
        double intoPos = 0.0;
        if (it.length > 1 && it[1] != null) {
            intoPos = it[1].re;
        } else if (it.length > 0 && it[0] != null) {
            intoPos = -it[0].re;
        }
        double terminalV = 0.0;
        if (vt.length > 1 && vt[0] != null && vt[1] != null) {
            terminalV = vt[1].re - vt[0].re;
        }
        telemetryCell[TELE_V] = terminalV;
        telemetryCell[TELE_I] = intoPos;
    }

    public static boolean isActiveSource() {
        return false;
    }

    public static double[] newStateArray() {
        return new double[0];
    }

    public static double[] snapshotState(double[] live) {
        Objects.requireNonNull(live, "live");
        if (live.length != 0) {
            throw new IllegalArgumentException(
                "CreativeLoadBlockEntity holds 0 states, got " + live.length);
        }
        return live.clone();
    }

    public static void assignState(double[] dst, double[] src) {
        if (dst == null || dst.length != 0 || src == null || src.length != 0) {
            throw new IllegalArgumentException(
                "CreativeLoadBlockEntity holds 0 states, got dst="
                    + (dst == null ? "null" : dst.length) + " src=" + (src == null ? "null" : src.length));
        }
    }
}
