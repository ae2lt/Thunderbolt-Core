package com.moakiee.thunderbolt.core.planner;

import java.util.Objects;

public record CraftInput<K>(K key, long amount, boolean returned, long uses, K remainder, ReusableStockSource reusableStockSource) {
   public static final long INFINITE_USES = Long.MAX_VALUE;

   public CraftInput(K key, long amount, boolean returned, long uses, K remainder, ReusableStockSource reusableStockSource) {
      Objects.requireNonNull(key, "key");
      if (amount <= 0L) {
         throw new IllegalArgumentException("input amount must be > 0, was " + amount);
      } else if (returned && uses <= 0L) {
         throw new IllegalArgumentException("returned input uses must be > 0, was " + uses);
      } else if (reusableStockSource == null || returned && uses == Long.MAX_VALUE && remainder == null) {
         this.key = key;
         this.amount = amount;
         this.returned = returned;
         this.uses = uses;
         this.remainder = remainder;
         this.reusableStockSource = reusableStockSource;
      } else {
         throw new IllegalArgumentException("host-owned reusable stock requires an unchanged, infinitely reusable input");
      }
   }

   public static <K> CraftInput<K> of(K key, long amount) {
      return new CraftInput<>(key, amount, false, Long.MAX_VALUE, null, null);
   }

   public static <K> CraftInput<K> returned(K key, long amount) {
      return new CraftInput<>(key, amount, true, Long.MAX_VALUE, null, null);
   }

   public static <K> CraftInput<K> returnedFrom(K key, long amount, ReusableStockSource source) {
      return new CraftInput<>(key, amount, true, Long.MAX_VALUE, null, Objects.requireNonNull(source, "source"));
   }

   public static <K> CraftInput<K> finiteUse(K key, long amount, long uses) {
      return new CraftInput<>(key, amount, true, uses, null, null);
   }

   public static <K> CraftInput<K> consumedReturning(K key, long amount, K remainder) {
      return new CraftInput<>(key, amount, false, Long.MAX_VALUE, Objects.requireNonNull(remainder), null);
   }

   public long unitsFor(long times) {
      if (!this.returned) {
         return Sat.mul(this.amount, times);
      } else {
         long unit = this.uses == Long.MAX_VALUE ? 1L : Sat.ceilDiv(times, this.uses);
         return Sat.mul(this.amount, unit);
      }
   }

   public long firingsFrom(long available) {
      long perUnit = available / this.amount;
      return !this.returned ? perUnit : Sat.mul(perUnit, this.uses);
   }
}
