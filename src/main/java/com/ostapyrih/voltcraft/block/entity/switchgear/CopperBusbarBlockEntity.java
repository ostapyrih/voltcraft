package com.ostapyrih.voltcraft.block.entity.switchgear;

import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.block.switchgear.CopperBusbarBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import com.ostapyrih.voltcraft.simulation.electrical.BusbarElement;

/**
 * High-ampacity (500 A) copper busbar kernel adapter.
 * Always closed: unconditional low-impedance series admittance.
 * Holds no kernel state and no discrete flag.
 */
public class CopperBusbarBlockEntity extends BlockEntity implements KernelAttachedBlock {



    private final ElectricalElement element = new BusbarElement();

    public CopperBusbarBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public CopperBusbarBlockEntity(BlockPos pos, BlockState state) {
        this(VoltcraftBlockEntityTypes.COPPER_BUSBAR_BLOCK_ENTITY, pos, state);
    }

    @Override
    public ElectricalElement getElement() {
        return element;
    }

    @Override
    public BlockPos[] getTerminalPositions() {
        return BusbarElement.resolveTerminals(pos, readFacing());
    }

    private Direction readFacing() {
        try {
            BlockState cached = getCachedState();
            if (cached != null && cached.contains(CopperBusbarBlock.FACING)) {
                Direction facing = cached.get(CopperBusbarBlock.FACING);
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
        return BusbarElement.snapshotState();
    }

    @Override
    public void setStateArray(double[] state) {
        BusbarElement.checkState(state);
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
    }

    public void writeStateData(WriteView view) {
        BusbarElement.writeNbt(view);
    }

    public void readStateData(ReadView view) {
        BusbarElement.readNbt(view);
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
