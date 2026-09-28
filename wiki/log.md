## [2026-09-27] refactor | EU converter rebranded to standard E energy bridge

* **User Directive:** No new block. Change EU converter to a standard every mod can use.
* **Decision:** Wire protocol stays TeamReborn Energy API — it IS the standard Fabric energy transport (FE has no API on Fabric; interop mods bridge 1 E ≈ 1 FE). Dropped IC2-specific EU branding: block/item renamed to `Rotary Energy Bridge (230V AC to E)`, HUD labels EU→E (also fixed stale 100W spec label to 25W). Registry IDs (`converter_eu`) and class names unchanged for save/API compat.
* **Tests & Verification:** `EuConverterTest` + `RecipeValidationTest` green; datagen rerun for lang.

## [2026-09-27] fix | EU converter retuned 100W to 25W per EU/t

* **User Directive:** Downgrade conversion from 100W to 25W per EU/t. Fixes 1500/3000W inverter brownout oscillation.
* **Root Cause:** Empty buffer demanded 3205W (`32*100+5`), exceeding 1500/3000W inverter ratings, causing V sag below 207V and accumulator reset loop. New empty-buffer demand is 805W (`32*25+5`), fits all inverters; 5000W unit now runs cooler.
* **Changes:** `EuConverterLogic.WATTS_PER_EU_TICK` 100.0 -> 25.0; updated `EuConverterBlock`, `EuConverterBlockEntity` javadoc, `EuConverterTest` expectations (25W->1EU, 800W->32EU, 805/1055/130W demand), `wiki/implementation-plan.md`.
* **Tests & Verification:** `EuConverterTest` green (9/9).

## [2026-09-27] feat | Rotary EU Converter (230V AC to EU interop)
* **User Directive:** Add block for 230V AC to EU conversion where 100W continuous converts to 1 EU/tick (TeamReborn Energy API / TechReborn interop). Strictly only 230V AC must convert.
* **Architecture & Physics:**
  1. **Strict 230V AC Input Gate:**
     * AC input port is exclusively located at the rear face (`facing.getOpposite()`).
     * Grid frequency must satisfy $f \ge 40.0\,\text{Hz}$. DC waveforms ($f = 0\,\text{Hz}$) immediately drop power conversion to 0 EU and display `DC REJECT`.
     * Input voltage operating window: $207.0\,\text{V} \le V \le 253.0\,\text{V}$ (nominal 230V $\pm 10\%$). Brownouts ($V < 207\,\text{V}$) yield 0 EU. Overvoltage ($V > 253\,\text{V}$) activates surge trip latch and blocks operation until reset.
  2. **Physical Conversion Math & Buffering:**
     * Conversion formula: $P_{\text{delivered}} / 100.0$ EU/tick.
     * Fractional accumulation handles fractional power across game ticks.
     * Dynamic upstream power demand: $P_{\text{demand}} = (E_{\text{needed}} \times 100.0) + 5.0\,\text{W}$ where needed EU considers downstream load and buffer deficit.
     * Buffer capacity of 10,000 EU with up to 512 EU/t extraction on non-input faces via `team.reborn.energy.api.EnergyStorage`.
  3. **Decoupled Simulation Engine (`EuConverterLogic`):**
     * Simulation logic cleanly decoupled from Minecraft registry lifecycle, enabling comprehensive JUnit test coverage without game bootstrap overhead.
  4. **Dedicated Telemetry GUI (`EuConverterScreen`):**
     * Industrial slate HUD rendering real-time AC input metrics ($V, I, P, f$), conversion status LEDs (`CONVERTING`, `DC REJECT`, `BROWNOUT`, `TRIPPED`, `STANDBY`), buffer gauge, and trip reset controls.
  5. **Crafting & Datagen:**
     * Added recipe using Gold Bus Cable, Power MOSFET, Laminated Transformer Core, Heavy Copper Cable, Iron, and Redstone. Generated all blockstates, models, loot tables, and recipe JSONs.
* **Tests & Verification:**
  * Added `EuConverterTest` covering 100W->1EU/t, 3200W->32EU/t, DC waveform rejection, brownout rejection, overvoltage surge trip, buffer replenishment, and energy extraction. Full suite green (91/91 tests pass).

## [2026-09-27] fix | MPPT parallel battery isolation, 0W solar phantom current elimination & battery bank presets
* **User Directive:**
  * Fix MPPT acting as a voltage source in parallel with batteries and combining EMF/voltage with battery to downstream inverters.
  * Fix changing target voltage presets on MPPT doing nothing.
* **Root Causes & Physics Solved:**
  1. **Phantom 60A Output Current at 0W Solar:**
     * `calculateAvailableOutputCurrent()` previously defaulted to `60.0A` when `availSolar == 0.0` or `inputVoltage <= 1.0`, effectively acting as a permanent 60A phantom power source when unpowered or at night. Fixed: now strictly returns `0.0` Amperes when unpowered or when no solar power is generated.
  2. **Thevenin EMF Bus Inflation on Battery Rails:**
     * When MPPT was connected in parallel with a battery, stamping the MPPT as a 28.8V/57.6V Thevenin source with 0.05Ω internal resistance distorted Pass 1 of the MNA solver, inflating the DC bus voltage and presenting combined artificial voltage/currents to downstream inverters.
     * Fixed: In `ElectricalGrid.java`, non-storage sources with `avail <= 1e-4` (unpowered/night MPPT, tripped converters) are stamped as open circuits (`openSources`), preventing reverse drain or forward leakage.
     * In Pass 2, when `isCurrentRegulated()` is active (e.g. MPPT in Bulk mode injecting available solar power), the MNA corrector stamps it as an ideal current source ($I = \frac{P_{\text{solar}} \times \eta}{V_{\text{bus}}}$). The battery terminal voltage is preserved strictly according to Ohm's law ($V_{\text{bus}} = V_{\text{ocv}} + (I_{\text{solar}} - I_{\text{inverter}}) \cdot R_{\text{battery}}$) without artificial EMF inflation.
  3. **MPPT Voltage Preset Delegation & Multi-Stage Charging:**
     * `AbstractPowerConverterBlockEntity.propertyDelegate.set` previously directly wrote to `targetOutputVoltage`, bypassing polymorphic setters. Changed to `setTargetOutputVoltage(value / 10.0)`.
     * In `ChargeControllerBlockEntity`: `computeOutputVoltage` now checks downstream storage presence. When standalone without batteries, it regulates directly to the chosen preset (12V, 24V, 48V). When connected to a battery bank, it applies 3-stage charging (Bulk/Absorption/Float) matched to the selected nominal bank.
     * Added battery voltage mismatch protection (`isBatteryVoltageMismatch`): if user sets 12V MPPT preset on a 24V/48V battery bank, the controller safely trips offline and delivers 0A instead of damaging batteries or blowing back into solar panels.
  4. **MPPT Charger Screen UI:**
     * Assigned `ChargeControllerBlockEntity.getTypeKind()` to `ConverterScreenHandler.TYPE_CHARGE_CONTROLLER` (4).
     * `ConverterScreen` now displays `[12V Bank]`, `[24V Bank]`, and `[48V Bank]` buttons with active selection highlight (`► 24V Bank ◄`), header title `Battery Bank: %dV Nominal Bank`, and system moniker `MPPT Charger`.
* **Tests & Verification:**
  * Added unit tests in `CircuitDynamicsRealismTest`:
    * `testMpptZeroSolarProducesZeroCurrentAndNoVoltageInflation`: verifies MPPT produces 0A and inverter draws 100% from battery without voltage distortion.
    * `testMpptControlledCurrentInjectionDoesNotDistortBatteryBusVoltage`: verifies MPPT current injection charges battery per $V_{\text{ocv}} + I \cdot R_{\text{int}}$ without jumping to absorption EMF.
  * All 82 test cases pass cleanly (100% green).

## [2026-09-28] audit | Full codebase-vs-wiki reconciliation (56 blocks / 91 items / 103 recipes)

* **Scope:** Exhaustive diff of `src/main/java`, `src/client/java` (datagen), `src/main/generated`
  (recipes, models, lang, loot) against all 13 wiki content pages. No code changed; wiki only.
* **Inventory established:** 56 blocks (`VoltcraftBlocks.ALL_BLOCKS`: 12 ores, 9 cables, 6 switchgear,
  6 BESS, 4 DC-DC, 2 transformers, 2 rectifiers, 5 inverters, 1 energy bridge, 7 generation, 2 creative);
  91 items (56 block-items + 35 standalone: 5 raw, 8 refined, 3 alloy/polymer, 5 semi, 6 discrete, 8 cells);
  13 `BlockEntityType`s (cables/switchgear/ores have none by design); 103 generated recipes
  (57 shaped, 10 shapeless, 18 smelting + 18 blasting); 3 item groups; 5 `DataComponentTypes`.
* **Fixes applied per page:**
  * `conductors/cables.md`: replaced $\rho$-matrix with exact `ConductorType` $R_0$/block, $\alpha$,
    $I_{\max}$, insulation/melt temps, shock/insulated flags + thermal mass/cooling; bare Cu yield
    12x→**6x** (single-row `CCC`); steel-typo fix; superconductor ∞A/quench→**100 kA** Nether-Star recipe;
    fuse/graphite/contact-shock marked design-only.
  * `materials/ores.md`: vein ranges→exact sizes (8/7/6/5/6/6) + veins/chunk; **no biome filter**
    (`BiomePlacementModifier.of()` all-biomes); removed galena silver-nugget bonus (loot drops raw only);
    smelting section rewritten (36 furnace/blast JSONs, 0.7 XP, reversible 9x silver, 0.35 XP rubber);
    nichrome 5x→**2x** (Ni+Fe), fuse alloy 3x→**2x** (Pb+Zn), rubber shapeless coal/charcoal 2x variants.
  * `materials/electronic-components.md`: boule coal-only (no charcoal/furnace variant); wafer =
    boule+iron_ingot 8x (no saw/durability); doped wafers shapeless no-nugget (redstone/glowstone);
    PV/MOSFET/schottky/cap/BMS patterns corrected to exact JSON keys; schottky $0.3\text{V}$→code $0.7\text{V}$/diode.
  * `generation/solar-panels.md`: thin-film corrected to **70 V/3.57 A/88 V/4.0 A**, poly $I_{sc}$ 10.2→**10.1 A**,
    CPV $V_{oc}/I_{sc}$→**60 V/15.0 A**; exact weather factors (CPV 0 in rain, thin-film 0.40/0.20);
    exact temp coefficients; MPPT P&O + absorption 14.4 V/float 13.6 V per 12 V, 1200-tick timeout,
    0.2 A exit, presets + mismatch bands, 0 A-at-0 W, 2 W housekeeping.
  * `generation/generators.md`: hand crank decay 0.94→**0.97**, +0.35/crank, EMF $= v \times 13.8\text{ V}$,
    $R_{int} = 0.15\,\Omega$, exact back-EMF damping, exhaustion confirmed, ratchet sound marked spec-only;
    portable fuel rewritten as generic `FuelRegistry` ticks (examples, not constants), $R_{int}$/surge/eco-throttle.
  * `storage/electrochemistry.md`: chemistry tables replaced with exact `BatteryChemistry` values
    (V windows, mAh, C-rate/max A, $R_{int}$, runaway $T$, cycles); Na-S/VRFB/Ag₂O/Hg marked design-only;
    `DataComponentTypes` corrected to 5 (incl. `battery_bay`, `battery_cell_chemistry`).
  * `storage/battery-items.md`: per-cell $R_{int}$/C-rate/cycles/runaway added; all 8 recipes replaced
    with exact `ACA/LSK/AIA`-style patterns; `maxCount = 16` + runaway explosion noted.
  * `storage/battery-blocks.md`: exact series/parallel configs + computed V/Ah/kWh/cycles
    (LFP 15S1P 48 V/100 Ah/4000 cyc; Lead 6S1P 12 V/120 Ah/500; LTO 10S1P 24 V/60 Ah/15000;
    NiMH 20S20P 50 Ah/800; NiCd 20S25P 30 Ah/1000); rack 16 bays; AC-abuse temps marked spec-only.
  * `conversion/inverters.md`: new exact `InverterType` table (THD/eff/W) + 12/24/48 V UVLO/OVP modes;
    all 5 recipes replaced with exact JSON patterns (old transformer-core/comparator/steel text removed).
  * `conversion/power-converters.md`: new exact `ConverterType`/`TransformerType`/`RectifierType` tables;
    LDO shapeless-2x→**shaped 1x** (`AAA/RMR/CEC`); all 8 recipes replaced with exact patterns
    (incl. rectifier_bridge `RDR/DED/CDC` correction); new §4.3 documents `converter_eu` Energy Bridge
    (25 W→1 E/t, 10 kE, 512 E/t, 207–253 V / ≥40 Hz, trip/thermal latches, `GMG/TLT/IRI` recipe).
  * `conductors/switchgear.md`: implementation banner (6/6 blocks, no BE, no `fuse_cartridge_*` items,
    amp ratings spec-only); junction/knife/fuse/breaker/contactor patterns verified (contactor uses
    laminated core + repeater; fuse box uses `fuse_alloy_ingot`).
  * `tools/instruments-and-safety.md`: ❌ banner — **0 tool items implemented** (Phase 7 QUEUED); page kept as design spec.
  * `tools/creative-testing.md`: ✅ banner; generator presets extended (1000/10000 V/A, 6 $R_{int}$ steps,
    no 400 Hz); load presets replaced with exact 11/10/11 arrays; §4 commands rewritten to actual
    raycast syntax (`/voltcraft generator <V> [A] [Hz]`, `/voltcraft load resistance|power|current <v>`).
  * `index.md`: status tags (✅/❌), exact counts, creative-testing entry added, `machinery/` + missing
    energy-bridge page gap noted.
* **Verification:** edits are wiki-markdown only; no build run (no code touched). Counts cross-checked via
  `grep`/`cat` of generated JSONs, enum sources, loot/worldgen providers, and `VoltcraftCreativeCommands`.

