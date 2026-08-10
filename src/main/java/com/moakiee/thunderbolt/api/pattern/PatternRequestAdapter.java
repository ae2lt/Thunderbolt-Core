package com.moakiee.thunderbolt.api.pattern;

import appeng.api.crafting.IPatternDetails;

/**
 * Adapter interface for addons to adapt or wrap an {@link IPatternDetails} for a specific
 * requested amount.
 * <p>
 * Implementations are registered via {@link PatternRequestApi#registerAdapter} and are
 * invoked by Thunderbolt's crafting-tree mixin when a pattern needs to be scaled to a
 * specific requested amount.
 */
public interface PatternRequestAdapter {
    /**
     * Return an adapted view of {@code base} for the given {@code requestedAmount}.
     * Return {@code null} to indicate no adaptation — the caller will fall back to {@code base}.
     */
    IPatternDetails adapt(IPatternDetails base, long requestedAmount);
}
