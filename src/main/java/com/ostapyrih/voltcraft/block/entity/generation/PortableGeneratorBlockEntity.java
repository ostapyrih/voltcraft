package com.ostapyrih.voltcraft.block.entity.generation;

import com.ostapyrih.voltcraft.api.electrical.ElectricalElement;
import com.ostapyrih.voltcraft.api.electrical.GridConstants;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.block.generation.PortableGeneratorBlock;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import com.ostapyrih.voltcraft.simulation.electrical.GeneratorElement;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalTickDedupe;

/**
 * 1.8-2.2 kW portable inverter generator kernel adapter (230 V 50 Hz).
 * Running stamps a Thevenin source; dry tank stamps open circuit.
 * Kernel state is {@code [temperatureC, remainingFuelTicks]} with eco-throttle burn;
 * the discrete phase mirrors the RUNNING blockstate and books delivered energy.
 */
public class PortableGeneratorBlockEntity extends BlockEntity implements KernelAttachedBlock {

    public static final double RATED_POWER_WATTS = 1800.0;
    public static final double SURGE_POWER_WATTS = 2200.0;
    public static final double OUTPUT_VOLTAGE_RMS = 230.0;



    private static final int BLOCKSTATE_UPDATE_FLAGS = 3; // notify neighbors + sync to client

    private final double[] stateArray = GeneratorElement.newStateArray();
    private final double[] telemetryCell = new double[2];
    private double totalEnergyJoules = 0.0;
    /** Staged output EMF in volts (prime-mover current limit), read by the stamp. */
    private double stagedEmf = OUTPUT_VOLTAGE_RMS;

    private final ElectricalElement element;

    public PortableGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(VoltcraftBlockEntityTypes.PORTABLE_GENERATOR_BLOCK_ENTITY, pos, state);
        this.element = new GeneratorElement(this::isRunning, () -> stagedEmf, telemetryCell);
    }

    public void addFuel(int ticks) {
        this.stateArray[GeneratorElement.STATE_FUEL] = Math.max(0.0, this.stateArray[GeneratorElement.STATE_FUEL] + ticks);
        markDirty();
    }

    public double getRemainingFuelTicks() {
        return stateArray[GeneratorElement.STATE_FUEL];
    }

    public boolean isRunning() {
        return stateArray[GeneratorElement.STATE_FUEL] > 0.0;
    }

    public double getLastDeliveredCurrentAmps() {
        return telemetryCell[GeneratorElement.TELE_I];
    }

    public double getLastDeliveredPowerWatts() {
        return telemetryCell[GeneratorElement.TELE_P];
    }

    public double getTotalEnergyJoules() {
        return totalEnergyJoules;
    }

    public double getTemperatureCelsius() {
        return stateArray[GeneratorElement.STATE_TEMP];
    }

    public Direction getOutputFacing() {
        BlockState state = getCachedState();
        if (state.contains(PortableGeneratorBlock.FACING)) {
            return state.get(PortableGeneratorBlock.FACING);
        }
        return Direction.NORTH;
    }

    public BlockPos getOutputPos() {
        return pos.offset(getOutputFacing());
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
        return GeneratorElement.resolveTerminals(pos, readFacing());
    }

    private Direction readFacing() {
        try {
            BlockState cached = getCachedState();
            if (cached != null && cached.contains(PortableGeneratorBlock.FACING)) {
                Direction facing = cached.get(PortableGeneratorBlock.FACING);
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
        return GeneratorElement.snapshotState(stateArray);
    }

    @Override
    public void setStateArray(double[] state) {
        GeneratorElement.assignState(stateArray, state);
    }

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public boolean isActiveSource() {
        return GeneratorElement.isActiveSource(isRunning());
    }

    @Override
    public boolean isACSource() {
        return true;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        if (!ElectricalTickDedupe.claim(this, world)) {
            return;
        }
        // Stage the prime-mover limit from the previously solved operating
        // point before the kernel builds the next system.
        double teleI = telemetryCell[GeneratorElement.TELE_I];
        double teleP = telemetryCell[GeneratorElement.TELE_P];
        double teleV = teleI > 1e-6 ? teleP / teleI : 0.0;
        boolean running = isRunning();
        stagedEmf = GeneratorElement.stageEmf(running, teleV, teleI);
        if (!running) {
            telemetryCell[GeneratorElement.TELE_I] = 0.0;
            telemetryCell[GeneratorElement.TELE_P] = 0.0;
        } else {
            double p = telemetryCell[GeneratorElement.TELE_P];
            if (p > 0.0) {
                this.totalEnergyJoules += p * GridConstants.DT;
            }
        }
        if (world != null) {
            BlockState cached = getCachedState();
            if (cached.contains(PortableGeneratorBlock.RUNNING)
                    && cached.get(PortableGeneratorBlock.RUNNING) != running) {
                world.setBlockState(pos, cached.with(PortableGeneratorBlock.RUNNING, running),
                    BLOCKSTATE_UPDATE_FLAGS);
            }
        }
    }

    public void writeStateData(WriteView view) {
        GeneratorElement.writeNbt(view, stateArray[GeneratorElement.STATE_TEMP], stateArray[GeneratorElement.STATE_FUEL],
            totalEnergyJoules);
    }

    public void readStateData(ReadView view) {
        GeneratorElement.assignState(stateArray, GeneratorElement.readNbtState(view));
        this.totalEnergyJoules = GeneratorElement.readNbtTotalEnergy(view);
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
