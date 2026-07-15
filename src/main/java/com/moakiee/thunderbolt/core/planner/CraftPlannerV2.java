package com.moakiee.thunderbolt.core.planner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
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
 *       capacity order with a {@code trail} for commit/rollback and a <b>per-node visit cap K</b>.
 *       The cap bounds total work to {@code O(K · edges)} — never exponential, never N². When a node's
 *       budget is exhausted it freezes (best-effort commit on its top recipe), a safe degradation.</li>
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
     * the linear backbone or recover via a handful of backtracks, never approaching this bound. 64 is
     * generous enough that {@link CraftPlan#budgetExhausted()} stays false on realistic inputs while
     * still guaranteeing {@code O(64·E)} worst-case work on adversarial contention. Callers may tier it
     * (e.g. √n on an overloaded CPU).
     */
    public static final int DEFAULT_VISIT_CAP = 64;

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
     * many levels degrades to "missing" (Policy A) and sets {@link CraftPlan#budgetExhausted()}, exactly
     * like a visit-budget freeze. 256 levels (~512 stack frames with the paired {@code fire}) is far
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
    private Map<K, Long> capacity;

    // Mutable planning state (all writes go through the trail so a branch can be rolled back).
    private final Map<K, Long> bpPool = new HashMap<>();      // byproduct / surplus supply
    private final Map<K, Long> stockLeft = new HashMap<>();   // remaining inventory snapshot
    private final Map<K, Long> usedStock = new HashMap<>();   // drawn from inventory
    private final Map<ReusableStockKey<K>, Long> reusableStockLeft = new HashMap<>();
    private final Map<ReusableStockKey<K>, Long> reusablePool = new HashMap<>();
    private final Map<ReusableStockUsageKey<K>, Long> usedReusableStock = new HashMap<>();
    private final Map<K, Long> missing = new HashMap<>();     // unmet at raw leaves
    private final Map<K, Long> grossDemand = new HashMap<>(); // pre-extraction request totals (bytes)
    private final Map<CraftPattern<K>, Long> firings = new IdentityHashMap<>();

    // Monotonic (never rolled back) — this is what bounds the search.
    private final Map<K, Integer> visit = new HashMap<>();
    private final Deque<Runnable> trail = new ArrayDeque<>();
    private int processed;

    // Set once if any node hits the per-node visit cap and falls back to best-effort. Not rolled back.
    private boolean budgetExhausted;

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
        reusableStockLeft.putAll(graph.reusableStock());
        obtain(target, amount);
        boolean feasible = missing.isEmpty();
        CraftPlan<K> fallback = new CraftPlan<>(true, feasible,
                new IdentityHashMap<>(firings),
                new HashMap<>(usedStock),
                new HashMap<>(usedReusableStock),
                new HashMap<>(missing),
                new HashMap<>(grossDemand),
                processed,
                budgetExhausted);
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

    private long producibleVia(CraftPattern<K> p, Map<K, Long> cap) {
        long bound = Sat.SAT;
        for (CraftInput<K> in : p.inputs()) {
            long c;
            if (in.reusableStockSource() != null) {
                c = Sat.add(
                        graph.reusableStock(in.reusableStockSource().storageScope(), in.key()),
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

    /** @return the amount of {@code x} that could NOT be obtained (also recorded in {@link #missing}). */
    private long obtain(K x, long d) {
        if (d <= 0) {
            return 0;
        }
        bump(grossDemand, x, d);
        reserveSelfSeed(x);
        d -= drawPools(x, d);
        if (d <= 0) {
            return 0;
        }

        // Depth guard: refuse to recurse past MAX_OBTAIN_DEPTH. We already drew stock/byproducts above,
        // so only the un-stocked remainder is reported missing — turning a would-be StackOverflowError
        // into the same best-effort "missing" degradation a visit-budget freeze produces.
        if (depth >= MAX_OBTAIN_DEPTH) {
            addMissing(x, d);
            budgetExhausted = true;
            return d;
        }

        List<CraftPattern<K>> ps = patternsByOutput.getOrDefault(x, List.of());
        if (ps.isEmpty()) {
            addMissing(x, d);
            return d;
        }

        processed++;
        int v = visit.getOrDefault(x, 0);
        visit.put(x, v + 1);

        // Single recipe, or budget exhausted: no search — best-effort commit (deficits surface as
        // missing at the leaves). Freezing on exhaustion is the safe degradation.
        if (ps.size() == 1 || v >= visitCap) {
            if (v >= visitCap) {
                budgetExhausted = true; // the per-node cap, not a clean decision, ended the search here
            }
            return commitBestEffort(ps, x, d);
        }

        List<CraftPattern<K>> ordered = byCapacityDesc(ps);
        for (CraftPattern<K> r : ordered) {
            int mark = trail.size();
            long beforeMissing = missingTotal;
            long unmet = fire(x, r, d, true);
            if (missingTotal == beforeMissing) {
                return unmet; // this recipe satisfied d without introducing any shortfall
            }
            rollback(mark); // restores pool/firings/missing(+total); try the next recipe
        }
        // No recipe fully worked within budget: commit the highest-capacity one (records missing).
        return commitBestEffort(ordered, x, d);
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
            if (in.reusableStockSource() != null) {
                unmet = obtainReusableSeed(r, in, amt);
            } else if (isSelfReturnedSeed(r, in)) {
                long obtained = drawReservedSelfSeed(in.key(), amt);
                if (obtained < amt) {
                    obtained = Sat.add(obtained, drawPools(in.key(), amt - obtained));
                }
                long stillNeeded = amt - obtained;
                unmet = stillNeeded > 0
                        ? craftSelfSeedFromAlternative(in.key(), stillNeeded, r)
                        : 0L;
                if (unmet > 0) addMissing(in.key(), unmet);
            } else {
                depth++;
                try {
                    unmet = obtain(in.key(), amt);
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
                        bump(reusablePool,
                                new ReusableStockKey<>(
                                        in.reusableStockSource().poolScope(), in.key()),
                                returned);
                    } else {
                        bump(bpPool, in.key(), returned);
                    }
                }
            }
            if (search && missingTotal > entryMissing) {
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
    private long obtainReusableSeed(CraftPattern<K> pattern, CraftInput<K> input, long amount) {
        var source = input.reusableStockSource();
        if (source == null || amount <= 0) return Math.max(0L, amount);

        var poolKey = new ReusableStockKey<K>(source.poolScope(), input.key());
        long fromPool = Math.min(amount, get(reusablePool, poolKey));
        if (fromPool > 0) {
            put(reusablePool, poolKey, get(reusablePool, poolKey) - fromPool);
        }

        long obtained = fromPool;
        long remaining = amount - obtained;
        if (remaining > 0) {
            var storageKey = new ReusableStockKey<K>(source.storageScope(), input.key());
            long borrowed = Math.min(remaining, get(reusableStockLeft, storageKey));
            if (borrowed > 0) {
                put(reusableStockLeft, storageKey, get(reusableStockLeft, storageKey) - borrowed);
                var usageKey = new ReusableStockUsageKey<K>(
                        source.storageScope(), source.poolScope(), input.key());
                put(usedReusableStock, usageKey,
                        Sat.add(get(usedReusableStock, usageKey), borrowed));
                obtained = Sat.add(obtained, borrowed);
                remaining -= borrowed;
            }
        }

        if (remaining <= 0) return 0L;
        if (isSelfReturnedSeed(pattern, input)) {
            long unmet = craftSelfSeedFromAlternative(input.key(), remaining, pattern);
            return unmet;
        }

        depth++;
        try {
            return obtain(input.key(), remaining);
        } finally {
            depth--;
        }
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
            fire(key, alternative, amount, true);
            if (missingTotal == beforeMissing) return 0L;
            rollback(mark);
        }
        return amount;
    }

    /** Holds a self-output catalyst aside before ordinary demand can consume it as finished output. */
    private void reserveSelfSeed(K key) {
        long required = 0L;
        for (CraftPattern<K> pattern : patternsByOutput.getOrDefault(key, List.of())) {
            for (CraftInput<K> input : pattern.inputs()) {
                if (isSelfReturnedSeed(pattern, input) && input.reusableStockSource() == null) {
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
        trail.push(() -> {
            if (old == null) {
                m.remove(k);
            } else {
                m.put(k, old);
            }
        });
        if (newVal == 0) {
            m.remove(k);
        } else {
            m.put(k, newVal);
        }
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
}
