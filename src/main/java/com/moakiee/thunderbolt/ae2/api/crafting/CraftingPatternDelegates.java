package com.moakiee.thunderbolt.ae2.api.crafting;

import appeng.api.crafting.IPatternDetails;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

public final class CraftingPatternDelegates {
   public static IPatternDetails forProviderLookup(IPatternDetails details) {
      Objects.requireNonNull(details, "details");
      Set<IPatternDetails> visited = Collections.newSetFromMap(new IdentityHashMap<>());
      IPatternDetails current = details;

      while (current instanceof IProviderLookupPattern) {
         IProviderLookupPattern wrapped = (IProviderLookupPattern)current;
         if (!visited.add(current)) {
            throw new IllegalStateException("cyclic provider lookup pattern delegation");
         }

         IPatternDetails next = Objects.requireNonNull(wrapped.providerLookupPattern(), "providerLookupPattern");
         if (next == current) {
            throw new IllegalStateException("provider lookup pattern delegates to itself");
         }

         current = next;
      }

      return current;
   }

   private CraftingPatternDelegates() {
   }
}
