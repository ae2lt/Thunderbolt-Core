# Thunderbolt Core

Thunderbolt Core 是 AE2 Lightning Tech 使用的 AE2 优化与底层基础设施模组，也可以单独安装为 AE2 自动合成加速器。

## 环境要求

- Minecraft `1.21.1`
- NeoForge `21.1.x`（开发基线：`21.1.219`）
- Applied Energistics 2 `19.2.17`–`19.2.x`

客户端和服务端均需将 Thunderbolt Core 与 AE2 放入 `mods` 目录。

## 主要功能

- 加速 AE2 自动合成规划与批量下发
- 为兼容模组提供合成扩展 API
- 为 AE2LT 提供过载频道与矩阵合成基础设施
- 包含 Advanced AE、NeoECO、AE2 Crafting Tree 和 ExtendedAE Plus 的可选兼容钩子

## 运行参数

- `-Dthunderbolt.watchdogMs=<毫秒>`：首次慢规划警告延迟
- `-Dthunderbolt.watchdogRepeatMs=<毫秒>`：后续警告间隔
- `-Dthunderbolt.maxCraftSearchWork=<数量>`：规划器搜索工作量上限
- `-Dthunderbolt.maxCraftDepth=<数量>`：规划深度上限

## 开发构建

```powershell
.\gradlew.bat build
```

```powershell
.\gradlew.bat publishToMavenLocal
```

Maven 坐标：`com.moakiee.thunderbolt:thunderbolt:1.0.0`。

问题反馈：[GitHub Issues](https://github.com/ae2lt/Thunderbolt-Core/issues) ·
许可证：[GNU LGPL 3.0](LICENSE)
