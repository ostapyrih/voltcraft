# Creative Testing Blocks & Grid Diagnostic Tools

> **Code status (audited 2026-09-28): IMPLEMENTED.**
> `voltcraft:creative_generator` + `voltcraft:creative_load` (`block/creative/*`,
> `simulation/creative/*Logic.java`, GUIs + `/voltcraft` commands). No crafting recipes —
> creative inventory (`VoltCraft: Power & Grid` tab) / `/give` only. Loot tables drop themselves.

Part of the [[../core-idea|VoltCraft Core Idea & Architecture]] specification.

---

## 1. Overview

Testing complex electrical circuits, battery banks, transmission lines, and power converters in creative mode requires precision instrumentation and source/load injection. VoltCraft provides two dedicated creative-only blocks:
1. **Creative Power Generator (`voltcraft:creative_generator`):** Universal electrical source supplying arbitrary DC or AC waveforms, adjustable EMF, live current ceilings, and internal resistance settings.
2. **Creative Electrical Load (`voltcraft:creative_load`):** Universal dummy load bank simulating constant resistance, constant current, or constant power dissipation with real-time telemetry.

Neither block has crafting recipes; they are found exclusively in the Creative Inventory under the **VoltCraft** tab or via `/give`.

---

## 2. Creative Power Generator (`voltcraft:creative_generator`)

### 2.1 Visual Design
* **Top:** Golden quantum resonance ring and energetic vortex matrix.
* **Sides:** Cosmic obsidian casing featuring a glowing purple infinity symbol `∞` and digital telemetry readout.
* **Bottom:** Indestructible bedrock-alloy foundation.

### 2.2 Graphical Interface (GUI Dashboard)
Right-clicking the block with an empty hand opens an industrial laboratory control dashboard ($300 \times 210\text{ px}$):
* **Live Status Badge:** `ONLINE` (green) or `OFFLINE` (red).
* **3-Column Live Telemetry:**
  * **Setting Column:** Setpoint EMF voltage ($V$), Waveform frequency ($\text{DC}$, $50\,\text{Hz}$, $60\,\text{Hz}$), and internal source resistance ($R_{\text{int}}$).
  * **Output Column:** Real-time delivered current ($A$), real power ($W$), and configured current ceiling ($A$).
  * **Energy Column:** Total accumulated energy delivered ($\text{kWh}$ / $\text{kJ}$), ideal voltage source status, and internal dissipation ($\sim 0\,\text{W}$).
* **Precision EMF Controls:**
  * **Direct Entry:** Numerical text input box with `[Set]` button or Enter key.
  * **Fine-Tuning:** `[-10V]`, `[-1V]`, `[+1V]`, `[+10V]` step buttons.
  * **Quick Voltage Presets (exact, `CreativeGeneratorLogic.VOLTAGE_PRESETS`):**
    `[5V]`, `[12V]`, `[24V]`, `[48V]`, `[120V]`, `[230V]`, `[400V]`, `[1000V]`, `[10000V]`.
* **Hardware State Buttons:**
  * `[POWER: ON / OFF]` toggle switch.
  * `[Waveform]` cycle selector ($\text{DC} \to 50\,\text{Hz} \to 60\,\text{Hz}$ — exact
    `FREQ_PRESETS = {0, 50, 60}`; no 400 Hz preset exists).
  * `[Current Limit]` cycle button (exact `CURRENT_PRESETS`):
    $1, 5, 10, 25, 50, 100, 500, 1000, 10000\,\text{A}$.
  * `[Internal R]` cycle button (exact `R_INT_PRESETS`):
    $0.0001, 0.001, 0.01, 0.1, 1.0, 10.0\,\Omega$ (default $0.001\,\Omega$).
  * `[Reset]` energy counter button.

### 2.3 In-World Quick Controls
Interacting directly with the block in the world with items allows rapid hotkey adjustments:
* **Right-Click with Redstone Item (Dust, Torch, Lever, Redstone Block):** Toggle generator output **ON / OFF**.

---

## 3. Creative Electrical Load (`voltcraft:creative_load`)

### 3.1 Visual Design
* **Top:** Heavy exhaust ventilation louvers with dynamic thermal glow.
* **Sides:** Heavy industrial dummy load bank with black cooling fins and glowing magenta 7-segment digital display reading `[LOAD]`.
* **Bottom:** Heavy equipment skid mount.

### 3.2 Operating Modes
The dummy load operates in three distinct physical modes:
1. **Constant Resistance ($R$ Mode):** Simulates an ohmic load ($I = V/R$, $P = V^2/R$).
2. **Constant Current ($I$ Mode):** Simulates an active electronic current sink pulling fixed amperage ($P = V \cdot I$).
3. **Constant Power ($P$ Mode):** Simulates a switch-mode power supply or inverter load where $I = P/V$ (negative incremental impedance).

### 3.3 Graphical Interface (GUI Dashboard)
Right-clicking the dummy load with an empty hand opens the test bench dashboard ($300 \times 210\text{ px}$):
* **Live Status Badge:** `ACTIVE` (green) or `DISABLED` (red).
* **3-Column Live Telemetry:**
  * **Setpoint Column:** Current target value ($\Omega$, $W$, or $A$), active mode, and circuit state.
  * **Live Draw Column:** Measured terminal node voltage ($V$), drawn current ($A$), and instantaneous power dissipated ($W$).
  * **Metrics Column:** Accumulated energy consumption ($\text{kWh}$), instantaneous equivalent resistance ($R_{\text{eq}}$), and power factor ($1.00$).
* **Operating Mode Selection:**
  * One-click mode toggle buttons: `[Resistance (Ω)]`, `[Power (W)]`, `[Current (A)]`.
* **Precision Setpoint Controls:**
  * **Direct Input Field:** Text box + `[Set]` button or Enter key.
  * **Step Buttons:** `[-10]`, `[-1]`, `[+1]`, `[+10]` (or $\pm 100\text{W}$ / $\pm 10\text{W}$ in power mode).
  * **Mode-Sensitive Quick Presets (exact code arrays):**
    * **Resistance Mode** (`RESISTANCE_PRESETS`, 11): `[0.5Ω]`, `[1Ω]`, `[2Ω]`, `[5Ω]`, `[10Ω]`, `[25Ω]`, `[50Ω]`, `[100Ω]`, `[250Ω]`, `[500Ω]`, `[1000Ω]` (default $10\,\Omega$).
    * **Power Mode** (`POWER_PRESETS`, 10): `[10W]`, `[50W]`, `[100W]`, `[250W]`, `[500W]`, `[1kW]`, `[2.5kW]`, `[5kW]`, `[10kW]`, `[50kW]` (default $1000\text{ W}$ on mode switch).
    * **Current Mode** (`CURRENT_PRESETS`, 11): `[0.1A]`, `[0.5A]`, `[1A]`, `[2A]`, `[5A]`, `[10A]`, `[16A]`, `[25A]`, `[32A]`, `[50A]`, `[100A]` (default $10\text{ A}$ on mode switch).
* **State Controls:**
  * `[LOAD: ON / OFF]` toggle switch.
  * `[Reset Energy]` button.

---

## 4. Command Line Reference (`/voltcraft`)

> Exact syntax from `command/VoltcraftCreativeCommands.java` — **raycast-targeted** (look at the
> block within 8 blocks; no XYZ arguments). Previous XYZ/`enabled`/`reset_energy`/`400hz`
> documentation was wrong and has been replaced.

```
# Creative Power Generator (look at the block, then run):
/voltcraft generator <voltage> [max_current] [frequency]
# voltage: 0..1000000 V · max_current: 0..1000000 A · frequency: 0..10000 Hz (0 = DC)

# Creative Electrical Load (look at the block, then run):
/voltcraft load resistance <ohms>    # 0.0001..10000000 Ω
/voltcraft load power <watts>        # 0..100000000 W
/voltcraft load current <amps>       # 0..1000000 A
```
