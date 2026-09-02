package com.moakiee.thunderbolt.ae2.crafting;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import org.junit.jupiter.api.Test;

class Ae2CraftingTreeSummaryBridgeTest {
    @Test
    void attachesCurrentAe2CraftingTreePayload() {
        var summary = new CurrentSummary();
        var plan = plan();

        assertTrue(Ae2CraftingTreeSummaryBridge.attachSupportedIntegration(
                summary, plan, getClass().getClassLoader()));
        assertSame(plan, summary.getJob().sourcePlan());
    }

    private static CraftingPlan plan() {
        return new CraftingPlan(
                null, 1, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
    }

    private static final class CurrentSummary
            implements com.neuvillette.ae2ct.api.ICraftingPlanSummary {
        private com.neuvillette.ae2ct.api.RecipeHelper job;

        @Override
        public com.neuvillette.ae2ct.api.RecipeHelper getJob() {
            return job;
        }

        @Override
        public void setJob(com.neuvillette.ae2ct.api.RecipeHelper job) {
            this.job = job;
        }
    }
}
