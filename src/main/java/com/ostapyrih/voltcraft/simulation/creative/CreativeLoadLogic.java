package com.ostapyrih.voltcraft.simulation.creative;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import net.minecraft.util.math.BlockPos;

/**
 * Pure simulation logic for the Creative Load block.
 * Decoupled from Minecraft BlockEntity lifecycle for unit testability and MNA grid solving.
 *
 * <p>Phase E: the legacy consumer grid contract is deleted. This class
 * is a plain configuration/telemetry holder (mode/target presets plus consumed-energy
 * bookkeeping); the kernel-side stamp lives in
 * {@code CreativeLoadBlockEntity.CreativeLoadElement}.</p>
 */
public class CreativeLoadLogic {

    public enum LoadMode {
        CONSTANT_RESISTANCE("Constant Resistance (Ω)"),
        CONSTANT_POWER("Constant Power (W)"),
        CONSTANT_CURRENT("Constant Current (A)");

        private final String displayName;

        LoadMode(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public static final double[] RESISTANCE_PRESETS = { 0.5, 1.0, 2.0, 5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0 };
    public static final double[] POWER_PRESETS = { 10.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0, 50000.0 };
    public static final double[] CURRENT_PRESETS = { 0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 16.0, 25.0, 32.0, 50.0, 100.0 };

    private final BlockPos pos;
    private LoadMode mode = LoadMode.CONSTANT_RESISTANCE;
    private double targetValue = 10.0;
    private boolean enabled = true;

    private double lastMeasuredVoltage = 0.0;
    private double lastDeliveredCurrent = 0.0;
    private double lastDeliveredPower = 0.0;
    private double totalEnergyConsumedJoules = 0.0;

    public CreativeLoadLogic(BlockPos pos) {
        this.pos = pos;
    }

    public LoadMode getMode() {
        return mode;
    }

    public void setMode(LoadMode mode) {
        this.mode = mode;
    }

    public double getTargetValue() {
        return targetValue;
    }

    public void setTargetValue(double targetValue) {
        this.targetValue = Math.max(1e-4, targetValue);
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

    public double getLastMeasuredVoltage() {
        return lastMeasuredVoltage;
    }

    public double getLastDeliveredCurrent() {
        return lastDeliveredCurrent;
    }

    public double getLastDeliveredPower() {
        return lastDeliveredPower;
    }

    public double getTotalEnergyConsumedJoules() {
        return totalEnergyConsumedJoules;
    }

    public void setTotalEnergyConsumedJoules(double totalEnergyConsumedJoules) {
        this.totalEnergyConsumedJoules = totalEnergyConsumedJoules;
    }

    public void resetEnergy() {
        this.totalEnergyConsumedJoules = 0.0;
    }

    public String getTargetDisplay() {
        return switch (mode) {
            case CONSTANT_RESISTANCE -> String.format("%.2f Ω", targetValue);
            case CONSTANT_POWER -> String.format("%.1f W", targetValue);
            case CONSTANT_CURRENT -> String.format("%.2f A", targetValue);
        };
    }

    public LoadMode cycleMode() {
        LoadMode[] modes = LoadMode.values();
        this.mode = modes[(mode.ordinal() + 1) % modes.length];
        switch (mode) {
            case CONSTANT_RESISTANCE -> this.targetValue = 10.0;
            case CONSTANT_POWER -> this.targetValue = 1000.0;
            case CONSTANT_CURRENT -> this.targetValue = 10.0;
        }
        return this.mode;
    }

    public double cycleTargetValue() {
        double[] presets = switch (mode) {
            case CONSTANT_RESISTANCE -> RESISTANCE_PRESETS;
            case CONSTANT_POWER -> POWER_PRESETS;
            case CONSTANT_CURRENT -> CURRENT_PRESETS;
        };

        for (double preset : presets) {
            if (preset > targetValue + 1e-4) {
                this.targetValue = preset;
                return this.targetValue;
            }
        }
        this.targetValue = presets[0];
        return this.targetValue;
    }

    // ==================== Legacy grid hooks (now plain methods) ====================
    // Formerly legacy component/consumer overrides; kept without an interface
    // for the GUI/config path. The kernel reads staged values through the BE element.

    public BlockPos getPos() {
        return this.pos;
    }

    public ElectricalState getElectricalState() {
        return enabled ? ElectricalState.NOMINAL : ElectricalState.OFF;
    }

    public void setElectricalState(ElectricalState state) {}

    // ==================== Load ratings (now plain methods) ====================

    public double getNominalPowerDemand() {
        if (!enabled) return 0.0;
        return switch (mode) {
            case CONSTANT_POWER -> targetValue;
            case CONSTANT_RESISTANCE -> {
                double v = lastMeasuredVoltage > 0.5 ? lastMeasuredVoltage : 230.0;
                yield (v * v) / Math.max(1e-4, targetValue);
            }
            case CONSTANT_CURRENT -> {
                double v = lastMeasuredVoltage > 0.5 ? lastMeasuredVoltage : 230.0;
                yield v * targetValue;
            }
        };
    }

    public double getNominalVoltage() {
        return lastMeasuredVoltage > 0.5 ? lastMeasuredVoltage : 230.0;
    }

    public double getMinOperatingVoltage() {
        return 0.0;
    }

    public double getMaxOperatingVoltage() {
        return 1_000_000.0;
    }

    public double getEquivalentResistance() {
        if (!enabled) {
            return Double.POSITIVE_INFINITY;
        }
        return switch (mode) {
            case CONSTANT_RESISTANCE -> Math.max(1e-4, targetValue);
            case CONSTANT_POWER -> {
                double p = Math.max(0.1, targetValue);
                double v = lastMeasuredVoltage > 0.5 ? lastMeasuredVoltage : 230.0;
                yield Math.max(1e-4, (v * v) / p);
            }
            case CONSTANT_CURRENT -> {
                double i = Math.max(1e-3, targetValue);
                double v = lastMeasuredVoltage > 0.5 ? lastMeasuredVoltage : 230.0;
                yield Math.max(1e-4, v / i);
            }
        };
    }

    public void onPowerReceived(double terminalVoltage, double deliveredCurrent, double durationSeconds) {
        this.lastMeasuredVoltage = terminalVoltage;
        this.lastDeliveredCurrent = deliveredCurrent;
        this.lastDeliveredPower = terminalVoltage * deliveredCurrent;
        this.totalEnergyConsumedJoules += this.lastDeliveredPower * durationSeconds;
    }
}
