# Changelog

## 1.0.3-forge-1.20.1-r3 - 2026-08-10

### Changed

- Lowered the Forge runtime requirement from 47.4.20 to 47.4.18.
- Extended the accepted AE2 range to 15.4.10 through 15.x.
- Added a build profile for verification against AE2-UELM 15.5.0-uelm.
- Kept Minecraft 1.21.1 artifacts and source lines unchanged.

## 1.0.3-forge-1.20.1-r2 - 2026-08-10

### Fixed

- Resolved the CraftingService Mixin conflict with CrazyAE2Addons 3.1.0.
- Remapped Time Wheel CPU menu lifecycle injections for the production Forge
  runtime.
- Added the required no-argument Forge mod constructor.
- Avoided the SavedData ownership collision with AE2 Lightning Tech.
- Corrected Forge 1.20.1 CPU NBT Mixin descriptors.

## 1.0.3-forge-1.20.1-r1 - 2026-08-08

### Added

- Initial unofficial Forge 1.20.1 port of Thunderbolt Core 1.0.3.
