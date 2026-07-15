package com.moakiee.thunderbolt.ae2.timewheel;

import appeng.api.stacks.AEKey;
import com.moakiee.thunderbolt.core.planner.ReusableStockSource;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Planning macro that requires a reusable seed pool once per submitted job, not once per firing. */
public interface ReusableSeedPattern {
    /** Stable identity of the physical host storage that owns this pattern's private seeds. */
    Object reusableSeedStorageScope();

    /** Stable identity used to keep this contracted cycle's reserve separate from other cycles. */
    UUID reusableSeedGroupId();

    /** Keys in the strongly-connected component that can carry this cycle's reusable state. */
    Set<AEKey> reusableSeedCycleKeys();

    /** True only when every expanded child has exactly one positive {@code inputSeed} key. */
    boolean hasSingleSeedInputPerMember();

    /** Total seed pool required by this job, including its configured parallel multiplier. */
    Map<AEKey, Long> totalReusableSeedRequirements();

    /** Whether an actual stored variant can satisfy the planned reusable-seed key. */
    default boolean acceptsReusableSeedVariant(AEKey planned, AEKey actual) {
        return planned != null && planned.equals(actual);
    }

    /** Planner routing: safe single-seed loops share one pool; multi-seed loops remain isolated. */
    default ReusableStockSource reusableStockSource() {
        Object storage = Objects.requireNonNull(
                reusableSeedStorageScope(), "reusableSeedStorageScope");
        Object pool = hasSingleSeedInputPerMember()
                ? new SharedPool(storage)
                : new DedicatedPool(storage, reusableSeedGroupId());
        return new ReusableStockSource(storage, pool);
    }

    /**
     * Read-only host seed snapshot available to this contracted pattern during planning. The
     * executing CPU still performs the real extraction atomically when the job is submitted.
     */
    default Map<AEKey, Long> availableReusableSeedSnapshot() {
        return Map.of();
    }

    record SharedPool(Object storageScope) {
        public SharedPool {
            Objects.requireNonNull(storageScope, "storageScope");
        }
    }

    record DedicatedPool(Object storageScope, UUID groupId) {
        public DedicatedPool {
            Objects.requireNonNull(storageScope, "storageScope");
            Objects.requireNonNull(groupId, "groupId");
        }
    }
}
