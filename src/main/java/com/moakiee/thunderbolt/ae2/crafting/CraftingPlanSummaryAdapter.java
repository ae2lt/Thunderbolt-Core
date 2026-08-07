package com.moakiee.thunderbolt.ae2.crafting;

import java.util.Map;
import java.util.Objects;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;

/** Builds concrete, display-only AE2 plans for integrations that cast the public plan interface. */
public final class CraftingPlanSummaryAdapter {

    private CraftingPlanSummaryAdapter() {
    }

    /**
     * Returns a concrete AE2 plan containing the interface-visible state of {@code plan}.
     *
     * <p>The returned value is only for summary/display code. Menu storage and CPU submission must
     * retain the original plan because it may carry private routing or execution metadata.
     */
    public static CraftingPlan adapt(ICraftingPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan instanceof CraftingPlan craftingPlan) {
            return craftingPlan;
        }
        return new CraftingPlan(
                plan.finalOutput(),
                plan.bytes(),
                plan.simulation(),
                plan.multiplePaths(),
                copyCounter(plan.usedItems()),
                copyCounter(plan.emittedItems()),
                copyCounter(plan.missingItems()),
                Map.copyOf(plan.patternTimes()));
    }

    private static KeyCounter copyCounter(KeyCounter source) {
        Objects.requireNonNull(source, "plan counter");
        var result = new KeyCounter();
        for (var entry : source) {
            result.add(entry.getKey(), entry.getLongValue());
        }
        return result;
    }
}
