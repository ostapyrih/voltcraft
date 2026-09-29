package com.ostapyrih.voltcraft.block.entity.creative;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.screen.handler.CreativeLoadScreenHandler;
import com.ostapyrih.voltcraft.simulation.creative.CreativeLoadLogic;
import com.ostapyrih.voltcraft.simulation.creative.CreativeLoadLogic.LoadMode;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
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

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

/**
 * Creative-only electrical load block for testing circuit behavior, voltage drops,
 * transformer loading, inverter THD and overcurrent trips.
 * Supports Constant Resistance, Constant Power, and Constant Current modes.
 *
 * <p>Kernel adapter: implements {@link KernelAttachedBlock} through the static
 * nested {@link CreativeLoadElement} (resistive load in {@code CONSTANT_RESISTANCE},
 * constant-power in {@code CONSTANT_POWER}, constant-current in
 * {@code CONSTANT_CURRENT}). All decision logic lives in the nested class with
 * supplier-injected staging because unit-test runtimes cannot initialize
 * {@code BlockEntity} subclasses at all ({@code BlockEntity.&lt;clinit&gt;} touches
 * {@code Registries}). The outer BE owns the {@link CreativeLoadLogic} config holder,
 * the write-only telemetry cell, and the (empty) state array.</p>
 *
 * <ul>
 *   <li>Terminals: two adjacent positions, east/west
 *       ({@code TERMINAL_OFFSETS = {{1,0,0},{-1,0,0}}}). Load sign convention is
 *       {@code T0}-referenced: {@code V = Vt[0] - Vt[1]}, consumed current
 *       {@code It[0]} (positive while consuming).</li>
 *   <li>States: exactly 0 kernel-owned reals. Mode/target settings are discrete
 *       staging inputs, never in the state array.</li>
 *   <li>Element stamp: resistive mode stamps an admittance (AC-safe); power/current
 *       modes stamp the DC-only {@code constantPower}/{@code constantCurrent}
 *       linearizations on DC islands and a resistive equivalent
 *       ({@code R = Vnom^2/P}, {@code R = Vnom/I}, reusing the logic's nominal-voltage
 *       fallback) on AC islands. Disabled or non-positive targets stamp nothing.</li>
 *   <li>Derivatives: stateless except the telemetry cache {@code [terminalV, consumedI]},
 *       write-only, meaningful only after {@code kernel.tick()}.</li>
 *   <li>{@link #tickElectrical(ServerWorld)} folds previous-tick telemetry into the
 *       logic's consumed-energy bookkeeping only (same formulas as the legacy
 *       {@code onPowerReceived} path); never mutates kernel state, never touches the
 *       kernel, null-world safe.</li>
 * </ul>
 *
 * <p>NBT keys (preserved): {@code "mode"}, {@code "target_value"}, {@code "enabled"},
 * {@code "total_energy"}.</p>
 */
public class CreativeLoadBlockEntity extends BlockEntity implements KernelAttachedBlock, ExtendedScreenHandlerFactory<BlockPos> {

    /**
     * Static kernel element. Fully unit-testable without any registry, world, or
     * block-entity instance.
     */
    public static final class CreativeLoadElement implements ElectricalElement {
        /** Terminal offsets: east / west of the BE position. */
        public static final int[][] TERMINAL_OFFSETS = {{1, 0, 0}, {-1, 0, 0}};
        /** Mode ordinal of {@code CONSTANT_RESISTANCE} (mirrors {@code LoadMode}). */
        public static final int MODE_RESISTANCE = 0;
        /** Mode ordinal of {@code CONSTANT_POWER} (mirrors {@code LoadMode}). */
        public static final int MODE_POWER = 1;
        /** Mode ordinal of {@code CONSTANT_CURRENT} (mirrors {@code LoadMode}). */
        public static final int MODE_CURRENT = 2;
        /** Floor for stamped resistances in ohms (numerical-stability guard). */
        public static final double MIN_RESISTANCE_OHM = 1e-4;
        /** Constant-power/current knee voltage in volts (numerical-stability floor). */
        public static final double CP_VMIN_VOLTS = 1.0;
        /** Telemetry cell index of the terminal voltage in volts (T0-referenced). */
        public static final int TELE_V = 0;
        /** Telemetry cell index of the consumed current in amps (positive while consuming). */
        public static final int TELE_I = 1;

        private final IntSupplier modeOrdinal;
        private final DoubleSupplier targetValue;
        private final BooleanSupplier enabled;
        private final DoubleSupplier nominalVoltage;
        private final double[] telemetryCell;

        /**
         * @param modeOrdinal supplier for the BE-owned load-mode ordinal (read at stamp time)
         * @param targetValue supplier for the BE-owned mode setpoint in ohms/watts/amps
         * @param enabled supplier for the BE-owned enabled flag (read at stamp time only)
         * @param nominalVoltage supplier for the nominal rail voltage in volts
         *        (AC resistive fallback only)
         * @param telemetryCell BE/test-owned write-only cache {@code [terminalV, consumedI]},
         *        length {@code >= 2}
         */
        public CreativeLoadElement(IntSupplier modeOrdinal, DoubleSupplier targetValue,
                                   BooleanSupplier enabled, DoubleSupplier nominalVoltage,
                                   double[] telemetryCell) {
            this.modeOrdinal = Objects.requireNonNull(modeOrdinal, "modeOrdinal");
            this.targetValue = Objects.requireNonNull(targetValue, "targetValue");
            this.enabled = Objects.requireNonNull(enabled, "enabled");
            this.nominalVoltage = Objects.requireNonNull(nominalVoltage, "nominalVoltage");
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
            return 0;
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
                Stamps.admittance(y, terminals[0], terminals[1], new Complex(1.0 / r, 0.0));
                return;
            }
            if (omega == 0.0) {
                if (mode == MODE_POWER) {
                    Stamps.constantPower(y, in, terminals[0], terminals[1], v,
                        target, CP_VMIN_VOLTS, 0.0);
                } else if (mode == MODE_CURRENT) {
                    Stamps.constantCurrent(y, in, terminals[0], terminals[1], v,
                        target, CP_VMIN_VOLTS, 0.0);
                }
                // Unknown mode ordinals stamp nothing (open circuit).
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
                        Stamps.admittance(y, terminals[0], terminals[1], new Complex(1.0 / r, 0.0));
                    }
                }
            }
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
            double intoT0 = 0.0;
            if (it.length > 0 && it[0] != null) {
                intoT0 = it[0].re;
            }
            double terminalV = 0.0;
            if (vt.length > 1 && vt[0] != null && vt[1] != null) {
                terminalV = vt[0].re - vt[1].re;
            }
            // Telemetry-only cache: write-only, never read by control flow.
            telemetryCell[TELE_V] = terminalV;
            telemetryCell[TELE_I] = intoT0;
        }

        /** Loads never source the island. */
        public static boolean isActiveSource() {
            return false;
        }

        /** Loads hold no kernel state: always a fresh empty array. */
        public static double[] newStateArray() {
            return new double[0];
        }

        /** Defensive snapshot: validates the empty length, returns a clone. */
        public static double[] snapshotState(double[] live) {
            Objects.requireNonNull(live, "live");
            if (live.length != 0) {
                throw new IllegalArgumentException(
                    "CreativeLoadBlockEntity holds 0 states, got " + live.length);
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
                    "CreativeLoadBlockEntity holds 0 states, got dst="
                        + (dst == null ? "null" : dst.length) + " src=" + (src == null ? "null" : src.length));
            }
        }
    }

    private final CreativeLoadLogic logic;
    private final double[] telemetryCell = new double[2];
    private final double[] stateArray = CreativeLoadElement.newStateArray();
    private final ElectricalElement element;

    private final PropertyDelegate propertyDelegate = new PropertyDelegate() {
        @Override
        public int get(int index) {
            int targetVal = (int) Math.round(logic.getTargetValue() * 100.0);
            int v = (int) Math.round(logic.getLastMeasuredVoltage() * 10.0);
            int i = (int) Math.round(logic.getLastDeliveredCurrent() * 100.0);
            int p = (int) Math.round(logic.getLastDeliveredPower() * 10.0);
            double rVal = getEquivalentResistance();
            if (Double.isInfinite(rVal) || Double.isNaN(rVal)) rVal = 99999.0;
            int rEq = (int) Math.round(Math.min(99999.0, rVal) * 100.0);
            int kJ = (int) Math.round(logic.getTotalEnergyConsumedJoules() / 1000.0);

            return switch (index) {
                case CreativeLoadScreenHandler.PROP_MODE_ORDINAL -> logic.getMode().ordinal();
                case CreativeLoadScreenHandler.PROP_TARGET_VALUE_LOW -> CreativeLoadScreenHandler.packLow(targetVal);
                case CreativeLoadScreenHandler.PROP_TARGET_VALUE_HIGH -> CreativeLoadScreenHandler.packHigh(targetVal);
                case CreativeLoadScreenHandler.PROP_ENABLED -> logic.isEnabled() ? 1 : 0;
                case CreativeLoadScreenHandler.PROP_VOLTAGE_X10 -> v;
                case CreativeLoadScreenHandler.PROP_CURRENT_LOW -> CreativeLoadScreenHandler.packLow(i);
                case CreativeLoadScreenHandler.PROP_CURRENT_HIGH -> CreativeLoadScreenHandler.packHigh(i);
                case CreativeLoadScreenHandler.PROP_POWER_LOW -> CreativeLoadScreenHandler.packLow(p);
                case CreativeLoadScreenHandler.PROP_POWER_HIGH -> CreativeLoadScreenHandler.packHigh(p);
                case CreativeLoadScreenHandler.PROP_RESISTANCE_LOW -> CreativeLoadScreenHandler.packLow(rEq);
                case CreativeLoadScreenHandler.PROP_RESISTANCE_HIGH -> CreativeLoadScreenHandler.packHigh(rEq);
                case CreativeLoadScreenHandler.PROP_ENERGY_LOW -> CreativeLoadScreenHandler.packLow(kJ);
                case CreativeLoadScreenHandler.PROP_ENERGY_HIGH -> CreativeLoadScreenHandler.packHigh(kJ);
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {}

        @Override
        public int size() {
            return CreativeLoadScreenHandler.PROPERTY_COUNT;
        }
    };

    public CreativeLoadBlockEntity(BlockPos pos, BlockState state) {
        super(VoltcraftBlockEntityTypes.CREATIVE_LOAD_BLOCK_ENTITY, pos, state);
        this.logic = new CreativeLoadLogic(pos);
        this.element = new CreativeLoadElement(
            () -> logic.getMode().ordinal(), () -> logic.getTargetValue(),
            () -> logic.isEnabled(), () -> logic.getNominalVoltage(), telemetryCell);
    }

    public PropertyDelegate getPropertyDelegate() {
        return propertyDelegate;
    }

    @Override
    public Text getDisplayName() {
        return Text.translatable(getCachedState().getBlock().getTranslationKey());
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
        return new CreativeLoadScreenHandler(syncId, playerInventory, this.pos, this.propertyDelegate);
    }

    @Override
    public BlockPos getScreenOpeningData(ServerPlayerEntity player) {
        return this.pos;
    }

    public CreativeLoadLogic getLogic() {
        return logic;
    }

    public LoadMode getMode() {
        return logic.getMode();
    }

    public void setMode(LoadMode mode) {
        logic.setMode(mode);
        markDirty();
    }

    public double getTargetValue() {
        return logic.getTargetValue();
    }

    public void setTargetValue(double targetValue) {
        logic.setTargetValue(targetValue);
        markDirty();
    }

    public boolean isEnabled() {
        return logic.isEnabled();
    }

    public void setEnabled(boolean enabled) {
        logic.setEnabled(enabled);
        markDirty();
    }

    public void toggleEnabled() {
        logic.toggleEnabled();
        markDirty();
    }

    public double getLastMeasuredVoltage() {
        return logic.getLastMeasuredVoltage();
    }

    public double getLastDeliveredCurrent() {
        return logic.getLastDeliveredCurrent();
    }

    public double getLastDeliveredPower() {
        return logic.getLastDeliveredPower();
    }

    public double getTotalEnergyConsumedJoules() {
        return logic.getTotalEnergyConsumedJoules();
    }

    public void resetEnergy() {
        logic.resetEnergy();
        markDirty();
    }

    public String getTargetDisplay() {
        return logic.getTargetDisplay();
    }

    public LoadMode cycleMode() {
        LoadMode m = logic.cycleMode();
        markDirty();
        return m;
    }

    public double cycleTargetValue() {
        double val = logic.cycleTargetValue();
        markDirty();
        return val;
    }

    // ==================== KernelAttachedBlock ====================

    @Override
    public ElectricalElement getElement() {
        return element;
    }

    @Override
    public BlockPos[] getTerminalPositions() {
        int[][] o = CreativeLoadElement.TERMINAL_OFFSETS;
        BlockPos[] out = new BlockPos[o.length];
        for (int k = 0; k < o.length; k++) {
            out[k] = pos.add(o[k][0], o[k][1], o[k][2]);
        }
        return out;
    }

    @Override
    public double[] getStateArray() {
        return CreativeLoadElement.snapshotState(stateArray);
    }

    @Override
    public void setStateArray(double[] state) {
        CreativeLoadElement.assignState(stateArray, state);
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public boolean isActiveSource() {
        return CreativeLoadElement.isActiveSource();
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        // Discrete bookkeeping only: folds previous-tick telemetry into the consumed-energy
        // counters with the legacy onPowerReceived formulas. Never mutates kernel state,
        // never touches the kernel; the world argument is never dereferenced (null-safe).
        logic.onPowerReceived(telemetryCell[CreativeLoadElement.TELE_V],
            telemetryCell[CreativeLoadElement.TELE_I], GridConstants.DT);
    }

    // ==================== Legacy config hooks (plain methods, no grid role) ====================

    public ElectricalState getElectricalState() {
        return logic.getElectricalState();
    }

    public void setElectricalState(ElectricalState state) {
        logic.setElectricalState(state);
    }

    public double getNominalPowerDemand() {
        return logic.getNominalPowerDemand();
    }

    public double getNominalVoltage() {
        return logic.getNominalVoltage();
    }

    public double getMinOperatingVoltage() {
        return logic.getMinOperatingVoltage();
    }

    public double getMaxOperatingVoltage() {
        return logic.getMaxOperatingVoltage();
    }

    public double getEquivalentResistance() {
        return logic.getEquivalentResistance();
    }

    public void onPowerReceived(double terminalVoltage, double deliveredCurrent, double durationSeconds) {
        logic.onPowerReceived(terminalVoltage, deliveredCurrent, durationSeconds);
    }

    // ==================== Serialization (keys preserved) ====================

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        int modeOrdinal = view.getInt("mode", 0);
        if (modeOrdinal >= 0 && modeOrdinal < LoadMode.values().length) {
            logic.setMode(LoadMode.values()[modeOrdinal]);
        }
        logic.setTargetValue(view.getDouble("target_value", 10.0));
        logic.setEnabled(view.getBoolean("enabled", true));
        logic.setTotalEnergyConsumedJoules(view.getDouble("total_energy", 0.0));
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        view.putInt("mode", logic.getMode().ordinal());
        view.putDouble("target_value", logic.getTargetValue());
        view.putBoolean("enabled", logic.isEnabled());
        view.putDouble("total_energy", logic.getTotalEnergyConsumedJoules());
    }
}
