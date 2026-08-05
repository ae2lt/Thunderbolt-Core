package com.moakiee.thunderbolt.api.crafting.batch;

/** Pattern-side upper bound for one CPU batch dispatch. */
public interface BatchCopyLimitPattern {

    long maxBatchCopies();
}
