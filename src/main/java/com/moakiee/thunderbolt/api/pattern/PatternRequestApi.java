package com.moakiee.thunderbolt.api.pattern;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import appeng.api.crafting.IPatternDetails;

/**
 * Public API for addons to hook into Thunderbolt's pattern-request adaptation pipeline.
 * <p>
 * Addons register {@link PatternRequestAdapter}s that can adapt a pattern's
 * {@link IPatternDetails} for a specific requested amount. These adapters are invoked
 * by Thunderbolt's crafting-tree mixin, keeping the mixin layer addon-agnostic.
 */
public final class PatternRequestApi {
    private static final List<PatternRequestAdapter> ADAPTERS = new CopyOnWriteArrayList<>();

    private PatternRequestApi() {}

    /**
     * Register a pattern-request adapter provided by an addon.
     */
    public static void registerAdapter(PatternRequestAdapter adapter) {
        if (adapter == null) return;
        ADAPTERS.add(adapter);
    }

    /**
     * Unregister a previously registered adapter.
     */
    public static void unregisterAdapter(PatternRequestAdapter adapter) {
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
