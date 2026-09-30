package com.ostapyrih.voltcraft.simulation.electrical;

import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.simulation.chemistry.BatteryChemistry;
import com.ostapyrih.voltcraft.simulation.chemistry.BatterySimulation;
import com.mojang.serialization.Codec;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class BatteryElement implements ElectricalElement {
        public static final int STATE_SOC = 0;
        public static final int STATE_TEMP = 1;
        public static final int STATE_HEALTH = 2;

        public static final String KEY_STATE_ARRAY = "stateArray";
        public static final String KEY_BMS_OPEN = "bmsOpen";
    public static final int[][] TERMINAL_OFFSETS = {{0, 0, -1}, {0, 0, 1}};

    /** FACING-relative: {@code [FRONT (-), BACK (+)]}; null facing degrades to NORTH. */
    public static BlockPos[] resolveTerminals(BlockPos pos, Direction facing) {
        Direction f = facing != null ? facing : Direction.NORTH;
        return new BlockPos[]{pos.offset(f), pos.offset(f.getOpposite())};
    }
    public static final double MIN_PACK_RESISTANCE_OHM = 1e-4;
    public static final double BMS_OVERTEMP_OPEN_C = 60.0;
    /** Reclose threshold below the trip point (hysteresis against chatter). */
    public static final double BMS_OVERTEMP_CLOSE_C = 55.0;
    public static final double BMS_RECOVERY_HYST_V_PER_CELL = 0.05;
    public static final double HEALTH_DECAY_K = 1e-9;
    public static final int TELE_V = 0;
    public static final int TELE_I = 1;

    private final BatteryChemistry chemistry;
    private final int seriesCount;
    private final int parallelCount;
    private final BooleanSupplier bmsOpen;
    private final double[] telemetryCell;

    public BatteryElement(BatteryChemistry chemistry, int seriesCount, int parallelCount,
                          BooleanSupplier bmsOpen, double[] telemetryCell) {
        this.chemistry = Objects.requireNonNull(chemistry, "chemistry");
        this.seriesCount = Math.max(1, seriesCount);
        this.parallelCount = Math.max(1, parallelCount);
        this.bmsOpen = Objects.requireNonNull(bmsOpen, "bmsOpen");
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
        return 3;
    }

    @Override
    public void stamp(Complex[][] y, Complex[] in, int[] terminals, Complex[] v,
                      double[] state, double omega) {
        if (bmsOpen.getAsBoolean()) {
            return;
        }
        double soc = clamp01(state[STATE_SOC]);
        double emf = packEmf(chemistry, seriesCount, soc);
        double r = Math.max(MIN_PACK_RESISTANCE_OHM,
            packResistance(chemistry, seriesCount, parallelCount, soc,
                state[STATE_TEMP], state[STATE_HEALTH]));
        // South-positive source polarity: terminals[1] (south) is positive, so It[0] > 0 on discharge.
        Stamps.thevenin(y, in, terminals[1], terminals[0],
            new Complex(1.0 / r, 0.0), new Complex(emf, 0.0));
    }

    @Override
    public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
        double intoNeg = 0.0;
        double imag = 0.0;
        if (it.length > 0 && it[0] != null) {
            intoNeg = it[0].re;
            imag = it[0].magnitude();
        }
        double terminalV = 0.0;
        if (vt.length > 1 && vt[0] != null && vt[1] != null) {
            terminalV = vt[1].re - vt[0].re;
        }
        telemetryCell[TELE_V] = terminalV;
        telemetryCell[TELE_I] = intoNeg;
        double soc = clamp01(state[STATE_SOC]);
        double q = Math.max(1.0, packCapacityCoulombs(chemistry, parallelCount));
        dxdt[STATE_SOC] = -intoNeg / q;
        double r = Math.max(MIN_PACK_RESISTANCE_OHM,
            packResistance(chemistry, seriesCount, parallelCount, soc,
                state[STATE_TEMP], state[STATE_HEALTH]));
        double heatingW = imag * imag * r;
        double hCell = Math.max(0.05, chemistry.getCapacityAmpHours() * 0.02);
        double coolingW = hCell * seriesCount * parallelCount
            * (state[STATE_TEMP] - GridConstants.AMBIENT_C);
        double cellMass = chemistry.getCapacityAmpHours() <= 5.0 ? 40.0
            : chemistry.getCapacityAmpHours() * 20.0;
        double totalMass = Math.max(10.0, cellMass * seriesCount * parallelCount);
        dxdt[STATE_TEMP] = (heatingW - coolingW) / totalMass;
        boolean overcurrent = imag > maxDischargeAmps(chemistry, parallelCount);
        boolean overtemp = state[STATE_TEMP] > BMS_OVERTEMP_OPEN_C;
        dxdt[STATE_HEALTH] = (overcurrent || overtemp) ? -HEALTH_DECAY_K * imag : 0.0;
    }

    /** Pack open-circuit EMF: {@code series * OCV(chemistry, soc)}. */
    public static double packEmf(BatteryChemistry chemistry, int series, double soc) {
        return Math.max(1, series) * BatterySimulation.getOpenCircuitVoltage(chemistry, clamp01(soc));
    }

    /** Pack series resistance: {@code cellR * series / parallel}. */
    public static double packResistance(BatteryChemistry chemistry, int series, int parallel,
                                        double soc, double tempC, double health) {
        double cellR = BatterySimulation.getInternalResistance(
            chemistry, clamp01(soc), tempC, Math.max(0.1, Math.min(1.0, health)));
        return (cellR * Math.max(1, series)) / (double) Math.max(1, parallel);
    }

    /** Pack charge capacity in coulombs. */
    public static double packCapacityCoulombs(BatteryChemistry chemistry, int parallel) {
        return chemistry.getCapacityAmpHours() * Math.max(1, parallel) * 3600.0;
    }

    /** Pack undervoltage trip threshold. */
    public static double packMinVoltage(BatteryChemistry chemistry, int series) {
        return Math.max(1, series) * chemistry.getCutoffVoltage();
    }

    /** Pack surge ceiling. */
    public static double packMaxVoltage(BatteryChemistry chemistry, int series) {
        return Math.max(1, series) * chemistry.getFullChargeVoltage() * 1.05;
    }

    /** Pack overcurrent threshold. */
    public static double maxDischargeAmps(BatteryChemistry chemistry, int parallel) {
        return Math.max(1, parallel) * chemistry.getMaxDischargeCurrentAmps();
    }

    /** Discrete BMS transition: opens below cutoff or overtemp, recloses with hysteresis. */
    public static boolean bmsNext(boolean open, double terminalV, double tempC,
                                  double minPackV, int series) {
        if (open) {
            double recoverV = minPackV + Math.max(1, series) * BMS_RECOVERY_HYST_V_PER_CELL;
            return !(terminalV > recoverV && tempC < BMS_OVERTEMP_CLOSE_C);
        }
        return terminalV < minPackV || tempC > BMS_OVERTEMP_OPEN_C;
    }

    public static boolean isActiveSource(boolean bmsOpen) {
        return !bmsOpen;
    }

    public static double[] newStateArray() {
        return new double[]{1.0, GridConstants.AMBIENT_C, 1.0};
    }

    public static double[] snapshotState(double[] live) {
        Objects.requireNonNull(live, "live");
        return live.clone();
    }

    public static void assignState(double[] dst, double[] src) {
        if (dst == null || dst.length != 3 || src == null || src.length != 3) {
            throw new IllegalArgumentException(
                "BatteryBlockEntity holds 3 states, got dst="
                    + (dst == null ? "null" : dst.length) + " src=" + (src == null ? "null" : src.length));
        }
        dst[STATE_SOC] = clamp01(src[STATE_SOC]);
        dst[STATE_TEMP] = src[STATE_TEMP];
        dst[STATE_HEALTH] = clamp01(src[STATE_HEALTH]);
    }

    public static void writeNbt(WriteView view, double soc, double tempC, double health,
                                boolean bmsOpen) {
        List<Double> boxed = new ArrayList<>(3);
        boxed.add(soc);
        boxed.add(tempC);
        boxed.add(health);
        view.put(KEY_STATE_ARRAY, Codec.DOUBLE.listOf(), boxed);
        view.putBoolean(KEY_BMS_OPEN, bmsOpen);
    }

    public static double[] readNbtState(ReadView view) {
        List<Double> list = view.read(KEY_STATE_ARRAY, Codec.DOUBLE.listOf()).orElse(List.of());
        if (list.size() != 3) {
            return newStateArray();
        }
        return new double[]{list.get(0), list.get(1), list.get(2)};
    }

    public static boolean readNbtBmsOpen(ReadView view) {
        return view.getBoolean(KEY_BMS_OPEN, false);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
