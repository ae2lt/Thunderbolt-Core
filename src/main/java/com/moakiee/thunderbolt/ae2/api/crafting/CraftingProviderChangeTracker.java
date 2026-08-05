package com.moakiee.thunderbolt.ae2.api.crafting;

import appeng.api.networking.crafting.ICraftingService;
import appeng.hooks.ticking.TickHandler;
import appeng.me.service.CraftingService;
import com.moakiee.thunderbolt.ae2.mixin.CraftingServiceAccessor;

/**
 * Detects changes to AE2's mounted crafting-provider set without exposing AE2 internals to
 * dependent mods.
 */
public final class CraftingProviderChangeTracker {
    private long lastCheckTick = Long.MIN_VALUE;

    /**
     * Returns true when provider-dependent state should be recomputed.
     *
     * <p>An equality check intentionally triggers once more on the following tick. AE2 timestamps
     * provider changes at tick granularity, so another provider may change later in the same tick
     * after the caller has already checked.
     */
    public boolean shouldRecheck(ICraftingService service) {
        if (!(service instanceof CraftingService crafting)) return true;
        long changedTick = ((CraftingServiceAccessor) crafting)
                .thunderbolt$getCraftingProviders().getLastModifiedOnTick();
        return shouldRecheck(changedTick, TickHandler.instance().getCurrentTick());
    }

    boolean shouldRecheck(long changedTick, long currentTick) {
        if (changedTick < lastCheckTick) return false;
        lastCheckTick = currentTick;
        return true;
    }

    public void reset() {
        lastCheckTick = Long.MIN_VALUE;
    }
}
