package com.moakiee.thunderbolt.ae2.timewheel;

public final class ReusableSeedReservation {
   public static long reservedForTask(long totalReserved, long ownPoolBalance, boolean declaredOwnSeedInput) {
      long total = Math.max(0L, totalReserved);
      return !declaredOwnSeedInput ? total : Math.max(0L, total - Math.max(0L, ownPoolBalance));
   }

   private ReusableSeedReservation() {
   }
}
