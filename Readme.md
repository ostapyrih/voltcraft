# VoltCraft

> [!WARNING]
> **Early development (v0.1).** APIs, block IDs, recipes, and simulation constants will change without backward compatibility. Expect bugs and incomplete content. Feedback and suggestions are very welcome — see [Contributing](#contributing).

A physically grounded electrical power simulation mod for modern Minecraft (Fabric).

No magic FE/RF buffers. VoltCraft simulates real Voltage (V), Current (I), Resistance (R), Impedance (Z), Power (W), Joule heating (`P = I²·R`), and electrochemistry.

- **Platform:** Fabric Loader + Fabric API, Minecraft `1.21.11`
- **Language:** Java 21 (records, sealed interfaces, pattern matching)
- **License:** MIT — see `LICENSE.txt`
- **Package:** `com.ostapyrih.voltcraft`, public API in `com.ostapyrih.voltcraft.api.*`

## Features

**Conductors (9 types)** — bare / insulated / heavy copper, aluminum transmission, silver precision, gold bus, steel fence, nichrome heating, superconductor conduit. Each with resistivity `ρ`, temperature coefficient `α`, ampacity, insulation melt point, and shock hazard. See `src/main/java/com/ostapyrih/voltcraft/block/cable/ConductorType.java`.

**Switchgear & protection** — copper busbar, junction box, knife switch, fuse box, circuit breaker, contactor relay. See `src/main/java/com/ostapyrih/voltcraft/block/switchgear/`.

**Energy storage**
- Portable cells (item form, `DataComponentTypes`): 18650 Li-Ion 3.7V / 3000mAh, 21700 high-drain, AA alkaline, zinc-carbon, CR2032, Li-SOCl₂, NiCd, NiMH.
- Stationary BESS (block form): LiFePO₄, Lead-Acid, LTO, NiMH/NiCd blocks, modular 18650 battery rack.
- Real OCV vs SoC curves, C-rates, CC/CV charging, internal resistance `V_term = V_emf - I·R_int`, thermal runaway.

**Power conversion**
- DC-DC: Buck, Boost, Buck-Boost/SEPIC, Linear LDO (configurable output 1–600 V GUI)
- AC Transformers: Step-Up / Step-Down
- Rectifiers: Bridge, Active Synchronous
- Inverters: Square, Modified Sine, Pure Sine SPWM, Grid-Tie (anti-islanding), Hybrid ESS
- EU Converter: 230V AC ↔ TechReborn Energy (`teamreborn:energy`) bridge

**Generation**
- Solar: Monocrystalline PERC, Polycrystalline, Thin-Film CdTe, Concentrator CPV + MPPT charge controller. Celestial angle, weather/temperature derating, shading/bypass.
- Mechanical/combustion: Hand-crank dynamo (~100 W DC), portable inverter generator (1–2 kW 230V AC pure sine)
- Creative Generator / Creative Load for testing and map-making

**Materials & worldgen** — Bauxite, Galena, Sphalerite, Spodumene, Pentlandite, High-Purity Quartz (stone + deepslate variants), with smelting into Al / Pb / Zn / Li / Ni / Ag, plus Nichrome, fuse alloy, rubber, and full semiconductor chain: boule → wafer → doped P/N → PV cell → MOSFET / Schottky / capacitor / BMS board.

**Simulation engine**
- Modified Nodal Analysis (DC) + AC phasor solver, thermal equilibrium solver
- Centralized `ElectricalGrid.tick()` — wires never tick (see Architecture below)
- Brownout / Surge / Overcurrent failure modes with fire, arcs, fuse pops

## Physics

```
Ohm's Law:        I = V / R  (DC),  Ĩ = Ṽ / Z  (AC)
Power:            P = V·I  →  I = P / V
Line loss:        P_loss = I²·R_line
Source terminal:  V_term = V_emf - I·R_int
```

| State | Condition | Effect |
|---|---|---|
| Brownout | `V < V_min` | Machine stalls / flickers, controllers reset |
| Nominal | `V_min ≤ V ≤ V_max` | Full efficiency |
| Surge | `V > 1.3×V_nominal` | Destroyed circuits, arcs, fire |
| Overcurrent | `I > I_max` | Joule heating → insulation melt (>120 °C) → ignites neighbors |

## Requirements

| Dependency | Version |
|---|---|
| Minecraft | `1.21.11` |
| Yarn mappings | `1.21.11+build.6` |
| Fabric Loader | `0.19.5+` |
| Fabric API | `0.141.6+1.21.11` |
| Java | 21+ |
| Gradle / Loom | 9.6.1 / 1.17.21 |

Defined in `gradle.properties`.

## Install (players)

1. Install Fabric Loader `0.19.5+` for Minecraft `1.21.11` + Fabric API `0.141.6+1.21.11`.
2. Download a VoltCraft release jar and drop it in `mods/`.
3. Requires TechReborn Energy `4.1.0` (bundled via `include` in `build.gradle` — no separate install needed for standard builds).

## Build from source

```bash
./gradlew build        # output in build/libs/
./gradlew runClient    # test client
./gradlew runServer    # test server
./gradlew test         # JUnit 5 physics tests (MNA, AC, thermal, converters, grid topology)
```

Resources use `fabric.mod.json` expansion (`src/main/resources/fabric.mod.json`). Client datagen entrypoint: `com.ostapyrih.voltcraft.client.VoltcraftDataGenerator`.

## Quick start

1. Mine Bauxite / Galena / Quartz → smelt → craft magnet wire, wafers, PV cells.
2. Place Solar Panel → MPPT Charge Controller → LiFePO₄ battery block → DC Buck converter → load, wired with insulated copper.
3. Undersize the cable or overvolt the rail to see brownout heating, melted insulation, and fire — then add fuse boxes / breakers.
4. Use Creative Generator / Load (`/voltcraft` commands in `VoltcraftCreativeCommands`) to characterize a network.

Recipes and constants: `wiki/` catalog, start at `wiki/index.md`.

## Architecture

1. **No wire ticking:** `CableBlock` / wire BlockEntities never tick. All Ohm/thermal math runs centrally in `simulation/grid/ElectricalGrid.java` — one tick per independent network. Merge on place, BFS split on break (`GridTopologyHelper.java`).
2. **Server-authoritative:** physics, grid management, fire/destruction run on the logical server only. Client gets sync packets (active state, smoke, glow/spark).
3. **Persistence:** grids survive reboots via `PersistentState`; nodes in unloaded chunks suspend without tearing down topology (`GridManager.java`).
4. **Item vs Block storage:** portable cells (`item/battery/BatteryCellItem.java` + `VoltcraftDataComponents.java`) vs stationary BESS (`block/entity/storage/`). Racks/benches bridge the two.
5. **Tools:** powered tools are unpowered chassis + swappable bays (1×18650 / 2×Alkaline / 3×NiMH, 3.0–4.2 V rail).

## API for addon devs

Contracts live in `src/main/java/com/ostapyrih/voltcraft/api/` and avoid leaking internals:

- `grid/IElectricalGrid.java`, `IGridNode.java`, `IGridConductor.java`, `IElectricalConnectable.java`
- `energy/IElectricComponent.java`, `IElectricSource.java`, `IElectricConsumer.java`, `IElectricStorage.java`, `IElectricConverter.java`
- `data/ElectricalState.java`, `Phasor.java`, `BatteryCellSpec.java`

Implement the interfaces on your BlockEntities and connect via cables/busbars — the grid discovers and ticks you.

## Docs

- `core-idea.md` — vision, math, grid lifecycle, subsystem map
- `wiki/index.md` — master catalog: conductors, storage, conversion, generation, machinery, tools, materials
- `wiki/log.md` — chronological change log
- `AGENTS.md` — contributor / agent directives (physics laws, API isolation, modern 1.21 APIs)
- `llm-wiki.md` — wiki maintenance conventions

## Project layout

```
src/main/java/com/ostapyrih/voltcraft/
  api/            # public contracts (grid, energy, data)
  simulation/     # grid, solver (MNA/AC/thermal), chemistry, conversion, generation, creative
  block/          # cables, switchgear, storage, conversion, generation, creative
  block/entity/   # BlockEntities (converters, batteries, solar, generators)
  item/  component/  screen/  network/  command/  world/
src/test/java/... # CircuitDynamicsRealismTest, ACSolverTest, BatteryGridIntegrationTest, etc.
wiki/ raw/ core-idea.md
```

## Contributing

This project is in an early stage, so any suggestions are welcome — physics models, gameplay balance, new blocks/items, textures, docs, or code cleanup.

Issues and PRs welcome. Match Yarn naming, Java 21 style, keep API in `api.*`, add a JUnit test for new physics (`src/test/java/...`), and update `wiki/index.md` + `wiki/log.md` when changing constants or recipes.

## License

MIT — see `LICENSE.txt`.
