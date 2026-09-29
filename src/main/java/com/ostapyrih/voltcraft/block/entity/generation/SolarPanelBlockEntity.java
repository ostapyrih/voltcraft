package com.ostapyrih.voltcraft.block.entity.generation;

import com.mojang.serialization.Codec;
import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.block.generation.SolarPanelBlock;
import com.ostapyrih.voltcraft.simulation.generation.SolarIrradianceSimulation;
import com.ostapyrih.voltcraft.simulation.generation.SolarPanelType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * Photovoltaic solar panel kernel adapter. Exposes solar-generated DC EMF into the
 * kernel-owned island topology.
 *
 * <p>Structural note: all kernel decision logic lives in the static nested
 * {@link SolarElement} with EMF / resistance / irradiance staging suppliers injected
 * and telemetry written into an injected cell, because unit-test runtimes cannot
 * initialize {@code BlockEntity} subclasses at all ({@code BlockEntity.&lt;clinit&gt;}
 * touches {@code Registries}). Production wires the BE-owned staged discrete fields;
 * tests inject their own cells. The outer BE still owns the {@code double[1]} state
 * array, the staged discrete fields, and the telemetry cell — the nested class is a
 * pure function of its inputs.</p>
 *
 * <ul>
 *   <li>Terminals (item 4): two adjacent positions, east/west. West-positive source polarity
 *       convention: {@code terminals[1]} (west) is positive. (Panels expose DC terminals
 *       on the underside/edges — {@code SolarPanelBlock.canConnect} excludes only the
 *       sky face; terminal positions stay east/west like the switchgear adapters.)</li>
 *   <li>States (item 7): exactly 1 kernel-owned real, {@code [temperatureC]}.
 *       Irradiance, EMF, resistance, and power ratings are discrete <b>staging</b>
 *       inputs, never in the state array (item 9). Defensive copies on
 *       {@link #getStateArray()} (clone) / {@link #setStateArray(double[])} (copy into
 *       BE-owned storage, validated). Fresh panels start at {@code [AMBIENT_C]}. The
 *       topology owner ({@code GridManager}) must seed kernel state from the BE after
 *       every {@code setElements} and copy kernel state back into the BE after every
 *       tick before the discrete phase.</li>
 *   <li>Element stamp: Thevenin with staged {@code EMF = electromotiveForce},
 *       {@code R = max(0.05, internalResistance)}; when {@code EMF <= 0} (night) stamps
 *       nothing (open circuit).</li>
 *   <li>Derivatives: {@code dT/dt = (K_IRRAD * irradiance + I^2*R - COOLING*(T-AMB)) / C}
 *       with {@code K_IRRAD = 0.02 W per W/m^2} (about 20 W cell heating at STC),
 *       {@code C = 500 J/K} panel thermal mass, {@code COOLING = 2.0 W/K}.</li>
 *   <li>Telemetry cache (item 12): {@code derivatives} stores terminal voltage
 *       ({@code Vt[1]-Vt[0]}) and delivered current ({@code It[0]}, positive while
 *       generating) into the injected cell. Write-only, never read by stamp/derivative
 *       control flow; meaningful only after {@code kernel.tick()}.</li>
 *   <li>{@link #tickElectrical(ServerWorld)} is discrete-only (item 10): with a
 *       non-null world it recomputes irradiance and stages EMF/resistance/ratings via
 *       {@link SolarIrradianceSimulation}; with a null world it keeps the current
 *       staging (null-safe). It also folds previous-tick telemetry power into the
 *       discrete energy counter. It never mutates the state array and never touches the
 *       kernel.</li>
 * </ul>
 *
 * <p>Fallback rollback contract (item 11): on {@code fallbackActive} the BE state array
 * is authoritative — re-sync the kernel from the BE before the next solve and skip the
 * commit; otherwise commit kernel state into the BE before the discrete phase.
 * Per-island semantics. The kernel integrates even on fallback; that state is discarded.</p>
 *
 * <p>NBT keys: {@code "stateArray"} ({@code Codec.DOUBLE.listOf()}, {@code [temperatureC]}),
 * {@code "total_energy_generated"} (preserved legacy key for the discrete energy
 * counter). Reads restore state only when the stored list has exactly 1 finite entry,
 * else ambient default (forward-tolerant). Staged irradiance/EMF/resistance are
 * transient and not persisted. Telemetry is transient and not persisted.</p>
 */
public class SolarPanelBlockEntity extends BlockEntity implements KernelAttachedBlock {

    /** State index of the cell temperature in Celsius. */
    public static final int STATE_TEMP = 0;

    /** NBT key for the kernel state slice {@code [temperatureC]}. */
    public static final String KEY_STATE_ARRAY = "stateArray";
    /** Preserved legacy NBT key for the discrete lifetime energy counter. */
    public static final String KEY_TOTAL_ENERGY = "total_energy_generated";

    /**
     * Static kernel element + state/NBT helpers. Fully unit-testable without any
     * registry, world, or block-entity instance.
     */
    public static final class SolarElement implements ElectricalElement {
        /** Terminal offsets: east / west of the BE position. */
        public static final int[][] TERMINAL_OFFSETS = {{1, 0, 0}, {-1, 0, 0}};
        /** Floor for the staged Thevenin resistance in ohms. */
        public static final double MIN_RESISTANCE_OHM = 0.05;
        /** Irradiance heating gain in W per W/m^2. */
        public static final double IRRAD_HEAT_W_PER_W_M2 = 0.02;
        /** Panel thermal mass in J/K. */
        public static final double THERMAL_MASS_J_PER_K = 500.0;
        /** Linear cooling coefficient in W/K toward ambient. */
        public static final double COOLING_COEFF_W_PER_K = 2.0;
        /** Telemetry cell index of the terminal voltage in volts. */
        public static final int TELE_V = 0;
        /** Telemetry cell index of the delivered current in amps (positive while generating). */
        public static final int TELE_I = 1;

        private final DoubleSupplier electromotiveForce;
        private final DoubleSupplier internalResistance;
        private final DoubleSupplier irradiance;
        private final double[] telemetryCell;

        /**
         * @param electromotiveForce supplier for the staged EMF in volts (read at stamp time)
         * @param internalResistance supplier for the staged series resistance in ohms
         * @param irradiance supplier for the staged plane irradiance in W/m^2
         * @param telemetryCell BE/test-owned write-only cache {@code [terminalV, deliveredI]},
         *        length {@code >= 2}
         */
        public SolarElement(DoubleSupplier electromotiveForce, DoubleSupplier internalResistance,
                            DoubleSupplier irradiance, double[] telemetryCell) {
            this.electromotiveForce = Objects.requireNonNull(electromotiveForce, "electromotiveForce");
            this.internalResistance = Objects.requireNonNull(internalResistance, "internalResistance");
            this.irradiance = Objects.requireNonNull(irradiance, "irradiance");
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
            // West-positive source polarity: terminals[1] (west) is positive.
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
            // Telemetry-only cache (item 28): write-only, never read by control flow.
            telemetryCell[TELE_V] = terminalV;
            telemetryCell[TELE_I] = intoPos;
            double r = Math.max(MIN_RESISTANCE_OHM, internalResistance.getAsDouble());
            double heatingW = IRRAD_HEAT_W_PER_W_M2 * Math.max(0.0, irradiance.getAsDouble())
                + imag * imag * r;
            double coolingW = COOLING_COEFF_W_PER_K * (state[STATE_TEMP] - GridConstants.AMBIENT_C);
            dxdt[STATE_TEMP] = (heatingW - coolingW) / THERMAL_MASS_J_PER_K;
        }

        /** Source classification: active whenever the staged EMF is positive (daylight). */
        public static boolean isActiveSource(double electromotiveForce) {
            return electromotiveForce > 0.0;
        }

        /** Fresh-panel kernel state {@code [AMBIENT_C]} (defensive: a new array per call). */
        public static double[] newStateArray() {
            return new double[]{GridConstants.AMBIENT_C};
        }

        /** Defensive snapshot: returns a clone, never the live array. */
        public static double[] snapshotState(double[] live) {
            Objects.requireNonNull(live, "live");
            return live.clone();
        }

        /**
         * Copies {@code src} into BE-owned {@code dst} (defensive: never retains the
         * kernel array by reference). Both must be non-null, length 1.
         */
        public static void assignState(double[] dst, double[] src) {
            if (dst == null || dst.length != 1 || src == null || src.length != 1) {
                throw new IllegalArgumentException(
                    "SolarPanelBlockEntity holds 1 state, got dst="
                        + (dst == null ? "null" : dst.length) + " src=" + (src == null ? "null" : src.length));
            }
            dst[STATE_TEMP] = src[STATE_TEMP];
        }

        /** Writes the state slice plus the discrete lifetime energy counter. */
        public static void writeNbt(WriteView view, double temperatureC, double totalEnergyJoules) {
            List<Double> boxed = new ArrayList<>(1);
            boxed.add(temperatureC);
            view.put(KEY_STATE_ARRAY, Codec.DOUBLE.listOf(), boxed);
            view.putDouble(KEY_TOTAL_ENERGY, totalEnergyJoules);
        }

        /**
         * Reads the state slice; returns ambient default unless the stored list has
         * exactly 1 finite entry (forward-tolerant).
         */
        public static double[] readNbtState(ReadView view) {
            List<Double> list = view.read(KEY_STATE_ARRAY, Codec.DOUBLE.listOf()).orElse(List.of());
            if (list.size() == 1 && list.get(0) != null && Double.isFinite(list.get(0))) {
                return new double[]{list.get(0)};
            }
            return newStateArray();
        }

        /** Reads the discrete lifetime energy counter (defaults to 0 when absent). */
        public static double readNbtTotalEnergy(ReadView view) {
            return view.getDouble(KEY_TOTAL_ENERGY, 0.0);
        }
    }

    private final SolarPanelType panelType;

    private final double[] stateArray = SolarElement.newStateArray();
    private final double[] telemetryCell = new double[2];

    // Discrete staging inputs (item 9): set by tickElectrical, read by the stamp.
    private double currentIrradiance = 0.0;
    private double electromotiveForce = 0.0;
    private double internalResistance = 1.0;
    private double maxOutputCurrent = 0.0;
    private double peakPowerAvailable = 0.0;
    private double totalEnergyGeneratedJoules = 0.0;

    private final ElectricalElement element;

    public SolarPanelBlockEntity(BlockPos pos, BlockState state, SolarPanelType panelType) {
        super(VoltcraftBlockEntityTypes.SOLAR_PANEL_BLOCK_ENTITY, pos, state);
        this.panelType = panelType;
        this.element = new SolarElement(() -> electromotiveForce, () -> internalResistance,
            () -> currentIrradiance, telemetryCell);
    }

    public SolarPanelBlockEntity(BlockPos pos, BlockState state) {
        this(
            pos,
            state,
            state.getBlock() instanceof SolarPanelBlock spb ? spb.getPanelType() : SolarPanelType.MONOCRYSTALLINE_PERC
        );
    }

    public SolarPanelType getPanelType() {
        return panelType;
    }

    public double getCurrentIrradiance() {
        return currentIrradiance;
    }

    public double getPeakPowerAvailable() {
        return peakPowerAvailable;
    }

    /**
     * Telemetry only: delivered current cached by the last {@code derivatives} call.
     * Valid only after {@code kernel.tick()} (item 12).
     */
    public double getLastDrawnCurrent() {
        return telemetryCell[SolarElement.TELE_I];
    }

    public double getTotalEnergyGeneratedJoules() {
        return totalEnergyGeneratedJoules;
    }

    /** Staged open-circuit EMF in volts (0 at night). */
    public double getElectromotiveForce() {
        return electromotiveForce;
    }

    /** Staged Thevenin series resistance in ohms. */
    public double getInternalResistance() {
        return internalResistance;
    }

    /** Staged short-circuit current rating in amps. */
    public double getMaxOutputCurrent() {
        return maxOutputCurrent;
    }

    /**
     * Legacy block-ticker entry point (kept for Block association); delegates to the
     * discrete staging phase.
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
        int[][] o = SolarElement.TERMINAL_OFFSETS;
        BlockPos[] out = new BlockPos[o.length];
        for (int k = 0; k < o.length; k++) {
            out[k] = pos.add(o[k][0], o[k][1], o[k][2]);
        }
        return out;
    }

    @Override
    public double[] getStateArray() {
        return SolarElement.snapshotState(stateArray);
    }

    @Override
    public void setStateArray(double[] state) {
        SolarElement.assignState(stateArray, state);
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public boolean isActiveSource() {
        return SolarElement.isActiveSource(electromotiveForce);
    }

    @Override
    public boolean isACSource() {
        return false;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        // Discrete staging only: never mutates the state array, never touches the kernel.
        if (world != null) {
            this.currentIrradiance = SolarIrradianceSimulation.calculateIrradiance(world, pos, panelType);
            SolarIrradianceSimulation.SolarOutput output = SolarIrradianceSimulation.computeSolarOutput(
                panelType, currentIrradiance, 20.0
            );
            this.electromotiveForce = output.electromotiveForce();
            this.internalResistance = output.internalResistanceOhms();
            this.maxOutputCurrent = output.maxCurrentAmps();
            this.peakPowerAvailable = output.peakPowerAvailableWatts();
        }
        // Discrete lifetime energy bookkeeping from previous-tick telemetry (item 12).
        double v = telemetryCell[SolarElement.TELE_V];
        double i = telemetryCell[SolarElement.TELE_I];
        if (v > 0.0 && i > 0.0) {
            this.totalEnergyGeneratedJoules += v * i * GridConstants.DT;
        }
    }

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     */
    public void writeStateData(WriteView view) {
        Objects.requireNonNull(view, "view");
        SolarElement.writeNbt(view, stateArray[STATE_TEMP], totalEnergyGeneratedJoules);
    }

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     */
    public void readStateData(ReadView view) {
        Objects.requireNonNull(view, "view");
        SolarElement.assignState(stateArray, SolarElement.readNbtState(view));
        this.totalEnergyGeneratedJoules = SolarElement.readNbtTotalEnergy(view);
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
