package com.ostapyrih.voltcraft.block.entity.generation;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.energy.IElectricSource;
import com.ostapyrih.voltcraft.api.energy.IElectricStorage;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.block.entity.conversion.AbstractPowerConverterBlockEntity;
import com.ostapyrih.voltcraft.screen.handler.ConverterScreenHandler;
import com.ostapyrih.voltcraft.simulation.generation.MPPTLogic;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalGrid;
import com.ostapyrih.voltcraft.simulation.grid.GridManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;

/**
 * Block entity for the MPPT Solar Charge Controller.
 * Executes Maximum Power Point Tracking (Perturb &amp; Observe) and multi-stage battery charging.
 * Limits load to what is available from upstream solar generation to prevent solar voltage drop.
 * Synchronizes with 12V, 24V, and 48V battery banks with active mismatch protection.
 */
public class ChargeControllerBlockEntity extends AbstractPowerConverterBlockEntity {

    private final MPPTLogic mpptLogic = new MPPTLogic();

    public ChargeControllerBlockEntity(BlockPos pos, BlockState state) {
        super(VoltcraftBlockEntityTypes.CHARGE_CONTROLLER_BLOCK_ENTITY, pos, state);
        this.targetOutputVoltage = 24.0;
        mpptLogic.setBatteryBankVoltage(24.0);
    }

    public MPPTLogic getMpptLogic() {
        return mpptLogic;
    }

    @Override
    public boolean acceptsInputFrequency(double frequencyHz) {
        return frequencyHz <= 0.001; // DC only from solar panels
    }

    @Override
    public double getOutputFrequency() {
        return 0.0; // DC output to battery
    }

    @Override
    public boolean isOutputConfigurable() {
        return true;
    }

    @Override
    public int getTypeKind() {
        return ConverterScreenHandler.TYPE_CHARGE_CONTROLLER;
    }

    @Override
    public double getEfficiency() {
        return 0.98; // 98% MPPT synchronous buck efficiency
    }

    @Override
    public double getMinInputVoltage() {
        return 15.0; // Minimum input to start MPPT tracking
    }

    @Override
    public double getMaxInputVoltage() {
        return 150.0; // 150V Max PV open-circuit rating
    }

    @Override
    public double getMaxOutputCurrent() {
        return 60.0; // 60A charge controller rating
    }

    @Override
    public double getNominalOutputVoltage() {
        return targetOutputVoltage;
    }

    @Override
    public boolean isGridTie() {
        return false;
    }

    @Override
    public double getTotalHarmonicDistortion() {
        return 0.0;
    }

    @Override
    public void setTargetOutputVoltage(double target) {
        double bank = target <= 15.0 ? 12.0 : (target <= 30.0 ? 24.0 : 48.0);
        super.setTargetOutputVoltage(bank);
        mpptLogic.setBatteryBankVoltage(bank);
        this.tripGraceTicks = 40;
        markDirty();
    }

    public boolean hasDownstreamStorage() {
        if (world instanceof ServerWorld sw) {
            BlockPos outPos = pos.offset(getOutputPortDirection());
            ElectricalGrid outGrid = GridManager.get(sw).getGridAt(outPos);
            if (outGrid != null) {
                for (List<IElectricSource> list : outGrid.getSources().values()) {
                    for (IElectricSource src : list) {
                        if (src instanceof IElectricStorage) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public double getDownstreamRailVoltage() {
        if (world instanceof ServerWorld sw) {
            BlockPos outPos = pos.offset(getOutputPortDirection());
            ElectricalGrid outGrid = GridManager.get(sw).getGridAt(outPos);
            if (outGrid != null) {
                return outGrid.getNodeVoltage(outPos);
            }
        }
        return 0.0;
    }

    public boolean isBatteryStorageMismatch() {
        if (world instanceof ServerWorld sw) {
            BlockPos outPos = pos.offset(getOutputPortDirection());
            ElectricalGrid outGrid = GridManager.get(sw).getGridAt(outPos);
            if (outGrid != null) {
                for (List<IElectricSource> list : outGrid.getSources().values()) {
                    for (IElectricSource src : list) {
                        if (src instanceof IElectricStorage storage) {
                            double nom = storage.getNominalVoltage();
                            double bank = targetOutputVoltage;
                            // Check against standard bank rating
                            if (bank <= 15.0 && nom > 18.0) return true;
                            if (bank > 15.0 && bank <= 30.0 && (nom < 18.0 || nom > 36.0)) return true;
                            if (bank > 30.0 && nom < 36.0) return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public boolean isBatteryVoltageMismatch(double battV) {
        if (hasDownstreamStorage()) {
            return isBatteryStorageMismatch();
        }
        if (battV <= 1.0) return false;
        double bank = targetOutputVoltage;
        if (bank <= 15.0) {
            return battV < 8.0 || battV > 17.0;
        } else if (bank <= 30.0) {
            return battV < 17.0 || battV > 34.0;
        } else {
            return battV < 34.0 || battV > 68.0;
        }
    }

    @Override
    public boolean isOutputCurrentRegulated() {
        return !tripped && hasDownstreamStorage() && mpptLogic.getStage() == MPPTLogic.ChargeStage.BULK
            && getUpstreamAvailableSolarWatts() > 0.0;
    }

    /**
     * Queries upstream generation capacity from connected solar panels.
     */
    public double getUpstreamAvailableSolarWatts() {
        if (world instanceof ServerWorld sw) {
            Direction inDir = getInputPortDirection();
            BlockPos inPos = pos.offset(inDir);
            GridManager gm = GridManager.get(sw);
            ElectricalGrid inGrid = gm.getGridAt(inPos);
            if (inGrid != null) {
                double total = 0.0;
                for (List<IElectricSource> list : inGrid.getSources().values()) {
                    for (IElectricSource src : list) {
                        if (src instanceof SolarPanelBlockEntity sp) {
                            total += sp.getPeakPowerAvailable();
                        } else if (src != null && src.getElectromotiveForce() > 0.0) {
                            total += src.getElectromotiveForce() * src.getMaxOutputCurrent();
                        }
                    }
                }
                if (total > 0.0) return total;
            }

            // Fallback: check input port position for direct panel connection
            BlockEntity be = sw.getBlockEntity(inPos);
            if (be instanceof SolarPanelBlockEntity sp) {
                return sp.getPeakPowerAvailable();
            }

            // Fallback 2: check all 6 neighbors for solar panels (handles panel replacement
            // without cable reconnect - panels register at their own position, not wire position)
            for (Direction dir : Direction.values()) {
                BlockPos neighborPos = inPos.offset(dir);
                BlockEntity neighborBe = sw.getBlockEntity(neighborPos);
                if (neighborBe instanceof SolarPanelBlockEntity sp) {
                    return sp.getPeakPowerAvailable();
                }
            }
        }
        return 0.0;
    }

    @Override
    protected double calculateInputPowerDemand() {
        if (tripped || inputVoltage <= 1.0) return 0.0;
        double availSolar = getUpstreamAvailableSolarWatts();
        double eta = Math.max(0.1, getEfficiency());

        // In Bulk or Absorption mode, harvest full available solar power to feed the battery DC bus
        // and support any downstream loads (such as inverters or DC appliances).
        if (mpptLogic.getStage() == MPPTLogic.ChargeStage.BULK || mpptLogic.getStage() == MPPTLogic.ChargeStage.ABSORPTION) {
            if (availSolar > 0.0) {
                return availSolar;
            }
        }

        // Float mode: battery is topped up, throttle intake to ONLY what is consumed by bus + losses
        // Do NOT draw available solar - that would dissipate excess as heat in the converter.
        return (outputPowerWatts / eta) + (tripped ? 0.0 : 2.0);
    }

    @Override
    protected double calculateAvailableOutputCurrent() {
        if (tripped || inputVoltage <= 1.0) return 0.0;

        double availSolar = getUpstreamAvailableSolarWatts();
        if (availSolar <= 0.0 && inputVoltage > 1.0 && inputPowerWatts > 0.0) {
            availSolar = inputPowerWatts;
        }
        if (availSolar <= 0.0) {
            return 0.0;
        }

        if (hasDownstreamStorage() && isBatteryStorageMismatch()) {
            return 0.0;
        }

        double railV = getDownstreamRailVoltage();
        double targetV = railV > 1.0 ? railV : Math.max(1.0, targetOutputVoltage);

        // In Float stage, limit output current to what battery actually accepts
        // (output power / voltage), not what panels could theoretically provide
        double maxAmps;
        if (mpptLogic.getStage() == MPPTLogic.ChargeStage.FLOAT) {
            maxAmps = (outputPowerWatts / Math.max(0.1, getEfficiency())) / targetV;
        } else {
            maxAmps = (availSolar * getEfficiency()) / targetV;
        }
        return Math.clamp(maxAmps, 0.0, getMaxOutputCurrent());
    }

    @Override
    protected double computeOutputVoltage(double inputVoltage) {
        mpptLogic.setBatteryBankVoltage(targetOutputVoltage);
        double availSolar = getUpstreamAvailableSolarWatts();
        if (availSolar <= 0.0 && inputVoltage > 1.0 && inputPowerWatts > 0.0) {
            availSolar = inputPowerWatts;
        }
        if (tripped || availSolar <= 0.0 || inputVoltage < getMinInputVoltage()) {
            return 0.0;
        }

        boolean hasStorage = hasDownstreamStorage();

        if (hasStorage) {
            if (isBatteryStorageMismatch()) {
                return 0.0;
            }
            double railV = getDownstreamRailVoltage();
            double battV = railV > 1.0 ? railV : targetOutputVoltage;
            boolean settled = MPPTLogic.isRailSettled(inputVoltage, getMinInputVoltage(),
                mpptLogic.getTargetInputVoltage(), calculateInputPowerDemand(), lastDemandWatts);
            mpptLogic.step(inputVoltage, inputCurrentAmps, battV, settled);

            if (mpptLogic.getStage() == MPPTLogic.ChargeStage.BULK || mpptLogic.getStage() == MPPTLogic.ChargeStage.ABSORPTION) {
                return mpptLogic.getAbsorptionVoltage();
            } else {
                return mpptLogic.getFloatVoltage();
            }
        }

        // Direct standalone output without battery (powering inverters or DC loads directly)
        return targetOutputVoltage;
    }

    @Override
    public void tick(ServerWorld world) {
        if (hasDownstreamStorage() && isBatteryStorageMismatch()) {
            if (tripGraceTicks == 0) {
                this.tripped = true;
                this.reportedState = ElectricalState.SURGE;
            }
        }
        super.tick(world);
    }

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        mpptLogic.setBatteryBankVoltage(this.targetOutputVoltage);
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
    }
}
