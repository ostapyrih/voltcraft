package com.ostapyrih.voltcraft.block.entity.conversion;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.energy.IElectricSource;
import com.ostapyrih.voltcraft.block.conversion.InverterBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.simulation.conversion.InverterType;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalGrid;
import com.ostapyrih.voltcraft.simulation.grid.GridManager;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Block entity for DC-AC inverters (Square Wave, Modified Sine, Pure Sine SPWM, Grid-Tie, Hybrid ESS).
 *
 * <p>Overload protection uses a three-state machine: NORMAL, WARN, TRIPPED. Sustained overload
 * drives the inverter into a latched TRIPPED state with a cooldown before auto-retry, mirroring
 * how a real inverter behaves instead of oscillating between full output and zero every few ticks.</p>
 */
public class InverterBlockEntity extends AbstractPowerConverterBlockEntity {

    // --- Overload protection ---
    /** Sustained overload grace before latching a trip. ~3 s at 20 tps. */
    private static final int OVERLOAD_GRACE_TICKS = 60;
    /** Off-time after a trip before auto-retry. ~10 s at 20 tps. */
    private static final int TRIP_COOLDOWN_TICKS = 200;
    /** Voltage sag fraction above which we consider the output severely overloaded → immediate trip. */
    private static final double SEVERE_SAG_FRACTION = 0.10;
    /** Voltage sag fraction above which we consider the output mildly overloaded → WARN. */
    private static final double MILD_SAG_FRACTION = 0.03;
    /** Output current at or above this fraction of rated counts as "at cap". */
    private static final double AT_CAP_CURRENT_FRACTION = 0.98;

    /** Low-battery cutoffs per DC input class. Inverter refuses to run below these. */
    private static final double LOW_BATTERY_V_12 = 10.5;
    private static final double LOW_BATTERY_V_24 = 21.0;
    private static final double LOW_BATTERY_V_48 = 42.0;

    private final InverterType inverterType;
    private boolean atsIslandMode = false;
    private double nominalInputVoltage = 48.0;

    // --- Protection state ---
    private boolean inverterTripped = false;
    private int overloadTicks = 0;
    private int cooldownTicks = 0;

    public InverterBlockEntity(BlockPos pos, BlockState state, InverterType inverterType) {
        super(VoltcraftBlockEntityTypes.INVERTER_BLOCK_ENTITY, pos, state);
        this.inverterType = inverterType;
        this.targetOutputVoltage = inverterType.getNominalOutputVoltage();
    }

    public InverterBlockEntity(BlockPos pos, BlockState state) {
        this(
            pos,
            state,
            state.getBlock() instanceof InverterBlock ib ? ib.getInverterType() : InverterType.PURE_SINE
        );
    }

    public InverterType getInverterType() {
        return inverterType;
    }

    public boolean isAtsIslandMode() {
        return atsIslandMode;
    }

    @Override
    public double getNominalInputVoltage() {
        return nominalInputVoltage;
    }

    @Override
    public void setNominalInputVoltage(double voltage) {
        super.setNominalInputVoltage(voltage);
        if (voltage <= 15.0) {
            this.nominalInputVoltage = 12.0;
        } else if (voltage <= 30.0) {
            this.nominalInputVoltage = 24.0;
        } else {
            this.nominalInputVoltage = 48.0;
        }
        markDirty();
    }

    @Override
    public void resetTrip() {
        super.resetTrip();
        this.inverterTripped = false;
        this.overloadTicks = 0;
        this.cooldownTicks = 0;
        if (inverterType.hasAutomaticTransferSwitch()) {
            if (world instanceof ServerWorld sw) {
                this.atsIslandMode = !isExternalGridPresent(sw);
            } else {
                this.atsIslandMode = true;
            }
        }
    }

    @Override
    public boolean isTripped() {
        return super.isTripped() || inverterTripped;
    }

    private boolean isExternalGridPresent(ServerWorld world) {
        BlockPos outPos = pos.offset(getOutputPortDirection());
        ElectricalGrid outGrid = GridManager.get(world).getGridAt(outPos);
        if (outGrid == null) {
            return false;
        }

        for (List<IElectricSource> list : outGrid.getSources().values()) {
            for (IElectricSource src : list) {
                if (src == this.outputSource) {
                    continue;
                }
                if (src.getElectromotiveForce() >= 20.0 && src.getFrequency() > 0.001) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean acceptsInputFrequency(double frequencyHz) {
        return frequencyHz <= 0.001; // DC only
    }

    @Override
    public double getOutputFrequency() {
        return 50.0;
    }

    @Override
    public boolean isOutputConfigurable() {
        return true;
    }

    @Override
    public int getTypeKind() {
        return 1; // Inverter
    }

    @Override
    public double getEfficiency() {
        return inverterType.getEfficiency();
    }

    @Override
    public double getMinInputVoltage() {
        if (nominalInputVoltage <= 15.0) return 10.0;
        if (nominalInputVoltage <= 30.0) return 20.0;
        return 40.0;
    }

    @Override
    public double getMaxInputVoltage() {
        if (nominalInputVoltage <= 15.0) return 16.5;
        if (nominalInputVoltage <= 30.0) return 33.0;
        return 66.0;
    }

    @Override
    public double getMaxOutputCurrent() {
        return inverterType.getMaxPowerWatts() / Math.max(1.0, targetOutputVoltage);
    }

    @Override
    public double getNominalOutputVoltage() {
        return targetOutputVoltage;
    }

    @Override
    public boolean isGridTie() {
        return inverterType.isGridTie() && !atsIslandMode;
    }

    @Override
    public double getTotalHarmonicDistortion() {
        return inverterType.getTotalHarmonicDistortionPercent();
    }

    @Override
    public void tick(ServerWorld world) {
        // Hybrid ESS ATS logic (unchanged)
        if (inverterType.hasAutomaticTransferSwitch()) {
            boolean extGrid = isExternalGridPresent(world);
            if (!extGrid) {
                this.atsIslandMode = true;
                this.antiIslandingTicks = 0;
            } else {
                this.atsIslandMode = false;
            }
        }

        super.tick(world);

        updateInverterProtection();
    }

    private boolean isLowBattery() {
        return inputVoltage > 1.0 && inputVoltage < getLowBatteryCutoff();
    }

    private double getLowBatteryCutoff() {
        if (nominalInputVoltage <= 15.0) return LOW_BATTERY_V_12;
        if (nominalInputVoltage <= 30.0) return LOW_BATTERY_V_24;
        return LOW_BATTERY_V_48;
    }

    /**
     * Overload and low-battery protection state machine. Runs AFTER super.tick so that
     * outputCurrentAmps, actualOutputVoltage, and inputVoltage are freshly updated.
     *
     * <p>Behaviour:</p>
     * <ul>
     *   <li>Severe overload (output voltage sag &gt; {@link #SEVERE_SAG_FRACTION}) → immediate trip.</li>
     *   <li>Mild overload (sag &gt; {@link #MILD_SAG_FRACTION} and current at cap) → WARN; trip
     *       after {@link #OVERLOAD_GRACE_TICKS} sustained ticks.</li>
     *   <li>Low DC input below the class cutoff → immediate trip.</li>
     *   <li>On trip: output latched to zero for {@link #TRIP_COOLDOWN_TICKS}, then auto-retry.</li>
     * </ul>
     */
    private void updateInverterProtection() {
        if (inverterTripped) {
            if (cooldownTicks > 0) {
                cooldownTicks--;
            } else {
                inverterTripped = false;
                overloadTicks = 0;
            }
            return;
        }

        // Low DC input is a hard stop: no output until voltage recovers.
        if (isLowBattery()) {
            inverterTripped = true;
            cooldownTicks = TRIP_COOLDOWN_TICKS;
            overloadTicks = 0;
            return;
        }

        double ratedCurrent = getMaxOutputCurrent();
        if (ratedCurrent <= 0.0 || targetOutputVoltage <= 1.0) {
            return;
        }

        // No load (or below the current cap) means no sag to evaluate. Idle current is drawn
        // by the input stage regardless of output, so a 0 A output is NOT an overload — it
        // just means nothing is plugged in yet.
        if (outputCurrentAmps < ratedCurrent * AT_CAP_CURRENT_FRACTION) {
            overloadTicks = Math.max(0, overloadTicks - 2);
            return;
        }

        // Only now, with current at cap, does a sag mean anything.
        double voltageRatio = actualOutputVoltage / targetOutputVoltage;
        double sag = Math.max(0.0, 1.0 - voltageRatio);

        if (sag > SEVERE_SAG_FRACTION) {
            inverterTripped = true;
            cooldownTicks = TRIP_COOLDOWN_TICKS;
            overloadTicks = 0;
            return;
        }

        if (sag > MILD_SAG_FRACTION) {
            overloadTicks++;
            if (overloadTicks > OVERLOAD_GRACE_TICKS) {
                inverterTripped = true;
                cooldownTicks = TRIP_COOLDOWN_TICKS;
                overloadTicks = 0;
            }
        } else {
            overloadTicks = Math.max(0, overloadTicks - 2);
        }
    }

    @Override
    public ElectricalState getElectricalState() {
        if (inverterTripped) {
            return ElectricalState.OFF;
        }
        return super.getElectricalState();
    }

    @Override
    protected double computeOutputVoltage(double inputVoltage) {
        if (inverterTripped) {
            return 0.0;
        }
        return targetOutputVoltage;
    }

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        this.atsIslandMode = view.getBoolean("ats_island_mode", false);
        this.nominalInputVoltage = view.getDouble("nominal_input_voltage", 48.0);
        this.inverterTripped = view.getBoolean("inverter_tripped", false);
        this.cooldownTicks = view.getInt("cooldown_ticks", 0);
        this.overloadTicks = view.getInt("overload_ticks", 0);
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        view.putBoolean("ats_island_mode", this.atsIslandMode);
        view.putDouble("nominal_input_voltage", this.nominalInputVoltage);
        view.putBoolean("inverter_tripped", this.inverterTripped);
        view.putInt("cooldown_ticks", this.cooldownTicks);
        view.putInt("overload_ticks", this.overloadTicks);
    }
}