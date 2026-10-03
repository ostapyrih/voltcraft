package com.ostapyrih.voltcraft.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class RecipeValidationTest {

    private static final Path RECIPE_DIR = Paths.get("src/main/generated/data/voltcraft/recipe");

    @Test
    void testAllGeneratedRecipeFilesAreValidJson() {
        File dir = RECIPE_DIR.toFile();
        assertTrue(dir.exists() && dir.isDirectory(), "Recipe directory must exist: " + RECIPE_DIR);

        File[] recipeFiles = dir.listFiles((d, name) -> name.endsWith(".json"));
        assertNotNull(recipeFiles, "Recipe files array should not be null");
        assertTrue(recipeFiles.length >= 80, "Expected at least 80 recipes, found: " + recipeFiles.length);

        Set<String> recipeTypes = new HashSet<>();

        for (File file : recipeFiles) {
            try (FileReader reader = new FileReader(file)) {
                JsonElement json = JsonParser.parseReader(reader);
                assertTrue(json.isJsonObject(), "Recipe must be a JSON object: " + file.getName());
                JsonObject obj = json.getAsJsonObject();

                assertTrue(obj.has("type"), "Recipe must have a 'type': " + file.getName());
                String type = obj.get("type").getAsString();
                recipeTypes.add(type);

                if (type.equals("minecraft:smelting") || type.equals("minecraft:blasting")) {
                    assertTrue(obj.has("ingredient"), "Smelting recipe must have an ingredient: " + file.getName());
                    assertTrue(obj.has("result"), "Smelting recipe must have a result: " + file.getName());
                    assertTrue(obj.has("cookingtime"), "Smelting recipe must have a cookingtime: " + file.getName());
                } else if (type.equals("minecraft:crafting_shaped")) {
                    assertTrue(obj.has("pattern"), "Shaped recipe must have pattern: " + file.getName());
                    assertTrue(obj.has("key"), "Shaped recipe must have key: " + file.getName());
                    assertTrue(obj.has("result"), "Shaped recipe must have result: " + file.getName());
                } else if (type.equals("minecraft:crafting_shapeless")) {
                    assertTrue(obj.has("ingredients"), "Shapeless recipe must have ingredients: " + file.getName());
                    assertTrue(obj.has("result"), "Shapeless recipe must have result: " + file.getName());
                }
            } catch (Exception e) {
                fail("Failed to parse JSON recipe in file: " + file.getName() + " due to: " + e.getMessage());
            }
        }

        assertTrue(recipeTypes.contains("minecraft:smelting"), "Must contain smelting recipes");
        assertTrue(recipeTypes.contains("minecraft:blasting"), "Must contain blasting recipes");
        assertTrue(recipeTypes.contains("minecraft:crafting_shaped"), "Must contain shaped recipes");
        assertTrue(recipeTypes.contains("minecraft:crafting_shapeless"), "Must contain shapeless recipes");
    }

    @Test
    void testSpecificEssentialRecipesExist() {
        String[] essentialFiles = {
            // Smelting
            "aluminum_ingot_from_smelting_raw_bauxite.json",
            "aluminum_ingot_from_smelting_ore_bauxite.json",
            "aluminum_ingot_from_smelting_deepslate_ore_bauxite.json",
            "lead_ingot_from_smelting_raw_galena.json",
            "lead_ingot_from_smelting_ore_galena.json",
            "zinc_ingot_from_smelting_raw_sphalerite.json",
            "zinc_ingot_from_smelting_ore_sphalerite.json",
            "lithium_ingot_from_smelting_raw_spodumene.json",
            "lithium_ingot_from_smelting_ore_spodumene.json",
            "nickel_ingot_from_smelting_raw_pentlandite.json",
            "nickel_ingot_from_smelting_ore_pentlandite.json",
            "pure_silica_dust_from_smelting_ore_high_purity_quartz.json",
            // Blasting
            "aluminum_ingot_from_blasting_raw_bauxite.json",
            "lead_ingot_from_blasting_raw_galena.json",
            "zinc_ingot_from_blasting_raw_sphalerite.json",
            "lithium_ingot_from_blasting_raw_spodumene.json",
            "nickel_ingot_from_blasting_raw_pentlandite.json",
            "pure_silica_dust_from_blasting_ore_high_purity_quartz.json",
            // Alloys & Materials
            "fuse_alloy_ingot.json",
            "nichrome_ingot.json",
            "silver_ingot.json",
            "silver_nugget.json",
            "rubber_sheet_from_smelting_slime_ball.json",
            // Cables
            "cable_copper_bare.json",
            "cable_copper_insulated.json",
            "cable_copper_heavy.json",
            "cable_aluminum_transmission.json",
            "cable_silver_precision.json",
            "cable_gold_bus.json",
            "cable_steel_fence.json",
            "cable_nichrome_heating.json",
            "conduit_superconductor.json",
            // Switchgear
            "copper_busbar.json",
            "junction_box.json",
            "knife_switch.json",
            "fuse_box.json",
            "circuit_breaker.json",
            "contactor_relay.json",
            // Electronics
            "silicon_boule.json",
            "silicon_wafer.json",
            "doped_wafer_p.json",
            "doped_wafer_n.json",
            "photovoltaic_cell.json",
            "mosfet_power_transistor.json",
            "schottky_diode.json",
            "filter_capacitor_electrolytic.json",
            "copper_magnet_wire.json",
            "transformer_core_laminated.json",
            "bms_logic_board.json",
            // Portable battery cells
            "battery_18650_li_ion.json",
            "battery_21700_high_drain.json",
            "battery_cell_alkaline.json",
            "battery_cell_zinc_carbon.json",
            "battery_coin_cr2032.json",
            "battery_cell_lisocl2.json",
            "battery_cell_nicd.json",
            "battery_cell_nimh.json",
            // Stationary energy storage blocks (BESS)
            "battery_block_lifepo4.json",
            "battery_block_lead_acid.json",
            "battery_block_lto.json",
            "battery_rack_modular.json",
            "battery_block_nimh.json",
            "battery_block_nicd.json",
            "converter_eu.json"
        };

        for (String file : essentialFiles) {
            File f = RECIPE_DIR.resolve(file).toFile();
            assertTrue(f.exists(), "Essential recipe missing: " + file);
        }
    }
}
