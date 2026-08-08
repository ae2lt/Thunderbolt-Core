package com.moakiee.thunderbolt.ae2.overload.cpu;

import appeng.api.stacks.AEKey;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public record PendingOverloadClaim(
   PendingOverloadOutputKey key,
   long claimedAmount,
   boolean routesToRequester,
   long requesterAmount,
   AEKey exactExpectedKey,
   List<OverloadConsumerCredit> consumerCredits,
   boolean sharedReusableSeedPool
) {
   public PendingOverloadClaim(
      PendingOverloadOutputKey key,
      long claimedAmount,
      boolean routesToRequester,
      long requesterAmount,
      AEKey exactExpectedKey,
      List<OverloadConsumerCredit> consumerCredits,
      boolean sharedReusableSeedPool
   ) {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(exactExpectedKey, "exactExpectedKey");
      if (claimedAmount <= 0L) {
         throw new IllegalArgumentException("claimedAmount must be > 0");
      } else {
         consumerCredits = OverloadConsumerCredit.normalize(consumerCredits);
         if (!OverloadConsumerCredit.fitsWithin(consumerCredits, claimedAmount)) {
            throw new IllegalArgumentException("consumer credits are outside the claim");
         } else {
            long maximumRequester = claimedAmount - OverloadConsumerCredit.total(consumerCredits);
            if (requesterAmount >= 0L && requesterAmount <= maximumRequester && (routesToRequester || requesterAmount == 0L)) {
               this.key = key;
               this.claimedAmount = claimedAmount;
               this.routesToRequester = routesToRequester;
               this.requesterAmount = requesterAmount;
               this.exactExpectedKey = exactExpectedKey;
               this.consumerCredits = consumerCredits;
               this.sharedReusableSeedPool = sharedReusableSeedPool;
            } else {
               throw new IllegalArgumentException("requesterAmount is outside the claim");
            }
         }
      }
   }

   public PendingOverloadClaim(
      PendingOverloadOutputKey key,
      long claimedAmount,
      boolean routesToRequester,
      AEKey exactExpectedKey,
      List<OverloadConsumerCredit> consumerCredits,
      boolean sharedReusableSeedPool
   ) {
      this(
         key,
         claimedAmount,
         routesToRequester,
         routesToRequester ? claimedAmount - OverloadConsumerCredit.total(consumerCredits) : 0L,
         exactExpectedKey,
         consumerCredits,
         sharedReusableSeedPool
      );
   }

   public PendingOverloadClaim(
      PendingOverloadOutputKey key,
      long claimedAmount,
      boolean routesToRequester,
      AEKey exactExpectedKey,
      long reusableSeedAmount,
      @Nullable UUID reusableSeedGroupId,
      boolean sharedReusableSeedPool
   ) {
      this(key, claimedAmount, routesToRequester, exactExpectedKey, legacyCredits(reusableSeedAmount, reusableSeedGroupId), sharedReusableSeedPool);
   }

   public long reusableSeedAmount() {
      return OverloadConsumerCredit.total(this.consumerCredits);
   }

   @Nullable
   public UUID reusableSeedGroupId() {
      return this.consumerCredits.size() == 1 ? this.consumerCredits.get(0).consumerId() : null;
   }

   private static List<OverloadConsumerCredit> legacyCredits(long amount, @Nullable UUID consumerId) {
      if (amount < 0L) {
         throw new IllegalArgumentException("reusableSeedAmount must not be negative");
      } else {
         return amount == 0L ? List.of() : List.of(new OverloadConsumerCredit(Objects.requireNonNull(consumerId, "reusableSeedGroupId"), amount));
      }
   }
}
