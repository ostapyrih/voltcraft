package com.ostapyrih.voltcraft.conversion;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.simulation.conversion.EuConverterLogic;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class EuConverterTest {

    @Test
    void testNominal100WConversionProducesOneEuTick() {
        EuConverterLogic converter = new EuConverterLogic(new BlockPos(0, 64, 0));
        assertFalse(converter.isTripped());
        assertEquals(0, converter.getStoredEu());

        // Supply 230V AC @ 25W (V = 230.0V, I = 25.0 / 230.0 A, 50Hz) for 1 tick (0.05s)
        double voltage = 230.0;
        double current = 25.0 / voltage;
        converter.onPowerReceived(voltage, current, 0.05, 50.0);

        assertEquals(ElectricalState.NOMINAL, converter.getElectricalState());
        assertEquals(1, converter.getStoredEu(), "25W continuous should yield exactly 1 EU/t");
        assertEquals(1, converter.getTotalEuGenerated());
    }

    @Test
    void testHighPower3200WConversionProduces32EuTick() {
        EuConverterLogic converter = new EuConverterLogic();

        // 800W @ 230V AC -> 32 EU/t at 25W per EU
        double voltage = 230.0;
        double current = 800.0 / voltage;
        converter.onPowerReceived(voltage, current, 0.05, 50.0);

        assertEquals(ElectricalState.NOMINAL, converter.getElectricalState());
        assertEquals(32, converter.getStoredEu(), "800W should yield 32 EU/t");
        assertEquals(32, converter.getTotalEuGenerated());
    }

    @Test
    void testDcWaveformRejectionProducesZeroEu() {
        EuConverterLogic converter = new EuConverterLogic();

        // Supply 230V DC (0.0 Hz) @ 1000W
        converter.onPowerReceived(230.0, 4.35, 0.05, 0.0);

        assertEquals(ElectricalState.OFF, converter.getElectricalState(), "DC input must be rejected");
        assertEquals(0, converter.getStoredEu(), "No EU should be produced from DC");
        assertFalse(converter.isAcOperatingValid());
    }

    @Test
    void testBrownoutVoltageBelow207VProducesZeroEu() {
        EuConverterLogic converter = new EuConverterLogic();

        // Supply 120V AC (below 207V) @ 50Hz
        converter.onPowerReceived(120.0, 2.0, 0.05, 50.0);

        assertEquals(ElectricalState.BROWNOUT, converter.getElectricalState(), "120V AC should report brownout");
        assertEquals(0, converter.getStoredEu(), "No EU should be produced during brownout");
        assertFalse(converter.isAcOperatingValid());
    }

    @Test
    void testOvervoltageSurgeTrip() {
        EuConverterLogic converter = new EuConverterLogic();

        // Supply 260V AC (> 253V max operating)
        converter.onPowerReceived(260.0, 1.0, 0.05, 50.0);

        assertTrue(converter.isTripped(), "Converter must trip on overvoltage surge");
        assertEquals(ElectricalState.SURGE, converter.getElectricalState());
        assertEquals(0, converter.getStoredEu());

        // Subsequent valid power should still be blocked while tripped
        converter.onPowerReceived(230.0, 0.435, 0.05, 50.0);
        assertEquals(0, converter.getStoredEu(), "Blocked while tripped");

        // Reset trip
        converter.resetTrip();
        assertFalse(converter.isTripped());

        // Now power should be accepted (25W -> 1 EU/t)
        converter.onPowerReceived(230.0, 25.0 / 230.0, 0.05, 50.0);
        assertEquals(1, converter.getStoredEu(), "Power accepted after reset");
    }

    @Test
    void testFractionalPowerAccumulation() {
        EuConverterLogic converter = new EuConverterLogic();

        // 12.5W for 1 tick -> 0.5 EU (stored: 0, accumulator: 0.5)
        converter.onPowerReceived(230.0, 12.5 / 230.0, 0.05, 50.0);
        assertEquals(0, converter.getStoredEu());

        // 12.5W for 2nd tick -> 0.5 + 0.5 = 1.0 EU (stored: 1, accumulator: 0.0)
        converter.onPowerReceived(230.0, 12.5 / 230.0, 0.05, 50.0);
        assertEquals(1, converter.getStoredEu());
    }

    @Test
    void testPowerDemandCalculation() {
        EuConverterLogic converter = new EuConverterLogic();
        converter.onPowerReceived(230.0, 0.0, 0.05, 50.0);

        // Buffer empty (10,000 EU deficit), downstream load 0 -> demand caps buffer replenishment at 32 EU/t
        // Demand = (32 EU/t * 25 W/EU) + 5W idle = 805 W
        converter.updatePowerDemand(0.0);
        assertEquals(805.0, converter.getTargetDemandWatts(), 1e-4);

        // When downstream consumes 10 EU/t
        converter.updatePowerDemand(10.0);
        // Demand = ((10 + 32) * 25) + 5 = 1055 W
        assertEquals(1055.0, converter.getTargetDemandWatts(), 1e-4);

        // When buffer is full and downstream load is 5 EU/t
        converter.setStoredEu(converter.getCapacity());
        converter.updatePowerDemand(5.0);
        // Buffer deficit is 0, so neededEu = 5.0
        // Demand = (5.0 * 25) + 5 = 130 W
        assertEquals(130.0, converter.getTargetDemandWatts(), 1e-4);
    }

    @Test
    void testEnergyExtraction() {
        EuConverterLogic converter = new EuConverterLogic();
        converter.setStoredEu(1000);

        long extracted = converter.extractEu(128);
        assertEquals(128, extracted);
        assertEquals(872, converter.getStoredEu());

        // Clamped by MAX_EXTRACT_RATE (512)
        extracted = converter.extractEu(1000);
        assertEquals(512, extracted);
        assertEquals(360, converter.getStoredEu());
    }

    @Test
    void testThermalDissipationAndCooling() {
        EuConverterLogic converter = new EuConverterLogic();
        converter.setTemperatureCelsius(80.0);

        // Cooling when idle (input power = 0)
        converter.updateThermal(1.0); // 1 second
        assertTrue(converter.getTemperatureCelsius() < 80.0, "Should passively cool towards 20°C");
    }
}
