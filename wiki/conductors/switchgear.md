# Switchgear, Busbars & Protection Hardware

Part of the [[../core-idea|VoltCraft Core Idea & Architecture]] specification.

---

## 1. Switchgear Hardware & Functionality

Switchgear hardware controls current flow, isolates network partitions, and protects cables and machines against catastrophic overcurrent and fire.

> **Code status (audited 2026-09-28):** all 6 blocks exist (`block/switchgear/*.java`,
> registered in `VoltcraftBlocks`, recipes + loot + blockstates generated). They have **no**
> `BlockEntity` by design (Law #1: no wire ticking) — grid join/split is handled centrally by
> `ElectricalGrid`. **No `fuse_cartridge_*` items exist** — `FuseBoxBlock` recipe uses
> `fuse_alloy_ingot` directly. Amp ratings below ($500\text{ A}$, $100\text{ A}$) are spec text;
> no per-block ampacity constant was found in code.

---

## 2. Crafting Recipes

### 2.1 Copper Busbar (`voltcraft:copper_busbar`)
* $500\text{ A}$ low-impedance copper bar for panelboards.
* Crafting Table:
  ```
  [ Copper Ingot ] [ Copper Ingot ] [ Copper Ingot ]
  [ Copper Ingot ] [ Copper Ingot ] [ Copper Ingot ]
  [ Smooth Stone ] [ Smooth Stone ] [ Smooth Stone ]
  ==> Yields: 4x Copper Busbar
  ```

### 2.2 Junction Box (`voltcraft:junction_box`)
* 6-sided internal terminal block routing cables cleanly.
* Pattern `ICI/CTC/ICI` (I = iron ingot, C = bare copper wire, T = terracotta), 2x:
  ```
  [ Iron Ingot       ] [ Bare Copper Wire ] [ Iron Ingot       ]
  [ Bare Copper Wire ] [ Terracotta       ] [ Bare Copper Wire ]
  [ Iron Ingot       ] [ Bare Copper Wire ] [ Iron Ingot       ]
  ==> Yields: 2x Junction Box
  ```

### 2.3 Manual Knife Switch (`voltcraft:knife_switch`)
* $100\text{ A}$ visible disconnect blade (spec rating). Right-click to toggle connection.
* Pattern `" CL"/CCS/TTT` (C = copper ingot, L = lever, S = smooth stone, T = terracotta), 1x:
  ```
  [    None    ] [ Copper Ingot ] [ Lever        ]
  [ Copper Ingot] [ Copper Ingot ] [ Smooth Stone ]
  [ Terracotta ] [ Terracotta   ] [ Terracotta   ]
  ==> Yields: 1x Knife Switch
  ```

### 2.4 Cartridge Fuse Box (`voltcraft:fuse_box`)
* Holds 1 sacrificial fuse-alloy element. **No separate cartridge items exist** — the recipe
  consumes `fuse_alloy_ingot` directly.
* Pattern `TGT/CFC/TRT` (T = terracotta, G = glass pane, C = bare Cu, F = fuse alloy, R = redstone), 1x:
  ```
  [ Terracotta       ] [ Glass Pane     ] [ Terracotta       ]
  [ Bare Copper Wire ] [ Fuse Alloy     ] [ Bare Copper Wire ]
  [ Terracotta       ] [ Redstone Dust  ] [ Terracotta       ]
  ==> Yields: 1x Fuse Box
  ```

### 2.5 Resettable Circuit Breaker (`voltcraft:circuit_breaker`)
* Thermal-magnetic breaker. Trips automatically on fault; player flips handle to reset.
* Pattern `ILI/WNW/TRT` (I = iron ingot, L = lever, W = magnet wire, N = MOSFET, T = terracotta, R = redstone), 1x:
  ```
  [ Iron Ingot         ] [ Lever              ] [ Iron Ingot         ]
  [ Copper Magnet Wire ] [ Power MOSFET       ] [ Copper Magnet Wire ]
  [ Terracotta         ] [ Redstone Dust      ] [ Terracotta         ]
  ==> Yields: 1x Circuit Breaker
  ```

### 2.6 Contactor Relay (`voltcraft:contactor_relay`)
* Redstone-driven heavy electromagnetic switch ($100\text{ A}$ spec rating).
* Pattern `ICI/WNW/TRT` (I = iron ingot, C = bare Cu, W = magnet wire, N = laminated core, T = terracotta, R = repeater), 1x:
  ```
  [ Iron Ingot         ] [ Bare Copper Wire   ] [ Iron Ingot         ]
  [ Copper Magnet Wire ] [ Laminated Core     ] [ Copper Magnet Wire ]
  [ Terracotta         ] [ Redstone Repeater  ] [ Terracotta         ]
  ==> Yields: 1x Contactor Relay
  ```
