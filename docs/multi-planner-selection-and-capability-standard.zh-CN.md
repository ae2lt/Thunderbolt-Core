# Thunderbolt 多合成算法提供器与能力声明标准

## 1. 模型

Thunderbolt 把算法实现、节点选择和一次计算的候选链分成三层：

```text
进程级算法注册表
  ID + 实现 + 算法优先级 + 是否公开

Grid 节点提供器
  当前选择的算法 ID + 玩家优先级

CraftingCalculation 快照
  当前 Grid 的公开算法和在线提供器选择
  -> 去重、排序
  -> 第一项能够处理请求的算法锁定整次计算
  -> AE2 原版算法兜底
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
- `thunderbolt:vanilla` 是保留 ID，代表 AE2 原版规划器。

注册表提供：

```java
CraftingPlanningEngines.get(id);
CraftingPlanningEngines.getPublic();
CraftingPlanningEngines.isPublic(id);
CraftingPlanningEngines.allIds();
```

`getPublic()` 和 `isPublic()` 都把 `thunderbolt:vanilla` 视为公开算法。Vanilla
不注册为普通 `CraftingPlanningEngine`；解析器遇到这个保留 ID 时进入 AE2 原版路径。

## 3. 最小提供器 API

一个 Grid 节点只选择一个算法：

```java
public interface CraftingAlgorithmProvider extends IGridNodeService {
    ResourceLocation getSelectedAlgorithm();

    int getPriority();
}
```

提供器可以选择公开算法，包括 Vanilla。含义是为该公开算法赋予这个节点配置的玩家
优先级，而不是注册第二份算法实现。

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
| Thunderbolt V2 | 是 | 1000 | CPU-A: 20 | 20 |
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

- 在所有已注册算法和 Vanilla 之间切换；
- 公开/需要提供器状态显示；
- 玩家优先级 `-10/-1/0/+1/+10` 调整；
- 服务端通过 container button 立即验证并写回；
- 优先级限制在 `[-1_000_000, 1_000_000]`，避免溢出；
- 当前 NBT 中未知的算法 ID仍显示在列表中，不静默丢失。

默认 GUI 是可选辅助层，不是 `CraftingAlgorithmProvider` 的依赖。

## 8. 计算快照与算法锁定

AE2 在选择实际 CPU 之前创建 `CraftingCalculation`。Thunderbolt 因此在任务提交到规划
线程之前完成以下工作：

1. 从 Grid service 读取在线提供器；
2. 读取每个节点的算法 ID 和玩家优先级；
3. 与公开算法合并、去重和排序；
4. 把不可变候选链写入本次 `CraftingCalculation`；
5. 异步线程只使用算法注册表和不可变请求数据，不持有方块实体或提供器对象。

引擎协议：

```java
boolean check(IGrid grid, PlanningRequest request);

PlanningEngineSession createSession(IGrid grid, PlanningRequest request);
```

首次 probe 可以返回：

```text
DECLINE
HANDLED(plan)
HANDLED(null)
```

- `DECLINE`：继续下一候选算法；
- `HANDLED(plan)`：锁定算法，并复用同一 session 处理后续 amount probe；
- `HANDLED(null)`：权威的不可合成结果，同样锁定算法；
- 已锁定算法后续再返回 `DECLINE` 属于协议错误，不能中途换算法；
- 候选链最终必须进入 Vanilla，保证 AE2 原版行为仍是兜底。

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
| 正确完成 | `SUPPORTED` | 是 | 使用并锁定 |
| `check=false` | `CHECK_REJECTED` | 否 | 尝试下一项 |
| 首次 probe 为 `DECLINE` | `ATTEMPT_DECLINED` | 否 | 尝试下一项 |
| 生产路径拒绝但强制调用成功 | `FALSE_NEGATIVE` | 否 | 只损失优化机会 |
| 抛出异常 | `ENGINE_ERROR` | 否 | 记录并尝试下一项 |
| 超时且可取消 | `ENGINE_TIMEOUT` | 否 | 取消并降级 |
| 超时且不响应取消 | `NON_COOPERATIVE_TIMEOUT` | 否 | 隔离算法 |
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
