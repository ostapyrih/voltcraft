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

## 3. High-Performance Graph-Based Architecture ✅ kernel islands (grid-rehaul shipped)

### 3.1 Performance Bottleneck Prevention: The No-Wire-Ticking Law
* **Anti-Pattern:** Running tick loops (`BlockEntity.tick()`) on hundreds of individual wire blocks. This devastates server TPS.
* **Architecture Solution (shipped):**
  * Wires are passive topological connectors with zero per-block ticking.
  * Cables plus declared `KernelAttachedBlock` terminal positions are collected into deterministic electrical islands by `GridManager` (union-find over 6-Manhattan cable adjacency OR same-block terminal ownership; `POS_ORDER` node/element ordering; islands ordered by minimum node).
  * **Only the kernel ticks** — exactly one `ElectricalKernel.tick()` (one Newton solve + RK2 integration at `DT = 0.05 s`) per island per server tick, gated on `converged && !singular`. Rebuilds happen only when dirty (cable place/break hooks, attached-block add/remove, chunk load/unload scans); melts queue breaks for the next rebuild boundary, never mid-tick. Exactly-once discrete phase per tick via `ElectricalTickDedupe`.
  * ❌ The old `ElectricalGrid` / `mergeGrids` / BFS-split / `GridTopologyHelper` path is deleted; `api/energy/*` is an empty legacy directory.

### 3.2 Solve Model: Complex Nodal + Newton (replaces the old DC description)
* Single solve point `ElectricalKernel` returns `KernelSolveResult` (`voltage`, `converged`, `singular`, `residual`, `newtonIterations`, `linearEliminationSteps`, `fallbackActive`).
* Frozen constants (`api/electrical/GridConstants.java`): `DT = 0.05`, `NEWTON_TOL = 1e-6`, `NEWTON_MAX_ITER = 40`, `NEWTON_MAX_STEP = 50.0 V`, `NEWTON_RESIDUAL_FLOOR = 1e-10`, `LINEAR_RESIDUAL_TOL = 1e-9`, `AMBIENT_C = 20.0 °C`, `AC_FREQUENCY_HZ = 50.0` (`AC_OMEGA_RAD_PER_S = 2π·50`).
* AC is solved in the complex domain (`api/electrical/Complex.java`, `api/data/Phasor.java`: magnitude + phase). Per-island `omega` is `0` for DC and `2π·50` when at least one active AC source is present (conductors/passives never vote). DC-only stamps (`constantPower`, `constantCurrent`, `oneWayThevenin`) throw on `omega != 0`, so AC-island converter inputs / creative loads stamp a resistive approximation (`R = Vnom²/P`).
* Reference scheme: each galvanically connected component gets one reference node (V = 0) via a unit row (no GMIN shunt); conductor floor `1e-4 Ω`; melted scan is strict `T > melt`, side-effect free.
* Elements hold no persistent state — the kernel owns all `double[]` slots. `BatteryElement` is a Thevenin equivalent (`[SoC, T, health]`, open-BMS `1 MΩ`, `60 °C` open / `55 °C` reclose hysteresis). `ConverterElement` is 4-terminal (staged demand in, staged EMF out, `0.05 Ω` source R). MPPT (`MPPTLogic`) is P&O + Bulk/Absorption/Float with output-current foldback; the Energy Bridge `converter_eu` gates on `207–253 V / ≥ 40 Hz` (`25 W → 1 E/t`, `10 kE` buffer, `512 E/t`). Full kernel contract: `docs/kernel.md`.
* **Chunk Boundary Safety:**
  * `GridManager` tracks loaded chunks; rebuilds include only positions whose own chunk is loaded.
  * Nodes in unloaded chunks contribute nothing this rebuild without destroying the persisted cable index; BlockEntity state arrays survive unload gaps and re-seed the kernel after reload.
  * Power networks persist across world restarts via Minecraft's `PersistentState` — cables only (`PERSISTENCE_KEY = "voltcraft_power_grids"`); voltages and kernel states are transient.

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
* `electrical/ElectricalElement`: stateless lumped element stamped into `Y·V = I` (`terminalCount`, `stateCount`, `stamp`, `derivatives`, `requiresReturnPath`).
* `electrical/Conductor`: resistive branch (`resistance`, `temperature`, `heatCapacity`, `coolingCoeff`, `meltingTemp`); mechanical implementation `CableConductorAdapter` (cable R / `0.001 Ω` default, terminal link `0.0001 Ω` never-melting `1e9 °C`).
* `electrical/Complex` + `data/Phasor`: immutable complex phasor math (magnitude + phase) for the AC domain.
* `electrical/Stamps`: frozen `admittance` / `draw` / `thevenin` / `powerInto` plus DC-only `constantPower` / `constantCurrent` / `oneWayThevenin` (throw on `omega != 0`).
* `electrical/GridConstants`: frozen `DT`, Newton tolerances, `AMBIENT_C`, `AC_FREQUENCY_HZ` / `AC_OMEGA_RAD_PER_S`.
* `data/ElectricalState`: `OFF / NOMINAL / BROWNOUT / SURGE / DESTROYED` state machine.
* `grid/KernelAttachedBlock`: ✅ shipped topology contract implemented by all 16 production grid BlockEntities (element + terminal positions + state array + `tickElectrical` + `isActiveSource`/`isACSource`); ⚠️ its javadoc still claims "no production block entity implements this interface" — stale, contradicted by the 16 implementors.
* ❌ `api/energy/*` (`IElectricComponent` / `IElectricSource` / `IElectricConsumer` / `IElectricStorage` / `IElectricConverter`) and the old `api/grid` legacy types (`IElectricalGrid`, `IGridNode`, `IGridConductor`) are design-only/deleted: the directory is empty and the kernel islands are the single subsystem.
