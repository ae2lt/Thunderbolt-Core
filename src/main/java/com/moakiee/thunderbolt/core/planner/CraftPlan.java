package com.moakiee.thunderbolt.core.planner;

import java.util.Map;

public record CraftPlan<K>(
   boolean supported,
   boolean feasible,
   Map<CraftPattern<K>, Long> firings,
   Map<K, Long> usedStock,
   Map<ReusableStockUsageKey<K>, Long> usedReusableStock,
   Map<K, Long> missing,
   Map<K, Long> grossDemand,
   int itemsProcessed,
   boolean budgetExhausted
) {
}
