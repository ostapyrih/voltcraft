package com.ostapyrih.voltcraft.client;

import com.ostapyrih.voltcraft.block.VoltcraftBlocks;
import com.ostapyrih.voltcraft.item.VoltcraftItems;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricRecipeProvider;
import net.minecraft.data.recipe.RecipeExporter;
import net.minecraft.data.recipe.RecipeGenerator;
import net.minecraft.item.ItemConvertible;
import net.minecraft.item.Items;
import net.minecraft.recipe.book.RecipeCategory;
import net.minecraft.registry.RegistryWrapper;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Generates vanilla furnace, blast furnace, and crafting recipes for all VoltCraft metallurgy, conductors, switchgear, electronics, battery storage, and power conversion.
 */
public class VoltcraftRecipeGenerator extends FabricRecipeProvider {

    public VoltcraftRecipeGenerator(FabricDataOutput output, CompletableFuture<RegistryWrapper.WrapperLookup> registriesFuture) {
        super(output, registriesFuture);
    }

    @Override
    public String getName() {
        return "VoltCraft Recipes";
    }

    @Override
    protected RecipeGenerator getRecipeGenerator(RegistryWrapper.WrapperLookup registries, RecipeExporter exporter) {
        return new RecipeGenerator(registries, exporter) {
            @Override
            public void generate() {
                // ==================== 1. ORE SMELTING & BLASTING ====================\
                // Aluminum (Bauxite)
                List<ItemConvertible> bauxiteInputs = List.of(
                    VoltcraftItems.RAW_BAUXITE,
                    VoltcraftBlocks.ORE_BAUXITE,
                    VoltcraftBlocks.DEEPSLATE_ORE_BAUXITE
                );
                offerSmelting(bauxiteInputs, RecipeCategory.MISC, VoltcraftItems.ALUMINUM_INGOT, 0.7f, 200, "aluminum_ingot");
                offerBlasting(bauxiteInputs, RecipeCategory.MISC, VoltcraftItems.ALUMINUM_INGOT, 0.7f, 100, "aluminum_ingot");

                // Lead (Galena)
                List<ItemConvertible> galenaInputs = List.of(
                    VoltcraftItems.RAW_GALENA,
                    VoltcraftBlocks.ORE_GALENA,
                    VoltcraftBlocks.DEEPSLATE_ORE_GALENA
                );
                offerSmelting(galenaInputs, RecipeCategory.MISC, VoltcraftItems.LEAD_INGOT, 0.7f, 200, "lead_ingot");
                offerBlasting(galenaInputs, RecipeCategory.MISC, VoltcraftItems.LEAD_INGOT, 0.7f, 100, "lead_ingot");

                // Zinc (Sphalerite)
                List<ItemConvertible> sphaleriteInputs = List.of(
                    VoltcraftItems.RAW_SPHALERITE,
                    VoltcraftBlocks.ORE_SPHALERITE,
                    VoltcraftBlocks.DEEPSLATE_ORE_SPHALERITE
                );
                offerSmelting(sphaleriteInputs, RecipeCategory.MISC, VoltcraftItems.ZINC_INGOT, 0.7f, 200, "zinc_ingot");
                offerBlasting(sphaleriteInputs, RecipeCategory.MISC, VoltcraftItems.ZINC_INGOT, 0.7f, 100, "zinc_ingot");

                // Lithium (Spodumene)
                List<ItemConvertible> spodumeneInputs = List.of(
                    VoltcraftItems.RAW_SPODUMENE,
                    VoltcraftBlocks.ORE_SPODUMENE,
                    VoltcraftBlocks.DEEPSLATE_ORE_SPODUMENE
                );
                offerSmelting(spodumeneInputs, RecipeCategory.MISC, VoltcraftItems.LITHIUM_INGOT, 0.7f, 200, "lithium_ingot");
                offerBlasting(spodumeneInputs, RecipeCategory.MISC, VoltcraftItems.LITHIUM_INGOT, 0.7f, 100, "lithium_ingot");

                // Nickel (Pentlandite)
                List<ItemConvertible> pentlanditeInputs = List.of(
                    VoltcraftItems.RAW_PENTLANDITE,
                    VoltcraftBlocks.ORE_PENTLANDITE,
                    VoltcraftBlocks.DEEPSLATE_ORE_PENTLANDITE
                );
                offerSmelting(pentlanditeInputs, RecipeCategory.MISC, VoltcraftItems.NICKEL_INGOT, 0.7f, 200, "nickel_ingot");
                offerBlasting(pentlanditeInputs, RecipeCategory.MISC, VoltcraftItems.NICKEL_INGOT, 0.7f, 100, "nickel_ingot");

                // High-Purity Quartz -> High-Purity Silica Dust
                List<ItemConvertible> quartzInputs = List.of(
                    VoltcraftBlocks.ORE_HIGH_PURITY_QUARTZ,
                    VoltcraftBlocks.DEEPSLATE_ORE_QUARTZ
                );
                offerSmelting(quartzInputs, RecipeCategory.MISC, VoltcraftItems.PURE_SILICA_DUST, 0.7f, 200, "pure_silica_dust");
                offerBlasting(quartzInputs, RecipeCategory.MISC, VoltcraftItems.PURE_SILICA_DUST, 0.7f, 100, "pure_silica_dust");

                // Silver Nugget <-> Silver Ingot
                offerReversibleCompactingRecipes(
                    RecipeCategory.MISC,
                    VoltcraftItems.SILVER_NUGGET,
                    RecipeCategory.MISC,
                    VoltcraftItems.SILVER_INGOT
                );

                // Rubber Sheet Smelting from Slime Ball
                offerSmelting(List.of(Items.SLIME_BALL), RecipeCategory.MISC, VoltcraftItems.RUBBER_SHEET, 0.35f, 200, "rubber_sheet");
                offerBlasting(List.of(Items.SLIME_BALL), RecipeCategory.MISC, VoltcraftItems.RUBBER_SHEET, 0.35f, 100, "rubber_sheet");

                // ==================== 2. SPECIALIZED ALLOYS ====================\
                // Fuse Alloy Ingot (Lead + Zinc)
                createShapeless(RecipeCategory.MISC, VoltcraftItems.FUSE_ALLOY_INGOT, 2)
                    .input(VoltcraftItems.LEAD_INGOT)
                    .input(VoltcraftItems.ZINC_INGOT)
                    .criterion(hasItem(VoltcraftItems.LEAD_INGOT), conditionsFromItem(VoltcraftItems.LEAD_INGOT))
                    .criterion(hasItem(VoltcraftItems.ZINC_INGOT), conditionsFromItem(VoltcraftItems.ZINC_INGOT))
                    .offerTo(exporter);

                // Nichrome Ingot (Nickel + Iron)
                createShapeless(RecipeCategory.MISC, VoltcraftItems.NICHROME_INGOT, 2)
                    .input(VoltcraftItems.NICKEL_INGOT)
                    .input(Items.IRON_INGOT)
                    .criterion(hasItem(VoltcraftItems.NICKEL_INGOT), conditionsFromItem(VoltcraftItems.NICKEL_INGOT))
                    .offerTo(exporter);

                // Rubber Sheet (Slime + Coal/Charcoal shapeless)
                createShapeless(RecipeCategory.MISC, VoltcraftItems.RUBBER_SHEET, 2)
                    .input(Items.SLIME_BALL)
                    .input(Items.COAL)
                    .criterion(hasItem(Items.SLIME_BALL), conditionsFromItem(Items.SLIME_BALL))
                    .offerTo(exporter, "rubber_sheet_from_coal");

                createShapeless(RecipeCategory.MISC, VoltcraftItems.RUBBER_SHEET, 2)
                    .input(Items.SLIME_BALL)
                    .input(Items.CHARCOAL)
                    .criterion(hasItem(Items.SLIME_BALL), conditionsFromItem(Items.SLIME_BALL))
                    .offerTo(exporter, "rubber_sheet_from_charcoal");

                // ==================== 3. SEMICONDUCTOR CHAIN ====================\
                // Silicon Boule (4x Silica Dust + Coal)
                createShapeless(RecipeCategory.MISC, VoltcraftItems.SILICON_BOULE, 1)
                    .input(VoltcraftItems.PURE_SILICA_DUST)
                    .input(VoltcraftItems.PURE_SILICA_DUST)
                    .input(VoltcraftItems.PURE_SILICA_DUST)
                    .input(VoltcraftItems.PURE_SILICA_DUST)
                    .input(Items.COAL)
                    .criterion(hasItem(VoltcraftItems.PURE_SILICA_DUST), conditionsFromItem(VoltcraftItems.PURE_SILICA_DUST))
                    .offerTo(exporter);

                // Silicon Wafer (Silicon Boule + Iron Ingot / Shears)
                createShapeless(RecipeCategory.MISC, VoltcraftItems.SILICON_WAFER, 8)
                    .input(VoltcraftItems.SILICON_BOULE)
                    .input(Items.IRON_INGOT)
                    .criterion(hasItem(VoltcraftItems.SILICON_BOULE), conditionsFromItem(VoltcraftItems.SILICON_BOULE))
                    .offerTo(exporter);

                // Doped P-Wafer (Silicon Wafer + Redstone Dust)
                createShapeless(RecipeCategory.MISC, VoltcraftItems.DOPED_WAFER_P, 1)
                    .input(VoltcraftItems.SILICON_WAFER)
                    .input(Items.REDSTONE)
                    .criterion(hasItem(VoltcraftItems.SILICON_WAFER), conditionsFromItem(VoltcraftItems.SILICON_WAFER))
                    .offerTo(exporter);

                // Doped N-Wafer (Silicon Wafer + Glowstone Dust)
                createShapeless(RecipeCategory.MISC, VoltcraftItems.DOPED_WAFER_N, 1)
                    .input(VoltcraftItems.SILICON_WAFER)
                    .input(Items.GLOWSTONE_DUST)
                    .criterion(hasItem(VoltcraftItems.SILICON_WAFER), conditionsFromItem(VoltcraftItems.SILICON_WAFER))
                    .offerTo(exporter);

                // Photovoltaic Cell
                createShaped(RecipeCategory.MISC, VoltcraftItems.PHOTOVOLTAIC_CELL, 3)
                    .pattern("SGS")
                    .pattern("NPN")
                    .pattern("CAC")
                    .input('S', VoltcraftItems.SILVER_NUGGET)
                    .input('G', Items.GLASS_PANE)
                    .input('N', VoltcraftItems.DOPED_WAFER_N)
                    .input('P', VoltcraftItems.DOPED_WAFER_P)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .input('A', VoltcraftItems.ALUMINUM_INGOT)
                    .criterion(hasItem(VoltcraftItems.DOPED_WAFER_P), conditionsFromItem(VoltcraftItems.DOPED_WAFER_P))
                    .offerTo(exporter);

                // ==================== 4. DISCRETE ELECTRONIC COMPONENTS ====================\
                // Power MOSFET Transistor
                createShaped(RecipeCategory.MISC, VoltcraftItems.MOSFET_POWER_TRANSISTOR, 4)
                    .pattern("RIR")
                    .pattern("NPN")
                    .pattern("CCC")
                    .input('R', VoltcraftItems.RUBBER_SHEET)
                    .input('I', Items.IRON_NUGGET)
                    .input('N', VoltcraftItems.DOPED_WAFER_N)
                    .input('P', VoltcraftItems.DOPED_WAFER_P)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .criterion(hasItem(VoltcraftItems.DOPED_WAFER_N), conditionsFromItem(VoltcraftItems.DOPED_WAFER_N))
                    .offerTo(exporter);

                // Schottky Diode
                createShaped(RecipeCategory.MISC, VoltcraftItems.SCHOTTKY_DIODE, 4)
                    .pattern(" G ")
                    .pattern("CNC")
                    .pattern(" P ")
                    .input('G', Items.GOLD_NUGGET)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .input('N', VoltcraftItems.DOPED_WAFER_N)
                    .input('P', Items.GLASS_PANE)
                    .criterion(hasItem(VoltcraftItems.DOPED_WAFER_N), conditionsFromItem(VoltcraftItems.DOPED_WAFER_N))
                    .offerTo(exporter);

                // Electrolytic Filter Capacitor
                createShaped(RecipeCategory.MISC, VoltcraftItems.FILTER_CAPACITOR_ELECTROLYTIC, 4)
                    .pattern("RAR")
                    .pattern("PGP")
                    .pattern("CAC")
                    .input('R', VoltcraftItems.RUBBER_SHEET)
                    .input('A', VoltcraftItems.ALUMINUM_INGOT)
                    .input('P', Items.PAPER)
                    .input('G', Items.GLOWSTONE_DUST)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .criterion(hasItem(VoltcraftItems.ALUMINUM_INGOT), conditionsFromItem(VoltcraftItems.ALUMINUM_INGOT))
                    .offerTo(exporter);

                // Copper Magnet Wire
                createShapeless(RecipeCategory.MISC, VoltcraftItems.COPPER_MAGNET_WIRE, 8)
                    .input(Items.COPPER_INGOT)
                    .input(Items.REDSTONE)
                    .criterion(hasItem(Items.COPPER_INGOT), conditionsFromItem(Items.COPPER_INGOT))
                    .offerTo(exporter);

                // Laminated Transformer Core
                createShaped(RecipeCategory.MISC, VoltcraftItems.TRANSFORMER_CORE_LAMINATED, 2)
                    .pattern("ISI")
                    .pattern("III")
                    .pattern("ISI")
                    .input('I', Items.IRON_INGOT)
                    .input('S', VoltcraftItems.PURE_SILICA_DUST)
                    .criterion(hasItem(VoltcraftItems.PURE_SILICA_DUST), conditionsFromItem(VoltcraftItems.PURE_SILICA_DUST))
                    .offerTo(exporter);

                // BMS Logic Board
                createShaped(RecipeCategory.MISC, VoltcraftItems.BMS_LOGIC_BOARD, 1)
                    .pattern("GMG")
                    .pattern("DRS")
                    .pattern("CWC")
                    .input('G', VoltcraftBlocks.CABLE_GOLD_BUS)
                    .input('M', VoltcraftItems.MOSFET_POWER_TRANSISTOR)
                    .input('D', VoltcraftItems.SCHOTTKY_DIODE)
                    .input('R', Items.REPEATER)
                    .input('S', VoltcraftItems.SILVER_NUGGET)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .input('W', VoltcraftItems.SILICON_WAFER)
                    .criterion(hasItem(VoltcraftItems.MOSFET_POWER_TRANSISTOR), conditionsFromItem(VoltcraftItems.MOSFET_POWER_TRANSISTOR))
                    .offerTo(exporter);

                // ==================== 5. CABLES & CONDUCTORS ====================\
                // Bare Copper Wire (3 Copper Ingots -> 6 Wire)
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.CABLE_COPPER_BARE, 6)
                    .pattern("CCC")
                    .input('C', Items.COPPER_INGOT)
                    .criterion(hasItem(Items.COPPER_INGOT), conditionsFromItem(Items.COPPER_INGOT))
                    .offerTo(exporter);

                // Insulated Copper Cable (Rubber + Bare Copper + Rubber)
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.CABLE_COPPER_INSULATED, 6)
                    .pattern("RRR")
                    .pattern("CCC")
                    .pattern("RRR")
                    .input('R', VoltcraftItems.RUBBER_SHEET)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .criterion(hasItem(VoltcraftBlocks.CABLE_COPPER_BARE), conditionsFromItem(VoltcraftBlocks.CABLE_COPPER_BARE))
                    .offerTo(exporter);

                // Heavy Industrial Copper Cable
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.CABLE_COPPER_HEAVY, 6)
                    .pattern("IRI")
                    .pattern("CCC")
                    .pattern("IRI")
                    .input('I', Items.IRON_INGOT)
                    .input('R', VoltcraftItems.RUBBER_SHEET)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_INSULATED)
                    .criterion(hasItem(VoltcraftBlocks.CABLE_COPPER_INSULATED), conditionsFromItem(VoltcraftBlocks.CABLE_COPPER_INSULATED))
                    .offerTo(exporter);

                // Aluminum Overhead Transmission Line
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.CABLE_ALUMINUM_TRANSMISSION, 16)
                    .pattern("AAA")
                    .pattern("ASA")
                    .pattern("AAA")
                    .input('A', VoltcraftItems.ALUMINUM_INGOT)
                    .input('S', Items.STRING)
                    .criterion(hasItem(VoltcraftItems.ALUMINUM_INGOT), conditionsFromItem(VoltcraftItems.ALUMINUM_INGOT))
                    .offerTo(exporter);

                // Silver Precision Wire
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.CABLE_SILVER_PRECISION, 8)
                    .pattern("SSS")
                    .pattern("RPR")
                    .input('S', VoltcraftItems.SILVER_INGOT)
                    .input('R', VoltcraftItems.RUBBER_SHEET)
                    .input('P', Items.PAPER)
                    .criterion(hasItem(VoltcraftItems.SILVER_INGOT), conditionsFromItem(VoltcraftItems.SILVER_INGOT))
                    .offerTo(exporter);

                // Gold Bus Cable
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.CABLE_GOLD_BUS, 8)
                    .pattern("GGG")
                    .pattern("RPR")
                    .input('G', Items.GOLD_INGOT)
                    .input('R', VoltcraftItems.RUBBER_SHEET)
                    .input('P', Items.GLASS_PANE)
                    .criterion(hasItem(Items.GOLD_INGOT), conditionsFromItem(Items.GOLD_INGOT))
                    .offerTo(exporter);

                // Steel Security Fence Wire
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.CABLE_STEEL_FENCE, 12)
                    .pattern("NIN")
                    .pattern("IZI")
                    .pattern("NIN")
                    .input('N', Items.IRON_NUGGET)
                    .input('I', Items.IRON_INGOT)
                    .input('Z', VoltcraftItems.ZINC_INGOT)
                    .criterion(hasItem(VoltcraftItems.ZINC_INGOT), conditionsFromItem(VoltcraftItems.ZINC_INGOT))
                    .offerTo(exporter);

                // Nichrome Heating Wire
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.CABLE_NICHROME_HEATING, 8)
                    .pattern("CNC")
                    .pattern("NTN")
                    .pattern("CNC")
                    .input('C', Items.CLAY_BALL)
                    .input('N', VoltcraftItems.NICHROME_INGOT)
                    .input('T', Items.TERRACOTTA)
                    .criterion(hasItem(VoltcraftItems.NICHROME_INGOT), conditionsFromItem(VoltcraftItems.NICHROME_INGOT))
                    .offerTo(exporter);

                // Superconductor Cryogenic Conduit
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.CONDUIT_SUPERCONDUCTOR, 4)
                    .pattern("AGA")
                    .pattern("CSC")
                    .pattern("AWA")
                    .input('A', VoltcraftItems.ALUMINUM_INGOT)
                    .input('G', Items.GLASS_PANE)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .input('S', Items.NETHER_STAR)
                    .input('W', Items.WATER_BUCKET)
                    .criterion(hasItem(Items.NETHER_STAR), conditionsFromItem(Items.NETHER_STAR))
                    .offerTo(exporter);

                // ==================== 6. SWITCHGEAR & SAFETY HARDWARE ====================\
                // Copper Busbar
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.COPPER_BUSBAR, 4)
                    .pattern("CCC")
                    .pattern("CCC")
                    .pattern("SSS")
                    .input('C', Items.COPPER_INGOT)
                    .input('S', Items.SMOOTH_STONE)
                    .criterion(hasItem(Items.COPPER_INGOT), conditionsFromItem(Items.COPPER_INGOT))
                    .offerTo(exporter);

                // Junction Box
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.JUNCTION_BOX, 2)
                    .pattern("ICI")
                    .pattern("CTC")
                    .pattern("ICI")
                    .input('I', Items.IRON_INGOT)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .input('T', Items.TERRACOTTA)
                    .criterion(hasItem(VoltcraftBlocks.CABLE_COPPER_BARE), conditionsFromItem(VoltcraftBlocks.CABLE_COPPER_BARE))
                    .offerTo(exporter);

                // Manual Knife Switch
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.KNIFE_SWITCH, 1)
                    .pattern(" CL")
                    .pattern("CCS")
                    .pattern("TTT")
                    .input('C', Items.COPPER_INGOT)
                    .input('L', Items.LEVER)
                    .input('S', Items.SMOOTH_STONE)
                    .input('T', Items.TERRACOTTA)
                    .criterion(hasItem(Items.COPPER_INGOT), conditionsFromItem(Items.COPPER_INGOT))
                    .offerTo(exporter);

                // Cartridge Fuse Box
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.FUSE_BOX, 1)
                    .pattern("TGT")
                    .pattern("CFC")
                    .pattern("TRT")
                    .input('T', Items.TERRACOTTA)
                    .input('G', Items.GLASS_PANE)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .input('F', VoltcraftItems.FUSE_ALLOY_INGOT)
                    .input('R', Items.REDSTONE)
                    .criterion(hasItem(VoltcraftItems.FUSE_ALLOY_INGOT), conditionsFromItem(VoltcraftItems.FUSE_ALLOY_INGOT))
                    .offerTo(exporter);

                // Resettable Circuit Breaker
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.CIRCUIT_BREAKER, 1)
                    .pattern("ILI")
                    .pattern("WNW")
                    .pattern("TRT")
                    .input('I', Items.IRON_INGOT)
                    .input('L', Items.LEVER)
                    .input('W', VoltcraftItems.COPPER_MAGNET_WIRE)
                    .input('N', VoltcraftItems.MOSFET_POWER_TRANSISTOR)
                    .input('T', Items.TERRACOTTA)
                    .input('R', Items.REDSTONE)
                    .criterion(hasItem(VoltcraftItems.MOSFET_POWER_TRANSISTOR), conditionsFromItem(VoltcraftItems.MOSFET_POWER_TRANSISTOR))
                    .offerTo(exporter);

                // Contactor Relay
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.CONTACTOR_RELAY, 1)
                    .pattern("ICI")
                    .pattern("WNW")
                    .pattern("TRT")
                    .input('I', Items.IRON_INGOT)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .input('W', VoltcraftItems.COPPER_MAGNET_WIRE)
                    .input('N', VoltcraftItems.TRANSFORMER_CORE_LAMINATED)
                    .input('T', Items.TERRACOTTA)
                    .input('R', Items.REPEATER)
                    .criterion(hasItem(VoltcraftItems.TRANSFORMER_CORE_LAMINATED), conditionsFromItem(VoltcraftItems.TRANSFORMER_CORE_LAMINATED))
                    .offerTo(exporter);

                // ==================== 7. PORTABLE BATTERY CELLS ====================\
                // 18650 Li-Ion Cell (2x)
                createShaped(RecipeCategory.MISC, VoltcraftItems.BATTERY_18650_LI_ION, 2)
                    .pattern("ACA")
                    .pattern("LSK")
                    .pattern("AIA")
                    .input('A', VoltcraftItems.ALUMINUM_INGOT)
                    .input('C', Items.COPPER_INGOT)
                    .input('L', VoltcraftItems.LITHIUM_INGOT)
                    .input('S', VoltcraftItems.PURE_SILICA_DUST)
                    .input('K', Items.COAL)
                    .input('I', Items.IRON_INGOT)
                    .criterion(hasItem(VoltcraftItems.LITHIUM_INGOT), conditionsFromItem(VoltcraftItems.LITHIUM_INGOT))
                    .offerTo(exporter);

                // 21700 High-Discharge Li-Ion Cell (2x)
                createShaped(RecipeCategory.MISC, VoltcraftItems.BATTERY_21700_HIGH_DRAIN, 2)
                    .pattern("NCN")
                    .pattern("LSK")
                    .pattern("AIA")
                    .input('N', VoltcraftItems.NICKEL_INGOT)
                    .input('C', Items.COPPER_INGOT)
                    .input('L', VoltcraftItems.LITHIUM_INGOT)
                    .input('S', VoltcraftItems.PURE_SILICA_DUST)
                    .input('K', Items.COAL)
                    .input('A', VoltcraftItems.ALUMINUM_INGOT)
                    .input('I', Items.IRON_INGOT)
                    .criterion(hasItem(VoltcraftItems.LITHIUM_INGOT), conditionsFromItem(VoltcraftItems.LITHIUM_INGOT))
                    .offerTo(exporter);

                // Alkaline Cell (4x)
                createShaped(RecipeCategory.MISC, VoltcraftItems.BATTERY_CELL_ALKALINE, 4)
                    .pattern("NZN")
                    .pattern("CRC")
                    .pattern("NIN")
                    .input('N', Items.IRON_NUGGET)
                    .input('Z', VoltcraftItems.ZINC_INGOT)
                    .input('C', Items.CHARCOAL)
                    .input('R', Items.REDSTONE)
                    .input('I', Items.IRON_INGOT)
                    .criterion(hasItem(VoltcraftItems.ZINC_INGOT), conditionsFromItem(VoltcraftItems.ZINC_INGOT))
                    .offerTo(exporter);

                // Zinc-Carbon Cell (4x)
                createShaped(RecipeCategory.MISC, VoltcraftItems.BATTERY_CELL_ZINC_CARBON, 4)
                    .pattern("PZP")
                    .pattern("KYK")
                    .pattern("PUP")
                    .input('P', Items.PAPER)
                    .input('Z', VoltcraftItems.ZINC_INGOT)
                    .input('K', Items.CHARCOAL)
                    .input('Y', Items.CLAY_BALL)
                    .input('U', Items.COPPER_INGOT)
                    .criterion(hasItem(VoltcraftItems.ZINC_INGOT), conditionsFromItem(VoltcraftItems.ZINC_INGOT))
                    .offerTo(exporter);

                // CR2032 Lithium Coin Cell (4x)
                createShaped(RecipeCategory.MISC, VoltcraftItems.BATTERY_COIN_CR2032, 4)
                    .pattern("NLN")
                    .pattern("CGC")
                    .input('N', Items.IRON_NUGGET)
                    .input('L', VoltcraftItems.LITHIUM_INGOT)
                    .input('C', Items.COPPER_INGOT)
                    .input('G', Items.GLASS_PANE)
                    .criterion(hasItem(VoltcraftItems.LITHIUM_INGOT), conditionsFromItem(VoltcraftItems.LITHIUM_INGOT))
                    .offerTo(exporter);

                // Li-SOCl2 Industrial Backup Cell (2x)
                createShaped(RecipeCategory.MISC, VoltcraftItems.BATTERY_CELL_LISOCL2, 2)
                    .pattern("ILI")
                    .pattern("GBG")
                    .pattern("INI")
                    .input('I', Items.IRON_INGOT)
                    .input('L', VoltcraftItems.LITHIUM_INGOT)
                    .input('G', Items.GLASS_PANE)
                    .input('B', Items.BLAZE_POWDER)
                    .input('N', Items.GOLD_NUGGET)
                    .criterion(hasItem(VoltcraftItems.LITHIUM_INGOT), conditionsFromItem(VoltcraftItems.LITHIUM_INGOT))
                    .offerTo(exporter);

                // NiCd Rechargeable Cell (2x)
                createShaped(RecipeCategory.MISC, VoltcraftItems.BATTERY_CELL_NICD, 2)
                    .pattern("NKN")
                    .pattern("YRY")
                    .pattern("NDN")
                    .input('N', Items.IRON_NUGGET)
                    .input('K', VoltcraftItems.NICKEL_INGOT)
                    .input('Y', Items.CLAY_BALL)
                    .input('R', Items.REDSTONE)
                    .input('D', VoltcraftItems.LEAD_INGOT)
                    .criterion(hasItem(VoltcraftItems.NICKEL_INGOT), conditionsFromItem(VoltcraftItems.NICKEL_INGOT))
                    .offerTo(exporter);

                // NiMH Rechargeable Cell (2x)
                createShaped(RecipeCategory.MISC, VoltcraftItems.BATTERY_CELL_NIMH, 2)
                    .pattern("NKN")
                    .pattern("ASA")
                    .pattern("NZN")
                    .input('N', Items.IRON_NUGGET)
                    .input('K', VoltcraftItems.NICKEL_INGOT)
                    .input('A', VoltcraftItems.ALUMINUM_INGOT)
                    .input('S', VoltcraftItems.PURE_SILICA_DUST)
                    .input('Z', VoltcraftItems.ZINC_INGOT)
                    .criterion(hasItem(VoltcraftItems.NICKEL_INGOT), conditionsFromItem(VoltcraftItems.NICKEL_INGOT))
                    .offerTo(exporter);

                // ==================== 8. STATIONARY BESS STORAGE BLOCKS ====================\
                // LiFePO4 Battery Block
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.BATTERY_BLOCK_LIFEPO4, 1)
                    .pattern("IBI")
                    .pattern("LML")
                    .pattern("SRS")
                    .input('I', Items.IRON_INGOT)
                    .input('B', VoltcraftBlocks.COPPER_BUSBAR)
                    .input('L', VoltcraftItems.LITHIUM_INGOT)
                    .input('M', VoltcraftItems.BMS_LOGIC_BOARD)
                    .input('S', Items.IRON_BLOCK)
                    .input('R', VoltcraftItems.RUBBER_SHEET)
                    .criterion(hasItem(VoltcraftItems.BMS_LOGIC_BOARD), conditionsFromItem(VoltcraftItems.BMS_LOGIC_BOARD))
                    .offerTo(exporter);

                // Lead-Acid Battery Bank
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.BATTERY_BLOCK_LEAD_ACID, 1)
                    .pattern("LCL")
                    .pattern("LWL")
                    .pattern("TTT")
                    .input('L', VoltcraftItems.LEAD_INGOT)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .input('W', Items.WATER_BUCKET)
                    .input('T', Items.TERRACOTTA)
                    .criterion(hasItem(VoltcraftItems.LEAD_INGOT), conditionsFromItem(VoltcraftItems.LEAD_INGOT))
                    .offerTo(exporter);

                // LTO Battery Block
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.BATTERY_BLOCK_LTO, 1)
                    .pattern("IGI")
                    .pattern("LML")
                    .pattern("QSQ")
                    .input('I', Items.IRON_INGOT)
                    .input('G', VoltcraftBlocks.CABLE_GOLD_BUS)
                    .input('L', VoltcraftItems.LITHIUM_INGOT)
                    .input('M', VoltcraftItems.BMS_LOGIC_BOARD)
                    .input('Q', Items.QUARTZ)
                    .input('S', VoltcraftItems.PURE_SILICA_DUST)
                    .criterion(hasItem(VoltcraftItems.BMS_LOGIC_BOARD), conditionsFromItem(VoltcraftItems.BMS_LOGIC_BOARD))
                    .offerTo(exporter);

                // Modular 18650 Battery Rack
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.BATTERY_RACK_MODULAR, 1)
                    .pattern("IBI")
                    .pattern("CTC")
                    .pattern("IMI")
                    .input('I', Items.IRON_INGOT)
                    .input('B', VoltcraftBlocks.COPPER_BUSBAR)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_INSULATED)
                    .input('T', Items.CHEST)
                    .input('M', VoltcraftItems.BMS_LOGIC_BOARD)
                    .criterion(hasItem(VoltcraftItems.BMS_LOGIC_BOARD), conditionsFromItem(VoltcraftItems.BMS_LOGIC_BOARD))
                    .offerTo(exporter);

                // NiMH Battery Pack Block
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.BATTERY_BLOCK_NIMH, 1)
                    .pattern("IBI")
                    .pattern("NMN")
                    .pattern("ARA")
                    .input('I', Items.IRON_INGOT)
                    .input('B', VoltcraftBlocks.COPPER_BUSBAR)
                    .input('N', VoltcraftItems.BATTERY_CELL_NIMH)
                    .input('M', VoltcraftItems.BMS_LOGIC_BOARD)
                    .input('A', VoltcraftItems.ALUMINUM_INGOT)
                    .input('R', VoltcraftItems.RUBBER_SHEET)
                    .criterion(hasItem(VoltcraftItems.BATTERY_CELL_NIMH), conditionsFromItem(VoltcraftItems.BATTERY_CELL_NIMH))
                    .offerTo(exporter);

                // NiCd Battery Pack Block
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.BATTERY_BLOCK_NICD, 1)
                    .pattern("IBI")
                    .pattern("DMD")
                    .pattern("LRL")
                    .input('I', Items.IRON_INGOT)
                    .input('B', VoltcraftBlocks.COPPER_BUSBAR)
                    .input('D', VoltcraftItems.BATTERY_CELL_NICD)
                    .input('M', VoltcraftItems.BMS_LOGIC_BOARD)
                    .input('L', VoltcraftItems.LEAD_INGOT)
                    .input('R', VoltcraftItems.RUBBER_SHEET)
                    .criterion(hasItem(VoltcraftItems.BATTERY_CELL_NICD), conditionsFromItem(VoltcraftItems.BATTERY_CELL_NICD))
                    .offerTo(exporter);

                // ==================== 9. POWER CONVERSION & INVERSION ====================\
                // 1. Buck Step-Down Converter
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.CONVERTER_DC_BUCK, 1)
                    .pattern("ADA")
                    .pattern("MWE")
                    .pattern("ICI")
                    .input('A', VoltcraftItems.ALUMINUM_INGOT)
                    .input('D', VoltcraftItems.SCHOTTKY_DIODE)
                    .input('M', VoltcraftItems.MOSFET_POWER_TRANSISTOR)
                    .input('W', VoltcraftItems.COPPER_MAGNET_WIRE)
                    .input('E', VoltcraftItems.FILTER_CAPACITOR_ELECTROLYTIC)
                    .input('I', Items.IRON_INGOT)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .criterion(hasItem(VoltcraftItems.MOSFET_POWER_TRANSISTOR), conditionsFromItem(VoltcraftItems.MOSFET_POWER_TRANSISTOR))
                    .offerTo(exporter);

                // 2. Boost Step-Up Converter
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.CONVERTER_DC_BOOST, 1)
                    .pattern("AWA")
                    .pattern("MDE")
                    .pattern("ICI")
                    .input('A', VoltcraftItems.ALUMINUM_INGOT)
                    .input('W', VoltcraftItems.COPPER_MAGNET_WIRE)
                    .input('M', VoltcraftItems.MOSFET_POWER_TRANSISTOR)
                    .input('D', VoltcraftItems.SCHOTTKY_DIODE)
                    .input('E', VoltcraftItems.FILTER_CAPACITOR_ELECTROLYTIC)
                    .input('I', Items.IRON_INGOT)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .criterion(hasItem(VoltcraftItems.MOSFET_POWER_TRANSISTOR), conditionsFromItem(VoltcraftItems.MOSFET_POWER_TRANSISTOR))
                    .offerTo(exporter);

                // 3. Buck-Boost SEPIC Converter
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.CONVERTER_DC_BUCK_BOOST, 1)
                    .pattern("AWE")
                    .pattern("MBD")
                    .pattern("IWI")
                    .input('A', VoltcraftItems.ALUMINUM_INGOT)
                    .input('W', VoltcraftItems.COPPER_MAGNET_WIRE)
                    .input('E', VoltcraftItems.FILTER_CAPACITOR_ELECTROLYTIC)
                    .input('M', VoltcraftItems.MOSFET_POWER_TRANSISTOR)
                    .input('B', VoltcraftItems.BMS_LOGIC_BOARD)
                    .input('D', VoltcraftItems.SCHOTTKY_DIODE)
                    .input('I', Items.IRON_INGOT)
                    .criterion(hasItem(VoltcraftItems.BMS_LOGIC_BOARD), conditionsFromItem(VoltcraftItems.BMS_LOGIC_BOARD))
                    .offerTo(exporter);

                // 4. Linear LDO Voltage Regulator
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.REGULATOR_LINEAR_LDO, 1)
                    .pattern("AAA")
                    .pattern("RMR")
                    .pattern("CEC")
                    .input('A', VoltcraftItems.ALUMINUM_INGOT)
                    .input('R', VoltcraftItems.RUBBER_SHEET)
                    .input('M', VoltcraftItems.MOSFET_POWER_TRANSISTOR)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .input('E', VoltcraftItems.FILTER_CAPACITOR_ELECTROLYTIC)
                    .criterion(hasItem(VoltcraftItems.MOSFET_POWER_TRANSISTOR), conditionsFromItem(VoltcraftItems.MOSFET_POWER_TRANSISTOR))
                    .offerTo(exporter);

                // 5. AC Step-Down Transformer
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.TRANSFORMER_AC_STEP_DOWN, 1)
                    .pattern("ILI")
                    .pattern("WLC")
                    .pattern("TCT")
                    .input('I', Items.IRON_INGOT)
                    .input('L', VoltcraftItems.TRANSFORMER_CORE_LAMINATED)
                    .input('W', VoltcraftItems.COPPER_MAGNET_WIRE)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .input('T', Items.TERRACOTTA)
                    .criterion(hasItem(VoltcraftItems.TRANSFORMER_CORE_LAMINATED), conditionsFromItem(VoltcraftItems.TRANSFORMER_CORE_LAMINATED))
                    .offerTo(exporter);

                // 6. AC Step-Up Transformer
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.TRANSFORMER_AC_STEP_UP, 1)
                    .pattern("ILI")
                    .pattern("CLW")
                    .pattern("TCT")
                    .input('I', Items.IRON_INGOT)
                    .input('L', VoltcraftItems.TRANSFORMER_CORE_LAMINATED)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .input('W', VoltcraftItems.COPPER_MAGNET_WIRE)
                    .input('T', Items.TERRACOTTA)
                    .criterion(hasItem(VoltcraftItems.TRANSFORMER_CORE_LAMINATED), conditionsFromItem(VoltcraftItems.TRANSFORMER_CORE_LAMINATED))
                    .offerTo(exporter);

                // 7. Full-Wave Bridge Rectifier
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.RECTIFIER_BRIDGE, 1)
                    .pattern("RDR")
                    .pattern("DED")
                    .pattern("CDC")
                    .input('R', VoltcraftItems.RUBBER_SHEET)
                    .input('D', VoltcraftItems.SCHOTTKY_DIODE)
                    .input('E', VoltcraftItems.FILTER_CAPACITOR_ELECTROLYTIC)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .criterion(hasItem(VoltcraftItems.SCHOTTKY_DIODE), conditionsFromItem(VoltcraftItems.SCHOTTKY_DIODE))
                    .offerTo(exporter);

                // 8. Active Synchronous Rectifier
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.RECTIFIER_ACTIVE_SYNCHRONOUS, 1)
                    .pattern("AMA")
                    .pattern("MBM")
                    .pattern("CEC")
                    .input('A', VoltcraftItems.ALUMINUM_INGOT)
                    .input('M', VoltcraftItems.MOSFET_POWER_TRANSISTOR)
                    .input('B', VoltcraftItems.BMS_LOGIC_BOARD)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .input('E', VoltcraftItems.FILTER_CAPACITOR_ELECTROLYTIC)
                    .criterion(hasItem(VoltcraftItems.MOSFET_POWER_TRANSISTOR), conditionsFromItem(VoltcraftItems.MOSFET_POWER_TRANSISTOR))
                    .offerTo(exporter);

                // 9. Square Wave Inverter (1500W)
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.INVERTER_SQUARE_WAVE, 1)
                    .pattern("IMI")
                    .pattern("LWE")
                    .pattern("CRN")
                    .input('I', Items.IRON_INGOT)
                    .input('M', VoltcraftItems.MOSFET_POWER_TRANSISTOR)
                    .input('L', VoltcraftItems.TRANSFORMER_CORE_LAMINATED)
                    .input('W', VoltcraftItems.COPPER_MAGNET_WIRE)
                    .input('E', VoltcraftItems.FILTER_CAPACITOR_ELECTROLYTIC)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .input('R', Items.REPEATER)
                    .input('N', VoltcraftBlocks.CABLE_COPPER_INSULATED)
                    .criterion(hasItem(VoltcraftItems.MOSFET_POWER_TRANSISTOR), conditionsFromItem(VoltcraftItems.MOSFET_POWER_TRANSISTOR))
                    .offerTo(exporter);

                // 10. Modified Sine Wave Inverter (3000W)
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.INVERTER_MODIFIED_SINE, 1)
                    .pattern("AMA")
                    .pattern("MLE")
                    .pattern("CBN")
                    .input('A', VoltcraftItems.ALUMINUM_INGOT)
                    .input('M', VoltcraftItems.MOSFET_POWER_TRANSISTOR)
                    .input('L', VoltcraftItems.TRANSFORMER_CORE_LAMINATED)
                    .input('E', VoltcraftItems.FILTER_CAPACITOR_ELECTROLYTIC)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .input('B', VoltcraftItems.BMS_LOGIC_BOARD)
                    .input('N', VoltcraftBlocks.CABLE_COPPER_INSULATED)
                    .criterion(hasItem(VoltcraftItems.BMS_LOGIC_BOARD), conditionsFromItem(VoltcraftItems.BMS_LOGIC_BOARD))
                    .offerTo(exporter);

                // 11. Pure Sine Wave SPWM Inverter (5000W)
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.INVERTER_PURE_SINE, 1)
                    .pattern("AMA")
                    .pattern("BLW")
                    .pattern("HEN")
                    .input('A', VoltcraftItems.ALUMINUM_INGOT)
                    .input('M', VoltcraftItems.MOSFET_POWER_TRANSISTOR)
                    .input('B', VoltcraftItems.BMS_LOGIC_BOARD)
                    .input('L', VoltcraftItems.TRANSFORMER_CORE_LAMINATED)
                    .input('W', VoltcraftItems.COPPER_MAGNET_WIRE)
                    .input('H', VoltcraftBlocks.CABLE_COPPER_HEAVY)
                    .input('E', VoltcraftItems.FILTER_CAPACITOR_ELECTROLYTIC)
                    .input('N', VoltcraftBlocks.CABLE_COPPER_INSULATED)
                    .criterion(hasItem(VoltcraftItems.BMS_LOGIC_BOARD), conditionsFromItem(VoltcraftItems.BMS_LOGIC_BOARD))
                    .offerTo(exporter);

                // 12. Synchronous Grid-Tie Inverter (6000W)
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.INVERTER_GRID_TIE, 1)
                    .pattern("SMS")
                    .pattern("BLR")
                    .pattern("HEN")
                    .input('S', VoltcraftItems.SILVER_INGOT)
                    .input('M', VoltcraftItems.MOSFET_POWER_TRANSISTOR)
                    .input('B', VoltcraftItems.BMS_LOGIC_BOARD)
                    .input('L', VoltcraftItems.TRANSFORMER_CORE_LAMINATED)
                    .input('R', VoltcraftBlocks.CONTACTOR_RELAY)
                    .input('H', VoltcraftBlocks.CABLE_COPPER_HEAVY)
                    .input('E', VoltcraftItems.FILTER_CAPACITOR_ELECTROLYTIC)
                    .input('N', VoltcraftBlocks.CABLE_COPPER_INSULATED)
                    .criterion(hasItem(VoltcraftBlocks.CONTACTOR_RELAY), conditionsFromItem(VoltcraftBlocks.CONTACTOR_RELAY))
                    .offerTo(exporter);

                // 13. Hybrid Multi-Mode Inverter ESS (8000W)
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.INVERTER_HYBRID_ESS, 1)
                    .pattern("GMG")
                    .pattern("BRB")
                    .pattern("HLN")
                    .input('G', Items.GOLD_INGOT)
                    .input('M', VoltcraftItems.MOSFET_POWER_TRANSISTOR)
                    .input('B', VoltcraftItems.BMS_LOGIC_BOARD)
                    .input('R', VoltcraftBlocks.CONTACTOR_RELAY)
                    .input('H', VoltcraftBlocks.CABLE_COPPER_HEAVY)
                    .input('L', VoltcraftItems.TRANSFORMER_CORE_LAMINATED)
                    .input('N', VoltcraftBlocks.CABLE_COPPER_INSULATED)
                    .criterion(hasItem(VoltcraftBlocks.CONTACTOR_RELAY), conditionsFromItem(VoltcraftBlocks.CONTACTOR_RELAY))
                    .offerTo(exporter);

                // 14. Rotary Energy Bridge (230V AC to E)
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.CONVERTER_EU, 1)
                    .pattern("GMG")
                    .pattern("TLT")
                    .pattern("IRI")
                    .input('G', VoltcraftBlocks.CABLE_GOLD_BUS)
                    .input('M', VoltcraftItems.MOSFET_POWER_TRANSISTOR)
                    .input('T', VoltcraftItems.TRANSFORMER_CORE_LAMINATED)
                    .input('L', VoltcraftBlocks.CABLE_COPPER_HEAVY)
                    .input('I', Items.IRON_INGOT)
                    .input('R', Items.REDSTONE)
                    .criterion(hasItem(VoltcraftItems.TRANSFORMER_CORE_LAMINATED), conditionsFromItem(VoltcraftItems.TRANSFORMER_CORE_LAMINATED))
                    .offerTo(exporter);

                // ==================== 10. POWER GENERATION & RENEWABLES ====================\
                // 1. Monocrystalline PERC Solar Panel (400W)
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.SOLAR_PANEL_MONOCRYSTALLINE, 1)
                    .pattern("GGG")
                    .pattern("PPP")
                    .pattern("ACA")
                    .input('G', Items.GLASS_PANE)
                    .input('P', VoltcraftItems.PHOTOVOLTAIC_CELL)
                    .input('A', VoltcraftItems.ALUMINUM_INGOT)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .criterion(hasItem(VoltcraftItems.PHOTOVOLTAIC_CELL), conditionsFromItem(VoltcraftItems.PHOTOVOLTAIC_CELL))
                    .offerTo(exporter);

                // 2. Polycrystalline Solar Panel (300W)
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.SOLAR_PANEL_POLYCRYSTALLINE, 1)
                    .pattern("GGG")
                    .pattern("PPP")
                    .pattern("ICI")
                    .input('G', Items.GLASS_PANE)
                    .input('P', VoltcraftItems.PHOTOVOLTAIC_CELL)
                    .input('I', Items.IRON_INGOT)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .criterion(hasItem(VoltcraftItems.PHOTOVOLTAIC_CELL), conditionsFromItem(VoltcraftItems.PHOTOVOLTAIC_CELL))
                    .offerTo(exporter);

                // 3. Thin-Film CdTe Solar Panel (250W)
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.SOLAR_PANEL_THIN_FILM, 1)
                    .pattern("GGG")
                    .pattern("SLS")
                    .pattern("RCR")
                    .input('G', Items.GLASS_PANE)
                    .input('S', VoltcraftItems.PURE_SILICA_DUST)
                    .input('L', VoltcraftItems.LEAD_INGOT)
                    .input('R', VoltcraftItems.RUBBER_SHEET)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .criterion(hasItem(VoltcraftItems.PURE_SILICA_DUST), conditionsFromItem(VoltcraftItems.PURE_SILICA_DUST))
                    .offerTo(exporter);

                // 4. Concentrated Photovoltaic Panel (700W CPV)
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.SOLAR_PANEL_CONCENTRATOR, 1)
                    .pattern("QGQ")
                    .pattern("PPP")
                    .pattern("ACA")
                    .input('Q', Items.QUARTZ)
                    .input('G', Items.GLOWSTONE_DUST)
                    .input('P', VoltcraftItems.PHOTOVOLTAIC_CELL)
                    .input('A', VoltcraftItems.ALUMINUM_INGOT)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_HEAVY)
                    .criterion(hasItem(VoltcraftItems.PHOTOVOLTAIC_CELL), conditionsFromItem(VoltcraftItems.PHOTOVOLTAIC_CELL))
                    .offerTo(exporter);

                // 5. MPPT Solar Charge Controller (60A)
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.CHARGE_CONTROLLER_MPPT, 1)
                    .pattern("AMA")
                    .pattern("WBD")
                    .pattern("ICI")
                    .input('A', VoltcraftItems.ALUMINUM_INGOT)
                    .input('M', VoltcraftItems.MOSFET_POWER_TRANSISTOR)
                    .input('W', VoltcraftItems.COPPER_MAGNET_WIRE)
                    .input('B', VoltcraftItems.BMS_LOGIC_BOARD)
                    .input('D', VoltcraftItems.SCHOTTKY_DIODE)
                    .input('I', Items.IRON_INGOT)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .criterion(hasItem(VoltcraftItems.BMS_LOGIC_BOARD), conditionsFromItem(VoltcraftItems.BMS_LOGIC_BOARD))
                    .offerTo(exporter);

                // 6. Hand-Crank DC Dynamo (100W)
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.GENERATOR_HAND_CRANK, 1)
                    .pattern(" L ")
                    .pattern("IWI")
                    .pattern("ICI")
                    .input('L', Items.LEVER)
                    .input('I', Items.IRON_INGOT)
                    .input('W', VoltcraftItems.COPPER_MAGNET_WIRE)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_BARE)
                    .criterion(hasItem(VoltcraftItems.COPPER_MAGNET_WIRE), conditionsFromItem(VoltcraftItems.COPPER_MAGNET_WIRE))
                    .offerTo(exporter);

                // 7. Portable Inverter Generator (1.8kW 230V AC)
                createShaped(RecipeCategory.BUILDING_BLOCKS, VoltcraftBlocks.GENERATOR_PORTABLE_INVERTER, 1)
                    .pattern("IFI")
                    .pattern("IMI")
                    .pattern("ICI")
                    .input('I', Items.IRON_INGOT)
                    .input('F', Items.FURNACE)
                    .input('M', VoltcraftBlocks.INVERTER_PURE_SINE)
                    .input('C', VoltcraftBlocks.CABLE_COPPER_INSULATED)
                    .criterion(hasItem(VoltcraftBlocks.INVERTER_PURE_SINE), conditionsFromItem(VoltcraftBlocks.INVERTER_PURE_SINE))
                    .offerTo(exporter);
            }
        };
    }
}
