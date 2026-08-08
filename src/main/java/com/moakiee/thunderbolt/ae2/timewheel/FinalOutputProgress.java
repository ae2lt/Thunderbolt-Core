package com.moakiee.thunderbolt.ae2.timewheel;

final class FinalOutputProgress {
   private FinalOutputProgress() {
   }

   static long recoverableInventoryAmount(long held, long reusableReserve, long retained, long remainingDemand) {
      long protectedAmount = addSaturated(Math.max(0L, reusableReserve), Math.max(0L, retained));
      long unprotected = Math.max(0L, Math.max(0L, held) - protectedAmount);
      return Math.min(unprotected, Math.max(0L, remainingDemand));
   }

   private static long addSaturated(long left, long right) {
      return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
   }
}
