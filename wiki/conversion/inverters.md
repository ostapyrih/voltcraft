# Inverters & DC-AC Conversion

Part of the [[../core-idea|VoltCraft Core Idea & Architecture]] specification.

---

## 1. Principles of Power Inversion

An **Inverter** converts Direct Current (DC) from batteries or solar arrays into Alternating Current (AC) for distribution or driving AC machinery.

### 1.1 AC Phasor Model & Equations
$$V(t) = V_{\text{peak}} \cdot \sin(2\pi f t + \phi) \implies V_{\text{RMS}} = \frac{V_{\text{peak}}}{\sqrt{2}}$$
In the server tick model ($20\text{ ticks/sec}$), AC network solving uses phasor representation:
$$\tilde{V} = V_{\text{RMS}} \angle \theta \qquad \tilde{I} = I_{\text{RMS}} \angle (\theta - \phi)$$
* **Real, Reactive & Apparent Power:**
  $$S = V_{\text{RMS}} \cdot I_{\text{RMS}} \text{ [VA]} \qquad P = S \cdot \cos(\phi) \text{ [W]} \qquad Q = S \cdot \sin(\phi) \text{ [VAR]}$$

---

## 2. Inverter Topologies & Waveform Quality

> **Code source:** `simulation/conversion/InverterType.java` — exact THD / efficiency / power / voltage.
> All five output $230\text{ V}$ nominal. DC input modes with UVLO/OVP are implemented in
> `InverterBlockEntity` (12 V in: UVLO 10 V / OVP 16.5 V; 24 V in: 20 V / 33 V; 48 V in: 40 V / 66 V).

| Inverter (code) | Block ID | THD | $\eta$ | Max power | Grid-tie | ATS |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| Square Wave | `voltcraft:inverter_square_wave` | $48.0\%$ | $0.90$ | $1500\text{ W}$ | no | no |
| Modified Sine | `voltcraft:inverter_modified_sine` | $28.0\%$ | $0.92$ | $3000\text{ W}$ | no | no |
| Pure Sine SPWM | `voltcraft:inverter_pure_sine` | $2.5\%$ | $0.96$ | $5000\text{ W}$ | no | no |
| Synchronous Grid-Tie | `voltcraft:inverter_grid_tie` | $2.0\%$ | $0.97$ | $6000\text{ W}$ | **yes** (PLL + 2-tick anti-islanding) | no |
| Hybrid ESS Multi-Mode | `voltcraft:inverter_hybrid_ess` | $2.0\%$ | $0.96$ | $8000\text{ W}$ | **yes** | **yes** ($<10\text{ ms}$ ATS) |

$$\text{THD} = \frac{\sqrt{\sum_{n=2}^\infty V_n^2}}{V_1}$$

```
Square Wave                  Modified Sine Wave           Pure Sine Wave (SPWM)
      +V ┌──────┐                  +V ┌────┐                    +V      _.-''''-._
         │      │                     │    │                          .'          '.
      ───┼──────┼──────      ───┼────┘    └───┼───       ───┼─────/──────────────\──
         │      │                             │  │            │    /                \
      -V └──────┘                  -V         └──┘         -V └───'                  '-
   (THD ~ 48%)                  (THD ~ 25-30%)                 (THD < 3%)
```

1. **Square Wave (Tier 1):** Simple push-pull H-bridge ($\text{THD} \approx 48\%$). Inexpensive early game; causes buzzing, heavy motor overheating, and destroys microcontrollers.
2. **Modified Sine (Tier 2):** 3-level stepped waveform ($\text{THD} \approx 25\text{--}30\%$). Powers resistive heaters and universal motors safely.
3. **Pure Sine Wave SPWM (Tier 3):** High-frequency SPWM with LC filter ($\text{THD} < 3\%$). Smooth clean sinusoidal wave safe for computers, medical tools, and precision factory machines. Peak efficiency $95\text{--}98\%$.

---

## 3. Operating Modes: Off-Grid, Grid-Tie & Hybrid

| Mode | Function | Synchronization | Anti-Islanding Protection | Application |
| :--- | :--- | :--- | :--- | :--- |
| **Off-Grid (Stand-Alone)** | Voltage Source Generator ($230\text{V} / 50\text{Hz}$) | Master internal clock | Not applicable | Remote outposts and islanded microgrids. |
| **Grid-Tie (Interactive)** | Current Source injecting power into live AC grid | Phase-Locked Loop (PLL) | **Mandatory:** Shuts down in 2 ticks if grid collapses | Rooftop solar grid feed-in without islanding danger. |
| **Hybrid (Multi-Mode / ESS)** | Bi-directional grid-tie + battery backup | Integrated $<10\text{ ms}$ ATS | Automatic transfer disconnect | Mission-critical UPS for computers and reactor loops. |

---

## 4. Inverter Hardware Crafting Recipes

> Exact patterns from `VoltcraftRecipeGenerator` / `src/main/generated/data/voltcraft/recipe/inverter_*.json`.
> Previous wiki patterns (transformer-core/redstone-dust, comparator, steel-sheet variants) were stale.

### 4.1 Square Wave Inverter (`voltcraft:inverter_square_wave`)
```
Pattern IMI/LWE/CRN (I = iron ingot, M = MOSFET, L = laminated core,
W = magnet wire, E = capacitor, C = bare Cu, R = repeater, N = insulated Cu):
[ Iron Ingot ] [ Power MOSFET ] [ Iron Ingot ]
[ Laminated Core ] [ Magnet Wire ] [ Capacitor ]
[ Bare Copper Wire ] [ Repeater ] [ Insulated Copper Cable ]
==> Yields: 1x Square Wave Inverter (1500W)
```

### 4.2 Modified Sine Wave Inverter (`voltcraft:inverter_modified_sine`)
```
Pattern AMA/MLE/CBN (A = aluminum, M = MOSFET, L = core, E = capacitor,
C = bare Cu, B = BMS, N = insulated Cu):
[ Aluminum Ingot ] [ Power MOSFET ] [ Aluminum Ingot ]
[ Power MOSFET ] [ Laminated Core ] [ Capacitor ]
[ Bare Copper Wire ] [ BMS Board ] [ Insulated Copper Cable ]
==> Yields: 1x Modified Sine Wave Inverter (3000W)
```

### 4.3 Pure Sine Wave SPWM Inverter (`voltcraft:inverter_pure_sine`)
```
Pattern AMA/BLW/HEN (A = aluminum, M = MOSFET, B = BMS, L = core,
W = magnet wire, H = heavy Cu, E = capacitor, N = insulated Cu):
[ Aluminum Ingot ] [ Power MOSFET ] [ Aluminum Ingot ]
[ BMS Board ] [ Laminated Core ] [ Magnet Wire ]
[ Heavy Copper Cable ] [ Capacitor ] [ Insulated Copper Cable ]
==> Yields: 1x Pure Sine Wave Inverter (5000W)
```

### 4.4 Synchronous Grid-Tie Inverter (`voltcraft:inverter_grid_tie`)
```
Pattern SMS/BLR/HEN (S = silver ingot, M = MOSFET, B = BMS, L = core,
R = contactor relay, H = heavy Cu, E = capacitor, N = insulated Cu):
[ Silver Ingot ] [ Power MOSFET ] [ Silver Ingot ]
[ BMS Board ] [ Laminated Core ] [ Contactor Relay ]
[ Heavy Copper Cable ] [ Capacitor ] [ Insulated Copper Cable ]
==> Yields: 1x Grid-Tie Inverter (6000W)
```

### 4.5 Hybrid ESS Multi-Mode Inverter (`voltcraft:inverter_hybrid_ess`)
```
Pattern GMG/BRB/HLN (G = gold ingot, M = MOSFET, B = BMS, R = contactor,
H = heavy Cu, L = core, N = insulated Cu):
[ Gold Ingot ] [ Power MOSFET ] [ Gold Ingot ]
[ BMS Board ] [ Contactor Relay ] [ BMS Board ]
[ Heavy Copper Cable ] [ Laminated Core ] [ Insulated Copper Cable ]
==> Yields: 1x Hybrid ESS Inverter (8000W)
```

---

## 5. Visual Port Identification: DC Input vs AC Output

All inverters inherit from `AbstractPowerConverterBlock` and feature high-contrast directional visual indicators:

1. **AC Output Port (Front Face):**
   * **Visual:** Bright **GREEN** border trim, active power LED, and bold silkscreen **`OUT`** indicator.
   * **Terminals:** Screw-down terminal posts with red positive/live (`+`/`L`) and black neutral (`-`/`N`) terminals.
   * **Connection:** Connects to the AC distribution subgrid.

2. **DC Input Port (Back Face):**
   * **Visual:** High-visibility **BLUE** border trim, input DC sensing LED, and bold silkscreen **`IN`** indicator.
   * **Terminals:** Heavy industrial socket receptacle with brass inlet prongs.
   * **Connection:** Connects to upstream DC battery storage banks or photovoltaic array feeders.

3. **Sides & Top:**
   * **Sides:** High-surface-area heatsink fins for power transistor thermal dissipation (non-connectable).
   * **Top:** Ventilation mesh with sinusoidal waveform symbol `~` representing AC conversion tier.
