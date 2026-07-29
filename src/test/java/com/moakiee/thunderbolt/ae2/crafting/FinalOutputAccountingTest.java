package com.moakiee.thunderbolt.ae2.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FinalOutputAccountingTest {
    @Test
    void rejectedFallThroughOutputCompletesWithoutBeingConsumed() {
        long completed = FinalOutputAccounting.completedAmount(
                true, 1L, 0L);

        assertEquals(1L, completed);
        assertEquals(0L, FinalOutputAccounting.physicallyAcceptedAmount(
                0L, completed, 0L));
        assertEquals(0L, FinalOutputAccounting.deferredAmount(
                true, 1L, 0L));
    }

    @Test
    void rejectedRequesterOutputIsDeferredAndRetainedByTheCpu() {
        long completed = FinalOutputAccounting.completedAmount(
                false, 1L, 0L);
        long deferred = FinalOutputAccounting.deferredAmount(
                false, 1L, 0L);

        assertEquals(0L, completed);
        assertEquals(1L, deferred);
        assertEquals(1L, FinalOutputAccounting.physicallyAcceptedAmount(
                deferred, completed, 0L));
    }

    @Test
    void partialRequesterAcceptanceSplitsCompletionAndDeferral() {
        assertEquals(3L, FinalOutputAccounting.completedAmount(
                false, 10L, 3L));
        assertEquals(7L, FinalOutputAccounting.deferredAmount(
                false, 10L, 3L));
        assertEquals(10L, FinalOutputAccounting.completedAmount(
                false, 10L, 20L));
    }

    @Test
    void requesterAcceptanceIsBoundedByCompletedProgress() {
        assertEquals(5L, FinalOutputAccounting.physicallyAcceptedAmount(
                2L, 3L, 10L));
        assertEquals(4L, FinalOutputAccounting.physicallyAcceptedAmount(
                2L, 10L, 2L));
    }

    @Test
    void physicalAcceptanceSaturates() {
        assertEquals(Long.MAX_VALUE,
                FinalOutputAccounting.physicallyAcceptedAmount(
                        Long.MAX_VALUE, 1L, 1L));
    }
}
