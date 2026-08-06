package com.moakiee.thunderbolt.core.crafting.support;

/**
 * Per-calculation enable hook for the fast-crafting planner, implemented by the
 * {@code CraftingCalculation} mixin and driven by the host mod.
 *
 * <p>The crafting-service extension uses this internal calculation hook to enable the portable fast
 * path when an integration requests it. CPU ownership and product-specific plan binding are separate
 * concerns owned by the registered planner or host mod.
 *
 * <p>The {@code thunderbolt$} prefix keeps the synthetic members unique on the mixed-in AE2 class.
 */
public interface FastCraftingControl {

    /** Force-enable or force-disable the fast planner for this single calculation. */
    void thunderbolt$setFastPlanningEnabled(boolean enabled);

    /** @return whether the fast planner will be attempted for this calculation. */
    boolean thunderbolt$isFastPlanningEnabled();
}
