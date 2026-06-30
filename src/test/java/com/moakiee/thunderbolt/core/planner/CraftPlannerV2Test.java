package com.moakiee.thunderbolt.core.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class CraftPlannerV2Test {

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

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "A", 3);

        assertTrue(plan.supported());
        assertTrue(plan.feasible());
        assertEquals(3L, plan.usedStock().get("C"));
        assertTrue(plan.missing().isEmpty());
    }

    /** A byproduct of one craft satisfies a sibling demand from the shared pool, with no stock of it. */
    @Test
    void byproductFeedsSiblingDemand() {
        CraftPattern<String> t = new CraftPattern<>(
                "T", 1, List.of(CraftInput.of("main", 1), CraftInput.of("scrap", 1)), "T");
        CraftPattern<String> main = new CraftPattern<>(
                "main", 1, List.of(CraftInput.of("raw", 1)), List.of(CraftOutput.of("scrap", 1)), "main");
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern(t)
                .pattern(main)
                .stock("raw", 10)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "T", 1);

        assertTrue(plan.feasible(), "scrap should come from main's byproduct, not stock");
        assertEquals(1L, firingsOf(plan, t));
        assertEquals(1L, firingsOf(plan, main));
        assertEquals(1L, plan.usedStock().get("raw"));
        assertNull(plan.usedStock().get("scrap"));
        assertTrue(plan.missing().isEmpty());
    }

    /** Dynamic capacity selection: prefer the recipe current stock can actually fulfill. */
    @Test
    void picksRecipeFeasibleUnderStock() {
        CraftPattern<String> viaDiamond = new CraftPattern<>("A", 1, List.of(CraftInput.of("diamond", 5)), "viaDiamond");
        CraftPattern<String> viaIron = new CraftPattern<>("A", 1, List.of(CraftInput.of("iron", 5)), "viaIron");
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern(viaDiamond)
                .pattern(viaIron)
                .stock("diamond", 1)
                .stock("iron", 100)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "A", 1);

        assertTrue(plan.feasible());
        assertEquals(0L, firingsOf(plan, viaDiamond));
        assertEquals(1L, firingsOf(plan, viaIron));
        assertEquals(5L, plan.usedStock().get("iron"));
    }

    /**
     * Capacity is optimistic (it double-counts a shared input), so the highest-capacity recipe is
     * tried first and fails at runtime; bounded backtracking rolls it back and the alternative wins.
     */
    @Test
    void boundedBacktrackRecoversAlternative() {
        // r1 needs B+D, both made from a shared pool of 4 -> 3 A would need 6 shared (infeasible).
        CraftPattern<String> r1 = new CraftPattern<>(
                "A", 1, List.of(CraftInput.of("B", 1), CraftInput.of("D", 1)), "r1");
        CraftPattern<String> b = new CraftPattern<>("B", 1, List.of(CraftInput.of("shared", 1)), "B");
        CraftPattern<String> d = new CraftPattern<>("D", 1, List.of(CraftInput.of("shared", 1)), "D");
        // r2 needs iron, exactly enough for 3 A.
        CraftPattern<String> r2 = new CraftPattern<>("A", 1, List.of(CraftInput.of("iron", 1)), "r2");
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern(r1).pattern(r2).pattern(b).pattern(d)
                .stock("shared", 4)
                .stock("iron", 3)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "A", 3);

        assertTrue(plan.feasible(), "should backtrack from the shared recipe to the iron recipe");
        assertEquals(3L, firingsOf(plan, r2));
        assertEquals(0L, firingsOf(plan, r1));
        assertEquals(3L, plan.usedStock().get("iron"));
        assertNull(plan.usedStock().get("shared")); // the failed branch was fully rolled back
        assertTrue(plan.missing().isEmpty());
    }

    /** Returned (container/in-pattern catalyst) input needs one seed, not amount*times. */
    @Test
    void returnedInputCostsOneSeedNotPerCraft() {
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("filled", 1, List.of(CraftInput.of("water", 1), CraftInput.returned("bucket", 1)))
                .stock("water", 1000)
                .stock("bucket", 1)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "filled", 100);

        assertTrue(plan.feasible(), "1 bucket should be reused 100 times");
        assertEquals(100L, plan.usedStock().get("water"));
        assertEquals(1L, plan.usedStock().get("bucket"));
    }

    /**
     * Durability tool {@code 1·tool(4) + 1·B → 1·C + tool(3)}: the degradation chain is solved once
     * and reduced to the closed form {@code tools = ceil(times / uses)} — 100 crafts need ceil(100/4)
     * = 25 tools, not 100 (普通) nor 1 (无限催化剂). This is the "成环差分" reduction.
     */
    @Test
    void finiteUseToolBatchesByUses() {
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("C", 1, List.of(CraftInput.of("B", 1), CraftInput.finiteUse("tool", 1, 4)))
                .stock("B", 1000)
                .stock("tool", 25)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "C", 100);

        assertTrue(plan.feasible());
        assertEquals(100L, plan.usedStock().get("B"));
        assertEquals(25L, plan.usedStock().get("tool"), "ceil(100/4) tools, each surviving 4 firings");
        assertTrue(plan.missing().isEmpty());
    }

    /** One tool short of capacity: the chain cannot cover the last batch, surfacing the shortfall. */
    @Test
    void finiteUseToolShortfallIsReported() {
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("C", 1, List.of(CraftInput.of("B", 1), CraftInput.finiteUse("tool", 1, 4)))
                .stock("B", 1000)
                .stock("tool", 24) // covers only 96 firings
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "C", 100);

        assertFalse(plan.feasible());
        assertEquals(1L, plan.missing().get("tool"), "ceil(100/4)=25 needed, 24 on hand");
    }

    /** Closed form stays O(1) at scale: a million crafts resolve without per-firing iteration. */
    @Test
    void finiteUseToolScalesWithoutEnumeration() {
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("C", 1, List.of(CraftInput.of("B", 1), CraftInput.finiteUse("tool", 1, 1000)))
                .stock("B", 5_000_000)
                .stock("tool", 1000)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "C", 1_000_000);

        assertTrue(plan.feasible());
        assertEquals(1_000_000L, plan.usedStock().get("B"));
        assertEquals(1000L, plan.usedStock().get("tool"));
    }

    /**
     * Craftable durability tool, modeled as the adapter does: the tool is a "use" currency, one craft
     * yields {@code n} uses (output amount n). Existing (partial) uses in stock are spent first, then
     * {@code ceil(shortfall / n)} fresh tools are crafted — "按链长×数量" preferring stock before craft.
     */
    @Test
    void craftableToolYieldsNUsesPerCraftStockFirst() {
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("C", 1, List.of(CraftInput.of("B", 1), CraftInput.of("use", 1)))
                .pattern("use", 4, List.of(CraftInput.of("ingot", 3))) // 1 craft = 1 full tool = 4 uses
                .stock("B", 1000)
                .stock("use", 2)   // a half-spent tool already on hand: 2 uses
                .stock("ingot", 100)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "C", 10);

        assertTrue(plan.feasible());
        assertEquals(10L, plan.usedStock().get("B"));
        assertEquals(2L, plan.usedStock().get("use"), "spend the 2 stocked uses before crafting");
        assertEquals(6L, plan.usedStock().get("ingot"), "ceil((10-2)/4)=2 tools crafted -> 6 ingot");
        assertTrue(plan.missing().isEmpty());
    }

    /**
     * End-to-end durability: the chain is <b>built step by step</b> via {@link DurabilityChain} (exactly
     * as the adapter walks getRemainingKey — n is derived, not given), then fed to the planner as the
     * adapter does (carrier = full tool, stock = aggregate uses, craft output scaled by n). Three
     * random-durability tools sit in stock; requests run up to 1e18 with max durability up to 8192.
     * Verifies the closed form crafts exactly the right number of fresh tools, that the leftover
     * durability handed back is a single valid partial tool, and that the degraded-first translation
     * draws concrete tools covering exactly what was spent from stock.
     */
    @Test
    void craftableToolWithRandomStockAndHugeAmountsBalancesDurability() {
        Random rnd = new Random(20260630L);
        for (int iter = 0; iter < 2000; iter++) {
            long maxDur = 1 + rnd.nextInt(8192);                 // D ∈ [1, 8192]
            // 3 tools with random remaining durability r ∈ [1, D]; a tool with r left sits at link D-r.
            long[] rem = {
                1 + (long) (rnd.nextDouble() * maxDur),
                1 + (long) (rnd.nextDouble() * maxDur),
                1 + (long) (rnd.nextDouble() * maxDur),
            };
            Map<String, Long> toolStock = new HashMap<>();
            for (long r : rem) {
                toolStock.merge("t" + (maxDur - r), 1L, Long::sum);
            }
            Function<String, String> degrade = k -> {
                long dd = Long.parseLong(k.substring(1));
                return dd + 1 < maxDur ? "t" + (dd + 1) : null;
            };

            DurabilityChain<String> chain =
                    DurabilityChain.build("t0", degrade, k -> toolStock.getOrDefault(k, 0L), 8192);
            // d==1 tools have no chain (single use); skip those degenerate draws for this end-to-end case.
            if (chain == null) {
                continue;
            }
            assertEquals(maxDur, chain.n(), "n derived from chain length");
            String use = chain.carrier();                        // = "t0", the full tool key
            long stockUses = chain.totalUses();
            assertEquals(rem[0] + rem[1] + rem[2], stockUses, "Σ链长×数量");

            long want = rnd.nextBoolean()
                    ? 1 + rnd.nextInt(500)                       // small: may stay within stock
                    : 1 + (long) (rnd.nextDouble() * 1e18);      // huge: forces crafting, ≤ 1e18

            CraftPattern<String> useCraft = new CraftPattern<>(
                    use, maxDur, List.of(CraftInput.of("ingot", 1)), "useCraft"); // 1 craft = 1 full tool = D uses
            CraftPattern<String> cCraft = new CraftPattern<>(
                    "C", 1, List.of(CraftInput.of("B", 1), CraftInput.of(use, 1)), "cCraft");
            CraftGraph<String> g = CraftGraph.<String>builder()
                    .pattern(cCraft)
                    .pattern(useCraft)
                    .stock(use, stockUses)   // carrier stock = aggregate uses (what the adapter sets)
                    .stock("B", want)        // enough raw for every C
                    .stock("ingot", want)    // enough raw for every fresh tool (tools ≤ want)
                    .build();

            CraftPlan<String> plan = CraftPlannerV2.plan(g, "C", want);

            long shortfall = Math.max(0, want - stockUses);
            long tools = shortfall == 0 ? 0 : (shortfall + maxDur - 1) / maxDur; // ceil(shortfall / D)
            long fromStock = Math.min(want, stockUses);
            long leftover = stockUses + tools * maxDur - want;   // durability returned to the network

            String msg = "iter=" + iter + " D=" + maxDur + " stock=" + stockUses + " want=" + want;
            assertTrue(plan.feasible(), msg);
            assertTrue(plan.missing().isEmpty(), msg);
            assertEquals(want, plan.usedStock().get("B"), msg);
            assertEquals(fromStock, plan.usedStock().getOrDefault(use, 0L), msg);
            assertEquals(tools, firingsOf(plan, useCraft), msg);
            assertEquals(tools, plan.usedStock().getOrDefault("ingot", 0L), msg);
            assertTrue(leftover >= 0, msg + " leftover=" + leftover);
            if (tools > 0) {
                assertTrue(leftover < maxDur, msg + " leftover=" + leftover); // one partial tool, not a whole wasted one
            }

            // Degraded-first translation of the uses actually spent from stock draws real tools that
            // cover exactly that demand (within one partial tool) — what the adapter writes to usedItems.
            Map<String, Long> drawn = new HashMap<>();
            chain.chargeFromStock(fromStock, (k, c) -> drawn.merge(k, c, Long::sum));
            long covered = 0;
            for (Map.Entry<String, Long> e : drawn.entrySet()) {
                long idx = Long.parseLong(e.getKey().substring(1));
                covered += (maxDur - idx) * e.getValue();
                assertTrue(e.getValue() <= toolStock.get(e.getKey()), msg + " over-draw @" + e.getKey());
            }
            assertTrue(covered >= fromStock && covered - fromStock < maxDur, msg + " covered=" + covered);
        }
    }

    @Test
    void reportsMissingWhenNoRecipeCanBeFulfilled() {
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("A", 1, List.of(CraftInput.of("diamond", 5)))
                .stock("diamond", 2)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "A", 1);

        assertTrue(plan.supported());
        assertFalse(plan.feasible());
        assertEquals(3L, plan.missing().get("diamond"));
    }

    /** The diamond cascade must be visited near-linearly, never the exponential 2^depth. */
    @Test
    void diamondCascadeIsLinearNotExponential() {
        int diamonds = 20;
        CraftGraph.Builder<String> b = CraftGraph.<String>builder();
        String prevConv = "I0";
        for (int i = 1; i <= diamonds; i++) {
            String left = "L" + i;
            String right = "R" + i;
            String conv = "I" + i;
            b.pattern(prevConv, 1, List.of(CraftInput.of(left, 1)));
            b.pattern(prevConv, 1, List.of(CraftInput.of(right, 1)));
            b.pattern(left, 1, List.of(CraftInput.of(conv, 1)));
            b.pattern(right, 1, List.of(CraftInput.of(conv, 1)));
            prevConv = conv;
        }
        b.stock(prevConv, 1_000);

        CraftPlan<String> plan = CraftPlannerV2.plan(b.build(), "I0", 1);

        assertTrue(plan.supported());
        assertTrue(plan.feasible());
        assertTrue(plan.itemsProcessed() <= 1 + 3 * diamonds,
                "expected ~O(k) processing, got " + plan.itemsProcessed());
    }

    @Test
    void overflowSaturatesToMissingNotWraparound() {
        CraftGraph.Builder<String> b = CraftGraph.<String>builder();
        int depth = 25;
        for (int i = 0; i < depth; i++) {
            b.pattern("X" + i, 1, List.of(CraftInput.of("X" + (i + 1), 10)));
        }

        CraftPlan<String> plan = CraftPlannerV2.plan(b.build(), "X0", 1);

        assertTrue(plan.supported());
        assertFalse(plan.feasible());
        assertTrue(plan.missing().getOrDefault("X" + depth, 0L) > 0,
                "saturated demand must surface as positive missing amount");
    }

    @Test
    void pureCycleIsBrokenTowardTargetNotDeclined() {
        // A -> B, B -> A : the back-edge from B to A (the target, being made) is cut, so B becomes a
        // leaf. With no stock the plan is supported but infeasible (missing B), never an infinite loop.
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("A", 1, List.of(CraftInput.of("B", 1)))
                .pattern("B", 1, List.of(CraftInput.of("A", 1)))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "A", 1);
        assertTrue(plan.supported());
        assertFalse(plan.feasible());
        assertEquals(1L, plan.missing().get("B"));
    }

    @Test
    void compressCycleMakesBlocksFromIngotStock() {
        // 9 ingot -> 1 block (compress) and 1 block -> 9 ingot (decompress). Target = block, stock =
        // ingots: the decompress recipe is the back-edge and gets cut, so blocks are made from ingots.
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("block", 1, List.of(CraftInput.of("ingot", 9)))
                .pattern("ingot", 9, List.of(CraftInput.of("block", 1)))
                .stock("ingot", 64)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "block", 5);
        assertTrue(plan.supported());
        assertTrue(plan.feasible());
        assertEquals(45L, plan.usedStock().get("ingot")); // 5 blocks * 9 ingots
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void containerIsConsumedAndRefilledFromItsOwnLeftover() {
        // Container model the adapter builds for a filled bucket: making P consumes one full bucket and
        // hands back an empty one (byproduct); the empty + water refills a full bucket. With a single
        // seed bucket + water, a batch of P should reuse the returned empties instead of needing N fulls.
        CraftPattern<String> makeP = new CraftPattern<>(
                "P", 1, List.of(CraftInput.of("full_bucket", 1)),
                List.of(CraftOutput.of("empty_bucket", 1)), "makeP");
        CraftPattern<String> refill = new CraftPattern<>(
                "full_bucket", 1, List.of(CraftInput.of("empty_bucket", 1), CraftInput.of("water", 1)), "refill");
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern(makeP)
                .pattern(refill)
                .stock("full_bucket", 1)
                .stock("water", 1000)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "P", 5);

        assertTrue(plan.supported());
        assertTrue(plan.feasible());
        assertEquals(1L, plan.usedStock().get("full_bucket")); // one seed bucket, reused
        assertEquals(4L, plan.usedStock().get("water"));       // 4 refills from the 5 returned empties
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void decompressCycleMakesIngotsFromBlockStock() {
        // Same pair, opposite direction: target = ingot, stock = blocks. Now the compress recipe is the
        // back-edge that gets cut, so ingots come from decompressing blocks.
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("block", 1, List.of(CraftInput.of("ingot", 9)))
                .pattern("ingot", 9, List.of(CraftInput.of("block", 1)))
                .stock("block", 10)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "ingot", 20);
        assertTrue(plan.supported());
        assertTrue(plan.feasible());
        assertEquals(3L, plan.usedStock().get("block")); // ceil(20/9) = 3 blocks -> 27 ingots
        assertTrue(plan.missing().isEmpty());
    }

    /**
     * A fully infeasible tree where every item has two recipes would explode to 2^depth without the
     * per-node visit cap. With the cap, work stays bounded and it still terminates with a missing report.
     */
    @Test
    void boundedSearchDoesNotBlowUp() {
        int depth = 30;
        CraftGraph.Builder<String> b = CraftGraph.<String>builder();
        for (int i = 0; i < depth; i++) {
            b.pattern(new CraftPattern<>("A" + i, 1, List.of(CraftInput.of("A" + (i + 1), 1)), "A" + i + "_x"));
            b.pattern(new CraftPattern<>("A" + i, 1, List.of(CraftInput.of("A" + (i + 1), 1)), "A" + i + "_y"));
        }
        // A{depth} is a raw leaf with no stock -> the whole thing is infeasible.

        CraftPlan<String> plan = CraftPlannerV2.plan(b.build(), "A0", 1);

        assertTrue(plan.supported());
        assertFalse(plan.feasible());
        assertTrue(plan.itemsProcessed() < 50_000,
                "per-node K cap must keep work bounded, got " + plan.itemsProcessed());
    }

    /**
     * v2-memo-deps: a shared deep chain feeds many parents. The linear backbone resolves each item
     * exactly once, so work is O(parents + depth), NOT parents*depth (which is what re-expanding the
     * shared subgraph per parent would cost).
     */
    @Test
    void sharedSubgraphIsResolvedOncePerItem() {
        int parents = 50;
        int depth = 50;
        CraftGraph.Builder<String> b = CraftGraph.<String>builder();

        java.util.List<CraftInput<String>> tInputs = new java.util.ArrayList<>();
        for (int i = 0; i < parents; i++) {
            tInputs.add(CraftInput.of("P" + i, 1));
            b.pattern("P" + i, 1, List.of(CraftInput.of("common", 1)));
        }
        b.pattern(new CraftPattern<>("T", 1, tInputs, "T"));
        b.pattern("common", 1, List.of(CraftInput.of("c0", 1)));
        for (int i = 0; i < depth; i++) {
            b.pattern("c" + i, 1, List.of(CraftInput.of("c" + (i + 1), 1)));
        }
        b.stock("c" + depth, 1_000);

        CraftPlan<String> plan = CraftPlannerV2.plan(b.build(), "T", 1);

        assertTrue(plan.feasible());
        assertTrue(plan.itemsProcessed() <= parents + depth + 10,
                "shared chain must be visited once, not per-parent; got " + plan.itemsProcessed());
    }

    /**
     * v2-lazy-deduct: a single linear pass splits demand across two recipes by current remaining
     * capacity (reservation shrinks capRemaining with O(1) deduction), draining the scarcer input
     * only as far as it goes and routing the rest to the other — no backtracking needed.
     */
    @Test
    void linearPassSplitsAcrossRecipesByRemainingCapacity() {
        CraftPattern<String> viaY = new CraftPattern<>("A", 1, List.of(CraftInput.of("y", 1)), "viaY");
        CraftPattern<String> viaX = new CraftPattern<>("A", 1, List.of(CraftInput.of("x", 1)), "viaX");
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern(viaY)
                .pattern(viaX)
                .stock("y", 6)
                .stock("x", 4)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "A", 10);

        assertTrue(plan.feasible());
        assertEquals(6L, firingsOf(plan, viaY));
        assertEquals(4L, firingsOf(plan, viaX));
        assertEquals(6L, plan.usedStock().get("y"));
        assertEquals(4L, plan.usedStock().get("x"));
        assertTrue(plan.missing().isEmpty());
    }

    /**
     * Property test: thousands of randomly generated multi-layer DAGs with random output/input ratios,
     * random stock and random byproducts. Each plan is checked by an INDEPENDENT mass-balance oracle
     * (re-derived from the reported firings + usedStock, never from planner internals):
     * <ul>
     *   <li>no stock is ever over-drawn ({@code usedStock[k] <= stock[k]});</li>
     *   <li>flow conservation holds at every item: {@code inflow + missing >= outflow}. On a DAG this
     *       is exactly the condition for the firing schedule to be executable, so a {@code feasible}
     *       plan that passes this is genuinely valid (no false positives) and a {@code missing} report
     *       is numerically consistent.</li>
     * </ul>
     */
    @Test
    void randomNestedGraphsAreSoundUnderMassBalance() {
        for (long seed = 0; seed < 3_000; seed++) {
            RandomGraph rg = buildRandomGraph(seed);
            CraftPlan<String> plan = CraftPlannerV2.plan(rg.graph, rg.target, rg.amount);

            assertTrue(plan.supported(), "acyclic graph must be supported, seed=" + seed);
            assertMassBalance(plan, rg, seed);
        }
    }

    private static void assertMassBalance(CraftPlan<String> plan, RandomGraph rg, long seed) {
        Map<String, Long> inflow = new HashMap<>();
        Map<String, Long> outflow = new HashMap<>();

        for (Map.Entry<String, Long> e : plan.usedStock().entrySet()) {
            long stock = rg.stock.getOrDefault(e.getKey(), 0L);
            assertTrue(e.getValue() <= stock,
                    "over-drew stock " + e.getKey() + " used=" + e.getValue() + " have=" + stock + " seed=" + seed);
            inflow.merge(e.getKey(), e.getValue(), Long::sum);
        }
        for (CraftPattern<String> p : rg.patterns) {
            long f = plan.firings().getOrDefault(p, 0L);
            if (f <= 0) {
                continue;
            }
            inflow.merge(p.output(), f * p.outputAmount(), Long::sum);
            for (CraftOutput<String> out : p.byproducts()) {
                inflow.merge(out.key(), f * out.amount(), Long::sum);
            }
            for (CraftInput<String> in : p.inputs()) {
                if (!in.returned()) {
                    outflow.merge(in.key(), f * in.amount(), Long::sum);
                }
            }
        }
        outflow.merge(rg.target, rg.amount, Long::sum);

        Set<String> keys = new LinkedHashSet<>(inflow.keySet());
        keys.addAll(outflow.keySet());
        for (String k : keys) {
            long supply = inflow.getOrDefault(k, 0L) + plan.missing().getOrDefault(k, 0L);
            long demand = outflow.getOrDefault(k, 0L);
            assertTrue(supply >= demand,
                    "conservation broken for " + k + ": inflow+missing=" + supply + " < outflow=" + demand
                            + " (feasible=" + plan.feasible() + ", seed=" + seed + ")");
        }
        if (plan.feasible()) {
            assertTrue(plan.missing().isEmpty(), "feasible plan must report no missing, seed=" + seed);
        }
    }

    private record RandomGraph(CraftGraph<String> graph, List<CraftPattern<String>> patterns,
                               Map<String, Long> stock, String target, long amount) {
    }

    /**
     * Builds a random acyclic crafting graph: item {@code i} may only consume items {@code j > i}, so
     * the graph is a DAG by construction (no recursion). Amounts are kept small so the multiplicative
     * demand along chains stays comfortably within {@code long} for the oracle's exact arithmetic.
     */
    private static RandomGraph buildRandomGraph(long seed) {
        Random rnd = new Random(seed);
        int n = 8 + rnd.nextInt(7); // 8..14 items
        CraftGraph.Builder<String> b = CraftGraph.<String>builder();
        List<CraftPattern<String>> patterns = new ArrayList<>();
        Map<String, Long> stock = new HashMap<>();

        for (int i = 0; i < n; i++) {
            String ki = "i" + i;
            boolean leaf = i >= n - 2 || (i > 0 && rnd.nextInt(100) < 30);
            if (leaf) {
                long s = rnd.nextInt(40); // may be 0 -> contributes to an infeasible branch
                if (s > 0) {
                    stock.put(ki, s);
                    b.stock(ki, s);
                }
                continue;
            }
            if (rnd.nextInt(100) < 20) { // a craftable item that also has some stock
                long s = 1 + rnd.nextInt(8);
                stock.merge(ki, s, Long::sum);
                b.stock(ki, s);
            }
            int recipes = 1 + rnd.nextInt(2); // 1..2 competing recipes
            for (int r = 0; r < recipes; r++) {
                int outAmt = 1 + rnd.nextInt(3);
                int numIn = 1 + rnd.nextInt(3);
                Set<String> chosen = new LinkedHashSet<>();
                List<CraftInput<String>> inputs = new ArrayList<>();
                for (int c = 0; c < numIn; c++) {
                    int j = i + 1 + rnd.nextInt(n - i - 1);
                    if (chosen.add("i" + j)) {
                        inputs.add(CraftInput.of("i" + j, 1 + rnd.nextInt(3)));
                    }
                }
                List<CraftOutput<String>> byp = new ArrayList<>();
                if (rnd.nextInt(100) < 25 && i + 1 < n) {
                    int j = i + 1 + rnd.nextInt(n - i - 1);
                    byp.add(CraftOutput.of("i" + j, 1 + rnd.nextInt(2)));
                }
                CraftPattern<String> p = new CraftPattern<>(ki, outAmt, inputs, byp, "p" + i + "_" + r);
                patterns.add(p);
                b.pattern(p);
            }
        }
        long amount = 1 + rnd.nextInt(4);
        return new RandomGraph(b.build(), patterns, stock, "i0", amount);
    }

    /**
     * Completeness via reverse construction: build a graph that is feasible <em>by construction</em>
     * (a witness plan exists with exactly-provisioned stock), then add real <em>trap</em> recipes that
     * the optimistic capacity estimate prefers but that are actually infeasible (a diamond that
     * double-counts one shared unit). The greedy linear pass takes the bait, fails, and the bounded
     * search must roll back and recover the witness.
     *
     * <p>The planner MUST come back feasible across thousands of random graphs, and crucially
     * {@link CraftPlan#budgetExhausted()} must stay {@code false}: normal random contention is
     * recovered in a couple of backtracks and never approaches the per-node cap. That cap is only a
     * safety net for adversarial inputs, not something realistic graphs lean on.
     */
    @Test
    void reverseConstructedGraphsAreAlwaysCraftable() {
        for (long seed = 0; seed < 3_000; seed++) {
            RandomGraph rg = buildFeasibleGraph(seed);
            CraftPlan<String> plan = CraftPlannerV2.plan(rg.graph, rg.target, rg.amount);

            assertTrue(plan.supported(), "seed=" + seed);
            assertTrue(plan.feasible(),
                    "reverse-constructed graph must be craftable but planner reported missing="
                            + plan.missing() + " seed=" + seed);
            assertTrue(plan.missing().isEmpty(), "seed=" + seed);
            assertFalse(plan.budgetExhausted(),
                    "bounded search recovered, so the per-node cap must not have been hit, seed=" + seed);
            assertMassBalance(plan, rg, seed); // soundness on top of completeness
        }
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    private static RandomGraph buildFeasibleGraph(long seed) {
        Random rnd = new Random(seed);
        int m = 6 + rnd.nextInt(8); // 6..13 witness items
        long amount = 1 + rnd.nextInt(4);

        boolean[] leaf = new boolean[m];
        List<CraftPattern<String>> witness = new ArrayList<>();
        CraftPattern<?>[] witnessOf = new CraftPattern<?>[m];
        // 1) decide structure + witness recipe per craftable item (inputs strictly deeper => DAG).
        for (int i = 0; i < m; i++) {
            leaf[i] = i >= m - 2 || (i > 0 && rnd.nextInt(100) < 25);
            if (leaf[i]) {
                continue;
            }
            int outAmt = 1 + rnd.nextInt(3);
            int numIn = 1 + rnd.nextInt(3);
            Set<String> chosen = new LinkedHashSet<>();
            List<CraftInput<String>> inputs = new ArrayList<>();
            for (int c = 0; c < numIn; c++) {
                int j = i + 1 + rnd.nextInt(m - i - 1);
                if (chosen.add("w" + j)) {
                    inputs.add(CraftInput.of("w" + j, 1 + rnd.nextInt(3)));
                }
            }
            List<CraftOutput<String>> byp = new ArrayList<>();
            if (rnd.nextInt(100) < 30) { // a benign byproduct: extra supply of some deeper item
                int j = i + 1 + rnd.nextInt(m - i - 1);
                byp.add(CraftOutput.of("w" + j, 1 + rnd.nextInt(2)));
            }
            CraftPattern<String> p = new CraftPattern<>("w" + i, outAmt, inputs, byp, "w" + i);
            witnessOf[i] = p;
            witness.add(p);
        }

        // 2) propagate demand top-down (index order is topological) to size the leaf stock exactly.
        long[] required = new long[m];
        required[0] = amount;
        for (int i = 0; i < m; i++) {
            if (required[i] <= 0 || leaf[i]) {
                continue;
            }
            @SuppressWarnings("unchecked")
            CraftPattern<String> p = (CraftPattern<String>) witnessOf[i];
            long firings = ceilDiv(required[i], p.outputAmount());
            for (CraftInput<String> in : p.inputs()) {
                int j = Integer.parseInt(in.key().substring(1));
                required[j] += firings * in.amount();
            }
        }

        // 3) assemble the graph: witness recipes + exactly-enough leaf stock.
        CraftGraph.Builder<String> b = CraftGraph.<String>builder();
        List<CraftPattern<String>> all = new ArrayList<>(witness);
        Map<String, Long> stock = new HashMap<>();
        for (CraftPattern<String> p : witness) {
            b.pattern(p);
        }
        for (int i = 0; i < m; i++) {
            if (leaf[i] && required[i] > 0) {
                stock.put("w" + i, required[i]);
                b.stock("w" + i, required[i]);
            }
        }

        // 4) traps: on demand-path items, add a recipe the optimistic capacity *prefers* (it can
        //    "make" more than the witness) but that is actually infeasible — a self-contained diamond
        //    t1 <- s and t2 <- s sharing a single unit s, so making both t1 AND t2 is impossible. The
        //    greedy pass commits the trap, fails, and the bounded search must backtrack to the witness
        //    (whose dedicated stock is restored on rollback). Provably recoverable, in 2 visits/node.
        for (int i = 0; i < m; i++) {
            if (leaf[i] || required[i] <= 0 || rnd.nextInt(100) >= 50) {
                continue;
            }
            String s = "s" + i;
            String t1 = "t1_" + i;
            String t2 = "t2_" + i;
            b.stock(s, 1);
            stock.put(s, 1L);
            CraftPattern<String> tp1 = new CraftPattern<>(t1, 1, List.of(CraftInput.of(s, 1)), "tp1_" + i);
            CraftPattern<String> tp2 = new CraftPattern<>(t2, 1, List.of(CraftInput.of(s, 1)), "tp2_" + i);
            // outputAmount > required[i] => trap's optimistic capacity beats the witness, so it's tried first.
            CraftPattern<String> trap = new CraftPattern<>(
                    "w" + i, required[i] + 1, List.of(CraftInput.of(t1, 1), CraftInput.of(t2, 1)), "trap" + i);
            all.add(tp1);
            all.add(tp2);
            all.add(trap);
            b.pattern(tp1);
            b.pattern(tp2);
            b.pattern(trap);
        }

        return new RandomGraph(b.build(), all, stock, "w0", amount);
    }
}
