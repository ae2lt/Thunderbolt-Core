package com.moakiee.thunderbolt.ae2.api.crafting;

import appeng.api.crafting.IPatternDetails;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;

/** Resolves nested execution wrappers to the pattern used for provider registration. */
public final class CraftingPatternDelegates {
    public static IPatternDetails forProviderLookup(IPatternDetails details) {
        Objects.requireNonNull(details, "details");
        var visited = Collections.newSetFromMap(new IdentityHashMap<IPatternDetails, Boolean>());
        var current = details;
        while (current instanceof IProviderLookupPattern wrapped) {
            if (!visited.add(current)) {
                throw new IllegalStateException("cyclic provider lookup pattern delegation");
            }
            var next = Objects.requireNonNull(
                    wrapped.providerLookupPattern(), "providerLookupPattern");
            if (next == current) {
                throw new IllegalStateException("provider lookup pattern delegates to itself");
            }
            current = next;
        }
        return current;
    }

    private CraftingPatternDelegates() { }
}
