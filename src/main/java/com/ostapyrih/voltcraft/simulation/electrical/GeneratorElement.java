package com.ostapyrih.voltcraft.simulation.electrical;

import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.block.entity.generation.PortableGeneratorBlockEntity;
import com.mojang.serialization.Codec;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

public final class GeneratorElement implements ElectricalElement {
        public static final int STATE_TEMP = 0;
        public static final int STATE_FUEL = 1;

        public static final String KEY_STATE_ARRAY = "stateArray";
        public static final String KEY_TOTAL_ENERGY = "total_energy_joules";
    public static final int[][] TERMINAL_OFFSETS = {{0, 0, -1}, {0, 0, 1}};

    /** FACING-relative: {@code [FRONT (-), BACK (+)]}; null facing degrades to NORTH. */
    public static BlockPos[] resolveTerminals(BlockPos pos, Direction facing) {
        Direction f = facing != null ? facing : Direction.NORTH;
        return new BlockPos[]{pos.offset(f), pos.offset(f.getOpposite())};
    }
    public static final double INTERNAL_RESISTANCE_OHM = 0.15;
    public static final double OUTPUT_FREQUENCY_HZ = 50.0;
    public static final double IDLE_FUEL_BURN_RATIO = 0.25;
    public static final double LOAD_FUEL_BURN_RATIO = 0.75;
    public static final double TICKS_PER_S = 1.0 / GridConstants.DT;
    public static final double ENGINE_IDLE_HEAT_W = 30.0;
    public static final double ENGINE_LOAD_HEAT_W = 400.0;
    public static final double THERMAL_MASS_J_PER_K = 2000.0;
    public static final double COOLING_COEFF_W_PER_K = 5.0;
    public static final int TELE_I = 0;
    public static final int TELE_P = 1;

    private final BooleanSupplier running;
    private final DoubleSupplier stagedEmf;
    private final double[] telemetryCell;

    public GeneratorElement(BooleanSupplier running, double[] telemetryCell) {
        this(running, () -> PortableGeneratorBlockEntity.OUTPUT_VOLTAGE_RMS, telemetryCell);
    }

    public GeneratorElement(BooleanSupplier running, DoubleSupplier stagedEmf, double[] telemetryCell) {
        this.running = Objects.requireNonNull(running, "running");
        this.stagedEmf = Objects.requireNonNull(stagedEmf, "stagedEmf");
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
        if (!running.getAsBoolean()) {
            return;
        }
        double emf = stagedEmf.getAsDouble();
        if (!(emf > 0.0)) {
            return;
        }
        // South-positive source polarity: terminals[1] (south) is positive.
        Stamps.thevenin(y, in, terminals[1], terminals[0],
            new Complex(1.0 / INTERNAL_RESISTANCE_OHM, 0.0),
            new Complex(emf, 0.0));
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
        double deliveredP = Math.max(0.0, terminalV) * Math.max(0.0, intoPos);
        telemetryCell[TELE_I] = intoPos;
        telemetryCell[TELE_P] = deliveredP;
        double loadFrac = Math.min(1.0,
            deliveredP / PortableGeneratorBlockEntity.RATED_POWER_WATTS);
        double heatingW = ENGINE_IDLE_HEAT_W + ENGINE_LOAD_HEAT_W * loadFrac
            + imag * imag * INTERNAL_RESISTANCE_OHM;
        double coolingW = COOLING_COEFF_W_PER_K * (state[STATE_TEMP] - GridConstants.AMBIENT_C);
        dxdt[STATE_TEMP] = (heatingW - coolingW) / THERMAL_MASS_J_PER_K;
        dxdt[STATE_FUEL] = -(IDLE_FUEL_BURN_RATIO + LOAD_FUEL_BURN_RATIO * loadFrac) * TICKS_PER_S;
    }

    public static boolean isActiveSource(boolean running) {
        return running;
    }

    /**
     * Stages the generator EMF with prime-mover current limiting (AVR droop).
     *
     * <p>An ideal 230 V Thevenin source serves any overload silently at
     * near-nominal voltage; a real inverter generator cannot. The staged EMF
     * holds nominal voltage whenever the previously delivered current stays
     * within the surge rating, and sags to {@code surgeCurrent * R_load}
     * under overload, so the bus brownouts and output caps at surge power
     * instead of exceeding it. The limit is keyed on load resistance (a load
     * property, invariant under EMF changes for resistive AC loads), not on
     * current, so the staging is a one-step contraction with no
     * engage/disengage limit cycle: overloads stay limited, relief recovers
     * immediately, and dead shorts stay current-limited near zero volts.</p>
     *
     * @param running whether the engine runs (fuel remains)
     * @param prevTerminalV previously solved terminal voltage magnitude in volts
     * @param prevDeliveredAmps previously delivered current in amps ({@code > 0} on discharge)
     * @return EMF to stamp this tick in volts
     */
    public static double stageEmf(boolean running, double prevTerminalV, double prevDeliveredAmps) {
        if (!running) {
            return 0.0;
        }
        if (!(prevTerminalV > 1.0) || !(prevDeliveredAmps > 1e-6)) {
            return PortableGeneratorBlockEntity.OUTPUT_VOLTAGE_RMS;
        }
        double surgeI = PortableGeneratorBlockEntity.SURGE_POWER_WATTS
            / PortableGeneratorBlockEntity.OUTPUT_VOLTAGE_RMS;
        double eLim = surgeI * (prevTerminalV / prevDeliveredAmps);
        return Math.min(PortableGeneratorBlockEntity.OUTPUT_VOLTAGE_RMS, Math.max(0.0, eLim));
    }

    public static double surgeCurrentAmps() {
        return PortableGeneratorBlockEntity.SURGE_POWER_WATTS
            / PortableGeneratorBlockEntity.OUTPUT_VOLTAGE_RMS;
    }

    public static double[] newStateArray() {
        return new double[]{GridConstants.AMBIENT_C, 0.0};
    }

    public static double[] snapshotState(double[] live) {
        Objects.requireNonNull(live, "live");
        return live.clone();
    }

    public static void assignState(double[] dst, double[] src) {
        if (dst == null || dst.length != 2 || src == null || src.length != 2) {
            throw new IllegalArgumentException(
                "PortableGeneratorBlockEntity holds 2 states, got dst="
                    + (dst == null ? "null" : dst.length) + " src=" + (src == null ? "null" : src.length));
        }
        dst[STATE_TEMP] = src[STATE_TEMP];
        dst[STATE_FUEL] = Math.max(0.0, src[STATE_FUEL]);
    }

    public static void writeNbt(WriteView view, double tempC, double fuelTicks,
                                double totalEnergyJoules) {
        List<Double> boxed = new ArrayList<>(2);
        boxed.add(tempC);
        boxed.add(fuelTicks);
        view.put(KEY_STATE_ARRAY, Codec.DOUBLE.listOf(), boxed);
        view.putDouble(KEY_TOTAL_ENERGY, totalEnergyJoules);
    }

    public static double[] readNbtState(ReadView view) {
        List<Double> list = view.read(KEY_STATE_ARRAY, Codec.DOUBLE.listOf()).orElse(List.of());
        if (list.size() != 2) {
            return newStateArray();
        }
        return new double[]{list.get(0), list.get(1)};
    }

    public static double readNbtTotalEnergy(ReadView view) {
        return view.getDouble(KEY_TOTAL_ENERGY, 0.0);
    }
}
