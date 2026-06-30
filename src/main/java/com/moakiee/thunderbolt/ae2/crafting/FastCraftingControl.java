package com.moakiee.thunderbolt.ae2.crafting;

/**
 * Per-calculation enable hook for the fast-crafting planner, implemented by the
 * {@code CraftingCalculation} mixin and driven by the host mod.
 *
 * <p>Thunderbolt Core can run standalone (the planner is enabled by {@code CoreConfig.FAST_PATH_ENABLED}
 * for every calculation). When AE2 Lightning Tech is present it uses this hook to gate the fast path to
 * exactly the jobs it wants accelerated (e.g. only when a TimeWheel CPU is active), by calling
 * {@link #ae2lt$setFastPlanningEnabled(boolean)} on the freshly created calculation.
 *
 * <p>The {@code ae2lt$} prefix keeps the synthetic members unique on the mixed-in AE2 class.
 */
public interface FastCraftingControl {

    /** Force-enable or force-disable the fast planner for this single calculation. */
    void ae2lt$setFastPlanningEnabled(boolean enabled);

    /** @return whether the fast planner will be attempted for this calculation. */
    boolean ae2lt$isFastPlanningEnabled();
}
