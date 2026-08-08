package com.moakiee.thunderbolt.ae2.batch;

import appeng.api.networking.crafting.ICraftingProvider;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.NoSuchElementException;

public final class BatchProviderFilterIterable implements Iterable<ICraftingProvider> {
   private final Iterable<ICraftingProvider> raw;
   private final IdentityHashMap<ICraftingProvider, Boolean> excluded;

   public BatchProviderFilterIterable(Iterable<ICraftingProvider> raw, IdentityHashMap<ICraftingProvider, Boolean> excluded) {
      this.raw = raw;
      this.excluded = excluded;
   }

   @Override
   public Iterator<ICraftingProvider> iterator() {
      return new BatchProviderFilterIterable.FilteringIterator(this.raw.iterator(), this.excluded);
   }

   private static final class FilteringIterator implements Iterator<ICraftingProvider> {
      private final Iterator<ICraftingProvider> raw;
      private final IdentityHashMap<ICraftingProvider, Boolean> excluded;
      private ICraftingProvider next;
      private boolean ready;

      private FilteringIterator(Iterator<ICraftingProvider> raw, IdentityHashMap<ICraftingProvider, Boolean> excluded) {
         this.raw = raw;
         this.excluded = excluded;
      }

      @Override
      public boolean hasNext() {
         while (!this.ready && this.raw.hasNext()) {
            ICraftingProvider candidate = this.raw.next();
            if (!this.excluded.containsKey(candidate)) {
               this.next = candidate;
               this.ready = true;
            }
         }

         return this.ready;
      }

      public ICraftingProvider next() {
         if (!this.ready && !this.hasNext()) {
            throw new NoSuchElementException();
         } else {
            this.ready = false;
            ICraftingProvider result = this.next;
            this.next = null;
            return result;
         }
      }
   }
}
