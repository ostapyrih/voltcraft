# VoltCraft Knowledge Base Index

Welcome to the VoltCraft persistent LLM wiki. This catalog indexes all architectural specifications, physical simulation models, electrical component definitions, crafting recipes, and gameplay mechanics across modular category folders.

---

## 🏛️ Core Idea & System Design
* [[core-idea|VoltCraft Core Idea & Architecture]] — Master foundational vision, core simulation philosophy, mathematical foundations, graph-based grid network architecture, and API package isolation.
* [[implementation-plan|Code Architecture & Implementation Plan]] — Complete Java 21 / Fabric package structure, API contracts, centralized simulation engine design, and 8-phase streamlined development roadmap.

---

## ⛏️ Materials & Metallurgy (`wiki/materials/`)
* [[materials/ores|Natural Ores & Raw Metallurgy]] — World generation stratigraphy, Y-levels, vein distributions, biome preferences, smelting outputs, and specialized alloys (Nichrome, Lead-Tin fuse alloy, Vulcanized Rubber).
* [[materials/electronic-components|Electronic Components & Manufacturing]] — Semiconductor fabrication chain (silicon boules, wafers, doped P/N wafers, PV cells) and discrete electronic parts (Power MOSFETs, Schottky diodes, electrolytic capacitors, magnet wire, transformer cores, BMS PCBs) with recipes.

---

## ⚡ Conductors & Distribution (`wiki/conductors/`)
* [[conductors/cables|Cables & Conductor Metallurgy]] — Conductor resistivity matrix ($\rho$, $\alpha$, $I_{\text{max}}$), Joule heating, insulation melting, contact shock mechanics, and crafting recipes for all 9 cable types.
* [[conductors/switchgear|Switchgear & Protection Hardware]] — High-current copper busbars, junction boxes, manual knife switches, cartridge fuse boxes, resettable circuit breakers, and contactor relays with recipes.

---

## 🔋 Energy Storage (`wiki/storage/`)
* [[storage/electrochemistry|Electrochemistry & Battery Fundamentals]] — Thermodynamic OCV vs SoC curves, C-ratings, primary/secondary cell matrices, CC/CV charging algorithms, BMS balancing, and thermal runaway hazards.
* [[storage/battery-items|Battery Items: Portable Cells]] — 18650 Li-Ion ($3.7\text{V}$, $3000\text{ mAh}$), 21700 high-drain, AA alkaline, zinc-carbon, CR2032 coin cells, NiCd, NiMH; Minecraft 1.21 `DataComponentTypes` and crafting recipes.
* [[storage/battery-blocks|Battery Blocks: Stationary BESS]] — $LiFePO_4$ industrial blocks ($48\text{ V} / 100\text{ Ah}$, fire-safe), sealed lead-acid banks, modular 18650 racks, vanadium redox flow battery stacks & tanks, and molten-salt blocks with recipes.

---

## 🔁 Power Conversion & Inversion (`wiki/conversion/`)
* [[conversion/inverters|Inverters & DC-AC Conversion]] — AC phasor modeling, waveform tiers (pure sine SPWM, modified sine, square wave), THD, off-grid vs synchronous grid-tie (anti-islanding), and hybrid ESS multi-mode inverters with recipes.
* [[conversion/power-converters|Power Converters: DC-DC, Transformers & Rectifiers]] — Buck (step-down), boost (step-up), universal buck-boost/SEPIC, linear LDO regulators, AC step-up/step-down transformers, and bridge/active synchronous rectifiers with recipes.

---

## ☀️ Power Generation (`wiki/generation/`)
* [[generation/solar-panels|Solar Panels & Photovoltaic Generation]] — Shockley diode physics, non-linear I-V/P-V curves, celestial angle calculations, weather/temperature attenuation, MPPT tracking algorithms, string shading bypass diodes, and panel crafting recipes.
* [[generation/generators|Fuel Generators, Dynamos & Renewables]] — Hand crank dynamos ($100\text{ W}$ DC) and compact portable inverter generators ($1.0\text{--}2.0\text{ kW}$ $230\text{V}$ AC pure sine) with recipes.

---

## 🛠️ Tools, Bench & Diagnostics (`wiki/tools/`)
* [[tools/instruments-and-safety|Tools, Diagnostic Instruments & Safety PPE]] — Digital multimeters, clamp meters, oscilloscope tablets, thermal imaging cameras, wire stripper pliers, insulated electrician's gloves ($1000\text{V}$), replacement cartridge fuses, and the stationary **Battery Charger & Diagnostic Bench** with recipes.

---

## Wiki Metadata
* **Maintained By:** Antigravity / LLM Agent
* **Conventions:** See [AGENTS.md](file:///home/ostapyrih/Projects/voltcraft/AGENTS.md) and [llm-wiki.md](file:///home/ostapyrih/Projects/voltcraft/llm-wiki.md)
* **Log:** [[log|Activity Log]]
