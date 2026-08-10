# Thunderbolt Core - Unofficial Forge 1.20.1 Port

This project ports the supplied `thunderbolt-1.0.3.jar` to Minecraft 1.20.1
Forge. Thunderbolt is the shared AE2 infrastructure layer for high-throughput
crafting, batch dispatch, indexed storage, channel helpers, and optional
compatibility with Advanced AE and Neo ECO AE Extension.

## Target

- Minecraft 1.20.1
- Forge 47.4.20+
- Java 17
- Applied Energistics 2 15.4.x
- Optional Advanced AE integration

The supplied upstream artifact is NeoForge 1.21.1 and cannot be copied directly
into a Forge 1.20.1 instance. The source was separately decompiled for porting,
then adapted to Forge registries, lifecycle hooks, capabilities, and Java 17
Mixin configuration.

## Build

```powershell
.\gradlew.bat build --no-daemon
```

The distribution artifact is:

```text
build/libs/thunderbolt-1.0.3-forge-1.20.1-r2.jar
```

The `-slim.jar` file is the development artifact and should not be distributed.

## Scope

The port keeps the upstream package/API names under
`com.moakiee.thunderbolt`. It includes the planner, batch executor, indexed
storage, channel helpers, overload pattern support, time-wheel CPU support,
and optional-mod mixin selectors. NeoForge-only metadata is replaced by Forge
metadata; the upstream NeoForge JAR is not bundled.

## License

GNU LGPL 3.0. This is an unofficial port and is not endorsed by Applied
Energistics 2 or the original upstream project authors.
