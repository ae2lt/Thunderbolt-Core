package com.moakiee.thunderbolt.ae2.mixin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Future;

import com.google.common.collect.ImmutableSet;
import com.llamalad7.mixinextras.sugar.Local;

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
import appeng.me.service.CraftingService;

import com.moakiee.thunderbolt.ae2.crafting.ExtendedCraftingCpuCluster;
import com.moakiee.thunderbolt.ae2.crafting.ExtendedCraftingCpuClusterHost;
import com.moakiee.thunderbolt.ae2.crafting.FastCraftingControl;
import com.moakiee.thunderbolt.ae2.crafting.LoopCraftingPlan;
import com.moakiee.thunderbolt.ae2.timewheel.TimeWheelCraftingCpuPool;

@Mixin(value = CraftingService.class, remap = false)
public abstract class ExtendedCraftingCpuServiceMixin {
    @Unique
    private static final Comparator<ExtendedCraftingCpuCluster> THUNDERBOLT_EXTENDED_CPU_FAST_FIRST = Comparator
            .comparingInt(ExtendedCraftingCpuCluster::getCoProcessors)
            .reversed()
            .thenComparingLong(ExtendedCraftingCpuCluster::getAvailableStorage);

    @Unique
    private final Set<ExtendedCraftingCpuCluster> thunderbolt$extendedCpuClusters = new HashSet<>();

    @Unique
    private long thunderbolt$lastExtendedCraftingLogicChangeTick = Long.MIN_VALUE;

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
    private long lastProcessedCraftingLogicChangeTick;

    @Shadow
    public abstract void addLink(CraftingLink link);

    @Inject(
            method = "beginCraftingCalculation",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/ExecutorService;submit(Ljava/util/concurrent/Callable;)Ljava/util/concurrent/Future;",
                    shift = At.Shift.BEFORE))
    private void thunderbolt$enableFastPlanningForTimeWheelCpu(Level level,
                                                               ICraftingSimulationRequester simRequester,
                                                               AEKey what,
                                                               long amount,
                                                               CalculationStrategy strategy,
                                                               CallbackInfoReturnable<Future<ICraftingPlan>> cir,
                                                               @Local CraftingCalculation job) {
        boolean enabled = this.thunderbolt$extendedCpuClusters.stream()
                .anyMatch(cluster -> cluster instanceof TimeWheelCraftingCpuPool && cluster.isActive());
        ((FastCraftingControl) job).ae2lt$setFastPlanningEnabled(enabled);
    }

    @Inject(
            method = "onServerEndTick",
            at = @At(
                    value = "FIELD",
                    target = "Lappeng/me/service/CraftingService;lastProcessedCraftingLogicChangeTick:J",
                    opcode = Opcodes.GETFIELD,
                    ordinal = 0))
    private void thunderbolt$tickExtendedCpuClusters(CallbackInfo ci) {
        long latest = Long.MIN_VALUE;
        for (var cluster : this.thunderbolt$extendedCpuClusters) {
            latest = Math.max(latest, cluster.tickCraftingLogic(
                    this.energyGrid, (CraftingService) (Object) this));
            if (cluster.consumeCpuListChanged()) {
                this.updateList = true;
            }
        }
        if (latest != this.thunderbolt$lastExtendedCraftingLogicChangeTick) {
            this.thunderbolt$lastExtendedCraftingLogicChangeTick = latest;
            // Force AE2's normal waiting-key refresh without capturing its latestChange local.
            this.lastProcessedCraftingLogicChangeTick = -1L;
        }
    }

    @Inject(
            method = "onServerEndTick",
            at = @At(
                    value = "FIELD",
                    target = "Lappeng/me/service/CraftingService;interests:Lcom/google/common/collect/Multimap;",
                    opcode = Opcodes.GETFIELD,
                    ordinal = 0))
    private void thunderbolt$addExtendedWaitingKeys(CallbackInfo ci) {
        for (var cluster : this.thunderbolt$extendedCpuClusters) {
            cluster.addWaitingKeys(this.currentlyCrafting);
        }
    }

    @Inject(method = "removeNode", at = @At("TAIL"))
    private void thunderbolt$onRemoveNode(IGridNode gridNode, CallbackInfo ci) {
        if (gridNode.getOwner() instanceof ExtendedCraftingCpuClusterHost host) {
            this.thunderbolt$extendedCpuClusters.remove(host.getExtendedCraftingCpuCluster());
            this.updateList = true;
        }
    }

    @Inject(method = "addNode", at = @At("TAIL"))
    private void thunderbolt$onAddNode(IGridNode gridNode, CompoundTag savedData, CallbackInfo ci) {
        if (gridNode.getOwner() instanceof ExtendedCraftingCpuClusterHost host) {
            thunderbolt$addExtendedCpuCluster(host.getExtendedCraftingCpuCluster());
            this.updateList = true;
        }
    }

    @Inject(method = "updateCPUClusters", at = @At("TAIL"))
    private void thunderbolt$updateExtendedCpuClusters(CallbackInfo ci) {
        this.thunderbolt$extendedCpuClusters.clear();
        for (var machineClass : this.grid.getMachineClasses()) {
            for (var node : this.grid.getMachineNodes(machineClass)) {
                if (node.getOwner() instanceof ExtendedCraftingCpuClusterHost host) {
                    thunderbolt$addExtendedCpuCluster(host.getExtendedCraftingCpuCluster());
                }
            }
        }
    }

    @Inject(
            method = "insertIntoCpus",
            at = @At("RETURN"),
            cancellable = true,
            order = 1500)
    private void thunderbolt$insertIntoExtendedCpuClusters(
            AEKey what,
            long amount,
            Actionable type,
            CallbackInfoReturnable<Long> cir) {
        long inserted = cir.getReturnValue();
        for (var cluster : this.thunderbolt$extendedCpuClusters) {
            if (inserted >= amount) {
                break;
            }
            inserted += cluster.insert(what, amount - inserted, type);
        }
        cir.setReturnValue(inserted);
    }

    @Inject(method = "submitJob", at = @At("HEAD"), cancellable = true)
    private void thunderbolt$submitToExplicitExtendedCpuCluster(ICraftingPlan job,
                                                                 ICraftingRequester requestingMachine,
                                                                 ICraftingCPU target,
                                                                 boolean prioritizePower,
                                                                 IActionSource src,
                                                                 CallbackInfoReturnable<ICraftingSubmitResult> cir) {
        if (job.simulation()) {
            return;
        }

        if (target instanceof ExtendedCraftingCpuCluster cluster) {
            if (!cluster.canAcceptPlan(job)) {
                cir.setReturnValue(CraftingSubmitResult.CPU_OFFLINE);
            } else {
                cir.setReturnValue(cluster.submitJob(this.grid, job, src, requestingMachine));
            }
            return;
        }

        if (target != null) {
            for (var cluster : this.thunderbolt$extendedCpuClusters) {
                if (cluster.containsCpu(target)) {
                    cir.setReturnValue(CraftingSubmitResult.CPU_BUSY);
                    return;
                }
            }
        }

        if (thunderbolt$isPlanBound(job)) {
            if (target != null) {
                cir.setReturnValue(CraftingSubmitResult.CPU_OFFLINE);
                return;
            }
            var cluster = thunderbolt$findSuitableExtendedCpuCluster(
                    job, src, new MutableObject<>());
            cir.setReturnValue(cluster != null
                    ? cluster.submitJob(this.grid, job, src, requestingMachine)
                    : CraftingSubmitResult.CPU_OFFLINE);
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
    private void thunderbolt$submitToAutomaticExtendedCpuCluster(
            ICraftingPlan job,
            ICraftingRequester requestingMachine,
            ICraftingCPU target,
            boolean prioritizePower,
            IActionSource src,
            CallbackInfoReturnable<ICraftingSubmitResult> cir,
            @Local MutableObject<UnsuitableCpus> unsuitableCpusResult) {
        var cluster = thunderbolt$findSuitableExtendedCpuCluster(
                job, src, unsuitableCpusResult);
        if (cluster != null) {
            cir.setReturnValue(cluster.submitJob(this.grid, job, src, requestingMachine));
        }
    }

    @Inject(method = "getCpus", at = @At("RETURN"), cancellable = true, order = 1500)
    private void thunderbolt$getExtendedCpus(CallbackInfoReturnable<ImmutableSet<ICraftingCPU>> cir) {
        var cpus = ImmutableSet.<ICraftingCPU>builder().addAll(cir.getReturnValue());
        for (var cluster : this.thunderbolt$extendedCpuClusters) {
            if (!cluster.isActive()) {
                continue;
            }
            for (var cpu : cluster.getActiveCpus()) {
                cpus.add(cpu);
            }
            if (cluster.getAvailableStorage() > 0L) {
                cpus.add(cluster);
            }
        }
        cir.setReturnValue(cpus.build());
    }

    @Inject(method = "getRequestedAmount", at = @At("RETURN"), cancellable = true, order = 1500)
    private void thunderbolt$getExtendedRequestedAmount(AEKey what, CallbackInfoReturnable<Long> cir) {
        long requested = cir.getReturnValue();
        for (var cluster : this.thunderbolt$extendedCpuClusters) {
            long addition = cluster.getRequestedAmount(what);
            requested = requested >= Long.MAX_VALUE - addition ? Long.MAX_VALUE : requested + addition;
        }
        cir.setReturnValue(requested);
    }

    @Inject(method = "hasCpu", at = @At("HEAD"), cancellable = true)
    private void thunderbolt$hasExtendedCpu(ICraftingCPU cpu, CallbackInfoReturnable<Boolean> cir) {
        for (var cluster : this.thunderbolt$extendedCpuClusters) {
            if (cluster.containsCpu(cpu)) {
                cir.setReturnValue(true);
                return;
            }
        }
    }

    @Unique
    private void thunderbolt$addExtendedCpuCluster(ExtendedCraftingCpuCluster cluster) {
        if (cluster == null || !this.thunderbolt$extendedCpuClusters.add(cluster)) {
            return;
        }
        cluster.prepareForCraftingService();
        cluster.restoreCraftingLinks(this::addLink);
    }

    @Unique
    @Nullable
    private ExtendedCraftingCpuCluster thunderbolt$findSuitableExtendedCpuCluster(
            ICraftingPlan job,
            IActionSource src,
            MutableObject<UnsuitableCpus> unsuitableCpusResult) {
        var valid = new ArrayList<ExtendedCraftingCpuCluster>(
                this.thunderbolt$extendedCpuClusters.size());
        int offline = 0;
        int tooSmall = 0;
        int excluded = 0;
        for (var cluster : this.thunderbolt$extendedCpuClusters) {
            if (!cluster.isActive()) {
                offline++;
                continue;
            }
            if (cluster.getAvailableStorage() < job.bytes()) {
                tooSmall++;
                continue;
            }
            if (!cluster.canAcceptPlan(job)) {
                excluded++;
                continue;
            }
            if (!cluster.canBeAutoSelectedFor(src)) {
                excluded++;
                continue;
            }
            valid.add(cluster);
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
            return THUNDERBOLT_EXTENDED_CPU_FAST_FIRST.compare(a, b);
        });
        return valid.getFirst();
    }

    @Unique
    private static boolean thunderbolt$isPlanBound(ICraftingPlan job) {
        return job instanceof LoopCraftingPlan;
    }

    @Unique
    private static int saturatingAdd(int left, int right) {
        return left >= Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right;
    }
}
