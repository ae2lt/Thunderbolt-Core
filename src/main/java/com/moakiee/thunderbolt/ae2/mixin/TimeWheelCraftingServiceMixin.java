package com.moakiee.thunderbolt.ae2.mixin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.Future;

import com.google.common.collect.ImmutableSet;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;

import org.apache.commons.lang3.mutable.MutableObject;
import org.objectweb.asm.Opcodes;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.crafting.UnsuitableCpus;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;

import com.moakiee.thunderbolt.ae2.crafting.FastCraftingControl;
import com.moakiee.thunderbolt.ae2.timewheel.TimeWheelCraftingCPU;
import com.moakiee.thunderbolt.ae2.timewheel.TimeWheelCraftingCpuPool;
import com.moakiee.thunderbolt.ae2.timewheel.TimeWheelCraftingCpuPoolProvider;
import com.moakiee.thunderbolt.ae2.timewheel.TimeWheelFastPlanningGate;

@Mixin(value = CraftingService.class, remap = false)
public abstract class TimeWheelCraftingServiceMixin {
    @Unique
    private static final Comparator<TimeWheelCraftingCpuPool> THUNDERBOLT_TIME_WHEEL_FAST_FIRST = Comparator
            .comparingInt(TimeWheelCraftingCpuPool::getCoProcessors)
            .reversed()
            .thenComparingLong(TimeWheelCraftingCpuPool::getAvailableStorage);

    @Unique
    @Nullable
    private Set<IGridNode> thunderbolt$timeWheelProviderNodes;

    @Unique
    @Nullable
    private Set<TimeWheelCraftingCpuPool> thunderbolt$timeWheelPools;

    @Unique
    @Nullable
    private Set<TimeWheelCraftingCpuPool> thunderbolt$refreshedTimeWheelPools;

    @Shadow
    @Final
    private IGrid grid;

    @Shadow
    @Final
    private IEnergyService energyGrid;

    @Shadow
    @Final
    private Set<AEKey> currentlyCrafting;

    @Shadow
    private boolean updateList;

    @Shadow
    public abstract void addLink(CraftingLink link);

    @Inject(
            method = "beginCraftingCalculation",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/ExecutorService;submit(Ljava/util/concurrent/Callable;)Ljava/util/concurrent/Future;",
                    shift = At.Shift.BEFORE))
    private void thunderbolt$enableFastPlanningForTimeWheelPool(Level level,
                                                                ICraftingSimulationRequester simRequester,
                                                                AEKey what,
                                                                long amount,
                                                                CalculationStrategy strategy,
                                                                CallbackInfoReturnable<Future<ICraftingPlan>> cir,
                                                                @Local CraftingCalculation job) {
        boolean enabled = TimeWheelFastPlanningGate.shouldEnableFastPlanning(thunderbolt$getTimeWheelPools());
        ((FastCraftingControl) job).ae2lt$setFastPlanningEnabled(enabled);
    }

    @Inject(
            method = "onServerEndTick",
            at = @At(
                    value = "FIELD",
                    target = "Lappeng/me/service/CraftingService;lastProcessedCraftingLogicChangeTick:J",
                    opcode = Opcodes.GETFIELD,
                    ordinal = 0))
    private void thunderbolt$tickTimeWheelPools(CallbackInfo ci,
                                                 @Local(ordinal = 0) LocalLongRef latestChange) {
        thunderbolt$refreshTimeWheelPools();
        long latest = latestChange.get();
        for (var pool : thunderbolt$getTimeWheelPools()) {
            latest = Math.max(latest, pool.tickCraftingLogic(this.energyGrid, (CraftingService) (Object) this));
            if (pool.consumeCpuListChanged()) {
                this.updateList = true;
            }
        }
        latestChange.set(latest);
    }

    @Inject(
            method = "onServerEndTick",
            at = @At(
                    value = "FIELD",
                    target = "Lappeng/me/service/CraftingService;interests:Lcom/google/common/collect/Multimap;",
                    opcode = Opcodes.GETFIELD,
                    ordinal = 0))
    private void thunderbolt$addTimeWheelWaitingKeys(CallbackInfo ci) {
        for (var pool : thunderbolt$getTimeWheelPools()) {
            pool.addWaitingKeys(this.currentlyCrafting);
        }
    }

    @Inject(method = "removeNode", at = @At("TAIL"))
    private void thunderbolt$onRemoveNode(IGridNode gridNode, CallbackInfo ci) {
        if (thunderbolt$getTimeWheelProviderNodes().remove(gridNode)) {
            thunderbolt$refreshTimeWheelPools();
        }
    }

    @Inject(method = "addNode", at = @At("TAIL"))
    private void thunderbolt$onAddNode(IGridNode gridNode, CompoundTag savedData, CallbackInfo ci) {
        if (thunderbolt$getTimeWheelProvider(gridNode) != null) {
            thunderbolt$getTimeWheelProviderNodes().add(gridNode);
            thunderbolt$refreshTimeWheelPools();
        }
    }

    @Inject(method = "updateCPUClusters", at = @At("TAIL"))
    private void thunderbolt$updateTimeWheelPools(CallbackInfo ci) {
        var providerNodes = thunderbolt$getTimeWheelProviderNodes();
        providerNodes.clear();
        for (var machineClass : this.grid.getMachineClasses()) {
            for (var node : this.grid.getMachineNodes(machineClass)) {
                if (thunderbolt$getTimeWheelProvider(node) != null) {
                    providerNodes.add(node);
                }
            }
        }
        thunderbolt$refreshTimeWheelPools();
    }

    @Inject(
            method = "insertIntoCpus",
            at = @At(value = "RETURN", shift = At.Shift.BY, by = -1),
            order = 500)
    private void thunderbolt$insertIntoTimeWheelPools(AEKey what,
                                                       long amount,
                                                       Actionable type,
                                                       CallbackInfoReturnable<Long> cir,
                                                       @Local(ordinal = 1) LocalLongRef inserted) {
        for (var pool : thunderbolt$getTimeWheelPools()) {
            if (inserted.get() >= amount) {
                break;
            }
            inserted.set(inserted.get() + pool.insert(what, amount - inserted.get(), type));
        }
    }

    @Inject(method = "submitJob", at = @At("HEAD"), cancellable = true)
    private void thunderbolt$submitToExplicitTimeWheelPool(ICraftingPlan job,
                                                            ICraftingRequester requestingMachine,
                                                            ICraftingCPU target,
                                                            boolean prioritizePower,
                                                            IActionSource src,
                                                            CallbackInfoReturnable<ICraftingSubmitResult> cir) {
        if (job.simulation()) {
            return;
        }

        if (target instanceof TimeWheelCraftingCpuPool pool) {
            cir.setReturnValue(pool.submitJob(this.grid, job, src, requestingMachine));
            return;
        }

        if (target instanceof TimeWheelCraftingCPU) {
            cir.setReturnValue(CraftingSubmitResult.CPU_BUSY);
        }
    }

    @Inject(
            method = "submitJob",
            at = @At(
                    value = "INVOKE_ASSIGN",
                    target = "Lappeng/me/service/CraftingService;findSuitableCraftingCPU"
                            + "(Lappeng/api/networking/crafting/ICraftingPlan;"
                            + "ZLappeng/api/networking/security/IActionSource;"
                            + "Lorg/apache/commons/lang3/mutable/MutableObject;)"
                            + "Lappeng/me/cluster/implementations/CraftingCPUCluster;"),
            cancellable = true)
    private void thunderbolt$submitToAutomaticTimeWheelPool(ICraftingPlan job,
                                                             ICraftingRequester requestingMachine,
                                                             ICraftingCPU target,
                                                             boolean prioritizePower,
                                                             IActionSource src,
                                                             CallbackInfoReturnable<ICraftingSubmitResult> cir,
                                                             @Local CraftingCPUCluster cpuCluster,
                                                             @Local MutableObject<UnsuitableCpus> unsuitableCpusResult) {
        var pool = thunderbolt$findSuitableTimeWheelPool(job, src, unsuitableCpusResult);
        if (pool != null) {
            cir.setReturnValue(pool.submitJob(this.grid, job, src, requestingMachine));
        }
    }

    @Inject(
            method = "getCpus",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/ImmutableSet$Builder;build()Lcom/google/common/collect/ImmutableSet;"))
    private void thunderbolt$getTimeWheelCpus(CallbackInfoReturnable<ImmutableSet<ICraftingCPU>> cir,
                                               @Local(ordinal = 0) ImmutableSet.Builder<ICraftingCPU> cpus) {
        for (var pool : thunderbolt$getTimeWheelPools()) {
            if (!pool.isActive()) {
                continue;
            }
            for (var cpu : pool.getActiveCpus()) {
                cpus.add(cpu);
            }
            if (pool.getAvailableStorage() > 0L) {
                cpus.add(pool);
            }
        }
    }

    @Inject(method = "getRequestedAmount", at = @At("RETURN"), cancellable = true)
    private void thunderbolt$getTimeWheelRequestedAmount(AEKey what, CallbackInfoReturnable<Long> cir) {
        long requested = cir.getReturnValue();
        for (var pool : thunderbolt$getTimeWheelPools()) {
            long addition = pool.getRequestedAmount(what);
            requested = requested >= Long.MAX_VALUE - addition ? Long.MAX_VALUE : requested + addition;
        }
        cir.setReturnValue(requested);
    }

    @Inject(method = "hasCpu", at = @At("HEAD"), cancellable = true)
    private void thunderbolt$hasTimeWheelCpu(ICraftingCPU cpu, CallbackInfoReturnable<Boolean> cir) {
        for (var pool : thunderbolt$getTimeWheelPools()) {
            if (pool.containsCpu(cpu)) {
                cir.setReturnValue(true);
                return;
            }
        }
    }

    @Unique
    private void thunderbolt$addTimeWheelPool(TimeWheelCraftingCpuPool pool) {
        if (!thunderbolt$getTimeWheelPools().add(pool)) {
            return;
        }
        pool.resolvePendingLoad();
        pool.restoreCraftingLinks(this::addLink);
    }

    @Unique
    private void thunderbolt$refreshTimeWheelPools() {
        var providerNodes = thunderbolt$getTimeWheelProviderNodes();
        var pools = thunderbolt$getTimeWheelPools();
        var refreshedPools = thunderbolt$getRefreshedTimeWheelPools();
        refreshedPools.clear();
        for (var node : providerNodes) {
            var provider = thunderbolt$getTimeWheelProvider(node);
            if (provider == null) {
                continue;
            }
            var pool = provider.getTimeWheelCraftingCpuPool();
            if (pool != null) {
                refreshedPools.add(pool);
            }
        }

        boolean changed = !pools.equals(refreshedPools);
        pools.removeIf(pool -> !refreshedPools.contains(pool));
        for (var pool : refreshedPools) {
            thunderbolt$addTimeWheelPool(pool);
        }
        if (changed) {
            this.updateList = true;
        }
    }

    @Unique
    @Nullable
    private static TimeWheelCraftingCpuPoolProvider thunderbolt$getTimeWheelProvider(IGridNode node) {
        var service = node.getService(TimeWheelCraftingCpuPoolProvider.class);
        if (service != null) {
            return service;
        }
        return node.getOwner() instanceof TimeWheelCraftingCpuPoolProvider provider ? provider : null;
    }

    @Unique
    private Set<IGridNode> thunderbolt$getTimeWheelProviderNodes() {
        if (this.thunderbolt$timeWheelProviderNodes == null) {
            this.thunderbolt$timeWheelProviderNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        }
        return this.thunderbolt$timeWheelProviderNodes;
    }

    @Unique
    private Set<TimeWheelCraftingCpuPool> thunderbolt$getTimeWheelPools() {
        if (this.thunderbolt$timeWheelPools == null) {
            this.thunderbolt$timeWheelPools = Collections.newSetFromMap(new IdentityHashMap<>());
        }
        return this.thunderbolt$timeWheelPools;
    }

    @Unique
    private Set<TimeWheelCraftingCpuPool> thunderbolt$getRefreshedTimeWheelPools() {
        if (this.thunderbolt$refreshedTimeWheelPools == null) {
            this.thunderbolt$refreshedTimeWheelPools = Collections.newSetFromMap(new IdentityHashMap<>());
        }
        return this.thunderbolt$refreshedTimeWheelPools;
    }

    @Unique
    @Nullable
    private TimeWheelCraftingCpuPool thunderbolt$findSuitableTimeWheelPool(
            ICraftingPlan job,
            IActionSource src,
            MutableObject<UnsuitableCpus> unsuitableCpusResult) {
        var pools = thunderbolt$getTimeWheelPools();
        var valid = new ArrayList<TimeWheelCraftingCpuPool>(pools.size());
        int offline = 0;
        int tooSmall = 0;
        int excluded = 0;
        for (var pool : pools) {
            if (!pool.isActive()) {
                offline++;
                continue;
            }
            if (pool.getAvailableStorage() < job.bytes()) {
                tooSmall++;
                continue;
            }
            if (!pool.canBeAutoSelectedFor(src)) {
                excluded++;
                continue;
            }
            valid.add(pool);
        }

        if (valid.isEmpty()) {
            if (offline > 0 || tooSmall > 0 || excluded > 0) {
                var existing = unsuitableCpusResult.getValue();
                if (existing == null) {
                    unsuitableCpusResult.setValue(new UnsuitableCpus(offline, 0, tooSmall, excluded));
                } else {
                    unsuitableCpusResult.setValue(new UnsuitableCpus(
                            saturatingAdd(existing.offline(), offline),
                            existing.busy(),
                            saturatingAdd(existing.tooSmall(), tooSmall),
                            saturatingAdd(existing.excluded(), excluded)));
                }
            }
            return null;
        }

        valid.sort((a, b) -> {
            boolean firstPreferred = a.isPreferredFor(src);
            boolean secondPreferred = b.isPreferredFor(src);
            if (firstPreferred != secondPreferred) {
                return Boolean.compare(secondPreferred, firstPreferred);
            }
            return THUNDERBOLT_TIME_WHEEL_FAST_FIRST.compare(a, b);
        });
        return valid.getFirst();
    }

    @Unique
    private static int saturatingAdd(int left, int right) {
        return left >= Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right;
    }
}
