package com.ostapyrih.voltcraft.block.entity.generation;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.energy.IElectricSource;
import com.ostapyrih.voltcraft.api.grid.IGridTopologyListener;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
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
 * BlockEntity for the 100W Hand-Crank Dynamo.
 * Converts mechanical flywheel inertia into 12V DC power.
 */
public class HandCrankGeneratorBlockEntity extends BlockEntity implements IElectricSource, IGridTopologyListener {

    private double flywheelSpeed = 0.0; // 0.0 to 1.0 normalized rotational speed
    private double electromotiveForce = 0.0;
    private double lastDrawnCurrent = 0.0;
    private double totalEnergyJoules = 0.0;

    private UUID lastGridId = null;

    public HandCrankGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(VoltcraftBlockEntityTypes.HAND_CRANK_GENERATOR_BLOCK_ENTITY, pos, state);
    }

    /**
     * Called when a player right-clicks the dynamo.
     */
    public void crank() {
        this.flywheelSpeed = Math.min(1.0, this.flywheelSpeed + 0.35);
        markDirty();
    }

    public double getFlywheelSpeed() {
        return flywheelSpeed;
    }

    public double getLastDrawnCurrent() {
        return lastDrawnCurrent;
    }

    public double getTotalEnergyJoules() {
        return totalEnergyJoules;
    }

    public void tick(ServerWorld world) {
        // 1. Mechanical flywheel decay & electrical generation
        if (flywheelSpeed > 0.001) {
            // Voltage proportional to angular velocity: up to 13.8V open-circuit (12V nominal)
            this.electromotiveForce = flywheelSpeed * 13.8;

            // Mechanical friction decay (0.97 per tick = ~2 seconds spindown from 1.0)
            double mechanicalDecay = 0.97;
            this.flywheelSpeed *= mechanicalDecay;
        } else {
            this.flywheelSpeed = 0.0;
            this.electromotiveForce = 0.0;
        }

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

    // ==================== IElectricComponent ====================

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public ElectricalState getElectricalState() {
        return flywheelSpeed > 0.05 ? ElectricalState.NOMINAL : ElectricalState.OFF;
    }

    @Override
    public void setElectricalState(ElectricalState state) {}

    // ==================== IElectricSource ====================

    @Override
    public double getElectromotiveForce() {
        return electromotiveForce;
    }

    @Override
    public double getInternalResistance() {
        return 0.15; // 150 mOhm internal winding resistance
    }

    @Override
    public double getMaxOutputCurrent() {
        return 8.33; // 8.33A rating (100W at 12V)
    }

    @Override
    public double getFrequency() {
        return 0.0; // DC output
    }

    @Override
    public void onPowerDrawn(double currentAmps, double durationSeconds) {
        this.lastDrawnCurrent = currentAmps;
        double powerW = electromotiveForce * currentAmps;
        this.totalEnergyJoules += powerW * durationSeconds;

        // Electromagnetic counter-torque (back-EMF damping slows the flywheel faster under load)
        double backEmfDamping = (powerW / 100.0) * 0.05;
        this.flywheelSpeed = Math.max(0.0, this.flywheelSpeed - backEmfDamping);
    }

    @Override
    public void onGridTopologyChanged() {
        this.lastGridId = null;
    }

    // ==================== Serialization ====================

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        this.flywheelSpeed = view.getDouble("flywheel_speed", 0.0);
        this.totalEnergyJoules = view.getDouble("total_energy_joules", 0.0);
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        view.putDouble("flywheel_speed", this.flywheelSpeed);
        view.putDouble("total_energy_joules", this.totalEnergyJoules);
    }
}
