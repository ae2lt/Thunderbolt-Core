package com.moakiee.thunderbolt.api.probability;

import appeng.api.crafting.IPatternDetails;

/**
 * Adapter interface implemented by addons (e.g. Probability-Pattern) to adapt or wrap an
 * {@link IPatternDetails} for a requested amount.
 * <p>
 * Implementations are registered via {@link ProbabilityPatternApi#registerPatternRequestAdapter}
 * and are invoked by Thunderbolt's crafting-tree mixin when a pattern needs to be scaled
 * to a specific requested amount.
 */
public interface PatternRequestAdapter {
    /**
     * Return an adapted view of {@code base} for the given {@code requestedAmount}.
     * Return {@code null} to indicate no adaptation — the caller will fall back to {@code base}.
     */
    IPatternDetails adapt(IPatternDetails base, long requestedAmount);
}
