package com.moakiee.thunderbolt.ae2.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BatchCpuAccountingTest {
    @Test
    void quadraticBudgetContinuesPastIntegerCopyRange() {
        assertEquals(10_000_000_000L,
                BatchCpuAccounting.maxCopiesForCpuOps(100_000, BatchCpuAccounting.Mode.QUADRATIC));
    }

    @Test
    void quadraticCostHandlesTheEntireLongRangeWithoutOverflow() {
        assertEquals(Integer.MAX_VALUE,
                BatchCpuAccounting.cpuOpsForCopies(Long.MAX_VALUE, BatchCpuAccounting.Mode.QUADRATIC));
    }
}
