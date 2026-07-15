package com.moakiee.thunderbolt.ae2.overload.cpu;

import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;

/**
 * One concrete claim against one pending overload output entry.
 */
public record PendingOverloadClaim(
        PendingOverloadOutputKey key,
        long claimedAmount,
        boolean routesToRequester,
        AEKey exactExpectedKey,
        long reusableSeedAmount,
        @Nullable UUID reusableSeedGroupId,
        boolean sharedReusableSeedPool
) {
    public PendingOverloadClaim {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(exactExpectedKey, "exactExpectedKey");
        if (claimedAmount <= 0) {
            throw new IllegalArgumentException("claimedAmount must be > 0");
        }
        if (reusableSeedAmount < 0 || reusableSeedAmount > claimedAmount) {
            throw new IllegalArgumentException("reusableSeedAmount is outside the claim");
        }
        if (reusableSeedAmount > 0) {
            Objects.requireNonNull(reusableSeedGroupId, "reusableSeedGroupId");
        }
    }
}
