package com.moakiee.thunderbolt.ae2.timewheel;

import appeng.api.stacks.AEKey;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Planning macro that requires a reusable seed pool once per submitted job, not once per firing. */
public interface ReusableSeedPattern {
    /** Stable identity used to keep this contracted cycle's reserve separate from other cycles. */
    UUID reusableSeedGroupId();

    /** Keys in the strongly-connected component that can carry this cycle's reusable state. */
    Set<AEKey> reusableSeedCycleKeys();

    /** True only when every expanded child has exactly one positive {@code inputSeed} key. */
    boolean hasSingleSeedInputPerMember();

    /** Total seed pool required by this job, including its configured parallel multiplier. */
    Map<AEKey, Long> totalReusableSeedRequirements();

    /**
     * Read-only host seed snapshot available to this contracted pattern during planning. The
     * executing CPU still performs the real extraction atomically when the job is submitted.
     */
    default Map<AEKey, Long> availableReusableSeedSnapshot() {
        return Map.of();
    }
}
