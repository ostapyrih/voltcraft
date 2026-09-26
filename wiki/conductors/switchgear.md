# Switchgear, Busbars & Protection Hardware

Part of the [[../core-idea|VoltCraft Core Idea & Architecture]] specification.

---

## 1. Switchgear Hardware & Functionality

Switchgear hardware controls current flow, isolates network partitions, and protects cables and machines against catastrophic overcurrent and fire.

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
* Crafting Table:
  ```
  [ Iron Sheet / Ingot ] [ Bare Copper Wire ] [ Iron Sheet / Ingot ]
  [ Bare Copper Wire   ] [ Terracotta       ] [ Bare Copper Wire   ]
  [ Iron Sheet / Ingot ] [ Bare Copper Wire ] [ Iron Sheet / Ingot ]
  ==> Yields: 2x Junction Box
  ```

### 2.3 Manual Knife Switch (`voltcraft:knife_switch`)
* $100\text{ A}$ visible disconnect blade. Right-click to toggle connection.
* Crafting Table:
  ```
  [    None    ] [ Copper Ingot ] [ Lever        ]
  [ Copper Ingot] [ Copper Ingot ] [ Smooth Stone ]
  [ Terracotta ] [ Terracotta   ] [ Terracotta   ]
  ==> Yields: 1x Knife Switch
  ```

### 2.4 Cartridge Fuse Box (`voltcraft:fuse_box`)
* Holds 1 sacrificial cartridge fuse element.
* Crafting Table:
  ```
  [ Terracotta       ] [ Glass Pane     ] [ Terracotta       ]
  [ Bare Copper Wire ] [ Fuse Cartridge ] [ Bare Copper Wire ]
  [ Terracotta       ] [ Redstone Dust  ] [ Terracotta       ]
  ==> Yields: 1x Fuse Box
  ```

### 2.5 Resettable Circuit Breaker (`voltcraft:circuit_breaker`)
* Thermal-magnetic breaker. Trips automatically on fault; player flips handle to reset.
* Crafting Table:
  ```
  [ Iron Ingot         ] [ Lever              ] [ Iron Ingot         ]
  [ Copper Magnet Wire ] [ Power MOSFET       ] [ Copper Magnet Wire ]
  [ Terracotta         ] [ Redstone Dust      ] [ Terracotta         ]
  ==> Yields: 1x Circuit Breaker
  ```

### 2.6 Contactor Relay (`voltcraft:contactor_relay`)
* Redstone-driven heavy electromagnetic switch ($100\text{ A}$).
* Crafting Table:
  ```
  [ Iron Ingot         ] [ Bare Copper Wire   ] [ Iron Ingot         ]
  [ Copper Magnet Wire ] [ Iron Core          ] [ Copper Magnet Wire ]
  [ Terracotta         ] [ Redstone Repeater  ] [ Terracotta         ]
  ==> Yields: 1x Contactor Relay
  ```
