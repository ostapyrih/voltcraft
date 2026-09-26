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
* Crafting / Processing:
  ```
  Arc Furnace or Blast Furnace:
  4x [ Pure Silica Dust ] + 1x [ Coal / Charcoal ] 
  ==> 1x Silicon Boule
  ```

### 1.2 Silicon Wafer (`voltcraft:silicon_wafer`)
* Precision thin disk sliced from a boule.
* Crafting Table (Shapeless):
  ```
  1x [ Silicon Boule ] + 1x [ Iron Hacksaw / Shear ]
  ==> Yields: 8x Silicon Wafer (Consumes tool durability)
  ```

### 1.3 Doped Wafers (P-Type & N-Type)
* **Doped P-Wafer (`voltcraft:doped_wafer_p`):**
  ```
  [   None   ] [ Copper Nugget ] [   None   ]
  [   None   ] [ Silicon Wafer ] [   None   ]
  [   None   ] [ Redstone Dust ] [   None   ]
  ==> Yields: 1x Doped P-Wafer
  ```
* **Doped N-Wafer (`voltcraft:doped_wafer_n`):**
  ```
  [   None   ] [ Gold Nugget   ] [   None   ]
  [   None   ] [ Silicon Wafer ] [   None   ]
  [   None   ] [ Glowstone Dust] [   None   ]
  ==> Yields: 1x Doped N-Wafer
  ```

### 1.4 Photovoltaic Cell Wafer (`voltcraft:photovoltaic_cell`)
* p-n junction with silver anti-reflective grid lines.
* Crafting Table:
  ```
  [ Silver Nugget ] [ Glass Pane      ] [ Silver Nugget ]
  [ Doped N-Wafer ] [ Doped P-Wafer   ] [ Doped N-Wafer ]
  [ Copper Wire   ] [ Aluminum Ingot  ] [ Copper Wire   ]
  ==> Yields: 3x Photovoltaic Cell
  ```

---

## 2. Discrete Electronic Components

### 2.1 Power MOSFET Transistor (`voltcraft:mosfet_power_transistor`)
* Essential for pure sine inverters and high-frequency switched-mode DC-DC converters.
* Crafting Table:
  ```
  [ Plastic / Resin  ] [ Copper Nugget    ] [ Plastic / Resin  ]
  [ Doped N-Wafer    ] [ Doped P-Wafer    ] [ Doped N-Wafer    ]
  [ Copper Wire      ] [ Copper Wire      ] [ Copper Wire      ]
  ==> Yields: 4x Power MOSFET
  ```

### 2.2 Schottky Diode (`voltcraft:schottky_diode`)
* Ultra-low forward-voltage drop ($0.3\text{ V}$) semiconductor junction.
* Crafting Table:
  ```
  [   None        ] [ Gold Nugget       ] [   None        ]
  [ Copper Wire   ] [ Doped N-Wafer     ] [ Copper Wire   ]
  [   None        ] [ Glass Pane        ] [   None        ]
  ==> Yields: 4x Schottky Diode
  ```

### 2.3 Electrolytic Filter Capacitor (`voltcraft:filter_capacitor_electrolytic`)
* High-capacitance aluminum foil cylinder for ripple voltage smoothing.
* Crafting Table:
  ```
  [ Rubber Sheet   ] [ Aluminum Foil/Ingot ] [ Rubber Sheet   ]
  [ Paper          ] [ Acid / Glowstone   ] [ Paper          ]
  [ Copper Wire    ] [ Aluminum Ingot      ] [ Copper Wire    ]
  ==> Yields: 4x Electrolytic Capacitor
  ```

### 2.4 Copper Magnet Wire (`voltcraft:copper_magnet_wire`)
* Ultra-thin enameled wire for electromagnetic windings.
* Crafting Table:
  ```
  [ Copper Ingot ] + [ Resin / Redstone Dust ] 
  ==> Yields: 8x Copper Magnet Wire Spool
  ```

### 2.5 Laminated Iron Transformer Core (`voltcraft:transformer_core_laminated`)
* Low eddy-current E-I silicon steel sheets.
* Crafting Table:
  ```
  [ Iron Ingot ] [ Pure Silica Dust ] [ Iron Ingot ]
  [ Iron Ingot ] [ Iron Ingot        ] [ Iron Ingot ]
  [ Iron Ingot ] [ Pure Silica Dust ] [ Iron Ingot ]
  ==> Yields: 2x Laminated Transformer Core
  ```

### 2.6 BMS Logic Board (`voltcraft:bms_logic_board`)
* Microcontroller PCB with cell balancing transistors and shunt telemetry.
* Crafting Table:
  ```
  [ Gold Wire     ] [ Power MOSFET     ] [ Gold Wire     ]
  [ Schottky Diode] [ Redstone Repeater] [ Silver Nugget ]
  [ Copper Wire   ] [ Silicon Wafer    ] [ Copper Wire   ]
  ==> Yields: 1x BMS Logic Board
  ```
