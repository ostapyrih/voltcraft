package com.ostapyrih.voltcraft.block.entity.switchgear;

import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.block.switchgear.KnifeSwitchBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import com.ostapyrih.voltcraft.simulation.electrical.SwitchElement;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalTickDedupe;

/**
 * Manual 100 A disconnect knife-switch kernel adapter.
 * Closed stamps series admittance; open stamps open circuit.
 * Holds no kernel state; the blade flag is a persisted BE field.
 */
public class KnifeSwitchBlockEntity extends BlockEntity implements KernelAttachedBlock {



    private boolean closed = SwitchElement.DEFAULT_CLOSED;

    private final ElectricalElement element = new SwitchElement(this::isClosed);

    public KnifeSwitchBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public KnifeSwitchBlockEntity(BlockPos pos, BlockState state) {
        this(VoltcraftBlockEntityTypes.KNIFE_SWITCH_BLOCK_ENTITY, pos, state);
    }

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
        markDirty();
    }

    @Override
    public ElectricalElement getElement() {
        return element;
    }

    @Override
    public BlockPos[] getTerminalPositions() {
        return SwitchElement.resolveTerminals(pos, readFacing());
    }

    private Direction readFacing() {
        try {
            BlockState cached = getCachedState();
            if (cached != null && cached.contains(KnifeSwitchBlock.FACING)) {
                Direction facing = cached.get(KnifeSwitchBlock.FACING);
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
        return SwitchElement.snapshotState();
    }

    @Override
    public void setStateArray(double[] state) {
        SwitchElement.checkState(state);
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        if (!ElectricalTickDedupe.claim(this, world)) {
            return;
        }
    }

    public void writeStateData(WriteView view) {
        SwitchElement.writeNbt(view, closed);
    }

    public void readStateData(ReadView view) {
        this.closed = SwitchElement.readNbtClosed(view);
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
