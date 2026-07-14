package com.moakiee.thunderbolt.ae2.timewheel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ReusableSeedReservationTest {
    @Test
    void dispatchedLoopSeedRemainsProtectedUntilItReturns() {
        long credit = ReusableSeedReservation.afterDispatch(0, 1, 500, false);

        assertEquals(500, credit);
        assertEquals(0, ReusableSeedReservation.availableToOrdinaryTask(500, 1_000, credit));

        credit = ReusableSeedReservation.afterReturn(credit, 1_000);
        assertEquals(0, credit);
        assertEquals(500, ReusableSeedReservation.availableToOrdinaryTask(1_500, 1_000, credit));
    }

    @Test
    void loopTaskMayUseTheWholePoolWhileOrdinaryTaskOnlySeesSurplus() {
        assertEquals(1_500,
                ReusableSeedReservation.availableToOrdinaryTask(1_500, 0, 0));
        assertEquals(500,
                ReusableSeedReservation.availableToOrdinaryTask(1_500, 1_000, 0));
    }

    @Test
    void sharedSingleSeedBatchCreditsOnlyThePhysicalSeedOnce() {
        assertEquals(1,
                ReusableSeedReservation.afterDispatch(0, 1, 1_000, true));
        assertEquals(1_000,
                ReusableSeedReservation.afterDispatch(0, 1, 1_000, false));
    }
}
