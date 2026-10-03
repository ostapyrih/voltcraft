package com.ostapyrih.voltcraft.block.entity.generation;

import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import com.ostapyrih.voltcraft.block.generation.HandCrankGeneratorBlock;

import com.ostapyrih.voltcraft.simulation.electrical.CrankElement;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalTickDedupe;

/**
 * 100 W hand-crank dynamo kernel adapter (12 V DC).
 * Stamps a Thevenin source scaled by flywheel speed; open circuit when still.
 * Kernel state is {@code [flywheelSpeed, totalEnergyJoules]}; crank strokes arrive
 * via player interaction while spindown runs in kernel derivatives.
 */
public class HandCrankGeneratorBlockEntity extends BlockEntity implements KernelAttachedBlock {



    private final double[] stateArray = CrankElement.newStateArray();
    private final double[] telemetryCell = new double[2];

    private final ElectricalElement element;

    public HandCrankGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(VoltcraftBlockEntityTypes.HAND_CRANK_GENERATOR_BLOCK_ENTITY, pos, state);
        this.element = new CrankElement(telemetryCell);
    }

    public void crank() {
        this.stateArray[CrankElement.STATE_SPEED] = CrankElement.crankNext(this.stateArray[CrankElement.STATE_SPEED]);
        markDirty();
    }

    public void addSpeed(double delta) {
        this.stateArray[CrankElement.STATE_SPEED] =
            Math.max(0.0, Math.min(1.0, this.stateArray[CrankElement.STATE_SPEED] + delta));
        markDirty();
    }

    public double getFlywheelSpeed() {
        return stateArray[CrankElement.STATE_SPEED];
    }

    public double getLastDrawnCurrent() {
        return telemetryCell[CrankElement.TELE_I];
    }

    public double getTotalEnergyJoules() {
        return stateArray[CrankElement.STATE_ENERGY];
    }

    public void tick(ServerWorld world) {
        tickElectrical(world);
    }

    @Override
    public ElectricalElement getElement() {
        return element;
    }

    @Override
    public BlockPos[] getTerminalPositions() {
        return CrankElement.resolveTerminals(pos, readFacing());
    }

    private Direction readFacing() {
        try {
            BlockState cached = getCachedState();
            if (cached != null && cached.contains(HandCrankGeneratorBlock.FACING)) {
                Direction facing = cached.get(HandCrankGeneratorBlock.FACING);
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
        return CrankElement.snapshotState(stateArray);
    }

    @Override
    public void setStateArray(double[] state) {
        CrankElement.assignState(stateArray, state);
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public boolean isActiveSource() {
        return CrankElement.isActiveSource(stateArray[CrankElement.STATE_SPEED]);
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
    }

    public void writeStateData(WriteView view) {
        CrankElement.writeNbt(view, stateArray[CrankElement.STATE_SPEED], stateArray[CrankElement.STATE_ENERGY]);
    }

    public void readStateData(ReadView view) {
        CrankElement.assignState(stateArray, CrankElement.readNbtState(view));
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
