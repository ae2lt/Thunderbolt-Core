package com.moakiee.thunderbolt.ae2.mixin;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moakiee.thunderbolt.ae2.api.crafting.CraftingPatternDelegates;
import com.moakiee.thunderbolt.ae2.crafting.FinalOutputAccounting;
import com.moakiee.thunderbolt.ae2.overload.cpu.InsertContext;
import com.moakiee.thunderbolt.ae2.overload.cpu.OverloadClaimResult;
import com.moakiee.thunderbolt.ae2.overload.cpu.OverloadCpuInsertSupport;
import com.moakiee.thunderbolt.ae2.overload.cpu.OverloadCpuStateManager;
import com.moakiee.thunderbolt.ae2.overload.cpu.OverloadPatternReference;
import com.moakiee.thunderbolt.ae2.overload.cpu.PendingOverloadClaim;
import com.moakiee.thunderbolt.ae2.overload.pattern.OverloadedProviderOnlyPatternDetails;
import net.minecraft.nbt.CompoundTag;
import java.util.Arrays;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftingCpuLogic.class, remap = false)
public abstract class CraftingCpuLogicMixin {
   @Shadow(
      remap = false
   )
   CraftingCPUCluster cluster;
   @Unique
   @Nullable
   private InsertContext ae2lt$insertContext;

   @Inject(
      method = {"insert"},
      at = {@At("HEAD")}
   )
   private void ae2lt$beginInsertContext(AEKey what, long amount, Actionable type, CallbackInfoReturnable<Long> cir) {
      this.ae2lt$insertContext = new InsertContext(what, amount, type);
   }

   @WrapOperation(
      method = {"insert"},
      at = {@At(
         value = "INVOKE",
         target = "Lappeng/crafting/inv/ListCraftingInventory;extract(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;)J",
         ordinal = 0
      )},
      remap = false
   )
   private long ae2lt$captureStrictWaitingMatch(ListCraftingInventory waitingFor, AEKey what, long amount, Actionable mode, Operation<Long> original) {
      long strictMatched = (Long)original.call(new Object[]{waitingFor, what, amount, mode});
      if (mode == Actionable.SIMULATE && this.ae2lt$insertContext != null) {
         strictMatched = OverloadCpuInsertSupport.nativeStrictMatch((CraftingCpuLogic)(Object)this, what, strictMatched, waitingFor.list.get(what));
         this.ae2lt$insertContext.setStrictMatched(strictMatched);
      }

      return strictMatched;
   }

   @Inject(
      method = {"insert"},
      at = {@At("RETURN")},
      cancellable = true
   )
   private void ae2lt$claimOverloadRemainder(AEKey what, long amount, Actionable type, CallbackInfoReturnable<Long> cir) {
      InsertContext ctx = this.ae2lt$insertContext;
      this.ae2lt$insertContext = null;
      if (ctx != null && what != null && ctx.getRequestedAmount() > 0L) {
         long remainder = Math.max(0L, ctx.getRequestedAmount() - ctx.getStrictMatched());
         if (remainder > 0L) {
            CraftingCpuLogic logic = (CraftingCpuLogic)(Object)this;
            if (OverloadCpuStateManager.INSTANCE.hasAnyPending(logic)) {
               OverloadClaimResult preview = OverloadCpuStateManager.INSTANCE.claim(logic, what, remainder, Actionable.SIMULATE);
               if (preview.claimedAnything()) {
                  ExecutingCraftingJob job = ((CraftingCpuLogicAccessor)logic).getJob();
                  if (job != null) {
                     ExecutingCraftingJobAccessor jobAccessor = (ExecutingCraftingJobAccessor)job;
                     CraftingLink link = ((ExecutingCraftingJobAccessor)job).getLink();
                     long requesterLimit = Math.min(preview.claimedForRequester(), Math.max(0L, jobAccessor.getRemainingAmount()));
                     long requesterAccepted = 0L;
                     if (requesterLimit > 0L) {
                        requesterAccepted = link != null ? link.insert(what, requesterLimit, type) : 0L;
                     }

                     long requesterCompleted = FinalOutputAccounting.completedAmount(true, requesterLimit, requesterAccepted);
                     OverloadClaimResult claims = preview.partitionRequester(requesterLimit, requesterCompleted);
                     if (type == Actionable.MODULATE) {
                        claims = OverloadCpuStateManager.INSTANCE.commitPreview(logic, claims);
                     }

                     if (claims.claimedAnything()) {
                        long supplementalReturn = 0L;
                        if (type == Actionable.MODULATE) {
                           this.ae2lt$deductClaimedWaitingFor(job, claims);
                           long inventoryAccepted = this.ae2lt$applyInventoryClaims(what, claims);
                           this.ae2lt$applyRequesterClaims(what, claims);
                           supplementalReturn = FinalOutputAccounting.physicallyAcceptedAmount(
                              inventoryAccepted, claims.claimedForRequester(), requesterAccepted
                           );
                           this.cluster.markDirty();
                        } else {
                           supplementalReturn = FinalOutputAccounting.physicallyAcceptedAmount(
                              claims.claimedForInventory(), claims.claimedForRequester(), requesterAccepted
                           );
                        }

                        cir.setReturnValue((Long)cir.getReturnValue() + supplementalReturn);
                     }
                  }
               }
            }
         }
      }
   }

   @WrapOperation(
      method = {"executeCrafting"},
      at = {@At(
         value = "INVOKE",
         target = "Lappeng/api/networking/crafting/ICraftingProvider;pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z"
      )},
      remap = false
   )
   private boolean ae2lt$registerOverloadExpectedOutputs(
      ICraftingProvider provider, IPatternDetails details, KeyCounter[] inputHolder, Operation<Boolean> original
   ) {
      CraftingCpuLogic logic = (CraftingCpuLogic)(Object)this;
      OverloadedProviderOnlyPatternDetails overloadDetails = CraftingPatternDelegates.forProviderLookup(details) instanceof OverloadedProviderOnlyPatternDetails overload
         ? overload
         : null;
      if (overloadDetails == null) {
         return OverloadCpuInsertSupport.hasPendingCollisionWithOrdinaryPattern(logic, details)
            ? false
            : (Boolean)original.call(new Object[]{provider, details, inputHolder});
      } else {
         ExecutingCraftingJob activeJob = ((CraftingCpuLogicAccessor)logic).getJob();
         if (activeJob != null
            && !OverloadCpuInsertSupport.hasStrictCollisionWithOverloadPattern(
               logic, details, overloadDetails, ((ExecutingCraftingJobAccessor)activeJob).getWaitingFor().list
            )) {
            OverloadPatternReference patternReference = new OverloadPatternReference(
               overloadDetails.overloadPatternIdentity(), overloadDetails.overloadPatternDetailsView().sourcePattern()
            );
            if (OverloadCpuStateManager.INSTANCE.hasAmbiguousOutputRegistration(logic, patternReference, overloadDetails.overloadPatternDetailsView())) {
               return false;
            } else {
               boolean pushed = (Boolean)original.call(new Object[]{provider, details, inputHolder});
               if (pushed) {
                  ExecutingCraftingJob job = ((CraftingCpuLogicAccessor)logic).getJob();
                  GenericStack finalOutput = job != null ? ((ExecutingCraftingJobAccessor)job).getFinalOutput() : null;
                  AEKey finalOutputKey = finalOutput != null ? finalOutput.what() : null;
                  OverloadCpuStateManager.INSTANCE
                     .registerExpectedOutputs(logic, patternReference, overloadDetails.overloadPatternDetailsView(), Arrays.asList(details.getOutputs()), finalOutputKey, 1L);
               }

               return pushed;
            }
         } else {
            return false;
         }
      }
   }

   @Inject(
      method = {"writeToNBT"},
      at = {@At("RETURN")}
   )
   private void ae2lt$writeOverloadState(CompoundTag data, CallbackInfo ci) {
      CraftingCpuLogic logic = (CraftingCpuLogic)(Object)this;
      CompoundTag overloadStateTag = OverloadCpuStateManager.INSTANCE.writeToTag(logic);
      if (overloadStateTag != null) {
         data.put("ae2ltOverloadState", overloadStateTag);
      } else {
         data.remove("ae2ltOverloadState");
      }
   }

   @Inject(
      method = {"readFromNBT"},
      at = {@At("RETURN")}
   )
   private void ae2lt$readOverloadState(CompoundTag data, CallbackInfo ci) {
      CraftingCpuLogic logic = (CraftingCpuLogic)(Object)this;
      OverloadCpuStateManager.INSTANCE.clear(logic);
      ExecutingCraftingJob job = ((CraftingCpuLogicAccessor)logic).getJob();
      if (job != null && data.contains("ae2ltOverloadState", 10)) {
         OverloadCpuStateManager.INSTANCE.readFromTag(logic, data.getCompound("ae2ltOverloadState"));
      }
   }

   @Inject(
      method = {"finishJob"},
      at = {@At("HEAD")}
   )
   private void ae2lt$clearOverloadState(boolean success, CallbackInfo ci) {
      OverloadCpuStateManager.INSTANCE.clear((CraftingCpuLogic)(Object)this);
   }

   @Unique
   private long ae2lt$applyInventoryClaims(AEKey incoming, OverloadClaimResult claims) {
      long claimed = claims.claimedForInventory();
      if (claimed <= 0L) {
         return 0L;
      } else {
         CraftingCpuLogic logic = (CraftingCpuLogic)(Object)this;
         ExecutingCraftingJob job = ((CraftingCpuLogicAccessor)logic).getJob();
         if (job == null) {
            return 0L;
         } else {
            ExecutingCraftingJobAccessor jobAccessor = (ExecutingCraftingJobAccessor)job;
            ((ElapsedTimeTrackerAccessor)jobAccessor.getTimeTracker()).invokeDecrementItems(claimed, incoming.getType());
            logic.getInventory().insert(incoming, claimed, Actionable.MODULATE);
            return claimed;
         }
      }
   }

   @Unique
   private void ae2lt$applyRequesterClaims(AEKey incoming, OverloadClaimResult claims) {
      long claimed = claims.claimedForRequester();
      if (claimed > 0L) {
         CraftingCpuLogic logic = (CraftingCpuLogic)(Object)this;
         CraftingCpuLogicAccessor logicAccessor = (CraftingCpuLogicAccessor)logic;
         ExecutingCraftingJob job = logicAccessor.getJob();
         if (job != null) {
            ExecutingCraftingJobAccessor jobAccessor = (ExecutingCraftingJobAccessor)job;
            ((ElapsedTimeTrackerAccessor)jobAccessor.getTimeTracker()).invokeDecrementItems(claimed, incoming.getType());
            logicAccessor.invokePostChange(incoming);
            long remaining = Math.max(0L, jobAccessor.getRemainingAmount() - claimed);
            jobAccessor.setRemainingAmount(remaining);
            if (remaining <= 0L) {
               logicAccessor.invokeFinishJob(true);
               this.cluster.updateOutput(null);
            } else {
               GenericStack finalOutput = jobAccessor.getFinalOutput();
               if (finalOutput != null) {
                  this.cluster.updateOutput(new GenericStack(finalOutput.what(), remaining));
               }
            }
         }
      }
   }

   @Unique
   private void ae2lt$deductClaimedWaitingFor(ExecutingCraftingJob job, OverloadClaimResult claims) {
      ListCraftingInventory waitingFor = ((ExecutingCraftingJobAccessor)job).getWaitingFor();

      for (PendingOverloadClaim claim : claims.claims()) {
         waitingFor.extract(claim.exactExpectedKey(), claim.claimedAmount(), Actionable.MODULATE);
      }
   }
}
