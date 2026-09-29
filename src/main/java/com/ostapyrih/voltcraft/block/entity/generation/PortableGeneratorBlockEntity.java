package com.ostapyrih.voltcraft.block.entity.generation;

import com.mojang.serialization.Codec;
import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.block.generation.PortableGeneratorBlock;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 1.8-2.2 kW Portable Inverter Generator kernel adapter.
 * Provides 230V 50Hz pure sine AC power with load-dependent eco-throttle fuel consumption.
 *
 * <p>Structural note: all kernel decision logic lives in the static nested
 * {@link GeneratorElement} with the running flag supplier-injected and telemetry written
 * into an injected cell, because unit-test runtimes cannot initialize
 * {@code BlockEntity} subclasses at all ({@code BlockEntity.&lt;clinit&gt;} touches
 * {@code Registries}). Production wires {@code this::isRunning} plus the BE-owned
 * telemetry cell; tests inject their own cells. The outer BE still owns the
 * {@code double[2]} state array, the discrete energy counter, and the telemetry cell —
 * the nested class is a pure function of its inputs.</p>
 *
 * <ul>
 *   <li>Terminals (item 4): two adjacent positions, east/west. Phase-C source polarity
 *       convention: {@code terminals[1]} (west) is positive. (The Block still exposes
 *       only the front-socket face via {@code canConnect}; terminal positions stay
 *       east/west like Phase B.)</li>
 *   <li>States (item 7): exactly 2 kernel-owned reals, {@code [temperatureC,
 *       remainingFuelTicks]}. Defensive copies on {@link #getStateArray()} (clone) /
 *       {@link #setStateArray(double[])} (copy into BE-owned storage, validated; fuel
 *       floored at 0). Fresh generators start at {@code [AMBIENT_C, 0.0]} (empty tank).
 *       The topology owner ({@code GridManager}) must seed kernel state from the BE after
 *       every {@code setElements} and copy kernel state back into the BE after every tick
 *       before the discrete phase.</li>
 *   <li>Element stamp: Thevenin {@code 230 V RMS + 0.15 ohm} when running, open circuit
 *       when the tank is dry.</li>
 *   <li>Derivatives: {@code dT/dt = (IDLE_W + LOAD_W * loadFrac + I^2*R - COOL*(T-AMB)) / C}
 *       with {@code IDLE_W = 30}, {@code LOAD_W = 400}, {@code C = 2000 J/K},
 *       {@code COOL = 5 W/K}; {@code dFuel/dt = -(IDLE_BURN + LOAD_BURN * loadFrac) * TICKS_PER_S}
 *       with the legacy eco-throttle ratios ({@code 0.25} idle, {@code 0.75} at full
 *       rated load, {@code loadFrac = min(1, P / 1800)}) scaled by
 *       {@code TICKS_PER_S = 1 / DT = 20} because fuel is denominated in ticks while
 *       kernel derivatives are per second. Load power is evaluated live from
 *       {@code Vt}/{@code It} ({@code P = max(0, (Vt[1]-Vt[0]) * It[0])}).</li>
 *   <li>Telemetry cache (item 12): {@code derivatives} stores delivered current
 *       ({@code It[0]}, positive while generating) and delivered power into the injected
 *       cell. Write-only, never read by stamp/derivative control flow; meaningful only
 *       after {@code kernel.tick()}.</li>
 *   <li>{@link #tickElectrical(ServerWorld)} is discrete-only (item 10): it mirrors the
 *       {@code RUNNING} blockstate when a world is present (null-safe no-op otherwise),
 *       folds previous-tick telemetry power into the discrete energy counter, and zeroes
 *       telemetry when dry. Fuel burn itself moved to kernel derivatives. It never
 *       mutates the state array and never touches the kernel.</li>
 * </ul>
 *
 * <p>Fallback rollback contract (item 11): on {@code fallbackActive} the BE state array
 * is authoritative — re-sync the kernel from the BE before the next solve and skip the
 * commit; otherwise commit kernel state into the BE before the discrete phase.
 * Per-island semantics. The kernel integrates even on fallback; that state is discarded.</p>
 *
 * <p>NBT keys: {@code "stateArray"} ({@code Codec.DOUBLE.listOf()},
 * {@code [temperatureC, remainingFuelTicks]}), {@code "total_energy_joules"} (preserved
 * legacy key for the discrete energy counter), plus preserved legacy mirror
 * {@code "remaining_fuel_ticks"}. Reads prefer {@code stateArray} with exactly 2 finite
 * entries, else the legacy fuel key with ambient temperature, else fresh defaults.
 * Telemetry is transient and not persisted.</p>
 */
public class PortableGeneratorBlockEntity extends BlockEntity implements KernelAttachedBlock {

    public static final double RATED_POWER_WATTS = 1800.0;
    public static final double SURGE_POWER_WATTS = 2200.0;
    public static final double OUTPUT_VOLTAGE_RMS = 230.0;

    /** State index of the engine temperature in Celsius. */
    public static final int STATE_TEMP = 0;
    /** State index of the remaining fuel in ticks. */
    public static final int STATE_FUEL = 1;

    /** NBT key for the kernel state slice {@code [temperatureC, remainingFuelTicks]}. */
    public static final String KEY_STATE_ARRAY = "stateArray";
    /** Preserved legacy NBT key for the discrete lifetime energy counter. */
    public static final String KEY_TOTAL_ENERGY = "total_energy_joules";
    /** Preserved legacy NBT mirror of the fuel state. */
    public static final String KEY_FUEL_LEGACY = "remaining_fuel_ticks";

    /**
     * Static kernel element + state/NBT helpers. Fully unit-testable without any
     * registry, world, or block-entity instance.
     */
    public static final class GeneratorElement implements ElectricalElement {
        /** Terminal offsets: east / west of the BE position. */
        public static final int[][] TERMINAL_OFFSETS = {{1, 0, 0}, {-1, 0, 0}};
        /** Inverter bridge series resistance in ohms. */
        public static final double INTERNAL_RESISTANCE_OHM = 0.15;
        /** Pure sine wave output frequency in Hz. */
        public static final double OUTPUT_FREQUENCY_HZ = 50.0;
        /** Eco-throttle idle burn ratio (fraction of full burn at no load). */
        public static final double IDLE_FUEL_BURN_RATIO = 0.25;
        /** Eco-throttle load burn ratio (added at full rated load). */
        public static final double LOAD_FUEL_BURN_RATIO = 0.75;
        /** Fuel-tick scaling: kernel derivatives are per second, fuel is in ticks. */
        public static final double TICKS_PER_S = 1.0 / GridConstants.DT;
        /** Engine idle heat in watts. */
        public static final double ENGINE_IDLE_HEAT_W = 30.0;
        /** Engine full-load heat in watts. */
        public static final double ENGINE_LOAD_HEAT_W = 400.0;
        /** Engine thermal mass in J/K. */
        public static final double THERMAL_MASS_J_PER_K = 2000.0;
        /** Linear cooling coefficient in W/K toward ambient. */
        public static final double COOLING_COEFF_W_PER_K = 5.0;
        /** Telemetry cell index of the delivered current in amps (positive while generating). */
        public static final int TELE_I = 0;
        /** Telemetry cell index of the delivered power in watts. */
        public static final int TELE_P = 1;

        private final BooleanSupplier running;
        private final double[] telemetryCell;

        /**
         * @param running supplier for the BE-owned running flag (fuel {@code > 0});
         *        read at stamp time only
         * @param telemetryCell BE/test-owned write-only cache {@code [deliveredI, deliveredP]},
         *        length {@code >= 2}
         */
        public GeneratorElement(BooleanSupplier running, double[] telemetryCell) {
            this.running = Objects.requireNonNull(running, "running");
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
            if (!running.getAsBoolean()) {
                return;
            }
            // Phase-C polarity: terminals[1] (west) is positive.
            Stamps.thevenin(y, in, terminals[1], terminals[0],
                new Complex(1.0 / INTERNAL_RESISTANCE_OHM, 0.0),
                new Complex(PortableGeneratorBlockEntity.OUTPUT_VOLTAGE_RMS, 0.0));
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
            // Telemetry-only cache (item 28): write-only, never read by control flow.
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

        /** Source classification: active whenever fuel remains. */
        public static boolean isActiveSource(boolean running) {
            return running;
        }

        /** Surge current limit in amps ({@code SURGE_W / 230 V} ≈ 9.56 A). */
        public static double surgeCurrentAmps() {
            return PortableGeneratorBlockEntity.SURGE_POWER_WATTS
                / PortableGeneratorBlockEntity.OUTPUT_VOLTAGE_RMS;
        }

        /** Fresh-generator kernel state {@code [AMBIENT_C, 0.0]} (defensive: new array per call). */
        public static double[] newStateArray() {
            return new double[]{GridConstants.AMBIENT_C, 0.0};
        }

        /** Defensive snapshot: returns a clone, never the live array. */
        public static double[] snapshotState(double[] live) {
            Objects.requireNonNull(live, "live");
            return live.clone();
        }

        /**
         * Copies {@code src} into BE-owned {@code dst} (defensive: never retains the
         * kernel array by reference). Both must be non-null, length 2. Fuel is floored
         * at 0; temperature passes through.
         */
        public static void assignState(double[] dst, double[] src) {
            if (dst == null || dst.length != 2 || src == null || src.length != 2) {
                throw new IllegalArgumentException(
                    "PortableGeneratorBlockEntity holds 2 states, got dst="
                        + (dst == null ? "null" : dst.length) + " src=" + (src == null ? "null" : src.length));
            }
            dst[STATE_TEMP] = src[STATE_TEMP];
            dst[STATE_FUEL] = Math.max(0.0, src[STATE_FUEL]);
        }

        /** Writes the state slice, the legacy fuel mirror, and the discrete energy counter. */
        public static void writeNbt(WriteView view, double tempC, double fuelTicks,
                                    double totalEnergyJoules) {
            List<Double> boxed = new ArrayList<>(2);
            boxed.add(tempC);
            boxed.add(fuelTicks);
            view.put(KEY_STATE_ARRAY, Codec.DOUBLE.listOf(), boxed);
            view.putDouble(KEY_FUEL_LEGACY, fuelTicks);
            view.putDouble(KEY_TOTAL_ENERGY, totalEnergyJoules);
        }

        /**
         * Reads the state slice; prefers {@code stateArray} with exactly 2 finite entries,
         * else the legacy fuel key with ambient temperature, else fresh defaults
         * (forward-tolerant).
         */
        public static double[] readNbtState(ReadView view) {
            List<Double> list = view.read(KEY_STATE_ARRAY, Codec.DOUBLE.listOf()).orElse(List.of());
            if (list.size() == 2 && list.get(0) != null && list.get(1) != null
                    && Double.isFinite(list.get(0)) && Double.isFinite(list.get(1))) {
                return new double[]{list.get(0), list.get(1)};
            }
            double fuel = view.getDouble(KEY_FUEL_LEGACY, Double.NaN);
            if (Double.isFinite(fuel)) {
                return new double[]{GridConstants.AMBIENT_C, fuel};
            }
            return newStateArray();
        }

        /** Reads the discrete lifetime energy counter (defaults to 0 when absent). */
        public static double readNbtTotalEnergy(ReadView view) {
            return view.getDouble(KEY_TOTAL_ENERGY, 0.0);
        }
    }

    private static final int BLOCKSTATE_UPDATE_FLAGS = 3; // notify neighbors + sync to client

    private final double[] stateArray = GeneratorElement.newStateArray();
    private final double[] telemetryCell = new double[2];
    private double totalEnergyJoules = 0.0;

    private final ElectricalElement element;

    public PortableGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(VoltcraftBlockEntityTypes.PORTABLE_GENERATOR_BLOCK_ENTITY, pos, state);
        this.element = new GeneratorElement(this::isRunning, telemetryCell);
    }

    /** Player refuel interaction: appends furnace-fuel ticks to the BE-owned fuel state. */
    public void addFuel(int ticks) {
        this.stateArray[STATE_FUEL] = Math.max(0.0, this.stateArray[STATE_FUEL] + ticks);
        markDirty();
    }

    public double getRemainingFuelTicks() {
        return stateArray[STATE_FUEL];
    }

    public boolean isRunning() {
        return stateArray[STATE_FUEL] > 0.0;
    }

    /**
     * Telemetry only: delivered current cached by the last {@code derivatives} call.
     * Valid only after {@code kernel.tick()} (item 12).
     */
    public double getLastDeliveredCurrentAmps() {
        return telemetryCell[GeneratorElement.TELE_I];
    }

    /**
     * Telemetry only: delivered power cached by the last {@code derivatives} call.
     * Valid only after {@code kernel.tick()} (item 12).
     */
    public double getLastDeliveredPowerWatts() {
        return telemetryCell[GeneratorElement.TELE_P];
    }

    public double getTotalEnergyJoules() {
        return totalEnergyJoules;
    }

    public double getTemperatureCelsius() {
        return stateArray[STATE_TEMP];
    }

    public Direction getOutputFacing() {
        BlockState state = getCachedState();
        if (state.contains(PortableGeneratorBlock.FACING)) {
            return state.get(PortableGeneratorBlock.FACING);
        }
        return Direction.NORTH;
    }

    public BlockPos getOutputPos() {
        return pos.offset(getOutputFacing());
    }

    /**
     * Legacy block-ticker entry point (kept for Block association); delegates to the
     * discrete phase. Fuel burn moved to kernel derivatives.
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
        int[][] o = GeneratorElement.TERMINAL_OFFSETS;
        BlockPos[] out = new BlockPos[o.length];
        for (int k = 0; k < o.length; k++) {
            out[k] = pos.add(o[k][0], o[k][1], o[k][2]);
        }
        return out;
    }

    @Override
    public double[] getStateArray() {
        return GeneratorElement.snapshotState(stateArray);
    }

    @Override
    public void setStateArray(double[] state) {
        GeneratorElement.assignState(stateArray, state);
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public boolean isActiveSource() {
        return GeneratorElement.isActiveSource(isRunning());
    }

    @Override
    public boolean isACSource() {
        return true;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        // Discrete phase only: fuel staging, blockstate mirror, energy bookkeeping.
        // Never mutates the state array, never touches the kernel. Null world safe.
        boolean running = isRunning();
        if (!running) {
            telemetryCell[GeneratorElement.TELE_I] = 0.0;
            telemetryCell[GeneratorElement.TELE_P] = 0.0;
        } else {
            double p = telemetryCell[GeneratorElement.TELE_P];
            if (p > 0.0) {
                this.totalEnergyJoules += p * GridConstants.DT;
            }
        }
        if (world != null) {
            BlockState cached = getCachedState();
            if (cached.contains(PortableGeneratorBlock.RUNNING)
                    && cached.get(PortableGeneratorBlock.RUNNING) != running) {
                world.setBlockState(pos, cached.with(PortableGeneratorBlock.RUNNING, running),
                    BLOCKSTATE_UPDATE_FLAGS);
            }
        }
    }

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     */
    public void writeStateData(WriteView view) {
        Objects.requireNonNull(view, "view");
        GeneratorElement.writeNbt(view, stateArray[STATE_TEMP], stateArray[STATE_FUEL],
            totalEnergyJoules);
    }

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     */
    public void readStateData(ReadView view) {
        Objects.requireNonNull(view, "view");
        GeneratorElement.assignState(stateArray, GeneratorElement.readNbtState(view));
        this.totalEnergyJoules = GeneratorElement.readNbtTotalEnergy(view);
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
