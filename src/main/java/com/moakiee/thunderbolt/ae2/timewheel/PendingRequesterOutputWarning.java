package com.moakiee.thunderbolt.ae2.timewheel;

final class PendingRequesterOutputWarning {
   static final long WARNING_DELAY_TICKS = 20L;
   private static final long NOT_BLOCKED = Long.MIN_VALUE;
   private long blockedSinceTick = Long.MIN_VALUE;

   boolean update(long currentTick, boolean blocked) {
      if (!blocked) {
         this.reset();
         return false;
      } else if (this.blockedSinceTick != Long.MIN_VALUE && currentTick >= this.blockedSinceTick) {
         return currentTick - this.blockedSinceTick >= 20L;
      } else {
         this.blockedSinceTick = currentTick;
         return false;
      }
   }

   void reset() {
      this.blockedSinceTick = Long.MIN_VALUE;
   }
}
