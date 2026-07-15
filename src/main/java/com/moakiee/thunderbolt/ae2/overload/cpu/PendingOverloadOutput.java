package com.moakiee.thunderbolt.ae2.overload.cpu;

import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceLocation;

import appeng.api.stacks.AEKey;

/**
 * One overload-only pending output tracked beside AE2's native waiting list.
 * <p>
 * This only exists for outputs whose match mode is ID_ONLY. STRICT outputs stay
 * entirely inside AE2's normal waitingFor handling.
 */
public final class PendingOverloadOutput {
    private final PendingOverloadOutputKey key;
    private final OverloadCpuOwner owner;
    private final OverloadPatternReference patternReference;
    private final ResourceLocation itemId;
    private final AEKey exactExpectedKey;
    private final boolean routesToRequester;
    private final long registeredOrder;
    @Nullable
    private UUID reusableSeedGroupId;
    private boolean sharedReusableSeedPool;
    private long remainingAmount;
    private long remainingReusableSeedAmount;

    public PendingOverloadOutput(
            PendingOverloadOutputKey key,
            OverloadCpuOwner owner,
            OverloadPatternReference patternReference,
            ResourceLocation itemId,
            AEKey exactExpectedKey,
            long remainingAmount,
            boolean routesToRequester,
            long registeredOrder
    ) {
        this(key, owner, patternReference, itemId, exactExpectedKey, remainingAmount,
                routesToRequester, registeredOrder, null, false, 0L);
    }

    public PendingOverloadOutput(
            PendingOverloadOutputKey key,
            OverloadCpuOwner owner,
            OverloadPatternReference patternReference,
            ResourceLocation itemId,
            AEKey exactExpectedKey,
            long remainingAmount,
            boolean routesToRequester,
            long registeredOrder,
            @Nullable UUID reusableSeedGroupId,
            boolean sharedReusableSeedPool,
            long remainingReusableSeedAmount
    ) {
        this.key = Objects.requireNonNull(key, "key");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.patternReference = Objects.requireNonNull(patternReference, "patternReference");
        this.itemId = Objects.requireNonNull(itemId, "itemId");
        this.exactExpectedKey = Objects.requireNonNull(exactExpectedKey, "exactExpectedKey");
        if (remainingAmount <= 0) {
            throw new IllegalArgumentException("remainingAmount must be > 0");
        }
        this.remainingAmount = remainingAmount;
        this.routesToRequester = routesToRequester;
        this.registeredOrder = registeredOrder;
        if (remainingReusableSeedAmount < 0 || remainingReusableSeedAmount > remainingAmount) {
            throw new IllegalArgumentException("reusable seed amount is outside pending output");
        }
        if (remainingReusableSeedAmount > 0) {
            this.reusableSeedGroupId = Objects.requireNonNull(
                    reusableSeedGroupId, "reusableSeedGroupId");
        } else {
            this.reusableSeedGroupId = reusableSeedGroupId;
        }
        this.sharedReusableSeedPool = sharedReusableSeedPool;
        this.remainingReusableSeedAmount = remainingReusableSeedAmount;
    }

    public PendingOverloadOutputKey key() {
        return key;
    }

    public OverloadCpuOwner owner() {
        return owner;
    }

    public OverloadPatternReference patternReference() {
        return patternReference;
    }

    public ResourceLocation itemId() {
        return itemId;
    }

    public AEKey exactExpectedKey() {
        return exactExpectedKey;
    }

    public long remainingAmount() {
        return remainingAmount;
    }

    public boolean routesToRequester() {
        return routesToRequester;
    }

    public int outputSlotIndex() {
        return key.outputSlotIndex();
    }

    public long registeredOrder() {
        return registeredOrder;
    }

    public long remainingReusableSeedAmount() { return remainingReusableSeedAmount; }
    public @Nullable UUID reusableSeedGroupId() { return reusableSeedGroupId; }
    public boolean sharedReusableSeedPool() { return sharedReusableSeedPool; }

    public void addExpected(long amount) {
        addExpected(amount, null);
    }

    public void addExpected(long amount, @Nullable OverloadReusableSeedMetadata reusableSeed) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        if (reusableSeed != null) {
            if (reusableSeed.amount() > amount) {
                throw new IllegalArgumentException("reusable seed exceeds added output");
            }
            if (reusableSeedGroupId == null) {
                reusableSeedGroupId = reusableSeed.groupId();
                sharedReusableSeedPool = reusableSeed.sharedPool();
            } else if (!reusableSeedGroupId.equals(reusableSeed.groupId())
                    || sharedReusableSeedPool != reusableSeed.sharedPool()) {
                throw new IllegalArgumentException("overload seed output changed ledger owner");
            }
            remainingReusableSeedAmount = addSaturated(
                    remainingReusableSeedAmount, reusableSeed.amount());
        }
        remainingAmount = addSaturated(remainingAmount, amount);
    }

    public long claim(long requestedAmount) {
        if (requestedAmount <= 0) {
            return 0;
        }

        long claimed = Math.min(remainingAmount, requestedAmount);
        remainingAmount -= claimed;
        return claimed;
    }

    public long claimReusableSeed(long claimedOutput, boolean mutate) {
        long claimed = Math.min(Math.max(0L, claimedOutput), remainingReusableSeedAmount);
        if (mutate) remainingReusableSeedAmount -= claimed;
        return claimed;
    }

    public boolean isSatisfied() {
        return remainingAmount <= 0;
    }

    private static long addSaturated(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
