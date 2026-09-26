package com.ostapyrih.voltcraft.block.entity.conversion;

import com.ostapyrih.voltcraft.api.energy.IElectricSource;
import com.ostapyrih.voltcraft.block.conversion.InverterBlock;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.simulation.conversion.InverterType;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalGrid;
import com.ostapyrih.voltcraft.simulation.grid.GridManager;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Block entity for DC-AC inverters (Square Wave, Modified Sine, Pure Sine SPWM, Grid-Tie, Hybrid ESS).
 */
public class InverterBlockEntity extends AbstractPowerConverterBlockEntity {

    private final InverterType inverterType;
    private boolean atsIslandMode = false;
    private double nominalInputVoltage = 48.0;

    public InverterBlockEntity(BlockPos pos, BlockState state, InverterType inverterType) {
        super(VoltcraftBlockEntityTypes.INVERTER_BLOCK_ENTITY, pos, state);
        this.inverterType = inverterType;
        this.targetOutputVoltage = inverterType.getNominalOutputVoltage();
    }

    public InverterBlockEntity(BlockPos pos, BlockState state) {
        this(
            pos,
            state,
            state.getBlock() instanceof InverterBlock ib ? ib.getInverterType() : InverterType.PURE_SINE
        );
    }

    public InverterType getInverterType() {
        return inverterType;
    }

    public boolean isAtsIslandMode() {
        return atsIslandMode;
    }

    @Override
    public double getNominalInputVoltage() {
        return nominalInputVoltage;
    }

    @Override
    public void setNominalInputVoltage(double voltage) {
        super.setNominalInputVoltage(voltage);
        if (voltage <= 15.0) {
            this.nominalInputVoltage = 12.0;
        } else if (voltage <= 30.0) {
            this.nominalInputVoltage = 24.0;
        } else {
            this.nominalInputVoltage = 48.0;
        }
        markDirty();
    }

    @Override
    public void resetTrip() {
        super.resetTrip();
        if (inverterType.hasAutomaticTransferSwitch()) {
            if (world instanceof ServerWorld sw) {
                this.atsIslandMode = !isExternalGridPresent(sw);
            } else {
                this.atsIslandMode = true;
            }
        }
    }

    private boolean isExternalGridPresent(ServerWorld world) {
        BlockPos outPos = pos.offset(getOutputPortDirection());
        ElectricalGrid outGrid = GridManager.get(world).getGridAt(outPos);
        if (outGrid == null) {
            return false;
        }

        for (List<IElectricSource> list : outGrid.getSources().values()) {
            for (IElectricSource src : list) {
                // Exclude the inverter's own output source
                if (src == this.outputSource) {
                    continue;
                }
                // Check if an external AC generator or utility source is energizing this network
                if (src.getElectromotiveForce() >= 20.0 && src.getFrequency() > 0.001) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean acceptsInputFrequency(double frequencyHz) {
        return frequencyHz <= 0.001; // DC only
    }

    @Override
    public double getOutputFrequency() {
        return 50.0; // Inverters invert DC to 50 Hz AC
    }

    @Override
    public boolean isOutputConfigurable() {
        return true;
    }

    @Override
    public int getTypeKind() {
        return 1; // Inverter
    }

    @Override
    public double getEfficiency() {
        return inverterType.getEfficiency();
    }

    @Override
    public double getMinInputVoltage() {
        if (nominalInputVoltage <= 15.0) return 10.0;
        if (nominalInputVoltage <= 30.0) return 20.0;
        return 40.0; // 48V battery minimum discharge limit
    }

    @Override
    public double getMaxInputVoltage() {
        if (nominalInputVoltage <= 15.0) return 16.5;
        if (nominalInputVoltage <= 30.0) return 33.0;
        return 66.0; // 48V battery overvoltage protection threshold
    }

    @Override
    public double getMaxOutputCurrent() {
        return inverterType.getMaxPowerWatts() / Math.max(1.0, targetOutputVoltage);
    }

    @Override
    public double getNominalOutputVoltage() {
        return targetOutputVoltage;
    }

    @Override
    public boolean isGridTie() {
        return inverterType.isGridTie() && !atsIslandMode;
    }

    @Override
    public double getTotalHarmonicDistortion() {
        return inverterType.getTotalHarmonicDistortionPercent();
    }

    @Override
    public void tick(ServerWorld world) {
        // Hybrid ESS ATS logic
        if (inverterType.hasAutomaticTransferSwitch()) {
            boolean extGrid = isExternalGridPresent(world);
            if (!extGrid) {
                // External utility/generator grid unpowered or absent:
                // ATS operates in islanded off-grid battery backup mode
                this.atsIslandMode = true;
                this.antiIslandingTicks = 0;
            } else {
                // External grid energized: ATS synchronizes with external grid
                this.atsIslandMode = false;
            }
        }

        super.tick(world);
    }

    @Override
    protected double computeOutputVoltage(double inputVoltage) {
        return targetOutputVoltage;
    }

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        this.atsIslandMode = view.getBoolean("ats_island_mode", false);
        this.nominalInputVoltage = view.getDouble("nominal_input_voltage", 48.0);
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        view.putBoolean("ats_island_mode", this.atsIslandMode);
        view.putDouble("nominal_input_voltage", this.nominalInputVoltage);
    }
}
