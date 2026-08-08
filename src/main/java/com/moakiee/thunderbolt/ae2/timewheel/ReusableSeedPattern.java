package com.moakiee.thunderbolt.ae2.timewheel;

import appeng.api.stacks.AEKey;
import com.moakiee.thunderbolt.core.planner.ReusableStockSource;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public interface ReusableSeedPattern {
   Object reusableSeedStorageScope();

   UUID reusableSeedGroupId();

   Set<AEKey> reusableSeedCycleKeys();

   boolean hasSingleSeedInputPerMember();

   Map<AEKey, Long> totalReusableSeedRequirements();

   default boolean acceptsReusableSeedVariant(AEKey planned, AEKey actual) {
      return planned != null && planned.equals(actual);
   }

   default ReusableStockSource reusableStockSource() {
      Object storage = Objects.requireNonNull(this.reusableSeedStorageScope(), "reusableSeedStorageScope");
      Object pool = this.hasSingleSeedInputPerMember()
         ? new ReusableSeedPattern.SharedPool(storage)
         : new ReusableSeedPattern.DedicatedPool(storage, this.reusableSeedGroupId());
      return new ReusableStockSource(storage, pool, this.reusableSeedGroupId());
   }

   default Map<AEKey, Long> availableReusableSeedSnapshot() {
      return Map.of();
   }

   public static record DedicatedPool(Object storageScope, UUID groupId) {
      public DedicatedPool(Object storageScope, UUID groupId) {
         Objects.requireNonNull(storageScope, "storageScope");
         Objects.requireNonNull(groupId, "groupId");
         this.storageScope = storageScope;
         this.groupId = groupId;
      }
   }

   public static record SharedPool(Object storageScope) {
      public SharedPool(Object storageScope) {
         Objects.requireNonNull(storageScope, "storageScope");
         this.storageScope = storageScope;
      }
   }
}
