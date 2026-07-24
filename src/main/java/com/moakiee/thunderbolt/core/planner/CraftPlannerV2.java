package com.moakiee.thunderbolt.core.planner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v2 autocrafting planner: dynamic-capacity greedy over a shared mutable pool, with byproduct reuse
 * and bounded backtracking for contended choices.
 *
 * <p>This is the evolution of {@link CraftPlanner} (v1, closed-form two-pass). It keeps v1's strengths
 * — quantity-independent batching ({@code ceil} arithmetic), {@code returned} (container/catalyst)
 * inputs in closed form, saturating arithmetic — and adds:
 *
 * <ul>
 *   <li><b>In-engine cycle breaking ("去头尾")</b>: instead of declining when the recipe graph has a
 *       cycle, a DFS from the target drops back-edges, keeping the recipe direction toward the target
 *       and cutting the reverse. A compress/decompress pair (1 block ⇄ 9 ingots) is planned directly;
 *       the reverse side resolves from stock/missing. Cuts only remove options, so feasibility is never
 *       overstated (no false positives).</li>
 *   <li><b>Shared pool + byproducts</b>: stock and crafting byproducts live in one mutable pool;
 *       demand draws byproducts first, then stock, then crafts. Multi-output patterns are supported
 *       (a pattern's extra outputs feed sibling demands).</li>
 *   <li><b>Dynamic-capacity greedy</b>: among an item's recipes, the one with the highest current
 *       capacity ({@code stock + craftable}) is preferred, so the planner naturally balances onto the
 *       recipe current stock can actually fulfill (no scarcity metric needed).</li>
 *   <li><b>Bounded backtracking</b>: contended items (more than one recipe) are searched in
 *       capacity order with a {@code trail} for commit/rollback. Each node has a visit cap {@code K};
 *       after that, that node permanently freezes to its highest-capacity greedy recipe while parents,
 *       siblings and descendants retain their own independent search budgets. Exhaustion can therefore
 *       make one node choose a suboptimal route, but never invalidates the whole calculation. Failed
 *       speculative subtrees are memoized only for the exact node, amount, depth and rollback-restored
 *       availability state, preventing repeated proof work without reusing a stale inventory result.</li>
 * </ul>
 *
 * <p><b>Soundness (no false positives):</b> the pool is never overdrawn (a draw is capped by what is
 * actually present), so a plan reports {@link CraftPlan#feasible() feasible} only when every demand was
 * met from stock or from a craft whose own inputs were met. Shortfalls always surface in
 * {@link CraftPlan#missing()}.
 */
public final class CraftPlannerV2<K> {

    /**
     * Default per-node visit cap. It is a safety net, not the workhorse: normal graphs are resolved by
     * the linear backbone or recover via a handful of backtracks, never approaching this bound. 256
     * lets a hot shared node participate in several rounds of parent/fuzzy backtracking before only
     * that node freezes to its greedy choice.
     */
    public static final int DEFAULT_VISIT_CAP = 256;

    /**
     * Maximum number of alternate roots tried for a proven conservative conversion SCC. This is a
     * fixed bound, so cycle orientation remains linear in graph size rather than enumerating cuts.
     */
    static final int MAX_CONVERSION_ORIENTATION_RETRIES = 4;

    /**
     * Stack-overflow safety net for the bounded fallback search. {@link #obtain} recurses once per
     * crafting edge along a single root-to-leaf path ({@code obtain → fire → obtain}), so its stack depth
     * equals the depth of the (acyclic) recipe DAG. The clean linear backbone ({@link #linearPass}) is
     * fully iterative and resolves every feasible, non-contended request without recursing — the
     * recursion is entered only when that backbone reports infeasible or hits contention. To keep a
     * pathologically deep recipe chain from overflowing the calculating thread's stack, descent past this
     * many levels degrades only that branch to "missing" (Policy A), allowing its parent to try another
     * route. 256 levels (~512 stack frames with the paired {@code fire}) is far
     * deeper than any real Minecraft recipe chain yet safe on a default thread stack; overridable via
     * {@code -Dthunderbolt.maxCraftDepth} for unusual {@code -Xss} setups.
     */
    public static final int MAX_OBTAIN_DEPTH =
            Math.max(16, Integer.getInteger("thunderbolt.maxCraftDepth", 256));

    private final CraftGraph<K> graph;
    private final int visitCap;
    private final Set<K> cutOutputs = new LinkedHashSet<>();
    private final Map<CraftPattern<K>, Set<K>> suppressedPositiveFeedbackOutputs =
            new IdentityHashMap<>();
    private final Map<K, Long> reservedSelfSeeds = new HashMap<>();
    private boolean requiresSeedOrderedPlanning;

    // Current recursion depth of the bounded fallback search (obtain/fire). Guards against stack overflow
    // on degenerate deep chains; see MAX_OBTAIN_DEPTH. Not part of the rolled-back planning state.
    private int depth;

    private final Map<K, List<CraftPattern<K>>> patternsByOutput = new HashMap<>();
    // Canonical, stock-independent material transformation for patterns whose whole downstream tree
    // can be proven simple and deterministic. Equal ids let one obtain() call search an equivalent
    // branch once instead of reopening the same dependency tree under a different intermediate key.
    private final Map<CraftPattern<K>, Integer> materialFootprintByPattern = new IdentityHashMap<>();
    private Map<K, Long> capacity;

    // Mutable planning state (all writes go through the trail so a branch can be rolled back).
    private final Map<K, Long> bpPool = new HashMap<>();      // byproduct / surplus supply
    private final Map<K, Long> stockLeft = new HashMap<>();   // remaining inventory snapshot
    private final Map<K, Long> usedStock = new HashMap<>();   // drawn from inventory
    /** Route-private host borrows that may still be reassigned by the global variant matcher. */
    private final Map<ReusableStockRouteKey<K>, Long> reusableBorrowedDemand = new HashMap<>();
    /** Returned non-exact variants, reusable only by the route/consumer that owns them. */
    private final Map<ReusableStockRouteKey<K>, Long> reusablePrivatePool = new HashMap<>();
    /** Returned exact variants, safely reusable by every route in the logical shared pool. */
    private final Map<ReusableStockKey<K>, Long> reusablePool = new HashMap<>();
    /** Exact host allocations already exposed as shared credit; these can no longer be rematched. */
    private final Map<ReusableStockUsageKey<K>, Long> pinnedExactReusableStock = new HashMap<>();
    private final Map<ReusableStockUsageKey<K>, Long> usedReusableStock = new HashMap<>();
    private final Map<K, Long> missing = new HashMap<>();     // unmet at raw leaves
    private final Map<K, Long> grossDemand = new HashMap<>(); // pre-extraction request totals (bytes)
    private final Map<CraftPattern<K>, Long> firings = new IdentityHashMap<>();

    // Node-local search state is monotonic: rollback restores inventory, not knowledge already learned.
    private final Map<K, Integer> visit = new HashMap<>();
    private final Map<K, CraftPattern<K>> frozenGreedyPattern = new HashMap<>();
    // Exact failure memo for speculative calls. availabilityState is restored with trail rollback,
    // so a proof is reused only when node, amount and every availability-affecting map are identical.
    private final Set<SearchFailure<K>> failedSpeculativeSearches = new HashSet<>();
    private final Deque<Runnable> trail = new ArrayDeque<>();
    private long availabilityState;
    private long nextAvailabilityState;
    private int processed;

    // Running sum of all unmet (missing) amounts; trail-restored. A search branch is accepted iff it
    // introduces no new missing, so the decision survives nested single-recipe commits.
    private long missingTotal;

    private CraftPlannerV2(CraftGraph<K> graph, int visitCap) {
        this.graph = graph;
        this.visitCap = Math.max(1, visitCap);
    }

    public static <K> CraftPlan<K> plan(CraftGraph<K> graph, K target, long amount) {
        return plan(graph, target, amount, DEFAULT_VISIT_CAP);
    }

    public static <K> CraftPlan<K> plan(CraftGraph<K> graph, K target, long amount, int visitCap) {
        if (amount <= 0) {
            return new CraftPlan<>(true, true, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0, false);
        }
        CycleAnalysis<K> cycleAnalysis = CycleAnalysis.analyze(graph, target);
        CraftPlannerV2<K> firstPlanner = new CraftPlannerV2<>(graph, visitCap);
        CraftPlan<K> first = firstPlanner.run(target, amount, List.of());
        if (first.feasible()
                || firstPlanner.cutOutputs.stream().noneMatch(cycleAnalysis::mayReorient)) {
            return first;
        }

        int retries = 0;
        for (K cutOutput : firstPlanner.cutOutputs) {
            if (retries >= MAX_CONVERSION_ORIENTATION_RETRIES) break;
            if (!first.missing().containsKey(cutOutput) || !cycleAnalysis.mayReorient(cutOutput)) {
                continue;
            }
            retries++;
            CraftPlan<K> retry = new CraftPlannerV2<>(graph, visitCap)
                    .run(target, amount, List.of(cutOutput));
            if (retry.feasible()) return retry;
        }
        return first;
    }

    private CraftPlan<K> run(K target, long amount, List<K> priorityRoots) {
        if (amount <= 0) {
            return new CraftPlan<>(true, true, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0, false);
        }

        identifyPositiveFeedbackByproducts(target);

        // Build an acyclic view of the reachable recipe graph: a DFS from the target drops any recipe
        // whose input is an ancestor still being expanded (a back-edge), i.e. AE2's "去头尾". For a
        // compress/decompress pair (1 block ⇄ 9 ingots) this keeps the direction toward the target
        // (compress when making blocks, decompress when making ingots) and cuts the reverse, so the
        // other side falls back to stock/missing — exactly what a sane plan does. No cycle ever reaches
        // the topological passes, so we never bail to AE2 just because a reverse recipe exists.
        Set<K> items = new LinkedHashSet<>();
        List<K> postOrder = new ArrayList<>();
        buildDag(target, priorityRoots, postOrder, items);
        List<K> order = new ArrayList<>(postOrder.size()); // target-first topo order = reverse post-order
        for (int i = postOrder.size() - 1; i >= 0; i--) {
            order.add(postOrder.get(i));
        }
        this.capacity = capacityFromOrder(order, items.size());
        indexEquivalentMaterialFootprints(order);

        // Returned catalysts must be acquired before the firing's outputs enter the shared pool.
        // The recursive path already has that execution order; the aggregate linear pass does not,
        // so using it here could let a positive macro output bootstrap its own seed algebraically.
        if (!requiresSeedOrderedPlanning) {
            // 1) Linear backbone (v2-memo-deps / v2-lazy-deduct): one topological aggregation pass,
            //    each item resolved exactly once = O(n + E). Reservation-based capacity gives O(1)
            //    deduction (no recompute loop). When this fully succeeds, contention never mattered.
            CraftPlan<K> linear = linearPass(order, target, amount);
            if (linear.feasible()) {
                return enforceCycleBootstrap(linear);
            }
        }

        // 2) Contended cone only: fall back to the bounded recursive search (trail + per-node cap K).
        for (K x : items) {
            stockLeft.put(x, graph.stock(x));
        }
        obtain(target, amount, true);
        boolean feasible = missing.isEmpty();
        CraftPlan<K> fallback = new CraftPlan<>(true, feasible,
                new IdentityHashMap<>(firings),
                new HashMap<>(usedStock),
                new HashMap<>(usedReusableStock),
                new HashMap<>(missing),
                new HashMap<>(grossDemand),
                processed,
                false);
        return enforceCycleBootstrap(fallback);
    }

    /**
     * A balanced container cycle still needs one physical state token to start. A purely algebraic
     * flow can otherwise schedule {@code full -> empty -> full} with zero initial containers. When a
     * fired consumed-returning input is refilled by another fired pattern, require one batch from
     * inventory unless some fired acyclic producer supplies either state from outside the pair.
     */
    private CraftPlan<K> enforceCycleBootstrap(CraftPlan<K> plan) {
        Map<K, List<CraftPattern<K>>> firedByOutput = new HashMap<>();
        for (Map.Entry<CraftPattern<K>, Long> entry : plan.firings().entrySet()) {
            if (entry.getValue() > 0) {
                firedByOutput.computeIfAbsent(entry.getKey().output(), ignored -> new ArrayList<>())
                        .add(entry.getKey());
            }
        }

        Map<K, Long> used = new HashMap<>(plan.usedStock());
        Map<K, Long> missing = new HashMap<>(plan.missing());
        Set<Set<K>> handled = new java.util.HashSet<>();

        for (CraftPattern<K> consumer : plan.firings().keySet()) {
            if (plan.firings().getOrDefault(consumer, 0L) <= 0) continue;
            for (CraftInput<K> transition : consumer.inputs()) {
                K remainder = transition.remainder();
                if (remainder == null) continue;
                if (transition.key().equals(remainder)) continue;

                long refillRequirement = Long.MAX_VALUE;
                for (CraftPattern<K> refill : firedByOutput.getOrDefault(transition.key(), List.of())) {
                    for (CraftInput<K> refillInput : refill.inputs()) {
                        if (remainder.equals(refillInput.key())) {
                            refillRequirement = Math.min(refillRequirement, refillInput.amount());
                        }
                    }
                }
                if (refillRequirement == Long.MAX_VALUE) continue;

                Set<K> states = Set.of(transition.key(), remainder);
                if (!handled.add(states)) continue;
                if (used.getOrDefault(transition.key(), 0L) > 0
                        || used.getOrDefault(remainder, 0L) > 0
                        || hasExternalBootstrapProducer(states, firedByOutput)) {
                    continue;
                }

                long required = Math.max(1L, Math.min(transition.amount(), refillRequirement));
                K chosen = graph.stock(transition.key()) >= graph.stock(remainder)
                        ? transition.key() : remainder;
                long extracted = Math.min(required, graph.stock(chosen));
                if (extracted > 0) {
                    used.merge(chosen, extracted, Sat::add);
                }
                if (extracted < required) {
                    missing.merge(chosen, required - extracted, Sat::add);
                }
            }
        }

        enforceDirectFeedbackBootstrap(plan, firedByOutput, used, missing);

        return new CraftPlan<>(plan.supported(), missing.isEmpty(), plan.firings(), used,
                plan.usedReusableStock(), missing, plan.grossDemand(), plan.itemsProcessed(),
                plan.budgetExhausted());
    }

    /**
     * Handles a narrow, common partial-return loop such as
     * {@code 2 A -> 2 B + C; C -> A}. The normal flow equations correctly charge the net A
     * consumption, but an executable schedule also has to keep one returned batch in circulation:
     * two firings consume two A net yet need three A initially. This pass adds only that reusable
     * bootstrap reserve.
     *
     * <p>Classification is deliberately narrow: the consumer has one ordinary input and one byproduct,
     * while the refill has that byproduct as its sole ordinary input. We do not search paths, subsets,
     * or firing orders. Multi-input/output and ambiguous relations stay on ordinary accounting instead
     * of turning this into a general Petri-net solver.
     */
    private void enforceDirectFeedbackBootstrap(
            CraftPlan<K> plan,
            Map<K, List<CraftPattern<K>>> firedByOutput,
            Map<K, Long> used,
            Map<K, Long> missing) {
        Map<K, SeedRequirement<K>> seedRequirements = new HashMap<>();

        for (Map.Entry<CraftPattern<K>, Long> consumerEntry : plan.firings().entrySet()) {
            CraftPattern<K> consumer = consumerEntry.getKey();
            long consumerFirings = consumerEntry.getValue();
            if (consumerFirings <= 0 || consumer.byproducts().size() != 1
                    || ordinaryInputCount(consumer) != 1) {
                continue;
            }

            for (CraftInput<K> consumed : consumer.inputs()) {
                // Different-state container remainders are handled by the explicit bootstrap pass
                // above; returned/finite-use inputs already have their own closed-form seed semantics.
                if (consumed.returned() || consumed.remainder() != null) continue;

                for (CraftOutput<K> returnedState : consumer.byproducts()) {
                    DirectRefill<K> refill = uniqueDirectRefill(
                            consumed.key(), returnedState.key(), firedByOutput, plan.firings());
                    if (refill == null) continue;
                    // Keep this a byproduct-only classification. If the returned state has its own
                    // primary producer, the graph is no longer the narrow half-loop handled here.
                    if (!graph.patternsFor(returnedState.key()).isEmpty()) {
                        continue;
                    }

                    long gcd = gcd(returnedState.amount(), refill.input().amount());
                    long consumerBatch = refill.input().amount() / gcd;
                    long refillBatch = returnedState.amount() / gcd;
                    long consumedPerCycle = Sat.mul(consumed.amount(), consumerBatch);
                    long recoveredPerCycle = Sat.mul(refill.pattern().outputAmount(), refillBatch);
                    // Strict gain belongs to the contracted closed-loop planner. Its feedback output
                    // is suppressed from the ordinary shared pool before planning, so do not add the
                    // reusable-bootstrap accounting used by lossy and balanced ordinary paths.
                    if (recoveredPerCycle > consumedPerCycle) continue;
                    long totalConsumed = Sat.mul(consumed.amount(), consumerFirings);
                    long reusableSeed = Math.min(
                            totalConsumed, Math.min(consumedPerCycle, recoveredPerCycle));
                    if (reusableSeed <= 0) continue;

                    long returnedUnits = Sat.mul(returnedState.amount(), consumerFirings);
                    long maxRefillFirings = returnedUnits / refill.input().amount();
                    long maximumRecovery = Sat.mul(refill.pattern().outputAmount(), maxRefillFirings);
                    long inherentNet = Math.max(0L, totalConsumed - Math.min(totalConsumed, maximumRecovery));

                    long actualRecovery = Sat.mul(
                            refill.pattern().outputAmount(),
                            plan.firings().getOrDefault(refill.pattern(), 0L));
                    long actualNet = Math.max(0L, totalConsumed - Math.min(totalConsumed, actualRecovery));
                    // If the chosen flow already leaves one recovery batch unused, its extra net input
                    // is the seed (balanced bucket loops commonly land here). Otherwise reserve it now.
                    long embeddedSeed = Math.max(0L, actualNet - inherentNet);
                    long extraSeed = Math.max(0L, reusableSeed - embeddedSeed);
                    if (extraSeed > 0) {
                        // The seed may be stored in the returned state instead. Record the alternative
                        // now and reserve it once after all consumers have been scanned; the same seed
                        // can bootstrap several patterns sequentially and must not be double-charged.
                        long seedRefillFirings = Sat.ceilDiv(
                                extraSeed, refill.pattern().outputAmount());
                        long returnedStateSeed = Sat.mul(
                                refill.input().amount(), seedRefillFirings);
                        SeedRequirement<K> candidate = new SeedRequirement<>(
                                extraSeed, returnedState.key(), returnedStateSeed);
                        seedRequirements.merge(consumed.key(), candidate,
                                CraftPlannerV2::largerSeedRequirement);
                    }
                }
            }
        }

        for (Map.Entry<K, SeedRequirement<K>> seed : seedRequirements.entrySet()) {
            K key = seed.getKey();
            SeedRequirement<K> requirement = seed.getValue();
            long returnedAlreadyUsed = used.getOrDefault(requirement.returnedState(), 0L);
            long returnedNeeded = Math.max(0L, requirement.returnedAmount() - returnedAlreadyUsed);
            long returnedAvailable = Math.max(
                    0L, graph.stock(requirement.returnedState()) - returnedAlreadyUsed);
            if (returnedNeeded <= returnedAvailable) {
                if (returnedNeeded > 0) {
                    used.merge(requirement.returnedState(), returnedNeeded, Sat::add);
                }
                continue;
            }

            long alreadyUsed = used.getOrDefault(key, 0L);
            long available = Math.max(0L, graph.stock(key) - alreadyUsed);
            long extracted = Math.min(requirement.consumedAmount(), available);
            if (extracted > 0) used.merge(key, extracted, Sat::add);
            if (extracted < requirement.consumedAmount()) {
                missing.merge(key, requirement.consumedAmount() - extracted, Sat::add);
            }
        }
    }

    private record SeedRequirement<K>(long consumedAmount, K returnedState, long returnedAmount) {
    }

    private static <K> SeedRequirement<K> largerSeedRequirement(
            SeedRequirement<K> left, SeedRequirement<K> right) {
        if (right.consumedAmount() > left.consumedAmount()) return right;
        if (right.consumedAmount() < left.consumedAmount()) return left;
        return right.returnedAmount() < left.returnedAmount() ? right : left;
    }

    private record DirectRefill<K>(CraftPattern<K> pattern, CraftInput<K> input) {
    }

    /** Returns the sole fired direct {@code returnedState -> consumedState} refill, or null if ambiguous. */
    private DirectRefill<K> uniqueDirectRefill(
            K consumedState,
            K returnedState,
            Map<K, List<CraftPattern<K>>> firedByOutput,
            Map<CraftPattern<K>, Long> fired) {
        DirectRefill<K> found = null;
        for (CraftPattern<K> producer : firedByOutput.getOrDefault(consumedState, List.of())) {
            if (fired.getOrDefault(producer, 0L) <= 0 || ordinaryInputCount(producer) != 1) continue;
            for (CraftInput<K> input : producer.inputs()) {
                if (input.returned() || input.remainder() != null
                        || !returnedState.equals(input.key())) {
                    continue;
                }
                if (found != null && found.pattern() != producer) return null;
                found = new DirectRefill<>(producer, input);
            }
        }
        return found;
    }

    private static <K> int ordinaryInputCount(CraftPattern<K> pattern) {
        int count = 0;
        for (CraftInput<K> input : pattern.inputs()) {
            if (!input.returned() && input.remainder() == null) count++;
        }
        return count;
    }

    private static long gcd(long a, long b) {
        a = Math.max(1L, a);
        b = Math.max(1L, b);
        while (b != 0) {
            long next = a % b;
            a = b;
            b = next;
        }
        return a;
    }

    /**
     * Finds direct byproduct feedback whose material state grows after one balanced round, for example
     * {@code A -> B + C; C -> 2 A}. Such a loop is intentionally not a capability of the ordinary
     * planner: it must first be compiled into one closed-loop macro pattern. We therefore keep the raw
     * member recipes visible, but do not let the growing feedback byproduct enter the shared pool.
     * Existing stock of the returned state remains usable, so finite non-feedback crafts still work.
     *
     * <p>This is a linear, deliberately local guard matching the local feedback optimization below;
     * arbitrary SCC coefficient solving remains exclusively in the closed-loop analyzer.
     */
    private void identifyPositiveFeedbackByproducts(K target) {
        Set<K> seen = new LinkedHashSet<>();
        Deque<K> queue = new ArrayDeque<>();
        seen.add(target);
        queue.add(target);

        while (!queue.isEmpty()) {
            K output = queue.remove();
            for (CraftPattern<K> consumer : graph.patternsFor(output)) {
                for (CraftInput<K> input : consumer.inputs()) {
                    if (seen.add(input.key())) queue.add(input.key());
                }
                for (CraftOutput<K> byproduct : consumer.byproducts()) {
                    if (seen.add(byproduct.key())) queue.add(byproduct.key());
                }

                Set<K> ordinaryInputs = new LinkedHashSet<>();
                for (CraftInput<K> input : consumer.inputs()) {
                    if (!input.returned() && input.remainder() == null) {
                        ordinaryInputs.add(input.key());
                    }
                }
                for (K consumedKey : ordinaryInputs) {
                    long consumedAmount = ordinaryInputAmount(consumer, consumedKey);
                    if (consumedAmount <= 0) continue;
                    for (CraftOutput<K> byproduct : consumer.byproducts()) {
                        long returnedAmount = byproductAmount(consumer, byproduct.key());
                        if (returnedAmount <= 0) continue;
                        for (CraftPattern<K> refill : graph.patternsFor(consumedKey)) {
                            long refillInput = ordinaryInputAmount(refill, byproduct.key());
                            if (refillInput <= 0) continue;
                            long common = gcd(returnedAmount, refillInput);
                            long consumerBatch = refillInput / common;
                            long refillBatch = returnedAmount / common;
                            long consumedPerRound = Sat.mul(consumedAmount, consumerBatch);
                            long recoveredPerRound = Sat.mul(refill.outputAmount(), refillBatch);
                            if (recoveredPerRound > consumedPerRound) {
                                suppressedPositiveFeedbackOutputs
                                        .computeIfAbsent(consumer, ignored -> new LinkedHashSet<>())
                                        .add(byproduct.key());
                            }
                        }
                    }
                }
            }
        }
    }

    private static <K> long ordinaryInputAmount(CraftPattern<K> pattern, K key) {
        long result = 0L;
        for (CraftInput<K> input : pattern.inputs()) {
            if (!input.returned() && input.remainder() == null && key.equals(input.key())) {
                result = Sat.add(result, input.amount());
            }
        }
        return result;
    }

    private static <K> long byproductAmount(CraftPattern<K> pattern, K key) {
        long result = 0L;
        for (CraftOutput<K> output : pattern.byproducts()) {
            if (key.equals(output.key())) result = Sat.add(result, output.amount());
        }
        return result;
    }

    private boolean mayReuseByproduct(CraftPattern<K> pattern, K key) {
        return !suppressedPositiveFeedbackOutputs
                .getOrDefault(pattern, Set.of())
                .contains(key);
    }

    private boolean hasExternalBootstrapProducer(
            Set<K> states,
            Map<K, List<CraftPattern<K>>> firedByOutput) {
        for (K state : states) {
            for (CraftPattern<K> producer : firedByOutput.getOrDefault(state, List.of())) {
                boolean consumesCycleState = producer.inputs().stream()
                        .anyMatch(input -> states.contains(input.key()));
                if (!consumesCycleState) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- graph construction ------------------------------------------------

    private static final int GRAY = 1; // on the current DFS path (an ancestor)
    private static final int BLACK = 2; // fully expanded

    private static final class Frame<K> {
        final K node;
        final List<K> children;
        int i;

        Frame(K node, List<K> children) {
            this.node = node;
            this.children = children;
        }
    }

    /**
     * Iterative DFS from {@code target} that records each node's <em>acyclic</em> recipe set into
     * {@link #patternsByOutput} and emits a post-order. A recipe is kept only if none of its inputs is an
     * ancestor currently on the DFS path ({@code GRAY}); such a recipe would close a cycle ("去头尾"), so
     * it is dropped and the input is satisfied from stock/another recipe instead. Each node and edge is
     * touched once → {@code O(n + E)}; iterative (not recursive) so deep graphs can't overflow the stack.
     */
    private void buildDag(
            K target,
            List<K> priorityRoots,
            List<K> postOrderOut,
            Set<K> itemsOut) {
        Map<K, Integer> color = new HashMap<>();
        for (K priorityRoot : priorityRoots) {
            buildDagRoot(priorityRoot, color, postOrderOut, itemsOut);
        }
        buildDagRoot(target, color, postOrderOut, itemsOut);
    }

    private void buildDagRoot(
            K root,
            Map<K, Integer> color,
            List<K> postOrderOut,
            Set<K> itemsOut) {
        if (color.containsKey(root)) return;
        Deque<Frame<K>> stack = new ArrayDeque<>();
        color.put(root, GRAY);
        itemsOut.add(root);
        stack.push(frameFor(root, color, itemsOut));
        while (!stack.isEmpty()) {
            Frame<K> f = stack.peek();
            if (f.i < f.children.size()) {
                K c = f.children.get(f.i++);
                if (color.get(c) == null) { // WHITE -> descend (GRAY children are excluded by frameFor)
                    color.put(c, GRAY);
                    stack.push(frameFor(c, color, itemsOut));
                }
            } else {
                color.put(f.node, BLACK);
                postOrderOut.add(f.node);
                stack.pop();
            }
        }
    }

    private Frame<K> frameFor(K x, Map<K, Integer> color, Set<K> itemsOut) {
        List<CraftPattern<K>> all = graph.patternsFor(x);
        List<CraftPattern<K>> usable = new ArrayList<>(all.size());
        Set<K> children = new LinkedHashSet<>();
        for (CraftPattern<K> p : all) {
            boolean cyclic = false;
            for (CraftInput<K> in : p.inputs()) {
                if (in.returned() && in.uses() == CraftInput.INFINITE_USES) {
                    requiresSeedOrderedPlanning = true;
                }
                if (isSelfReturnedSeed(p, in)) continue;
                Integer col = color.get(in.key());
                if (col != null && col == GRAY) { // input is an ancestor being made -> back-edge, cut it
                    cyclic = true;
                    break;
                }
            }
            if (cyclic) {
                cutOutputs.add(x);
                continue;
            }
            usable.add(p);
            for (CraftInput<K> in : p.inputs()) {
                if (isSelfReturnedSeed(p, in)) continue;
                children.add(in.key());
                itemsOut.add(in.key());
            }
        }
        patternsByOutput.put(x, usable);
        return new Frame<>(x, new ArrayList<>(children));
    }

    /** cap[X] = stock + max recipe-producible (reverse-topo, byproducts ignored = optimistic upper bound). */
    private Map<K, Long> capacityFromOrder(List<K> order, int sizeHint) {
        Map<K, Long> cap = new HashMap<>(sizeHint * 2);
        for (int i = order.size() - 1; i >= 0; i--) {
            K x = order.get(i);
            long best = 0;
            for (CraftPattern<K> p : patternsByOutput.getOrDefault(x, List.of())) {
                best = Math.max(best, producibleVia(p, cap));
                if (Sat.isSaturated(best)) {
                    break;
                }
            }
            cap.put(x, Sat.add(graph.stock(x), best));
        }
        return cap;
    }

    /**
     * Gives simple deterministic recipe trees a canonical material-transformation id. Intermediate
     * item names disappear from the shape, so {@code E→C→A1} and {@code E→D→A2} receive the same id,
     * while exact batch sizes and branching structure remain part of it.
     *
     * <p>This is deliberately a proof, not a heuristic. A craftable intermediate with direct stock or
     * possible dynamic pool credit keeps its own identity, and patterns with returned inputs,
     * remainders, reusable hosts or byproducts are left unclassified. Those routes still search
     * normally; only a pair with identical classified ids may skip a repeated failed expansion.
     */
    private void indexEquivalentMaterialFootprints(List<K> order) {
        Set<K> dynamicPoolKeys = new HashSet<>();
        for (Map.Entry<K, List<CraftPattern<K>>> entry : patternsByOutput.entrySet()) {
            for (CraftPattern<K> pattern : entry.getValue()) {
                if (pattern.outputAmount() > 1) {
                    dynamicPoolKeys.add(pattern.output());
                }
                for (CraftOutput<K> output : pattern.byproducts()) {
                    dynamicPoolKeys.add(output.key());
                }
                for (CraftInput<K> input : pattern.inputs()) {
                    if (input.returned() || input.remainder() != null
                            || input.reusableStockSource() != null) {
                        dynamicPoolKeys.add(input.key());
                        if (input.remainder() != null) {
                            dynamicPoolKeys.add(input.remainder());
                        }
                    }
                }
            }
        }

        FootprintInterner interner = new FootprintInterner();
        Map<K, Integer> footprintByKey = new HashMap<>();
        for (int i = order.size() - 1; i >= 0; i--) {
            K key = order.get(i);
            List<CraftPattern<K>> patterns = patternsByOutput.getOrDefault(key, List.of());
            if (patterns.isEmpty()) {
                footprintByKey.put(key, interner.intern(new MaterialLeaf(key)));
                continue;
            }

            Integer common = null;
            boolean allEquivalent = true;
            for (CraftPattern<K> pattern : patterns) {
                Integer footprint = materialFootprint(pattern, footprintByKey, interner);
                if (footprint != null) {
                    materialFootprintByPattern.put(pattern, footprint);
                }
                if (footprint == null) {
                    allEquivalent = false;
                } else if (common == null) {
                    common = footprint;
                } else if (!common.equals(footprint)) {
                    allEquivalent = false;
                }
            }

            // Direct stock and dynamic surplus/byproduct credit belong to this concrete intermediate,
            // not merely to its production tree. Keep its identity when it is used by a parent.
            if (graph.stock(key) > 0 || dynamicPoolKeys.contains(key)
                    || !allEquivalent || common == null) {
                footprintByKey.put(key, interner.intern(new MaterialLeaf(key)));
            } else {
                footprintByKey.put(key, common);
            }
        }
    }

    private Integer materialFootprint(
            CraftPattern<K> pattern,
            Map<K, Integer> footprintByKey,
            FootprintInterner interner) {
        if (!pattern.byproducts().isEmpty()) {
            return null;
        }

        Map<Integer, Long> amounts = new HashMap<>();
        for (CraftInput<K> input : pattern.inputs()) {
            if (input.returned() || input.remainder() != null
                    || input.reusableStockSource() != null) {
                return null;
            }
            Integer inputFootprint = footprintByKey.get(input.key());
            if (inputFootprint == null) {
                return null;
            }
            long previous = amounts.getOrDefault(inputFootprint, 0L);
            if (Long.MAX_VALUE - previous < input.amount()) {
                return null; // exact proof only; never merge two different saturated totals
            }
            amounts.put(inputFootprint, previous + input.amount());
        }

        List<MaterialTerm> terms = new ArrayList<>(amounts.size());
        for (Map.Entry<Integer, Long> entry : amounts.entrySet()) {
            terms.add(new MaterialTerm(entry.getKey(), entry.getValue()));
        }
        terms.sort((left, right) -> Integer.compare(left.footprint(), right.footprint()));
        return interner.intern(new MaterialRecipe(pattern.outputAmount(), List.copyOf(terms)));
    }

    private long producibleVia(CraftPattern<K> p, Map<K, Long> cap) {
        long bound = Sat.SAT;
        for (CraftInput<K> in : p.inputs()) {
            long c;
            if (in.reusableStockSource() != null) {
                c = Sat.add(
                        graph.reusableStock(in.reusableStockSource(), in.key()),
                        cap.getOrDefault(in.key(), 0L));
            } else if (isSelfReturnedSeed(p, in)) {
                c = graph.stock(in.key());
                for (CraftPattern<K> alternative : patternsByOutput.getOrDefault(in.key(), List.of())) {
                    if (alternative == p || hasSelfReturnedSeed(alternative)) continue;
                    c = Sat.add(c, producibleVia(alternative, cap));
                    if (c >= in.amount()) break;
                }
            } else {
                c = cap.getOrDefault(in.key(), 0L);
            }
            bound = Math.min(bound, in.firingsFrom(c)); // finite-use tools bound by uses·units
            if (bound == 0) {
                return 0;
            }
        }
        return Sat.mul(bound, p.outputAmount());
    }

    // ---- linear backbone: one topological aggregation pass, each item resolved once -------------

    /**
     * Resolves the whole request in a single topological pass (target → leaves). Each item is visited
     * once, its full demand already aggregated, then split across recipes by current remaining
     * capacity. Capacity is reserved with O(1) deduction ({@code need} doubles as the reservation
     * counter), so there is no recompute loop — this is the {@code O(n + E)} clean backbone. If it
     * comes back feasible the plan is exact and contention never bound; otherwise the caller escalates
     * to the bounded search on the contended cone.
     */
    private CraftPlan<K> linearPass(List<K> order, K target, long amount) {
        Map<K, Long> need = new HashMap<>();
        Map<K, Long> bp = new HashMap<>();       // byproduct / surplus pool
        Map<K, Long> stockL = new HashMap<>();   // remaining inventory
        Map<K, Long> used = new HashMap<>();
        Map<K, Long> miss = new HashMap<>();
        Map<K, Long> gross = new HashMap<>();
        Map<CraftPattern<K>, Long> fired = new IdentityHashMap<>();
        need.put(target, amount);
        int done = 0;

        for (K x : order) {
            long d = need.getOrDefault(x, 0L);
            if (d <= 0) {
                continue;
            }
            done++;
            gross.put(x, d);

            long fromBp = Math.min(d, lget(bp, x));
            if (fromBp > 0) {
                bp.put(x, lget(bp, x) - fromBp);
                d -= fromBp;
            }
            long fromStock = Math.min(d, stockL.computeIfAbsent(x, graph::stock));
            if (fromStock > 0) {
                stockL.put(x, lget(stockL, x) - fromStock);
                used.merge(x, fromStock, Sat::add);
                d -= fromStock;
            }
            if (d <= 0) {
                continue;
            }

            List<CraftPattern<K>> ps = patternsByOutput.getOrDefault(x, List.of());
            if (ps.isEmpty()) {
                miss.merge(x, d, Sat::add);
                continue;
            }
            allocateLinear(x, d, ps, need, bp, fired);
        }

        boolean feasible = miss.isEmpty();
        return new CraftPlan<>(true, feasible, fired, used, Map.of(), miss, gross, done, false);
    }

    /** Split {@code d} of {@code x} across recipes by current remaining capacity (dynamic balance). */
    private void allocateLinear(K x, long d, List<CraftPattern<K>> ps,
                                Map<K, Long> need, Map<K, Long> bp, Map<CraftPattern<K>, Long> fired) {
        List<CraftPattern<K>> ordered = new ArrayList<>(ps);
        ordered.sort((a, b) -> Long.compare(capRemainingVia(b, need), capRemainingVia(a, need)));

        for (CraftPattern<K> r : ordered) {
            if (d <= 0) {
                break;
            }
            long p = capRemainingVia(r, need);
            if (p <= 0) {
                continue;
            }
            long make = Math.min(d, p);
            long t = Sat.ceilDiv(make, r.outputAmount());
            long consumed = Math.min(d, Sat.mul(t, r.outputAmount()));
            fireLinear(x, r, t, consumed, need, bp, fired);
            d -= consumed;
        }
        // Leftover nobody had capacity for: push demand down the primary recipe; the deficit surfaces
        // at the raw leaves (same optimistic behaviour as AE2's simulation).
        if (d > 0) {
            CraftPattern<K> r0 = ps.get(0);
            long t = Sat.ceilDiv(d, r0.outputAmount());
            fireLinear(x, r0, t, d, need, bp, fired);
        }
    }

    private void fireLinear(K x, CraftPattern<K> r, long t, long consumedOwn,
                            Map<K, Long> need, Map<K, Long> bp, Map<CraftPattern<K>, Long> fired) {
        fired.merge(r, t, Sat::add);
        for (CraftInput<K> in : r.inputs()) {
            long amt = in.unitsFor(t); // closed form: normal=amount·t, catalyst=amount, finite-use=amount·ceil(t/uses)
            need.merge(in.key(), amt, Sat::add); // demand forward = reservation (capRemaining shrinks)
        }
        for (CraftOutput<K> out : r.byproducts()) {
            if (mayReuseByproduct(r, out.key())) {
                bp.merge(out.key(), Sat.mul(out.amount(), t), Sat::add);
            }
        }
        long surplus = Sat.mul(t, r.outputAmount()) - consumedOwn;
        if (surplus > 0) {
            bp.merge(x, surplus, Sat::add);
        }
    }

    /** capRemaining(input) = static capacity − already-reserved demand; combined over a recipe's inputs. */
    private long capRemainingVia(CraftPattern<K> r, Map<K, Long> need) {
        long bound = Sat.SAT;
        for (CraftInput<K> in : r.inputs()) {
            long cr = Math.max(0L, capacity.getOrDefault(in.key(), 0L) - need.getOrDefault(in.key(), 0L));
            bound = Math.min(bound, in.firingsFrom(cr));
            if (bound == 0) {
                return 0;
            }
        }
        return Sat.mul(bound, r.outputAmount());
    }

    private long lget(Map<K, Long> m, K k) {
        Long v = m.get(k);
        return v == null ? 0L : v;
    }

    // ---- core: obtain d units of x, consuming from pool/stock, crafting the rest ----------------

    /**
     * @param commitFailure whether an exhausted route must commit its greedy partial plan and concrete
     *                      missing leaves. Speculative parents pass {@code false}: they only need a
     *                      non-zero result before rolling the branch back.
     * @return the amount of {@code x} that could not be obtained
     */
    private long obtain(K x, long d, boolean commitFailure) {
        if (d <= 0) {
            return 0;
        }
        bump(grossDemand, x, d);
        reserveSelfSeed(x);
        d -= drawPools(x, d);
        if (d <= 0) {
            return 0;
        }

        // This is a branch-local safety guard. Report only the unstocked remainder as missing so the
        // parent may roll this branch back and try another route; never invalidate unrelated nodes.
        if (depth >= MAX_OBTAIN_DEPTH) {
            if (commitFailure) addMissing(x, d);
            return d;
        }

        List<CraftPattern<K>> ps = patternsByOutput.getOrDefault(x, List.of());
        if (ps.isEmpty()) {
            if (commitFailure) addMissing(x, d);
            return d;
        }

        // Depth is part of the proof identity: a route rejected only because it reached the stack
        // guard must remain eligible when the same node is later reached through a shorter parent path.
        SearchFailure<K> failureKey = new SearchFailure<>(x, d, availabilityState, depth);
        if (!commitFailure && failedSpeculativeSearches.contains(failureKey)) {
            return d;
        }

        if (processed < Integer.MAX_VALUE) {
            processed++;
        }

        CraftPattern<K> frozen = frozenGreedyPattern.get(x);
        if (frozen != null) {
            long unmet = fire(x, frozen, d, !commitFailure);
            if (!commitFailure && unmet > 0) failedSpeculativeSearches.add(failureKey);
            return unmet;
        }

        int v = visit.getOrDefault(x, 0);
        if (v >= visitCap) {
            CraftPattern<K> greedy = ps.size() == 1 ? ps.get(0) : byCapacityDesc(ps).get(0);
            frozenGreedyPattern.put(x, greedy);
            long unmet = fire(x, greedy, d, !commitFailure);
            if (!commitFailure && unmet > 0) failedSpeculativeSearches.add(failureKey);
            return unmet;
        }
        visit.put(x, v + 1);

        // A single recipe needs no alternate search, but its descendants may still resolve their own
        // contention normally until one of them reaches its local cap.
        if (ps.size() == 1) {
            long unmet = fire(x, ps.get(0), d, !commitFailure);
            if (!commitFailure && unmet > 0) failedSpeculativeSearches.add(failureKey);
            return unmet;
        }

        List<CraftPattern<K>> ordered = byCapacityDesc(ps);
        List<CraftPattern<K>> distinctBranches = distinctMaterialBranches(ordered);
        if (distinctBranches.size() == 1) {
            // There is no materially different alternative to discover. Commit the representative
            // once instead of speculatively expanding it, rolling it back, and expanding it again.
            long unmet = fire(x, distinctBranches.get(0), d, !commitFailure);
            if (!commitFailure && unmet > 0) failedSpeculativeSearches.add(failureKey);
            return unmet;
        }
        for (CraftPattern<K> r : distinctBranches) {
            int mark = trail.size();
            long beforeMissing = missingTotal;
            long unmet = fire(x, r, d, true);
            if (unmet == 0 && missingTotal == beforeMissing) {
                return unmet; // this recipe satisfied d without introducing any shortfall
            }
            rollback(mark); // restores pool/firings/missing(+total); try the next recipe
        }
        if (!commitFailure) {
            failedSpeculativeSearches.add(failureKey);
            return d;
        }
        // Root/final route: commit the highest-capacity one and record its concrete missing leaves.
        return commitBestEffort(distinctBranches, x, d);
    }

    private List<CraftPattern<K>> distinctMaterialBranches(List<CraftPattern<K>> ordered) {
        if (ordered.size() < 2 || materialFootprintByPattern.isEmpty()) {
            return ordered;
        }
        Set<Integer> seen = new HashSet<>();
        List<CraftPattern<K>> distinct = new ArrayList<>(ordered.size());
        for (CraftPattern<K> pattern : ordered) {
            Integer footprint = materialFootprintByPattern.get(pattern);
            if (footprint == null || seen.add(footprint)) {
                distinct.add(pattern);
            }
        }
        return distinct;
    }

    private long commitBestEffort(List<CraftPattern<K>> ps, K x, long d) {
        CraftPattern<K> r = ps.size() == 1 ? ps.get(0) : byCapacityDesc(ps).get(0);
        return fire(x, r, d, false);
    }

    /**
     * Fire {@code r} enough times to make {@code d} of {@code x}, obtaining its inputs recursively and
     * injecting outputs (surplus + byproducts) into the pool.
     *
     * @param search if true, abort accounting is meaningful: returns total input shortfall so the
     *               caller can decide to roll back and try another recipe.
     * @return input shortfall (0 means this recipe fully satisfied d).
     */
    private long fire(K x, CraftPattern<K> r, long d, boolean search) {
        long entryMissing = missingTotal;
        long times = Sat.ceilDiv(d, r.outputAmount());
        bumpFiring(r, times);

        long inputUnmet = 0;
        for (CraftInput<K> in : r.inputs()) {
            long amt = in.unitsFor(times); // closed form per flavour
            long unmet;
            ReusableSeedAcquisition reusableAcquisition = null;
            if (in.reusableStockSource() != null) {
                reusableAcquisition = obtainReusableSeed(r, in, amt, search);
                unmet = reusableAcquisition.unmet();
            } else if (isSelfReturnedSeed(r, in)) {
                long obtained = drawReservedSelfSeed(in.key(), amt);
                if (obtained < amt) {
                    obtained = Sat.add(obtained, drawPools(in.key(), amt - obtained));
                }
                long stillNeeded = amt - obtained;
                unmet = stillNeeded > 0
                        ? craftSelfSeedFromAlternative(in.key(), stillNeeded, r)
                        : 0L;
                if (!search && unmet > 0) addMissing(in.key(), unmet);
            } else {
                depth++;
                try {
                    unmet = obtain(in.key(), amt, !search);
                } finally {
                    depth--;
                }
            }
            inputUnmet = Sat.add(inputUnmet, unmet);
            if (in.returned() && in.uses() == CraftInput.INFINITE_USES) {
                // true catalyst/container: the seed is handed back, net consumption zero —
                // return what we actually got into the pool for reuse downstream. A finite-use
                // tool is degraded (consumed) by these firings, so nothing goes back.
                long returned = amt - unmet;
                if (returned > 0) {
                    if (in.reusableStockSource() != null) {
                        var source = in.reusableStockSource();
                        var route = new ReusableStockRouteKey<K>(source, in.key());
                        if (reusableAcquisition.sharedReturnable() > 0) {
                            bump(reusablePool,
                                    new ReusableStockKey<>(source.poolScope(), in.key()),
                                    reusableAcquisition.sharedReturnable());
                        }
                        if (reusableAcquisition.privateReturnable() > 0) {
                            bump(reusablePrivatePool, route,
                                    reusableAcquisition.privateReturnable());
                        }
                    } else {
                        bump(bpPool, in.key(), returned);
                    }
                }
            }
            if (search && (inputUnmet > 0 || missingTotal > entryMissing)) {
                return inputUnmet; // a shortfall appeared; bail early, the caller will roll back
            }
        }

        long produced = Sat.mul(times, r.outputAmount());
        long surplus = produced - d;
        if (surplus > 0) {
            bump(bpPool, x, surplus);
        }
        for (CraftOutput<K> out : r.byproducts()) {
            if (mayReuseByproduct(r, out.key())) {
                bump(bpPool, out.key(), Sat.mul(out.amount(), times));
            }
        }
        return inputUnmet;
    }

    /**
     * Draws a reusable seed only through its logical loop pool. A pool first reuses its own returned
     * state, then borrows from the shared physical host inventory, and finally falls back to normal
     * network stock/crafting. Ordinary recipes can never see either private layer.
     */
    private ReusableSeedAcquisition obtainReusableSeed(
            CraftPattern<K> pattern, CraftInput<K> input, long amount, boolean search) {
        var source = input.reusableStockSource();
        if (source == null || amount <= 0) {
            return new ReusableSeedAcquisition(Math.max(0L, amount), 0L, 0L);
        }

        var route = new ReusableStockRouteKey<K>(source, input.key());
        long fromPrivate = Math.min(amount, get(reusablePrivatePool, route));
        if (fromPrivate > 0) {
            put(reusablePrivatePool, route, get(reusablePrivatePool, route) - fromPrivate);
        }

        long remaining = amount - fromPrivate;
        var poolKey = new ReusableStockKey<K>(source.poolScope(), input.key());
        long fromPool = Math.min(remaining, get(reusablePool, poolKey));
        if (fromPool > 0) {
            put(reusablePool, poolKey, get(reusablePool, poolKey) - fromPool);
        }

        remaining -= fromPool;
        long borrowedExact = 0L;
        long borrowedPrivate = 0L;
        if (remaining > 0) {
            var borrowed = borrowReusableStock(source, input.key(), remaining);
            if (borrowed.amount() > 0) {
                borrowedExact = borrowed.pinnedExactAmount();
                borrowedPrivate = borrowed.amount() - borrowedExact;
                remaining -= borrowed.amount();
            }
        }

        long externalExact = 0L;
        if (remaining > 0 && isSelfReturnedSeed(pattern, input)) {
            // A normal-network seed is reserved before ordinary demand can consume the same key.
            // Host stock still has priority; this path is only the fallback when the private host
            // could not provide the complete bootstrap state.
            long reserved = drawReservedSelfSeed(input.key(), remaining);
            remaining -= reserved;
            externalExact = reserved;
        }
        if (remaining <= 0) {
            return new ReusableSeedAcquisition(
                    0L,
                    Sat.add(Sat.add(fromPool, borrowedExact), externalExact),
                    Sat.add(fromPrivate, borrowedPrivate));
        }
        long unmet;
        long externalRequest = remaining;
        if (isSelfReturnedSeed(pattern, input)) {
            unmet = craftSelfSeedFromAlternative(input.key(), remaining, pattern);
            if (unmet > 0) addMissing(input.key(), unmet);
        } else {
            depth++;
            try {
                unmet = obtain(input.key(), remaining, !search);
            } finally {
                depth--;
            }
        }
        externalExact = Sat.add(externalExact, externalRequest - unmet);
        return new ReusableSeedAcquisition(
                unmet,
                Sat.add(Sat.add(fromPool, borrowedExact), externalExact),
                Sat.add(fromPrivate, borrowedPrivate));
    }

    /**
     * Adds as much demand as the global physical-variant matching problem can satisfy. Every probe
     * re-solves all still-private routes together, so an earlier fuzzy request may be reassigned when
     * a later, more constrained request arrives. An exact allocation is removed from that rematchable
     * set as soon as its returned catalyst is exposed as shared credit; future probes subtract the
     * pinned physical units first. This keeps route matching order-independent without invalidating a
     * shared credit that a later pattern may already have consumed.
     */
    private BorrowedReusableSeed borrowReusableStock(
            ReusableStockSource source, K plannedKey, long requested) {
        if (requested <= 0) return new BorrowedReusableSeed(0L, 0L);
        var route = new ReusableStockRouteKey<K>(source, plannedKey);
        long existing = get(reusableBorrowedDemand, route);

        long low = 0L;
        long high = requested;
        if (!isReusableDemandFeasible(route, addNonNegative(existing, high))) {
            while (low < high) {
                long distance = high - low;
                long middle = low + (distance >>> 1) + (distance & 1L);
                if (isReusableDemandFeasible(route, addNonNegative(existing, middle))) {
                    low = middle;
                } else {
                    high = middle - 1L;
                }
            }
        } else {
            low = high;
        }
        if (low <= 0) return new BorrowedReusableSeed(0L, 0L);

        var demands = new HashMap<>(reusableBorrowedDemand);
        demands.put(route, addNonNegative(existing, low));
        var allocation = ReusableStockMatcher.allocate(
                availableReusableStock(), demands,
                candidate -> graph.reusableStockCandidates(candidate.source(), candidate.plannedKey()));
        if (!allocation.feasible()) {
            throw new IllegalStateException("feasible reusable-stock probe produced no allocation");
        }

        var matchedUsage = reusableUsage(allocation);
        var exactUsage = new ReusableStockUsageKey<K>(
                source.storageScope(), source.poolScope(), source.routingScope(),
                plannedKey, plannedKey);
        long pinnedExact = Math.min(low, get(matchedUsage, exactUsage));
        if (pinnedExact > 0) {
            put(pinnedExactReusableStock, exactUsage,
                    Sat.add(get(pinnedExactReusableStock, exactUsage), pinnedExact));
        }
        put(reusableBorrowedDemand, route, demands.get(route) - pinnedExact);

        // Removing the just-pinned exact edge and the same amount of route demand preserves the
        // feasible residual allocation. Re-solving keeps every still-private fuzzy assignment free
        // to move while the exposed exact shared credit is permanently excluded from host supply.
        allocation = ReusableStockMatcher.allocate(
                availableReusableStock(), reusableBorrowedDemand,
                candidate -> graph.reusableStockCandidates(candidate.source(), candidate.plannedKey()));
        if (!allocation.feasible()) {
            throw new IllegalStateException("pinning an exact reusable allocation broke residual matching");
        }
        matchedUsage = reusableUsage(allocation);
        var desiredUsage = new HashMap<ReusableStockUsageKey<K>, Long>(pinnedExactReusableStock);
        for (var entry : matchedUsage.entrySet()) {
            desiredUsage.merge(entry.getKey(), entry.getValue(), CraftPlannerV2::addNonNegative);
        }
        replaceTracked(usedReusableStock, desiredUsage);
        return new BorrowedReusableSeed(low, pinnedExact);
    }

    private Map<ReusableStockUsageKey<K>, Long> reusableUsage(
            ReusableStockMatcher.Result<K> allocation) {
        var desiredUsage = new HashMap<ReusableStockUsageKey<K>, Long>();
        for (var entry : allocation.allocation().entrySet()) {
            var allocationKey = entry.getKey();
            var allocationRoute = allocationKey.route();
            var allocationSource = allocationRoute.source();
            var usage = new ReusableStockUsageKey<K>(
                    allocationSource.storageScope(),
                    allocationSource.poolScope(),
                    allocationSource.routingScope(),
                    allocationRoute.plannedKey(),
                    allocationKey.actualKey());
            desiredUsage.merge(usage, entry.getValue(), CraftPlannerV2::addNonNegative);
        }
        return desiredUsage;
    }

    /** Physical host snapshot with exact shared credits removed from future max-flow probes. */
    private Map<ReusableStockKey<K>, Long> availableReusableStock() {
        var available = new HashMap<ReusableStockKey<K>, Long>(graph.reusableStock());
        for (var pinned : pinnedExactReusableStock.entrySet()) {
            var physical = new ReusableStockKey<K>(
                    pinned.getKey().storageScope(), pinned.getKey().actualKey());
            long left = get(available, physical) - pinned.getValue();
            if (left > 0) available.put(physical, left);
            else available.remove(physical);
        }
        return available;
    }

    private boolean isReusableDemandFeasible(ReusableStockRouteKey<K> route, long routeDemand) {
        var demands = new HashMap<>(reusableBorrowedDemand);
        if (routeDemand > 0) demands.put(route, routeDemand);
        return ReusableStockMatcher.allocate(
                availableReusableStock(), demands,
                candidate -> graph.reusableStockCandidates(candidate.source(), candidate.plannedKey()))
                .feasible();
    }

    private <T> void replaceTracked(Map<T, Long> target, Map<T, Long> replacement) {
        var keys = new HashSet<T>();
        keys.addAll(target.keySet());
        keys.addAll(replacement.keySet());
        for (var key : keys) {
            long next = get(replacement, key);
            if (get(target, key) != next) {
                put(target, key, next);
            }
        }
    }

    private static long addNonNegative(long left, long right) {
        return left >= Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private record ReusableSeedAcquisition(
            long unmet, long sharedReturnable, long privateReturnable) {
    }

    private record BorrowedReusableSeed(long amount, long pinnedExactAmount) {
    }

    private static <K> boolean isSelfReturnedSeed(CraftPattern<K> pattern, CraftInput<K> input) {
        return input.returned()
                && input.uses() == CraftInput.INFINITE_USES
                && pattern.output().equals(input.key());
    }

    private static <K> boolean hasSelfReturnedSeed(CraftPattern<K> pattern) {
        for (CraftInput<K> input : pattern.inputs()) {
            if (isSelfReturnedSeed(pattern, input)) return true;
        }
        return false;
    }

    /** Crafts only the catalyst seed via a non-self alternative; the gain macro itself is excluded. */
    private long craftSelfSeedFromAlternative(K key, long amount, CraftPattern<K> excluded) {
        List<CraftPattern<K>> alternatives = new ArrayList<>();
        for (CraftPattern<K> pattern : patternsByOutput.getOrDefault(key, List.of())) {
            if (pattern != excluded && !hasSelfReturnedSeed(pattern)) alternatives.add(pattern);
        }
        alternatives.sort((a, b) -> Long.compare(
                producibleVia(b, capacity), producibleVia(a, capacity)));
        for (CraftPattern<K> alternative : alternatives) {
            int mark = trail.size();
            long beforeMissing = missingTotal;
            long unmet = fire(key, alternative, amount, true);
            if (unmet == 0 && missingTotal == beforeMissing) return 0L;
            rollback(mark);
        }
        return amount;
    }

    /** Holds a self-output catalyst aside before ordinary demand can consume it as finished output. */
    private void reserveSelfSeed(K key) {
        long required = 0L;
        for (CraftPattern<K> pattern : patternsByOutput.getOrDefault(key, List.of())) {
            for (CraftInput<K> input : pattern.inputs()) {
                if (isSelfReturnedSeed(pattern, input)) {
                    required = Math.max(required, input.amount());
                }
            }
        }
        long alreadyReserved = get(reservedSelfSeeds, key);
        long additional = Math.max(0L, required - alreadyReserved);
        if (additional <= 0) return;
        long available = get(stockLeft, key);
        long held = Math.min(additional, available);
        if (held > 0) {
            put(stockLeft, key, available - held);
            put(reservedSelfSeeds, key, Sat.add(alreadyReserved, held));
        }
    }

    private long drawReservedSelfSeed(K key, long amount) {
        long available = get(reservedSelfSeeds, key);
        long drawn = Math.min(amount, available);
        if (drawn > 0) {
            put(reservedSelfSeeds, key, available - drawn);
            put(usedStock, key, Sat.add(get(usedStock, key), drawn));
        }
        return drawn;
    }

    /** Draw up to {@code d} of {@code x}: byproduct pool first, then inventory (counted as used stock). */
    private long drawPools(K x, long d) {
        long got = 0;
        long bp = Math.min(d, get(bpPool, x));
        if (bp > 0) {
            put(bpPool, x, get(bpPool, x) - bp);
            got += bp;
            d -= bp;
        }
        long st = Math.min(d, get(stockLeft, x));
        if (st > 0) {
            put(stockLeft, x, get(stockLeft, x) - st);
            put(usedStock, x, Sat.add(get(usedStock, x), st));
            got += st;
        }
        return got;
    }

    private List<CraftPattern<K>> byCapacityDesc(List<CraftPattern<K>> ps) {
        List<CraftPattern<K>> sorted = new ArrayList<>(ps);
        sorted.sort((a, b) -> Long.compare(producibleVia(b, capacity), producibleVia(a, capacity)));
        return sorted;
    }

    // ---- trail-logged mutation helpers -----------------------------------------------------------

    private static <T> long get(Map<T, Long> m, T k) {
        Long v = m.get(k);
        return v == null ? 0L : v;
    }

    private <T> void put(Map<T, Long> m, T k, long newVal) {
        Long old = m.get(k);
        long oldAvailabilityState = availabilityState;
        boolean tracksAvailability = tracksAvailability(m);
        trail.push(() -> {
            if (old == null) {
                m.remove(k);
            } else {
                m.put(k, old);
            }
            if (tracksAvailability) {
                availabilityState = oldAvailabilityState;
            }
        });
        if (tracksAvailability) {
            availabilityState = ++nextAvailabilityState;
        }
        if (newVal == 0) {
            m.remove(k);
        } else {
            m.put(k, newVal);
        }
    }

    private boolean tracksAvailability(Map<?, Long> map) {
        return map == bpPool
                || map == stockLeft
                || map == reservedSelfSeeds
                || map == reusableBorrowedDemand
                || map == reusablePrivatePool
                || map == reusablePool
                || map == pinnedExactReusableStock;
    }

    private <T> void bump(Map<T, Long> m, T k, long delta) {
        if (delta == 0) {
            return;
        }
        put(m, k, Sat.add(get(m, k), delta));
    }

    private void addMissing(K k, long amt) {
        if (amt <= 0) {
            return;
        }
        put(missing, k, Sat.add(get(missing, k), amt));
        long old = missingTotal;
        trail.push(() -> missingTotal = old);
        missingTotal = Sat.add(missingTotal, amt);
    }

    private void bumpFiring(CraftPattern<K> r, long delta) {
        Long old = firings.get(r);
        trail.push(() -> {
            if (old == null) {
                firings.remove(r);
            } else {
                firings.put(r, old);
            }
        });
        firings.put(r, Sat.add(old == null ? 0L : old, delta));
    }

    private void rollback(int mark) {
        while (trail.size() > mark) {
            trail.pop().run();
        }
    }

    private record MaterialLeaf(Object key) {
    }

    private record MaterialTerm(int footprint, long amount) {
    }

    private record MaterialRecipe(long outputAmount, List<MaterialTerm> inputs) {
    }

    private record SearchFailure<K>(K key, long amount, long availabilityState, int depth) {
    }

    private static final class FootprintInterner {
        private final Map<Object, Integer> ids = new HashMap<>();

        private int intern(Object shape) {
            Integer existing = ids.get(shape);
            if (existing != null) {
                return existing;
            }
            int id = ids.size() + 1;
            ids.put(shape, id);
            return id;
        }
    }
}
