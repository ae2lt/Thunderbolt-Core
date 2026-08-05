package com.moakiee.thunderbolt.api.crafting.planner;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.crafting.ICraftingPlan;

/** Result of one planner invocation. */
public record CraftingPlannerResult(
        CraftingPlannerStatus status,
        @Nullable ICraftingPlan plan,
        @Nullable String diagnostic) {

    public CraftingPlannerResult {
        Objects.requireNonNull(status, "status");
        if (status == CraftingPlannerStatus.EXACT_FEASIBLE && plan == null) {
            throw new IllegalArgumentException("EXACT_FEASIBLE requires a plan");
        }
        if (status == CraftingPlannerStatus.UNSUPPORTED && plan != null) {
            throw new IllegalArgumentException("UNSUPPORTED cannot carry a plan");
        }
    }

    public static CraftingPlannerResult feasible(ICraftingPlan plan) {
        return new CraftingPlannerResult(CraftingPlannerStatus.EXACT_FEASIBLE,
                Objects.requireNonNull(plan, "plan"), null);
    }

    public static CraftingPlannerResult infeasible(@Nullable ICraftingPlan simulationPlan,
                                                    @Nullable String diagnostic) {
        return new CraftingPlannerResult(CraftingPlannerStatus.EXACT_INFEASIBLE,
                simulationPlan, diagnostic);
    }

    public static CraftingPlannerResult unsupported() {
        return new CraftingPlannerResult(CraftingPlannerStatus.UNSUPPORTED, null, null);
    }

    public static CraftingPlannerResult budgetExhausted(@Nullable ICraftingPlan bestEffortPlan,
                                                        @Nullable String diagnostic) {
        return new CraftingPlannerResult(CraftingPlannerStatus.BUDGET_EXHAUSTED,
                bestEffortPlan, diagnostic);
    }
}
