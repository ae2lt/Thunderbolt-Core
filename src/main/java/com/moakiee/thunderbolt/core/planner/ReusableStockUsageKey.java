package com.moakiee.thunderbolt.core.planner;

import java.util.Objects;

/** One private-stock borrow, retaining both its physical host and logical execution pool. */
public record ReusableStockUsageKey<K>(Object storageScope, Object poolScope, K key) {
    public ReusableStockUsageKey {
        Objects.requireNonNull(storageScope, "storageScope");
        Objects.requireNonNull(poolScope, "poolScope");
        Objects.requireNonNull(key, "key");
    }
}
