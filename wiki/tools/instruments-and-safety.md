# Tools, Diagnostic Instruments & Safety Equipment

Part of the [[../core-idea|VoltCraft Core Idea & Architecture]] specification.

---

## 1. Modular Battery Bay & Cell Replacement System

All powered electronic instruments, diagnostic meters, and portable tools in VoltCraft feature **modular, replaceable battery bays**. 

### 1.1 Non-Destructive Cell Lifecycles & Tool Crafting
* **No Batteries in Crafting Recipes:** Powered tools are crafted strictly as unpowered chassis with empty internal battery bays and electrical contact springs. Batteries are never consumed as permanent crafting components.
* **Degradation & Hot-Swapping:** Real chemical cells degrade over time (State of Health / SOH % decreases with cycle count and thermal strain). When a cell exhausts its charge or permanently degrades in capacity, players pop open the tool's compartment and replace or recharge the cell.
* **In-Game Interaction:**
  * **Shift + Right-Click** in the air with the tool in hand opens the **Battery Compartment GUI**.
  * Alternatively, combine the tool with compatible battery cells in a 2x2 or 3x3 crafting grid to insert/swap cells.

### 1.2 Versatile Voltage & Form-Factor Compatibility
Electronic tools operate under an internal target voltage range. The standard handheld tool compartment ($3.0\text{--}4.2\text{ V}$ rail) supports interchangeable combinations:

```
  [ Option A: 1x 18650 Li-Ion ] ───► 1 x 3.7V (Rechargeable, 3000 mAh) ───► Total: 3.7V  [NOMINAL]
  [ Option B: 2x 1.5V Alkaline ] ──► 2 x 1.5V in series (Disposable)    ───► Total: 3.0V  [NOMINAL]
  [ Option C: 3x 1.2V NiMH ]    ───► 3 x 1.2V in series (Rechargeable)  ───► Total: 3.6V  [NOMINAL]
```

* **Series Sum Calculation:**
  $$V_{\text{bay}} = \sum_{i=1}^{N} V_{\text{cell}, i}$$
* **Brownout Protection:** If $V_{\text{bay}} < V_{\text{min}}$ ($< 2.8\text{ V}$), the device screen flickers and refuses to take readings.
* **Over-Voltage Hazard:** If $V_{\text{bay}} > V_{\text{max}}$ (e.g. attempting to force multiple 3.7V Li-Ion cells into a 3.7V bay without proper regulation), the tool's internal input protection fuse pops or the logic board is destroyed.
* **Individual Cell Health Tracking:** Each slotted cell maintains its own Minecraft 1.21 `DataComponentTypes` (`voltcraft:battery_charge`, `voltcraft:battery_health`, `voltcraft:battery_temperature`).

---

## 2. Tool & Safety Crafting Recipes

All powered tools produce **Empty Chassis** with spring-loaded battery contacts.

### 2.1 Digital Multimeter (`voltcraft:digital_multimeter`)
* **Chassis:** Handheld meter with LCD readout.
* **Battery Bay:** Accepts $1\times$ 18650 Li-Ion ($3.7\text{ V}$) OR $2\times$ Alkaline / Zinc-Carbon ($3.0\text{ V}$).
* **Functions:** Right-click any cable or machine face to inspect real-time Voltage ($V$), Current ($I$), Power ($W$), and Resistance ($R$).
* Crafting Table (Empty Chassis):
  ```
  [ Redstone Torch    ] [ Glass Pane       ] [ Redstone Torch    ]
  [ Bare Copper Wire  ] [ Redstone Dust    ] [ Bare Copper Wire  ]
  [ Iron Ingot        ] [ Copper Nugget    ] [ Iron Ingot        ]
  ==> Yields: 1x Digital Multimeter (Empty Battery Bay)
  ```

### 2.2 Clamp Meter (`voltcraft:clamp_meter`)
* **Chassis:** Induction clamp probe for live circuits.
* **Battery Bay:** Accepts $1\times$ 18650 Li-Ion ($3.7\text{ V}$) OR $2\times$ Alkaline / Zinc-Carbon ($3.0\text{ V}$).
* **Functions:** Non-invasive AC/DC current measurement through cables without disconnecting circuits.
* Crafting Table (Empty Chassis):
  ```
  [ Iron Nugget        ] [ Transformer Core ] [ Iron Nugget        ]
  [ Iron Ingot         ] [ Copper Magnet Wire] [ Iron Ingot        ]
  [ Insulated Cable    ] [ Copper Nugget    ] [ Insulated Cable    ]
  ==> Yields: 1x Clamp Meter (Empty Battery Bay)
  ```

### 2.3 Oscilloscope Tablet (`voltcraft:oscilloscope_tablet`)
* **Chassis:** High-speed graphical waveform analyzer.
* **Battery Bay:** Accepts $1\times$ 18650 Li-Ion ($3.7\text{ V}$) OR $1\times$ 21700 cell OR $3\times$ NiMH cells ($3.6\text{ V}$).
* **Functions:** Opens GUI rendering live AC/DC waveform graphs, frequency ($f$), THD %, and phase angle ($\phi$).
* Crafting Table (Empty Chassis):
  ```
  [ Glass Pane         ] [ Glass Pane       ] [ Glass Pane         ]
  [ Gold Wire          ] [ BMS Logic Board  ] [ Gold Wire          ]
  [ Aluminum Ingot     ] [ Copper Busbar    ] [ Aluminum Ingot     ]
  ==> Yields: 1x Oscilloscope Tablet (Empty Battery Bay)
  ```

### 2.4 Thermal Imaging Camera (`voltcraft:thermal_imaging_camera`)
* **Chassis:** Infrared micro-bolometer camera.
* **Battery Bay:** Accepts $1\times$ 18650 Li-Ion ($3.7\text{ V}$) OR $1\times$ 21700 cell OR $2\times$ Alkaline cells ($3.0\text{ V}$).
* **Functions:** Renders a real-time thermal HUD overlay: highlights overheating cables, overloaded transformers, and shaded solar panel hot-spots.
* Crafting Table (Empty Chassis):
  ```
  [ Optical Glass Lens ] [ Gold Nugget      ] [ Aluminum Ingot     ]
  [ Pure Silica Dust   ] [ BMS Logic Board  ] [ Iron Ingot         ]
  [ Aluminum Ingot     ] [ Copper Busbar    ] [ Aluminum Ingot     ]
  ==> Yields: 1x Thermal Imaging Camera (Empty Battery Bay)
  ```

### 2.5 Wire Stripper & Crimper Pliers (`voltcraft:wire_stripper_pliers`)
* Non-powered hand tool ($500$ uses). Strips insulated cables to bare wire, cuts cables instantly, and crimps lug connectors.
* Crafting Table:
  ```
  [ Iron Ingot   ] [ Shears       ] [    None    ]
  [ Rubber Sheet ] [ Iron Ingot   ] [    None    ]
  [ Rubber Sheet ] [ Rubber Sheet ] [    None    ]
  ==> Yields: 1x Wire Stripper Pliers
  ```

### 2.6 Insulated Electrician's Gloves (`voltcraft:electrician_gloves`)
* Non-powered PPE glove armor slot ($250$ shock absorptions). Absorbs shocks up to $1000\text{ V}$.
* Crafting Table:
  ```
  [ Rubber Sheet ] [    None    ] [ Rubber Sheet ]
  [ Rubber Sheet ] [ Leather    ] [ Rubber Sheet ]
  [ Rubber Sheet ] [ Leather    ] [ Rubber Sheet ]
  ==> Yields: 1x Electrician Gloves
  ```

### 2.7 Sacrificial Cartridge Fuses (10A, 16A, 32A, 64A)
* Sacrificial lead-tin alloy elements for `voltcraft:fuse_box`.
* **10A Fuse (`voltcraft:fuse_cartridge_10a`):**
  Crafting Table (Shapeless): `[ Glass Pane ] + [ Lead-Tin Fuse Alloy ] + [ Copper Nugget ]` $\to$ 4x
* **16A Fuse (`voltcraft:fuse_cartridge_16a`):**
  Crafting Table (Shapeless): `[ Glass Pane ] + [ Lead-Tin Fuse Alloy ] + [ Iron Nugget ]` $\to$ 4x
* **32A Fuse (`voltcraft:fuse_cartridge_32a`):**
  Crafting Table (Shapeless): `[ Glass Pane ] + [ Lead-Tin Fuse Alloy ] + [ Bare Copper Wire ]` $\to$ 4x
* **64A Industrial Fuse (`voltcraft:fuse_cartridge_64a`):**
  Crafting Table (Shapeless): `[ Ceramic / Terracotta ] + [ Lead-Tin Fuse Alloy ] + [ Silver Nugget ]` $\to$ 4x

### 2.8 Battery Charger & Diagnostic Bench (`voltcraft:battery_charger_station`)
* **Stationary Diagnostic Workstation:** Placed in the world and connected to an electrical circuit ($12\text{--}240\text{V}$ AC/DC).
* **Slots & Capacity:** Features 4 multi-chemistry cell charging bays and 1 tool chassis dock.
* **Functions:**
  * Recharges 18650, 21700, NiCd, and NiMH cells following proper CC/CV multi-stage charging algorithms.
  * Direct tool charging: docks any modular powered tool (multimeter, thermal camera, oscilloscope) to recharge its installed cells in-situ without needing manual extraction.
  * Diagnostic Cycle: Performs battery health diagnostic cycling, reporting exact real-world remaining capacity (mAh), internal resistance ($m\Omega$), and State of Health (SOH %).
* **Crafting Table:**
  ```
  [ Iron Ingot             ] [ Digital Multimeter ] [ Iron Ingot             ]
  [ Pure Sine Inverter     ] [ Crafting Table     ] [ Buck DC Converter      ]
  [ Insulated Copper Cable ] [ BMS Logic Board    ] [ Insulated Copper Cable ]
  ==> Yields: 1x Battery Charger Station
  ```
