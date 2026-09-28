# Battery Items: Portable Cells & Handheld Power

Part of the [[../core-idea|VoltCraft Core Idea & Architecture]] specification.
See also [[electrochemistry|Electrochemistry & Battery Fundamentals]].

---

## 1. Overview & Tool Compartment Compatibility

Battery items are individual chemical cells carried in inventories or inserted into handheld tools, electric armor, portable diagnostic instruments, and stationary battery racks. State is stored via Minecraft 1.21 `DataComponentTypes`:
* `voltcraft:battery_charge`: Fractional charge $[0.0, 1.0]$.
* `voltcraft:battery_health`: State of Health fraction.
* `voltcraft:battery_temperature`: Active cell temperature (runaway at per-chemistry $T_{\text{crit}}$ — $150^\circ\text{C}$ Li-Ion, $85^\circ\text{C}$ alkaline, $60^\circ\text{C}$ Zn-C, $70^\circ\text{C}$ CR2032, $120^\circ\text{C}$ Li-SOCl2, $80^\circ\text{C}$ NiCd, $65^\circ\text{C}$ NiMH; cell explodes via `BatteryCellItem`).
* `voltcraft:battery_cell_chemistry`: Chemistry key string.
* `voltcraft:battery_bay`: Tool-bay packing (`BatteryBayData`).

> Implemented cells: 8 (`item/battery/BatteryCellItem.java`, `maxCount = 16`).
> Specs below ($R_{\text{int}}$, C-rate, cycles) are the exact `BatteryChemistry` enum values.
> All recipes below are the exact shaped patterns from `VoltcraftRecipeGenerator`.

### 1.1 Tool Bay Interoperability & Voltage Compatibility
Tools and portable devices require target operating voltages (typically a $3.0\text{--}4.2\text{ V}$ DC rail). Battery bays feature physical spring contacts that accommodate versatile cell combinations:
* **Option A (High Capacity / Rechargeable):** $1\times$ 18650 Li-Ion Cell ($3.7\text{ V}$ nominal, $3000\text{ mAh}$).
* **Option B (Disposable / Early Game):** $2\times$ Alkaline / Zinc-Carbon Cells in series ($2 \times 1.5\text{--}1.8\text{ V} = 3.0\text{--}3.6\text{ V}$).
* **Option C (Freeze-Proof / Cold Biomes):** $3\times$ NiCd / NiMH Cells in series ($3 \times 1.2\text{ V} = 3.6\text{ V}$).

### 1.2 Degradation & Replacement Lifecycle
* **No Batteries in Crafting:** Powered tools are crafted as empty chassis.
* **Degradation:** Over repeated charge/discharge cycles, the cell's `voltcraft:battery_health` gradually decreases. Lower health reduces effective storage capacity.
* **Replacement:** When a cell is depleted or degraded, players sneak-right click the tool to open the battery compartment, pop the worn cell out, and insert a fresh or recharged cell. Disposable cells (Alkaline/Zinc-Carbon) are recycled or discarded; rechargeable cells (18650, NiMH, NiCd) are recharged in the [[../tools/instruments-and-safety|Battery Charger Station]] (design-only, Phase 7).

---

## 2. Item Cell Crafting Recipes

### 2.1 18650 Li-Ion Cell (`voltcraft:battery_18650_li_ion`)
* Chemistry: `LI_ION_18650` ($3.7\text{ V}$ nominal, $2.8\text{--}4.2\text{ V}$, $3000\text{ mAh}$, $5C$ = $15\text{ A}$ max, $R_{\text{int}} = 0.025\,\Omega$, rechargeable, runaway $150^\circ\text{C}$, 1000 cycles).
* High energy density for power tools, flashlights, jetpacks, and battery racks.
* Pattern `ACA/LSK/AIA` (A = aluminum ingot, C = copper ingot, L = lithium ingot, S = silica dust, K = coal, I = iron ingot):
  ```
  [ Aluminum Ingot ] [ Copper Ingot     ] [ Aluminum Ingot ]
  [ Lithium Ingot  ] [ Pure Silica Dust ] [ Coal           ]
  [ Aluminum Ingot ] [ Iron Ingot       ] [ Aluminum Ingot ]
  ==> Yields: 2x 18650 Li-Ion Cell (Uncharged)
  ```

### 2.2 21700 High-Discharge Li-Ion Cell (`voltcraft:battery_21700_high_drain`)
* Chemistry: `LI_ION_21700` NMC ($3.7\text{ V}$ nominal, $2.7\text{--}4.2\text{ V}$, $5000\text{ mAh}$, $10C$ = $50\text{ A}$, $R_{\text{int}} = 0.015\,\Omega$, rechargeable, $150^\circ\text{C}$, 1200 cycles).
* Heavy-duty power for electric mining drills, jetpacks, and plasma welders.
* Pattern `NCN/LSK/AIA` (N = nickel ingot, rest as above):
  ```
  [ Nickel Ingot   ] [ Copper Ingot     ] [ Nickel Ingot   ]
  [ Lithium Ingot  ] [ Pure Silica Dust ] [ Coal           ]
  [ Aluminum Ingot ] [ Iron Ingot       ] [ Aluminum Ingot ]
  ==> Yields: 2x 21700 High-Drain Cell
  ```

### 2.3 Alkaline Cell (`voltcraft:battery_cell_alkaline`)
* Chemistry: `ALKALINE` $Zn\text{-}MnO_2$ ($1.5\text{ V}$, $0.8\text{--}1.6\text{ V}$, $2000\text{ mAh}$, $1C$, $R_{\text{int}} = 0.15\,\Omega$, disposable, $85^\circ\text{C}$, 1 cycle).
* Powers early handheld torches, wireless remotes, basic meters.
* Pattern `NZN/CRC/NIN` (N = iron nugget, Z = zinc ingot, C = charcoal, R = redstone, I = iron ingot):
  ```
  [ Iron Nugget ] [ Zinc Ingot  ] [ Iron Nugget ]
  [ Charcoal    ] [ Redstone    ] [ Charcoal    ]
  [ Iron Nugget ] [ Iron Ingot  ] [ Iron Nugget ]
  ==> Yields: 4x Alkaline Cell
  ```

### 2.4 Zinc-Carbon Cell (`voltcraft:battery_cell_zinc_carbon`)
* Chemistry: `ZINC_CARBON` ($1.5\text{ V}$, $0.9\text{--}1.55\text{ V}$, $800\text{ mAh}$, $0.5C$, $R_{\text{int}} = 0.35\,\Omega$, disposable, $60^\circ\text{C}$).
* Cheap early cell. Leaks corrosive residue when drained.
* Pattern `PZP/KYK/PUP` (P = paper, Z = zinc, K = charcoal, Y = clay ball, U = copper ingot):
  ```
  [ Paper     ] [ Zinc Ingot ] [ Paper     ]
  [ Charcoal  ] [ Clay Ball  ] [ Charcoal  ]
  [ Paper     ] [ Copper Ingot ] [ Paper   ]
  ==> Yields: 4x Zinc-Carbon Cell
  ```

### 2.5 CR2032 Lithium Coin Cell (`voltcraft:battery_coin_cr2032`)
* Chemistry: `COIN_CR2032` $Li\text{-}MnO_2$ ($3.0\text{ V}$, $2.0\text{--}3.3\text{ V}$, $220\text{ mAh}$, $0.2C$, $R_{\text{int}} = 10.0\,\Omega$, disposable, $70^\circ\text{C}$).
* Micro-sensors, wristwatches, computer motherboard RTC clocks.
* Pattern `NLN/CGC` 2-row (N = iron nugget, L = lithium, C = copper ingot, G = glass pane):
  ```
  [ Iron Nugget ] [ Lithium Ingot ] [ Iron Nugget ]
  [ Copper Ingot] [ Glass Pane    ] [ Copper Ingot]
  ==> Yields: 4x CR2032 Coin Cell
  ```

### 2.6 Industrial $Li\text{-}SOCl_2$ Backup Cell (`voltcraft:battery_cell_lisocl2`)
* Chemistry: `LITHIUM_THIONYL` ($3.6\text{ V}$, $3.0\text{--}3.7\text{ V}$, $1500\text{ mAh}$, $0.5C$, $R_{\text{int}} = 5.0\,\Omega$, disposable, $120^\circ\text{C}$).
* Powers long-term bunker telemetry and remote sensor beacons.
* Pattern `ILI/GBG/INI` (I = iron ingot, L = lithium, G = glass pane, B = blaze powder, N = gold nugget):
  ```
  [ Iron Ingot ] [ Lithium Ingot ] [ Iron Ingot ]
  [ Glass Pane ] [ Blaze Powder  ] [ Glass Pane ]
  [ Iron Ingot ] [ Gold Nugget   ] [ Iron Ingot ]
  ==> Yields: 2x Li-SOCl2 Cell
  ```

### 2.7 NiCd Rechargeable Cell (`voltcraft:battery_cell_nicd`)
* Chemistry: `NICD` ($1.2\text{ V}$, $0.9\text{--}1.45\text{ V}$, $1200\text{ mAh}$, $5C$ = $6\text{ A}$, $R_{\text{int}} = 0.02\,\Omega$, rechargeable, $80^\circ\text{C}$, 1000 cycles).
* Prone to memory effect if recharged before full depletion.
* Pattern `NKN/YRY/NDN` (N = iron nugget, K = nickel ingot, Y = clay, R = redstone, D = lead ingot):
  ```
  [ Iron Nugget ] [ Nickel Ingot ] [ Iron Nugget ]
  [ Clay Ball   ] [ Redstone     ] [ Clay Ball   ]
  [ Iron Nugget ] [ Lead Ingot   ] [ Iron Nugget ]
  ==> Yields: 2x NiCd Cell
  ```

### 2.8 NiMH Rechargeable Cell (`voltcraft:battery_cell_nimh`)
* Chemistry: `NIMH` ($1.2\text{ V}$, $1.0\text{--}1.42\text{ V}$, $2500\text{ mAh}$, $3C$ = $7.5\text{ A}$, $R_{\text{int}} = 0.03\,\Omega$, rechargeable, $65^\circ\text{C}$, 800 cycles).
* Standard reusable cell for tools and meters.
* Pattern `NKN/ASA/NZN` (N = iron nugget, K = nickel, A = aluminum, S = silica dust, Z = zinc):
  ```
  [ Iron Nugget  ] [ Nickel Ingot    ] [ Iron Nugget  ]
  [ Aluminum Ingot ] [ Pure Silica Dust ] [ Aluminum Ingot ]
  [ Iron Nugget  ] [ Zinc Ingot      ] [ Iron Nugget  ]
  ==> Yields: 2x NiMH Cell
  ```
