package com.ostapyrih.voltcraft.block.entity.storage;

import com.ostapyrih.voltcraft.api.data.BatteryCellSpec;
import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.energy.IElectricStorage;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.item.battery.BatteryCellItem;
import com.ostapyrih.voltcraft.simulation.chemistry.BatteryChemistry;
import com.ostapyrih.voltcraft.simulation.chemistry.BatterySimulation;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalGrid;
import com.ostapyrih.voltcraft.simulation.grid.GridManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Modular Battery Rack BlockEntity bridging item-form cells (e.g. 18650 Li-Ion, NiMH, NiCd)
 * to the stationary world ElectricalGrid.
 * Holds 16 cell bays with configurable Series or Parallel busbar wiring.
 */
public class BatteryRackBlockEntity extends BlockEntity implements IElectricStorage, Inventory {

    public enum RackWiringMode {
        SERIES,
        PARALLEL
    }

    public static final int INVENTORY_SIZE = 16;
    private final DefaultedList<ItemStack> inventory = DefaultedList.ofSize(INVENTORY_SIZE, ItemStack.EMPTY);

    private ElectricalState electricalState = ElectricalState.NOMINAL;
    private RackWiringMode wiringMode = RackWiringMode.SERIES;
    private UUID lastGridId = null;

    public BatteryRackBlockEntity(BlockPos pos, BlockState state) {
        super(VoltcraftBlockEntityTypes.BATTERY_RACK_BLOCK_ENTITY, pos, state);
    }

    public int getSlottedCellCount() {
        int count = 0;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem) {
                count++;
            }
        }
        return count;
    }

    public RackWiringMode getWiringMode() {
        return wiringMode;
    }

    public void setWiringMode(RackWiringMode mode) {
        this.wiringMode = mode;
        markDirty();
    }

    public void toggleWiringMode() {
        this.wiringMode = (this.wiringMode == RackWiringMode.SERIES) ? RackWiringMode.PARALLEL : RackWiringMode.SERIES;
        markDirty();
    }

    public double getAverageTemperatureCelsius() {
        int count = 0;
        double totalTemp = 0.0;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem) {
                totalTemp += BatteryCellItem.getTemperature(stack);
                count++;
            }
        }
        return count > 0 ? (totalTemp / count) : 20.0;
    }

    public double getMaxCellTemperatureCelsius() {
        double maxT = 20.0;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem) {
                maxT = Math.max(maxT, BatteryCellItem.getTemperature(stack));
            }
        }
        return maxT;
    }

    @Override
    public void markRemoved() {
        super.markRemoved();
        onRemovedFromWorld();
    }

    public void tick(ServerWorld world) {
        // Passive cooling toward ambient 20°C for all slotted cells
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem) {
                double temp = BatteryCellItem.getTemperature(stack);
                if (temp > 20.0) {
                    double cooling = 0.25 * (temp - 20.0) * 0.05;
                    BatteryCellItem.setTemperature(stack, Math.max(20.0, temp - cooling));
                }
            }
        }

        GridManager gridManager = GridManager.get(world);
        ElectricalGrid currentGrid = gridManager.getGridAt(pos);

        // Self-heal / seed grid node if missing on chunk/world load
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
        int count = getSlottedCellCount();
        if (count == 0) return 0.0;
        double totalSoc = 0.0;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem) {
                totalSoc += BatteryCellItem.getCharge(stack);
                count++;
            }
        }
        return count > 0 ? (totalSoc / count) : 0.0;
    }

    @Override
    public double getStateOfHealth() {
        int count = 0;
        double totalHealth = 0.0;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem) {
                totalHealth += BatteryCellItem.getHealth(stack);
                count++;
            }
        }
        return count > 0 ? ((totalHealth / count) * 100.0) : 100.0;
    }

    @Override
    public double getMaxStorageJoules() {
        double joules = 0.0;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem cellItem) {
                BatteryChemistry chem = cellItem.getChemistry();
                joules += chem.getCapacityAmpHours() * chem.getNominalVoltage() * 3600.0;
            }
        }
        return joules;
    }

    @Override
    public double getStoredJoules() {
        return getMaxStorageJoules() * getStateOfCharge();
    }

    @Override
    public BatteryCellSpec getChemistrySpec() {
        return BatteryCellSpec.LI_ION_18650;
    }

    @Override
    public void addEnergy(double joules) {
        int count = getSlottedCellCount();
        if (count == 0) return;
        double joulesPerCell = joules / count;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem cellItem) {
                BatteryChemistry chem = cellItem.getChemistry();
                if (!chem.isRechargeable()) continue;
                double cellMaxJ = chem.getCapacityAmpHours() * chem.getNominalVoltage() * 3600.0;
                double currentCharge = BatteryCellItem.getCharge(stack);
                BatteryCellItem.setCharge(stack, Math.min(1.0, currentCharge + (joulesPerCell / cellMaxJ)));
            }
        }
        markDirty();
    }

    @Override
    public double extractEnergy(double joules) {
        int count = getSlottedCellCount();
        if (count == 0) return 0.0;
        double requestedPerCell = joules / count;
        double deliveredTotal = 0.0;

        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem cellItem) {
                BatteryChemistry chem = cellItem.getChemistry();
                double cellMaxJ = chem.getCapacityAmpHours() * chem.getNominalVoltage() * 3600.0;
                double currentCharge = BatteryCellItem.getCharge(stack);
                double currentJ = currentCharge * cellMaxJ;
                double cellDelivered = Math.min(requestedPerCell, currentJ);
                BatteryCellItem.setCharge(stack, Math.max(0.0, currentCharge - (cellDelivered / cellMaxJ)));
                deliveredTotal += cellDelivered;
            }
        }
        markDirty();
        return deliveredTotal;
    }

    // ==================== IElectricSource ====================

    @Override
    public double getElectromotiveForce() {
        if (electricalState == ElectricalState.DESTROYED || getStateOfCharge() <= 0.001) return 0.0;

        int count = getSlottedCellCount();
        if (count == 0) return 0.0;

        double totalV = 0.0;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem cellItem) {
                totalV += BatteryCellItem.getTerminalVoltage(stack, cellItem.getChemistry());
            }
        }

        if (wiringMode == RackWiringMode.PARALLEL) {
            // Parallel wiring: pack terminal voltage is the average of cell open-circuit voltages
            return totalV / count;
        } else {
            // Series wiring (default): pack terminal voltage is the direct sum of all cell voltages!
            return totalV;
        }
    }

    @Override
    public double getInternalResistance() {
        int count = getSlottedCellCount();
        if (count == 0) return 1e6;

        double totalR = 0.0;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem cellItem) {
                totalR += cellItem.getChemistry().getInternalResistanceOhms();
            }
        }

        if (wiringMode == RackWiringMode.PARALLEL) {
            return (totalR / count) / count;
        } else {
            return Math.max(0.005, totalR);
        }
    }

    @Override
    public double getMaxOutputCurrent() {
        if (electricalState == ElectricalState.DESTROYED || getStateOfCharge() <= 0.001) return 0.0;

        int count = getSlottedCellCount();
        if (count == 0) return 0.0;

        if (wiringMode == RackWiringMode.PARALLEL) {
            double totalI = 0.0;
            for (ItemStack stack : inventory) {
                if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem cellItem) {
                    totalI += cellItem.getChemistry().getMaxDischargeCurrentAmps();
                }
            }
            return totalI;
        } else {
            double minI = Double.MAX_VALUE;
            for (ItemStack stack : inventory) {
                if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem cellItem) {
                    minI = Math.min(minI, cellItem.getChemistry().getMaxDischargeCurrentAmps());
                }
            }
            return minI == Double.MAX_VALUE ? 0.0 : minI;
        }
    }

    @Override
    public double getFrequency() {
        return 0.0; // Battery rack is DC
    }

    @Override
    public void onPowerDrawn(double currentAmps, double durationSeconds) {
        int count = getSlottedCellCount();
        if (count == 0) return;

        if (currentAmps <= 0.0 || getStateOfCharge() <= 0.001) {
            // Passive ambient cooling when idle / unloaded / discharged
            for (ItemStack stack : inventory) {
                if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem) {
                    double temp = BatteryCellItem.getTemperature(stack);
                    if (temp > 20.0) {
                        double cooling = 0.5 * (temp - 20.0);
                        BatteryCellItem.setTemperature(stack, Math.max(20.0, temp - (cooling * durationSeconds)));
                    }
                }
            }
            markDirty();
            return;
        }

        // In series: identical current passes through all cells. In parallel: current is divided across cells.
        double cellCurrent = (wiringMode == RackWiringMode.PARALLEL) ? (currentAmps / count) : currentAmps;
        boolean anyRunaway = false;

        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem cellItem) {
                double soc = BatteryCellItem.getCharge(stack);
                double health = BatteryCellItem.getHealth(stack);
                double temp = BatteryCellItem.getTemperature(stack);

                BatterySimulation.SimulationStepResult result = BatterySimulation.step(
                    cellItem.getChemistry(),
                    cellCurrent,
                    durationSeconds,
                    soc,
                    temp,
                    health,
                    20.0
                );

                BatteryCellItem.setCharge(stack, result.newSoc());
                BatteryCellItem.setHealth(stack, result.newHealth());
                BatteryCellItem.setTemperature(stack, result.newTemperatureCelsius());

                if (result.thermalRunaway() || result.newTemperatureCelsius() >= cellItem.getChemistry().getThermalRunawayTempCelsius()) {
                    anyRunaway = true;
                }
            }
        }
        markDirty();

        if (anyRunaway && world != null && !world.isClient()) {
            triggerThermalRunaway();
        }
    }

    // ==================== IElectricConsumer ====================

    @Override
    public double getNominalPowerDemand() {
        int count = getSlottedCellCount();
        if (count == 0 || getStateOfCharge() >= 0.99) return 0.0;
        return (wiringMode == RackWiringMode.PARALLEL) ? (count * 5.0) : (getNominalVoltage() * 1.5);
    }

    @Override
    public double getNominalVoltage() {
        int count = getSlottedCellCount();
        if (count == 0) return 14.8;
        double totalNom = 0.0;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem cellItem) {
                totalNom += cellItem.getChemistry().getNominalVoltage();
            }
        }
        return (wiringMode == RackWiringMode.PARALLEL) ? (totalNom / count) : totalNom;
    }

    @Override
    public double getMinOperatingVoltage() {
        int count = getSlottedCellCount();
        if (count == 0) return 12.0;
        double totalCut = 0.0;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem cellItem) {
                totalCut += cellItem.getChemistry().getCutoffVoltage();
            }
        }
        return (wiringMode == RackWiringMode.PARALLEL) ? (totalCut / count) : totalCut;
    }

    @Override
    public double getMaxOperatingVoltage() {
        int count = getSlottedCellCount();
        if (count == 0) return 16.8;
        double totalFull = 0.0;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem cellItem) {
                totalFull += cellItem.getChemistry().getFullChargeVoltage();
            }
        }
        return (wiringMode == RackWiringMode.PARALLEL) ? (totalFull / count) : totalFull;
    }

    @Override
    public void onPowerReceived(double terminalVoltage, double deliveredCurrent, double durationSeconds) {
        onPowerReceived(terminalVoltage, deliveredCurrent, durationSeconds, 0.0);
    }

    @Override
    public void onPowerReceived(double terminalVoltage, double deliveredCurrent, double durationSeconds, double frequencyHz) {
        int count = getSlottedCellCount();
        if (count == 0 || deliveredCurrent <= 0.0 || electricalState == ElectricalState.DESTROYED) return;

        if (frequencyHz > 0.001) {
            // Unrectified AC applied across battery rack: 0 charge gain, severe cell heating & degradation
            double cellCurrent = (wiringMode == RackWiringMode.PARALLEL)
                ? (deliveredCurrent / count)
                : deliveredCurrent;

            boolean anyRunaway = false;

            for (ItemStack stack : inventory) {
                if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem cellItem) {
                    BatteryChemistry chem = cellItem.getChemistry();
                    double rInt = chem.getInternalResistanceOhms();
                    double cellHeatingWatts = cellCurrent * cellCurrent * rInt;
                    double thermalMass = chem.getCapacityAmpHours() <= 5.0 ? 40.0 : (chem.getCapacityAmpHours() * 20.0);

                    double temp = BatteryCellItem.getTemperature(stack);
                    double newTemp = temp + ((cellHeatingWatts / thermalMass) * durationSeconds);
                    BatteryCellItem.setTemperature(stack, newTemp);

                    double health = BatteryCellItem.getHealth(stack);
                    BatteryCellItem.setHealth(stack, Math.max(0.0, health - (0.01 * durationSeconds)));

                    if (newTemp >= chem.getThermalRunawayTempCelsius()) {
                        anyRunaway = true;
                    }
                }
            }

            if (anyRunaway && world != null && !world.isClient()) {
                triggerThermalRunaway();
            } else {
                this.electricalState = ElectricalState.SURGE;
            }

            markDirty();
            return; // Strict realism: ZERO charge gained from AC
        }

        // Thermodynamic check: applied charging potential must strictly exceed pack EMF
        double packEmf = getElectromotiveForce();
        if (terminalVoltage <= packEmf) {
            // Cannot charge when supplied voltage is less than or equal to battery pack EMF
            return;
        }

        // Overvoltage hazard check: if voltage exceeds safe maximum threshold (105% full-charge pack voltage)
        double maxOperatingV = getMaxOperatingVoltage();
        if (terminalVoltage > maxOperatingV * 1.05) {
            double overvoltage = terminalVoltage - maxOperatingV;
            double cellOvervoltage = (wiringMode == RackWiringMode.PARALLEL) ? overvoltage : (overvoltage / count);
            double cellCurrent = (wiringMode == RackWiringMode.PARALLEL) ? (deliveredCurrent / count) : deliveredCurrent;

            boolean anyRunaway = false;
            for (ItemStack stack : inventory) {
                if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem cellItem) {
                    BatteryChemistry chem = cellItem.getChemistry();
                    double excessWatts = cellOvervoltage * cellCurrent;
                    double thermalMass = chem.getCapacityAmpHours() <= 5.0 ? 40.0 : (chem.getCapacityAmpHours() * 20.0);
                    double temp = BatteryCellItem.getTemperature(stack);
                    double newTemp = temp + ((excessWatts / thermalMass) * durationSeconds);
                    BatteryCellItem.setTemperature(stack, newTemp);

                    double health = BatteryCellItem.getHealth(stack);
                    BatteryCellItem.setHealth(stack, Math.max(0.0, health - (0.05 * durationSeconds)));

                    if (newTemp >= chem.getThermalRunawayTempCelsius()) {
                        anyRunaway = true;
                    }
                }
            }

            this.electricalState = ElectricalState.SURGE;
            markDirty();

            if (anyRunaway && world != null && !world.isClient()) {
                triggerThermalRunaway();
            }
            return;
        }

        // Controlled DC charging
        if (this.electricalState == ElectricalState.SURGE) {
            this.electricalState = ElectricalState.NOMINAL;
        }

        double cellCurrent = (wiringMode == RackWiringMode.PARALLEL)
            ? -(deliveredCurrent / count)
            : -deliveredCurrent;

        boolean anyRunaway = false;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem cellItem) {
                if (!cellItem.getChemistry().isRechargeable()) continue;

                double soc = BatteryCellItem.getCharge(stack);
                double health = BatteryCellItem.getHealth(stack);
                double temp = BatteryCellItem.getTemperature(stack);

                BatterySimulation.SimulationStepResult result = BatterySimulation.step(
                    cellItem.getChemistry(),
                    cellCurrent,
                    durationSeconds,
                    soc,
                    temp,
                    health,
                    20.0
                );

                BatteryCellItem.setCharge(stack, result.newSoc());
                BatteryCellItem.setHealth(stack, result.newHealth());
                BatteryCellItem.setTemperature(stack, result.newTemperatureCelsius());

                if (result.thermalRunaway() || result.newTemperatureCelsius() >= cellItem.getChemistry().getThermalRunawayTempCelsius()) {
                    anyRunaway = true;
                }
            }
        }
        markDirty();

        if (anyRunaway && world != null && !world.isClient()) {
            triggerThermalRunaway();
        }
    }

    private void triggerThermalRunaway() {
        this.electricalState = ElectricalState.DESTROYED;
        if (world instanceof ServerWorld sw) {
            sw.getServer().execute(() -> {
                sw.breakBlock(pos, false);
                sw.createExplosion(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 3.5f, net.minecraft.world.World.ExplosionSourceType.BLOCK);
            });
        }
    }

    // ==================== Inventory Implementation ====================

    @Override
    public int size() {
        return INVENTORY_SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getStack(int slot) {
        return inventory.get(slot);
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        ItemStack result = Inventories.splitStack(inventory, slot, amount);
        if (!result.isEmpty()) markDirty();
        return result;
    }

    @Override
    public ItemStack removeStack(int slot) {
        ItemStack result = Inventories.removeStack(inventory, slot);
        if (!result.isEmpty()) markDirty();
        return result;
    }

    @Override
    public void setStack(int slot, ItemStack stack) {
        inventory.set(slot, stack);
        markDirty();
    }

    @Override
    public boolean canPlayerUse(PlayerEntity player) {
        return Inventory.canPlayerUse(this, player);
    }

    @Override
    public void clear() {
        inventory.clear();
        markDirty();
    }

    // ==================== NBT Serialization ====================

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        Inventories.readData(view, this.inventory);
        try {
            this.wiringMode = RackWiringMode.valueOf(view.getString("wiring_mode", "SERIES"));
        } catch (IllegalArgumentException e) {
            this.wiringMode = RackWiringMode.SERIES;
        }
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        Inventories.writeData(view, this.inventory);
        view.putString("wiring_mode", this.wiringMode.name());
    }
}
