package com.moakiee.thunderbolt.ae2.overload.cpu;

import java.util.Objects;
import java.util.UUID;

/** Reusable-seed ownership attached to one ID_ONLY overload output slot. */
public record OverloadReusableSeedMetadata(
        UUID groupId,
        boolean sharedPool,
        long amount) {
    public OverloadReusableSeedMetadata {
        Objects.requireNonNull(groupId, "groupId");
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
    }
}
