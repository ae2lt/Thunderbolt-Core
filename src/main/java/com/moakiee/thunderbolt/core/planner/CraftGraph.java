package com.moakiee.thunderbolt.core.planner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class CraftGraph<K> {
   private final Map<K, List<CraftPattern<K>>> patternsByOutput;
   private final Map<K, Long> stock;
   private final Map<ReusableStockKey<K>, Long> reusableStock;
   private final Map<ReusableStockRouteKey<K>, List<K>> reusableStockRoutes;

   private CraftGraph(
      Map<K, List<CraftPattern<K>>> patternsByOutput,
      Map<K, Long> stock,
      Map<ReusableStockKey<K>, Long> reusableStock,
      Map<ReusableStockRouteKey<K>, List<K>> reusableStockRoutes
   ) {
      this.patternsByOutput = patternsByOutput;
      this.stock = stock;
      this.reusableStock = reusableStock;
      this.reusableStockRoutes = reusableStockRoutes;
   }

   public List<CraftPattern<K>> patternsFor(K key) {
      return this.patternsByOutput.getOrDefault(key, List.of());
   }

   public long stock(K key) {
      Long v = this.stock.get(key);
      return v == null ? 0L : Math.max(0L, v);
   }

   public long reusableStock(Object scope, K key) {
      Long value = this.reusableStock.get(new ReusableStockKey(scope, key));
      return value == null ? 0L : Math.max(0L, value);
   }

   public long reusableStock(ReusableStockSource source, K plannedKey) {
      long total = 0L;

      for (K actual : this.reusableStockCandidates(source, plannedKey)) {
         total = Sat.add(total, this.reusableStock(source.storageScope(), actual));
      }

      return total;
   }

   public List<K> reusableStockCandidates(ReusableStockSource source, K plannedKey) {
      ReusableStockRouteKey<K> route = new ReusableStockRouteKey<>(source, plannedKey);
      List<K> candidates = this.reusableStockRoutes.get(route);
      return candidates != null ? candidates : List.of(plannedKey);
   }

   Map<ReusableStockKey<K>, Long> reusableStock() {
      return this.reusableStock;
   }

   public static <K> CraftGraph.Builder<K> builder() {
      return new CraftGraph.Builder<>();
   }

   public static final class Builder<K> {
      private final Map<K, List<CraftPattern<K>>> patterns = new HashMap<>();
      private final Map<K, Long> stock = new HashMap<>();
      private final Map<ReusableStockKey<K>, Long> reusableStock = new HashMap<>();
      private final Map<ReusableStockRouteKey<K>, LinkedHashSet<K>> reusableStockRoutes = new HashMap<>();

      public CraftGraph.Builder<K> pattern(CraftPattern<K> pattern) {
         this.patterns.computeIfAbsent(pattern.output(), k -> new ArrayList<>()).add(pattern);
         return this;
      }

      public CraftGraph.Builder<K> pattern(K output, long outAmount, List<CraftInput<K>> inputs) {
         return this.pattern(new CraftPattern<>(output, outAmount, inputs, null));
      }

      public CraftGraph.Builder<K> pattern(K output, long outAmount, List<CraftInput<K>> inputs, List<CraftOutput<K>> byproducts) {
         return this.pattern(new CraftPattern<>(output, outAmount, inputs, byproducts, null));
      }

      public CraftGraph.Builder<K> stock(K key, long amount) {
         this.stock.merge(key, amount, Sat::add);
         return this;
      }

      public CraftGraph.Builder<K> reusableStock(Object scope, K key, long amount) {
         if (amount > 0L) {
            this.reusableStock.merge(new ReusableStockKey<>(scope, key), amount, Math::max);
         }

         return this;
      }

      public CraftGraph.Builder<K> reusableStockRoute(ReusableStockSource source, K plannedKey, Iterable<? extends K> actualVariants) {
         ReusableStockRouteKey<K> route = new ReusableStockRouteKey<>(source, plannedKey);
         LinkedHashSet<K> accepted = this.reusableStockRoutes.computeIfAbsent(route, ignored -> new LinkedHashSet<>());

         for (K actual : actualVariants) {
            if (actual != null) {
               accepted.add(actual);
            }
         }

         return this;
      }

      public CraftGraph<K> build() {
         Map<K, List<CraftPattern<K>>> frozen = new HashMap<>();
         this.patterns.forEach((k, v) -> frozen.put((K)k, List.copyOf(v)));
         HashMap<ReusableStockRouteKey<K>, List<K>> frozenRoutes = new HashMap<>();
         this.reusableStockRoutes.forEach((route, variants) -> frozenRoutes.put((ReusableStockRouteKey<K>)route, List.copyOf(variants)));
         return new CraftGraph<>(frozen, Map.copyOf(this.stock), Map.copyOf(this.reusableStock), Map.copyOf(frozenRoutes));
      }
   }
}
