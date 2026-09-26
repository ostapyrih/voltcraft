package com.ostapyrih.voltcraft.block.entity.storage;

import com.ostapyrih.voltcraft.api.data.BatteryCellSpec;
import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.energy.IElectricStorage;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.block.storage.BatteryBlock;
import com.ostapyrih.voltcraft.simulation.chemistry.BatteryChemistry;
import com.ostapyrih.voltcraft.simulation.chemistry.BatterySimulation;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalGrid;
import com.ostapyrih.voltcraft.simulation.grid.GridManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Stationary electrochemical Battery Energy Storage System (BESS) block entity.
 * Directly integrates into the ElectricalGrid as an active IElectricStorage node.
 */
public class BatteryBlockEntity extends BlockEntity implements IElectricStorage {

    private final BatteryChemistry chemistry;
    private final int seriesCount;
    private final int parallelCount;

    private double stateOfCharge = 1.0;
    private double stateOfHealth = 1.0;
    private double temperatureCelsius = 20.0;
    private ElectricalState electricalState = ElectricalState.NOMINAL;
    private UUID lastGridId = null;

    public BatteryBlockEntity(
        BlockEntityType<?> type,
        BlockPos pos,
        BlockState state,
        BatteryChemistry chemistry,
        int seriesCount,
        int parallelCount
    ) {
        super(type, pos, state);
        this.chemistry = chemistry;
        this.seriesCount = Math.max(1, seriesCount);
        this.parallelCount = Math.max(1, parallelCount);
    }

    public BatteryBlockEntity(BlockPos pos, BlockState state, BatteryChemistry chemistry, int seriesCount, int parallelCount) {
        this(VoltcraftBlockEntityTypes.BATTERY_BLOCK_ENTITY, pos, state, chemistry, seriesCount, parallelCount);
    }

    public BatteryBlockEntity(BlockPos pos, BlockState state) {
        this(
            VoltcraftBlockEntityTypes.BATTERY_BLOCK_ENTITY,
            pos,
            state,
            state.getBlock() instanceof BatteryBlock bb ? bb.getChemistry() : BatteryChemistry.LIFEPO4,
            state.getBlock() instanceof BatteryBlock bb ? bb.getSeriesCount() : 15,
            state.getBlock() instanceof BatteryBlock bb ? bb.getParallelCount() : 1
        );
    }

    public BatteryChemistry getChemistry() {
        return chemistry;
    }

    public int getSeriesCount() {
        return seriesCount;
    }

    public int getParallelCount() {
        return parallelCount;
    }

    public double getTemperatureCelsius() {
        return temperatureCelsius;
    }

    @Override
    public void markRemoved() {
        super.markRemoved();
        onRemovedFromWorld();
    }

    public void tick(ServerWorld world) {
        GridManager gridManager = GridManager.get(world);
        ElectricalGrid currentGrid = gridManager.getGridAt(pos);

        // Self-heal / seed grid if missing on chunk/world load
        if (currentGrid == null) {
            gridManager.onConductorPlaced(world, pos, com.ostapyrih.voltcraft.block.cable.ConductorType.HEAVY_COPPER);
            currentGrid = gridManager.getGridAt(pos);
        }

        UUID currentGridId = currentGrid != null ? currentGrid.getGridId() : null;

        if (!Objects.equals(currentGridId, lastGridId)) {
            if (lastGridId != null) {
                for (ElectricalGrid g : gridManager.getAllGrids()) {
                    if (g.getGridId().equals(lastGridId)) {
                        g.unregisterSource(pos, this);
                        g.unregisterConsumer(pos, this);
                        break;
                    }
                }
            }
            if (currentGrid != null) {
                currentGrid.registerSource(pos, this);
            }
            lastGridId = currentGridId;
        } else if (currentGrid != null) {
            List<com.ostapyrih.voltcraft.api.energy.IElectricSource> registered = currentGrid.getSources().get(pos);
            if (registered == null || !registered.contains(this)) {
                currentGrid.registerSource(pos, this);
            }
        }
    }

    public void onRemovedFromWorld() {
        if (world instanceof ServerWorld sw) {
            GridManager gm = GridManager.get(sw);
            for (ElectricalGrid g : gm.getAllGrids()) {
                g.unregisterSource(pos, this);
                g.unregisterConsumer(pos, this);
            }
            ElectricalGrid grid = gm.getGridAt(pos);
            if (grid != null) {
                grid.unregisterSource(pos, this);
                grid.unregisterConsumer(pos, this);
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
        return electricalState;
    }

    @Override
    public void setElectricalState(ElectricalState state) {
        this.electricalState = state;
    }

    // ==================== IElectricStorage ====================

    @Override
    public double getStateOfCharge() {
        return stateOfCharge;
    }

    @Override
    public double getStateOfHealth() {
        return stateOfHealth * 100.0;
    }

    @Override
    public double getMaxStorageJoules() {
        double totalCapacityAh = chemistry.getCapacityAmpHours() * parallelCount;
        double packNominalV = chemistry.getNominalVoltage() * seriesCount;
        return totalCapacityAh * packNominalV * 3600.0;
    }

    @Override
    public double getStoredJoules() {
        return getMaxStorageJoules() * stateOfCharge;
    }

    @Override
    public BatteryCellSpec getChemistrySpec() {
        return new BatteryCellSpec(
            chemistry.getDisplayName(),
            chemistry.getNominalVoltage(),
            chemistry.getCutoffVoltage(),
            chemistry.getFullChargeVoltage(),
            chemistry.getCapacityMilliAmpHours(),
            chemistry.getMaxDischargeCRate(),
            chemistry.getInternalResistanceOhms(),
            chemistry.isRechargeable()
        );
    }

    @Override
    public void addEnergy(double joules) {
        double maxJ = getMaxStorageJoules();
        if (maxJ > 0.0) {
            this.stateOfCharge = Math.min(1.0, this.stateOfCharge + (joules / maxJ));
            markDirty();
        }
    }

    @Override
    public double extractEnergy(double joules) {
        double currentJ = getStoredJoules();
        double delivered = Math.min(joules, currentJ);
        double maxJ = getMaxStorageJoules();
        if (maxJ > 0.0) {
            this.stateOfCharge = Math.max(0.0, this.stateOfCharge - (delivered / maxJ));
            markDirty();
        }
        return delivered;
    }

    // ==================== IElectricSource ====================

    @Override
    public double getElectromotiveForce() {
        if (electricalState == ElectricalState.DESTROYED || stateOfCharge <= 0.001) return 0.0;
        return seriesCount * BatterySimulation.getOpenCircuitVoltage(chemistry, stateOfCharge);
    }

    @Override
    public double getInternalResistance() {
        double cellR = BatterySimulation.getInternalResistance(chemistry, stateOfCharge, temperatureCelsius, stateOfHealth);
        return (cellR * seriesCount) / (double) parallelCount;
    }

    @Override
    public double getMaxOutputCurrent() {
        if (electricalState == ElectricalState.DESTROYED || stateOfCharge <= 0.001) return 0.0;
        return parallelCount * chemistry.getMaxDischargeCurrentAmps();
    }

    @Override
    public double getFrequency() {
        return 0.0; // Batteries are strictly direct current (DC)
    }

    @Override
    public void onPowerDrawn(double currentAmps, double durationSeconds) {
        if (currentAmps <= 0.0 || stateOfCharge <= 0.001) {
            // Passive ambient cooling when idle / unloaded / discharged
            if (temperatureCelsius > 20.0) {
                double cooling = 0.5 * (temperatureCelsius - 20.0);
                this.temperatureCelsius = Math.max(20.0, this.temperatureCelsius - (cooling * durationSeconds));
                markDirty();
            }
            return;
        }

        double cellCurrent = currentAmps / (double) parallelCount;
        BatterySimulation.SimulationStepResult result = BatterySimulation.step(
            chemistry,
            cellCurrent,
            durationSeconds,
            stateOfCharge,
            temperatureCelsius,
            stateOfHealth,
            20.0
        );

        this.stateOfCharge = result.newSoc();
        this.temperatureCelsius = result.newTemperatureCelsius();
        this.stateOfHealth = result.newHealth();

        if (result.thermalRunaway() && world != null && !world.isClient()) {
            this.electricalState = ElectricalState.DESTROYED;
            if (world instanceof ServerWorld sw) {
                sw.getServer().execute(() -> {
                    sw.breakBlock(pos, false);
                    sw.createExplosion(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 3.0f, net.minecraft.world.World.ExplosionSourceType.BLOCK);
                });
            }
        }
        markDirty();
    }

    // ==================== IElectricConsumer ====================

    @Override
    public double getNominalPowerDemand() {
        // Commanded charging power when state of charge < 1.0 (0.5C rate)
        if (stateOfCharge >= 0.999 || !chemistry.isRechargeable()) return 0.0;
        double packV = seriesCount * chemistry.getNominalVoltage();
        double chargeI = parallelCount * chemistry.getCapacityAmpHours() * 0.5;
        return packV * chargeI;
    }

    @Override
    public double getNominalVoltage() {
        return seriesCount * chemistry.getNominalVoltage();
    }

    @Override
    public double getMinOperatingVoltage() {
        return seriesCount * chemistry.getCutoffVoltage();
    }

    @Override
    public double getMaxOperatingVoltage() {
        return seriesCount * chemistry.getFullChargeVoltage() * 1.05;
    }

    @Override
    public void onPowerReceived(double terminalVoltage, double deliveredCurrent, double durationSeconds) {
        onPowerReceived(terminalVoltage, deliveredCurrent, durationSeconds, 0.0);
    }

    @Override
    public void onPowerReceived(double terminalVoltage, double deliveredCurrent, double durationSeconds, double frequencyHz) {
        if (deliveredCurrent <= 0.0 || !chemistry.isRechargeable() || electricalState == ElectricalState.DESTROYED) return;

        if (frequencyHz > 0.001) {
            // Unrectified AC applied across DC battery cells:
            // Symmetrical AC yields 0 net chemical charge transfer (Delta SoC = 0)
            // But dissipates full RMS Joule heating across internal resistance
            double cellCurrent = deliveredCurrent / (double) parallelCount;
            double cellR = BatterySimulation.getInternalResistance(chemistry, stateOfCharge, temperatureCelsius, stateOfHealth);
            double heatingWatts = (cellCurrent * cellCurrent * cellR) * seriesCount * parallelCount;
            double cellThermalMass = chemistry.getCapacityAmpHours() <= 5.0 ? 40.0 : (chemistry.getCapacityAmpHours() * 20.0);
            double totalThermalMass = Math.max(10.0, cellThermalMass * seriesCount * parallelCount);

            this.temperatureCelsius += (heatingWatts / totalThermalMass) * durationSeconds;
            this.stateOfHealth = Math.max(0.0, this.stateOfHealth - (0.01 * durationSeconds));

            if (this.temperatureCelsius >= chemistry.getThermalRunawayTempCelsius() && world != null && !world.isClient()) {
                this.electricalState = ElectricalState.DESTROYED;
                if (world instanceof ServerWorld sw) {
                    sw.getServer().execute(() -> {
                        sw.breakBlock(pos, false);
                        sw.createExplosion(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 3.5f, net.minecraft.world.World.ExplosionSourceType.BLOCK);
                    });
                }
            } else {
                this.electricalState = ElectricalState.SURGE;
            }

            markDirty();
            return; // Strict realism: ZERO charge gained from AC
        }

        // Applied voltage must strictly exceed battery EMF to drive charging current
        if (terminalVoltage <= getElectromotiveForce()) {
            return;
        }

        // Overvoltage hazard check
        if (terminalVoltage > getMaxOperatingVoltage() * 1.05) {
            double overvoltage = terminalVoltage - getMaxOperatingVoltage();
            double cellOvervoltage = overvoltage / (double) seriesCount;
            double cellCurrent = deliveredCurrent / (double) parallelCount;
            double cellR = BatterySimulation.getInternalResistance(chemistry, stateOfCharge, temperatureCelsius, stateOfHealth);
            double heatingWatts = (cellOvervoltage * cellCurrent) * seriesCount * parallelCount;
            double cellThermalMass = chemistry.getCapacityAmpHours() <= 5.0 ? 40.0 : (chemistry.getCapacityAmpHours() * 20.0);
            double totalThermalMass = Math.max(10.0, cellThermalMass * seriesCount * parallelCount);

            this.temperatureCelsius += (heatingWatts / totalThermalMass) * durationSeconds;
            this.stateOfHealth = Math.max(0.0, this.stateOfHealth - (0.05 * durationSeconds));

            if (this.temperatureCelsius >= chemistry.getThermalRunawayTempCelsius() && world != null && !world.isClient()) {
                this.electricalState = ElectricalState.DESTROYED;
                if (world instanceof ServerWorld sw) {
                    sw.getServer().execute(() -> {
                        sw.breakBlock(pos, false);
                        sw.createExplosion(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 3.5f, net.minecraft.world.World.ExplosionSourceType.BLOCK);
                    });
                }
            } else {
                this.electricalState = ElectricalState.SURGE;
            }
            markDirty();
            return;
        }

        // Controlled DC charging
        if (this.electricalState == ElectricalState.SURGE) {
            this.electricalState = ElectricalState.NOMINAL;
        }

        // Negative current represents charging into the cells
        double cellCurrent = -(deliveredCurrent / (double) parallelCount);
        BatterySimulation.SimulationStepResult result = BatterySimulation.step(
            chemistry,
            cellCurrent,
            durationSeconds,
            stateOfCharge,
            temperatureCelsius,
            stateOfHealth,
            20.0
        );

        this.stateOfCharge = result.newSoc();
        this.temperatureCelsius = result.newTemperatureCelsius();
        this.stateOfHealth = result.newHealth();

        if (result.thermalRunaway() && world != null && !world.isClient()) {
            this.electricalState = ElectricalState.DESTROYED;
            if (world instanceof ServerWorld sw) {
                sw.getServer().execute(() -> {
                    sw.breakBlock(pos, false);
                    sw.createExplosion(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 3.0f, net.minecraft.world.World.ExplosionSourceType.BLOCK);
                });
            }
        }
        markDirty();
    }

    // ==================== NBT Serialization ====================

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        this.stateOfCharge = view.getDouble("state_of_charge", 1.0);
        this.stateOfHealth = view.getDouble("state_of_health", 1.0);
        this.temperatureCelsius = view.getDouble("temperature", 20.0);
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        view.putDouble("state_of_charge", this.stateOfCharge);
        view.putDouble("state_of_health", this.stateOfHealth);
        view.putDouble("temperature", this.temperatureCelsius);
    }
}
