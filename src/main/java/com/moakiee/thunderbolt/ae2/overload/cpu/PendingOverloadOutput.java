package com.moakiee.thunderbolt.ae2.overload.cpu;

import appeng.api.stacks.AEKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

public final class PendingOverloadOutput {
   private final PendingOverloadOutputKey key;
   private final OverloadCpuOwner owner;
   private final OverloadPatternReference patternReference;
   private final ResourceLocation itemId;
   private final AEKey exactExpectedKey;
   private final boolean routesToRequester;
   private final long registeredOrder;
   private final LinkedHashMap<UUID, Long> remainingConsumerCredits = new LinkedHashMap<>();
   private boolean sharedReusableSeedPool;
   private long remainingAmount;

   public PendingOverloadOutput(
      PendingOverloadOutputKey key,
      OverloadCpuOwner owner,
      OverloadPatternReference patternReference,
      ResourceLocation itemId,
      AEKey exactExpectedKey,
      long remainingAmount,
      boolean routesToRequester,
      long registeredOrder
   ) {
      this(key, owner, patternReference, itemId, exactExpectedKey, remainingAmount, routesToRequester, registeredOrder, null, false, 0L);
   }

   public PendingOverloadOutput(
      PendingOverloadOutputKey key,
      OverloadCpuOwner owner,
      OverloadPatternReference patternReference,
      ResourceLocation itemId,
      AEKey exactExpectedKey,
      long remainingAmount,
      boolean routesToRequester,
      long registeredOrder,
      @Nullable UUID reusableSeedGroupId,
      boolean sharedReusableSeedPool,
      long remainingReusableSeedAmount
   ) {
      this(
         key,
         owner,
         patternReference,
         itemId,
         exactExpectedKey,
         remainingAmount,
         routesToRequester,
         registeredOrder,
         legacyCredits(remainingReusableSeedAmount, reusableSeedGroupId),
         sharedReusableSeedPool
      );
   }

   public PendingOverloadOutput(
      PendingOverloadOutputKey key,
      OverloadCpuOwner owner,
      OverloadPatternReference patternReference,
      ResourceLocation itemId,
      AEKey exactExpectedKey,
      long remainingAmount,
      boolean routesToRequester,
      long registeredOrder,
      List<OverloadConsumerCredit> consumerCredits,
      boolean sharedReusableSeedPool
   ) {
      this.key = Objects.requireNonNull(key, "key");
      this.owner = Objects.requireNonNull(owner, "owner");
      this.patternReference = Objects.requireNonNull(patternReference, "patternReference");
      this.itemId = Objects.requireNonNull(itemId, "itemId");
      this.exactExpectedKey = Objects.requireNonNull(exactExpectedKey, "exactExpectedKey");
      if (remainingAmount <= 0L) {
         throw new IllegalArgumentException("remainingAmount must be > 0");
      } else {
         this.remainingAmount = remainingAmount;
         this.routesToRequester = routesToRequester;
         this.registeredOrder = registeredOrder;
         List<OverloadConsumerCredit> normalizedCredits = OverloadConsumerCredit.normalize(consumerCredits);
         if (!OverloadConsumerCredit.fitsWithin(normalizedCredits, remainingAmount)) {
            throw new IllegalArgumentException("consumer credits are outside pending output");
         } else {
            for (OverloadConsumerCredit credit : normalizedCredits) {
               this.remainingConsumerCredits.put(credit.consumerId(), Long.valueOf(credit.amount()));
            }

            this.sharedReusableSeedPool = sharedReusableSeedPool;
         }
      }
   }

   public PendingOverloadOutputKey key() {
      return this.key;
   }

   public OverloadCpuOwner owner() {
      return this.owner;
   }

   public OverloadPatternReference patternReference() {
      return this.patternReference;
   }

   public ResourceLocation itemId() {
      return this.itemId;
   }

   public AEKey exactExpectedKey() {
      return this.exactExpectedKey;
   }

   public long remainingAmount() {
      return this.remainingAmount;
   }

   public boolean routesToRequester() {
      return this.routesToRequester;
   }

   public int outputSlotIndex() {
      return this.key.outputSlotIndex();
   }

   public long registeredOrder() {
      return this.registeredOrder;
   }

   public List<OverloadConsumerCredit> consumerCredits() {
      return OverloadConsumerCredit.fromAmounts(this.remainingConsumerCredits);
   }

   public long remainingReusableSeedAmount() {
      return OverloadConsumerCredit.total(this.consumerCredits());
   }

   public long remainingPublicAmount() {
      return Math.max(0L, this.remainingAmount - this.remainingReusableSeedAmount());
   }

   @Nullable
   public UUID reusableSeedGroupId() {
      return this.remainingConsumerCredits.size() == 1 ? this.remainingConsumerCredits.keySet().iterator().next() : null;
   }

   public boolean sharedReusableSeedPool() {
      return this.sharedReusableSeedPool;
   }

   public void addExpected(long amount) {
      this.addExpected(amount, null);
   }

   public void addExpected(long amount, @Nullable OverloadReusableSeedMetadata reusableSeed) {
      if (amount <= 0L) {
         throw new IllegalArgumentException("amount must be > 0");
      } else {
         if (reusableSeed != null) {
            if (!OverloadConsumerCredit.fitsWithin(reusableSeed.consumerCredits(), amount)) {
               throw new IllegalArgumentException("consumer credits exceed added output");
            }

            boolean hadConsumerCredits = !this.remainingConsumerCredits.isEmpty();
            if (hadConsumerCredits && this.sharedReusableSeedPool != reusableSeed.sharedPool()) {
               throw new IllegalArgumentException("overload output changed legacy pool semantics");
            }

            LinkedHashMap<UUID, Long> updatedCredits = new LinkedHashMap<>(this.remainingConsumerCredits);

            for (OverloadConsumerCredit credit : reusableSeed.consumerCredits()) {
               updatedCredits.merge(credit.consumerId(), Long.valueOf(credit.amount()), OverloadConsumerCredit::addSaturated);
            }

            long updatedRemaining = addSaturated(this.remainingAmount, amount);
            if (!OverloadConsumerCredit.fitsWithin(OverloadConsumerCredit.fromAmounts(updatedCredits), updatedRemaining)) {
               throw new IllegalArgumentException("consumer credits exceed saturated pending output capacity");
            }

            this.remainingConsumerCredits.clear();
            this.remainingConsumerCredits.putAll(updatedCredits);
            if (!hadConsumerCredits) {
               this.sharedReusableSeedPool = reusableSeed.sharedPool();
            }
         }

         this.remainingAmount = addSaturated(this.remainingAmount, amount);
      }
   }

   public long claim(long requestedAmount) {
      if (requestedAmount <= 0L) {
         return 0L;
      } else {
         long claimed = Math.min(this.remainingAmount, requestedAmount);
         this.remainingAmount -= claimed;
         return claimed;
      }
   }

   public List<OverloadConsumerCredit> claimConsumerCredits(long claimedOutput, boolean mutate) {
      return this.claimConsumerCredits(claimedOutput, mutate, ignored -> true);
   }

   public List<OverloadConsumerCredit> claimConsumerCredits(long claimedOutput, boolean mutate, Predicate<UUID> acceptsConsumer) {
      long remaining = Math.max(0L, claimedOutput);
      if (remaining != 0L && !this.remainingConsumerCredits.isEmpty()) {
         Objects.requireNonNull(acceptsConsumer, "acceptsConsumer");
         ArrayList<OverloadConsumerCredit> claimed = new ArrayList<>();
         Iterator<Entry<UUID, Long>> iterator = this.remainingConsumerCredits.entrySet().iterator();

         while (iterator.hasNext() && remaining > 0L) {
            Entry<UUID, Long> entry = iterator.next();
            if (acceptsConsumer.test(entry.getKey())) {
               long amount = Math.min(entry.getValue(), remaining);
               if (amount > 0L) {
                  claimed.add(new OverloadConsumerCredit(entry.getKey(), amount));
                  remaining -= amount;
                  if (mutate) {
                     long left = entry.getValue() - amount;
                     if (left == 0L) {
                        iterator.remove();
                     } else {
                        entry.setValue(left);
                     }
                  }
               }
            }
         }

         return List.copyOf(claimed);
      } else {
         return List.of();
      }
   }

   public long acceptedConsumerCreditAmount(Predicate<UUID> acceptsConsumer) {
      Objects.requireNonNull(acceptsConsumer, "acceptsConsumer");
      long total = 0L;

      for (Entry<UUID, Long> entry : this.remainingConsumerCredits.entrySet()) {
         if (acceptsConsumer.test(entry.getKey())) {
            total = addSaturated(total, entry.getValue());
         }
      }

      return total;
   }

   public List<OverloadConsumerCredit> claimConsumerCredits(Collection<OverloadConsumerCredit> requestedCredits, boolean mutate) {
      List<OverloadConsumerCredit> requested = OverloadConsumerCredit.normalize(requestedCredits);
      if (!requested.isEmpty() && !this.remainingConsumerCredits.isEmpty()) {
         ArrayList<OverloadConsumerCredit> claimed = new ArrayList<>(requested.size());

         for (OverloadConsumerCredit request : requested) {
            long available = this.remainingConsumerCredits.getOrDefault(request.consumerId(), 0L);
            long amount = Math.min(available, request.amount());
            if (amount > 0L) {
               claimed.add(new OverloadConsumerCredit(request.consumerId(), amount));
               if (mutate) {
                  long left = available - amount;
                  if (left == 0L) {
                     this.remainingConsumerCredits.remove(request.consumerId());
                  } else {
                     this.remainingConsumerCredits.put(request.consumerId(), Long.valueOf(left));
                  }
               }
            }
         }

         return List.copyOf(claimed);
      } else {
         return List.of();
      }
   }

   public long claimReusableSeed(long claimedOutput, boolean mutate) {
      return OverloadConsumerCredit.total(this.claimConsumerCredits(claimedOutput, mutate));
   }

   public boolean isSatisfied() {
      return this.remainingAmount <= 0L;
   }

   private static long addSaturated(long left, long right) {
      return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
   }

   private static List<OverloadConsumerCredit> legacyCredits(long amount, @Nullable UUID consumerId) {
      if (amount < 0L) {
         throw new IllegalArgumentException("remainingReusableSeedAmount must not be negative");
      } else {
         return amount == 0L ? List.of() : List.of(new OverloadConsumerCredit(Objects.requireNonNull(consumerId, "reusableSeedGroupId"), amount));
      }
   }
}
