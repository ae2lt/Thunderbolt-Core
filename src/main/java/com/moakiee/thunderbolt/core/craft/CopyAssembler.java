package com.moakiee.thunderbolt.core.craft;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import java.util.List;

public interface CopyAssembler {
   CopyAssembler.AssembledCopy assembleOneCopy(IPatternDetails var1, KeyCounter[] var2);

   public static record AssembledCopy(AEKey output, long outputCount, List<CopyAssembler.Stack> remainders, List<CopyAssembler.Stack> sharedRemainders) {
      public AssembledCopy(AEKey output, long outputCount, List<CopyAssembler.Stack> remainders) {
         this(output, outputCount, remainders, List.of());
      }
   }

   public static record Stack(AEKey key, long count) {
   }
}
