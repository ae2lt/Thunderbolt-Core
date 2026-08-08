package com.moakiee.thunderbolt.core.planner;

import java.util.Objects;

public record ReusableStockKey<K>(Object scope, K key) {
   public ReusableStockKey(Object scope, K key) {
      Objects.requireNonNull(scope, "scope");
      Objects.requireNonNull(key, "key");
      this.scope = scope;
      this.key = key;
   }
}
