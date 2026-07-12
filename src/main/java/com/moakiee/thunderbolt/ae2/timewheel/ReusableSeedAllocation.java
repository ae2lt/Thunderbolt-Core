package com.moakiee.thunderbolt.ae2.timewheel;

/** Pure arithmetic for the job-start rule: take reusable seed storage first, then ME stock. */
public final class ReusableSeedAllocation {
    public static long hostRequest(long plannedUsed, long seedRequirement) {
        return Math.min(Math.max(0L, plannedUsed), Math.max(0L, seedRequirement));
    }

    public static long networkRemainder(long plannedUsed, long hostExtracted) {
        return Math.max(0L, Math.max(0L, plannedUsed) - Math.max(0L, hostExtracted));
    }

    private ReusableSeedAllocation() { }
}
