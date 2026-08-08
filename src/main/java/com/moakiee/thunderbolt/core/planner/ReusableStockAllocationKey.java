package com.moakiee.thunderbolt.core.planner;

import java.util.Objects;

public record ReusableStockAllocationKey<K>(ReusableStockRouteKey<K> route, K actualKey) {
   public ReusableStockAllocationKey(ReusableStockRouteKey<K> route, K actualKey) {
      Objects.requireNonNull(route, "route");
      Objects.requireNonNull(actualKey, "actualKey");
      this.route = route;
      this.actualKey = actualKey;
   }
}
