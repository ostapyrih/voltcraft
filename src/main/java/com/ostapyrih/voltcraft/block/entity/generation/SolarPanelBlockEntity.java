package com.ostapyrih.voltcraft.block.entity.generation;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.energy.IElectricSource;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.block.generation.SolarPanelBlock;
import com.ostapyrih.voltcraft.simulation.generation.SolarIrradianceSimulation;
import com.ostapyrih.voltcraft.simulation.generation.SolarPanelType;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalGrid;
import com.ostapyrih.voltcraft.simulation.grid.GridManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

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

    private UUID lastGridId = null;

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

        // 2. Grid network registration
        GridManager gridManager = GridManager.get(world);
        ElectricalGrid currentGrid = gridManager.getGridAt(pos);

        if (currentGrid == null) {
            gridManager.onConductorPlaced(world, pos, ConductorType.INSULATED_COPPER);
            currentGrid = gridManager.getGridAt(pos);
        }

        UUID currentGridId = currentGrid != null ? currentGrid.getGridId() : null;

        if (!Objects.equals(currentGridId, lastGridId)) {
            if (lastGridId != null) {
                for (ElectricalGrid g : gridManager.getAllGrids()) {
                    if (g.getGridId().equals(lastGridId)) {
                        g.unregisterSource(pos, this);
                        break;
                    }
                }
            }
            if (currentGrid != null) {
                currentGrid.registerSource(pos, this);
            }
            lastGridId = currentGridId;
        } else if (currentGrid != null) {
            List<IElectricSource> registered = currentGrid.getSources().get(pos);
            if (registered == null || !registered.contains(this)) {
                currentGrid.registerSource(pos, this);
            }
        }

        if (currentGrid == null) {
            this.lastDrawnCurrent = 0.0;
        }
    }

    @Override
    public void markRemoved() {
        super.markRemoved();
        if (world instanceof ServerWorld sw) {
            GridManager gm = GridManager.get(sw);
            for (ElectricalGrid g : gm.getAllGrids()) {
                g.unregisterSource(pos, this);
            }
            ElectricalGrid grid = gm.getGridAt(pos);
            if (grid != null) {
                grid.unregisterSource(pos, this);
            }
        }
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
