package com.ostapyrih.voltcraft.component;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DataComponentTest {

    @Test
    public void testBatteryBayDataSeriesVoltage() {
        // Test 1: Single 18650 Li-Ion cell at 100% charge
        BatteryBayData.BatteryCellData liIonCell = new BatteryBayData.BatteryCellData(
            "voltcraft:battery_cell_18650_liion",
            "LiCoO2",
            1.0,
            100.0
        );

        BatteryBayData bayLiIon = new BatteryBayData(List.of(liIonCell), 3.7, 4.5);
        assertEquals(4.2, bayLiIon.getTotalSeriesVoltage(), 1e-4, "Full 18650 cell should yield 4.2V");
        assertTrue(bayLiIon.isOperational(), "3.7V target should accept 4.2V");
        assertFalse(bayLiIon.isOvervoltage(), "4.2V is below 4.5V max limit");

        // Test 2: Two Alkaline cells in series at 100% charge (1.6V each => 3.2V)
        BatteryBayData.BatteryCellData alkaline1 = new BatteryBayData.BatteryCellData(
            "voltcraft:battery_cell_aa_alkaline",
            "Zn-MnO2",
            1.0,
            100.0
        );
        BatteryBayData.BatteryCellData alkaline2 = new BatteryBayData.BatteryCellData(
            "voltcraft:battery_cell_aa_alkaline",
            "Zn-MnO2",
            1.0,
            100.0
        );

        BatteryBayData bayAlkaline = new BatteryBayData(List.of(alkaline1, alkaline2), 3.7, 4.5);
        assertEquals(3.2, bayAlkaline.getTotalSeriesVoltage(), 1e-4, "2x Alkaline cells should yield 3.2V");
        assertTrue(bayAlkaline.isOperational(), "3.2V is within 75% operational range of 3.7V tool");
        assertFalse(bayAlkaline.isOvervoltage());

        // Test 3: Three NiMH cells in series at 100% charge (1.45V each => 4.35V)
        BatteryBayData.BatteryCellData nimh = new BatteryBayData.BatteryCellData(
            "voltcraft:battery_cell_aa_nimh",
            "NiMH",
            1.0,
            100.0
        );
        BatteryBayData bayNimh = new BatteryBayData(List.of(nimh, nimh, nimh), 3.7, 4.5);
        assertEquals(3 * 1.45, bayNimh.getTotalSeriesVoltage(), 1e-4, "3x NiMH cells should yield 4.35V");
        assertTrue(bayNimh.isOperational());

        // Test 4: Overvoltage condition (e.g. 2x Li-Ion cells = 8.4V in a 4.5V bay)
        BatteryBayData bayOver = new BatteryBayData(List.of(liIonCell, liIonCell), 3.7, 4.5);
        assertEquals(8.4, bayOver.getTotalSeriesVoltage(), 1e-4);
        assertTrue(bayOver.isOvervoltage(), "8.4V must trigger overvoltage in 4.5V rated bay");
        assertFalse(bayOver.isOperational(), "Overvoltage bay must not be operational");
    }

    @Test
    public void testBatteryBayCodecRoundtrip() {
        BatteryBayData.BatteryCellData cell = new BatteryBayData.BatteryCellData(
            "voltcraft:battery_cell_18650_liion",
            "LiCoO2",
            0.85,
            96.4
        );
        BatteryBayData original = new BatteryBayData(List.of(cell), 3.7, 4.5);

        JsonElement json = BatteryBayData.CODEC.encodeStart(JsonOps.INSTANCE, original)
            .getOrThrow(IllegalStateException::new);

        BatteryBayData decoded = BatteryBayData.CODEC.parse(JsonOps.INSTANCE, json)
            .getOrThrow(IllegalStateException::new);

        assertEquals(original.targetVoltage(), decoded.targetVoltage(), 1e-4);
        assertEquals(original.maxSeriesVoltage(), decoded.maxSeriesVoltage(), 1e-4);
        assertEquals(1, decoded.cells().size());
        assertEquals("LiCoO2", decoded.cells().get(0).chemistry());
        assertEquals(0.85, decoded.cells().get(0).charge(), 1e-4);
        assertEquals(96.4, decoded.cells().get(0).health(), 1e-4);
    }
}
