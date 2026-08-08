package com.moakiee.thunderbolt.ae2.batch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetails.IInput;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.moakiee.thunderbolt.ae2.crafting.ExecuteLoopPattern;

public final class SharedBatchInputs {
   private SharedBatchInputs() {
   }

   public static boolean hasSharedInputs(IPatternDetails details) {
      if (details == null) {
         return false;
      } else {
         IInput[] inputs = details.getInputs();

         for (int slot = 0; slot < inputs.length; slot++) {
            for (GenericStack possible : inputs[slot].getPossibleInputs()) {
               if (possible.what() != null && isSharedInput(details, slot, possible.what())) {
                  return true;
               }
            }
         }

         return false;
      }
   }

   public static boolean isSharedInput(IPatternDetails details, int slot, AEKey concreteKey) {
      if (details != null && concreteKey != null) {
         IInput[] inputs = details.getInputs();
         if (slot >= 0 && slot < inputs.length) {
            if ((details instanceof ExecuteLoopPattern loop ? loop.delegate() : details) instanceof SharedBatchInputPattern explicit
               && explicit.isSharedBatchInput(slot, concreteKey)) {
               return true;
            }

            AEKey remaining = inputs[slot].getRemainingKey(concreteKey);
            return concreteKey.equals(remaining);
         } else {
            return false;
         }
      } else {
         return false;
      }
   }
}
