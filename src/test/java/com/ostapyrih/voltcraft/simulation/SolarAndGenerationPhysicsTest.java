package com.ostapyrih.voltcraft.simulation;

import com.ostapyrih.voltcraft.simulation.generation.MPPTLogic;
import com.ostapyrih.voltcraft.simulation.generation.SolarIrradianceSimulation;
import com.ostapyrih.voltcraft.simulation.generation.SolarPanelType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SolarAndGenerationPhysicsTest {

    @Test
    @DisplayName("Solar panel STC output matches rated technology specifications")
    void testSolarPanelSTCOutput() {
        // Monocrystalline PERC at 1000 W/m² and 25°C
        SolarIrradianceSimulation.SolarOutput mono = SolarIrradianceSimulation.computeSolarOutput(
            SolarPanelType.MONOCRYSTALLINE_PERC, 1000.0, 25.0
        );

        assertEquals(1000.0, mono.irradianceWattsPerM2(), 1e-4);
        assertEquals(48.0, mono.electromotiveForce(), 1e-2); // Voc
        assertEquals(10.0, mono.maxCurrentAmps(), 1e-2);       // Imp
        assertEquals(400.0, mono.peakPowerAvailableWatts(), 1e-2); // Vmp * Imp = 40 * 10 = 400W

        // Polycrystalline at STC
        SolarIrradianceSimulation.SolarOutput poly = SolarIrradianceSimulation.computeSolarOutput(
            SolarPanelType.POLYCRYSTALLINE, 1000.0, 25.0
        );
        assertEquals(300.16, poly.peakPowerAvailableWatts(), 1.0); // ~300W

        // Thin-film CdTe at STC
        SolarIrradianceSimulation.SolarOutput thinFilm = SolarIrradianceSimulation.computeSolarOutput(
            SolarPanelType.THIN_FILM_CDTE, 1000.0, 25.0
        );
        assertEquals(250.0, thinFilm.peakPowerAvailableWatts(), 1.0); // ~250W

        // Concentrator CPV at STC
        SolarIrradianceSimulation.SolarOutput cpv = SolarIrradianceSimulation.computeSolarOutput(
            SolarPanelType.CONCENTRATOR_CPV, 1000.0, 25.0
        );
        assertEquals(700.0, cpv.peakPowerAvailableWatts(), 1.0); // ~700W
    }

    @Test
    @DisplayName("Solar output scales linearly with reduced irradiance and logarithmically with voltage")
    void testSolarOutputUnderReducedIrradiance() {
        // At 500 W/m² (50% STC sunlight)
        SolarIrradianceSimulation.SolarOutput halfSun = SolarIrradianceSimulation.computeSolarOutput(
            SolarPanelType.MONOCRYSTALLINE_PERC, 500.0, 25.0
        );

        // Current is halved
        assertEquals(5.0, halfSun.maxCurrentAmps(), 0.05);
        // Voltage drops only slightly due to logarithmic diode behavior
        assertTrue(halfSun.electromotiveForce() < 48.0);
        assertTrue(halfSun.electromotiveForce() > 47.9);
        // Peak power is approximately halved (~200W)
        assertEquals(200.0, halfSun.peakPowerAvailableWatts(), 5.0);

        // Zero irradiance (night) produces 0 power and 0 current
        SolarIrradianceSimulation.SolarOutput night = SolarIrradianceSimulation.computeSolarOutput(
            SolarPanelType.MONOCRYSTALLINE_PERC, 0.0, 25.0
        );
        assertEquals(0.0, night.peakPowerAvailableWatts(), 1e-6);
        assertEquals(0.0, night.maxCurrentAmps(), 1e-6);
        assertEquals(0.0, night.electromotiveForce(), 1e-6);
    }

    @Test
    @DisplayName("Weather factors: CPV drops to 0 in overcast/rain, Thin-Film maintains diffuse output")
    void testWeatherAttenuationFactors() {
        // Rain
        assertEquals(0.25, SolarPanelType.MONOCRYSTALLINE_PERC.getWeatherFactor(true, false));
        assertEquals(0.25, SolarPanelType.POLYCRYSTALLINE.getWeatherFactor(true, false));
        assertEquals(0.40, SolarPanelType.THIN_FILM_CDTE.getWeatherFactor(true, false));
        assertEquals(0.0, SolarPanelType.CONCENTRATOR_CPV.getWeatherFactor(true, false));

        // Thunderstorm
        assertEquals(0.10, SolarPanelType.MONOCRYSTALLINE_PERC.getWeatherFactor(true, true));
        assertEquals(0.20, SolarPanelType.THIN_FILM_CDTE.getWeatherFactor(true, true));
        assertEquals(0.0, SolarPanelType.CONCENTRATOR_CPV.getWeatherFactor(true, true));
    }

    @Test
    @DisplayName("Temperature coefficient derates power output at elevated cell temperatures")
    void testTemperatureCoefficientDerating() {
        // 55°C cell temp (30°C above 25°C STC standard)
        SolarIrradianceSimulation.SolarOutput hotCell = SolarIrradianceSimulation.computeSolarOutput(
            SolarPanelType.MONOCRYSTALLINE_PERC, 1000.0, 55.0
        );

        // Monocrystalline PERC temp coefficient is -0.35%/°C -> 30 * (-0.0035) = -10.5% derating
        assertTrue(hotCell.peakPowerAvailableWatts() < 400.0);
        assertTrue(hotCell.peakPowerAvailableWatts() > 320.0);
    }

    @Test
    @DisplayName("MPPT logic executes 3-stage battery charging (Bulk -> Absorption -> Float)")
    void testMPPT3StageChargingStateMachine() {
        MPPTLogic mppt = new MPPTLogic(24.0); // 24V nominal battery bank

        assertEquals(24.0, mppt.getBatteryBankVoltage());
        assertEquals(28.8, mppt.getAbsorptionVoltage(), 0.01);
        assertEquals(27.2, mppt.getFloatVoltage(), 0.01);
        assertEquals(MPPTLogic.ChargeStage.BULK, mppt.getStage());

        // 1. Bulk charging under-voltage (e.g. battery at 22.0V)
        double vTarget = mppt.step(60.0, 5.0, 22.0);
        assertEquals(MPPTLogic.ChargeStage.BULK, mppt.getStage());
        assertEquals(28.8, vTarget, 0.01);

        // 2. Transition into Absorption when battery hits 28.8V
        vTarget = mppt.step(60.0, 5.0, 28.75);
        assertEquals(MPPTLogic.ChargeStage.ABSORPTION, mppt.getStage());
        assertEquals(28.8, vTarget, 0.01);

        // 3. Absorption completes when current tapers (< 0.2A) -> transitions to Float.
        // The taper must persist through the absorption-exit debounce (short
        // transients ride through); loop past it.
        for (int i = 0; i < 45; i++) {
            vTarget = mppt.step(60.0, 0.15, 28.8);
        }
        assertEquals(MPPTLogic.ChargeStage.FLOAT, mppt.getStage());
        assertEquals(27.2, vTarget, 0.01); // Drops to float maintenance voltage

        // 4. If battery drops below nominal float (e.g. high evening load pulls it to 25.0V), re-enters Bulk
        vTarget = mppt.step(60.0, 2.0, 25.5);
        assertEquals(MPPTLogic.ChargeStage.BULK, mppt.getStage());
        assertEquals(28.8, vTarget, 0.01);
    }

    @Test
    @DisplayName("Unloaded MPPT output maintains Bulk EMF and does not prematurely transition to Float")
    void testMPPTUnloadedOutputStaysBulk() {
        MPPTLogic mppt = new MPPTLogic(12.0); // 12V nominal bank
        assertEquals(14.4, mppt.getAbsorptionVoltage(), 0.01);
        assertEquals(13.6, mppt.getFloatVoltage(), 0.01);
        assertEquals(MPPTLogic.ChargeStage.BULK, mppt.getStage());

        // When unloaded, inputCurrent is 0.0 A and output rail floats at EMF (14.4V).
        // It must NOT enter Absorption or Float because no actual charging current is flowing.
        for (int i = 0; i < 50; i++) {
            double vTarget = mppt.step(40.0, 0.0, 14.4);
            assertEquals(MPPTLogic.ChargeStage.BULK, mppt.getStage(), "Tick " + i + " must stay in BULK");
            assertEquals(14.4, vTarget, 0.01);
        }
    }

    @Test
    @DisplayName("MPPT bank voltages scale proportionally across 12V, 24V, and 48V modes")
    void testMPPTBankModes12V24V48V() {
        // 12V Bank Mode
        MPPTLogic mppt12 = new MPPTLogic(12.0);
        assertEquals(12.0, mppt12.getBatteryBankVoltage());
        assertEquals(14.4, mppt12.getAbsorptionVoltage(), 1e-4);
        assertEquals(13.6, mppt12.getFloatVoltage(), 1e-4);

        // 24V Bank Mode
        MPPTLogic mppt24 = new MPPTLogic(24.0);
        assertEquals(24.0, mppt24.getBatteryBankVoltage());
        assertEquals(28.8, mppt24.getAbsorptionVoltage(), 1e-4);
        assertEquals(27.2, mppt24.getFloatVoltage(), 1e-4);

        // 48V Bank Mode
        MPPTLogic mppt48 = new MPPTLogic(48.0);
        assertEquals(48.0, mppt48.getBatteryBankVoltage());
        assertEquals(57.6, mppt48.getAbsorptionVoltage(), 1e-4);
        assertEquals(54.4, mppt48.getFloatVoltage(), 1e-4);

        // Verify full 3-stage charge cycle on 48V bank
        // 1. Bulk
        double vTarget = mppt48.step(100.0, 10.0, 48.0);
        assertEquals(MPPTLogic.ChargeStage.BULK, mppt48.getStage());
        assertEquals(57.6, vTarget, 0.01);

        // 2. Transition into Absorption near 57.6V
        vTarget = mppt48.step(100.0, 5.0, 57.4);
        assertEquals(MPPTLogic.ChargeStage.ABSORPTION, mppt48.getStage());
        assertEquals(57.6, vTarget, 0.01);

        // 3. Absorption to Float when current drops (sustained taper past the debounce)
        for (int i = 0; i < 45; i++) {
            vTarget = mppt48.step(100.0, 0.1, 57.6);
        }
        assertEquals(MPPTLogic.ChargeStage.FLOAT, mppt48.getStage());
        assertEquals(54.4, vTarget, 0.01);

        // 4. Heavy load pulls 48V bank below float drop limit (54.4 - 2.0 = 52.4V) -> returns to Bulk
        vTarget = mppt48.step(100.0, 5.0, 51.0);
        assertEquals(MPPTLogic.ChargeStage.BULK, mppt48.getStage());
        assertEquals(57.6, vTarget, 0.01);
    }

    @Test
    @DisplayName("MPPT target freezes while the rail is unsettled, tracks when settled")
    void testMPPTGatedAdaptation() {
        MPPTLogic mppt = new MPPTLogic(24.0);
        mppt.step(36.0, 5.0, 22.0, true); // baseline, settled
        double parked = mppt.getTargetInputVoltage();

        // Demand ramp (power surging while voltage falls): must NOT walk the target.
        mppt.step(30.0, 8.0, 22.0, false);
        mppt.step(25.0, 10.0, 22.0, false);
        assertEquals(parked, mppt.getTargetInputVoltage(), 1e-9,
            "P&O must not adapt on demand-driven swings");
        // Baseline keeps tracking so re-entry compares fresh measurements.
        mppt.step(36.0, 5.0, 22.0, true);
        double afterDip = mppt.getTargetInputVoltage();
        mppt.step(36.2, 5.1, 22.0, true); // genuine improvement at higher voltage
        assertTrue(mppt.getTargetInputVoltage() > afterDip,
            "Settled improvement must still track upward");
    }

    @Test
    @DisplayName("MPPT Perturb & Observe algorithm tracks maximum power point")
    void testMPPTPerturbAndObserveTracking() {
        MPPTLogic mppt = new MPPTLogic(48.0);
        double initialTarget = mppt.getTargetInputVoltage();

        // Step 1: initial baseline
        mppt.step(36.0, 10.0, 50.0); // P = 360W

        // Step 2: voltage increased and power increased (38V, 10A -> 380W)
        mppt.step(38.0, 10.0, 50.0);
        // P&O should continue perturbing upward
        double afterIncrease = mppt.getTargetInputVoltage();
        assertTrue(afterIncrease > initialTarget);

        // Step 3: voltage increased but power dropped (40V, 8A -> 320W)
        mppt.step(40.0, 8.0, 50.0);
        // P&O should reverse direction (decrease target)
        double afterReverse = mppt.getTargetInputVoltage();
        assertTrue(afterReverse < afterIncrease);
    }
}
