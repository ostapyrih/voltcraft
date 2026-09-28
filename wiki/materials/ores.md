# Natural Ores & Raw Metallurgy

Part of the [[../core-idea|VoltCraft Core Idea & Architecture]] specification.

---

## 1. Geological Distribution & World Generation

> **Code sources:** `world/VoltcraftConfiguredFeatures.java` (vein sizes),
> `world/VoltcraftPlacedFeatures.java` (Y-ranges, counts), `world/VoltcraftBiomeModifications.java`.
> Placement uses `BiomePlacementModifier.of()` with **no biome filter** — all ores generate in every
> biome. Counts are veins per chunk.

| Ore Identifier | Deepslate Variant | Primary Mineral | Y-Range (code) | Vein size (code) | Veins/chunk | Mining Level | Smelting Product |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `voltcraft:ore_bauxite` | `voltcraft:deepslate_ore_bauxite` | Hydrous Aluminum Oxides | $Y = 32 \dots 128$ (trapezoid) | $8$ blocks | 6 | Stone Pickaxe | `voltcraft:aluminum_ingot` |
| `voltcraft:ore_galena` | `voltcraft:deepslate_ore_galena` | Lead-Silver Sulfide ($PbS \cdot Ag_2S$) | $Y = -64 \dots 16$ (uniform) | $7$ blocks | 5 | Iron Pickaxe | `voltcraft:lead_ingot` (via `raw_galena`; **no** silver-nugget bonus in loot table) |
| `voltcraft:ore_sphalerite` | `voltcraft:deepslate_ore_sphalerite` | Zinc Sulfide ($ZnS$) | $Y = -16 \dots 48$ (uniform) | $6$ blocks | 5 | Stone Pickaxe | `voltcraft:zinc_ingot` |
| `voltcraft:ore_spodumene` | `voltcraft:deepslate_ore_spodumene` | Lithium Pyroxene ($LiAl(SiO_3)_2$) | $Y = -64 \dots 8$ (uniform) | $5$ blocks | 4 | Iron Pickaxe | `voltcraft:lithium_ingot` (via `raw_spodumene`) |
| `voltcraft:ore_pentlandite` | `voltcraft:deepslate_ore_pentlandite` | Nickel-Iron Sulfide ($(Fe,Ni)_9S_8$) | $Y = -56 \dots 0$ (uniform) | $6$ blocks | 4 | Iron Pickaxe | `voltcraft:nickel_ingot` |
| `voltcraft:ore_high_purity_quartz`| `voltcraft:deepslate_ore_quartz` | Crystalline Silica ($SiO_2$) | $Y = -32 \dots 64$ (uniform) | $6$ blocks | 5 | Iron Pickaxe | `voltcraft:pure_silica_dust` |

Loot (`client/VoltcraftBlockLootTableGenerator.java`): every ore drops its raw
(`raw_bauxite/galena/sphalerite/spodumene/pentlandite`, quartz ores drop `pure_silica_dust`
directly). No silver-nugget side drop exists. Block hardness: stone ores 3.0f, deepslate 4.5f.

---

## 2. Refining & Metallurgical Recipes

> All furnace recipes are generated twice (smelting 200t + blasting 100t, 0.7 XP, `group:<metal>`),
> from raw **and** both ore variants (36 JSONs). Silver compacting is standard 9x reversible.
> Rubber from slime is 0.35 XP. Exact files: `src/main/generated/data/voltcraft/recipe/*.json`.

### 2.1 Smelting & Blasting (Furnace / Blast Furnace)
* **Aluminum Ingot:** `raw_bauxite` / `ore_bauxite` / `deepslate_ore_bauxite` $\to$ `aluminum_ingot`.
* **Lead Ingot:** `raw_galena` / `ore_galena` / `deepslate_ore_galena` $\to$ `lead_ingot` (no silver by-product).
* **Zinc Ingot:** `raw_sphalerite` / `ore_sphalerite` / `deepslate_ore_sphalerite` $\to$ `zinc_ingot`.
* **Lithium Ingot:** `raw_spodumene` / `ore_spodumene` / `deepslate_ore_spodumene` $\to$ `lithium_ingot`.
* **Nickel Ingot:** `raw_pentlandite` / `ore_pentlandite` / `deepslate_ore_pentlandite` $\to$ `nickel_ingot`.
* **Silica Dust:** `ore_high_purity_quartz` / `deepslate_ore_quartz` $\to$ `pure_silica_dust`.
* **Rubber Sheet:** `slime_ball` $\to$ `rubber_sheet` (furnace/blast, 0.35 XP).
* **Silver:** $9 \times$ `silver_nugget` $\to$ `silver_ingot` (shaped); $1 \times$ `silver_ingot` $\to$ $9 \times$ `silver_nugget` (shapeless).

### 2.2 Specialized Alloys (Crafting & Arc Smelting)
> Exact shapeless recipes in code (`VoltcraftRecipeGenerator`):
* **Nichrome Ingot:** `nickel_ingot` + `iron_ingot` (shapeless) ==> **2x** `nichrome_ingot`.
  (Spec text describing 4x Ni + 1x Fe ==> 5x is **not** the implemented recipe.)
* **Fuse Alloy Ingot:** `lead_ingot` + `zinc_ingot` (shapeless) ==> **2x** `fuse_alloy_ingot`.
  (No lead-tin / iron-nugget variant; no 3x yield in code.)
* **Vulcanized Rubber Sheet:**
  * Furnace/blast: `slime_ball` ==> 1x `rubber_sheet` (0.35 XP).
  * Shapeless: `slime_ball` + `coal` ==> 2x, `slime_ball` + `charcoal` ==> 2x.
  (No sulfur-dust variant in code.)
