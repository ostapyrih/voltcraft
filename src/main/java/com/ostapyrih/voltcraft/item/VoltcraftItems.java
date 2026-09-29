package com.ostapyrih.voltcraft.item;

import com.ostapyrih.voltcraft.Voltcraft;
import com.ostapyrih.voltcraft.block.VoltcraftBlocks;
import com.ostapyrih.voltcraft.item.battery.BatteryCellItem;
import com.ostapyrih.voltcraft.simulation.chemistry.BatteryChemistry;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Registry of all raw metallurgy, semiconductor substrates, discrete components,
 * portable battery cells, and block items.
 */
public class VoltcraftItems {

    public static final Map<Identifier, Item> ALL_ITEMS = new LinkedHashMap<>();

    // --- Block Items for Ores ---
    public static final Item ORE_BAUXITE = registerBlockItem("ore_bauxite", VoltcraftBlocks.ORE_BAUXITE);
    public static final Item DEEPSLATE_ORE_BAUXITE = registerBlockItem("deepslate_ore_bauxite", VoltcraftBlocks.DEEPSLATE_ORE_BAUXITE);
    public static final Item ORE_GALENA = registerBlockItem("ore_galena", VoltcraftBlocks.ORE_GALENA);
    public static final Item DEEPSLATE_ORE_GALENA = registerBlockItem("deepslate_ore_galena", VoltcraftBlocks.DEEPSLATE_ORE_GALENA);
    public static final Item ORE_SPHALERITE = registerBlockItem("ore_sphalerite", VoltcraftBlocks.ORE_SPHALERITE);
    public static final Item DEEPSLATE_ORE_SPHALERITE = registerBlockItem("deepslate_ore_sphalerite", VoltcraftBlocks.DEEPSLATE_ORE_SPHALERITE);
    public static final Item ORE_SPODUMENE = registerBlockItem("ore_spodumene", VoltcraftBlocks.ORE_SPODUMENE);
    public static final Item DEEPSLATE_ORE_SPODUMENE = registerBlockItem("deepslate_ore_spodumene", VoltcraftBlocks.DEEPSLATE_ORE_SPODUMENE);
    public static final Item ORE_PENTLANDITE = registerBlockItem("ore_pentlandite", VoltcraftBlocks.ORE_PENTLANDITE);
    public static final Item DEEPSLATE_ORE_PENTLANDITE = registerBlockItem("deepslate_ore_pentlandite", VoltcraftBlocks.DEEPSLATE_ORE_PENTLANDITE);
    public static final Item ORE_HIGH_PURITY_QUARTZ = registerBlockItem("ore_high_purity_quartz", VoltcraftBlocks.ORE_HIGH_PURITY_QUARTZ);
    public static final Item DEEPSLATE_ORE_QUARTZ = registerBlockItem("deepslate_ore_quartz", VoltcraftBlocks.DEEPSLATE_ORE_QUARTZ);

    // --- Conductors & Transmission Lines ---
    public static final Item CABLE_COPPER_BARE = registerBlockItem("cable_copper_bare", VoltcraftBlocks.CABLE_COPPER_BARE);
    public static final Item CABLE_COPPER_INSULATED = registerBlockItem("cable_copper_insulated", VoltcraftBlocks.CABLE_COPPER_INSULATED);
    public static final Item CABLE_COPPER_HEAVY = registerBlockItem("cable_copper_heavy", VoltcraftBlocks.CABLE_COPPER_HEAVY);
    public static final Item CABLE_ALUMINUM_TRANSMISSION = registerBlockItem("cable_aluminum_transmission", VoltcraftBlocks.CABLE_ALUMINUM_TRANSMISSION);
    public static final Item CABLE_SILVER_PRECISION = registerBlockItem("cable_silver_precision", VoltcraftBlocks.CABLE_SILVER_PRECISION);
    public static final Item CABLE_GOLD_BUS = registerBlockItem("cable_gold_bus", VoltcraftBlocks.CABLE_GOLD_BUS);
    public static final Item CABLE_STEEL_FENCE = registerBlockItem("cable_steel_fence", VoltcraftBlocks.CABLE_STEEL_FENCE);
    public static final Item CABLE_NICHROME_HEATING = registerBlockItem("cable_nichrome_heating", VoltcraftBlocks.CABLE_NICHROME_HEATING);
    public static final Item CONDUIT_SUPERCONDUCTOR = registerBlockItem("conduit_superconductor", VoltcraftBlocks.CONDUIT_SUPERCONDUCTOR);

    // --- Switchgear & Safety Hardware ---
    public static final Item COPPER_BUSBAR = registerBlockItem("copper_busbar", VoltcraftBlocks.COPPER_BUSBAR);
    public static final Item JUNCTION_BOX = registerBlockItem("junction_box", VoltcraftBlocks.JUNCTION_BOX);
    public static final Item KNIFE_SWITCH = registerBlockItem("knife_switch", VoltcraftBlocks.KNIFE_SWITCH);
    public static final Item CIRCUIT_BREAKER = registerBlockItem("circuit_breaker", VoltcraftBlocks.CIRCUIT_BREAKER);
    public static final Item FUSE_BOX = registerBlockItem("fuse_box", VoltcraftBlocks.FUSE_BOX);
    public static final Item CONTACTOR_RELAY = registerBlockItem("contactor_relay", VoltcraftBlocks.CONTACTOR_RELAY);
    public static final Item EARTH_ROD = registerBlockItem("earth_rod", VoltcraftBlocks.EARTH_ROD);

    // --- Stationary Energy Storage (BESS) ---
    public static final Item BATTERY_BLOCK_LIFEPO4 = registerBlockItem("battery_block_lifepo4", VoltcraftBlocks.BATTERY_BLOCK_LIFEPO4);
    public static final Item BATTERY_BLOCK_LEAD_ACID = registerBlockItem("battery_block_lead_acid", VoltcraftBlocks.BATTERY_BLOCK_LEAD_ACID);
    public static final Item BATTERY_BLOCK_LTO = registerBlockItem("battery_block_lto", VoltcraftBlocks.BATTERY_BLOCK_LTO);
    public static final Item BATTERY_RACK_MODULAR = registerBlockItem("battery_rack_modular", VoltcraftBlocks.BATTERY_RACK_MODULAR);
    public static final Item BATTERY_BLOCK_NIMH = registerBlockItem("battery_block_nimh", VoltcraftBlocks.BATTERY_BLOCK_NIMH);
    public static final Item BATTERY_BLOCK_NICD = registerBlockItem("battery_block_nicd", VoltcraftBlocks.BATTERY_BLOCK_NICD);

    // --- Power Conversion: DC-DC Converters & Linear LDO ---
    public static final Item CONVERTER_DC_BUCK = registerBlockItem("converter_dc_buck", VoltcraftBlocks.CONVERTER_DC_BUCK);
    public static final Item CONVERTER_DC_BOOST = registerBlockItem("converter_dc_boost", VoltcraftBlocks.CONVERTER_DC_BOOST);
    public static final Item CONVERTER_DC_BUCK_BOOST = registerBlockItem("converter_dc_buck_boost", VoltcraftBlocks.CONVERTER_DC_BUCK_BOOST);
    public static final Item REGULATOR_LINEAR_LDO = registerBlockItem("regulator_linear_ldo", VoltcraftBlocks.REGULATOR_LINEAR_LDO);

    public static final Item TRANSFORMER_AC_STEP_DOWN = registerBlockItem("transformer_ac_step_down", VoltcraftBlocks.TRANSFORMER_AC_STEP_DOWN);
    public static final Item TRANSFORMER_AC_STEP_UP = registerBlockItem("transformer_ac_step_up", VoltcraftBlocks.TRANSFORMER_AC_STEP_UP);

    public static final Item RECTIFIER_BRIDGE = registerBlockItem("rectifier_bridge", VoltcraftBlocks.RECTIFIER_BRIDGE);
    public static final Item RECTIFIER_ACTIVE_SYNCHRONOUS = registerBlockItem("rectifier_active_synchronous", VoltcraftBlocks.RECTIFIER_ACTIVE_SYNCHRONOUS);

    public static final Item INVERTER_SQUARE_WAVE = registerBlockItem("inverter_square_wave", VoltcraftBlocks.INVERTER_SQUARE_WAVE);
    public static final Item INVERTER_MODIFIED_SINE = registerBlockItem("inverter_modified_sine", VoltcraftBlocks.INVERTER_MODIFIED_SINE);
    public static final Item INVERTER_PURE_SINE = registerBlockItem("inverter_pure_sine", VoltcraftBlocks.INVERTER_PURE_SINE);
    public static final Item INVERTER_GRID_TIE = registerBlockItem("inverter_grid_tie", VoltcraftBlocks.INVERTER_GRID_TIE);
    public static final Item INVERTER_HYBRID_ESS = registerBlockItem("inverter_hybrid_ess", VoltcraftBlocks.INVERTER_HYBRID_ESS);

    public static final Item CONVERTER_EU = registerBlockItem("converter_eu", VoltcraftBlocks.CONVERTER_EU);

    // --- Power Generation & Renewable Systems ---
    public static final Item SOLAR_PANEL_MONOCRYSTALLINE = registerBlockItem("solar_panel_monocrystalline", VoltcraftBlocks.SOLAR_PANEL_MONOCRYSTALLINE);
    public static final Item SOLAR_PANEL_POLYCRYSTALLINE = registerBlockItem("solar_panel_polycrystalline", VoltcraftBlocks.SOLAR_PANEL_POLYCRYSTALLINE);
    public static final Item SOLAR_PANEL_THIN_FILM = registerBlockItem("solar_panel_thin_film", VoltcraftBlocks.SOLAR_PANEL_THIN_FILM);
    public static final Item SOLAR_PANEL_CONCENTRATOR = registerBlockItem("solar_panel_concentrator", VoltcraftBlocks.SOLAR_PANEL_CONCENTRATOR);
    public static final Item CHARGE_CONTROLLER_MPPT = registerBlockItem("charge_controller_mppt", VoltcraftBlocks.CHARGE_CONTROLLER_MPPT);
    public static final Item GENERATOR_HAND_CRANK = registerBlockItem("generator_hand_crank", VoltcraftBlocks.GENERATOR_HAND_CRANK);
    public static final Item GENERATOR_PORTABLE_INVERTER = registerBlockItem("generator_portable_inverter", VoltcraftBlocks.GENERATOR_PORTABLE_INVERTER);

    // --- Testing & Creative Hardware ---
    public static final Item CREATIVE_GENERATOR = registerBlockItem("creative_generator", VoltcraftBlocks.CREATIVE_GENERATOR);
    public static final Item CREATIVE_LOAD = registerBlockItem("creative_load", VoltcraftBlocks.CREATIVE_LOAD);

    // --- Raw Ores ---
    public static final Item RAW_BAUXITE = registerItem("raw_bauxite", Item::new);
    public static final Item RAW_GALENA = registerItem("raw_galena", Item::new);
    public static final Item RAW_SPHALERITE = registerItem("raw_sphalerite", Item::new);
    public static final Item RAW_SPODUMENE = registerItem("raw_spodumene", Item::new);
    public static final Item RAW_PENTLANDITE = registerItem("raw_pentlandite", Item::new);

    // --- Refined Ingots, Dusts & Nuggets ---
    public static final Item ALUMINUM_INGOT = registerItem("aluminum_ingot", Item::new);
    public static final Item LEAD_INGOT = registerItem("lead_ingot", Item::new);
    public static final Item ZINC_INGOT = registerItem("zinc_ingot", Item::new);
    public static final Item LITHIUM_INGOT = registerItem("lithium_ingot", Item::new);
    public static final Item NICKEL_INGOT = registerItem("nickel_ingot", Item::new);
    public static final Item SILVER_INGOT = registerItem("silver_ingot", Item::new);
    public static final Item SILVER_NUGGET = registerItem("silver_nugget", Item::new);
    public static final Item PURE_SILICA_DUST = registerItem("pure_silica_dust", Item::new);

    // --- Specialized Alloys & Polymers ---
    public static final Item NICHROME_INGOT = registerItem("nichrome_ingot", Item::new);
    public static final Item FUSE_ALLOY_INGOT = registerItem("fuse_alloy_ingot", Item::new);
    public static final Item RUBBER_SHEET = registerItem("rubber_sheet", Item::new);

    // --- Semiconductor Chain ---
    public static final Item SILICON_BOULE = registerItem("silicon_boule", Item::new);
    public static final Item SILICON_WAFER = registerItem("silicon_wafer", Item::new);
    public static final Item DOPED_WAFER_P = registerItem("doped_wafer_p", Item::new);
    public static final Item DOPED_WAFER_N = registerItem("doped_wafer_n", Item::new);
    public static final Item PHOTOVOLTAIC_CELL = registerItem("photovoltaic_cell", Item::new);

    // --- Discrete Electronic Components ---
    public static final Item MOSFET_POWER_TRANSISTOR = registerItem("mosfet_power_transistor", Item::new);
    public static final Item SCHOTTKY_DIODE = registerItem("schottky_diode", Item::new);
    public static final Item FILTER_CAPACITOR_ELECTROLYTIC = registerItem("filter_capacitor_electrolytic", Item::new);
    public static final Item COPPER_MAGNET_WIRE = registerItem("copper_magnet_wire", Item::new);
    public static final Item TRANSFORMER_CORE_LAMINATED = registerItem("transformer_core_laminated", Item::new);
    public static final Item BMS_LOGIC_BOARD = registerItem("bms_logic_board", Item::new);

    // --- Portable Battery Cells (Item Form) ---
    public static final Item BATTERY_18650_LI_ION = registerItem("battery_18650_li_ion", s -> new BatteryCellItem(s, BatteryChemistry.LI_ION_18650));
    public static final Item BATTERY_21700_HIGH_DRAIN = registerItem("battery_21700_high_drain", s -> new BatteryCellItem(s, BatteryChemistry.LI_ION_21700));
    public static final Item BATTERY_CELL_ALKALINE = registerItem("battery_cell_alkaline", s -> new BatteryCellItem(s, BatteryChemistry.ALKALINE));
    public static final Item BATTERY_CELL_ZINC_CARBON = registerItem("battery_cell_zinc_carbon", s -> new BatteryCellItem(s, BatteryChemistry.ZINC_CARBON));
    public static final Item BATTERY_COIN_CR2032 = registerItem("battery_coin_cr2032", s -> new BatteryCellItem(s, BatteryChemistry.COIN_CR2032));
    public static final Item BATTERY_CELL_LISOCL2 = registerItem("battery_cell_lisocl2", s -> new BatteryCellItem(s, BatteryChemistry.LITHIUM_THIONYL));
    public static final Item BATTERY_CELL_NICD = registerItem("battery_cell_nicd", s -> new BatteryCellItem(s, BatteryChemistry.NICD));
    public static final Item BATTERY_CELL_NIMH = registerItem("battery_cell_nimh", s -> new BatteryCellItem(s, BatteryChemistry.NIMH));

    private static Item registerItem(String name, Function<Item.Settings, Item> itemFactory) {
        Identifier id = Identifier.of(Voltcraft.MOD_ID, name);
        RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM, id);
        Item.Settings settings = new Item.Settings().registryKey(key);
        Item item = itemFactory.apply(settings);
        ALL_ITEMS.put(id, item);
        return Registry.register(Registries.ITEM, key, item);
    }

    private static Item registerBlockItem(String name, Block block) {
        Identifier id = Identifier.of(Voltcraft.MOD_ID, name);
        RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM, id);
        Item.Settings settings = new Item.Settings()
            .registryKey(key)
            .useBlockPrefixedTranslationKey();
        BlockItem item = new BlockItem(block, settings);
        item.appendBlocks(Item.BLOCK_ITEMS, item);
        ALL_ITEMS.put(id, item);
        return Registry.register(Registries.ITEM, key, item);
    }

    public static void initialize() {
        // Trigger static init
    }
}
