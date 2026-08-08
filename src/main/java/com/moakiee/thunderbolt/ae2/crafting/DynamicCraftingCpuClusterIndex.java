package com.moakiee.thunderbolt.ae2.crafting;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

public final class DynamicCraftingCpuClusterIndex<P, C> {
   private final Set<P> providers = Collections.newSetFromMap(new IdentityHashMap<>());
   private final Set<C> clusters = Collections.newSetFromMap(new IdentityHashMap<>());
   private final Set<C> clustersView = Collections.unmodifiableSet(this.clusters);
   private final Set<C> refreshedClusters = Collections.newSetFromMap(new IdentityHashMap<>());

   public boolean addProvider(P provider) {
      return this.providers.add(provider);
   }

   public boolean removeProvider(P provider) {
      return this.providers.remove(provider);
   }

   public void replaceProviders(Iterable<? extends P> replacements) {
      this.providers.clear();

      for (P provider : replacements) {
         this.providers.add(provider);
      }
   }

   public boolean refresh(Function<? super P, ? extends C> resolver, Consumer<? super C> onAdded) {
      this.refreshedClusters.clear();

      for (P provider : this.providers) {
         C cluster = (C)resolver.apply(provider);
         if (cluster != null) {
            this.refreshedClusters.add(cluster);
         }
      }

      boolean changed = !this.clusters.equals(this.refreshedClusters);
      Iterator<C> iterator = this.clusters.iterator();

      while (iterator.hasNext()) {
         if (!this.refreshedClusters.contains(iterator.next())) {
            iterator.remove();
         }
      }

      for (C cluster : this.refreshedClusters) {
         if (this.clusters.add(cluster)) {
            onAdded.accept(cluster);
         }
      }

      return changed;
   }

   public Set<C> clusters() {
      return this.clustersView;
   }
}
