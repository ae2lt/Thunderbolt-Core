package com.moakiee.thunderbolt.ae2.batch;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ListCraftingInventory;
import com.moakiee.thunderbolt.ae2.crafting.ExecuteLoopPattern;

public final class ParallelBatchCpuHelper {
    private ParallelBatchCpuHelper() {
    }

    @Nullable
    public static BulkResult bulkExtract(IPatternDetails details, ListCraftingInventory inv, long maxCraft) {
        return bulkExtract(details, inv, maxCraft, true, Map.of());
    }

    @Nullable
    public static BulkResult bulkExtract(IPatternDetails details, ListCraftingInventory inv, long maxCraft,
                                         boolean allowSharedInputs, Map<AEKey, Long> reservedStock) {
        if (maxCraft <= 0) return null;

        var inputs = details.getInputs();
        int slots = inputs.length;
        var chosenKeys = new AEKey[slots];
        var units = new long[slots];
        var available = new long[slots];
        var shared = new boolean[slots];
        var executionDetails = details instanceof ExecuteLoopPattern loop
                ? loop.delegate() : details;
        var sharedPattern = allowSharedInputs
                && executionDetails instanceof SharedBatchInputPattern pattern ? pattern : null;
        var reserved = reservedStock != null ? reservedStock : Map.<AEKey, Long>of();

        for (int slot = 0; slot < slots; slot++) {
            var input = inputs[slot];
            var possibles = input.getPossibleInputs();
            AEKey bestKey = null;
            long bestUnits = 0;
            long bestAvailable = 0;
            long bestCopies = 0;
            boolean bestShared = false;
            for (var possible : possibles) {
                if (possible.what() == null) continue;
                long perCopy = saturatingMultiply(possible.amount(), input.getMultiplier());
                if (perCopy <= 0) continue;
                long inInventory = Math.max(0L,
                        inv.extract(possible.what(), Long.MAX_VALUE, Actionable.SIMULATE)
                                - Math.max(0L, reserved.getOrDefault(possible.what(), 0L)));
                boolean isShared = sharedPattern != null
                        && sharedPattern.isSharedBatchInput(slot, possible.what());
                long copies = isShared ? (inInventory >= perCopy ? maxCraft : 0L) : inInventory / perCopy;
                if (copies > bestCopies) {
                    bestKey = possible.what();
                    bestUnits = perCopy;
                    bestAvailable = inInventory;
                    bestCopies = copies;
                    bestShared = isShared;
                    if (copies >= maxCraft) break;
                }
            }
            if (bestKey == null || bestCopies <= 0) return null;
            chosenKeys[slot] = bestKey;
            units[slot] = bestUnits;
            available[slot] = bestAvailable;
            shared[slot] = bestShared;
        }

        var fixedByKey = new HashMap<AEKey, Long>(slots * 2);
        var variableByKey = new HashMap<AEKey, Long>(slots * 2);
        var availableByKey = new HashMap<AEKey, Long>(slots * 2);
        for (int slot = 0; slot < slots; slot++) {
            availableByKey.put(chosenKeys[slot], available[slot]);
            (shared[slot] ? fixedByKey : variableByKey)
                    .merge(chosenKeys[slot], units[slot], ParallelBatchCpuHelper::saturatingAdd);
        }

        long actual = maxCraft;
        for (var entry : availableByKey.entrySet()) {
            long fixed = fixedByKey.getOrDefault(entry.getKey(), 0L);
            long variable = variableByKey.getOrDefault(entry.getKey(), 0L);
            if (fixed > entry.getValue()) return null;
            if (variable > 0) actual = Math.min(actual, (entry.getValue() - fixed) / variable);
            if (actual <= 0) return null;
        }
        var extractedByKey = new HashMap<AEKey, Long>(availableByKey.size() * 2);
        for (var entry : availableByKey.entrySet()) {
            long need = saturatingAdd(
                    fixedByKey.getOrDefault(entry.getKey(), 0L),
                    saturatingMultiply(variableByKey.getOrDefault(entry.getKey(), 0L), actual));
            long got = inv.extract(entry.getKey(), need, Actionable.MODULATE);
            extractedByKey.put(entry.getKey(), got);
            if (got < need) {
                for (var rollback : extractedByKey.entrySet()) {
                    if (rollback.getValue() > 0) {
                        inv.insert(rollback.getKey(), rollback.getValue(), Actionable.MODULATE);
                    }
                }
                return null;
            }
        }

        var scaled = new KeyCounter[slots];
        for (int slot = 0; slot < slots; slot++) {
            scaled[slot] = new KeyCounter();
            long amount = shared[slot] ? units[slot] : saturatingMultiply(units[slot], actual);
            if (amount > 0) scaled[slot].add(chosenKeys[slot], amount);
        }
        return new BulkResult(scaled, actual, chosenKeys, units, shared);
    }

    public static void reinject(BulkResult result, long leftoverCopies, ListCraftingInventory inv) {
        if (leftoverCopies <= 0) return;
        long returnedCopies = Math.min(leftoverCopies, result.remainingCopies);
        for (int slot = 0; slot < result.scaledInputs.length; slot++) {
            if (result.sharedInputs[slot]) continue;
            long amount = saturatingMultiply(result.units[slot], returnedCopies);
            if (amount > 0 && result.keys[slot] != null) {
                inv.insert(result.keys[slot], amount, Actionable.MODULATE);
                result.scaledInputs[slot].remove(result.keys[slot], amount);
            }
        }
        result.remainingCopies -= returnedCopies;
        if (result.remainingCopies == 0 && !result.sharedDispatched) result.reinjectShared(inv);
    }

    public static void registerExpectedOutputs(BatchJobView job, IPatternDetails details,
                                               BulkResult result, long dispatched) {
        registerExpectedOutputs(job, details, result.keys, result.sharedInputs, dispatched);
    }

    public static void registerExpectedOutputs(BatchJobView job, IPatternDetails details,
                                               AEKey[] chosenKeys, long dispatched) {
        registerExpectedOutputs(job, details, chosenKeys, null, dispatched);
    }

    private static void registerExpectedOutputs(BatchJobView job, IPatternDetails details,
                                                AEKey[] chosenKeys, boolean[] shared, long dispatched) {
        if (dispatched <= 0) return;
        var executionDetails = details instanceof ExecuteLoopPattern loop
                ? loop.delegate() : details;
        var sharedPattern = executionDetails instanceof SharedBatchInputPattern pattern
                ? pattern : null;
        var sharedOutputsLeft = new HashMap<AEKey, Long>();
        for (var output : details.getOutputs()) {
            long sharedAmount = 0L;
            if (sharedPattern != null) {
                long remainingShared = sharedOutputsLeft.computeIfAbsent(
                        output.what(), sharedPattern::sharedBatchOutputAmount);
                sharedAmount = Math.min(output.amount(), Math.max(0L, remainingShared));
                sharedOutputsLeft.put(output.what(), remainingShared - sharedAmount);
            }
            long scalable = Math.max(0L, output.amount() - sharedAmount);
            job.insertWaitingFor(output.what(), saturatingAdd(
                    sharedAmount, saturatingMultiply(scalable, dispatched)));
        }
        var inputs = details.getInputs();
        for (int slot = 0; slot < inputs.length; slot++) {
            var input = inputs[slot];
            var possibles = input.getPossibleInputs();
            if (possibles.length == 0) continue;
            AEKey consumed = chosenKeys != null && slot < chosenKeys.length && chosenKeys[slot] != null
                    ? chosenKeys[slot] : possibles[0].what();
            AEKey remaining = input.getRemainingKey(consumed);
            if (remaining != null) {
                long copies = shared != null && slot < shared.length && shared[slot] ? 1L : dispatched;
                // CraftingCpuHelper registers one remainder per completed template operation;
                // the possible stack's physical amount only affects extraction, not return count.
                long perCopy = input.getMultiplier();
                long count = saturatingMultiply(perCopy, copies);
                job.insertWaitingFor(remaining, count);
                job.addContainerMaxItems(count, remaining.getType());
            }
        }
    }

    public static KeyCounter[] cloneSingleCopy(BulkResult result) {
        return copySlice(result, 1);
    }

    public static KeyCounter[] copySlice(BulkResult result, long sliceCount) {
        var slice = new KeyCounter[result.scaledInputs.length];
        for (int slot = 0; slot < slice.length; slot++) {
            slice[slot] = new KeyCounter();
            long amount = result.sharedInputs[slot]
                    ? result.units[slot]
                    : saturatingMultiply(Math.max(0, sliceCount), result.units[slot]);
            if (amount > 0 && result.keys[slot] != null) slice[slot].add(result.keys[slot], amount);
        }
        return slice;
    }

    public static void markDispatched(BulkResult result, long dispatchedCopies) {
        if (dispatchedCopies <= 0) return;
        long accepted = Math.min(dispatchedCopies, result.remainingCopies);
        for (int slot = 0; slot < result.scaledInputs.length; slot++) {
            long amount;
            if (result.sharedInputs[slot]) {
                amount = result.sharedDispatched ? 0L : result.units[slot];
            } else {
                amount = saturatingMultiply(result.units[slot], accepted);
            }
            if (amount > 0 && result.keys[slot] != null) {
                result.scaledInputs[slot].remove(result.keys[slot], amount);
            }
        }
        result.sharedDispatched = true;
        result.remainingCopies -= accepted;
    }

    public static final class BulkResult {
        public final KeyCounter[] scaledInputs;
        public final long actualCopies;
        final AEKey[] keys;
        final long[] units;
        final boolean[] sharedInputs;
        long remainingCopies;
        boolean sharedDispatched;

        public BulkResult(KeyCounter[] scaledInputs, long actualCopies, AEKey[] keys,
                          long[] units, boolean[] sharedInputs) {
            this.scaledInputs = scaledInputs;
            this.actualCopies = actualCopies;
            this.keys = Arrays.copyOf(keys, keys.length);
            this.units = Arrays.copyOf(units, units.length);
            this.sharedInputs = Arrays.copyOf(sharedInputs, sharedInputs.length);
            this.remainingCopies = actualCopies;
        }

        private void reinjectShared(ListCraftingInventory inv) {
            for (int slot = 0; slot < scaledInputs.length; slot++) {
                if (!sharedInputs[slot] || keys[slot] == null) continue;
                inv.insert(keys[slot], units[slot], Actionable.MODULATE);
                scaledInputs[slot].remove(keys[slot], units[slot]);
            }
        }
    }

    private static long saturatingAdd(long left, long right) {
        return left >= Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long saturatingMultiply(long left, long right) {
        if (left <= 0 || right <= 0) return 0L;
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }
}
