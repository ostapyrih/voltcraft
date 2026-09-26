# Natural Ores & Raw Metallurgy

Part of the [[../core-idea|VoltCraft Core Idea & Architecture]] specification.

---

## 1. Geological Distribution & World Generation

VoltCraft introduces realistic mineral deposits to supply the specialized conductors, chemical electrolytes, and semiconductors required for electrical engineering.

```
       Overworld Mineral Stratigraphy
   Y
  128 ┌──────────────────────────────────────────────┐
      │  [Bauxite Ore]  (Jungles / Warm Biomes)      │
   64 ├──────────────────────────────────────────────┤
      │  [Sphalerite (Zinc)]                         │
   32 ├──────────────────────────────────────────────┤
      │  Vanilla Copper / Iron Range                 │
    0 ├──────────────────────────────────────────────┤  <-- Deepslate Transition
  -16 │  [Pentlandite (Nickel)]   [Galena (Lead/Ag)] │
  -32 ├──────────────────────────────────────────────┤
      │  [Spodumene (Lithium)] (Deep Pegmatites)     │
  -64 └──────────────────────────────────────────────┘
```

| Ore Identifier | Deepslate Variant | Primary Mineral | Biome Preferences | Y-Range | Vein Size | Mining Level | Smelting Product |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `voltcraft:ore_bauxite` | `voltcraft:deepslate_ore_bauxite` | Hydrous Aluminum Oxides | Jungles, Savannas, Plains | $Y = 32 \dots 128$ | $8\text{--}14$ blocks | Stone Pickaxe | `voltcraft:aluminum_ingot` |
| `voltcraft:ore_galena` | `voltcraft:deepslate_ore_galena` | Lead-Silver Sulfide ($PbS \cdot Ag_2S$) | Universal, Mountains | $Y = -64 \dots 16$ | $6\text{--}10$ blocks | Iron Pickaxe | `voltcraft:lead_ingot` + $25\%$ `voltcraft:silver_nugget` |
| `voltcraft:ore_sphalerite` | `voltcraft:deepslate_ore_sphalerite` | Zinc Sulfide ($ZnS$) | River valleys, Plains, Swamps | $Y = -16 \dots 48$ | $6\text{--}10$ blocks | Stone Pickaxe | `voltcraft:zinc_ingot` |
| `voltcraft:ore_spodumene` | `voltcraft:deepslate_ore_spodumene` | Lithium Pyroxene ($LiAl(SiO_3)_2$) | Badlands, Peaks, Deepslate | $Y = -64 \dots 8$ | $4\text{--}8$ blocks | Iron Pickaxe | `voltcraft:raw_lithium` $\to$ `voltcraft:lithium_ingot` |
| `voltcraft:ore_pentlandite` | `voltcraft:deepslate_ore_pentlandite` | Nickel-Iron Sulfide ($(Fe,Ni)_9S_8$) | Deep crust, Stony Peaks | $Y = -56 \dots 0$ | $4\text{--}8$ blocks | Iron Pickaxe | `voltcraft:nickel_ingot` |
| `voltcraft:ore_high_purity_quartz`| `voltcraft:deepslate_ore_quartz` | Crystalline Silica ($SiO_2$) | Desert, Granite dikes | $Y = -32 \dots 64$ | $4\text{--}6$ blocks | Iron Pickaxe | `voltcraft:pure_silica_dust` |

---

## 2. Refining & Metallurgical Recipes

### 2.1 Smelting & Blasting (Furnace / Blast Furnace)
* **Aluminum Ingot:**
  $$\text{Raw Bauxite} + \text{Fuel} \xrightarrow{\text{Blast Furnace}} \text{Aluminum Ingot} \quad (100\text{ XP})$$
* **Lead Ingot:**
  $$\text{Raw Galena} + \text{Fuel} \xrightarrow{\text{Furnace / Blast Furnace}} \text{Lead Ingot} + 25\%\, \text{Silver Nugget}$$
* **Zinc Ingot:**
  $$\text{Raw Sphalerite} + \text{Fuel} \xrightarrow{\text{Furnace / Blast Furnace}} \text{Zinc Ingot}$$
* **Nickel Ingot:**
  $$\text{Raw Nickel} + \text{Fuel} \xrightarrow{\text{Blast Furnace}} \text{Nickel Ingot}$$
* **Lithium Ingot:**
  $$\text{Raw Lithium} + \text{Fuel} \xrightarrow{\text{Blast Furnace}} \text{Lithium Ingot}$$
* **Silver Ingot:**
  $$9 \times \text{Silver Nugget} \xrightarrow{\text{Crafting Table}} 1 \times \text{Silver Ingot}$$

### 2.2 Specialized Alloys (Crafting & Arc Smelting)
* **Nichrome Ingot (Heating Alloy - $80\%$ Nickel / $20\%$ Chromium-Iron):**
  Crafting Table (Shapeless):
  ```
  [ Nickel Ingot ] + [ Nickel Ingot ] + [ Nickel Ingot ] + [ Nickel Ingot ] + [ Iron Ingot ]
  ==> Yields: 5x Nichrome Ingot
  ```
* **Lead-Tin Fuse Alloy (Low Melting Point $230^\circ\text{C}$):**
  Crafting Table (Shapeless):
  ```
  [ Lead Ingot ] + [ Lead Ingot ] + [ Zinc Ingot ] (or Vanilla Iron Nugget)
  ==> Yields: 3x Fuse Alloy Ingot
  ```
* **Vulcanized Rubber:**
  Crafting Table / Furnace:
  ```
  [ Slimeball / Resin ] + [ Sulfur Dust / Coal Dust ] ---> Furnace Smelt ---> Rubber Sheet
  ```
