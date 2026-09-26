package com.ostapyrih.voltcraft.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.ostapyrih.voltcraft.api.data.BatteryCellSpec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;

import java.util.ArrayList;
import java.util.List;

/**
 * DataComponent payload for tools with modular battery compartments.
 * Holds slotted cell data and calculates total series voltage, operational state, and health.
 */
public record BatteryBayData(List<BatteryCellData> cells, double targetVoltage, double maxSeriesVoltage) {

    public static final BatteryBayData EMPTY = new BatteryBayData(List.of(), 3.7, 4.5);

    /**
     * Discrete data for an individual cell slotted inside a battery bay.
     */
    public record BatteryCellData(String itemId, String chemistry, double charge, double health) {
        public static final Codec<BatteryCellData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                Codec.STRING.fieldOf("item_id").forGetter(BatteryCellData::itemId),
                Codec.STRING.fieldOf("chemistry").forGetter(BatteryCellData::chemistry),
                Codec.DOUBLE.fieldOf("charge").forGetter(BatteryCellData::charge),
                Codec.DOUBLE.fieldOf("health").forGetter(BatteryCellData::health)
            ).apply(instance, BatteryCellData::new)
        );

        public static final PacketCodec<ByteBuf, BatteryCellData> PACKET_CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, BatteryCellData::itemId,
            PacketCodecs.STRING, BatteryCellData::chemistry,
            PacketCodecs.DOUBLE, BatteryCellData::charge,
            PacketCodecs.DOUBLE, BatteryCellData::health,
            BatteryCellData::new
        );

        public double getTerminalVoltage() {
            BatteryCellSpec spec = BatteryCellSpec.LI_ION_18650;
            if ("Zn-MnO2".equals(chemistry)) spec = BatteryCellSpec.ALKALINE;
            else if ("Zn-C".equals(chemistry)) spec = BatteryCellSpec.ZINC_CARBON;
            else if ("NiMH".equals(chemistry)) spec = BatteryCellSpec.NIMH;
            else if ("NiCd".equals(chemistry)) spec = BatteryCellSpec.NICD;
            else if ("NMC".equals(chemistry)) spec = BatteryCellSpec.LI_ION_21700;
            else if ("Li-MnO2".equals(chemistry)) spec = BatteryCellSpec.COIN_CR2032;

            double ratio = Math.max(0.0, Math.min(1.0, charge));
            return spec.cutoffVoltage() + (spec.fullChargeVoltage() - spec.cutoffVoltage()) * ratio;
        }
    }

    public static final Codec<BatteryBayData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            BatteryCellData.CODEC.listOf().fieldOf("cells").forGetter(BatteryBayData::cells),
            Codec.DOUBLE.optionalFieldOf("target_voltage", 3.7).forGetter(BatteryBayData::targetVoltage),
            Codec.DOUBLE.optionalFieldOf("max_series_voltage", 4.5).forGetter(BatteryBayData::maxSeriesVoltage)
        ).apply(instance, BatteryBayData::new)
    );

    public static final PacketCodec<ByteBuf, BatteryBayData> PACKET_CODEC = PacketCodec.tuple(
        BatteryCellData.PACKET_CODEC.collect(PacketCodecs.toList()),
        BatteryBayData::cells,
        PacketCodecs.DOUBLE,
        BatteryBayData::targetVoltage,
        PacketCodecs.DOUBLE,
        BatteryBayData::maxSeriesVoltage,
        BatteryBayData::new
    );

    public BatteryBayData {
        cells = List.copyOf(cells);
    }

    /**
     * Calculates total series voltage of all inserted cells: V_bay = sum(V_cell).
     */
    public double getTotalSeriesVoltage() {
        double totalV = 0.0;
        for (BatteryCellData cell : cells) {
            totalV += cell.getTerminalVoltage();
        }
        return totalV;
    }

    public boolean isOperational() {
        double v = getTotalSeriesVoltage();
        return v >= (targetVoltage * 0.75) && v <= maxSeriesVoltage;
    }

    public boolean isOvervoltage() {
        return getTotalSeriesVoltage() > maxSeriesVoltage;
    }

    public BatteryBayData withCell(int slot, BatteryCellData cellData) {
        List<BatteryCellData> newCells = new ArrayList<>(cells);
        while (newCells.size() <= slot) {
            newCells.add(null);
        }
        newCells.set(slot, cellData);
        // Clean nulls
        newCells.removeIf(java.util.Objects::isNull);
        return new BatteryBayData(newCells, targetVoltage, maxSeriesVoltage);
    }
}
