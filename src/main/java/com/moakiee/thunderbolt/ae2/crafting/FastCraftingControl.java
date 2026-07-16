package com.moakiee.thunderbolt.ae2.crafting;

/**
 * Per-calculation enable hook for the fast-crafting planner, implemented by the
 * {@code CraftingCalculation} mixin and driven by the host mod.
 *
 * <p>The crafting-service extension uses this internal calculation hook to enable the portable fast
 * path when an active time-wheel CPU is registered. CPU ownership is a separate plan-level concern represented by
 * {@link LoopCraftingPlan}; enabling this optimization does not itself lock a plan to one CPU.
 *
 * <p>The {@code ae2lt$} prefix keeps the synthetic members unique on the mixed-in AE2 class.
 */
public interface FastCraftingControl {

    /** Force-enable or force-disable the fast planner for this single calculation. */
    void ae2lt$setFastPlanningEnabled(boolean enabled);

    /** @return whether the fast planner will be attempted for this calculation. */
    boolean ae2lt$isFastPlanningEnabled();
}
