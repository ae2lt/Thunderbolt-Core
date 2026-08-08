package com.moakiee.thunderbolt.core.planner;

public record PlanningResult<K>(CraftPlan<K> plan, PlanningDiagnostics diagnostics) {
}
