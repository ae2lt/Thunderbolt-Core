package com.moakiee.thunderbolt.ae2.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import com.moakiee.thunderbolt.ae2.timewheel.ReusableSeedPattern;
import com.moakiee.thunderbolt.ae2.timewheel.TimeWheelCraftingCpuPoolHost;
import com.moakiee.thunderbolt.ae2.timewheel.TimeWheelPoolRestrictedPattern;
import com.moakiee.thunderbolt.core.planner.ReusableBootstrapRoute;
import com.moakiee.thunderbolt.core.planner.ReusableStockSource;
import com.moakiee.thunderbolt.core.planner.ReusableStockUsageKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;

public record LoopCraftingPlan(
   CraftingPlan delegate,
   List<TimeWheelPoolRestrictedPattern> restrictions,
   Map<AEKey, Long> totalReusableSeeds,
   Map<AEKey, Long> hostReusableSeeds,
   List<LoopCraftingPlan.HostReusableSeedAllocation> hostReusableSeedAllocations
) implements ICraftingPlan {
   public LoopCraftingPlan(
      CraftingPlan delegate,
      List<TimeWheelPoolRestrictedPattern> restrictions,
      Map<AEKey, Long> totalReusableSeeds,
      Map<AEKey, Long> hostReusableSeeds,
      List<LoopCraftingPlan.HostReusableSeedAllocation> hostReusableSeedAllocations
   ) {
      if (delegate == null) {
         throw new IllegalArgumentException("delegate must not be null");
      } else {
         restrictions = List.copyOf(restrictions);
         totalReusableSeeds = Map.copyOf(totalReusableSeeds);
         hostReusableSeeds = Map.copyOf(hostReusableSeeds);
         hostReusableSeedAllocations = List.copyOf(hostReusableSeedAllocations);
         if (restrictions.isEmpty()) {
            throw new IllegalArgumentException("loop plan must have at least one restriction");
         } else {
            this.delegate = delegate;
            this.restrictions = restrictions;
            this.totalReusableSeeds = totalReusableSeeds;
            this.hostReusableSeeds = hostReusableSeeds;
            this.hostReusableSeedAllocations = hostReusableSeedAllocations;
         }
      }
   }

   public static ICraftingPlan wrapIfNeeded(ICraftingPlan plan) {
      return wrapIfNeeded(plan, null);
   }

   public static ICraftingPlan wrapIfNeeded(ICraftingPlan plan, Map<ReusableStockUsageKey<AEKey>, Long> usedReusableStock) {
      if (!(plan instanceof CraftingPlan craftingPlan)) {
         return plan;
      } else {
         ArrayList restrictions = new ArrayList();
         ArrayList reusablePatterns = new ArrayList();

         for (IPatternDetails details : craftingPlan.patternTimes().keySet()) {
            if (details instanceof TimeWheelPoolRestrictedPattern restricted) {
               restrictions.add(restricted);
            }

            if (details instanceof ReusableSeedPattern seeded) {
               reusablePatterns.add(seeded);
            }
         }

         Map<AEKey, Long> totalSeeds = aggregateTotalSeeds(reusablePatterns);
         LinkedHashMap<AEKey, Long> hostSeeds = new LinkedHashMap<>();
         ArrayList<LoopCraftingPlan.HostReusableSeedAllocation> hostAllocations = new ArrayList<>();
         LinkedHashMap<LoopCraftingPlan.HostRequirementKey, Long> hostUsageByRoute = new LinkedHashMap<>();
         Map<LoopCraftingPlan.HostRequirementKey, Long> hostLimitByRoute = aggregateHostLimits(reusablePatterns);
         if (usedReusableStock != null) {
            for (Entry<ReusableStockUsageKey<AEKey>, Long> entry : usedReusableStock.entrySet()) {
               if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L) {
                  ReusableBootstrapRoute<?> bootstrapRoute = entry.getKey().routingScope() instanceof ReusableBootstrapRoute<?> route ? route : null;
                  ReusableSeedPattern owner = bootstrapRoute == null
                     ? reusableStockOwner(reusablePatterns, entry.getKey())
                     : reusableBootstrapOwner(reusablePatterns, entry.getKey(), bootstrapRoute);
                  if (owner == null) {
                     throw new IllegalStateException("private reusable-stock usage has no owning loop pattern");
                  }

                  AEKey plannedKey = bootstrapRoute != null && bootstrapRoute.returnedSeedKey() instanceof AEKey key ? key : entry.getKey().key();
                  hostSeeds.merge(bootstrapRoute != null ? entry.getKey().actualKey() : entry.getKey().key(), entry.getValue(), LoopCraftingPlan::saturatingAdd);
                  if (bootstrapRoute == null) {
                     hostUsageByRoute.merge(LoopCraftingPlan.HostRequirementKey.from(entry.getKey()), entry.getValue(), LoopCraftingPlan::saturatingAdd);
                  }

                  hostAllocations.add(
                     new LoopCraftingPlan.HostReusableSeedAllocation(
                        entry.getKey().storageScope(),
                        entry.getKey().poolScope(),
                        owner.reusableStockSource().routingScope(),
                        plannedKey,
                        entry.getKey().actualKey(),
                        entry.getValue(),
                        owner.reusableSeedGroupId(),
                        owner.hasSingleSeedInputPerMember(),
                        bootstrapRoute != null
                     )
                  );
               }
            }
         }

         for (Entry<LoopCraftingPlan.HostRequirementKey, Long> usage : hostUsageByRoute.entrySet()) {
            if (usage.getValue() > hostLimitByRoute.getOrDefault(usage.getKey(), 0L)) {
               throw new IllegalStateException("private reusable-stock usage exceeds its route seed requirement");
            }
         }

         return (ICraftingPlan)(restrictions.isEmpty()
            ? craftingPlan
            : new LoopCraftingPlan(craftingPlan, restrictions, totalSeeds, hostSeeds, hostAllocations));
      }
   }

   public boolean canRunOn(TimeWheelCraftingCpuPoolHost host) {
      for (TimeWheelPoolRestrictedPattern restriction : this.restrictions) {
         if (!restriction.acceptsTimeWheelPool(host)) {
            return false;
         }
      }

      return true;
   }

   public boolean acceptsReusableSeedVariant(AEKey planned, AEKey actual) {
      if (planned != null && actual != null) {
         for (IPatternDetails details : this.delegate.patternTimes().keySet()) {
            if (details instanceof ReusableSeedPattern seeded
               && seeded.totalReusableSeedRequirements().getOrDefault(planned, 0L) > 0L
               && seeded.acceptsReusableSeedVariant(planned, actual)) {
               return true;
            }
         }

         return planned.equals(actual);
      } else {
         return false;
      }
   }

   public boolean acceptsReusableSeedVariant(LoopCraftingPlan.HostReusableSeedAllocation allocation, AEKey actual) {
      if (allocation != null && actual != null) {
         if (allocation.bootstrap()) {
            return allocation.actualKey().equals(actual);
         } else {
            for (IPatternDetails details : this.delegate.patternTimes().keySet()) {
               if (details instanceof ReusableSeedPattern seeded) {
                  ReusableStockSource source = seeded.reusableStockSource();
                  if (allocation.storageScope().equals(source.storageScope())
                     && allocation.poolScope().equals(source.poolScope())
                     && allocation.routingScope().equals(source.routingScope())
                     && seeded.totalReusableSeedRequirements().getOrDefault(allocation.plannedKey(), 0L) > 0L
                     && allocation.actualKey().equals(actual)
                     && seeded.acceptsReusableSeedVariant(allocation.plannedKey(), actual)) {
                     return true;
                  }
               }
            }

            return false;
         }
      } else {
         return false;
      }
   }

   public Map<UUID, Map<AEKey, Long>> reusableSeedGroups() {
      LinkedHashMap<UUID, Map<AEKey, Long>> groups = new LinkedHashMap<>();

      for (IPatternDetails details : this.delegate.patternTimes().keySet()) {
         if (details instanceof ReusableSeedPattern seeded) {
            groups.merge(seeded.reusableSeedGroupId(), positiveCopy(seeded.totalReusableSeedRequirements()), LoopCraftingPlan::mergePositiveMaxCopies);
         }
      }

      return Map.copyOf(groups);
   }

   public Map<UUID, Set<AEKey>> reusableSeedCycleKeys() {
      LinkedHashMap<UUID, Set<AEKey>> groups = new LinkedHashMap<>();

      for (IPatternDetails details : this.delegate.patternTimes().keySet()) {
         if (details instanceof ReusableSeedPattern seeded) {
            groups.merge(seeded.reusableSeedGroupId(), Set.copyOf(seeded.reusableSeedCycleKeys()), (left, right) -> {
               LinkedHashSet<AEKey> merged = new LinkedHashSet<>(left);
               merged.addAll(right);
               return Set.copyOf(merged);
            });
         }
      }

      return Map.copyOf(groups);
   }

   public Set<UUID> dedicatedReusableSeedGroups() {
      LinkedHashSet<UUID> groups = new LinkedHashSet<>();

      for (IPatternDetails details : this.delegate.patternTimes().keySet()) {
         if (details instanceof ReusableSeedPattern) {
            ReusableSeedPattern seeded = (ReusableSeedPattern)details;
            if (!seeded.hasSingleSeedInputPerMember()) {
               groups.add(seeded.reusableSeedGroupId());
            }
         }
      }

      return Set.copyOf(groups);
   }

   private static void mergePositiveSum(Map<AEKey, Long> target, Map<AEKey, Long> source) {
      for (Entry<AEKey, Long> entry : source.entrySet()) {
         if (entry.getKey() != null && positive(entry.getValue()) > 0L) {
            target.merge(entry.getKey(), entry.getValue(), LoopCraftingPlan::saturatingAdd);
         }
      }
   }

   private static Map<AEKey, Long> positiveCopy(Map<AEKey, Long> source) {
      LinkedHashMap<AEKey, Long> result = new LinkedHashMap<>();
      mergePositiveSum(result, source);
      return Map.copyOf(result);
   }

   private static Map<AEKey, Long> mergePositiveMaxCopies(Map<AEKey, Long> left, Map<AEKey, Long> right) {
      LinkedHashMap<AEKey, Long> result = new LinkedHashMap<>(left);

      for (Entry<AEKey, Long> entry : right.entrySet()) {
         if (entry.getKey() != null && positive(entry.getValue()) > 0L) {
            result.merge(entry.getKey(), entry.getValue(), Math::max);
         }
      }

      return Map.copyOf(result);
   }

   private static Map<AEKey, Long> aggregateTotalSeeds(List<ReusableSeedPattern> patterns) {
      LinkedHashMap<Object, Map<AEKey, Long>> sharedByStorage = new LinkedHashMap<>();
      LinkedHashMap<AEKey, Long> total = new LinkedHashMap<>();

      for (ReusableSeedPattern seeded : patterns) {
         Map<AEKey, Long> requirements = positiveCopy(seeded.totalReusableSeedRequirements());
         if (seeded.hasSingleSeedInputPerMember()) {
            sharedByStorage.merge(seeded.reusableSeedStorageScope(), requirements, LoopCraftingPlan::mergePositiveMaxCopies);
         } else {
            mergePositiveSum(total, requirements);
         }
      }

      for (Map<AEKey, Long> shared : sharedByStorage.values()) {
         mergePositiveSum(total, shared);
      }

      return Map.copyOf(total);
   }

   private static Map<LoopCraftingPlan.HostRequirementKey, Long> aggregateHostLimits(List<ReusableSeedPattern> patterns) {
      LinkedHashMap<LoopCraftingPlan.HostRequirementKey, Long> result = new LinkedHashMap<>();

      for (ReusableSeedPattern seeded : patterns) {
         ReusableStockSource source = seeded.reusableStockSource();

         for (Entry<AEKey, Long> requirement : seeded.totalReusableSeedRequirements().entrySet()) {
            long amount = positive(requirement.getValue());
            if (requirement.getKey() != null && amount > 0L) {
               result.merge(
                  new LoopCraftingPlan.HostRequirementKey(source.storageScope(), source.poolScope(), source.routingScope(), requirement.getKey()),
                  Long.valueOf(amount),
                  Math::max
               );
            }
         }
      }

      return Map.copyOf(result);
   }

   private static ReusableSeedPattern reusableStockOwner(List<ReusableSeedPattern> patterns, ReusableStockUsageKey<AEKey> usage) {
      for (ReusableSeedPattern seeded : patterns) {
         ReusableStockSource source = seeded.reusableStockSource();
         if (usage.storageScope().equals(source.storageScope())
            && usage.poolScope().equals(source.poolScope())
            && usage.routingScope().equals(source.routingScope())
            && seeded.totalReusableSeedRequirements().getOrDefault(usage.key(), 0L) > 0L) {
            return seeded;
         }
      }

      return null;
   }

   private static ReusableSeedPattern reusableBootstrapOwner(
      List<ReusableSeedPattern> patterns, ReusableStockUsageKey<AEKey> usage, ReusableBootstrapRoute<?> bootstrapRoute
   ) {
      if (bootstrapRoute.returnedSeedKey() instanceof AEKey returnedSeedKey) {
         for (ReusableSeedPattern seeded : patterns) {
            ReusableStockSource source = seeded.reusableStockSource();
            if (usage.storageScope().equals(source.storageScope())
               && usage.poolScope().equals(source.poolScope())
               && bootstrapRoute.ownerRoutingScope().equals(source.routingScope())
               && seeded.totalReusableSeedRequirements().getOrDefault(returnedSeedKey, 0L) > 0L) {
               return seeded;
            }
         }

         return null;
      } else {
         return null;
      }
   }

   private static long positive(Long value) {
      return value != null ? Math.max(0L, value) : 0L;
   }

   private static long saturatingAdd(long left, long right) {
      return left >= Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
   }

   public GenericStack finalOutput() {
      return this.delegate.finalOutput();
   }

   public long bytes() {
      return this.delegate.bytes();
   }

   public boolean simulation() {
      return this.delegate.simulation();
   }

   public boolean multiplePaths() {
      return this.delegate.multiplePaths();
   }

   public KeyCounter usedItems() {
      return this.delegate.usedItems();
   }

   public KeyCounter emittedItems() {
      return this.delegate.emittedItems();
   }

   public KeyCounter missingItems() {
      return this.delegate.missingItems();
   }

   public Map<IPatternDetails, Long> patternTimes() {
      return this.delegate.patternTimes();
   }

   private static record HostRequirementKey(Object storageScope, Object poolScope, Object routingScope, AEKey plannedKey) {
      private static LoopCraftingPlan.HostRequirementKey from(ReusableStockUsageKey<AEKey> usage) {
         return new LoopCraftingPlan.HostRequirementKey(usage.storageScope(), usage.poolScope(), usage.routingScope(), usage.key());
      }
   }

   public static record HostReusableSeedAllocation(
      Object storageScope,
      Object poolScope,
      Object routingScope,
      AEKey plannedKey,
      AEKey actualKey,
      long amount,
      UUID reusableSeedGroupId,
      boolean sharedPool,
      boolean bootstrap
   ) {
      public HostReusableSeedAllocation(
         Object storageScope,
         Object poolScope,
         Object routingScope,
         AEKey plannedKey,
         AEKey actualKey,
         long amount,
         UUID reusableSeedGroupId,
         boolean sharedPool,
         boolean bootstrap
      ) {
         Objects.requireNonNull(storageScope, "storageScope");
         Objects.requireNonNull(poolScope, "poolScope");
         Objects.requireNonNull(routingScope, "routingScope");
         Objects.requireNonNull(plannedKey, "plannedKey");
         Objects.requireNonNull(actualKey, "actualKey");
         Objects.requireNonNull(reusableSeedGroupId, "reusableSeedGroupId");
         if (amount <= 0L) {
            throw new IllegalArgumentException("amount must be > 0");
         } else {
            this.storageScope = storageScope;
            this.poolScope = poolScope;
            this.routingScope = routingScope;
            this.plannedKey = plannedKey;
            this.actualKey = actualKey;
            this.amount = amount;
            this.reusableSeedGroupId = reusableSeedGroupId;
            this.sharedPool = sharedPool;
            this.bootstrap = bootstrap;
         }
      }
   }
}
