package com.moakiee.thunderbolt.ae2.timewheel;

/**
 * Debounces the generic AE2 "can't store items" warning for deferred requester output.
 *
 * <p>The first delivery attempt can run recursively from inside {@code NetworkStorage.insert}.
 * AE2 rejects that recursion with a zero insertion, even though retrying on a later server tick
 * succeeds normally. Treat only a sustained rejection as a storage failure so that this expected
 * one-tick handoff does not flash an error in the crafting CPU screen.
 */
final class PendingRequesterOutputWarning {
    static final long WARNING_DELAY_TICKS = 20L;
    private static final long NOT_BLOCKED = Long.MIN_VALUE;

    private long blockedSinceTick = NOT_BLOCKED;

    boolean update(long currentTick, boolean blocked) {
        if (!blocked) {
            reset();
            return false;
        }
        if (blockedSinceTick == NOT_BLOCKED || currentTick < blockedSinceTick) {
            blockedSinceTick = currentTick;
            return false;
        }
        return currentTick - blockedSinceTick >= WARNING_DELAY_TICKS;
    }

    void reset() {
        blockedSinceTick = NOT_BLOCKED;
    }
}
