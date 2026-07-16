package com.moakiee.thunderbolt.ae2.batch;

import appeng.api.stacks.AEKey;

/** Marks input slots sent once for an accepted batch instead of once per copy. */
public interface SharedBatchInputPattern {
    boolean isSharedBatchInput(int slot, AEKey concreteKey);

    /** Portion of this output that represents the one seed returned for the whole batch. */
    default long sharedBatchOutputAmount(AEKey outputKey) {
        return 0L;
    }
}
