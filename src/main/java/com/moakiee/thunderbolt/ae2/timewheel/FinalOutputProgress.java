package com.moakiee.thunderbolt.ae2.timewheel;

/** Final-output accounting shared by the time-wheel CPU and its regression tests. */
final class FinalOutputProgress {
    private FinalOutputProgress() {
    }

    static long completedAmount(boolean standalone, long offered, long requesterAccepted) {
        long boundedOffered = Math.max(0L, offered);
        if (standalone) {
            return boundedOffered;
        }
        return Math.min(boundedOffered, Math.max(0L, requesterAccepted));
    }

    /**
     * Amount a requester job must retain in the CPU and retry later. Standalone jobs deliberately
     * leave their unaccepted output in the current ME insertion chain instead.
     */
    static long deferredRequesterAmount(boolean standalone, long offered, long requesterAccepted) {
        if (standalone) {
            return 0L;
        }
        long boundedOffered = Math.max(0L, offered);
        long boundedAccepted = Math.min(boundedOffered, Math.max(0L, requesterAccepted));
        return boundedOffered - boundedAccepted;
    }
}
