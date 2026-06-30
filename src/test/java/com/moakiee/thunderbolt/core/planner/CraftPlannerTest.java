package com.moakiee.thunderbolt.core.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CraftPlannerTest {

    private static long firingsOf(CraftPlan<String> plan, CraftPattern<String> p) {
        return plan.firings().getOrDefault(p, 0L);
    }

    @Test
    void singleChainCraftsAlongOnePath() {
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("A", 1, List.of(CraftInput.of("B", 1)))
                .pattern("B", 1, List.of(CraftInput.of("C", 1)))
                .stock("C", 10)
                .build();

        CraftPlan<String> plan = CraftPlanner.plan(g, "A", 3);

        assertTrue(plan.supported());
        assertTrue(plan.feasible());
        assertEquals(3L, plan.usedStock().get("C"));
        assertTrue(plan.missing().isEmpty());
    }

    /**
     * The diamond cascade A←(B|C), B←D, C←D, D←(E|F), ... is AE2's exponential worst case
     * (convergence node at depth i expanded 2^i times). The DAG planner must visit each item once.
     */
    @Test
    void diamondCascadeIsLinearNotExponential() {
        int diamonds = 20; // AE2 would expand ~2^20 ≈ 1e6 nodes for the deepest convergence point
        CraftGraph.Builder<String> b = CraftGraph.<String>builder();
        // levels: top single, then split pair, then converge single, ...
        // c0=A; pair(b1,c1); conv d1; pair; conv d2; ...
        String top = "I0";
        String prevConv = top;
        for (int i = 1; i <= diamonds; i++) {
            String left = "L" + i;
            String right = "R" + i;
            String conv = "I" + i;
            // prevConv made from left OR right (two patterns)
            b.pattern(prevConv, 1, List.of(CraftInput.of(left, 1)));
            b.pattern(prevConv, 1, List.of(CraftInput.of(right, 1)));
            // both left and right reduce to the same next convergence item
            b.pattern(left, 1, List.of(CraftInput.of(conv, 1)));
            b.pattern(right, 1, List.of(CraftInput.of(conv, 1)));
            prevConv = conv;
        }
        b.stock(prevConv, 1_000);
        CraftGraph<String> g = b.build();

        CraftPlan<String> plan = CraftPlanner.plan(g, top, 1);

        assertTrue(plan.supported());
        assertTrue(plan.feasible());
        // Reachable items = 1 + 3*diamonds (each diamond adds left,right,conv). Visited once each.
        assertTrue(plan.itemsProcessed() <= 1 + 3 * diamonds,
                "expected O(k) processing, got " + plan.itemsProcessed());
    }

    /** No scarcity metric: pick the recipe current stock can actually fulfill (iron, not diamond). */
    @Test
    void picksRecipeFeasibleUnderStock() {
        CraftPattern<String> viaDiamond = new CraftPattern<>("A", 1, List.of(CraftInput.of("diamond", 5)), "viaDiamond");
        CraftPattern<String> viaIron = new CraftPattern<>("A", 1, List.of(CraftInput.of("iron", 5)), "viaIron");
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern(viaDiamond) // first in preference order, but not enough diamonds
                .pattern(viaIron)
                .stock("diamond", 1)
                .stock("iron", 100)
                .build();

        CraftPlan<String> plan = CraftPlanner.plan(g, "A", 1);

        assertTrue(plan.feasible());
        assertEquals(0L, firingsOf(plan, viaDiamond));
        assertEquals(1L, firingsOf(plan, viaIron));
        assertEquals(5L, plan.usedStock().get("iron"));
    }

    @Test
    void reportsMissingWhenNoRecipeCanBeFulfilled() {
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("A", 1, List.of(CraftInput.of("diamond", 5)))
                .stock("diamond", 2)
                .build();

        CraftPlan<String> plan = CraftPlanner.plan(g, "A", 1);

        assertTrue(plan.supported());
        assertFalse(plan.feasible());
        // 1*A needs 5 diamonds, only 2 in stock -> 3 missing at the raw leaf.
        assertEquals(3L, plan.missing().get("diamond"));
    }

    /** Returned (container/in-pattern catalyst) input needs one seed batch, not amount*times. */
    /** On an infeasible unique tree, the plan still carries the partial firings + the missing leaf. */
    @Test
    void infeasibleStillProducesPartialPlanAndMissing() {
        CraftPattern<String> aFromB = new CraftPattern<>("A", 1, List.of(CraftInput.of("B", 1)), "A<-B");
        CraftPattern<String> bFromC = new CraftPattern<>("B", 1, List.of(CraftInput.of("C", 1)), "B<-C");
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern(aFromB)
                .pattern(bFromC)
                .stock("C", 5)
                .build();

        CraftPlan<String> plan = CraftPlanner.plan(g, "A", 10);

        assertTrue(plan.supported());
        assertFalse(plan.feasible());
        assertEquals(5L, plan.missing().get("C"));
        // Partial crafts are still emitted (matches AE2's optimistic simulation plan).
        assertEquals(10L, firingsOf(plan, aFromB));
        assertEquals(10L, firingsOf(plan, bFromC));
    }

    @Test
    void returnedInputCostsOneSeedNotPerCraft() {
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("filled", 1, List.of(CraftInput.of("water", 1), CraftInput.returned("bucket", 1)))
                .stock("water", 1000)
                .stock("bucket", 1)
                .build();

        CraftPlan<String> plan = CraftPlanner.plan(g, "filled", 100);

        assertTrue(plan.feasible(), "1 bucket should be reused 100 times");
        assertEquals(100L, plan.usedStock().get("water"));
        assertEquals(1L, plan.usedStock().get("bucket"));
    }

    /** Random multipliers + huge request: values grow geometrically but step count stays O(items). */
    @Test
    void randomAmountsLargeRequestStaysLinear() {
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("A", 1, List.of(CraftInput.of("B", 3)))
                .pattern("B", 1, List.of(CraftInput.of("C", 2)))
                .pattern("C", 1, List.of(CraftInput.of("D", 4)))
                .pattern("D", 1, List.of(CraftInput.of("G", 2)))
                .stock("G", 1_000_000_000L)
                .build();

        CraftPlan<String> plan = CraftPlanner.plan(g, "A", 1_000);

        assertTrue(plan.feasible());
        // need: A=1000 -> B=3000 -> C=6000 -> D=24000 -> G=48000
        assertEquals(48_000L, plan.usedStock().get("G"));
        // A,B,C,D are crafted and G is visited then served from stock: 5 items, each once.
        assertEquals(5, plan.itemsProcessed());
    }

    @Test
    void overflowSaturatesToMissingNotWraparound() {
        // Each level multiplies by 10; depth 25 -> 10^25, far beyond long.
        CraftGraph.Builder<String> b = CraftGraph.<String>builder();
        int depth = 25;
        for (int i = 0; i < depth; i++) {
            b.pattern("X" + i, 1, List.of(CraftInput.of("X" + (i + 1), 10)));
        }
        // X{depth} is a raw leaf with no stock.
        CraftGraph<String> g = b.build();

        CraftPlan<String> plan = CraftPlanner.plan(g, "X0", 1);

        assertTrue(plan.supported());
        assertFalse(plan.feasible());
        // The deep leaf demand saturated; reported as missing, never a wrapped/negative amount.
        long missingLeaf = plan.missing().getOrDefault("X" + depth, 0L);
        assertTrue(missingLeaf > 0, "saturated demand must surface as positive missing amount");
    }

    @Test
    void recursionFallsBackAsUnsupported() {
        // A -> B, B -> A : a cycle the fast path must decline.
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("A", 1, List.of(CraftInput.of("B", 1)))
                .pattern("B", 1, List.of(CraftInput.of("A", 1)))
                .build();

        CraftPlan<String> plan = CraftPlanner.plan(g, "A", 1);

        assertFalse(plan.supported());
    }

    @Test
    void usesStockBeforeCrafting() {
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("A", 1, List.of(CraftInput.of("B", 1)))
                .stock("A", 4)
                .stock("B", 100)
                .build();

        CraftPlan<String> plan = CraftPlanner.plan(g, "A", 10);

        assertTrue(plan.feasible());
        assertEquals(4L, plan.usedStock().get("A"));
        assertEquals(6L, plan.usedStock().get("B")); // only the remaining 6 are crafted
    }
}
