# Fuel Generators, Dynamos & Renewable Generation

Part of the [[../core-idea|VoltCraft Core Idea & Architecture]] specification.

---

## 1. Mechanical Generation Overview

Dynamos and portable generators convert mechanical rotation into clean electrical power via electromagnetic induction. They provide dependable on-demand power for off-grid operations, emergency battery resuscitation, and field workshops.

---

## 2. Generator Technology Matrix

### 2.1 Hand-Crank DC Dynamo (`voltcraft:generator_hand_crank`)
* **Output:** $12\text{V}$ DC nominal (up to $13.8\text{ V}$ open-circuit at full flywheel),
  $100\text{ W}$ max ($8.33\text{ A}$), $R_{\text{int}} = 0.15\,\Omega$, $f = 0\text{ Hz}$.
* **Flywheel (exact, `HandCrankGeneratorBlockEntity`):** each right-click adds $+0.35$ speed
  (clamped $[0, 1]$); EMF $= \text{speed} \times 13.8\text{ V}$; decay $\times 0.97$/tick
  ($\sim 2$ s spindown). State `NOMINAL` only while speed $> 0.05$.
* **Back-EMF Counter Torque:** $\text{speed} \leftarrow \text{speed} - (P/100) \times 0.05$ on every
  `onPowerDrawn` — heavy loads stall the wheel faster.
* **Human Exhaustion:** `player.addExhaustion(0.3F)` per crank (code-confirmed in `HandCrankGeneratorBlock`).
  No ratchet-click sound exists in code (spec text only).
* **Crafting Recipe:**
  ```
  [                      ] [ Lever                ] [                      ]
  [ Iron Ingot           ] [ Copper Magnet Wire   ] [ Iron Ingot           ]
  [ Iron Ingot           ] [ Bare Copper Wire     ] [ Iron Ingot           ]
  ==> Yields: 1x Hand-Crank DC Dynamo (100W)
  ```

---

### 2.2 Portable Inverter Generator (`voltcraft:generator_portable_inverter`)
* **Continuous Rated Output:** $1800\text{ W}$ ($1.8\text{ kW}$).
* **Surge / Peak Output:** $2200\text{ W}$ ($2.2\text{ kW}$, $\approx 9.56\text{ A}$ current limit).
* **Output Characteristics (Front socket only — `canConnect` = facing face):** Single-phase $230\text{V}$ AC at $50\text{Hz}$ pure sine, $R_{\text{int}} = 0.15\,\Omega$.
* **Fuel Ingestion (exact, `PortableGeneratorBlock`):** accepts **any** `FuelRegistry` fuel
  (vanilla + modded) at its registry burn ticks (`fuelTicks / 20` = displayed seconds, lava bucket
  returns its remainder). Examples at vanilla defaults: coal/charcoal $\approx 1600$ ticks ($80$ s),
  blaze rod $\approx 2400$ ticks, lava bucket $\approx 20000$ ticks — these are registry values, not
  hardcoded constants.
* **Smart Eco-Throttle Engine Management:**
  - Burns at only $0.25\times$ idle rate when unloaded ($P_{\text{load}} \approx 0$).
  - Dynamically throttles up linearly: $\text{burnRate} = 0.25 + 0.75 \times (P_{\text{load}} / 1800\text{ W})$.
* **Acoustics & Visual Smoke:**
  - Plays authentic engine rumble and combustion crackle sounds when running.
  - Emits smoke particles from the side exhaust muffler.
* **Crafting Recipe:**
  ```
  [ Iron Ingot           ] [ Furnace              ] [ Iron Ingot           ]
  [ Iron Ingot           ] [ Pure Sine Inverter   ] [ Iron Ingot           ]
  [ Iron Ingot           ] [ Insulated Cable      ] [ Iron Ingot           ]
  ==> Yields: 1x Portable Inverter Generator (1.8kW)
  ```

---

## 3. Port Orientation & Terminal Guide

All directional generation blocks strictly follow the VoltCraft standard port identification:
* **Blue `[IN]` Port:** Upstream electrical intake (e.g. PV string into MPPT controller). Marked with bold Blue trim and recessed socket pins.
* **Green `[OUT]` Port:** Regulated power delivery (e.g. MPPT charging batteries, Generator supplying AC loads). Marked with bold Green trim and terminal posts.
