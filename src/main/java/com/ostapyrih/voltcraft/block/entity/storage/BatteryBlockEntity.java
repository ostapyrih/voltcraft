package com.ostapyrih.voltcraft.block.entity.storage;

import com.mojang.serialization.Codec;
import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.block.storage.BatteryBlock;
import com.ostapyrih.voltcraft.simulation.chemistry.BatteryChemistry;
import com.ostapyrih.voltcraft.simulation.chemistry.BatterySimulation;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Stationary electrochemical Battery Energy Storage System (BESS) kernel adapter.
 *
 * <p>Structural note: all decision logic lives in the static nested
 * {@link BatteryElement} with the BMS flag supplier-injected and telemetry written
 * into an injected cell, because unit-test runtimes cannot initialize
 * {@code BlockEntity} subclasses at all ({@code BlockEntity.&lt;clinit&gt;} touches
 * {@code Registries}). Production wires {@code this::isBmsOpen} plus the BE-owned
 * telemetry cell; tests inject their own cells. The outer BE still owns the
 * {@code double[3]} state array, the {@code bmsOpen} boolean field (item 9/13), and
 * the telemetry cell — the nested class is a pure function of its inputs.</p>
 *
 * <ul>
 *   <li>Terminals (item 4): two adjacent positions, east/west
 *       ({@code TERMINAL_OFFSETS = {{1,0,0},{-1,0,0}}}). West-positive source polarity
 *       convention: {@code terminals[1]} (west) is the positive terminal, so the
 *       current entering {@code terminals[0]} ({@code It[0]}) is positive while the
 *       pack discharges and {@code dSoc/dt = -It[0]/Q}.</li>
 *   <li>States (item 7): exactly 3 kernel-owned reals, {@code [soc, temperatureC,
 *       health]}. Defensive copies on {@link #getStateArray()} (clone) /
 *       {@link #setStateArray(double[])} (copy into BE-owned storage with
 *       {@code [0,1]} clamping on SoC/health) via {@link BatteryElement#snapshotState} /
 *       {@link BatteryElement#assignState}. Fresh packs start at
 *       {@code [1.0, AMBIENT_C, 1.0]}. The kernel zero-initializes element state on
 *       topology rebuild, so the topology owner ({@code GridManager}) must seed kernel
 *       state from the BE after every {@code setElements}, and copy kernel state back
 *       into the BE after every tick before the discrete phase.</li>
 *   <li>Discrete flag (item 9): {@code bmsOpen} is a BE boolean field persisted in NBT,
 *       never inside the state array. The element reads it at stamp time: closed stamps
 *       a Thevenin source, open stamps nothing (open circuit).</li>
 *   <li>Element stamp: Thevenin with {@code EMF = series * OCV(chemistry, soc)} via
 *       {@link BatterySimulation#getOpenCircuitVoltage} (pure electrochemistry, no grid
 *       contact) and series {@code R = cellR * series / parallel} via
 *       {@link BatterySimulation#getInternalResistance}, floored at
 *       {@code MIN_PACK_RESISTANCE_OHM}. When {@code bmsOpen}, open circuit.</li>
 *   <li>Derivatives: {@code dSoc/dt = -It[0]/Q} with pack charge
 *       {@code Q = chemistryAh * parallel * 3600 C};
 *       {@code dT/dt = (I^2*R - hPack*(T - AMBIENT)) / Cth} with per-cell cooling
 *       {@code hCell = max(0.05, capAh * 0.02) W/K} summed over the pack and thermal
 *       mass mirroring {@link BatterySimulation#step} ({@code 40 J/K} for small cells,
 *       else {@code capAh * 20 J/K} per cell); {@code dHealth/dt = -HEALTH_DECAY_K*|I|}
 *       only under overcurrent ({@code |I| > parallel * chemistry max A}) or overtemp
 *       ({@code T > 60 C}), else {@code 0}.</li>
 *   <li>Telemetry cache (item 12): {@code derivatives} stores terminal voltage
 *       ({@code Vt[1]-Vt[0]}) and delivered current ({@code It[0]}, positive on
 *       discharge) into the injected cell. Write-only, never read by stamp/derivative
 *       control flow; meaningful only after {@code kernel.tick()}.</li>
 *   <li>{@link #tickElectrical(ServerWorld)} performs the discrete BMS check only
 *       (item 10) via {@link BatteryElement#bmsNext}: it never mutates the state array
 *       and never touches the kernel. Null world safe (the world argument is reserved
 *       for future destruction effects; the BMS decision needs no world). Opens below
 *       pack cutoff voltage or above {@code 60 C}; closes on recovery with hysteresis
 *       ({@code +series*0.05 V}, reclose below {@code 55 C}). Temperature is read from
 *       the state array read-only; voltage comes from previous-tick telemetry.</li>
 * </ul>
 *
 * <p>Fallback rollback contract (item 11): on {@code fallbackActive} the BE state array
 * is authoritative — the topology owner must re-sync the kernel from the BE via
 * {@code kernel.setElementState(idx, be.getStateArray())} before the next solve and
 * must NOT commit kernel state into the BE. Otherwise the owner commits
 * ({@code be.setStateArray(kernel.getElementState(idx))}) before the discrete phase.
 * Rollback is per-island: other islands commit normally. The kernel integrates element
 * state even on fallback operating points, which is exactly the state being discarded.</p>
 *
 * <p>Documented simplifications: thermal-runaway explosion/destruction events
 * from the legacy implementation are removed (destruction wiring is deferred to a later change);
 * the BMS open-circuit replaces the legacy {@code DESTROYED} electrical state.</p>
 *
 * <p>NBT keys: {@code "stateArray"} ({@code Codec.DOUBLE.listOf()},
 * {@code [soc, temperatureC, health]}), {@code "bmsOpen"} (boolean), plus preserved
 * legacy keys {@code "state_of_charge"}, {@code "state_of_health"},
 * {@code "temperature"}. Reads prefer {@code stateArray} when it holds exactly 3 finite
 * entries, else fall back to the legacy triple, else fresh-pack defaults. Telemetry and
 * staged chemistry geometry (final constructor fields) are not persisted.</p>
 */
public class BatteryBlockEntity extends BlockEntity implements KernelAttachedBlock {

    /** State index of the pack state of charge in {@code [0,1]}. */
    public static final int STATE_SOC = 0;
    /** State index of the pack core temperature in Celsius. */
    public static final int STATE_TEMP = 1;
    /** State index of the pack state of health in {@code [0,1]}. */
    public static final int STATE_HEALTH = 2;

    /** NBT key for the kernel state slice {@code [soc, temperatureC, health]}. */
    public static final String KEY_STATE_ARRAY = "stateArray";
    /** NBT key for the BMS open flag. */
    public static final String KEY_BMS_OPEN = "bmsOpen";
    /** Preserved legacy NBT key for state of charge. */
    public static final String KEY_SOC_LEGACY = "state_of_charge";
    /** Preserved legacy NBT key for state of health. */
    public static final String KEY_SOH_LEGACY = "state_of_health";
    /** Preserved legacy NBT key for temperature. */
    public static final String KEY_TEMP_LEGACY = "temperature";

    /**
     * Static kernel element + state/NBT/BMS helpers. Fully unit-testable without any
     * registry, world, or block-entity instance.
     */
    public static final class BatteryElement implements ElectricalElement {
        /** Terminal offsets: east / west of the BE position. */
        public static final int[][] TERMINAL_OFFSETS = {{1, 0, 0}, {-1, 0, 0}};
        /** Floor for the stamped pack series resistance in ohms. */
        public static final double MIN_PACK_RESISTANCE_OHM = 1e-4;
        /** BMS over-temperature trip threshold in Celsius. */
        public static final double BMS_OVERTEMP_OPEN_C = 60.0;
        /** BMS over-temperature reclose threshold in Celsius (hysteresis). */
        public static final double BMS_OVERTEMP_CLOSE_C = 55.0;
        /** BMS undervoltage recovery hysteresis per series cell in volts. */
        public static final double BMS_RECOVERY_HYST_V_PER_CELL = 0.05;
        /** Health degradation rate in 1/(A s), applied only outside the safe window. */
        public static final double HEALTH_DECAY_K = 1e-9;
        /** Telemetry cell index of the terminal voltage in volts. */
        public static final int TELE_V = 0;
        /** Telemetry cell index of the delivered current in amps (positive on discharge). */
        public static final int TELE_I = 1;

        private final BatteryChemistry chemistry;
        private final int seriesCount;
        private final int parallelCount;
        private final BooleanSupplier bmsOpen;
        private final double[] telemetryCell;

        /**
         * @param chemistry pack cell chemistry (pure enum, registry-free)
         * @param seriesCount cells in series ({@code >= 1} after flooring)
         * @param parallelCount strings in parallel ({@code >= 1} after flooring)
         * @param bmsOpen supplier for the BE-owned BMS flag (read at stamp time only)
         * @param telemetryCell BE/test-owned write-only cache {@code [terminalV, deliveredI]},
         *        length {@code >= 2}
         */
        public BatteryElement(BatteryChemistry chemistry, int seriesCount, int parallelCount,
                              BooleanSupplier bmsOpen, double[] telemetryCell) {
            this.chemistry = Objects.requireNonNull(chemistry, "chemistry");
            this.seriesCount = Math.max(1, seriesCount);
            this.parallelCount = Math.max(1, parallelCount);
            this.bmsOpen = Objects.requireNonNull(bmsOpen, "bmsOpen");
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
            // West-positive source polarity: terminals[1] (west) is positive, so It[0] > 0 on discharge.
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
            // Telemetry-only cache (item 28): write-only, never read by control flow.
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

        /** Pack open-circuit EMF in volts: {@code series * OCV(chemistry, soc)}. */
        public static double packEmf(BatteryChemistry chemistry, int series, double soc) {
            return Math.max(1, series) * BatterySimulation.getOpenCircuitVoltage(chemistry, clamp01(soc));
        }

        /** Pack series resistance in ohms: {@code cellR * series / parallel}. */
        public static double packResistance(BatteryChemistry chemistry, int series, int parallel,
                                            double soc, double tempC, double health) {
            double cellR = BatterySimulation.getInternalResistance(
                chemistry, clamp01(soc), tempC, Math.max(0.1, Math.min(1.0, health)));
            return (cellR * Math.max(1, series)) / (double) Math.max(1, parallel);
        }

        /** Pack charge capacity in coulombs: {@code chemistryAh * parallel * 3600}. */
        public static double packCapacityCoulombs(BatteryChemistry chemistry, int parallel) {
            return chemistry.getCapacityAmpHours() * Math.max(1, parallel) * 3600.0;
        }

        /** Pack undervoltage trip threshold in volts: {@code series * chemistry cutoff}. */
        public static double packMinVoltage(BatteryChemistry chemistry, int series) {
            return Math.max(1, series) * chemistry.getCutoffVoltage();
        }

        /** Pack surge ceiling in volts: {@code series * fullCharge * 1.05}. */
        public static double packMaxVoltage(BatteryChemistry chemistry, int series) {
            return Math.max(1, series) * chemistry.getFullChargeVoltage() * 1.05;
        }

        /** Pack overcurrent threshold in amps: {@code parallel * chemistry max A}. */
        public static double maxDischargeAmps(BatteryChemistry chemistry, int parallel) {
            return Math.max(1, parallel) * chemistry.getMaxDischargeCurrentAmps();
        }

        /**
         * Discrete BMS transition (pure): opens below pack cutoff or above
         * {@code 60 C}; an open BMS recloses only above
         * {@code minPackV + series * 0.05 V} and below {@code 55 C} (hysteresis).
         */
        public static boolean bmsNext(boolean open, double terminalV, double tempC,
                                      double minPackV, int series) {
            if (open) {
                double recoverV = minPackV + Math.max(1, series) * BMS_RECOVERY_HYST_V_PER_CELL;
                return !(terminalV > recoverV && tempC < BMS_OVERTEMP_CLOSE_C);
            }
            return terminalV < minPackV || tempC > BMS_OVERTEMP_OPEN_C;
        }

        /** Source classification: active whenever the BMS is closed. */
        public static boolean isActiveSource(boolean bmsOpen) {
            return !bmsOpen;
        }

        /** Fresh-pack kernel state {@code [1.0, AMBIENT_C, 1.0]} (defensive: new array per call). */
        public static double[] newStateArray() {
            return new double[]{1.0, GridConstants.AMBIENT_C, 1.0};
        }

        /** Defensive snapshot: returns a clone, never the live array. */
        public static double[] snapshotState(double[] live) {
            Objects.requireNonNull(live, "live");
            return live.clone();
        }

        /**
         * Copies {@code src} into BE-owned {@code dst} (defensive: never retains the
         * kernel array by reference). Both must be non-null, length 3. SoC/health are
         * clamped to {@code [0,1]}; temperature passes through.
         */
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

        /** Writes the state slice, the BMS flag, and the preserved legacy keys. */
        public static void writeNbt(WriteView view, double soc, double tempC, double health,
                                    boolean bmsOpen) {
            List<Double> boxed = new ArrayList<>(3);
            boxed.add(soc);
            boxed.add(tempC);
            boxed.add(health);
            view.put(KEY_STATE_ARRAY, Codec.DOUBLE.listOf(), boxed);
            view.putBoolean(KEY_BMS_OPEN, bmsOpen);
            view.putDouble(KEY_SOC_LEGACY, soc);
            view.putDouble(KEY_SOH_LEGACY, health);
            view.putDouble(KEY_TEMP_LEGACY, tempC);
        }

        /**
         * Reads the state slice; prefers {@code stateArray} with exactly 3 finite entries,
         * else the legacy triple, else fresh-pack defaults (forward-tolerant).
         */
        public static double[] readNbtState(ReadView view) {
            List<Double> list = view.read(KEY_STATE_ARRAY, Codec.DOUBLE.listOf()).orElse(List.of());
            if (list.size() == 3 && list.get(0) != null && list.get(1) != null && list.get(2) != null
                    && Double.isFinite(list.get(0)) && Double.isFinite(list.get(1))
                    && Double.isFinite(list.get(2))) {
                return new double[]{list.get(0), list.get(1), list.get(2)};
            }
            double soc = view.getDouble(KEY_SOC_LEGACY, Double.NaN);
            double soh = view.getDouble(KEY_SOH_LEGACY, Double.NaN);
            double temp = view.getDouble(KEY_TEMP_LEGACY, Double.NaN);
            if (Double.isFinite(soc) && Double.isFinite(soh) && Double.isFinite(temp)) {
                return new double[]{soc, temp, soh};
            }
            return newStateArray();
        }

        /** Reads the BMS flag (defaults to closed when absent). */
        public static boolean readNbtBmsOpen(ReadView view) {
            return view.getBoolean(KEY_BMS_OPEN, false);
        }

        private static double clamp01(double v) {
            return Math.max(0.0, Math.min(1.0, v));
        }
    }

    private final BatteryChemistry chemistry;
    private final int seriesCount;
    private final int parallelCount;

    private final double[] stateArray = BatteryElement.newStateArray();
    private final double[] telemetryCell = new double[2];
    private boolean bmsOpen;

    private final ElectricalElement element;

    public BatteryBlockEntity(
        BlockEntityType<?> type,
        BlockPos pos,
        BlockState state,
        BatteryChemistry chemistry,
        int seriesCount,
        int parallelCount
    ) {
        super(type, pos, state);
        this.chemistry = chemistry;
        this.seriesCount = Math.max(1, seriesCount);
        this.parallelCount = Math.max(1, parallelCount);
        this.element = new BatteryElement(chemistry, this.seriesCount, this.parallelCount,
            this::isBmsOpen, telemetryCell);
    }

    public BatteryBlockEntity(BlockPos pos, BlockState state, BatteryChemistry chemistry, int seriesCount, int parallelCount) {
        this(VoltcraftBlockEntityTypes.BATTERY_BLOCK_ENTITY, pos, state, chemistry, seriesCount, parallelCount);
    }

    public BatteryBlockEntity(BlockPos pos, BlockState state) {
        this(
            VoltcraftBlockEntityTypes.BATTERY_BLOCK_ENTITY,
            pos,
            state,
            state.getBlock() instanceof BatteryBlock bb ? bb.getChemistry() : BatteryChemistry.LIFEPO4,
            state.getBlock() instanceof BatteryBlock bb ? bb.getSeriesCount() : 15,
            state.getBlock() instanceof BatteryBlock bb ? bb.getParallelCount() : 1
        );
    }

    public BatteryChemistry getChemistry() {
        return chemistry;
    }

    public int getSeriesCount() {
        return seriesCount;
    }

    public int getParallelCount() {
        return parallelCount;
    }

    public double getTemperatureCelsius() {
        return stateArray[STATE_TEMP];
    }

    /** Discrete BMS flag: {@code true} isolates the pack (open circuit). */
    public boolean isBmsOpen() {
        return bmsOpen;
    }

    /**
     * Telemetry only: terminal voltage cached by the last {@code derivatives} call.
     * Valid only after {@code kernel.tick()} (item 12).
     */
    public double getLastTerminalVoltage() {
        return telemetryCell[BatteryElement.TELE_V];
    }

    /**
     * Telemetry only: delivered current in amps (positive on discharge) cached by the
     * last {@code derivatives} call. Valid only after {@code kernel.tick()} (item 12).
     */
    public double getLastCurrentAmps() {
        return telemetryCell[BatteryElement.TELE_I];
    }

    // Legacy display accessors kept for Block use (plain methods, no grid role).
    // Grid participation runs centrally in the kernel; these read BE-owned state only.

    /** Pack state of charge in {@code [0,1]}. */
    public double getStateOfCharge() {
        return stateArray[STATE_SOC];
    }

    /** Pack state of health in percent. */
    public double getStateOfHealth() {
        return stateArray[STATE_HEALTH] * 100.0;
    }

    /** Pack open-circuit EMF in volts (0 when the BMS is open). */
    public double getElectromotiveForce() {
        if (bmsOpen) {
            return 0.0;
        }
        return BatteryElement.packEmf(chemistry, seriesCount, stateArray[STATE_SOC]);
    }

    @Override
    public ElectricalElement getElement() {
        return element;
    }

    @Override
    public BlockPos[] getTerminalPositions() {
        int[][] o = BatteryElement.TERMINAL_OFFSETS;
        BlockPos[] out = new BlockPos[o.length];
        for (int k = 0; k < o.length; k++) {
            out[k] = pos.add(o[k][0], o[k][1], o[k][2]);
        }
        return out;
    }

    @Override
    public double[] getStateArray() {
        return BatteryElement.snapshotState(stateArray);
    }

    @Override
    public void setStateArray(double[] state) {
        BatteryElement.assignState(stateArray, state);
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public boolean isActiveSource() {
        return BatteryElement.isActiveSource(bmsOpen);
    }

    @Override
    public boolean isACSource() {
        return false;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        // Discrete BMS check only: never mutates the state array, never touches the kernel.
        // Voltage comes from previous-tick telemetry; temperature is read read-only.
        boolean next = BatteryElement.bmsNext(bmsOpen, telemetryCell[BatteryElement.TELE_V],
            stateArray[STATE_TEMP], BatteryElement.packMinVoltage(chemistry, seriesCount), seriesCount);
        if (next != bmsOpen) {
            bmsOpen = next;
            markDirty();
        }
    }

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     */
    public void writeStateData(WriteView view) {
        Objects.requireNonNull(view, "view");
        BatteryElement.writeNbt(view, stateArray[STATE_SOC], stateArray[STATE_TEMP],
            stateArray[STATE_HEALTH], bmsOpen);
    }

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     */
    public void readStateData(ReadView view) {
        Objects.requireNonNull(view, "view");
        BatteryElement.assignState(stateArray, BatteryElement.readNbtState(view));
        this.bmsOpen = BatteryElement.readNbtBmsOpen(view);
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
