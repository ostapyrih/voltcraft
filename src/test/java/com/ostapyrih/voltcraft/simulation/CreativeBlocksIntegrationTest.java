package com.ostapyrih.voltcraft.simulation;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.simulation.creative.CreativeGeneratorLogic;
import com.ostapyrih.voltcraft.simulation.creative.CreativeLoadLogic;
import com.ostapyrih.voltcraft.simulation.grid.ElectricalGrid;
import com.ostapyrih.voltcraft.simulation.grid.GridConductor;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CreativeBlocksIntegrationTest {

    @Test
    public void testCreativeGeneratorPresetsAndOutput() {
        BlockPos genPos = new BlockPos(0, 64, 0);
        CreativeGeneratorLogic gen = new CreativeGeneratorLogic(genPos);

        // Initial default: 230V, 1000A, 1mOhm, DC, enabled
        assertEquals(230.0, gen.getVoltage(), 1e-4);
        assertEquals(230.0, gen.getElectromotiveForce(), 1e-4);
        assertEquals(1000.0, gen.getMaxOutputCurrent(), 1e-4);
        assertEquals(0.001, gen.getInternalResistance(), 1e-6);
        assertEquals("DC", gen.getFrequencyDisplay());
        assertTrue(gen.isEnabled());
        assertEquals(ElectricalState.NOMINAL, gen.getElectricalState());

        // Toggle disabled
        gen.toggleEnabled();
        assertFalse(gen.isEnabled());
        assertEquals(0.0, gen.getElectromotiveForce(), 1e-4);
        assertEquals(0.0, gen.getMaxOutputCurrent(), 1e-4);
        assertEquals(ElectricalState.OFF, gen.getElectricalState());

        // Re-enable
        gen.setEnabled(true);
        assertTrue(gen.isEnabled());
        assertEquals(230.0, gen.getElectromotiveForce(), 1e-4);

        // Test cycle presets
        double nextV = gen.cycleVoltage();
        assertEquals(400.0, nextV, 1e-4);

        // Test frequency cycling
        double f = gen.cycleFrequency();
        assertEquals(50.0, f, 1e-4);
        assertEquals("AC 50 Hz", gen.getFrequencyDisplay());

        f = gen.cycleFrequency();
        assertEquals(60.0, f, 1e-4);
        assertEquals("AC 60 Hz", gen.getFrequencyDisplay());

        f = gen.cycleFrequency();
        assertEquals(0.0, f, 1e-4);
        assertEquals("DC", gen.getFrequencyDisplay());
    }

    @Test
    public void testCreativeLoadModesAndResistance() {
        BlockPos loadPos = new BlockPos(1, 64, 0);
        CreativeLoadLogic load = new CreativeLoadLogic(loadPos);

        // Default: CONSTANT_RESISTANCE, 10 Ohms
        assertEquals(CreativeLoadLogic.LoadMode.CONSTANT_RESISTANCE, load.getMode());
        assertEquals(10.0, load.getTargetValue(), 1e-4);
        assertEquals(10.0, load.getEquivalentResistance(), 1e-4);

        // Cycle to CONSTANT_POWER
        load.cycleMode();
        assertEquals(CreativeLoadLogic.LoadMode.CONSTANT_POWER, load.getMode());
        assertEquals(1000.0, load.getTargetValue(), 1e-4);
        // At 230V default bootstrap: R = 230^2 / 1000 = 52.9 Ohms
        assertEquals(52.9, load.getEquivalentResistance(), 0.1);

        // Cycle to CONSTANT_CURRENT
        load.cycleMode();
        assertEquals(CreativeLoadLogic.LoadMode.CONSTANT_CURRENT, load.getMode());
        assertEquals(10.0, load.getTargetValue(), 1e-4);
        // At 230V default bootstrap: R = 230 / 10 = 23 Ohms
        assertEquals(23.0, load.getEquivalentResistance(), 0.1);

        // When disabled, load becomes open circuit (infinite resistance)
        load.toggleEnabled();
        assertFalse(load.isEnabled());
        assertEquals(Double.POSITIVE_INFINITY, load.getEquivalentResistance());
    }

    @Test
    public void testGeneratorDrivingConstantResistanceLoad() {
        BlockPos genPos = new BlockPos(0, 64, 0);
        BlockPos loadPos = new BlockPos(1, 64, 0);

        CreativeGeneratorLogic gen = new CreativeGeneratorLogic(genPos);
        gen.setVoltage(230.0);

        CreativeLoadLogic load = new CreativeLoadLogic(loadPos);
        load.setMode(CreativeLoadLogic.LoadMode.CONSTANT_RESISTANCE);
        load.setTargetValue(23.0); // 23 Ohms

        ElectricalGrid grid = new ElectricalGrid();
        grid.registerSource(genPos, gen);
        grid.registerConsumer(loadPos, load);
        grid.addConductor(new GridConductor(genPos, loadPos, ConductorType.HEAVY_COPPER.toThermalSpec(), 100.0, true));

        // Tick circuit
        grid.tick(null);

        // Terminal voltage should be ~230V (negligible drops with heavy copper and 1mOhm source R)
        assertEquals(230.0, load.getLastMeasuredVoltage(), 0.5);
        // I = 230V / 23 Ohm = 10 A
        assertEquals(10.0, load.getLastDeliveredCurrent(), 0.1);
        // P = 230V * 10A = 2300 W
        assertEquals(2300.0, load.getLastDeliveredPower(), 20.0);
        assertEquals(2300.0, gen.getLastDeliveredPower(), 20.0);

        // Reset counters and verify exact 1.0s (20 ticks) accumulation
        gen.resetEnergy();
        load.resetEnergy();
        for (int i = 0; i < 20; i++) { // 20 ticks = 1.0 second
            grid.tick(null);
        }
        // E = ~2300 Joules
        assertEquals(2300.0, load.getTotalEnergyConsumedJoules(), 30.0);
        assertEquals(2300.0, gen.getTotalEnergyJoules(), 30.0);

        // Reset load energy
        load.resetEnergy();
        assertEquals(0.0, load.getTotalEnergyConsumedJoules(), 1e-6);
    }

    @Test
    public void testGeneratorDrivingConstantPowerLoad() {
        BlockPos genPos = new BlockPos(0, 64, 0);
        BlockPos loadPos = new BlockPos(1, 64, 0);

        CreativeGeneratorLogic gen = new CreativeGeneratorLogic(genPos);
        gen.setVoltage(230.0);

        CreativeLoadLogic load = new CreativeLoadLogic(loadPos);
        load.setMode(CreativeLoadLogic.LoadMode.CONSTANT_POWER);
        load.setTargetValue(1000.0); // 1000 Watts

        ElectricalGrid grid = new ElectricalGrid();
        grid.registerSource(genPos, gen);
        grid.registerConsumer(loadPos, load);
        grid.addConductor(new GridConductor(genPos, loadPos, ConductorType.HEAVY_COPPER.toThermalSpec(), 100.0, true));

        // Bootstrap two ticks to establish equilibrium
        grid.tick(null);
        grid.tick(null);

        assertEquals(230.0, load.getLastMeasuredVoltage(), 0.5);
        // I = 1000W / 230V = ~4.35 A
        assertEquals(4.35, load.getLastDeliveredCurrent(), 0.1);
        // P = 1000 W
        assertEquals(1000.0, load.getLastDeliveredPower(), 15.0);
    }

    @Test
    public void testGeneratorDrivingConstantCurrentLoad() {
        BlockPos genPos = new BlockPos(0, 64, 0);
        BlockPos loadPos = new BlockPos(1, 64, 0);

        CreativeGeneratorLogic gen = new CreativeGeneratorLogic(genPos);
        gen.setVoltage(48.0); // 48V telecom / battery standard

        CreativeLoadLogic load = new CreativeLoadLogic(loadPos);
        load.setMode(CreativeLoadLogic.LoadMode.CONSTANT_CURRENT);
        load.setTargetValue(5.0); // 5 Amperes

        ElectricalGrid grid = new ElectricalGrid();
        grid.registerSource(genPos, gen);
        grid.registerConsumer(loadPos, load);
        grid.addConductor(new GridConductor(genPos, loadPos, ConductorType.HEAVY_COPPER.toThermalSpec(), 100.0, true));

        // Bootstrap two ticks to establish equilibrium
        grid.tick(null);
        grid.tick(null);

        assertEquals(48.0, load.getLastMeasuredVoltage(), 0.5);
        // I = 5.0 A
        assertEquals(5.0, load.getLastDeliveredCurrent(), 0.1);
        // P = 48V * 5A = 240 W
        assertEquals(240.0, load.getLastDeliveredPower(), 10.0);
    }
}
