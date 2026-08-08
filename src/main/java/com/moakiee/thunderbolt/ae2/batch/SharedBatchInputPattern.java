package com.moakiee.thunderbolt.ae2.batch;

import appeng.api.stacks.AEKey;

public interface SharedBatchInputPattern {
   boolean isSharedBatchInput(int var1, AEKey var2);

   default long sharedBatchOutputAmount(AEKey outputKey) {
      return 0L;
   }
}
