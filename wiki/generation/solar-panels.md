# Solar Panels & Photovoltaic Generation

Part of the [[../core-idea|VoltCraft Core Idea & Architecture]] specification.

---

## 1. Photovoltaic Physics & Electrical Characteristics

A solar panel converts radiant electromagnetic energy (photons) directly into electrical current via the photovoltaic effect in semiconductor p-n junctions.

### 1.1 Non-Linear I-V and P-V Curves
$$I(V) = I_{\text{ph}} - I_0 \left[ \exp\left( \frac{q (V + I \cdot R_s)}{n \cdot k_B \cdot T} \right) - 1 \right] - \frac{V + I \cdot R_s}{R_{\text{sh}}}$$
* **Short-Circuit Current ($I_{\text{sc}}$):** Maximum current when output terminals are shorted ($V = 0$). Directly proportional to irradiance $G$.
* **Open-Circuit Voltage ($V_{\text{oc}}$):** Maximum terminal voltage under zero load ($I = 0$).
* **Maximum Power Point (MPP):** $(V_{\text{mp}}, I_{\text{mp}})$ where $P = V \cdot I$ is maximized.

### 1.2 Environmental Irradiance Model
Requires an unobstructed sky view above (`world.isSkyVisible(pos.up())`):
* **Celestial Zenith Angle:** Computed from `world.getTimeOfDay()`. Solar noon occurs at tick 6,000 where $\cos(\theta_{\text{zenith}}) = 1.0$.
* **Air Mass Beam Attenuation:** Direct sunlight intensity scales realistically as the sun traverses the horizon.
* **Weather Factors (exact, `SolarPanelType.getWeatherFactor`):**
  - Clear Sky: $1.0\times$.
  - Rain: Thin-Film CdTe $0.40\times$, all others $0.25\times$.
  - Thunderstorm: Thin-Film CdTe $0.20\times$, all others $0.10\times$.
  - Concentrator CPV: **$0.0\times$** in any rain or thunder (direct-beam only).
  - Night: $0.0\text{ W/m}^2$.
* **Temperature Coefficients (exact per type):** Mono PERC $-0.35\%/^\circ\text{C}$,
  Poly $-0.39\%/^\circ\text{C}$, Thin-Film $-0.25\%/^\circ\text{C}$, CPV $-0.15\%/^\circ\text{C}$.

---

## 2. Solar Panel Technology Matrix

| Solar Panel Type | Block ID | $P_{\text{mp}}$ (STC) | $V_{\text{mp}}$ | $I_{\text{mp}}$ | $V_{\text{oc}}$ | $I_{\text{sc}}$ | $\eta$ / $\gamma_T$ | Highlights |
|---|---|---|---|---|---|---|---|---|
| **Monocrystalline PERC** | `voltcraft:solar_panel_monocrystalline` | 400 W | 40.0 V | 10.0 A | 48.0 V | 10.8 A | 21.5% / $-0.35\%/^\circ\text{C}$ | High efficiency; bypass diode string protection against hot spots |
| **Polycrystalline** | `voltcraft:solar_panel_polycrystalline` | 300 W | 32.0 V | 9.38 A | 38.5 V | 10.1 A | 17.0% / $-0.39\%/^\circ\text{C}$ | Cost-effective mid-game solar generation |
| **Thin-Film CdTe** | `voltcraft:solar_panel_thin_film` | 250 W | 70.0 V | 3.57 A | 88.0 V | 4.0 A | 15.0% / $-0.25\%/^\circ\text{C}$ | Superior performance in overcast/diffuse lighting & high temperatures |
| **Concentrator CPV** | `voltcraft:solar_panel_concentrator` | 700 W | 50.0 V | 14.0 A | 60.0 V | 15.0 A | 38.0% / $-0.15\%/^\circ\text{C}$ | Multi-junction aerospace cell with Fresnel lens array; requires direct beam (0 output in rain/thunder) |

> Source: `simulation/generation/SolarPanelType.java`. Previous wiki values for Thin-Film
> ($60\text{ V}/4.17\text{ A}/72\text{ V}/4.6\text{ A}$), Poly $I_{\text{sc}} = 10.2\text{ A}$ and
> CPV $V_{\text{oc}}/I_{\text{sc}} = 62\text{ V}/15.5\text{ A}$ were stale and have been corrected.

---

## 3. MPPT Charge Controller (`voltcraft:charge_controller_mppt`)

* **Input Port (Rear / South - Blue `[IN]`):** Connects to solar strings ($15\text{--}150\text{V}$ DC).
* **Output Port (Front / North - Green `[OUT]`):** Connects to battery bank ($12\text{V}$, $24\text{V}$, or $48\text{V}$ nominal).
* **Maximum Output Current:** $60\text{ A}$.
* **Conversion Efficiency:** $98\%$ synchronous buck conversion.
* **Tracking Algorithm:** Perturb & Observe (P&O) in `MPPTLogic`: $0.2\text{ V}$ steps,
  target window $10\text{--}150\text{ V}$, $0.05\text{ W}$ noise deadband, rail-settled gating
  (servo band $+0.5\text{ V}$, demand deadband $\max(2\text{ W}, 5\%)$).
* **3-Stage Battery Charging State Machine (exact thresholds):**
  1. **Bulk:** Constant current injection at maximum available solar power until absorption voltage threshold is reached.
  2. **Absorption:** $14.4\text{ V}$ per 12 V bank ($28.8\text{ V}$ @ 24 V, $57.6\text{ V}$ @ 48 V) until current tapers $< 0.2\text{ A}$ or $1200$-tick timeout.
  3. **Float:** $13.6\text{ V}$ per 12 V bank ($27.2\text{ V}$ @ 24 V, $54.4\text{ V}$ @ 48 V); returns to Bulk if rail sags $> 1.0\text{ V}$ below float.
* **Bank presets & protection:** `[12V Bank]` / `[24V Bank]` / `[48V Bank]` (snap $\le 15\text{ V} \to 12$, $\le 30\text{ V} \to 24$, else $48$).
  Nominal-mismatch ($> 18\text{ V}$ on 12 V bank, outside $18\text{--}36\text{ V}$ on 24 V, $< 36\text{ V}$ on 48 V)
  or live-rail mismatch trips output to $0\text{ A}$. Zero solar ($0\text{ W}$ / $V_{\text{in}} \le 1\text{ V}$)
  yields strictly $0.0\text{ A}$ — no phantom current. Controller housekeeping draw $2\text{ W}$ in Float.

---

## 4. Crafting Recipes

### 4.1 Monocrystalline PERC Solar Panel
```
[ Glass Pane           ] [ Glass Pane           ] [ Glass Pane           ]
[ Photovoltaic Cell    ] [ Photovoltaic Cell    ] [ Photovoltaic Cell    ]
[ Aluminum Ingot       ] [ Bare Copper Wire     ] [ Aluminum Ingot       ]
==> Yields: 1x Monocrystalline PERC Solar Panel (400W)
```

### 4.2 Polycrystalline Solar Panel
```
[ Glass Pane           ] [ Glass Pane           ] [ Glass Pane           ]
[ Photovoltaic Cell    ] [ Photovoltaic Cell    ] [ Photovoltaic Cell    ]
[ Iron Ingot           ] [ Bare Copper Wire     ] [ Iron Ingot           ]
==> Yields: 1x Polycrystalline Solar Panel (300W)
```

### 4.3 Thin-Film CdTe Solar Panel
```
[ Glass Pane           ] [ Glass Pane           ] [ Glass Pane           ]
[ High-Purity Silica   ] [ Lead Ingot           ] [ High-Purity Silica   ]
[ Rubber Sheet         ] [ Bare Copper Wire     ] [ Rubber Sheet         ]
==> Yields: 1x Thin-Film CdTe Solar Panel (250W)
```

### 4.4 Concentrated Photovoltaic Panel (CPV)
```
[ Quartz               ] [ Glowstone Dust       ] [ Quartz               ]
[ Photovoltaic Cell    ] [ Photovoltaic Cell    ] [ Photovoltaic Cell    ]
[ Aluminum Ingot       ] [ Heavy Copper Cable   ] [ Aluminum Ingot       ]
==> Yields: 1x Concentrated Photovoltaic Panel (700W CPV)
```

### 4.5 MPPT Solar Charge Controller
```
[ Aluminum Ingot       ] [ Power MOSFET         ] [ Aluminum Ingot       ]
[ Copper Magnet Wire   ] [ BMS Logic Board      ] [ Schottky Diode       ]
[ Iron Ingot           ] [ Bare Copper Wire     ] [ Iron Ingot           ]
==> Yields: 1x MPPT Solar Charge Controller (60A)
```
