package com.ostapyrih.voltcraft.block.entity.creative;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.screen.handler.CreativeGeneratorScreenHandler;
import com.ostapyrih.voltcraft.simulation.creative.CreativeGeneratorLogic;
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

/**
 * Creative-only power generator for testing grid networks, converters, cables, and loads.
 * Provides freely configurable voltage, max current, internal resistance, and DC/AC frequency.
 *
 * <p>Kernel adapter: implements {@link KernelAttachedBlock} through the static
 * nested {@link CreativeGeneratorElement} (ideal Thevenin source with staged EMF, west-positive
 * source polarity: {@code terminals[1]} positive). All decision logic lives in the nested
 * class with supplier-injected staging because unit-test runtimes cannot initialize
 * {@code BlockEntity} subclasses at all ({@code BlockEntity.&lt;clinit&gt;} touches
 * {@code Registries}). The outer BE owns the {@link CreativeGeneratorLogic} config holder,
 * the write-only telemetry cell, and the (empty) state array.</p>
 *
 * <ul>
 *   <li>Terminals: two adjacent positions, east/west
 *       ({@code TERMINAL_OFFSETS = {{1,0,0},{-1,0,0}}}).</li>
 *   <li>States: exactly 0 kernel-owned reals. Voltage/current/frequency settings are
 *       discrete staging inputs, never in the state array.</li>
 *   <li>Element stamp: Thevenin with staged {@code EMF} and
 *       {@code R = max(1e-4, staged resistance)}; disabled or {@code EMF <= 0} stamps
 *       nothing (open circuit).</li>
 *   <li>Derivatives: stateless except the telemetry cache {@code [terminalV, deliveredI]}
 *       (delivered current positive while sourcing), write-only, meaningful only after
 *       {@code kernel.tick()}.</li>
 *   <li>{@link #tickElectrical(ServerWorld)} folds previous-tick telemetry into the
 *       logic's delivered-energy bookkeeping only (same formulas as the legacy
 *       {@code onPowerDrawn} path); never mutates kernel state, never touches the kernel,
 *       null-world safe.</li>
 * </ul>
 *
 * <p>Documented simplifications: the legacy solver-side current-limit clamp
 * ({@code maxCurrent}) is not staged into the kernel stamp — the element is an ideal
 * Thevenin source. Frequency selects island omega via {@link #isACSource()} but the
 * stamp itself is waveform-agnostic (same Thevenin at any omega).</p>
 *
 * <p>NBT keys (preserved): {@code "voltage"}, {@code "max_current"},
 * {@code "internal_resistance"}, {@code "frequency"}, {@code "enabled"},
 * {@code "total_energy"}.</p>
 */
public class CreativeGeneratorBlockEntity extends BlockEntity implements KernelAttachedBlock, ExtendedScreenHandlerFactory<BlockPos> {

    /**
     * Static kernel element. Fully unit-testable without any registry, world, or
     * block-entity instance.
     */
    public static final class CreativeGeneratorElement implements ElectricalElement {
        /** Terminal offsets: east / west of the BE position. */
        public static final int[][] TERMINAL_OFFSETS = {{1, 0, 0}, {-1, 0, 0}};
        /** Floor for the staged Thevenin resistance in ohms (numerical-stability guard). */
        public static final double MIN_RESISTANCE_OHM = 1e-4;
        /** Telemetry cell index of the terminal voltage in volts. */
        public static final int TELE_V = 0;
        /** Telemetry cell index of the delivered current in amps (positive while sourcing). */
        public static final int TELE_I = 1;

        private final BooleanSupplier enabled;
        private final DoubleSupplier electromotiveForce;
        private final DoubleSupplier internalResistance;
        private final double[] telemetryCell;

        /**
         * @param enabled supplier for the BE-owned enabled flag (read at stamp time only)
         * @param electromotiveForce supplier for the staged EMF in volts (read at stamp time)
         * @param internalResistance supplier for the staged series resistance in ohms
         * @param telemetryCell BE/test-owned write-only cache {@code [terminalV, deliveredI]},
         *        length {@code >= 2}
         */
        public CreativeGeneratorElement(BooleanSupplier enabled, DoubleSupplier electromotiveForce,
                                        DoubleSupplier internalResistance, double[] telemetryCell) {
            this.enabled = Objects.requireNonNull(enabled, "enabled");
            this.electromotiveForce = Objects.requireNonNull(electromotiveForce, "electromotiveForce");
            this.internalResistance = Objects.requireNonNull(internalResistance, "internalResistance");
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
            double intoNeg = 0.0;
            if (it.length > 0 && it[0] != null) {
                intoNeg = it[0].re;
            }
            double terminalV = 0.0;
            if (vt.length > 1 && vt[0] != null && vt[1] != null) {
                terminalV = vt[1].re - vt[0].re;
            }
            // Telemetry-only cache: write-only, never read by control flow.
            telemetryCell[TELE_V] = terminalV;
            telemetryCell[TELE_I] = intoNeg;
        }

        /** Source classification: active whenever enabled with a positive staged EMF. */
        public static boolean isActiveSource(boolean enabled, double electromotiveForce) {
            return enabled && electromotiveForce > 0.0;
        }

        /** AC classification mirrors the legacy waveform flag: AC whenever above DC. */
        public static boolean isACSource(double frequencyHz) {
            return frequencyHz > 0.001;
        }

        /** Generators hold no kernel state: always a fresh empty array. */
        public static double[] newStateArray() {
            return new double[0];
        }

        /** Defensive snapshot: validates the empty length, returns a clone. */
        public static double[] snapshotState(double[] live) {
            Objects.requireNonNull(live, "live");
            if (live.length != 0) {
                throw new IllegalArgumentException(
                    "CreativeGeneratorBlockEntity holds 0 states, got " + live.length);
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
                    "CreativeGeneratorBlockEntity holds 0 states, got dst="
                        + (dst == null ? "null" : dst.length) + " src=" + (src == null ? "null" : src.length));
            }
        }
    }

    private final CreativeGeneratorLogic logic;
    private final double[] telemetryCell = new double[2];
    private final double[] stateArray = CreativeGeneratorElement.newStateArray();
    private final ElectricalElement element;

    private final PropertyDelegate propertyDelegate = new PropertyDelegate() {
        @Override
        public int get(int index) {
            int v = (int) Math.round(logic.getVoltage() * 10.0);
            int iMax = (int) Math.round(logic.getMaxCurrent() * 10.0);
            int rInt = (int) Math.round(logic.getInternalResistance() * 10000.0);
            int outI = (int) Math.round(logic.getLastDeliveredCurrent() * 100.0);
            int outP = (int) Math.round(logic.getLastDeliveredPower() * 10.0);
            int kJ = (int) Math.round(logic.getTotalEnergyJoules() / 1000.0);

            return switch (index) {
                case CreativeGeneratorScreenHandler.PROP_VOLTAGE_LOW -> CreativeGeneratorScreenHandler.packLow(v);
                case CreativeGeneratorScreenHandler.PROP_VOLTAGE_HIGH -> CreativeGeneratorScreenHandler.packHigh(v);
                case CreativeGeneratorScreenHandler.PROP_CURRENT_LIMIT_LOW -> CreativeGeneratorScreenHandler.packLow(iMax);
                case CreativeGeneratorScreenHandler.PROP_CURRENT_LIMIT_HIGH -> CreativeGeneratorScreenHandler.packHigh(iMax);
                case CreativeGeneratorScreenHandler.PROP_R_INT_LOW -> CreativeGeneratorScreenHandler.packLow(rInt);
                case CreativeGeneratorScreenHandler.PROP_R_INT_HIGH -> CreativeGeneratorScreenHandler.packHigh(rInt);
                case CreativeGeneratorScreenHandler.PROP_FREQUENCY_X10 -> (int) Math.round(logic.getFrequency() * 10.0);
                case CreativeGeneratorScreenHandler.PROP_ENABLED -> logic.isEnabled() ? 1 : 0;
                case CreativeGeneratorScreenHandler.PROP_OUT_CURRENT_LOW -> CreativeGeneratorScreenHandler.packLow(outI);
                case CreativeGeneratorScreenHandler.PROP_OUT_CURRENT_HIGH -> CreativeGeneratorScreenHandler.packHigh(outI);
                case CreativeGeneratorScreenHandler.PROP_OUT_POWER_LOW -> CreativeGeneratorScreenHandler.packLow(outP);
                case CreativeGeneratorScreenHandler.PROP_OUT_POWER_HIGH -> CreativeGeneratorScreenHandler.packHigh(outP);
                case CreativeGeneratorScreenHandler.PROP_ENERGY_LOW -> CreativeGeneratorScreenHandler.packLow(kJ);
                case CreativeGeneratorScreenHandler.PROP_ENERGY_HIGH -> CreativeGeneratorScreenHandler.packHigh(kJ);
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {}

        @Override
        public int size() {
            return CreativeGeneratorScreenHandler.PROPERTY_COUNT;
        }
    };

    public CreativeGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(VoltcraftBlockEntityTypes.CREATIVE_GENERATOR_BLOCK_ENTITY, pos, state);
        this.logic = new CreativeGeneratorLogic(pos);
        this.element = new CreativeGeneratorElement(
            () -> logic.isEnabled(), () -> logic.getElectromotiveForce(),
            () -> logic.getInternalResistance(), telemetryCell);
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
        return new CreativeGeneratorScreenHandler(syncId, playerInventory, this.pos, this.propertyDelegate);
    }

    @Override
    public BlockPos getScreenOpeningData(ServerPlayerEntity player) {
        return this.pos;
    }

    public CreativeGeneratorLogic getLogic() {
        return logic;
    }

    public double getVoltage() {
        return logic.getVoltage();
    }

    public void setVoltage(double voltage) {
        logic.setVoltage(voltage);
        markDirty();
    }

    public void setMaxCurrent(double maxCurrent) {
        logic.setMaxCurrent(maxCurrent);
        markDirty();
    }

    public void setInternalResistance(double internalResistance) {
        logic.setInternalResistance(internalResistance);
        markDirty();
    }

    public double getFrequency() {
        return logic.getFrequency();
    }

    public void setFrequency(double frequency) {
        logic.setFrequency(frequency);
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

    public double getLastDeliveredCurrent() {
        return logic.getLastDeliveredCurrent();
    }

    public double getLastDeliveredPower() {
        return logic.getLastDeliveredPower();
    }

    public double getTotalEnergyJoules() {
        return logic.getTotalEnergyJoules();
    }

    public void resetEnergy() {
        logic.resetEnergy();
        markDirty();
    }

    public String getFrequencyDisplay() {
        return logic.getFrequencyDisplay();
    }

    public double cycleVoltage() {
        double v = logic.cycleVoltage();
        markDirty();
        return v;
    }

    public double cycleCurrentLimit() {
        double c = logic.cycleCurrentLimit();
        markDirty();
        return c;
    }

    public double cycleInternalResistance() {
        double r = logic.cycleInternalResistance();
        markDirty();
        return r;
    }

    public double cycleFrequency() {
        double f = logic.cycleFrequency();
        markDirty();
        return f;
    }

    // ==================== KernelAttachedBlock ====================

    @Override
    public ElectricalElement getElement() {
        return element;
    }

    @Override
    public BlockPos[] getTerminalPositions() {
        int[][] o = CreativeGeneratorElement.TERMINAL_OFFSETS;
        BlockPos[] out = new BlockPos[o.length];
        for (int k = 0; k < o.length; k++) {
            out[k] = pos.add(o[k][0], o[k][1], o[k][2]);
        }
        return out;
    }

    @Override
    public double[] getStateArray() {
        return CreativeGeneratorElement.snapshotState(stateArray);
    }

    @Override
    public void setStateArray(double[] state) {
        CreativeGeneratorElement.assignState(stateArray, state);
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public boolean isActiveSource() {
        return CreativeGeneratorElement.isActiveSource(logic.isEnabled(), logic.getElectromotiveForce());
    }

    @Override
    public boolean isACSource() {
        return CreativeGeneratorElement.isACSource(logic.getFrequency());
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        // Discrete bookkeeping only: folds previous-tick telemetry into the delivered-energy
        // counters with the legacy onPowerDrawn formulas. Never mutates kernel state,
        // never touches the kernel; the world argument is never dereferenced (null-safe).
        logic.onPowerDrawn(telemetryCell[CreativeGeneratorElement.TELE_I], GridConstants.DT);
    }

    // ==================== Legacy config hooks (plain methods, no grid role) ====================

    public ElectricalState getElectricalState() {
        return logic.getElectricalState();
    }

    public void setElectricalState(ElectricalState state) {
        logic.setElectricalState(state);
    }

    public double getElectromotiveForce() {
        return logic.getElectromotiveForce();
    }

    public double getInternalResistance() {
        return logic.getInternalResistance();
    }

    public double getMaxOutputCurrent() {
        return logic.getMaxOutputCurrent();
    }

    public void onPowerDrawn(double currentAmps, double durationSeconds) {
        logic.onPowerDrawn(currentAmps, durationSeconds);
    }

    // ==================== Serialization (keys preserved) ====================

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        logic.setVoltage(view.getDouble("voltage", 230.0));
        logic.setMaxCurrent(view.getDouble("max_current", 1000.0));
        logic.setInternalResistance(view.getDouble("internal_resistance", 0.001));
        logic.setFrequency(view.getDouble("frequency", 0.0));
        logic.setEnabled(view.getBoolean("enabled", true));
        logic.setTotalEnergyJoules(view.getDouble("total_energy", 0.0));
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        view.putDouble("voltage", logic.getVoltage());
        view.putDouble("max_current", logic.getMaxCurrent());
        view.putDouble("internal_resistance", logic.getInternalResistance());
        view.putDouble("frequency", logic.getFrequency());
        view.putBoolean("enabled", logic.isEnabled());
        view.putDouble("total_energy", logic.getTotalEnergyJoules());
    }
}
