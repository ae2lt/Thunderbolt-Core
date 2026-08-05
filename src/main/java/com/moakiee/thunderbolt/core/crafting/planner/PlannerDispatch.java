package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.Objects;

import com.moakiee.thunderbolt.api.crafting.planner.CraftingPlannerRegistry;
import com.moakiee.thunderbolt.api.crafting.planner.CraftingPlannerRequest;
import com.moakiee.thunderbolt.api.crafting.planner.CraftingPlannerResult;
import com.moakiee.thunderbolt.api.crafting.planner.CraftingPlannerStatus;

/** Selects at most one terminal planner for a request. */
public final class PlannerDispatch {

    private PlannerDispatch() {
    }

    public static Selection dispatch(CraftingPlannerRequest request) {
        Objects.requireNonNull(request, "request");
        for (var registered : CraftingPlannerRegistry.planners()) {
            CraftingPlannerResult result = Objects.requireNonNull(
                    registered.planner().plan(request),
                    () -> "Planner returned null: " + registered.id());
            if (result.status() != CraftingPlannerStatus.UNSUPPORTED) {
                return new Selection(registered.id(), result);
            }
        }
        return Selection.unsupported();
    }

    public record Selection(String plannerId, CraftingPlannerResult result) {

        private static Selection unsupported() {
            return new Selection("", CraftingPlannerResult.unsupported());
        }

        public boolean handled() {
            return result.status() != CraftingPlannerStatus.UNSUPPORTED;
        }
    }
}
