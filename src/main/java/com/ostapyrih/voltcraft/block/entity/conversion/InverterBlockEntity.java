package com.ostapyrih.voltcraft.block.entity.conversion;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.block.conversion.InverterBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.simulation.conversion.InverterType;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import com.ostapyrih.voltcraft.simulation.electrical.ConverterElement;

/**
 * Block entity for DC-AC inverters (square, modified-sine, pure-sine, grid-tie, hybrid).
 * Overload protection latches TRIPPED with a cooldown before auto-retry.
 */
public class InverterBlockEntity extends AbstractPowerConverterBlockEntity {

    private static final int OVERLOAD_GRACE_TICKS = 60;
    private static final int TRIP_COOLDOWN_TICKS = 200;
    private static final double SEVERE_SAG_FRACTION = 0.10;
    private static final double MILD_SAG_FRACTION = 0.03;
    private static final double AT_CAP_CURRENT_FRACTION = 0.98;

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
            this.atsIslandMode = true;
        }
    }

    @Override
    public boolean isTripped() {
        return super.isTripped() || inverterTripped;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        super.tickElectrical(world);
        updateKernelInverterProtection();
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
        return 1;
    }

    @Override
    public boolean isACSource() {
        return ConverterElement.isACOutput(getTypeKind());
    }

    @Override
    public boolean isActiveSource() {
        return ConverterElement.isActiveSource(isTripped(), stagedOutputEmf);
    }

    private void updateKernelInverterProtection() {
        if (inverterTripped) {
            if (cooldownTicks > 0) {
                cooldownTicks--;
            } else {
                inverterTripped = false;
                overloadTicks = 0;
            }
            return;
        }

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

        if (outputCurrentAmps < ratedCurrent * AT_CAP_CURRENT_FRACTION) {
            overloadTicks = Math.max(0, overloadTicks - 2);
            return;
        }

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

    private boolean isLowBattery() {
        return inputVoltage > 1.0 && inputVoltage < getLowBatteryCutoff();
    }

    private double getLowBatteryCutoff() {
        if (nominalInputVoltage <= 15.0) return LOW_BATTERY_V_12;
        if (nominalInputVoltage <= 30.0) return LOW_BATTERY_V_24;
        return LOW_BATTERY_V_48;
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