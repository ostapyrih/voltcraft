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

import com.ostapyrih.voltcraft.simulation.electrical.SpliceElement;

/**
 * Junction-box splice kernel adapter.
 * Always closed: unconditional low-impedance series admittance.
 * Holds no kernel state and no discrete flag.
 */
public class JunctionBoxBlockEntity extends BlockEntity implements KernelAttachedBlock {



    private final ElectricalElement element = new SpliceElement();

    public JunctionBoxBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public JunctionBoxBlockEntity(BlockPos pos, BlockState state) {
        this(VoltcraftBlockEntityTypes.JUNCTION_BOX_BLOCK_ENTITY, pos, state);
    }

    @Override
    public ElectricalElement getElement() {
        return element;
    }

    @Override
    public BlockPos[] getTerminalPositions() {
        int[][] o = SpliceElement.TERMINAL_OFFSETS;
        BlockPos[] out = new BlockPos[o.length];
        for (int k = 0; k < o.length; k++) {
            out[k] = pos.add(o[k][0], o[k][1], o[k][2]);
        }
        return out;
    }

    @Override
    public double[] getStateArray() {
        return SpliceElement.snapshotState();
    }

    @Override
    public void setStateArray(double[] state) {
        SpliceElement.checkState(state);
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
    }

    public void writeStateData(WriteView view) {
        SpliceElement.writeNbt(view);
    }

    public void readStateData(ReadView view) {
        SpliceElement.readNbt(view);
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
