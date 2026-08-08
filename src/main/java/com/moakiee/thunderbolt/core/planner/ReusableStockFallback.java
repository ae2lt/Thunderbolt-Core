package com.moakiee.thunderbolt.core.planner;

public final class ReusableStockFallback {
   public static long supplementalSelfSeedStock(long required, long seedSnapshotAmount, long ordinaryVisibleAmount) {
      long positiveRequired = Math.max(0L, required);
      long available = Math.min(positiveRequired, Math.max(0L, seedSnapshotAmount));
      long ordinaryVisible = Math.min(positiveRequired, Math.max(0L, ordinaryVisibleAmount));
      return Math.max(0L, available - ordinaryVisible);
   }

   private ReusableStockFallback() {
   }
}
