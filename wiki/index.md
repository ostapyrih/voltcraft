# VoltCraft Knowledge Base Index

Welcome to the VoltCraft persistent LLM wiki. This catalog indexes all architectural specifications, physical simulation models, electrical component definitions, crafting recipes, and gameplay mechanics across modular category folders.

> **Code audit 2026-10-03 (implemented vs spec, grid-rehaul):** 56 blocks (`VoltcraftBlocks.ALL_BLOCKS`),
> 91 items (56 block-items + 35 standalone in `VoltcraftItems`), 19 `BlockEntityType`s
> (`VoltcraftBlockEntityTypes`: battery, rack, converter, transformer, rectifier, inverter,
> EU converter, solar, charge controller, hand crank, portable generator, creative
> generator/load, knife switch, contactor, busbar, junction, breaker, fuse box),
> 103 generated recipes (`src/main/generated/data/voltcraft/recipe/*.json`: 57 shaped, 10 shapeless,
> 18 smelting + 18 blasting), 3 creative tabs, 5 `DataComponentTypes`.
> All recipes are **generated** by `src/client/java/com/ostapyrih/voltcraft/client/VoltcraftRecipeGenerator.java` — no hand-written JSON.
> Simulation core is the kernel-island subsystem (`simulation/grid/ElectricalKernel.java`,
> `GridManager.java`, `IslandContext.java`, `CableConductorAdapter.java`,
> `ElectricalTickDedupe.java`; `api/electrical/*` + `api/data/Phasor.java`): legacy
> `ElectricalGrid` / `GridNode` / `GridConductor` / `GridTopologyHelper` /
> `ModifiedNodalAnalysis` / `ACSolver` / `api/energy/*` are deleted (empty legacy dir).
> Status tags below: ✅ implemented · ⚠️ partial/stale · ❌ design-only (Phase 7 queued).

---

## 🏛️ Core Idea & System Design
* [[core-idea|VoltCraft Core Idea & Architecture]] — Master foundational vision, core simulation philosophy, mathematical foundations, graph-based grid network architecture, and API package isolation.
* [[implementation-plan|Code Architecture & Implementation Plan]] — Complete Java 21 / Fabric package structure, API contracts, centralized simulation engine design, and 8-phase streamlined development roadmap.

---

## ⛏️ Materials & Metallurgy (`wiki/materials/`) ✅
* [[materials/ores|Natural Ores & Raw Metallurgy]] ✅ — World generation stratigraphy (exact vein sizes 8/7/6/5/6/6, Y-ranges, veins/chunk, no biome filter), smelting/blasting outputs, and alloys (Nichrome 2x, Fuse Alloy 2x, Rubber variants). ✅ audited against `VoltcraftConfiguredFeatures` / `VoltcraftPlacedFeatures` / loot tables.
* [[materials/electronic-components|Electronic Components & Manufacturing]] ✅ — Semiconductor fabrication chain (silicon boules, wafers, doped P/N wafers, PV cells) and discrete electronic parts (Power MOSFETs, Schottky diodes, electrolytic capacitors, magnet wire, transformer cores, BMS PCBs) with exact `SGS/NPN/CAC`-style patterns. ✅ audited.

---

## ⚡ Conductors & Distribution (`wiki/conductors/`) ✅
* [[conductors/cables|Cables & Conductor Metallurgy]] ✅ — Exact `ConductorType` constants ($R_0$/block, $\alpha$, $I_{\text{max}}$, insulation/melt temps, shock flags, thermal mass/cooling) and generated recipe yields (bare Cu 6x single-row). Superconductor = $100\text{ kA}$ / Nether Star recipe; fuse cartridges & graphite blocks are design-only.
* [[conductors/switchgear|Switchgear & Protection Hardware]] ✅ — All 6 blocks implemented (no BlockEntity by design); fuse box consumes `fuse_alloy_ingot` (no `fuse_cartridge_*` items exist); amp ratings are spec text.

---

## 🔋 Energy Storage (`wiki/storage/`) ✅
* [[storage/electrochemistry|Electrochemistry & Battery Fundamentals]] ✅ — Exact `BatteryChemistry` enum table (nominal/cutoff/full V, mAh, C-rate, $R_{\text{int}}$, runaway $T$, cycles). Na-S / VRFB / Ag₂O / Mercury rows are design-only (no enum entries). Exact `DataComponentTypes` (5, incl. `battery_bay`).
* [[storage/battery-items|Battery Items: Portable Cells]] ✅ — All 8 implemented cells with exact chemistry stats and `ACA/LSK/AIA`-style recipe patterns; `maxCount = 16`, per-chemistry runaway explosion.
* [[storage/battery-blocks|Battery Blocks: Stationary BESS]] ✅ — Exact series/parallel configs (LFP 15S1P 48 V/100 Ah, Lead 6S1P 12 V/120 Ah, LTO 10S1P 24 V/60 Ah, NiMH 20S20P 24 V/50 Ah, NiCd 20S25P 24 V/30 Ah), 16-bay rack modes, AC-abuse thresholds marked spec-only. No flow/molten-salt blocks exist.

---

## 🔁 Power Conversion & Inversion (`wiki/conversion/`) ✅
* [[conversion/inverters|Inverters & DC-AC Conversion]] ✅ — Exact `InverterType` table (THD 48/28/2.5/2.0/2.0%, $\eta$ 90/92/96/97/96%, 1500/3000/5000/6000/8000 W @ 230 V), DC input modes 12/24/48 V with UVLO/OVP, exact `IMI/LWE/CRN`-style recipes.
* [[conversion/power-converters|Power Converters: DC-DC, Transformers, Rectifiers & Energy Bridge]] ✅ — Exact `ConverterType` / `TransformerType` (5000 VA, 96%) / `RectifierType` (bridge 1.4 V/88%/32 A, active 0.05 V/98.5%/64 A) specs; LDO is **shaped** 1x (not shapeless 2x); new §4.3 documents the **Rotary Energy Bridge** `converter_eu` (25 W → 1 E/t, 10 kE buffer, 512 E/t, 207–253 V / ≥40 Hz gate).

---

## ☀️ Power Generation (`wiki/generation/`) ✅
* [[generation/solar-panels|Solar Panels & Photovoltaic Generation]] ✅ — Exact `SolarPanelType` STC values (thin-film corrected to 70 V/3.57 A/88 V/4.0 A, poly $I_{\text{sc}} = 10.1\text{ A}$), exact weather factors (CPV 0 in rain), MPPT P&O + Bulk/Absorption ($14.4\text{ V}$/12 V)/Float ($13.6\text{ V}$/12 V) constants, 60 A / 98% / 15–150 V ratings.
* [[generation/generators|Fuel Generators, Dynamos & Renewables]] ✅ — Hand crank exact ($+0.35$/crank, $\times 0.97$ decay, EMF $= v \times 13.8\text{ V}$, $0.15\,\Omega$, back-EMF damping, 0.3 exhaustion); portable generator uses generic `FuelRegistry` ticks (not hardcoded), $0.15\,\Omega$, eco-throttle $0.25 + 0.75 \cdot P/1800$.

---

## 🛠️ Tools, Bench & Diagnostics (`wiki/tools/`)
* [[tools/instruments-and-safety|Tools, Diagnostic Instruments & Safety PPE]] ❌ **Design-only — Phase 7 QUEUED, 0 items implemented.** Multimeters, clamp meters, oscilloscope tablets, thermal cameras, strippers, gloves, cartridge fuses, charger bench: spec text with banner.
* [[tools/creative-testing|Creative Testing Blocks & Grid Diagnostic Tools]] ✅ — Both creative blocks implemented (no recipes); exact voltage/current/$R_{\text{int}}$/frequency preset arrays; `/voltcraft` command syntax corrected to raycast form (no XYZ args, no 400 Hz preset).

> Note: `AGENTS.md` references a `wiki/machinery/` category (furnaces, motors, electrolyzers, pumps,
> luminaires, chargers) — **no such directory or pages exist**. Likewise there is no
> `wiki/conversion/energy-bridge.md`; the bridge is documented inside `power-converters.md` §4.3.

---

## Wiki Metadata
* **Maintained By:** Antigravity / LLM Agent
* **Conventions:** See [AGENTS.md](file:///home/ostapyrih/Projects/voltcraft/AGENTS.md) and [llm-wiki.md](file:///home/ostapyrih/Projects/voltcraft/llm-wiki.md)
* **Log:** [[log|Activity Log]]
