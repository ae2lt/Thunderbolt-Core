package com.moakiee.thunderbolt.ae2.timewheel;

import appeng.api.stacks.AEKey;
import java.util.Map;

/** Planning macro that requires reusable seeds once per submitted job, not once per firing. */
public interface ReusableSeedPattern {
    Map<AEKey, Long> reusableSeedRequirements();

    /**
     * Maximum seed stock the owning CPU may borrow from host storage for this job. The minimum
     * requirement still controls whether the job can start; any extra stock is optional but is
     * returned and protected exactly like the minimum seed while the job runs.
     */
    default Map<AEKey, Long> maximumReusableSeedRequirements() {
        return reusableSeedRequirements();
    }

    /**
     * Read-only host seed snapshot available to this contracted pattern during planning. The
     * executing CPU still performs the real extraction atomically when the job is submitted.
     */
    default Map<AEKey, Long> availableReusableSeedSnapshot() {
        return Map.of();
    }
}
