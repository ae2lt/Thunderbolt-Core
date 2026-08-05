package com.moakiee.thunderbolt.api.probability;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import appeng.api.crafting.IPatternDetails;

/**
 * Public API for Probability-Pattern addons to hook into Thunderbolt's AE2 processing.
 * <p>
 * It allows an addon (e.g. Probability-Pattern) to register {@link PatternRequestAdapter}s
 * that adapt a pattern's {@link IPatternDetails} for a requested amount — invoked by
 * Thunderbolt's crafting-tree mixin. This keeps Thunderbolt's mixins addon-agnostic.
 */
public final class ProbabilityPatternApi {
    private static final List<PatternRequestAdapter> ADAPTERS = new CopyOnWriteArrayList<>();

    private ProbabilityPatternApi() {}

    /**
     * Register a pattern-request adapter provided by an addon.
     */
    public static void registerPatternRequestAdapter(PatternRequestAdapter adapter) {
        if (adapter == null) return;
        ADAPTERS.add(adapter);
    }

    /**
     * Unregister a previously registered adapter.
     */
    public static void unregisterPatternRequestAdapter(PatternRequestAdapter adapter) {
        if (adapter == null) return;
        ADAPTERS.remove(adapter);
    }

    /**
     * Adapt the provided {@code base} pattern details for the specified {@code requestedAmount} by
     * invoking registered adapters in registration order. The first non-null adaptation is returned.
     * If none adapt, the original base is returned. Adapter exceptions are ignored defensively.
     */
    public static IPatternDetails adaptPatternForRequest(IPatternDetails base, long requestedAmount) {
        if (ADAPTERS.isEmpty() || base == null) return base;
        for (PatternRequestAdapter a : ADAPTERS) {
            try {
                IPatternDetails out = a.adapt(base, requestedAmount);
                if (out != null) return out;
            } catch (Throwable t) {
                // defensive: do not let faulty adapters break AE2/Thunderbolt behaviour
                t.printStackTrace();
            }
        }
        return base;
    }
}
