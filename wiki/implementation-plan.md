### Grid-rehaul: kernel-island simulation core (SHIPPED, replaces the old DC/MNA description)

* ✅ Single solve point `simulation/grid/ElectricalKernel.java` — Newton solve over complex `Y·V = I`, returns `KernelSolveResult` (`voltage`, `converged`, `singular`, `residual`, `newtonIterations`, `linearEliminationSteps`, `fallbackActive`); one `tick()` = one solve + RK2 at `DT = 0.05 s`, gated on `converged && !singular`. Frozen constants in `api/electrical/GridConstants.java` (`NEWTON_MAX_ITER = 40`, `NEWTON_TOL = 1e-6`, `NEWTON_MAX_STEP = 50.0 V`, `NEWTON_RESIDUAL_FLOOR = 1e-10`, `LINEAR_RESIDUAL_TOL = 1e-9`, `AMBIENT_C = 20.0 °C`, `AC 50 Hz`).
* ✅ `GridManager` (~950 lines) builds deterministic electrical islands (union-find over cable adjacency + same-block terminal ownership, `POS_ORDER` ordering), holds per-island kernels/voltages/omega, handles transitions via position-matched warm-start carryover (sourced islands only — sourceless islands keep the loads-open bias so removal/fuel-exhaustion goes dark). The old voltage-snapshot logic and island rebuild path were removed; persistence is cables-only (`PERSISTENCE_KEY = "voltcraft_power_grids"`).
* ✅ Phasor math in the complex domain: `api/electrical/Complex.java`, `api/data/Phasor.java`, `api/electrical/Conductor.java`. Per-island `omega`: `0` for DC, `2π·50` with an active AC source. New files `IslandContext.java` (island snapshot contract), `ElectricalTickDedupe.java` (exactly-once discrete phase per tick), `CableConductorAdapter.java` (cable `0.001 Ω` default / terminal-link `0.0001 Ω` never-melting).
* ✅ `BatteryElement` — Thevenin equivalent (`V_term = V_emf(SoC) − I·R_pack`, `[SoC, T, health]` state, open-BMS `1 MΩ`, `60 °C` open / `55 °C` reclose, `1e-4 Ω` pack-R floor). `ConverterElement` — 4-terminal (staged demand in / staged EMF out, `0.05 Ω`, UVLO/thermal/overvoltage latches, AC-island resistive approximation `R = Vnom²/P`).
* ✅ MPPT — real P&O tracker (`MPPTLogic`) with Bulk/Absorption/Float and output-current foldback (`P_out,max = P_solar·η`, `I_max = min(60 A, P_out,max/V_bus)`); unpowered outputs stamp open (0 A at 0 W, no phantom current).
* ✅ Energy Bridge `converter_eu` — 230 V AC (`207–253 V`, `f ≥ 40 Hz`) ↔ E (`25 W → 1 E/t`, `10 kE` buffer, `512 E/t` max push, thermal + surge latches) via `EuConverterLogic`.
* ❌ Deleted and must not be referenced as current: `ElectricalGrid`, `GridNode`, `GridConductor`, `GridTopologyHelper`, `ModifiedNodalAnalysis`, `ACSolver`, `api/energy/*` (empty legacy dir), old `api/grid` legacy types. Full kernel contract: `docs/kernel.md`.

### Phase 5: Power Conversion & Inversion (COMPLETED)
* Implemented DC-DC converters, AC transformers, AC-DC rectifiers, and DC-AC inverters with isolated multi-port bridge entity architecture, 32-bit packed word telemetry, and interactive industrial tuning GUI.
* **Storage & Conversion Updates Completed:**
  * **Thermodynamic EMF Charging Threshold:** Batteries strictly reject charging current when applied terminal voltage $V_{\text{terminal}} \le V_{\text{emf}}$ ($\Delta\text{SoC} = 0$).
  * **Overvoltage Thermal Runaway Hazard:** High overvoltage ($V_{\text{terminal}} > 1.05 \times V_{\text{max}}$) triggers destructive Joule heating ($V_{\text{excess}} \cdot I$) driving cells into thermal runaway and detonating the block.
  * **Cell Temperature Tracking & Cooling:** Added dynamic cell temperature modeling with ambient passive convective cooling down to 20°C and live temperature telemetry in chat and HUD.
  * **Inverter DC Input Modes:** Added switchable nominal input voltage modes (12V, 24V, 48V) with dynamic under-voltage lockout (UVLO: 10V, 20V, 40V) and overvoltage protection (OVP: 16.5V, 33V, 66V).
  * **Interactive GUI Presets & Telemetry:**
    * DC-DC Converters: Restricted to discrete industrial standard presets `[5V]`, `[12V]`, `[24V]`, and `[48V]` with active indicator (e.g. `► 12V ◄`) and removed arbitrary free-tuning controls.
    * Inverters: Dedicated `[12V In]`, `[24V In]`, and `[48V In]` configuration buttons with active input voltage display and dynamic UVLO/OVP limits.
    * Fixed client/server initialization race condition in `ConverterScreenHandler` and `ConverterScreen` by pre-populating client property delegate from block entity and making control rebuilding reactive across ticks.
    * Direct C2S network packets (`SetConverterVoltagePayload` and `SetConverterInputVoltagePayload`) for instant zero-latency UI synchronization.
    * Signed 32-bit packed telemetry across network delegates to eliminate 16-bit negative overflow on power > 3276.7W.
    * Expanded GUI width to 300px HUD with dedicated 3-column input, system, and output panels.
    * Dedicated GUIs and screen handlers for Creative Load and Creative Generator.
    * Registered all Phase 6 Solar PV blocks and generator blocks into the `VoltCraft Power Grid` creative item group tab.

### Phase 6: Photovoltaics, MPPT & Realistic Circuit Dynamics (COMPLETED)
* **Real Circuit Physics & Instantaneous Response:**
  * Removed artificial slow demand slew ramping (`slewDemandUp` +10%/tick and cap growth delays). Inverters and converters respond instantaneously to connected loads as in real electronics.
  * **Battery Terminal Voltage Sag:** Ohm's law terminal drop ($V_{\text{terminal}} = V_{\text{ocv}} - I \cdot R_{\text{pack}}$) accurately models battery sag under high current discharge (e.g. 8000W inverter on high-internal-resistance packs).
  * **Brownout Foldback ("Use What It Has"):** Overloaded inverters and converters fold back output voltage ($V_{\text{out}} = P_{\text{available}} / I_{\text{out}}$) entering `ElectricalState.BROWNOUT`, delivering available power without tripping or cycling on/off.
  * **Debounced UVLO with Cooldown Latch:** Replaced 20Hz rapid chatter with a 4-tick debounced trip and 60-tick (3s) latch cooldown with voltage hysteresis.
  * **MPPT Charge Controller & Battery Bus Realism:**
    * **Zero Phantom Power at 0W Solar:** `calculateAvailableOutputCurrent()` strictly returns `0.0A` when `availSolar == 0.0` or `inputVoltage <= 1.0`, completely eliminating phantom 60A output current when unpowered or at night.
    * **MNA Parallel Current Source Injection (historical — pre-rehaul, now superseded by the kernel islands above):** In the old `ElectricalGrid.java`, unpowered MPPT outputs were stamped as open circuits so they neither sink nor leak reverse current. During active charging with downstream storage, Bulk charge stage injected available solar current directly ($I = \frac{P_{\text{solar}} \times \eta}{V_{\text{bus}}}$). The battery bus voltage was determined strictly by battery $V_{\text{ocv}} + (I_{\text{solar}} - I_{\text{inverter}}) \cdot R_{\text{pack}}$, preventing artificial Thevenin EMF inflation. ✅ Shipped equivalent today: `ChargeControllerBlockEntity` ideal-diode OR-ing (`max(E_CV, V_bus)`), signed-forward CC, foldback-capped EMF, plus kernel open-port isolation (`requiresReturnPath`).
    * **Downstream Storage Detection & 3-Stage Charging:** When no battery is present, the MPPT acts as a regulated power supply matching the configured preset. When battery storage is present, it executes Bulk/Absorption/Float matched to the nominal bank.
    * **Battery Bank Presets & Mismatch Protection:** MPPT screen supports `[12V Bank]`, `[24V Bank]`, and `[48V Bank]` presets. Connecting an MPPT set to 12V to a 24V/48V battery bank trips protective shutdown to prevent cross-voltage destruction.
    * **MPPT GUI Controls:** Dedicated `MPPT Charger` dashboard with active bank selection (`► 24V Bank ◄`), nominal bank telemetry, and trip reset.
  * **Surge Recovery:** Batteries and battery racks automatically recover from `ElectricalState.SURGE` to `ElectricalState.NOMINAL` when receiving controlled DC charging within safe voltage thresholds.
  * **Rotary Energy Bridge (230V AC to E interop):**
    * Converts 25W continuous real AC power into 1 E/t ($P / 25.0$ E/t) using TeamReborn Energy API (`EnergyStorage.SIDED`) — the standard Fabric energy transport (1 E bridged 1:1 with FE by interop mods), usable by any energy consumer, not IC2-locked.
    * Strictly requires 230V AC ($207\,\text{V} \le V \le 253\,\text{V}$, $f \ge 40.0\,\text{Hz}$).
    * DC waveforms ($f < 40\,\text{Hz}$) and brownouts ($V < 207\,\text{V}$) produce 0 E; overvoltage ($V > 253\,\text{V}$) trips protection.
    * Decoupled `EuConverterLogic` handles energy buffering (10,000 E capacity, 512 E/t max push), thermal dissipation, and fractional power accumulation.
    * Interactive client HUD (`EuConverterScreen`) displays real-time input telemetry, conversion efficiency, buffer state, and trip reset.


### Phase 7: Diagnostic Instruments, Modular Tools, Bench & PPE (QUEUED)
* Multimeter, Oscilloscope tablet, Clamp meter, Thermal camera.
* Insulated gloves, Wire strippers.
* Battery charging bench and diagnostics suite.
