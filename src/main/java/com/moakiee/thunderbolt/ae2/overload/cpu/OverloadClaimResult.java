package com.moakiee.thunderbolt.ae2.overload.cpu;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Result of attempting to claim incoming items against overload-side pending
 * outputs.
 */
public record OverloadClaimResult(
        long claimedAmount,
        List<PendingOverloadClaim> claims
) {
    public static final OverloadClaimResult EMPTY = new OverloadClaimResult(0, List.of());

    public OverloadClaimResult {
        if (claimedAmount < 0) {
            throw new IllegalArgumentException("claimedAmount must be >= 0");
        }
        claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
    }

    public boolean claimedAnything() {
        return claimedAmount > 0;
    }

    public long claimedForRequester() {
        long total = 0;
        for (var claim : claims) {
            if (claim.routesToRequester()) {
                total += claim.claimedAmount() - claim.reusableSeedAmount();
            }
        }
        return total;
    }

    public long claimedForInventory() {
        return claimedAmount - claimedForRequester();
    }

    /**
     * Keeps every inventory/reusable-seed claim but caps the requester-routed portion to what the
     * requester actually accepted. The unaccepted tail remains pending and can be retried later.
     */
    public OverloadClaimResult limitRequester(long acceptedRequesterAmount) {
        long requesterRemaining = Math.max(0L, acceptedRequesterAmount);
        long total = 0L;
        var limited = new ArrayList<PendingOverloadClaim>(claims.size());
        for (var claim : claims) {
            long kept = claim.claimedAmount();
            if (claim.routesToRequester()) {
                long requesterPart = claim.claimedAmount() - claim.reusableSeedAmount();
                long accepted = Math.min(requesterPart, requesterRemaining);
                requesterRemaining -= accepted;
                kept = claim.reusableSeedAmount() + accepted;
            }
            if (kept <= 0) continue;
            long reusable = Math.min(claim.reusableSeedAmount(), kept);
            limited.add(new PendingOverloadClaim(
                    claim.key(),
                    kept,
                    claim.routesToRequester(),
                    claim.exactExpectedKey(),
                    reusable,
                    claim.reusableSeedGroupId(),
                    claim.sharedReusableSeedPool()));
            total = addSaturated(total, kept);
        }
        return total > 0 ? new OverloadClaimResult(total, limited) : EMPTY;
    }

    private static long addSaturated(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
