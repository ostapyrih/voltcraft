package com.ostapyrih.voltcraft.block.entity.generation;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.energy.IElectricSource;
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

/**
 * BlockEntity for Photovoltaic Solar Panels.
 * Exposes solar-generated DC EMF and dynamic current into the electrical grid.
 */
public class SolarPanelBlockEntity extends BlockEntity implements IElectricSource {

    private final SolarPanelType panelType;
    private double currentIrradiance = 0.0;
    private double electromotiveForce = 0.0;
    private double internalResistance = 1.0;
    private double maxOutputCurrent = 0.0;
    private double peakPowerAvailable = 0.0;
    private double lastDrawnCurrent = 0.0;
    private double totalEnergyGeneratedJoules = 0.0;

    public SolarPanelBlockEntity(BlockPos pos, BlockState state, SolarPanelType panelType) {
        super(VoltcraftBlockEntityTypes.SOLAR_PANEL_BLOCK_ENTITY, pos, state);
        this.panelType = panelType;
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

    public double getLastDrawnCurrent() {
        return lastDrawnCurrent;
    }

    public double getTotalEnergyGeneratedJoules() {
        return totalEnergyGeneratedJoules;
    }

    /**
     * Recomputes irradiance and panel characteristics. Grid participation is handled
     * centrally by {@code ElectricalGrid.refreshParticipants} — no registration here.
     */
    public void tick(ServerWorld world) {
        // 1. Calculate celestial solar irradiance and electrical characteristics
        this.currentIrradiance = SolarIrradianceSimulation.calculateIrradiance(world, pos, panelType);
        SolarIrradianceSimulation.SolarOutput output = SolarIrradianceSimulation.computeSolarOutput(
            panelType, currentIrradiance, 20.0
        );

        this.electromotiveForce = output.electromotiveForce();
        this.internalResistance = output.internalResistanceOhms();
        this.maxOutputCurrent = output.maxCurrentAmps();
        this.peakPowerAvailable = output.peakPowerAvailableWatts();
    }

    // ==================== IElectricComponent ====================\

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public ElectricalState getElectricalState() {
        return currentIrradiance > 5.0 ? ElectricalState.NOMINAL : ElectricalState.OFF;
    }

    @Override
    public void setElectricalState(ElectricalState state) {}

    // ==================== IElectricSource ====================\

    @Override
    public double getElectromotiveForce() {
        return electromotiveForce;
    }

    @Override
    public double getInternalResistance() {
        return internalResistance;
    }

    @Override
    public double getMaxOutputCurrent() {
        return maxOutputCurrent;
    }

    @Override
    public double getAvailableOutputCurrent() {
        return maxOutputCurrent;
    }

    @Override
    public double getFrequency() {
        return 0.0; // Solar PV is purely DC
    }

    @Override
    public void onPowerDrawn(double currentAmps, double durationSeconds) {
        this.lastDrawnCurrent = currentAmps;
        double powerWatts = electromotiveForce * currentAmps;
        this.totalEnergyGeneratedJoules += powerWatts * durationSeconds;
    }

    // ==================== Serialization ====================\

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        this.totalEnergyGeneratedJoules = view.getDouble("total_energy_generated", 0.0);
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        view.putDouble("total_energy_generated", this.totalEnergyGeneratedJoules);
    }
}
