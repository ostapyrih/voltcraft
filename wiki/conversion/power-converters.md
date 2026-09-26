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
* Uses laminated silicon steel cores to reduce hysteresis ($P_h \propto f \cdot B_{\text{max}}^{1.6}$) and eddy current losses.
* Strictly requires alternating magnetic flux ($f > 0$). Applying continuous DC causes zero back-EMF, resulting in near dead-short conditions and instantaneous breaker trip.

### 4.2 AC-DC Rectifiers & Filtering
* **Bridge Rectifier:** 4-diode Graetz bridge with $1.4\text{V}$ forward drop.
* **Active Synchronous Rectifier:** MOSFET-based low-$R_{\text{DS(on)}}$ rectification ($>98.5\%$ efficiency).
* **Capacitor Smoothing:** Output ripple voltage $V_{\text{ripple}} \approx \frac{I_{\text{load}}}{2 f C}$. Sensitive computing logic crashes if ripple exceeds $\pm 5\%$.
* **Mandatory for Battery Charging:** Any AC generation must be routed through a Rectifier before connecting to stationary battery blocks or battery racks.

---

## 5. Converter Crafting Recipes

### 5.1 Buck Step-Down Converter (`voltcraft:converter_dc_buck`)
```
[ Iron Ingot         ] [ Copper Magnet Wire ] [ Iron Ingot         ]
[ Power MOSFET       ] [ Filter Capacitor   ] [ Schottky Diode     ]
[ Bare Copper Wire   ] [ Terracotta         ] [ Bare Copper Wire   ]
==> Yields: 1x Buck Step-Down Converter
```

### 5.2 Boost Step-Up Converter (`voltcraft:converter_dc_boost`)
```
[ Iron Ingot         ] [ Schottky Diode     ] [ Iron Ingot         ]
[ Copper Magnet Wire ] [ Filter Capacitor   ] [ Power MOSFET       ]
[ Bare Copper Wire   ] [ Terracotta         ] [ Bare Copper Wire   ]
==> Yields: 1x Boost Step-Up Converter
```

### 5.3 Universal Buck-Boost / SEPIC Converter (`voltcraft:converter_dc_buck_boost`)
```
[ Aluminum Ingot         ] [ Filter Capacitor   ] [ Aluminum Ingot         ]
[ Power MOSFET           ] [ Copper Magnet Wire ] [ Power MOSFET           ]
[ Insulated Copper Cable ] [ BMS / Control PCB  ] [ Insulated Copper Cable ]
==> Yields: 1x Universal Buck-Boost Converter
```

### 5.4 Linear LDO Voltage Regulator (`voltcraft:regulator_linear_ldo`)
Crafting Table (Shapeless):
```
[ Power MOSFET ] + [ Copper Nugget ] + [ Aluminum Ingot (Heatsink) ]
==> Yields: 2x Linear LDO Regulator
```

### 5.5 AC Step-Down Transformer (`voltcraft:transformer_ac_step_down`)
```
[ Iron Ingot             ] [ Transformer Core   ] [ Iron Ingot             ]
[ Copper Magnet Wire     ] [ Transformer Core   ] [ Bare Copper Wire       ]
[ Iron Ingot             ] [ Insulated Cable    ] [ Iron Ingot             ]
==> Yields: 1x AC Step-Down Transformer
```

### 5.6 AC Step-Up Transformer (`voltcraft:transformer_ac_step_up`)
```
[ Iron Ingot             ] [ Transformer Core   ] [ Iron Ingot             ]
[ Bare Copper Wire       ] [ Transformer Core   ] [ Copper Magnet Wire     ]
[ Iron Ingot             ] [ Insulated Cable    ] [ Iron Ingot             ]
==> Yields: 1x AC Step-Up Transformer
```

### 5.7 Full-Wave Bridge Rectifier (`voltcraft:rectifier_bridge`)
```
[ Schottky Diode     ] [ Filter Capacitor   ] [ Schottky Diode     ]
[ Bare Copper Wire   ] [ Terracotta         ] [ Bare Copper Wire   ]
[ Schottky Diode     ] [ Filter Capacitor   ] [ Schottky Diode     ]
==> Yields: 1x Bridge Rectifier
```

### 5.8 Active Synchronous Rectifier (`voltcraft:rectifier_active_synchronous`)
```
[ Power MOSFET           ] [ Filter Capacitor   ] [ Power MOSFET           ]
[ BMS / Control PCB      ] [ Heat Sink / Alum   ] [ BMS / Control PCB      ]
[ Power MOSFET           ] [ Filter Capacitor   ] [ Power MOSFET           ]
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
