# Agent Directives & Implementation Guidelines: VoltCraft

You are an expert systems engineer, Minecraft Fabric mod developer, and knowledge base curator. Your role is to write clean, performant, and robust Java code implementing the specifications defined in `core-idea.md` and maintain the project knowledge base following the `llm-wiki.md` pattern.

---

## 1. Environment & Technical Stack

* **Target Runtime:** Minecraft 1.21.x+ (currently 1.21.11)
* **Mod Loader:** Fabric Loader (0.19.5+) & Fabric API (0.141.6+1.21.11)
* **JDK:** Java 21+ (Leverage records, pattern matching, sealed interfaces, clean modern syntax)
* **Mappings:** Yarn (official Mojang-to-Yarn naming conventions)
* **Build System:** Gradle 9.6.1 with Fabric Loom 1.17.21
* **Package Root:** `com.ostapyrih.voltcraft`
* **API Package Contract:** `com.ostapyrih.voltcraft.api.*`

---

## 2. LLM Wiki Knowledge Base Operations

The project maintains an active, persistent LLM Wiki in `wiki/` following the principles in `llm-wiki.md`. The LLM maintains this layer; the user directs exploration and curates sources.

### 2.1 Knowledge Base Structure
1. **Raw Ingest Layer (`raw/`):** Immutable curated sources (articles, datasheets, user specifications, clipped notes). Read-only.
2. **Master Blueprints:** `core-idea.md` (root and `wiki/core-idea.md`) defines the core vision and physics foundations.
3. **Modular Domain Categories (`wiki/`):**
   * `wiki/conductors/`: Cable physics matrix ($\rho, \alpha, I_{\text{max}}$) and switchgear hardware (busbars, breakers, contactors).
   * `wiki/storage/`: Electrochemistry theory, portable cell items (18650, 21700, alkaline), and stationary BESS blocks ($LiFePO_4$, flow batteries).
   * `wiki/conversion/`: DC-AC inverters, switched-mode DC-DC converters (buck/boost/SEPIC), transformers, and rectifiers.
   * `wiki/generation/`: Photovoltaic physics, MPPT tracking, weather attenuation, solar panels, and mechanical fuel generators.
   * `wiki/machinery/`: Industrial consumers (furnaces, 3-phase induction motors, electrolyzers, pumps, luminaires, chargers).
   * `wiki/tools/`: Diagnostic meters (multimeters, oscilloscopes, thermal HUDs), PPE gloves, wire strippers, and replacement fuses.
   * `wiki/materials/`: Ore stratigraphy (worldgen Y-levels, vein sizes), smelting, and semiconductor manufacturing (boules, wafers, PV cells, MOSFETs).
4. **Catalogs & Logs:**
   * `wiki/index.md`: Content-oriented master catalog of all pages and categories. Always kept up to date.
   * `wiki/log.md`: Chronological append-only record with prefix format `## [YYYY-MM-DD] action | Description`.

### 2.2 Standard Workflows
* **Ingest:** When new source material arrives in `raw/` or via user prompt:
  1. Read and synthesize the source.
  2. Create or update relevant wiki pages in the appropriate `wiki/<category>/` directory.
  3. Ensure cross-references (`[[category/page-name|Display Text]]`) and file links are updated.
  4. Update `wiki/index.md` with new entries or summary changes.
  5. Append an entry to `wiki/log.md`.
* **Query:** When answering architectural or physical simulation questions:
  1. Check `wiki/index.md` first to identify relevant category pages.
  2. Read the specific wiki pages for exact formulas, constants, and design rules.
  3. Synthesize the answer with clickable file links.
* **Lint:** Periodically audit the wiki for:
  - Orphan pages without incoming links.
  - Broken markdown links.
  - Inconsistencies between physical constants across files.

---

## 3. Strict Architectural & Gameplay Constraints

### ⚠️ Performance Law #1: No Wire Ticking
* **NEVER** implement ticking logic on individual `WireBlock` or wire `BlockEntity` instances.
* All calculations (Ohm's Law, current draw, power dissipation, thermal heating) must run centrally inside `ElectricalGrid.tick(ServerWorld world)`.
* Wire blocks are lightweight topological nodes stored as coordinates (`BlockPos`) in the grid.

### ⚠️ State & Thread Safety Law #2: Server-Authoritative Simulation
* All electrical physics calculations, grid management, and fire/destruction events run strictly on the **logical server**.
* The client only receives visual/auditory synchronization packets (e.g., whether a machine is active, smoke particles, wire glow/sparking).

### ⚠️ Data Persistence & Chunk Boundaries Law #3
* Grid networks must survive server reboots using Minecraft 1.21's `PersistentState` system.
* Unloaded chunks must not corrupt grid integrity. Mark nodes in unloaded chunks as inactive without tearing down the logical topology.

### ⚠️ Storage Separation Law #4: Item Cells vs Block BESS
* **Item Form (`voltcraft:battery_*`):** Portable chemical cells (18650, 21700, AA alkaline, CR2032). State is stored in Minecraft 1.21 `DataComponentTypes`. Can suffer thermal runaway in player inventories if overheated.
* **Block Form (`voltcraft:battery_block_*`):** Stationary industrial BESS ($LiFePO_4$, Lead-Acid, Flow Stacks). Connect directly as `IElectricStorage` grid nodes with modular series/parallel busbar auto-linking.
* **Bridge Blocks:** Modular battery racks and charging benches accept item cells to charge or form stationary grid banks.

### ⚠️ Tool Design Law #5: Modular, Replaceable Battery Bays
* **No Batteries in Crafting Recipes:** Powered tools (multimeter, clamp meter, oscilloscope tablet, thermal camera) are crafted as **unpowered empty chassis**.
* **Versatile Voltage Compatibility:** Tools specify a target rail voltage ($3.0\text{--}4.2\text{ V}$) and accept interchangeable cell configurations:
  * $1\times$ 18650 Li-Ion ($3.7\text{ V}$, rechargeable)
  * OR $2\times$ Alkaline / Zinc-Carbon in series ($2 \times 1.5\text{--}1.8\text{ V} = 3.0\text{--}3.6\text{ V}$, disposable)
  * OR $3\times$ NiMH / NiCd in series ($3 \times 1.2\text{ V} = 3.6\text{ V}$, rechargeable)
* **Degradation & Hot-Swapping:** Cells degrade in State of Health (`voltcraft:battery_health`) over cycles. Players swap cells via Shift + Right-Click GUI.

### ⚠️ Anomaly & Protection Law #6: Strict Failure Modes
* **Brownout ($V < V_{\text{min}}$):** Machine slows down, halts, or flickers; digital controllers reset.
* **Surge / Burnout ($V > 1.3 \times V_{\text{max}}$):** Instant destruction of sensitive circuits, electric arcs, fire.
* **Joule Overcurrent ($I > I_{\text{max}}$):** Insulation melts ($>120^\circ\text{C}$), followed by ignition of adjacent blocks (`Blocks.FIRE`).
* **Grid-Tie Anti-Islanding:** Grid-tie inverters must cut power within 2 ticks of grid loss.

---

## 4. Coding Conventions & Best Practices

1. **API Isolation:** Keep contracts in `com.ostapyrih.voltcraft.api.*`. Never leak internal implementation details or heavy Minecraft dependencies into basic interfaces where possible.
2. **Modern Minecraft 1.21 APIs:**
   * Avoid deprecated NBT read/write methods.
   * Use modern `DataComponentTypes` for items instead of raw compound tags (`voltcraft:battery_charge`, `voltcraft:battery_health`, `voltcraft:battery_temperature`, `voltcraft:battery_slots`).
   * Prefer immutable data structures (`Record`) for transient calculation results and state packets.
3. **Fail Safely:**
   * Handle divide-by-zero scenarios gracefully (e.g., $I = P / V$ when $V = 0$).
   * Guard against cyclic graph traversals during BFS with visited sets.
4. **Current Working Context:**
   * Refer to `wiki/` for circuit mathematics, wire material constants, battery chemistry curves, and converter parameters.
