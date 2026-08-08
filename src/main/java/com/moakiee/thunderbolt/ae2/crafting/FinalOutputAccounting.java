package com.moakiee.thunderbolt.ae2.crafting;

public final class FinalOutputAccounting {
   private FinalOutputAccounting() {
   }

   public static long completedAmount(boolean fallsThroughToNetwork, long offered, long requesterAccepted) {
      long boundedOffered = Math.max(0L, offered);
      return fallsThroughToNetwork ? boundedOffered : Math.min(boundedOffered, Math.max(0L, requesterAccepted));
   }

   public static long deferredAmount(boolean fallsThroughToNetwork, long offered, long requesterAccepted) {
      if (fallsThroughToNetwork) {
         return 0L;
      } else {
         long boundedOffered = Math.max(0L, offered);
         long boundedAccepted = Math.min(boundedOffered, Math.max(0L, requesterAccepted));
         return boundedOffered - boundedAccepted;
      }
   }

   public static long physicallyAcceptedAmount(long inventoryAccepted, long completedRequesterAmount, long requesterAccepted) {
      long boundedInventory = Math.max(0L, inventoryAccepted);
      long boundedRequester = Math.min(Math.max(0L, completedRequesterAmount), Math.max(0L, requesterAccepted));
      return addSaturated(boundedInventory, boundedRequester);
   }

   private static long addSaturated(long left, long right) {
      return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
   }
}
