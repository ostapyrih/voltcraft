package com.ostapyrih.voltcraft.block.entity.switchgear;

import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;

import com.ostapyrih.voltcraft.simulation.electrical.ContactorElement;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalTickDedupe;

/**
 * Electromagnetically actuated contactor/relay kernel adapter.
 * Closed stamps series admittance; open stamps open circuit.
 * Holds no kernel state; coil drive wiring is not yet connected.
 */
public class ContactorRelayBlockEntity extends BlockEntity implements KernelAttachedBlock {



    private boolean closed = ContactorElement.DEFAULT_CLOSED;

    private final ElectricalElement element = new ContactorElement(this::isClosed);

    public ContactorRelayBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public ContactorRelayBlockEntity(BlockPos pos, BlockState state) {
        this(VoltcraftBlockEntityTypes.CONTACTOR_RELAY_BLOCK_ENTITY, pos, state);
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
        int[][] o = ContactorElement.TERMINAL_OFFSETS;
        BlockPos[] out = new BlockPos[o.length];
        for (int k = 0; k < o.length; k++) {
            out[k] = pos.add(o[k][0], o[k][1], o[k][2]);
        }
        return out;
    }

    @Override
    public double[] getStateArray() {
        return ContactorElement.snapshotState();
    }

    @Override
    public void setStateArray(double[] state) {
        ContactorElement.checkState(state);
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
        ContactorElement.writeNbt(view, closed);
    }

    public void readStateData(ReadView view) {
        this.closed = ContactorElement.readNbtClosed(view);
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
