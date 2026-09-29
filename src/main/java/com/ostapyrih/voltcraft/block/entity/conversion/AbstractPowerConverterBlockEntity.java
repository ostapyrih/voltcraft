package com.ostapyrih.voltcraft.block.entity.conversion;

import com.mojang.serialization.Codec;
import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.conversion.AbstractPowerConverterBlock;
import com.ostapyrih.voltcraft.screen.handler.ConverterScreenHandler;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/**
 * Base block entity for all multi-port conversion hardware (DC-DC converters, AC transformers, rectifiers, inverters).
 * Bridges an upstream power network (as an input consumer) and downstream network (as an output source)
 * without shorting them into a single through-conductor.
 *
 * <p>Converter kernel adapter: this BE implements {@link KernelAttachedBlock} through the static
 * nested {@link ConverterElement}. All decision logic (stamps, derivatives telemetry, discrete
 * staging/trip/NBT/state helpers) lives in that nested class with supplier-injected discrete
 * cells, because unit-test runtimes cannot initialize {@code BlockEntity} subclasses at all
 * ({@code BlockEntity.&lt;clinit&gt;} touches {@code Registries}). Production wires
 * {@code this::isTripped} plus BE-owned staged fields and the telemetry cell; tests inject
 * their own cells. The outer BE still owns the (empty) state array, the {@code tripped}
 * boolean field, the staged demand/EMF fields, and the telemetry cell. The kernel path
 * ({@link #tickElectrical}) is authoritative for trip logic and staging; the legacy
 * dual-grid bridge ({@code tick}/{@code InputConsumer}/{@code OutputSource}) was removed
 * when the kernel islands became the single subsystem.</p>
 */
public abstract class AbstractPowerConverterBlockEntity extends BlockEntity implements KernelAttachedBlock, ExtendedScreenHandlerFactory<BlockPos> {

    // --- Simulation timestep / shared thresholds ---
    private static final double TICK_DELTA_SECONDS = 0.05;
    private static final double DEAD_RAIL_VOLTAGE = 1.0;
    private static final double MIN_EFFICIENCY_FLOOR = 0.1;
    private static final double IDLE_DRAW_WATTS = 2.0;
    private static final int DEFAULT_TRIP_GRACE_TICKS = 40; // 2-second grace so circuit solves/voltages establish before re-tripping

    // --- Output throttling / brownout classification ---
    private static final double THROTTLE_ONSET_MARGIN = 1.10; // throttle engages below 110% of minVin
    private static final double MIN_THROTTLE = 0.1;

    // --- Thermal model ---
    private static final double AMBIENT_TEMPERATURE_C = 20.0;
    private static final double MIN_RATED_POWER_W = 100.0;
    private static final double MIN_BASE_COOLING_COEFF = 1.5;
    private static final double COOLING_COEFF_DIVISOR = 40.0;
    private static final double FAN_ONSET_TEMPERATURE_C = 45.0;
    private static final double FAN_MAX_BONUS = 2.5;
    private static final double FAN_RAMP_RANGE_C = 20.0;
    private static final double MIN_HEAT_CAPACITY = 120.0;
    private static final double HEAT_CAPACITY_FACTOR = 0.25;

    // --- Misc defaults ---
    private static final double DEFAULT_TARGET_OUTPUT_VOLTAGE = 12.0;
    private static final double DEFAULT_TEMPERATURE_C = 20.0;
    private static final double UNINITIALIZED_DEMAND_WATTS = -1.0;
    private static final double NOMINAL_INPUT_VOLTAGE_DEFAULT = 48.0;
    private static final double SERVO_TARGET_MULTIPLIER = 1.15;
    private static final double SERVO_TARGET_OFFSET_V = 0.5;

    protected double inputVoltage = 0.0;
    protected double inputCurrentAmps = 0.0;
    protected double inputPowerWatts = 0.0;

    protected double outputVoltageEmf = 0.0;
    protected double actualOutputVoltage = 0.0;
    protected double outputCurrentAmps = 0.0;
    protected double outputPowerWatts = 0.0;

    protected double targetOutputVoltage = DEFAULT_TARGET_OUTPUT_VOLTAGE;
    protected double temperatureCelsius = DEFAULT_TEMPERATURE_C;
    protected boolean tripped = false;
    protected int antiIslandingTicks = 0;
    protected int underVoltageTicks = 0;
    protected int tripCooldownTicks = 0;
    protected int tripGraceTicks = 0;

    protected double inputCurrentCap = 0.0;
    protected boolean outputHungry = false;
    protected double lastDemandWatts = UNINITIALIZED_DEMAND_WATTS;

    protected ElectricalState reportedState = ElectricalState.OFF;

    // --- Kernel path: BE-owned adapter state ---
    private final double[] stateArray = ConverterElement.newStateArray();
    private final double[] telemetryCell = new double[ConverterElement.TELE_LEN];
    private final ElectricalElement element = new ConverterElement(
        this::isTripped, this::getStagedInputDemandWatts, this::getStagedOutputEmf,
        this::getStagingNominalInputVoltage, telemetryCell);
    /** Staged input demand in watts (Item-10 discrete field; consumed read-only by the stamp). */
    protected double stagedInputDemandWatts = 0.0;
    /** Staged output EMF in volts (Item-10 discrete field; consumed read-only by the stamp). */
    protected double stagedOutputEmf = 0.0;

    /**
     * Static kernel element + state/NBT/staging helpers. Fully unit-testable without any
     * registry, world, or block-entity instance.
     *
     * <ul>
     *   <li>Terminals (item 4): four adjacent positions, FACING-relative —
     *       {@code [BACK in+, LEFT in−, FRONT out+, RIGHT out−]} resolved by
     *       {@link ConverterElement#resolveConverterTerminals}. The input pair is
     *       {@code terminals[0..1]} ({@code Vin = V[0] − V[1]}), the
     *       output pair is {@code terminals[2..3]} (FRONT out+, RIGHT out−;
     *       {@code Vout = V[2] − V[3]}). The stamp never couples the pairs: the two sides
     *       stay galvanically isolated, like the legacy dual-grid bridge.</li>
     *   <li>States (item 7): exactly 0 kernel-owned reals. Converters carry no integrator
     *       state in the kernel; temperature is a BE-side discrete/staging field advanced
     *       by the outer {@code tickElectrical} with the legacy thermal constants, and
     *       efficiency lives only in demand staging (never in the stamp), so neither can
     *       leak across islands. {@link #snapshotState}/{@link #assignState} validate the
     *       empty length defensively.</li>
     *   <li>Discrete flag (item 9): {@code tripped} is a BE boolean field persisted in NBT,
     *       never inside the state array. The element reads it at stamp time: tripped opens
     *       both sides (zero stamp).</li>
     *   <li>Element stamp: the input side draws the <em>staged</em> demand (DC islands via
     *       {@link Stamps#constantPower}; AC islands via a resistive approximation
     *       {@code R = Vnom^2 / P} because constant-power has no AC linearization), the
     *       output side stamps a Thevenin source with the <em>staged</em> EMF and the
     *       existing 50 mΩ series resistance. The stamp never computes demand or EMF itself.</li>
     *   <li>Derivatives: stateless (no-op on the empty slice) except for the telemetry
     *       cache (item 12): {@code [delivered output power, input voltage, output voltage,
     *       output current magnitude]}, write-only, never read by stamp/derivative control
     *       flow; meaningful only after {@code kernel.tick()}.</li>
     *   <li>{@code tickElectrical} (outer, item 10) performs demand/EMF staging plus trip
     *       logic only, via the pure {@link #stageDemandWatts}/{@link #stageEmf}/
     *       {@link #tripNext}/{@link #advanceCounter} helpers: it consumes previous-tick
     *       telemetry, never mutates the state array, never touches the kernel, and never
     *       dereferences the world (null-safe by construction).</li>
     *   <li>Fallback rollback (item 11): kernel state is empty so the topology owner
     *       re-sync is a no-op; the owner discards the write-only telemetry via
     *       {@link #resetTelemetry} and keeps the BE copies authoritative. Per-island.</li>
     *   <li>Omega (item 6): island frequency selects the input linearization; the output
     *       waveform class comes from {@link #isACOutput} (inverter/transformer AC, else DC).</li>
     * </ul>
     *
     * <p>NBT keys: {@code "stateArray"} ({@code Codec.DOUBLE.listOf()}, always empty),
     * {@code "tripped"} (boolean). Staged demand/EMF and telemetry are transient and never
     * persisted; the outer BE additionally preserves its legacy {@code "target_voltage"} and
     * {@code "temperature"} keys.</p>
     */
    public static final class ConverterElement implements ElectricalElement {
        /**
         * Canonical NORTH-orientation terminal offsets: {@code [0]=south in+,
         * [1]=west in−, [2]=north out+, [3]=east out−}. Source of truth only for
         * the default facing; the runtime layout is FACING-relative via
         * {@link #resolveConverterTerminals}.
         */
        public static final int[][] TERMINAL_OFFSETS = {{0, 0, 1}, {-1, 0, 0}, {0, 0, -1}, {1, 0, 0}};

        /**
         * FACING-relative terminal resolution (pure, null-world-safe): input pair
         * {@code [BACK+, LEFT−]}, output pair {@code [FRONT+, RIGHT−]}, i.e.
         * {@code [0]=pos.offset(facing.getOpposite())},
         * {@code [1]=pos.offset(facing.rotateYCounterclockwise())},
         * {@code [2]=pos.offset(facing)}, {@code [3]=pos.offset(facing.rotateYClockwise())}.
         * A null facing degrades to {@link Direction#NORTH}.
         */
        public static BlockPos[] resolveConverterTerminals(BlockPos pos, Direction facing) {
            Direction f = facing != null ? facing : Direction.NORTH;
            return new BlockPos[]{
                pos.offset(f.getOpposite()),
                pos.offset(f.rotateYCounterclockwise()),
                pos.offset(f),
                pos.offset(f.rotateYClockwise())
            };
        }
        /** Output source series resistance in ohms (existing 50 mΩ constant). */
        public static final double SOURCE_R_OHM = 0.05;
        /** Idle draw in watts added to every staged demand (existing constant). */
        public static final double IDLE_DRAW_WATTS = 2.0;
        /** Constant-power knee voltage in volts (numerical-stability floor). */
        public static final double CP_VMIN_VOLTS = 1.0;
        /** Dead-rail threshold in volts: no demand or EMF is staged at or below this. */
        public static final double DEAD_RAIL_VOLTS = 1.0;
        /** Efficiency floor for demand staging (divide-by-zero guard). */
        public static final double MIN_EFFICIENCY = 0.1;
        /** Overvoltage shutdown margin: output forced open above {@code 1.3 * maxVin}. */
        public static final double OVERVOLTAGE_MARGIN = 1.3;
        /** UVLO trip margin: sustained operation below {@code 0.9 * minVin} latches a trip. */
        public static final double UVLO_TRIP_MARGIN = 0.9;
        /** UVLO sustained-tick threshold (existing constant: 6 ticks / 300 ms). */
        public static final int UVLO_TRIP_TICKS = 6;
        /** Anti-islanding grid-dead threshold in volts (existing constant: 20 V). */
        public static final double ANTI_ISLAND_MIN_VOLTS = 20.0;
        /** Anti-islanding sustained-tick threshold (existing constant: 4 ticks / 200 ms). */
        public static final int ANTI_ISLAND_TRIP_TICKS = 4;
        /** Thermal trip threshold in Celsius (existing constant: 125 C). */
        public static final double THERMAL_TRIP_C = 125.0;

        /** Type kind of DC-DC converters (mirrors {@code getTypeKind}). */
        public static final int TYPE_KIND_DC_DC = 0;
        /** Type kind of inverters (mirrors {@code getTypeKind}). */
        public static final int TYPE_KIND_INVERTER = 1;
        /** Type kind of transformers (mirrors {@code getTypeKind}). */
        public static final int TYPE_KIND_TRANSFORMER = 2;
        /** Type kind of rectifiers (mirrors {@code getTypeKind}). */
        public static final int TYPE_KIND_RECTIFIER = 3;
        /** Type kind of the EU bridge (mirrors {@code getTypeKind}). */
        public static final int TYPE_KIND_EU = 4;

        /** Telemetry cell index of the delivered output power in watts (positive when sourcing). */
        public static final int TELE_P_OUT = 0;
        /** Telemetry cell index of the input-pair voltage in volts. */
        public static final int TELE_V_IN = 1;
        /** Telemetry cell index of the output-pair voltage in volts. */
        public static final int TELE_V_OUT = 2;
        /** Telemetry cell index of the output-pair current magnitude in amps. */
        public static final int TELE_I_OUT = 3;
        /** Telemetry cell length. */
        public static final int TELE_LEN = 4;

        /** NBT key for the kernel state slice (always empty for converters). */
        public static final String KEY_STATE_ARRAY = "stateArray";
        /** NBT key for the tripped flag. */
        public static final String KEY_TRIPPED = "tripped";

        private final BooleanSupplier tripped;
        private final DoubleSupplier inputDemandWatts;
        private final DoubleSupplier outputEmf;
        private final DoubleSupplier nominalInputVoltage;
        private final double[] telemetryCell;

        /**
         * @param tripped supplier for the BE-owned trip flag (read at stamp time only)
         * @param inputDemandWatts supplier for the BE-staged input demand in watts (read-only;
         *        staged by {@code tickElectrical} from previous-tick telemetry, never computed here)
         * @param outputEmf supplier for the BE-staged output EMF in volts (read-only)
         * @param nominalInputVoltage supplier for the nominal input voltage in volts
         *        (AC resistive fallback only)
         * @param telemetryCell BE/test-owned write-only cache, length {@code >= TELE_LEN}
         */
        public ConverterElement(BooleanSupplier tripped, DoubleSupplier inputDemandWatts,
                                DoubleSupplier outputEmf, DoubleSupplier nominalInputVoltage,
                                double[] telemetryCell) {
            this.tripped = Objects.requireNonNull(tripped, "tripped");
            this.inputDemandWatts = Objects.requireNonNull(inputDemandWatts, "inputDemandWatts");
            this.outputEmf = Objects.requireNonNull(outputEmf, "outputEmf");
            this.nominalInputVoltage = Objects.requireNonNull(nominalInputVoltage, "nominalInputVoltage");
            Objects.requireNonNull(telemetryCell, "telemetryCell");
            if (telemetryCell.length < TELE_LEN) {
                throw new IllegalArgumentException("telemetryCell needs length >= " + TELE_LEN);
            }
            this.telemetryCell = telemetryCell;
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
                    new Complex(1.0 / SOURCE_R_OHM, 0.0), new Complex(emf, 0.0));
            }
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
            // Telemetry-only cache (item 28): write-only, never read by control flow.
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

        /**
         * Source classification: active whenever untripped with a positive staged EMF.
         */
        public static boolean isActiveSource(boolean tripped, double stagedEmf) {
            return !tripped && stagedEmf > 0.0;
        }

        /**
         * Output waveform table: inverters and transformers source AC; DC-DC converters,
         * rectifiers, and the EU bridge do not (the EU bridge has no grid output at all).
         */
        public static boolean isACOutput(int typeKind) {
            return typeKind == TYPE_KIND_INVERTER || typeKind == TYPE_KIND_TRANSFORMER;
        }

        /**
         * Stages the next input demand from previous-tick output telemetry (pure Item-10
         * helper): {@code delivered / efficiency + idle}, or zero on a dead/tripped rail.
         * Non-finite inputs degrade to the efficiency floor and zero power, never NaN.
         */
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

        /**
         * Stages the next output EMF from the subclass-computed raw target (pure Item-10
         * helper): passes through unless tripped, dead-railed, or above the
         * {@code 1.3 * maxVin} overvoltage shutdown (output held open, no latch).
         */
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

        /**
         * Latched trip OR over the immediate causes (pure Item-10 helper). Sustained
         * conditions (overload, anti-islanding, UVLO) enter through counter-derived flags
         * built with {@link #advanceCounter}; overtemperature trips immediately, matching
         * the legacy thermal model. Overvoltage never latches here: it holds the output
         * open via {@link #stageEmf} and surfaces as SURGE in the reported state.
         */
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

        /** Sustained-condition counter: increments while the condition holds, else resets. */
        public static int advanceCounter(int count, boolean condition) {
            return condition ? count + 1 : 0;
        }

        /**
         * Fallback rollback helper (Item 11): discards the write-only telemetry cache so a
         * fallback tick's partial values never feed the next discrete phase. The BE-owned
         * copies stay authoritative; kernel state is empty so re-sync is a no-op.
         */
        public static void resetTelemetry(double[] cell) {
            Objects.requireNonNull(cell, "cell");
            if (cell.length < TELE_LEN) {
                throw new IllegalArgumentException("telemetryCell needs length >= " + TELE_LEN);
            }
            for (int k = 0; k < TELE_LEN; k++) {
                cell[k] = 0.0;
            }
        }

        /** Converters hold no kernel state: always a fresh empty array. */
        public static double[] newStateArray() {
            return new double[0];
        }

        /** Defensive snapshot: validates the empty length, returns a clone. */
        public static double[] snapshotState(double[] live) {
            Objects.requireNonNull(live, "live");
            if (live.length != 0) {
                throw new IllegalArgumentException(
                    "Converter holds 0 states, got " + live.length);
            }
            return live.clone();
        }

        /**
         * Copies {@code src} into BE-owned {@code dst} (defensive: never retains the
         * kernel array by reference). Both must be non-null, length 0.
         */
        public static void assignState(double[] dst, double[] src) {
            if (dst == null || dst.length != 0 || src == null || src.length != 0) {
                throw new IllegalArgumentException(
                    "Converter holds 0 states, got dst="
                        + (dst == null ? "null" : dst.length) + " src=" + (src == null ? "null" : src.length));
            }
        }

        /** Writes the (empty) state slice plus the tripped flag. */
        public static void writeNbt(WriteView view, boolean tripped) {
            view.put(KEY_STATE_ARRAY, Codec.DOUBLE.listOf(), List.<Double>of());
            view.putBoolean(KEY_TRIPPED, tripped);
        }

        /**
         * Reads the state slice; converters hold no kernel state, so this always returns a
         * fresh empty array (forward-tolerant: any stored content is ignored).
         */
        public static double[] readNbtState(ReadView view) {
            view.read(KEY_STATE_ARRAY, Codec.DOUBLE.listOf());
            return newStateArray();
        }

        /** Reads the tripped flag (defaults to untripped when absent). */
        public static boolean readNbtBlown(ReadView view) {
            return view.getBoolean(KEY_TRIPPED, false);
        }
    }

    protected final PropertyDelegate propertyDelegate = new PropertyDelegate() {
        @Override
        public int get(int index) {
            int inCurr = (int) Math.round(inputCurrentAmps * 100.0);
            int inPow = (int) Math.round(inputPowerWatts * 10.0);
            int outCurr = (int) Math.round(outputCurrentAmps * 100.0);
            int outPow = (int) Math.round(outputPowerWatts * 10.0);
            double dispOutV = actualOutputVoltage > 0.1 ? actualOutputVoltage : outputVoltageEmf;

            return switch (index) {
                case ConverterScreenHandler.PROP_INPUT_VOLTAGE_X10 -> (int) Math.round(inputVoltage * 10.0);
                case ConverterScreenHandler.PROP_INPUT_CURRENT_LOW -> ConverterScreenHandler.packLow(inCurr);
                case ConverterScreenHandler.PROP_INPUT_CURRENT_HIGH -> ConverterScreenHandler.packHigh(inCurr);
                case ConverterScreenHandler.PROP_INPUT_POWER_LOW -> ConverterScreenHandler.packLow(inPow);
                case ConverterScreenHandler.PROP_INPUT_POWER_HIGH -> ConverterScreenHandler.packHigh(inPow);

                case ConverterScreenHandler.PROP_OUTPUT_VOLTAGE_X10 -> (int) Math.round(dispOutV * 10.0);
                case ConverterScreenHandler.PROP_OUTPUT_CURRENT_LOW -> ConverterScreenHandler.packLow(outCurr);
                case ConverterScreenHandler.PROP_OUTPUT_CURRENT_HIGH -> ConverterScreenHandler.packHigh(outCurr);
                case ConverterScreenHandler.PROP_OUTPUT_POWER_LOW -> ConverterScreenHandler.packLow(outPow);
                case ConverterScreenHandler.PROP_OUTPUT_POWER_HIGH -> ConverterScreenHandler.packHigh(outPow);

                case ConverterScreenHandler.PROP_TARGET_VOLTAGE_X10 -> (int) Math.round(targetOutputVoltage * 10.0);
                case ConverterScreenHandler.PROP_TEMPERATURE_X10 -> (int) Math.round(temperatureCelsius * 10.0);
                case ConverterScreenHandler.PROP_EFFICIENCY_X10 -> (int) Math.round(getEfficiency() * 1000.0);
                case ConverterScreenHandler.PROP_THD_X10 -> (int) Math.round(getTotalHarmonicDistortion() * 10.0);
                case ConverterScreenHandler.PROP_STATE_ORDINAL -> getElectricalState().ordinal();
                case ConverterScreenHandler.PROP_FLAGS -> {
                    int flags = 0;
                    if (tripped) flags |= 1;
                    if (isOutputConfigurable()) flags |= 2;
                    if (isGridTie()) flags |= 4;
                    yield flags;
                }
                case ConverterScreenHandler.PROP_INPUT_PORT_DIR -> getInputPortDirection().ordinal();
                case ConverterScreenHandler.PROP_OUTPUT_PORT_DIR -> getOutputPortDirection().ordinal();
                case ConverterScreenHandler.PROP_TYPE_KIND -> getTypeKind();
                case ConverterScreenHandler.PROP_NOMINAL_INPUT_VOLTAGE_X10 -> (int) Math.round(getNominalInputVoltage() * 10.0);
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            if (index == ConverterScreenHandler.PROP_TARGET_VOLTAGE_X10) {
                setTargetOutputVoltage(value / 10.0);
                markDirty();
            } else if (index == ConverterScreenHandler.PROP_NOMINAL_INPUT_VOLTAGE_X10) {
                setNominalInputVoltage(value / 10.0);
                markDirty();
            } else if (index == ConverterScreenHandler.PROP_FLAGS) {
                if ((value & 1) == 0 && tripped) {
                    resetTrip();
                }
            }
        }

        @Override
        public int size() {
            return ConverterScreenHandler.PROPERTY_COUNT;
        }
    };

    public AbstractPowerConverterBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public PropertyDelegate getPropertyDelegate() {
        return propertyDelegate;
    }

    public boolean isOutputConfigurable() {
        return false;
    }

    public int getTypeKind() {
        return 0;
    }

    public double getNominalInputVoltage() {
        return NOMINAL_INPUT_VOLTAGE_DEFAULT;
    }

    public double getServoTargetVoltage() {
        return getMinInputVoltage() * SERVO_TARGET_MULTIPLIER + SERVO_TARGET_OFFSET_V;
    }

    public void setNominalInputVoltage(double voltage) {
        this.tripGraceTicks = DEFAULT_TRIP_GRACE_TICKS;
        this.underVoltageTicks = 0;
    }

    public double getOutputFrequency() {
        return 0.0;
    }

    public boolean acceptsInputFrequency(double frequencyHz) {
        return true;
    }

    public boolean isOutputCurrentRegulated() {
        return false;
    }

    @Override
    public Text getDisplayName() {
        return Text.translatable(getCachedState().getBlock().getTranslationKey());
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
        return new ConverterScreenHandler(syncId, playerInventory, this.pos, this.propertyDelegate);
    }

    @Override
    public BlockPos getScreenOpeningData(ServerPlayerEntity player) {
        return this.pos;
    }

    public Direction getInputPortDirection() {
        if (getCachedState().contains(AbstractPowerConverterBlock.FACING)) {
            return getCachedState().get(AbstractPowerConverterBlock.FACING).getOpposite();
        }
        return Direction.NORTH;
    }

    public Direction getOutputPortDirection() {
        if (getCachedState().contains(AbstractPowerConverterBlock.FACING)) {
            return getCachedState().get(AbstractPowerConverterBlock.FACING);
        }
        return Direction.SOUTH;
    }

    public boolean isInputPort(Direction side) {
        return side == getInputPortDirection();
    }

    public boolean isOutputPort(Direction side) {
        return side == getOutputPortDirection();
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    /**
     * Staged input demand in watts from the last {@link #tickElectrical} pass (GUI cache).
     */
    protected double getStagedInputDemandWatts() {
        return stagedInputDemandWatts;
    }

    /**
     * Staged output EMF in volts from the last {@link #tickElectrical} pass (GUI cache).
     */
    protected double getStagedOutputEmf() {
        return stagedOutputEmf;
    }

    /**
     * Nominal input voltage used only for the AC resistive-fallback stamp.
     */
    protected double getStagingNominalInputVoltage() {
        return (getMinInputVoltage() + getMaxInputVoltage()) * 0.5;
    }

    @Override
    public ElectricalElement getElement() {
        return element;
    }

    @Override
    public BlockPos[] getTerminalPositions() {
        return ConverterElement.resolveConverterTerminals(pos, readFacing());
    }

    /**
     * Reads {@code FACING} from the cached state with a {@link Direction#NORTH}
     * fallback (null-world / test-double / wrong-block safe: only {@code pos} plus
     * the cached state are read, never the world).
     */
    private Direction readFacing() {
        try {
            BlockState cached = getCachedState();
            if (cached != null && cached.contains(AbstractPowerConverterBlock.FACING)) {
                Direction facing = cached.get(AbstractPowerConverterBlock.FACING);
                if (facing != null) {
                    return facing;
                }
            }
        } catch (Exception ignored) {
            // Fall through to NORTH.
        }
        return Direction.NORTH;
    }

    @Override
    public double[] getStateArray() {
        return ConverterElement.snapshotState(stateArray);
    }

    @Override
    public void setStateArray(double[] state) {
        ConverterElement.assignState(stateArray, state);
    }

    @Override
    public boolean isActiveSource() {
        return ConverterElement.isActiveSource(tripped, stagedOutputEmf);
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        // Discrete phase only (Item 10): consumes previous-tick telemetry, stages demand/EMF,
        // advances BE-side thermal staging, and latches trips. Never mutates the state array
        // (converters hold none), never touches the kernel. The world argument is reserved for
        // future destruction effects and is never dereferenced: null-safe by construction.
        double telePOut = telemetryCell[ConverterElement.TELE_P_OUT];
        double teleVIn = telemetryCell[ConverterElement.TELE_V_IN];
        double teleVOut = telemetryCell[ConverterElement.TELE_V_OUT];
        double teleIOut = telemetryCell[ConverterElement.TELE_I_OUT];

        // Snapshot the demand that fed the just-solved tick before re-staging.
        double prevDemandWatts = stagedInputDemandWatts;

        // GUI-visible caches follow previous-tick telemetry (Item 12 staging).
        this.inputVoltage = teleVIn;
        this.inputPowerWatts = Math.max(0.0, prevDemandWatts);
        this.inputCurrentAmps = teleVIn > ConverterElement.DEAD_RAIL_VOLTS
            ? Math.max(0.0, prevDemandWatts) / Math.max(1.0, teleVIn) : 0.0;
        this.outputPowerWatts = Math.max(0.0, Double.isFinite(telePOut) ? telePOut : 0.0);
        this.actualOutputVoltage = teleVOut;
        this.outputCurrentAmps = Math.max(0.0, Double.isFinite(teleIOut) ? teleIOut : 0.0);

        double minVin = getMinInputVoltage();
        double maxVin = getMaxInputVoltage();
        double rawTarget = computeOutputVoltage(teleVIn);
        this.stagedOutputEmf = ConverterElement.stageEmf(rawTarget, tripped, teleVIn, maxVin);
        this.outputVoltageEmf = stagedOutputEmf;
        double eff = getEfficiency();
        this.stagedInputDemandWatts = ConverterElement.stageDemandWatts(
            telePOut, eff, tripped, teleVIn);

        updateKernelThermal(prevDemandWatts, telePOut, eff);

        if (tripGraceTicks > 0) {
            tripGraceTicks--;
        }
        boolean grace = tripGraceTicks > 0;
        double maxOut = Math.max(0.0, getMaxOutputCurrent());
        boolean overloadNow = !grace && maxOut > 0.0 && teleIOut > maxOut;
        boolean islandCond = !grace && isGridTie() && teleVOut < ConverterElement.ANTI_ISLAND_MIN_VOLTS;
        antiIslandingTicks = ConverterElement.advanceCounter(antiIslandingTicks, islandCond);
        boolean islandNow = antiIslandingTicks >= ConverterElement.ANTI_ISLAND_TRIP_TICKS;
        boolean uvloCond = !grace && teleVIn > ConverterElement.DEAD_RAIL_VOLTS
            && teleVIn < minVin * ConverterElement.UVLO_TRIP_MARGIN;
        underVoltageTicks = ConverterElement.advanceCounter(underVoltageTicks, uvloCond);
        if (teleVIn >= minVin) {
            underVoltageTicks = 0;
        }
        boolean uvloNow = underVoltageTicks >= ConverterElement.UVLO_TRIP_TICKS;
        boolean next = ConverterElement.tripNext(tripped, temperatureCelsius,
            overloadNow, islandNow, uvloNow);
        if (next != tripped) {
            tripped = next;
            markDirty();
        }
        updateKernelReportedState(teleVIn, minVin, maxVin);
    }

    /**
     * BE-side thermal staging for the kernel path: loss is the previous staged demand minus
     * the delivered output telemetry; cooling/fan/heat-capacity terms reuse the legacy
     * constants. Tripping itself happens in {@link #tickElectrical} via
     * {@link ConverterElement#tripNext}.
     */
    private void updateKernelThermal(double prevDemandWatts, double telePOutWatts, double efficiency) {
        double delivered = Double.isFinite(telePOutWatts) ? Math.max(0.0, telePOutWatts) : 0.0;
        double lossWatts = Math.max(0.0, Math.max(0.0, prevDemandWatts) - delivered);
        double deltaAboveAmbient = Math.max(0.0, temperatureCelsius - AMBIENT_TEMPERATURE_C);

        double ratedPower = Math.max(MIN_RATED_POWER_W, getMaxOutputCurrent() * getNominalOutputVoltage());
        double eff = Double.isFinite(efficiency) ? efficiency : MIN_EFFICIENCY_FLOOR;
        eff = Math.max(MIN_EFFICIENCY_FLOOR, eff);
        double ratedFullLoss = (ratedPower * (1.0 - eff)) / eff;
        double baseCoolingCoeff = Math.max(MIN_BASE_COOLING_COEFF, ratedFullLoss / COOLING_COEFF_DIVISOR);

        double fanMultiplier = 1.0;
        if (temperatureCelsius > FAN_ONSET_TEMPERATURE_C) {
            fanMultiplier = 1.0 + Math.min(FAN_MAX_BONUS, (temperatureCelsius - FAN_ONSET_TEMPERATURE_C) / FAN_RAMP_RANGE_C);
        }

        double coolingWatts = baseCoolingCoeff * fanMultiplier * deltaAboveAmbient;
        double heatCapacity = Math.max(MIN_HEAT_CAPACITY, ratedPower * HEAT_CAPACITY_FACTOR);
        double deltaTemp = ((lossWatts - coolingWatts) / heatCapacity) * TICK_DELTA_SECONDS;
        this.temperatureCelsius = Math.max(AMBIENT_TEMPERATURE_C, this.temperatureCelsius + deltaTemp);
    }

    /**
     * Reported-state classification for the kernel path, mirroring the legacy mapping:
     * tripped/dead rail off, overvoltage surge, undervoltage brownout, else nominal.
     */
    private void updateKernelReportedState(double teleVIn, double minVin, double maxVin) {
        if (tripped || !(teleVIn > ConverterElement.DEAD_RAIL_VOLTS)) {
            reportedState = ElectricalState.OFF;
        } else if (Double.isFinite(maxVin) && teleVIn > maxVin * ConverterElement.OVERVOLTAGE_MARGIN) {
            reportedState = ElectricalState.SURGE;
        } else if (teleVIn < minVin) {
            reportedState = ElectricalState.BROWNOUT;
        } else {
            reportedState = ElectricalState.NOMINAL;
        }
    }

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     */
    public void writeStateData(WriteView view) {
        Objects.requireNonNull(view, "view");
        ConverterElement.writeNbt(view, tripped);
    }

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     */
    public void readStateData(ReadView view) {
        Objects.requireNonNull(view, "view");
        ConverterElement.assignState(stateArray, ConverterElement.readNbtState(view));
        this.tripped = ConverterElement.readNbtBlown(view);
    }

    public ElectricalState getElectricalState() {
        return reportedState;
    }

    public void setElectricalState(ElectricalState state) {
        // Managed by internal state transitions
    }

    /**
     * Conversion efficiency ratio [0.0, 1.0] for the current operating point.
     * Implemented per topology by subclasses; consumed by kernel-path demand staging.
     */
    public abstract double getEfficiency();

    public abstract double getMinInputVoltage();
    public abstract double getMaxInputVoltage();
    public abstract double getMaxOutputCurrent();
    public abstract double getNominalOutputVoltage();
    public abstract boolean isGridTie();
    public abstract double getTotalHarmonicDistortion();

    public double getTargetOutputVoltage() {
        return targetOutputVoltage;
    }

    public void setTargetOutputVoltage(double target) {
        this.targetOutputVoltage = target;
        markDirty();
    }

    public double getInputVoltage() {
        return inputVoltage;
    }

    public double getInputPowerWatts() {
        return inputPowerWatts;
    }

    public double getOutputVoltage() {
        return actualOutputVoltage > 0.1 ? actualOutputVoltage : outputVoltageEmf;
    }

    public double getOutputCurrentAmps() {
        return outputCurrentAmps;
    }

    public double getOutputPowerWatts() {
        return outputPowerWatts;
    }

    public double getTemperatureCelsius() {
        return temperatureCelsius;
    }

    public boolean isTripped() {
        return tripped;
    }

    public void resetTrip() {
        this.tripped = false;
        this.tripCooldownTicks = 0;
        this.underVoltageTicks = 0;
        this.antiIslandingTicks = 0;
        this.tripGraceTicks = DEFAULT_TRIP_GRACE_TICKS;
        this.inputCurrentCap = 0.0;
        this.outputHungry = false;
        this.lastDemandWatts = UNINITIALIZED_DEMAND_WATTS;
        markDirty();
    }


    /**
     * Shared by {@link #calculateAvailableOutputCurrent}:
     * full authority (1.0) above 110% of minVin, linearly ramping down to {@link #MIN_THROTTLE} as
     * the input rail approaches its cutoff.
     */
    private double computeThrottleFactor(double minVin) {
        double throttleVin = minVin * THROTTLE_ONSET_MARGIN;
        if (inputVoltage >= throttleVin) {
            return 1.0;
        }
        return Math.clamp((inputVoltage - minVin) / Math.max(0.1, throttleVin - minVin), MIN_THROTTLE, 1.0);
    }



    // ---------------------------------------------------------------------
    // Subclass hooks
    // ---------------------------------------------------------------------

    /**
     * Subclasses calculate regulated output EMF based on conversion topology.
     */
    protected abstract double computeOutputVoltage(double inputVoltage);

    /**
     * Calculates input power demand in Watts.
     */
    protected double calculateInputPowerDemand() {
        if (tripped || inputVoltage <= DEAD_RAIL_VOLTAGE) {
            return 0.0;
        }
        double eta = Math.max(MIN_EFFICIENCY_FLOOR, getEfficiency());
        return (outputPowerWatts / eta) + IDLE_DRAW_WATTS;
    }

    /**
     * Calculates available output current in Amperes.
     */
    protected double calculateAvailableOutputCurrent() {
        if (tripped || inputVoltage <= DEAD_RAIL_VOLTAGE) return 0.0;
        double rated = Math.max(0.0, getMaxOutputCurrent());
        double throttle = computeThrottleFactor(getMinInputVoltage());
        return rated * throttle;
    }

    // ==================== Serialization ====================

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        this.targetOutputVoltage = view.getDouble("target_voltage", DEFAULT_TARGET_OUTPUT_VOLTAGE);
        this.temperatureCelsius = view.getDouble("temperature", DEFAULT_TEMPERATURE_C);
        this.tripped = view.getBoolean("tripped", false);
        this.tripGraceTicks = DEFAULT_TRIP_GRACE_TICKS;
        this.inputCurrentCap = 0.0;
        this.outputHungry = false;
        this.lastDemandWatts = UNINITIALIZED_DEMAND_WATTS;
        this.reportedState = ElectricalState.OFF;
        readStateData(view);
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        view.putDouble("target_voltage", this.targetOutputVoltage);
        view.putDouble("temperature", this.temperatureCelsius);
        view.putBoolean("tripped", this.tripped);
        writeStateData(view);
    }
}