package com.moakiee.thunderbolt.ae2.overload.cpu;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.moakiee.thunderbolt.ae2.overload.model.MatchMode;
import com.moakiee.thunderbolt.ae2.overload.pattern.OverloadPatternDetails;
import com.moakiee.thunderbolt.ae2.overload.pattern.SourcePatternSnapshot;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

public final class OverloadCpuState {
   private static final String TAG_NEXT_SEQUENCE = "NextSequence";
   private static final String TAG_PENDING = "Pending";
   private static final String TAG_PATTERN_IDENTITY = "PatternIdentity";
   private static final String TAG_SOURCE_PATTERN = "SourcePattern";
   private static final String TAG_OUTPUT_SLOT = "OutputSlot";
   private static final String TAG_ITEM_ID = "ItemId";
   private static final String TAG_EXACT_TEMPLATE = "ExactTemplate";
   private static final String TAG_REMAINING = "RemainingAmount";
   private static final String TAG_ROUTES_TO_REQUESTER = "RoutesToRequester";
   private static final String TAG_REGISTERED_ORDER = "RegisteredOrder";
   private static final String TAG_REUSABLE_SEED_GROUP = "ReusableSeedGroup";
   private static final String TAG_SHARED_REUSABLE_SEED_POOL = "SharedReusableSeedPool";
   private static final String TAG_REMAINING_REUSABLE_SEED = "RemainingReusableSeed";
   private static final String TAG_CONSUMER_CREDITS = "ConsumerCredits";
   private static final String TAG_CONSUMER_ID = "ConsumerId";
   private static final String TAG_CONSUMER_AMOUNT = "Amount";
   private final OverloadCpuOwner owner;
   private final Map<PendingOverloadOutputKey, PendingOverloadOutput> pendingByKey = new LinkedHashMap<>();
   private final Map<ResourceLocation, LinkedHashSet<PendingOverloadOutputKey>> pendingByItemId = new LinkedHashMap<>();
   private long nextSequence = 1L;

   public OverloadCpuState(OverloadCpuOwner owner) {
      this.owner = Objects.requireNonNull(owner, "owner");
   }

   public OverloadCpuOwner owner() {
      return this.owner;
   }

   public Collection<PendingOverloadOutput> allPending() {
      return List.copyOf(this.pendingByKey.values());
   }

   Collection<PendingOverloadOutput> pendingView() {
      return this.pendingByKey.values();
   }

   public boolean isEmpty() {
      return this.pendingByKey.isEmpty();
   }

   public void registerExpectedOutputs(
      OverloadPatternReference patternReference,
      OverloadPatternDetails patternDetails,
      List<GenericStack> actualOutputs,
      @Nullable AEKey finalOutputKey,
      long pushedCopies
   ) {
      this.registerExpectedOutputs(patternReference, patternDetails, actualOutputs, finalOutputKey, pushedCopies, Map.of());
   }

   public void registerExpectedOutputs(
      OverloadPatternReference patternReference,
      OverloadPatternDetails patternDetails,
      List<GenericStack> actualOutputs,
      @Nullable AEKey finalOutputKey,
      long pushedCopies,
      Map<Integer, OverloadReusableSeedMetadata> reusableSeeds
   ) {
      Objects.requireNonNull(patternReference, "patternReference");
      Objects.requireNonNull(patternDetails, "patternDetails");
      Objects.requireNonNull(actualOutputs, "actualOutputs");
      if (pushedCopies <= 0L) {
         throw new IllegalArgumentException("pushedCopies must be > 0");
      } else {
         for (int outputIndex = 0; outputIndex < patternDetails.outputs().size(); outputIndex++) {
            OverloadPatternDetails.OutputSlot output = patternDetails.outputs().get(outputIndex);
            if (output.matchMode() == MatchMode.ID_ONLY) {
               int ae2SlotIndex = output.slotIndex();
               if (ae2SlotIndex >= 0 && ae2SlotIndex < actualOutputs.size()) {
                  GenericStack actual = actualOutputs.get(ae2SlotIndex);
                  if (actual.what() instanceof AEItemKey) {
                     ResourceLocation itemId = itemIdOf(output);
                     AEKey exactExpectedKey = actual.what();
                     long amount = multiplySaturated((long)output.amountPerCraft(), pushedCopies);
                     OverloadReusableSeedMetadata reusableSeed = reusableSeeds.get(output.slotIndex());
                     this.registerExpectedOutput(
                        patternReference, output.slotIndex(), itemId, exactExpectedKey, amount, routesToRequester(output, finalOutputKey), reusableSeed
                     );
                  }
               }
            }
         }
      }
   }

   void registerExpectedOutput(
      OverloadPatternReference patternReference,
      int outputSlotIndex,
      ResourceLocation itemId,
      AEKey exactExpectedKey,
      long amount,
      boolean routesToRequester,
      @Nullable OverloadReusableSeedMetadata reusableSeed
   ) {
      Objects.requireNonNull(patternReference, "patternReference");
      Objects.requireNonNull(itemId, "itemId");
      Objects.requireNonNull(exactExpectedKey, "exactExpectedKey");
      if (outputSlotIndex < 0) {
         throw new IllegalArgumentException("outputSlotIndex must be >= 0");
      } else if (amount <= 0L) {
         throw new IllegalArgumentException("amount must be > 0");
      } else {
         PendingOverloadOutputKey key = new PendingOverloadOutputKey(this.owner.craftingId(), patternReference.patternIdentity(), outputSlotIndex);
         PendingOverloadOutput existing = this.pendingByKey.get(key);
         if (existing != null) {
            if (existing.itemId().equals(itemId) && existing.exactExpectedKey().equals(exactExpectedKey) && existing.routesToRequester() == routesToRequester) {
               existing.addExpected(amount, reusableSeed);
            } else {
               throw new IllegalStateException("overload pending-output identity merged incompatible slots");
            }
         } else {
            PendingOverloadOutput pending = new PendingOverloadOutput(
               key,
               this.owner,
               patternReference,
               itemId,
               exactExpectedKey,
               amount,
               routesToRequester,
               this.nextSequence++,
               reusableSeed != null ? reusableSeed.consumerCredits() : List.of(),
               reusableSeed != null && reusableSeed.sharedPool()
            );
            this.pendingByKey.put(key, pending);
            this.pendingByItemId.computeIfAbsent(itemId, ignored -> new LinkedHashSet<>()).add(key);
         }
      }
   }

   public OverloadClaimResult claimByItemId(ResourceLocation itemId, long amount, boolean mutate) {
      return this.claimByItemId(itemId, amount, mutate, (consumer, expected) -> true);
   }

   public OverloadClaimResult claimByItemId(ResourceLocation itemId, long amount, boolean mutate, BiPredicate<UUID, AEKey> acceptsConsumerVariant) {
      Objects.requireNonNull(itemId, "itemId");
      Objects.requireNonNull(acceptsConsumerVariant, "acceptsConsumerVariant");
      if (amount <= 0L) {
         return OverloadClaimResult.EMPTY;
      } else {
         LinkedHashSet<PendingOverloadOutputKey> keys = this.pendingByItemId.get(itemId);
         if (keys != null && !keys.isEmpty()) {
            long remaining = amount;
            ArrayList<PendingOverloadClaim> claims = new ArrayList<>();

            for (PendingOverloadOutput pending : keys.stream()
               .map(this.pendingByKey::get)
               .filter(Objects::nonNull)
               .sorted(Comparator.comparingLong(PendingOverloadOutput::registeredOrder))
               .toList()) {
               if (remaining <= 0L) {
                  break;
               }

               Predicate<UUID> acceptsConsumer = consumer -> acceptsConsumerVariant.test(consumer, pending.exactExpectedKey());
               long acceptedCredits = pending.acceptedConsumerCreditAmount(acceptsConsumer);
               long compatibleAmount = addSaturated(pending.remainingPublicAmount(), acceptedCredits);
               long claimable = Math.min(Math.min(pending.remainingAmount(), remaining), compatibleAmount);
               if (claimable > 0L) {
                  List<OverloadConsumerCredit> consumerCredits = pending.claimConsumerCredits(claimable, mutate, acceptsConsumer);
                  if (mutate) {
                     pending.claim(claimable);
                     if (pending.isSatisfied()) {
                        this.removeSatisfied(pending);
                     }
                  }

                  claims.add(
                     new PendingOverloadClaim(
                        pending.key(), claimable, pending.routesToRequester(), pending.exactExpectedKey(), consumerCredits, pending.sharedReusableSeedPool()
                     )
                  );
                  remaining -= claimable;
               }
            }

            long claimedAmount = amount - remaining;
            return claimedAmount > 0L ? new OverloadClaimResult(claimedAmount, claims) : OverloadClaimResult.EMPTY;
         } else {
            return OverloadClaimResult.EMPTY;
         }
      }
   }

   public OverloadClaimResult commitPreview(OverloadClaimResult preview) {
      Objects.requireNonNull(preview, "preview");
      if (!preview.claimedAnything()) {
         return OverloadClaimResult.EMPTY;
      } else {
         long total = 0L;
         ArrayList<PendingOverloadClaim> committed = new ArrayList<>(preview.claims().size());

         for (PendingOverloadClaim requested : preview.claims()) {
            PendingOverloadOutput pending = this.pendingByKey.get(requested.key());
            if (pending != null) {
               List<OverloadConsumerCredit> availableCredits = pending.claimConsumerCredits(requested.consumerCredits(), false);
               long requestedPublic = Math.max(0L, requested.claimedAmount() - requested.reusableSeedAmount());
               long committedPublic = Math.min(requestedPublic, pending.remainingPublicAmount());
               List<OverloadConsumerCredit> consumerCredits = pending.claimConsumerCredits(availableCredits, true);
               long amount = addSaturated(OverloadConsumerCredit.total(consumerCredits), committedPublic);
               if (amount > 0L) {
                  pending.claim(amount);
                  long committedRequester = Math.min(requested.requesterAmount(), committedPublic);
                  committed.add(
                     new PendingOverloadClaim(
                        pending.key(),
                        amount,
                        pending.routesToRequester(),
                        committedRequester,
                        pending.exactExpectedKey(),
                        consumerCredits,
                        pending.sharedReusableSeedPool()
                     )
                  );
                  total = addSaturated(total, amount);
                  if (pending.isSatisfied()) {
                     this.removeSatisfied(pending);
                  }
               }
            }
         }

         return total > 0L ? new OverloadClaimResult(total, committed) : OverloadClaimResult.EMPTY;
      }
   }

   public long getRemainingForItem(ResourceLocation itemId) {
      BigInteger total = this.getRemainingForItemExact(itemId);
      return total.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) >= 0 ? Long.MAX_VALUE : total.longValue();
   }

   BigInteger getRemainingForItemExact(ResourceLocation itemId) {
      Objects.requireNonNull(itemId, "itemId");
      LinkedHashSet<PendingOverloadOutputKey> keys = this.pendingByItemId.get(itemId);
      if (keys != null && !keys.isEmpty()) {
         BigInteger total = BigInteger.ZERO;

         for (PendingOverloadOutputKey key : keys) {
            PendingOverloadOutput pending = this.pendingByKey.get(key);
            if (pending != null) {
               total = total.add(BigInteger.valueOf(pending.remainingAmount()));
            }
         }

         return total;
      } else {
         return BigInteger.ZERO;
      }
   }

   public boolean hasExactPending(AEKey incoming) {
      return this.getRemainingForExactKey(incoming).signum() > 0;
   }

   BigInteger getRemainingForExactKey(AEKey incoming) {
      if (incoming == null) {
         return BigInteger.ZERO;
      } else {
         BigInteger total = BigInteger.ZERO;

         for (PendingOverloadOutput pending : this.pendingByKey.values()) {
            if (pending.remainingAmount() > 0L && pending.exactExpectedKey().equals(incoming)) {
               total = total.add(BigInteger.valueOf(pending.remainingAmount()));
            }
         }

         return total;
      }
   }

   public void clear() {
      this.pendingByKey.clear();
      this.pendingByItemId.clear();
   }

   public CompoundTag toTag(Provider registries) {
      Objects.requireNonNull(registries, "registries");
      CompoundTag tag = new CompoundTag();
      tag.putLong("NextSequence", this.nextSequence);
      ListTag pendingList = new ListTag();

      for (PendingOverloadOutput pending : this.pendingByKey.values()) {
         CompoundTag pendingTag = new CompoundTag();
         pendingTag.putString("PatternIdentity", pending.key().patternIdentity());
         pendingTag.put("SourcePattern", pending.patternReference().sourcePattern().toTag());
         pendingTag.putInt("OutputSlot", pending.key().outputSlotIndex());
         pendingTag.putString("ItemId", pending.itemId().toString());
         pendingTag.put("ExactTemplate", pending.exactExpectedKey().toTagGeneric());
         pendingTag.putLong("RemainingAmount", pending.remainingAmount());
         pendingTag.putBoolean("RoutesToRequester", pending.routesToRequester());
         pendingTag.putLong("RegisteredOrder", pending.registeredOrder());
         if (!pending.consumerCredits().isEmpty()) {
            writeConsumerCredits(pendingTag, pending.consumerCredits());
            pendingTag.putBoolean("SharedReusableSeedPool", pending.sharedReusableSeedPool());
            if (pending.reusableSeedGroupId() != null) {
               pendingTag.putUUID("ReusableSeedGroup", pending.reusableSeedGroupId());
               pendingTag.putLong("RemainingReusableSeed", pending.remainingReusableSeedAmount());
            }
         }

         pendingList.add(pendingTag);
      }

      tag.put("Pending", pendingList);
      return tag;
   }

   public static OverloadCpuState fromTag(OverloadCpuOwner owner, CompoundTag tag, Provider registries) {
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(tag, "tag");
      Objects.requireNonNull(registries, "registries");
      OverloadCpuState state = new OverloadCpuState(owner);
      state.nextSequence = Math.max(1L, tag.getLong("NextSequence"));
      ListTag pendingList = tag.getList("Pending", 10);

      for (int i = 0; i < pendingList.size(); i++) {
         CompoundTag pendingTag = pendingList.getCompound(i);
         OverloadPatternReference patternReference = new OverloadPatternReference(
            pendingTag.getString("PatternIdentity"), SourcePatternSnapshot.fromTag(pendingTag.getCompound("SourcePattern"))
         );
         PendingOverloadOutputKey key = new PendingOverloadOutputKey(
            owner.craftingId(), pendingTag.getString("PatternIdentity"), pendingTag.getInt("OutputSlot")
         );
         PendingOverloadOutput pending = new PendingOverloadOutput(
            key,
            owner,
            patternReference,
            ResourceLocation.parse(pendingTag.getString("ItemId")),
            loadExactExpectedKey(pendingTag, registries),
            pendingTag.getLong("RemainingAmount"),
            pendingTag.getBoolean("RoutesToRequester"),
            pendingTag.getLong("RegisteredOrder"),
            readConsumerCredits(pendingTag),
            pendingTag.getBoolean("SharedReusableSeedPool")
         );
         state.pendingByKey.put(key, pending);
         state.pendingByItemId.computeIfAbsent(pending.itemId(), ignored -> new LinkedHashSet<>()).add(key);
         state.nextSequence = Math.max(state.nextSequence, pending.registeredOrder() + 1L);
      }

      return state;
   }

   static void writeConsumerCredits(CompoundTag tag, Collection<OverloadConsumerCredit> consumerCredits) {
      Objects.requireNonNull(tag, "tag");
      ListTag creditsTag = new ListTag();

      for (OverloadConsumerCredit credit : OverloadConsumerCredit.normalize(consumerCredits)) {
         CompoundTag creditTag = new CompoundTag();
         creditTag.putUUID("ConsumerId", credit.consumerId());
         creditTag.putLong("Amount", credit.amount());
         creditsTag.add(creditTag);
      }

      tag.put("ConsumerCredits", creditsTag);
   }

   static List<OverloadConsumerCredit> readConsumerCredits(CompoundTag tag) {
      Objects.requireNonNull(tag, "tag");
      if (tag.contains("ConsumerCredits", 9)) {
         ArrayList<OverloadConsumerCredit> credits = new ArrayList<>();
         ListTag creditsTag = tag.getList("ConsumerCredits", 10);

         for (int i = 0; i < creditsTag.size(); i++) {
            CompoundTag creditTag = creditsTag.getCompound(i);
            if (creditTag.hasUUID("ConsumerId")) {
               long amount = creditTag.getLong("Amount");
               if (amount > 0L) {
                  credits.add(new OverloadConsumerCredit(creditTag.getUUID("ConsumerId"), amount));
               }
            }
         }

         return OverloadConsumerCredit.normalize(credits);
      } else {
         if (tag.hasUUID("ReusableSeedGroup")) {
            long amount = tag.getLong("RemainingReusableSeed");
            if (amount > 0L) {
               return List.of(new OverloadConsumerCredit(tag.getUUID("ReusableSeedGroup"), amount));
            }
         }

         return List.of();
      }
   }

   private static AEKey loadExactExpectedKey(CompoundTag pendingTag, Provider registries) {
      if (!pendingTag.contains("ExactTemplate", 10)) {
         throw new IllegalArgumentException("pending overload entry is missing an exact expected key");
      } else {
         AEKey key = AEKey.fromTagGeneric(pendingTag.getCompound("ExactTemplate").copy());
         if (key == null) {
            throw new IllegalArgumentException("pending overload entry has an invalid exact expected key");
         } else {
            return key;
         }
      }
   }

   private void removeSatisfied(PendingOverloadOutput pending) {
      this.pendingByKey.remove(pending.key());
      LinkedHashSet<PendingOverloadOutputKey> keys = this.pendingByItemId.get(pending.itemId());
      if (keys != null) {
         keys.remove(pending.key());
         if (keys.isEmpty()) {
            this.pendingByItemId.remove(pending.itemId());
         }
      }
   }

   private static ResourceLocation itemIdOf(OverloadPatternDetails.OutputSlot output) {
      AEItemKey key = AEItemKey.of(output.template());
      if (key == null) {
         throw new IllegalArgumentException("output template must resolve to an item key");
      } else {
         return key.getId();
      }
   }

   private static boolean routesToRequester(OverloadPatternDetails.OutputSlot output, @Nullable AEKey finalOutputKey) {
      if (finalOutputKey == null) {
         return false;
      } else {
         AEItemKey outputKey = AEItemKey.of(output.template());
         return outputKey == null
            ? false
            : OutputRouteDecision.routesToRequester(
               output.matchMode(), outputKey.equals(finalOutputKey), outputKey.dropSecondary().equals(finalOutputKey.dropSecondary())
            );
      }
   }

   private static long multiplySaturated(long left, long right) {
      if (left > 0L && right > 0L) {
         return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
      } else {
         return 0L;
      }
   }

   private static long addSaturated(long left, long right) {
      return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
   }
}
