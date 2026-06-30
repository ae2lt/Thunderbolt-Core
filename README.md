# Thunderbolt Core

Thunderbolt Core is the shared AE2 optimization and infrastructure layer for
AE2 Lightning Tech.

- Mod name: `Thunderbolt Core`
- Mod id: `thunderbolt`
- Java namespace: `com.moakiee.thunderbolt`
- Maven coordinate: `com.moakiee.thunderbolt:thunderbolt:2.0.0-alpha.1`
- Minecraft / loader: Minecraft `1.21.1`, NeoForge `21.1.x`

## What It Provides

Thunderbolt Core hosts low-level AE2 hooks used by AE2LT and compatible addons:

- a fast autocrafting planner installed through AE2 mixins
- batch crafting helpers and public crafting extension APIs
- overloaded-channel helpers used by AE2 Lightning Tech
- pure planner/cell/crafting-core utilities with unit tests

The runtime mod can be used independently as an AE2 crafting accelerator, while
AE2 Lightning Tech depends on it for its overloaded network and matrix crafting
infrastructure.

## Build

```powershell
.\gradlew.bat build
```

To publish the local Maven artifact consumed by AE2 Lightning Tech:

```powershell
.\gradlew.bat publishToMavenLocal
```

## Runtime Switches

- `-Dthunderbolt.fastPath=false` disables the fast autocrafting planner.
- `-Dthunderbolt.watchdogMs=<ms>` changes the first slow-planning warning delay.
- `-Dthunderbolt.watchdogRepeatMs=<ms>` changes repeated slow-planning warnings.
