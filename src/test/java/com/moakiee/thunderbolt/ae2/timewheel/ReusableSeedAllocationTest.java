package com.moakiee.thunderbolt.ae2.timewheel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ReusableSeedAllocationTest {
    @Test
    void enoughHostSeedsLeaveNothingForMeNetwork() {
        long request = ReusableSeedAllocation.hostRequest(2, 2);
        assertEquals(2, request);
        assertEquals(0, ReusableSeedAllocation.networkRemainder(2, 2));
    }

    @Test
    void insufficientHostSeedsFallBackToMeNetworkForExactRemainder() {
        long request = ReusableSeedAllocation.hostRequest(2, 2);
        assertEquals(2, request);
        assertEquals(1, ReusableSeedAllocation.networkRemainder(2, 1));
        assertEquals(2, ReusableSeedAllocation.networkRemainder(2, 0));
    }

    @Test
    void hostNeverTakesMoreThanPlanActuallyUsesOrDeclaredSeedRequirement() {
        assertEquals(1, ReusableSeedAllocation.hostRequest(1, 5));
        assertEquals(2, ReusableSeedAllocation.hostRequest(5, 2));
        assertEquals(0, ReusableSeedAllocation.hostRequest(-1, 2));
        assertEquals(0, ReusableSeedAllocation.networkRemainder(1, 5));
    }
}
