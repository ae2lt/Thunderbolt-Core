package com.moakiee.thunderbolt.ae2.mixin;

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
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;
import com.moakiee.thunderbolt.ae2.crafting.CraftingCpuSelectionOrder;
import com.moakiee.thunderbolt.ae2.crafting.DynamicCraftingCpuClusterIndex;
import com.moakiee.thunderbolt.ae2.crafting.ExtendedCraftingCpuCluster;
import com.moakiee.thunderbolt.ae2.crafting.ExtendedCraftingCpuClusterProvider;
import com.moakiee.thunderbolt.ae2.crafting.FastCraftingControl;
import com.moakiee.thunderbolt.ae2.crafting.LoopCraftingPlan;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.Future;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.apache.commons.lang3.mutable.MutableObject;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Crazy AE2の互換Mixinが上書きしたCraftingServiceへ、Thunderboltの注入を適用する。
// 1300はCrazy AE2互換Mixinの優先度1200を上回るための固定値。
@Mixin(
   value = {CraftingService.class},
   priority = 1300,
   remap = false
)
public abstract class ExtendedCraftingCpuServiceMixin {
   @Unique
   @Nullable
   private DynamicCraftingCpuClusterIndex<IGridNode, ExtendedCraftingCpuCluster> thunderbolt$extendedCpuClusterIndex;
   @Unique
   private long thunderbolt$lastExtendedCraftingLogicChangeTick;
   @Unique
   private boolean thunderbolt$lastExtendedCraftingLogicChangeTickInitialized;
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
   public abstract void addLink(CraftingLink var1);

   @Inject(
      method = {"beginCraftingCalculation"},
      at = {@At(
         value = "INVOKE",
         target = "Ljava/util/concurrent/ExecutorService;submit(Ljava/util/concurrent/Callable;)Ljava/util/concurrent/Future;",
         shift = Shift.BEFORE
      )}
   )
   private void thunderbolt$enableFastPlanningForTimeWheelCpu(
      Level level,
      ICraftingSimulationRequester simRequester,
      AEKey what,
      long amount,
      CalculationStrategy strategy,
      CallbackInfoReturnable<Future<ICraftingPlan>> cir,
      @Local CraftingCalculation job
   ) {
      this.thunderbolt$refreshExtendedCpuClusters();
      boolean enabled = this.thunderbolt$getExtendedCpuClusters().stream().anyMatch(cluster -> cluster.isActive() && cluster.isFastPlanningEnabled());
      ((FastCraftingControl)job).ae2lt$setFastPlanningEnabled(enabled);
   }

   @Inject(
      method = {"onServerEndTick"},
      at = {@At(
         value = "FIELD",
         target = "Lappeng/me/service/CraftingService;lastProcessedCraftingLogicChangeTick:J",
         opcode = 180,
         ordinal = 0
      )}
   )
   private void thunderbolt$tickExtendedCpuClusters(CallbackInfo ci) {
      this.thunderbolt$refreshExtendedCpuClusters();
      long latest = Long.MIN_VALUE;

      for (ExtendedCraftingCpuCluster cluster : this.thunderbolt$getExtendedCpuClusters()) {
         latest = Math.max(latest, cluster.tickCraftingLogic(this.energyGrid, (CraftingService)(Object)this));
         if (cluster.consumeCpuListChanged()) {
            this.updateList = true;
         }
      }

      if (!this.thunderbolt$lastExtendedCraftingLogicChangeTickInitialized || latest != this.thunderbolt$lastExtendedCraftingLogicChangeTick) {
         this.thunderbolt$lastExtendedCraftingLogicChangeTickInitialized = true;
         this.thunderbolt$lastExtendedCraftingLogicChangeTick = latest;
         this.lastProcessedCraftingLogicChangeTick = -1L;
      }
   }

   @Inject(
      method = {"onServerEndTick"},
      at = {@At(
         value = "FIELD",
         target = "Lappeng/me/service/CraftingService;interests:Lcom/google/common/collect/Multimap;",
         opcode = 180,
         ordinal = 0
      )}
   )
   private void thunderbolt$addExtendedWaitingKeys(CallbackInfo ci) {
      for (ExtendedCraftingCpuCluster cluster : this.thunderbolt$getExtendedCpuClusters()) {
         cluster.addWaitingKeys(this.currentlyCrafting);
      }
   }

   @Inject(
      method = {"removeNode"},
      at = {@At("TAIL")}
   )
   private void thunderbolt$onRemoveNode(IGridNode gridNode, CallbackInfo ci) {
      if (this.thunderbolt$getExtendedCpuClusterIndex().removeProvider(gridNode)) {
         this.thunderbolt$refreshExtendedCpuClusters();
      }
   }

   @Inject(
      method = {"addNode"},
      at = {@At("TAIL")}
   )
   private void thunderbolt$onAddNode(IGridNode gridNode, CompoundTag savedData, CallbackInfo ci) {
      if (thunderbolt$getExtendedCpuClusterProvider(gridNode) != null) {
         this.thunderbolt$getExtendedCpuClusterIndex().addProvider(gridNode);
         this.thunderbolt$refreshExtendedCpuClusters();
      }
   }

   @Inject(
      method = {"updateCPUClusters"},
      at = {@At("TAIL")}
   )
   private void thunderbolt$updateExtendedCpuClusters(CallbackInfo ci) {
      ArrayList<IGridNode> providerNodes = new ArrayList<>();

      for (Class<?> machineClass : this.grid.getMachineClasses()) {
         for (IGridNode node : this.grid.getMachineNodes(machineClass)) {
            if (thunderbolt$getExtendedCpuClusterProvider(node) != null) {
               providerNodes.add(node);
            }
         }
      }

      this.thunderbolt$getExtendedCpuClusterIndex().replaceProviders(providerNodes);
      this.thunderbolt$refreshExtendedCpuClusters();
   }

   @Inject(
      method = {"insertIntoCpus"},
      at = {@At(
         value = "RETURN",
         shift = Shift.BY,
         by = -1
      )}
   )
   private void thunderbolt$insertIntoExtendedCpuClusters(
      AEKey what, long amount, Actionable type, CallbackInfoReturnable<Long> cir, @Local(ordinal = 1) LocalLongRef insertedRef
   ) {
      long inserted = insertedRef.get();

      for (ExtendedCraftingCpuCluster cluster : this.thunderbolt$getExtendedCpuClusters()) {
         if (inserted >= amount) {
            break;
         }

         inserted += cluster.insert(what, amount - inserted, type);
      }

      insertedRef.set(inserted);
   }

   @Inject(
      method = {"submitJob"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void thunderbolt$submitToExplicitExtendedCpuCluster(
      ICraftingPlan job,
      ICraftingRequester requestingMachine,
      ICraftingCPU target,
      boolean prioritizePower,
      IActionSource src,
      CallbackInfoReturnable<ICraftingSubmitResult> cir
   ) {
      if (!job.simulation()) {
         if (target instanceof ExtendedCraftingCpuCluster cluster) {
            if (!cluster.canAcceptPlan(job)) {
               cir.setReturnValue(CraftingSubmitResult.CPU_OFFLINE);
            } else {
               cir.setReturnValue(cluster.submitJob(this.grid, job, src, requestingMachine));
            }
         } else {
            if (target != null) {
               for (ExtendedCraftingCpuCluster cluster : this.thunderbolt$getExtendedCpuClusters()) {
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

               ExtendedCraftingCpuCluster clusterx = this.thunderbolt$findSuitableExtendedCpuCluster(job, prioritizePower, src, new MutableObject());
               cir.setReturnValue(clusterx != null ? clusterx.submitJob(this.grid, job, src, requestingMachine) : CraftingSubmitResult.CPU_OFFLINE);
            }
         }
      }
   }

   @Inject(
      method = {"submitJob"},
      at = {@At(
         value = "INVOKE_ASSIGN",
         target = "Lappeng/me/service/CraftingService;findSuitableCraftingCPU(Lappeng/api/networking/crafting/ICraftingPlan;ZLappeng/api/networking/security/IActionSource;Lorg/apache/commons/lang3/mutable/MutableObject;)Lappeng/me/cluster/implementations/CraftingCPUCluster;"
      )},
      cancellable = true
   )
   private void thunderbolt$submitToAutomaticExtendedCpuCluster(
      ICraftingPlan job,
      ICraftingRequester requestingMachine,
      ICraftingCPU target,
      boolean prioritizePower,
      IActionSource src,
      CallbackInfoReturnable<ICraftingSubmitResult> cir,
      @Local CraftingCPUCluster cpuCluster,
      @Local MutableObject<UnsuitableCpus> unsuitableCpusResult
   ) {
      if (!thunderbolt$isPlanBound(job)) {
         ExtendedCraftingCpuCluster extendedCluster = this.thunderbolt$findSuitableExtendedCpuCluster(job, prioritizePower, src, unsuitableCpusResult);
         if (extendedCluster != null) {
            if (cpuCluster == null
               || CraftingCpuSelectionOrder.compare(
                     extendedCluster.isPreferredFor(src),
                     extendedCluster.getCoProcessors(),
                     extendedCluster.getAvailableStorage(),
                     cpuCluster.isPreferredFor(src),
                     cpuCluster.getCoProcessors(),
                     cpuCluster.getAvailableStorage(),
                     prioritizePower
                  )
                  < 0) {
               cir.setReturnValue(extendedCluster.submitJob(this.grid, job, src, requestingMachine));
            }
         }
      }
   }

   @Inject(
      method = {"getCpus"},
      at = {@At(
         value = "INVOKE",
         target = "Lcom/google/common/collect/ImmutableSet$Builder;build()Lcom/google/common/collect/ImmutableSet;"
      )}
   )
   private void thunderbolt$getExtendedCpus(CallbackInfoReturnable<ImmutableSet<ICraftingCPU>> cir, @Local(ordinal = 0) Builder<ICraftingCPU> cpus) {
      this.thunderbolt$addExtendedCpus(cpus);
   }

   @Inject(
      method = {"getCpus"},
      at = {@At("HEAD")},
      remap = false
   )
   private void thunderbolt$appendExtendedCpusAfterEarlyReturn(
      CallbackInfoReturnable<ImmutableSet<ICraftingCPU>> cir
   ) {
      ImmutableSet<ICraftingCPU> existing = cir.getReturnValue();
      // 先行Mixinが返却値を作っていない通常経路では、AE2本体のgetCpus処理へ任せる。
      if (existing == null) {
         return;
      }

      // Crazy AE2がCPU優先度処理で先に返した集合へ、Thunderboltの拡張CPUを重複なく追加する。
      Builder<ICraftingCPU> cpus = ImmutableSet.builder();
      cpus.addAll(existing);
      this.thunderbolt$addExtendedCpus(cpus);
      cir.setReturnValue(cpus.build());
   }

   @Unique
   private void thunderbolt$addExtendedCpus(Builder<ICraftingCPU> cpus) {
      // 接続中の各拡張CPUクラスタを走査し、稼働中のCPUだけをAE2のCPU集合へ追加する。
      for (ExtendedCraftingCpuCluster cluster : this.thunderbolt$getExtendedCpuClusters()) {
         // 構造が未形成または停止中のクラスタは、CPUとして公開しない。
         if (!cluster.isActive()) {
            continue;
         }

         for (ICraftingCPU cpu : cluster.getActiveCpus()) {
            cpus.add(cpu);
         }

         // 空きクラフトストレージがある場合だけ、クラスタ自体を選択対象へ追加する。
         if (cluster.getAvailableStorage() > 0L) {
            cpus.add(cluster);
         }
      }
   }

   @Inject(
      method = {"getRequestedAmount"},
      at = {@At("RETURN")},
      cancellable = true
   )
   private void thunderbolt$getExtendedRequestedAmount(AEKey what, CallbackInfoReturnable<Long> cir) {
      long requested = (Long)cir.getReturnValue();

      for (ExtendedCraftingCpuCluster cluster : this.thunderbolt$getExtendedCpuClusters()) {
         long addition = cluster.getRequestedAmount(what);
         requested = requested >= Long.MAX_VALUE - addition ? Long.MAX_VALUE : requested + addition;
      }

      cir.setReturnValue(requested);
   }

   @Inject(
      method = {"hasCpu"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void thunderbolt$hasExtendedCpu(ICraftingCPU cpu, CallbackInfoReturnable<Boolean> cir) {
      for (ExtendedCraftingCpuCluster cluster : this.thunderbolt$getExtendedCpuClusters()) {
         if (cluster.containsCpu(cpu)) {
            cir.setReturnValue(true);
            return;
         }
      }
   }

   @Unique
   private void thunderbolt$addExtendedCpuCluster(ExtendedCraftingCpuCluster cluster) {
      cluster.prepareForCraftingService();
      cluster.restoreCraftingLinks(this::addLink);
   }

   @Unique
   private void thunderbolt$refreshExtendedCpuClusters() {
      boolean changed = this.thunderbolt$getExtendedCpuClusterIndex()
         .refresh(ExtendedCraftingCpuServiceMixin::thunderbolt$resolveExtendedCpuCluster, this::thunderbolt$addExtendedCpuCluster);
      if (changed) {
         this.updateList = true;
      }
   }

   @Unique
   private DynamicCraftingCpuClusterIndex<IGridNode, ExtendedCraftingCpuCluster> thunderbolt$getExtendedCpuClusterIndex() {
      if (this.thunderbolt$extendedCpuClusterIndex == null) {
         this.thunderbolt$extendedCpuClusterIndex = new DynamicCraftingCpuClusterIndex<>();
      }

      return this.thunderbolt$extendedCpuClusterIndex;
   }

   @Unique
   private Set<ExtendedCraftingCpuCluster> thunderbolt$getExtendedCpuClusters() {
      return this.thunderbolt$getExtendedCpuClusterIndex().clusters();
   }

   @Unique
   @Nullable
   private static ExtendedCraftingCpuClusterProvider thunderbolt$getExtendedCpuClusterProvider(IGridNode node) {
      ExtendedCraftingCpuClusterProvider service = (ExtendedCraftingCpuClusterProvider)node.getService(ExtendedCraftingCpuClusterProvider.class);
      if (service != null) {
         return service;
      } else {
         return node.getOwner() instanceof ExtendedCraftingCpuClusterProvider provider ? provider : null;
      }
   }

   @Unique
   @Nullable
   private static ExtendedCraftingCpuCluster thunderbolt$resolveExtendedCpuCluster(IGridNode node) {
      ExtendedCraftingCpuClusterProvider provider = thunderbolt$getExtendedCpuClusterProvider(node);
      return provider != null ? provider.getExtendedCraftingCpuCluster() : null;
   }

   @Unique
   @Nullable
   private ExtendedCraftingCpuCluster thunderbolt$findSuitableExtendedCpuCluster(
      ICraftingPlan job, boolean prioritizePower, IActionSource src, MutableObject<UnsuitableCpus> unsuitableCpusResult
   ) {
      Set<ExtendedCraftingCpuCluster> clusters = this.thunderbolt$getExtendedCpuClusters();
      ArrayList<ExtendedCraftingCpuCluster> valid = new ArrayList<>(clusters.size());
      int offline = 0;
      int busy = 0;
      int tooSmall = 0;
      int excluded = 0;

      for (ExtendedCraftingCpuCluster cluster : clusters) {
         if (!cluster.isActive()) {
            offline++;
         } else if (cluster.isBusy()) {
            busy++;
         } else if (cluster.getAvailableStorage() < job.bytes()) {
            tooSmall++;
         } else if (!cluster.canAcceptPlan(job)) {
            excluded++;
         } else if (!cluster.canBeAutoSelectedFor(src)) {
            excluded++;
         } else {
            valid.add(cluster);
         }
      }

      if (!valid.isEmpty()) {
         valid.sort(
            (a, b) -> CraftingCpuSelectionOrder.compare(
                  a.isPreferredFor(src),
                  a.getCoProcessors(),
                  a.getAvailableStorage(),
                  b.isPreferredFor(src),
                  b.getCoProcessors(),
                  b.getAvailableStorage(),
                  prioritizePower
               )
         );
         return valid.get(0);
      } else {
         if (offline > 0 || busy > 0 || tooSmall > 0 || excluded > 0) {
            UnsuitableCpus existing = (UnsuitableCpus)unsuitableCpusResult.getValue();
            if (existing == null) {
               unsuitableCpusResult.setValue(new UnsuitableCpus(offline, busy, tooSmall, excluded));
            } else {
               unsuitableCpusResult.setValue(
                  new UnsuitableCpus(
                     saturatingAdd(existing.offline(), offline),
                     saturatingAdd(existing.busy(), busy),
                     saturatingAdd(existing.tooSmall(), tooSmall),
                     saturatingAdd(existing.excluded(), excluded)
                  )
               );
            }
         }

         return null;
      }
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
