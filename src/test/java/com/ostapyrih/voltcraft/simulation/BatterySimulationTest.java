package com.ostapyrih.voltcraft.simulation;

import com.ostapyrih.voltcraft.simulation.chemistry.BatteryChemistry;
import com.ostapyrih.voltcraft.simulation.chemistry.BatterySimulation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class BatterySimulationTest {

    @Test
    @DisplayName("Verify OCV vs SoC curves for Li-Ion, LiFePO4, and Lead-Acid")
    void testOpenCircuitVoltageCurves() {
        // Li-Ion 18650: 2.8V cutoff, 4.2V full charge, ~3.7V nominal mid-plateau
        double vLi0 = BatterySimulation.getOpenCircuitVoltage(BatteryChemistry.LI_ION_18650, 0.0);
        double vLi50 = BatterySimulation.getOpenCircuitVoltage(BatteryChemistry.LI_ION_18650, 0.5);
        double vLi100 = BatterySimulation.getOpenCircuitVoltage(BatteryChemistry.LI_ION_18650, 1.0);

        assertEquals(2.8, vLi0, 0.01, "0% SoC should match cutoff voltage");
        assertTrue(vLi50 >= 3.6 && vLi50 <= 3.85, "50% SoC should be on nominal plateau ~3.7V: " + vLi50);
        assertEquals(4.2, vLi100, 0.01, "100% SoC should match full charge voltage");

        // LiFePO4: 2.5V cutoff, 3.65V max, extremely flat plateau around 3.25V
        double vLfp0 = BatterySimulation.getOpenCircuitVoltage(BatteryChemistry.LIFEPO4, 0.0);
        double vLfp50 = BatterySimulation.getOpenCircuitVoltage(BatteryChemistry.LIFEPO4, 0.5);
        double vLfp100 = BatterySimulation.getOpenCircuitVoltage(BatteryChemistry.LIFEPO4, 1.0);

        assertEquals(2.5, vLfp0, 0.01);
        assertTrue(vLfp50 >= 3.2 && vLfp50 <= 3.4, "LiFePO4 50% SoC should sit around 3.3V plateau: " + vLfp50);
        assertEquals(3.65, vLfp100, 0.01);

        // Lead-Acid: 1.75V to 2.4V
        double vPb0 = BatterySimulation.getOpenCircuitVoltage(BatteryChemistry.LEAD_ACID, 0.0);
        double vPb100 = BatterySimulation.getOpenCircuitVoltage(BatteryChemistry.LEAD_ACID, 1.0);
        assertTrue(vPb100 > vPb0, "Lead acid voltage should scale positively with SoC");
    }

    @Test
    @DisplayName("Verify temperature and health scaling of internal resistance")
    void testInternalResistanceScaling() {
        BatteryChemistry chem = BatteryChemistry.LI_ION_18650;

        double rRoom = BatterySimulation.getInternalResistance(chem, 0.8, 25.0, 1.0);
        double rFreezing = BatterySimulation.getInternalResistance(chem, 0.8, -10.0, 1.0);
        double rDegraded = BatterySimulation.getInternalResistance(chem, 0.8, 25.0, 0.4);

        assertTrue(rFreezing > rRoom, "Freezing temperature must increase internal impedance");
        assertTrue(rDegraded > rRoom, "Degraded health must increase internal impedance");
    }

    @Test
    @DisplayName("Simulate 1-hour discharge step: verify SoC depletion and Joule heating")
    void testDischargeSimulationStep() {
        BatteryChemistry chem = BatteryChemistry.LI_ION_18650; // 3.0 Ah nominal
        double initialSoc = 1.0;
        double dischargeCurrent = 3.0; // 1C discharge (3 Amps)
        double dtSeconds = 60.0; // 1 minute
        double initialTemp = 20.0;
        double initialHealth = 1.0;

        BatterySimulation.SimulationStepResult result = BatterySimulation.step(
            chem,
            dischargeCurrent,
            dtSeconds,
            initialSoc,
            initialTemp,
            initialHealth,
            20.0
        );

        assertTrue(result.newSoc() < initialSoc, "SoC must decrease during discharge");
        assertTrue(result.newTemperatureCelsius() >= initialTemp, "Joule heat must warm up the cell");
        assertFalse(result.thermalRunaway(), "Normal 1C discharge must not cause thermal runaway");
        assertTrue(result.newHealth() <= initialHealth, "Health degrades with cycling");
    }

    @Test
    @DisplayName("Simulate high-rate overcurrent causing thermal runaway")
    void testThermalRunawayTrigger() {
        BatteryChemistry chem = BatteryChemistry.LI_ION_18650;
        // Severe dead short-circuit current (60 Amps)
        double currentAmps = 60.0;
        double temp = 140.0; // Already critically hot near runaway threshold (150°C)

        BatterySimulation.SimulationStepResult result = BatterySimulation.step(
            chem,
            currentAmps,
            10.0, // 10 seconds of extreme short circuit
            0.8,
            temp,
            1.0,
            20.0
        );

        assertTrue(result.thermalRunaway(), "High temperature and massive current must trigger thermal runaway");
    }


    @Test
    @DisplayName("Verify 100Ah LiFePO4 battery does not overheat under realistic 25A discharge")
    void testPrismaticLiFePO4ThermalStabilityUnderLoad() {
        BatteryChemistry chem = BatteryChemistry.LIFEPO4; // 100 Ah nominal
        double initialSoc = 1.0;
        double current = 25.0; // 25A continuous (0.25C rate, typical for 1000W load at 48V)
        double temp = 20.0;
        double soc = initialSoc;
        double health = 1.0;

        // Simulate 10 minutes (600 seconds) in 1-second steps
        for (int i = 0; i < 600; i++) {
            BatterySimulation.SimulationStepResult res = BatterySimulation.step(
                chem,
                current,
                1.0,
                soc,
                temp,
                health,
                20.0
            );
            soc = res.newSoc();
            temp = res.newTemperatureCelsius();
            health = res.newHealth();
            assertFalse(res.thermalRunaway(), "100Ah LiFePO4 must not enter thermal runaway under 25A load");
        }

        // Temperature should rise very moderately (below 35°C), not exploding!
        assertTrue(temp < 35.0, "Prismatic pack temperature should stay cool under 0.25C load, was: " + temp);
    }

    @Test
    @DisplayName("Lead-acid 120Ah cell sags millivolts, not volts, under a 0.25C load")
    void testLeadAcidVoltageSagUnderNormalLoadIsRealistic() {
        BatteryChemistry chem = BatteryChemistry.LEAD_ACID; // 120 Ah nominal
        double ocvHalf = BatterySimulation.getOpenCircuitVoltage(chem, 0.5); // ~2.075V
        double r = BatterySimulation.getInternalResistance(chem, 0.5, 25.0, 1.0);
        double vTerm = ocvHalf - 30.0 * r; // 30A = 0.25C rate

        // A 120Ah SLA monobloc measures ~8-10 mOhm per 12V (roughly 1.5 mOhm/cell),
        // so 30A must sag ~45mV/cell. Sagging 0.6V/cell would brown out a 12V
        // inverter while still ~80% full (only ~30Ah usable of 120Ah nameplate).
        assertTrue(vTerm > 1.9, "30A through a 120Ah cell must stay above 1.9V, was: " + vTerm);
    }

    @Test
    @DisplayName("Nickel block configs match wiki spec energetics (NiMH 1.2kWh, NiCd 720Wh)")
    void testNickelBlockSpecEnergetics() {
        // Block wiring per VoltcraftBlocks (must stay in sync):
        // NiMH = 20S20P, NiCd = 20S25P (wiki/storage/battery-blocks.md §§2.5-2.6).
        // Pack energy follows the same formula as BatteryBlockEntity.getMaxStorageJoules().
        double nimhJoules = (BatteryChemistry.NIMH.getCapacityAmpHours() * 20)
            * (BatteryChemistry.NIMH.getNominalVoltage() * 20) * 3600.0;
        assertEquals(1_200.0, nimhJoules / 3600.0, 1.0, "NiMH block must store 1.2kWh (50Ah @ 24V)");

        double nicdJoules = (BatteryChemistry.NICD.getCapacityAmpHours() * 25)
            * (BatteryChemistry.NICD.getNominalVoltage() * 20) * 3600.0;
        assertEquals(720.0, nicdJoules / 3600.0, 1.0, "NiCd block must store 720Wh (30Ah @ 24V)");

        // Parallel strings must also bring pack resistance down to sane levels:
        // NiMH 20x0.030/20 = 30 mOhm, NiCd 20x0.020/25 = 16 mOhm.
        double nimhPackR = BatterySimulation.getInternalResistance(BatteryChemistry.NIMH, 0.8, 25.0, 1.0) * 20 / 20;
        assertTrue(nimhPackR < 0.1, "NiMH pack resistance must stay under 0.1 ohm, was: " + nimhPackR);
        double nicdPackR = BatterySimulation.getInternalResistance(BatteryChemistry.NICD, 0.8, 25.0, 1.0) * 20 / 25;
        assertTrue(nicdPackR < 0.1, "NiCd pack resistance must stay under 0.1 ohm, was: " + nicdPackR);
    }

    @Test
    @DisplayName("Lead-acid 6S 120Ah pack delivers near-nameplate amp-hours before cutoff")
    void testLeadAcidPackDeliversFullNameplateCapacity() {
        BatteryChemistry chem = BatteryChemistry.LEAD_ACID;
        int series = 6; // 12V block
        double packCutoff = series * chem.getCutoffVoltage(); // 10.5V
        double current = 20.0;
        double dt = 10.0;

        double soc = 1.0;
        double temp = 20.0;
        double health = 1.0;
        double deliveredAh = 0.0;

        for (int i = 0; i < 3000; i++) {
            BatterySimulation.SimulationStepResult res = BatterySimulation.step(
                chem, current, dt, soc, temp, health, 20.0
            );
            deliveredAh += (current * dt) / 3600.0;
            soc = res.newSoc();
            temp = res.newTemperatureCelsius();
            health = res.newHealth();
            assertFalse(res.thermalRunaway(), "0.17C discharge must never cause thermal runaway");
            double packTerminal = series * res.terminalVoltage();
            if (packTerminal < packCutoff) {
                break;
            }
        }

        assertTrue(deliveredAh >= 110.0,
            "120Ah pack at 20A must deliver >= 110Ah before 10.5V cutoff, delivered: " + deliveredAh);
    }
}