package com.ostapyrih.voltcraft.block.entity.switchgear;

import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.block.switchgear.FuseBoxBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import com.ostapyrih.voltcraft.simulation.electrical.FuseElement;

/**
 * Sacrificial cartridge-fuse kernel adapter.
 * Intact stamps series admittance {@code 1 / R_FUSE_OHM}; blown stamps open circuit.
 * Kernel state is {@code [temperatureC, integrity]}; the discrete phase latches
 * {@code blown} once integrity is depleted, with a one-tick sense delay.
 */
public class FuseBoxBlockEntity extends BlockEntity implements KernelAttachedBlock {



    private final double[] stateArray = FuseElement.newStateArray();
    private final double[] telemetryCell = new double[1];
    private boolean blown;

    private final ElectricalElement element = new FuseElement(this::isBlown, telemetryCell);

    public FuseBoxBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public FuseBoxBlockEntity(BlockPos pos, BlockState state) {
        this(VoltcraftBlockEntityTypes.FUSE_BOX_BLOCK_ENTITY, pos, state);
    }

    public boolean isBlown() {
        return blown;
    }

    public boolean isClosed() {
        return !blown;
    }

    /** Restores an intact fuse at ambient temperature. */
    public void replaceFuse() {
        this.blown = false;
        this.stateArray[FuseElement.STATE_TEMP] = GridConstants.AMBIENT_C;
        this.stateArray[FuseElement.STATE_INTEGRITY] = 1.0;
        markDirty();
    }

    public double getLastCurrentAmps() {
        return telemetryCell[0];
    }

    public double getTemperatureCelsius() {
        return stateArray[FuseElement.STATE_TEMP];
    }

    public double getIntegrity() {
        return stateArray[FuseElement.STATE_INTEGRITY];
    }

    @Override
    public ElectricalElement getElement() {
        return element;
    }

    @Override
    public BlockPos[] getTerminalPositions() {
        return FuseElement.resolveTerminals(pos, readFacing());
    }

    private Direction readFacing() {
        try {
            BlockState cached = getCachedState();
            if (cached != null && cached.contains(FuseBoxBlock.FACING)) {
                Direction facing = cached.get(FuseBoxBlock.FACING);
                if (facing != null) {
                    return facing;
                }
            }
        } catch (Exception ignored) {
            // Fall through to NORTH.
        }
        return Direction.NORTH;
    }

    @Override
    public double[] getStateArray() {
        return FuseElement.snapshotState(stateArray);
    }

    @Override
    public void setStateArray(double[] state) {
        FuseElement.assignState(stateArray, state);
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        if (FuseElement.blowCheck(stateArray[FuseElement.STATE_INTEGRITY], blown) && !blown) {
            blown = true;
            markDirty();
        }
    }

    public void writeStateData(WriteView view) {
        FuseElement.writeNbt(view, stateArray[FuseElement.STATE_TEMP], stateArray[FuseElement.STATE_INTEGRITY], blown);
    }

    public void readStateData(ReadView view) {
        FuseElement.assignState(stateArray, FuseElement.readNbtState(view));
        this.blown = FuseElement.readNbtBlown(view);
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
