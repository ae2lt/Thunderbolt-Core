# Thunderbolt Core

[简体中文](README_zh_CN.md)

Thunderbolt Core is the shared AE2 optimization and infrastructure layer for
AE2 Lightning Tech. It can also be installed as a standalone AE2 crafting
accelerator.

## Requirements

- Minecraft `1.21.1`
- NeoForge `21.1.x` (development baseline: `21.1.219`)
- Applied Energistics 2 `19.2.17`–`19.2.x`

Place Thunderbolt Core and AE2 in the `mods` directory on both the client and
server.

## Features

- fast AE2 autocrafting planning and batch dispatch
- crafting extension APIs for compatible addons
- overloaded-channel and matrix-crafting infrastructure for AE2LT
- optional compatibility hooks for Advanced AE, NeoECO, AE2 Crafting Tree,
  and ExtendedAE Plus

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

Maven coordinate: `com.moakiee.thunderbolt:thunderbolt:1.0.2`.

Issues: [GitHub Issues](https://github.com/ae2lt/Thunderbolt-Core/issues) ·
License: [GNU LGPL 3.0](LICENSE)
