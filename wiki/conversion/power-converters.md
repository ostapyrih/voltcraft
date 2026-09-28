# Power Converters: DC-DC, AC Transformers & Rectifiers

Part of the [[../core-idea|VoltCraft Core Idea & Architecture]] specification.

---

## 1. Overview of Power Conversion

In VoltCraft's realistic electrical grid, devices and transmission lines operate at differing voltage levels and distinct AC/DC waveforms:
* Long-distance transmission lines run at high voltage ($kV$) to reduce $I^2 R$ line losses.
* Industrial AC motors run at intermediate AC voltages ($400\text{V} / 230\text{V}$ at $50\text{ Hz}$).
* Logic devices, computers, and sensors run at low DC voltages ($5\text{V}, 12\text{V}, 24\text{V}, 48\text{V}$).
* Batteries operate on dynamic chemistry-dependent DC voltages ($1.2\text{V}\text{--}48\text{V}$ at $0\text{ Hz}$).

---

## 2. Waveform & Frequency Operating Constraints

Each conversion topology enforces physical AC vs DC waveform contracts:

| Conversion Topology | Permitted Input Frequency | Output Frequency | Behavior on Incompatible Waveform |
| :--- | :--- | :--- | :--- |
| **DC-DC Converter** (Buck, Boost, Buck-Boost, LDO) | **$0\text{ Hz}$ (DC Only)** | **$0\text{ Hz}$ (DC)** | Trips offline (`tripped = true`) if AC ($>0\text{ Hz}$) is detected. |
| **Inverter** (Square Wave, Modified, Pure Sine, Hybrid) | **$0\text{ Hz}$ (DC Only)** | **$50.0\text{ Hz}$ (AC)** | Trips offline (`tripped = true`) if AC is fed into DC input terminals. |
| **Transformer** (Step-Down, Step-Up) | **$>0\text{ Hz}$ (AC Only)** | **$50.0\text{ Hz}$ (AC)** | Trips offline (`tripped = true`) if DC ($0\text{ Hz}$) is applied (core saturation short-circuit). |
| **Rectifier** (Bridge, Active Synchronous) | **Any (AC or DC)** | **$0\text{ Hz}$ (DC)** | Converts AC to DC. Safe pass-through if fed DC. |

---

## 3. DC-DC Converter Topologies (Switched-Mode SMPS)

> **Code source:** `simulation/conversion/ConverterType.java` — exact ratings.
> GUI offers only `[5V] / [12V] / [24V] / [48V]` presets. Output law:
> BUCK $\min(V_{\text{in}}, V_{\text{set}})$, BOOST $\max(V_{\text{in}}, V_{\text{set}})$,
> BUCK_BOOST $V_{\text{set}}$, LDO $V_{\text{set}}$ iff $V_{\text{in}} \ge V_{\text{set}}$ else $0$.
> LDO efficiency is dynamic $\eta = V_{\text{out}} / V_{\text{in}}$.

| Type | Block ID | $\eta$ (nom.) | Max $I$ | Default $V$ | $V_{\text{in}}$ range | Linear |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| Buck | `voltcraft:converter_dc_buck` | $0.94$ | $100\text{ A}$ | $12\text{ V}$ | $8\text{--}60\text{ V}$ | no |
| Boost | `voltcraft:converter_dc_boost` | $0.92$ | $60\text{ A}$ | $48\text{ V}$ | $10\text{--}40\text{ V}$ | no |
| Buck-Boost / SEPIC | `voltcraft:converter_dc_buck_boost` | $0.90$ | $100\text{ A}$ | $24\text{ V}$ | $8\text{--}60\text{ V}$ | no |
| Linear LDO | `voltcraft:regulator_linear_ldo` | dynamic ($V_{\text{out}}/V_{\text{in}}$) | $20\text{ A}$ | $5\text{ V}$ | $6\text{--}35\text{ V}$ | **yes** ($P_{\text{loss}} = (V_{\text{in}} - V_{\text{out}}) \cdot I$) |

```
       Buck (Step-Down)                     Boost (Step-Up)
        Switch      Inductor                 Inductor       Diode
  Vin ───[ / ]──┬───[ L ]───┬── Vout   Vin ───[ L ]───┬────[ >| ]───┬── Vout
                │           │                         │             │
              [ |< ]      [===] C                   [ / ] Switch  [===] C
              Diode         │                         │             │
  GND ──────────┴───────────┴── GND    GND ───────────┴─────────────┴── GND
```

1. **Buck (Step-Down):** $V_{\text{out}} = D \cdot V_{\text{in}}$ where $D = t_{\text{on}}/T_s$. Steps $48\text{V} \to 12\text{V}/5\text{V}$ ($\eta \approx 92\text{--}96\%$).
2. **Boost (Step-Up):** $V_{\text{out}} = \frac{V_{\text{in}}}{1 - D}$. Steps $12\text{V} \to 48\text{V}/400\text{V}$ ($\eta \approx 88\text{--}94\%$).
3. **Buck-Boost & SEPIC:** Stabilizes erratic fluctuating solar/wind inputs ($12\text{--}60\text{V} \to 24\text{V}$).
4. **Linear LDO Regulators:** Low-noise dynamic pass transistor ($P_{\text{loss}} = (V_{\text{in}} - V_{\text{out}}) \cdot I$). Converts excess voltage directly into heat.

---

## 4. AC Transformers & Rectifiers

### 4.1 AC Transformers
$$\frac{V_s}{V_p} = \frac{N_s}{N_p} = a \qquad \frac{I_s}{I_p} = \frac{1}{a}$$
* Code (`TransformerType`): both units $5000\text{ VA}$, $\eta = 0.96$.
  Step-Down $a = 24/230$ ($230\text{ V} \to 24\text{ V}$); Step-Up $a = 230/24$ ($24\text{ V} \to 230\text{ V}$).
* Uses laminated silicon steel cores to reduce hysteresis ($P_h \propto f \cdot B_{\text{max}}^{1.6}$) and eddy current losses.
* Strictly requires alternating magnetic flux ($f > 0$). Applying continuous DC causes zero back-EMF, resulting in near dead-short conditions and instantaneous breaker trip.

### 4.2 AC-DC Rectifiers & Filtering
* **Bridge Rectifier** (`RectifierType.BRIDGE`): $1.40\text{ V}$ drop ($2 \times 0.7\text{ V}$ Si),
  $\eta = 0.88$, $32\text{ A}$ max. DC bus: $V_{\text{dc}} = \max(0, V_{\text{ac,rms}} \cdot \sqrt{2} - 1.40)$.
* **Active Synchronous Rectifier** (`ACTIVE_SYNCHRONOUS`): $0.05\text{ V}$ MOSFET drop,
  $\eta = 0.985$, $64\text{ A}$ max.
* **Capacitor Smoothing:** Output ripple voltage $V_{\text{ripple}} \approx \frac{I_{\text{load}}}{2 f C}$. Sensitive computing logic crashes if ripple exceeds $\pm 5\%$.
* **Mandatory for Battery Charging:** Any AC generation must be routed through a Rectifier before connecting to stationary battery blocks or battery racks.

### 4.3 Rotary Energy Bridge — 230 V AC to E (`voltcraft:converter_eu`)
> Code: `simulation/conversion/EuConverterLogic.java`, `block/conversion/EuConverterBlock.java`,
> TeamReborn Energy API (`EnergyStorage.SIDED`). Display name: **Rotary Energy Bridge (230V AC to E)**;
> registry/class IDs stay `converter_eu` / `EuConverter*` for save compat.
* **Strict AC gate (rear face only):** $207\text{--}253\text{ V}$ ($230\text{ V} \pm 10\%$), $f \ge 40.0\text{ Hz}$.
  DC ($f < 40\text{ Hz}$) → `DC REJECT`, $0\text{ E}$; $V < 207\text{ V}$ → brownout, $0\text{ E}$;
  $V > 253\text{ V}$ → surge trip latch until reset; thermal trip at $125^\circ\text{C}$.
* **Conversion:** $25\text{ W} \to 1\text{ E/tick}$ ($P/25.0$ E/t, fractional accumulator),
  buffer $10{,}000\text{ E}$, extraction $\le 512\text{ E/t}$ on non-input faces.
  Idle demand $= (\text{needed E} \times 25.0) + 5.0\text{ W}$ (needed capped at $128\text{ E/t}$).
* **Recipe** (`converter_eu.json`, pattern `GMG/TLT/IRI`): gold bus cable + MOSFET +
  laminated core + heavy copper cable + iron + redstone → 1x.

---

## 5. Converter Crafting Recipes

> Exact patterns from `src/main/generated/data/voltcraft/recipe/converter_*.json`,
> `transformer_*.json`, `rectifier_*.json`, `regulator_*.json`.
> Previous wiki entries (terracotta bases, comparator/steel-sheet variants, shapeless LDO) were stale.

### 5.1 Buck Step-Down Converter (`voltcraft:converter_dc_buck`)
```
Pattern ADA/MWE/ICI (A = aluminum, D = schottky, M = MOSFET, W = magnet wire,
E = capacitor, I = iron ingot, C = bare Cu):
[ Aluminum Ingot ] [ Schottky Diode     ] [ Aluminum Ingot ]
[ Power MOSFET   ] [ Magnet Wire        ] [ Capacitor      ]
[ Iron Ingot     ] [ Bare Copper Wire   ] [ Iron Ingot     ]
==> Yields: 1x Buck Step-Down Converter
```

### 5.2 Boost Step-Up Converter (`voltcraft:converter_dc_boost`)
```
Pattern AWA/MDE/ICI:
[ Aluminum Ingot ] [ Magnet Wire      ] [ Aluminum Ingot ]
[ Power MOSFET   ] [ Schottky Diode   ] [ Capacitor      ]
[ Iron Ingot     ] [ Bare Copper Wire ] [ Iron Ingot     ]
==> Yields: 1x Boost Step-Up Converter
```

### 5.3 Universal Buck-Boost / SEPIC Converter (`voltcraft:converter_dc_buck_boost`)
```
Pattern AWE/MBD/IWI (B = BMS board):
[ Aluminum Ingot ] [ Magnet Wire      ] [ Capacitor      ]
[ Power MOSFET   ] [ BMS Board        ] [ Schottky Diode ]
[ Iron Ingot     ] [ Magnet Wire      ] [ Iron Ingot     ]
==> Yields: 1x Universal Buck-Boost Converter
```

### 5.4 Linear LDO Voltage Regulator (`voltcraft:regulator_linear_ldo`)
```
Pattern AAA/RMR/CEC — SHAPED (not shapeless), 1x yield:
[ Aluminum Ingot   ] [ Aluminum Ingot   ] [ Aluminum Ingot   ]
[ Rubber Sheet     ] [ Power MOSFET     ] [ Rubber Sheet     ]
[ Bare Copper Wire ] [ Capacitor        ] [ Bare Copper Wire ]
==> Yields: 1x Linear LDO Regulator
```

### 5.5 AC Step-Down Transformer (`voltcraft:transformer_ac_step_down`)
```
Pattern ILI/WLC/TCT (I = iron ingot, L = laminated core, W = magnet wire,
C = bare Cu, T = terracotta):
[ Iron Ingot     ] [ Laminated Core   ] [ Iron Ingot     ]
[ Magnet Wire    ] [ Laminated Core   ] [ Bare Copper Wire ]
[ Iron Ingot     ] [ Terracotta       ] [ Iron Ingot     ]
==> Yields: 1x AC Step-Down Transformer (230V to 24V)
```

### 5.6 AC Step-Up Transformer (`voltcraft:transformer_ac_step_up`)
```
Pattern ILI/CLW/TCT (magnet wire and bare Cu swapped vs step-down):
[ Iron Ingot     ] [ Laminated Core   ] [ Iron Ingot     ]
[ Bare Copper Wire ] [ Laminated Core ] [ Magnet Wire    ]
[ Iron Ingot     ] [ Terracotta       ] [ Iron Ingot     ]
==> Yields: 1x AC Step-Up Transformer (24V to 230V)
```

### 5.7 Full-Wave Bridge Rectifier (`voltcraft:rectifier_bridge`)
```
Pattern RDR/DED/CDC (R = rubber, D = schottky diode, E = capacitor, C = bare Cu):
[ Rubber Sheet     ] [ Schottky Diode ] [ Rubber Sheet     ]
[ Schottky Diode   ] [ Capacitor      ] [ Schottky Diode   ]
[ Bare Copper Wire ] [ Schottky Diode ] [ Bare Copper Wire ]
==> Yields: 1x Bridge Rectifier
```

### 5.8 Active Synchronous Rectifier (`voltcraft:rectifier_active_synchronous`)
```
Pattern AMA/MBM/CEC (A = aluminum, M = MOSFET, B = BMS, C = bare Cu, E = capacitor):
[ Aluminum Ingot   ] [ Power MOSFET   ] [ Aluminum Ingot   ]
[ Power MOSFET     ] [ BMS Board      ] [ Power MOSFET     ]
[ Bare Copper Wire ] [ Capacitor      ] [ Bare Copper Wire ]
==> Yields: 1x Active Synchronous Rectifier
```

---

## 6. Port Geometry & Visual Terminal Identification (Input vs Output)

In real power engineering and in VoltCraft, power conversion units are strictly directional. To make system layout immediately intuitive without needing diagnostic tools:

```
          [ TOP FACE: Schematic Symbol & Vent Grille ]
                          ┌─────────────┐
                          │   TOP VENTS │
    BACK FACE             │   SCHEMATIC │             FRONT FACE
   (INPUT / PRI)          │             │            (OUTPUT / SEC)
  ┌─────────────┐   ┌─────┴─────────────┴─────┐   ┌─────────────┐
  │ █ BLUE BAR█ │   │                         │   │█ GREEN BAR █│
  │  [ IN ]     ├───┤   SIDE: HEATSINK FINS   ├───┤  [ OUT ]    │
  │ (O) (O) PINS│   │   NON-CONNECTABLE       │   │ (+) (-) LUGS│
  └─────────────┘   └─────┬─────────────┬─────┘   └─────────────┘
                          │ BOTTOM BASE │
                          └─────────────┘
```

1. **OUTPUT PORT (Front Face - `FACING`):**
   * **Color Code:** Bright **GREEN** border trim and active power LED indicator.
   * **Markings:** Prominent bold **`OUT`** silkscreen label with forward directional arrows.
   * **Terminals:** Red positive terminal lug (`+`) and black negative terminal lug (`-`) with hex screws.
   * **Behavior:** Directly connects to the secondary downstream `ElectricalGrid`. Delivers regulated voltage/current.

2. **INPUT PORT (Back Face - `FACING.getOpposite()`):**
   * **Color Code:** Vivid **BLUE** border trim and input status LED indicator.
   * **Markings:** Prominent bold **`IN`** silkscreen label.
   * **Terminals:** Heavy industrial 3-pin appliance socket receptacle with brass contact pins.
   * **Behavior:** Directly connects to the primary upstream `ElectricalGrid`. Sinks current to supply the conversion core.

3. **HEATSINK SIDES (Left & Right Faces):**
   * Heavy extruded aluminum cooling heatsink fins.
   * **Connection Rule:** Intentionally **NON-CONNECTABLE** (`canConnect` returns `false`). Prevents accidental circuit cross-shorts and simplifies adjacent busbar placement.

4. **TOP & BOTTOM FACES:**
   * **Top:** Ventilation mesh with equipment-specific schematic symbol (e.g. buck step-down arrow, transformer induction coils). Non-connectable.
   * **Bottom:** Vibration-damping base plate with rubber mounting feet. Non-connectable.

---

## 7. Graphical Interface & 32-bit High-Power Protocol

Right-clicking any power converter opens the expanded **$300 \times 208\text{ px}$ Control Dashboard**:

### 7.1 Telemetry Display
* **Input Column (88 px):** Live input voltage ($V$), drawn current ($A$), and instantaneous input power ($W$).
* **System Core Column (92 px):** Core temperature ($^\circ\text{C}$), dynamic conversion efficiency ($\eta$), harmonic distortion (THD), and physical port mapping.
* **Output Column (88 px):** Output EMF voltage ($V$), delivered current ($A$), and active delivered power ($W$).

### 7.2 32-bit Integer Packing (No Power Overflow)
Vanilla Minecraft `PropertyDelegate` synchronizes properties across the network using signed 16-bit `short` primitives (range $-32768 \dots 32767$). In high-power scenarios ($>3276.8\text{ W}$ when scaled by $\times 10$), standard delegates overflow into negative readings (e.g., $+3300\text{ W} \to -3253\text{ W}$).

VoltCraft resolves this by packing all current ($A$) and power ($W$) measurements into pairs of 16-bit words:
* `LOW = value & 0xFFFF`
* `HIGH = (value >> 16) & 0xFFFF`
* Reconstructed on client: `value = (HIGH << 16) | (LOW & 0xFFFF)`
This architecture safely supports multi-megawatt systems ($>2\times 10^8\text{ W}$) with zero overflow.

### 7.3 Topology-Specific Voltage Presets
To prevent accidental misconfiguration of AC inverters or transformers, the standard voltage preset buttons are strictly contextualized:
* **DC-DC Converters (Buck, Boost, Buck-Boost):** Display DC logic presets:
  `[5V]`, `[12V]`, `[24V]`, `[48V]`
* **AC Inverters & Pure Sine Converters:** Presets are omitted to prevent hazardous DC rail voltages on AC appliances; fine-step tuning buttons (`[-10V]`, `[-1V]`, `[+1V]`, `[+10V]`) and direct text entry are provided instead.
