# Thunderbolt 三层架构、稳定 API 与 AE2LT 专用运行时边界

## 文档状态

- 状态：已在 `refactor/thunderbolt-three-layer-architecture` 双仓库分支实施，尚未提交或发布远端。
- 适用仓库：`Thunderbolt_lib`、`AE2-Lightning-Tech`。
- 当前基线：`Thunderbolt_lib/dev @ 52aa293`。
- AE2LT 基线：`feat/safe-adaptive-batch-ramp @ 38ca2d72`；创建分支时已有未提交的无线 cadence、闭环解码和相位锁改动，本次未回退或覆盖它们。
- 本文同时记录架构决策、实际落点和验证边界。
- 本轮新增决策：Thunderbolt 的生产源码原则上收敛为 `api`、`mixin`、`core` 三个顶层职责包；根包只允许保留模组入口和配置引导，不承载领域逻辑。

## 实施结果

本分支已经完成以下结构性变更：

1. Thunderbolt 正式稳定面收敛为 `api.channel`、`api.crafting.planner`、`api.crafting.batch` 和 `api.eject`；旧 `ae2.api` 属于冻结前实验接口，本次直接迁移，不作为已发布稳定 ABI 保留。
2. `supportsSingleSeedBatch()` 保留为 Batch SPI 的兼容方法；新调度器调用中立的 `supportsSharedBatchInputs()`，其默认实现反向调用旧方法，因此已有 override 继续生效。
3. Planner SPI 已实现确定性优先级、单终结规划器和 `EXACT_FEASIBLE`、`EXACT_INFEASIBLE`、`UNSUPPORTED`、`BUDGET_EXHAUSTED` 四态结果；未终结时进入 Thunderbolt 默认规划器或 AE2 回退。
4. Eject API 不再暴露 `WeakReference`、ghost BlockEntity、SavedData 或运行时索引；这些实现分别进入 `core.eject` 和 `mixin.platform.eject`。
5. Thunderbolt 的生产 Java 包除入口/配置外只使用 `api`、`mixin`、`core`。频道公共类型已使用 `ChannelSourceRegistry`、`HighCapacityChannelOwner`、`ConnectionChannelCapacityProvider` 等中立命名；`FastCraftingPlanner` 位于 `mixin.ae2.crafting.support`，纯算法位于 `core.crafting.planner`。
6. TimeWheel、闭环计划/任务、扩展 CPU、LT 种子接口、过载模型/匹配/认领/CPU 状态、无线模型和 Matrix CraftingCore 已迁回 AE2LT 命名空间。
7. 通用规划器不导入 `com.moakiee.ae2lt.*`，也不判断 `TimeWheel`、`LoopCraftingPlan`、`ReusableSeedPattern` 或 `OverloadedProviderOnlyPatternDetails`。AE2LT 通过内部中立元数据 `ReusableStockPattern`、`FuzzyPatternInputs`、`CraftingStockPolicy` 复用默认算法，并由自己的 Mixin 完成闭环计划绑定。
8. Channel、Planner、Batch、Eject 四个稳定 API 家族均已有独立契约测试，覆盖稳定 ID/生命周期、确定性排序、旧 Batch 方法兼容和未安装 Eject runtime 时的安全 no-op，并使用非 AE2LT 的伪第三方实现。
9. Thunderbolt 全量测试通过；AE2LT 迁入的 `crafting.runtime`、`crafting.timewheel`、`overload.runtime` 定向测试通过。AE2LT 全量测试仍有 16 个既有无线 cadence 实验失败，均位于创建分支前已经修改的文件中，不属于本次迁移。

## 定位决策

Thunderbolt 的长期定位是：

> 扩展 AE2 的公共接入能力，并提供可被 AE2 及其附属模组复用的常用算法库。

Thunderbolt 不应继续承载只服务于 AE2LT 具体物品、机器或玩法机制的完整运行时实现。

据此确定以下边界：

1. `api` 只保留需要长期兼容的公共契约，当前仅有频道 API、合成规划器 SPI、Batch 合成 SPI 和 Eject capability 投影 API 四个家族。
2. `mixin` 负责把上述稳定契约接入 AE2，并兼容没有主动适配 Thunderbolt 的附属模组；Mixin、Accessor、反射桥和目标模组版本判断都属于不稳定实现层。
3. `core` 提供频道分配、配方图、整数求解、冲突核、批量调度等关键算法和默认实现，但不新增面向某个玩法的 SPI。
4. 天枢闭环样板的求解语义、TimeWheel CPU、种子账本和持久化实现移动回 AE2LT。
5. LT 过载样板的 `STRICT`、`ID_ONLY`、`MIXED` 匹配以及输出认领算法移动回 AE2LT。
6. 不再因为某个具体模组提出接入需求，就在 Thunderbolt 中增加对应 API。具体模组应实现既有规划器 SPI、Batch SPI 或频道 API；现有契约不能表达时，先留在模组自身，等出现第二个独立用例再评估是否抽象。

## 三层包结构

### `api`：唯一稳定兼容面

`com.moakiee.thunderbolt.api` 是 Thunderbolt 唯一承诺源码与二进制稳定性的包。允许的子域只有：

```text
api
├── channel
├── crafting
│   ├── planner
│   └── batch
└── eject
```

频道、合成与 Eject 是三个一级 API 域；Planner 与 Batch 是合成域内的两个 SPI 家族，不是新的一级域。

`api` 中禁止出现具体产品或实现名，例如 `Probability`、`Overload`、`TimeWheel`、`Tianshu`、`Wireless`、`AdvancedAE`、`NeoECO`。`Eject` 在本文中特指中立的 capability 投影/弹出端点语义，不代表 AE2LT 的某个具体方块。API 也不得依赖 `mixin`、`core` 的实现类或 AE2/LT 的内部类。

### `mixin`：AE2 与未主动兼容模组的适配层

`com.moakiee.thunderbolt.mixin` 负责：

- 把频道 API 和 `core.channel` 接入 AE2 pathing；
- 在 AE2 合成计算入口选择 Crafting Planner SPI；
- 在 AE2 CPU 下发入口调用 Batch SPI；
- 在 NeoForge capability 查询和世界访问入口实现 Eject 端点投影；
- 通过 `@Pseudo` Mixin、Accessor 或最小反射桥兼容 AdvancedAE、NeoECO 等没有主动实现 Thunderbolt API 的模组；
- 在目标版本变化时明确失败、禁用或回退。

Mixin 层可以出现目标模组名称，因为它本来就是版本相关兼容代码；但这些名称和类型不能泄漏到 `api` 或 `core`。Mixin 层不得实现概率、闭环、过载等产品算法，也不得拥有长期业务状态。

### `core`：关键算法和默认实现

`com.moakiee.thunderbolt.core` 负责：

- 通用频道容量、借用和图分配算法；
- 通用配方图、规划、冲突核、整数与有界搜索；
- Batch 调度、预算、失败回注等默认算法；
- Eject 端点索引、离线拒绝与持久化默认实现；
- 可复用的存储索引、饱和算术、图和集合工具。

`core` 可以实现 `api` 中的接口，也可以被第三方规划器选择性复用，但它不是新的扩展点集合。仅 `api` 包承担稳定 SPI 承诺；`core` 的内部模型和优化可以随算法演进。

依赖方向固定为：

```text
mixin ─────► api
  │          ▲
  └────► core┘

api 不依赖 core 或 mixin
core 不依赖 mixin
```

## 规划器与执行器不是同一种组件

### `CraftPlannerV2`

`CraftPlannerV2<K>` 是规划期图求解器。它接收库存快照、配方图、目标键和目标数量，计算：

- 每个样板需要执行多少次；
- 应当直接使用多少网络库存；
- 哪些副产物可以复用；
- 哪些原料缺失；
- 当前结果是否可行；
- 搜索预算是否耗尽。

其核心输入输出为：

```text
CraftGraph<K>
├── stock
├── patterns
├── inputs
├── outputs / byproducts
└── target + amount
        ↓
CraftPlannerV2<K>
        ↓
CraftPlan<K>
├── firings
├── usedStock
├── missing
├── grossDemand
├── feasible
└── budgetExhausted
```

`CraftPlannerV2` 不提取真实物品，不调用样板供应器，也不等待机器返还产物。

### `FastCraftingPlanner`

`FastCraftingPlanner` 是 AE2 与通用求解器之间的适配层：

1. 从 `ICraftingService`、`IPatternDetails` 和 AE2 库存模拟状态构造 `CraftGraph<AEKey>`；
2. 调用 `CraftPlannerV2`；
3. 将 `CraftPlan<AEKey>` 转换回 AE2 `CraftingPlan`；
4. 复现 AE2 的 `simulation`、`usedItems`、`missingItems`、`patternTimes` 和字节记账语义。

因此，`CraftPlannerV2` 可以保持纯算法模型，`FastCraftingPlanner` 则属于 Thunderbolt 的 AE2 默认适配实现。

### TimeWheel CPU

TimeWheel CPU 是执行期组件。它负责：

- 接收已经生成的计划；
- 提取初始材料；
- 调度待执行样板；
- 调用 `pushPattern` 或 `pushBatch`；
- 扣除能量；
- 等待、认领并重新分配返回产物；
- 保存 CPU 任务、库存、链接和进度；
- 处理取消、掉线、重载和方块拆除；
- 管理闭环种子在宿主、CPU、机器和返回路径之间的所有权。

调用关系是：

```text
CraftPlannerV2 / FastCraftingPlanner
                 │
                 ▼
          ICraftingPlan
                 │
                 ▼
        TimeWheel/Tianshu CPU
                 │
                 ▼
      provider / machine / returned output
```

所以 `CraftPlannerV2` 解决“如何合成”，TimeWheel CPU 解决“如何执行”。二者不是同一层，也不是互相替代的两种求解器。

## TimeWheel 对闭环样板是否必要

### 当前实现中的结论

当前闭环计划被包装成 `LoopCraftingPlan`，并被 `ExtendedCraftingCpuServiceMixin` 视为绑定计划。绑定计划只能提交给能够接受它的扩展 CPU；找不到兼容 CPU 时直接返回 `CPU_OFFLINE`。

当前唯一完整实现该执行契约的是 `TimeWheelCraftingCpuPool` 与 `Ae2LtTimeWheelCraftingCpuLogic`。因此：

- 删除 TimeWheel 且不提供替代执行器，会使当前闭环计划无法提交；
- 在当前代码结构中，TimeWheel CPU 是闭环样板端到端运行的必要实现。

### 机制层面的结论

闭环样板真正要求的是一个专用、有状态、可持久化的执行器，必须保证：

1. 宏样板被展开为实际成员样板执行次数；
2. 初始种子不会被当作普通消耗品或最终产物；
3. 种子能够在不同状态、不同成员和不同消费者之间安全流转；
4. 返回产物能够区分最终输出、下一阶段种子和普通副产物；
5. 取消、重启和卸载不会复制或吞掉种子；
6. 计划只能在拥有对应天枢和种子库存的 CPU 上运行。

“时间轮”只是当前执行器采用的调度结构。64 槽时间轮、唤醒索引、成功下发量子和批量快路都可以被其他调度实现替换，不是闭环定义本身的一部分。

## 当前闭环运行时规模

按当前源码中的直接职责统计，TimeWheel/闭环运行时约包含 24 个 Java 文件、7,024 个物理行：

| 分类 | 文件数 | 物理行数 | 说明 |
|---|---:|---:|---|
| 当前完整语义所需的执行与正确性路径 | 18 | 6,646 | 计划包装、执行逻辑、种子账本、宿主接口、持久化和记账 |
| 可替换的调度优化、菜单与兼容层 | 5 | 362 | productive scheduler、唤醒索引、菜单与 AE2CT 展示兼容 |
| 当前未被生产源码引用 | 1 | 16 | `ReusableSeedReservation` |

该统计是按文件职责划分，不代表 6,646 行都必须原样保留。`Ae2LtTimeWheelCraftingCpuLogic` 单文件同时混合了正确性逻辑、普通样板执行、批量优化、缓存和调度优化；移动和拆分后，闭环核心应当明显缩小。

## Thunderbolt 应保留的通用规划语义

Thunderbolt 可以维护一个面向 AE2 原版语义及通用附属模组扩展的求解器。以下能力具有通用性：

- 普通消耗型输入；
- 多候选样板；
- 网络库存共享；
- 主输出和副产物；
- 不同物品的容器返还；
- 原样返回的催化剂和非损耗容器；
- 基于 `getRemainingKey` 的耐久工具与有限使用次数；
- AE2 普通模糊输入和具体候选分配；
- 压缩/解压、容器回收等一般配方循环；
- 发射型物品；
- `long` 饱和运算；
- 数量无关的聚合传播；
- 多路线冲突、共享库存竞争和有界搜索；
- 完整计划、缺失材料和诊断信息。

这些语义都来自 AE2 API 或一般配方图问题，不应因为目前主要由 AE2LT 使用就移动回 LT。

建议保留的核心类型包括：

- `CraftGraph`
- `CraftPattern`
- `CraftInput`
- `CraftOutput`
- `CraftPlan`
- `PlanningResult`
- `PlanningDiagnostics`
- `CraftPlannerV2`
- `FastCraftingPlanner`
- 通用整数、循环、耐久链和有界组合算法

## AE2LT 应拥有的闭环求解语义

重构前通用规划器已经混入以下闭环专用状态：

- `ReusableStockSource` 的 `storageScope / poolScope / routingScope`；
- `reusableStockRoutes`；
- `reservedSelfSeeds`；
- `feedbackSeedBootstraps`；
- `feedbackSeedConverters`；
- `reservedFeedbackSeedOutputs`；
- `reservedFeedbackSeedHostOutputs`；
- `seedOrderedDependencyCone`；
- 共享种子池和独占种子池；
- 闭环首次启动种子的借用与状态转换；
- 闭环消费者和生产者之间的种子路由。

这些能力不属于公开 Planner SPI。实施后，闭环样板分析、宿主/池身份、计划绑定、种子账本和持久化都由 AE2LT 负责；Thunderbolt `core` 只保留不带产品类型的可复用库存路由算法。后续若拆出独立 `ClosedLoopPlanner`，它负责：

1. 识别天枢闭环宏样板；
2. 分析闭环成员和强连通状态；
3. 计算初始种子和宿主私有库存借用；
4. 选择共享或独占种子池；
5. 生成 `TianshuClosedLoopCraftingPlan`；
6. 将普通物料依赖交给 Thunderbolt 通用求解器；
7. 将闭环元数据交给 AE2LT 的专用 CPU 执行。

Thunderbolt 只提供统一的 Crafting Planner SPI 和可复用的 `core` 算法。不能为了闭环再增加绑定计划、计划装饰、种子槽或样板展开等独立公共接口。Thunderbolt 的核心求解器中也不能出现 `TimeWheel`、`Tianshu`、`LoopSeed` 或 AE2LT 具体类型判断。当前过渡实现由 AE2LT 通过非稳定的 core 元数据接口调用默认求解器；这些接口不是新的公共 SPI。

## 应移动回 AE2LT 的过载语义

LT 过载样板的下列机制属于产品规则，不属于 AE2 原版语义：

- `STRICT`、`ID_ONLY`、`MIXED` 匹配模式；
- 同物品 ID、忽略组件的输入候选展开；
- 过载样板专属宿主限制；
- 模糊输出认领；
- 同一输出在 requester、CPU 库存和种子池之间的分配；
- `OverloadCpuState` 与待认领输出持久化；
- 普通严格 waiting 与过载模糊 waiting 的重叠扣账。

重构前 `FastCraftingPlanner` 直接依赖 `OverloadedProviderOnlyPatternDetails`，CPU Mixin 也直接维护过载状态。当前直接依赖已删除，且没有增加输出路由或 Overload 专用公共 API：

- 独立过载规划器若需要终结整个请求，直接实现统一 Crafting Planner SPI；当前实现先通过中立 core hook 复用默认规划器；
- 过载规划器可以复用 Thunderbolt `core` 的配方图、整数和搜索算法；
- `ID_ONLY`、输出认领和持久化仍由 AE2LT 自己实现；
- Thunderbolt 不导入 `com.moakiee.ae2lt.*`，也不导入 LT 专用过载模型；
- 若普通 Thunderbolt 规划器遇到无法表达的过载语义，应返回 `UNSUPPORTED`，由规划器选择器交给 AE2LT 规划器或回退 AE2，而不是临时增加新接口。

## “AE2 原版语义超集”的定义

保留通用求解器是合理的，但当前 `CraftPlannerV2 + FastCraftingPlanner` 尚不能严格宣称已经是 AE2 原版语义的完整超集。

如果要使用“原版语义超集”这一承诺，至少必须满足：

### 正确性

1. Thunderbolt 返回的每一份可行计划都能由 AE2 执行，不得过量使用库存；
2. 对普通 AE2 样板，AE2 能规划成功的请求，Thunderbolt 也能成功，或明确返回“交还 AE2”；
3. `usedItems`、`patternTimes`、`missingItems`、`emittedItems` 和 `simulation` 行为与 AE2 可观察语义一致；
4. `CRAFT_LESS` 继续由 AE2 数量尝试和二分策略驱动；
5. 模糊输入在规划期计费的具体键与执行期实际提取键必须能够对账；
6. 预算耗尽不能伪装成已经证明的不可行结果。

### 明确的结果状态

建议将当前 `supported / feasible / budgetExhausted` 语义收敛为明确状态：

```java
public enum PlanningStatus {
    EXACT_FEASIBLE,
    EXACT_INFEASIBLE,
    UNSUPPORTED,
    BUDGET_EXHAUSTED
}
```

- `EXACT_FEASIBLE`：可以安全覆盖 AE2 结果；
- `EXACT_INFEASIBLE`：已经证明该数量无法完成，可以生成严格 Missing；
- `UNSUPPORTED`：当前模型无法表达，应交还 AE2 或由专用求解器处理；
- `BUDGET_EXHAUSTED`：只有当前具体路线的诊断结果，不能声称所有路线都失败。

在完整性尚未证明前，普通 AE2 CPU 应只接受前两个状态。LT 专用 CPU 可以单独选择是否接受有界诊断或禁止回退的策略。

### 当前仍需处理的差异

当前实现至少存在以下边界，不能直接作为完整超集证明：

- 模糊候选笛卡尔积有固定预算；
- 搜索和递归深度有上限；
- 预算耗尽时的 Missing 是当前路线的启发式补料目标；
- 只作为副产物出现的物品不会通过主动超产主输出获得；
- 规划选择的模糊变体可能与执行 CPU 实际提取的变体不同；
- 共享子图下的字节统计可能小于 AE2 原始树展开值；
- 一般循环通过保守切边避免假阳性，但可能损失原版可达路线。

因此迁移后应将求解器描述为“有正确性保证的通用快速求解器”，等差分测试和回退契约完成后再升级为“AE2 原版语义超集”。

## Thunderbolt 的稳定 API 面

### Channel API

Channel API 只描述频道问题中外部实现必须提供的事实，例如节点需求、容量来源和边容量。频道最大流、容量借用、路径重算和缓存属于 `core.channel`；AE2 `GridNode`、`GridConnection` 和 `PathingCalculation` 的注入属于 `mixin.ae2.channel`。

当前频道代码已完成以下净化：

- `OverloadedGridNodeOwner`、`OverloadedSubtreeNode`、`OverloadedChannelOwnerHelper` 已改为中立职责类型；
- `WirelessConnectionCapProvider` 已抽象为一般的 `ConnectionChannelCapacityProvider`，API 不写死无线产品形态；
- `ChannelSourceRegistry` 使用稳定字符串 ID、重复注册检测和幂等注销句柄；只传 Class 的重载仅是兼容快捷入口；
- `BorrowedCapacityCalculator` 移入 `core.channel`；
- `ControllerMachineNodeLookup` 等 AE2 机器索引桥移入 `mixin` 支持代码。

频道 API 加通用算法仍是当前最合适的方案。仅靠事件无法维护整棵 pathing 图的不变量；让每个模组自行 Mixin 又会产生不可组合的注入冲突。

### Crafting Planner SPI

规划器 SPI 是所有特殊规划语义的唯一入口。它至少需要稳定定义：

- 不可变的规划请求和可取消/有预算的规划上下文；
- 规划器身份、确定性的选择顺序和重复注册处理；
- `EXACT_FEASIBLE`、`EXACT_INFEASIBLE`、`UNSUPPORTED`、`BUDGET_EXHAUSTED` 四种结果；
- 标准 AE2 计划、Missing 和诊断的所有权；
- `UNSUPPORTED` 与预算耗尽后的安全回退，不允许把部分计划当作成功结果；
- Planner 抛异常时的隔离和日志策略，不能吞掉 `ThreadDeath`、`OutOfMemoryError` 等 `Throwable`。

Thunderbolt 自带 AE2 原版语义规划器和通用快速规划器。Probability-Pattern、AE2LT 闭环和 AE2LT 过载如果需要特殊规划，都应实现该 SPI；不能再分别增加概率样板、计划装饰、候选输入或输出路由 API。

规划器可以复用 `core.crafting`，但 Planner SPI 的请求和结果不能直接暴露 `CraftPlannerV2` 的可变内部状态。这样核心算法可以重写，第三方规划器的二进制契约仍保持稳定。

Planner SPI 第一版只选择一个对完整请求负责的终结规划器，不提供按注册顺序层层改写计划的 decorator 链。一个请求同时包含多种特殊语义时，只有明确声明支持该组合的规划器才能接管；否则返回 `UNSUPPORTED` 并走下一个规划器或 AE2 回退。这样可以避免多个模组按加载顺序修改同一计划而产生不可验证的组合语义。

### Batch 合成 SPI

Batch SPI 保留：

- `IBatchCraftingProvider`；
- 不可变的 `BatchDispatchContext`；
- 明确的容量提示、实际接收数量和失败回注契约；
- 普通 AE2、AdvancedAE、NeoECO 的 Mixin 适配。

`IBatchCraftingProvider.supportsSingleSeedBatch()` 可以保留。它的稳定定义不是“支持天枢种子”，而是“provider 能否让一份明确标记为整批共享的可复用输入服务于本次接受的全部 copies”。默认值继续为 `false`，不支持该能力的旧 provider 不改变行为。

为了避免已有实现断裂，可以保留 `supportsSingleSeedBatch()` 作为兼容入口，并在未来追加更中立的 `supportsSharedBatchInputs()` 默认方法，由新方法回调旧方法；Dispatcher 逐步改为调用新名称。哪些 slot 是共享输入应作为已经验证的 Batch 上下文/计划元数据提供，不能让 provider 猜测，也不能让 `core.batch` 直接识别 `ExecuteLoopPattern`。

`BatchDispatchMode.UNBOUNDED` 仍需在冻结 API 前重新命名并收紧语义，确保它只是通用 CPU 记账策略，而不是绕过预算的产品开关。

### Eject capability 投影 API

Eject API 描述一个中立能力：把某个世界位置/面的 capability 查询投影到另一个已登记宿主，并在宿主离线时提供确定的拒绝行为。该能力可用于弹出端口、远程接口、虚拟面或跨结构代理，不依赖 AE2LT 的具体方块。

当前 `EjectCapabilityRegistry` 的职责混合过重，不能原样作为稳定 API：

- `Entry` 暴露 `WeakReference<BlockEntity>` 和具体 ghost `BlockEntity`；
- `api.eject` 直接依赖 `internal.eject.EjectRegistrationSavedData` 与 `ThunderboltGhostOutputBlockEntity`；
- 注册、运行时索引、服务器生命周期、持久化迁移和 ghost 构造都集中在一个 public class；
- `setBypass(boolean)` 以成对调用维护 ThreadLocal 深度，异常路径容易被误用。

目标拆分为：

- `api.eject`：不可变端点描述、注册/注销句柄、宿主解析与离线策略；
- `core.eject`：端点索引、重复注册规则、持久化和默认拒绝实现；
- `mixin.platform.eject`：`BlockCapability`、`Level#getBlockEntity` 等侵入桥；
- ghost BlockEntity、SavedData 名称和弱引用缓存不出现在 API 类型签名中。

Eject 注册必须返回稳定句柄或使用稳定 ID，明确同一位置/面重复注册、多宿主优先级、卸载、跨维度、服务端停止和旧存档迁移语义。

### 不再保留为独立 API 的接口

以下当前或先前提议的接口不进入目标稳定 API：

- `ExtendedCraftingCpuCluster`、`ExtendedCraftingCpuClusterProvider`、`ExtendedCraftingCpuClusterHost`：当前只有 TimeWheel/LT 使用，随专用 CPU 移回 AE2LT；
- `BoundCraftingPlan`、`CraftingPlanDecorator`：不创建；特殊计划由对应规划器和产品执行层负责；
- `PatternFiringExpander`、`IPlannedSeedSlotPattern`、`ISeedPreservingCraftingTask`：闭环语义，移回 AE2LT；
- `PlannerInputSemantics`、概率样板 Adapter、通用输出路由：不创建，交由完整 Planner SPI 实现；
- `IPrioritizedCraftingTask`、`IProviderLookupPattern`、`CraftingTaskPriorities`、`CraftingPatternDelegates`：若默认实现仍需要，降为 `core` 或 `mixin` 内部协议，不作为第三方稳定 SPI；
- `api.wireless`：LT 无线连接产品机制，移动回 AE2LT；Eject capability 投影作为独立中立 API 保留；
- `IIndexedStorageCellItem`：AE2 cell handler 的默认实现配置，不升级为新的一级 SPI。

## API 稳定性规则

1. 只有 `com.moakiee.thunderbolt.api` 承担兼容承诺；`mixin` 和 `core` 不能被 API 类型签名引用。
2. 已发布接口优先做可向后兼容的追加；删除、改签名或改变默认语义必须提升主版本。
3. 注册项必须有稳定 ID、重复注册规则、确定性顺序和可测试的生命周期；不能只依赖 Class、加载顺序或“第一个非 null”。
4. 请求、上下文和结果优先使用不可变值对象；集合返回只读视图或快照。
5. 结果必须显式表示“不支持、预算耗尽、暂时不可用和严格失败”，不得用 `null`、异常吞噬或猜测回退混淆。
6. API 不暴露 Mixin Accessor、反射句柄、目标模组类、内部缓存、线程模型或实现专用 NBT。
7. 新 API 必须至少有两个独立使用场景，或属于 AE2 明确缺失的基础能力；否则先留在提出需求的模组中。
8. 每个稳定 SPI 都必须有契约测试、无实现时的 no-op/回退测试，以及至少一个非 AE2LT 的伪实现测试。

## 建议的最终目录

```text
Thunderbolt_lib
└── com.moakiee.thunderbolt
    ├── ThunderboltCore / CoreConfig
    │   └── 只负责模组启动和配置装配
    ├── api
    │   ├── channel
    │   ├── crafting
    │   │   ├── planner
    │   │   └── batch
    │   └── eject
    ├── mixin
    │   ├── ae2
    │   │   ├── channel
    │   │   └── crafting
    │   ├── platform
    │   │   └── eject
    │   └── compat
    │       ├── advancedae
    │       └── neoeco
    └── core
        ├── channel
        ├── crafting
        │   ├── planner
        │   └── batch
        ├── eject
        ├── storage
        └── graph / math / util

AE2-Lightning-Tech
└── com.moakiee.ae2lt
    ├── logic.tianshu.loop
    │   ├── 闭环样板格式与解码
    │   ├── ClosedLoopPlanner
    │   ├── TianshuClosedLoopCraftingPlan
    │   └── 闭环种子与消费者路由
    ├── logic.tianshu.crafting
    │   ├── TianshuCraftingCpuPool
    │   ├── TianshuCraftingCPU
    │   ├── TianshuCraftingCpuLogic
    │   ├── 时间轮调度细节
    │   └── 种子账本、持久化和恢复
    ├── overload
    │   ├── 过载样板模型
    │   ├── 输入匹配
    │   ├── 输出认领
    │   └── CPU 状态
    └── mixin
        └── 闭环菜单、AE2CT 和 LT 专用执行兼容
```

## 重构前源码审计与实际落点

下表的“当前区域”是 `52aa293` 重构前基线；“目标处理”已经在本分支完成：

| 当前区域 | 当前规模/代表类型 | 目标处理 |
|---|---|---|
| `ae2/mixin` | 34 个 Mixin、Accessor 和选择器 | 移到 `mixin.ae2`、`mixin.platform` 或 `mixin.compat.<mod>`；LT 菜单和闭环 Mixin 移回 LT，Eject Mixin 留在 platform 适配层 |
| `ae2/api/crafting` | 11 个类型 | 只把 Planner SPI 与 Batch SPI 的稳定契约迁入 `api.crafting`；种子、任务优先级和 provider delegate 等降为内部或移回 LT |
| `ae2/channel` | `ChannelProviderRegistry`、`BorrowedCapacityCalculator`、多个 `Overloaded*` | 稳定事实契约进 `api.channel`，算法进 `core.channel`，AE2 索引桥进 `mixin`，LT 命名移回 LT |
| `ae2/crafting` | `FastCraftingPlanner`、扩展 CPU、闭环计划和样板 | AE2 规划入口适配进 `mixin` 支持层；通用算法进 `core.crafting`；扩展 CPU 和闭环类型移回 LT |
| `ae2/batch` | CPU/AAE/NeoECO 批量执行 | 通用调度进 `core.crafting.batch`；目标模组访问和 Accessor 进 `mixin.compat` |
| `ae2/timewheel`、`ae2/overload` | 17 个 TimeWheel 文件、32 个过载文件 | 整体迁回 AE2LT，只通过 Planner SPI 或 Batch SPI 使用 Thunderbolt |
| `api/wireless` | LT 无线连接模型 | 迁回 AE2LT，不保留 Thunderbolt 公共 API |
| `api/eject` | 远程 capability 投影，但当前混合 public API、运行时索引、SavedData 和 ghost BE | 中立契约保留在 `api.eject`；索引/持久化进 `core.eject`；侵入桥进 `mixin.platform.eject` |
| `ae2/cell`、`core/cell` | indexed cell 默认实现与索引算法 | 算法和默认实现可进 `core.storage`，但不形成新的稳定 SPI |
| `core/craft` | `CraftingCore`、`MolecularCopyAssembler` 等 | 当前只被 LT Matrix 使用且含 AE2LT/共享种子语义；先移回 LT，只有净化成独立通用 Batch 算法后才回 `core` |
| `internal/*`、`registry/*` | eject ghost BE、SavedData、BlockEntity 注册 | Eject 实现按 `core.eject`/`mixin.platform.eject` 拆分；其余产品注册随对应机制回 LT；根包不保留第四个领域层 |

`FastCraftingPlanner` 虽然不是 Mixin 类，但它读取 AE2 服务并把 AE2 对象转换为 `core` 模型，属于 Mixin 入口背后的 AE2 bridge，不属于稳定 API。最终可放在 `mixin.ae2.crafting.support`；名称中的 `Mixin` 表示这一整层的接入职责，不要求其中每个帮助类都带 `@Mixin` 注解。

## 已执行的迁移顺序

### 阶段 1：冻结最小稳定 API

1. 建立 `api.channel`、`api.crafting.planner`、`api.crafting.batch`、`api.eject`；
2. 为 Planner SPI 锁定请求、结果状态、选择顺序、取消、预算和回退；
3. 保留 `supportsSingleSeedBatch` 的兼容行为，把语义重述为通用 shared-batch-input capability，并冻结容量、shared input 与 leftover 语义；
4. 将频道接口改为中立容量/需求/边模型，删除 `Overloaded`、`Wireless` 产品命名；
5. 将 Eject 的端点契约与运行时索引、SavedData、ghost BE 和 Mixin 拆开；
6. 明确 API 版本和兼容测试，在本阶段不增加绑定计划、计划装饰、样板展开、候选输入或输出路由 SPI；
7. 为旧包名提供一轮 `@Deprecated` 转发或双版本发布计划，避免下游必须在同一提交瞬间切换。

### 阶段 2：移动 TimeWheel 与闭环类型

1. 将 `ae2/timewheel` 具体实现移动到 AE2LT；
2. 将 `LoopCraftingPlan`、`ExecuteLoopPattern` 和闭环种子接口移动到 AE2LT；
3. 移动 TimeWheel 菜单和 AE2CT 兼容 Mixin；
4. 将 `ExtendedCraftingCpuCluster*`、`PatternFiringExpander` 和种子任务接口一起移动到 AE2LT；
5. Planner SPI 已作为唯一专用规划入口接入 AE2；当前过渡实现仍由 Thunderbolt 默认规划器计算普通依赖，再由 AE2LT 绑定闭环计划，后续独立闭环规划器可直接注册该 SPI；
6. 将类名从实现算法名改为产品职责名；
7. 保持已有 NBT 标签、任务 UUID 和存档版本兼容。

### 阶段 3：净化通用求解器

1. 从 `CraftPlannerV2` 和 `FastCraftingPlanner` 的类型边界移除 AE2LT 产品身份；通用 reusable-stock 流量、一般循环和反馈启动算法暂留 `core`，宿主/池身份与最终闭环计划归 AE2LT；
2. 从 `FastCraftingPlanner` 移除 `ReusableSeedPattern` 和 LT 过载模型直接依赖，改为非稳定的中立 core 策略接口；
3. 普通 AE2 语义继续通过通用图求解；
4. 闭环求解器在 AE2LT 中复用 Thunderbolt 图、整数和诊断工具；
5. 将 `FastCraftingPlanner` 作为 AE2 bridge 放入 `mixin` 支持层，核心算法不再导入 AE2 内部实现；
6. 明确 `EXACT / UNSUPPORTED / BUDGET_EXHAUSTED` 结果契约。

### 阶段 4：移动过载算法

1. 将过载样板模型、匹配模式和编辑状态移动回 AE2LT；
2. 将输出认领和 CPU 状态移动回 AE2LT；
3. AE2LT 过载匹配、认领和持久化留在 LT；当前候选扩展通过非稳定的中立 core hook 接入默认规划器，不新增通用输入候选或输出路由 SPI；若以后需要独立终结规划，则直接实现统一 Planner SPI；
4. 普通 AE2、AdvancedAE、NeoECO 执行适配不得再引用 LT 过载具体类型。

### 阶段 5：整理 Mixin 与 Core

1. 将 AE2 入口、Accessor 和桥接帮助类统一放入 `mixin.ae2`；
2. 将 AdvancedAE、NeoECO 等兼容代码分入 `mixin.compat.<modid>`；
3. 将 Eject capability 注入移入 `mixin.platform.eject`；
4. 把频道、规划、Batch、Eject 和存储关键算法/默认实现移入对应 `core` 子包；
5. 移动无线、LT Matrix CraftingCore 和其他产品注册/持久化实现；
6. 检查 `api` 不依赖 `core`/`mixin`，`core` 不依赖 `mixin`。

### 阶段 6：删除冻结前旧包

旧闭环、过载和 `ae2.*` 类型已从 Thunderbolt 生产源码删除并迁入 AE2LT。新 `api.*` 从本分支起作为稳定面；若远端已有第三方使用冻结前实验包，应在发布前单独提供迁移说明，而不是重新把产品类型塞回稳定 API。

## 验收标准

### Thunderbolt 独立性

- 只安装 AE2 与 Thunderbolt 时可以启动；
- 领域源码除根包启动/配置类外，只存在 `api`、`mixin`、`core` 三个顶层职责包；
- `api` 不引用 `core`、`mixin`、Accessor、反射桥、目标模组类或实现专用 NBT；
- `core` 不依赖 `mixin`，也不依赖 `timewheel`、`overload` 或 AE2LT；
- `FastCraftingPlanner` 不导入 LT 闭环或过载具体类型；
- Thunderbolt 不包含 `Ae2LtTimeWheelCraftingCpuLogic` 等产品实现；
- Thunderbolt 不包含 `api.probability`、`api.overload`、`api.timewheel` 或 `api.wireless`；
- Channel、Planner、Batch、Eject 四个稳定 API 家族有独立契约测试；
- AdvancedAE、NeoECO 等目标模组名称只出现在 `mixin` 的兼容实现/选择器、测试和构建依赖中。

### API 稳定性

- 本分支冻结后的 `api.*` 消费者可通过后续兼容测试继续加载；冻结前 `ae2.*` 实验包按迁移说明升级；
- 新增默认方法不会改变旧实现的行为；
- Planner 注册顺序与结果选择在不同 JVM 和加载顺序下保持确定；
- 无第三方 Planner/Batch/Channel provider 时完整回退到 AE2/Thunderbolt 默认行为；
- `UNSUPPORTED`、`BUDGET_EXHAUSTED`、暂时容量为零和严格失败不会互相混淆；
- API DTO 的集合和上下文不可被调用方修改；
- 旧 `supportsSingleSeedBatch()` 实现通过兼容默认方法继续生效，新 shared-input 名称不改变旧 provider 行为；
- Eject API 类型签名不出现 WeakReference、ghost BlockEntity、SavedData 或 Mixin 类型；
- 伪造的非 AE2LT 第三方实现能够只依赖 `api` 编译和通过契约测试。

### 通用规划器

- 普通消耗、容器、催化剂、耐久、模糊、多输出、副产物和一般循环有差分测试；
- `EXACT_FEASIBLE` 不产生无法执行的计划；
- `EXACT_INFEASIBLE` 的 Missing 已传导到叶子原料；
- 预算耗尽不得冒充严格不可行；
- 对未支持语义可以安全交还 AE2；
- 大数量请求不按 firing 数逐次展开。

### AE2LT 闭环

- 闭环计划只提交到正确天枢/Pigmee CPU；
- 单种子、多种子、共享种子池和独占种子池行为保持不变；
- 模糊种子变体不会串到其他消费者；
- 取消、重启、区块卸载和方块拆除不复制或吞掉物品；
- 没有批量供应器时能走单次下发；
- TimeWheel 调度性能不因移动仓库而退化；
- 旧世界 NBT 能够继续加载。

### AE2LT 过载

- `STRICT`、`ID_ONLY`、`MIXED` 输入语义保持不变；
- 普通 waiting 与过载 waiting 不重复扣账；
- 模糊输出只认领到正确 requester、CPU 或闭环消费者；
- AE2、AdvancedAE、NeoECO 三条执行路径行为一致；
- 普通样板不进入过载状态机。

## 最终结论

Thunderbolt 的目标不是积累许多功能接口，而是维持三个职责清晰的包：稳定契约放 `api`，AE2 与未主动兼容模组的侵入适配放 `mixin`，关键算法和默认实现放 `core`。

稳定 API 只保留 Channel、Crafting Planner、Crafting Batch、Eject capability 投影四个家族。`supportsSingleSeedBatch()` 作为通用 Batch shared-input 能力兼容保留。概率样板、闭环、过载等特殊规划全部通过统一 Planner SPI 接入；无线、扩展 CPU、绑定计划、计划装饰、样板展开和 LT 种子类型不再形成独立 Thunderbolt API。

TimeWheel CPU 是当前闭环样板的必要执行器，却不是 Thunderbolt 定位中的通用 API 或通用算法库；它应移动回 AE2LT。闭环种子求解和 LT 过载匹配同样应移动回 AE2LT。Thunderbolt 只保留统一接入它们所需的 Planner/Batch SPI、中立 Eject capability 投影、AE2/NeoForge Mixin 接缝以及可独立复用的频道、图、整数、存储和批量算法。
