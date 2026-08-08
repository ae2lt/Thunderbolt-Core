package com.moakiee.thunderbolt.ae2.api.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;

public interface IBatchCraftingProvider extends ICraftingProvider {
   default BatchDispatchMode getBatchDispatchMode(IPatternDetails details) {
      return BatchDispatchMode.NORMAL;
   }

   default long getBatchCapacity(IPatternDetails details) {
      return this.isBusy() ? 0L : Long.MAX_VALUE;
   }

   default boolean supportsSingleSeedBatch() {
      return false;
   }

   long pushBatch(IPatternDetails var1, KeyCounter[] var2, long var3);

   default long pushBatch(BatchDispatchContext context) {
      return this.pushBatch(context.details(), context.oneCopyTemplate(), context.maxCraft());
   }

   default boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
      return this.pushBatch(patternDetails, inputHolder, 1L) == 0L;
   }
}
