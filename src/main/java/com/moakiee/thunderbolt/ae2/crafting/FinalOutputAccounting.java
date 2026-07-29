package com.moakiee.thunderbolt.ae2.crafting;

/**
 * Keeps final-output job progress separate from physical insertion acceptance.
 *
 * <p>AE2 standalone jobs deliberately complete when their final output is
 * offered, even though their requester accepts zero and the item falls through
 * to ordinary ME storage. Requester-backed jobs may instead retain a rejected
 * output in the CPU for a later retry.</p>
 */
public final class FinalOutputAccounting {
    private FinalOutputAccounting() {
    }

    public static long completedAmount(
            boolean fallsThroughToNetwork,
            long offered,
            long requesterAccepted) {
        long boundedOffered = Math.max(0L, offered);
        if (fallsThroughToNetwork) {
            return boundedOffered;
        }
        return Math.min(boundedOffered, Math.max(0L, requesterAccepted));
    }

    public static long deferredAmount(
            boolean fallsThroughToNetwork,
            long offered,
            long requesterAccepted) {
        if (fallsThroughToNetwork) {
            return 0L;
        }
        long boundedOffered = Math.max(0L, offered);
        long boundedAccepted = Math.min(
                boundedOffered,
                Math.max(0L, requesterAccepted));
        return boundedOffered - boundedAccepted;
    }

    /**
     * Amount the current insertion chain must consider physically consumed.
     * Completed requester claims can exceed this value when output falls
     * through to normal ME storage.
     */
    public static long physicallyAcceptedAmount(
            long inventoryAccepted,
            long completedRequesterAmount,
            long requesterAccepted) {
        long boundedInventory = Math.max(0L, inventoryAccepted);
        long boundedRequester = Math.min(
                Math.max(0L, completedRequesterAmount),
                Math.max(0L, requesterAccepted));
        return addSaturated(boundedInventory, boundedRequester);
    }

    private static long addSaturated(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
