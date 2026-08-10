# Forge 1.20.1 Port

This directory is the source of an unofficial Forge 1.20.1 port of Thunderbolt Core.

## Origin

- Upstream project: [ae2lt/Thunderbolt-Core](https://github.com/ae2lt/Thunderbolt-Core)
- Source baseline: the supplied `thunderbolt-1.0.3.jar` NeoForge 1.21.1 artifact
- Port target: Minecraft 1.20.1 and Forge 47.4.18+

The port is maintained independently from the upstream project. It is not an
official release, endorsement, or compatibility guarantee from the original
authors.

## Porting scope

The source keeps the upstream package and API naming where practical while
adapting the platform-specific parts to Forge 1.20.1. This includes lifecycle
registration, capabilities, saved data, AE2 15.x APIs, synchronization
packets, resource metadata, and Java 17-compatible language/API usage.

## Build

```powershell
.\gradlew.bat clean build --no-daemon
```

The compiled JAR is intentionally excluded from this source repository.

## Dependencies

- Minecraft 1.20.1
- Forge 47.4.18+
- Applied Energistics 2 15.4.10 through 15.x
- AE2 Unofficial Extended Life Modern 15.5.0-uelm

Advanced AE and Neo ECO AE Extension integrations are optional.

## License

This port is distributed under the GNU Lesser General Public License v3.0.
The upstream project and its authors retain their own rights and licenses.
