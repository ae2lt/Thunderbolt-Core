package com.moakiee.thunderbolt.ae2.mixin;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.hooks.ticking.TickHandler;
import appeng.me.service.CraftingService;

import com.moakiee.thunderbolt.ae2.batch.BatchCpuAccounting;
import com.moakiee.thunderbolt.ae2.batch.BatchExecutor;
import com.moakiee.thunderbolt.ae2.batch.BatchProviderFilterIterable;
import com.moakiee.thunderbolt.ae2.batch.NeoEcoBatchJobView;
import com.moakiee.thunderbolt.ae2.util.MixinReflectionSupport;

/** Makes NeoECO CPUs dispatch compatible patterns through Thunderbolt batch providers. */
@Pseudo
@Mixin(targets = "cn.dancingsnow.neoecoae.api.me.ECOCraftingCPULogic", remap = false)
public abstract class ECOCraftingCpuLogicBatchMixin {
    @Unique
    private static final @Nullable Class<?> AE2LT_ECO_LOGIC_CLASS =
            MixinReflectionSupport.findClassSafe("cn.dancingsnow.neoecoae.api.me.ECOCraftingCPULogic");
    @Unique
    private static final @Nullable Field AE2LT_ECO_JOB_FIELD =
            MixinReflectionSupport.findDeclaredFieldSafe(AE2LT_ECO_LOGIC_CLASS, "job");
    @Unique
    private static final @Nullable Field AE2LT_ECO_INVENTORY_FIELD =
            MixinReflectionSupport.findDeclaredFieldSafe(AE2LT_ECO_LOGIC_CLASS, "inventory");
    @Unique
    private static final @Nullable Field AE2LT_ECO_CPU_FIELD =
            MixinReflectionSupport.findDeclaredFieldSafe(AE2LT_ECO_LOGIC_CLASS, "cpu");

    @Unique
    @Nullable
    private Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> ae2lt$ecoBatchedByTask;
    @Unique
    private long ae2lt$ecoBatchTick;
    @Unique
    private boolean ae2lt$ecoBatchExhaustedThisTick;

    @WrapOperation(
            method = "tickCraftingLogic",
            at = @At(
                    value = "INVOKE",
                    target = "Lcn/dancingsnow/neoecoae/api/me/ECOCraftingCPULogic;executeCrafting"
                            + "(ILappeng/me/service/CraftingService;Lappeng/api/networking/energy/IEnergyService;"
                            + "Lnet/minecraft/world/level/Level;)I"))
    private int ae2lt$wrapEcoExecuteCrafting(
            @Coerce Object self,
            int remainingOps,
            CraftingService craftingService,
            IEnergyService energyService,
            Level level,
            Operation<Integer> original) {
        long now = TickHandler.instance().getCurrentTick();
        var batchedByTask = ae2lt$getEcoBatchedByTask();
        if (now != ae2lt$ecoBatchTick) {
            ae2lt$ecoBatchTick = now;
            batchedByTask.clear();
            ae2lt$ecoBatchExhaustedThisTick = false;
        }

        Object job = MixinReflectionSupport.getFieldValueSafe(AE2LT_ECO_JOB_FIELD, this);
        Object rawInventory = MixinReflectionSupport.getFieldValueSafe(AE2LT_ECO_INVENTORY_FIELD, this);
        if (ae2lt$ecoBatchExhaustedThisTick
                || !NeoEcoBatchJobView.acceptsJob(job)
                || !(rawInventory instanceof ListCraftingInventory inventory)) {
            return original.call(self, remainingOps, craftingService, energyService, level);
        }

        Object cpu = MixinReflectionSupport.getFieldValueSafe(AE2LT_ECO_CPU_FIELD, this);
        var batchResult = BatchExecutor.runBatchOnly(
                remainingOps,
                BatchCpuAccounting.Mode.LINEAR,
                craftingService,
                energyService,
                level,
                new NeoEcoBatchJobView(job),
                inventory,
                batchedByTask,
                () -> ae2lt$markEcoCpuDirty(cpu));

        if (batchResult.dispatchedCopies() > 0) {
            return batchResult.consumedCpuOps();
        }

        // NeoECO's original call retains its own pattern-bus fast path and ordinary per-copy path.
        ae2lt$ecoBatchExhaustedThisTick = true;
        return original.call(self, remainingOps, craftingService, energyService, level);
    }

    @WrapOperation(
            method = {
                    "executeCrafting",
                    "collectAvailableProviders"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/me/service/CraftingService;getProviders"
                            + "(Lappeng/api/crafting/IPatternDetails;)Ljava/lang/Iterable;"),
            remap = false,
            require = 1,
            expect = 1,
            allow = 1)
    private Iterable<ICraftingProvider> ae2lt$filterEcoBatchedProviders(
            CraftingService craftingService,
            IPatternDetails details,
            Operation<Iterable<ICraftingProvider>> original) {
        Iterable<ICraftingProvider> raw = original.call(craftingService, details);
        var batchedByTask = ae2lt$getEcoBatchedByTask();
        if (batchedByTask.isEmpty()) {
            return raw;
        }
        var perTask = batchedByTask.get(details);
        if (perTask == null || perTask.isEmpty()) {
            return raw;
        }
        return new BatchProviderFilterIterable(raw, perTask);
    }

    @Unique
    private Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> ae2lt$getEcoBatchedByTask() {
        if (ae2lt$ecoBatchedByTask == null) {
            ae2lt$ecoBatchedByTask = new HashMap<>();
        }
        return ae2lt$ecoBatchedByTask;
    }

    @Unique
    private static void ae2lt$markEcoCpuDirty(@Nullable Object cpu) {
        if (cpu instanceof ECOCraftingCpuAccessor accessor) {
            accessor.invokeMarkDirty();
        }
    }
}
