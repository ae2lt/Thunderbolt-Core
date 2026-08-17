package com.moakiee.thunderbolt.ae2.timewheel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class TimeWheelClosedLoopStartupContractTest {
    private static final Path LOGIC = Path.of(
            "src/main/java/com/moakiee/thunderbolt/ae2/timewheel/"
                    + "Ae2LtTimeWheelCraftingCpuLogic.java");

    @Test
    void exactInputPrecheckMatchesTheMainProject() throws Exception {
        String source = Files.readString(LOGIC);
        int start = source.indexOf("private Set<AEKey> findMissingExactInputKeys");
        int end = source.indexOf("@Nullable", start);
        assertTrue(start >= 0 && end > start);

        String method = source.substring(start, end);
        assertTrue(method.contains("inventory.extract("));
        assertFalse(method.contains("reservedCraftingInventory"));
        assertFalse(method.contains("seedReturnQuota"));
        assertFalse(method.contains("loopSeedLedgers.totalReserved"));
    }
}
