package com.ostapyrih.voltcraft.block.entity.generation;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.energy.IElectricSource;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.block.generation.PortableGeneratorBlock;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalGrid;
import com.ostapyrih.voltcraft.simulation.grid.GridManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * BlockEntity for the 1.8-2.2 kW Portable Inverter Generator.
 * Provides 230V 50Hz pure sine AC power with load-dependent eco-throttle fuel consumption.
 */
public class PortableGeneratorBlockEntity extends BlockEntity implements IElectricSource {

    public static final double RATED_POWER_WATTS = 1800.0;
    public static final double SURGE_POWER_WATTS = 2200.0;
    public static final double OUTPUT_VOLTAGE_RMS = 230.0;

    private double remainingFuelTicks = 0.0;
    private double lastDeliveredCurrentAmps = 0.0;
    private double lastDeliveredPowerWatts = 0.0;
    private double totalEnergyJoules = 0.0;

    private UUID lastGridId = null;

    public PortableGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(VoltcraftBlockEntityTypes.PORTABLE_GENERATOR_BLOCK_ENTITY, pos, state);
    }

    public void addFuel(int ticks) {
        this.remainingFuelTicks += ticks;
        markDirty();
    }

    public double getRemainingFuelTicks() {
        return remainingFuelTicks;
    }

    public boolean isRunning() {
        return remainingFuelTicks > 0.0;
    }

    public double getLastDeliveredPowerWatts() {
        return lastDeliveredPowerWatts;
    }

    public double getTotalEnergyJoules() {
        return totalEnergyJoules;
    }

    public Direction getOutputFacing() {
        BlockState state = getCachedState();
        if (state.contains(PortableGeneratorBlock.FACING)) {
            return state.get(PortableGeneratorBlock.FACING);
        }
        return Direction.NORTH;
    }

    public BlockPos getOutputPos() {
        return pos.offset(getOutputFacing());
    }

    public void tick(ServerWorld world) {
        boolean wasRunning = getCachedState().get(PortableGeneratorBlock.RUNNING);
        boolean isRunning = remainingFuelTicks > 0.0;

        // 1. Eco-throttle fuel consumption
        if (isRunning) {
            double loadRatio = Math.min(1.0, lastDeliveredPowerWatts / RATED_POWER_WATTS);
            // Idle consumes 0.25x fuel rate, 100% rated load consumes 1.0x fuel rate
            double burnRate = 0.25 + (0.75 * loadRatio);
            this.remainingFuelTicks = Math.max(0.0, this.remainingFuelTicks - burnRate);
            if (this.remainingFuelTicks <= 0.0) {
                isRunning = false;
            }
        } else {
            this.lastDeliveredCurrentAmps = 0.0;
            this.lastDeliveredPowerWatts = 0.0;
        }

        // Sync blockstate RUNNING property
        if (wasRunning != isRunning) {
            world.setBlockState(pos, getCachedState().with(PortableGeneratorBlock.RUNNING, isRunning), 3);
        }

        // 2. Grid attachment synchronization at the front output socket
        BlockPos outPos = getOutputPos();
        GridManager gridManager = GridManager.get(world);
        ElectricalGrid currentGrid = gridManager.getGridAt(outPos);

        UUID currentGridId = currentGrid != null ? currentGrid.getGridId() : null;

        if (!Objects.equals(currentGridId, lastGridId)) {
            if (lastGridId != null) {
                for (ElectricalGrid g : gridManager.getAllGrids()) {
                    if (g.getGridId().equals(lastGridId)) {
                        g.unregisterSource(outPos, this);
                        break;
                    }
                }
            }
            if (currentGrid != null && isRunning) {
                currentGrid.registerSource(outPos, this);
            }
            lastGridId = currentGridId;
        } else if (currentGrid != null) {
            List<IElectricSource> registered = currentGrid.getSources().get(outPos);
            if (isRunning) {
                if (registered == null || !registered.contains(this)) {
                    currentGrid.registerSource(outPos, this);
                }
            } else if (registered != null && registered.contains(this)) {
                currentGrid.unregisterSource(outPos, this);
            }
        }
    }

    @Override
    public void markRemoved() {
        super.markRemoved();
        if (world instanceof ServerWorld sw) {
            GridManager gm = GridManager.get(sw);
            BlockPos outPos = getOutputPos();
            for (ElectricalGrid g : gm.getAllGrids()) {
                g.unregisterSource(outPos, this);
            }
            ElectricalGrid grid = gm.getGridAt(outPos);
            if (grid != null) {
                grid.unregisterSource(outPos, this);
            }
        }
    }

    // ==================== IElectricComponent ====================

    @Override
    public BlockPos getPos() {
        return getOutputPos();
    }

    @Override
    public ElectricalState getElectricalState() {
        return isRunning() ? ElectricalState.NOMINAL : ElectricalState.OFF;
    }

    @Override
    public void setElectricalState(ElectricalState state) {}

    // ==================== IElectricSource ====================

    @Override
    public double getElectromotiveForce() {
        return isRunning() ? OUTPUT_VOLTAGE_RMS : 0.0;
    }

    @Override
    public double getInternalResistance() {
        return 0.15; // 150 mOhm inverter bridge internal resistance
    }

    @Override
    public double getMaxOutputCurrent() {
        return SURGE_POWER_WATTS / OUTPUT_VOLTAGE_RMS; // ~9.56 A surge limit
    }

    @Override
    public double getFrequency() {
        return 50.0; // 50 Hz Pure Sine Wave AC
    }

    @Override
    public void onPowerDrawn(double currentAmps, double durationSeconds) {
        this.lastDeliveredCurrentAmps = currentAmps;
        this.lastDeliveredPowerWatts = OUTPUT_VOLTAGE_RMS * currentAmps;
        this.totalEnergyJoules += lastDeliveredPowerWatts * durationSeconds;
    }

    // ==================== Serialization ====================

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        this.remainingFuelTicks = view.getDouble("remaining_fuel_ticks", 0.0);
        this.totalEnergyJoules = view.getDouble("total_energy_joules", 0.0);
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        view.putDouble("remaining_fuel_ticks", this.remainingFuelTicks);
        view.putDouble("total_energy_joules", this.totalEnergyJoules);
    }
}
