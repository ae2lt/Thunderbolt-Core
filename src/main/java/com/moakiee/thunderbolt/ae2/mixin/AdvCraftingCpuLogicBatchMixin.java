package com.moakiee.thunderbolt.ae2.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.hooks.ticking.TickHandler;
import appeng.me.service.CraftingService;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moakiee.thunderbolt.ae2.batch.AaeBatchJobView;
import com.moakiee.thunderbolt.ae2.batch.BatchCpuAccounting;
import com.moakiee.thunderbolt.ae2.batch.BatchExecutor;
import com.moakiee.thunderbolt.ae2.batch.BatchProviderFilterIterable;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.level.Level;
import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU;
import net.pedroksl.advanced_ae.common.logic.AdvCraftingCPULogic;
import net.pedroksl.advanced_ae.common.logic.ExecutingCraftingJob;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(
   value = {AdvCraftingCPULogic.class},
   remap = false
)
public abstract class AdvCraftingCpuLogicBatchMixin {
   @Shadow
   private ExecutingCraftingJob job;
   @Shadow
   @Final
   private ListCraftingInventory inventory;
   @Shadow
   @Final
   AdvCraftingCPU cpu;
   @Unique
   @Nullable
   private Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> ae2lt$batchedByTask;
   @Unique
   private long ae2lt$batchTick;
   @Unique
   private boolean ae2lt$batchExhaustedThisTick;

   @WrapOperation(
      method = {"tickCraftingLogic"},
      at = {@At(
         value = "INVOKE",
         target = "Lnet/pedroksl/advanced_ae/common/logic/AdvCraftingCPULogic;executeCrafting(ILappeng/me/service/CraftingService;Lappeng/api/networking/energy/IEnergyService;Lnet/minecraft/world/level/Level;)I"
      )}
   )
   private int ae2lt$wrapExecuteCrafting(
      AdvCraftingCPULogic self, int remainingOps, CraftingService craftingService, IEnergyService energyService, Level level, Operation<Integer> original
   ) {
      long now = TickHandler.instance().getCurrentTick();
      Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> batchedByTask = this.ae2lt$getBatchedByTask();
      if (now != this.ae2lt$batchTick) {
         this.ae2lt$batchTick = now;
         batchedByTask.clear();
         this.ae2lt$batchExhaustedThisTick = false;
      }

      if (this.job != null && !this.ae2lt$batchExhaustedThisTick) {
         BatchExecutor.BatchRunResult batchResult = BatchExecutor.runBatchOnly(
            remainingOps,
            BatchCpuAccounting.Mode.LINEAR,
            craftingService,
            energyService,
            level,
            new AaeBatchJobView(this.job),
            this.inventory,
            batchedByTask,
            this.cpu::markDirty
         );
         if (batchResult.dispatchedCopies() > 0L) {
            return batchResult.consumedCpuOps();
         } else {
            this.ae2lt$batchExhaustedThisTick = true;
            return (Integer)original.call(new Object[]{self, remainingOps, craftingService, energyService, level});
         }
      } else {
         return (Integer)original.call(new Object[]{self, remainingOps, craftingService, energyService, level});
      }
   }

   @WrapOperation(
      method = {"executeCrafting"},
      at = {@At(
         value = "INVOKE",
         target = "Lappeng/me/service/CraftingService;getProviders(Lappeng/api/crafting/IPatternDetails;)Ljava/lang/Iterable;"
      )}
   )
   private Iterable<ICraftingProvider> ae2lt$filterBatched(
      CraftingService craftingService, IPatternDetails details, Operation<Iterable<ICraftingProvider>> original
   ) {
      Iterable<ICraftingProvider> raw = (Iterable<ICraftingProvider>)original.call(new Object[]{craftingService, details});
      Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> batchedByTask = this.ae2lt$getBatchedByTask();
      if (batchedByTask.isEmpty()) {
         return raw;
      } else {
         IdentityHashMap<ICraftingProvider, Boolean> perTask = batchedByTask.get(details);
         return (Iterable<ICraftingProvider>)(perTask != null && !perTask.isEmpty() ? new BatchProviderFilterIterable(raw, perTask) : raw);
      }
   }

   @Unique
   private Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> ae2lt$getBatchedByTask() {
      if (this.ae2lt$batchedByTask == null) {
         this.ae2lt$batchedByTask = new HashMap<>();
      }

      return this.ae2lt$batchedByTask;
   }
}
