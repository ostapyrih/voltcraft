package com.ostapyrih.voltcraft.simulation.creative;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import net.minecraft.util.math.BlockPos;

/**
 * Pure simulation logic for the Creative Generator.
 * Decoupled from Minecraft BlockEntity lifecycle for unit testability and MNA grid solving.
 *
 * <p>Kernel-side: the legacy source grid contract is deleted. This class
 * is a plain configuration/telemetry holder (voltage/current/frequency presets plus
 * delivered-energy bookkeeping); the kernel-side stamp lives in
 * {@code CreativeGeneratorBlockEntity.CreativeGeneratorElement}.</p>
 */
public class CreativeGeneratorLogic {

    public static final double[] VOLTAGE_PRESETS = { 5.0, 12.0, 24.0, 48.0, 120.0, 230.0, 400.0, 1000.0, 10000.0 };
    public static final double[] CURRENT_PRESETS = { 1.0, 5.0, 10.0, 25.0, 50.0, 100.0, 500.0, 1000.0, 10000.0 };
    public static final double[] R_INT_PRESETS = { 0.0001, 0.001, 0.01, 0.1, 1.0, 10.0 };
    public static final double[] FREQ_PRESETS = { 0.0, 50.0, 60.0 };

    private final BlockPos pos;
    private double voltage = 230.0;
    private double maxCurrent = 1000.0;
    private double internalResistance = 0.001; // 1 mOhm
    private double frequency = 0.0; // 0 = DC, 50 = AC 50Hz, 60 = AC 60Hz
    private boolean enabled = true;

    private double lastDeliveredCurrent = 0.0;
    private double lastDeliveredPower = 0.0;
    private double totalEnergyJoules = 0.0;

    public CreativeGeneratorLogic(BlockPos pos) {
        this.pos = pos;
    }

    public double getVoltage() {
        return voltage;
    }

    public void setVoltage(double voltage) {
        this.voltage = Math.max(0.0, voltage);
    }

    public double getMaxCurrent() {
        return maxCurrent;
    }

    public void setMaxCurrent(double maxCurrent) {
        this.maxCurrent = Math.max(0.0, maxCurrent);
    }

    public void setInternalResistance(double internalResistance) {
        this.internalResistance = Math.max(1e-5, internalResistance);
    }

    public double getFrequency() {
        return frequency;
    }

    public void setFrequency(double frequency) {
        this.frequency = Math.max(0.0, frequency);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void toggleEnabled() {
        this.enabled = !this.enabled;
    }

    public double getLastDeliveredCurrent() {
        return lastDeliveredCurrent;
    }

    public double getLastDeliveredPower() {
        return lastDeliveredPower;
    }

    public double getTotalEnergyJoules() {
        return totalEnergyJoules;
    }

    public void setTotalEnergyJoules(double totalEnergyJoules) {
        this.totalEnergyJoules = totalEnergyJoules;
    }

    public void resetEnergy() {
        this.totalEnergyJoules = 0.0;
    }

    public String getFrequencyDisplay() {
        if (frequency <= 0.001) return "DC";
        return String.format("AC %.0f Hz", frequency);
    }

    public double cycleVoltage() {
        for (double preset : VOLTAGE_PRESETS) {
            if (preset > voltage + 0.1) {
                this.voltage = preset;
                return this.voltage;
            }
        }
        this.voltage = VOLTAGE_PRESETS[0];
        return this.voltage;
    }

    public double cycleCurrentLimit() {
        for (double preset : CURRENT_PRESETS) {
            if (preset > maxCurrent + 0.1) {
                this.maxCurrent = preset;
                return this.maxCurrent;
            }
        }
        this.maxCurrent = CURRENT_PRESETS[0];
        return this.maxCurrent;
    }

    public double cycleInternalResistance() {
        for (double preset : R_INT_PRESETS) {
            if (preset > internalResistance + 1e-6) {
                this.internalResistance = preset;
                return this.internalResistance;
            }
        }
        this.internalResistance = R_INT_PRESETS[0];
        return this.internalResistance;
    }

    public double cycleFrequency() {
        for (double preset : FREQ_PRESETS) {
            if (preset > frequency + 0.1) {
                this.frequency = preset;
                return this.frequency;
            }
        }
        this.frequency = FREQ_PRESETS[0];
        return this.frequency;
    }

    // ==================== Legacy grid hooks (now plain methods) ====================
    // Formerly legacy component/source overrides; kept without an interface
    // for the GUI/config path. The kernel reads staged values through the BE element.

    public BlockPos getPos() {
        return this.pos;
    }

    public ElectricalState getElectricalState() {
        return enabled ? ElectricalState.NOMINAL : ElectricalState.OFF;
    }

    public void setElectricalState(ElectricalState state) {}

    // ==================== Source ratings (now plain methods) ====================

    public double getElectromotiveForce() {
        return enabled ? voltage : 0.0;
    }

    public double getInternalResistance() {
        return internalResistance;
    }

    public double getMaxOutputCurrent() {
        return enabled ? maxCurrent : 0.0;
    }

    public void onPowerDrawn(double currentAmps, double durationSeconds) {
        this.lastDeliveredCurrent = currentAmps;
        this.lastDeliveredPower = voltage * currentAmps;
        this.totalEnergyJoules += this.lastDeliveredPower * durationSeconds;
    }
}
