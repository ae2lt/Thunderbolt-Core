package com.moakiee.thunderbolt.core.planner;

import java.util.Objects;

public record ReusableStockSource(Object storageScope, Object poolScope, Object routingScope) {
   public ReusableStockSource(Object storageScope, Object poolScope) {
      this(storageScope, poolScope, poolScope);
   }

   public ReusableStockSource(Object storageScope, Object poolScope, Object routingScope) {
      Objects.requireNonNull(storageScope, "storageScope");
      Objects.requireNonNull(poolScope, "poolScope");
      Objects.requireNonNull(routingScope, "routingScope");
      this.storageScope = storageScope;
      this.poolScope = poolScope;
      this.routingScope = routingScope;
   }
}
