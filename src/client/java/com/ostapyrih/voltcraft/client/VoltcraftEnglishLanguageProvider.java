package com.ostapyrih.voltcraft.client;

import com.ostapyrih.voltcraft.item.VoltcraftItems;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricLanguageProvider;
import net.minecraft.registry.RegistryWrapper;

import java.util.concurrent.CompletableFuture;

/**
 * Generates en_us.json translations for all VoltCraft blocks, items, and creative tabs.
 */
public class VoltcraftEnglishLanguageProvider extends FabricLanguageProvider {

    public VoltcraftEnglishLanguageProvider(FabricDataOutput dataOutput, CompletableFuture<RegistryWrapper.WrapperLookup> registryLookup) {
        super(dataOutput, registryLookup);
    }

    @Override
    public void generateTranslations(RegistryWrapper.WrapperLookup registryLookup, TranslationBuilder builder) {
        // Creative Tabs
        builder.add("itemGroup.voltcraft.materials", "VoltCraft: Materials & Metallurgy");
        builder.add("itemGroup.voltcraft.components", "VoltCraft: Electronics & Components");
        builder.add("itemGroup.voltcraft.grid", "VoltCraft: Power & Grid");

        // Helper to register both block and item translation keys
        registerBlockWithItem(builder, "ore_bauxite", "Bauxite Ore");
        registerBlockWithItem(builder, "deepslate_ore_bauxite", "Deepslate Bauxite Ore");
        registerBlockWithItem(builder, "ore_galena", "Galena Ore");
        registerBlockWithItem(builder, "deepslate_ore_galena", "Deepslate Galena Ore");
        registerBlockWithItem(builder, "ore_sphalerite", "Sphalerite Ore");
        registerBlockWithItem(builder, "deepslate_ore_sphalerite", "Deepslate Sphalerite Ore");
        registerBlockWithItem(builder, "ore_spodumene", "Spodumene Ore");
        registerBlockWithItem(builder, "deepslate_ore_spodumene", "Deepslate Spodumene Ore");
        registerBlockWithItem(builder, "ore_pentlandite", "Pentlandite Ore");
        registerBlockWithItem(builder, "deepslate_ore_pentlandite", "Deepslate Pentlandite Ore");
        registerBlockWithItem(builder, "ore_high_purity_quartz", "High-Purity Quartz Ore");
        registerBlockWithItem(builder, "deepslate_ore_quartz", "Deepslate Quartz Ore");

        // Conductors & Cables
        registerBlockWithItem(builder, "cable_copper_bare", "Bare Copper Wire");
        registerBlockWithItem(builder, "cable_copper_insulated", "Insulated Copper Cable");
        registerBlockWithItem(builder, "cable_copper_heavy", "Heavy Industrial Copper Cable");
        registerBlockWithItem(builder, "cable_aluminum_transmission", "Aluminum Transmission Line");
        registerBlockWithItem(builder, "cable_silver_precision", "Silver Precision Wire");
        registerBlockWithItem(builder, "cable_gold_bus", "Gold Bus Cable");
        registerBlockWithItem(builder, "cable_steel_fence", "Galvanized Steel Fence Wire");
        registerBlockWithItem(builder, "cable_nichrome_heating", "Nichrome Heating Wire");
        registerBlockWithItem(builder, "conduit_superconductor", "Cryogenic Superconductor Conduit");

        // Switchgear & Safety Hardware
        registerBlockWithItem(builder, "copper_busbar", "Copper Busbar");
        registerBlockWithItem(builder, "junction_box", "Junction Box");
        registerBlockWithItem(builder, "knife_switch", "Manual Knife Switch");
        registerBlockWithItem(builder, "circuit_breaker", "Resettable Circuit Breaker");
        registerBlockWithItem(builder, "fuse_box", "Cartridge Fuse Box");
        registerBlockWithItem(builder, "contactor_relay", "Electromagnetic Contactor Relay");

        // Stationary Energy Storage Systems (BESS)
        registerBlockWithItem(builder, "battery_block_lifepo4", "LiFePO4 Battery Block (48V 100Ah)");
        registerBlockWithItem(builder, "battery_block_lead_acid", "Heavy Lead-Acid Battery Bank (12V 120Ah)");
        registerBlockWithItem(builder, "battery_block_lto", "Lithium-Titanate (LTO) Block (24V 60Ah)");
        registerBlockWithItem(builder, "battery_rack_modular", "Modular 18650 Battery Rack");
        registerBlockWithItem(builder, "battery_block_nimh", "NiMH Battery Pack Block (24V 50Ah)");
        registerBlockWithItem(builder, "battery_block_nicd", "NiCd Battery Pack Block (24V 30Ah)");

        // Power Conversion: DC-DC Converters & Linear LDO
        registerBlockWithItem(builder, "converter_dc_buck", "Buck Step-Down Converter");
        registerBlockWithItem(builder, "converter_dc_boost", "Boost Step-Up Converter");
        registerBlockWithItem(builder, "converter_dc_buck_boost", "Buck-Boost SEPIC Converter");
        registerBlockWithItem(builder, "regulator_linear_ldo", "Linear LDO Voltage Regulator");

        // Power Conversion: AC Transformers
        registerBlockWithItem(builder, "transformer_ac_step_down", "AC Step-Down Transformer (230V to 24V)");
        registerBlockWithItem(builder, "transformer_ac_step_up", "AC Step-Up Transformer (24V to 230V)");

        // Power Conversion: AC-DC Rectifiers
        registerBlockWithItem(builder, "rectifier_bridge", "Full-Wave Bridge Rectifier");
        registerBlockWithItem(builder, "rectifier_active_synchronous", "Active Synchronous Rectifier");

        // Power Conversion: DC-AC Inverters
        registerBlockWithItem(builder, "inverter_square_wave", "Square Wave Inverter (1500W)");
        registerBlockWithItem(builder, "inverter_modified_sine", "Modified Sine Wave Inverter (3000W)");
        registerBlockWithItem(builder, "inverter_pure_sine", "Pure Sine Wave SPWM Inverter (5000W)");
        registerBlockWithItem(builder, "inverter_grid_tie", "Synchronous Grid-Tie Inverter (6000W)");
        registerBlockWithItem(builder, "inverter_hybrid_ess", "Hybrid Multi-Mode Inverter ESS (8000W)");

        // Power Conversion: 230V AC to E Energy Bridge
        registerBlockWithItem(builder, "converter_eu", "Rotary Energy Bridge (230V AC to E)");

        // Power Generation & Renewable Systems
        registerBlockWithItem(builder, "solar_panel_monocrystalline", "Monocrystalline PERC Solar Panel (400W)");
        registerBlockWithItem(builder, "solar_panel_polycrystalline", "Polycrystalline Solar Panel (300W)");
        registerBlockWithItem(builder, "solar_panel_thin_film", "Thin-Film CdTe Solar Panel (250W)");
        registerBlockWithItem(builder, "solar_panel_concentrator", "Concentrated Photovoltaic Panel (700W CPV)");
        registerBlockWithItem(builder, "charge_controller_mppt", "MPPT Solar Charge Controller (60A)");
        registerBlockWithItem(builder, "generator_hand_crank", "Hand-Crank DC Dynamo (100W)");
        registerBlockWithItem(builder, "generator_portable_inverter", "Portable Inverter Generator (1.8kW 230V AC)");

        // Testing & Creative Hardware
        registerBlockWithItem(builder, "creative_generator", "Creative Power Generator");
        registerBlockWithItem(builder, "creative_load", "Creative Electrical Load");

        // Raw Ores
        builder.add(VoltcraftItems.RAW_BAUXITE, "Raw Bauxite");
        builder.add(VoltcraftItems.RAW_GALENA, "Raw Galena");
        builder.add(VoltcraftItems.RAW_SPHALERITE, "Raw Sphalerite");
        builder.add(VoltcraftItems.RAW_SPODUMENE, "Raw Spodumene");
        builder.add(VoltcraftItems.RAW_PENTLANDITE, "Raw Pentlandite");

        // Refined Metals & Mineral Dusts
        builder.add(VoltcraftItems.ALUMINUM_INGOT, "Aluminum Ingot");
        builder.add(VoltcraftItems.LEAD_INGOT, "Lead Ingot");
        builder.add(VoltcraftItems.ZINC_INGOT, "Zinc Ingot");
        builder.add(VoltcraftItems.LITHIUM_INGOT, "Lithium Ingot");
        builder.add(VoltcraftItems.NICKEL_INGOT, "Nickel Ingot");
        builder.add(VoltcraftItems.SILVER_INGOT, "Silver Ingot");
        builder.add(VoltcraftItems.SILVER_NUGGET, "Silver Nugget");
        builder.add(VoltcraftItems.PURE_SILICA_DUST, "High-Purity Silica Dust");

        // Engineering Alloys & Insulation
        builder.add(VoltcraftItems.NICHROME_INGOT, "Nichrome Ingot");
        builder.add(VoltcraftItems.FUSE_ALLOY_INGOT, "Eutectic Fuse Alloy Ingot");
        builder.add(VoltcraftItems.RUBBER_SHEET, "Vulcanized Rubber Dielectric Sheet");

        // Semiconductors & Photovoltaics
        builder.add(VoltcraftItems.SILICON_BOULE, "Monocrystalline Silicon Boule");
        builder.add(VoltcraftItems.SILICON_WAFER, "Polished Silicon Wafer");
        builder.add(VoltcraftItems.DOPED_WAFER_P, "P-Doped Silicon Wafer");
        builder.add(VoltcraftItems.DOPED_WAFER_N, "N-Doped Silicon Wafer");
        builder.add(VoltcraftItems.PHOTOVOLTAIC_CELL, "Photovoltaic Solar Cell");

        // Power Electronics & Discrete Components
        builder.add(VoltcraftItems.MOSFET_POWER_TRANSISTOR, "Power MOSFET Transistor");
        builder.add(VoltcraftItems.SCHOTTKY_DIODE, "Schottky Barrier Diode");
        builder.add(VoltcraftItems.FILTER_CAPACITOR_ELECTROLYTIC, "Electrolytic Filter Capacitor");
        builder.add(VoltcraftItems.COPPER_MAGNET_WIRE, "Enameled Copper Magnet Wire");
        builder.add(VoltcraftItems.TRANSFORMER_CORE_LAMINATED, "Laminated Transformer Core");
        builder.add(VoltcraftItems.BMS_LOGIC_BOARD, "Battery Management System (BMS) Board");

        // Portable Battery Cells (Item Form)
        builder.add(VoltcraftItems.BATTERY_18650_LI_ION, "18650 Li-Ion Cell (3.7V 3000mAh)");
        builder.add(VoltcraftItems.BATTERY_21700_HIGH_DRAIN, "21700 High-Drain Li-Ion Cell (3.7V 5000mAh)");
        builder.add(VoltcraftItems.BATTERY_CELL_ALKALINE, "Alkaline Cell (1.5V 2000mAh)");
        builder.add(VoltcraftItems.BATTERY_CELL_ZINC_CARBON, "Zinc-Carbon Cell (1.5V 800mAh)");
        builder.add(VoltcraftItems.BATTERY_COIN_CR2032, "CR2032 Lithium Coin Cell (3.0V 220mAh)");
        builder.add(VoltcraftItems.BATTERY_CELL_LISOCL2, "Industrial Li-SOCl2 Backup Cell (3.6V 1500mAh)");
        builder.add(VoltcraftItems.BATTERY_CELL_NICD, "NiCd Rechargeable Cell (1.2V 1200mAh)");
        builder.add(VoltcraftItems.BATTERY_CELL_NIMH, "NiMH Rechargeable Cell (1.2V 2500mAh)");
    }

    private void registerBlockWithItem(TranslationBuilder builder, String name, String translation) {
        builder.add("block.voltcraft." + name, translation);
        builder.add("item.voltcraft." + name, translation);
    }
}
