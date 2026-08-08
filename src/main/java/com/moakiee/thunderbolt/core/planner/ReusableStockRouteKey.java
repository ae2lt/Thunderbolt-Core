package com.moakiee.thunderbolt.core.planner;

import java.util.Objects;

public record ReusableStockRouteKey<K>(ReusableStockSource source, K plannedKey) {
   public ReusableStockRouteKey(ReusableStockSource source, K plannedKey) {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(plannedKey, "plannedKey");
      this.source = source;
      this.plannedKey = plannedKey;
   }
}
