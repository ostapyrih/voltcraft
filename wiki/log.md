## [2026-10-02] fix | Player-reported: MPPT backfeed, MPPT minus behaviour, EU buffer never fills + overheat

* **User reports (in-game):** (1) second battery on the MPPT output bus: GUI shows 28.1 V / 7.71 A /
  0.0 W — current flows backwards with power clamped to zero (dishonest telemetry); (2) MPPT minus
  disconnect: current keeps flowing — catch both with tests; (3) generator-fed EU bridge: internal
  buffer never fills, only the total counter grows, bridge overheats.
* **Root Causes & Fixes (production):**
  1. **MPPT backfeed** (`ChargeControllerBlockEntity`): bidirectional Thevenin output sinks pack
     current whenever the bus outranks the charge target; telemetry clamps negative power to 0,
     producing the 0 W-at-7 A lie. Fix: ideal-diode OR-ing — CV mode stages
     `max(E_CV, V_bus)` (lagged one tick like all staging; stable, no loop), so a hotter bank leaves
     the charger idling at ~0 A with honest zeros. CC engages on signed forward current only
     (magnitude-based engage latched the limiter on reverse transients into an over-unity leak);
     short-guard and mismatch-trip take precedence appropriately. Caught by unit T20 (7S bank vs
     12V MPPT: 15.7 A sink → 0.003 A) and a two-phase GameTest (idle-then-trip on a stiff bus).
  2. **EU buffer/thermal** (`EuConverterBlockEntity`): conversion accumulated into a logic-side counter
     that the vanilla tick clobbered back to the (always-zero) TR buffer level every tick — buffer never
     filled, demand never tapered off 805 W, all 805 W became heat → overheat trip. Fix: converted
     energy lands in the TR storage (single source of truth, capacity-clamped); logic counter mirrors
     it. Buffer fills in ~312 ticks, demand tapers to ~5 W, generator unloads, peak ~74 °C. Caught by
     unit T10 (fill + taper + thermal + dark branch) and a buffer-growth assert in the live-feed GameTest.
  3. **Double-ticked discrete phase** (new `simulation/grid/ElectricalTickDedupe.java`): every grid BE ran
     `tickElectrical` twice per server tick (vanilla block ticker + kernel pre-tick), doubling all counters
     (absorption/float timeouts and debounce windows halved, thermal/energy meters doubled). Exactly-once
     claim per (block, world-tick), world-free harnesses unaffected. Base converter staging split into
     guard-wrapper + `doTickElectrical` so overrides route correctly (a naive guard in both silently
     skipped base staging entirely — caught immediately by 5 baseline GameTest failures).
* **Tests:** unit T20 (backfeed), T21/T22 (MPPT output/input minus disconnect — both already clean,
  kept as regression locks), unit T10 (EU buffer/taper/thermal); GameTests: EU live-feed buffer assert,
  MPPT output/input minus disconnect-resume, parallel second battery health, stiff-source blocking +
  late mismatch-trip two-phase, registration-retry helper for placed test blocks. Full unit suite green,
  `runGameTest` 22/22 green three consecutive runs.
* **Wiki:** `generation/solar-panels.md` (backfeed blocking), `conversion/power-converters.md` §4.3
  (single source of truth), `tools/creative-testing.md` (dropout/open-port, earlier).

## [2026-10-02] fix | GameTest failures: architectural root causes (BMS latch, MPPT traps, EU dead seam, generator no-limit, shorted test rig)

* **User Directive:** GameTests catch interesting bugs — fix them architecturally (root causes, not surface patches).
* **Failures in (`runGameTest`, 6/18):** BMS latched open (3 solar tests), MPPT pinned ~0 V / Pout 0–7 W (T18),
  heavy-load cold-start BMS trip, EU bridge 0 W demand on live feed (2 tests), generator serving 2500 W
  silently (no surge cap), cable-churn BMS latch.
* **Root Causes & Fixes (production):**
  1. **BMS false-trip latch** (`BatteryBlockEntity`, `BatteryRackBlockEntity`): protection tripped on
     terminal undervoltage including pre-bootstrap 0 V telemetry and load-induced sag, then could never
     reclose (open terminal follows the dead bus). Now depletion-anchored: healthy SoC-based pack EMF
     forces closed/reclose (black-start capable); terminal-based `bmsNext` applies only when EMF itself
     is below recovery (true depletion); overtemperature still forces open. `bmsNext` contract unchanged.
  2. **MPPT foldback trap** (`ChargeControllerBlockEntity`): output foldback capped EMF from live bus
     voltage, ratcheting collapse to ~0.1 V with no recovery, and the rating foldback had no bootstrap
     gate. Both gated on a formed rail; plus CC/CV regulation (deadband latch + debounced headroom
     release), solar current ceiling keyed on the CV target (lagged-bus keying overshoots on rising bus),
     cable-compensated charge voltage (+0.3 V current-gated headroom + contraction-limited lagged comp),
     absorption-exit debounce (40 ticks) in `MPPTLogic`, mismatch-trip debounce (20 ticks), FLOAT
     re-bulk on charge opportunity (a float-stranded charger with the follow pinning EMF at the bus
     could never recover).
  3. **EU bridge dead seam** (`EuConverterBlockEntity`): `onPowerReceived` was never called, so charge
     demand sat at 0 W forever. Measurement seam wired into `tickElectrical` (input power/current from
     staged demand, frequency from island omega, demand/thermal update); vanilla `tick()` keeps only TR
     moves + hands over moved volume.
  4. **Generator prime-mover limit** (`GeneratorElement.stageEmf`, BE staging): ideal 230 V source served
     any overload silently. Staged EMF holds nominal within surge current, sags to
     `surgeI * R_load` under overload (resistance-keyed: one-step contraction, no limit cycle).
  5. **Open-ported load Newton stall** (`ElectricalElement.requiresReturnPath`, kernel shorting/zeroing
     mirroring the 4-terminal convention): single-wire-cut constant-power loads stalled Newton (40 iters,
     residual floor) freezing telemetry at pre-fault values; now stamp open, telemetry settles 0 W.
  6. **Load brownout dropout** (`CreativeLoadBlockEntity`, staged flag 6 V/10 V hysteresis, P/I modes):
     source-limited rails spiraled into collapse instead of honest hiccup (Law #6 brownout→halt).
  7. **Warm-start across rebuilds** (`GridManager.rebuildIslands`): solved voltages snapshot by position
     and re-staged as new kernels' initial iterate (unknown nodes take island mean), gated on islands
     that retain an active source (seeding a sourceless island sustains phantom nonzero equilibria —
     caught as a hot-swap/fuel-exhaustion regression); rebuilds no longer throw Newton back to the
     loads-open bias lottery.
  8. **AC load nominal fixed 230 V** (`CreativeLoadLogic`): measured-following nominal turned AC loads
     into disguised constant-power, defeating source current limiting; fixed-R is correct resistive physics.
* **Test-harness/spec corrections (not production):** `GameTestCircuitBuilder` output bus was hard-shorted
  (both rails through x=0 column + adjacent cross-net pairs; battery dumped ~940 A into 6.6 mΩ) — rewired
  to provably separated rails (battery moved to (0,1,6), minus rail hops the gap at y=2/y=3); unit T15
  cold-start circuits use heavy copper like the rig (thin wire caps that run at ~930 W Pmax — physically
  undeliverable); T17 cuts the minus rail (a single (+) cut on a ring bus islands nothing); gen
  two-loads test uses 500 W + 400 W (the working EU bridge legitimately draws ~805 W beside them);
  two `SolarAndGenerationPhysicsTest` absorption-exit cases loop past the new 40-tick debounce.
* **Tests & Verification:** full unit suite green (190+); `runGameTest` **22/22 green, three
  consecutive runs** (was 12/18 at start).
  Wiki pages updated: `generation/solar-panels.md` (CC/CV, compensation, debounce),
  `generation/generators.md` (AVR droop), `storage/battery-blocks.md` (BMS philosophy),
  `conversion/power-converters.md` §4.3 (EU seam), `tools/creative-testing.md` (dropout/open-port).

## [2026-09-30] fix | MPPT power output foldback, Newton solver convergence lockup, and single-wire return path verification

* **User Directive:**
  1. Fix MPPT output showing >400W (e.g. 500W–600W+) on a 400W solar panel when connected to a 12V battery and a parallel 2500W load.
  2. Fix circuit lockup/freeze: turning off the 2500W load dropped telemetry to 30W, but turning it back on caused it not to draw any load (0W) until the MPPT was disconnected.
  3. Fix MPPT operating when only the solar panel positive wire (`+`) or negative wire (`-`) was connected without a return wire.
* **Root Causes & Physics Solved:**
  1. **MPPT Power Overdemand & Output Foldback:**
     - In `ConverterElement.stamp`, MPPT output was stamped as an unconstrained Thevenin source ($R=0.05\,\Omega, V_{\text{emf}}=14.4\,\text{V}$). Under heavy parallel load on a 12V bus, current soared to $>50\,\text{A}$ ($>600\,\text{W}$). `AbstractPowerConverterBlockEntity.tickElectrical` previously staged upstream demand as $P_{\text{out}} / \eta + 2.0\,\text{W}$ without capping to available solar generation ($P_{\text{solar}}$), demanding impossible power from a 400W panel.
     - Demanding a 600W constant-power load on a 400W source has no real mathematical solution. Newton-Raphson in `ElectricalKernel.solve()` diverged (`converged = false`), which caused `ElectricalKernel.tick()` to skip element state/telemetry integration, freezing the 2500W load telemetry at 0W until the MPPT was detached.
     - Fixed in [`ChargeControllerBlockEntity.java`](file:///home/ostapyrih/Projects/voltcraft/src/main/java/com/ostapyrih/voltcraft/block/entity/generation/ChargeControllerBlockEntity.java):
       - Dynamically fold back output EMF: $P_{\text{out,max}} = P_{\text{solar}} \times \eta$, $I_{\text{max}} = \min(60\,\text{A}, P_{\text{out,max}} / V_{\text{bus}})$, $V_{\text{target}} = \min(V_{\text{absorption}}, V_{\text{bus}} + I_{\text{max}} \cdot R_{\text{source}})$. Heavy parallel loads now draw extra current from the battery instead of collapsing the solar panel.
       - Clamped `stagedInputDemandWatts` to `availSolar`.
  2. **Topological Return Path Verification (Single-Wire Phantom Current):**
     - When only one wire was connected between a solar panel and MPPT, the internal admittances formed an open chain. In MNA, grounding an arbitrary node in that component destroyed current conservation on Norton injections, driving phantom current through the ground reference.
     - Added `hasReturnPath(termA, termB, excludeElementIndex, excludePort)` in [`ElectricalKernel.java`](file:///home/ostapyrih/Projects/voltcraft/src/main/java/com/ostapyrih/voltcraft/simulation/grid/ElectricalKernel.java) to verify topological return circuits. If a 4-terminal converter's input port has no closed return path, Port 0 degenerates its stamp and clamps input voltages and currents to 0.0, strictly enforcing cut-set laws.
* **Tests & Verification:**
  * Added `testMPPTOutputFoldbackUnderHeavyParallelLoad` in [`AdapterConvertersTest.java`](file:///home/ostapyrih/Projects/voltcraft/src/test/java/com/ostapyrih/voltcraft/simulation/grid/AdapterConvertersTest.java).
  * Full test suite green (162/162 tests pass cleanly).

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

