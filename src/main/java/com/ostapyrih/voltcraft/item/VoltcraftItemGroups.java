package com.ostapyrih.voltcraft.item;

import com.ostapyrih.voltcraft.Voltcraft;
import com.ostapyrih.voltcraft.block.VoltcraftBlocks;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Creative mode tabs for VoltCraft, separating raw metallurgy, components & batteries, and power grid hardware.
 */
public class VoltcraftItemGroups {

    public static final ItemGroup MATERIALS_GROUP = Registry.register(
        Registries.ITEM_GROUP,
        Identifier.of(Voltcraft.MOD_ID, "materials"),
        FabricItemGroup.builder()
            .icon(() -> new ItemStack(VoltcraftItems.ALUMINUM_INGOT))
            .displayName(Text.translatable("itemGroup.voltcraft.materials"))
            .entries((displayContext, entries) -> {
                // Stone & Deepslate Ores
                entries.add(VoltcraftBlocks.ORE_BAUXITE);
                entries.add(VoltcraftBlocks.DEEPSLATE_ORE_BAUXITE);
                entries.add(VoltcraftBlocks.ORE_GALENA);
                entries.add(VoltcraftBlocks.DEEPSLATE_ORE_GALENA);
                entries.add(VoltcraftBlocks.ORE_SPHALERITE);
                entries.add(VoltcraftBlocks.DEEPSLATE_ORE_SPHALERITE);
                entries.add(VoltcraftBlocks.ORE_SPODUMENE);
                entries.add(VoltcraftBlocks.DEEPSLATE_ORE_SPODUMENE);
                entries.add(VoltcraftBlocks.ORE_PENTLANDITE);
                entries.add(VoltcraftBlocks.DEEPSLATE_ORE_PENTLANDITE);
                entries.add(VoltcraftBlocks.ORE_HIGH_PURITY_QUARTZ);
                entries.add(VoltcraftBlocks.DEEPSLATE_ORE_QUARTZ);

                // Raw Ores
                entries.add(VoltcraftItems.RAW_BAUXITE);
                entries.add(VoltcraftItems.RAW_GALENA);
                entries.add(VoltcraftItems.RAW_SPHALERITE);
                entries.add(VoltcraftItems.RAW_SPODUMENE);
                entries.add(VoltcraftItems.RAW_PENTLANDITE);

                // Refined Ingots & Mineral Dusts
                entries.add(VoltcraftItems.ALUMINUM_INGOT);
                entries.add(VoltcraftItems.LEAD_INGOT);
                entries.add(VoltcraftItems.ZINC_INGOT);
                entries.add(VoltcraftItems.LITHIUM_INGOT);
                entries.add(VoltcraftItems.NICKEL_INGOT);
                entries.add(VoltcraftItems.SILVER_INGOT);
                entries.add(VoltcraftItems.SILVER_NUGGET);
                entries.add(VoltcraftItems.PURE_SILICA_DUST);

                // Engineering Alloys & Insulation
                entries.add(VoltcraftItems.NICHROME_INGOT);
                entries.add(VoltcraftItems.FUSE_ALLOY_INGOT);
                entries.add(VoltcraftItems.RUBBER_SHEET);
            })
            .build()
    );

    public static final ItemGroup COMPONENTS_GROUP = Registry.register(
        Registries.ITEM_GROUP,
        Identifier.of(Voltcraft.MOD_ID, "components"),
        FabricItemGroup.builder()
            .icon(() -> new ItemStack(VoltcraftItems.BMS_LOGIC_BOARD))
            .displayName(Text.translatable("itemGroup.voltcraft.components"))
            .entries((displayContext, entries) -> {
                // Semiconductors & Photovoltaics
                entries.add(VoltcraftItems.SILICON_BOULE);
                entries.add(VoltcraftItems.SILICON_WAFER);
                entries.add(VoltcraftItems.DOPED_WAFER_P);
                entries.add(VoltcraftItems.DOPED_WAFER_N);
                entries.add(VoltcraftItems.PHOTOVOLTAIC_CELL);

                // Power Electronics & Discrete Components
                entries.add(VoltcraftItems.MOSFET_POWER_TRANSISTOR);
                entries.add(VoltcraftItems.SCHOTTKY_DIODE);
                entries.add(VoltcraftItems.FILTER_CAPACITOR_ELECTROLYTIC);
                entries.add(VoltcraftItems.COPPER_MAGNET_WIRE);
                entries.add(VoltcraftItems.TRANSFORMER_CORE_LAMINATED);
                entries.add(VoltcraftItems.BMS_LOGIC_BOARD);

                // Portable Battery Cells (Item Form)
                entries.add(VoltcraftItems.BATTERY_18650_LI_ION);
                entries.add(VoltcraftItems.BATTERY_21700_HIGH_DRAIN);
                entries.add(VoltcraftItems.BATTERY_CELL_ALKALINE);
                entries.add(VoltcraftItems.BATTERY_CELL_ZINC_CARBON);
                entries.add(VoltcraftItems.BATTERY_COIN_CR2032);
                entries.add(VoltcraftItems.BATTERY_CELL_LISOCL2);
                entries.add(VoltcraftItems.BATTERY_CELL_NICD);
                entries.add(VoltcraftItems.BATTERY_CELL_NIMH);
            })
            .build()
    );

    public static final ItemGroup GRID_GROUP = Registry.register(
        Registries.ITEM_GROUP,
        Identifier.of(Voltcraft.MOD_ID, "grid"),
        FabricItemGroup.builder()
            .icon(() -> new ItemStack(VoltcraftBlocks.KNIFE_SWITCH))
            .displayName(Text.translatable("itemGroup.voltcraft.grid"))
            .entries((displayContext, entries) -> {
                // Conductors & Transmission Lines
                entries.add(VoltcraftBlocks.CABLE_COPPER_BARE);
                entries.add(VoltcraftBlocks.CABLE_COPPER_INSULATED);
                entries.add(VoltcraftBlocks.CABLE_COPPER_HEAVY);
                entries.add(VoltcraftBlocks.CABLE_ALUMINUM_TRANSMISSION);
                entries.add(VoltcraftBlocks.CABLE_SILVER_PRECISION);
                entries.add(VoltcraftBlocks.CABLE_GOLD_BUS);
                entries.add(VoltcraftBlocks.CABLE_STEEL_FENCE);
                entries.add(VoltcraftBlocks.CABLE_NICHROME_HEATING);
                entries.add(VoltcraftBlocks.CONDUIT_SUPERCONDUCTOR);

                // Switchgear & Safety Hardware
                entries.add(VoltcraftBlocks.COPPER_BUSBAR);
                entries.add(VoltcraftBlocks.JUNCTION_BOX);
                entries.add(VoltcraftBlocks.KNIFE_SWITCH);
                entries.add(VoltcraftBlocks.CIRCUIT_BREAKER);
                entries.add(VoltcraftBlocks.FUSE_BOX);
                entries.add(VoltcraftBlocks.CONTACTOR_RELAY);
                entries.add(VoltcraftBlocks.EARTH_ROD);

                // Stationary Energy Storage Systems (BESS)
                entries.add(VoltcraftBlocks.BATTERY_BLOCK_LIFEPO4);
                entries.add(VoltcraftBlocks.BATTERY_BLOCK_LEAD_ACID);
                entries.add(VoltcraftBlocks.BATTERY_BLOCK_LTO);
                entries.add(VoltcraftBlocks.BATTERY_RACK_MODULAR);
                entries.add(VoltcraftBlocks.BATTERY_BLOCK_NIMH);
                entries.add(VoltcraftBlocks.BATTERY_BLOCK_NICD);

                // Power Conversion: DC-DC Converters & Linear LDO
                entries.add(VoltcraftBlocks.CONVERTER_DC_BUCK);
                entries.add(VoltcraftBlocks.CONVERTER_DC_BOOST);
                entries.add(VoltcraftBlocks.CONVERTER_DC_BUCK_BOOST);
                entries.add(VoltcraftBlocks.REGULATOR_LINEAR_LDO);

                // Power Conversion: AC Transformers
                entries.add(VoltcraftBlocks.TRANSFORMER_AC_STEP_DOWN);
                entries.add(VoltcraftBlocks.TRANSFORMER_AC_STEP_UP);

                // Power Conversion: AC-DC Rectifiers
                entries.add(VoltcraftBlocks.RECTIFIER_BRIDGE);
                entries.add(VoltcraftBlocks.RECTIFIER_ACTIVE_SYNCHRONOUS);

                // Power Conversion: DC-AC Inverters
                entries.add(VoltcraftBlocks.INVERTER_SQUARE_WAVE);
                entries.add(VoltcraftBlocks.INVERTER_MODIFIED_SINE);
                entries.add(VoltcraftBlocks.INVERTER_PURE_SINE);
                entries.add(VoltcraftBlocks.INVERTER_GRID_TIE);
                entries.add(VoltcraftBlocks.INVERTER_HYBRID_ESS);

                // Power Conversion: 230V AC to E Energy Bridge
                entries.add(VoltcraftBlocks.CONVERTER_EU);

                // Power Generation & Renewable Systems
                entries.add(VoltcraftBlocks.SOLAR_PANEL_MONOCRYSTALLINE);
                entries.add(VoltcraftBlocks.SOLAR_PANEL_POLYCRYSTALLINE);
                entries.add(VoltcraftBlocks.SOLAR_PANEL_THIN_FILM);
                entries.add(VoltcraftBlocks.SOLAR_PANEL_CONCENTRATOR);
                entries.add(VoltcraftBlocks.CHARGE_CONTROLLER_MPPT);
                entries.add(VoltcraftBlocks.GENERATOR_HAND_CRANK);
                entries.add(VoltcraftBlocks.GENERATOR_PORTABLE_INVERTER);

                // Testing & Creative Hardware
                entries.add(VoltcraftBlocks.CREATIVE_GENERATOR);
                entries.add(VoltcraftBlocks.CREATIVE_LOAD);
            })
            .build()
    );

    public static void initialize() {
        // Triggers classloading and static registration of tabs
    }
}
