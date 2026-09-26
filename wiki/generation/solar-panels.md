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
* **Weather Factors:**
  - Clear Sky: $1.0\times$ direct beam + diffuse irradiance.
  - Rain / Overcast: $0.20\text{--}0.35\times$ diffuse irradiance.
  - Thunderstorm: $0.05\text{--}0.12\times$ dark overcast.
  - Night: $0.0\text{ W/m}^2$.
* **Temperature Coefficient ($\gamma_T \approx -0.38\%/^\circ\text{C}$):** Panels produce higher output in cold biomes (Snowy Plains, Ice Spikes) and derate in hot environments (Desert, Badlands, Nether).

---

## 2. Solar Panel Technology Matrix

| Solar Panel Type | Block ID | $P_{\text{mp}}$ (STC) | $V_{\text{mp}}$ | $I_{\text{mp}}$ | $V_{\text{oc}}$ | $I_{\text{sc}}$ | Highlights |
|---|---|---|---|---|---|---|---|
| **Monocrystalline PERC** | `voltcraft:solar_panel_monocrystalline` | 400 W | 40.0 V | 10.0 A | 48.0 V | 10.8 A | High efficiency; bypass diode string protection against hot spots |
| **Polycrystalline** | `voltcraft:solar_panel_polycrystalline` | 300 W | 32.0 V | 9.38 A | 38.5 V | 10.2 A | Cost-effective mid-game solar generation |
| **Thin-Film CdTe** | `voltcraft:solar_panel_thin_film` | 250 W | 60.0 V | 4.17 A | 72.0 V | 4.6 A | Superior performance in overcast/diffuse lighting & high temperatures |
| **Concentrator CPV** | `voltcraft:solar_panel_concentrator` | 700 W | 50.0 V | 14.0 A | 62.0 V | 15.5 A | Multi-junction aerospace cell with Fresnel lens array; requires direct beam |

---

## 3. MPPT Charge Controller (`voltcraft:charge_controller_mppt`)

* **Input Port (Rear / South - Blue `[IN]`):** Connects to solar strings ($15\text{--}150\text{V}$ DC).
* **Output Port (Front / North - Green `[OUT]`):** Connects to battery bank ($12\text{V}$, $24\text{V}$, or $48\text{V}$ nominal).
* **Maximum Output Current:** $60\text{ A}$.
* **Conversion Efficiency:** $98\%$ synchronous buck conversion.
* **Tracking Algorithm:** Perturb & Observe (P&O) dynamically shifts terminal voltage to lock onto $(V_{\text{mp}}, I_{\text{mp}})$.
* **3-Stage Battery Charging State Machine:**
  1. **Bulk:** Constant current injection at maximum available solar power until absorption voltage threshold is reached.
  2. **Absorption:** Constant voltage saturation stage until battery current tapers below threshold.
  3. **Float:** Lower maintenance voltage to keep battery fully charged while avoiding electrolyte outgassing.

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
