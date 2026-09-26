# Creative Testing Blocks & Grid Diagnostic Tools

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
  * **Quick Voltage Presets:** Dedicated one-click buttons for `[5V]`, `[12V]`, `[24V]`, `[48V]`, `[120V]`, `[230V]`, and `[400V]`.
* **Hardware State Buttons:**
  * `[POWER: ON / OFF]` toggle switch.
  * `[Waveform]` cycle selector ($\text{DC} \to 50\,\text{Hz} \to 60\,\text{Hz}$).
  * `[Current Limit]` cycle button ($1\,\text{A} \dots 10000\,\text{A}$).
  * `[Internal R]` cycle button ($0.0001\,\Omega \dots 10\,\Omega$).
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
  * **Mode-Sensitive Quick Presets:**
    * **Resistance Mode:** `[1Ω]`, `[5Ω]`, `[10Ω]`, `[50Ω]`, `[100Ω]`.
    * **Power Mode:** `[100W]`, `[500W]`, `[1kW]`, `[2.5kW]`, `[5kW]`.
    * **Current Mode:** `[1A]`, `[5A]`, `[10A]`, `[25A]`, `[50A]`.
* **State Controls:**
  * `[LOAD: ON / OFF]` toggle switch.
  * `[Reset Energy]` button.

---

## 4. Command Line Reference (`/voltcraft`)

Admins and map developers can configure targeting blocks at specific coordinates or target raycast blocks via chat commands:

```
# Creative Power Generator Commands
/voltcraft generator <x> <y> <z> voltage <volts>
/voltcraft generator <x> <y> <z> current <maxAmps>
/voltcraft generator <x> <y> <z> frequency <dc|50hz|60hz|400hz>
/voltcraft generator <x> <y> <z> resistance <ohms>
/voltcraft generator <x> <y> <z> enabled <true|false>
/voltcraft generator <x> <y> <z> reset_energy

# Creative Electrical Load Commands
/voltcraft load <x> <y> <z> mode <resistance|current|power>
/voltcraft load <x> <y> <z> value <numericValue>
/voltcraft load <x> <y> <z> enabled <true|false>
/voltcraft load <x> <y> <z> reset_energy
```
