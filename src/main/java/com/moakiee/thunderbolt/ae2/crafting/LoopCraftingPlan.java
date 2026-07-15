package com.moakiee.thunderbolt.ae2.crafting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private static void mergePositiveSum(Map<AEKey, Long> target, Map<AEKey, Long> source) {
        for (var entry : source.entrySet()) {
            if (entry.getKey() != null && positive(entry.getValue()) > 0) {
                target.merge(entry.getKey(), entry.getValue(), LoopCraftingPlan::saturatingAdd);
            }
        }
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
