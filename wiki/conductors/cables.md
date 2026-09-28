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

> **Code source:** `block/cable/ConductorType.java`, `simulation/solver/ThermalEquilibrium.java`.
> Per-block resistance/thermal values below are the exact enum constants. Grid MNA stamping uses
> $R(T) = R_0 \cdot [1 + \alpha (T - T_0)]$ with $T_0 = 20^\circ\text{C}$.
> $R_0$ is the resistance of **one block-length segment** ($\Omega$/block), not $\rho \cdot L/A$.

| Material | Identifier | $R_0$ ($\Omega$/block) | Temp Coeff $\alpha$ ($1/\text{K}$) | Rated $I_{\text{max}}$ | Insulation limit | Metal melt | Insulated | Shock hazard |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Superconductor** | `voltcraft:conduit_superconductor` | $1.0 \times 10^{-7}$ | $0.0$ | $100{,}000\text{ A}$ | $200^\circ\text{C}$ | $1000^\circ\text{C}$ | yes | no |
| **Silver** | `voltcraft:cable_silver_precision` | $0.0063$ | $+0.00380$ | $48\text{ A}$ | $150^\circ\text{C}$ | $961^\circ\text{C}$ | yes | no |
| **Copper (Bare)** | `voltcraft:cable_copper_bare` | $0.0068$ | $+0.00393$ | $32\text{ A}$ | $1085^\circ\text{C}$ (no insulation) | $1085^\circ\text{C}$ | no | **yes** |
| **Copper (Insulated)**| `voltcraft:cable_copper_insulated` | $0.0068$ | $+0.00393$ | $32\text{ A}$ | $120^\circ\text{C}$ | $1085^\circ\text{C}$ | yes | no |
| **Copper (Heavy)** | `voltcraft:cable_copper_heavy` | $0.0011$ | $+0.00393$ | $120\text{ A}$ | $250^\circ\text{C}$ | $1085^\circ\text{C}$ | yes | no |
| **Gold** | `voltcraft:cable_gold_bus` | $0.0097$ | $+0.00340$ | $40\text{ A}$ | $180^\circ\text{C}$ | $1064^\circ\text{C}$ | yes | no |
| **Aluminum** | `voltcraft:cable_aluminum_transmission` | $0.0026$ | $+0.00429$ | $64\text{ A}$ | $660^\circ\text{C}$ (bare) | $660^\circ\text{C}$ | no | **yes** |
| **Iron / Steel** | `voltcraft:cable_steel_fence` | $0.0388$ | $+0.00500$ | $16\text{ A}$ | $1538^\circ\text{C}$ (bare) | $1538^\circ\text{C}$ | no | **yes** |
| **Nichrome** | `voltcraft:cable_nichrome_heating` | $0.7333$ | $+0.00040$ | $8\text{ A}$ | $1400^\circ\text{C}$ (bare) | $1400^\circ\text{C}$ | no | **yes** |

Thermal model per type (`heatCapacity`, `coolingRate`): Bare Cu (8.5, 0.18), Insulated Cu (9.5, 0.14),
Heavy Cu (55.0, 0.55), Aluminum (25.0, 0.35), Silver (7.0, 0.16), Gold (6.0, 0.15),
Steel (11.0, 0.20), Nichrome (12.0, 0.10), Superconductor (100.0, 1.0).

> **Design-only (not in code):** `voltcraft:fuse_cartridge_*` ($10\text{--}64\text{ A}$) fuse items,
> graphite/carbon resistor blocks with negative $\alpha$, YBCO/cryogen quench mechanics at $77\text{ K}$,
> contact-shock damage ($V/25$ hearts) and arc-to-damp-block behavior are **spec text only**.
> Implemented: insulation/melt thermal trip via `ThermalEquilibrium`; uninsulated types flag
> `shockHazard = true`. The superconductor in code is a $100\text{ kA}$, $\alpha = 0$ conduit
> (recipe: Nether Star + Water Bucket), **not** infinite ampacity and **not** quench-gated.

---

## 3. Cable Crafting Recipes

> Generated by `client/VoltcraftRecipeGenerator.java` → `src/main/generated/data/voltcraft/recipe/*.json`.
> Counts below are the exact `result.count` values in code.

### 3.1 Bare Copper Wire (`voltcraft:cable_copper_bare`)
```
[ Copper Ingot ] [ Copper Ingot ] [ Copper Ingot ]
==> Yields: 6x Bare Copper Wire (single-row `CCC`)
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
[ Iron Ingot  ] [ Zinc Ingot  ] [ Iron Ingot  ]
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
[ Bare Copper Wire ] [ Nether Star   ] [ Bare Copper Wire ]
[ Aluminum Ingot ] [ Water Bucket    ] [ Aluminum Ingot ]
==> Yields: 4x Superconductor Conduit
```
Pattern `AGA/CSC/AWA` (`VoltcraftRecipeGenerator`: Al + glass + bare Cu + Nether Star + Water Bucket).
No YBCO powder / cryogen-fluid variant exists in code.
