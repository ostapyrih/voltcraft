package com.ostapyrih.voltcraft.simulation.electrical;

import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.simulation.chemistry.BatteryChemistry;
import com.mojang.serialization.Codec;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

public final class RackElement implements ElectricalElement {
        public static final String KEY_STATE_ARRAY = "stateArray";
        public static final String KEY_BMS_OPEN = "bmsOpen";
        public static final String KEY_WIRING_MODE = "wiring_mode";
    public static final int[][] TERMINAL_OFFSETS = {{0, 0, -1}, {0, 0, 1}};
    public static final BatteryChemistry DEFAULT_CHEMISTRY = BatteryChemistry.LI_ION_18650;
    public static final String WIRING_SERIES = "SERIES";
    public static final String WIRING_PARALLEL = "PARALLEL";

    private final IntSupplier cellCount;
    private final BooleanSupplier seriesMode;
    private final BooleanSupplier bmsOpen;
    private final double[] telemetryCell;

    public RackElement(IntSupplier cellCount, BooleanSupplier seriesMode,
                       BooleanSupplier bmsOpen, double[] telemetryCell) {
        this.cellCount = Objects.requireNonNull(cellCount, "cellCount");
        this.seriesMode = Objects.requireNonNull(seriesMode, "seriesMode");
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
        if (bmsOpen.getAsBoolean() || Math.max(0, cellCount.getAsInt()) == 0) {
            return;
        }
        int series = stagedSeries();
        int parallel = stagedParallel();
        double soc = Math.max(0.0, Math.min(1.0, state[BatteryElement.STATE_SOC]));
        double emf = BatteryElement.packEmf(DEFAULT_CHEMISTRY, series, soc);
        double r = Math.max(BatteryElement.MIN_PACK_RESISTANCE_OHM,
            BatteryElement.packResistance(DEFAULT_CHEMISTRY, series, parallel,
                soc, state[BatteryElement.STATE_TEMP], state[BatteryElement.STATE_HEALTH]));
        // South-positive source polarity: terminals[1] (south) is positive.
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
        telemetryCell[BatteryElement.TELE_V] = terminalV;
        telemetryCell[BatteryElement.TELE_I] = intoNeg;
        int series = stagedSeries();
        int parallel = stagedParallel();
        double soc = Math.max(0.0, Math.min(1.0, state[BatteryElement.STATE_SOC]));
        double q = Math.max(1.0,
            BatteryElement.packCapacityCoulombs(DEFAULT_CHEMISTRY, parallel));
        dxdt[BatteryElement.STATE_SOC] = -intoNeg / q;
        double r = Math.max(BatteryElement.MIN_PACK_RESISTANCE_OHM,
            BatteryElement.packResistance(DEFAULT_CHEMISTRY, series, parallel,
                soc, state[BatteryElement.STATE_TEMP], state[BatteryElement.STATE_HEALTH]));
        double heatingW = imag * imag * r;
        double hCell = Math.max(0.05, DEFAULT_CHEMISTRY.getCapacityAmpHours() * 0.02);
        double coolingW = hCell * Math.max(1, series) * Math.max(1, parallel)
            * (state[BatteryElement.STATE_TEMP] - GridConstants.AMBIENT_C);
        double cellMass = DEFAULT_CHEMISTRY.getCapacityAmpHours() <= 5.0 ? 40.0
            : DEFAULT_CHEMISTRY.getCapacityAmpHours() * 20.0;
        double totalMass = Math.max(10.0,
            cellMass * Math.max(1, series) * Math.max(1, parallel));
        dxdt[BatteryElement.STATE_TEMP] = (heatingW - coolingW) / totalMass;
        boolean overcurrent = imag
            > BatteryElement.maxDischargeAmps(DEFAULT_CHEMISTRY, parallel);
        boolean overtemp = state[BatteryElement.STATE_TEMP]
            > BatteryElement.BMS_OVERTEMP_OPEN_C;
        dxdt[BatteryElement.STATE_HEALTH] = (overcurrent || overtemp)
            ? -BatteryElement.HEALTH_DECAY_K * imag : 0.0;
    }

    public int stagedSeries() {
        return seriesMode.getAsBoolean() ? Math.max(1, cellCount.getAsInt()) : 1;
    }

    public int stagedParallel() {
        return seriesMode.getAsBoolean() ? 1 : Math.max(1, cellCount.getAsInt());
    }

    public double stagedMinVoltage() {
        return BatteryElement.packMinVoltage(DEFAULT_CHEMISTRY, stagedSeries());
    }

    public static boolean isActiveSource(boolean bmsOpen, int cellCount) {
        return !bmsOpen && cellCount > 0;
    }

    public static double[] newStateArray() {
        return new double[]{0.0, GridConstants.AMBIENT_C, 1.0};
    }

    public static void writeNbt(WriteView view, double soc, double tempC, double health,
                                boolean bmsOpen, boolean seriesMode) {
        List<Double> boxed = new ArrayList<>(3);
        boxed.add(soc);
        boxed.add(tempC);
        boxed.add(health);
        view.put(KEY_STATE_ARRAY, Codec.DOUBLE.listOf(), boxed);
        view.putBoolean(KEY_BMS_OPEN, bmsOpen);
        view.putString(KEY_WIRING_MODE, seriesMode ? WIRING_SERIES : WIRING_PARALLEL);
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

    public static boolean readNbtSeriesMode(ReadView view) {
        return !WIRING_PARALLEL.equals(view.getString(KEY_WIRING_MODE, WIRING_SERIES));
    }
}
