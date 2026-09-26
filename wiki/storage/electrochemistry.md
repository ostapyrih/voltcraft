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

### 2.1 Primary Cells (Non-Rechargeable / Single-Use)
> [!CAUTION]
> Attempting to push reverse current into a primary cell forces gas evolution ($H_2 / O_2$) and dendrite formation, triggering casing rupture and corrosive chemical splattering.

| Chemistry | Nominal Cell $V$ | Cutoff $V$ | Energy Density | Self-Discharge | Mechanical Properties & Hazards |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Zinc-Carbon** | $1.5\text{ V}$ | $0.9\text{ V}$ | $60\text{ Wh/kg}$ | High ($15\%/\text{yr}$) | Early-tier primitive cell. Cheap zinc casing acts as anode; punctures and leaks corrosive paste when fully discharged. |
| **Alkaline ($Zn\text{-}MnO_2$)** | $1.5\text{ V}$ | $0.8\text{ V}$ | $140\text{ Wh/kg}$ | Low ($2\%/\text{yr}$) | Standard single-use cell for portable flashlights, meters, and basic wireless redstone remotes. |
| **Lithium-Thionyl Chloride ($Li\text{-}SOCl_2$)** | $3.6\text{ V}$ | $3.0\text{ V}$ | $500\text{ Wh/kg}$ | Ultra-Low ($1\%/10\text{ yrs}$) | High-end industrial primary cell. Powers real-time clocks (RTC) and backup RAM for offline computers. Emits lethal toxic $SO_2$ gas if burned. |
| **Silver-Oxide ($Ag_2O$)** | $1.55\text{ V}$ | $1.2\text{ V}$ | $130\text{ Wh/kg}$ | Low ($5\%/\text{yr}$) | Ultra-flat discharge curve. Ideal for precision scientific instruments and micro-circuit logic gates. |
| **Mercury Cell** | $1.35\text{ V}$ | $1.0\text{ V}$ | $100\text{ Wh/kg}$ | Low | Historical vintage cell. Inexpensive; leaves lingering poisoned ground puddles if broken. |

---

### 2.2 Secondary Cells (Rechargeable)

| Chemistry | Nominal Cell $V$ | Voltage Window | Round-Trip $\eta$ | Cycle Life | Runaway $T_{\text{crit}}$ | Charging Protocol | In-Game Mechanics & Dynamics |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Lead-Acid (SLA / AGM)** | $2.0\text{ V}$ ($12\text{V}$ 6-cell bank) | $1.75\text{--}2.40\text{ V}$ | $\sim 75\%$ | $300\text{--}500$ | Low ($>70^\circ\text{C}$ vents $H_2$) | 3-Stage: CC / CV / Float | Very heavy (slow player movement when carried). Cheap bulk storage. Vents flammable/explosive $H_2$ gas in unventilated rooms. Suffer sulfation if left uncharged. |
| **Nickel-Cadmium (NiCd)** | $1.2\text{ V}$ | $1.00\text{--}1.45\text{ V}$ | $\sim 70\%$ | $1000+$ | Low | Constant Current (CC) | Resilient to extreme cold (operates down to $-40^\circ\text{C}$). Suffers from severe **Memory Effect** (permanently loses capacity if recharged before complete discharge). |
| **Nickel-Metal Hydride (NiMH)** | $1.2\text{ V}$ | $1.00\text{--}1.42\text{ V}$ | $\sim 80\%$ | $500\text{--}1000$ | Moderate ($>65^\circ\text{C}$) | CC with $-\Delta V$ cutoff | Successor to NiCd; no memory effect, higher capacity, environmentally safe. Sensitive to overcharging heat. |
| **Lithium-Ion ($LiCoO_2$)** | $3.7\text{ V}$ | $3.00\text{--}4.20\text{ V}$ | $\sim 95\%$ | $500\text{--}1200$ | **Extreme ($>150^\circ\text{C}$)** | Strict CC/CV | Highest energy density for mobile gear, power tools, electric jetpacks. Violently explodes if overcharged past $4.3\text{ V}$ or mechanically punctured. |
| **Lithium Iron Phosphate ($LiFePO_4$)**| $3.2\text{ V}$ | $2.50\text{--}3.65\text{ V}$ | $\sim 92\%$ | $3000+$ | Very Low ($>270^\circ\text{C}$) | Strict CC/CV | Industrial stationary storage workhorse. Chemically stable olivine structure: will not catch fire or explode even under heavy abuse or short circuit. |
| **Lithium-Titanate (LTO)** | $2.4\text{ V}$ | $1.50\text{--}2.80\text{ V}$ | $\sim 90\%$ | $10000+$ | Minimal | High-rate CC ($10C$) | Extreme cycle life and ultra-fast charging (seconds to minutes). Operates down to $-50^\circ\text{C}$. High manufacturing cost. |
| **Sodium-Sulfur ($Na\text{-}S$) / Molten Salt** | $2.0\text{ V}$ | $1.80\text{--}2.20\text{ V}$ | $\sim 85\%$ | $2500+$ | High (Requires $300^\circ\text{C}$ operating temp) | CC/CV | Grid-scale containerized storage. Has internal auxiliary heater. If grid power is lost and heaters cool, electrolyte solidifies, disabling the battery until reheated. |
| **Flow Battery (Vanadium Redox)** | $1.26\text{ V}$ per cell | $1.00\text{--}1.60\text{ V}$ | $\sim 75\%$ | $15000+$ | None (Water-based electrolyte) | Continuous electrolyte pumping | Multi-block stationary installation with external liquid chemical tanks. Storage capacity scales purely with tank volume; zero degradation over time. |

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
  * Uses modern `DataComponentTypes` (no raw compound NBT):
    * `voltcraft:battery_charge`: Micro-Coulombs or normalized $[0.0, 1.0]$ SoC.
    * `voltcraft:battery_health`: State of Health (SOH %) based on cumulative charge cycles.
    * `voltcraft:battery_chemistry`: Identifier (e.g. `voltcraft:li_ion_18650`).
    * `voltcraft:battery_temperature`: Dynamically tracks cell temperature. High temperature while held in inventory issues warning sounds; exceeding $150^\circ\text{C}$ ignites the player's inventory!
  * **Stackability:** Uncharged/unformatted factory-fresh cells stack up to 16. Once initialized with charge or health data, they become unique non-stackable instances.
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

Charging algorithms are executed by [[power-converters|Battery Chargers]]:

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
