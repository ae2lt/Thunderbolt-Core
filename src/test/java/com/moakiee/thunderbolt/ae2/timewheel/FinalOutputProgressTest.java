package com.moakiee.thunderbolt.ae2.timewheel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FinalOutputProgressTest {
    @Test
    void standaloneJobCompletesFromTheAmountOfferedToMeStorage() {
        assertEquals(1L, FinalOutputProgress.completedAmount(true, 1L, 0L));
        assertEquals(1_000L, FinalOutputProgress.completedAmount(true, 1_000L, 0L));
    }

    @Test
    void requesterJobCompletesOnlyTheAmountAcceptedByItsRequester() {
        assertEquals(3L, FinalOutputProgress.completedAmount(false, 10L, 3L));
        assertEquals(10L, FinalOutputProgress.completedAmount(false, 10L, 20L));
    }
}
