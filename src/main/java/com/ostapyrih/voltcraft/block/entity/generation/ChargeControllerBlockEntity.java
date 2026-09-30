package com.ostapyrih.voltcraft.block.entity.generation;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.block.entity.conversion.AbstractPowerConverterBlockEntity;
import com.ostapyrih.voltcraft.block.entity.storage.BatteryBlockEntity;
import com.ostapyrih.voltcraft.block.entity.storage.BatteryRackBlockEntity;
import com.ostapyrih.voltcraft.api.grid.KernelAttachedBlock;
import com.ostapyrih.voltcraft.screen.handler.ConverterScreenHandler;
import com.ostapyrih.voltcraft.simulation.electrical.ConverterElement;
import com.ostapyrih.voltcraft.simulation.generation.MPPTLogic;
import com.ostapyrih.voltcraft.simulation.grid.GridManager;
import com.ostapyrih.voltcraft.simulation.grid.IslandContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * MPPT solar charge controller (Perturb &amp; Observe) with multi-stage battery charging.
 * Snaps to 12/24/48 V banks with mismatch protection; load is limited to upstream
 * solar availability. Reuses the shared converter 4-terminal kernel pattern.
 */
public class ChargeControllerBlockEntity extends AbstractPowerConverterBlockEntity {

    // --- Fixed converter ratings ---
    private static final double DEFAULT_BANK_VOLTAGE = 24.0;
    private static final double MIN_INPUT_VOLTAGE = 15.0; // Minimum input to start MPPT tracking
    private static final double MAX_INPUT_VOLTAGE = 150.0; // 150V Max PV open-circuit rating
    private static final double MAX_OUTPUT_CURRENT_A = 60.0; // 60A charge controller rating
    private static final double EFFICIENCY = 0.98; // 98% MPPT synchronous buck efficiency
    private static final int TRIP_GRACE_TICKS = 40;
    private static final double DEAD_RAIL_VOLTAGE = 1.0;

    // --- Battery bank classification (12V / 24V / 48V) ---
    private static final double BANK_12V_MAX = 15.0;
    private static final double BANK_24V_MAX = 30.0;

    // --- Live terminal-voltage sanity ranges per bank (isBatteryVoltageMismatch) ---
    private static final double SAFE_12V_MIN = 8.0;
    private static final double SAFE_12V_MAX = 17.0;
    private static final double SAFE_24V_MIN = 17.0;
    private static final double SAFE_24V_MAX = 34.0;
    private static final double SAFE_48V_MIN = 34.0;
    private static final double SAFE_48V_MAX = 68.0;

    // --- Storage nominal-voltage mismatch thresholds (isBatteryStorageMismatch) ---
    private static final double STORAGE_NOMINAL_12V_LIMIT = 18.0;
    private static final double STORAGE_NOMINAL_24V_LIMIT = 36.0;

    private final MPPTLogic mpptLogic = new MPPTLogic();

    public ChargeControllerBlockEntity(BlockPos pos, BlockState state) {
        super(VoltcraftBlockEntityTypes.CHARGE_CONTROLLER_BLOCK_ENTITY, pos, state);
        this.targetOutputVoltage = DEFAULT_BANK_VOLTAGE;
        mpptLogic.setBatteryBankVoltage(DEFAULT_BANK_VOLTAGE);
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
        return EFFICIENCY;
    }

    @Override
    public double getMinInputVoltage() {
        return MIN_INPUT_VOLTAGE;
    }

    @Override
    public double getMaxInputVoltage() {
        return MAX_INPUT_VOLTAGE;
    }

    @Override
    public double getMaxOutputCurrent() {
        return MAX_OUTPUT_CURRENT_A;
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
        double bank = classifyBank(target);
        super.setTargetOutputVoltage(bank);
        mpptLogic.setBatteryBankVoltage(bank);
        this.tripGraceTicks = TRIP_GRACE_TICKS;
        markDirty();
    }

    private static double classifyBank(double target) {
        if (target <= BANK_12V_MAX) return 12.0;
        if (target <= BANK_24V_MAX) return 24.0;
        return 48.0;
    }

    private BlockPos getOutputPos() {
        return pos.offset(getOutputPortDirection());
    }

    public boolean hasDownstreamStorage() {
        BlockEntity out = world instanceof ServerWorld sw ? sw.getBlockEntity(getOutputPos()) : null;
        if (out instanceof BatteryBlockEntity || out instanceof BatteryRackBlockEntity) {
            return true;
        }
        return getDownstreamRailVoltage() > DEAD_RAIL_VOLTAGE;
    }

    public double getDownstreamRailVoltage() {
        return actualOutputVoltage;
    }

    public boolean isBatteryStorageMismatch() {
        BlockEntity out = world instanceof ServerWorld sw ? sw.getBlockEntity(getOutputPos()) : null;
        if (out instanceof BatteryBlockEntity battery) {
            double nominal = battery.getChemistry().getNominalVoltage()
                * Math.max(1, battery.getSeriesCount());
            return isNominalVoltageMismatched(nominal);
        }
        return false;
    }

    private boolean isNominalVoltageMismatched(double nom) {
        double bank = targetOutputVoltage;
        if (bank <= BANK_12V_MAX) {
            return nom > STORAGE_NOMINAL_12V_LIMIT;
        }
        if (bank <= BANK_24V_MAX) {
            return nom < STORAGE_NOMINAL_12V_LIMIT || nom > STORAGE_NOMINAL_24V_LIMIT;
        }
        return nom < STORAGE_NOMINAL_24V_LIMIT;
    }

    public boolean isBatteryVoltageMismatch(double battV) {
        if (isBatteryStorageMismatch()) {
            return true;
        }
        if (battV <= DEAD_RAIL_VOLTAGE) {
            return false;
        }
        double bank = targetOutputVoltage;
        if (bank <= BANK_12V_MAX) {
            return battV < SAFE_12V_MIN || battV > SAFE_12V_MAX;
        } else if (bank <= BANK_24V_MAX) {
            return battV < SAFE_24V_MIN || battV > SAFE_24V_MAX;
        } else {
            return battV < SAFE_48V_MIN || battV > SAFE_48V_MAX;
        }
    }

    @Override
    public boolean isOutputCurrentRegulated() {
        return !tripped && hasDownstreamStorage() && mpptLogic.getStage() == MPPTLogic.ChargeStage.BULK
            && getUpstreamAvailableSolarWatts() > 0.0;
    }

    public double getUpstreamAvailableSolarWatts() {
        if (world instanceof ServerWorld sw) {
            GridManager gm = GridManager.get(sw);
            IslandContext island = gm.getIslandAt(pos);
            if (island != null) {
                double total = 0.0;
                for (KernelAttachedBlock kab : island.blocks()) {
                    if (kab instanceof SolarPanelBlockEntity sp) {
                        total += sp.getPeakPowerAvailable();
                    }
                }
                if (total > 0.0) {
                    return total;
                }
            }

            Direction inDir = getInputPortDirection();
            BlockPos inPos = pos.offset(inDir);

            // Direct attachment: panel block entity at the input port position.
            BlockEntity be = sw.getBlockEntity(inPos);
            if (be instanceof SolarPanelBlockEntity sp) {
                return sp.getPeakPowerAvailable();
            }

            // Panel replacement tolerance: all 6 neighbors of the input position.
            for (Direction dir : Direction.values()) {
                BlockPos neighborPos = inPos.offset(dir);
                BlockEntity neighborBe = sw.getBlockEntity(neighborPos);
                if (neighborBe instanceof SolarPanelBlockEntity sp) {
                    return sp.getPeakPowerAvailable();
                }
            }
        }
        if (inputVoltage > DEAD_RAIL_VOLTAGE && inputPowerWatts > 0.0) {
            return inputPowerWatts;
        }
        return 0.0;
    }

    @Override
    protected double computeOutputVoltage(double inputVoltage) {
        mpptLogic.setBatteryBankVoltage(targetOutputVoltage);
        if (tripped || inputVoltage < getMinInputVoltage()) {
            return 0.0;
        }

        if (hasDownstreamStorage() && isBatteryVoltageMismatch(actualOutputVoltage)) {
            return 0.0;
        }

        double railV = getDownstreamRailVoltage();
        double battV = railV > DEAD_RAIL_VOLTAGE ? railV : targetOutputVoltage;
        boolean settled = MPPTLogic.isRailSettled(inputVoltage, getMinInputVoltage(),
                mpptLogic.getTargetInputVoltage(), calculateInputPowerDemand(), lastDemandWatts);
        mpptLogic.step(inputVoltage, inputCurrentAmps, battV, settled);

        double targetV;
        if (mpptLogic.getStage() == MPPTLogic.ChargeStage.BULK
                || mpptLogic.getStage() == MPPTLogic.ChargeStage.ABSORPTION) {
            targetV = mpptLogic.getAbsorptionVoltage();
        } else {
            targetV = mpptLogic.getFloatVoltage();
        }

        // Limit output EMF according to available upstream solar power to prevent overloading solar panels
        double availSolar = getUpstreamAvailableSolarWatts();
        if (availSolar > 0.0 && actualOutputVoltage > DEAD_RAIL_VOLTAGE) {
            double pOutMax = availSolar * getEfficiency();
            double iMax = Math.min(MAX_OUTPUT_CURRENT_A, pOutMax / Math.max(1.0, actualOutputVoltage));
            double vMaxFoldback = actualOutputVoltage + iMax * ConverterElement.SOURCE_R_OHM;
            targetV = Math.min(targetV, vMaxFoldback);
        }

        return targetV;
    }

    @Override
    public void tickElectrical(ServerWorld world) {
        super.tickElectrical(world);
        double availSolar = getUpstreamAvailableSolarWatts();
        if (availSolar > 0.0) {
            this.stagedInputDemandWatts = Math.min(this.stagedInputDemandWatts, availSolar);
        }
        if (hasDownstreamStorage() && isBatteryVoltageMismatch(actualOutputVoltage)) {
            if (tripGraceTicks == 0) {
                this.tripped = true;
                this.reportedState = ElectricalState.SURGE;
            }
        }
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