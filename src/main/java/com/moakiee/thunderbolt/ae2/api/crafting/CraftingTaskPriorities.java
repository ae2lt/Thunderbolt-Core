package com.moakiee.thunderbolt.ae2.api.crafting;

public final class CraftingTaskPriorities {
   public static int compare(Object left, Object right) {
      int leftPriority = left instanceof IPrioritizedCraftingTask prioritized ? prioritized.dispatchPriority() : 0;
      int rightPriority = right instanceof IPrioritizedCraftingTask prioritizedx ? prioritizedx.dispatchPriority() : 0;
      int compared = Integer.compare(rightPriority, leftPriority);
      if (compared != 0) {
         return compared;
      } else {
         int leftOrder = left instanceof IPrioritizedCraftingTask prioritizedxx ? prioritizedxx.dispatchOrder() : 0;
         int rightOrder = right instanceof IPrioritizedCraftingTask prioritizedxxx ? prioritizedxxx.dispatchOrder() : 0;
         return Integer.compare(leftOrder, rightOrder);
      }
   }

   public static int compare(Object left, Object right, Object preferred) {
      int compared = compare(left, right);
      if (compared != 0 || left == right) {
         return compared;
      } else if (left == preferred) {
         return -1;
      } else {
         return right == preferred ? 1 : 0;
      }
   }

   private CraftingTaskPriorities() {
   }
}
