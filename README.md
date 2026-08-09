# Thunderbolt Core

[简体中文](README_zh_CN.md)

Thunderbolt Core is the shared AE2 optimization and infrastructure layer for
AE2 Lightning Tech. It can also be installed as a standalone AE2 crafting
accelerator.

This repository tracks the **Minecraft Forge 1.20.1** port (branch `1.20`) of
the upstream project. It is kept in sync with
[ae2lt/Thunderbolt-Core](https://github.com/ae2lt/Thunderbolt-Core) `main` on a
best-effort basis.

## Requirements

- Minecraft `1.20.1`
- Forge `47.4.0`+ (not NeoForge)
- Java `17`
- Applied Energistics 2 `15.4.10` (or any 1.20.1 release that keeps the
  patched crafting classes stable)

Place Thunderbolt Core and AE2 in the `mods` directory on both the client and
server.

## Features

- fast AE2 autocrafting planning and batch dispatch (linear-time planner
  installed on AE2's `CraftingCalculation` via mixin)
- crafting extension APIs for compatible addons
- overloaded-channel and matrix-crafting infrastructure for AE2LT
- infinite indexed-cell storage backend
- time-wheel crafting CPU scheduling and closed-loop crafting support
- optional compatibility hooks for Advanced AE, NeoECO, AE2 Crafting Tree,
  and ExtendedAE Plus (loaded only when the corresponding mod is present)

## Runtime Options

- `-Dthunderbolt.watchdogMs=<ms>`: first slow-planning warning delay
- `-Dthunderbolt.watchdogRepeatMs=<ms>`: repeated warning interval
- `-Dthunderbolt.maxCraftSearchWork=<count>`: planner search-work budget
- `-Dthunderbolt.maxCraftDepth=<count>`: planner depth limit

## Development

```powershell
.\gradlew.bat build
```

```powershell
.\gradlew.bat publishToMavenLocal
```

Maven coordinate: `com.moakiee.thunderbolt:thunderbolt:1.0.7`.

The distributable local build is `build/libs/thunderbolt-1.0.7-all.jar`.
The unclassified JAR does not contain the required MixinExtras jar-in-jar
dependency and is intended only as an intermediate development artifact.

Issues: [GitHub Issues](https://github.com/ae2lt/Thunderbolt-Core/issues) ·
License: [GNU LGPL 3.0](LICENSE)
