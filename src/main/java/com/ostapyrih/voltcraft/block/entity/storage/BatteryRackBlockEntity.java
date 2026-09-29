package com.ostapyrih.voltcraft.block.entity.storage;

import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.item.battery.BatteryCellItem;
import com.ostapyrih.voltcraft.simulation.chemistry.BatteryChemistry;
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

import com.ostapyrih.voltcraft.simulation.electrical.BatteryElement;
import com.ostapyrih.voltcraft.simulation.electrical.RackElement;

/**
 * Modular battery rack bridging 16 item-form cell bays to the kernel island topology.
 * Series mode stages {@code series = count, parallel = 1} (and vice versa for parallel).
 * Kernel state is pack-average {@code [soc, temperatureC, health]}; an empty rack or an
 * open BMS stamps open circuit.
 */
public class BatteryRackBlockEntity extends BlockEntity implements KernelAttachedBlock, Inventory {

    public enum RackWiringMode {
        SERIES,
        PARALLEL
    }

    public static final int INVENTORY_SIZE = 16;



    private final DefaultedList<ItemStack> inventory = DefaultedList.ofSize(INVENTORY_SIZE, ItemStack.EMPTY);

    private final double[] stateArray = RackElement.newStateArray();
    private final double[] telemetryCell = new double[]{Double.NaN, Double.NaN};
    private boolean bmsOpen;
    private RackWiringMode wiringMode = RackWiringMode.SERIES;

    private final ElectricalElement element;

    public BatteryRackBlockEntity(BlockPos pos, BlockState state) {
        super(VoltcraftBlockEntityTypes.BATTERY_RACK_BLOCK_ENTITY, pos, state);
        this.element = new RackElement(this::getSlottedCellCount,
            () -> wiringMode == RackWiringMode.SERIES, this::isBmsOpen, telemetryCell);
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

    public boolean isBmsOpen() {
        return bmsOpen;
    }

    public double getLastTerminalVoltage() {
        double v = telemetryCell[BatteryElement.TELE_V];
        return Double.isFinite(v) ? v : 0.0;
    }

    public double getLastCurrentAmps() {
        double v = telemetryCell[BatteryElement.TELE_I];
        return Double.isFinite(v) ? v : 0.0;
    }

    public double getAverageTemperatureCelsius() {
        int count = 0;
        double totalTemp = 0.0;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem) {
                totalTemp += BatteryCellItem.getTemperature(stack);
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

    public void tick(ServerWorld world) {
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem) {
                double temp = BatteryCellItem.getTemperature(stack);
                if (temp > 20.0) {
                    double cooling = 0.25 * (temp - 20.0) * 0.05;
                    BatteryCellItem.setTemperature(stack, Math.max(20.0, temp - cooling));
                }
            }
        }
    }

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

    public double getElectromotiveForce() {
        if (getStateOfCharge() <= 0.001) return 0.0;

        int count = getSlottedCellCount();
        if (count == 0) return 0.0;

        double totalV = 0.0;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.getItem() instanceof BatteryCellItem cellItem) {
                totalV += BatteryCellItem.getTerminalVoltage(stack, cellItem.getChemistry());
            }
        }

        if (wiringMode == RackWiringMode.PARALLEL) {
            return totalV / count;
        } else {
            return totalV;
        }
    }

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
    public ElectricalElement getElement() {
        return element;
    }

    @Override
    public BlockPos[] getTerminalPositions() {
        int[][] o = RackElement.TERMINAL_OFFSETS;
        BlockPos[] out = new BlockPos[o.length];
        for (int k = 0; k < o.length; k++) {
            out[k] = pos.add(o[k][0], o[k][1], o[k][2]);
        }
        return out;
    }

    @Override
    public double[] getStateArray() {
        return BatteryElement.snapshotState(stateArray);
    }

    @Override
    public void setStateArray(double[] state) {
        BatteryElement.assignState(stateArray, state);
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public boolean isActiveSource() {
        return RackElement.isActiveSource(bmsOpen, getSlottedCellCount());
    }

    @Override
    public boolean isACSource() {
        return false;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        // No solve has run yet; telemetry is not a measurement.
        if (!Double.isFinite(telemetryCell[BatteryElement.TELE_V])) {
            return;
        }
        RackElement self = (RackElement) element;
        boolean next = BatteryElement.bmsNext(bmsOpen,
            telemetryCell[BatteryElement.TELE_V],
            stateArray[BatteryElement.STATE_TEMP],
            self.stagedMinVoltage(), self.stagedSeries());
        if (next != bmsOpen) {
            bmsOpen = next;
            markDirty();
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

    public void writeStateData(WriteView view) {
        RackElement.writeNbt(view, stateArray[BatteryElement.STATE_SOC],
            stateArray[BatteryElement.STATE_TEMP], stateArray[BatteryElement.STATE_HEALTH],
            bmsOpen, wiringMode == RackWiringMode.SERIES);
    }

    public void readStateData(ReadView view) {
        BatteryElement.assignState(stateArray, RackElement.readNbtState(view));
        this.bmsOpen = RackElement.readNbtBmsOpen(view);
        this.wiringMode = RackElement.readNbtSeriesMode(view)
            ? RackWiringMode.SERIES : RackWiringMode.PARALLEL;
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        Inventories.writeData(view, this.inventory);
        writeStateData(view);
    }

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        Inventories.readData(view, this.inventory);
        readStateData(view);
    }
}
