package com.moakiee.thunderbolt.api.crafting.planner;

/** Exhaustive result states for one terminal planner invocation. */
public enum CraftingPlannerStatus {
    /** The planner produced an exact, executable result. */
    EXACT_FEASIBLE,
    /** The planner proved that the requested amount is not feasible. */
    EXACT_INFEASIBLE,
    /** The planner does not own the semantics required by this request. */
    UNSUPPORTED,
    /** The planner accepted the request but exhausted its bounded search budget. */
    BUDGET_EXHAUSTED
}
