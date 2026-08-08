package com.moakiee.thunderbolt.core.planner;

import java.util.Objects;

public record CraftOutput<K>(K key, long amount) {
   public CraftOutput(K key, long amount) {
      Objects.requireNonNull(key, "key");
      if (amount <= 0L) {
         throw new IllegalArgumentException("output amount must be > 0, was " + amount);
      } else {
         this.key = key;
         this.amount = amount;
      }
   }

   public static <K> CraftOutput<K> of(K key, long amount) {
      return new CraftOutput<>(key, amount);
   }
}
