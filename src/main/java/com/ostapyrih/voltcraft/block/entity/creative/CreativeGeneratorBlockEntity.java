package com.ostapyrih.voltcraft.block.entity.creative;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.api.energy.IElectricSource;
import com.ostapyrih.voltcraft.block.entity.VoltcraftBlockEntityTypes;
import com.ostapyrih.voltcraft.screen.handler.CreativeGeneratorScreenHandler;
import com.ostapyrih.voltcraft.simulation.creative.CreativeGeneratorLogic;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalGrid;
import com.ostapyrih.voltcraft.simulation.grid.GridManager;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Creative-only power generator for testing grid networks, converters, cables, and loads.
 * Provides freely configurable voltage, max current, internal resistance, and DC/AC frequency.
 */
public class CreativeGeneratorBlockEntity extends BlockEntity implements IElectricSource, ExtendedScreenHandlerFactory<BlockPos> {

    private final CreativeGeneratorLogic logic;
    private UUID lastGridId = null;

    private final PropertyDelegate propertyDelegate = new PropertyDelegate() {
        @Override
        public int get(int index) {
            int v = (int) Math.round(logic.getVoltage() * 10.0);
            int iMax = (int) Math.round(logic.getMaxCurrent() * 10.0);
            int rInt = (int) Math.round(logic.getInternalResistance() * 10000.0);
            int outI = (int) Math.round(logic.getLastDeliveredCurrent() * 100.0);
            int outP = (int) Math.round(logic.getLastDeliveredPower() * 10.0);
            int kJ = (int) Math.round(logic.getTotalEnergyJoules() / 1000.0);

            return switch (index) {
                case CreativeGeneratorScreenHandler.PROP_VOLTAGE_LOW -> CreativeGeneratorScreenHandler.packLow(v);
                case CreativeGeneratorScreenHandler.PROP_VOLTAGE_HIGH -> CreativeGeneratorScreenHandler.packHigh(v);
                case CreativeGeneratorScreenHandler.PROP_CURRENT_LIMIT_LOW -> CreativeGeneratorScreenHandler.packLow(iMax);
                case CreativeGeneratorScreenHandler.PROP_CURRENT_LIMIT_HIGH -> CreativeGeneratorScreenHandler.packHigh(iMax);
                case CreativeGeneratorScreenHandler.PROP_R_INT_LOW -> CreativeGeneratorScreenHandler.packLow(rInt);
                case CreativeGeneratorScreenHandler.PROP_R_INT_HIGH -> CreativeGeneratorScreenHandler.packHigh(rInt);
                case CreativeGeneratorScreenHandler.PROP_FREQUENCY_X10 -> (int) Math.round(logic.getFrequency() * 10.0);
                case CreativeGeneratorScreenHandler.PROP_ENABLED -> logic.isEnabled() ? 1 : 0;
                case CreativeGeneratorScreenHandler.PROP_OUT_CURRENT_LOW -> CreativeGeneratorScreenHandler.packLow(outI);
                case CreativeGeneratorScreenHandler.PROP_OUT_CURRENT_HIGH -> CreativeGeneratorScreenHandler.packHigh(outI);
                case CreativeGeneratorScreenHandler.PROP_OUT_POWER_LOW -> CreativeGeneratorScreenHandler.packLow(outP);
                case CreativeGeneratorScreenHandler.PROP_OUT_POWER_HIGH -> CreativeGeneratorScreenHandler.packHigh(outP);
                case CreativeGeneratorScreenHandler.PROP_ENERGY_LOW -> CreativeGeneratorScreenHandler.packLow(kJ);
                case CreativeGeneratorScreenHandler.PROP_ENERGY_HIGH -> CreativeGeneratorScreenHandler.packHigh(kJ);
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {}

        @Override
        public int size() {
            return CreativeGeneratorScreenHandler.PROPERTY_COUNT;
        }
    };

    public CreativeGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(VoltcraftBlockEntityTypes.CREATIVE_GENERATOR_BLOCK_ENTITY, pos, state);
        this.logic = new CreativeGeneratorLogic(pos);
    }

    public PropertyDelegate getPropertyDelegate() {
        return propertyDelegate;
    }

    @Override
    public Text getDisplayName() {
        return Text.translatable(getCachedState().getBlock().getTranslationKey());
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
        return new CreativeGeneratorScreenHandler(syncId, playerInventory, this.pos, this.propertyDelegate);
    }

    @Override
    public BlockPos getScreenOpeningData(ServerPlayerEntity player) {
        return this.pos;
    }

    public CreativeGeneratorLogic getLogic() {
        return logic;
    }

    public double getVoltage() {
        return logic.getVoltage();
    }

    public void setVoltage(double voltage) {
        logic.setVoltage(voltage);
        markDirty();
    }

    public void setMaxCurrent(double maxCurrent) {
        logic.setMaxCurrent(maxCurrent);
        markDirty();
    }

    public void setInternalResistance(double internalResistance) {
        logic.setInternalResistance(internalResistance);
        markDirty();
    }

    public double getFrequency() {
        return logic.getFrequency();
    }

    public void setFrequency(double frequency) {
        logic.setFrequency(frequency);
        markDirty();
    }

    public boolean isEnabled() {
        return logic.isEnabled();
    }

    public void setEnabled(boolean enabled) {
        logic.setEnabled(enabled);
        markDirty();
    }

    public void toggleEnabled() {
        logic.toggleEnabled();
        markDirty();
    }

    public double getLastDeliveredCurrent() {
        return logic.getLastDeliveredCurrent();
    }

    public double getLastDeliveredPower() {
        return logic.getLastDeliveredPower();
    }

    public double getTotalEnergyJoules() {
        return logic.getTotalEnergyJoules();
    }

    public void resetEnergy() {
        logic.resetEnergy();
        markDirty();
    }

    public String getFrequencyDisplay() {
        return logic.getFrequencyDisplay();
    }

    public double cycleVoltage() {
        double v = logic.cycleVoltage();
        markDirty();
        return v;
    }

    public double cycleCurrentLimit() {
        double c = logic.cycleCurrentLimit();
        markDirty();
        return c;
    }

    public double cycleInternalResistance() {
        double r = logic.cycleInternalResistance();
        markDirty();
        return r;
    }

    public double cycleFrequency() {
        double f = logic.cycleFrequency();
        markDirty();
        return f;
    }

    @Override
    public void markRemoved() {
        super.markRemoved();
        onRemovedFromWorld();
    }

    public void tick(ServerWorld world) {
        GridManager gridManager = GridManager.get(world);
        ElectricalGrid currentGrid = gridManager.getGridAt(pos);

        // Self-heal / seed grid node if missing on chunk/world load
        if (currentGrid == null) {
            gridManager.onConductorPlaced(world, pos, com.ostapyrih.voltcraft.block.cable.ConductorType.HEAVY_COPPER);
            currentGrid = gridManager.getGridAt(pos);
        }

        UUID currentGridId = currentGrid != null ? currentGrid.getGridId() : null;

        if (!Objects.equals(currentGridId, lastGridId)) {
            if (lastGridId != null) {
                for (ElectricalGrid g : gridManager.getAllGrids()) {
                    if (g.getGridId().equals(lastGridId)) {
                        g.unregisterSource(pos, this);
                        break;
                    }
                }
            }
            if (currentGrid != null) {
                currentGrid.registerSource(pos, this);
            }
            lastGridId = currentGridId;
        } else if (currentGrid != null) {
            List<IElectricSource> registered = currentGrid.getSources().get(pos);
            if (registered == null || !registered.contains(this)) {
                currentGrid.registerSource(pos, this);
            }
        }

        // Disconnection safety: if unattached to any grid, immediately zero out measurements
        if (currentGrid == null) {
            this.logic.onPowerDrawn(0.0, 0.05);
        }
    }

    public void onRemovedFromWorld() {
        if (world instanceof ServerWorld sw) {
            GridManager gm = GridManager.get(sw);
            for (ElectricalGrid g : gm.getAllGrids()) {
                g.unregisterSource(pos, this);
            }
            ElectricalGrid grid = gm.getGridAt(pos);
            if (grid != null) {
                grid.unregisterSource(pos, this);
            }
        }
        this.logic.onPowerDrawn(0.0, 0.05);
    }

    // ==================== IElectricComponent ====================

    @Override
    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public ElectricalState getElectricalState() {
        return logic.getElectricalState();
    }

    @Override
    public void setElectricalState(ElectricalState state) {
        logic.setElectricalState(state);
    }

    // ==================== IElectricSource ====================

    @Override
    public double getElectromotiveForce() {
        return logic.getElectromotiveForce();
    }

    @Override
    public double getInternalResistance() {
        return logic.getInternalResistance();
    }

    @Override
    public double getMaxOutputCurrent() {
        return logic.getMaxOutputCurrent();
    }

    @Override
    public void onPowerDrawn(double currentAmps, double durationSeconds) {
        logic.onPowerDrawn(currentAmps, durationSeconds);
    }

    // ==================== Serialization ====================

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        logic.setVoltage(view.getDouble("voltage", 230.0));
        logic.setMaxCurrent(view.getDouble("max_current", 1000.0));
        logic.setInternalResistance(view.getDouble("internal_resistance", 0.001));
        logic.setFrequency(view.getDouble("frequency", 0.0));
        logic.setEnabled(view.getBoolean("enabled", true));
        logic.setTotalEnergyJoules(view.getDouble("total_energy", 0.0));
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        view.putDouble("voltage", logic.getVoltage());
        view.putDouble("max_current", logic.getMaxCurrent());
        view.putDouble("internal_resistance", logic.getInternalResistance());
        view.putDouble("frequency", logic.getFrequency());
        view.putBoolean("enabled", logic.isEnabled());
        view.putDouble("total_energy", logic.getTotalEnergyJoules());
    }
}
