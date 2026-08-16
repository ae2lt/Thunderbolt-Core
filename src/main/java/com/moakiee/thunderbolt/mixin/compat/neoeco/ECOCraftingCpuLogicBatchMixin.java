package com.moakiee.thunderbolt.mixin.compat.neoeco;

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

import com.moakiee.thunderbolt.core.crafting.batch.BatchCpuAccounting;
import com.moakiee.thunderbolt.core.crafting.batch.BatchExecutor;
import com.moakiee.thunderbolt.core.crafting.batch.BatchProviderFilterIterable;
import com.moakiee.thunderbolt.core.util.MixinReflectionSupport;

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
    private Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> thunderbolt$ecoBatchedByTask;
    @Unique
    private long thunderbolt$ecoBatchTick;
    @Unique
    private boolean thunderbolt$ecoBatchExhaustedThisTick;

    @WrapOperation(
            method = "tickCraftingLogic",
            at = @At(
                    value = "INVOKE",
                    target = "Lcn/dancingsnow/neoecoae/api/me/ECOCraftingCPULogic;executeCrafting"
                            + "(ILappeng/me/service/CraftingService;Lappeng/api/networking/energy/IEnergyService;"
                            + "Lnet/minecraft/world/level/Level;)I"))
    private int thunderbolt$wrapEcoExecuteCrafting(
            @Coerce Object self,
            int remainingOps,
            CraftingService craftingService,
            IEnergyService energyService,
            Level level,
            Operation<Integer> original) {
        long now = TickHandler.instance().getCurrentTick();
        var batchedByTask = thunderbolt$getEcoBatchedByTask();
        if (now != thunderbolt$ecoBatchTick) {
            thunderbolt$ecoBatchTick = now;
            batchedByTask.clear();
            thunderbolt$ecoBatchExhaustedThisTick = false;
        }

        Object job = MixinReflectionSupport.getFieldValueSafe(AE2LT_ECO_JOB_FIELD, this);
        Object rawInventory = MixinReflectionSupport.getFieldValueSafe(AE2LT_ECO_INVENTORY_FIELD, this);
        if (thunderbolt$ecoBatchExhaustedThisTick
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
                new NeoEcoBatchJobView(job, level),
                inventory,
                batchedByTask,
                () -> thunderbolt$markEcoCpuDirty(cpu));

        if (batchResult.dispatchedCopies() > 0) {
            return batchResult.consumedCpuOps();
        }

        // NeoECO's original call retains its own pattern-bus fast path and ordinary per-copy path.
        thunderbolt$ecoBatchExhaustedThisTick = true;
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
    private Iterable<ICraftingProvider> thunderbolt$filterEcoBatchedProviders(
            CraftingService craftingService,
            IPatternDetails details,
            Operation<Iterable<ICraftingProvider>> original) {
        Iterable<ICraftingProvider> raw = original.call(craftingService, details);
        var batchedByTask = thunderbolt$getEcoBatchedByTask();
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
    private Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> thunderbolt$getEcoBatchedByTask() {
        if (thunderbolt$ecoBatchedByTask == null) {
            thunderbolt$ecoBatchedByTask = new HashMap<>();
        }
        return thunderbolt$ecoBatchedByTask;
    }

    @Unique
    private static void thunderbolt$markEcoCpuDirty(@Nullable Object cpu) {
        if (cpu instanceof ECOCraftingCpuAccessor accessor) {
            accessor.invokeMarkDirty();
        }
    }
}
