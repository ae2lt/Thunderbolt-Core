package com.moakiee.thunderbolt.ae2.timewheel;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.moakiee.thunderbolt.core.planner.Sat;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

/**
 * Signed reusable-seed ledgers for one TimeWheel job.
 *
 * <p>Every single-seed flow shares one pool. A flow with any member that consumes multiple seed
 * key types receives a pool dedicated to its macro-pattern id. {@link #totalReserved} is the
 * incrementally maintained sum of every pool's positive balance, making a reservation lookup
 * independent of the number of dedicated pools.</p>
 */
final class LoopSeedLedgerBook {
    private static final String TAG_LEDGERS = "loopSeedLedger";
    private static final String TAG_OUTPUT_CLAIMS = "loopSeedOutputClaims";
    private static final String TAG_POOL_SHARED = "shared";
    private static final String TAG_POOL_ID = "poolId";
    private static final String TAG_POOL_ENTRIES = "entries";
    private static final String TAG_NEGATIVE = "negative";

    static final PoolId SHARED_POOL = new PoolId(null);

    private final Map<PoolId, Map<AEKey, Long>> ledgers = new HashMap<>();
    private final KeyCounter totalReserved = new KeyCounter();

    void initialize(
            Map<UUID, Map<AEKey, Long>> groups,
            Set<UUID> dedicatedGroups) {
        clear();
        if (groups == null || groups.isEmpty()) return;
        var dedicated = dedicatedGroups != null ? dedicatedGroups : Set.<UUID>of();
        for (var group : groups.entrySet()) {
            if (group.getKey() == null || group.getValue() == null) continue;
            var pool = dedicated.contains(group.getKey())
                    ? PoolId.dedicated(group.getKey()) : SHARED_POOL;
            for (var seed : group.getValue().entrySet()) {
                if (seed.getKey() != null && seed.getValue() != null && seed.getValue() > 0) {
                    adjust(pool, seed.getKey(), seed.getValue());
                }
            }
        }
    }

    static PoolId poolFor(UUID groupId, boolean singleSeedInputPerMember) {
        return singleSeedInputPerMember
                ? SHARED_POOL : PoolId.dedicated(Objects.requireNonNull(groupId, "groupId"));
    }

    /**
     * Lazy reservation map. Consumers perform an O(1) lookup per extracted key. For a declared
     * seed input, only this task's own positive pool balance is subtracted from the aggregate;
     * every other pool remains protected.
     */
    Map<AEKey, Long> reservationView(
            @Nullable PoolId ownPool,
            Predicate<AEKey> allowedOwnSeedInput) {
        Predicate<AEKey> allowed = allowedOwnSeedInput != null
                ? allowedOwnSeedInput : ignored -> false;
        return new AbstractMap<>() {
            @Override
            public Long get(Object key) {
                if (!(key instanceof AEKey aeKey)) return null;
                long reserved = totalReserved.get(aeKey);
                if (reserved <= 0) return null;
                reserved = ReusableSeedReservation.reservedForTask(
                        reserved,
                        ownPool != null ? balance(ownPool, aeKey) : 0L,
                        ownPool != null && allowed.test(aeKey));
                return reserved > 0 ? reserved : null;
            }

            @Override
            public boolean containsKey(Object key) {
                return get(key) != null;
            }

            @Override
            public boolean isEmpty() {
                if (totalReserved.isEmpty()) return true;
                for (var entry : totalReserved) {
                    if (get(entry.getKey()) != null) return false;
                }
                return true;
            }

            @Override
            public Set<Entry<AEKey, Long>> entrySet() {
                var entries = new LinkedHashSet<Entry<AEKey, Long>>();
                for (var entry : totalReserved) {
                    var value = get(entry.getKey());
                    if (value != null) {
                        entries.add(Map.entry(entry.getKey(), value));
                    }
                }
                return Set.copyOf(entries);
            }
        };
    }

    boolean hasReservations() {
        return !totalReserved.isEmpty();
    }

    void recordDispatch(
            PoolId pool,
            KeyCounter inputSeed,
            KeyCounter outputSeed,
            long scale) {
        recordDispatch(pool, inputSeed, scale, outputSeed, scale);
    }

    void recordDispatch(
            PoolId pool,
            KeyCounter inputSeed,
            long inputScale,
            KeyCounter outputSeed,
            long outputScale) {
        if (pool == null || inputScale <= 0 || outputScale <= 0) return;
        var changes = new HashMap<AEKey, Long>();
        if (inputSeed != null) {
            for (var entry : inputSeed) {
                long amount = Sat.mul(entry.getLongValue(), inputScale);
                if (amount > 0) changes.merge(
                        entry.getKey(), -amount, LoopSeedLedgerBook::saturatingSignedAdd);
            }
        }
        if (outputSeed != null) {
            for (var entry : outputSeed) {
                long amount = Sat.mul(entry.getLongValue(), outputScale);
                if (amount > 0) changes.merge(
                        entry.getKey(), amount, LoopSeedLedgerBook::saturatingSignedAdd);
            }
        }
        for (var change : changes.entrySet()) {
            adjust(pool, change.getKey(), change.getValue());
        }
    }

    void rekey(PoolId pool, AEKey expected, AEKey actual, long amount) {
        if (pool == null || expected == null || actual == null || amount <= 0
                || expected.equals(actual)) return;
        adjust(pool, expected, -amount);
        adjust(pool, actual, amount);
    }

    void clear() {
        ledgers.clear();
        totalReserved.clear();
    }

    void readFromNBT(CompoundTag data, HolderLookup.Provider registries) {
        clear();
        var ledgerTags = data.getList(TAG_LEDGERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < ledgerTags.size(); i++) {
            var poolTag = ledgerTags.getCompound(i);
            if (poolTag.contains(TAG_POOL_ENTRIES, Tag.TAG_LIST)) {
                var pool = readPool(poolTag, false);
                if (pool == null) continue;
                var entries = poolTag.getList(TAG_POOL_ENTRIES, Tag.TAG_COMPOUND);
                for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
                    readLedgerEntry(pool, entries.getCompound(entryIndex), registries);
                }
            } else {
                // Compatibility with the short-lived flat global-ledger format.
                readLedgerEntry(SHARED_POOL, poolTag, registries);
            }
        }

        var claimTags = data.getList(TAG_OUTPUT_CLAIMS, Tag.TAG_COMPOUND);
        for (int i = 0; i < claimTags.size(); i++) {
            var claimTag = claimTags.getCompound(i);
            var stack = GenericStack.readTag(registries, claimTag);
            if (stack == null || stack.amount() <= 0) continue;
            var pool = readPool(claimTag, true);
            // Migration from the return-time credit format: expected outputs were already in
            // flight, so the new dispatch-time ledger must include their credit immediately.
            if (pool != null) adjust(pool, stack.what(), stack.amount());
        }
    }

    void writeToNBT(CompoundTag data, HolderLookup.Provider registries) {
        if (ledgers.isEmpty()) {
            data.remove(TAG_LEDGERS);
        } else {
            var ledgerTags = new ListTag();
            var pools = new ArrayList<>(ledgers.keySet());
            pools.sort((left, right) -> {
                if (left.isShared() != right.isShared()) return left.isShared() ? -1 : 1;
                if (left.isShared()) return 0;
                return left.groupId().compareTo(right.groupId());
            });
            for (var pool : pools) {
                var entries = ledgers.get(pool);
                if (entries == null || entries.isEmpty()) continue;
                var poolTag = new CompoundTag();
                writePool(poolTag, pool);
                var entryTags = new ListTag();
                for (var entry : entries.entrySet()) {
                    if (entry.getValue() == 0) continue;
                    long magnitude = entry.getValue() == Long.MIN_VALUE
                            ? Long.MAX_VALUE : Math.abs(entry.getValue());
                    var entryTag = GenericStack.writeTag(
                            registries, new GenericStack(entry.getKey(), magnitude));
                    if (entry.getValue() < 0) entryTag.putBoolean(TAG_NEGATIVE, true);
                    entryTags.add(entryTag);
                }
                if (!entryTags.isEmpty()) {
                    poolTag.put(TAG_POOL_ENTRIES, entryTags);
                    ledgerTags.add(poolTag);
                }
            }
            if (ledgerTags.isEmpty()) data.remove(TAG_LEDGERS);
            else data.put(TAG_LEDGERS, ledgerTags);
        }

        data.remove(TAG_OUTPUT_CLAIMS);
    }

    long balance(PoolId pool, AEKey key) {
        var ledger = ledgers.get(pool);
        return ledger != null ? ledger.getOrDefault(key, 0L) : 0L;
    }

    long totalReserved(AEKey key) {
        return totalReserved.get(key);
    }

    Map<AEKey, Long> positiveSnapshot() {
        var result = new HashMap<AEKey, Long>();
        for (var entry : totalReserved) {
            if (entry.getLongValue() > 0) {
                result.put(entry.getKey(), entry.getLongValue());
            }
        }
        return Map.copyOf(result);
    }

    int ledgerCount() {
        return ledgers.size();
    }

    private void readLedgerEntry(
            PoolId pool,
            CompoundTag entryTag,
            HolderLookup.Provider registries) {
        var stack = GenericStack.readTag(registries, entryTag);
        if (stack == null || stack.amount() <= 0) return;
        adjust(pool, stack.what(), entryTag.getBoolean(TAG_NEGATIVE)
                ? -stack.amount() : stack.amount());
    }

    private void adjust(PoolId pool, AEKey key, long delta) {
        if (pool == null || key == null || delta == 0) return;
        var ledger = ledgers.computeIfAbsent(pool, ignored -> new HashMap<>());
        long oldValue = ledger.getOrDefault(key, 0L);
        long newValue = saturatingSignedAdd(oldValue, delta);
        if (newValue == 0) ledger.remove(key);
        else ledger.put(key, newValue);
        if (ledger.isEmpty()) ledgers.remove(pool);

        long oldPositive = Math.max(0L, oldValue);
        long newPositive = Math.max(0L, newValue);
        if (newPositive > oldPositive) {
            totalReserved.add(key, newPositive - oldPositive);
        } else if (oldPositive > newPositive) {
            totalReserved.remove(key, oldPositive - newPositive);
        }
    }

    private static void writePool(CompoundTag tag, PoolId pool) {
        if (pool.isShared()) tag.putBoolean(TAG_POOL_SHARED, true);
        else tag.putUUID(TAG_POOL_ID, pool.groupId());
    }

    private static @Nullable PoolId readPool(CompoundTag tag, boolean legacyShared) {
        if (tag.getBoolean(TAG_POOL_SHARED)) return SHARED_POOL;
        if (tag.hasUUID(TAG_POOL_ID)) return PoolId.dedicated(tag.getUUID(TAG_POOL_ID));
        return legacyShared ? SHARED_POOL : null;
    }

    private static long saturatingSignedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0 && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }

    record PoolId(@Nullable UUID groupId) {
        static PoolId dedicated(UUID groupId) {
            return new PoolId(Objects.requireNonNull(groupId, "groupId"));
        }

        boolean isShared() {
            return groupId == null;
        }
    }
}
