# Battery Blocks: Stationary Energy Storage Systems (BESS)

Part of the [[../core-idea|VoltCraft Core Idea & Architecture]] specification.
See also [[electrochemistry|Electrochemistry & Battery Fundamentals]].

---

## 1. Overview of Battery Blocks

Battery blocks are stationary `BlockEntity` structures placed in the world as direct nodes within the `ElectricalGrid`. They provide bulk power buffering for solar installations, generator sets, factory automation, and emergency backup.

---

## 2. Block Storage Crafting Recipes

> Code: `block/VoltcraftBlocks.java` (series/parallel counts), `simulation/chemistry/BatteryChemistry.java`
> (cell specs). Pack nominal V = series $\times$ cell nominal; pack Ah = parallel $\times$ cell Ah.

### 2.1 $LiFePO_4$ Battery Block (`voltcraft:battery_block_lifepo4`)
* **Config:** `LIFEPO4, 15S, 1P` → $15 \times 3.2\text{ V} = 48\text{ V}$ nominal ($37.5\text{--}54.75\text{ V}$ window), $1 \times 100\text{ Ah} = 100\text{ Ah} / 4.8\,\text{kWh}$, $3C$ ($300\text{ A}$), $R_{\text{cell}} = 0.6\text{ m}\Omega$, 4000 cycles, runaway $270^\circ\text{C}$.
* **Fire-Safe & Chemically Stable:** Olivine structure prevents thermal runaway fire/explosion under damage or short circuits.
* **Crafting Table (`IBI/LML/SRS`; I = iron ingot, B = copper busbar, L = lithium ingot, M = BMS, S = iron block, R = rubber):**
  ```
  [ Iron Ingot    ] [ Copper Busbar       ] [ Iron Ingot    ]
  [ Lithium Ingot ] [ BMS Logic Board     ] [ Lithium Ingot ]
  [ Iron Block    ] [ Rubber Sheet        ] [ Iron Block    ]
  ==> Yields: 1x LiFePO4 Battery Block
  ```

### 2.2 Heavy Lead-Acid Battery Bank (`voltcraft:battery_block_lead_acid`)
* **Config:** `LEAD_ACID, 6S, 1P` → $6 \times 2.0\text{ V} = 12\text{ V}$ nominal ($10.5\text{--}14.4\text{ V}$), $120\text{ Ah} / 1.44\,\text{kWh}$, $1.5C$ ($180\text{ A}$), $1.5\text{ m}\Omega$/cell, 500 cycles, $70^\circ\text{C}$ $H_2$ vent.
* Cheap early bulk storage. Heavy mining time. Vents explosive $H_2$ gas if placed in unventilated rooms.
* **Crafting Table:**
  ```
  [ Lead Ingot   ] [ Bare Copper Wire ] [ Lead Ingot   ]
  [ Lead Ingot   ] [ Water Bucket     ] [ Lead Ingot   ]
  [ Terracotta   ] [ Terracotta       ] [ Terracotta   ]
  ==> Yields: 1x Lead-Acid Battery Bank
  ```

### 2.3 Lithium-Titanate (LTO) Block (`voltcraft:battery_block_lto`)
* **Config:** `LTO, 10S, 1P` → $10 \times 2.4\text{ V} = 24\text{ V}$ nominal ($15\text{--}28\text{ V}$), $60\text{ Ah} / 1.44\,\text{kWh}$, $10C$ ($600\text{ A}$, 6-min charge), $0.8\text{ m}\Omega$/cell, 15000 cycles, $200^\circ\text{C}$.
* **Crafting Table:**
  ```
  [ Iron Ingot    ] [ Gold Bus Cable     ] [ Iron Ingot    ]
  [ Lithium Ingot ] [ BMS Logic Board    ] [ Lithium Ingot ]
  [ Quartz        ] [ Pure Silica Dust   ] [ Quartz        ]
  ==> Yields: 1x LTO Battery Block
  ```

### 2.4 Modular 18650 Battery Rack (`voltcraft:battery_rack_modular`)
* Bridges item-form 18650 / 21700 / small cylindrical cells to the stationary world grid.
* Contains **16 bays** (`BatteryRackBlockEntity.INVENTORY_SIZE = 16`, any `BatteryCellItem`).
* **Busbar Wiring Modes (`RackWiringMode`):**
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
* **Config:** `NIMH, 20S, 20P` → $20 \times 1.2\text{ V} = 24\text{ V}$ nominal, $20 \times 2.5\text{ Ah} = 50\text{ Ah} / 1.2\,\text{kWh}$, 800 cycles, $65^\circ\text{C}$.
* High safety, non-toxic, reliable intermediate industrial storage without expensive lithium requirement.
* **Crafting Table:**
  ```
  [ Iron Ingot     ] [ Copper Busbar   ] [ Iron Ingot     ]
  [ NiMH Cell      ] [ BMS Logic Board ] [ NiMH Cell      ]
  [ Aluminum Ingot ] [ Rubber Sheet    ] [ Aluminum Ingot ]
  ==> Yields: 1x NiMH Battery Pack Block
  ```

### 2.6 NiCd Battery Pack Block (`voltcraft:battery_block_nicd`)
* **Config:** `NICD, 20S, 25P` → $20 \times 1.2\text{ V} = 24\text{ V}$ nominal, $25 \times 1.2\text{ Ah} = 30\text{ Ah} / 720\,\text{Wh}$, 1000 cycles, $80^\circ\text{C}$.
* Extreme temperature resilience (operates in sub-zero and extreme desert heat), rugged high-drain tolerance.
* **Crafting Table:**
  ```
  [ Iron Ingot  ] [ Copper Busbar   ] [ Iron Ingot  ]
  [ NiCd Cell   ] [ BMS Logic Board ] [ NiCd Cell   ]
  [ Lead Ingot  ] [ Rubber Sheet    ] [ Lead Ingot  ]
  ==> Yields: 1x NiCd Battery Pack Block
  ```

---

## 3. BMS Protection Philosophy (fixed 2026-10)

The BMS guards **depletion and overtemperature** — it is not a fuse. Terminal undervoltage alone
never latches the pack open: while the pack's own open-circuit EMF (SoC-anchored) stays above the
recovery threshold, the BMS stays closed (or recloses) so inrush/brownout sag and pre-bootstrap
telemetry can never strand a healthy bank offline unrecoverably. Terminal-based trip/recovery
(`bmsNext`, pack-minimum plus hysteresis) applies only once EMF itself falls below recovery
— i.e. genuine depletion — plus forced open above $60^\circ\text{C}$ (reclose below $55^\circ\text{C}$).
Overcurrent remains the fuse/breaker domain.

## 4. Waveform Realism: AC vs DC Battery Physics

Electrochemical cells operate strictly on **Direct Current (DC)** via unidirectional ion transport between anode and cathode:

### 3.1 Unrectified AC Behavior
> Thresholds below ($80/95/140^\circ\text{C}$) are **spec-text illustration**, not code constants.
> Code runaway gates are per-chemistry (`BatteryChemistry`): Li-Ion $150^\circ\text{C}$,
> Lead-Acid $70^\circ\text{C}$, LFP $270^\circ\text{C}$, LTO $200^\circ\text{C}$, NiMH $65^\circ\text{C}$, NiCd $80^\circ\text{C}$.
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
