package com.ostapyrih.voltcraft.block.entity.storage;

import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.block.storage.BatteryBlock;
import com.ostapyrih.voltcraft.simulation.chemistry.BatteryChemistry;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalTickDedupe;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import com.ostapyrih.voltcraft.simulation.electrical.BatteryElement;

/**
 * Stationary battery pack kernel adapter (BESS).
 * A closed BMS stamps a Thevenin source ({@code series * OCV} over
 * {@code cellR * series / parallel}); an open BMS stamps open circuit.
 * Kernel state is {@code [soc, temperatureC, health]} with hysteresis reclose.
 */
public class BatteryBlockEntity extends BlockEntity implements KernelAttachedBlock {



    private final BatteryChemistry chemistry;
    private final int seriesCount;
    private final int parallelCount;

    private final double[] stateArray = BatteryElement.newStateArray();
    private final double[] telemetryCell = new double[]{Double.NaN, Double.NaN};
    private boolean bmsOpen;

    private final ElectricalElement element;

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
        this.element = new BatteryElement(chemistry, this.seriesCount, this.parallelCount,
            this::isBmsOpen, telemetryCell);
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
        return stateArray[BatteryElement.STATE_TEMP];
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

    // Plain display accessors for Block use; grid math reads BE-owned state only.

    public double getStateOfCharge() {
        return stateArray[BatteryElement.STATE_SOC];
    }

    public double getStateOfHealth() {
        return stateArray[BatteryElement.STATE_HEALTH] * 100.0;
    }

    public double getElectromotiveForce() {
        if (bmsOpen) {
            return 0.0;
        }
        return BatteryElement.packEmf(chemistry, seriesCount, stateArray[BatteryElement.STATE_SOC]);
    }

    @Override
    public ElectricalElement getElement() {
        return element;
    }

    @Override
    public BlockPos[] getTerminalPositions() {
        return BatteryElement.resolveTerminals(pos, readFacing());
    }

    private Direction readFacing() {
        try {
            BlockState cached = getCachedState();
            if (cached != null && cached.contains(BatteryBlock.FACING)) {
                Direction facing = cached.get(BatteryBlock.FACING);
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
        return BatteryElement.isActiveSource(bmsOpen);
    }

    @Override
    public boolean isACSource() {
        return false;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        if (!ElectricalTickDedupe.claim(this, world)) {
            return;
        }
        // No solve has run yet; telemetry is not a measurement.
        if (!Double.isFinite(telemetryCell[BatteryElement.TELE_V])) {
            return;
        }
        double minPackV = BatteryElement.packMinVoltage(chemistry, seriesCount);
        double recoverV = minPackV + Math.max(1, seriesCount) * BatteryElement.BMS_RECOVERY_HYST_V_PER_CELL;
        double emf = BatteryElement.packEmf(chemistry, seriesCount,
            stateArray[BatteryElement.STATE_SOC]);
        double tempC = stateArray[BatteryElement.STATE_TEMP];
        boolean next;
        if (tempC > BatteryElement.BMS_OVERTEMP_OPEN_C) {
            // Overtemperature always forces protection, regardless of charge.
            next = true;
        } else if (emf > recoverV && tempC < BatteryElement.BMS_OVERTEMP_CLOSE_C) {
            // Healthy charge: terminal sag is load-induced (inrush, brownout)
            // or a pre-bootstrap artifact, not depletion. Forcing closed keeps
            // the bus former online and lets the bus recover instead of
            // latching open on a transient and dying unrecoverably.
            // Overcurrent remains the fuse/breaker domain, not the BMS latch.
            next = false;
        } else {
            next = BatteryElement.bmsNext(bmsOpen, telemetryCell[BatteryElement.TELE_V],
                tempC, minPackV, seriesCount);
        }
        if (next != bmsOpen) {
            bmsOpen = next;
            markDirty();
        }
    }

    public void writeStateData(WriteView view) {
        BatteryElement.writeNbt(view, stateArray[BatteryElement.STATE_SOC], stateArray[BatteryElement.STATE_TEMP],
            stateArray[BatteryElement.STATE_HEALTH], bmsOpen);
    }

    public void readStateData(ReadView view) {
        BatteryElement.assignState(stateArray, BatteryElement.readNbtState(view));
        this.bmsOpen = BatteryElement.readNbtBmsOpen(view);
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
