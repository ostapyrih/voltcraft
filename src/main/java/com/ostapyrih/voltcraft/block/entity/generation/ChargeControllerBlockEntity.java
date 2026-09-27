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

    // --- Fixed converter ratings ---
    private static final double DEFAULT_BANK_VOLTAGE = 24.0;
    private static final double MIN_INPUT_VOLTAGE = 15.0; // Minimum input to start MPPT tracking
    private static final double MAX_INPUT_VOLTAGE = 150.0; // 150V Max PV open-circuit rating
    private static final double MAX_OUTPUT_CURRENT_A = 60.0; // 60A charge controller rating
    private static final double EFFICIENCY = 0.98; // 98% MPPT synchronous buck efficiency
    private static final double MIN_EFFICIENCY_FLOOR = 0.1; // guards against div-by-near-zero
    private static final double FLOAT_IDLE_DRAW_W = 2.0; // controller's own housekeeping draw in Float
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

    /** Snaps a raw requested voltage to the nearest supported nominal bank: 12V, 24V, or 48V. */
    private static double classifyBank(double target) {
        if (target <= BANK_12V_MAX) return 12.0;
        if (target <= BANK_24V_MAX) return 24.0;
        return 48.0;
    }

    /** The electrical grid attached to this converter's output port, or {@code null} off-thread/unconnected. */
    private ElectricalGrid getOutputGrid() {
        if (!(world instanceof ServerWorld sw)) {
            return null;
        }
        return GridManager.get(sw).getGridAt(getOutputPos());
    }

    private BlockPos getOutputPos() {
        return pos.offset(getOutputPortDirection());
    }

    public boolean hasDownstreamStorage() {
        ElectricalGrid outGrid = getOutputGrid();
        if (outGrid == null) {
            return false;
        }
        for (List<IElectricSource> list : outGrid.getSources().values()) {
            for (IElectricSource src : list) {
                if (src instanceof IElectricStorage) {
                    return true;
                }
            }
        }
        return false;
    }

    public double getDownstreamRailVoltage() {
        ElectricalGrid outGrid = getOutputGrid();
        return outGrid != null ? outGrid.getNodeVoltage(getOutputPos()) : 0.0;
    }

    public boolean isBatteryStorageMismatch() {
        ElectricalGrid outGrid = getOutputGrid();
        if (outGrid == null) {
            return false;
        }
        for (List<IElectricSource> list : outGrid.getSources().values()) {
            for (IElectricSource src : list) {
                if (src instanceof IElectricStorage storage && isNominalVoltageMismatched(storage.getNominalVoltage())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Checks a connected battery's nominal voltage rating against the standard bank rating. */
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
        if (hasDownstreamStorage()) {
            return isBatteryStorageMismatch();
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

    /**
     * {@link #getUpstreamAvailableSolarWatts()}, with a fallback to whatever this converter is
     * already drawing when no panel can be located but power is demonstrably flowing in (e.g.
     * a grid topology query missed the panel this tick).
     */
    private double effectiveAvailableSolarWatts() {
        double availSolar = getUpstreamAvailableSolarWatts();
        if (availSolar <= 0.0 && inputVoltage > DEAD_RAIL_VOLTAGE && inputPowerWatts > 0.0) {
            availSolar = inputPowerWatts;
        }
        return availSolar;
    }

    @Override
    protected double calculateInputPowerDemand() {
        if (tripped || inputVoltage <= DEAD_RAIL_VOLTAGE) return 0.0;
        double availSolar = getUpstreamAvailableSolarWatts();
        double eta = Math.max(MIN_EFFICIENCY_FLOOR, getEfficiency());

        // In Bulk or Absorption mode, harvest full available solar power to feed the battery DC bus
        // and support any downstream loads (such as inverters or DC appliances).
        if (mpptLogic.getStage() == MPPTLogic.ChargeStage.BULK || mpptLogic.getStage() == MPPTLogic.ChargeStage.ABSORPTION) {
            if (availSolar > 0.0) {
                return availSolar;
            }
        }

        // Float mode: battery is topped up, throttle intake to ONLY what is consumed by bus + losses
        // Do NOT draw available solar - that would dissipate excess as heat in the converter.
        return (outputPowerWatts / eta) + (tripped ? 0.0 : FLOAT_IDLE_DRAW_W);
    }

    @Override
    protected double calculateAvailableOutputCurrent() {
        if (tripped || inputVoltage <= DEAD_RAIL_VOLTAGE) return 0.0;

        double availSolar = effectiveAvailableSolarWatts();
        if (availSolar <= 0.0) {
            return 0.0;
        }

        if (hasDownstreamStorage() && isBatteryStorageMismatch()) {
            return 0.0;
        }

        double railV = getDownstreamRailVoltage();
        double targetV = railV > DEAD_RAIL_VOLTAGE ? railV : Math.max(DEAD_RAIL_VOLTAGE, targetOutputVoltage);

        // In Float stage, limit output current to what battery actually accepts
        // (output power / voltage), not what panels could theoretically provide
        double maxAmps;
        if (mpptLogic.getStage() == MPPTLogic.ChargeStage.FLOAT) {
            maxAmps = (outputPowerWatts / Math.max(MIN_EFFICIENCY_FLOOR, getEfficiency())) / targetV;
        } else {
            maxAmps = (availSolar * getEfficiency()) / targetV;
        }
        return Math.clamp(maxAmps, 0.0, getMaxOutputCurrent());
    }

    @Override
    protected double computeOutputVoltage(double inputVoltage) {
        mpptLogic.setBatteryBankVoltage(targetOutputVoltage);
        double availSolar = effectiveAvailableSolarWatts();
        if (tripped || availSolar <= 0.0 || inputVoltage < getMinInputVoltage()) {
            return 0.0;
        }

        boolean hasStorage = hasDownstreamStorage();

        if (hasStorage) {
            if (isBatteryStorageMismatch()) {
                return 0.0;
            }
            double railV = getDownstreamRailVoltage();
            double battV = railV > DEAD_RAIL_VOLTAGE ? railV : targetOutputVoltage;
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