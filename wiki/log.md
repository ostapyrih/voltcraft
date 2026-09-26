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

