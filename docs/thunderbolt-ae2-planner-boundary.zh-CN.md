# Thunderbolt 规划器、核心样板契约与 AE2LT 运行时边界

## 当前结论

本设计以 PR #3 同步分支为目标，并语义合入
`feature/generic-conflict-solver @ 209d5dc`。合入后遵守以下边界：

1. 完整通用 planner、反馈环分析、冲突求解和 AE2 默认规划桥留在 Thunderbolt；
2. 过载样板与闭环样板向 Fast Planner/CPU 暴露的契约留在 Thunderbolt `core`；
3. `LoopCraftingPlan` 留在 Thunderbolt，但它是 `core` 中的默认实现，不属于稳定 API；
4. AE2LT 继续拥有过载样板数据格式、物品、界面、解码、输出认领以及闭环样板格式、天枢分析和 TimeWheel 执行器；
5. Thunderbolt 不导入 `com.moakiee.ae2lt.*`，AE2LT 使用版本匹配的 Thunderbolt core 契约接入这些能力。

这里的“接口留在 Thunderbolt”不等于把它们纳入稳定公共 API。`api` 只保留与 Mixin
接入强相关的算法、节点、CPU 和 batch 注册契约；Fast Planner 直接理解的样板事实以及
闭环执行元数据属于 `core`。具体数据结构、机器规则和持久化仍由产品实现拥有。

## 三层职责

### `api`：稳定扩展契约

`com.moakiee.thunderbolt.api` 是 Mixin 接入和外部注册实现依赖的稳定区域。

```text
api.crafting
├── algorithm/provider     算法注册、节点提供器、优先级与会话
├── cpu                    扩展 CPU 发现、提交与 canHandle
└── batch                  批量供应器与调度模式契约
```

### `core`：Thunderbolt 默认实现

`core` 可以被内部和第三方选择性复用，但不承诺像 `api` 一样保持实现细节稳定。

```text
core.crafting
├── algorithm              候选路由状态、deadline 监控与回退编排
├── planner                默认引擎适配、FastCraftingPlanner、图模型、反馈环和冲突求解
├── pattern                Planner 的通用样板能力
├── overload               过载样板的 planner-facing 元数据
├── loop                   闭环宏样板、种子、任务顺序和 CPU 限制
├── support                与 planner 无关的 Mixin 控制钩子和样板辅助
├── plan                   LoopCraftingPlan 等默认计划实现
└── batch                  默认批量调度实现
```

`LoopCraftingPlan` 位于 `core.crafting.plan`，原因是它包含 Thunderbolt 对
AE2 `CraftingPlan` 的具体包装、可复用库存分配和宿主借用记录。第三方不能通过实现或注册
`LoopCraftingPlan` 来声明新计划类型；版本匹配的集成实现 `core.crafting.loop` 中的样板元数据
接口，再由 Thunderbolt 默认规划会话生成计划。这里的 `public` 只满足跨模块编译，不承诺
像 `api` 一样保持源码或二进制兼容。

### `mixin`：AE2 接入与兼容层

`mixin` 只负责将 API/core 接入 AE2 或没有主动适配的附属模组，包括：

- 计算入口的候选解析、完整计算所有权和顺序回退；
- 扩展 CPU 的发现、列表、提交和 tick；
- AE2CT/菜单所需的只读计划摘要；
- AdvancedAE、NeoECO 等版本相关适配。

Mixin 不拥有闭环种子账本、过载输出认领或产品持久化状态。无限 CPU 容量显示等只依赖
Thunderbolt core 的 AE2 客户端适配也由 Thunderbolt 自己注册，不下放给 AE2LT。

Batch 热路径直接把现有 `BatchJobView` 传给
`pushBatch(details, template, maxCraft, job)`，不创建 dispatch context，也不复制 template
数组。job view 统一提供 level、crafting ID、任务和 waiting-for 视图；NeoECO 等适配器自行读取
所需信息，不把 job 拆成公共方法参数。已使用较久的三参数 `pushBatch` 保留为基础契约，job
重载默认转发给它。

依赖方向为：

```text
AE2LT / third party ──► Thunderbolt api + version-matched core contracts
Thunderbolt core    ──► Thunderbolt api
Thunderbolt mixin   ──► Thunderbolt api + core

api 不依赖 core 或 mixin
core 不依赖 mixin
Thunderbolt 不依赖 AE2LT
```

候选抓取阶段的 `check/capture` 是 Grid 线程上的受信轻量钩子，必须有界且不得阻塞；真正的
规划、会话创建和完成处理才进入统一的候选隔离与超时边界。只供两个 AE2 Mixin 互通的计算
桥接类型留在 `mixin.ae2.crafting` 包内，不作为 `core` 公共实现面暴露。

## 通用 planner 合入范围

`feature/generic-conflict-solver @ 209d5dc` 不能按旧路径直接复制，因为当前分支已将
`core.planner` 迁到 `core.crafting.planner`。本次按语义合入以下内容：

- `BoundedIntegerLinearSolver` 和低宽冲突分量求解；
- 同次 AE2 计算中的图、搜索和预算复用；
- `PlanningCancellation` 的取消传播；
- 图导出、reachable work、副产物调度和可复用库存匹配的有界失败；
- `ConservativeFeedbackAnalysis` 的闭环启动种子核算；
- 有界搜索失败时最小化 missing 诊断；
- planner capability reference suite 和 `referenceCapabilityTest` Gradle 任务。

`CraftPlannerV2` 只计算图上的库存、样板 firing、缺失和可行性，不负责真实机器下发。
`FastCraftingPlanner` 负责把 AE2 快照转换为通用图，再把结果转换回 AE2 `CraftingPlan`。
`ThunderboltV2PlanningEngine` 与它适配的 planner 同处 `core.crafting.planner`，算法路由包不再
反向拥有具体 planner 的引擎包装。

## 核心样板契约

### 通用 pattern 契约

- `FuzzyPatternInputs`：声明输入/输出槽是否接受或产生同主 ID 的晚绑定变体；
- `IWrappedPatternDetails`：暴露包装器的 delegate；
- `IProviderLookupPattern`：任务身份不变时，指定 provider lookup 使用的样板；
- `ReusableStockPattern`：声明 job-scoped 可复用库存需求和快照；
- `ReusableStockSource`：使用 storage/pool/routing 三层身份隔离物理库存与逻辑闭环；
- `CraftingStockPolicy`：请求者限制 planner 可使用的既有网络库存。

这些接口是 planner 的输入事实，不包含 AE2LT 物品、菜单或机器类型。

### 过载样板契约

`core.crafting.overload.OverloadedPatternDetails` 只声明：

- 稳定样板身份；
- 哪些输入槽允许同主 ID 变体；
- 哪些输出槽会产生晚绑定同主 ID 变体；
- 是否存在模糊输入。

AE2LT 的 `OverloadedProviderOnlyPatternDetails` 在此基础上增加 LT 专用的宿主类型和
`OverloadPatternDetails` 视图。`EncodedOverloadPattern`、编辑状态、物品 codec、输出认领和
CPU 状态仍属于 AE2LT。

因此 Thunderbolt 可以正确建立 supply matrix，而不需要导入 AE2LT 的具体模型。

### 闭环样板契约

`core.crafting.loop` 保留：

- `ClosedLoopPatternDetails` 与 `ClosedLoopBatchPatternDetails`；
- `PatternFiringExpander`；
- `ReusableSeedPattern`；
- `CraftingCpuRestrictedPattern`；
- `IPlannedSeedSlotPattern`、`ISeedPreservingCraftingTask`；
- `IPrioritizedCraftingTask`；
- `CraftingTaskPersistenceDefinition`。

这些接口描述宏样板展开、种子所有权、任务顺序、可持久化 definition 和 CPU 兼容性。
它们没有固定 TimeWheel 实现，也没有直接引用天枢方块或 AE2LT payload。

AE2LT 的 `Ae2ClosedLoopPatternDetails` 实现这些接口；TimeWheel CPU 通过
`LoopCraftingPlan.canRunOn(host)` 和自身 `canHandle(plan)` 明确接受该计划。

## `LoopCraftingPlan` 生命周期

默认规划算法先生成 AE2 `CraftingPlan`，并在 `PlanningMetadataStore` 中保存这次计划实际
借用的可复用库存。Thunderbolt 的计算结束 Mixin 统一生成计划包装：

```text
CraftingPlan
  + pattern metadata
  + used reusable stock
          │
          ▼
LoopCraftingPlan.wrapIfNeeded(...)
          │
          ├── 没有 CPU 限制样板：返回原 CraftingPlan
          └── 存在闭环样板：返回 core.crafting.plan.LoopCraftingPlan
```

`LoopCraftingPlan` 保存：

- 原 AE2 delegate；
- CPU 兼容限制；
- 每个闭环所需的总种子；
- 从宿主实际借用的具体变体；
- storage/pool/routing/group 维度的分配记录。

菜单和 AE2CT 只读取计划摘要；原始 `ICraftingPlan` 仍是 CPU 提交、路由、预留和执行的
权威对象，不能为了展示静默替换成另一份计划。

## AE2LT 仍然拥有的实现

Thunderbolt API 不接管以下产品职责：

- 过载样板的物品、编码界面、NBT/组件 codec 和 provider 限制；
- `STRICT`/`ID_ONLY` 编辑规则的产品配置；
- 过载输出认领、waiting 重叠扣账和 CPU 持久化；
- 天枢闭环 payload、成员解码、SCC/种子配置和上传终端；
- TimeWheel 调度、种子账本、任务恢复、取消和方块生命周期；
- 天枢/矩阵机器对具体计划的执行策略。

这些实现只通过 Thunderbolt API 和 `LoopCraftingPlan` 的公开只读方法协作。

## 兼容与回退

1. `CraftingPlanningEngines` 是唯一算法注册入口，不恢复第二套 planner registry；
2. 每个候选会话独占一次完整 `computePlan`；成功前不对外宣布选中算法；
3. 任一 probe `DECLINE`、运行时异常或 deadline 超时都会丢弃整次候选结果，并按固定顺序从头运行下一算法；
4. 不在 probe 中途换算法，不混用不同算法的精确、`CRAFT_LESS` 或 simulation 结果；
5. 外部取消向候选传入统一的 `PlanningExitException`，但路由层保留来源、丢弃返回值并按 cancel 直接传播；JVM 致命错误也直接传播，二者都不伪装为算法回退；
6. 所有候选（含 Vanilla）失败时返回空 used、空 missing 的不可提交 simulation 计划，并显示“全部失败”；
7. 所有候选（含 Vanilla）都在隔离线程运行：3 秒预算到点只发布 deadline，5 秒宽限期内接受已经算出的完整可用结果但不继续扩展搜索；8 秒仍不返回才隔离算法、发 `Thread.interrupt()`、摘除并继续顺序回退；旧调用退出后自动解除隔离；
8. 等待隔离候选时持续让出 AE2 calculation；不响应 checkpoint/interrupt 的候选也不能卡住调用线程，同一算法在旧调用退出前不能创建新调用；
9. 扩展 CPU 默认只接受原版 `CraftingPlan`，必须显式重写 `canHandle` 才能接受自定义计划；
10. `CraftingCpuRestrictedPattern` 的全部限制都通过后，闭环计划才可提交到对应 CPU；
11. 预算耗尽和未证明反馈环不得伪装为可行计划。

## 验证

Thunderbolt：

```bash
./gradlew test --no-daemon
./gradlew referenceCapabilityTest --no-daemon
```

AE2LT 必须使用本次 Thunderbolt 构建后执行：

```bash
./gradlew test --no-daemon
```

验收时还应确认：

- Thunderbolt 生产源码没有 `com.moakiee.ae2lt` 导入；
- `api` 没有依赖 `core`/`mixin`；
- AE2LT 没有导入 `com.moakiee.thunderbolt.mixin.*`，所需 accessor 由 AE2LT 自己持有；
- AE2LT 不再定义重复的 `LoopCraftingPlan` 和闭环/过载 planner-facing 接口；
- 两仓库 `git diff --check` 通过。
