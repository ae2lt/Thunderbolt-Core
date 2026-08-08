package com.moakiee.thunderbolt.ae2.batch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.me.service.CraftingService;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

public final class TickProviderDispatchSchedule {
   private final IdentityHashMap<IPatternDetails, TickProviderDispatchSchedule.PatternSchedule> patterns = new IdentityHashMap<>();
   private long tick = Long.MIN_VALUE;

   public void beginTick(long currentTick) {
      if (this.tick != currentTick) {
         this.tick = currentTick;
         this.patterns.clear();
      }
   }

   public Iterable<ICraftingProvider> candidates(CraftingService craftingService, IPatternDetails providerLookupPattern, IPatternDetails canonicalPattern) {
      TickProviderDispatchSchedule.PatternSchedule schedule = this.patterns
         .computeIfAbsent(canonicalPattern, ignored -> snapshotProviders(craftingService, providerLookupPattern));
      return schedule.candidates;
   }

   private static TickProviderDispatchSchedule.PatternSchedule snapshotProviders(CraftingService craftingService, IPatternDetails providerLookupPattern) {
      ArrayList<ICraftingProvider> providers = new ArrayList<>();

      for (ICraftingProvider provider : craftingService.getProviders(providerLookupPattern)) {
         providers.add(provider);
      }

      return new TickProviderDispatchSchedule.PatternSchedule(providers);
   }

   public boolean isBlocked(IPatternDetails canonicalPattern, ICraftingProvider provider) {
      TickProviderDispatchSchedule.PatternSchedule schedule = this.patterns.get(canonicalPattern);
      return schedule != null && schedule.candidates.isBlocked(provider);
   }

   public void recordFailure(IPatternDetails canonicalPattern, ICraftingProvider provider) {
      TickProviderDispatchSchedule.PatternSchedule schedule = this.patterns
         .computeIfAbsent(canonicalPattern, ignored -> new TickProviderDispatchSchedule.PatternSchedule(List.of()));
      schedule.candidates.block(provider);
   }

   public void recordSuccess(IPatternDetails canonicalPattern, ICraftingProvider provider) {
      TickProviderDispatchSchedule.PatternSchedule schedule = this.patterns.get(canonicalPattern);
      if (schedule != null) {
         schedule.candidates.markSuccess(provider);
      }
   }

   public int blockedCount(IPatternDetails canonicalPattern) {
      TickProviderDispatchSchedule.PatternSchedule schedule = this.patterns.get(canonicalPattern);
      return schedule != null ? schedule.candidates.blockedCount() : 0;
   }

   private static final class PatternSchedule {
      private final IdentityCandidateQueue<ICraftingProvider> candidates;

      private PatternSchedule(List<ICraftingProvider> providers) {
         this.candidates = new IdentityCandidateQueue<>(providers);
      }
   }
}
