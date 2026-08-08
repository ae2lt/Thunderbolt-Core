package com.moakiee.thunderbolt.ae2.overload.cpu;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Map.Entry;

public record OverloadConsumerCredit(UUID consumerId, long amount) {
   public OverloadConsumerCredit(UUID consumerId, long amount) {
      Objects.requireNonNull(consumerId, "consumerId");
      if (amount <= 0L) {
         throw new IllegalArgumentException("amount must be positive");
      } else {
         this.consumerId = consumerId;
         this.amount = amount;
      }
   }

   public static List<OverloadConsumerCredit> normalize(Collection<OverloadConsumerCredit> credits) {
      Objects.requireNonNull(credits, "credits");
      LinkedHashMap<UUID, Long> amounts = new LinkedHashMap<>();

      for (OverloadConsumerCredit credit : credits) {
         Objects.requireNonNull(credit, "consumer credit");
         amounts.merge(credit.consumerId(), Long.valueOf(credit.amount()), OverloadConsumerCredit::addSaturated);
      }

      return fromAmounts(amounts);
   }

   public static List<OverloadConsumerCredit> fromAmounts(Map<UUID, Long> amounts) {
      Objects.requireNonNull(amounts, "amounts");
      ArrayList<OverloadConsumerCredit> result = new ArrayList<>(amounts.size());

      for (Entry<UUID, Long> entry : amounts.entrySet()) {
         UUID consumerId = Objects.requireNonNull(entry.getKey(), "consumerId");
         Long amount = Objects.requireNonNull(entry.getValue(), "consumer amount");
         if (amount < 0L) {
            throw new IllegalArgumentException("consumer amount must not be negative");
         }

         if (amount > 0L) {
            result.add(new OverloadConsumerCredit(consumerId, amount));
         }
      }

      return List.copyOf(result);
   }

   public static long total(Collection<OverloadConsumerCredit> credits) {
      Objects.requireNonNull(credits, "credits");
      long total = 0L;

      for (OverloadConsumerCredit credit : credits) {
         total = addSaturated(total, Objects.requireNonNull(credit, "consumer credit").amount());
      }

      return total;
   }

   public static boolean fitsWithin(Collection<OverloadConsumerCredit> credits, long maximumAmount) {
      Objects.requireNonNull(credits, "credits");
      if (maximumAmount < 0L) {
         return false;
      } else {
         long remaining = maximumAmount;

         for (OverloadConsumerCredit credit : credits) {
            long amount = Objects.requireNonNull(credit, "consumer credit").amount();
            if (amount > remaining) {
               return false;
            }

            remaining -= amount;
         }

         return true;
      }
   }

   public static List<OverloadConsumerCredit> limit(Collection<OverloadConsumerCredit> credits, long maximumAmount) {
      if (maximumAmount <= 0L) {
         return List.of();
      } else {
         long remaining = maximumAmount;
         ArrayList<OverloadConsumerCredit> result = new ArrayList<>();

         for (OverloadConsumerCredit credit : credits) {
            if (remaining <= 0L) {
               break;
            }

            long amount = Math.min(credit.amount(), remaining);
            if (amount > 0L) {
               result.add(new OverloadConsumerCredit(credit.consumerId(), amount));
               remaining -= amount;
            }
         }

         return List.copyOf(result);
      }
   }

   static long addSaturated(long left, long right) {
      return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
   }
}
