# Cables & Conductor Materials

Part of the [[../core-idea|VoltCraft Core Idea & Architecture]] specification.

---

## 1. Conductor Physics & Mathematical Model

All conductors (cables, wires, busbars) in VoltCraft are passive topological connections within an `ElectricalGrid`. Rather than ticking individual blocks, conductor properties are compiled into the grid's admittance matrix.

### 1.1 DC Resistance of a Conductor Segment
For a cable segment of length $L$ (meters / blocks) and effective cross-sectional area $A$ ($\text{m}^2$):
$$R_0 = \rho \cdot \frac{L}{A}$$
Where:
* $\rho$ is the material resistivity at reference temperature $T_0 = 20^\circ\text{C}$ ($293.15\text{ K}$) in $\Omega \cdot \text{m}$.
* $A$ is the cross-sectional area. Standard thin wire is modeled at $A = 2.5\text{ mm}^2 = 2.5 \times 10^{-6}\text{ m}^2$, heavy industrial cable at $A = 16\text{ mm}^2$, and heavy busbars at $A = 100\text{ mm}^2$.

### 1.2 Temperature Dependence
Resistance increases dynamically with temperature according to the linear temperature coefficient of resistance $\alpha$:
$$R(T) = R_0 \cdot \left[1 + \alpha (T - T_0)\right]$$
* High-current loads generate heat, raising $T$, which raises $R$, creating a positive thermal feedback loop unless dissipation cools the conductor.
* Certain materials (like Carbon/Graphite) feature a negative temperature coefficient ($\alpha < 0$), decreasing resistance as they heat.

### 1.3 Joule Heating & Thermal Equilibrium
Power dissipated as heat across a wire segment carrying current $I$:
$$P_{\text{loss}} = I^2 \cdot R(T)$$
Thermal accumulation per tick $\Delta t$ ($0.05\text{ s}$):
$$\Delta Q = P_{\text{loss}} \cdot \Delta t - h \cdot S \cdot (T - T_{\text{ambient}}) \cdot \Delta t$$
$$\Delta T = \frac{\Delta Q}{m \cdot c_p}$$
Where:
* $h$: Convective cooling coefficient (higher in water or windy rain; zero in vacuum).
* $S$: Surface area of the cable exposed to air.
* $m$: Mass of the wire segment ($m = \text{volume} \cdot \text{density} = L \cdot A \cdot d$).
* $c_p$: Specific heat capacity of the conductor metal ($\text{J} / (\text{kg}\cdot\text{K})$).

---

## 2. Materials & Conductivity Matrix

| Material | Identifier | Resistivity $\rho$ ($\Omega \cdot \text{m}$) | Temp Coeff $\alpha$ ($1/\text{K}$) | Rated Ampacity ($I_{\text{max}}$) | Melting Point | In-Game Mechanics & Environmental Hazards |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Superconductor** | `voltcraft:conduit_superconductor` | $0.0$ | $0.0$ | Unlimited | Breaks if $T > 77\text{ K}$ | Zero Joule loss. Requires continuous cryogenic cooling (liquid nitrogen/helium). Quenches violently if warmed. |
| **Silver** | `voltcraft:cable_silver_precision` | $1.59 \times 10^{-8}$ | $+0.0038$ | $48\text{ A}$ | $961^\circ\text{C}$ | Lowest resistance; ideal for ultra-precision electronics, computing buses, and high-frequency instrumentation. |
| **Copper (Bare)** | `voltcraft:cable_copper_bare` | $1.68 \times 10^{-8}$ | $+0.0039$ | $32\text{ A}$ | $1085^\circ\text{C}$ | Baseline general-purpose wire. Shocks mobs and players on contact ($V / 25$ hearts/sec). Arcs to damp blocks. |
| **Copper (Insulated)**| `voltcraft:cable_copper_insulated` | $1.68 \times 10^{-8}$ | $+0.0039$ | $32\text{ A}$ | $1085^\circ\text{C}$ | Safe to touch. Coated with vulcanized rubber or PVC. Insulation softens and melts at $>120^\circ\text{C}$, releasing toxic smoke and exposing bare copper. |
| **Copper (Heavy)** | `voltcraft:cable_copper_heavy` | $1.68 \times 10^{-8}$ | $+0.0039$ | $120\text{ A}$ | $1085^\circ\text{C}$ | $16\,\text{mm}^2$ armored plant distribution. Resists blasts and high inrush currents. Thermal limit $250^\circ\text{C}$. |
| **Gold** | `voltcraft:cable_gold_bus` | $2.44 \times 10^{-8}$ | $+0.0034$ | $40\text{ A}$ | $1064^\circ\text{C}$ | Corrosion-immune. Laid underwater or in acid without oxidation. Ideal for sensor telemetry lines. |
| **Aluminum** | `voltcraft:cable_aluminum_transmission` | $2.65 \times 10^{-8}$ | $+0.0039$ | $64\text{ A}$ | $660^\circ\text{C}$ | Lightweight high-voltage overhead pylon line. Cheap and high ampacity; brittle against explosions. |
| **Iron / Steel** | `voltcraft:cable_steel_fence` | $9.71 \times 10^{-8}$ | $+0.0050$ | $16\text{ A}$ | $1538^\circ\text{C}$ | Cheap early-game perimeter fence wire. High resistance; shocks touching mobs with heavy knockback. |
| **Lead / Tin (Fuse)**| `voltcraft:fuse_cartridge_*` | $2.20 \times 10^{-7}$ | $+0.0042$ | $10\text{--}64\text{ A}$ | $230^\circ\text{C}$ | Sacrificial alloy for fuse cartridges. Melts quickly when $I > I_{\text{rating}}$, intentionally severing circuit. |
| **Nichrome** | `voltcraft:cable_nichrome_heating` | $1.10 \times 10^{-6}$ | $+0.0004$ | $8\text{ A}$ | $1400^\circ\text{C}$ | High resistivity heating element. Used in electric furnaces, water heaters, and thermal radiators. |
| **Graphite / Carbon**| Resistor Block | $1.00 \times 10^{-5}$ | $-0.0005$ | $4\text{ A}$ | $3600^\circ\text{C}$ | Negative temp coefficient ($\alpha < 0$). Dummy load resistors, arc electrodes, surge arresters. |

---

## 3. Cable Crafting Recipes

### 3.1 Bare Copper Wire (`voltcraft:cable_copper_bare`)
```
[    None    ] [ Copper Ingot ] [    None    ]
[ Copper Ingot] [ Copper Ingot ] [ Copper Ingot]
[    None    ] [ Copper Ingot ] [    None    ]
==> Yields: 12x Bare Copper Wire
```

### 3.2 Insulated Copper Cable (`voltcraft:cable_copper_insulated`)
```
[ Rubber Sheet     ] [ Rubber Sheet     ] [ Rubber Sheet     ]
[ Bare Copper Wire ] [ Bare Copper Wire ] [ Bare Copper Wire ]
[ Rubber Sheet     ] [ Rubber Sheet     ] [ Rubber Sheet     ]
==> Yields: 6x Insulated Copper Cable
```

### 3.3 Heavy Industrial Cable (`voltcraft:cable_copper_heavy`)
```
[ Iron Ingot             ] [ Rubber Sheet             ] [ Iron Ingot             ]
[ Insulated Copper Cable ] [ Insulated Copper Cable   ] [ Insulated Copper Cable ]
[ Iron Ingot             ] [ Rubber Sheet             ] [ Iron Ingot             ]
==> Yields: 6x Heavy Industrial Cable
```

### 3.4 Aluminum Overhead Transmission Line (`voltcraft:cable_aluminum_transmission`)
```
[ Aluminum Ingot ] [ Aluminum Ingot ] [ Aluminum Ingot ]
[ Aluminum Ingot ] [ String / Fiber ] [ Aluminum Ingot ]
[ Aluminum Ingot ] [ Aluminum Ingot ] [ Aluminum Ingot ]
==> Yields: 16x Aluminum Transmission Line
```

### 3.5 Silver Precision Wire (`voltcraft:cable_silver_precision`)
```
[ Silver Ingot ] [ Silver Ingot ] [ Silver Ingot ]
[ Rubber Sheet ] [ Paper / Wool ] [ Rubber Sheet ]
==> Yields: 8x Silver Precision Wire
```

### 3.6 Gold Chemical-Resistant Bus (`voltcraft:cable_gold_bus`)
```
[ Gold Ingot   ] [ Gold Ingot   ] [ Gold Ingot   ]
[ Rubber Sheet ] [ Glass Pane   ] [ Rubber Sheet ]
==> Yields: 8x Gold Bus Cable
```

### 3.7 Galvanized Steel Security Wire (`voltcraft:cable_steel_fence`)
```
[ Iron Nugget ] [ Iron Ingot  ] [ Iron Nugget ]
[ Iron Ingot  ] [ Zinc Ingot  ] [ Iron Iron   ]
[ Iron Nugget ] [ Iron Ingot  ] [ Iron Nugget ]
==> Yields: 12x Steel Security Wire
```

### 3.8 Nichrome Heating Wire (`voltcraft:cable_nichrome_heating`)
```
[ Clay Ball      ] [ Nichrome Ingot ] [ Clay Ball      ]
[ Nichrome Ingot ] [ Terracotta     ] [ Nichrome Ingot ]
[ Clay Ball      ] [ Nichrome Ingot ] [ Clay Ball      ]
==> Yields: 8x Nichrome Heating Element Wire
```

### 3.9 Superconductor Cryogenic Conduit (`voltcraft:conduit_superconductor`)
```
[ Aluminum Ingot ] [ Glass Pane      ] [ Aluminum Ingot ]
[ Copper Wire    ] [ Nether Star / YBCO Powder ] [ Copper Wire ]
[ Aluminum Ingot ] [ Bucket of Water / Cryogen ] [ Aluminum Ingot ]
==> Yields: 4x Superconductor Conduit
```
