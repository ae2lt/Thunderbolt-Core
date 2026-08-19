# Thunderbolt 多合成算法提供器与能力声明标准

## 1. 模型

Thunderbolt 把算法实现、节点选择和一次计算的候选链分成三层：

```text
进程级算法注册表
  ID + 实现 + 算法优先级 + 是否公开

Grid 节点提供器
  提供的私有算法 ID 集合 + 当前选择的算法 ID + 玩家优先级

CraftingCalculation 快照
  当前 Grid 的公开算法和在线提供器选择
  -> 去重、排序
  -> 每个候选独占运行一次完整计算
  -> 第一项完整成功的算法成为结果算法
  -> 失败、拒绝或超时后整次重算下一候选
  -> AE2 原版算法作为最后一个普通候选
```

Grid 不保存也不复制一份全局选择。节点只保存自己的选择，因此网络分裂不复制冲突
状态，网络合并也不需要从两边静默挑选一份全局配置。

## 2. 算法注册

算法作者在模组初始化期注册实现，同时声明算法优先级和公开性：

```java
CraftingPlanningEngines.register(engine, algorithmPriority, publicAlgorithm);
```

- `algorithmPriority`：算法作者根据 Thunderbolt 参考测试声明的默认排序；
- `publicAlgorithm=true`：没有任何提供器节点时仍可参与规划；
- `publicAlgorithm=false`：必须由至少一个在线节点选择才可参与规划；
- 重复 ID 不允许替换已有实现或元数据；
- `ae2:vanilla` 是保留 ID，代表 AE2 原版规划器；
- `thunderbolt:all_failed` 是只用于结果显示的保留 ID，不能注册或选择。

注册表提供：

```java
CraftingPlanningEngines.get(id);
CraftingPlanningEngines.getPublic();
CraftingPlanningEngines.isPublic(id);
CraftingPlanningEngines.allIds();
```

`getPublic()` 和 `isPublic()` 都把 `ae2:vanilla` 视为公开算法。Vanilla
不注册为普通 `CraftingPlanningEngine`；解析器遇到这个保留 ID 时进入 AE2 原版路径。

## 3. 最小提供器 API

一个 Grid 节点固定提供一个或多个私有算法，并在“自己的算法 + 所有公开算法”中选择当前算法：

```java
public interface CraftingAlgorithmProvider extends IGridNodeService {
    ResourceLocation getProvidedAlgorithm();

    default List<ResourceLocation> getProvidedAlgorithms() {
        return List.of(getProvidedAlgorithm());
    }

    ResourceLocation getSelectedAlgorithm();

    int getPriority();
}
```

`getProvidedAlgorithm()` 保留为主算法和兼容回退；旧实现无需修改，默认仍只提供这一个
算法。需要提供多个私有算法的节点覆盖 `getProvidedAlgorithms()`，并把主算法放在第一项。
这组所有权不会随 GUI 选择改变。提供器也可以选择公开算法，包括 Vanilla；含义是为该
公开算法赋予这个节点配置的玩家优先级，而不是注册第二份算法实现。提供器不能选择其他
节点拥有的私有算法，默认 GUI 与 Grid 解析都会校验这一限制。尚未注册的附加私有算法不
进入菜单，因此配置关闭的可选后端不会显示为可选项。

同一算法由多个节点选择时只生成一个候选项，玩家优先级取这些节点中的最大值。

若节点需要使用 Thunderbolt 默认 GUI，则实现可写扩展：

```java
public interface ConfigurableCraftingAlgorithmProvider
        extends CraftingAlgorithmProvider {
    void setSelection(CraftingAlgorithmSelection selection);
}
```

第三方节点不需要默认 GUI 时，只实现最小接口即可。

## 4. 优先级解析

公开算法在没有节点选择时使用：

```text
PUBLIC_DEFAULT_PRIORITY = 0
```

若至少一个提供器选择了某个公开算法，则显式提供器优先级替换公开默认值，而不是与
默认值取最大值。这样玩家把公开算法设为负优先级时仍然有效。

候选项按以下键排序：

1. 玩家/提供器优先级降序；
2. 算法声明优先级降序；
3. 算法 ID 字典序，仅作为确定性同级裁决。

玩家优先级始终比算法优先级更高。算法优先级只在玩家优先级相同时发挥作用。

Vanilla 在没有显式节点选择时，算法优先级为最低，因此自然位于最后。节点可以用较
高玩家优先级显式选择 Vanilla；因为 Vanilla 是终止候选项，这相当于玩家明确要求在
它后面的快速算法不再运行。

示例：

| 算法 | 公开 | 算法优先级 | 在线提供器优先级 | 有效玩家优先级 |
| --- | --- | ---: | --- | ---: |
| Thunderbolt V2 | 否 | 1000 | 闪电 CPU-A: 20 | 20 |
| Third Party | 否 | 800 | CPU-B: 30 | 30 |
| Other Public | 是 | 500 | 无 | 0 |
| Vanilla | 是 | 最低 | 无 | 0 |

结果：

```text
Third Party -> Thunderbolt V2 -> Other Public -> Vanilla
```

## 5. 网络变化与区块加载

Grid service 只维护当前 Grid 中的提供器节点引用，并在开始一次计算前读取在线节点的
选择：

- 网络分离：每个子网只看到分到自己一侧的提供器；
- 网络合并：两个提供器集合做并集，然后按同一排序规则重新解析；
- 相同算法：按算法 ID 去重并取最高玩家优先级；
- 不同算法：保留双方选择，不覆盖任何节点 NBT；
- 提供器区块卸载或节点离线：该节点暂时不参与解析；
- 重新加载：节点读取自己的 NBT，重新进入所在 Grid；
- 公开算法：无论提供器是否加载都保持可用；
- 非公开算法：最后一个相关提供器离线后从候选链移除。

这个模型不承诺“非公开节点算法在节点卸载后仍继续可用”。如果需要这种行为，就必须
重新引入网络级快照、版本和分裂合并冲突协议，不属于本接口。

## 6. 默认 NBT 状态

Thunderbolt 提供可直接复用的状态对象：

```java
private final DefaultCraftingAlgorithmProviderState algorithmProvider =
        new DefaultCraftingAlgorithmProviderState(
                ThunderboltV2PlanningEngine.ID,
                List.of(
                        ThunderboltV2PlanningEngine.ID,
                        CpSatPlanningEngine.ID),
                0,
                this::setChanged);
```

宿主把它注册为节点服务：

```java
mainNode.addService(CraftingAlgorithmProvider.class, algorithmProvider);
```

在宿主自己的 NBT 中保存和加载：

```java
var algorithmTag = new CompoundTag();
algorithmProvider.writeToNBT(algorithmTag);
tag.put("ThunderboltAlgorithmProvider", algorithmTag);

algorithmProvider.readFromNBT(
        tag.getCompound("ThunderboltAlgorithmProvider"));
```

`thunderbolt:v2` 与 `thunderbolt:cp_sat` 是两个完整候选，不是同一规划器的两个整数后端。
V2 session 只运行 V2；CP-SAT session 直接导出 `CraftGraph`、建立 OR-Tools 模型并独立回放，
不会创建或调用 `CraftPlannerV2`。CP-SAT 已直接编码普通副产物以及经加权非增益证明的反馈
SCC；容器 remainder 规范化为普通副产物，耐久损坏由图导出层折成 carrier use 链。对未
证明的活动反馈或重叠 host 匹配返回 `DECLINE`。路由器随后从头尝试
下一个候选，不能把 CP-SAT 的 firing 向量交给 V2 继续补全，也不能把 V2 的成功结果记为
CP-SAT。

编码字段只有：

```text
Algorithm: ResourceLocation string
Priority: int
```

读取未知算法 ID 时不会删除它。该算法模组以后恢复时，原有节点选择也会恢复生效。
`readFromNBT` 不触发 dirty callback；只有玩家实际修改选择时才触发。

## 7. 默认子菜单

宿主节点可以从自己的主菜单或交互处理器打开默认子菜单：

```java
CraftingAlgorithmProviderMenu.open(
        serverPlayer,
        algorithmProvider,
        Component.translatable("gui.thunderbolt.algorithm_provider.title"),
        player -> stillAllowedToConfigure(player));
```

宿主必须提供距离、所有权或安全终端权限检查。默认菜单不猜测第三方机器的权限模型。

默认界面支持：

- 在节点提供的已注册私有算法、所有公开算法和 Vanilla 之间切换；
- 公开/需要提供器状态显示；
- 玩家优先级 `-10/-1/0/+1/+10` 调整；
- 服务端通过 container button 立即验证并写回；
- 优先级限制在 `[-1_000_000, 1_000_000]`，避免溢出；
- 当前 NBT 中未知的算法 ID 不会被删除；其实现重新注册后会恢复为可选项。

默认 GUI 是可选辅助层，不是 `CraftingAlgorithmProvider` 的依赖。

## 8. 完整计算所有权、诊断与顺序回退

AE2 在选择实际 CPU 之前创建 `CraftingCalculation`。Thunderbolt 因此在任务提交到规划
线程之前完成以下工作：

1. 从 Grid service 读取在线提供器；
2. 读取每个节点的算法 ID 和玩家优先级；
3. 与公开算法合并、去重和排序；
4. 构造所有候选共用的 `PlanningRequest`；
5. 在当前 Grid 所在线程依次执行每个引擎的 `check`，通过后立即执行 `capture`；
6. 把候选、引擎引用和抓取结果绑定后写入本次 `CraftingCalculation`；
7. 异步线程只接收公共请求和已经抓取的数据，不再直接接收 `IGrid`、方块实体或提供器对象。

引擎协议：

```java
boolean check(IGrid grid, PlanningRequest request);

default Object capture(IGrid grid, PlanningRequest request) {
    return null;
}

@Nullable PlanningEngineSession createSession(
        PlanningRequest request,
        @Nullable Object capturedInput,
        PlanningAttemptContext context);
```

`check` 是同步、低成本且必须有界、不得阻塞的适用性检查；返回 `false` 时直接跳过该候选，
不执行抓取。`capture` 同样必须有界、不得阻塞，只负责从 live Grid 抓取该引擎额外需要的
数据，默认返回 `null`，表示无需额外数据。返回对象必须能在后台安全读取，后台不得再通过它
间接访问或修改 live Grid。这两个入口在候选隔离线程创建之前执行，因此故意不承担后台超时
兜底；注册实现必须把它们当作 Grid 线程上的轻量快照阶段。
`createSession` 在隔离的候选线程执行，只接收公共请求、对应抓取结果和通用生命周期
context；返回 `null` 等价于该候选 `DECLINE`。V2 当前所需输入已包含在公共请求中，因此
使用默认空抓取。

会话在每个 amount probe 返回：

```text
DECLINE
HANDLED(plan)
HANDLED(null)
```

- `HANDLED(plan)`：本 probe 成功；
- `HANDLED(null)`：本 probe 的权威不可合成结果；
- `DECLINE`：当前候选放弃整次计算，而不是只把这一个 probe 交给另一算法。

一个候选必须独占 AE2 的完整 `computePlan`，包括精确数量、`CRAFT_LESS` 探测和最终
simulation。只有完整计算与 session `finish` 都成功后才写入结果算法。任何 probe
`DECLINE` 或运行时异常都会丢弃该候选的全部结果，从初始 requested amount 开始运行下一候选；
软超时仅停止继续扩展，宽限期内返回的完整结果仍可接受，硬超时才无条件丢弃。不同算法的 probe
绝不拼成同一份计划。外部取消在候选内部使用统一退出
协议，但路由层保留取消来源：候选后来返回的结果会被丢弃，整次计算按 cancel 退出且不回退。
JVM 致命错误同样不回退。

deadline 与诊断通过稳定 API 提供：

```java
PlanningAttempt attempt(long amount, boolean simulate, PlanningAttemptContext context);

ICraftingPlan finish(ICraftingPlan result, PlanningAttemptContext context);

context.deadlineNanos();
context.checkpoint();
context.report(new PlanningDiagnosticSnapshot(phase, metrics));
```

`createSession`、`attempt` 和 `finish` 共用同一个 context。引擎应在图导出、搜索以及高频循环中调用
`checkpoint()`，并用 engine-neutral 的 phase
与数值 metrics 报告进度。默认时序为：2 秒记录慢调用诊断；前 3 秒为正常计算预算，
预算结束后 `checkpoint()` 直接抛候选级 `PlanningExitException`，但不发线程中断。外部计算
取消也先传入同一个 `PlanningExitException`，不立刻发线程中断，并从取消时刻给予最多 1 秒
退出宽限（与原硬期限取先到者）；不向引擎暴露第二种协作退出状态。引擎必须在
捕获 exit 后立刻收敛：已有经过验证的当前最优计划就返回该计划，没有可用计划就返回
`DECLINE`，不得再启动新的 probe 或扩展搜索。路由层随后按保留的来源处理：预算退出允许
接受宽限期内返回的完整可用结果或顺序回退；外部取消仍会立即向调用方传播 cancel，并丢弃候选
后来返回的结果。只有候选未在取消宽限内退出时才发硬中断并隔离，因此正常取消不会令算法进入
中断路径。
返回和 `finally`/session 清理共用随后 1 秒宽限期；总计 4 秒仍未返回时隔离并摘除该调用，
同时发送中断。三个时间分别可用
`thunderbolt.planningWarnMs`、`thunderbolt.planningTimeoutMs` 和
`thunderbolt.planningStopGraceMs` 调整。

每个非 Vanilla 候选的 `check/capture` 在提交 AE2 计算任务前由 Grid 所在线程顺序执行；
所有候选（包括 Vanilla）的计算都在隔离的 daemon 虚拟线程中执行，自定义引擎的
`createSession/probe/finish/close` 也全部限制在同一个候选线程。session 在成功、`DECLINE`、
异常或超时退出后统一关闭。等待后台候选时，AE2 计算所有者每次只检查一次结果就把当前 tick
交还给 AE2，不会把服务端 tick 卡在 `simulateFor()`。宽限期内可以接受完整可用结果，但不继续
启动新的 amount probe；4 秒硬期限到达时先把仍在运行的算法（Vanilla 使用其保留 ID）标记为隔离并
阻止它的新调用，再发 interrupt，随后调用方立即摘除该次调用并
按顺序回退。被摘除的调用实际返回后自动解除隔离。Java 的
`Thread.interrupt()` 只设置中断状态，并只会让 `sleep/wait/join` 等阻塞点直接抛
`InterruptedException`；任意纯计算代码不会自动抛异常。因此永久忽略 checkpoint 和中断的
第三方线程可能继续占用一个 daemon 虚拟线程，但不能继续卡住 AE2 或被再次选用。Java 21 的
`Thread.stop()` 直接抛 `UnsupportedOperationException`，不作为强杀手段。

候选链按顺序包含 Vanilla。若包括 Vanilla 在内的所有候选均失败，返回一个 fail-closed
结果：`simulation=true`，`usedItems`、`missingItems`、`emittedItems` 和 pattern times
全部为空，算法显示 ID 为 `thunderbolt:all_failed`（中文显示“全部失败”）。该计划不能提交执行。

## 9. 算法能力声明

`algorithmPriority` 不是算法作者任意声称的速度排名。作者应使用 Thunderbolt 的统一参考
用例测试生产接口，并发布：

```text
EngineCapabilityDeclaration
  engineId
  engineVersion
  referenceStandardVersion
  environment
  claims[]
  knownLimitations[]
```

Thunderbolt 不要求服务器启动时重新跑完整能力测试。能力声明是作者声明，不是运行时
认证；服务端仍可以禁用有问题的算法。

测试结果至少区分：

| 生产路径结果 | 分类 | 是否算支持 | 调度行为 |
| --- | --- | --- | --- |
| 完整计算正确完成 | `SUPPORTED` | 是 | 使用该结果 |
| `check=false` | `CHECK_REJECTED` | 否 | 尝试下一项 |
| 任一 probe 为 `DECLINE` | `ATTEMPT_DECLINED` | 否 | 整次重算下一项 |
| 生产路径拒绝但强制调用成功 | `FALSE_NEGATIVE` | 否 | 只损失优化机会 |
| 抛出异常 | `ENGINE_ERROR` | 否 | 记录并尝试下一项 |
| 超时且可取消 | `ENGINE_TIMEOUT` | 否 | 取消并降级 |
| 超时且不响应取消 | `NON_COOPERATIVE_TIMEOUT` | 否 | 记录诊断并停用实现 |
| 返回错误 HANDLED 结果 | `FALSE_POSITIVE` | 否 | 高风险，禁止提交 |

参考用例应至少覆盖：

- 单链、多分支 DAG 和贪心陷阱；
- 多配方竞争同一中间物；
- 普通循环、自增长循环和无法启动的循环；
- 催化剂、可复用库存和耐久工具；
- 模糊输入、替代物和剩余物；
- 完整计划、缺失材料模拟和 CRAFT_LESS 多次 probe；
- 数量守恒、库存守恒、输出数量和缺失数量验证；
- 超时预算、取消响应和异常路径。

微小耗时差异不能覆盖正确性。玩家优先级也始终高于算法声明优先级。

## 10. 计划与 CPU 兼容性

算法返回的仍是 `ICraftingPlan`，实际运行时类型不会因为接口返回值而丢失。CPU 兼容性
由扩展 CPU 自己声明，不维护“计划类型到 CPU 类型”的注册表：

```java
default boolean canHandle(ICraftingPlan plan) {
    return plan instanceof CraftingPlan;
}
```

路由规则如下：

- `CraftingPlan` 可以提交给 AE2 原版 CPU；扩展 CPU 默认也接受；
- 其他 `ICraftingPlan` 不能静默提交给原版 CPU，只能提交给 `canHandle(plan)` 返回
  `true` 的扩展 CPU；
- 玩家显式选择不兼容的 CPU 时直接拒绝，不自动替换成其他 CPU；
- 自动选择时先过滤不兼容 CPU，再按来源偏好、协处理器和存储量排序；
- 没有兼容 CPU 时明确返回不可提交结果，不把专用计划降级为普通计划。

网格节点仍需暴露扩展 CPU，使 CraftingService 能发现它；这只是 CPU 实例发现机制，
不是兼容性注册。
