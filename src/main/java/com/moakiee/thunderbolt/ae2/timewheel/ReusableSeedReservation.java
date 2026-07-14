package com.moakiee.thunderbolt.ae2.timewheel;

import com.moakiee.thunderbolt.core.planner.Sat;

/** Pure arithmetic for the minimum-seed invariant enforced by the TimeWheel CPU. */
public final class ReusableSeedReservation {
    public static long reservedInInventory(long protectedSeed, long inFlightCredit) {
        return Math.max(0L, Math.max(0L, protectedSeed) - Math.max(0L, inFlightCredit));
    }

    public static long availableToOrdinaryTask(
            long held, long protectedSeed, long inFlightCredit) {
        return Math.max(0L, Math.max(0L, held)
                - reservedInInventory(protectedSeed, inFlightCredit));
    }

    public static long afterDispatch(
            long currentCredit, long seedPerCopy, long copies, boolean sharedBatch) {
        long scale = sharedBatch ? 1L : Math.max(0L, copies);
        return Sat.add(Math.max(0L, currentCredit),
                Sat.mul(Math.max(0L, seedPerCopy), scale));
    }

    public static long afterReturn(long currentCredit, long returned) {
        return Math.max(0L, Math.max(0L, currentCredit) - Math.max(0L, returned));
    }

    private ReusableSeedReservation() { }
}
