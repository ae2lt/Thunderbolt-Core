package com.moakiee.thunderbolt.ae2.overload.cpu;

import appeng.api.config.Actionable;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuLogic;
import com.moakiee.thunderbolt.ae2.overload.model.MatchMode;
import com.moakiee.thunderbolt.ae2.overload.pattern.OverloadPatternDetails;
import it.unimi.dsi.fastutil.objects.Object2LongMap.Entry;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BiPredicate;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

public final class OverloadCpuStateManager {
   public static final OverloadCpuStateManager INSTANCE = new OverloadCpuStateManager();
   private final Map<Object, OverloadCpuState> states = new WeakHashMap<>();

   private OverloadCpuStateManager() {
   }

   public synchronized OverloadCpuState getOrCreate(CraftingCpuLogic logic) {
      Objects.requireNonNull(logic, "logic");
      ICraftingLink link = logic.getLastLink();
      if (link == null) {
         throw new IllegalStateException("crafting logic has no active link");
      } else {
         return this.getOrCreate(logic, link.getCraftingID());
      }
   }

   public synchronized OverloadCpuState getOrCreate(Object logic, UUID craftingId) {
      Objects.requireNonNull(logic, "logic");
      Objects.requireNonNull(craftingId, "craftingId");
      return this.states.computeIfAbsent(logic, ignored -> new OverloadCpuState(OverloadCpuOwner.from(craftingId, logic)));
   }

   public synchronized Optional<OverloadCpuState> get(CraftingCpuLogic logic) {
      Objects.requireNonNull(logic, "logic");
      return Optional.ofNullable(this.states.get(logic));
   }

   public synchronized void registerExpectedOutputs(
      CraftingCpuLogic logic,
      OverloadPatternReference patternReference,
      OverloadPatternDetails patternDetails,
      List<GenericStack> actualOutputs,
      @Nullable AEKey finalOutputKey,
      long pushedCopies
   ) {
      Objects.requireNonNull(logic, "logic");
      Objects.requireNonNull(patternReference, "patternReference");
      Objects.requireNonNull(patternDetails, "patternDetails");
      Objects.requireNonNull(actualOutputs, "actualOutputs");
      if (pushedCopies <= 0L) {
         throw new IllegalArgumentException("pushedCopies must be > 0");
      } else {
         this.getOrCreate(logic).registerExpectedOutputs(patternReference, patternDetails, actualOutputs, finalOutputKey, pushedCopies);
      }
   }

   public synchronized void registerExpectedOutputs(
      Object logic,
      UUID craftingId,
      OverloadPatternReference patternReference,
      OverloadPatternDetails patternDetails,
      List<GenericStack> actualOutputs,
      @Nullable AEKey finalOutputKey,
      long pushedCopies
   ) {
      this.registerExpectedOutputs(logic, craftingId, patternReference, patternDetails, actualOutputs, finalOutputKey, pushedCopies, Map.of());
   }

   public synchronized void registerExpectedOutputs(
      Object logic,
      UUID craftingId,
      OverloadPatternReference patternReference,
      OverloadPatternDetails patternDetails,
      List<GenericStack> actualOutputs,
      @Nullable AEKey finalOutputKey,
      long pushedCopies,
      Map<Integer, OverloadReusableSeedMetadata> reusableSeeds
   ) {
      Objects.requireNonNull(logic, "logic");
      Objects.requireNonNull(craftingId, "craftingId");
      Objects.requireNonNull(patternReference, "patternReference");
      Objects.requireNonNull(patternDetails, "patternDetails");
      Objects.requireNonNull(actualOutputs, "actualOutputs");
      Objects.requireNonNull(reusableSeeds, "reusableSeeds");
      if (pushedCopies <= 0L) {
         throw new IllegalArgumentException("pushedCopies must be > 0");
      } else {
         this.getOrCreate(logic, craftingId)
            .registerExpectedOutputs(patternReference, patternDetails, actualOutputs, finalOutputKey, pushedCopies, reusableSeeds);
      }
   }

   public synchronized boolean hasAmbiguousOutputRegistration(
      CraftingCpuLogic logic, OverloadPatternReference patternReference, OverloadPatternDetails patternDetails
   ) {
      return this.hasAmbiguousOutputRegistration((Object)logic, patternReference, patternDetails);
   }

   public synchronized boolean hasAmbiguousOutputRegistration(Object logic, OverloadPatternReference patternReference, OverloadPatternDetails patternDetails) {
      Objects.requireNonNull(logic, "logic");
      Objects.requireNonNull(patternReference, "patternReference");
      Objects.requireNonNull(patternDetails, "patternDetails");
      OverloadCpuState state = this.states.get(logic);
      LinkedHashMap<ResourceLocation, OverloadCpuStateManager.OutputRegistrationCandidate> batch = new LinkedHashMap<>();

      for (OverloadPatternDetails.OutputSlot output : patternDetails.outputs()) {
         if (output.matchMode() == MatchMode.ID_ONLY) {
            ResourceLocation itemId = itemIdOf(output);
            OverloadCpuStateManager.OutputRegistrationCandidate candidate = new OverloadCpuStateManager.OutputRegistrationCandidate(
               patternReference.patternIdentity(), output.slotIndex(), true
            );
            OverloadCpuStateManager.OutputRegistrationCandidate batchExisting = batch.putIfAbsent(itemId, candidate);
            if (batchExisting != null && !batchExisting.equals(candidate)) {
               return true;
            }

            if (state != null) {
               for (PendingOverloadOutput pending : state.pendingView()) {
                  if (pending.itemId().equals(itemId)) {
                     PendingOverloadOutputKey pendingKey = pending.key();
                     if (!pendingKey.patternIdentity().equals(candidate.patternIdentity()) || pendingKey.outputSlotIndex() != candidate.outputSlotIndex()) {
                        return true;
                     }
                  }
               }
            }
         }
      }

      return false;
   }

   public synchronized OverloadClaimResult claim(CraftingCpuLogic logic, AEKey incoming, long amount, Actionable actionable) {
      return this.claim((Object)logic, incoming, amount, actionable);
   }

   public synchronized OverloadClaimResult claim(Object logic, AEKey incoming, long amount, Actionable actionable) {
      return this.claim(logic, incoming, amount, actionable, (consumer, expected) -> true);
   }

   public synchronized OverloadClaimResult claim(
      Object logic, AEKey incoming, long amount, Actionable actionable, BiPredicate<UUID, AEKey> acceptsConsumerVariant
   ) {
      Objects.requireNonNull(logic, "logic");
      Objects.requireNonNull(incoming, "incoming");
      Objects.requireNonNull(actionable, "actionable");
      Objects.requireNonNull(acceptsConsumerVariant, "acceptsConsumerVariant");
      if (amount <= 0L) {
         return OverloadClaimResult.EMPTY;
      } else {
         AEItemKey itemKey = asItemKey(incoming);
         if (itemKey == null) {
            return OverloadClaimResult.EMPTY;
         } else {
            OverloadCpuState state = this.states.get(logic);
            if (state == null) {
               return OverloadClaimResult.EMPTY;
            } else {
               OverloadClaimResult result = state.claimByItemId(itemKey.getId(), amount, actionable == Actionable.MODULATE, acceptsConsumerVariant);
               if (actionable == Actionable.MODULATE && state.isEmpty()) {
                  this.states.remove(logic);
               }

               return result;
            }
         }
      }
   }

   public synchronized OverloadClaimResult commitPreview(Object logic, OverloadClaimResult preview) {
      Objects.requireNonNull(logic, "logic");
      Objects.requireNonNull(preview, "preview");
      OverloadCpuState state = this.states.get(logic);
      if (state != null && preview.claimedAnything()) {
         OverloadClaimResult committed = state.commitPreview(preview);
         if (state.isEmpty()) {
            this.states.remove(logic);
         }

         return committed;
      } else {
         return OverloadClaimResult.EMPTY;
      }
   }

   public synchronized long getRemainingForItem(CraftingCpuLogic logic, ResourceLocation itemId) {
      return this.getRemainingForItem((Object)logic, itemId);
   }

   public synchronized long getRemainingForItem(Object logic, ResourceLocation itemId) {
      Objects.requireNonNull(logic, "logic");
      Objects.requireNonNull(itemId, "itemId");
      OverloadCpuState state = this.states.get(logic);
      return state != null ? state.getRemainingForItem(itemId) : 0L;
   }

   public synchronized boolean hasNativeStrictWaiting(Object logic, ResourceLocation itemId, KeyCounter nativeWaiting) {
      Objects.requireNonNull(logic, "logic");
      Objects.requireNonNull(itemId, "itemId");
      Objects.requireNonNull(nativeWaiting, "nativeWaiting");
      BigInteger nativeAmount = BigInteger.ZERO;

      for (Entry<AEKey> entry : nativeWaiting) {
         if (entry != null && entry.getLongValue() > 0L) {
            Object var8 = entry.getKey();
            if (var8 instanceof AEItemKey) {
               AEItemKey item = (AEItemKey)var8;
               if (item.getId().equals(itemId)) {
                  nativeAmount = nativeAmount.add(BigInteger.valueOf(entry.getLongValue()));
               }
            }
         }
      }

      OverloadCpuState state = this.states.get(logic);
      BigInteger idOnlyAmount = state != null ? state.getRemainingForItemExact(itemId) : BigInteger.ZERO;
      return hasNativeDemandBeyondIdOnly(nativeAmount, idOnlyAmount);
   }

   static boolean hasNativeDemandBeyondIdOnly(BigInteger nativeAmount, BigInteger idOnlyAmount) {
      Objects.requireNonNull(nativeAmount, "nativeAmount");
      Objects.requireNonNull(idOnlyAmount, "idOnlyAmount");
      return nativeAmount.compareTo(idOnlyAmount) > 0;
   }

   public synchronized boolean hasExactPending(Object logic, AEKey incoming) {
      Objects.requireNonNull(logic, "logic");
      Objects.requireNonNull(incoming, "incoming");
      OverloadCpuState state = this.states.get(logic);
      return state != null && state.hasExactPending(incoming);
   }

   public synchronized long getRemainingForExactKey(Object logic, AEKey incoming) {
      Objects.requireNonNull(logic, "logic");
      Objects.requireNonNull(incoming, "incoming");
      OverloadCpuState state = this.states.get(logic);
      if (state == null) {
         return 0L;
      } else {
         BigInteger amount = state.getRemainingForExactKey(incoming);
         return amount.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) >= 0 ? Long.MAX_VALUE : amount.longValue();
      }
   }

   public synchronized List<PendingOverloadOutput> snapshotPending(CraftingCpuLogic logic) {
      return this.snapshotPending((Object)logic);
   }

   public synchronized List<PendingOverloadOutput> snapshotPending(Object logic) {
      Objects.requireNonNull(logic, "logic");
      OverloadCpuState state = this.states.get(logic);
      return state != null ? List.copyOf(state.allPending()) : List.of();
   }

   public synchronized boolean hasAnyPending(CraftingCpuLogic logic) {
      return this.hasAnyPending((Object)logic);
   }

   public synchronized boolean hasAnyPending(Object logic) {
      Objects.requireNonNull(logic, "logic");
      OverloadCpuState state = this.states.get(logic);
      return state != null && !state.isEmpty();
   }

   public synchronized void clear(CraftingCpuLogic logic) {
      this.clear((Object)logic);
   }

   public synchronized void clear(Object logic) {
      Objects.requireNonNull(logic, "logic");
      this.states.remove(logic);
   }

   @Nullable
   public synchronized CompoundTag writeToTag(CraftingCpuLogic logic, Provider registries) {
      return this.writeToTag((Object)logic, registries);
   }

   @Nullable
   public synchronized CompoundTag writeToTag(Object logic, Provider registries) {
      Objects.requireNonNull(logic, "logic");
      Objects.requireNonNull(registries, "registries");
      OverloadCpuState state = this.states.get(logic);
      return state != null && !state.isEmpty() ? state.toTag(registries) : null;
   }

   public synchronized void readFromTag(CraftingCpuLogic logic, CompoundTag tag, Provider registries) {
      ICraftingLink link = logic.getLastLink();
      if (link == null) {
         throw new IllegalStateException("crafting logic has no active link");
      } else {
         this.readFromTag(logic, link.getCraftingID(), tag, registries);
      }
   }

   public synchronized void readFromTag(Object logic, UUID craftingId, CompoundTag tag, Provider registries) {
      Objects.requireNonNull(logic, "logic");
      Objects.requireNonNull(craftingId, "craftingId");
      Objects.requireNonNull(tag, "tag");
      Objects.requireNonNull(registries, "registries");
      if (tag.isEmpty()) {
         this.states.remove(logic);
      } else {
         this.states.put(logic, OverloadCpuState.fromTag(OverloadCpuOwner.from(craftingId, logic), tag, registries));
      }
   }

   private static AEItemKey asItemKey(AEKey key) {
      return key instanceof AEItemKey itemKey ? itemKey : null;
   }

   private static ResourceLocation itemIdOf(OverloadPatternDetails.OutputSlot output) {
      AEItemKey key = AEItemKey.of(output.template());
      if (key == null) {
         throw new IllegalArgumentException("output template must resolve to an item key");
      } else {
         return key.getId();
      }
   }

   private static record OutputRegistrationCandidate(String patternIdentity, int outputSlotIndex, boolean idOnly) {
   }
}
