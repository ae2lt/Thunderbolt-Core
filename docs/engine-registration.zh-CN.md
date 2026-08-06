# AE2 计算引擎注册指南（第三方适配闪电库）

> 适用仓库：`Thunderbolt-Core`（闪电库）与任意第三方 AE2 计算优化模组（如 AE2VM、NeoECO AE）。
> 用途：第三方模组如何把「自己的合成计算引擎」注册进闪电库的附属名单，并出现在互斥选择里。
> 闪电库对第三方是 **可选前置**：不安装闪电库，第三方照常运行；安装后，第三方检测到即可注册。

---

## 0. 总览

闪电库（Thunderbolt Core）在 AE2 的 `CraftingService#beginCraftingCalculation` 入口注入了一个
**第一层递归 mixin**（`CraftingServiceEngineSelectionMixin`，`order=40`，先于 vm 的 `order=100`、
eco 的 `order=500`）。它读取：

1. **附属名单** —— `CraftingEngineRegistry`，第三方在这里注册自己的引擎；
2. **当前选择** —— `CraftingEngineSelection`，玩家通过 `/thunderbolt engine` 选择的互斥引擎。

规则：

- **选中哪个引擎就走哪个**；
- **都没开（`none`）原路返回 AE2 原版计算**；
- **闪电（`thunderbolt`）选中时**：返回原版计算逻辑并取消其余 mixin —— 第一层计算仍会落到闪电
  自己的深层规划器（`CraftingCalculationMixin`）。

```
┌───────────────────────────────────────────────────────────────┐
│  CraftingService#beginCraftingCalculation  (HEAD)             │
│                                                               │
│  order=40   闪电选择 mixin ──► 读名单+选择 → 路由/取消          │
│  order=100  vm mixin        ──► 检查 isCancelled，跳过或接管    │
│  order=500  eco mixin       ──► 检查 isCancelled，跳过或接管    │
│  (原方法体)  AE2 原版计算      ──► 创建 CraftingCalculation     │
└───────────────────────────────────────────────────────────────┘
```

---

## 1. 添加可选依赖

第三方 **不要** 在 `neoforge.mods.toml` 里写 `thunderbolt` 硬依赖，只需编译期引用：

```gradle
// build.gradle —— compileOnly，不打包、不强制加载
compileOnly "curse.maven:thunderbolt-core-<curseid>:<fileid>"   // 以实际发布坐标为准
```

`neoforge.mods.toml` 中**不要**加 `[[dependencies]]` 指向 `thunderbolt`（可选前置，不加依赖项）。

---

## 2. 运行时检测并注册

在自己的 `@Mod` 构造器中，用 `ModList.isLoaded(CraftingEngineRegistry.MODID)` 守卫后注册：

```java
// 第三方 @Mod 构造器
@Mod("mycraftingmod")
public final class MyCraftingMod {

    public MyCraftingMod(IEventBus modEventBus) {
        // 闪电库是可选前置：检测到才注册；没装则整段跳过，本模组照常工作
        if (net.neoforged.fml.ModList.get().isLoaded(CraftingEngineRegistry.MODID)) {
            CraftingEngineRegistry.register(new MyCraftingEngine());
        }
    }
}
```

> **为什么安全**：Java 对类采用惰性解析。闪电库未安装时，`isLoaded` 守卫短路，
> `CraftingEngineRegistry` / `CraftingEngine` 这些类永远不会被加载/解析，不会抛
> `NoClassDefFoundError`。

---

## 3. 实现 `CraftingEngine`

```java
package mymod.engine;

import java.util.concurrent.Future;

import appeng.api.networking.crafting.ICraftingPlan;

import com.moakiee.thunderbolt.api.crafting.engine.CraftingEngine;
import com.moakiee.thunderbolt.api.crafting.engine.CraftingEngineRequest;
import com.moakiee.thunderbolt.api.crafting.engine.CraftingEngineRegistry;

public final class MyCraftingEngine implements CraftingEngine {

    @Override
    public String id() {
        return "myvm";            // 稳定 id：出现在 /thunderbolt engine list 里
    }

    @Override
    public String modId() {
        return "mycraftingmod";   // 所属 mod id；ModList 用它做可用性门控
    }

    @Override
    public boolean isEnabled() {
        // 返回该引擎自身的开关（例如读取你自己的配置）
        return MyConfig.enabled();
    }

    @Override
    public Future<ICraftingPlan> route(CraftingEngineRequest request) {
        // 用 request 提供的信息启动你的计算引擎。
        // 返回 future = 接管本次计算（闪电 mixin 会取消其余引擎的 mixin）；
        // 返回 null   = 本次无法处理，放行回 AE2 原版。
        return MyEngine.calculate(
                request.grid(),
                request.requester(),
                request.what(),
                request.amount(),
                request.strategy());
    }
}
```

### 3.1 `CraftingEngineRequest` 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `level()` | `Level` | 计算所在世界 |
| `grid()` | `IGrid` | 目标 AE 网络 |
| `requester()` | `ICraftingSimulationRequester` | 下单方（终端 / 合成链接 / 接口） |
| `what()` | `AEKey` | 目标物品 |
| `amount()` | `long` | 请求数量 |
| `strategy()` | `CalculationStrategy` | AE2 计算策略 |
| `nativeInvoker()` | `NativeCraftingInvoker` | 调用**原版** `beginCraftingCalculation` 的受守卫回调，用于回退 |

### 3.2 回退原版

引擎处理不了时，可直接返回 `null`（放行原版），或调用守卫后的原版路径自行拿 future：

```java
@Override
public Future<ICraftingPlan> route(CraftingEngineRequest request) {
    if (!MyEngine.canHandle(request.what())) {
        return null;                                    // 放行：原版 / 其它引擎
    }
    return request.nativeInvoker().callNative(
            request.level(), request.requester(), request.what(),
            request.amount(), request.strategy());      // 显式走原版
}
```

> `nativeInvoker` 内部已由闪电库做好防重入（ThreadLocal 守卫），不会递归。

---

## 4. 选择机制（互斥）

- 选择是 **单值互斥**：`none | thunderbolt | 各第三方引擎 id`，至多一个生效。
- 默认 **`none`**（原版路径）。
- 玩家用命令切换：
  ```text
  /thunderbolt engine list                 # 查看可选引擎（自动包含已注册且已加载的）
  /thunderbolt engine none                 # 回到原版
  /thunderbolt engine thunderbolt          # 闪电自己的快速规划器
  /thunderbolt engine myvm                 # 切换到第三方引擎（示例）
  ```
- 选择持久化在服务端配置 `config/thunderbolt-server.toml`（`craftingEngine` 项），
  服务端权威、重启保留、自动同步到客户端。

### 4.1 可用性门控

`CraftingEngineRegistry.available()` / `isAvailable(id)` 会检查：
- 引擎已注册；
- `modId()` 对应的 mod 已加载（`modId() == null` 表示常驻，如闪电自己）。

未加载第三方 mod 的引擎不会出现在选择列表里，但若配置里残留了它的 id，mixin 会安全放行回原版。

---

## 5. 推荐用法（按场景切换）

| 场景 | 建议选择 |
|---|---|
| 大单量 | `vm`（若其适配后注册） |
| 直算递归 | `eco`（若其适配后注册） |
| 常规 / 精华类自动合成 | `thunderbolt`（闪电快速规划器） |
| 需要纯原版对照 | `none` |

---

## 6. 注意事项

1. **`id()` 必须唯一且稳定**；同名注册会**替换**先前引擎。
2. **`isEnabled()` 返回 `false` 时**，即使被选中，mixin 也会放行回原版（并打 WARN 日志）。
3. **闪电选中时取消其它引擎**依赖 mixin 顺序 + `cir.isCancelled()`：
   - vm 已检查 `isCancelled`，会被正确跳过；
   - eco 当前**不检查** `isCancelled` —— 若需要被闪电“取消”，eco 侧需后续适配
     （检查 `cir.isCancelled()` 或读取 `CraftingEngineSelection.current()`）。
4. **不要**在 `route` 里长时间阻塞服务端线程；返回异步 future 更安全。
5. 注册时机：`@Mod` 构造器即可；选择 mixin 在计算发生时实时读名单，无先后顺序问题。

---

## 7. 相关代码位置（闪电库侧）

| 组件 | 路径 |
|---|---|
| 引擎契约 | `api/crafting/engine/CraftingEngine.java` |
| 请求上下文 | `api/crafting/engine/CraftingEngineRequest.java` |
| 附属名单 | `api/crafting/engine/CraftingEngineRegistry.java` |
| 互斥选择 | `api/crafting/engine/CraftingEngineSelection.java` |
| 配置持久化 | `core/crafting/engine/CraftingEngineConfig.java` |
| 切换命令 | `core/crafting/engine/CraftingEngineCommand.java` |
| 闪电自身引擎 | `core/crafting/engine/ThunderboltEngine.java` |
| 第一层 mixin | `mixin/ae2/crafting/CraftingServiceEngineSelectionMixin.java` |
| 深层联动 | `mixin/ae2/crafting/CraftingCalculationMixin.java` |
