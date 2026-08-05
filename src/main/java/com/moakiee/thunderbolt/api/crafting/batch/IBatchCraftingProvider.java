package com.moakiee.thunderbolt.api.crafting.batch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;

/**
 * Stable opt-in contract for CPU-side homogeneous batch dispatch.
 *
 * <p>The caller supplies a single-copy input template and a maximum copy count. Implementations
 * return the number of copies they did not accept. Returning a partial leftover is valid.
 */
public interface IBatchCraftingProvider extends ICraftingProvider {

    default BatchDispatchMode getBatchDispatchMode(IPatternDetails details) {
        return BatchDispatchMode.NORMAL;
    }

    /** Advisory upper bound. {@link #pushBatch(BatchDispatchContext)} remains authoritative. */
    default long getBatchCapacity(IPatternDetails details) {
        return isBusy() ? 0L : Long.MAX_VALUE;
    }

    /**
     * Whether explicitly identified reusable inputs may be supplied once for all accepted copies.
     *
     * <p>This is capability only. It does not decide which input is reusable and does not grant
     * ownership, routing or persistence semantics to the provider.
     */
    default boolean supportsSharedBatchInputs() {
        return supportsSingleSeedBatch();
    }

    /**
     * Compatibility name retained for providers compiled against the first Thunderbolt batch API.
     * New implementations should override {@link #supportsSharedBatchInputs()}.
     */
    @Deprecated(forRemoval = false)
    default boolean supportsSingleSeedBatch() {
        return false;
    }

    long pushBatch(IPatternDetails details, KeyCounter[] oneCopyTemplate, long maxCraft);

    default long pushBatch(BatchDispatchContext context) {
        return pushBatch(context.details(), context.oneCopyTemplate(), context.maxCraft());
    }

    @Override
    default boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        return pushBatch(patternDetails, inputHolder, 1L) == 0L;
    }
}
