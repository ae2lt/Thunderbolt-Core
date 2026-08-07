package com.moakiee.thunderbolt.ae2.mixin;

import java.util.ArrayList;
import java.util.Set;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.stacks.AEKey;
import appeng.me.service.CraftingService;

import com.moakiee.thunderbolt.ae2.crafting.DynamicCraftingCpuClusterIndex;
import com.moakiee.thunderbolt.ae2.crafting.ExtendedCraftingCpuCluster;
import com.moakiee.thunderbolt.ae2.crafting.ExtendedCraftingCpuClusterProvider;

/**
 * Insert-into support for extended CPU clusters on {@link CraftingService}.
 *
 * <p>Kept in a separate mixin class on purpose: AdvancedAE 1.3.6 {@code @Overwrite}s
 * {@code CraftingService.insertIntoCpus} entirely (its {@code MixinCraftingService} leaves no
 * original invocation), which makes any injector on that method fail at prepare time with
 * "cannot inject into ... merged by ..." — a failure that {@code require = 0} does NOT suppress
 * in Mixin 0.8.5 (the merge check runs unconditionally during {@code InjectionInfo.prepare}).
 * {@link OptionalMixinSelector} therefore skips this whole class when AdvancedAE is loaded, so
 * the remaining {@link ExtendedCraftingCpuServiceMixin} injectors (ticking, getCpus, submitJob,
 * ...) stay active. Without AdvancedAE the normal injector path applies and extended CPUs
 * participate in item insertion again.
 *
 * <p>The cluster index is deliberately duplicated from {@link ExtendedCraftingCpuServiceMixin}
 * under distinct {@code thunderbolt$insert*} names: {@code @Unique} members are real fields in
 * the target class, so two mixins may not declare the same one.
 */
@Mixin(value = CraftingService.class, remap = false)
public abstract class ExtendedCraftingCpuInsertMixin {
    @Shadow
    @Final
    private IGrid grid;

    @Shadow
    private boolean updateList;

    @Shadow
    public abstract void addLink(appeng.crafting.CraftingLink link);

    @Unique
    @Nullable
    private DynamicCraftingCpuClusterIndex<IGridNode, ExtendedCraftingCpuCluster>
            thunderbolt$insertCpuClusterIndex;

    @Inject(
            method = "insertIntoCpus",
            // AdvancedAE unconditionally sets the return value from a cancellable RETURN
            // injector. A later RETURN callback is therefore skipped entirely. Mutating AE2's
            // local accumulator before IRETURN composes with both AdvancedAE and NeoECO instead.
            at = @At(value = "RETURN", shift = At.Shift.BY, by = -1))
    private void thunderbolt$insertIntoExtendedCpuClusters(
            AEKey what,
            long amount,
            Actionable type,
            CallbackInfoReturnable<Long> cir,
            @Local(ordinal = 1) LocalLongRef insertedRef) {
        long inserted = insertedRef.get();
        for (var cluster : thunderbolt$getInsertCpuClusters()) {
            if (inserted >= amount) {
                break;
            }
            inserted += cluster.insert(what, amount - inserted, type);
        }
        insertedRef.set(inserted);
    }

    @Inject(method = "updateCPUClusters", at = @At("TAIL"))
    private void thunderbolt$refreshInsertCpuClusters(CallbackInfo ci) {
        var providerNodes = new ArrayList<IGridNode>();
        for (var machineClass : this.grid.getMachineClasses()) {
            for (var node : this.grid.getMachineNodes(machineClass)) {
                if (thunderbolt$getInsertCpuClusterProvider(node) != null) {
                    providerNodes.add(node);
                }
            }
        }
        thunderbolt$getInsertCpuClusterIndex().replaceProviders(providerNodes);
        if (thunderbolt$getInsertCpuClusterIndex().refresh(
                ExtendedCraftingCpuInsertMixin::thunderbolt$resolveInsertCpuCluster,
                this::thunderbolt$addInsertCpuCluster)) {
            this.updateList = true;
        }
    }

    @Unique
    private void thunderbolt$addInsertCpuCluster(ExtendedCraftingCpuCluster cluster) {
        cluster.prepareForCraftingService();
        cluster.restoreCraftingLinks(this::addLink);
    }

    @Unique
    private DynamicCraftingCpuClusterIndex<IGridNode, ExtendedCraftingCpuCluster>
            thunderbolt$getInsertCpuClusterIndex() {
        if (this.thunderbolt$insertCpuClusterIndex == null) {
            this.thunderbolt$insertCpuClusterIndex = new DynamicCraftingCpuClusterIndex<>();
        }
        return this.thunderbolt$insertCpuClusterIndex;
    }

    @Unique
    private Set<ExtendedCraftingCpuCluster> thunderbolt$getInsertCpuClusters() {
        return thunderbolt$getInsertCpuClusterIndex().clusters();
    }

    @Unique
    @Nullable
    private static ExtendedCraftingCpuClusterProvider thunderbolt$getInsertCpuClusterProvider(IGridNode node) {
        var service = node.getService(ExtendedCraftingCpuClusterProvider.class);
        if (service != null) {
            return service;
        }
        return node.getOwner() instanceof ExtendedCraftingCpuClusterProvider provider ? provider : null;
    }

    @Unique
    @Nullable
    private static ExtendedCraftingCpuCluster thunderbolt$resolveInsertCpuCluster(IGridNode node) {
        var provider = thunderbolt$getInsertCpuClusterProvider(node);
        return provider != null ? provider.getExtendedCraftingCpuCluster() : null;
    }
}
