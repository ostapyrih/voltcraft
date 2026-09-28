# Batteries & Electrochemical Energy Storage

Part of the [[../core-idea|VoltCraft Core Idea & Architecture]] specification.

---

## 1. Electrochemical Simulation Model

Batteries in VoltCraft are dynamic chemical cells, **not** abstract energy buffers. The system simulates realistic electrochemical behaviors:

### 1.1 State of Charge (SoC) & Open-Circuit Voltage (OCV)
The State of Charge $\text{SoC} \in [0.0, 1.0]$ represents the fractional chemical potential remaining:
$$\text{SoC}(t) = \frac{Q(t)}{Q_{\text{nominal}}}$$
Terminal voltage under open-circuit condition is determined by a non-linear chemical OCV curve:
$$V_{\text{ocv}} = f_{\text{chem}}(\text{SoC})$$
For example, a Lithium-Ion cell sits at $\sim 4.2\text{ V}$ at $100\%$ SoC, remains on a flat plateau around $3.7\text{ V}$ through $80\text{--}20\%$ SoC, and plunges steeply below $3.0\text{ V}$ as it approaches $0\%$.

### 1.2 Internal Resistance & Terminal Voltage Under Load
Every real cell possesses internal resistance $R_{\text{int}}$, which dynamically varies with SoC, temperature $T$, and cycle age $C$:
$$R_{\text{int}} = R_0 \cdot g(\text{SoC}) \cdot \exp\left(\frac{E_a}{k_B T}\right) \cdot \left(1 + \beta_{\text{aging}} \cdot C\right)$$
Under discharge current $I_{\text{dis}}$:
$$V_{\text{terminal}} = V_{\text{ocv}} - I_{\text{dis}} \cdot R_{\text{int}}$$
Under charging current $I_{\text{chg}}$:
$$V_{\text{terminal}} = V_{\text{ocv}} + I_{\text{chg}} \cdot R_{\text{int}}$$
Notice that pulling high current causes significant **voltage sag**, dropping terminal voltage below machine brownout limits even when capacity remains.
> **Design rule — DCIR must scale with cell size:** base resistance $R_0$ is the per-cell DC internal resistance of one physical cell, so a $120\text{ Ah}$ SLA cell sits near $1.5\text{ m}\Omega$ (roughly $9\text{ m}\Omega$ per $12\text{ V}$ monobloc), a $100\text{ Ah}$ $LiFePO_4$ prismatic near $0.6\text{ m}\Omega$, and a $60\text{ Ah}$ LTO cell near $0.8\text{ m}\Omega$. Copying a small-cell value (e.g. $20\text{ m}\Omega$ from a $3\text{ Ah}$ 18650) onto a traction cell sags volts under normal loads and strands most of the nameplate capacity behind inverter UVLO (a $6\text{S}$ lead-acid block then delivered only $\sim 30\text{ Ah}$ of $120\text{ Ah}$).

### 1.3 C-Rating & Current Limits
A battery's capacity $Q$ is measured in Ampere-hours ($\text{Ah}$) or milliampere-hours ($\text{mAh}$).
The discharge rate is expressed in multiples of capacity $C$:
$$I_{\text{rated}} = C_{\text{rate}} \cdot Q_{\text{nominal}}$$
* $1C$ discharge completely drains the cell in 1 hour ($3600\text{ s} = 72{,}000\text{ ticks}$).
* Discharging above rated $C_{\text{rate}}$ causes extreme $I^2 R_{\text{int}}$ Joule dissipation within the cell electrolyte.

### 1.4 Thermal Dissipation & Thermal Runaway
Heat generated inside the battery core:
$$P_{\text{heat}} = I^2 \cdot R_{\text{int}} + I \cdot T \cdot \frac{\partial V_{\text{ocv}}}{\partial T} \quad \text{(Joule + Entropic Heat)}$$
If heat accumulation drives internal cell temperature above $T_{\text{runaway}}$:
1. Exothermic decomposition of the electrolyte begins.
2. Pressure rises, rupturing safety vent discs with toxic gas emission.
3. Rapid oxygen release fuels explosive deflagration (`Explosion.DestructionType.DESTROY_WITH_DECAY`) accompanied by long-lasting chemical fire.

---

## 2. Battery Chemistry Matrix

> **Code source:** `simulation/chemistry/BatteryChemistry.java` — exact implemented values.
> Format: nominal / cutoff–full (V), capacity, max C-rate (max A), $R_{\text{int}}$, rechargeable,
> runaway $T$, cycle life. Chemistries listed as design-only have **no enum entry**.

### 2.1 Primary Cells (Non-Rechargeable / Single-Use)
> [!CAUTION]
> Attempting to push reverse current into a primary cell forces gas evolution ($H_2 / O_2$) and dendrite formation, triggering casing rupture and corrosive chemical splattering.

| Chemistry (code) | Nominal / Window (V) | Capacity | Max rate | $R_{\text{int}}$ | Runaway | Cycles | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Zinc-Carbon** (`ZINC_CARBON`) | $1.5$ / $0.9\text{--}1.55$ | $800\text{ mAh}$ | $0.5C$ ($0.4\text{ A}$) | $0.350\,\Omega$ | $60^\circ\text{C}$ | 1 | Early-tier primitive cell. |
| **Alkaline ($Zn\text{-}MnO_2$)** (`ALKALINE`) | $1.5$ / $0.8\text{--}1.6$ | $2000\text{ mAh}$ | $1.0C$ ($2.0\text{ A}$) | $0.150\,\Omega$ | $85^\circ\text{C}$ | 1 | Standard single-use cell. |
| **Lithium-Thionyl Chloride ($Li\text{-}SOCl_2$)** (`LITHIUM_THIONYL`) | $3.6$ / $3.0\text{--}3.7$ | $1500\text{ mAh}$ | $0.5C$ ($0.75\text{ A}$) | $5.0\,\Omega$ | $120^\circ\text{C}$ | 1 | 15+ yr shelf life; toxic $SO_2$ if burned. |
| **CR2032 ($Li\text{-}MnO_2$)** (`COIN_CR2032`) | $3.0$ / $2.0\text{--}3.3$ | $220\text{ mAh}$ | $0.2C$ ($0.044\text{ A}$) | $10.0\,\Omega$ | $70^\circ\text{C}$ | 1 | Coin cell for RTC/micro-sensors. |
| Silver-Oxide ($Ag_2O$) | — | — | — | — | — | — | **Design-only, not in code.** |
| Mercury Cell | — | — | — | — | — | — | **Design-only, not in code.** |

---

### 2.2 Secondary Cells (Rechargeable)

| Chemistry (code) | Nominal / Window (V) | Capacity (per cell) | Max rate | $R_{\text{int}}$ | Runaway | Cycles (code) | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Lead-Acid (SLA / AGM)** (`LEAD_ACID`) | $2.0$ / $1.75\text{--}2.40$ | $120{,}000\text{ mAh}$ ($120\text{ Ah}$) | $1.5C$ ($180\text{ A}$) | $0.0015\,\Omega$ | $70^\circ\text{C}$ vents $H_2$ | 500 | 6S = 12 V block. Heavy; sulfation if left flat (spec). |
| **Nickel-Cadmium (NiCd)** (`NICD`) | $1.2$ / $0.9\text{--}1.45$ | $1200\text{ mAh}$ | $5.0C$ ($6.0\text{ A}$) | $0.020\,\Omega$ | $80^\circ\text{C}$ | 1000 | $-40^\circ\text{C}$ resilient; memory effect (spec). |
| **Nickel-Metal Hydride (NiMH)** (`NIMH`) | $1.2$ / $1.0\text{--}1.42$ | $2500\text{ mAh}$ | $3.0C$ ($7.5\text{ A}$) | $0.030\,\Omega$ | $65^\circ\text{C}$ | 800 | No memory effect. |
| **Lithium-Ion 18650** (`LI_ION_18650`) | $3.7$ / $2.8\text{--}4.2$ | $3000\text{ mAh}$ | $5.0C$ ($15\text{ A}$) | $0.025\,\Omega$ | $150^\circ\text{C}$ | 1000 | Violent vent past $4.3\text{ V}$ / puncture (spec). |
| **Lithium-Ion 21700** (`LI_ION_21700`) | $3.7$ / $2.7\text{--}4.2$ | $5000\text{ mAh}$ | $10.0C$ ($50\text{ A}$) | $0.015\,\Omega$ | $150^\circ\text{C}$ | 1200 | High-drain NMC. |
| **Lithium Iron Phosphate ($LiFePO_4$)** (`LIFEPO4`) | $3.2$ / $2.50\text{--}3.65$ | $100{,}000\text{ mAh}$ ($100\text{ Ah}$) | $3.0C$ ($300\text{ A}$) | $0.0006\,\Omega$ | $270^\circ\text{C}$ | 4000 | 15S = 48 V block; olivine-stable. |
| **Lithium-Titanate (LTO)** (`LTO`) | $2.4$ / $1.50\text{--}2.80$ | $60{,}000\text{ mAh}$ ($60\text{ Ah}$) | $10.0C$ ($600\text{ A}$) | $0.0008\,\Omega$ | $200^\circ\text{C}$ | 15000 | 10S = 24 V block; $-50^\circ\text{C}$ capable. |
| Sodium-Sulfur ($Na\text{-}S$) / Molten Salt | — | — | — | — | — | — | **Design-only, not in code.** |
| Flow Battery (Vanadium Redox) | — | — | — | — | — | — | **Design-only, not in code.** |

---

## 3. Physical Form Factors: Items vs. Blocks

VoltCraft models energy storage across two distinct physical domains: **Items** (portable cells, modular power) and **Blocks** (stationary energy storage systems - BESS).

```
   ITEM FORM: Portable / Modular              BLOCK FORM: Grid-Scale / Stationary
   ┌─────────────────────────────┐           ┌───────────────────────────────────┐
   │ 18650 Li-Ion Cell (Item)    │           │ LiFePO4 Battery Block (BlockPos)  │
   │ • 3.7V nominal, 3000 mAh    │           │ • 48V rack module / 100 Ah        │
   │ • DataComponentTypes state  │           │ • Directly wired to ElectricalGrid│
   │ • Handheld tools & armor    │           │ • Non-combustible, 3000+ cycles   │
   │ • Slots into Battery Racks  │           │ • Factory & solar array buffer    │
   └──────────────┬──────────────┘           └───────────────────────────────────┘
                  │ (Insert into rack)
                  ▼
   ┌─────────────────────────────┐
   │ Modular Battery Rack Block  │
   │ Bridges Item Cells to Grid  │
   └─────────────────────────────┘
```

### 3.1 Item-Form Batteries (Cylindrical Cells & Portable Packs)
Item batteries are individual chemical cells or compact packs held in player inventories and slotted into equipment.
* **Key Item Archetypes:**
  * **18650 Li-Ion Cell:** Standard $18\text{ mm} \times 65\text{ mm}$ cylindrical cell. $3.7\text{ V}$ nominal, $2500\text{--}3500\text{ mAh}$, $C_{\text{rate}} = 3\text{--}5C$. High energy density for power tools, handheld wireless terminals, diagnostic multimeters, flashlights, and powered exoskeletons.
  * **21700 High-Discharge Li-Ion Cell:** Larger cell for high-current electric jetpacks and plasma cutters ($C_{\text{rate}} \ge 10C$).
  * **AA / AAA Alkaline Cells:** Disposable single-use cells for early handheld gadgets.
  * **CR2032 Coin Cell:** Button cell for micro-sensors, digital clocks, and computer motherboard RTC backup.
* **Minecraft 1.21 Technical Implementation:**
  * Uses modern `DataComponentTypes` (`component/VoltcraftDataComponents.java`, no raw NBT):
    * `voltcraft:battery_charge` (`Double`): normalized SoC $[0.0, 1.0]$.
    * `voltcraft:battery_health` (`Double`): SOH fraction.
    * `voltcraft:battery_temperature` (`Double`): live cell $^\circ\text{C}$.
    * `voltcraft:battery_cell_chemistry` (`String`): chemistry key.
    * `voltcraft:battery_bay` (`BatteryBayData` codec): tool-bay cell list + rail limits.
  * **Stackability:** factory-fresh cells `maxCount = 16` (`BatteryCellItem`); once charged/aged they
    carry components and stop stacking. Overheated cells trigger thermal-runaway explosion
    (`BatteryCellItem`, per-chemistry $T_{\text{crit}}$ above — e.g. $150^\circ\text{C}$ Li-Ion).
  * Custom dynamic durability bar rendered in GUI representing SoC color gradient (Green $\to$ Yellow $\to$ Red).

### 3.2 Block-Form Batteries (Stationary Energy Storage Systems - BESS)
Block batteries are full $1 \times 1 \times 1$ world blocks (or multi-block structures) placed as permanent grid nodes.
* **Key Block Archetypes:**
  * **$LiFePO_4$ Battery Block:**
    * The standard stationary battery storage unit for industrial grids and solar arrays.
    * Pre-assembled industrial module containing internal series/parallel cell groups (typically configured as a $48\text{V}$ nominal, $100\text{ Ah} = 4.8\text{ kWh}$ block).
    * **Safety:** Chemically safe olivine crystal structure. Even if punctured by a pickaxe or subjected to massive overcurrent short-circuit, it **never experiences thermal runaway fire or explosion**.
    * **Cycle Life:** $3000\text{--}5000$ cycles before dropping to $80\%$ SOH.
  * **Heavy Lead-Acid Battery Bank (Block):**
    * Bulky $12\text{V} / 24\text{V}$ starter and bulk backup bank. Heavy mining break time; slows player if pushed.
  * **Vanadium Redox Flow Battery (Multi-Block):**
    * Central cell stack block connected via fluid pipes to external electrolyte fluid tanks.
* **Grid Integration:**
  * Directly connects to the `ElectricalGrid` graph upon placement.
  * Ticked centrally by `ElectricalGrid.tick()` as an `IElectricStorage` node.
  * Stacking adjacent $LiFePO_4$ blocks automatically establishes low-resistance internal busbars, allowing players to build scalable battery powerwalls.

### 3.3 The Bridge: Modular Battery Rack / Charger Block
To bridge the item and block domains:
* The **Battery Rack Block** is a grid-connected `BlockEntity` equipped with inventory slots (e.g., $4\times$, $8\times$, or $16\times$ cell bays).
* Players insert individual item-form cells (such as 18650 Li-Ion cells) into the rack.
* The rack contains internal bus switching (configurable via GUI or wrench for series $N\text{S}$ or parallel $M\text{P}$ topologies).
* Allows players to salvage found 18650 cells, charge them from stationary solar/wind power, and remove them for use in handheld field equipment.

---

## 4. Charging Protocols & Regulation

Charging algorithms are executed by [[../conversion/power-converters|Battery Chargers]]:

### 4.1 Constant Current / Constant Voltage (CC/CV)
Mandatory for all Lithium-based chemistries (both 18650 items and $LiFePO_4$ blocks):
1. **Constant Current (CC) Phase:**
   * Charger limits current to $I_{\text{bulk}} = C_{\text{rate}} \cdot Q$.
   * Cell terminal voltage rises as charge enters.
2. **Constant Voltage (CV) Phase:**
   * Initiated once $V_{\text{terminal}}$ reaches maximum cutoff $V_{\text{max}}$ ($4.2\text{ V}$ for Li-Ion, $3.65\text{ V}$ for $LiFePO_4$).
   * Voltage is clamped strictly at $V_{\text{max}}$.
   * Charging current tapers down asymptotically: $I(t) \to 0$.
   * Charge terminates when $I \le 0.05 C$.

### 4.2 Negative Delta-V ($-\Delta V$) Cutoff
Used for NiCd and NiMH cells:
* Cell voltage rises smoothly during constant-current charge.
* When full, cell temperature surges and terminal voltage drops slightly by $5\text{--}10\text{ mV}$.
* Charger detects $\frac{dV}{dt} < 0$ and cuts power immediately to prevent electrolyte boil-off.

### 4.3 Float & Trickle Charging
Used for Lead-Acid:
* Maintains battery indefinitely at float voltage ($2.25\text{--}2.30\text{ V/cell}$) to counteract internal self-discharge without causing water electrolysis.

---

## 5. Battery Management System (BMS)

Multi-cell batteries require active management:
1. **Cell Balancing (Passive / Active):**
   * Multi-cell packs in series ($N\text{S}$) suffer from capacity mismatch. The weakest cell fills first and risks overvoltage, while remaining cells are underfilled.
   * **Passive Balancing:** Bleeds excess charge through shunt resistors on high-voltage cells.
   * **Active Balancing:** Uses capacitive charge pumps or inductive buck-boost circuits to shuttle charge from high-voltage cells to low-voltage cells without thermal loss.
2. **Protection Guardrails:**
   * **Over-Voltage Protection (OVP):** Disconnects charger if any cell exceeds $V_{\text{max}}$.
   * **Under-Voltage Protection (UVP):** Disconnects load if any cell falls below $V_{\text{min}}$ (preventing copper dendrite shunts).
   * **Over-Current Protection (OCP):** Trips MOSFET switch on short circuits.
   * **Over-Temperature Protection (OTP):** Halts operation if temperature crosses safety margin.
3. **Telemetry & Communication Bus:**
   * Transmits digital packets (SoC %, SOH % health, temperature, cell voltages, remaining run-time) over an isolated data cable to computers and cockpit monitors.
