# Electrical kernel (`ElectricalKernel`)

Central nodal-analysis kernel: owns all element state and performs the full
Newton solve. Elements and conductors are lightweight topological descriptors;
all voltages, states, and iteration live in the kernel.

## Fallback region and `fallbackActive` flag

- Fallback exists for numerical stability, not physical accuracy.
- `fallbackActive` reports whether the numerical crutch was active at the
  converged operating point (final iteration only).
- The framework does not act on this signal (tick still integrates when
  `converged==true`; see Phase 3 test 24b note).
- Adapter layers are responsible for interpreting `fallbackActive` (skip
  persistent state, trigger protection, display warning, etc.).

Mechanism notes:

- `constantPower` / `constantCurrent` fall back when `v < vMin`
  (`constantPower` also when `v0 <= 1e-6`): a resistive admittance
  (`P/vMin²`, respectively `Iset/vMin`) with zero parallel current replaces
  the physical linearization for that build.
- `oneWayThevenin` has no `vMin`; its numerical crutch is the derivative
  floor `1e-9·g` in `dIdv = max(g·sigmoid(x/s), 1e-9·g)`. Fallback is reported
  when the floor is active (`g·sigmoid(x/s) < 1e-9·g`).
- Tracking uses a static `ThreadLocal<Boolean>` flag in `Stamps`
  (`clearFallbackFlag` / `isFallbackFlagSet`; stamps mark via a private
  `markFallback()` in their fallback branches only). The `ThreadLocal`
  keeps concurrent solves on different threads from interfering.
- The kernel clears the flag immediately before each final-system (`Yf`/`If`)
  build and reads it right after, so the reported value reflects the final
  iteration's returned `V`. Trial (`Ytry`) builds during backtracking may set
  the flag, but it is overwritten by the clear before `Yf` and never pollutes
  the result. If the loop breaks on a non-finite `Vnew` before any `Yf` build,
  the last `Yf` value is kept (`false` if no `Yf` ran yet). `buildSystem`
  itself never clears the flag; the kernel owns clear/read.

## Phase 3 — state integration and conductor thermal

- `tick()`: exactly one `solve()` per tick (item 15). Returns immediately
  when `!converged || singular` (item 6); the gate ignores
  `fallbackActive`, so a converged fallback operating point still
  integrates (the framework does not act on the flag).
- Element state uses RK2 midpoint at the SAME operating point for both
  stages (item 15): `Vt`/`It` are built once per element per tick from the
  converged `V`; `k2` reuses the same values via fresh clones. Item 28
  purity: the kernel builds fresh `Vt`/`It` per element, passes only clones
  into `derivatives`, evaluates `k2` on a `mid` copy (never an alias of
  kernel state), and mutates kernel-owned state solely in the final
  `state[i] += dt*k2[i]` update. Zero-length state is a no-op.
- Conductor thermal uses RK2 with resistance read once per tick per
  conductor (item 20); `powerLossW = |dV|²/R` is identical in both stages
  because both use the same `V` — only the cooling term is re-evaluated at
  the midpoint temperature.
- `terminalCurrents(i)`: current represented by the converged stamp
  (item 14), positive entering the element. Item-22 formula on fresh local
  arrays with local indices `0..k-1`:
  `It[j] = Σ_m Yl[j][m]*Vt[m] − Il[j]`; no global GMIN or other element
  contributes. The local stamp's fallback flag is discarded (cleared before
  and after) so `solve()`'s `fallbackActive` is unaffected. The
  `terminalCurrents(i, V)` overload takes a supplied operating point;
  `integrateElements` uses it with the tick's `V` to guarantee the same
  operating point without a `lastSolution` round-trip.
- `findMeltedConductors()`: strict `temperature() > meltingTemp()`
  (not `>=`), side-effect free (item 13); returns a new list every call.
- `lastSolution` is unchanged by Phase 3 queries (item 31): only `solve()`
  writes it (`tick` integrates from the returned vector directly).

## Solver status (`ComplexNodalSolver`)

- Solves `Y·V = I` by dense Gaussian elimination with max-magnitude
  partial pivoting on defensive copies (caller arrays never mutated).
- Frozen pivot semantics: after the pivot search a pivot is singular when
  its magnitude is exactly zero or non-finite — no tolerances. Row swaps
  and the pivot search itself are never counted. An elimination factor
  `f` that is exactly `0.0` performs no row update and is not counted;
  any other `f` performs one counted row update.
  `linearEliminationSteps` counts only actual row updates.
- Back-substitution never divides by a zero/non-finite diagonal: such
  rows yield `x[i] = ZERO` and iteration continues, so a singular system
  still returns a finite voltage vector (no NaN/Inf; Phase 1 test 7,
  Phase 4 test 32).
- `SolveResult`: `voltage` (last iterate), `converged =
  !singular && isFinite(residual) && residual < LINEAR_RESIDUAL_TOL`
  (`1e-9`), `singular`, normalized residual
  `||YV−I|| / max(||I||, ||YV||, 1)` (Euclidean norms over complex
  magnitudes), `linearEliminationSteps`.
- Invariant: singular implies `!converged` (Phase 4 test 32).
- Helpers `zeroMatrix(n)` / `zeroVector(n)` reject `n < 0`;
  non-square `Y`, `Y/I` length mismatch, and null entries throw
  `IllegalArgumentException`.

## GMIN shunt

- Every nodal diagonal receives a real `GMIN = 1e-9` S shunt on every
  system build (`buildSystem`), including the final `Yf` build and every
  backtracking trial build. It is real and power-relevant: Tellegen
  balance must include `P_GMIN = GMIN·Σ|V|²` (Phase 2 test 14).
- GMIN pins floating/disconnected nodes to finite voltages but carries
  no reference EMF (cf. Phase 1 test 5 vs Phase 2 test 15).

## Newton loop (`ElectricalKernel.solve`)

- Constants: `NEWTON_MAX_ITER = 40`, `NEWTON_TOL = 1e-6` (max voltage
  delta), `NEWTON_MAX_STEP = 50.0` V (per-iteration step clamp),
  `NEWTON_RESIDUAL_FLOOR = 1e-10`, `LINEAR_RESIDUAL_TOL = 1e-9`.
- Each iteration: build `(Y, I)` at `V`, record `resBefore`, one linear
  solve (every call counted in `newtonIterations`, singular ones
  included; elimination steps accumulate into
  `linearEliminationSteps`). A non-finite `Vnew` breaks the loop
  immediately. Otherwise the step is clamped to `NEWTON_MAX_STEP` via
  `alpha = min(1, MAX_STEP / max(maxDelta, 1e-9))` and line-searched:
  up to 4 trials, `alpha` halved after each rejection.
- Backtracking acceptance: a trial is accepted when `rTry <= resBefore`
  or `rTry < NEWTON_RESIDUAL_FLOOR`; trial #4 (`bt == 3`) is accepted
  unconditionally, guaranteeing progress.
- After the step, the final system `(Yf, If)` is rebuilt at the new `V`
  (with the fallback flag cleared before and read after) and
  `residual = normalizedResidual(Yf, If, V)`.
- Convergence requires `maxDelta < NEWTON_TOL && !singular &&
  isFinite(residual) && residual < LINEAR_RESIDUAL_TOL`. Any singular
  iteration forces the final result to `converged = false`, even if a
  later iteration looks converged.
- Pure linear networks converge in 2 iterations (Phase 4 test 30
  asserts `< 5`); nonlinear CP fixtures converge within 20 from a warm
  start (Phase 2 tests 11, 13).
- `n == 0` short-circuits to an empty, converged, non-singular result.

## Stamps sign convention

- `admittance(Y,a,b,g)`: `Y[a][a]+=g; Y[b][b]+=g; Y[a][b]-=g; Y[b][a]-=g`.
- `draw(I,a,b,i)`: `I[a]-=i; I[b]+=i`. Positive `i` flows from `a` to `b`.
- `thevenin(Y,I,a,b,g,emf)`: series admittance `g` with EMF `emf`,
  positive terminal `a`; Norton equivalent = `admittance(g)` plus
  current source `g*emf` injected into `a` (drawn from `b`).
- `powerInto(Vt,It,from,to) = Σ Re(Vt[k]·conj(It[k]))`, positive when
  the element consumes. Throws on `Vt/It` length mismatch, null entries,
  or invalid `[from,to)` ranges.
- Node-index and `Y/I` size checks throw `IllegalArgumentException`.
- Sign audit (Phase 4 test 29): in a closed series loop the source shows
  `It[0] < 0` (delivering, negative entering at the positive terminal)
  and the load shows `It[0] > 0` (consuming). The audit needs a true
  loop: a source with a floating terminal is an open circuit (only
  GMIN-scale leakage flows), so the fixture closes the loop with a
  return conductor.

## DC-only nonlinear primitives

- `constantPower`, `constantCurrent`, `oneWayThevenin` throw
  `IllegalArgumentException` on any `omega != 0.0` (Phase 2 test 17,
  Phase 4 test 31, e.g. `314.159`). All kernel uses of these stamps run
  at `setOmega(0)`; purely linear tests are omega-agnostic.
- `constantPower` (draws `P/v` from `a` toward `b`): linearization
  `I(v) ≈ (−P/v0²)·v + 2P/v0`, i.e. `g = −P/v0²`, `iEq = 2P/v0`,
  stamped as `admittance(g) + draw(iEq)`. Fallback (`v0 < vMin` or
  `v0 <= 1e-6`): resistive `R = vMin²/P` (`admittance(P/vMin²)`, zero
  parallel current). Overload with no physical root converges onto the
  fallback point with `fallbackActive == true` and `V_load < vMin`
  (Phase 2 test 12); a normal point reports no fallback (test 12b).
- `constantCurrent` (draws `iSet` from `a` toward `b`): pure current
  source at/above `vMin`; fallback below `vMin` is a resistive
  `iSet/vMin` admittance with zero parallel current. Stamp-level sign:
  nodal `I = [−iSet, +iSet]`, element currents `It = [+iSet, −iSet]`
  (Phase 2 test 18).
- `oneWayThevenin` (`g` S, `emf` V, softness `s` V): smoothed diode via
  softplus/sigmoid; `sourceOnly=true` delivers below `emf` and blocks
  reverse, `false` sinks above `emf` and blocks below. Stamp is the
  Newton linearization `iEq = i0 − dIdv·v0` as
  `admittance(dIdv) + draw(iEq)` with derivative floor `1e-9·g`.
  No `vMin`; fallback is reported when the floor is active
  (`g·sigmoid(x/s) < 1e-9·g`). Stamp-level signs verified in Phase 2
  test 16 (forward delivery `i0 ≈ −g·(emf − s·ln2)`, blocked reverse
  `|i0| ≈ g·s·ln2`, load-orientation sink).
- Input validation for nonlinear primitives (item 27): `constantPower`
  requires finite `P > 0`, finite `vMin > 0`; `constantCurrent`
  requires finite `Iset > 0`, finite `vMin > 0`; `oneWayThevenin`
  requires finite `g > 0`, finite `s > 0`, finite `emf`; all three
  require `omega == 0.0` (Phase 2 test 19).

## State ownership

- The kernel owns all element state (`double[]` slots sized by
  `stateCount()`); elements hold no persistent state and perform no
  serialization.
- `setElements` clears all state to fresh zero arrays and resets
  `lastSolution` to null. State preservation across topology replacement
  is the adapter's job: snapshot with `getElementState(i)` before,
  restore with `setElementState(i, ...)` after.
- `setElementState` rejects bad indices and length mismatches;
  `getElementState` / `getLastSolution` return defensive copies
  (`getLastSolution` returns null when no solution exists yet).
- Bad element indices throw `IllegalArgumentException` everywhere
  (including both `terminalCurrents` overloads); `terminalCurrents(i)`
  additionally throws `IllegalStateException` before the first solve.

## Tick and element integration

- `tick()`: exactly one `solve()` per tick (item 15) with the fixed step
  `DT = 0.05` s. Returns immediately when `!converged || singular`
  (item 6), leaving element state and conductor temperatures
  byte-identical (Phase 3 test 24). The gate ignores `fallbackActive`:
  a converged fallback point still integrates.
- Element state uses one RK2 (midpoint) step at the SAME operating point
  for both stages (item 15): per element, `Vt` is gathered fresh from
  the converged `V` and `It` is computed once via the item-22 formula,
  so `k2` reuses the same values. Item-28 purity: fresh `Vt`/`It` per
  element, only clones passed into `derivatives`, `k2` evaluated on a
  `mid = state + 0.5·dt·k1` copy (never an alias of kernel state), and
  kernel state mutated solely by `state[i] += dt·k2[i]`. Zero-length
  state is a no-op (Phase 3 test 25).
- Verified consumers: exponential decay matches `10·e^(−0.5)` within 1%
  after 10 ticks (Phase 3 test 20); SoC discharge `Δ = n·DT·I/Q`
  exactly with `It[0] = +1 A` on both stages of every tick (test 21);
  200-tick runs stay finite with final residual `< 1e-5`
  (Phase 4 test 26).

## Conductor thermal

- Resistance is read once per tick per conductor (item 20); both RK2
  stages share the resistive power `powerLossW = |dV|²/R` computed from
  the same tick voltage `V` — only the cooling term is re-evaluated at
  the midpoint temperature: `d1 = (P − kc·(T−AMB))/cap`,
  `Tm = T + dt/2·d1`, `d2 = (P − kc·(Tm−AMB))/cap`,
  `T += dt·d2`, with `AMB = AMBIENT_C = 20.0 °C`.
- Exact checks (Phase 3 tests 22–23): with zero cooling the RK2 step is
  exact (`T = 20 + n·DT·P`); with cooling the trace follows
  `20 + P·(1−e^(−t))` while heating and Newton cooling
  `20 + (Theat−20)·e^(−t)` after the source is removed, monotonically
  decreasing.
- `buildSystem` stamps each conductor as `1/max(R, 1e-4)` S; the thermal
  integrator uses the raw `R`.
- `findMeltedConductors()`: strict `temperature() > meltingTemp()` (not
  `>=`), side-effect free, fresh list per call (item 13).

## Validation

- Conductor physical validation (item 29, `IllegalArgumentException`):
  node indices in range; `resistance` finite and `> 0`;
  `heatCapacity` finite and `> 0`; `coolingCoeff` finite and `>= 0`;
  `meltingTemp` finite.
- Topology validation: element terminal indices in range
  (`validateTerminals`), conductor node indices in range
  (`validateConductors`), `setElements` element/terminal size and
  `terminalCount` match, `setNodeCount` rejects `n < 0`.

## Warm-start and `lastSolution` semantics

- `lastSolution` is the LAST Newton iterate returned by `solve()`, not
  necessarily a converged point: on a failed solve it is the break-point
  iterate (or the non-finite-broken vector), still stored. Only
  `solve()` writes it; `tick`, `terminalCurrents`, and
  `findMeltedConductors` never touch it (item 31).
- `setInitialVoltage(v)` installs a one-shot override consumed (cleared)
  by the next `solve()`; it never modifies the stored last solution.
  `setInitialVoltage(null)` clears the pending override, so the next
  solve warm-starts from `lastSolution` (verified by a stamp spy that
  records the first-iteration voltage: after override-then-null the
  first stamped `V` equals `lastSolution` to `1e-12`, not the discarded
  override — Phase 4 test 33). A length-mismatched override is ignored
  (falls through to `lastSolution`, then zeros).
- Warm-start sensitivity (measured, Phase 4 test 28): on tree-loaded CP
  networks a source-V start can trap Newton in a fallback/physical
  branch limit cycle (40 exhausted iterations), while a zero start
  converges in 2 iterations; the robustness generator therefore uses a
  zero warm start and tallies `fallbackActive` separately.

## `KernelSolveResult` (7 components, fallback amendment)

- `voltage` (last iterate), `converged`, `singular`, `residual`
  (normalized residual of the final iterate), `newtonIterations` (every
  linear-solve call, singular included), `linearEliminationSteps`
  (accumulated row updates), `fallbackActive` (final-iteration system
  build only — whether the numerical-stability crutch was active at the
  returned operating point; the framework does not act on it).

## Constants (`GridConstants`, frozen)

- `DT = 0.05` s, `GMIN = 1e-9` S, `NEWTON_TOL = 1e-6`,
  `NEWTON_MAX_ITER = 40`, `NEWTON_MAX_STEP = 50.0` V,
  `NEWTON_RESIDUAL_FLOOR = 1e-10`, `LINEAR_RESIDUAL_TOL = 1e-9`,
  `AMBIENT_C = 20.0` °C, `AC_FREQUENCY_HZ = 50.0`,
  `AC_OMEGA_RAD_PER_S = 2π·50`.

## Contract coverage map (items 1–32 + fallback amendment)

- Linear core: Complex value semantics and IEEE division; Ohm/divider/
  parallel topologies; floating-network differential accuracy; earth
  shunt reference; GMIN pinning; singular pivot detection and the
  singular ⇒ `!converged` invariant with finite voltages; disconnected
  nodes stay finite; elimination-step counting.
- Newton core: step tolerance and residual gating; 40-iteration cap;
  50 V step clamp; 4-trial backtracking with unconditional trial-#4
  (`bt == 3`) acceptance and the `1e-10` residual floor; linear-in-2
  convergence; `n == 0` empty result.
- Stamps: frozen `admittance`/`draw`/`thevenin`/`powerInto` formulas and
  argument validation; source/load sign convention with positive
  entering-element current (item 14).
- Nonlinear: DC-only enforcement (item 27, part); CP/CC/one-way
  linearizations, fallback branches and flag conditions; overload
  fallback convergence vs normal-point silence; CC and one-way
  stamp-level sign audits; full input-validation matrix.
- Kernel services: local terminal indices in `terminalCurrents`
  (`It[j] = Σ_m Yl[j][m]·Vt[m] − Il[j]` on fresh local arrays with
  indices `0..k-1`, flag discarded — item 22); state ownership and
  adapter snapshot discipline; conductor physical validation
  (item 29); strict-`>` side-effect-free melted scan (item 13).
- Time stepping: one solve per tick at shared operating point
  (item 15); failed-solve gate preserving state/temperature (item 6);
  derivatives purity (item 28); conductor RK2 with once-per-tick
  resistance (item 20); `lastSolution` written by `solve()` only
  (item 31); override/null-clear warm-start discipline with last-iterate
  (not necessarily converged) semantics.
- Amendment: `fallbackActive` 7th result component, final-build-only
  semantics, ThreadLocal tracking, framework takes no action on it.
- Verification: 200-tick finiteness with residual `< 1e-5`
  (Phase 4 test 26); 100-node single-tick diagnostic
  (nodes = 100, elements = 51, conductors = 99 — report-only timing,
  Phase 4 test 27); seeded (0x5EED) tree-regime robustness at
  100/100 with CP feasibility `P <= 0.8·Vs²/(4·Rint)` enforced by
  P-resampling (Phase 4 test 28).

## Adapter layer (`GridManager` + `KernelAttachedBlock`, Phase E)

The kernel owns the solve; the adapter owns topology, discovery, and the
block-entity (BE) state lifecycle. Phase E deleted the legacy dual-grid subsystem
(`ElectricalGrid`, `GridNode`, `GridConductor`, `GridTopologyHelper`,
`ModifiedNodalAnalysis`, `ACSolver`, `api/energy/*`, `api/grid` legacy types and
their tests): the kernel islands are now the single subsystem.

### Node and conductor construction

- Nodes are cable positions union declared terminal positions of
  `KernelAttachedBlock`s. A cable and a terminal at the same position are the
  same node. A block-entity position is NOT a node unless a cable or terminal
  sits there.
- Conductors form only between 6-Manhattan adjacent nodes (no diagonals), purely
  mechanical with no semantic filtering:
  - cable-to-cable and cable-to-terminal use `cableR`: the per-type base
    resistance of the known cable endpoint(s) (averaged for mixed gauges), else
    `CableConductorAdapter.DEFAULT_CABLE_R_OHM` (`0.001` ohm);
  - terminal-to-terminal uses `CableConductorAdapter.TERMINAL_LINK_R_OHM`
    (`0.0001` ohm): an internal near-short that never melts (melting temp `1e9`)
    and is never a break candidate.

### Terminal position convention

- Every family exposes adjacent terminals as world positions derived from fixed
  offsets of the BE position: all 2-terminal families (sources, storage,
  switchgear, creative generator/load) use east/west
  (`TERMINAL_OFFSETS = {{1,0,0},{-1,0,0}}`); converters use four terminals
  east/west/north/south (`{{1,0,0},{-1,0,0},{0,0,-1},{0,0,1}}`) with the input
  pair on `terminals[0..1]` (east in+, west in−) and the output pair on
  `terminals[2..3]` (north out+, south out−); earth uses a single down terminal
  (`{{0,-1,0}}`).
- Source polarity convention (Phase C): `terminals[1]` is the positive terminal,
  so discharge current entering `terminals[0]` (`It[0]`) is positive. Creative
  loads are `T0`-referenced instead (`V = Vt[0] − Vt[1]`, consumed `It[0]`).

### Islands and per-island omega

- Island connectivity = topology conductors OR same-block terminal ownership
  (all terminals of one block land in the same island even with no cable between
  them; union-find, BFS-equivalent).
- `resolveOmega`: no active source, or all-DC actives, gives `0`; at least one
  active AC source gives `AC_OMEGA_RAD_PER_S`. Conductors and passives never
  determine omega. Classification comes from the BE (`isActiveSource` /
  `isACSource`): batteries/solar/generators/crank active when closed/staged and
  always DC; converters active when untripped with positive staged EMF with the
  waveform table DC-DC/rectifier/EU DC, inverter/transformer AC; loads and fuses
  never source.
- AC-island isolation: DC-only stamps (`constantPower`, `constantCurrent`,
  `oneWayThevenin`) throw on `omega != 0`; converter inputs and creative loads
  therefore stamp a resistive approximation (`R = Vnom²/P`, `R = Vnom/I`) on AC
  islands. Islands solve independently: restaging one island leaves another
  bit-identical (Phase D test `islandsSolveIndependently`, Phase E regression).

### Adapter State Authority (BE → kernel → BE)

- The BE owns persistent state; the kernel owns transient integration slots.
  `setElements` zeroes kernel state, so the topology owner seeds the kernel from
  the BE after every rebuild (`kernel.setElementState(i, be.getStateArray())`)
  and commits kernel state back into the BE after every converged tick, before
  the discrete phase (`be.setStateArray(kernel.getElementState(i))`).
- Fallback discard is per-island: on `fallbackActive` the BE copies stay
  authoritative — re-sync the kernel from the BE before the next solve and skip
  the commit (converters additionally discard the write-only telemetry via
  `resetTelemetry`); other islands commit normally. The kernel integrates even on
  fallback operating points; that state is discarded, never copied across
  generations.
- All crossings are defensive copies: `getStateArray` clones,
  `setStateArray`/`assignState` copies into BE-owned storage (never retains the
  kernel array), `getElementState`/`getLastSolution` return copies. Discrete
  flags (`tripped`, `blown`, `bmsOpen`, staged demand/EMF) are BE boolean/double
  fields persisted in NBT, never inside the state array.

### Deterministic mapping

- Node indices follow the `BlockPos` sort order (`POS_ORDER`: x, then y, then z);
  element indices follow sorted block-entity positions; islands order by minimum
  node position. Rebuilds are deterministic regardless of discovery order, and
  state is re-seeded by element index, so the same topology always maps the same
  BE to the same kernel slots (incremental index: entries arrive via
  place/break hooks and chunk-load scans, never a per-tick full scan).

### Tick order

Per server tick: rebuild-if-dirty (apply queued breaks, rebuild islands, clear
dirty) → per island: `tickElectrical` (block discrete pre-phase: staging, trips,
bookkeeping; never touches the kernel) → exactly one `kernel.tick()`
(solve + integrate) → observation solve (exposes fallback/convergence flags
without integrating again) → commit branch (sync kernel states to BEs) or
discard branch (re-sync kernel from BEs; converters reset telemetry) →
`findMeltedConductors` scan → queue cable breaks + mark dirty for the next
boundary. Telemetry (`derivatives` caches) is therefore always one tick delayed:
discrete decisions consume previous-tick values only.

### Fallback rollback per family (contract items 11/14)

- Battery/rack (`[soc, T, health]` / rack slice): BE authoritative on fallback,
  else commit; BMS (`bmsNext`) opens below pack cutoff or above 60 °C and
  recloses with hysteresis (`+series·0.05 V`, below 55 °C).
- Solar/generator/crank (`[T]` / `[T, fuel]` / `[flywheel, energy]`): same
  BE-authoritative rollback; staging (irradiance/EMF/fuel) is discrete-only.
- Converters/EU bridge (0 states): re-sync is a no-op; owner discards telemetry
  via `resetTelemetry`; trip latch + demand/EMF staging via the pure
  `stageDemandWatts`/`stageEmf`/`tripNext` helpers.
- Creative generator/load (0 states): same stateless rollback; bookkeeping folds
  telemetry through the legacy energy formulas in `tickElectrical`.
- Switchgear: knife/busbar/junction/contactor/breaker/earth (0 states, trip/blow
  flags discrete); fuse (`[T, integrity]`, item 14): integrity/temperature roll
  back like battery state while the latched `blown` flag persists and forces an
  open stamp.

### Topology index and dirty events; mutation boundary

- Index events (all mark dirty, none scan per tick): chunk load (marks loaded),
  chunk unload (clears the loaded flag, entry retained for persistence),
  cable place/break hooks, attached-block add/remove. Rebuilds include only
  positions whose own chunk is loaded; unloaded chunks contribute nothing, and
  BE state arrays survive unload gaps in the BE (the kernel is re-seeded from
  the BE on the post-reload rebuild).
- Mutation boundary: conductor melts (`findMeltedConductors`, strict `>`,
  side-effect free) only queue cable-break positions (`pendingBreaks`) plus the
  dirty flag during island ticks; breaks apply (`world.breakBlock`) at the next
  rebuild boundary, never mid-tick.
- NBT keys are preserved per family (`stateArray` slices plus legacy mirrors
  such as `state_of_charge`, `target_voltage`, `tripped`/`blown`/`bmsOpen`).
- Known Phase E limitations: attached-block discovery has no production caller
  yet (`putAttachedBlock` is driven by tests; the chunk seed scan covers cables
  only), and a block straddling a loaded/unloaded chunk boundary with a partial
  terminal set is not guarded at rebuild (chunk tests unload all involved
  chunks together).
