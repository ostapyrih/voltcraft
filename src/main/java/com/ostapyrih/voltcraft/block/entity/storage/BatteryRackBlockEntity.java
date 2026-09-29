package com.ostapyrih.voltcraft.block.entity.storage;

import com.mojang.serialization.Codec;
import com.ostapyrih.voltcraft.api.electrical.Complex;
import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.electrical.Stamps;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Modular Battery Rack kernel adapter bridging item-form cells (e.g. 18650 Li-Ion,
 * NiMH, NiCd) to the kernel-owned island topology.
 * Holds 16 cell bays with configurable Series or Parallel busbar wiring.
 *
 * <p>Structural note: all kernel decision logic lives in the static nested
 * {@link RackElement} with cell-count / wiring / BMS suppliers injected and telemetry
 * written into an injected cell, because unit-test runtimes cannot initialize
 * {@code BlockEntity} subclasses at all ({@code BlockEntity.&lt;clinit&gt;} touches
 * {@code Registries}). Production wires {@code this::getSlottedCellCount},
 * {@code () -> wiringMode == SERIES}, and {@code this::isBmsOpen} plus the BE-owned
 * telemetry cell; tests inject their own cells. The outer BE still owns the
 * {@code double[3]} state array, the {@code bmsOpen} boolean field (item 9/13), the
 * inventory, and the telemetry cell — the nested class is a pure function of its
 * inputs. Inventory NBT stays in the outer vanilla overrides (ItemStacks are not
 * unit-testable); only state/BMS/wiring bodies are static.</p>
 *
 * <ul>
 *   <li>Terminals (item 4): two adjacent positions, east/west. Phase-C source polarity
 *       convention shared with {@link BatteryBlockEntity}: {@code terminals[1]} (west)
 *       is positive, so {@code It[0]} is positive while the rack discharges.</li>
 *   <li>States (item 7): exactly 3 kernel-owned reals, {@code [soc, temperatureC,
 *       health]} as pack averages. Defensive copies via
 *       {@link BatteryBlockEntity.BatteryElement#snapshotState} /
 *       {@link BatteryBlockEntity.BatteryElement#assignState} (shared pack helpers).
 *       Empty racks default to {@code [0.0, AMBIENT_C, 1.0]}. The topology owner
 *       ({@code GridManager}) must seed kernel state from the BE after every
 *       {@code setElements} and copy kernel state back into the BE after every tick
 *       before the discrete phase.</li>
 *   <li>Inventory-aware stub: only the slotted cell <b>count</b> and wiring mode enter
 *       kernel math. Series mode stages {@code series = count, parallel = 1};
 *       parallel mode stages {@code series = 1, parallel = count}. Charge capacity
 *       scales as {@code Q = DEFAULT_CHEMISTRY Ah * parallel * 3600}. Cell chemistry
 *       is fixed to the documented default ({@link BatteryChemistry#LI_ION_18650},
 *       matching the legacy chemistry-spec default); per-cell SoC/health/temperature
 *       spread and mixed-chemistry packs are future scope — the inventory remains the
 *       staging/display layer (see legacy accessors below).</li>
 *   <li>Discrete flag (item 9): {@code bmsOpen} persisted in NBT, never in the state
 *       array; open stamps nothing. An empty rack additionally stamps open
 *       ({@code cellCount == 0}).</li>
 *   <li>Derivatives/telemetry: shared pack model with {@link BatteryBlockEntity}
 *       ({@code dSoc/dt = -It[0]/Q}, Joule heating vs pack cooling, health decay only
 *       outside the safe window); telemetry {@code [terminalV, deliveredI]} is
 *       write-only and valid only after {@code kernel.tick()} (item 12). Per-cell
 *       writeback of kernel SoC into ItemStacks is future scope.</li>
 *   <li>{@link #tickElectrical(ServerWorld)} performs the discrete BMS check only
 *       (item 10) with the staged series/parallel geometry; never mutates the state
 *       array, never touches the kernel. Null world safe.</li>
 * </ul>
 *
 * <p>Fallback rollback contract (item 11): on {@code fallbackActive} the BE state array
 * is authoritative — re-sync the kernel from the BE before the next solve and skip the
 * commit; otherwise commit kernel state into the BE before the discrete phase.
 * Per-island semantics. The kernel integrates even on fallback; that state is discarded.</p>
 *
 * <p>NBT keys: {@code "stateArray"} ({@code Codec.DOUBLE.listOf()}), {@code "bmsOpen"}
 * (boolean), {@code "wiring_mode"} (String, preserved legacy key), plus the inventory
 * slots via {@code Inventories} (outer only, preserved). Static reads prefer
 * {@code stateArray} with exactly 3 finite entries, else fresh-rack defaults.</p>
 */
public class BatteryRackBlockEntity extends BlockEntity implements KernelAttachedBlock, Inventory {

    public enum RackWiringMode {
        SERIES,
        PARALLEL
    }

    public static final int INVENTORY_SIZE = 16;

    /** NBT key for the kernel state slice {@code [soc, temperatureC, health]}. */
    public static final String KEY_STATE_ARRAY = "stateArray";
    /** NBT key for the BMS open flag. */
    public static final String KEY_BMS_OPEN = "bmsOpen";
    /** Preserved legacy NBT key for the busbar wiring mode. */
    public static final String KEY_WIRING_MODE = "wiring_mode";

    /**
     * Static kernel element + state/NBT/BMS helpers. Fully unit-testable without any
     * registry, world, or block-entity instance.
     */
    public static final class RackElement implements ElectricalElement {
        /** Terminal offsets: east / west of the BE position. */
        public static final int[][] TERMINAL_OFFSETS = {{1, 0, 0}, {-1, 0, 0}};
        /** Documented default cell chemistry for kernel math (legacy spec default). */
        public static final BatteryChemistry DEFAULT_CHEMISTRY = BatteryChemistry.LI_ION_18650;
        /** Wiring name persisted under {@code "wiring_mode"} for series mode. */
        public static final String WIRING_SERIES = "SERIES";
        /** Wiring name persisted under {@code "wiring_mode"} for parallel mode. */
        public static final String WIRING_PARALLEL = "PARALLEL";

        private final IntSupplier cellCount;
        private final BooleanSupplier seriesMode;
        private final BooleanSupplier bmsOpen;
        private final double[] telemetryCell;

        /**
         * @param cellCount supplier for the BE-owned slotted cell count
         * @param seriesMode supplier, {@code true} for series busbars, {@code false} for parallel
         * @param bmsOpen supplier for the BE-owned BMS flag (read at stamp time only)
         * @param telemetryCell BE/test-owned write-only cache {@code [terminalV, deliveredI]},
         *        length {@code >= 2}
         */
        public RackElement(IntSupplier cellCount, BooleanSupplier seriesMode,
                           BooleanSupplier bmsOpen, double[] telemetryCell) {
            this.cellCount = Objects.requireNonNull(cellCount, "cellCount");
            this.seriesMode = Objects.requireNonNull(seriesMode, "seriesMode");
            this.bmsOpen = Objects.requireNonNull(bmsOpen, "bmsOpen");
            Objects.requireNonNull(telemetryCell, "telemetryCell");
            if (telemetryCell.length < 2) {
                throw new IllegalArgumentException("telemetryCell needs length >= 2");
            }
            this.telemetryCell = telemetryCell;
        }

        @Override
        public int terminalCount() {
            return 2;
        }

        @Override
        public int stateCount() {
            return 3;
        }

        @Override
        public void stamp(Complex[][] y, Complex[] in, int[] terminals, Complex[] v,
                          double[] state, double omega) {
            if (bmsOpen.getAsBoolean() || Math.max(0, cellCount.getAsInt()) == 0) {
                return;
            }
            int series = stagedSeries();
            int parallel = stagedParallel();
            double soc = Math.max(0.0, Math.min(1.0, state[BatteryBlockEntity.STATE_SOC]));
            double emf = BatteryBlockEntity.BatteryElement.packEmf(DEFAULT_CHEMISTRY, series, soc);
            double r = Math.max(BatteryBlockEntity.BatteryElement.MIN_PACK_RESISTANCE_OHM,
                BatteryBlockEntity.BatteryElement.packResistance(DEFAULT_CHEMISTRY, series, parallel,
                    soc, state[BatteryBlockEntity.STATE_TEMP], state[BatteryBlockEntity.STATE_HEALTH]));
            // Phase-C polarity: terminals[1] (west) is positive.
            Stamps.thevenin(y, in, terminals[1], terminals[0],
                new Complex(1.0 / r, 0.0), new Complex(emf, 0.0));
        }

        @Override
        public void derivatives(double[] dxdt, double[] state, Complex[] vt, Complex[] it) {
            double intoNeg = 0.0;
            double imag = 0.0;
            if (it.length > 0 && it[0] != null) {
                intoNeg = it[0].re;
                imag = it[0].magnitude();
            }
            double terminalV = 0.0;
            if (vt.length > 1 && vt[0] != null && vt[1] != null) {
                terminalV = vt[1].re - vt[0].re;
            }
            // Telemetry-only cache (item 28): write-only, never read by control flow.
            telemetryCell[BatteryBlockEntity.BatteryElement.TELE_V] = terminalV;
            telemetryCell[BatteryBlockEntity.BatteryElement.TELE_I] = intoNeg;
            int series = stagedSeries();
            int parallel = stagedParallel();
            double soc = Math.max(0.0, Math.min(1.0, state[BatteryBlockEntity.STATE_SOC]));
            double q = Math.max(1.0,
                BatteryBlockEntity.BatteryElement.packCapacityCoulombs(DEFAULT_CHEMISTRY, parallel));
            dxdt[BatteryBlockEntity.STATE_SOC] = -intoNeg / q;
            double r = Math.max(BatteryBlockEntity.BatteryElement.MIN_PACK_RESISTANCE_OHM,
                BatteryBlockEntity.BatteryElement.packResistance(DEFAULT_CHEMISTRY, series, parallel,
                    soc, state[BatteryBlockEntity.STATE_TEMP], state[BatteryBlockEntity.STATE_HEALTH]));
            double heatingW = imag * imag * r;
            double hCell = Math.max(0.05, DEFAULT_CHEMISTRY.getCapacityAmpHours() * 0.02);
            double coolingW = hCell * Math.max(1, series) * Math.max(1, parallel)
                * (state[BatteryBlockEntity.STATE_TEMP] - GridConstants.AMBIENT_C);
            double cellMass = DEFAULT_CHEMISTRY.getCapacityAmpHours() <= 5.0 ? 40.0
                : DEFAULT_CHEMISTRY.getCapacityAmpHours() * 20.0;
            double totalMass = Math.max(10.0,
                cellMass * Math.max(1, series) * Math.max(1, parallel));
            dxdt[BatteryBlockEntity.STATE_TEMP] = (heatingW - coolingW) / totalMass;
            boolean overcurrent = imag
                > BatteryBlockEntity.BatteryElement.maxDischargeAmps(DEFAULT_CHEMISTRY, parallel);
            boolean overtemp = state[BatteryBlockEntity.STATE_TEMP]
                > BatteryBlockEntity.BatteryElement.BMS_OVERTEMP_OPEN_C;
            dxdt[BatteryBlockEntity.STATE_HEALTH] = (overcurrent || overtemp)
                ? -BatteryBlockEntity.BatteryElement.HEALTH_DECAY_K * imag : 0.0;
        }

        /** Staged series geometry from count + wiring (series mode: count in series). */
        public int stagedSeries() {
            return seriesMode.getAsBoolean() ? Math.max(1, cellCount.getAsInt()) : 1;
        }

        /** Staged parallel geometry from count + wiring (parallel mode: count in parallel). */
        public int stagedParallel() {
            return seriesMode.getAsBoolean() ? 1 : Math.max(1, cellCount.getAsInt());
        }

        /** Staged pack cutoff voltage for the BMS check. */
        public double stagedMinVoltage() {
            return BatteryBlockEntity.BatteryElement.packMinVoltage(DEFAULT_CHEMISTRY, stagedSeries());
        }

        /**
         * Source classification: active when the BMS is closed and at least one cell is
         * slotted.
         */
        public static boolean isActiveSource(boolean bmsOpen, int cellCount) {
            return !bmsOpen && cellCount > 0;
        }

        /** Fresh-rack kernel state {@code [0.0, AMBIENT_C, 1.0]} (defensive: new array per call). */
        public static double[] newStateArray() {
            return new double[]{0.0, GridConstants.AMBIENT_C, 1.0};
        }

        /** Writes the state slice, the BMS flag, and the wiring mode name. */
        public static void writeNbt(WriteView view, double soc, double tempC, double health,
                                    boolean bmsOpen, boolean seriesMode) {
            List<Double> boxed = new ArrayList<>(3);
            boxed.add(soc);
            boxed.add(tempC);
            boxed.add(health);
            view.put(KEY_STATE_ARRAY, Codec.DOUBLE.listOf(), boxed);
            view.putBoolean(KEY_BMS_OPEN, bmsOpen);
            view.putString(KEY_WIRING_MODE, seriesMode ? WIRING_SERIES : WIRING_PARALLEL);
        }

        /**
         * Reads the state slice; returns fresh-rack defaults unless the stored list has
         * exactly 3 finite entries (forward-tolerant).
         */
        public static double[] readNbtState(ReadView view) {
            List<Double> list = view.read(KEY_STATE_ARRAY, Codec.DOUBLE.listOf()).orElse(List.of());
            if (list.size() == 3 && list.get(0) != null && list.get(1) != null && list.get(2) != null
                    && Double.isFinite(list.get(0)) && Double.isFinite(list.get(1))
                    && Double.isFinite(list.get(2))) {
                return new double[]{list.get(0), list.get(1), list.get(2)};
            }
            return newStateArray();
        }

        /** Reads the BMS flag (defaults to closed when absent). */
        public static boolean readNbtBmsOpen(ReadView view) {
            return view.getBoolean(KEY_BMS_OPEN, false);
        }

        /** Reads the wiring mode (defaults to series when absent or unrecognized). */
        public static boolean readNbtSeriesMode(ReadView view) {
            return !WIRING_PARALLEL.equals(view.getString(KEY_WIRING_MODE, WIRING_SERIES));
        }
    }

    private final DefaultedList<ItemStack> inventory = DefaultedList.ofSize(INVENTORY_SIZE, ItemStack.EMPTY);

    private final double[] stateArray = RackElement.newStateArray();
    private final double[] telemetryCell = new double[2];
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

    /** Discrete BMS flag: {@code true} isolates the rack (open circuit). */
    public boolean isBmsOpen() {
        return bmsOpen;
    }

    /**
     * Telemetry only: terminal voltage cached by the last {@code derivatives} call.
     * Valid only after {@code kernel.tick()} (item 12).
     */
    public double getLastTerminalVoltage() {
        return telemetryCell[BatteryBlockEntity.BatteryElement.TELE_V];
    }

    /**
     * Telemetry only: delivered current in amps (positive on discharge) cached by the
     * last {@code derivatives} call. Valid only after {@code kernel.tick()} (item 12).
     */
    public double getLastCurrentAmps() {
        return telemetryCell[BatteryBlockEntity.BatteryElement.TELE_I];
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

    /**
     * Passive cell cooling toward ambient for all slotted cells. Staging-only work on
     * discrete inventory data; never touches the kernel state array. Block-ticker entry
     * point (kept for Block association); kernel staging runs in
     * {@link #tickElectrical(ServerWorld)}.
     */
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
    }

    // Legacy inventory-based display accessors kept for Block use (plain methods, no grid
    // role). Kernel math reads the state array plus the slotted count only.

    /** Average cell charge in {@code [0,1]} across slotted cells (inventory staging layer). */
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

    /** Pack EMF from slotted cells and wiring mode (inventory staging layer). */
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
            // Parallel wiring: pack terminal voltage is the average of cell open-circuit voltages
            return totalV / count;
        } else {
            // Series wiring (default): pack terminal voltage is the direct sum of all cell voltages!
            return totalV;
        }
    }

    /** Summed cell energy capacity in joules (inventory staging layer). */
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
        return BatteryBlockEntity.BatteryElement.snapshotState(stateArray);
    }

    @Override
    public void setStateArray(double[] state) {
        BatteryBlockEntity.BatteryElement.assignState(stateArray, state);
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
        // Discrete BMS check only: never mutates the state array, never touches the kernel.
        RackElement self = (RackElement) element;
        boolean next = BatteryBlockEntity.BatteryElement.bmsNext(bmsOpen,
            telemetryCell[BatteryBlockEntity.BatteryElement.TELE_V],
            stateArray[BatteryBlockEntity.STATE_TEMP],
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

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     * Covers state slice, BMS flag, and wiring mode; inventory slots are handled in the
     * vanilla overrides below (ItemStacks are not unit-testable).
     */
    public void writeStateData(WriteView view) {
        Objects.requireNonNull(view, "view");
        RackElement.writeNbt(view, stateArray[BatteryBlockEntity.STATE_SOC],
            stateArray[BatteryBlockEntity.STATE_TEMP], stateArray[BatteryBlockEntity.STATE_HEALTH],
            bmsOpen, wiringMode == RackWiringMode.SERIES);
    }

    /**
     * Public NBT body (test seam and future sync hook); vanilla entry points delegate here.
     */
    public void readStateData(ReadView view) {
        Objects.requireNonNull(view, "view");
        BatteryBlockEntity.BatteryElement.assignState(stateArray, RackElement.readNbtState(view));
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
