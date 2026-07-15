package com.moakiee.thunderbolt.ae2.timewheel;

import appeng.api.stacks.AEKey;
import java.util.Map;

/** Planning macro that requires a reusable seed pool once per submitted job, not once per firing. */
public interface ReusableSeedPattern {
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
