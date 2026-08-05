package com.moakiee.thunderbolt.api.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CraftingPlannerRegistryContractTest {

    @Test
    void thirdPartyPlannersUsePriorityThenStableRegistrationOrder() {
        ICraftingPlanner unsupported = request -> CraftingPlannerResult.unsupported();
        var low = CraftingPlannerRegistry.register("contract-test:low", 10, unsupported);
        var firstHigh = CraftingPlannerRegistry.register("contract-test:first-high", 20, unsupported);
        var secondHigh = CraftingPlannerRegistry.register("contract-test:second-high", 20, unsupported);
        try {
            assertEquals(
                    java.util.List.of(
                            "contract-test:first-high",
                            "contract-test:second-high",
                            "contract-test:low"),
                    CraftingPlannerRegistry.planners().stream()
                            .map(CraftingPlannerRegistry.RegisteredPlanner::id)
                            .toList());
            assertThrows(IllegalStateException.class, () -> CraftingPlannerRegistry.register(
                    "contract-test:low", Integer.MAX_VALUE, unsupported));
        } finally {
            low.close();
            firstHigh.close();
            secondHigh.close();
        }
        assertEquals(java.util.List.of(), CraftingPlannerRegistry.planners());
    }
}
