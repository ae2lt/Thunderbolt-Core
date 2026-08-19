package com.moakiee.thunderbolt.mixin.compat.advancedae;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.hooks.ticking.TickHandler;
import appeng.me.service.CraftingService;
import net.pedroksl.advanced_ae.common.logic.AdvCraftingCPULogic;

import com.moakiee.thunderbolt.core.crafting.batch.BatchCpuAccounting;
import com.moakiee.thunderbolt.core.crafting.batch.BatchExecutor;
import com.moakiee.thunderbolt.core.crafting.batch.BatchProviderFilterIterable;
import com.moakiee.thunderbolt.core.crafting.batch.DefaultBatchJobView;
import com.moakiee.thunderbolt.core.util.MixinReflectionSupport;

@Pseudo
@Mixin(targets = "net.pedroksl.advanced_ae.common.logic.AdvCraftingCPULogic", remap = false)
public abstract class AdvCraftingCpuLogicBatchMixin {
    // AdvancedAE 为可选依赖：Mixin 的 @Shadow 按 name+desc 精确匹配，Object 类型的 shadow
    // 必失配，因此照抄 AdvCraftingCpuLogicMixin 的静态缓存反射范式，查找失败时返回 null 降级。
    @Unique
    private static final @Nullable Class<?> AE2LT_ADV_BATCH_LOGIC_CLASS =
            MixinReflectionSupport.findClassSafe("net.pedroksl.advanced_ae.common.logic.AdvCraftingCPULogic");

    @Unique
    private static final @Nullable Field AE2LT_ADV_BATCH_JOB_FIELD =
            MixinReflectionSupport.findDeclaredFieldSafe(AE2LT_ADV_BATCH_LOGIC_CLASS, "job");

    @Unique
    private static final @Nullable Field AE2LT_ADV_BATCH_CPU_FIELD =
            MixinReflectionSupport.findDeclaredFieldSafe(AE2LT_ADV_BATCH_LOGIC_CLASS, "cpu");

    @Unique
    private static final @Nullable Class<?> AE2LT_ADV_BATCH_CPU_CLASS =
            MixinReflectionSupport.findClassSafe("net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU");

    @Unique
    private static final @Nullable Method AE2LT_ADV_BATCH_MARK_DIRTY_METHOD =
            MixinReflectionSupport.findDeclaredMethodSafe(AE2LT_ADV_BATCH_CPU_CLASS, "markDirty");

    @Shadow
    @Final
    private ListCraftingInventory inventory;

    @Unique
    @Nullable
    private Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> thunderbolt$batchedByTask;

    @Unique
    private long thunderbolt$batchTick;

    @Unique
    private boolean thunderbolt$batchExhaustedThisTick;

    @WrapOperation(
            method = "tickCraftingLogic",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/pedroksl/advanced_ae/common/logic/AdvCraftingCPULogic;executeCrafting"
                            + "(ILappeng/me/service/CraftingService;Lappeng/api/networking/energy/IEnergyService;"
                            + "Lnet/minecraft/world/level/Level;)I"
            )
    )
    private int thunderbolt$wrapExecuteCrafting(AdvCraftingCPULogic self,
                                          int remainingOps,
                                          CraftingService craftingService,
                                          IEnergyService energyService,
                                          Level level,
                                          Operation<Integer> original) {
        long now = TickHandler.instance().getCurrentTick();
        var batchedByTask = thunderbolt$getBatchedByTask();
        if (now != thunderbolt$batchTick) {
            thunderbolt$batchTick = now;
            batchedByTask.clear();
            thunderbolt$batchExhaustedThisTick = false;
        }

        // 反射读取 job（null 安全）；反射失败或字段不存在时得到 null，走原版调用降级。
        Object job = MixinReflectionSupport.getFieldValueSafe(AE2LT_ADV_BATCH_JOB_FIELD, this);
        if (job == null || thunderbolt$batchExhaustedThisTick) {
            return original.call(self, remainingOps, craftingService, energyService, level);
        }

        var jobAccessor = (AaeExecutingCraftingJobAccessor) (Object) job;
        var timeTracker = (AaeElapsedTimeTrackerAccessor) jobAccessor.getTimeTracker();
        var batchResult = BatchExecutor.runBatchOnly(
                remainingOps,
                BatchCpuAccounting.Mode.LINEAR,
                craftingService,
                energyService,
                new DefaultBatchJobView(
                        level,
                        jobAccessor.getLink().getCraftingID(),
                        jobAccessor.getTasks(),
                        jobAccessor.getWaitingFor(),
                        task -> ((AaeTaskProgressAccessor) task).getValue(),
                        (task, value) -> ((AaeTaskProgressAccessor) task).setValue(value),
                        timeTracker,
                        (tracker, count, type) -> ((AaeElapsedTimeTrackerAccessor) tracker)
                                .invokeAddMaxItems(count, type)),
                inventory,
                batchedByTask,
                this::thunderbolt$markCpuDirty);

        if (batchResult.dispatchedCopies() > 0) {
            // AdvancedAE CPUs keep batch extraction/provider dispatch, but pay one operation per copy.
            // UNBOUNDED providers (such as creative item sources) still pay one operation per dispatch.
            return batchResult.consumedCpuOps();
        }

        // No batch-dispatchable task this tick (no batch provider / all full / out of material).
        // Game time is frozen within a tick, so capacity cannot recover; skip the per-round re-probe.
        thunderbolt$batchExhaustedThisTick = true;
        return original.call(self, remainingOps, craftingService, energyService, level);
    }

    @WrapOperation(
            method = "executeCrafting",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/me/service/CraftingService;getProviders"
                            + "(Lappeng/api/crafting/IPatternDetails;)Ljava/lang/Iterable;"
            )
    )
    private Iterable<ICraftingProvider> thunderbolt$filterBatched(CraftingService craftingService,
                                                            IPatternDetails details,
                                                            Operation<Iterable<ICraftingProvider>> original) {
        var raw = original.call(craftingService, details);
        var batchedByTask = thunderbolt$getBatchedByTask();
        if (batchedByTask.isEmpty()) return raw;
        var perTask = batchedByTask.get(details);
        if (perTask == null || perTask.isEmpty()) return raw;
        return new BatchProviderFilterIterable(raw, perTask);
    }

    @Unique
    private void thunderbolt$markCpuDirty() {
        Object cpu = MixinReflectionSupport.getFieldValueSafe(AE2LT_ADV_BATCH_CPU_FIELD, this);
        if (cpu == null) return;
        MixinReflectionSupport.invokeMethodSafe(
                AE2LT_ADV_BATCH_MARK_DIRTY_METHOD,
                cpu,
                "mark AdvancedAE crafting CPU dirty");
    }

    @Unique
    private Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> thunderbolt$getBatchedByTask() {
        if (this.thunderbolt$batchedByTask == null) {
            this.thunderbolt$batchedByTask = new HashMap<>();
        }
        return this.thunderbolt$batchedByTask;
    }
}
