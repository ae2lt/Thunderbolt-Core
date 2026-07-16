package com.moakiee.thunderbolt.ae2.batch;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.service.CraftingService;

import com.moakiee.thunderbolt.ae2.api.crafting.IBatchCraftingProvider;
import com.moakiee.thunderbolt.ae2.api.crafting.BatchDispatchMode;
import com.moakiee.thunderbolt.ae2.api.crafting.CraftingPatternDelegates;
import com.moakiee.thunderbolt.ae2.crafting.ExecuteLoopPattern;

public final class BatchExecutor {
    private BatchExecutor() {
    }

    /**
     * Patterns matching this rule are skipped by the batch dispatcher (they are handled by another
     * path). Decoupled from content: the host mod installs the rule during setup (e.g. to exclude
     * its overload-only pattern type). Defaults to "skip nothing" so the lib works standalone.
     */
    private static volatile Predicate<IPatternDetails> skipRule = details -> false;
    private static volatile Predicate<IPatternDetails> batchEligibleRule = details -> true;

    /** Installs the pattern skip rule. Call once from the host mod's setup. */
    public static void setSkipRule(Predicate<IPatternDetails> rule) {
        skipRule = rule != null ? rule : details -> false;
    }

    /**
     * Installs the pattern eligibility rule for batch providers. Defaults to "try every pattern"
     * so standalone library users keep the original behavior.
     */
    public static void setBatchEligibleRule(Predicate<IPatternDetails> rule) {
        batchEligibleRule = rule != null ? rule : details -> true;
    }

    public static BatchRunResult runBatchOnly(int remainingOps,
                                              BatchCpuAccounting.Mode accountingMode,
                                              CraftingService cs,
                                              IEnergyService es,
                                              Level level,
                                              BatchJobView job,
                                              ListCraftingInventory inv,
                                              Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> batchedByTask,
                                              Runnable markDirty) {
        return runBatchOnly(remainingOps, accountingMode, cs, es, level, job, inv,
                batchedByTask, markDirty, Map.of());
    }

    public static BatchRunResult runBatchOnly(int remainingOps,
                                              BatchCpuAccounting.Mode accountingMode,
                                              CraftingService cs,
                                              IEnergyService es,
                                              Level level,
                                              BatchJobView job,
                                              ListCraftingInventory inv,
                                              Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> batchedByTask,
                                              Runnable markDirty,
                                              Map<appeng.api.stacks.AEKey, Long> reservedStock) {
        if (job == null) return BatchRunResult.EMPTY;

        var taskIter = job.taskIterator();
        if (!taskIter.hasNext()) return BatchRunResult.EMPTY;

        long totalPushed = 0;
        int consumedOps = 0;
        int opsBudget = remainingOps;
        if (opsBudget <= 0) return BatchRunResult.EMPTY;
        if (accountingMode == null) accountingMode = BatchCpuAccounting.Mode.LINEAR;
        boolean dirty = false;
        boolean sawBatchProvider = false;

        while (taskIter.hasNext()) {
            var task = taskIter.next();
            long taskValue = task.getValue();
            if (taskValue <= 0) {
                taskIter.remove();
                continue;
            }

            var details = task.details();
            var executionDetails = details instanceof ExecuteLoopPattern loop
                    ? loop.delegate() : details;
            if (skipRule.test(executionDetails)) {
                continue;
            }
            if (!batchEligibleRule.test(executionDetails)) {
                continue;
            }

            var providerPattern = CraftingPatternDelegates.forProviderLookup(details);
            var perTaskBatched = batchedByTask.get(details);
            java.util.ArrayList<EligibleProvider> eligible = null;
            for (var provider : cs.getProviders(providerPattern)) {
                if (!(provider instanceof IBatchCraftingProvider batch)) continue;
                sawBatchProvider = true;
                if (executionDetails instanceof SharedBatchInputPattern
                        && !batch.supportsSingleSeedBatch()) continue;
                if (perTaskBatched != null && perTaskBatched.containsKey(provider)) continue;
                long capacity = batch.getBatchCapacity(executionDetails);
                if (capacity <= 0) continue;
                var dispatchMode = batch.getBatchDispatchMode(executionDetails);
                if (dispatchMode == null) {
                    dispatchMode = BatchDispatchMode.NORMAL;
                }
                if (eligible == null) {
                    eligible = new java.util.ArrayList<>();
                }
                eligible.add(new EligibleProvider(batch, provider, capacity, dispatchMode));
            }
            if (eligible == null) continue;

            if (executionDetails instanceof SharedBatchInputPattern && eligible.size() > 1) {
                // One reusable seed cannot be in multiple executing providers simultaneously.
                eligible.sort(java.util.Comparator
                        .comparing((EligibleProvider provider) -> provider.mode() != BatchDispatchMode.UNBOUNDED)
                        .thenComparing(EligibleProvider::capacity, java.util.Comparator.reverseOrder()));
                eligible.subList(1, eligible.size()).clear();
            }

            boolean hasUnboundedProvider = eligible.stream()
                    .anyMatch(provider -> provider.mode() == BatchDispatchMode.UNBOUNDED);
            long availableBatchCapacity = 0;
            for (var provider : eligible) {
                availableBatchCapacity = saturatingAdd(availableBatchCapacity, provider.capacity());
            }
            if (availableBatchCapacity <= 0) continue;

            // Unbounded providers must see the whole task before normal providers can consume the
            // CPU copy budget. Normal providers retain the smaller-first balancing order.
            eligible.sort(java.util.Comparator
                    .comparing((EligibleProvider provider) -> provider.mode() != BatchDispatchMode.UNBOUNDED)
                    .thenComparingLong(EligibleProvider::capacity));

            long copyBudget = hasUnboundedProvider
                    ? Long.MAX_VALUE
                    : BatchCpuAccounting.maxCopiesForCpuOps(opsBudget, accountingMode);
            if (copyBudget <= 0) {
                if (dirty) markDirty.run();
                return new BatchRunResult(totalPushed, consumedOps, sawBatchProvider);
            }

            long budget = Math.min(Math.min(taskValue, availableBatchCapacity), copyBudget);
            if (details instanceof BatchCopyLimitPattern limited) {
                budget = Math.min(budget, Math.max(1L, limited.maxBatchCopies()));
            }
            if (budget <= 0) continue;

            var result = ParallelBatchCpuHelper.bulkExtract(
                    details, inv, budget, true, reservedStock);
            if (result == null) {
                continue;
            }

            long realCraft = result.actualCopies;
            double powerForReal = CraftingCpuHelper.calculatePatternPower(result.scaledInputs);
            double powerOne = realCraft > 0 ? powerForReal / realCraft : 0.0D;
            double availablePower = es.extractAEPower(powerForReal, Actionable.SIMULATE, PowerMultiplier.CONFIG);
            if (availablePower < powerForReal - 0.01D) {
                long affordable = powerOne > 0.0D ? floorToLong(availablePower / powerOne) : 0L;
                if (affordable <= 0) {
                    ParallelBatchCpuHelper.reinject(result, realCraft, inv);
                    if (dirty) markDirty.run();
                    return new BatchRunResult(totalPushed, consumedOps, sawBatchProvider);
                }
                long scaleDown = realCraft - affordable;
                if (scaleDown > 0) {
                    ParallelBatchCpuHelper.reinject(result, scaleDown, inv);
                    realCraft = affordable;
                }
            }

            long initialRealCraft = realCraft;
            long leftover = realCraft;
            KeyCounter[] oneCopy = ParallelBatchCpuHelper.cloneSingleCopy(result);

            for (int i = 0; i < eligible.size() && leftover > 0; i++) {
                var eligibleProvider = eligible.get(i);
                var batch = eligibleProvider.provider();
                boolean unbounded = eligibleProvider.mode() == BatchDispatchMode.UNBOUNDED;
                long sliceCap = unbounded
                        ? Long.MAX_VALUE
                        : BatchCpuAccounting.maxCopiesForCpuOps(opsBudget, accountingMode);
                if (sliceCap <= 0) break;
                long slice;
                if (unbounded) {
                    slice = leftover;
                } else {
                    int remainingProviders = eligible.size() - i;
                    slice = Math.max(1L, leftover / remainingProviders);
                }
                slice = Math.min(slice, leftover);
                slice = Math.min(slice, sliceCap);
                slice = Math.min(slice, eligibleProvider.capacity());

                long subLeftover;
                try {
                    subLeftover = batch.pushBatch(executionDetails, oneCopy, slice);
                } catch (Throwable t) {
                    appeng.core.AELog.warn("[ae2lt] IBatchCraftingProvider %s threw during pushBatch; treating as full leftover. %s",
                            batch, t);
                    subLeftover = slice;
                }
                if (subLeftover < 0 || subLeftover > slice) {
                    appeng.core.AELog.warn("[ae2lt] IBatchCraftingProvider %s returned out-of-range leftover %d for slice=%d; treating as full leftover.",
                            batch, subLeftover, slice);
                    subLeftover = slice;
                }

                long dispatched = slice - subLeftover;
                if (dispatched <= 0) continue;

                ParallelBatchCpuHelper.markDispatched(result, dispatched);
                es.extractAEPower(powerOne * dispatched, Actionable.MODULATE, PowerMultiplier.CONFIG);
                ParallelBatchCpuHelper.registerExpectedOutputs(job, details, result, dispatched);
                dirty = true;

                int opsCost = unbounded
                        ? 1
                        : BatchCpuAccounting.cpuOpsForCopies(dispatched, accountingMode);
                consumedOps += opsCost;
                opsBudget -= opsCost;

                long newValue = task.getValue() - dispatched;
                task.setValue(newValue);
                totalPushed = saturatingAdd(totalPushed, dispatched);
                leftover -= dispatched;

                if (initialRealCraft > 1) {
                    if (perTaskBatched == null) {
                        perTaskBatched = batchedByTask.computeIfAbsent(details, key -> new IdentityHashMap<>());
                    }
                    perTaskBatched.put(eligibleProvider.identity(), Boolean.TRUE);
                }

                if (newValue <= 0) {
                    taskIter.remove();
                    if (leftover > 0) {
                        ParallelBatchCpuHelper.reinject(result, leftover, inv);
                        leftover = 0;
                    }
                    if (opsBudget <= 0) {
                        if (dirty) markDirty.run();
                        return new BatchRunResult(totalPushed, consumedOps, sawBatchProvider);
                    }
                    break;
                }

                if (opsBudget <= 0) {
                    if (leftover > 0) {
                        ParallelBatchCpuHelper.reinject(result, leftover, inv);
                        leftover = 0;
                    }
                    if (dirty) markDirty.run();
                    return new BatchRunResult(totalPushed, consumedOps, sawBatchProvider);
                }
            }

            if (leftover > 0) {
                ParallelBatchCpuHelper.reinject(result, leftover, inv);
            }
        }

        if (dirty) markDirty.run();
        return new BatchRunResult(totalPushed, consumedOps, sawBatchProvider);
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long floorToLong(double value) {
        if (!Double.isFinite(value) || value >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return value <= 0.0D ? 0L : (long) Math.floor(value);
    }

    private record EligibleProvider(IBatchCraftingProvider provider, ICraftingProvider identity,
                                    long capacity, BatchDispatchMode mode) {
    }

    public record BatchRunResult(long dispatchedCopies, int consumedCpuOps, boolean sawBatchProvider) {
        public static final BatchRunResult EMPTY = new BatchRunResult(0L, 0, false);

        public boolean shouldRetryBatchThisTick() {
            return dispatchedCopies > 0;
        }
    }
}
