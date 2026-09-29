package com.ostapyrih.voltcraft.block.entity.switchgear;

import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.block.switchgear.CircuitBreakerBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import com.ostapyrih.voltcraft.simulation.electrical.BreakerElement;

/**
 * Resettable circuit-breaker kernel adapter (manual trip/reset only).
 * Untripped stamps series admittance; tripped stamps open circuit.
 * Holds no kernel state; the trip flag is a persisted BE field.
 */
public class CircuitBreakerBlockEntity extends BlockEntity implements KernelAttachedBlock {



    private boolean tripped = BreakerElement.DEFAULT_TRIPPED;

    private final ElectricalElement element = new BreakerElement(this::isTripped);

    public CircuitBreakerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public CircuitBreakerBlockEntity(BlockPos pos, BlockState state) {
        this(VoltcraftBlockEntityTypes.CIRCUIT_BREAKER_BLOCK_ENTITY, pos, state);
    }

    public boolean isTripped() {
        return tripped;
    }

    public boolean isClosed() {
        return !tripped;
    }

    public void setTripped(boolean tripped) {
        this.tripped = tripped;
        markDirty();
    }

    /** Clears a latched trip. */
    public void reset() {
        setTripped(false);
    }

    @Override
    public ElectricalElement getElement() {
        return element;
    }

    @Override
    public BlockPos[] getTerminalPositions() {
        return BreakerElement.resolveTerminals(pos, readFacing());
    }

    private Direction readFacing() {
        try {
            BlockState cached = getCachedState();
            if (cached != null && cached.contains(CircuitBreakerBlock.FACING)) {
                Direction facing = cached.get(CircuitBreakerBlock.FACING);
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
        return BreakerElement.snapshotState();
    }

    @Override
    public void setStateArray(double[] state) {
        BreakerElement.checkState(state);
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
    }

    public void writeStateData(WriteView view) {
        BreakerElement.writeNbt(view, tripped);
    }

    public void readStateData(ReadView view) {
        this.tripped = BreakerElement.readNbtTripped(view);
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
