package com.moakiee.thunderbolt.core.planner;

import java.util.Map;

/**
 * Result of {@link CraftPlanner#plan}.
 *
 * @param supported     {@code false} means the fast path declined (e.g. recursion/cycle detected);
 *                      caller must fall back to AE2's simulator. When {@code false} all other fields
 *                      are empty/zero.
 * @param feasible      {@code true} if the requested amount can be fully crafted from current stock.
 *                      When {@code false}, {@link #missing} lists what is short (a partial plan is
 *                      still provided for the craftable part).
 * @param firings       pattern -> number of times to fire it (the compact plan). Keyed by pattern
 *                      object identity.
 * @param usedStock     item -> amount drawn directly from the inventory snapshot.
 * @param usedReusableStock host + logical pool + item -> amount borrowed from private storage.
 * @param missing       item -> amount that could not be obtained (raw leaves under DEEP mode).
 * @param grossDemand   item -> total amount requested before drawing from stock (one entry per
 *                      visited item). Exposed so the AE2 adapter can reproduce AE2's byte accounting
 *                      ({@code addStackBytes} is charged on the pre-extraction request amount).
 * @param itemsProcessed number of items visited by the linear demand pass, or recursive node
 *                       invocations performed by the bounded fallback. Request magnitude does not
 *                       affect this value because every firing count is handled in closed form.
 * @param budgetExhausted retained for result compatibility. Node-local visit exhaustion no longer
 *                       invalidates a calculation: that node freezes to one greedy recipe, so the v2
 *                       planner leaves this {@code false}. Depth overflow is reported as a normal
 *                       branch-local missing input and may be recovered by a parent alternative.
 * @param <K> item key type
 */
public record CraftPlan<K>(
        boolean supported,
        boolean feasible,
        Map<CraftPattern<K>, Long> firings,
        Map<K, Long> usedStock,
        Map<ReusableStockUsageKey<K>, Long> usedReusableStock,
        Map<K, Long> missing,
        Map<K, Long> grossDemand,
        int itemsProcessed,
        boolean budgetExhausted) {

    public static <K> CraftPlan<K> unsupported() {
        return new CraftPlan<>(false, false, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0, false);
    }
}
