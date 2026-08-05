package com.moakiee.thunderbolt.ae2.timewheel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FinalOutputProgressTest {
    @Test
    void terminalRecoveryNeverConsumesProtectedSeedOrRetainedOutput() {
        assertEquals(64L,
                FinalOutputProgress.recoverableInventoryAmount(192L, 64L, 64L, 128L));
        assertEquals(0L,
                FinalOutputProgress.recoverableInventoryAmount(128L, 64L, 64L, 128L));
        assertEquals(20L,
                FinalOutputProgress.recoverableInventoryAmount(1_000L, 0L, 0L, 20L));
    }

    @Test
    void terminalRecoveryHandlesSaturatedProtectedAccounting() {
        assertEquals(0L,
                FinalOutputProgress.recoverableInventoryAmount(
                        Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
    }
}
