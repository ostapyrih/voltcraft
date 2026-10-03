package com.ostapyrih.voltcraft.simulation.electrical;

import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.mojang.serialization.Codec;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/**
 * Four-terminal kernel element: input pair draws staged demand, output pair sources
 * staged EMF. Holds no kernel state; telemetry is a write-only cache.
 */
public final class ConverterElement implements ElectricalElement {
    public static final int[][] TERMINAL_OFFSETS = {{0, 0, 1}, {-1, 0, 0}, {0, 0, -1}, {1, 0, 0}};

    /** FACING-relative resolution ({@code [BACK+, LEFT−, FRONT+, RIGHT−]}); null degrades to NORTH. */
    public static BlockPos[] resolveConverterTerminals(BlockPos pos, Direction facing) {
        Direction f = facing != null ? facing : Direction.NORTH;
        return new BlockPos[]{
            pos.offset(f.getOpposite()),
            pos.offset(f.rotateYCounterclockwise()),
            pos.offset(f),
            pos.offset(f.rotateYClockwise())
        };
    }
    public static final double SOURCE_R_OHM = 0.05;
    public static final double IDLE_DRAW_WATTS = 2.0;
    public static final double CP_VMIN_VOLTS = 1.0;
    public static final double DEAD_RAIL_VOLTS = 1.0;
    public static final double MIN_EFFICIENCY = 0.1;
    public static final double OVERVOLTAGE_MARGIN = 1.3;
    /** UVLO trip margin: sustained operation below {@code 0.9 * minVin} latches a trip. */
    public static final double UVLO_TRIP_MARGIN = 0.9;
    public static final int UVLO_TRIP_TICKS = 6;
    public static final double ANTI_ISLAND_MIN_VOLTS = 20.0;
    public static final int ANTI_ISLAND_TRIP_TICKS = 4;
    public static final double THERMAL_TRIP_C = 125.0;

    public static final int TYPE_KIND_DC_DC = 0;
    public static final int TYPE_KIND_INVERTER = 1;
    public static final int TYPE_KIND_TRANSFORMER = 2;
    public static final int TYPE_KIND_RECTIFIER = 3;
    public static final int TYPE_KIND_EU = 4;

    public static final int TELE_P_OUT = 0;
    public static final int TELE_V_IN = 1;
    public static final int TELE_V_OUT = 2;
    public static final int TELE_I_OUT = 3;
    public static final int TELE_LEN = 4;

    public static final String KEY_STATE_ARRAY = "stateArray";
    public static final String KEY_TRIPPED = "tripped";

    private final BooleanSupplier tripped;
    private final DoubleSupplier inputDemandWatts;
    private final DoubleSupplier outputEmf;
    private final DoubleSupplier nominalInputVoltage;
    private final double sourceROhm;
    private final double[] telemetryCell;

    public ConverterElement(BooleanSupplier tripped, DoubleSupplier inputDemandWatts,
                            DoubleSupplier outputEmf, DoubleSupplier nominalInputVoltage,
                            double[] telemetryCell) {
        this(tripped, inputDemandWatts, outputEmf, nominalInputVoltage, SOURCE_R_OHM, telemetryCell);
    }

    public ConverterElement(BooleanSupplier tripped, DoubleSupplier inputDemandWatts,
                            DoubleSupplier outputEmf, DoubleSupplier nominalInputVoltage,
                            double sourceROhm, double[] telemetryCell) {
        this.tripped = Objects.requireNonNull(tripped, "tripped");
        this.inputDemandWatts = Objects.requireNonNull(inputDemandWatts, "inputDemandWatts");
        this.outputEmf = Objects.requireNonNull(outputEmf, "outputEmf");
        this.nominalInputVoltage = Objects.requireNonNull(nominalInputVoltage, "nominalInputVoltage");
        if (!Double.isFinite(sourceROhm) || sourceROhm == 0.0) {
            throw new IllegalArgumentException(
                "sourceROhm must be finite and nonzero (negative allowed for cable-compensated regulators): "
                    + sourceROhm);
        }
        this.sourceROhm = sourceROhm;
        if (telemetryCell.length < TELE_LEN) {
            throw new IllegalArgumentException("telemetryCell needs length >= " + TELE_LEN);
        }
        this.telemetryCell = telemetryCell;
    }

    /** Output Thevenin series resistance in ohms (CV regulation stiffness). */
    public double sourceResistance() {
        return sourceROhm;
    }

    @Override
    public int terminalCount() {
        return 4;
    }

    @Override
    public int stateCount() {
        return 0;
    }

    @Override
    public void stamp(Complex[][] y, Complex[] in, int[] terminals, Complex[] v,
                      double[] state, double omega) {
        if (tripped.getAsBoolean()) {
            return;
        }
        double demand = inputDemandWatts.getAsDouble();
        if (Double.isFinite(demand) && demand > 0.0) {
            if (omega == 0.0) {
                Stamps.constantPower(y, in, terminals[0], terminals[1], v,
                    demand, CP_VMIN_VOLTS, 0.0);
            } else {
                // AC approximation: constant-power has no AC linearization, so the input
                // draws the same power resistively at nominal voltage (R = Vnom^2 / P).
                double vnom = nominalInputVoltage.getAsDouble();
                if (Double.isFinite(vnom) && vnom > 0.0) {
                    double r = (vnom * vnom) / demand;
                    if (Double.isFinite(r) && r > 0.0) {
                        Stamps.admittance(y, terminals[0], terminals[1],
                            new Complex(1.0 / r, 0.0));
                    }
                }
            }
        }
        double emf = outputEmf.getAsDouble();
        if (Double.isFinite(emf) && emf > 0.0) {
            Stamps.thevenin(y, in, terminals[2], terminals[3],
                new Complex(1.0 / sourceROhm, 0.0), new Complex(emf, 0.0));
        }
    }

    @Override
    public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
        double pOut = 0.0;
        double vIn = 0.0;
        double vOut = 0.0;
        double iOut = 0.0;
        if (vt != null && it != null) {
            if (vt.length > 3 && it.length > 3
                    && vt[2] != null && vt[3] != null && it[2] != null && it[3] != null) {
                // powerInto sign is positive on consumption; delivered power negates it.
                double consumed = vt[2].re * it[2].re + vt[2].im * it[2].im
                    + vt[3].re * it[3].re + vt[3].im * it[3].im;
                pOut = -consumed;
            }
            if (vt.length > 1 && vt[0] != null && vt[1] != null) {
                vIn = vt[0].re - vt[1].re;
            }
            if (vt.length > 3 && vt[2] != null && vt[3] != null) {
                vOut = vt[2].re - vt[3].re;
            }
            if (it.length > 2 && it[2] != null) {
                iOut = it[2].magnitude();
            }
        }
        telemetryCell[TELE_P_OUT] = pOut;
        telemetryCell[TELE_V_IN] = vIn;
        telemetryCell[TELE_V_OUT] = vOut;
        telemetryCell[TELE_I_OUT] = iOut;
    }

    public static boolean isActiveSource(boolean tripped, double stagedEmf) {
        return !tripped && stagedEmf > 0.0;
    }

    public static boolean isACOutput(int typeKind) {
        return typeKind == TYPE_KIND_INVERTER || typeKind == TYPE_KIND_TRANSFORMER;
    }

    /** Stages input demand as {@code delivered / efficiency + idle}; zero on dead/tripped rail. */
    public static double stageDemandWatts(double lastOutputPowerW, double efficiency,
                                          boolean tripped, double inputVoltageV) {
        if (tripped || !(inputVoltageV > DEAD_RAIL_VOLTS)) {
            return 0.0;
        }
        double delivered = Double.isFinite(lastOutputPowerW) ? Math.max(0.0, lastOutputPowerW) : 0.0;
        double eff = Double.isFinite(efficiency) ? efficiency : MIN_EFFICIENCY;
        eff = Math.max(MIN_EFFICIENCY, eff);
        return delivered / eff + IDLE_DRAW_WATTS;
    }

    /** Stages output EMF; held open when tripped, dead-railed, or above {@code 1.3 * maxVin}. */
    public static double stageEmf(double rawTargetV, boolean tripped,
                                  double inputVoltageV, double maxInputV) {
        if (tripped || !(inputVoltageV > DEAD_RAIL_VOLTS)) {
            return 0.0;
        }
        if (Double.isFinite(maxInputV) && Double.isFinite(inputVoltageV)
                && inputVoltageV > maxInputV * OVERVOLTAGE_MARGIN) {
            return 0.0;
        }
        if (!Double.isFinite(rawTargetV) || rawTargetV <= 0.0) {
            return 0.0;
        }
        return rawTargetV;
    }

    /** Latched trip OR: sticky once tripped, immediate on overtemperature. */
    public static boolean tripNext(boolean tripped, double temperatureC,
                                   boolean overloadNow, boolean islandNow, boolean uvloNow) {
        if (tripped) {
            return true;
        }
        if (temperatureC >= THERMAL_TRIP_C) {
            return true;
        }
        return overloadNow || islandNow || uvloNow;
    }

    /** Sustained-condition counter: increments while held, else resets. */
    public static int advanceCounter(int count, boolean condition) {
        return condition ? count + 1 : 0;
    }

    public static void resetTelemetry(double[] cell) {
        Objects.requireNonNull(cell, "cell");
        if (cell.length < TELE_LEN) {
            throw new IllegalArgumentException("telemetryCell needs length >= " + TELE_LEN);
        }
        for (int k = 0; k < TELE_LEN; k++) {
            cell[k] = 0.0;
        }
    }

    public static double[] newStateArray() {
        return new double[0];
    }

    public static double[] snapshotState(double[] live) {
        Objects.requireNonNull(live, "live");
        if (live.length != 0) {
            throw new IllegalArgumentException(
                "Converter holds 0 states, got " + live.length);
        }
        return live.clone();
    }

    public static void assignState(double[] dst, double[] src) {
        if (dst == null || dst.length != 0 || src == null || src.length != 0) {
            throw new IllegalArgumentException(
                "Converter holds 0 states, got dst="
                    + (dst == null ? "null" : dst.length) + " src=" + (src == null ? "null" : src.length));
        }
    }

    public static void writeNbt(WriteView view, boolean tripped) {
        view.put(KEY_STATE_ARRAY, Codec.DOUBLE.listOf(), List.<Double>of());
        view.putBoolean(KEY_TRIPPED, tripped);
    }

    public static double[] readNbtState(ReadView view) {
        view.read(KEY_STATE_ARRAY, Codec.DOUBLE.listOf());
        return newStateArray();
    }

    public static boolean readNbtBlown(ReadView view) {
        return view.getBoolean(KEY_TRIPPED, false);
    }
}
