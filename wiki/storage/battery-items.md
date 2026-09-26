# Battery Items: Portable Cells & Handheld Power

Part of the [[../core-idea|VoltCraft Core Idea & Architecture]] specification.
See also [[electrochemistry|Electrochemistry & Battery Fundamentals]].

---

## 1. Overview & Tool Compartment Compatibility

Battery items are individual chemical cells carried in inventories or inserted into handheld tools, electric armor, portable diagnostic instruments, and stationary battery racks. State is stored via Minecraft 1.21 `DataComponentTypes`:
* `voltcraft:battery_charge`: Fractional charge $[0.0, 1.0]$.
* `voltcraft:battery_health`: State of Health (SOH % from $100\%$ down to $0\%$).
* `voltcraft:battery_temperature`: Active cell temperature (thermal hazard if $>150^\circ\text{C}$).

### 1.1 Tool Bay Interoperability & Voltage Compatibility
Tools and portable devices require target operating voltages (typically a $3.0\text{--}4.2\text{ V}$ DC rail). Battery bays feature physical spring contacts that accommodate versatile cell combinations:
* **Option A (High Capacity / Rechargeable):** $1\times$ 18650 Li-Ion Cell ($3.7\text{ V}$ nominal, $3000\text{ mAh}$).
* **Option B (Disposable / Early Game):** $2\times$ Alkaline / Zinc-Carbon Cells in series ($2 \times 1.5\text{--}1.8\text{ V} = 3.0\text{--}3.6\text{ V}$).
* **Option C (Freeze-Proof / Cold Biomes):** $3\times$ NiCd / NiMH Cells in series ($3 \times 1.2\text{ V} = 3.6\text{ V}$).

### 1.2 Degradation & Replacement Lifecycle
* **No Batteries in Crafting:** Powered tools are crafted as empty chassis.
* **Degradation:** Over repeated charge/discharge cycles, the cell's `voltcraft:battery_health` gradually decreases. Lower health reduces effective storage capacity.
* **Replacement:** When a cell is depleted or degraded, players sneak-right click the tool to open the battery compartment, pop the worn cell out, and insert a fresh or recharged cell. Disposable cells (Alkaline/Zinc-Carbon) are recycled or discarded; rechargeable cells (18650, NiMH, NiCd) are recharged in the [[../tools/instruments-and-safety#battery-charger-station|Battery Charger Station]].

---

## 2. Item Cell Crafting Recipes

### 2.1 18650 Li-Ion Cell (`voltcraft:battery_18650_li_ion`)
* Chemistry: $LiCoO_2$ ($3.7\text{ V}$ nominal, $3000\text{ mAh}$, $5C$ discharge).
* High energy density for power tools, flashlights, jetpacks, and battery racks.
* Crafting Table:
  ```
  [ Aluminum Foil/Ingot ] [ Copper Nugget     ] [ Aluminum Foil/Ingot ]
  [ Lithium Ingot       ] [ Pure Silica / Acid] [ Carbon / Graphite   ]
  [ Aluminum Foil/Ingot ] [ Steel / Iron Ingot] [ Aluminum Foil/Ingot ]
  ==> Yields: 2x 18650 Li-Ion Cell (Uncharged)
  ```

### 2.2 21700 High-Discharge Li-Ion Cell (`voltcraft:battery_21700_high_drain`)\n* Chemistry: NMC ($3.7\text{ V}$ nominal, $5000\text{ mAh}$, $10C$ high-drain).
* Heavy-duty power for electric mining drills, jetpacks, and plasma welders.
* Crafting Table:
  ```
  [ Nickel Ingot        ] [ Copper Nugget     ] [ Nickel Ingot        ]
  [ Lithium Ingot       ] [ Pure Silica Dust  ] [ Carbon / Graphite   ]
  [ Aluminum Ingot      ] [ Steel / Iron Ingot] [ Aluminum Ingot      ]
  ==> Yields: 2x 21700 High-Drain Cell
  ```

### 2.3 Alkaline Cell (`voltcraft:battery_cell_alkaline`)
* Chemistry: $Zn\text{-}MnO_2$ ($1.5\text{ V}$, $2000\text{ mAh}$, single-use disposable).
* Powers early handheld torches, wireless remotes, basic meters.
* Crafting Table:
  ```
  [ Iron Nugget  ] [ Brass / Zinc Ingot ] [ Iron Nugget  ]
  [ Charcoal Dust] [ Redstone Dust      ] [ Charcoal Dust]
  [ Iron Nugget  ] [ Iron Ingot         ] [ Iron Nugget  ]
  ==> Yields: 4x Alkaline Cell
  ```

### 2.4 Zinc-Carbon Cell (`voltcraft:battery_cell_zinc_carbon`)
* Chemistry: $Zn\text{-}C$ ($1.5\text{ V}$, $800\text{ mAh}$, vintage/primitive).
* Cheap early cell. Leaks corrosive residue when drained.
* Crafting Table:
  ```
  [ Paper        ] [ Zinc Ingot   ] [ Paper        ]
  [ Charcoal Dust] [ Clay Ball    ] [ Charcoal Dust]
  [ Paper        ] [ Copper Nugget] [ Paper        ]
  ==> Yields: 4x Zinc-Carbon Cell
  ```

### 2.5 CR2032 Lithium Coin Cell (`voltcraft:battery_coin_cr2032`)
* Chemistry: $Li\text{-}MnO_2$ ($3.0\text{ V}$, $220\text{ mAh}$, coin cell).
* Micro-sensors, wristwatches, computer motherboard RTC clocks.
* Crafting Table:
  ```
  [ Iron Nugget   ] [ Lithium Ingot ] [ Iron Nugget   ]
  [ Copper Nugget ] [ Glass Pane    ] [ Copper Nugget ]
  ==> Yields: 4x CR2032 Coin Cell
  ```

### 2.6 Industrial $Li\text{-}SOCl_2$ Backup Cell (`voltcraft:battery_cell_lisocl2`)
* Chemistry: Lithium-Thionyl Chloride ($3.6\text{ V}$, $1500\text{ mAh}$, 15+ year shelf-life).
* Powers long-term bunker telemetry and remote sensor beacons.
* Crafting Table:
  ```
  [ Steel Ingot   ] [ Lithium Ingot    ] [ Steel Ingot   ]
  [ Glass Pane    ] [ Blaze Powder     ] [ Glass Pane    ]
  [ Steel Ingot   ] [ Gold Nugget      ] [ Steel Ingot   ]
  ==> Yields: 2x Li-SOCl2 Cell
  ```

### 2.7 NiCd Rechargeable Cell (`voltcraft:battery_cell_nicd`)
* Chemistry: Nickel-Cadmium ($1.2\text{ V}$, $1200\text{ mAh}$, sub-zero resilient to $-40^\circ\text{C}$).
* Prone to memory effect if recharged before full depletion.
* Crafting Table:
  ```
  [ Iron Nugget  ] [ Nickel Ingot ] [ Iron Nugget  ]
  [ Clay Ball    ] [ Redstone Dust] [ Clay Ball    ]
  [ Iron Nugget  ] [ Lead Ingot   ] [ Iron Nugget  ]
  ==> Yields: 2x NiCd Cell
  ```

### 2.8 NiMH Rechargeable Cell (`voltcraft:battery_cell_nimh`)
* Chemistry: Nickel-Metal Hydride ($1.2\text{ V}$, $2500\text{ mAh}$, no memory effect).
* Standard reusable cell for tools and meters.
* Crafting Table:
  ```
  [ Iron Nugget  ] [ Nickel Ingot    ] [ Iron Nugget  ]
  [ Aluminum Foil] [ Pure Silica Dust] [ Aluminum Foil]
  [ Iron Nugget  ] [ Zinc Ingot      ] [ Iron Nugget  ]
  ==> Yields: 2x NiMH Cell
  ```
