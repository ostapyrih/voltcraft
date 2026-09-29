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

import com.ostapyrih.voltcraft.simulation.electrical.EarthElement;

/**
 * Protective-earth (ground reference) kernel adapter.
 * Stamps shunt conductance to implicit ground; holds no kernel state.
 * Single terminal straight down ({@code pos.down()}).
 */
public class EarthBlockEntity extends BlockEntity implements KernelAttachedBlock {



    private final ElectricalElement element = new EarthElement();

    public EarthBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public EarthBlockEntity(BlockPos pos, BlockState state) {
        this(VoltcraftBlockEntityTypes.EARTH_BLOCK_ENTITY, pos, state);
    }

    @Override
    public ElectricalElement getElement() {
        return element;
    }

    @Override
    public BlockPos[] getTerminalPositions() {
        int[][] o = EarthElement.TERMINAL_OFFSETS;
        BlockPos[] out = new BlockPos[o.length];
        for (int k = 0; k < o.length; k++) {
            out[k] = pos.add(o[k][0], o[k][1], o[k][2]);
        }
        return out;
    }

    @Override
    public double[] getStateArray() {
        return EarthElement.snapshotState();
    }

    @Override
    public void setStateArray(double[] state) {
        EarthElement.checkState(state);
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
    }

    public void writeStateData(WriteView view) {
        EarthElement.writeNbt(view);
    }

    public void readStateData(ReadView view) {
        EarthElement.readNbt(view);
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
