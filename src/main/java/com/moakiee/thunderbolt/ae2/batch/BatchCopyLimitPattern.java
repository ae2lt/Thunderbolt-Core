package com.moakiee.thunderbolt.ae2.batch;

/** Optional per-pattern upper bound for one batch-provider dispatch. */
public interface BatchCopyLimitPattern {
    int maxBatchCopies();
}
