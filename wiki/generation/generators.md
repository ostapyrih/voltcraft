# Fuel Generators, Dynamos & Renewable Generation

Part of the [[../core-idea|VoltCraft Core Idea & Architecture]] specification.

---

## 1. Mechanical Generation Overview

Dynamos and portable generators convert mechanical rotation into clean electrical power via electromagnetic induction. They provide dependable on-demand power for off-grid operations, emergency battery resuscitation, and field workshops.

---

## 2. Generator Technology Matrix

### 2.1 Hand-Crank DC Dynamo (`voltcraft:generator_hand_crank`)
* **Output Port (Front - Green `[OUT]`):** Delivers $12\text{V}$ DC up to $100\text{ W}$ ($8.33\text{ A}$ max).
* **Flywheel Kinetic Inertia:** Right-clicking the crank spins the internal flywheel. Speed decays realistically over time ($v_{t+1} = v_t \times 0.94$).
* **Back-EMF Counter Torque:** Delivering power under heavy electrical load increases mechanical resistance, slowing the flywheel down faster ($v \leftarrow v - \Delta t \cdot (P_{\text{load}} / 250\text{ W})$).
* **Human Exhaustion:** Cranking applies physical exertion (`player.addExhaustion(0.3F)`), consuming hunger bars realistically.
* **Audio & Haptics:** Emits mechanical ratchet clicking sounds while spinning.
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
* **Surge / Peak Output:** $2200\text{ W}$ ($2.2\text{ kW}$).
* **Output Characteristics (Front - Green `[OUT]`):** Single-phase $230\text{V}$ AC at $50\text{Hz}$ pure sine wave ($\text{THD} < 2.5\%$).
* **Vanilla Furnace Fuel Ingestion:** Right-click with any vanilla furnace fuel to fuel the generator:
  - Coal / Charcoal (1600 burn ticks / 80 seconds)
  - Wood logs / Planks / Sticks
  - Blaze Rods (2400 burn ticks / 120 seconds)
  - Lava Bucket (20,000 burn ticks / 1000 seconds; returns empty bucket)
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
