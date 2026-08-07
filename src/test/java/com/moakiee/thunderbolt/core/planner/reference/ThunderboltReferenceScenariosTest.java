package com.moakiee.thunderbolt.core.crafting.planner.reference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ThunderboltReferenceScenariosTest {
    @Test
    void everyFibonacciReferenceHasAtLeastDepth32() {
        var fibonacci = ThunderboltReferenceScenarios.all().stream()
                .filter(scenario -> scenario.id().contains("/fibonacci/"))
                .toList();

        assertFalse(fibonacci.isEmpty());
        assertTrue(fibonacci.stream().allMatch(scenario -> scenario.scale() >= 32));
    }

    @Test
    void ordinarySelfGrowthIsNotAnAe2ParityRequirement() {
        assertTrue(ThunderboltReferenceScenarios.all().stream()
                .noneMatch(scenario -> scenario.id().startsWith("cycle/self-growth-cut/")));
    }
}
