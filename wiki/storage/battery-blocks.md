# Battery Blocks: Stationary Energy Storage Systems (BESS)

Part of the [[../core-idea|VoltCraft Core Idea & Architecture]] specification.
See also [[../batteries|Electrochemistry & Battery Fundamentals]].

---

## 1. Overview of Battery Blocks

Battery blocks are stationary `BlockEntity` structures placed in the world as direct nodes within the `ElectricalGrid`. They provide bulk power buffering for solar installations, generator sets, factory automation, and emergency backup.

---

## 2. Block Storage Crafting Recipes

### 2.1 $LiFePO_4$ Battery Block (`voltcraft:battery_block_lifepo4`)
* **Chemistry:** Lithium Iron Phosphate ($48\text{ V}$ nominal, $100\text{ Ah} / 4.8\,\text{kWh}$, $3500+$ cycles).
* **Fire-Safe & Chemically Stable:** Olivine structure prevents thermal runaway fire/explosion under damage or short circuits.
* **Crafting Table:**
  ```
  [ Iron Ingot    ] [ Copper Busbar       ] [ Iron Ingot    ]
  [ Lithium Ingot ] [ BMS Logic Board     ] [ Lithium Ingot ]
  [ Iron Block    ] [ Rubber Sheet        ] [ Iron Block    ]
  ==> Yields: 1x LiFePO4 Battery Block
  ```

### 2.2 Heavy Lead-Acid Battery Bank (`voltcraft:battery_block_lead_acid`)
* **Chemistry:** Sealed Lead-Acid ($12\text{ V}$, $120\text{ Ah} / 1.44\,\text{kWh}$, $400$ cycles).
* Cheap early bulk storage. Heavy mining time. Vents explosive $H_2$ gas if placed in unventilated rooms.
* **Crafting Table:**
  ```
  [ Lead Ingot   ] [ Bare Copper Wire ] [ Lead Ingot   ]
  [ Lead Ingot   ] [ Water Bucket     ] [ Lead Ingot   ]
  [ Terracotta   ] [ Terracotta       ] [ Terracotta   ]
  ==> Yields: 1x Lead-Acid Battery Bank
  ```

### 2.3 Lithium-Titanate (LTO) Block (`voltcraft:battery_block_lto`)
* **Chemistry:** LTO ($24\text{ V}$, $60\text{ Ah}$, $10C$ ultra-fast 6-minute charge, $15000+$ cycles, $-50^\circ\text{C}$ immune).
* **Crafting Table:**
  ```
  [ Iron Ingot    ] [ Gold Bus Cable     ] [ Iron Ingot    ]
  [ Lithium Ingot ] [ BMS Logic Board    ] [ Lithium Ingot ]
  [ Quartz        ] [ Pure Silica Dust   ] [ Quartz        ]
  ==> Yields: 1x LTO Battery Block
  ```

### 2.4 Modular 18650 Battery Rack (`voltcraft:battery_rack_modular`)
* Bridges item-form 18650 / 21700 / small cylindrical cells to the stationary world grid.
* Contains 16 bays for inserting cylindrical battery cells.
* **Busbar Wiring Modes:**
  * **SERIES (Default):** Pack EMF equals the exact sum of all installed cell voltages ($V_{\text{EMF}} = \sum V_{\text{cell}}$). 1 cell = $3.7\text{ V}$, 4 cells = $14.8\text{ V}$, 16 cells = $59.2\text{ V}$. Internal resistances add in series.
  * **PARALLEL:** Pack EMF equals average cell EMF ($V_{\text{EMF}} \approx 3.7\text{ V}$), with max ampacity scaling up as $N \times I_{\text{cell}}$.
  * **Wiring Toggle:** Right-click the rack with a `Copper Busbar` or any `Cable` block item to instantly switch between Series and Parallel topologies!
* **Interaction:**
  * Right-click with empty hand: inspect telemetry (installed bays, active wiring mode, pack EMF, SoC, internal resistance).
  * Sneak + right-click with empty hand: extract the last installed cell.
  * Right-click with a cell: slot the cell into the next free bay (1–16).
* **Crafting Table:**
  ```
  [ Iron Ingot         ] [ Copper Busbar    ] [ Iron Ingot         ]
  [ Insulated Cable    ] [ Chest            ] [ Insulated Cable    ]
  [ Iron Ingot         ] [ BMS Logic Board  ] [ Iron Ingot         ]
  ==> Yields: 1x Modular Battery Rack
  ```

### 2.5 NiMH Battery Pack Block (`voltcraft:battery_block_nimh`)
* **Chemistry:** Nickel-Metal Hydride ($24\text{ V}$ nominal [20S], $50\text{ Ah} / 1.2\,\text{kWh}$, $1000$ cycles).
* High safety, non-toxic, reliable intermediate industrial storage without expensive lithium requirement.
* **Crafting Table:**
  ```
  [ Iron Ingot     ] [ Copper Busbar   ] [ Iron Ingot     ]
  [ NiMH Cell      ] [ BMS Logic Board ] [ NiMH Cell      ]
  [ Aluminum Ingot ] [ Rubber Sheet    ] [ Aluminum Ingot ]
  ==> Yields: 1x NiMH Battery Pack Block
  ```

### 2.6 NiCd Battery Pack Block (`voltcraft:battery_block_nicd`)
* **Chemistry:** Nickel-Cadmium ($24\text{ V}$ nominal [20S], $30\text{ Ah} / 720\,\text{Wh}$, $1500$ cycles).
* Extreme temperature resilience (operates in sub-zero and extreme desert heat), rugged high-drain tolerance.
* **Crafting Table:**
  ```
  [ Iron Ingot  ] [ Copper Busbar   ] [ Iron Ingot  ]
  [ NiCd Cell   ] [ BMS Logic Board ] [ NiCd Cell   ]
  [ Lead Ingot  ] [ Rubber Sheet    ] [ Lead Ingot  ]
  ==> Yields: 1x NiCd Battery Pack Block
  ```

---

## 3. Waveform Realism: AC vs DC Battery Physics

Electrochemical cells operate strictly on **Direct Current (DC)** via unidirectional ion transport between anode and cathode:

### 3.1 Unrectified AC Behavior
* **Zero Net Charge ($\Delta\text{SoC} = 0$):** Symmetrical alternating current cycles cancel out net faradaic charge transfer ($\int_0^T I(t)dt = 0$).
* **Joule Heating ($P = I_{\text{rms}}^2 \cdot R_{\text{int}}$):** Alternating current continuously passes through the internal resistance of the cells, dissipating high thermal power into the pack.
* **Electrode Degradation & Delamination:** Rapid cyclic polar reversal strips and plates dendrites, breaking down solid-electrolyte interphase (SEI) layers and rapidly degrading State of Health ($\text{SoH}$).
* **Thermal Runaway & Explosion:** If connected directly to an AC grid (e.g. Inverter output, AC Generator, or Transformer secondary), the cell temperature will rapidly climb past critical runaway thresholds ($80^\circ\text{C}$ for Li-ion, $95^\circ\text{C}$ for Lead-Acid, $140^\circ\text{C}$ for LiFePO4), violently exploding and destroying surrounding blocks!

### 3.2 Safe Charging from AC Power
To charge any battery or battery rack from an AC source (such as 230V 50Hz mains or an inverter grid), players **must** place a **Rectifier** (`Full-Wave Bridge Rectifier` or `Active Synchronous Rectifier`) in between:
```
[ 230V AC Source ] ──> [IN] Rectifier [OUT] ──> [ 48V DC Battery Pack ]
```
The rectifier converts the AC waveform into smooth DC ($0\text{ Hz}$), allowing nominal CC/CV electrochemical charging.
