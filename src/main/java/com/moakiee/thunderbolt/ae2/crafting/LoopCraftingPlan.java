package com.moakiee.thunderbolt.ae2.crafting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;

import com.moakiee.thunderbolt.ae2.timewheel.TimeWheelCraftingCpuPoolHost;
import com.moakiee.thunderbolt.ae2.timewheel.ReusableSeedPattern;
import com.moakiee.thunderbolt.ae2.timewheel.TimeWheelPoolRestrictedPattern;

/**
 * A closed-loop plan produced from an AE2 {@link CraftingPlan} and restricted to one compatible
 * time-wheel CPU pool.
 */
public record LoopCraftingPlan(
        CraftingPlan delegate,
        List<TimeWheelPoolRestrictedPattern> restrictions,
        Map<AEKey, Long> totalReusableSeeds,
        Map<AEKey, Long> hostReusableSeeds) implements ICraftingPlan {

    public LoopCraftingPlan {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        restrictions = List.copyOf(restrictions);
        totalReusableSeeds = Map.copyOf(totalReusableSeeds);
        hostReusableSeeds = Map.copyOf(hostReusableSeeds);
        if (restrictions.isEmpty()) {
            throw new IllegalArgumentException("loop plan must have at least one restriction");
        }
    }

    /** Wraps an AE2 plan only when it contains a time-wheel-restricted pattern. */
    public static ICraftingPlan wrapIfNeeded(ICraftingPlan plan) {
        if (!(plan instanceof CraftingPlan craftingPlan)) {
            return plan;
        }
        var restrictions = new ArrayList<TimeWheelPoolRestrictedPattern>();
        var totalSeeds = new LinkedHashMap<AEKey, Long>();
        var availableSeedSnapshot = new LinkedHashMap<AEKey, Long>();
        for (var details : craftingPlan.patternTimes().keySet()) {
            if (details instanceof TimeWheelPoolRestrictedPattern restricted) {
                restrictions.add(restricted);
            }
            if (details instanceof ReusableSeedPattern seeded) {
                mergePositiveSum(totalSeeds, seeded.totalReusableSeedRequirements());
                for (var entry : seeded.availableReusableSeedSnapshot().entrySet()) {
                    if (entry.getKey() != null && positive(entry.getValue()) > 0) {
                        availableSeedSnapshot.merge(entry.getKey(), entry.getValue(), Math::max);
                    }
                }
            }
        }
        var hostSeeds = new LinkedHashMap<AEKey, Long>();
        for (var entry : totalSeeds.entrySet()) {
            long amount = Math.min(entry.getValue(), availableSeedSnapshot.getOrDefault(entry.getKey(), 0L));
            if (amount > 0) {
                hostSeeds.put(entry.getKey(), amount);
            }
        }
        return restrictions.isEmpty()
                ? craftingPlan
                : new LoopCraftingPlan(
                        craftingPlan,
                        restrictions,
                        totalSeeds,
                        hostSeeds);
    }

    public boolean canRunOn(TimeWheelCraftingCpuPoolHost host) {
        for (var restriction : restrictions) {
            if (!restriction.acceptsTimeWheelPool(host)) {
                return false;
            }
        }
        return true;
    }

    /** Reusable-seed requirements kept separate by contracted cycle. */
    public Map<UUID, Map<AEKey, Long>> reusableSeedGroups() {
        var groups = new LinkedHashMap<UUID, Map<AEKey, Long>>();
        for (var details : delegate.patternTimes().keySet()) {
            if (!(details instanceof ReusableSeedPattern seeded)) continue;
            groups.merge(
                    seeded.reusableSeedGroupId(),
                    positiveCopy(seeded.totalReusableSeedRequirements()),
                    LoopCraftingPlan::mergePositiveCopies);
        }
        return Map.copyOf(groups);
    }

    /** Strongly-connected state keys kept separate for each contracted cycle. */
    public Map<UUID, Set<AEKey>> reusableSeedCycleKeys() {
        var groups = new LinkedHashMap<UUID, Set<AEKey>>();
        for (var details : delegate.patternTimes().keySet()) {
            if (!(details instanceof ReusableSeedPattern seeded)) continue;
            groups.merge(
                    seeded.reusableSeedGroupId(),
                    Set.copyOf(seeded.reusableSeedCycleKeys()),
                    (left, right) -> {
                        var merged = new java.util.LinkedHashSet<AEKey>(left);
                        merged.addAll(right);
                        return Set.copyOf(merged);
                    });
        }
        return Map.copyOf(groups);
    }

    /** Groups that must not borrow the protected phase state of another loop macro. */
    public Set<UUID> dedicatedReusableSeedGroups() {
        var groups = new java.util.LinkedHashSet<UUID>();
        for (var details : delegate.patternTimes().keySet()) {
            if (details instanceof ReusableSeedPattern seeded
                    && !seeded.hasSingleSeedInputPerMember()) {
                groups.add(seeded.reusableSeedGroupId());
            }
        }
        return Set.copyOf(groups);
    }

    private static void mergePositiveSum(Map<AEKey, Long> target, Map<AEKey, Long> source) {
        for (var entry : source.entrySet()) {
            if (entry.getKey() != null && positive(entry.getValue()) > 0) {
                target.merge(entry.getKey(), entry.getValue(), LoopCraftingPlan::saturatingAdd);
            }
        }
    }

    private static Map<AEKey, Long> positiveCopy(Map<AEKey, Long> source) {
        var result = new LinkedHashMap<AEKey, Long>();
        mergePositiveSum(result, source);
        return Map.copyOf(result);
    }

    private static Map<AEKey, Long> mergePositiveCopies(
            Map<AEKey, Long> left, Map<AEKey, Long> right) {
        var result = new LinkedHashMap<AEKey, Long>(left);
        mergePositiveSum(result, right);
        return Map.copyOf(result);
    }

    private static long positive(Long value) {
        return value != null ? Math.max(0L, value) : 0L;
    }

    private static long saturatingAdd(long left, long right) {
        return left >= Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    @Override
    public GenericStack finalOutput() {
        return delegate.finalOutput();
    }

    @Override
    public long bytes() {
        return delegate.bytes();
    }

    @Override
    public boolean simulation() {
        return delegate.simulation();
    }

    @Override
    public boolean multiplePaths() {
        return delegate.multiplePaths();
    }

    @Override
    public KeyCounter usedItems() {
        var result = copyCounter(delegate.usedItems());
        for (var entry : hostReusableSeeds.entrySet()) {
            long planned = result.get(entry.getKey());
            result.remove(entry.getKey(), Math.min(planned, entry.getValue()));
        }
        return result;
    }

    @Override
    public KeyCounter emittedItems() {
        return delegate.emittedItems();
    }

    @Override
    public KeyCounter missingItems() {
        return delegate.missingItems();
    }

    @Override
    public Map<IPatternDetails, Long> patternTimes() {
        return delegate.patternTimes();
    }

    private static KeyCounter copyCounter(KeyCounter source) {
        var result = new KeyCounter();
        for (var entry : source) {
            result.add(entry.getKey(), entry.getLongValue());
        }
        return result;
    }
}
