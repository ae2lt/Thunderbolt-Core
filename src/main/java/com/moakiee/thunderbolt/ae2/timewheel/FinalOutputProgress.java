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
}
