package com.ostapyrih.voltcraft.simulation.conversion;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import net.minecraft.util.math.BlockPos;

/**
 * Pure simulation logic for the 230V AC to E Rotary Energy Bridge.
 * Decoupled from Minecraft BlockEntity lifecycle for unit testability and MNA grid solving.
 *
 * <p>Kernel-side: the legacy consumer grid contract is deleted. This class
 * is a plain configuration/telemetry holder (230V AC window, EU bookkeeping, thermal);
 * the kernel-side input-demand stamp lives in {@code EuConverterBlockEntity}'s
 * {@code ConverterElement} wiring.</p>
 */
public class EuConverterLogic {

    public static final double NOMINAL_VOLTAGE = 230.0;
    public static final double MIN_OPERATING_VOLTAGE = 207.0; // 230V - 10%
    public static final double MAX_OPERATING_VOLTAGE = 253.0; // 230V + 10%
    public static final double MIN_AC_FREQUENCY_HZ = 40.0;
    public static final double WATTS_PER_EU_TICK = 25.0;
    public static final long DEFAULT_CAPACITY = 10000L;
    public static final long MAX_EXTRACT_RATE = 512L;

    private final BlockPos pos;
    private long capacity = DEFAULT_CAPACITY;
    private long storedEu = 0L;
    private double euAccumulator = 0.0;
    private long totalEuGenerated = 0L;

    private double inputVoltage = 0.0;
    private double inputCurrentAmps = 0.0;
    private double inputPowerWatts = 0.0;
    private double inputFrequency = 0.0;

    private double currentEuOutputRate = 0.0;
    private double targetDemandWatts = 0.0;
    private double temperatureCelsius = 20.0;

    private boolean tripped = false;
    private ElectricalState reportedState = ElectricalState.OFF;

    public EuConverterLogic(BlockPos pos) {
        this.pos = pos;
    }

    public EuConverterLogic() {
        this(BlockPos.ORIGIN);
    }

    public BlockPos getPos() {
        return pos;
    }

    public ElectricalState getElectricalState() {
        return reportedState;
    }

    public void setElectricalState(ElectricalState state) {
        this.reportedState = state;
    }

    public double getNominalPowerDemand() {
        return targetDemandWatts;
    }

    public double getNominalVoltage() {
        return NOMINAL_VOLTAGE;
    }

    public double getMinOperatingVoltage() {
        return MIN_OPERATING_VOLTAGE;
    }

    public double getMaxOperatingVoltage() {
        return MAX_OPERATING_VOLTAGE;
    }

    public double getEquivalentResistance() {
        if (tripped || targetDemandWatts <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        double v = Math.max(1.0, inputVoltage);
        return Math.max(1e-4, (v * v) / targetDemandWatts);
    }

    public void onPowerReceived(double terminalVoltage, double deliveredCurrent, double durationSeconds) {
        onPowerReceived(terminalVoltage, deliveredCurrent, durationSeconds, inputFrequency);
    }

    public void onPowerReceived(double terminalVoltage, double deliveredCurrent, double durationSeconds, double frequencyHz) {
        inputVoltage = terminalVoltage;
        inputCurrentAmps = deliveredCurrent;
        inputPowerWatts = terminalVoltage * deliveredCurrent;
        inputFrequency = frequencyHz;

        boolean isAc = frequencyHz >= MIN_AC_FREQUENCY_HZ;
        boolean inVoltageWindow = terminalVoltage >= MIN_OPERATING_VOLTAGE && terminalVoltage <= MAX_OPERATING_VOLTAGE;

        if (terminalVoltage > MAX_OPERATING_VOLTAGE) {
            tripped = true;
            reportedState = ElectricalState.SURGE;
            euAccumulator = 0.0;
            // Shed: tripped bridge draws nothing, converts nothing, heats nothing.
            inputCurrentAmps = 0.0;
            inputPowerWatts = 0.0;
        } else if (tripped) {
            reportedState = ElectricalState.OFF;
            euAccumulator = 0.0;
            inputCurrentAmps = 0.0;
            inputPowerWatts = 0.0;
        } else if (!isAc) {
            // DC voltage or non-AC waveform: strictly reject conversion
            reportedState = ElectricalState.OFF;
            euAccumulator = 0.0;
            inputCurrentAmps = 0.0;
            inputPowerWatts = 0.0;
        } else if (!inVoltageWindow) {
            reportedState = terminalVoltage > 1.0 ? ElectricalState.BROWNOUT : ElectricalState.OFF;
            euAccumulator = 0.0;
            // Brownout shed: demand is shed (see updatePowerDemand), so book
            // zero draw instead of the full unconverted draw as heat.
            inputCurrentAmps = 0.0;
            inputPowerWatts = 0.0;
        } else {
            reportedState = ElectricalState.NOMINAL;
            // Physical Conversion: 25W continuous -> 1 E/t
            double netWatts = Math.max(0.0, inputPowerWatts);
            euAccumulator += (netWatts / WATTS_PER_EU_TICK);
            long wholeEu = (long) euAccumulator;
            if (wholeEu > 0) {
                euAccumulator -= wholeEu;
                long space = capacity - storedEu;
                long added = Math.min(wholeEu, space);
                storedEu += added;
                totalEuGenerated += added;
            }
        }
    }

    public void updatePowerDemand(double movedThisTick) {
        this.currentEuOutputRate = movedThisTick;
        long bufferDeficit = Math.max(0L, capacity - storedEu);
        double neededEu = Math.min(128.0, currentEuOutputRate + Math.min(32.0, (double) bufferDeficit));

        if (!isAcOperatingValid()) {
            this.targetDemandWatts = 0.0;
        } else {
            // 25W per EU/tick, plus 5W quiescent idle excitation power
            this.targetDemandWatts = (neededEu * WATTS_PER_EU_TICK) + 5.0;
        }
    }

    public void updateThermal(double dt) {
        double ambient = 20.0;
        // Browned-out (or otherwise non-operational) bridge converts nothing:
        // only quiescent heat, never the full unconverted draw as heat.
        double effectiveInputWatts = isAcOperatingValid() ? inputPowerWatts : 0.0;
        double lossWatts = Math.max(0.0, effectiveInputWatts - (currentEuOutputRate * WATTS_PER_EU_TICK * 0.95));
        double deltaT = Math.max(0.0, temperatureCelsius - ambient);
        double coolingWatts = 2.5 * deltaT;
        double deltaTemp = ((lossWatts - coolingWatts) / 200.0) * dt;
        this.temperatureCelsius = Math.max(ambient, this.temperatureCelsius + deltaTemp);

        // Thermal trip protection at 125°C
        if (this.temperatureCelsius >= 125.0 && !tripped) {
            this.tripped = true;
        }
    }

    public boolean isAcOperatingValid() {
        return !tripped && inputFrequency >= MIN_AC_FREQUENCY_HZ && inputVoltage >= MIN_OPERATING_VOLTAGE && inputVoltage <= MAX_OPERATING_VOLTAGE;
    }

    public boolean isTripped() {
        return tripped;
    }

    public void resetTrip() {
        this.tripped = false;
    }

    public long getStoredEu() {
        return storedEu;
    }

    public void setStoredEu(long storedEu) {
        this.storedEu = Math.max(0L, Math.min(capacity, storedEu));
    }

    public long getCapacity() {
        return capacity;
    }

    public void setCapacity(long capacity) {
        this.capacity = Math.max(1L, capacity);
    }

    public long extractEu(long maxExtract) {
        long extracted = Math.min(storedEu, Math.min(MAX_EXTRACT_RATE, maxExtract));
        storedEu -= extracted;
        return extracted;
    }

    public double getInputVoltage() {
        return inputVoltage;
    }

    public double getInputCurrentAmps() {
        return inputCurrentAmps;
    }

    public double getInputPowerWatts() {
        return inputPowerWatts;
    }

    public double getInputFrequency() {
        return inputFrequency;
    }

    public double getCurrentEuOutputRate() {
        return currentEuOutputRate;
    }

    public long getTotalEuGenerated() {
        return totalEuGenerated;
    }

    public void setTotalEuGenerated(long totalEuGenerated) {
        this.totalEuGenerated = totalEuGenerated;
    }

    public double getTemperatureCelsius() {
        return temperatureCelsius;
    }

    public void setTemperatureCelsius(double temperatureCelsius) {
        this.temperatureCelsius = temperatureCelsius;
    }

    public void setTripped(boolean tripped) {
        this.tripped = tripped;
    }

    public double getTargetDemandWatts() {
        return targetDemandWatts;
    }
}
