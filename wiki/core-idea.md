# VoltCraft: Core Idea & Architecture Specification

> [!NOTE]
> This is the foundational vision and system blueprint for **VoltCraft**.\n> Complete subsystem physics, matrices, world generation stratigraphy, and 3x3 crafting recipes are maintained in the modular knowledge base at [wiki/index.md](file:///home/ostapyrih/Projects/voltcraft/wiki/index.md).

---

## 1. Project Overview & Philosophy

* **Goal:** A physically grounded electrical power simulation mod for modern Minecraft.
* **Core Philosophy:** Electricity is not an abstract liquid buffer ("FE/RF/EU"). It operates on real electrical principles: Voltage ($V$), Current ($I$), Resistance ($R$), Impedance ($Z$), Power ($W$), Joule heating ($P = I^2 \cdot R$), and realistic electrochemistry for energy storage. Focus is placed cleanly on generation, transportation, storage, power conversion, and diagnostic tools.
* **Platform:** Fabric Loader & Fabric API on modern Minecraft (1.21.x+).
* **Language & Runtime:** Java 21+ (strict standard, leveraging Records, Pattern Matching, Sealed Interfaces, Data Components).
* **Package Contract:** `com.ostapyrih.voltcraft.*` with external contracts in `com.ostapyrih.voltcraft.api.*`.
* **Licensing:** MIT License for source code (maximum ecosystem compatibility and openness).

---

## 2. Mathematical & Physical Principles

### 2.1 Basic Circuit Equations
* **Ohm's Law:**
  $$I = \frac{V}{R} \quad \text{(DC)} \qquad \tilde{I} = \frac{\tilde{V}}{Z} \quad \text{(AC)}$$
* **Power Demand:**
  $$P = V \cdot I \implies I = \frac{P}{V}$$
* **Conductor Line Losses & Joule Heating:**
  $$P_{\text{loss}} = I^2 \cdot R_{\text{line}}$$
* **Real Source Terminal Voltage (with Internal Resistance $R_{\text{int}}$):**
  $$V_{\text{terminal}} = V_{\text{emf}} - I \cdot R_{\text{int}}$$

### 2.2 Operational States & Anomaly Behavior
All machines, cables, and storage devices operate under a centralized state machine:

```
          [ Undervoltage V < Vmin ]  ───►  [ BROWNOUT ]
                    ▲                           │
                    │                           ▼ (V restored)
[ OFF / UNPOWERED ] ┴─────────────────────────► [ NOMINAL (Vmin <= V <= Vmax) ]
                                                │
                                                ▼ (V > 1.3 * Vnominal)
                                         [ SURGE / DESTROYED ]
                                         (Electric Arcs, Fire, Pops Fuses)
```

1. **Under-voltage / Brownout ($V_{\text{actual}} < V_{\text{min}}$):** Device halts or runs at severely reduced speed; microcontrollers drop into reboot loops.
2. **Nominal Operation ($V_{\text{min}} \le V_{\text{actual}} \le V_{\text{max}}$):** Device functions at full efficiency.
3. **Over-voltage / Surge ($V_{\text{actual}} > V_{\text{max}}$):** If exceeded by $> 1.3 \times V_{\text{nominal}}$, component enters `DESTROYED` state (electric arcs, smoke particles, pops fuses).
4. **Current Overload ($I_{\text{actual}} > I_{\text{rating}}$):** Continuous thermal accumulation. When insulation melts ($>120^\circ\text{C}$), surrounding blocks ignite (`Blocks.FIRE`).

---

## 3. High-Performance Graph-Based Architecture

### 3.1 Performance Bottleneck Prevention: The No-Wire-Ticking Law
* **Anti-Pattern:** Running tick loops (`BlockEntity.tick()`) on hundreds of individual wire blocks. This devastates server TPS.
* **Architecture Solution:**
  * Wires are passive topological connectors with zero per-block ticking.
  * Connected wires, sources, and consumers are collected into a single graph entity: `ElectricalGrid`.
  * **Only the `ElectricalGrid` ticks** (1 aggregated tick calculation per independent power network).

### 3.2 Network Lifecycle & Topological Operations
* **On Wire Placement:**
  * Check adjacent block positions.
  * If no adjacent grid exists: Instantiate a new `ElectricalGrid`.
  * If 1 adjacent grid exists: Add position to that grid.
  * If $\ge 2$ distinct adjacent grids are bridged: Execute `mergeGrids(Grid A, Grid B)`.
* **On Wire Destruction:**
  * Remove position from the current `ElectricalGrid`.
  * Execute Breadth-First Search (BFS) from adjacent connection points.
  * If the graph was split into disjoint partitions, split into separate `ElectricalGrid` instances.
* **Chunk Boundary Safety:**
  * Grid tracks unloaded chunk boundaries via world chunk lifecycle events (`ServerChunkEvents`).
  * Nodes residing in unloaded chunks are suspended from active calculation without destroying grid topology.
  * Power networks persist across world restarts via Minecraft's `PersistentState`.

---

## 4. Subsystem Categories & Knowledge Base Map

Detailed component specifications, physics matrices, world generation parameters, and crafting recipes are organized into designated category modules:

| Subsystem Category | Knowledge Base Modules | Scope & Content |
| :--- | :--- | :--- |
| **Conductors & Switchgear** | [wiki/conductors/cables.md](file:///home/ostapyrih/Projects/voltcraft/wiki/conductors/cables.md)<br>[wiki/conductors/switchgear.md](file:///home/ostapyrih/Projects/voltcraft/wiki/conductors/switchgear.md) | Conductor resistivity matrix ($\rho, \alpha, I_{\text{max}}$), Joule heating, insulation melting, contact shock, busbars, knife switches, fuse boxes, circuit breakers, and contactors. |
| **Energy Storage** | [wiki/storage/electrochemistry.md](file:///home/ostapyrih/Projects/voltcraft/wiki/storage/electrochemistry.md)<br>[wiki/storage/battery-items.md](file:///home/ostapyrih/Projects/voltcraft/wiki/storage/battery-items.md)<br>[wiki/storage/battery-blocks.md](file:///home/ostapyrih/Projects/voltcraft/wiki/storage/battery-blocks.md) | Electrochemical physics, dynamic OCV vs SoC curves, C-ratings, CC/CV charging, BMS balancing, Item cells (18650 Li-Ion, 21700, alkaline) vs Block BESS ($LiFePO_4$ industrial blocks, lead-acid, modular racks, vanadium flow batteries). |
| **Power Conversion** | [wiki/conversion/inverters.md](file:///home/ostapyrih/Projects/voltcraft/wiki/conversion/inverters.md)<br>[wiki/conversion/power-converters.md](file:///home/ostapyrih/Projects/voltcraft/wiki/conversion/power-converters.md) | DC-AC inverters (square, modified sine, pure sine SPWM, grid-tie anti-islanding, hybrid ESS), switched-mode DC-DC converters (buck, boost, SEPIC), linear LDOs, iron-core transformers, and active rectifiers. |
| **Power Generation** | [wiki/generation/solar-panels.md](file:///home/ostapyrih/Projects/voltcraft/wiki/generation/solar-panels.md)<br>[wiki/generation/generators.md](file:///home/ostapyrih/Projects/voltcraft/wiki/generation/generators.md) | Photovoltaic p-n junction physics, celestial zenith irradiance calculations, rain/storm attenuation, MPPT tracking algorithms, string shading bypass diodes, hand crank dynamos, and realistic 1-2 kW portable inverter generators. |
| **Tools, Bench & PPE** | [wiki/tools/instruments-and-safety.md](file:///home/ostapyrih/Projects/voltcraft/wiki/tools/instruments-and-safety.md) | Field diagnostic meters (digital multimeter, clamp meter, oscilloscope tablet, thermal imaging HUD camera), wire stripper pliers, $1000\text{V}$ insulated electrician's gloves, replacement cartridge fuses ($10\text{A}\text{--}64\text{A}$), and the stationary **Battery Charger & Diagnostic Bench**. |
| **Materials & Metallurgy** | [wiki/materials/ores.md](file:///home/ostapyrih/Projects/voltcraft/wiki/materials/ores.md)<br>[wiki/materials/electronic-components.md](file:///home/ostapyrih/Projects/voltcraft/wiki/materials/electronic-components.md) | Natural ore stratigraphy (Bauxite, Galena, Sphalerite, Spodumene, Pentlandite, High-Purity Quartz), smelting, alloys (Nichrome, Lead-Tin), and semiconductor fabrication (boules, wafers, PV cells, MOSFETs, diodes, capacitors, BMS PCBs). |

---

## 5. API & Extensibility Contracts

All public interfaces and data records reside in `com.ostapyrih.voltcraft.api.*`:
* `IElectricalGrid`: Central network graph contract, managing admittance matrices and nodal ticks.
* `IElectricComponent`: Base interface for grid-connectable blocks and block entities.
* `IElectricSource`: Voltage/current generator with internal resistance $R_{\text{int}}$.
* `IElectricConsumer`: Power-demanding load with impedance and brownout/surge thresholds.
* `IElectricStorage`: Chemical storage cell exposing SoC, OCV, C-rate, and thermal state.
* `IElectricConverter`: Multi-port conversion component (DC-DC, DC-AC, AC-DC, AC-AC).
