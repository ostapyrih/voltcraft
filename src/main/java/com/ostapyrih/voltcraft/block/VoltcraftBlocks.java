package com.ostapyrih.voltcraft.block;

import com.ostapyrih.voltcraft.Voltcraft;
import com.ostapyrih.voltcraft.block.cable.CableBlock;
import com.ostapyrih.voltcraft.block.cable.ConductorType;
import com.ostapyrih.voltcraft.block.conversion.ConverterBlock;
import com.ostapyrih.voltcraft.block.conversion.EuConverterBlock;
import com.ostapyrih.voltcraft.block.conversion.InverterBlock;
import com.ostapyrih.voltcraft.block.conversion.RectifierBlock;
import com.ostapyrih.voltcraft.block.conversion.TransformerBlock;
import com.ostapyrih.voltcraft.block.creative.CreativeGeneratorBlock;
import com.ostapyrih.voltcraft.block.creative.CreativeLoadBlock;
import com.ostapyrih.voltcraft.block.generation.ChargeControllerBlock;
import com.ostapyrih.voltcraft.block.generation.HandCrankGeneratorBlock;
import com.ostapyrih.voltcraft.block.generation.PortableGeneratorBlock;
import com.ostapyrih.voltcraft.block.generation.SolarPanelBlock;
import com.ostapyrih.voltcraft.block.storage.BatteryBlock;
import com.ostapyrih.voltcraft.block.storage.BatteryRackBlock;
import com.ostapyrih.voltcraft.block.switchgear.*;
import com.ostapyrih.voltcraft.simulation.chemistry.BatteryChemistry;
import com.ostapyrih.voltcraft.simulation.conversion.ConverterType;
import com.ostapyrih.voltcraft.simulation.conversion.InverterType;
import com.ostapyrih.voltcraft.simulation.conversion.RectifierType;
import com.ostapyrih.voltcraft.simulation.conversion.TransformerType;
import com.ostapyrih.voltcraft.simulation.generation.SolarPanelType;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.enums.NoteBlockInstrument;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Natural mineral ores, conductors, switchgear, stationary BESS storage, power conversion,
 * power generation & renewables, and creative testing blocks introduced by VoltCraft.
 */
public class VoltcraftBlocks {

    public static final Map<Identifier, Block> ALL_BLOCKS = new LinkedHashMap<>();

    // Standard stone settings
    private static AbstractBlock.Settings stoneOreSettings() {
        return AbstractBlock.Settings.create()
            .mapColor(MapColor.STONE_GRAY)
            .instrument(NoteBlockInstrument.BASEDRUM)
            .requiresTool()
            .strength(3.0f, 3.0f);
    }

    // Deepslate settings
    private static AbstractBlock.Settings deepslateOreSettings() {
        return AbstractBlock.Settings.create()
            .mapColor(MapColor.DEEPSLATE_GRAY)
            .instrument(NoteBlockInstrument.BASEDRUM)
            .requiresTool()
            .strength(4.5f, 3.0f)
            .sounds(BlockSoundGroup.DEEPSLATE);
    }

    // Cable settings
    private static AbstractBlock.Settings cableSettings(boolean heavy) {
        return AbstractBlock.Settings.create()
            .mapColor(MapColor.OAK_TAN)
            .nonOpaque()
            .strength(heavy ? 1.5f : 0.5f)
            .sounds(heavy ? BlockSoundGroup.METAL : BlockSoundGroup.WOOL);
    }

    // Switchgear settings
    private static AbstractBlock.Settings switchgearSettings() {
        return AbstractBlock.Settings.create()
            .mapColor(MapColor.IRON_GRAY)
            .requiresTool()
            .strength(2.0f, 3.0f)
            .nonOpaque()
            .sounds(BlockSoundGroup.COPPER);
    }

    // Stationary BESS storage settings
    private static AbstractBlock.Settings batterySettings() {
        return AbstractBlock.Settings.create()
            .mapColor(MapColor.IRON_GRAY)
            .requiresTool()
            .strength(3.5f, 6.0f)
            .sounds(BlockSoundGroup.METAL);
    }

    // Power conversion hardware settings
    private static AbstractBlock.Settings conversionSettings() {
        return AbstractBlock.Settings.create()
            .mapColor(MapColor.IRON_GRAY)
            .requiresTool()
            .strength(2.5f, 5.0f)
            .sounds(BlockSoundGroup.METAL);
    }

    // Solar PV settings
    private static AbstractBlock.Settings solarSettings() {
        return AbstractBlock.Settings.create()
            .mapColor(MapColor.LAPIS_BLUE)
            .requiresTool()
            .nonOpaque()
            .strength(1.5f, 3.0f)
            .sounds(BlockSoundGroup.GLASS);
    }

    // Mechanical & combustion generator settings
    private static AbstractBlock.Settings generatorSettings() {
        return AbstractBlock.Settings.create()
            .mapColor(MapColor.IRON_GRAY)
            .requiresTool()
            .strength(3.0f, 6.0f)
            .sounds(BlockSoundGroup.METAL);
    }

    // Testing & Creative Hardware settings
    private static AbstractBlock.Settings creativeSettings() {
        return AbstractBlock.Settings.create()
            .mapColor(MapColor.PURPLE)
            .strength(-1.0f, 3600000.0f)
            .sounds(BlockSoundGroup.METAL);
    }

    // --- Natural Ores ---
    public static final Block ORE_BAUXITE = register("ore_bauxite", s -> new Block(s), stoneOreSettings());
    public static final Block DEEPSLATE_ORE_BAUXITE = register("deepslate_ore_bauxite", s -> new Block(s), deepslateOreSettings());
    public static final Block ORE_GALENA = register("ore_galena", s -> new Block(s), stoneOreSettings());
    public static final Block DEEPSLATE_ORE_GALENA = register("deepslate_ore_galena", s -> new Block(s), deepslateOreSettings());
    public static final Block ORE_SPHALERITE = register("ore_sphalerite", s -> new Block(s), stoneOreSettings());
    public static final Block DEEPSLATE_ORE_SPHALERITE = register("deepslate_ore_sphalerite", s -> new Block(s), deepslateOreSettings());
    public static final Block ORE_SPODUMENE = register("ore_spodumene", s -> new Block(s), stoneOreSettings());
    public static final Block DEEPSLATE_ORE_SPODUMENE = register("deepslate_ore_spodumene", s -> new Block(s), deepslateOreSettings());
    public static final Block ORE_PENTLANDITE = register("ore_pentlandite", s -> new Block(s), stoneOreSettings());
    public static final Block DEEPSLATE_ORE_PENTLANDITE = register("deepslate_ore_pentlandite", s -> new Block(s), deepslateOreSettings());
    public static final Block ORE_HIGH_PURITY_QUARTZ = register("ore_high_purity_quartz", s -> new Block(s), stoneOreSettings());
    public static final Block DEEPSLATE_ORE_QUARTZ = register("deepslate_ore_quartz", s -> new Block(s), deepslateOreSettings());

    // --- Conductors & Cables (Strict No-Wire-Ticking Architecture) ---
    public static final Block CABLE_COPPER_BARE = register(
        "cable_copper_bare", s -> new CableBlock(ConductorType.BARE_COPPER, s), cableSettings(false)
    );
    public static final Block CABLE_COPPER_INSULATED = register(
        "cable_copper_insulated", s -> new CableBlock(ConductorType.INSULATED_COPPER, s), cableSettings(false)
    );
    public static final Block CABLE_COPPER_HEAVY = register(
        "cable_copper_heavy", s -> new CableBlock(ConductorType.HEAVY_COPPER, s), cableSettings(true)
    );
    public static final Block CABLE_ALUMINUM_TRANSMISSION = register(
        "cable_aluminum_transmission", s -> new CableBlock(ConductorType.ALUMINUM_TRANSMISSION, s), cableSettings(false)
    );
    public static final Block CABLE_SILVER_PRECISION = register(
        "cable_silver_precision", s -> new CableBlock(ConductorType.SILVER_PRECISION, s), cableSettings(false)
    );
    public static final Block CABLE_GOLD_BUS = register(
        "cable_gold_bus", s -> new CableBlock(ConductorType.GOLD_BUS, s), cableSettings(false)
    );
    public static final Block CABLE_STEEL_FENCE = register(
        "cable_steel_fence", s -> new CableBlock(ConductorType.STEEL_FENCE, s), cableSettings(false)
    );
    public static final Block CABLE_NICHROME_HEATING = register(
        "cable_nichrome_heating", s -> new CableBlock(ConductorType.NICHROME_HEATING, s), cableSettings(false)
    );
    public static final Block CONDUIT_SUPERCONDUCTOR = register(
        "conduit_superconductor", s -> new CableBlock(ConductorType.SUPERCONDUCTOR_CONDUIT, s), cableSettings(true)
    );

    // --- Switchgear & Distribution Hardware ---
    public static final Block COPPER_BUSBAR = register(
        "copper_busbar", CopperBusbarBlock::new, switchgearSettings()
    );
    public static final Block JUNCTION_BOX = register(
        "junction_box", JunctionBoxBlock::new, switchgearSettings()
    );
    public static final Block KNIFE_SWITCH = register(
        "knife_switch", KnifeSwitchBlock::new, switchgearSettings()
    );
    public static final Block CIRCUIT_BREAKER = register(
        "circuit_breaker", CircuitBreakerBlock::new, switchgearSettings()
    );
    public static final Block FUSE_BOX = register(
        "fuse_box", FuseBoxBlock::new, switchgearSettings()
    );
    public static final Block CONTACTOR_RELAY = register(
        "contactor_relay", ContactorRelayBlock::new, switchgearSettings()
    );

    // --- Stationary Energy Storage Systems (BESS) ---
    public static final Block BATTERY_BLOCK_LIFEPO4 = register(
        "battery_block_lifepo4", s -> new BatteryBlock(s, BatteryChemistry.LIFEPO4, 15, 1), batterySettings()
    );
    public static final Block BATTERY_BLOCK_LEAD_ACID = register(
        "battery_block_lead_acid", s -> new BatteryBlock(s, BatteryChemistry.LEAD_ACID, 6, 1), batterySettings()
    );
    public static final Block BATTERY_BLOCK_LTO = register(
        "battery_block_lto", s -> new BatteryBlock(s, BatteryChemistry.LTO, 10, 1), batterySettings()
    );
    public static final Block BATTERY_RACK_MODULAR = register(
        "battery_rack_modular", BatteryRackBlock::new, batterySettings()
    );
    public static final Block BATTERY_BLOCK_NIMH = register(
        "battery_block_nimh", s -> new BatteryBlock(s, BatteryChemistry.NIMH, 20, 20), batterySettings()
    );
    public static final Block BATTERY_BLOCK_NICD = register(
        "battery_block_nicd", s -> new BatteryBlock(s, BatteryChemistry.NICD, 20, 25), batterySettings()
    );

    // --- Power Conversion: DC-DC Switched-Mode Converters & Linear LDO ---
    public static final Block CONVERTER_DC_BUCK = register(
        "converter_dc_buck", s -> new ConverterBlock(s, ConverterType.BUCK), conversionSettings()
    );
    public static final Block CONVERTER_DC_BOOST = register(
        "converter_dc_boost", s -> new ConverterBlock(s, ConverterType.BOOST), conversionSettings()
    );
    public static final Block CONVERTER_DC_BUCK_BOOST = register(
        "converter_dc_buck_boost", s -> new ConverterBlock(s, ConverterType.BUCK_BOOST), conversionSettings()
    );
    public static final Block REGULATOR_LINEAR_LDO = register(
        "regulator_linear_ldo", s -> new ConverterBlock(s, ConverterType.LINEAR_LDO), conversionSettings()
    );

    // --- Power Conversion: AC Transformers ---
    public static final Block TRANSFORMER_AC_STEP_DOWN = register(
        "transformer_ac_step_down", s -> new TransformerBlock(s, TransformerType.STEP_DOWN), conversionSettings()
    );
    public static final Block TRANSFORMER_AC_STEP_UP = register(
        "transformer_ac_step_up", s -> new TransformerBlock(s, TransformerType.STEP_UP), conversionSettings()
    );

    // --- Power Conversion: AC-DC Rectifiers ---
    public static final Block RECTIFIER_BRIDGE = register(
        "rectifier_bridge", s -> new RectifierBlock(s, RectifierType.BRIDGE), conversionSettings()
    );
    public static final Block RECTIFIER_ACTIVE_SYNCHRONOUS = register(
        "rectifier_active_synchronous", s -> new RectifierBlock(s, RectifierType.ACTIVE_SYNCHRONOUS), conversionSettings()
    );

    // --- Power Conversion: DC-AC Inverters ---
    public static final Block INVERTER_SQUARE_WAVE = register(
        "inverter_square_wave", s -> new InverterBlock(s, InverterType.SQUARE_WAVE), conversionSettings()
    );
    public static final Block INVERTER_MODIFIED_SINE = register(
        "inverter_modified_sine", s -> new InverterBlock(s, InverterType.MODIFIED_SINE), conversionSettings()
    );
    public static final Block INVERTER_PURE_SINE = register(
        "inverter_pure_sine", s -> new InverterBlock(s, InverterType.PURE_SINE), conversionSettings()
    );
    public static final Block INVERTER_GRID_TIE = register(
        "inverter_grid_tie", s -> new InverterBlock(s, InverterType.GRID_TIE), conversionSettings()
    );
    public static final Block INVERTER_HYBRID_ESS = register(
        "inverter_hybrid_ess", s -> new InverterBlock(s, InverterType.HYBRID_ESS), conversionSettings()
    );

    // --- Power Conversion: 230V AC to EU Converter ---
    public static final Block CONVERTER_EU = register(
        "converter_eu", EuConverterBlock::new, conversionSettings()
    );

    // --- Power Generation & Renewable Systems ---
    public static final Block SOLAR_PANEL_MONOCRYSTALLINE = register(
        "solar_panel_monocrystalline", s -> new SolarPanelBlock(s, SolarPanelType.MONOCRYSTALLINE_PERC), solarSettings()
    );
    public static final Block SOLAR_PANEL_POLYCRYSTALLINE = register(
        "solar_panel_polycrystalline", s -> new SolarPanelBlock(s, SolarPanelType.POLYCRYSTALLINE), solarSettings()
    );
    public static final Block SOLAR_PANEL_THIN_FILM = register(
        "solar_panel_thin_film", s -> new SolarPanelBlock(s, SolarPanelType.THIN_FILM_CDTE), solarSettings()
    );
    public static final Block SOLAR_PANEL_CONCENTRATOR = register(
        "solar_panel_concentrator", s -> new SolarPanelBlock(s, SolarPanelType.CONCENTRATOR_CPV), solarSettings()
    );
    public static final Block CHARGE_CONTROLLER_MPPT = register(
        "charge_controller_mppt", ChargeControllerBlock::new, conversionSettings()
    );
    public static final Block GENERATOR_HAND_CRANK = register(
        "generator_hand_crank", HandCrankGeneratorBlock::new, generatorSettings()
    );
    public static final Block GENERATOR_PORTABLE_INVERTER = register(
        "generator_portable_inverter", PortableGeneratorBlock::new, generatorSettings()
    );

    // --- Testing & Creative Hardware ---
    public static final Block CREATIVE_GENERATOR = register(
        "creative_generator", CreativeGeneratorBlock::new, creativeSettings()
    );
    public static final Block CREATIVE_LOAD = register(
        "creative_load", CreativeLoadBlock::new, creativeSettings()
    );

    private static Block register(String name, Function<AbstractBlock.Settings, Block> blockFactory, AbstractBlock.Settings baseSettings) {
        Identifier id = Identifier.of(Voltcraft.MOD_ID, name);
        RegistryKey<Block> key = RegistryKey.of(RegistryKeys.BLOCK, id);
        AbstractBlock.Settings settings = baseSettings.registryKey(key);
        Block block = blockFactory.apply(settings);
        ALL_BLOCKS.put(id, block);
        return Registry.register(Registries.BLOCK, key, block);
    }

    public static void initialize() {
        // Classloading trigger
    }
}
