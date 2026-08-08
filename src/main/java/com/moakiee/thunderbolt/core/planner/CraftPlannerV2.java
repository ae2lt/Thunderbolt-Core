package com.moakiee.thunderbolt.core.planner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.ToLongFunction;

public final class CraftPlannerV2<K> {
   public static final int DEFAULT_VISIT_CAP = 256;
   public static final int DEFAULT_SEARCH_WORK_BUDGET = Math.max(4096, Integer.getInteger("thunderbolt.maxCraftSearchWork", 262144));
   private static final int MIN_SEARCH_WORK_BUDGET = 4096;
   private static final int FALLBACK_WORK_PER_REACHABLE_UNIT = 64;
   private static final int MAX_FALLBACK_WORK_BUDGET = 262144;
   static final int MAX_CONVERSION_ORIENTATION_RETRIES = 4;
   public static final int MAX_OBTAIN_DEPTH = Math.max(16, Integer.getInteger("thunderbolt.maxCraftDepth", 256));
   private final CraftGraph<K> graph;
   private final int visitCap;
   private final CraftPlannerV2.SearchBudget searchBudget;
   private final CraftPlannerV2.FallbackBudget fallbackBudget;
   private final Map<K, CraftPattern<K>> routePreferences;
   private final CraftPlannerV2.DiagnosticsCollector diagnostics;
   private CraftPlannerV2.PreparedGraph<K> preparedGraph;
   private final Set<K> cutOutputs = new LinkedHashSet<>();
   private final Map<CraftPattern<K>, Set<K>> suppressedPositiveFeedbackOutputs = new IdentityHashMap<>();
   private final Map<CraftPattern<K>, Map<K, Long>> linearContainerBootstrapReserves = new IdentityHashMap<>();
   private final Map<K, Long> reservedSelfSeeds = new HashMap<>();
   private final Map<CraftPattern<K>, List<CraftPlannerV2.FeedbackSeedBootstrap<K>>> feedbackSeedBootstraps = new IdentityHashMap<>();
   private final Map<CraftPattern<K>, List<CraftPlannerV2.FeedbackSeedBootstrap<K>>> feedbackSeedConverters = new IdentityHashMap<>();
   private final Map<CraftPlannerV2.FeedbackSeedBootstrap<K>, Long> reservedFeedbackSeedOutputs = new HashMap<>();
   private final Map<CraftPlannerV2.FeedbackSeedBootstrap<K>, Long> reservedFeedbackSeedHostOutputs = new HashMap<>();
   private boolean requiresSeedOrderedPlanning;
   private final Set<K> ordinaryReturnedSeedKeys = new HashSet<>();
   private final Set<K> reachableByproductKeys = new HashSet<>();
   private final Set<K> seedOrderedDependencyCone = new HashSet<>();
   private int depth;
   private final Map<K, Boolean> aggregableMemo = new HashMap<>();
   private Set<K> byproductFeedableKeys;
   private final Map<K, List<CraftPattern<K>>> patternsByOutput = new HashMap<>();
   private final Map<CraftPattern<K>, Long> capacityScoreByPattern = new IdentityHashMap<>();
   private final Map<K, List<CraftPattern<K>>> capacityOrderByOutput = new HashMap<>();
   private final Map<CraftPattern<K>, Map<K, Long>> directRawConsumablesByPattern = new IdentityHashMap<>();
   private final Map<CraftPlannerV2.ConsumableProofState<K>, Long> provenDirectConsumableShortfalls = new HashMap<>();
   private final Map<CraftPattern<K>, Integer> materialFootprintByPattern = new IdentityHashMap<>();
   private Map<K, Long> capacity;
   private final Map<K, Long> bpPool = new HashMap<>();
   private final Map<K, Long> stockLeft = new HashMap<>();
   private final Map<K, Long> usedStock = new HashMap<>();
   private final Map<ReusableStockRouteKey<K>, Long> reusableBorrowedDemand = new HashMap<>();
   private final Map<ReusableStockRouteKey<K>, Long> reusablePrivatePool = new HashMap<>();
   private final Map<ReusableStockKey<K>, Long> reusablePool = new HashMap<>();
   private final Map<ReusableStockUsageKey<K>, Long> pinnedExactReusableStock = new HashMap<>();
   private final Map<ReusableStockUsageKey<K>, Long> usedReusableStock = new HashMap<>();
   private final Map<K, Long> missing = new HashMap<>();
   private final Map<K, Long> grossDemand = new HashMap<>();
   private final Map<CraftPattern<K>, Long> firings = new IdentityHashMap<>();
   private final List<CraftPlannerV2.RouteDecision<K>> routeDecisions = new ArrayList<>();
   private final List<CraftPlannerV2.RouteDecision<K>> replayRouteDecisions = new ArrayList<>();
   private final Map<K, Integer> visit = new HashMap<>();
   private final Set<CraftPlannerV2.SearchFailure<K>> failedSpeculativeSearches = new HashSet<>();
   private final Deque<Runnable> trail = new ArrayDeque<>();
   private long availabilityState;
   private long nextAvailabilityState;
   private int processed;
   private long missingTotal;
   private static final int GRAY = 1;
   private static final int BLACK = 2;

   private CraftPlannerV2(
      CraftGraph<K> graph,
      int visitCap,
      CraftPlannerV2.SearchBudget searchBudget,
      Map<K, CraftPattern<K>> routePreferences,
      CraftPlannerV2.DiagnosticsCollector diagnostics
   ) {
      this.graph = graph;
      this.visitCap = Math.max(1, visitCap);
      this.searchBudget = searchBudget;
      this.fallbackBudget = new CraftPlannerV2.FallbackBudget(diagnostics.fallbackBudgetLimit(), diagnostics);
      this.routePreferences = routePreferences;
      this.diagnostics = diagnostics;
   }

   private CraftPlannerV2(
      CraftPlannerV2.PreparedGraph<K> preparedGraph,
      int visitCap,
      CraftPlannerV2.SearchBudget searchBudget,
      Map<K, CraftPattern<K>> routePreferences,
      CraftPlannerV2.DiagnosticsCollector diagnostics
   ) {
      this(preparedGraph.graph, visitCap, searchBudget, routePreferences, diagnostics);
      this.preparedGraph = preparedGraph;
      this.loadPreparedGraph(preparedGraph);
   }

   public static <K> CraftPlan<K> plan(CraftGraph<K> graph, K target, long amount) {
      int reachableWork = reachableWorkEstimate(graph, target);
      return planDetailed(graph, target, amount, 256, scaledSearchWorkBudget(reachableWork), reachableWork).plan();
   }

   public static <K> CraftPlan<K> plan(CraftGraph<K> graph, K target, long amount, int visitCap) {
      int reachableWork = reachableWorkEstimate(graph, target);
      return planDetailed(graph, target, amount, visitCap, scaledSearchWorkBudget(reachableWork), reachableWork).plan();
   }

   static <K> CraftPlan<K> plan(CraftGraph<K> graph, K target, long amount, int visitCap, int searchWorkBudget) {
      return planDetailed(graph, target, amount, visitCap, searchWorkBudget).plan();
   }

   public static <K> PlanningResult<K> planDetailed(CraftGraph<K> graph, K target, long amount) {
      int reachableWork = reachableWorkEstimate(graph, target);
      return planDetailed(graph, target, amount, 256, scaledSearchWorkBudget(reachableWork), reachableWork);
   }

   static <K> PlanningResult<K> planDetailed(CraftGraph<K> graph, K target, long amount, int visitCap, int searchWorkBudget) {
      return planDetailed(graph, target, amount, visitCap, searchWorkBudget, reachableWorkEstimate(graph, target));
   }

   private static <K> PlanningResult<K> planDetailed(CraftGraph<K> graph, K target, long amount, int visitCap, int searchWorkBudget, int reachableWork) {
      long started = System.nanoTime();
      CraftPlannerV2.DiagnosticsCollector diagnostics = new CraftPlannerV2.DiagnosticsCollector(reachableWork, searchWorkBudget);
      if (amount <= 0L) {
         CraftPlan<K> empty = new CraftPlan<>(true, true, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0, false);
         return new PlanningResult<>(empty, diagnostics.finish(started, null));
      } else {
         CraftPlannerV2.SearchBudget budget = new CraftPlannerV2.SearchBudget(searchWorkBudget, diagnostics);
         int replayCharge = reachableWork;
         Map<List<K>, CraftPlannerV2.PreparedGraph<K>> preparedByOrientation = new HashMap<>();
         CraftPlannerV2.PlanVariant<K> firstVariant = new CraftPlannerV2.PlanVariant<>(List.of(), Map.of(), 0, 0L);
         CraftPlannerV2<K> firstPlanner = new CraftPlannerV2<>(graph, visitCap, budget, firstVariant.routePreferences(), diagnostics);
         CraftPlan<K> first = firstPlanner.run(target, amount, firstVariant.priorityRoots());
         preparedByOrientation.put(firstVariant.priorityRoots(), firstPlanner.preparedGraph);
         if (first.feasible()) {
            return finish(first, diagnostics, budget, started);
         } else if (first.budgetExhausted()) {
            return finish(first, diagnostics, budget, started);
         } else {
            CraftPlan<K> bestIncomplete = first;
            PriorityQueue<CraftPlannerV2.PlanVariant<K>> frontier = new PriorityQueue<>((left, right) -> {
               int byDiscrepancy = Integer.compare(left.discrepancies(), right.discrepancies());
               return byDiscrepancy != 0 ? byDiscrepancy : Long.compare(left.sequence(), right.sequence());
            });
            Set<CraftPlannerV2.VariantKey<K>> queued = new HashSet<>();
            queued.add(firstVariant.key());
            long sequence = 1L;
            if (!firstPlanner.cutOutputs.isEmpty()) {
               CycleAnalysis<K> cycleAnalysis = CycleAnalysis.analyze(graph, target);
               List<Entry<K, Long>> reorientCandidates = new ArrayList<>();

               for (K cutOutput : firstPlanner.cutOutputs) {
                  if (cycleAnalysis.mayReorient(cutOutput)) {
                     long attributedMissing = first.missing().getOrDefault(cutOutput, 0L);

                     for (K member : cycleAnalysis.membersOf(cutOutput)) {
                        if (!member.equals(cutOutput)) {
                           attributedMissing = Sat.add(attributedMissing, first.missing().getOrDefault(member, 0L));
                        }
                     }

                     if (attributedMissing > 0L) {
                        reorientCandidates.add(Map.entry(cutOutput, attributedMissing));
                     }
                  }
               }

               reorientCandidates.sort((left, right) -> Long.compare(right.getValue(), left.getValue()));
               int retries = 0;

               for (Entry<K, Long> candidate : reorientCandidates) {
                  if (retries >= 4) {
                     break;
                  }

                  retries++;
                  CraftPlannerV2.PlanVariant<K> variant = new CraftPlannerV2.PlanVariant<>(List.of(candidate.getKey()), Map.of(), 1, sequence++);
                  if (queued.add(variant.key())) {
                     frontier.add(variant);
                  }
               }
            }

            CraftPlannerV2.EnqueueResult enqueue = enqueueRouteVariants(firstVariant, firstPlanner, frontier, queued, sequence, budget, reachableWork);
            sequence = enqueue.sequence();
            boolean frontierTruncated = enqueue.truncated();
            diagnostics.recordFrontierSize(frontier.size());

            while (!frontier.isEmpty()) {
               CraftPlannerV2.PlanVariant<K> variant = frontier.poll();
               if (!budget.tryConsume(replayCharge)) {
                  return finish(markBudgetExhausted(bestIncomplete), diagnostics, budget, started);
               }

               CraftPlannerV2.PreparedGraph<K> prepared = preparedByOrientation.get(variant.priorityRoots());
               CraftPlannerV2<K> planner = prepared == null
                  ? new CraftPlannerV2<>(graph, visitCap, budget, variant.routePreferences(), diagnostics)
                  : new CraftPlannerV2<>(prepared, visitCap, budget, variant.routePreferences(), diagnostics);
               CraftPlan<K> candidate = planner.run(target, amount, variant.priorityRoots());
               preparedByOrientation.putIfAbsent(variant.priorityRoots(), planner.preparedGraph);
               if (candidate.feasible()) {
                  return finish(candidate, diagnostics, budget, started);
               }

               if (candidate.budgetExhausted()) {
                  return finish(markBudgetExhausted(betterIncompletePlan(bestIncomplete, candidate)), diagnostics, budget, started);
               }

               bestIncomplete = betterIncompletePlan(bestIncomplete, candidate);
               enqueue = enqueueRouteVariants(variant, planner, frontier, queued, sequence, budget, replayCharge);
               sequence = enqueue.sequence();
               frontierTruncated |= enqueue.truncated();
               diagnostics.recordFrontierSize(frontier.size());
            }

            CraftPlan<K> result = frontierTruncated ? markBudgetExhausted(bestIncomplete) : bestIncomplete;
            return finish(result, diagnostics, budget, started);
         }
      }
   }

   private static <K> PlanningResult<K> finish(
      CraftPlan<K> plan, CraftPlannerV2.DiagnosticsCollector diagnostics, CraftPlannerV2.SearchBudget budget, long started
   ) {
      return new PlanningResult<>(plan, diagnostics.finish(started, budget));
   }

   static <K> int scaledSearchWorkBudget(CraftGraph<K> graph, K target) {
      return scaledSearchWorkBudget(reachableWorkEstimate(graph, target));
   }

   private static int scaledSearchWorkBudget(int work) {
      int log = 32 - Integer.numberOfLeadingZeros(Math.max(1, work));
      long scaled = (long)work * ((long)log + 4L);
      return (int)Math.min((long)DEFAULT_SEARCH_WORK_BUDGET, Math.max(4096L, scaled));
   }

   private static int fallbackWorkBudget(int reachableWork) {
      long scaled = (long)Math.max(1, reachableWork) * 64L;
      return (int)Math.min(262144L, Math.max(64L, scaled));
   }

   static <K> int reachableWorkEstimate(CraftGraph<K> graph, K target) {
      Set<K> seen = new HashSet<>();
      Deque<K> queue = new ArrayDeque<>();
      seen.add(target);
      queue.add(target);
      long work = 0L;

      while (!queue.isEmpty()) {
         K key = queue.removeFirst();
         work++;

         for (CraftPattern<K> pattern : graph.patternsFor(key)) {
            work++;

            for (CraftInput<K> input : pattern.inputs()) {
               work++;
               if (seen.add(input.key())) {
                  queue.addLast(input.key());
               }
            }
         }

         if (work >= 2147483647L) {
            return Integer.MAX_VALUE;
         }
      }

      return Math.max(1, (int)work);
   }

   private static <K> CraftPlannerV2.EnqueueResult enqueueRouteVariants(
      CraftPlannerV2.PlanVariant<K> parent,
      CraftPlannerV2<K> planner,
      PriorityQueue<CraftPlannerV2.PlanVariant<K>> frontier,
      Set<CraftPlannerV2.VariantKey<K>> queued,
      long sequence,
      CraftPlannerV2.SearchBudget budget,
      int replayCharge
   ) {
      int affordableReplays = budget.remaining() / Math.max(1, replayCharge);
      int availableSlots = Math.max(0, affordableReplays - frontier.size());
      CraftPlannerV2.RouteAlternatives<K> routeAlternatives = planner.routeAlternatives(availableSlots);

      for (CraftPlannerV2.RouteAlternative<K> alternative : routeAlternatives.alternatives()) {
         Map<K, CraftPattern<K>> preferences = new HashMap<>(parent.routePreferences());
         if (alternative.pattern() == alternative.defaultPattern()) {
            preferences.remove(alternative.key());
         } else {
            preferences.put(alternative.key(), alternative.pattern());
         }

         CraftPlannerV2.PlanVariant<K> variant = new CraftPlannerV2.PlanVariant<>(
            parent.priorityRoots(), Map.copyOf(preferences), parent.priorityRoots().isEmpty() ? preferences.size() : preferences.size() + 1, sequence++
         );
         if (queued.add(variant.key())) {
            frontier.add(variant);
         }
      }

      return new CraftPlannerV2.EnqueueResult(sequence, routeAlternatives.truncated());
   }

   private static <K> CraftPlan<K> betterIncompletePlan(CraftPlan<K> current, CraftPlan<K> candidate) {
      int byKinds = Integer.compare(candidate.missing().size(), current.missing().size());
      if (byKinds < 0) {
         return candidate;
      } else if (byKinds > 0) {
         return current;
      } else {
         long currentTotal = missingTotal(current);
         long candidateTotal = missingTotal(candidate);
         return candidateTotal < currentTotal ? candidate : current;
      }
   }

   private static <K> long missingTotal(CraftPlan<K> plan) {
      long total = 0L;

      for (long amount : plan.missing().values()) {
         total = Sat.add(total, amount);
      }

      return total;
   }

   private static <K> CraftPlan<K> markBudgetExhausted(CraftPlan<K> plan) {
      return new CraftPlan<>(
         plan.supported(),
         plan.feasible(),
         plan.firings(),
         plan.usedStock(),
         plan.usedReusableStock(),
         plan.missing(),
         plan.grossDemand(),
         plan.itemsProcessed(),
         true
      );
   }

   private CraftPlan<K> run(K target, long amount, List<K> priorityRoots) {
      this.diagnostics.recordPlanRun();
      if (amount <= 0L) {
         return new CraftPlan<>(true, true, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0, false);
      } else {
         List<K> order;
         Set<K> items;
         if (this.preparedGraph == null) {
            long compileStarted = System.nanoTime();
            this.identifyPositiveFeedbackByproducts(target);
            items = new LinkedHashSet<>();
            List<K> postOrder = new ArrayList<>();
            this.buildDag(target, priorityRoots, postOrder, items);
            if (!this.requiresSeedOrderedPlanning && this.ordinaryReturnedSeedKeys.stream().anyMatch(this.reachableByproductKeys::contains)) {
               this.requiresSeedOrderedPlanning = true;
            }

            order = new ArrayList<>(postOrder.size());

            for (int i = postOrder.size() - 1; i >= 0; i--) {
               order.add(postOrder.get(i));
            }

            this.indexLinearContainerBootstrapReserves();
            this.indexSeedOrderedDependencyCone(order);
            this.capacity = this.capacityFromOrder(order, items.size());
            this.indexCapacityOrder();
            this.indexDirectRawConsumables();
            this.indexEquivalentMaterialFootprints(order);
            this.preparedGraph = this.snapshotPreparedGraph(order, items);
            this.diagnostics.recordCompilation(this.preparedGraph, System.nanoTime() - compileStarted);
         } else {
            order = this.preparedGraph.order;
            items = this.preparedGraph.items;
            this.diagnostics.recordCompilationReuse();
         }

         CraftPlan<K> linearDiagnosis = null;
         if (!this.requiresSeedOrderedPlanning) {
            long linearStarted = System.nanoTime();
            CraftPlan<K> linear = this.linearPass(order, target, amount);
            this.diagnostics.addLinearPassNanos(System.nanoTime() - linearStarted);
            if (linear.feasible()) {
               return this.enforceCycleBootstrap(linear);
            }

            linearDiagnosis = linear;
            CraftPlan<K> repaired = this.allocationRepair(order, target, amount);
            if (repaired != null && repaired.feasible()) {
               CraftPlan<K> bootstrapped = this.enforceCycleBootstrap(repaired);
               if (bootstrapped.feasible()) {
                  return bootstrapped;
               }
            }
         }

         for (K x : items) {
            this.stockLeft.put(x, this.graph.stock(x));
         }

         long searchStarted = System.nanoTime();
         this.obtain(target, amount, true);
         this.diagnostics.addSearchNanos(System.nanoTime() - searchStarted);
         boolean feasible = this.missing.isEmpty();
         boolean budgetTruncated = (this.searchBudget.exhausted() || this.diagnostics.resolutionExhausted()) && !feasible;
         CraftPlan<K> fallback = new CraftPlan<>(
            true,
            feasible,
            new IdentityHashMap<>(this.firings),
            new HashMap<>(this.usedStock),
            new HashMap<>(this.usedReusableStock),
            new HashMap<>(this.missing),
            new HashMap<>(this.grossDemand),
            this.processed,
            budgetTruncated
         );
         if (budgetTruncated && linearDiagnosis != null && this.hasCraftableMissing(fallback)) {
            CraftPlan<K> diagnosis = new CraftPlan<>(
               true,
               false,
               linearDiagnosis.firings(),
               linearDiagnosis.usedStock(),
               linearDiagnosis.usedReusableStock(),
               linearDiagnosis.missing(),
               linearDiagnosis.grossDemand(),
               linearDiagnosis.itemsProcessed(),
               true
            );
            return this.enforceCycleBootstrap(diagnosis);
         } else {
            return this.enforceCycleBootstrap(fallback);
         }
      }
   }

   private boolean hasCraftableMissing(CraftPlan<K> plan) {
      for (K key : plan.missing().keySet()) {
         if (!this.patternsByOutput.getOrDefault(key, List.of()).isEmpty()) {
            return true;
         }
      }

      return false;
   }

   private CraftPlannerV2.PreparedGraph<K> snapshotPreparedGraph(List<K> order, Set<K> items) {
      Map<K, List<CraftPattern<K>>> frozenPatterns = new HashMap<>();
      this.patternsByOutput.forEach((key, value) -> frozenPatterns.put((K)key, List.copyOf(value)));
      IdentityHashMap<CraftPattern<K>, Set<K>> frozenSuppressed = new IdentityHashMap<>();
      this.suppressedPositiveFeedbackOutputs.forEach((patternx, outputs) -> frozenSuppressed.put(patternx, Set.copyOf(outputs)));
      IdentityHashMap<CraftPattern<K>, Map<K, Long>> frozenContainerReserves = new IdentityHashMap<>();
      this.linearContainerBootstrapReserves
         .forEach((patternx, reserves) -> frozenContainerReserves.put(patternx, Map.copyOf((Map<? extends K, ? extends Long>)reserves)));
      IdentityHashMap<CraftPattern<K>, List<CraftPlannerV2.FeedbackSeedBootstrap<K>>> frozenBootstraps = new IdentityHashMap<>();
      this.feedbackSeedBootstraps.forEach((patternx, values) -> frozenBootstraps.put(patternx, List.copyOf(values)));
      IdentityHashMap<CraftPattern<K>, List<CraftPlannerV2.FeedbackSeedBootstrap<K>>> frozenConverters = new IdentityHashMap<>();
      this.feedbackSeedConverters.forEach((patternx, values) -> frozenConverters.put(patternx, List.copyOf(values)));
      IdentityHashMap<CraftPattern<K>, Long> frozenScores = new IdentityHashMap<>();
      frozenScores.putAll(this.capacityScoreByPattern);
      IdentityHashMap<CraftPattern<K>, Map<K, Long>> frozenRaw = new IdentityHashMap<>();
      frozenRaw.putAll(this.directRawConsumablesByPattern);
      IdentityHashMap<CraftPattern<K>, Integer> frozenFootprints = new IdentityHashMap<>();
      frozenFootprints.putAll(this.materialFootprintByPattern);
      int patternCount = 0;
      int inputCount = 0;
      int contended = 0;

      for (List<CraftPattern<K>> patterns : frozenPatterns.values()) {
         patternCount += patterns.size();
         if (patterns.size() > 1) {
            contended++;
         }

         for (CraftPattern<K> pattern : patterns) {
            inputCount += pattern.inputs().size();
         }
      }

      return new CraftPlannerV2.PreparedGraph<>(
         this.graph,
         List.copyOf(order),
         Set.copyOf(items),
         Set.copyOf(this.cutOutputs),
         frozenPatterns,
         frozenSuppressed,
         frozenContainerReserves,
         frozenBootstraps,
         frozenConverters,
         this.requiresSeedOrderedPlanning,
         Set.copyOf(this.seedOrderedDependencyCone),
         new HashMap<>(this.capacity),
         frozenScores,
         new HashMap<>(this.capacityOrderByOutput),
         frozenRaw,
         frozenFootprints,
         patternCount,
         inputCount,
         contended
      );
   }

   private void loadPreparedGraph(CraftPlannerV2.PreparedGraph<K> prepared) {
      this.cutOutputs.addAll(prepared.cutOutputs);
      this.patternsByOutput.putAll(prepared.patternsByOutput);
      this.suppressedPositiveFeedbackOutputs.putAll(prepared.suppressedPositiveFeedbackOutputs);
      this.linearContainerBootstrapReserves.putAll(prepared.linearContainerBootstrapReserves);
      this.feedbackSeedBootstraps.putAll(prepared.feedbackSeedBootstraps);
      this.feedbackSeedConverters.putAll(prepared.feedbackSeedConverters);
      this.requiresSeedOrderedPlanning = prepared.seedOrdered;
      this.seedOrderedDependencyCone.addAll(prepared.seedOrderedDependencyCone);
      this.capacity = prepared.capacity;
      this.capacityScoreByPattern.putAll(prepared.capacityScoreByPattern);
      this.capacityOrderByOutput.putAll(prepared.capacityOrderByOutput);
      this.directRawConsumablesByPattern.putAll(prepared.directRawConsumablesByPattern);
      this.materialFootprintByPattern.putAll(prepared.materialFootprintByPattern);
   }

   private CraftPlan<K> enforceCycleBootstrap(CraftPlan<K> plan) {
      Map<K, List<CraftPattern<K>>> firedByOutput = new HashMap<>();

      for (Entry<CraftPattern<K>, Long> entry : plan.firings().entrySet()) {
         if (entry.getValue() > 0L) {
            firedByOutput.computeIfAbsent(entry.getKey().output(), ignored -> new ArrayList<>()).add(entry.getKey());
         }
      }

      Map<K, Long> used = new HashMap<>(plan.usedStock());
      Map<K, Long> missing = new HashMap<>(plan.missing());
      Set<Set<K>> handled = new HashSet<>();

      for (CraftPattern<K> consumer : plan.firings().keySet()) {
         if (plan.firings().getOrDefault(consumer, 0L) > 0L) {
            for (CraftInput<K> transition : consumer.inputs()) {
               K remainder = transition.remainder();
               if (remainder != null && !transition.key().equals(remainder)) {
                  long refillRequirement = Long.MAX_VALUE;

                  for (CraftPattern<K> refill : firedByOutput.getOrDefault(transition.key(), List.of())) {
                     for (CraftInput<K> refillInput : refill.inputs()) {
                        if (remainder.equals(refillInput.key())) {
                           refillRequirement = Math.min(refillRequirement, refillInput.amount());
                        }
                     }
                  }

                  if (refillRequirement != Long.MAX_VALUE) {
                     Set<K> states = Set.of(transition.key(), remainder);
                     if (handled.add(states)
                        && used.getOrDefault(transition.key(), 0L) <= 0L
                        && used.getOrDefault(remainder, 0L) <= 0L
                        && !this.hasExternalBootstrapProducer(states, firedByOutput)) {
                        long required = Math.max(1L, Math.min(transition.amount(), refillRequirement));
                        K chosen = this.graph.stock(transition.key()) >= this.graph.stock(remainder) ? transition.key() : remainder;
                        long extracted = Math.min(required, this.graph.stock(chosen));
                        if (extracted > 0L) {
                           used.merge(chosen, extracted, Sat::add);
                        }

                        if (extracted < required) {
                           missing.merge(chosen, required - extracted, Sat::add);
                        }
                     }
                  }
               }
            }
         }
      }

      this.enforceDirectFeedbackBootstrap(plan, firedByOutput, used, missing);
      return new CraftPlan<>(
         plan.supported(),
         missing.isEmpty(),
         plan.firings(),
         used,
         plan.usedReusableStock(),
         missing,
         plan.grossDemand(),
         plan.itemsProcessed(),
         plan.budgetExhausted()
      );
   }

   private void enforceDirectFeedbackBootstrap(CraftPlan<K> plan, Map<K, List<CraftPattern<K>>> firedByOutput, Map<K, Long> used, Map<K, Long> missing) {
      Map<K, CraftPlannerV2.SeedRequirement<K>> seedRequirements = new HashMap<>();

      for (Entry<CraftPattern<K>, Long> consumerEntry : plan.firings().entrySet()) {
         CraftPattern<K> consumer = consumerEntry.getKey();
         long consumerFirings = consumerEntry.getValue();
         if (consumerFirings > 0L && consumer.byproducts().size() == 1 && ordinaryInputCount(consumer) == 1) {
            for (CraftInput<K> consumed : consumer.inputs()) {
               if (!consumed.returned() && consumed.remainder() == null) {
                  for (CraftOutput<K> returnedState : consumer.byproducts()) {
                     CraftPlannerV2.DirectRefill<K> refill = this.uniqueDirectRefill(consumed.key(), returnedState.key(), firedByOutput, plan.firings());
                     if (refill != null && this.graph.patternsFor(returnedState.key()).isEmpty()) {
                        long gcd = gcd(returnedState.amount(), refill.input().amount());
                        long consumerBatch = refill.input().amount() / gcd;
                        long refillBatch = returnedState.amount() / gcd;
                        long consumedPerCycle = Sat.mul(consumed.amount(), consumerBatch);
                        long recoveredPerCycle = Sat.mul(refill.pattern().outputAmount(), refillBatch);
                        if (recoveredPerCycle <= consumedPerCycle) {
                           long totalConsumed = Sat.mul(consumed.amount(), consumerFirings);
                           long reusableSeed = Math.min(totalConsumed, Math.min(consumedPerCycle, recoveredPerCycle));
                           if (reusableSeed > 0L) {
                              long returnedUnits = Sat.mul(returnedState.amount(), consumerFirings);
                              long maxRefillFirings = returnedUnits / refill.input().amount();
                              long maximumRecovery = Sat.mul(refill.pattern().outputAmount(), maxRefillFirings);
                              long inherentNet = Math.max(0L, totalConsumed - Math.min(totalConsumed, maximumRecovery));
                              long actualRecovery = Sat.mul(refill.pattern().outputAmount(), plan.firings().getOrDefault(refill.pattern(), 0L));
                              long actualNet = Math.max(0L, totalConsumed - Math.min(totalConsumed, actualRecovery));
                              long embeddedSeed = Math.max(0L, actualNet - inherentNet);
                              long extraSeed = Math.max(0L, reusableSeed - embeddedSeed);
                              if (extraSeed > 0L) {
                                 long seedRefillFirings = Sat.ceilDiv(extraSeed, refill.pattern().outputAmount());
                                 long returnedStateSeed = Sat.mul(refill.input().amount(), seedRefillFirings);
                                 CraftPlannerV2.SeedRequirement<K> candidate = new CraftPlannerV2.SeedRequirement<>(
                                    extraSeed, returnedState.key(), returnedStateSeed
                                 );
                                 seedRequirements.merge(consumed.key(), candidate, CraftPlannerV2::largerSeedRequirement);
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      for (Entry<K, CraftPlannerV2.SeedRequirement<K>> seed : seedRequirements.entrySet()) {
         K key = seed.getKey();
         CraftPlannerV2.SeedRequirement<K> requirement = seed.getValue();
         long returnedAlreadyUsed = used.getOrDefault(requirement.returnedState(), 0L);
         long returnedNeeded = Math.max(0L, requirement.returnedAmount() - returnedAlreadyUsed);
         long returnedAvailable = Math.max(0L, this.graph.stock(requirement.returnedState()) - returnedAlreadyUsed);
         if (returnedNeeded <= returnedAvailable) {
            if (returnedNeeded > 0L) {
               used.merge(requirement.returnedState(), returnedNeeded, Sat::add);
            }
         } else {
            long alreadyUsed = used.getOrDefault(key, 0L);
            long available = Math.max(0L, this.graph.stock(key) - alreadyUsed);
            long extracted = Math.min(requirement.consumedAmount(), available);
            if (extracted > 0L) {
               used.merge(key, extracted, Sat::add);
            }

            if (extracted < requirement.consumedAmount()) {
               missing.merge(key, requirement.consumedAmount() - extracted, Sat::add);
            }
         }
      }
   }

   private static <K> CraftPlannerV2.SeedRequirement<K> largerSeedRequirement(CraftPlannerV2.SeedRequirement<K> left, CraftPlannerV2.SeedRequirement<K> right) {
      if (right.consumedAmount() > left.consumedAmount()) {
         return right;
      } else if (right.consumedAmount() < left.consumedAmount()) {
         return left;
      } else {
         return right.returnedAmount() < left.returnedAmount() ? right : left;
      }
   }

   private CraftPlannerV2.DirectRefill<K> uniqueDirectRefill(
      K consumedState, K returnedState, Map<K, List<CraftPattern<K>>> firedByOutput, Map<CraftPattern<K>, Long> fired
   ) {
      CraftPlannerV2.DirectRefill<K> found = null;

      for (CraftPattern<K> producer : firedByOutput.getOrDefault(consumedState, List.of())) {
         if (fired.getOrDefault(producer, 0L) > 0L && ordinaryInputCount(producer) == 1) {
            for (CraftInput<K> input : producer.inputs()) {
               if (!input.returned() && input.remainder() == null && returnedState.equals(input.key())) {
                  if (found != null && found.pattern() != producer) {
                     return null;
                  }

                  found = new CraftPlannerV2.DirectRefill<>(producer, input);
               }
            }
         }
      }

      return found;
   }

   private static <K> int ordinaryInputCount(CraftPattern<K> pattern) {
      int count = 0;

      for (CraftInput<K> input : pattern.inputs()) {
         if (!input.returned() && input.remainder() == null) {
            count++;
         }
      }

      return count;
   }

   private static long gcd(long a, long b) {
      a = Math.max(1L, a);
      b = Math.max(1L, b);

      while (b != 0L) {
         long next = a % b;
         a = b;
         b = next;
      }

      return a;
   }

   private void identifyPositiveFeedbackByproducts(K target) {
      Set<K> seen = new LinkedHashSet<>();
      Deque<K> queue = new ArrayDeque<>();
      seen.add(target);
      queue.add(target);

      while (!queue.isEmpty()) {
         K output = queue.remove();

         for (CraftPattern<K> consumer : this.graph.patternsFor(output)) {
            for (CraftInput<K> input : consumer.inputs()) {
               if (seen.add(input.key())) {
                  queue.add(input.key());
               }
            }

            for (CraftOutput<K> byproduct : consumer.byproducts()) {
               if (seen.add(byproduct.key())) {
                  queue.add(byproduct.key());
               }
            }

            Set<K> ordinaryInputs = new LinkedHashSet<>();

            for (CraftInput<K> inputx : consumer.inputs()) {
               if (!inputx.returned() && inputx.remainder() == null) {
                  ordinaryInputs.add(inputx.key());
               }
            }

            for (K consumedKey : ordinaryInputs) {
               long consumedAmount = ordinaryInputAmount(consumer, consumedKey);
               if (consumedAmount > 0L) {
                  for (CraftOutput<K> byproductx : consumer.byproducts()) {
                     long returnedAmount = byproductAmount(consumer, byproductx.key());
                     if (returnedAmount > 0L) {
                        for (CraftPattern<K> refill : this.graph.patternsFor(consumedKey)) {
                           long refillInput = ordinaryInputAmount(refill, byproductx.key());
                           if (refillInput > 0L) {
                              long common = gcd(returnedAmount, refillInput);
                              long consumerBatch = refillInput / common;
                              long refillBatch = returnedAmount / common;
                              long consumedPerRound = Sat.mul(consumedAmount, consumerBatch);
                              long recoveredPerRound = Sat.mul(refill.outputAmount(), refillBatch);
                              if (recoveredPerRound > consumedPerRound) {
                                 this.suppressedPositiveFeedbackOutputs.computeIfAbsent(consumer, ignored -> new LinkedHashSet<>()).add(byproductx.key());
                              }
                           }
                        }
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
         if (key.equals(output.key())) {
            result = Sat.add(result, output.amount());
         }
      }

      return result;
   }

   private boolean mayReuseByproduct(CraftPattern<K> pattern, K key) {
      return !this.suppressedPositiveFeedbackOutputs.getOrDefault(pattern, Set.of()).contains(key);
   }

   private void indexLinearContainerBootstrapReserves() {
      this.linearContainerBootstrapReserves.clear();

      for (List<CraftPattern<K>> patterns : this.patternsByOutput.values()) {
         for (CraftPattern<K> consumer : patterns) {
            Map<K, Long> reserves = new HashMap<>();

            for (CraftInput<K> transition : consumer.inputs()) {
               K remainder = transition.remainder();
               if (remainder != null && !transition.key().equals(remainder)) {
                  long returned = byproductAmount(consumer, remainder);
                  if (returned > 0L && this.dependsOn(transition.key(), remainder)) {
                     reserves.merge(remainder, transition.amount(), Sat::add);
                  }
               }
            }

            reserves.replaceAll((key, required) -> Math.min(required, byproductAmount(consumer, (K)key)));
            reserves.values().removeIf(amount -> amount <= 0L);
            if (!reserves.isEmpty()) {
               this.linearContainerBootstrapReserves.put(consumer, Map.copyOf(reserves));
            }
         }
      }
   }

   private boolean dependsOn(K output, K requiredInput) {
      Set<K> seen = new HashSet<>();
      Deque<K> queue = new ArrayDeque<>();
      seen.add(output);
      queue.add(output);

      while (!queue.isEmpty()) {
         K current = queue.removeFirst();

         for (CraftPattern<K> pattern : this.patternsByOutput.getOrDefault(current, List.of())) {
            for (CraftInput<K> input : pattern.inputs()) {
               if (requiredInput.equals(input.key())) {
                  return true;
               }

               if (seen.add(input.key())) {
                  queue.addLast(input.key());
               }
            }
         }
      }

      return false;
   }

   private boolean hasExternalBootstrapProducer(Set<K> states, Map<K, List<CraftPattern<K>>> firedByOutput) {
      for (K state : states) {
         for (CraftPattern<K> producer : firedByOutput.getOrDefault(state, List.of())) {
            boolean consumesCycleState = producer.inputs().stream().anyMatch(input -> states.contains(input.key()));
            if (!consumesCycleState) {
               return true;
            }
         }
      }

      return false;
   }

   private void buildDag(K target, List<K> priorityRoots, List<K> postOrderOut, Set<K> itemsOut) {
      Map<K, Integer> color = new HashMap<>();

      for (K priorityRoot : priorityRoots) {
         this.buildDagRoot(priorityRoot, color, postOrderOut, itemsOut);
      }

      this.buildDagRoot(target, color, postOrderOut, itemsOut);
   }

   private void buildDagRoot(K root, Map<K, Integer> color, List<K> postOrderOut, Set<K> itemsOut) {
      if (!color.containsKey(root)) {
         Deque<CraftPlannerV2.Frame<K>> stack = new ArrayDeque<>();
         color.put(root, 1);
         itemsOut.add(root);
         stack.push(this.frameFor(root, color, itemsOut));

         while (!stack.isEmpty()) {
            CraftPlannerV2.Frame<K> f = stack.peek();
            if (f.i < f.children.size()) {
               K c = f.children.get(f.i++);
               if (color.get(c) == null) {
                  color.put(c, 1);
                  stack.push(this.frameFor(c, color, itemsOut));
               }
            } else {
               color.put(f.node, 2);
               postOrderOut.add(f.node);
               stack.pop();
            }
         }
      }
   }

   private CraftPlannerV2.Frame<K> frameFor(K x, Map<K, Integer> color, Set<K> itemsOut) {
      List<CraftPattern<K>> all = this.graph.patternsFor(x);
      List<CraftPattern<K>> usable = new ArrayList<>(all.size());
      Set<K> children = new LinkedHashSet<>();

      for (CraftPattern<K> p : all) {
         for (CraftOutput<K> byproduct : p.byproducts()) {
            this.reachableByproductKeys.add(byproduct.key());
         }

         List<CraftInput<K>> backEdges = new ArrayList<>(1);

         for (CraftInput<K> in : p.inputs()) {
            if (in.returned() && in.uses() == Long.MAX_VALUE) {
               if (!isSelfReturnedSeed(p, in) && in.reusableStockSource() == null) {
                  this.ordinaryReturnedSeedKeys.add(in.key());
               } else {
                  this.requiresSeedOrderedPlanning = true;
               }
            }

            if (!isSelfReturnedSeed(p, in) && !this.isHostBackedReusableSeed(in)) {
               Integer col = color.get(in.key());
               if (col != null && col == 1) {
                  backEdges.add(in);
               }
            }
         }

         List<CraftPlannerV2.FeedbackSeedBootstrap<K>> resolvedBackEdges = new ArrayList<>(backEdges.size());
         int resolvedBackEdgeCount = 0;

         for (CraftInput<K> backEdge : backEdges) {
            List<CraftPlannerV2.FeedbackSeedBootstrap<K>> direct = this.directFeedbackSeedBootstraps(p, backEdge);
            if (!direct.isEmpty()) {
               resolvedBackEdges.addAll(direct);
               resolvedBackEdgeCount++;
            } else {
               CraftPlannerV2.FeedbackSeedBootstrap<K> bootstrap = this.feedbackSeedBootstrapFromConverter(p, backEdge);
               if (bootstrap == null) {
                  break;
               }

               resolvedBackEdges.add(bootstrap);
               resolvedBackEdgeCount++;
            }
         }

         if (resolvedBackEdgeCount != backEdges.size()) {
            this.cutOutputs.add(x);
         } else {
            for (CraftPlannerV2.FeedbackSeedBootstrap<K> bootstrap : resolvedBackEdges) {
               this.addFeedbackSeedBootstrap(bootstrap);
            }

            usable.add(p);

            for (CraftInput<K> in : p.inputs()) {
               if (!isSelfReturnedSeed(p, in) && !this.isHostBackedReusableSeed(in) && !this.isFeedbackSeed(p, in) && !this.isFeedbackConverterInput(p, in)) {
                  children.add(in.key());
                  itemsOut.add(in.key());
               }
            }
         }
      }

      this.patternsByOutput.put(x, usable);
      return new CraftPlannerV2.Frame<>(x, new ArrayList<>(children));
   }

   private void indexSeedOrderedDependencyCone(List<K> order) {
      if (this.requiresSeedOrderedPlanning) {
         for (int i = order.size() - 1; i >= 0; i--) {
            K output = order.get(i);
            boolean affected = this.directlyRequiresSeedOrder(output);
            if (!affected) {
               label38:
               for (CraftPattern<K> pattern : this.patternsByOutput.getOrDefault(output, List.of())) {
                  for (CraftInput<K> input : pattern.inputs()) {
                     if (this.seedOrderedDependencyCone.contains(input.key())) {
                        affected = true;
                        break label38;
                     }
                  }
               }
            }

            if (affected) {
               this.seedOrderedDependencyCone.add(output);
            }
         }
      }
   }

   private boolean directlyRequiresSeedOrder(K output) {
      for (CraftPattern<K> pattern : this.patternsByOutput.getOrDefault(output, List.of())) {
         for (CraftInput<K> input : pattern.inputs()) {
            if (input.returned()
               && input.uses() == Long.MAX_VALUE
               && (isSelfReturnedSeed(pattern, input) || input.reusableStockSource() != null || this.reachableByproductKeys.contains(input.key()))) {
               return true;
            }
         }
      }

      return false;
   }

   private boolean isHostBackedReusableSeed(CraftInput<K> input) {
      return input.returned()
         && input.uses() == Long.MAX_VALUE
         && input.reusableStockSource() != null
         && this.graph.reusableStock(input.reusableStockSource(), input.key()) >= input.amount();
   }

   private void addFeedbackSeedBootstrap(CraftPlannerV2.FeedbackSeedBootstrap<K> bootstrap) {
      List<CraftPlannerV2.FeedbackSeedBootstrap<K>> seeds = this.feedbackSeedBootstraps.computeIfAbsent(bootstrap.loopPattern(), ignored -> new ArrayList<>());
      if (!seeds.contains(bootstrap)) {
         seeds.add(bootstrap);
      }

      List<CraftPlannerV2.FeedbackSeedBootstrap<K>> converters = this.feedbackSeedConverters
         .computeIfAbsent(bootstrap.converter(), ignored -> new ArrayList<>());
      if (!converters.contains(bootstrap)) {
         converters.add(bootstrap);
      }
   }

   private List<CraftPlannerV2.FeedbackSeedBootstrap<K>> directFeedbackSeedBootstraps(CraftPattern<K> loopPattern, CraftInput<K> seedInput) {
      if (seedInput.returned() && seedInput.uses() == Long.MAX_VALUE && seedInput.reusableStockSource() != null) {
         List<CraftPlannerV2.FeedbackSeedBootstrap<K>> result = new ArrayList<>();

         for (CraftPattern<K> candidate : this.patternsByOutput.getOrDefault(seedInput.key(), List.of())) {
            CraftInput<K> input = this.feedbackConverterInput(candidate, loopPattern.output(), seedInput.key());
            if (input != null) {
               result.add(new CraftPlannerV2.FeedbackSeedBootstrap<>(loopPattern, seedInput, candidate, input));
            }
         }

         return result;
      } else {
         return List.of();
      }
   }

   private CraftPlannerV2.FeedbackSeedBootstrap<K> feedbackSeedBootstrapFromConverter(CraftPattern<K> converter, CraftInput<K> converterInput) {
      for (CraftPattern<K> loopPattern : this.patternsByOutput.getOrDefault(converterInput.key(), List.of())) {
         for (CraftInput<K> seedInput : loopPattern.inputs()) {
            if (seedInput.key().equals(converter.output())
               && seedInput.returned()
               && seedInput.uses() == Long.MAX_VALUE
               && seedInput.reusableStockSource() != null
               && this.feedbackConverterInput(converter, loopPattern.output(), seedInput.key()) == converterInput) {
               return new CraftPlannerV2.FeedbackSeedBootstrap<>(loopPattern, seedInput, converter, converterInput);
            }
         }
      }

      return null;
   }

   private CraftInput<K> feedbackConverterInput(CraftPattern<K> candidate, K loopOutput, K seedKey) {
      if (!candidate.byproducts().isEmpty()) {
         return null;
      } else {
         CraftInput<K> loopInput = null;

         for (CraftInput<K> input : candidate.inputs()) {
            if (input.returned() || input.remainder() != null || input.reusableStockSource() != null) {
               return null;
            }

            if (input.key().equals(loopOutput)) {
               if (loopInput != null) {
                  return null;
               }

               loopInput = input;
            } else if (input.key().equals(seedKey)) {
               return null;
            }
         }

         return loopInput;
      }
   }

   private Map<K, Long> capacityFromOrder(List<K> order, int sizeHint) {
      Map<K, Long> cap = new HashMap<>(sizeHint * 2);

      for (int i = order.size() - 1; i >= 0; i--) {
         K x = order.get(i);
         long best = 0L;

         for (CraftPattern<K> p : this.patternsByOutput.getOrDefault(x, List.of())) {
            best = Math.max(best, this.producibleVia(p, cap));
            if (Sat.isSaturated(best)) {
               break;
            }
         }

         cap.put(x, Sat.add(this.graph.stock(x), best));
      }

      return cap;
   }

   private void indexEquivalentMaterialFootprints(List<K> order) {
      Set<K> dynamicPoolKeys = new HashSet<>();

      for (Entry<K, List<CraftPattern<K>>> entry : this.patternsByOutput.entrySet()) {
         for (CraftPattern<K> pattern : entry.getValue()) {
            if (pattern.outputAmount() > 1L) {
               dynamicPoolKeys.add(pattern.output());
            }

            for (CraftOutput<K> output : pattern.byproducts()) {
               dynamicPoolKeys.add(output.key());
            }

            for (CraftInput<K> input : pattern.inputs()) {
               if (input.returned() || input.remainder() != null || input.reusableStockSource() != null) {
                  dynamicPoolKeys.add(input.key());
                  if (input.remainder() != null) {
                     dynamicPoolKeys.add(input.remainder());
                  }
               }
            }
         }
      }

      CraftPlannerV2.FootprintInterner interner = new CraftPlannerV2.FootprintInterner();
      Map<K, Integer> footprintByKey = new HashMap<>();

      for (int i = order.size() - 1; i >= 0; i--) {
         K key = order.get(i);
         List<CraftPattern<K>> patterns = this.patternsByOutput.getOrDefault(key, List.of());
         if (patterns.isEmpty()) {
            footprintByKey.put(key, interner.intern(new CraftPlannerV2.MaterialLeaf(key)));
         } else {
            Integer common = null;
            boolean allEquivalent = true;

            for (CraftPattern<K> pattern : patterns) {
               Integer footprint = this.materialFootprint(pattern, footprintByKey, interner);
               if (footprint != null) {
                  this.materialFootprintByPattern.put(pattern, footprint);
               }

               if (footprint == null) {
                  allEquivalent = false;
               } else if (common == null) {
                  common = footprint;
               } else if (!common.equals(footprint)) {
                  allEquivalent = false;
               }
            }

            if (this.graph.stock(key) <= 0L && !dynamicPoolKeys.contains(key) && allEquivalent && common != null) {
               footprintByKey.put(key, common);
            } else {
               footprintByKey.put(key, interner.intern(new CraftPlannerV2.MaterialLeaf(key)));
            }
         }
      }
   }

   private Integer materialFootprint(CraftPattern<K> pattern, Map<K, Integer> footprintByKey, CraftPlannerV2.FootprintInterner interner) {
      if (!pattern.byproducts().isEmpty()) {
         return null;
      } else {
         Map<Integer, Long> amounts = new HashMap<>();

         for (CraftInput<K> input : pattern.inputs()) {
            if (input.returned() || input.remainder() != null || input.reusableStockSource() != null) {
               return null;
            }

            Integer inputFootprint = footprintByKey.get(input.key());
            if (inputFootprint == null) {
               return null;
            }

            long previous = amounts.getOrDefault(inputFootprint, 0L);
            if (Long.MAX_VALUE - previous < input.amount()) {
               return null;
            }

            amounts.put(inputFootprint, previous + input.amount());
         }

         List<CraftPlannerV2.MaterialTerm> terms = new ArrayList<>(amounts.size());

         for (Entry<Integer, Long> entry : amounts.entrySet()) {
            terms.add(new CraftPlannerV2.MaterialTerm(entry.getKey(), entry.getValue()));
         }

         terms.sort((left, right) -> Integer.compare(left.footprint(), right.footprint()));
         return interner.intern(new CraftPlannerV2.MaterialRecipe(pattern.outputAmount(), List.copyOf(terms)));
      }
   }

   private long producibleVia(CraftPattern<K> p, Map<K, Long> cap) {
      return this.producibleVia(p, cap, null);
   }

   private long producibleVia(CraftPattern<K> p, Map<K, Long> cap, Map<CraftPattern<K>, Long> memo) {
      if (memo != null) {
         Long cached = memo.get(p);
         if (cached != null) {
            return cached;
         }
      }

      long bound = 2305843009213693951L;
      boolean feedbackSeedsBootstrappable = this.canBootstrapAllFeedbackSeeds(p, cap);

      for (CraftInput<K> in : p.inputs()) {
         long c;
         if (in.reusableStockSource() != null) {
            c = Sat.add(this.graph.reusableStock(in.reusableStockSource(), in.key()), cap.getOrDefault(in.key(), 0L));
            if (feedbackSeedsBootstrappable && this.feedbackSeedBootstrap(p, in, cap) != null) {
               c = Math.max(c, in.amount());
            }
         } else if (isSelfReturnedSeed(p, in)) {
            c = this.graph.stock(in.key());

            for (CraftPattern<K> alternative : this.patternsByOutput.getOrDefault(in.key(), List.of())) {
               if (alternative != p && !hasSelfReturnedSeed(alternative)) {
                  c = Sat.add(c, this.producibleVia(alternative, cap, memo));
                  if (c >= in.amount()) {
                     break;
                  }
               }
            }
         } else {
            c = cap.getOrDefault(in.key(), 0L);
         }

         bound = Math.min(bound, in.firingsFrom(c));
         if (bound == 0L) {
            if (memo != null) {
               memo.put(p, 0L);
            }

            return 0L;
         }
      }

      long result = Sat.mul(bound, p.outputAmount());
      if (memo != null) {
         memo.put(p, result);
      }

      return result;
   }

   private void indexCapacityOrder() {
      this.capacityScoreByPattern.clear();
      this.capacityOrderByOutput.clear();

      for (Entry<K, List<CraftPattern<K>>> entry : this.patternsByOutput.entrySet()) {
         List<CraftPattern<K>> ordered = new ArrayList<>(entry.getValue());

         for (CraftPattern<K> pattern : ordered) {
            this.capacityScore(pattern);
         }

         ordered = this.groupedCapacityOrder(ordered, this::capacityScore, this::preexistingStockCapacity);
         this.capacityOrderByOutput.put(entry.getKey(), List.copyOf(ordered));
      }
   }

   private long capacityScore(CraftPattern<K> pattern) {
      Long cached = this.capacityScoreByPattern.get(pattern);
      return cached != null ? cached : this.producibleVia(pattern, this.capacity, this.capacityScoreByPattern);
   }

   private long preexistingStockCapacity(CraftPattern<K> pattern) {
      long bound = 2305843009213693951L;

      for (CraftInput<K> input : pattern.inputs()) {
         bound = Math.min(bound, input.firingsFrom(this.graph.stock(input.key())));
         if (bound == 0L) {
            return 0L;
         }
      }

      return Sat.mul(bound, pattern.outputAmount());
   }

   private void indexDirectRawConsumables() {
      this.directRawConsumablesByPattern.clear();

      for (List<CraftPattern<K>> patterns : this.patternsByOutput.values()) {
         for (CraftPattern<K> pattern : patterns) {
            Map<K, Long> perFiring = new HashMap<>();

            for (CraftInput<K> input : pattern.inputs()) {
               if (!input.returned() && input.reusableStockSource() == null && this.patternsByOutput.getOrDefault(input.key(), List.of()).isEmpty()) {
                  perFiring.merge(input.key(), input.amount(), Sat::add);
               }
            }

            if (!perFiring.isEmpty()) {
               this.directRawConsumablesByPattern.put(pattern, Map.copyOf(perFiring));
            }
         }
      }
   }

   private List<CraftPattern<K>> capacityOrder(K key) {
      List<CraftPattern<K>> ordered = this.capacityOrderByOutput.getOrDefault(key, this.patternsByOutput.getOrDefault(key, List.of()));
      return this.promotePreferredRoute(key, ordered);
   }

   private List<CraftPattern<K>> promotePreferredRoute(K key, List<CraftPattern<K>> ordered) {
      CraftPattern<K> preferred = this.routePreferences.get(key);
      int index = preferred == null ? -1 : ordered.indexOf(preferred);
      if (index <= 0) {
         return ordered;
      } else {
         List<CraftPattern<K>> promoted = new ArrayList<>(ordered.size());
         promoted.add(preferred);

         for (CraftPattern<K> pattern : ordered) {
            if (pattern != preferred) {
               promoted.add(pattern);
            }
         }

         return promoted;
      }
   }

   private boolean canBootstrapAllFeedbackSeeds(CraftPattern<K> pattern, Map<K, Long> materialCapacity) {
      List<CraftPlannerV2.FeedbackSeedBootstrap<K>> bootstraps = this.feedbackSeedBootstraps.get(pattern);
      if (bootstraps != null && !bootstraps.isEmpty()) {
         long requiredOutput = 0L;
         long availableOutput = this.graph.stock(pattern.output());
         Set<Object> countedStorageScopes = new HashSet<>();
         boolean foundSeed = false;

         for (CraftInput<K> seed : pattern.inputs()) {
            CraftPlannerV2.FeedbackSeedBootstrap<K> bootstrap = this.feedbackSeedBootstrap(pattern, seed, materialCapacity);
            if (bootstrap != null) {
               foundSeed = true;
               long hostAvailable = this.graph.reusableStock(seed.reusableStockSource(), seed.key());
               long seedShortfall = Math.max(0L, seed.amount() - hostAvailable);
               if (this.feedbackBootstrapSeedCapacity(bootstrap, materialCapacity) < seedShortfall) {
                  return false;
               }

               requiredOutput = Sat.add(requiredOutput, bootstrap.outputUnitsFor(seedShortfall));
               Object storageScope = seed.reusableStockSource().storageScope();
               if (countedStorageScopes.add(storageScope)) {
                  availableOutput = Sat.add(availableOutput, this.graph.reusableStock(storageScope, pattern.output()));
               }
            }
         }

         return foundSeed && availableOutput >= requiredOutput;
      } else {
         return false;
      }
   }

   private CraftPlan<K> linearPass(List<K> order, K target, long amount) {
      CraftPlannerV2.LinearPassState<K> pass = this.linearPassState(order, target, amount, Map.of());
      boolean feasible = pass.miss().isEmpty();
      return new CraftPlan<>(true, feasible, pass.fired(), pass.used(), Map.of(), pass.miss(), pass.gross(), pass.done(), false);
   }

   private CraftPlan<K> allocationRepair(List<K> order, K target, long amount) {
      int contended = this.preparedGraph.contendedOutputCount;
      if (contended == 0) {
         return null;
      } else {
         int maxIterations = (int)Math.min(64L, 4L + 2L * (long)contended);
         Map<CraftPattern<K>, Long> caps = new IdentityHashMap<>();
         Set<CraftPattern<K>> ineffective = Collections.newSetFromMap(new IdentityHashMap<>());

         for (int i = 0; i < maxIterations; i++) {
            if (!this.searchBudget.tryConsume()) {
               return null;
            }

            this.diagnostics.recordDynamicCapacityEvaluation();
            CraftPlannerV2.LinearPassState<K> pass = this.linearPassState(order, target, amount, caps);
            if (pass.miss().isEmpty()) {
               return new CraftPlan<>(true, true, pass.fired(), pass.used(), Map.of(), Map.of(), pass.gross(), pass.done(), false);
            }

            if (!this.tightenBlamedRoute(pass, caps, ineffective)) {
               return null;
            }
         }

         return null;
      }
   }

   private boolean tightenBlamedRoute(CraftPlannerV2.LinearPassState<K> pass, Map<CraftPattern<K>, Long> caps, Set<CraftPattern<K>> ineffective) {
      K shortLeaf = null;
      long shortfall = 0L;

      for (Entry<K, Long> entry : pass.miss().entrySet()) {
         if (entry.getValue() > shortfall) {
            shortLeaf = entry.getKey();
            shortfall = entry.getValue();
         }
      }

      if (shortLeaf == null) {
         return false;
      } else {
         Map<K, List<Entry<CraftPattern<K>, Long>>> consumers = new HashMap<>();

         for (Entry<CraftPattern<K>, Long> firing : pass.fired().entrySet()) {
            CraftPattern<K> r = firing.getKey();

            for (CraftInput<K> in : r.inputs()) {
               long units = in.unitsFor(firing.getValue());
               if (units > 0L) {
                  consumers.computeIfAbsent(in.key(), ignored -> new ArrayList<>()).add(Map.entry(r, units));
               }
            }
         }

         Map<K, Double> blameByItem = new HashMap<>();
         blameByItem.put(shortLeaf, (double)shortfall);
         CraftPattern<K> blamed = null;
         double blamedShare = 0.0;
         List<K> order = this.preparedGraph.order;

         for (int i = order.size() - 1; i >= 0; i--) {
            K item = order.get(i);
            Double blame = blameByItem.get(item);
            if (blame != null && !(blame <= 0.0)) {
               long demand = pass.need().getOrDefault(item, 0L);
               if (demand > 0L) {
                  for (Entry<CraftPattern<K>, Long> consumer : consumers.getOrDefault(item, List.of())) {
                     CraftPattern<K> r = consumer.getKey();
                     double share = blame * (double)consumer.getValue().longValue() / (double)demand;
                     if (!(share <= 0.0)) {
                        if (this.isContendedOutput(r.output()) && !ineffective.contains(r) && share > blamedShare) {
                           blamed = r;
                           blamedShare = share;
                        }

                        blameByItem.merge(r.output(), share, Double::sum);
                     }
                  }
               }
            }
         }

         if (blamed == null) {
            return false;
         } else {
            long allocated = pass.allocatedUnits().getOrDefault(blamed, 0L);
            long leafDemand = pass.need().getOrDefault(shortLeaf, 0L);
            double contribution = blamedShare / (double)shortfall * (double)leafDemand;
            long delta = contribution <= 0.0 ? allocated : (long)Math.ceil((double)shortfall * (double)allocated / contribution);
            long newCap = Math.max(0L, allocated - Math.max(1L, delta));
            Long existing = caps.get(blamed);
            if (existing != null && existing <= newCap) {
               ineffective.add(blamed);
               return this.tightenBlamedRoute(pass, caps, ineffective);
            } else {
               caps.put(blamed, newCap);
               return true;
            }
         }
      }
   }

   private boolean isContendedOutput(K output) {
      List<CraftPattern<K>> ps = this.patternsByOutput.getOrDefault(output, List.of());
      return ps.size() > 1 && this.distinctMaterialBranches(this.capacityOrder(output)).size() > 1;
   }

   private CraftPlannerV2.LinearPassState<K> linearPassState(List<K> order, K target, long amount, Map<CraftPattern<K>, Long> capUnits) {
      Map<K, Long> need = new HashMap<>();
      Map<K, Long> bp = new HashMap<>();
      Map<K, Long> stockL = new HashMap<>();
      Map<K, Long> used = new HashMap<>();
      Map<K, Long> miss = new HashMap<>();
      Map<K, Long> gross = new HashMap<>();
      Map<K, Long> returnedSeedReserve = new HashMap<>();
      Map<CraftPattern<K>, Long> fired = new IdentityHashMap<>();
      Map<CraftPattern<K>, Long> allocatedUnits = new IdentityHashMap<>();
      need.put(target, amount);
      int done = 0;

      for (K x : order) {
         long d = need.getOrDefault(x, 0L);
         if (d > 0L) {
            done++;
            gross.put(x, d);
            long fromBp = Math.min(d, this.lget(bp, x));
            if (fromBp > 0L) {
               bp.put(x, this.lget(bp, x) - fromBp);
               d -= fromBp;
            }

            long fromStock = Math.min(d, stockL.computeIfAbsent(x, this.graph::stock));
            if (fromStock > 0L) {
               stockL.put(x, this.lget(stockL, x) - fromStock);
               used.merge(x, fromStock, Sat::add);
               d -= fromStock;
            }

            if (d > 0L) {
               List<CraftPattern<K>> ps = this.patternsByOutput.getOrDefault(x, List.of());
               if (ps.isEmpty()) {
                  miss.merge(x, d, Sat::add);
               } else {
                  this.allocateLinear(x, d, ps, need, bp, returnedSeedReserve, fired, capUnits, allocatedUnits);
               }
            }
         }
      }

      return new CraftPlannerV2.LinearPassState<>(need, fired, used, miss, gross, allocatedUnits, done);
   }

   private void allocateLinear(
      K x,
      long d,
      List<CraftPattern<K>> ps,
      Map<K, Long> need,
      Map<K, Long> bp,
      Map<K, Long> returnedSeedReserve,
      Map<CraftPattern<K>, Long> fired,
      Map<CraftPattern<K>, Long> capUnits,
      Map<CraftPattern<K>, Long> allocatedUnits
   ) {
      for (CraftPattern<K> r : this.groupedCapacityOrder(
         ps, pattern -> this.capRemainingVia(pattern, need), pattern -> this.preexistingStockRemainingCapacity(pattern, need)
      )) {
         if (d <= 0L) {
            break;
         }

         long p = this.capRemainingVia(r, need);
         Long cap = capUnits.get(r);
         if (cap != null) {
            p = Math.min(p, Math.max(0L, cap - allocatedUnits.getOrDefault(r, 0L)));
         }

         if (p > 0L) {
            long make = Math.min(d, p);
            long t = Sat.ceilDiv(make, r.outputAmount());
            long consumed = Math.min(d, Sat.mul(t, r.outputAmount()));
            allocatedUnits.merge(r, consumed, Sat::add);
            this.fireLinear(x, r, t, consumed, need, bp, returnedSeedReserve, fired);
            d -= consumed;
         }
      }

      if (d > 0L) {
         CraftPattern<K> r0 = ps.get(0);
         long t = Sat.ceilDiv(d, r0.outputAmount());
         allocatedUnits.merge(r0, d, Sat::add);
         this.fireLinear(x, r0, t, d, need, bp, returnedSeedReserve, fired);
      }
   }

   private void fireLinear(
      K x, CraftPattern<K> r, long t, long consumedOwn, Map<K, Long> need, Map<K, Long> bp, Map<K, Long> returnedSeedReserve, Map<CraftPattern<K>, Long> fired
   ) {
      long previousFirings = fired.getOrDefault(r, 0L);
      fired.merge(r, t, Sat::add);

      for (CraftInput<K> in : r.inputs()) {
         long amt = in.unitsFor(t);
         if (in.returned() && in.uses() == Long.MAX_VALUE && in.reusableStockSource() == null) {
            long previousReserve = returnedSeedReserve.getOrDefault(in.key(), 0L);
            if (amt > previousReserve) {
               need.merge(in.key(), amt - previousReserve, Sat::add);
               returnedSeedReserve.put(in.key(), amt);
            }
         } else {
            need.merge(in.key(), amt, Sat::add);
         }
      }

      Map<K, Long> bootstrapReserves = new HashMap<>(this.linearContainerBootstrapReserves.getOrDefault(r, Map.of()));
      bootstrapReserves.replaceAll((key, reserve) -> Math.max(0L, reserve - Math.min(reserve, Sat.mul(byproductAmount(r, (K)key), previousFirings))));

      for (CraftOutput<K> out : r.byproducts()) {
         if (this.mayReuseByproduct(r, out.key())) {
            long produced = Sat.mul(out.amount(), t);
            long withheld = Math.min(produced, bootstrapReserves.getOrDefault(out.key(), 0L));
            if (withheld > 0L) {
               bootstrapReserves.put(out.key(), bootstrapReserves.get(out.key()) - withheld);
            }

            if (produced > withheld) {
               bp.merge(out.key(), produced - withheld, Sat::add);
            }
         }
      }

      long surplus = Sat.mul(t, r.outputAmount()) - consumedOwn;
      if (surplus > 0L) {
         bp.merge(x, surplus, Sat::add);
      }
   }

   private long capRemainingVia(CraftPattern<K> r, Map<K, Long> need) {
      long bound = 2305843009213693951L;

      for (CraftInput<K> in : r.inputs()) {
         long cr = Math.max(0L, this.capacity.getOrDefault(in.key(), 0L) - need.getOrDefault(in.key(), 0L));
         bound = Math.min(bound, in.firingsFrom(cr));
         if (bound == 0L) {
            return 0L;
         }
      }

      return Sat.mul(bound, r.outputAmount());
   }

   private long preexistingStockRemainingCapacity(CraftPattern<K> pattern, Map<K, Long> need) {
      long bound = 2305843009213693951L;

      for (CraftInput<K> input : pattern.inputs()) {
         long remaining = Math.max(0L, this.graph.stock(input.key()) - need.getOrDefault(input.key(), 0L));
         bound = Math.min(bound, input.firingsFrom(remaining));
         if (bound == 0L) {
            return 0L;
         }
      }

      return Sat.mul(bound, pattern.outputAmount());
   }

   private List<CraftPattern<K>> groupedCapacityOrder(
      List<CraftPattern<K>> patterns, ToLongFunction<CraftPattern<K>> totalCapacity, ToLongFunction<CraftPattern<K>> directStockCapacity
   ) {
      if (patterns.size() < 2) {
         return patterns;
      } else {
         List<List<CraftPattern<K>>> groups = new ArrayList<>();
         IdentityHashMap<Object, List<CraftPattern<K>>> bySource = new IdentityHashMap<>();

         for (CraftPattern<K> pattern : patterns) {
            Object source = pattern.source();
            if (source == null) {
               groups.add(new ArrayList<>(List.of(pattern)));
            } else {
               List<CraftPattern<K>> group = bySource.get(source);
               if (group == null) {
                  group = new ArrayList<>();
                  bySource.put(source, group);
                  groups.add(group);
               }

               group.add(pattern);
            }
         }

         for (List<CraftPattern<K>> group : groups) {
            if (group.size() > 1) {
               group.sort(
                  (left, right) -> {
                     int byStock = Long.compare(directStockCapacity.applyAsLong((CraftPattern<K>)right), directStockCapacity.applyAsLong((CraftPattern<K>)left));
                     return byStock != 0
                        ? byStock
                        : Long.compare(totalCapacity.applyAsLong((CraftPattern<K>)right), totalCapacity.applyAsLong((CraftPattern<K>)left));
                  }
               );
            }
         }

         groups.sort(
            (left, right) -> Long.compare(
                  this.maxCapacity((List<CraftPattern<K>>)right, totalCapacity), this.maxCapacity((List<CraftPattern<K>>)left, totalCapacity)
               )
         );
         List<CraftPattern<K>> ordered = new ArrayList<>(patterns.size());

         for (List<CraftPattern<K>> groupx : groups) {
            ordered.addAll(groupx);
         }

         return ordered;
      }
   }

   private long maxCapacity(List<CraftPattern<K>> patterns, ToLongFunction<CraftPattern<K>> capacity) {
      long best = 0L;

      for (CraftPattern<K> pattern : patterns) {
         best = Math.max(best, capacity.applyAsLong(pattern));
      }

      return best;
   }

   private long lget(Map<K, Long> m, K k) {
      Long v = m.get(k);
      return v == null ? 0L : v;
   }

   private long obtain(K x, long d, boolean commitFailure) {
      if (d <= 0L) {
         return 0L;
      } else {
         this.bump(this.grossDemand, x, d);
         this.reserveSelfSeed(x);
         this.reserveFeedbackSeedOutput(x, d);
         d -= this.drawPools(x, d);
         if (d <= 0L) {
            return 0L;
         } else if (this.depth >= MAX_OBTAIN_DEPTH) {
            if (commitFailure) {
               this.addMissing(x, d);
            }

            return d;
         } else {
            List<CraftPattern<K>> ps = this.patternsByOutput.getOrDefault(x, List.of());
            if (ps.isEmpty()) {
               if (commitFailure) {
                  this.addMissing(x, d);
               }

               return d;
            } else if (this.isAggregable(x)) {
               return this.obtainAggregate(x, d, commitFailure);
            } else {
               CraftPlannerV2.SearchFailure<K> failureKey = new CraftPlannerV2.SearchFailure<>(x, d, this.availabilityState, this.depth);
               if (!commitFailure && this.failedSpeculativeSearches.contains(failureKey)) {
                  this.diagnostics.recordFailureMemoHit();
                  return d;
               } else if (!this.diagnostics.tryConsumeResolutionWork()) {
                  return commitFailure ? this.commitBudgetFallback(x, d) : d;
               } else {
                  if (this.processed < Integer.MAX_VALUE) {
                     this.processed++;
                  }

                  if (ps.size() == 1) {
                     long unmet = this.fire(x, ps.get(0), d, !commitFailure);
                     if (!commitFailure && unmet > 0L && !this.searchBudget.exhausted() && !this.diagnostics.resolutionExhausted()) {
                        this.failedSpeculativeSearches.add(failureKey);
                     }

                     return unmet;
                  } else {
                     List<CraftPattern<K>> ordered = this.capacityOrder(x);
                     List<CraftPattern<K>> distinctBranches = this.distinctMaterialBranches(ordered);
                     if (distinctBranches.size() == 1) {
                        long unmet = this.fire(x, distinctBranches.get(0), d, !commitFailure);
                        if (!commitFailure && unmet > 0L && !this.searchBudget.exhausted() && !this.diagnostics.resolutionExhausted()) {
                           this.failedSpeculativeSearches.add(failureKey);
                        }

                        return unmet;
                     } else if (this.searchBudget.exhausted()) {
                        return commitFailure ? this.commitBudgetFallback(x, d) : d;
                     } else {
                        int v = this.visit.getOrDefault(x, 0);
                        if (v >= this.visitCap) {
                           this.diagnostics.recordHotNodeVisit();
                           return this.obtainHot(x, d, commitFailure, failureKey);
                        } else {
                           this.visit.put(x, v + 1);

                           for (CraftPattern<K> r : distinctBranches) {
                              if (!this.hasProvenDirectConsumableShortfall(r, d)) {
                                 if (!this.searchBudget.tryConsume()) {
                                    return commitFailure ? this.commitBestEffort(distinctBranches, x, d) : d;
                                 }

                                 int mark = this.trail.size();
                                 long beforeMissing = this.missingTotal;
                                 this.recordRouteDecision(x, r, distinctBranches);
                                 long unmet = this.fire(x, r, d, true);
                                 if (this.searchBudget.exhausted() || this.diagnostics.resolutionExhausted()) {
                                    this.rollback(mark);
                                    return commitFailure ? this.commitBestEffort(distinctBranches, x, d) : d;
                                 }

                                 if (unmet == 0L && this.missingTotal == beforeMissing) {
                                    return unmet;
                                 }

                                 this.rollback(mark);
                              }
                           }

                           if (!commitFailure) {
                              this.failedSpeculativeSearches.add(failureKey);
                              return d;
                           } else {
                              return this.commitBestEffort(distinctBranches, x, d);
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private long obtainHot(K x, long d, boolean commitFailure, CraftPlannerV2.SearchFailure<K> failureKey) {
      List<CraftPattern<K>> distinctBranches = this.hotRouteOrder(x);
      if (this.searchBudget.exhausted()) {
         return commitFailure ? this.commitBestEffort(distinctBranches, x, d) : d;
      } else {
         for (CraftPattern<K> route : distinctBranches) {
            if (!this.hasProvenDirectConsumableShortfall(route, d)) {
               if (!this.searchBudget.tryConsume()) {
                  return commitFailure ? this.commitBestEffort(distinctBranches, x, d) : d;
               }

               int mark = this.trail.size();
               long beforeMissing = this.missingTotal;
               this.recordRouteDecision(x, route, distinctBranches);
               long unmet = this.fire(x, route, d, true);
               if (this.searchBudget.exhausted() || this.diagnostics.resolutionExhausted()) {
                  this.rollback(mark);
                  return commitFailure ? this.commitBestEffort(distinctBranches, x, d) : d;
               }

               if (unmet == 0L && this.missingTotal == beforeMissing) {
                  return 0L;
               }

               this.rollback(mark);
            }
         }

         if (!commitFailure) {
            this.failedSpeculativeSearches.add(failureKey);
            return d;
         } else {
            return this.commitBestEffort(distinctBranches, x, d);
         }
      }
   }

   private List<CraftPattern<K>> hotRouteOrder(K x) {
      List<CraftPattern<K>> routes = new ArrayList<>(this.distinctMaterialBranches(this.capacityOrder(x)));
      if (routes.size() < 2) {
         return routes;
      } else {
         Map<K, Long> currentCapacity = new HashMap<>();
         Map<CraftPattern<K>, Long> scores = new IdentityHashMap<>();

         for (CraftPattern<K> route : routes) {
            scores.put(route, this.currentCapacityVia(route, currentCapacity, new HashSet<>()));
            if (this.searchBudget.exhausted()) {
               return routes;
            }
         }

         routes.sort((left, right) -> Long.compare(scores.get(right), scores.get(left)));
         return this.promotePreferredRoute(x, routes);
      }
   }

   private boolean hasProvenDirectConsumableShortfall(CraftPattern<K> pattern, long demand) {
      long times = Sat.ceilDiv(demand, pattern.outputAmount());

      for (Entry<K, Long> entry : this.directRawConsumablesByPattern.getOrDefault(pattern, Map.of()).entrySet()) {
         long required = Sat.mul(entry.getValue(), times);
         CraftPlannerV2.ConsumableProofState<K> state = new CraftPlannerV2.ConsumableProofState<>(entry.getKey(), this.availabilityState);
         Long unavailableFrom = this.provenDirectConsumableShortfalls.get(state);
         if (unavailableFrom != null && required >= unavailableFrom) {
            return true;
         }

         long available = Sat.add(get(this.stockLeft, entry.getKey()), get(this.bpPool, entry.getKey()));
         if (available < required) {
            this.provenDirectConsumableShortfalls.merge(state, required, Math::min);
            return true;
         }
      }

      return false;
   }

   private long currentCapacityVia(CraftPattern<K> pattern, Map<K, Long> memo, Set<K> evaluating) {
      this.diagnostics.recordDynamicCapacityEvaluation();
      if (!this.searchBudget.tryConsume()) {
         return 0L;
      } else {
         long bound = 2305843009213693951L;

         for (CraftInput<K> input : pattern.inputs()) {
            if (input.returned() || input.reusableStockSource() != null) {
               return this.capacityScore(pattern);
            }

            long available = this.currentCapacity(input.key(), memo, evaluating);
            bound = Math.min(bound, input.firingsFrom(available));
            if (bound == 0L) {
               return 0L;
            }
         }

         return Sat.mul(bound, pattern.outputAmount());
      }
   }

   private long currentCapacity(K key, Map<K, Long> memo, Set<K> evaluating) {
      Long cached = memo.get(key);
      if (cached != null) {
         return cached;
      } else {
         long immediate = Sat.add(get(this.stockLeft, key), get(this.bpPool, key));
         if (!evaluating.add(key)) {
            return immediate;
         } else {
            long bestCrafted = 0L;

            for (CraftPattern<K> pattern : this.patternsByOutput.getOrDefault(key, List.of())) {
               bestCrafted = Math.max(bestCrafted, this.currentCapacityVia(pattern, memo, evaluating));
               if (this.searchBudget.exhausted()) {
                  evaluating.remove(key);
                  return immediate;
               }

               if (Sat.isSaturated(bestCrafted)) {
                  break;
               }
            }

            evaluating.remove(key);
            long result = Sat.add(immediate, bestCrafted);
            memo.put(key, result);
            return result;
         }
      }
   }

   private List<CraftPattern<K>> distinctMaterialBranches(List<CraftPattern<K>> ordered) {
      if (ordered.size() >= 2 && !this.materialFootprintByPattern.isEmpty()) {
         Set<Integer> seen = new HashSet<>();
         List<CraftPattern<K>> distinct = new ArrayList<>(ordered.size());

         for (CraftPattern<K> pattern : ordered) {
            Integer footprint = this.materialFootprintByPattern.get(pattern);
            if (footprint == null || seen.add(footprint)) {
               distinct.add(pattern);
            }
         }

         this.diagnostics.recordEquivalentRoutesPruned(ordered.size() - distinct.size());
         return distinct;
      } else {
         return ordered;
      }
   }

   private boolean isAggregable(K y) {
      if (this.seedOrderedDependencyCone.contains(y)) {
         return false;
      } else {
         Boolean cached = this.aggregableMemo.get(y);
         if (cached != null) {
            return cached;
         } else {
            boolean result = this.computeAggregable(y);
            this.aggregableMemo.put(y, result);
            return result;
         }
      }
   }

   private boolean computeAggregable(K y) {
      if (this.cutOutputs.contains(y)) {
         return false;
      } else {
         List<CraftPattern<K>> ps = this.patternsByOutput.getOrDefault(y, List.of());
         if (ps.isEmpty()) {
            return false;
         } else if (ps.size() > 1 && this.distinctMaterialBranches(this.capacityOrder(y)).size() > 1) {
            return false;
         } else {
            for (CraftPattern<K> r : ps) {
               if (!r.byproducts().isEmpty()
                  || this.suppressedPositiveFeedbackOutputs.containsKey(r)
                  || this.feedbackSeedBootstraps.containsKey(r)
                  || this.feedbackSeedConverters.containsKey(r)) {
                  return false;
               }

               for (CraftInput<K> in : r.inputs()) {
                  if (in.returned() || in.remainder() != null || in.reusableStockSource() != null) {
                     return false;
                  }

                  if (this.byproductFeedableKeys().contains(in.key())) {
                     return false;
                  }
               }
            }

            return true;
         }
      }
   }

   private Set<K> byproductFeedableKeys() {
      if (this.byproductFeedableKeys == null) {
         Set<K> keys = new HashSet<>();

         for (List<CraftPattern<K>> patterns : this.patternsByOutput.values()) {
            for (CraftPattern<K> pattern : patterns) {
               for (CraftOutput<K> out : pattern.byproducts()) {
                  keys.add(out.key());
               }
            }
         }

         this.byproductFeedableKeys = keys;
      }

      return this.byproductFeedableKeys;
   }

   private long obtainAggregate(K x, long d, boolean commitFailure) {
      Map<K, Long> need = new HashMap<>();
      need.put(x, d);
      long unmet = 0L;
      boolean reached = false;
      Iterator var9 = this.preparedGraph.order.iterator();

      while (true) {
         K y;
         while (true) {
            if (!var9.hasNext()) {
               return unmet;
            }

            y = (K)var9.next();
            if (reached) {
               break;
            }

            if (y.equals(x)) {
               reached = true;
               break;
            }
         }

         long dy = need.getOrDefault(y, 0L);
         if (dy > 0L) {
            if (!y.equals(x)) {
               this.bump(this.grossDemand, y, dy);
               this.reserveSelfSeed(y);
               this.reserveFeedbackSeedOutput(y, dy);
               dy -= this.drawPools(y, dy);
               if (dy <= 0L) {
                  continue;
               }

               List<CraftPattern<K>> psy = this.patternsByOutput.getOrDefault(y, List.of());
               if (psy.isEmpty()) {
                  unmet = Sat.add(unmet, dy);
                  if (!commitFailure) {
                     return unmet;
                  }

                  this.addMissing(y, dy);
                  continue;
               }

               if (!this.isAggregable(y)) {
                  this.depth++;

                  long u;
                  try {
                     u = this.obtain(y, dy, commitFailure);
                  } finally {
                     this.depth--;
                  }

                  unmet = Sat.add(unmet, u);
                  if (!commitFailure && (u > 0L || this.searchBudget.exhausted() || this.diagnostics.resolutionExhausted())) {
                     return unmet;
                  }
                  continue;
               }
            }

            List<CraftPattern<K>> psyx = this.patternsByOutput.getOrDefault(y, List.of());
            CraftPattern<K> r = psyx.size() == 1 ? psyx.get(0) : this.capacityOrder(y).get(0);
            if (this.processed < Integer.MAX_VALUE) {
               this.processed++;
            }

            long times = Sat.ceilDiv(dy, r.outputAmount());
            this.bumpFiring(r, times);

            for (CraftInput<K> in : r.inputs()) {
               need.merge(in.key(), in.unitsFor(times), Sat::add);
            }

            long surplus = Sat.mul(times, r.outputAmount()) - dy;
            if (surplus > 0L) {
               this.bump(this.bpPool, y, surplus);
            }
         }
      }
   }

   private long commitBudgetFallback(K x, long d) {
      if (!this.fallbackBudget.tryConsume()) {
         this.addMissing(x, d);
         return d;
      } else {
         List<CraftPattern<K>> routes = this.distinctMaterialBranches(this.capacityOrder(x));
         if (routes.isEmpty()) {
            this.addMissing(x, d);
            return d;
         } else {
            return this.commitBestEffort(routes, x, d);
         }
      }
   }

   private long commitBestEffort(List<CraftPattern<K>> ps, K x, long d) {
      this.recordRouteDecision(x, ps.get(0), ps);
      return this.fire(x, ps.get(0), d, false);
   }

   private long fire(K x, CraftPattern<K> r, long d, boolean search) {
      long entryMissing = this.missingTotal;
      long times = Sat.ceilDiv(d, r.outputAmount());
      this.bumpFiring(r, times);
      boolean detectSiblingConflict = !search && r.inputs().size() > 1;
      Map<K, Long> usedAtEntry = (Map<K, Long>)(detectSiblingConflict ? new HashMap<>(this.usedStock) : Map.of());
      int decisionsAtEntry = this.routeDecisions.size();
      long inputUnmet = 0L;

      for (CraftInput<K> in : r.inputs()) {
         int decisionsBeforeInput = this.routeDecisions.size();
         Map<K, Long> missingBeforeInput = (Map<K, Long>)(detectSiblingConflict && decisionsBeforeInput > decisionsAtEntry
            ? new HashMap<>(this.missing)
            : Map.of());
         long amt = in.unitsFor(times);
         CraftPlannerV2.ReusableSeedAcquisition reusableAcquisition = null;
         long unmet;
         if (in.reusableStockSource() != null) {
            reusableAcquisition = this.obtainReusableSeed(r, in, amt, search);
            unmet = reusableAcquisition.unmet();
         } else if (isSelfReturnedSeed(r, in)) {
            long obtained = this.drawReservedSelfSeed(in.key(), amt);
            if (obtained < amt) {
               obtained = Sat.add(obtained, this.drawPools(in.key(), amt - obtained));
            }

            long stillNeeded = amt - obtained;
            unmet = stillNeeded > 0L ? this.craftSelfSeedFromAlternative(in.key(), stillNeeded, r) : 0L;
            if (!search && unmet > 0L) {
               this.addMissing(in.key(), unmet);
            }
         } else {
            this.depth++;

            try {
               unmet = this.obtain(in.key(), amt, !search);
            } finally {
               this.depth--;
            }
         }

         inputUnmet = Sat.add(inputUnmet, unmet);
         if (!search || !this.searchBudget.exhausted() && !this.diagnostics.resolutionExhausted()) {
            if (detectSiblingConflict && decisionsBeforeInput > decisionsAtEntry && this.hasSiblingStockConflict(usedAtEntry, missingBeforeInput)) {
               this.rememberReplayDecisions(decisionsAtEntry, decisionsBeforeInput);
            }

            if (in.returned() && in.uses() == Long.MAX_VALUE) {
               long returned = amt - unmet;
               if (returned > 0L) {
                  if (in.reusableStockSource() != null) {
                     ReusableStockSource source = in.reusableStockSource();
                     ReusableStockRouteKey<K> route = new ReusableStockRouteKey<>(source, in.key());
                     if (reusableAcquisition.sharedReturnable() > 0L) {
                        this.bump(this.reusablePool, new ReusableStockKey<>(source.poolScope(), in.key()), reusableAcquisition.sharedReturnable());
                     }

                     if (reusableAcquisition.privateReturnable() > 0L) {
                        this.bump(this.reusablePrivatePool, route, reusableAcquisition.privateReturnable());
                     }
                  } else {
                     this.bump(this.bpPool, in.key(), returned);
                  }
               }
            }

            if (!search || inputUnmet <= 0L && this.missingTotal <= entryMissing) {
               continue;
            }

            return inputUnmet;
         }

         return inputUnmet;
      }

      long produced = Sat.mul(times, r.outputAmount());
      long surplus = produced - d;
      if (surplus > 0L) {
         this.bump(this.bpPool, x, surplus);
      }

      for (CraftOutput<K> out : r.byproducts()) {
         if (this.mayReuseByproduct(r, out.key())) {
            this.bump(this.bpPool, out.key(), Sat.mul(out.amount(), times));
         }
      }

      return inputUnmet;
   }

   private boolean hasSiblingStockConflict(Map<K, Long> usedAtEntry, Map<K, Long> missingBeforeInput) {
      for (Entry<K, Long> entry : this.missing.entrySet()) {
         long beforeMissing = missingBeforeInput.getOrDefault(entry.getKey(), 0L);
         if (entry.getValue() > beforeMissing) {
            long beforeUsed = usedAtEntry.getOrDefault(entry.getKey(), 0L);
            if (get(this.usedStock, entry.getKey()) > beforeUsed) {
               return true;
            }
         }
      }

      return false;
   }

   private void rememberReplayDecisions(int fromInclusive, int toExclusive) {
      for (int i = fromInclusive; i < toExclusive; i++) {
         CraftPlannerV2.RouteDecision<K> decision = this.routeDecisions.get(i);
         boolean alreadyRemembered = false;

         for (CraftPlannerV2.RouteDecision<K> remembered : this.replayRouteDecisions) {
            if (remembered.key().equals(decision.key()) && remembered.selected() == decision.selected()) {
               alreadyRemembered = true;
               break;
            }
         }

         if (!alreadyRemembered) {
            this.replayRouteDecisions.add(decision);
         }
      }
   }

   private CraftPlannerV2.ReusableSeedAcquisition obtainReusableSeed(CraftPattern<K> pattern, CraftInput<K> input, long amount, boolean search) {
      ReusableStockSource source = input.reusableStockSource();
      if (source != null && amount > 0L) {
         ReusableStockRouteKey<K> route = new ReusableStockRouteKey<>(source, input.key());
         long fromPrivate = Math.min(amount, get(this.reusablePrivatePool, route));
         if (fromPrivate > 0L) {
            this.put(this.reusablePrivatePool, route, get(this.reusablePrivatePool, route) - fromPrivate);
         }

         long remaining = amount - fromPrivate;
         ReusableStockKey<K> poolKey = new ReusableStockKey<>(source.poolScope(), input.key());
         long fromPool = Math.min(remaining, get(this.reusablePool, poolKey));
         if (fromPool > 0L) {
            this.put(this.reusablePool, poolKey, get(this.reusablePool, poolKey) - fromPool);
         }

         remaining -= fromPool;
         long borrowedExact = 0L;
         long borrowedPrivate = 0L;
         if (remaining > 0L) {
            CraftPlannerV2.BorrowedReusableSeed borrowed = this.borrowReusableStock(source, input.key(), remaining);
            if (borrowed.amount() > 0L) {
               borrowedExact = borrowed.pinnedExactAmount();
               borrowedPrivate = borrowed.amount() - borrowedExact;
               remaining -= borrowed.amount();
            }
         }

         long externalExact = 0L;
         if (remaining > 0L && isSelfReturnedSeed(pattern, input)) {
            long reserved = this.drawReservedSelfSeed(input.key(), remaining);
            remaining -= reserved;
            externalExact = reserved;
         }

         if (remaining > 0L && this.isFeedbackSeed(pattern, input)) {
            long bootstrapped = this.consumeFeedbackSeedBootstrap(pattern, input, remaining);
            remaining -= bootstrapped;
            externalExact = Sat.add(externalExact, bootstrapped);
         }

         if (remaining <= 0L) {
            return new CraftPlannerV2.ReusableSeedAcquisition(
               0L, Sat.add(Sat.add(fromPool, borrowedExact), externalExact), Sat.add(fromPrivate, borrowedPrivate)
            );
         } else {
            long unmet;
            if (isSelfReturnedSeed(pattern, input)) {
               unmet = this.craftSelfSeedFromAlternative(input.key(), remaining, pattern);
               if (unmet > 0L) {
                  this.addMissing(input.key(), unmet);
               }
            } else if (this.isFeedbackSeed(pattern, input)) {
               unmet = remaining;
               if (!search) {
                  this.addMissing(input.key(), remaining);
               }
            } else {
               this.depth++;

               try {
                  unmet = this.obtain(input.key(), remaining, !search);
               } finally {
                  this.depth--;
               }
            }

            externalExact = Sat.add(externalExact, remaining - unmet);
            return new CraftPlannerV2.ReusableSeedAcquisition(
               unmet, Sat.add(Sat.add(fromPool, borrowedExact), externalExact), Sat.add(fromPrivate, borrowedPrivate)
            );
         }
      } else {
         return new CraftPlannerV2.ReusableSeedAcquisition(Math.max(0L, amount), 0L, 0L);
      }
   }

   private CraftPlannerV2.BorrowedReusableSeed borrowReusableStock(ReusableStockSource source, K plannedKey, long requested) {
      if (requested <= 0L) {
         return new CraftPlannerV2.BorrowedReusableSeed(0L, 0L);
      } else {
         ReusableStockRouteKey<K> route = new ReusableStockRouteKey<>(source, plannedKey);
         long existing = get(this.reusableBorrowedDemand, route);
         long low = 0L;
         long high = requested;
         if (!this.isReusableDemandFeasible(route, addNonNegative(existing, requested))) {
            while (low < high) {
               long distance = high - low;
               long middle = low + (distance >>> 1) + (distance & 1L);
               if (this.isReusableDemandFeasible(route, addNonNegative(existing, middle))) {
                  low = middle;
               } else {
                  high = middle - 1L;
               }
            }
         } else {
            low = requested;
         }

         if (low <= 0L) {
            return new CraftPlannerV2.BorrowedReusableSeed(0L, 0L);
         } else {
            HashMap<ReusableStockRouteKey<K>, Long> demands = new HashMap<>(this.reusableBorrowedDemand);
            demands.put(route, addNonNegative(existing, low));
            ReusableStockMatcher.Result<K> allocation = ReusableStockMatcher.allocate(
               this.availableReusableStock(), demands, candidate -> this.graph.reusableStockCandidates(candidate.source(), candidate.plannedKey())
            );
            if (!allocation.feasible()) {
               throw new IllegalStateException("feasible reusable-stock probe produced no allocation");
            } else {
               Map<ReusableStockUsageKey<K>, Long> matchedUsage = this.reusableUsage(allocation);
               ReusableStockUsageKey<K> exactUsage = new ReusableStockUsageKey<>(
                  source.storageScope(), source.poolScope(), source.routingScope(), plannedKey, plannedKey
               );
               long pinnedExact = Math.min(low, get(matchedUsage, exactUsage));
               if (pinnedExact > 0L) {
                  this.put(this.pinnedExactReusableStock, exactUsage, Sat.add(get(this.pinnedExactReusableStock, exactUsage), pinnedExact));
               }

               this.put(this.reusableBorrowedDemand, route, demands.get(route) - pinnedExact);
               allocation = ReusableStockMatcher.allocate(
                  this.availableReusableStock(),
                  this.reusableBorrowedDemand,
                  candidate -> this.graph.reusableStockCandidates(candidate.source(), candidate.plannedKey())
               );
               if (!allocation.feasible()) {
                  throw new IllegalStateException("pinning an exact reusable allocation broke residual matching");
               } else {
                  matchedUsage = this.reusableUsage(allocation);
                  HashMap<ReusableStockUsageKey<K>, Long> desiredUsage = new HashMap<>(this.pinnedExactReusableStock);

                  for (Entry<ReusableStockUsageKey<K>, Long> entry : matchedUsage.entrySet()) {
                     desiredUsage.merge(entry.getKey(), entry.getValue(), CraftPlannerV2::addNonNegative);
                  }

                  this.replaceTracked(this.usedReusableStock, desiredUsage);
                  return new CraftPlannerV2.BorrowedReusableSeed(low, pinnedExact);
               }
            }
         }
      }
   }

   private Map<ReusableStockUsageKey<K>, Long> reusableUsage(ReusableStockMatcher.Result<K> allocation) {
      HashMap<ReusableStockUsageKey<K>, Long> desiredUsage = new HashMap<>();

      for (Entry<ReusableStockAllocationKey<K>, Long> entry : allocation.allocation().entrySet()) {
         ReusableStockAllocationKey<K> allocationKey = entry.getKey();
         ReusableStockRouteKey<K> allocationRoute = allocationKey.route();
         ReusableStockSource allocationSource = allocationRoute.source();
         ReusableStockUsageKey<K> usage = new ReusableStockUsageKey<>(
            allocationSource.storageScope(),
            allocationSource.poolScope(),
            allocationSource.routingScope(),
            allocationRoute.plannedKey(),
            allocationKey.actualKey()
         );
         desiredUsage.merge(usage, entry.getValue(), CraftPlannerV2::addNonNegative);
      }

      return desiredUsage;
   }

   private Map<ReusableStockKey<K>, Long> availableReusableStock() {
      HashMap<ReusableStockKey<K>, Long> available = new HashMap<>(this.graph.reusableStock());

      for (Entry<ReusableStockUsageKey<K>, Long> pinned : this.pinnedExactReusableStock.entrySet()) {
         ReusableStockKey<K> physical = new ReusableStockKey<>(pinned.getKey().storageScope(), pinned.getKey().actualKey());
         long left = get(available, physical) - pinned.getValue();
         if (left > 0L) {
            available.put(physical, left);
         } else {
            available.remove(physical);
         }
      }

      return available;
   }

   private boolean isReusableDemandFeasible(ReusableStockRouteKey<K> route, long routeDemand) {
      HashMap<ReusableStockRouteKey<K>, Long> demands = new HashMap<>(this.reusableBorrowedDemand);
      if (routeDemand > 0L) {
         demands.put(route, routeDemand);
      }

      return ReusableStockMatcher.allocate(
            this.availableReusableStock(), demands, candidate -> this.graph.reusableStockCandidates(candidate.source(), candidate.plannedKey())
         )
         .feasible();
   }

   private <T> void replaceTracked(Map<T, Long> target, Map<T, Long> replacement) {
      HashSet<T> keys = new HashSet<>();
      keys.addAll(target.keySet());
      keys.addAll(replacement.keySet());

      for (T key : keys) {
         long next = get(replacement, key);
         if (get(target, key) != next) {
            this.put(target, key, next);
         }
      }
   }

   private static long addNonNegative(long left, long right) {
      return left >= Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
   }

   private static <K> boolean isSelfReturnedSeed(CraftPattern<K> pattern, CraftInput<K> input) {
      return input.returned() && input.uses() == Long.MAX_VALUE && pattern.output().equals(input.key());
   }

   private boolean isFeedbackSeed(CraftPattern<K> pattern, CraftInput<K> input) {
      return this.feedbackSeedBootstrap(pattern, input) != null;
   }

   private CraftPlannerV2.FeedbackSeedBootstrap<K> feedbackSeedBootstrap(CraftPattern<K> pattern, CraftInput<K> input) {
      return this.feedbackSeedBootstrap(pattern, input, this.capacity);
   }

   private CraftPlannerV2.FeedbackSeedBootstrap<K> feedbackSeedBootstrap(CraftPattern<K> pattern, CraftInput<K> input, Map<K, Long> materialCapacity) {
      CraftPlannerV2.FeedbackSeedBootstrap<K> best = null;
      long bestCapacity = -1L;
      long bestStateCost = Long.MAX_VALUE;

      for (CraftPlannerV2.FeedbackSeedBootstrap<K> bootstrap : this.feedbackSeedBootstraps.getOrDefault(pattern, List.of())) {
         if (bootstrap.seedInput() == input) {
            long candidateCapacity = this.feedbackBootstrapSeedCapacity(bootstrap, materialCapacity);
            long candidateStateCost = bootstrap.outputUnitsFor(input.amount());
            if (best == null || candidateCapacity > bestCapacity || candidateCapacity == bestCapacity && candidateStateCost < bestStateCost) {
               best = bootstrap;
               bestCapacity = candidateCapacity;
               bestStateCost = candidateStateCost;
            }
         }
      }

      return best;
   }

   private long feedbackBootstrapSeedCapacity(CraftPlannerV2.FeedbackSeedBootstrap<K> bootstrap, Map<K, Long> materialCapacity) {
      CraftInput<K> seed = bootstrap.seedInput();
      long availableLoopState = this.graph.stock(bootstrap.loopPattern().output());
      if (seed.reusableStockSource() != null) {
         availableLoopState = Sat.add(availableLoopState, this.graph.reusableStock(seed.reusableStockSource().storageScope(), bootstrap.loopPattern().output()));
      }

      long firings = bootstrap.converterInput().firingsFrom(availableLoopState);

      for (CraftInput<K> auxiliary : bootstrap.converter().inputs()) {
         if (auxiliary != bootstrap.converterInput()) {
            long available = materialCapacity == null
               ? this.graph.stock(auxiliary.key())
               : materialCapacity.getOrDefault(auxiliary.key(), this.graph.stock(auxiliary.key()));
            firings = Math.min(firings, auxiliary.firingsFrom(available));
            if (firings == 0L) {
               return 0L;
            }
         }
      }

      return Sat.mul(firings, bootstrap.converter().outputAmount());
   }

   private boolean isFeedbackConverterInput(CraftPattern<K> pattern, CraftInput<K> input) {
      for (CraftPlannerV2.FeedbackSeedBootstrap<K> bootstrap : this.feedbackSeedConverters.getOrDefault(pattern, List.of())) {
         if (bootstrap.converterInput() == input) {
            return true;
         }
      }

      return false;
   }

   private static <K> boolean hasSelfReturnedSeed(CraftPattern<K> pattern) {
      for (CraftInput<K> input : pattern.inputs()) {
         if (isSelfReturnedSeed(pattern, input)) {
            return true;
         }
      }

      return false;
   }

   private long craftSelfSeedFromAlternative(K key, long amount, CraftPattern<K> excluded) {
      List<CraftPattern<K>> alternatives = new ArrayList<>();

      for (CraftPattern<K> pattern : this.patternsByOutput.getOrDefault(key, List.of())) {
         if (pattern != excluded && !hasSelfReturnedSeed(pattern)) {
            alternatives.add(pattern);
         }
      }

      alternatives.sort((a, b) -> Long.compare(this.capacityScore((CraftPattern<K>)b), this.capacityScore((CraftPattern<K>)a)));
      alternatives = new ArrayList<>(this.promotePreferredRoute(key, alternatives));
      boolean competing = alternatives.size() > 1;

      for (CraftPattern<K> alternative : alternatives) {
         boolean admitted = competing ? this.searchBudget.tryConsume() : this.diagnostics.tryConsumeResolutionWork();
         if (!admitted) {
            if (!this.fallbackBudget.tryConsume()) {
               return amount;
            }

            this.fire(key, alternative, amount, false);
            return 0L;
         }

         int mark = this.trail.size();
         long beforeMissing = this.missingTotal;
         this.recordRouteDecision(key, alternative, alternatives);
         long unmet = this.fire(key, alternative, amount, true);
         if (this.searchBudget.exhausted() || this.diagnostics.resolutionExhausted()) {
            this.rollback(mark);
            return amount;
         }

         if (unmet == 0L && this.missingTotal == beforeMissing) {
            return 0L;
         }

         this.rollback(mark);
      }

      return amount;
   }

   private void reserveSelfSeed(K key) {
      long required = 0L;

      for (CraftPattern<K> pattern : this.patternsByOutput.getOrDefault(key, List.of())) {
         for (CraftInput<K> input : pattern.inputs()) {
            if (isSelfReturnedSeed(pattern, input)) {
               required = Math.max(required, input.amount());
            }
         }
      }

      long alreadyReserved = get(this.reservedSelfSeeds, key);
      long additional = Math.max(0L, required - alreadyReserved);
      if (additional > 0L) {
         long available = get(this.stockLeft, key);
         long held = Math.min(additional, available);
         if (held > 0L) {
            this.put(this.stockLeft, key, available - held);
            this.put(this.reservedSelfSeeds, key, Sat.add(alreadyReserved, held));
         }
      }
   }

   private long drawReservedSelfSeed(K key, long amount) {
      long available = get(this.reservedSelfSeeds, key);
      long drawn = Math.min(amount, available);
      if (drawn > 0L) {
         this.put(this.reservedSelfSeeds, key, available - drawn);
         this.put(this.usedStock, key, Sat.add(get(this.usedStock, key), drawn));
      }

      return drawn;
   }

   private void reserveFeedbackSeedOutput(K key, long demand) {
      long immediatelyAvailable = Sat.add(get(this.bpPool, key), get(this.stockLeft, key));
      if (demand > immediatelyAvailable) {
         List<CraftPattern<K>> patterns = this.patternsByOutput.getOrDefault(key, List.of());
         if (!patterns.isEmpty()) {
            CraftPattern<K> preferred = this.capacityOrder(key).get(0);
            List<CraftPlannerV2.FeedbackSeedBootstrap<K>> bootstraps = this.feedbackSeedBootstraps.get(preferred);
            if (bootstraps != null && !bootstraps.isEmpty()) {
               Map<CraftPlannerV2.FeedbackSeedBootstrap<K>, Long> additionalBySeed = new HashMap<>();
               long totalAdditional = 0L;

               for (CraftInput<K> seed : preferred.inputs()) {
                  CraftPlannerV2.FeedbackSeedBootstrap<K> bootstrap = this.feedbackSeedBootstrap(preferred, seed);
                  if (bootstrap != null) {
                     long hostAvailable = this.graph.reusableStock(seed.reusableStockSource(), seed.key());
                     long seedShortfall = Math.max(0L, seed.amount() - hostAvailable);
                     long required = bootstrap.outputUnitsFor(seedShortfall);
                     long alreadyReserved = get(this.reservedFeedbackSeedOutputs, bootstrap);
                     long additional = Math.max(0L, required - alreadyReserved);
                     if (additional > 0L) {
                        additionalBySeed.put(bootstrap, additional);
                        totalAdditional = Sat.add(totalAdditional, additional);
                     }
                  }
               }

               if (totalAdditional > 0L) {
                  int mark = this.trail.size();
                  long availableStock = get(this.stockLeft, key);

                  for (Entry<CraftPlannerV2.FeedbackSeedBootstrap<K>, Long> entry : additionalBySeed.entrySet()) {
                     CraftPlannerV2.FeedbackSeedBootstrap<K> bootstrap = entry.getKey();
                     long additional = entry.getValue();
                     long ordinary = Math.min(additional, availableStock);
                     availableStock -= ordinary;
                     long hostNeeded = additional - ordinary;
                     long hostBorrowed = 0L;
                     if (hostNeeded > 0L) {
                        CraftPlannerV2.BorrowedReusableSeed borrowed = this.borrowReusableStock(bootstrap.bootstrapSource(), key, hostNeeded);
                        hostBorrowed = borrowed.amount();
                        if (hostBorrowed < hostNeeded) {
                           this.rollback(mark);
                           return;
                        }
                     }

                     this.put(this.reservedFeedbackSeedOutputs, bootstrap, Sat.add(get(this.reservedFeedbackSeedOutputs, bootstrap), additional));
                     if (hostBorrowed > 0L) {
                        this.put(this.reservedFeedbackSeedHostOutputs, bootstrap, Sat.add(get(this.reservedFeedbackSeedHostOutputs, bootstrap), hostBorrowed));
                     }
                  }

                  this.put(this.stockLeft, key, availableStock);
               }
            }
         }
      }
   }

   private long consumeFeedbackSeedBootstrap(CraftPattern<K> pattern, CraftInput<K> input, long requested) {
      CraftPlannerV2.FeedbackSeedBootstrap<K> bootstrap = this.feedbackSeedBootstrap(pattern, input);
      if (bootstrap != null && requested > 0L) {
         long firings = Sat.ceilDiv(requested, bootstrap.converter().outputAmount());
         long requiredOutput = bootstrap.converterInput().unitsFor(firings);
         long reserved = get(this.reservedFeedbackSeedOutputs, bootstrap);
         if (requiredOutput > reserved) {
            return 0L;
         } else {
            int mark = this.trail.size();
            long beforeMissing = this.missingTotal;

            for (CraftInput<K> auxiliary : bootstrap.converter().inputs()) {
               if (auxiliary != bootstrap.converterInput()) {
                  this.depth++;

                  long unmet;
                  try {
                     unmet = this.obtain(auxiliary.key(), auxiliary.unitsFor(firings), false);
                  } finally {
                     this.depth--;
                  }

                  if (unmet > 0L || this.missingTotal > beforeMissing) {
                     this.rollback(mark);
                     return 0L;
                  }
               }
            }

            this.put(this.reservedFeedbackSeedOutputs, bootstrap, reserved - requiredOutput);
            long reservedFromHost = get(this.reservedFeedbackSeedHostOutputs, bootstrap);
            long fromHost = Math.min(requiredOutput, reservedFromHost);
            if (fromHost > 0L) {
               this.put(this.reservedFeedbackSeedHostOutputs, bootstrap, reservedFromHost - fromHost);
            }

            long fromOrdinaryStock = requiredOutput - fromHost;
            if (fromOrdinaryStock > 0L) {
               this.bump(this.usedStock, pattern.output(), fromOrdinaryStock);
            }

            this.bump(this.grossDemand, pattern.output(), requiredOutput);
            this.bumpFiring(bootstrap.converter(), firings);
            long produced = Sat.mul(firings, bootstrap.converter().outputAmount());
            long supplied = Math.min(requested, produced);
            long surplus = produced - supplied;
            if (surplus > 0L) {
               this.bump(this.bpPool, input.key(), surplus);
            }

            return supplied;
         }
      } else {
         return 0L;
      }
   }

   private long drawPools(K x, long d) {
      long got = 0L;
      long bp = Math.min(d, get(this.bpPool, x));
      if (bp > 0L) {
         this.put(this.bpPool, x, get(this.bpPool, x) - bp);
         got += bp;
         d -= bp;
      }

      long st = Math.min(d, get(this.stockLeft, x));
      if (st > 0L) {
         this.put(this.stockLeft, x, get(this.stockLeft, x) - st);
         this.put(this.usedStock, x, Sat.add(get(this.usedStock, x), st));
         got += st;
      }

      return got;
   }

   private static <T> long get(Map<T, Long> m, T k) {
      Long v = m.get(k);
      return v == null ? 0L : v;
   }

   private <T> void put(Map<T, Long> m, T k, long newVal) {
      Long old = m.get(k);
      long oldAvailabilityState = this.availabilityState;
      boolean tracksAvailability = this.tracksAvailability(m);
      this.trail.push(() -> {
         if (old == null) {
            m.remove(k);
         } else {
            m.put(k, old);
         }

         if (tracksAvailability) {
            this.availabilityState = oldAvailabilityState;
         }
      });
      if (tracksAvailability) {
         this.availabilityState = ++this.nextAvailabilityState;
      }

      if (newVal == 0L) {
         m.remove(k);
      } else {
         m.put(k, newVal);
      }
   }

   private boolean tracksAvailability(Map<?, Long> map) {
      return map == this.bpPool
         || map == this.stockLeft
         || map == this.reservedSelfSeeds
         || map == this.reservedFeedbackSeedOutputs
         || map == this.reservedFeedbackSeedHostOutputs
         || map == this.reusableBorrowedDemand
         || map == this.reusablePrivatePool
         || map == this.reusablePool
         || map == this.pinnedExactReusableStock;
   }

   private <T> void bump(Map<T, Long> m, T k, long delta) {
      if (delta != 0L) {
         this.put(m, k, Sat.add(get(m, k), delta));
      }
   }

   private void addMissing(K k, long amt) {
      if (amt > 0L) {
         this.put(this.missing, k, Sat.add(get(this.missing, k), amt));
         long old = this.missingTotal;
         this.trail.push(() -> this.missingTotal = old);
         this.missingTotal = Sat.add(this.missingTotal, amt);
      }
   }

   private void bumpFiring(CraftPattern<K> r, long delta) {
      Long old = this.firings.get(r);
      this.trail.push(() -> {
         if (old == null) {
            this.firings.remove(r);
         } else {
            this.firings.put(r, old);
         }
      });
      this.firings.put(r, Sat.add(old == null ? 0L : old, delta));
   }

   private void recordRouteDecision(K key, CraftPattern<K> selected, List<CraftPattern<K>> candidates) {
      if (candidates.size() >= 2) {
         int oldSize = this.routeDecisions.size();
         this.trail.push(() -> {
            while (this.routeDecisions.size() > oldSize) {
               this.routeDecisions.remove(this.routeDecisions.size() - 1);
            }
         });
         this.routeDecisions.add(new CraftPlannerV2.RouteDecision<>(key, selected, List.copyOf(candidates)));
      }
   }

   private CraftPlannerV2.RouteAlternatives<K> routeAlternatives(int limit) {
      List<CraftPlannerV2.RouteAlternative<K>> alternatives = new ArrayList<>();
      Set<K> emittedKeys = new HashSet<>();
      boolean truncated = false;

      for (int decisionIndex = this.replayRouteDecisions.size() - 1; decisionIndex >= 0; decisionIndex--) {
         CraftPlannerV2.RouteDecision<K> decision = this.replayRouteDecisions.get(decisionIndex);
         if (emittedKeys.add(decision.key())) {
            List<CraftPattern<K>> candidates = decision.candidates();
            int selected = candidates.indexOf(decision.selected());
            if (selected >= 0) {
               List<CraftPattern<K>> defaultOrder = this.capacityOrderByOutput
                  .getOrDefault(decision.key(), this.patternsByOutput.getOrDefault(decision.key(), List.of()));
               CraftPattern<K> defaultPattern = defaultOrder.isEmpty() ? decision.selected() : defaultOrder.get(0);

               for (int i = selected + 1; i < candidates.size(); i++) {
                  if (alternatives.size() >= limit) {
                     truncated = true;
                     break;
                  }

                  alternatives.add(new CraftPlannerV2.RouteAlternative<>(decision.key(), candidates.get(i), defaultPattern));
               }

               if (truncated) {
                  break;
               }

               for (int i = 0; i < selected; i++) {
                  if (alternatives.size() >= limit) {
                     truncated = true;
                     break;
                  }

                  alternatives.add(new CraftPlannerV2.RouteAlternative<>(decision.key(), candidates.get(i), defaultPattern));
               }

               if (truncated) {
                  break;
               }
            }
         }
      }

      return new CraftPlannerV2.RouteAlternatives<>(List.copyOf(alternatives), truncated);
   }

   private void rollback(int mark) {
      while (this.trail.size() > mark) {
         this.trail.pop().run();
      }
   }

   private static record BorrowedReusableSeed(long amount, long pinnedExactAmount) {
   }

   private static record ConsumableProofState<K>(K key, long availabilityState) {
   }

   private static final class DiagnosticsCollector {
      private final int reachableWorkEstimate;
      private final int configuredSearchBudget;
      private final int configuredResolutionBudget;
      private final int configuredFallbackBudget;
      private int reachableItems;
      private int reachablePatterns;
      private int inputEdges;
      private int contendedOutputs;
      private int cycleCuts;
      private boolean seedOrdered;
      private int planRuns;
      private int compiledOrientations;
      private int reusedCompilations;
      private int hotNodeVisits;
      private int dynamicCapacityEvaluations;
      private int equivalentRoutesPruned;
      private int failureMemoHits;
      private int frontierPeak;
      private boolean searchCutoff;
      private int consumedResolutionBudget;
      private boolean resolutionCutoff;
      private int consumedFallbackBudget;
      private boolean fallbackCutoff;
      private long graphCompileNanos;
      private long linearPassNanos;
      private long searchNanos;

      private DiagnosticsCollector(int reachableWorkEstimate, int configuredSearchBudget) {
         this.reachableWorkEstimate = reachableWorkEstimate;
         this.configuredSearchBudget = Math.max(1, configuredSearchBudget);
         this.configuredResolutionBudget = CraftPlannerV2.fallbackWorkBudget(reachableWorkEstimate);
         this.configuredFallbackBudget = CraftPlannerV2.fallbackWorkBudget(reachableWorkEstimate);
      }

      private int fallbackBudgetLimit() {
         return this.configuredFallbackBudget;
      }

      private void recordCompilation(CraftPlannerV2.PreparedGraph<?> prepared, long nanos) {
         this.compiledOrientations = increment(this.compiledOrientations);
         this.reachableItems = Math.max(this.reachableItems, prepared.items.size());
         this.reachablePatterns = Math.max(this.reachablePatterns, prepared.patternCount);
         this.inputEdges = Math.max(this.inputEdges, prepared.inputCount);
         this.contendedOutputs = Math.max(this.contendedOutputs, prepared.contendedOutputCount);
         this.cycleCuts = Math.max(this.cycleCuts, prepared.cutOutputs.size());
         this.seedOrdered = this.seedOrdered | prepared.seedOrdered;
         this.graphCompileNanos = addNanos(this.graphCompileNanos, nanos);
      }

      private void recordCompilationReuse() {
         this.reusedCompilations = increment(this.reusedCompilations);
      }

      private void recordPlanRun() {
         this.planRuns = increment(this.planRuns);
      }

      private void recordHotNodeVisit() {
         this.hotNodeVisits = increment(this.hotNodeVisits);
      }

      private void recordDynamicCapacityEvaluation() {
         this.dynamicCapacityEvaluations = increment(this.dynamicCapacityEvaluations);
      }

      private void recordEquivalentRoutesPruned(int count) {
         if (count > 0) {
            this.equivalentRoutesPruned = add(this.equivalentRoutesPruned, count);
         }
      }

      private void recordFailureMemoHit() {
         this.failureMemoHits = increment(this.failureMemoHits);
      }

      private void recordFrontierSize(int size) {
         this.frontierPeak = Math.max(this.frontierPeak, size);
      }

      private void recordSearchCutoff() {
         this.searchCutoff = true;
      }

      private boolean tryConsumeResolutionWork() {
         if (this.consumedResolutionBudget >= this.configuredResolutionBudget) {
            this.resolutionCutoff = true;
            return false;
         } else {
            this.consumedResolutionBudget = increment(this.consumedResolutionBudget);
            return true;
         }
      }

      private boolean resolutionExhausted() {
         return this.resolutionCutoff;
      }

      private void recordFallbackWork() {
         this.consumedFallbackBudget = increment(this.consumedFallbackBudget);
      }

      private void recordFallbackCutoff() {
         this.fallbackCutoff = true;
      }

      private void addLinearPassNanos(long nanos) {
         this.linearPassNanos = addNanos(this.linearPassNanos, nanos);
      }

      private void addSearchNanos(long nanos) {
         this.searchNanos = addNanos(this.searchNanos, nanos);
      }

      private PlanningDiagnostics finish(long started, CraftPlannerV2.SearchBudget budget) {
         int consumed = budget == null ? 0 : budget.consumed();
         return new PlanningDiagnostics(
            this.reachableWorkEstimate,
            this.reachableItems,
            this.reachablePatterns,
            this.inputEdges,
            this.contendedOutputs,
            this.cycleCuts,
            this.seedOrdered,
            this.configuredSearchBudget,
            consumed,
            this.configuredResolutionBudget,
            this.consumedResolutionBudget,
            this.configuredFallbackBudget,
            this.consumedFallbackBudget,
            this.planRuns,
            this.compiledOrientations,
            this.reusedCompilations,
            this.hotNodeVisits,
            this.dynamicCapacityEvaluations,
            this.equivalentRoutesPruned,
            this.failureMemoHits,
            this.frontierPeak,
            this.searchCutoff,
            this.resolutionCutoff,
            this.fallbackCutoff,
            this.graphCompileNanos,
            this.linearPassNanos,
            this.searchNanos,
            Math.max(0L, System.nanoTime() - started)
         );
      }

      private static int increment(int value) {
         return value == Integer.MAX_VALUE ? value : value + 1;
      }

      private static int add(int left, int right) {
         return left > Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right;
      }

      private static long addNanos(long left, long right) {
         long nonNegativeRight = Math.max(0L, right);
         return left > Long.MAX_VALUE - nonNegativeRight ? Long.MAX_VALUE : left + nonNegativeRight;
      }
   }

   private static record DirectRefill<K>(CraftPattern<K> pattern, CraftInput<K> input) {
   }

   private static record EnqueueResult(long sequence, boolean truncated) {
   }

   private static final class FallbackBudget {
      private int remaining;
      private final CraftPlannerV2.DiagnosticsCollector diagnostics;

      private FallbackBudget(int work, CraftPlannerV2.DiagnosticsCollector diagnostics) {
         this.remaining = Math.max(1, work);
         this.diagnostics = diagnostics;
      }

      private boolean tryConsume() {
         if (this.remaining <= 0) {
            this.diagnostics.recordFallbackCutoff();
            return false;
         } else {
            this.remaining--;
            this.diagnostics.recordFallbackWork();
            return true;
         }
      }
   }

   private static record FeedbackSeedBootstrap<K>(CraftPattern<K> loopPattern, CraftInput<K> seedInput, CraftPattern<K> converter, CraftInput<K> converterInput) {
      long outputUnitsFor(long seedAmount) {
         long firings = Sat.ceilDiv(seedAmount, this.converter.outputAmount());
         return this.converterInput.unitsFor(firings);
      }

      ReusableStockSource bootstrapSource() {
         ReusableStockSource owner = this.seedInput.reusableStockSource();
         return new ReusableStockSource(owner.storageScope(), owner.poolScope(), new ReusableBootstrapRoute(owner.routingScope(), this.seedInput.key()));
      }
   }

   private static final class FootprintInterner {
      private final Map<Object, Integer> ids = new HashMap<>();

      private int intern(Object shape) {
         Integer existing = this.ids.get(shape);
         if (existing != null) {
            return existing;
         } else {
            int id = this.ids.size() + 1;
            this.ids.put(shape, id);
            return id;
         }
      }
   }

   private static final class Frame<K> {
      final K node;
      final List<K> children;
      int i;

      Frame(K node, List<K> children) {
         this.node = node;
         this.children = children;
      }
   }

   private static record LinearPassState<K>(
      Map<K, Long> need,
      Map<CraftPattern<K>, Long> fired,
      Map<K, Long> used,
      Map<K, Long> miss,
      Map<K, Long> gross,
      Map<CraftPattern<K>, Long> allocatedUnits,
      int done
   ) {
   }

   private static record MaterialLeaf(Object key) {
   }

   private static record MaterialRecipe(long outputAmount, List<CraftPlannerV2.MaterialTerm> inputs) {
   }

   private static record MaterialTerm(int footprint, long amount) {
   }

   private static record PlanVariant<K>(List<K> priorityRoots, Map<K, CraftPattern<K>> routePreferences, int discrepancies, long sequence) {
      private PlanVariant(List<K> priorityRoots, Map<K, CraftPattern<K>> routePreferences, int discrepancies, long sequence) {
         priorityRoots = List.copyOf(priorityRoots);
         routePreferences = Map.copyOf(routePreferences);
         this.priorityRoots = priorityRoots;
         this.routePreferences = routePreferences;
         this.discrepancies = discrepancies;
         this.sequence = sequence;
      }

      private CraftPlannerV2.VariantKey<K> key() {
         return new CraftPlannerV2.VariantKey<>(this.priorityRoots, this.routePreferences);
      }
   }

   private static final class PreparedGraph<K> {
      private final CraftGraph<K> graph;
      private final List<K> order;
      private final Set<K> items;
      private final Set<K> cutOutputs;
      private final Map<K, List<CraftPattern<K>>> patternsByOutput;
      private final Map<CraftPattern<K>, Set<K>> suppressedPositiveFeedbackOutputs;
      private final Map<CraftPattern<K>, Map<K, Long>> linearContainerBootstrapReserves;
      private final Map<CraftPattern<K>, List<CraftPlannerV2.FeedbackSeedBootstrap<K>>> feedbackSeedBootstraps;
      private final Map<CraftPattern<K>, List<CraftPlannerV2.FeedbackSeedBootstrap<K>>> feedbackSeedConverters;
      private final boolean seedOrdered;
      private final Set<K> seedOrderedDependencyCone;
      private final Map<K, Long> capacity;
      private final Map<CraftPattern<K>, Long> capacityScoreByPattern;
      private final Map<K, List<CraftPattern<K>>> capacityOrderByOutput;
      private final Map<CraftPattern<K>, Map<K, Long>> directRawConsumablesByPattern;
      private final Map<CraftPattern<K>, Integer> materialFootprintByPattern;
      private final int patternCount;
      private final int inputCount;
      private final int contendedOutputCount;

      private PreparedGraph(
         CraftGraph<K> graph,
         List<K> order,
         Set<K> items,
         Set<K> cutOutputs,
         Map<K, List<CraftPattern<K>>> patternsByOutput,
         Map<CraftPattern<K>, Set<K>> suppressedPositiveFeedbackOutputs,
         Map<CraftPattern<K>, Map<K, Long>> linearContainerBootstrapReserves,
         Map<CraftPattern<K>, List<CraftPlannerV2.FeedbackSeedBootstrap<K>>> feedbackSeedBootstraps,
         Map<CraftPattern<K>, List<CraftPlannerV2.FeedbackSeedBootstrap<K>>> feedbackSeedConverters,
         boolean seedOrdered,
         Set<K> seedOrderedDependencyCone,
         Map<K, Long> capacity,
         Map<CraftPattern<K>, Long> capacityScoreByPattern,
         Map<K, List<CraftPattern<K>>> capacityOrderByOutput,
         Map<CraftPattern<K>, Map<K, Long>> directRawConsumablesByPattern,
         Map<CraftPattern<K>, Integer> materialFootprintByPattern,
         int patternCount,
         int inputCount,
         int contendedOutputCount
      ) {
         this.graph = graph;
         this.order = order;
         this.items = items;
         this.cutOutputs = cutOutputs;
         this.patternsByOutput = patternsByOutput;
         this.suppressedPositiveFeedbackOutputs = suppressedPositiveFeedbackOutputs;
         this.linearContainerBootstrapReserves = linearContainerBootstrapReserves;
         this.feedbackSeedBootstraps = feedbackSeedBootstraps;
         this.feedbackSeedConverters = feedbackSeedConverters;
         this.seedOrdered = seedOrdered;
         this.seedOrderedDependencyCone = seedOrderedDependencyCone;
         this.capacity = capacity;
         this.capacityScoreByPattern = capacityScoreByPattern;
         this.capacityOrderByOutput = capacityOrderByOutput;
         this.directRawConsumablesByPattern = directRawConsumablesByPattern;
         this.materialFootprintByPattern = materialFootprintByPattern;
         this.patternCount = patternCount;
         this.inputCount = inputCount;
         this.contendedOutputCount = contendedOutputCount;
      }
   }

   private static record ReusableSeedAcquisition(long unmet, long sharedReturnable, long privateReturnable) {
   }

   private static record RouteAlternative<K>(K key, CraftPattern<K> pattern, CraftPattern<K> defaultPattern) {
   }

   private static record RouteAlternatives<K>(List<CraftPlannerV2.RouteAlternative<K>> alternatives, boolean truncated) {
   }

   private static record RouteDecision<K>(K key, CraftPattern<K> selected, List<CraftPattern<K>> candidates) {
   }

   private static final class SearchBudget {
      private final int initial;
      private int remaining;
      private final CraftPlannerV2.DiagnosticsCollector diagnostics;
      private boolean exhausted;

      private SearchBudget(int work, CraftPlannerV2.DiagnosticsCollector diagnostics) {
         this.initial = Math.max(1, work);
         this.remaining = this.initial;
         this.diagnostics = diagnostics;
      }

      private boolean tryConsume() {
         return this.tryConsume(1);
      }

      private boolean tryConsume(int work) {
         int requested = Math.max(1, work);
         if (this.remaining < requested) {
            if (!this.exhausted) {
               this.exhausted = true;
               this.diagnostics.recordSearchCutoff();
            }

            return false;
         } else {
            this.remaining -= requested;
            return true;
         }
      }

      private boolean exhausted() {
         return this.exhausted;
      }

      private int remaining() {
         return this.remaining;
      }

      private int consumed() {
         return this.initial - this.remaining;
      }
   }

   private static record SearchFailure<K>(K key, long amount, long availabilityState, int depth) {
   }

   private static record SeedRequirement<K>(long consumedAmount, K returnedState, long returnedAmount) {
   }

   private static record VariantKey<K>(List<K> priorityRoots, Map<K, CraftPattern<K>> routePreferences) {
   }
}
