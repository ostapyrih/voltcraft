package com.ostapyrih.voltcraft.block.entity.generation;

import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
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
import net.minecraft.util.math.Direction;

import com.ostapyrih.voltcraft.simulation.electrical.SolarElement;

/**
 * Photovoltaic solar panel kernel adapter (DC).
 * Daytime stages EMF/resistance from sky irradiance and stamps a Thevenin source;
 * night (EMF 0) stamps open circuit. Kernel state is {@code [temperatureC]}.
 */
public class SolarPanelBlockEntity extends BlockEntity implements KernelAttachedBlock {



    private final SolarPanelType panelType;

    private final double[] stateArray = SolarElement.newStateArray();
    private final double[] telemetryCell = new double[2];

    // Discrete staging inputs: set by tickElectrical, read by the stamp.
    private double currentIrradiance = 0.0;
    private double electromotiveForce = 0.0;
    private double internalResistance = 1.0;
    private double maxOutputCurrent = 0.0;
    private double peakPowerAvailable = 0.0;
    private double totalEnergyGeneratedJoules = 0.0;

    private final ElectricalElement element;

    public SolarPanelBlockEntity(BlockPos pos, BlockState state, SolarPanelType panelType) {
        super(VoltcraftBlockEntityTypes.SOLAR_PANEL_BLOCK_ENTITY, pos, state);
        this.panelType = panelType;
        this.element = new SolarElement(() -> electromotiveForce, () -> internalResistance,
            () -> currentIrradiance, telemetryCell);
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
        return telemetryCell[SolarElement.TELE_I];
    }

    public double getTotalEnergyGeneratedJoules() {
        return totalEnergyGeneratedJoules;
    }

    public double getElectromotiveForce() {
        return electromotiveForce;
    }

    public double getInternalResistance() {
        return internalResistance;
    }

    public double getMaxOutputCurrent() {
        return maxOutputCurrent;
    }

    public void tick(ServerWorld world) {
        tickElectrical(world);
    }

    @Override
    public ElectricalElement getElement() {
        return element;
    }

    @Override
    public BlockPos[] getTerminalPositions() {
        return SolarElement.resolveTerminals(pos, readFacing());
    }

    private Direction readFacing() {
        try {
            BlockState cached = getCachedState();
            if (cached != null && cached.contains(SolarPanelBlock.FACING)) {
                Direction facing = cached.get(SolarPanelBlock.FACING);
                if (facing != null) {
                    return facing;
                }
            }
        } catch (Exception ignored) {
        }
        return Direction.NORTH;
    }

    @Override
    public double[] getStateArray() {
        return SolarElement.snapshotState(stateArray);
    }

    @Override
    public void setStateArray(double[] state) {
        SolarElement.assignState(stateArray, state);
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public boolean isActiveSource() {
        return SolarElement.isActiveSource(electromotiveForce);
    }

    @Override
    public boolean isACSource() {
        return false;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        if (world != null) {
            this.currentIrradiance = SolarIrradianceSimulation.calculateIrradiance(world, pos, panelType);
            SolarIrradianceSimulation.SolarOutput output = SolarIrradianceSimulation.computeSolarOutput(
                panelType, currentIrradiance, 20.0
            );
            this.electromotiveForce = output.electromotiveForce();
            this.internalResistance = output.internalResistanceOhms();
            this.maxOutputCurrent = output.maxCurrentAmps();
            this.peakPowerAvailable = output.peakPowerAvailableWatts();
        }
        double v = telemetryCell[SolarElement.TELE_V];
        double i = telemetryCell[SolarElement.TELE_I];
        if (v > 0.0 && i > 0.0) {
            this.totalEnergyGeneratedJoules += v * i * GridConstants.DT;
        }
    }

    public void writeStateData(WriteView view) {
        SolarElement.writeNbt(view, stateArray[SolarElement.STATE_TEMP], totalEnergyGeneratedJoules);
    }

    public void readStateData(ReadView view) {
        SolarElement.assignState(stateArray, SolarElement.readNbtState(view));
        this.totalEnergyGeneratedJoules = SolarElement.readNbtTotalEnergy(view);
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        writeStateData(view);
    }

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        readStateData(view);
    }
}
