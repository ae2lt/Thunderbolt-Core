package com.moakiee.thunderbolt.api.crafting.engine;

import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import appeng.api.networking.crafting.ICraftingPlan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic contract tests for the engine registry + selection (no Minecraft runtime required).
 */
class CraftingEngineSelectionTest {

    private static final String TEST_ENGINE = "test_vm_engine";

    private static CraftingEngine testEngine(String id) {
        return new CraftingEngine() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String displayName() {
                return id;
            }

            @Override
            public String modId() {
                return null; // always available (no mod gate in tests)
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public Future<ICraftingPlan> route(CraftingEngineRequest request) {
                return null;
            }
        };
    }

    @Test
    void defaultSelectionIsNone() {
        CraftingEngineSelection.seed(CraftingEngineRegistry.NONE);
        assertEquals(CraftingEngineRegistry.NONE, CraftingEngineSelection.current());
        assertTrue(CraftingEngineSelection.usesThunderboltPlanner());
    }

    @Test
    void selectingRegisteredEngineIsMutuallyExclusive() {
        CraftingEngineRegistry.register(testEngine(TEST_ENGINE));

        assertTrue(CraftingEngineSelection.select(TEST_ENGINE));
        assertEquals(TEST_ENGINE, CraftingEngineSelection.current());
        assertFalse(CraftingEngineSelection.usesThunderboltPlanner());

        // back to vanilla
        assertTrue(CraftingEngineSelection.select(CraftingEngineRegistry.NONE));
        assertEquals(CraftingEngineRegistry.NONE, CraftingEngineSelection.current());
        assertTrue(CraftingEngineSelection.usesThunderboltPlanner());
    }

    @Test
    void selectingUnknownEngineIsRejectedAndKeepsPrevious() {
        CraftingEngineSelection.seed(CraftingEngineRegistry.NONE);
        assertFalse(CraftingEngineSelection.select("ghost_engine"));
        assertEquals(CraftingEngineRegistry.NONE, CraftingEngineSelection.current());
    }

    @Test
    void registeringBlankIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CraftingEngineRegistry.register(testEngine(" ")));
    }

    @Test
    void seedAcceptsUnavailableValueAndRoutesFallThroughSafely() {
        CraftingEngineSelection.seed("some_removed_mod");
        assertEquals("some_removed_mod", CraftingEngineSelection.current());
        // not registered → the mixin falls through to vanilla; thunderbolt planner not driving
        assertFalse(CraftingEngineSelection.usesThunderboltPlanner());
        CraftingEngineSelection.seed(CraftingEngineRegistry.NONE);
    }

    @Test
    void thunderboltSelectionKeepsThunderboltPlanner() {
        CraftingEngineRegistry.registerThunderbolt(testEngine(CraftingEngineRegistry.THUNDERBOLT));
        assertTrue(CraftingEngineSelection.select(CraftingEngineRegistry.THUNDERBOLT));
        assertEquals(CraftingEngineRegistry.THUNDERBOLT, CraftingEngineSelection.current());
        assertTrue(CraftingEngineSelection.usesThunderboltPlanner());
        CraftingEngineSelection.seed(CraftingEngineRegistry.NONE);
    }
}
