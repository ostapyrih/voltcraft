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
import java.util.function.DoubleSupplier;

public final class SolarElement implements ElectricalElement {
        public static final int STATE_TEMP = 0;

        public static final String KEY_STATE_ARRAY = "stateArray";
        public static final String KEY_TOTAL_ENERGY = "total_energy_generated";
    public static final int[][] TERMINAL_OFFSETS = {{0, 0, -1}, {0, 0, 1}};

    /** FACING-relative: {@code [FRONT (-), BACK (+)]}; null facing degrades to NORTH. */
    public static BlockPos[] resolveTerminals(BlockPos pos, Direction facing) {
        Direction f = facing != null ? facing : Direction.NORTH;
        return new BlockPos[]{pos.offset(f), pos.offset(f.getOpposite())};
    }
    public static final double MIN_RESISTANCE_OHM = 0.05;
    public static final double IRRAD_HEAT_W_PER_W_M2 = 0.02;
    public static final double THERMAL_MASS_J_PER_K = 500.0;
    public static final double COOLING_COEFF_W_PER_K = 2.0;
    public static final int TELE_V = 0;
    public static final int TELE_I = 1;

    private final DoubleSupplier electromotiveForce;
    private final DoubleSupplier internalResistance;
    private final DoubleSupplier irradiance;
    private final double[] telemetryCell;

    public SolarElement(DoubleSupplier electromotiveForce, DoubleSupplier internalResistance,
                        DoubleSupplier irradiance, double[] telemetryCell) {
        this.electromotiveForce = Objects.requireNonNull(electromotiveForce, "electromotiveForce");
        this.internalResistance = Objects.requireNonNull(internalResistance, "internalResistance");
        this.irradiance = Objects.requireNonNull(irradiance, "irradiance");
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
        return 1;
    }

    @Override
    public void stamp(Complex[][] y, Complex[] in, int[] terminals, Complex[] v,
                      double[] state, double omega) {
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
        double intoPos = 0.0;
        double imag = 0.0;
        if (it.length > 0 && it[0] != null) {
            intoPos = it[0].re;
            imag = it[0].magnitude();
        }
        double terminalV = 0.0;
        if (vt.length > 1 && vt[0] != null && vt[1] != null) {
            terminalV = vt[1].re - vt[0].re;
        }
        telemetryCell[TELE_V] = terminalV;
        telemetryCell[TELE_I] = intoPos;
        double r = Math.max(MIN_RESISTANCE_OHM, internalResistance.getAsDouble());
        double heatingW = IRRAD_HEAT_W_PER_W_M2 * Math.max(0.0, irradiance.getAsDouble())
            + imag * imag * r;
        double coolingW = COOLING_COEFF_W_PER_K * (state[STATE_TEMP] - GridConstants.AMBIENT_C);
        dxdt[STATE_TEMP] = (heatingW - coolingW) / THERMAL_MASS_J_PER_K;
    }

    public static boolean isActiveSource(double electromotiveForce) {
        return electromotiveForce > 0.0;
    }

    public static double[] newStateArray() {
        return new double[]{GridConstants.AMBIENT_C};
    }

    public static double[] snapshotState(double[] live) {
        Objects.requireNonNull(live, "live");
        return live.clone();
    }

    public static void assignState(double[] dst, double[] src) {
        if (dst == null || dst.length != 1 || src == null || src.length != 1) {
            throw new IllegalArgumentException(
                "SolarPanelBlockEntity holds 1 state, got dst="
                    + (dst == null ? "null" : dst.length) + " src=" + (src == null ? "null" : src.length));
        }
        dst[STATE_TEMP] = src[STATE_TEMP];
    }

    public static void writeNbt(WriteView view, double temperatureC, double totalEnergyJoules) {
        List<Double> boxed = new ArrayList<>(1);
        boxed.add(temperatureC);
        view.put(KEY_STATE_ARRAY, Codec.DOUBLE.listOf(), boxed);
        view.putDouble(KEY_TOTAL_ENERGY, totalEnergyJoules);
    }

    public static double[] readNbtState(ReadView view) {
        List<Double> list = view.read(KEY_STATE_ARRAY, Codec.DOUBLE.listOf()).orElse(List.of());
        if (list.size() != 1) {
            return newStateArray();
        }
        return new double[]{list.get(0)};
    }

    public static double readNbtTotalEnergy(ReadView view) {
        return view.getDouble(KEY_TOTAL_ENERGY, 0.0);
    }
}
