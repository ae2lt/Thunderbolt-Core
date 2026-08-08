package com.moakiee.thunderbolt.core.planner;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds ordinary, non-growing feedback state machines before any aggregate planning pass runs.
 *
 * <p>The exact tier recognizes marked cycles: every state has one internal producer and consumer,
 * while every transition moves one weighted state to the next. Arbitrary arc weights are compiled
 * into primitive non-growing firing ratios; actual plans are decomposed into repeated weighted rounds
 * plus a bounded residual. This covers balanced raw catalysts such as
 * {@code A -> 2B; 2B + C -> E + D; D -> A} and lossy feedback such as
 * {@code 3A -> 2B; 2B -> D + 2A}, without admitting gain loops such as {@code A -> 2A}.
 * More complicated locally non-growing SCCs use a bounded canonical replay that may overstate the
 * initial marking but never reports an unexecutable firing multiset as ready.
 */
final class ConservativeFeedbackAnalysis<K> {

    /** Exact per-firing replay is limited to one primitive weighted round, never the request size. */
    private static final int MAX_PRIMITIVE_ROUND_FIRINGS = 4_096;
    private static final int MAX_SCHEDULE_OPTIONS = 4_096;
    private static final int MAX_FALLBACK_STARTS = 16;
    private static final int MAX_FALLBACK_REPLAY_STEPS = 4_096;
    private static final int MAX_FALLBACK_PATTERN_PROBES = 65_536;

    /** Prefix marking and net change of one deterministic execution block. */
    record ScheduleOption<K>(Map<K, BigInteger> required, Map<K, BigInteger> delta) {
        ScheduleOption {
            required = Map.copyOf(required);
            delta = Map.copyOf(delta);
        }
    }

    record RoundVector<K>(Map<CraftPattern<K>, Long> firings) {
        RoundVector {
            firings = Map.copyOf(firings);
        }
    }

    record Component<K>(Set<K> states,
                        List<K> stateOrder,
                        List<CraftPattern<K>> patterns,
                        List<Transition<K>> cycle,
                        List<RoundVector<K>> firingVectors,
                        boolean hasExternalProducer) {
        Component {
            states = Set.copyOf(states);
            stateOrder = List.copyOf(stateOrder);
            patterns = List.copyOf(patterns);
            cycle = List.copyOf(cycle);
            firingVectors = List.copyOf(firingVectors);
        }
    }

    /** Any proven non-growing ordinary SCC that is not a strict marked cycle. */
    record FallbackComponent<K>(Set<K> states,
                                List<K> stateOrder,
                                List<CraftPattern<K>> patterns) {
        FallbackComponent {
            states = Set.copyOf(states);
            stateOrder = List.copyOf(stateOrder);
            patterns = List.copyOf(patterns);
        }
    }

    record Analysis<K>(List<Component<K>> components,
                       List<FallbackComponent<K>> fallbacks) {
        Analysis {
            components = List.copyOf(components);
            fallbacks = List.copyOf(fallbacks);
        }
    }

    record Transition<K>(CraftPattern<K> pattern, K input, long inputAmount,
                         K output, long outputAmount) {
    }

    private ConservativeFeedbackAnalysis() {
    }

    static <K> Analysis<K> analyzeAll(
            List<K> itemOrder,
            Map<K, List<CraftPattern<K>>> patternsByOutput) {
        List<CraftPattern<K>> patterns = stablePatterns(itemOrder, patternsByOutput);
        Map<K, LinkedHashSet<K>> mutableAdjacency = new LinkedHashMap<>();
        Set<K> nodes = new LinkedHashSet<>();

        for (CraftPattern<K> pattern : patterns) {
            PlanningCancellation.check();
            List<K> outputs = outputKeys(pattern);
            nodes.addAll(outputs);
            for (CraftInput<K> input : pattern.inputs()) {
                nodes.add(input.key());
                if (!isOrdinary(input)) continue;
                LinkedHashSet<K> adjacent = mutableAdjacency.computeIfAbsent(
                        input.key(), ignored -> new LinkedHashSet<>());
                adjacent.addAll(outputs);
            }
        }
        for (K node : nodes) {
            mutableAdjacency.computeIfAbsent(node, ignored -> new LinkedHashSet<>());
        }

        Map<K, List<K>> adjacency = new LinkedHashMap<>();
        mutableAdjacency.forEach((key, value) -> adjacency.put(key, List.copyOf(value)));
        List<Set<K>> stronglyConnected = stronglyConnectedComponents(nodes, adjacency);
        Map<K, Integer> itemRank = new HashMap<>();
        for (int i = 0; i < itemOrder.size(); i++) itemRank.putIfAbsent(itemOrder.get(i), i);

        List<Component<K>> result = new ArrayList<>();
        List<FallbackComponent<K>> fallbacks = new ArrayList<>();
        for (Set<K> states : stronglyConnected) {
            PlanningCancellation.check();
            boolean selfLoop = states.size() == 1
                    && adjacency.getOrDefault(states.iterator().next(), List.of())
                            .contains(states.iterator().next());
            if (states.size() <= 1 && !selfLoop) continue;
            Component<K> component = classify(states, patterns, itemRank);
            if (component != null) {
                result.add(component);
            } else {
                FallbackComponent<K> fallback = classifyFallback(
                        states, patterns, itemRank);
                if (fallback != null) fallbacks.add(fallback);
            }
        }
        return new Analysis<>(result, fallbacks);
    }

    private static <K> List<CraftPattern<K>> stablePatterns(
            List<K> itemOrder,
            Map<K, List<CraftPattern<K>>> patternsByOutput) {
        Set<CraftPattern<K>> seen = new LinkedHashSet<>();
        List<CraftPattern<K>> result = new ArrayList<>();
        for (K item : itemOrder) {
            for (CraftPattern<K> pattern : patternsByOutput.getOrDefault(item, List.of())) {
                if (seen.add(pattern)) result.add(pattern);
            }
        }
        return result;
    }

    private static <K> List<K> outputKeys(CraftPattern<K> pattern) {
        Set<K> outputs = new LinkedHashSet<>();
        outputs.add(pattern.output());
        for (CraftOutput<K> byproduct : pattern.byproducts()) outputs.add(byproduct.key());
        return List.copyOf(outputs);
    }

    private static <K> boolean isOrdinary(CraftInput<K> input) {
        return !input.returned()
                && input.remainder() == null
                && input.reusableStockSource() == null;
    }

    private static <K> Component<K> classify(
            Set<K> states,
            List<CraftPattern<K>> patterns,
            Map<K, Integer> itemRank) {
        List<Transition<K>> transitions = new ArrayList<>();
        boolean hasExternalProducer = false;
        for (CraftPattern<K> pattern : patterns) {
            Map<K, Long> inputs = new LinkedHashMap<>();
            boolean invalidInternalInput = false;
            for (CraftInput<K> input : pattern.inputs()) {
                if (!states.contains(input.key())) continue;
                if (!isOrdinary(input)) {
                    invalidInternalInput = true;
                    break;
                }
                if (!mergeExact(inputs, input.key(), input.amount())) return null;
            }
            if (invalidInternalInput) return null;

            Map<K, Long> outputs = new LinkedHashMap<>();
            if (states.contains(pattern.output())
                    && !mergeExact(outputs, pattern.output(), pattern.outputAmount())) {
                return null;
            }
            for (CraftOutput<K> byproduct : pattern.byproducts()) {
                if (states.contains(byproduct.key())
                        && !mergeExact(outputs, byproduct.key(), byproduct.amount())) {
                    return null;
                }
            }

            // An acyclic producer may inject the first state and a sink may consume the final state.
            // Neither is a transition of the feedback machine itself.
            if (inputs.isEmpty() && !outputs.isEmpty()) {
                hasExternalProducer = true;
                continue;
            }
            if (inputs.isEmpty() || outputs.isEmpty()) continue;
            if (inputs.size() != 1 || outputs.size() != 1) return null;
            Map.Entry<K, Long> input = inputs.entrySet().iterator().next();
            Map.Entry<K, Long> output = outputs.entrySet().iterator().next();
            transitions.add(new Transition<>(
                    pattern, input.getKey(), input.getValue(), output.getKey(), output.getValue()));
        }
        if (transitions.isEmpty()) return null;

        Map<K, Transition<K>> consumerByState = new HashMap<>();
        Map<K, Transition<K>> producerByState = new HashMap<>();
        for (Transition<K> transition : transitions) {
            if (consumerByState.putIfAbsent(transition.input(), transition) != null
                    || producerByState.putIfAbsent(transition.output(), transition) != null) {
                return null;
            }
        }
        if (!consumerByState.keySet().equals(states)
                || !producerByState.keySet().equals(states)) {
            return null;
        }
        List<Transition<K>> cycle = new ArrayList<>(transitions.size());
        Set<CraftPattern<K>> visited = new HashSet<>();
        Transition<K> current = stableStart(transitions, itemRank);
        while (visited.add(current.pattern())) {
            cycle.add(current);
            current = consumerByState.get(current.output());
            if (current == null) return null;
        }
        if (current.pattern() != cycle.get(0).pattern() || cycle.size() != transitions.size()) {
            return null;
        }

        List<RoundVector<K>> firingVectors = primitiveFiringVectors(cycle);
        if (firingVectors.isEmpty()) return null; // strict gain or unrepresentable weighted round
        List<CraftPattern<K>> cyclePatterns = new ArrayList<>(cycle.size());
        for (Transition<K> transition : cycle) cyclePatterns.add(transition.pattern());
        List<K> stateOrder = new ArrayList<>(states);
        stateOrder.sort(java.util.Comparator.comparingInt(
                state -> itemRank.getOrDefault(state, Integer.MAX_VALUE)));
        return new Component<>(
                states, stateOrder, cyclePatterns, cycle, firingVectors, hasExternalProducer);
    }

    private static <K> Transition<K> stableStart(
            List<Transition<K>> transitions, Map<K, Integer> itemRank) {
        Transition<K> result = transitions.get(0);
        int resultRank = itemRank.getOrDefault(result.input(), Integer.MAX_VALUE);
        for (int i = 1; i < transitions.size(); i++) {
            Transition<K> candidate = transitions.get(i);
            int rank = itemRank.getOrDefault(candidate.input(), Integer.MAX_VALUE);
            if (rank < resultRank) {
                result = candidate;
                resultRank = rank;
            }
        }
        return result;
    }

    /**
     * Fallback for ordinary Petri SCCs whose unit-token potential proves every internal transition
     * locally non-growing. Local-growth transitions stay with the contracted gain-loop path or the
     * existing decline behavior.
     */
    private static <K> FallbackComponent<K> classifyFallback(
            Set<K> states,
            List<CraftPattern<K>> patterns,
            Map<K, Integer> itemRank) {
        List<CraftPattern<K>> internalPatterns = new ArrayList<>();
        for (CraftPattern<K> pattern : patterns) {
            BigInteger internalInput = BigInteger.ZERO;
            BigInteger internalOutput = BigInteger.ZERO;
            for (CraftInput<K> input : pattern.inputs()) {
                if (!states.contains(input.key())) continue;
                if (!isOrdinary(input)) return null;
                internalInput = internalInput.add(BigInteger.valueOf(input.amount()));
            }
            if (states.contains(pattern.output())) {
                internalOutput = internalOutput.add(BigInteger.valueOf(pattern.outputAmount()));
            }
            for (CraftOutput<K> output : pattern.byproducts()) {
                if (states.contains(output.key())) {
                    internalOutput = internalOutput.add(BigInteger.valueOf(output.amount()));
                }
            }
            if (internalInput.signum() == 0 || internalOutput.signum() == 0) continue;
            if (internalOutput.compareTo(internalInput) > 0) return null;
            internalPatterns.add(pattern);
        }
        if (internalPatterns.isEmpty()) return null;
        List<K> stateOrder = new ArrayList<>(states);
        stateOrder.sort(java.util.Comparator.comparingInt(
                state -> itemRank.getOrDefault(state, Integer.MAX_VALUE)));
        return new FallbackComponent<>(states, stateOrder, internalPatterns);
    }

    /**
     * Minimal positive transition ratio for one weighted round. Every state except the stable cut is
     * exactly balanced; the cut may lose tokens but must never gain them. This is the weighted form
     * of the old "fire every transition once" model.
     */
    private static <K> List<RoundVector<K>> primitiveFiringVectors(
            List<Transition<K>> stableCycle) {
        List<RoundVector<K>> result = new ArrayList<>(stableCycle.size());
        Set<Map<CraftPattern<K>, Long>> seen = new HashSet<>();
        for (int start = 0; start < stableCycle.size(); start++) {
            List<Transition<K>> rotated = new ArrayList<>(stableCycle.size());
            for (int offset = 0; offset < stableCycle.size(); offset++) {
                rotated.add(stableCycle.get((start + offset) % stableCycle.size()));
            }
            Map<CraftPattern<K>, Long> vector = primitiveFiringVector(rotated);
            if (vector != null && seen.add(vector)) result.add(new RoundVector<>(vector));
        }
        return List.copyOf(result);
    }

    private static <K> Map<CraftPattern<K>, Long> primitiveFiringVector(
            List<Transition<K>> cycle) {
        int size = cycle.size();
        BigInteger[] numerators = new BigInteger[size];
        BigInteger[] denominators = new BigInteger[size];
        numerators[0] = BigInteger.ONE;
        denominators[0] = BigInteger.ONE;
        for (int i = 1; i < size; i++) {
            Transition<K> previous = cycle.get(i - 1);
            Transition<K> current = cycle.get(i);
            BigInteger numerator = numerators[i - 1]
                    .multiply(BigInteger.valueOf(previous.outputAmount()));
            BigInteger denominator = denominators[i - 1]
                    .multiply(BigInteger.valueOf(current.inputAmount()));
            BigInteger gcd = numerator.gcd(denominator);
            numerators[i] = numerator.divide(gcd);
            denominators[i] = denominator.divide(gcd);
        }

        BigInteger commonDenominator = BigInteger.ONE;
        for (BigInteger denominator : denominators) {
            commonDenominator = lcm(commonDenominator, denominator);
            if (commonDenominator.compareTo(BigInteger.valueOf(Sat.SAT)) > 0) return null;
        }
        BigInteger[] firings = new BigInteger[size];
        BigInteger commonGcd = BigInteger.ZERO;
        for (int i = 0; i < size; i++) {
            firings[i] = numerators[i].multiply(commonDenominator.divide(denominators[i]));
            commonGcd = commonGcd.signum() == 0 ? firings[i] : commonGcd.gcd(firings[i]);
        }
        if (commonGcd.signum() <= 0) return null;
        for (int i = 0; i < size; i++) firings[i] = firings[i].divide(commonGcd);

        Transition<K> first = cycle.get(0);
        Transition<K> last = cycle.get(size - 1);
        BigInteger consumedAtCut = firings[0]
                .multiply(BigInteger.valueOf(first.inputAmount()));
        BigInteger producedAtCut = firings[size - 1]
                .multiply(BigInteger.valueOf(last.outputAmount()));
        if (producedAtCut.compareTo(consumedAtCut) > 0) return null;

        Map<CraftPattern<K>, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            if (firings[i].signum() <= 0
                    || firings[i].compareTo(BigInteger.valueOf(Sat.SAT)) > 0) {
                return null;
            }
            result.put(cycle.get(i).pattern(), firings[i].longValueExact());
        }
        return result;
    }

    /**
     * Canonical executable prefix markings for the actual firing multiset. Requested amounts are
     * decomposed into a primitive weighted round repeated in closed form plus one bounded residual.
     * When a primitive vector is too wide for exact per-firing replay, grouped rotations remain a
     * sound (possibly non-minimal) fallback.
     */
    static <K> List<ScheduleOption<K>> scheduleOptions(
            Component<K> component, Map<CraftPattern<K>, Long> fired) {
        List<Transition<K>> cycle = component.cycle();
        int size = cycle.size();
        long[] actual = new long[size];
        for (int i = 0; i < size; i++) {
            CraftPattern<K> pattern = cycle.get(i).pattern();
            actual[i] = fired.getOrDefault(pattern, 0L);
            if (actual[i] <= 0L) return List.of();
        }

        List<ScheduleOption<K>> result = new ArrayList<>();
        Set<Map<K, BigInteger>> seenRequirements = new HashSet<>();
        outer:
        for (RoundVector<K> roundVector : component.firingVectors()) {
            long[] vector = new long[size];
            long rounds = Long.MAX_VALUE;
            for (int i = 0; i < size; i++) {
                vector[i] = roundVector.firings().getOrDefault(cycle.get(i).pattern(), 0L);
                if (vector[i] <= 0L) continue outer;
                rounds = Math.min(rounds, actual[i] / vector[i]);
            }
            if (rounds == Long.MAX_VALUE) continue;

            long[] residual = new long[size];
            for (int i = 0; i < size; i++) {
                residual[i] = actual[i] - rounds * vector[i];
            }
            List<ScheduleOption<K>> roundOptions = rounds > 0L
                    ? schedulesForCounts(cycle, vector)
                    : List.of(zeroSchedule());
            List<ScheduleOption<K>> residualOptions = anyPositive(residual)
                    ? schedulesForCounts(cycle, residual)
                    : List.of(zeroSchedule());
            for (ScheduleOption<K> round : roundOptions) {
                ScheduleOption<K> repeated = repeat(round, rounds, component.states());
                if (repeated == null) continue;
                for (ScheduleOption<K> residualSchedule : residualOptions) {
                    ScheduleOption<K> roundsThenResidual = compose(
                            repeated, residualSchedule, component.states());
                    if (seenRequirements.add(roundsThenResidual.required())) {
                        result.add(roundsThenResidual);
                        if (result.size() >= MAX_SCHEDULE_OPTIONS) break outer;
                    }
                    ScheduleOption<K> residualThenRounds = compose(
                            residualSchedule, repeated, component.states());
                    if (seenRequirements.add(residualThenRounds.required())) {
                        result.add(residualThenRounds);
                        if (result.size() >= MAX_SCHEDULE_OPTIONS) break outer;
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * Deterministic prefix schedules for an arbitrary proven non-growing ordinary SCC. Small and
     * normally behaving firing multisets are replayed by enabled transition batches. If replay does
     * not converge within a fixed amount-independent work cap, the remaining firings are grouped in
     * stable order and topped up conservatively. Every returned marking is therefore executable;
     * only its minimality may degrade on a complicated Petri net.
     */
    static <K> List<ScheduleOption<K>> scheduleOptions(
            FallbackComponent<K> component, Map<CraftPattern<K>, Long> fired) {
        List<CraftPattern<K>> active = new ArrayList<>();
        for (CraftPattern<K> pattern : component.patterns()) {
            if (fired.getOrDefault(pattern, 0L) > 0L) active.add(pattern);
        }
        if (active.isEmpty()) return List.of();

        int starts = Math.min(active.size(), MAX_FALLBACK_STARTS);
        int replaySteps = Math.max(1, Math.min(MAX_FALLBACK_REPLAY_STEPS,
                MAX_FALLBACK_PATTERN_PROBES / starts / active.size()));
        List<ScheduleOption<K>> result = new ArrayList<>(starts);
        Set<Map<K, BigInteger>> seen = new HashSet<>();
        for (int start = 0; start < starts; start++) {
            PlanningCancellation.check();
            ScheduleOption<K> option = replayFallback(
                    component.states(), active, fired, start, replaySteps);
            if (seen.add(option.required())) result.add(option);
        }
        return List.copyOf(result);
    }

    private static <K> ScheduleOption<K> replayFallback(
            Set<K> states,
            List<CraftPattern<K>> patterns,
            Map<CraftPattern<K>, Long> fired,
            int start,
            int replaySteps) {
        long[] remaining = new long[patterns.size()];
        for (int i = 0; i < patterns.size(); i++) {
            remaining[i] = fired.getOrDefault(patterns.get(i), 0L);
        }
        Map<K, BigInteger> balance = new HashMap<>();
        Map<K, BigInteger> required = new LinkedHashMap<>();
        int cursor = start;
        int steps = 0;
        boolean seeded = false;
        while (anyPositive(remaining) && steps++ < replaySteps) {
            PlanningCancellation.check();
            int selected = -1;
            for (int offset = 0; offset < patterns.size(); offset++) {
                int index = (cursor + offset) % patterns.size();
                if (remaining[index] <= 0L) continue;
                if (fallbackEnabled(patterns.get(index), states, balance)) {
                    selected = index;
                    break;
                }
            }
            if (selected < 0) {
                selected = seeded
                        ? bestFallbackCut(states, patterns, remaining, balance, required, start)
                        : nextRemaining(remaining, start);
                topUpFallback(patterns.get(selected), states, BigInteger.ONE,
                        balance, required, false);
                seeded = true;
                cursor = selected;
                continue;
            }

            CraftPattern<K> pattern = patterns.get(selected);
            long batch = fallbackBatch(pattern, states, balance, remaining[selected]);
            fireFallback(pattern, states, BigInteger.valueOf(batch), balance);
            remaining[selected] -= batch;
            cursor = (selected + 1) % patterns.size();
        }

        // A bounded, sound degradation for very large or highly interleaved nets. Charging a whole
        // stable block may overstate the initial marking, but it cannot claim an unexecutable plan.
        if (anyPositive(remaining)) {
            for (int offset = 0; offset < patterns.size(); offset++) {
                int index = (start + offset) % patterns.size();
                if (remaining[index] <= 0L) continue;
                BigInteger times = BigInteger.valueOf(remaining[index]);
                topUpFallback(patterns.get(index), states, times,
                        balance, required, true);
                fireFallback(patterns.get(index), states, times, balance);
                remaining[index] = 0L;
            }
        }
        return new ScheduleOption<>(required, fallbackDelta(states, patterns, fired));
    }

    /** Prefer one refill material, then the smallest one-firing top-up, with stable rotation ties. */
    private static <K> int bestFallbackCut(
            Set<K> states,
            List<CraftPattern<K>> patterns,
            long[] remaining,
            Map<K, BigInteger> balance,
            Map<K, BigInteger> required,
            int start) {
        int best = -1;
        int bestNewKinds = Integer.MAX_VALUE;
        int bestKinds = Integer.MAX_VALUE;
        BigInteger bestTotal = null;
        for (int offset = 0; offset < patterns.size(); offset++) {
            int index = (start + offset) % patterns.size();
            if (remaining[index] <= 0L) continue;
            int newKinds = 0;
            int kinds = 0;
            BigInteger total = BigInteger.ZERO;
            for (Map.Entry<K, BigInteger> input
                    : fallbackInputs(patterns.get(index), states).entrySet()) {
                BigInteger shortage = input.getValue().subtract(
                        amount(balance, input.getKey())).max(BigInteger.ZERO);
                if (shortage.signum() > 0) {
                    kinds++;
                    if (amount(required, input.getKey()).signum() == 0) newKinds++;
                }
                total = total.add(shortage);
            }
            if (best < 0 || newKinds < bestNewKinds
                    || (newKinds == bestNewKinds && kinds < bestKinds)
                    || (newKinds == bestNewKinds && kinds == bestKinds
                            && total.compareTo(bestTotal) < 0)) {
                best = index;
                bestNewKinds = newKinds;
                bestKinds = kinds;
                bestTotal = total;
            }
        }
        if (best < 0) throw new IllegalStateException("firing count underflow");
        return best;
    }

    private static int nextRemaining(long[] remaining, int start) {
        for (int offset = 0; offset < remaining.length; offset++) {
            int index = (start + offset) % remaining.length;
            if (remaining[index] > 0L) return index;
        }
        throw new IllegalStateException("firing count underflow");
    }

    private static <K> boolean fallbackEnabled(
            CraftPattern<K> pattern, Set<K> states, Map<K, BigInteger> balance) {
        Map<K, BigInteger> inputs = fallbackInputs(pattern, states);
        for (Map.Entry<K, BigInteger> input : inputs.entrySet()) {
            if (amount(balance, input.getKey()).compareTo(input.getValue()) < 0) return false;
        }
        return true;
    }

    /** Maximum count that can be fired sequentially without another external top-up. */
    private static <K> long fallbackBatch(
            CraftPattern<K> pattern,
            Set<K> states,
            Map<K, BigInteger> balance,
            long remaining) {
        BigInteger limit = BigInteger.valueOf(remaining);
        Map<K, BigInteger> inputs = fallbackInputs(pattern, states);
        Map<K, BigInteger> outputs = fallbackOutputs(pattern, states);
        for (Map.Entry<K, BigInteger> input : inputs.entrySet()) {
            BigInteger loss = input.getValue().subtract(
                    outputs.getOrDefault(input.getKey(), BigInteger.ZERO));
            if (loss.signum() <= 0) continue;
            BigInteger availableAfterFirst = amount(balance, input.getKey())
                    .subtract(input.getValue());
            BigInteger count = BigInteger.ONE.add(availableAfterFirst.divide(loss));
            limit = limit.min(count);
        }
        return Math.max(1L, limit.longValueExact());
    }

    private static <K> void topUpFallback(
            CraftPattern<K> pattern,
            Set<K> states,
            BigInteger times,
            Map<K, BigInteger> balance,
            Map<K, BigInteger> required,
            boolean sequentialBlock) {
        Map<K, BigInteger> inputs = fallbackInputs(pattern, states);
        Map<K, BigInteger> outputs = fallbackOutputs(pattern, states);
        for (Map.Entry<K, BigInteger> input : inputs.entrySet()) {
            BigInteger demand;
            if (sequentialBlock) {
                BigInteger loss = input.getValue().subtract(
                        outputs.getOrDefault(input.getKey(), BigInteger.ZERO)).max(BigInteger.ZERO);
                demand = input.getValue().add(loss.multiply(times.subtract(BigInteger.ONE)));
            } else {
                demand = input.getValue().multiply(times);
            }
            BigInteger shortage = demand.subtract(amount(balance, input.getKey()));
            if (shortage.signum() <= 0) continue;
            balance.merge(input.getKey(), shortage, BigInteger::add);
            required.merge(input.getKey(), shortage, BigInteger::add);
        }
    }

    private static <K> void fireFallback(
            CraftPattern<K> pattern,
            Set<K> states,
            BigInteger times,
            Map<K, BigInteger> balance) {
        for (Map.Entry<K, BigInteger> input : fallbackInputs(pattern, states).entrySet()) {
            balance.merge(input.getKey(), input.getValue().multiply(times).negate(), BigInteger::add);
        }
        for (Map.Entry<K, BigInteger> output : fallbackOutputs(pattern, states).entrySet()) {
            balance.merge(output.getKey(), output.getValue().multiply(times), BigInteger::add);
        }
    }

    private static <K> Map<K, BigInteger> fallbackDelta(
            Set<K> states,
            List<CraftPattern<K>> patterns,
            Map<CraftPattern<K>, Long> fired) {
        Map<K, BigInteger> result = new LinkedHashMap<>();
        for (CraftPattern<K> pattern : patterns) {
            BigInteger times = BigInteger.valueOf(fired.getOrDefault(pattern, 0L));
            for (Map.Entry<K, BigInteger> input : fallbackInputs(pattern, states).entrySet()) {
                result.merge(input.getKey(), input.getValue().multiply(times).negate(), BigInteger::add);
            }
            for (Map.Entry<K, BigInteger> output : fallbackOutputs(pattern, states).entrySet()) {
                result.merge(output.getKey(), output.getValue().multiply(times), BigInteger::add);
            }
        }
        result.values().removeIf(value -> value.signum() == 0);
        return result;
    }

    private static <K> Map<K, BigInteger> fallbackInputs(
            CraftPattern<K> pattern, Set<K> states) {
        Map<K, BigInteger> result = new LinkedHashMap<>();
        for (CraftInput<K> input : pattern.inputs()) {
            if (states.contains(input.key())) {
                result.merge(input.key(), BigInteger.valueOf(input.amount()), BigInteger::add);
            }
        }
        return result;
    }

    private static <K> Map<K, BigInteger> fallbackOutputs(
            CraftPattern<K> pattern, Set<K> states) {
        Map<K, BigInteger> result = new LinkedHashMap<>();
        if (states.contains(pattern.output())) {
            result.merge(pattern.output(), BigInteger.valueOf(pattern.outputAmount()), BigInteger::add);
        }
        for (CraftOutput<K> output : pattern.byproducts()) {
            if (states.contains(output.key())) {
                result.merge(output.key(), BigInteger.valueOf(output.amount()), BigInteger::add);
            }
        }
        return result;
    }

    private static <K> List<ScheduleOption<K>> schedulesForCounts(
            List<Transition<K>> cycle, long[] counts) {
        BigInteger total = BigInteger.ZERO;
        for (long count : counts) total = total.add(BigInteger.valueOf(count));
        boolean exact = total.compareTo(BigInteger.valueOf(MAX_PRIMITIVE_ROUND_FIRINGS)) <= 0;
        List<ScheduleOption<K>> result = new ArrayList<>(cycle.size());
        Set<Map<K, BigInteger>> seen = new HashSet<>();
        for (int start = 0; start < cycle.size(); start++) {
            PlanningCancellation.check();
            ScheduleOption<K> option = exact
                    ? replayUnitFirings(cycle, counts, start, total.intValueExact())
                    : replayGroupedFirings(cycle, counts, start);
            if (seen.add(option.required())) result.add(option);
        }
        return result;
    }

    private static <K> ScheduleOption<K> replayUnitFirings(
            List<Transition<K>> cycle, long[] counts, int start, int total) {
        long[] remaining = counts.clone();
        Map<K, BigInteger> balance = new HashMap<>();
        Map<K, BigInteger> required = new LinkedHashMap<>();
        int cursor = start;
        for (int step = 0; step < total; step++) {
            PlanningCancellation.check();
            int selected = -1;
            for (int offset = 0; offset < cycle.size(); offset++) {
                int index = (cursor + offset) % cycle.size();
                if (remaining[index] <= 0L) continue;
                Transition<K> transition = cycle.get(index);
                if (amount(balance, transition.input())
                        .compareTo(BigInteger.valueOf(transition.inputAmount())) >= 0) {
                    selected = index;
                    break;
                }
            }
            if (selected < 0) {
                // A rotation denotes one canonical seed state. Keep topping up that state while its
                // transition remains; only after it is exhausted may a residual require another cut.
                if (remaining[start] > 0L) {
                    selected = start;
                } else {
                    for (int offset = 0; offset < cycle.size(); offset++) {
                        int index = (cursor + offset) % cycle.size();
                        if (remaining[index] > 0L) {
                            selected = index;
                            break;
                        }
                    }
                }
                if (selected < 0) throw new IllegalStateException("firing count underflow");
                topUpFor(cycle.get(selected), BigInteger.ONE, balance, required);
            }
            fire(cycle.get(selected), BigInteger.ONE, balance);
            remaining[selected]--;
            cursor = (selected + 1) % cycle.size();
        }
        return new ScheduleOption<>(required, deltaForCounts(cycle, counts));
    }

    private static <K> ScheduleOption<K> replayGroupedFirings(
            List<Transition<K>> cycle, long[] counts, int start) {
        Map<K, BigInteger> balance = new HashMap<>();
        Map<K, BigInteger> required = new LinkedHashMap<>();
        for (int offset = 0; offset < cycle.size(); offset++) {
            PlanningCancellation.check();
            int index = (start + offset) % cycle.size();
            if (counts[index] <= 0L) continue;
            BigInteger times = BigInteger.valueOf(counts[index]);
            topUpFor(cycle.get(index), times, balance, required);
            fire(cycle.get(index), times, balance);
        }
        return new ScheduleOption<>(required, deltaForCounts(cycle, counts));
    }

    private static <K> void topUpFor(
            Transition<K> transition,
            BigInteger times,
            Map<K, BigInteger> balance,
            Map<K, BigInteger> required) {
        BigInteger demand = BigInteger.valueOf(transition.inputAmount()).multiply(times);
        BigInteger available = amount(balance, transition.input());
        if (available.compareTo(demand) >= 0) return;
        BigInteger shortage = demand.subtract(available);
        balance.put(transition.input(), demand);
        required.merge(transition.input(), shortage, BigInteger::add);
    }

    private static <K> void fire(
            Transition<K> transition, BigInteger times, Map<K, BigInteger> balance) {
        BigInteger consumed = BigInteger.valueOf(transition.inputAmount()).multiply(times);
        BigInteger produced = BigInteger.valueOf(transition.outputAmount()).multiply(times);
        balance.merge(transition.input(), consumed.negate(), BigInteger::add);
        balance.merge(transition.output(), produced, BigInteger::add);
    }

    private static <K> Map<K, BigInteger> deltaForCounts(
            List<Transition<K>> cycle, long[] counts) {
        Map<K, BigInteger> result = new LinkedHashMap<>();
        for (int i = 0; i < cycle.size(); i++) {
            if (counts[i] <= 0L) continue;
            Transition<K> transition = cycle.get(i);
            BigInteger times = BigInteger.valueOf(counts[i]);
            result.merge(transition.input(),
                    BigInteger.valueOf(transition.inputAmount()).multiply(times).negate(),
                    BigInteger::add);
            result.merge(transition.output(),
                    BigInteger.valueOf(transition.outputAmount()).multiply(times),
                    BigInteger::add);
        }
        result.values().removeIf(value -> value.signum() == 0);
        return result;
    }

    private static <K> ScheduleOption<K> repeat(
            ScheduleOption<K> block, long repetitions, Set<K> states) {
        if (repetitions <= 0L) return zeroSchedule();
        Map<K, BigInteger> required = new LinkedHashMap<>();
        Map<K, BigInteger> delta = new LinkedHashMap<>();
        BigInteger repeats = BigInteger.valueOf(repetitions);
        BigInteger previous = BigInteger.valueOf(repetitions - 1L);
        for (K state : states) {
            BigInteger change = amount(block.delta(), state);
            if (change.signum() > 0) return null;
            BigInteger need = amount(block.required(), state)
                    .add(change.negate().multiply(previous));
            if (need.signum() > 0) required.put(state, need);
            BigInteger totalChange = change.multiply(repeats);
            if (totalChange.signum() != 0) delta.put(state, totalChange);
        }
        return new ScheduleOption<>(required, delta);
    }

    private static <K> ScheduleOption<K> compose(
            ScheduleOption<K> first, ScheduleOption<K> second, Set<K> states) {
        Map<K, BigInteger> required = new LinkedHashMap<>();
        Map<K, BigInteger> delta = new LinkedHashMap<>();
        for (K state : states) {
            BigInteger firstRequired = amount(first.required(), state);
            BigInteger secondRequiredAtStart = amount(second.required(), state)
                    .subtract(amount(first.delta(), state));
            BigInteger need = firstRequired.max(secondRequiredAtStart).max(BigInteger.ZERO);
            if (need.signum() > 0) required.put(state, need);
            BigInteger change = amount(first.delta(), state).add(amount(second.delta(), state));
            if (change.signum() != 0) delta.put(state, change);
        }
        return new ScheduleOption<>(required, delta);
    }

    private static <K> ScheduleOption<K> zeroSchedule() {
        return new ScheduleOption<>(Map.of(), Map.of());
    }

    private static boolean anyPositive(long[] counts) {
        for (long count : counts) if (count > 0L) return true;
        return false;
    }

    private static <K> BigInteger amount(Map<K, BigInteger> values, K key) {
        return values.getOrDefault(key, BigInteger.ZERO);
    }

    private static BigInteger lcm(BigInteger left, BigInteger right) {
        if (left.signum() == 0 || right.signum() == 0) return BigInteger.ZERO;
        return left.divide(left.gcd(right)).multiply(right);
    }

    private static <K> boolean mergeExact(Map<K, Long> values, K key, long amount) {
        try {
            values.put(key, Math.addExact(values.getOrDefault(key, 0L), amount));
            return true;
        } catch (ArithmeticException ignored) {
            return false;
        }
    }

    private static final class DfsFrame<K> {
        private final K node;
        private final List<K> children;
        private int index;

        private DfsFrame(K node, List<K> children) {
            this.node = node;
            this.children = children;
        }
    }

    private static <K> List<Set<K>> stronglyConnectedComponents(
            Set<K> nodes,
            Map<K, List<K>> adjacency) {
        List<K> finished = new ArrayList<>(nodes.size());
        Set<K> visited = new HashSet<>();
        for (K root : nodes) {
            if (!visited.add(root)) continue;
            Deque<DfsFrame<K>> stack = new ArrayDeque<>();
            stack.push(new DfsFrame<>(root, adjacency.getOrDefault(root, List.of())));
            while (!stack.isEmpty()) {
                PlanningCancellation.check();
                DfsFrame<K> frame = stack.peek();
                if (frame.index < frame.children.size()) {
                    K child = frame.children.get(frame.index++);
                    if (visited.add(child)) {
                        stack.push(new DfsFrame<>(child, adjacency.getOrDefault(child, List.of())));
                    }
                } else {
                    finished.add(frame.node);
                    stack.pop();
                }
            }
        }

        Map<K, List<K>> reverse = new LinkedHashMap<>();
        for (K node : nodes) reverse.put(node, new ArrayList<>());
        for (Map.Entry<K, List<K>> entry : adjacency.entrySet()) {
            for (K child : entry.getValue()) {
                reverse.computeIfAbsent(child, ignored -> new ArrayList<>()).add(entry.getKey());
            }
        }

        List<Set<K>> result = new ArrayList<>();
        Set<K> assigned = new HashSet<>();
        for (int i = finished.size() - 1; i >= 0; i--) {
            K root = finished.get(i);
            if (!assigned.add(root)) continue;
            Set<K> component = new LinkedHashSet<>();
            Deque<K> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                PlanningCancellation.check();
                K node = stack.pop();
                component.add(node);
                for (K previous : reverse.getOrDefault(node, List.of())) {
                    if (assigned.add(previous)) stack.push(previous);
                }
            }
            result.add(component);
        }
        return result;
    }
}
