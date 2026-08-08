package com.moakiee.thunderbolt.ae2.overload.cpu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record OverloadClaimResult(long claimedAmount, List<PendingOverloadClaim> claims) {
   public static final OverloadClaimResult EMPTY = new OverloadClaimResult(0L, List.of());

   public OverloadClaimResult(long claimedAmount, List<PendingOverloadClaim> claims) {
      if (claimedAmount < 0L) {
         throw new IllegalArgumentException("claimedAmount must be >= 0");
      } else {
         claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
         long remaining = claimedAmount;

         for (PendingOverloadClaim claim : claims) {
            if (claim.claimedAmount() > remaining) {
               throw new IllegalArgumentException("claims exceed claimedAmount");
            }

            remaining -= claim.claimedAmount();
         }

         if (remaining != 0L) {
            throw new IllegalArgumentException("claims do not add up to claimedAmount");
         } else {
            this.claimedAmount = claimedAmount;
            this.claims = claims;
         }
      }
   }

   public boolean claimedAnything() {
      return this.claimedAmount > 0L;
   }

   public long claimedForRequester() {
      long total = 0L;

      for (PendingOverloadClaim claim : this.claims) {
         total = addSaturated(total, claim.requesterAmount());
      }

      return total;
   }

   public long claimedForInventory() {
      return this.claimedAmount - this.claimedForRequester();
   }

   public List<OverloadConsumerCredit> consumerCredits() {
      LinkedHashMap<UUID, Long> amounts = new LinkedHashMap<>();

      for (PendingOverloadClaim claim : this.claims) {
         for (OverloadConsumerCredit credit : claim.consumerCredits()) {
            amounts.merge(credit.consumerId(), Long.valueOf(credit.amount()), OverloadConsumerCredit::addSaturated);
         }
      }

      return OverloadConsumerCredit.fromAmounts(amounts);
   }

   public long consumerCreditAmount(UUID consumerId) {
      Objects.requireNonNull(consumerId, "consumerId");
      long total = 0L;

      for (OverloadConsumerCredit credit : this.consumerCredits()) {
         if (credit.consumerId().equals(consumerId)) {
            total = OverloadConsumerCredit.addSaturated(total, credit.amount());
         }
      }

      return total;
   }

   public OverloadClaimResult limitRequester(long acceptedRequesterAmount) {
      long requesterRemaining = Math.max(0L, acceptedRequesterAmount);
      long total = 0L;
      ArrayList<PendingOverloadClaim> limited = new ArrayList<>(this.claims.size());

      for (PendingOverloadClaim claim : this.claims) {
         long inventoryPart = claim.claimedAmount() - claim.requesterAmount();
         long accepted = Math.min(claim.requesterAmount(), requesterRemaining);
         requesterRemaining -= accepted;
         long kept = inventoryPart + accepted;
         if (kept > 0L) {
            limited.add(
               new PendingOverloadClaim(
                  claim.key(),
                  kept,
                  claim.routesToRequester(),
                  accepted,
                  claim.exactExpectedKey(),
                  OverloadConsumerCredit.limit(claim.consumerCredits(), kept),
                  claim.sharedReusableSeedPool()
               )
            );
            total = addSaturated(total, kept);
         }
      }

      return total > 0L ? new OverloadClaimResult(total, limited) : EMPTY;
   }

   public OverloadClaimResult partitionRequester(long maximumRequesterAmount, long completedRequesterAmount) {
      long requestLimit = Math.max(0L, maximumRequesterAmount);
      long completedRemaining = Math.max(0L, completedRequesterAmount);
      long total = 0L;
      ArrayList<PendingOverloadClaim> partitioned = new ArrayList<>(this.claims.size());

      for (PendingOverloadClaim claim : this.claims) {
         long publicAmount = claim.requesterAmount();
         long requested = Math.min(publicAmount, requestLimit);
         requestLimit -= requested;
         long completed = Math.min(requested, completedRemaining);
         completedRemaining -= completed;
         long excess = publicAmount - requested;
         long inventoryPart = claim.claimedAmount() - publicAmount;
         long kept = addSaturated(addSaturated(inventoryPart, excess), completed);
         if (kept > 0L) {
            partitioned.add(
               new PendingOverloadClaim(
                  claim.key(),
                  kept,
                  claim.routesToRequester(),
                  completed,
                  claim.exactExpectedKey(),
                  OverloadConsumerCredit.limit(claim.consumerCredits(), kept),
                  claim.sharedReusableSeedPool()
               )
            );
            total = addSaturated(total, kept);
         }
      }

      return total > 0L ? new OverloadClaimResult(total, partitioned) : EMPTY;
   }

   private static long addSaturated(long left, long right) {
      return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
   }
}
