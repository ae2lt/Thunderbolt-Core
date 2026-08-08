package com.moakiee.thunderbolt.ae2.timewheel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class PendingRequesterOutputAccounting {
   private PendingRequesterOutputAccounting() {
   }

   static <K> PendingRequesterOutputAccounting.Reconciliation<K> capToRemainingDemand(
      List<PendingRequesterOutputAccounting.Credit<K>> pending, long remainingDemand, K newlyCompletedKey
   ) {
      if (pending != null && !pending.isEmpty()) {
         ArrayList<PendingRequesterOutputAccounting.Credit<K>> ordered = new ArrayList<>();
         PendingRequesterOutputAccounting.Credit<K> preferred = null;

         for (PendingRequesterOutputAccounting.Credit<K> entry : pending) {
            long amount = Math.max(0L, entry.amount());
            if (amount > 0L) {
               PendingRequesterOutputAccounting.Credit<K> credit = new PendingRequesterOutputAccounting.Credit<>(entry.key(), amount);
               if (newlyCompletedKey != null && Objects.equals(newlyCompletedKey, entry.key())) {
                  preferred = credit;
               } else {
                  ordered.add(credit);
               }
            }
         }

         if (preferred != null) {
            ordered.add(preferred);
         }

         long creditsLeft = Math.max(0L, remainingDemand);
         long released = 0L;
         ArrayList<PendingRequesterOutputAccounting.Credit<K>> retainedCredits = new ArrayList<>();

         for (PendingRequesterOutputAccounting.Credit<K> entryx : ordered) {
            long retained = Math.min(entryx.amount(), creditsLeft);
            creditsLeft -= retained;
            if (retained > 0L) {
               retainedCredits.add(new PendingRequesterOutputAccounting.Credit<>(entryx.key(), retained));
            }

            released = addSaturated(released, entryx.amount() - retained);
         }

         return new PendingRequesterOutputAccounting.Reconciliation<>(List.copyOf(retainedCredits), released);
      } else {
         return new PendingRequesterOutputAccounting.Reconciliation<>(List.of(), 0L);
      }
   }

   private static long addSaturated(long left, long right) {
      if (right <= 0L) {
         return left;
      } else {
         return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
      }
   }

   static record Credit<K>(K key, long amount) {
   }

   static record Reconciliation<K>(List<PendingRequesterOutputAccounting.Credit<K>> retained, long released) {
   }
}
