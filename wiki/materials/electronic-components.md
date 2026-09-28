# Electronic Components & Intermediate Materials

Part of the [[../core-idea|VoltCraft Core Idea & Architecture]] specification.

---

## 1. Semiconductor Manufacturing Chain

Semiconductor components form the building blocks for Solar Panels, Inverters, DC-DC Converters, and Battery Management Systems.

```
Pure Silica Dust ──► [Electric Arc Furnace] ──► Silicon Boule (Czochralski)
                                                    │
                                             [Wire Saw / Table]
                                                    ▼
                                              Silicon Wafer
                                            /               \
                       + Boron Dust        /                 \ + Redstone (P-donor)
                                          ▼                   ▼
                                    Doped P-Wafer       Doped N-Wafer
                                          \                   /
                                           ▼                 ▼
                                        Photovoltaic Cell / MOSFET
```

### 1.1 Silicon Boule (`voltcraft:silicon_boule`)
* High-purity single-crystal ingot.
* Implemented recipe (shapeless, `category:misc`):
  ```
  4x [ Pure Silica Dust ] + 1x [ Coal ]
  ==> 1x Silicon Boule
  ```
  (No arc/blast-furnace variant in code; charcoal is **not** accepted — only `minecraft:coal`.)

### 1.2 Silicon Wafer (`voltcraft:silicon_wafer`)
* Precision thin disk sliced from a boule.
* Implemented recipe (shapeless): `silicon_boule` + `iron_ingot` ==> **8x** `silicon_wafer`.
  The iron ingot is consumed (it stands in for the wire saw — no tool-durability mechanic exists).

### 1.3 Doped Wafers (P-Type & N-Type)
* Implemented recipes (both shapeless, 1x yield, no nuggets):
  * **Doped P-Wafer (`voltcraft:doped_wafer_p`):** `silicon_wafer` + `redstone` ==> 1x.
  * **Doped N-Wafer (`voltcraft:doped_wafer_n`):** `silicon_wafer` + `glowstone_dust` ==> 1x.

### 1.4 Photovoltaic Cell Wafer (`voltcraft:photovoltaic_cell`)
* p-n junction with silver anti-reflective grid lines. Pattern `SGS/NPN/CAC` (3x):
  ```
  [ Silver Nugget ] [ Glass Pane      ] [ Silver Nugget ]
  [ Doped N-Wafer ] [ Doped P-Wafer   ] [ Doped N-Wafer ]
  [ Bare Copper Wire ] [ Aluminum Ingot ] [ Bare Copper Wire ]
  ==> Yields: 3x Photovoltaic Cell
  ```

---

## 2. Discrete Electronic Components

### 2.1 Power MOSFET Transistor (`voltcraft:mosfet_power_transistor`)
* Essential for pure sine inverters and high-frequency switched-mode DC-DC converters.
* Pattern `RIR/NPN/CCC` (4x, `R` = rubber sheet, `I` = iron nugget):
  ```
  [ Rubber Sheet   ] [ Iron Nugget     ] [ Rubber Sheet   ]
  [ Doped N-Wafer  ] [ Doped P-Wafer   ] [ Doped N-Wafer  ]
  [ Bare Copper Wire ] [ Bare Copper Wire ] [ Bare Copper Wire ]
  ==> Yields: 4x Power MOSFET
  ```

### 2.2 Schottky Diode (`voltcraft:schottky_diode`)
* Code forward drop is **$0.7\text{ V}$ per diode ($1.4\text{ V}$ bridge)** in `RectifierType.BRIDGE`
  (spec text "$0.3\text{ V}$" is aspirational, not implemented).
* Pattern `" G "/CNC/" P "` (4x):
  ```
  [   None        ] [ Gold Nugget       ] [   None        ]
  [ Bare Copper Wire ] [ Doped N-Wafer  ] [ Bare Copper Wire ]
  [   None        ] [ Glass Pane        ] [   None        ]
  ==> Yields: 4x Schottky Diode
  ```

### 2.3 Electrolytic Filter Capacitor (`voltcraft:filter_capacitor_electrolytic`)
* Pattern `RAR/PGP/CAC` (4x; `G` = glowstone dust electrolyte stand-in):
  ```
  [ Rubber Sheet   ] [ Aluminum Ingot  ] [ Rubber Sheet   ]
  [ Paper          ] [ Glowstone Dust  ] [ Paper          ]
  [ Bare Copper Wire ] [ Aluminum Ingot ] [ Bare Copper Wire ]
  ==> Yields: 4x Electrolytic Capacitor
  ```

### 2.4 Copper Magnet Wire (`voltcraft:copper_magnet_wire`)
* Shapeless: `copper_ingot` + `redstone` ==> **8x**. (No resin variant in code.)

### 2.5 Laminated Iron Transformer Core (`voltcraft:transformer_core_laminated`)
* Pattern `ISI/III/ISI` (2x; `S` = pure silica dust silicon-steel stand-in):
  ```
  [ Iron Ingot ] [ Pure Silica Dust ] [ Iron Ingot ]
  [ Iron Ingot ] [ Iron Ingot       ] [ Iron Ingot ]
  [ Iron Ingot ] [ Pure Silica Dust ] [ Iron Ingot ]
  ==> Yields: 2x Laminated Transformer Core
  ```

### 2.6 BMS Logic Board (`voltcraft:bms_logic_board`)
* Pattern `GMG/DRS/CWC` (1x; `G` = **gold bus cable block**, not gold wire):
  ```
  [ Gold Bus Cable ] [ Power MOSFET     ] [ Gold Bus Cable   ]
  [ Schottky Diode ] [ Redstone Repeater] [ Silver Nugget    ]
  [ Bare Copper Wire ] [ Silicon Wafer  ] [ Bare Copper Wire ]
  ==> Yields: 1x BMS Logic Board
  ```
