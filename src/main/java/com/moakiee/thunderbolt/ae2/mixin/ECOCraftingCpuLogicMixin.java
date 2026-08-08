package com.moakiee.thunderbolt.ae2.mixin;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.inv.ListCraftingInventory;
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
import com.moakiee.thunderbolt.ae2.util.MixinReflectionSupport;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.Arrays;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(
   targets = {"cn.dancingsnow.neoecoae.api.me.ECOCraftingCPULogic"},
   remap = false
)
public abstract class ECOCraftingCpuLogicMixin {
   @Unique
   @Nullable
   private static final Class<?> AE2LT_ECO_LOGIC_CLASS = MixinReflectionSupport.findClassSafe("cn.dancingsnow.neoecoae.api.me.ECOCraftingCPULogic");
   @Unique
   @Nullable
   private static final Class<?> AE2LT_ECO_JOB_CLASS = MixinReflectionSupport.findClassSafe("cn.dancingsnow.neoecoae.api.me.ExecutingCraftingJob");
   @Unique
   @Nullable
   private static final Class<?> AE2LT_ECO_ELAPSED_TRACKER_CLASS = MixinReflectionSupport.findClassSafe("cn.dancingsnow.neoecoae.api.me.ElapsedTimeTracker");
   @Unique
   @Nullable
   private static final Field AE2LT_ECO_JOB_FIELD = MixinReflectionSupport.findDeclaredFieldSafe(AE2LT_ECO_LOGIC_CLASS, "job");
   @Unique
   @Nullable
   private static final Field AE2LT_ECO_INVENTORY_FIELD = MixinReflectionSupport.findDeclaredFieldSafe(AE2LT_ECO_LOGIC_CLASS, "inventory");
   @Unique
   @Nullable
   private static final Field AE2LT_ECO_CPU_FIELD = MixinReflectionSupport.findDeclaredFieldSafe(AE2LT_ECO_LOGIC_CLASS, "cpu");
   @Unique
   @Nullable
   private static final Method AE2LT_ECO_FINISH_JOB_METHOD = MixinReflectionSupport.findDeclaredMethodSafe(AE2LT_ECO_LOGIC_CLASS, "finishJob", boolean.class);
   @Unique
   @Nullable
   private static final Method AE2LT_ECO_POST_CHANGE_METHOD = MixinReflectionSupport.findDeclaredMethodSafe(AE2LT_ECO_LOGIC_CLASS, "postChange", AEKey.class);
   @Unique
   @Nullable
   private static final Field AE2LT_ECO_JOB_WAITING_FOR_FIELD = MixinReflectionSupport.findDeclaredFieldSafe(AE2LT_ECO_JOB_CLASS, "waitingFor");
   @Unique
   @Nullable
   private static final Field AE2LT_ECO_JOB_TIME_TRACKER_FIELD = MixinReflectionSupport.findDeclaredFieldSafe(AE2LT_ECO_JOB_CLASS, "timeTracker");
   @Unique
   @Nullable
   private static final Field AE2LT_ECO_JOB_FINAL_OUTPUT_FIELD = MixinReflectionSupport.findDeclaredFieldSafe(AE2LT_ECO_JOB_CLASS, "finalOutput");
   @Unique
   @Nullable
   private static final Field AE2LT_ECO_JOB_REMAINING_AMOUNT_FIELD = MixinReflectionSupport.findDeclaredFieldSafe(AE2LT_ECO_JOB_CLASS, "remainingAmount");
   @Unique
   @Nullable
   private static final Field AE2LT_ECO_JOB_LINK_FIELD = MixinReflectionSupport.findDeclaredFieldSafe(AE2LT_ECO_JOB_CLASS, "link");
   @Unique
   @Nullable
   private static final Method AE2LT_ECO_DECREMENT_ITEMS_METHOD = MixinReflectionSupport.findDeclaredMethodSafe(
      AE2LT_ECO_ELAPSED_TRACKER_CLASS, "decrementItems", long.class, AEKeyType.class
   );
   @Unique
   private static final boolean AE2LT_ECO_AVAILABLE = AE2LT_ECO_LOGIC_CLASS != null
      && AE2LT_ECO_JOB_CLASS != null
      && AE2LT_ECO_ELAPSED_TRACKER_CLASS != null
      && AE2LT_ECO_JOB_FIELD != null
      && AE2LT_ECO_INVENTORY_FIELD != null
      && AE2LT_ECO_CPU_FIELD != null
      && AE2LT_ECO_FINISH_JOB_METHOD != null
      && AE2LT_ECO_POST_CHANGE_METHOD != null
      && AE2LT_ECO_JOB_WAITING_FOR_FIELD != null
      && AE2LT_ECO_JOB_TIME_TRACKER_FIELD != null
      && AE2LT_ECO_JOB_FINAL_OUTPUT_FIELD != null
      && AE2LT_ECO_JOB_REMAINING_AMOUNT_FIELD != null
      && AE2LT_ECO_JOB_LINK_FIELD != null
      && AE2LT_ECO_DECREMENT_ITEMS_METHOD != null;
   @Unique
   @Nullable
   private InsertContext ae2lt$insertContext;

   @Inject(
      method = {"insert"},
      at = {@At("HEAD")}
   )
   private void ae2lt$beginInsertContext(AEKey what, long amount, Actionable type, CallbackInfoReturnable<Long> cir) {
      if (AE2LT_ECO_AVAILABLE) {
         this.ae2lt$insertContext = new InsertContext(what, amount, type);
      }
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
      if (AE2LT_ECO_AVAILABLE && mode == Actionable.SIMULATE && this.ae2lt$insertContext != null) {
         strictMatched = OverloadCpuInsertSupport.nativeStrictMatch(this, what, strictMatched, waitingFor.list.get(what));
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
      if (AE2LT_ECO_AVAILABLE) {
         InsertContext ctx = this.ae2lt$insertContext;
         this.ae2lt$insertContext = null;
         if (ctx != null && what != null && ctx.getRequestedAmount() > 0L) {
            long remainder = Math.max(0L, ctx.getRequestedAmount() - ctx.getStrictMatched());
            if (remainder > 0L) {
               if (OverloadCpuStateManager.INSTANCE.hasAnyPending(this)) {
                  OverloadClaimResult preview = OverloadCpuStateManager.INSTANCE.claim(this, what, remainder, Actionable.SIMULATE);
                  if (preview.claimedAnything()) {
                     Object job = this.ae2lt$getJob();
                     if (job != null) {
                        CraftingLink link = this.ae2lt$getJobLink(job);
                        long requesterLimit = Math.min(preview.claimedForRequester(), Math.max(0L, this.ae2lt$getJobRemainingAmount(job)));
                        long requesterAccepted = 0L;
                        if (requesterLimit > 0L) {
                           requesterAccepted = link != null ? link.insert(what, requesterLimit, type) : 0L;
                        }

                        long requesterCompleted = FinalOutputAccounting.completedAmount(true, requesterLimit, requesterAccepted);
                        OverloadClaimResult claims = preview.partitionRequester(requesterLimit, requesterCompleted);
                        if (type == Actionable.MODULATE) {
                           claims = OverloadCpuStateManager.INSTANCE.commitPreview(this, claims);
                        }

                        if (claims.claimedAnything()) {
                           if (type == Actionable.MODULATE) {
                              this.ae2lt$deductClaimedWaitingFor(claims);
                              long inventoryAccepted = this.ae2lt$applyInventoryClaims(what, claims);
                              this.ae2lt$applyRequesterClaims(what, claims);
                              long supplementalReturn = FinalOutputAccounting.physicallyAcceptedAmount(
                                 inventoryAccepted, claims.claimedForRequester(), requesterAccepted
                              );
                              Object cpu = this.ae2lt$getCpu();
                              if (cpu != null) {
                                 ((ECOCraftingCpuAccessor)cpu).invokeMarkDirty();
                              }

                              cir.setReturnValue((Long)cir.getReturnValue() + supplementalReturn);
                           } else {
                              long simulatedReturn = FinalOutputAccounting.physicallyAcceptedAmount(
                                 claims.claimedForInventory(), claims.claimedForRequester(), requesterAccepted
                              );
                              cir.setReturnValue((Long)cir.getReturnValue() + simulatedReturn);
                           }
                        }
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
      if (!AE2LT_ECO_AVAILABLE) {
         return (Boolean)original.call(new Object[]{provider, details, inputHolder});
      } else {
         OverloadedProviderOnlyPatternDetails overloadDetails = CraftingPatternDelegates.forProviderLookup(details) instanceof OverloadedProviderOnlyPatternDetails overload
            ? overload
            : null;
         if (overloadDetails == null) {
            return OverloadCpuInsertSupport.hasPendingCollisionWithOrdinaryPattern(this, details)
               ? false
               : (Boolean)original.call(new Object[]{provider, details, inputHolder});
         } else {
            Object activeJob = this.ae2lt$getJob();
            ListCraftingInventory waitingFor = activeJob != null ? this.ae2lt$getJobWaitingFor(activeJob) : null;
            if (waitingFor != null && !OverloadCpuInsertSupport.hasStrictCollisionWithOverloadPattern(this, details, overloadDetails, waitingFor.list)) {
               OverloadPatternReference patternReference = new OverloadPatternReference(
                  overloadDetails.overloadPatternIdentity(), overloadDetails.overloadPatternDetailsView().sourcePattern()
               );
               if (OverloadCpuStateManager.INSTANCE.hasAmbiguousOutputRegistration(this, patternReference, overloadDetails.overloadPatternDetailsView())) {
                  return false;
               } else {
                  boolean pushed = (Boolean)original.call(new Object[]{provider, details, inputHolder});
                  Object job = this.ae2lt$getJob();
                  if (pushed && job != null) {
                     GenericStack finalOutput = this.ae2lt$getJobFinalOutput(job);
                     AEKey finalOutputKey = finalOutput != null ? finalOutput.what() : null;
                     CraftingLink link = this.ae2lt$getJobLink(job);
                     if (link != null) {
                        UUID craftingId = link.getCraftingID();
                        OverloadCpuStateManager.INSTANCE
                           .registerExpectedOutputs(
                              this, craftingId, patternReference, overloadDetails.overloadPatternDetailsView(), Arrays.asList(details.getOutputs()), finalOutputKey, 1L
                           );
                     }
                  }

                  return pushed;
               }
            } else {
               return false;
            }
         }
      }
   }

   @Inject(
      method = {"writeToNBT"},
      at = {@At("RETURN")}
   )
   private void ae2lt$writeOverloadState(CompoundTag data, Provider registries, CallbackInfo ci) {
      if (AE2LT_ECO_AVAILABLE) {
         CompoundTag overloadStateTag = OverloadCpuStateManager.INSTANCE.writeToTag(this, registries);
         if (overloadStateTag != null) {
            data.put("ae2ltOverloadState", overloadStateTag);
         } else {
            data.remove("ae2ltOverloadState");
         }
      }
   }

   @Inject(
      method = {"readFromNBT"},
      at = {@At("RETURN")}
   )
   private void ae2lt$readOverloadState(CompoundTag data, Provider registries, CallbackInfo ci) {
      if (AE2LT_ECO_AVAILABLE) {
         OverloadCpuStateManager.INSTANCE.clear(this);
         Object job = this.ae2lt$getJob();
         if (job != null && data.contains("ae2ltOverloadState", 10)) {
            CraftingLink link = this.ae2lt$getJobLink(job);
            if (link != null) {
               OverloadCpuStateManager.INSTANCE.readFromTag(this, link.getCraftingID(), data.getCompound("ae2ltOverloadState"), registries);
            }
         }
      }
   }

   @Inject(
      method = {"finishJob"},
      at = {@At("HEAD")}
   )
   private void ae2lt$clearOverloadState(boolean success, CallbackInfo ci) {
      if (AE2LT_ECO_AVAILABLE) {
         OverloadCpuStateManager.INSTANCE.clear(this);
      }
   }

   @Unique
   private long ae2lt$applyInventoryClaims(AEKey incoming, OverloadClaimResult claims) {
      long claimed = claims.claimedForInventory();
      Object job = this.ae2lt$getJob();
      if (claimed > 0L && job != null) {
         this.ae2lt$decrementJobItems(job, claimed, incoming.getType());
         ListCraftingInventory inventory = this.ae2lt$getInventory();
         if (inventory != null) {
            inventory.insert(incoming, claimed, Actionable.MODULATE);
         }

         return claimed;
      } else {
         return 0L;
      }
   }

   @Unique
   private void ae2lt$applyRequesterClaims(AEKey incoming, OverloadClaimResult claims) {
      long claimed = claims.claimedForRequester();
      Object job = this.ae2lt$getJob();
      if (claimed > 0L && job != null) {
         this.ae2lt$decrementJobItems(job, claimed, incoming.getType());
         this.ae2lt$invokePostChange(incoming);
         long remaining = Math.max(0L, this.ae2lt$getJobRemainingAmount(job) - claimed);
         this.ae2lt$setJobRemainingAmount(job, remaining);
         if (remaining <= 0L) {
            this.ae2lt$invokeFinishJob(true);
         }
      }
   }

   @Unique
   private void ae2lt$deductClaimedWaitingFor(OverloadClaimResult claims) {
      Object job = this.ae2lt$getJob();
      if (job != null) {
         ListCraftingInventory waitingFor = this.ae2lt$getJobWaitingFor(job);
         if (waitingFor != null) {
            for (PendingOverloadClaim claim : claims.claims()) {
               waitingFor.extract(claim.exactExpectedKey(), claim.claimedAmount(), Actionable.MODULATE);
            }
         }
      }
   }

   @Unique
   @Nullable
   private Object ae2lt$getJob() {
      Object job = MixinReflectionSupport.getFieldValueSafe(AE2LT_ECO_JOB_FIELD, this);
      return AE2LT_ECO_JOB_CLASS != null && AE2LT_ECO_JOB_CLASS.isInstance(job) ? job : null;
   }

   @Unique
   @Nullable
   private ListCraftingInventory ae2lt$getInventory() {
      return MixinReflectionSupport.getFieldValueSafe(AE2LT_ECO_INVENTORY_FIELD, this) instanceof ListCraftingInventory inv ? inv : null;
   }

   @Unique
   @Nullable
   private Object ae2lt$getCpu() {
      return MixinReflectionSupport.getFieldValueSafe(AE2LT_ECO_CPU_FIELD, this);
   }

   @Unique
   @Nullable
   private ListCraftingInventory ae2lt$getJobWaitingFor(Object job) {
      return MixinReflectionSupport.getFieldValueSafe(AE2LT_ECO_JOB_WAITING_FOR_FIELD, job) instanceof ListCraftingInventory inv ? inv : null;
   }

   @Unique
   @Nullable
   private GenericStack ae2lt$getJobFinalOutput(Object job) {
      return MixinReflectionSupport.getFieldValueSafe(AE2LT_ECO_JOB_FINAL_OUTPUT_FIELD, job) instanceof GenericStack stack ? stack : null;
   }

   @Unique
   private long ae2lt$getJobRemainingAmount(Object job) {
      return MixinReflectionSupport.getLongFieldSafe(AE2LT_ECO_JOB_REMAINING_AMOUNT_FIELD, job, 0L);
   }

   @Unique
   private void ae2lt$setJobRemainingAmount(Object job, long remainingAmount) {
      MixinReflectionSupport.setLongFieldSafe(AE2LT_ECO_JOB_REMAINING_AMOUNT_FIELD, job, remainingAmount, "set ECO job remaining amount");
   }

   @Unique
   @Nullable
   private CraftingLink ae2lt$getJobLink(Object job) {
      return MixinReflectionSupport.getFieldValueSafe(AE2LT_ECO_JOB_LINK_FIELD, job) instanceof CraftingLink link ? link : null;
   }

   @Unique
   private void ae2lt$decrementJobItems(Object job, long amount, AEKeyType keyType) {
      Object timeTracker = MixinReflectionSupport.getFieldValueSafe(AE2LT_ECO_JOB_TIME_TRACKER_FIELD, job);
      if (timeTracker != null) {
         MixinReflectionSupport.invokeMethodSafe(AE2LT_ECO_DECREMENT_ITEMS_METHOD, timeTracker, "decrement ECO job items", amount, keyType);
      }
   }

   @Unique
   private void ae2lt$invokeFinishJob(boolean success) {
      MixinReflectionSupport.invokeMethodSafe(AE2LT_ECO_FINISH_JOB_METHOD, this, "finish ECO job", success);
   }

   @Unique
   private void ae2lt$invokePostChange(AEKey what) {
      MixinReflectionSupport.invokeMethodSafe(AE2LT_ECO_POST_CHANGE_METHOD, this, "ECO post change", what);
   }
}
