package com.ostapyrih.voltcraft.simulation.grid;

import com.ostapyrih.voltcraft.api.grid.IGridConductor;
import com.ostapyrih.voltcraft.simulation.solver.ThermalEquilibrium;
import net.minecraft.util.math.BlockPos;

/**
 * Concrete branch linking two adjacent grid nodes with resistance, thermal mass, and ampacity.
 */
public class GridConductor implements IGridConductor {
    private final BlockPos startPos;
    private final BlockPos endPos;
    private final ThermalEquilibrium.ThermalSpec thermalSpec;
    private final double maxAmpacity;
    private final boolean insulated;

    private double currentAmps;
    private double temperatureCelsius;
    private double effectiveResistance;

    public GridConductor(
        BlockPos startPos,
        BlockPos endPos,
        ThermalEquilibrium.ThermalSpec thermalSpec,
        double maxAmpacity,
        boolean insulated
    ) {
        this.startPos = startPos.toImmutable();
        this.endPos = endPos.toImmutable();
        this.thermalSpec = thermalSpec;
        this.maxAmpacity = maxAmpacity;
        this.insulated = insulated;
        this.temperatureCelsius = ThermalEquilibrium.T_AMBIENT;
        this.effectiveResistance = thermalSpec.baseResistance();
    }

    @Override
    public BlockPos getStartPos() {
        return startPos;
    }

    @Override
    public BlockPos getEndPos() {
        return endPos;
    }

    @Override
    public double getBaseResistance() {
        return thermalSpec.baseResistance();
    }

    @Override
    public double getEffectiveResistance() {
        return effectiveResistance;
    }

    @Override
    public double getMaxAmpacity() {
        return maxAmpacity;
    }

    @Override
    public double getCurrent() {
        return currentAmps;
    }

    @Override
    public void setCurrent(double currentAmps) {
        this.currentAmps = currentAmps;
    }

    @Override
    public double getTemperature() {
        return temperatureCelsius;
    }

    @Override
    public void setTemperature(double tempCelsius) {
        this.temperatureCelsius = tempCelsius;
    }

    @Override
    public double getMaxInsulationTemp() {
        return thermalSpec.maxInsulationTemp();
    }

    @Override
    public double getMeltingTemp() {
        return thermalSpec.meltingTemp();
    }

    @Override
    public boolean isInsulated() {
        return insulated;
    }

    public ThermalEquilibrium.ThermalSpec getThermalSpec() {
        return thermalSpec;
    }

    public void updateThermal(double ambientTemp, double dtSeconds) {
        ThermalEquilibrium.StepResult result = ThermalEquilibrium.step(
            this.temperatureCelsius,
            this.currentAmps,
            ambientTemp,
            dtSeconds,
            this.thermalSpec
        );
        this.temperatureCelsius = result.newTemperature();
        this.effectiveResistance = result.newResistance();
    }
}
