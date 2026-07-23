package com.moakiee.thunderbolt.ae2.crafting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.ChildCraftingSimulationState;
import appeng.crafting.inv.CraftingSimulationState;

import com.moakiee.thunderbolt.ae2.overload.model.MatchMode;
import com.moakiee.thunderbolt.ae2.overload.pattern.OverloadPatternDetails;
import com.moakiee.thunderbolt.ae2.overload.pattern.OverloadedProviderOnlyPatternDetails;
import com.moakiee.thunderbolt.core.planner.BoundedCombinations;
import com.moakiee.thunderbolt.core.planner.CraftGraph;
import com.moakiee.thunderbolt.core.planner.CraftInput;
import com.moakiee.thunderbolt.core.planner.CraftOutput;
import com.moakiee.thunderbolt.core.planner.CraftPattern;
import com.moakiee.thunderbolt.core.planner.CraftPlan;
import com.moakiee.thunderbolt.core.planner.CraftPlannerV2;
import com.moakiee.thunderbolt.core.planner.ReusableStockFallback;
import com.moakiee.thunderbolt.core.planner.ReusableStockUsageKey;
import com.moakiee.thunderbolt.core.planner.DurabilityChain;
import com.moakiee.thunderbolt.core.planner.Sat;
import com.moakiee.thunderbolt.ae2.timewheel.ReusableSeedPattern;

/**
 * Bridges one of AE2's per-amount crafting attempts ({@code CraftingCalculation#runCraftAttempt})
 * to the linear {@link CraftPlanner} fast path, producing AE2-compatible {@link CraftingPlan}s.
 *
 * <p>Hooking the per-amount attempt (rather than the whole {@code computePlan}) lets AE2 keep driving
 * its own strategy/binary-search loop while we replace only the expensive tree simulation of each
 * attempt. The contract of {@code runCraftAttempt(simulate, amount)} is mirrored exactly:
 * {@code simulate=false} returns a feasible plan or {@code null} (this amount can't be made);
 * {@code simulate=true} must return a non-null plan carrying the missing items.
 *
 * <p>Engine: the v2 planner ({@link CraftPlannerV2}) — a linear topological backbone with a bounded
 * backtracking fallback for contention. It natively handles byproducts and multiple competing recipes.
 *
 * <p>Correctness rules:
 * <ul>
 *   <li>Modeled in-engine (no decline): byproducts (shared pool), hard-fuzzy substitution (OR
 *       expansion), catalysts/non-degrading containers ({@code returned}), filled containers
 *       (consumed + leftover byproduct, e.g. bucket→empty), durability tools (use-token chain), and
 *       emittable items (infinite on-demand source → {@code emittedItems}). A recipe step we still
 *       cannot model (e.g. a durability chain longer than the cyclic budget) is dropped so its item
 *       surfaces as <em>missing</em> rather than declining the whole attempt.</li>
 *   <li><b>Recursion / cycle</b> → handled in-engine: the v2 planner breaks back-edges ("去头尾") so a
 *       compress/decompress pair (1 block ⇄ 9 ingots) is planned directly instead of declining. The
 *       reverse side resolves from stock/missing; cuts only remove options, never overstate feasibility.</li>
 *   <li>A <b>feasible</b> plan is always safe to return (mass-balanced ⇒ executable), even with
 *       byproducts and multiple recipe choices.</li>
 *   <li><b>Infeasible</b>: best-effort, never declined (Policy A). {@code simulate=false}→null,
 *       {@code simulate=true}→partial plan with missing items. We do NOT fall back to AE2's exhaustive
 *       simulator even if the bounded search hit its per-node cap — that slow path is exactly what this
 *       engine replaces; the per-node cap keeps work bounded and our result is mass-balanced/safe.</li>
 * </ul>
 *
 * <p><b>Execution-time contract (fuzzy substitution).</b> For a hard-fuzzy slot the planner commits to a
 * <em>concrete</em> substitute (the most-available option in the chosen combination) and charges that exact
 * key as used. AE2 still fires the single real {@link IPatternDetails}, and at extraction time its fuzzy
 * matcher may pull a <em>different</em> acceptable stack than the one the plan charged (e.g. a different
 * NBT/damage variant, or a different tag member that happened to be in the same slot). The plan stays
 * mass-balanced for the key it charged, but the network can end up consuming a sibling substitute instead.
 * This is an <b>execution-time</b> concern, not a planning one: reconciling "what the plan charged" with
 * "what the fuzzy slot actually resolved" is the responsibility of the executing CPU (the batch crafting
 * CPU logic), which sees the real extraction. The planner intentionally does not try to predict AE2's
 * runtime fuzzy resolution here — see the batch CPU logic for the execution-side fix.
 *
 * <p><b>Planning-side ID_ONLY (ignore-NBT) aggregation.</b> Overload patterns may mark an input slot
 * {@code ID_ONLY}: at execution any stack sharing the item id is accepted regardless of NBT. For such a
 * slot the planner expands the candidate set with every same-item stack already in the network (via
 * {@code findFuzzyTemplates}/{@code IGNORE_ALL}) so the slot's available stock is counted as the
 * cross-NBT sum. Without this the planner would only see the exact declared template and report a false
 * shortfall whenever the item is held under a different NBT variant.
 *
 * <p>Byte accounting reproduces AE2's formulas (see {@code CraftingTreeNode#request},
 * {@code CraftingTreeProcess#request} and {@code ICraftingSimulationState#addStackBytes}) over the
 * memoized DAG: byte-identical to AE2 for jobs without shared sub-graphs, smaller otherwise.
 */
public final class FastCraftingPlanner {

    /**
     * Hard-fuzzy budget: an input slot that accepts several substitutes is expanded into the cartesian
     * product of concrete choices, each becoming a competing recipe the v2 planner selects among by
     * availability. If a pattern's product of substitute counts exceeds this, we DON'T drop the recipe
     * (that would be a false negative for something AE2 can craft); instead we greedily keep the best
     * {@code FUZZY_NONCYCLE_STEPS} combinations — the lowest rank-sum over per-slot, most-available-first
     * ordered options — bounding the graph without losing the cheapest routes ("贪心前 N" for non-cyclic
     * fuzzy).
     */
    static final long FUZZY_NONCYCLE_STEPS = 64;

    /**
     * Cyclic-fuzzy budget. A durability tool {@code 1·A(n) + 1·B → 1·C + A(n-1)} forms a degradation
     * chain {@code A(n)→A(n-1)→…→broken}. We walk that chain once via {@code getRemainingKey}, capping
     * the walk here ("超步报缺失" → decline to AE2), then reduce it to the closed form
     * {@code uses = chainLen} so a batch costs {@code ceil(times / uses)} full tools instead of one
     * firing per durability point. Also bounds the secondary-fuzzy (re-fuzzy output) collapse.
     */
    static final long FUZZY_CYCLE_STEPS = 8192;

    private FastCraftingPlanner() {
    }

    /** Outcome of an attempt: either declined (run AE2) or handled (use {@link #plan}, may be null). */
    public record FastAttempt(
            boolean handled,
            @Nullable CraftingPlan plan,
            Map<ReusableStockUsageKey<AEKey>, Long> usedReusableStock) {
        public FastAttempt {
            usedReusableStock = Map.copyOf(usedReusableStock);
        }

        static FastAttempt decline() {
            return new FastAttempt(false, null, Map.of());
        }

        static FastAttempt handled(@Nullable CraftingPlan plan) {
            return new FastAttempt(true, plan, Map.of());
        }

        static FastAttempt handled(
                CraftingPlan plan, Map<ReusableStockUsageKey<AEKey>, Long> usedReusableStock) {
            return new FastAttempt(true, plan, usedReusableStock);
        }
    }

    /**
     * Try to satisfy a single {@code runCraftAttempt(simulate, amount)} call.
     */
    public static FastAttempt tryAttempt(ICraftingService craftingService,
                                         CraftingSimulationState networkInv,
                                         Level level,
                                         AEKey output,
                                         long amount,
                                         boolean simulate) {
        return tryAttempt(craftingService, networkInv, level, output, amount, simulate, null);
    }

    public static FastAttempt tryAttempt(ICraftingService craftingService,
                                         CraftingSimulationState networkInv,
                                         Level level,
                                         AEKey output,
                                         long amount,
                                         boolean simulate,
                                         @Nullable ReservedStockCraftingRequester reservedStock) {
        if (amount <= 0) {
            return FastAttempt.decline();
        }

        // Snapshot inventory the same way AE2 does: a child view that ignores the requested output
        // (existing output stock is never consumed; the full amount is always crafted).
        ChildCraftingSimulationState snapshot = new ChildCraftingSimulationState(networkInv);
        ChildCraftingSimulationState reusableSeedSnapshot = new ChildCraftingSimulationState(networkInv);
        snapshot.ignore(output);

        CraftGraph.Builder<AEKey> builder = CraftGraph.builder();
        boolean[] multiplePaths = {false};
        Map<AEKey, DurabilityChain<AEKey>> durability = new HashMap<>();
        Map<AEKey, Set<IPatternDetails>> patternSources = new HashMap<>();
        Set<AEKey> emittable = new HashSet<>();
        if (!buildGraph(craftingService, snapshot, reusableSeedSnapshot, level, output, builder, multiplePaths,
                durability, patternSources, emittable, reservedStock)) {
            // Rare hard declines only: e.g. a key used both as a durability carrier (priced in uses)
            // and as a plain whole-item input — two unit systems on one pool. AE2's exact simulator
            // handles those correctly, and correctness beats speed for the odd setup that hits this.
            return FastAttempt.decline();
        }

        CraftPlan<AEKey> plan = CraftPlannerV2.plan(builder.build(), output, amount);

        boolean multi = multiplePaths[0];
        // Emittable shortfalls are supplied by emitters, not crafted, so they don't make a plan
        // infeasible — only a non-emittable shortfall does.
        if (plan.feasible() || noNonEmittableMissing(plan, emittable)) {
            return FastAttempt.handled(
                    toAe2Plan(output, amount, plan, multi, false, durability,
                            patternSources, emittable, snapshot, reservedStock),
                    plan.usedReusableStock());
        }
        // Infeasible at this amount. Best-effort policy (Policy A): we never fall back to AE2's
        // exhaustive simulator for performance — that is the slow path this engine exists to avoid.
        // Cycles are broken in-engine and the per-node visit cap bounds the search, so the result is
        // both fast and safe: a feasible plan is always mass-balanced, and an infeasible one reports
        // the shortfall we found (even if the bounded search hit its cap, we trust our best effort
        // rather than risk hanging the calculator).
        if (!simulate) {
            return FastAttempt.handled(null); // this amount can't be made within our bounded search
        }
        return FastAttempt.handled(
                toAe2Plan(output, amount, plan, multi, true, durability,
                        patternSources, emittable, snapshot, reservedStock),
                plan.usedReusableStock()); // partial + missing
    }

    private static boolean noNonEmittableMissing(CraftPlan<AEKey> plan, Set<AEKey> emittable) {
        for (AEKey k : plan.missing().keySet()) {
            if (!emittable.contains(k)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameItem(AEKey a, AEKey b) {
        return a instanceof AEItemKey ai && b instanceof AEItemKey bi && ai.getItem() == bi.getItem();
    }

    /** BFS the reachable recipe graph; returns false to decline the fast path. */
    private static boolean buildGraph(ICraftingService craftingService,
                                      ChildCraftingSimulationState snapshot,
                                      ChildCraftingSimulationState reusableSeedSnapshot,
                                      Level level,
                                      AEKey root,
                                      CraftGraph.Builder<AEKey> builder,
                                      boolean[] multiplePaths,
                                      Map<AEKey, DurabilityChain<AEKey>> durability,
                                      Map<AEKey, Set<IPatternDetails>> patternSources,
                                      Set<AEKey> emittable,
                                      @Nullable ReservedStockCraftingRequester reservedStock) {
        Set<AEKey> seen = new HashSet<>();
        Deque<AEKey> queue = new ArrayDeque<>();
        // Memoized "how much is already in the network" per key (SIMULATE probe), used to rank fuzzy
        // substitutes most-available-first so the bounded keep-best-32 picks the cheapest routes.
        Map<AEKey, Long> availability = new HashMap<>();
        Map<AEKey, Long> supplementalSelfSeedStock = new HashMap<>();
        // Unit-system bookkeeping. A durability chain prices its links in USES (carrier pool); every
        // other node is priced in whole ITEMS. The same physical stock must never be counted under
        // both systems (or under two incompatible chains), so we track:
        //   itemUnitKeys — keys stocked/consumed as whole items (BFS-polled nodes + plain inputs);
        //   linkOwner    — every link of every registered chain -> that chain (uses-priced keys).
        // linkOwner doubles as the merge index: a slot whose declared start key is a mid-chain link of
        // an already-built chain reuses that chain when their step granularities line up ("相接"),
        // instead of building a second, overlapping chain. Any overlap that ISN'T such a clean merge
        // (a link priced as a whole item, or two chains stepping the same tool at different
        // durability-per-craft granularities) is a genuine conflict -> decline to AE2's simulator.
        Set<AEKey> itemUnitKeys = new HashSet<>();
        Map<AEKey, DurabilityChain<AEKey>> linkOwner = new HashMap<>();
        seen.add(root);
        queue.add(root);

        while (!queue.isEmpty()) {
            AEKey key = queue.poll();

            // Durability carrier: a finite-use token resource. Its stock (= aggregate uses over the
            // whole chain, 链长×数量) was set once at capture, so we don't re-stock it here — but we DO
            // craft it: a normal tool is craftable, and one crafted (full) tool yields n uses, so the
            // tool's real pattern is registered with its output scaled by n (优先按链长×数量).
            DurabilityChain<AEKey> carrier = durability.get(key);
            long outputScale = 1;
            if (carrier != null) {
                outputScale = carrier.n();
            } else {
                long available = usableStock(snapshot, key, reservedStock);
                if (available > 0) {
                    builder.stock(key, available);
                }
                itemUnitKeys.add(key); // this node is priced in whole items from here on
            }

            // Emitable items (e.g. via level/energy emitters) are an infinite on-demand source: keep
            // them as a leaf with their current real stock, and treat any shortfall as emitted (handled
            // in toAe2Plan) rather than crafted. Never decline just because an item is emittable.
            if (craftingService.canEmitFor(key)) {
                emittable.add(key);
                builder.stock(key, Sat.SAT);
                continue;
            }

            Collection<IPatternDetails> patterns = craftingService.getCraftingFor(key);
            // Count only the primary-output views we actually register (see restriction below); the raw
            // getCraftingFor count would over-report "multiple paths" for secondary-output aliases.
            int registeredForKey = 0;

            for (IPatternDetails details : patterns) {
                // Primary-output restriction: a pattern becomes a craft node ONLY under its declared
                // primary output. getCraftingFor(key) also returns patterns where `key` is merely a
                // SECONDARY output; if we registered those as a second node producing `key`, the same
                // real IPatternDetails would be fireable through two nodes and toAe2Plan (which merges
                // firings by source) could schedule it twice -> double-craft (over-draws stock, piles up
                // byproducts). So we skip non-primary views here: `key` is instead supplied from the
                // byproduct pool when its real primary is crafted, never by a second firing of this
                // source. Trade-off (accepted): an item that is ONLY ever a secondary output, with no
                // pattern of its own, surfaces as missing (Policy A best-effort) rather than being
                // made by deliberately over-producing the primary.
                GenericStack primaryStack = details.getPrimaryOutput();
                if (primaryStack == null || !key.equals(primaryStack.what())) {
                    continue;
                }
                patternSources.computeIfAbsent(key,
                        ignored -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()))
                        .add(details);
                List<GenericStack> outputs = details.getOutputs();
                GenericStack primary = null;
                List<CraftOutput<AEKey>> byproducts = new ArrayList<>(Math.max(0, outputs.size() - 1));
                for (GenericStack out : outputs) {
                    if (primary == null && key.equals(out.what())) {
                        primary = out;
                    } else {
                        byproducts.add(CraftOutput.of(out.what(), out.amount()));
                    }
                }
                if (primary == null) {
                    continue; // defensive: primary not enumerated in getOutputs(); skip this pattern
                }

                if (details instanceof ReusableSeedPattern seeded) {
                    var source = seeded.reusableStockSource();
                    var physicalVariants = seeded.availableReusableSeedSnapshot();
                    for (var entry : physicalVariants.entrySet()) {
                        if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) continue;
                        builder.reusableStock(
                                source.storageScope(), entry.getKey(), entry.getValue());
                    }
                    for (var requirement : seeded.totalReusableSeedRequirements().entrySet()) {
                        if (requirement.getKey() == null || requirement.getValue() == null
                                || requirement.getValue() <= 0) continue;
                        var acceptedVariants = new ArrayList<AEKey>();
                        for (var actual : physicalVariants.keySet()) {
                            if (actual != null
                                    && seeded.acceptsReusableSeedVariant(requirement.getKey(), actual)) {
                                acceptedVariants.add(actual);
                            }
                        }
                        builder.reusableStockRoute(source, requirement.getKey(), acceptedVariants);
                    }
                    // AE2 intentionally ignores pre-existing stock of the requested output. When a
                    // contracted gain loop uses that same key as its catalyst, publish only the part
                    // hidden by that ignore view. For a dependency key, its ordinary stock was
                    // already published above and CraftPlannerV2 reserves the seed from that same
                    // pool; publishing it twice would undercount loop firings by one seed batch.
                    long selfSeedRequired = seeded.totalReusableSeedRequirements()
                            .getOrDefault(key, 0L);
                    if (selfSeedRequired > 0) {
                        long supplemental = ReusableStockFallback.supplementalSelfSeedStock(
                                selfSeedRequired,
                                usableStock(reusableSeedSnapshot, key, reservedStock),
                                usableStock(snapshot, key, reservedStock));
                        long previous = supplementalSelfSeedStock.getOrDefault(key, 0L);
                        if (supplemental > previous) {
                            builder.stock(key, supplemental - previous);
                            supplementalSelfSeedStock.put(key, supplemental);
                        }
                    }
                }

                // Per-slot acceptable concrete options for the hard-fuzzy (OR) expansion.
                IPatternDetails.IInput[] inputs = details.getInputs();
                // Overload patterns expose per-slot match modes; an ID_ONLY (ignore-NBT) slot accepts any
                // stack sharing the item id, so its available stock is the SUM across NBT variants. The
                // slot index here lines up with how Ae2OverloadPatternDetails wrapped getInputs().
                OverloadPatternDetails overloadView = details instanceof OverloadedProviderOnlyPatternDetails op
                        ? op.overloadPatternDetailsView()
                        : null;
                List<List<SlotChoice>> slotOptions = new ArrayList<>(inputs.length);
                long combos = 1;
                boolean patternUnsatisfiable = false;
                for (int slot = 0; slot < inputs.length; slot++) {
                    IPatternDetails.IInput in = inputs[slot];
                    // Durability tool slot: collapse the degradation chain to one finite-use token.
                    ChainLookup lookup = durabilityChain(in, craftingService, snapshot, level, builder,
                            durability, linkOwner, itemUnitKeys, reservedStock);
                    if (lookup.conflict()) {
                        return false; // incompatible durability semantics -> AE2's exact simulator
                    }
                    DurabilityChain<AEKey> chain = lookup.chain();
                    if (chain != null) {
                        // Chain "uses" per firing = tools in the slot x multiplier. One chain step
                        // already encodes this slot's per-craft durability cost (the chain was built
                        // from this slot's own getRemainingKey), so a 2-durability-per-craft recipe
                        // simply produces a chain whose length is the firings a full tool survives.
                        long usesPerFiring = Sat.mul(
                                Math.max(1, in.getPossibleInputs()[0].amount()),
                                Math.max(1, in.getMultiplier()));
                        slotOptions.add(List.of(new SlotChoice(List.of(
                                CraftInput.of(chain.carrier(), usesPerFiring)))));
                        continue; // single deterministic option, never enqueued for crafting
                    }
                    boolean idOnly = overloadView != null && overloadView.inputMode(slot) == MatchMode.ID_ONLY;
                    List<GenericStack> templates = idOnlyTemplates(in, idOnly, snapshot);
                    List<CraftInput<AEKey>> opts = new ArrayList<>(templates.size());
                    for (GenericStack template : templates) {
                        AEKey inputKey = template.what();
                        if (linkOwner.containsKey(inputKey)) {
                            // Whole-item consumption of a key some chain prices in uses (its carrier
                            // OR any mid-chain damaged variant) would mix the two unit systems.
                            return false;
                        }
                        itemUnitKeys.add(inputKey);
                        AEKey remaining = in.getRemainingKey(inputKey) instanceof AEKey r ? r : null;
                        if (remaining == null) {
                            opts.add(CraftInput.of(inputKey, template.amount()));
                        } else if (remaining.equals(inputKey)) {
                            // Returned unchanged: a true catalyst / non-degrading container. One seed
                            // serves the whole batch (AE2's limitQty), modelled as a returned input.
                            if (details instanceof ReusableSeedPattern seeded
                                    && seeded.totalReusableSeedRequirements()
                                            .getOrDefault(inputKey, 0L) > 0) {
                                opts.add(CraftInput.returnedFrom(
                                        inputKey, template.amount(), seeded.reusableStockSource()));
                            } else {
                                opts.add(CraftInput.returned(inputKey, template.amount()));
                            }
                        } else if (sameItem(inputKey, remaining)) {
                            // Same item, lower durability, but durabilityChain declined it (chain longer
                            // than the cyclic budget). Report missing rather than decline: drop the option.
                            // If it was the slot's only option the pattern becomes unsatisfiable below.
                        } else {
                            // Different leftover item (e.g. filled bucket -> empty bucket): consume the
                            // full input and hand back the remainder as a byproduct, so refilling it
                            // closes a cycle the planner resolves instead of declining.
                            opts.add(CraftInput.consumedReturning(inputKey, template.amount(), remaining));
                        }
                    }
                    if (opts.isEmpty()) {
                        patternUnsatisfiable = true; // this recipe can't be fired; surfaces as missing
                        break;
                    }
                    // Rank this slot's substitutes most-available-first. When the full OR-product
                    // overruns the budget we keep only the best `FUZZY_NONCYCLE_STEPS` combinations
                    // (lowest rank-sum), so the cheapest/in-stock routes survive instead of the recipe
                    // being dropped wholesale.
                    if (opts.size() > 1) {
                        for (CraftInput<AEKey> o : opts) {
                            availability.computeIfAbsent(o.key(),
                                k -> usableStock(snapshot, k, reservedStock));
                        }
                        opts.sort(Comparator.comparingLong(
                            (CraftInput<AEKey> o) -> availability.get(o.key())).reversed());
                    }
                    List<SlotChoice> choices = expandSlotChoices(opts, in.getMultiplier(), availability);
                    slotOptions.add(choices);
                    // Saturating product: a raw `combos *= opts.size()` can overflow Long for patterns
                    // with many fuzzy slots over large tags, wrapping to a small value that slips past
                    // the budget check below. Sat.mul clamps so the budget comparison stays correct.
                    combos = Sat.mul(combos, choices.size());
                }
                if (patternUnsatisfiable) {
                    continue; // skip this recipe; if nothing else makes `key` it surfaces as missing
                }
                if (combos > 1) {
                    multiplePaths[0] = true; // fuzzy expanded into competing recipes
                }
                // For a craftable durability tool, one firing makes one full tool = n uses.
                long outAmount = Sat.mul(primary.amount(), outputScale);
                // Keep the best (lowest rank-sum) up to FUZZY_NONCYCLE_STEPS combinations; when the
                // product is within budget this emits all of them, otherwise it greedily keeps the front.
                emitBestCombinations(builder, seen, queue, key, outAmount, byproducts, slotOptions, details);
                registeredForKey++;
            }
            if (registeredForKey > 1) {
                multiplePaths[0] = true; // genuinely distinct recipes whose primary output is `key`
            }
        }
        return true;
    }

    /**
     * Concrete templates to consider for one input slot. A normal slot uses the pattern's declared
     * possible inputs verbatim. An ID_ONLY (ignore-NBT) overload slot additionally pulls every same-item
     * stack already in the network ({@code findFuzzyTemplates} uses {@code FuzzyMode.IGNORE_ALL} = same
     * item id regardless of NBT/damage), each carrying the slot's per-craft amount. The v2 planner then
     * treats them as competing inputs and splits firings across them, so the slot's effective stock is the
     * cross-NBT sum — eliminating false "missing" when the item is only held under an NBT variant the
     * pattern never enumerated. Crafting still works because the original declared template is kept first
     * (so its pattern is discovered) and AE2's executing CPU reconciles the actually-extracted variant.
     */
    private static List<GenericStack> idOnlyTemplates(IPatternDetails.IInput in, boolean idOnly,
                                                      ChildCraftingSimulationState snapshot) {
        GenericStack[] possible = in.getPossibleInputs();
        if (!idOnly) {
            return Arrays.asList(possible);
        }
        LinkedHashMap<AEKey, GenericStack> byKey = new LinkedHashMap<>();
        for (GenericStack template : possible) {
            byKey.putIfAbsent(template.what(), template);
            for (AEKey fuzzy : snapshot.findFuzzyTemplates(template.what())) {
                byKey.putIfAbsent(fuzzy, new GenericStack(fuzzy, template.amount()));
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /** Result of a durability-slot probe: a usable chain, nothing, or a unit conflict (decline). */
    private record ChainLookup(@Nullable DurabilityChain<AEKey> chain, boolean conflict) {
        static final ChainLookup NONE = new ChainLookup(null, false);
        static final ChainLookup CONFLICT = new ChainLookup(null, true);
    }

    /**
     * Detect a durability-tool input and capture its degradation chain once, delegating the chain
     * building / reduction to the engine ({@link DurabilityChain}).
     *
     * <p>The only AE2-specific bits are the two lambdas: {@code remaining} follows
     * {@code getRemainingKey} but returns {@code null} when the step leaves the tool's own {@link Item}
     * (a container like a bucket degrades into a <em>different</em> item → not durability), and
     * {@code stock} probes each exact variant's count (so partial tools are counted). The aggregate uses
     * become the carrier's stock in the graph.
     *
     * <p><b>Chains are matched to SLOT semantics, not just to the item.</b> {@code getRemainingKey} is
     * per-input: one recipe may cost 1 durability per craft, another 2. The chain is built by stepping
     * <em>this slot's</em> rule, so a 2-per-craft slot yields a chain whose length is the firings a full
     * tool survives — self-consistent on its own.
     *
     * <p><b>Anchoring at the fullest tool.</b> A slot's declared template need not be the full tool: an
     * overload pattern is captured from real items the player inserts, so its template is frequently a
     * <em>partially used</em> tool, while the tool that will actually refill the pool is crafted brand
     * new (full). Building the chain straight from a damaged template would miss the fuller variants
     * above it and, worse, never discover the tool's own craft recipe (that recipe's output is the full
     * key, which nothing else would poll). {@link #fullestAnchor} therefore relocates the anchor to the
     * fullest same-item variant the slot accepts (craftable variant first, à la AE2's
     * {@code CraftingTreeNode.findCraftedStack}, otherwise the longest-downward-walk stock variant). That
     * makes the fullest tool the single carrier for every slot on the orbit, so discovery order stops
     * mattering and we never need to re-anchor a chain backward.
     *
     * <p><b>Merge by connectivity ("相接"), not by item identity.</b> {@code linkOwner} indexes every
     * link of every already-built chain back to that chain. When a slot's (anchored) start key is
     * already a link of an existing chain we <em>reuse</em> that chain — but only when this slot's own
     * step from that link lands on the chain's <em>next</em> link, i.e. the two share the same
     * durability-per-craft granularity and lie on one orbit. That is precisely the case where two slots
     * start at different damage levels of the same tool yet connect into one chain, so pooling their
     * stock once is sound. If the step lands elsewhere (e.g. a 2-per-craft slot meeting a 1-per-craft
     * chain), the same physical tools would be priced in two incompatible "uses" units on one pool with
     * no sound linear split, so it reports a {@linkplain ChainLookup#CONFLICT conflict} and declines to
     * AE2. The same conflict is reported when a fresh chain's links overlap keys already priced as whole
     * items ({@code itemUnitKeys}) or links owned by another chain: with the fullest-anchor above, any
     * remaining overlap is a genuine unit-system clash, and declining is always safe.
     *
     * <p>Returns {@link ChainLookup#NONE} when this slot is not a reducible durability tool (plain
     * item, container, or a chain longer than {@link #FUZZY_CYCLE_STEPS}).
     */
    private static ChainLookup durabilityChain(IPatternDetails.IInput in,
                                               ICraftingService craftingService,
                                               ChildCraftingSimulationState snapshot,
                                               Level level,
                                               CraftGraph.Builder<AEKey> builder,
                                               Map<AEKey, DurabilityChain<AEKey>> registry,
                                               Map<AEKey, DurabilityChain<AEKey>> linkOwner,
                                               Set<AEKey> itemUnitKeys,
                                               @Nullable ReservedStockCraftingRequester reservedStock) {
        GenericStack[] possible = in.getPossibleInputs();
        if (possible.length == 0 || !(possible[0].what() instanceof AEItemKey template)) {
            return ChainLookup.NONE;
        }
        Item item = template.getItem();
        AEItemKey full = fullestAnchor(in, craftingService, snapshot, level, template, item);
        AEKey remaining = in.getRemainingKey(full);
        // Classify the resolved craftable/stock anchor, not the declared template. AE2 permits a
        // pattern to accidentally encode a damaged output for a damageable input; in that case the
        // pristine declared template may appear unchanged while the fuzzy craftable variant starts a
        // real degradation chain. Returning before fullestAnchor would miss that pattern entirely.
        if (full.equals(remaining)) {
            return ChainLookup.NONE; // true unbreakable / non-degrading catalyst
        }
        // One degradation step under THIS slot's rule (null = consumed outright / leaves the item).
        AEKey step = remaining instanceof AEItemKey next && next.getItem() == item ? next : null;

        // Merge index: is this slot's start key already a link of some built chain?
        DurabilityChain<AEKey> owner = linkOwner.get(full);
        if (owner != null) {
            if (step == null) {
                return ChainLookup.NONE; // plain consumption; the caller's linkOwner check declines
            }
            // "相接": stepping this slot's rule from `full` must land on the chain's next link, i.e.
            // both step at the same durability-per-craft granularity along the same orbit.
            List<AEKey> links = owner.links();
            int idx = links.indexOf(full);
            AEKey chainNext = idx >= 0 && idx + 1 < links.size() ? links.get(idx + 1) : null;
            return step.equals(chainNext)
                    ? new ChainLookup(owner, false)
                    : ChainLookup.CONFLICT; // same tool link, different per-craft granularity
        }
        if (step == null) {
            return ChainLookup.NONE;
        }

        DurabilityChain<AEKey> chain = DurabilityChain.build(
                full,
                k -> in.getRemainingKey(k) instanceof AEItemKey next && next.getItem() == item ? next : null,
                k -> usableStock(snapshot, k, reservedStock),
                FUZZY_CYCLE_STEPS);
        if (chain == null) {
            return ChainLookup.NONE;
        }
        for (AEKey link : chain.links()) {
            if (itemUnitKeys.contains(link) || linkOwner.containsKey(link)) {
                return ChainLookup.CONFLICT; // stock already counted under another unit system / chain
            }
        }
        registry.put(chain.carrier(), chain); // carrier == full == links[0]
        for (AEKey link : chain.links()) {
            linkOwner.put(link, chain);
        }
        builder.stock(full, chain.totalUses()); // carrier stock = aggregate uses (set once)
        return new ChainLookup(chain, false);
    }

    private static long usableStock(
            ChildCraftingSimulationState snapshot,
            AEKey key,
            @Nullable ReservedStockCraftingRequester reservedStock) {
        long actual = Math.max(0L, snapshot.extract(key, Long.MAX_VALUE, Actionable.SIMULATE));
        if (reservedStock == null) return actual;
        if (reservedStock.groupsSecondaryVariants(key)) {
            var group = new LinkedHashMap<AEKey, Long>();
            group.put(key, actual);
            for (AEKey variant : snapshot.findFuzzyTemplates(key)) {
                if (!key.dropSecondary().equals(variant.dropSecondary())) continue;
                long amount = Math.max(0L,
                        snapshot.extract(variant, Long.MAX_VALUE, Actionable.SIMULATE));
                group.put(variant, amount);
            }
            return Math.max(0L, Math.min(actual,
                    reservedStock.usablePreexistingStock(key, actual, Map.copyOf(group))));
        }
        return Math.max(0L, Math.min(actual, reservedStock.usablePreexistingStock(key, actual)));
    }

    /**
     * Pick the fullest (most-uses-remaining) same-item variant this slot accepts, to anchor its chain.
     *
     * <p>The relative durability of two variants is decided <em>structurally</em> — the one whose
     * downward {@code getRemainingKey} walk is longer takes more crafts to break, so it has more uses
     * left — never by reading a damage value (modded tools may not use vanilla durability at all).
     *
     * <p>A craftable variant, when present, is the absolute fullest: a freshly crafted tool is at max
     * durability, so no stock variant can beat it. So we take AE2's {@code getFuzzyCraftable} answer
     * directly when it exists (and skip the potentially expensive stock scan). Only when nothing
     * craftable is valid for the slot do we widen the search to same-item stock variants (fuzzy,
     * ignore-NBT) and the declared template, keeping the one with the longest downward walk. Any variant
     * that turns out to sit off this slot's step lattice simply isn't reached by the anchor's walk and
     * is left uncounted — an undercount, which is safe (it can only over-report missing, never
     * over-promise).
     */
    private static AEItemKey fullestAnchor(IPatternDetails.IInput in, ICraftingService craftingService,
                                           ChildCraftingSimulationState snapshot, Level level,
                                           AEItemKey template, Item item) {
        if (craftingService.getFuzzyCraftable(template, k -> in.isValid(k, level)) instanceof AEItemKey craftable
                && craftable.getItem() == item) {
            return craftable; // craftable == full durability == absolute fullest; no scan needed
        }
        AEItemKey anchor = template;
        long best = downwardLength(in, template, item);
        for (AEKey variant : snapshot.findFuzzyTemplates(template)) {
            if (!(variant instanceof AEItemKey ik) || ik.getItem() != item || ik.equals(anchor)) {
                continue;
            }
            long len = downwardLength(in, ik, item);
            if (len > best) {
                best = len;
                anchor = ik;
            }
        }
        return anchor;
    }

    /** Number of links on {@code from}'s downward chain (uses left + 1), capped at the cyclic budget. */
    private static long downwardLength(IPatternDetails.IInput in, AEItemKey from, Item item) {
        long len = 1;
        Set<AEKey> guard = new HashSet<>();
        AEKey cur = from;
        guard.add(cur);
        while (in.getRemainingKey(cur) instanceof AEItemKey next && next.getItem() == item && guard.add(next)) {
            cur = next;
            if (++len > FUZZY_CYCLE_STEPS) {
                break;
            }
        }
        return len;
    }

    /**
     * Emit up to {@link #FUZZY_NONCYCLE_STEPS} {@link CraftPattern}s for the per-slot substitute options,
     * choosing the lowest rank-sum (most-available-first) combinations when the full cartesian product
     * exceeds the budget. All share the same {@code source} {@link IPatternDetails} (so AE2 fires the one
     * real pattern and resolves the fuzzy slot from whatever the plan charged as used); the v2 planner
     * treats them as competing recipes and picks per availability.
     */
    private static void emitBestCombinations(CraftGraph.Builder<AEKey> builder, Set<AEKey> seen, Deque<AEKey> queue,
                                         AEKey key, long outputAmount, List<CraftOutput<AEKey>> byproducts,
                                         List<List<SlotChoice>> slotOptions, IPatternDetails source) {
        for (List<SlotChoice> selectedSlots
                : BoundedCombinations.bestFirst(slotOptions, (int) FUZZY_NONCYCLE_STEPS)) {
            List<CraftInput<AEKey>> coreInputs = new ArrayList<>();
            for (SlotChoice selected : selectedSlots) {
                coreInputs.addAll(selected.inputs());
            }
            // A container option (consume full, return empty) contributes its leftover as a byproduct of
            // THIS combination, so we copy the shared byproduct list and append per-chosen-option leftovers.
            List<CraftOutput<AEKey>> combo = byproducts;
            for (CraftInput<AEKey> opt : coreInputs) {
                if (seen.add(opt.key())) {
                    queue.add(opt.key());
                }
                if (opt.remainder() != null) {
                    if (combo == byproducts) {
                        combo = new ArrayList<>(byproducts);
                    }
                    combo.add(CraftOutput.of(opt.remainder(), opt.amount()));
                    if (seen.add(opt.remainder())) {
                        queue.add(opt.remainder());
                    }
                }
            }
            builder.pattern(new CraftPattern<>(key, outputAmount, coreInputs, combo, source));
        }
    }

    /** One concrete integer allocation of a fuzzy slot across its accepted substitutes. */
    private record SlotChoice(List<CraftInput<AEKey>> inputs) {
        private SlotChoice {
            inputs = List.copyOf(inputs);
        }
    }

    /**
     * Expands a slot multiplier into bounded integer partitions across its concrete substitutes.
     * AE2 may satisfy one slot with a mixture (for example two acacia, one birch and one oak plank),
     * whereas treating every substitute as an all-or-nothing recipe incorrectly requires all units
     * to come from one key. Pure allocations are retained and mixed allocations are ranked by how
     * many firings the current network stock can immediately support.
     */
    private static List<SlotChoice> expandSlotChoices(
            List<CraftInput<AEKey>> options,
            long multiplier,
            Map<AEKey, Long> availability) {
        long units = Math.max(1, multiplier);
        if (options.size() == 1) {
            return List.of(new SlotChoice(List.of(scaleInput(options.get(0), units))));
        }

        int limit = (int) FUZZY_NONCYCLE_STEPS;
        List<long[]> allocations = new ArrayList<>(limit);

        // Always retain every pure route that fits the budget.
        for (int i = 0; i < options.size() && allocations.size() < limit; i++) {
            long[] counts = new long[options.size()];
            counts[i] = units;
            allocations.add(counts);
        }

        // Then add mixed partitions, best substitute first. Enumeration is capped, so even very
        // large multipliers or tags cannot make graph construction unbounded.
        enumerateMixedAllocations(options.size(), 0, units, new long[options.size()], allocations, limit);

        List<SlotChoice> choices = new ArrayList<>(allocations.size());
        for (long[] allocation : allocations) {
            List<CraftInput<AEKey>> inputs = new ArrayList<>();
            for (int i = 0; i < allocation.length; i++) {
                if (allocation[i] > 0) {
                    inputs.add(scaleInput(options.get(i), allocation[i]));
                }
            }
            choices.add(new SlotChoice(inputs));
        }
        choices.sort(Comparator.comparingLong(
                (SlotChoice choice) -> immediatelySupportedFirings(choice, availability)).reversed());
        return choices;
    }

    private static void enumerateMixedAllocations(
            int optionCount,
            int index,
            long remaining,
            long[] counts,
            List<long[]> out,
            int limit) {
        if (out.size() >= limit) {
            return;
        }
        if (index == optionCount - 1) {
            counts[index] = remaining;
            int nonZero = 0;
            for (long count : counts) {
                if (count > 0) nonZero++;
            }
            if (nonZero > 1) {
                out.add(Arrays.copyOf(counts, counts.length));
            }
            return;
        }
        for (long count = remaining; count >= 0 && out.size() < limit; count--) {
            counts[index] = count;
            enumerateMixedAllocations(optionCount, index + 1, remaining - count, counts, out, limit);
            if (count == 0) break; // avoid long underflow
        }
        counts[index] = 0;
    }

    private static CraftInput<AEKey> scaleInput(CraftInput<AEKey> input, long units) {
        return new CraftInput<>(
                input.key(), Sat.mul(input.amount(), units), input.returned(), input.uses(),
                input.remainder(), input.reusableStockSource());
    }

    private static long immediatelySupportedFirings(
            SlotChoice choice,
            Map<AEKey, Long> availability) {
        long supported = Sat.SAT;
        for (CraftInput<AEKey> input : choice.inputs()) {
            supported = Math.min(supported,
                    input.firingsFrom(availability.getOrDefault(input.key(), 0L)));
        }
        return supported;
    }

    private static CraftingPlan toAe2Plan(AEKey output, long amount, CraftPlan<AEKey> plan,
                                          boolean multiplePaths, boolean simulation,
                                          Map<AEKey, DurabilityChain<AEKey>> durability,
                                          Map<AEKey, Set<IPatternDetails>> patternSources,
                                          Set<AEKey> emittable,
                                          ChildCraftingSimulationState snapshot,
                                          @Nullable ReservedStockCraftingRequester reservedStock) {
        // Several CraftPatterns may share one IPatternDetails (fuzzy combos / multi-output nodes), so
        // accumulate firing counts per real pattern.
        Map<IPatternDetails, Long> patternTimes = new HashMap<>();
        for (Map.Entry<CraftPattern<AEKey>, Long> e : plan.firings().entrySet()) {
            patternTimes.merge((IPatternDetails) e.getKey().source(), e.getValue(), Sat::add);
        }

        KeyCounter usedItems = new KeyCounter();
        KeyCounter emittedItems = new KeyCounter();
        for (Map.Entry<AEKey, Long> e : plan.usedStock().entrySet()) {
            if (emittable.contains(e.getKey())) {
                emittedItems.add(e.getKey(), e.getValue());
                continue;
            }
            DurabilityChain<AEKey> chain = durability.get(e.getKey());
            if (chain == null) {
                usedItems.add(e.getKey(), e.getValue());
            } else {
                // tokens drawn from stock -> real tools, most-degraded first
                chain.chargeFromStock(e.getValue(), usedItems::add);
            }
        }

        // AE2 extracts fuzzy-slot stock before it decides how much of the remainder must be crafted.
        // Preserve that observable behavior: charge any still-unused accepted stock up to the slot's
        // aggregate demand, even when the compact planner found a more economical concrete mix.
        chargeAvailableFuzzyStock(plan, usedItems, snapshot, reservedStock);

        KeyCounter missingItems = new KeyCounter();
        Set<AEKey> fuzzyIntermediateMissing = fuzzyIntermediateMissing(plan);
        for (Map.Entry<AEKey, Long> e : plan.missing().entrySet()) {
            if (emittable.contains(e.getKey())) {
                emittedItems.add(e.getKey(), e.getValue()); // emitter supplies the shortfall on demand
                continue;
            }
            DurabilityChain<AEKey> chain = durability.get(e.getKey());
            if (chain == null) {
                if (!fuzzyIntermediateMissing.contains(e.getKey())) {
                    missingItems.add(e.getKey(), e.getValue());
                }
            } else {
                // Missing uses become full tools to craft/supply: ceil(uses / n).
                missingItems.add(chain.carrier(), Sat.ceilDiv(e.getValue(), chain.n()));
            }
        }

        long bytes = computeBytes(plan, durability, patternSources);

        return new CraftingPlan(
                new GenericStack(output, amount),
                bytes,
                simulation,
                multiplePaths,
                usedItems,
                emittedItems,
                missingItems,
                patternTimes);
    }

    private static Set<AEKey> fuzzyIntermediateMissing(CraftPlan<AEKey> plan) {
        Set<AEKey> craftedOutputs = new HashSet<>();
        Set<IPatternDetails> sources = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (CraftPattern<AEKey> pattern : plan.firings().keySet()) {
            if (plan.firings().getOrDefault(pattern, 0L) > 0) {
                craftedOutputs.add(pattern.output());
                sources.add((IPatternDetails) pattern.source());
            }
        }

        Set<AEKey> suppressed = new HashSet<>();
        for (IPatternDetails source : sources) {
            for (IPatternDetails.IInput slot : source.getInputs()) {
                GenericStack[] possible = slot.getPossibleInputs();
                if (possible.length <= 1) continue;
                boolean routedThroughCraftableAlternative = Arrays.stream(possible)
                        .anyMatch(option -> craftedOutputs.contains(option.what()));
                if (routedThroughCraftableAlternative) {
                    for (GenericStack option : possible) {
                        suppressed.add(option.what());
                    }
                }
            }
        }
        return suppressed;
    }

    private static void chargeAvailableFuzzyStock(
            CraftPlan<AEKey> plan,
            KeyCounter usedItems,
            ChildCraftingSimulationState snapshot,
            @Nullable ReservedStockCraftingRequester reservedStock) {
        Map<IPatternDetails, Long> sourceFirings = new HashMap<>();
        for (var entry : plan.firings().entrySet()) {
            sourceFirings.merge((IPatternDetails) entry.getKey().source(), entry.getValue(), Sat::add);
        }
        for (var sourceEntry : sourceFirings.entrySet()) {
            long times = sourceEntry.getValue();
            for (IPatternDetails.IInput slot : sourceEntry.getKey().getInputs()) {
                GenericStack[] possible = slot.getPossibleInputs();
                if (possible.length <= 1) continue;
                long remainingUnits = Sat.mul(times, Math.max(1, slot.getMultiplier()));
                for (GenericStack option : possible) {
                    long unitAmount = Math.max(1, option.amount());
                    remainingUnits = Math.max(0, remainingUnits - usedItems.get(option.what()) / unitAmount);
                }
                for (GenericStack option : possible) {
                    if (remainingUnits <= 0) break;
                    long unitAmount = Math.max(1, option.amount());
                    long alreadyUsed = usedItems.get(option.what());
                    long availableStock = usableStock(snapshot, option.what(), reservedStock);
                    long unusedStock = Math.max(0, availableStock - alreadyUsed);
                    long extraUnits = Math.min(remainingUnits, unusedStock / unitAmount);
                    if (extraUnits > 0) {
                        usedItems.add(option.what(), Sat.mul(extraUnits, unitAmount));
                        remainingUnits -= extraUnits;
                    }
                }
            }
        }
    }

    /**
     * Reproduces AE2's byte total: {@code addStackBytes} per requested node
     * ({@code items / amountPerByte * 8}), plus one byte per pattern firing, one byte per returned
     * container firing, and {@code 8 * nodeCount}.
     */
    private static long computeBytes(CraftPlan<AEKey> plan,
                                     Map<AEKey, DurabilityChain<AEKey>> durability,
                                     Map<AEKey, Set<IPatternDetails>> patternSources) {
        double bytes = 0;
        for (Map.Entry<AEKey, Long> e : plan.grossDemand().entrySet()) {
            int amountPerByte = Math.max(1, e.getKey().getType().getAmountPerByte());
            // AE2 charges a node request for every requested input unit. A durability carrier is
            // represented in use-units in the graph, which is exactly the number of requests made by
            // the parent process; do not collapse it to the number of physical tools crafted here.
            bytes += (double) e.getValue() / amountPerByte * 8.0;
        }
        for (long times : plan.firings().values()) {
            bytes += times;
        }
        for (Map.Entry<CraftPattern<AEKey>, Long> entry : plan.firings().entrySet()) {
            long times = entry.getValue();
            for (CraftInput<AEKey> input : entry.getKey().inputs()) {
                if (input.returned() || input.remainder() != null || durability.containsKey(input.key())) {
                    bytes += (double) Sat.mul(times, input.amount());
                }
            }
        }
        long nodeCount = plan.grossDemand().size() - fuzzyNodeReduction(plan);
        nodeCount = Sat.add(nodeCount, unusedAlternativeNodeCount(plan, patternSources));
        bytes += 8.0 * Math.max(0, nodeCount);
        return (long) Math.ceil(bytes);
    }

    /**
     * AE2 creates every sibling {@code CraftingTreeProcess} when a craftable node is expanded, so the
     * input nodes of an unused alternative still occupy eight bytes each. The compact planner keeps
     * only demanded keys in {@code grossDemand}; restore those sibling nodes without defeating AE2's
     * lazy behavior for nodes satisfied entirely from stock (which have no selected source at all).
     */
    private static long unusedAlternativeNodeCount(
            CraftPlan<AEKey> plan,
            Map<AEKey, Set<IPatternDetails>> patternSources) {
        Map<AEKey, Set<IPatternDetails>> selectedByOutput = new HashMap<>();
        for (Map.Entry<CraftPattern<AEKey>, Long> entry : plan.firings().entrySet()) {
            if (entry.getValue() <= 0) continue;
            CraftPattern<AEKey> pattern = entry.getKey();
            selectedByOutput.computeIfAbsent(pattern.output(),
                    ignored -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()))
                    .add((IPatternDetails) pattern.source());
        }

        long extra = 0;
        for (Map.Entry<AEKey, Set<IPatternDetails>> entry : selectedByOutput.entrySet()) {
            Set<IPatternDetails> selected = entry.getValue();
            for (IPatternDetails source : patternSources.getOrDefault(entry.getKey(), Set.of())) {
                if (!selected.contains(source)) {
                    extra = Sat.add(extra, source.getInputs().length);
                }
            }
        }
        return extra;
    }

    private static long fuzzyNodeReduction(CraftPlan<AEKey> plan) {
        long reduction = 0;
        Set<IPatternDetails> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (CraftPattern<AEKey> pattern : plan.firings().keySet()) {
            IPatternDetails source = (IPatternDetails) pattern.source();
            if (!visited.add(source)) continue;
            for (IPatternDetails.IInput slot : source.getInputs()) {
                Set<AEKey> present = new HashSet<>();
                for (GenericStack possible : slot.getPossibleInputs()) {
                    if (plan.grossDemand().containsKey(possible.what())) {
                        present.add(possible.what());
                    }
                }
                reduction += Math.max(0, present.size() - 1L);
            }
        }
        return reduction;
    }
}
