# Thunderbolt Core

[English](README.md)

Thunderbolt Core 是 AE2 Lightning Tech 使用的 AE2 优化与底层基础设施模组，也可以单独安装为 AE2 自动合成加速器。

本仓库跟踪上游项目的 **Minecraft Forge 1.20.1 移植版**（`1.20.1` 分支），并尽力与 [ae2lt/Thunderbolt-Core](https://github.com/ae2lt/Thunderbolt-Core) 的 `main` 分支保持同步。

## 环境要求

- Minecraft `1.20.1`
- Forge `47.1.3` 及以上（非 NeoForge）
- Java `17`
- Applied Energistics 2 `15.4.10`（或任何合成类补丁所针对的类保持稳定的 1.20.1 版本）

客户端和服务端均需将 Thunderbolt Core 与 AE2 放入 `mods` 目录。

## 主要功能

- 加速 AE2 自动合成规划与批量下发（通过 mixin 注入 AE2 的 `CraftingCalculation` 安装线性时间规划器）
- 为兼容模组提供合成扩展 API
- 为 AE2LT 提供过载频道与矩阵合成基础设施
- 无限索引单元存储后端
- 时间轮合成 CPU 调度与闭环合成支持
- 包含 Advanced AE、NeoECO、AE2 Crafting Tree 和 ExtendedAE Plus 的可选兼容钩子（仅在对应模组存在时加载）

## 运行参数

- `-Dthunderbolt.watchdogMs=<毫秒>`：首次慢规划警告延迟
- `-Dthunderbolt.watchdogRepeatMs=<毫秒>`：后续警告间隔
- `-Dthunderbolt.planningTimeoutMs=<毫秒>`：候选协作退出期限（默认 3000）
- `-Dthunderbolt.planningInterruptGraceMs=<毫秒>`：发送 interrupt 前的宽限（默认 2000）
- `-Dthunderbolt.planningStopGraceMs=<毫秒>`：软期限后到隔离的总宽限（默认 5000）
- `-Dthunderbolt.maxCraftSearchWork=<数量>`：规划器搜索工作量上限
- `-Dthunderbolt.maxCraftDepth=<数量>`：规划深度上限

## 开发构建

```powershell
.\gradlew.bat build
```

```powershell
.\gradlew.bat publishToMavenLocal
```

Maven 坐标：`com.moakiee.thunderbolt:thunderbolt:2.0.0-beta.1`。

本地构建得到的可分发文件是 `build/libs/thunderbolt-forge-1.20.1-2.0.0-beta.1.jar`。
带 `-slim.jar` 后缀的 JAR 不含运行时必需的 MixinExtras 内嵌依赖，只作为开发过程的中间产物。

问题反馈：[GitHub Issues](https://github.com/ae2lt/Thunderbolt-Core/issues) ·
许可证：[GNU LGPL 3.0](LICENSE)
