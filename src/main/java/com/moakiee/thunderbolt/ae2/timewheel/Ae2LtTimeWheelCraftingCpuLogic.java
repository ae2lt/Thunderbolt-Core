package com.moakiee.thunderbolt.ae2.timewheel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.google.common.base.Preconditions;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.features.IPlayerRegistry;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.core.AELog;
import appeng.core.network.ClientboundPacket;
import appeng.core.network.clientbound.CraftingJobStatusPacket;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.hooks.ticking.TickHandler;
import appeng.me.service.CraftingService;

import com.moakiee.thunderbolt.ae2.batch.BatchExecutor;
import com.moakiee.thunderbolt.ae2.batch.BatchCpuAccounting;
import com.moakiee.thunderbolt.ae2.batch.BatchJobView;
import com.moakiee.thunderbolt.ae2.batch.BatchTaskHandle;
import com.moakiee.thunderbolt.ae2.batch.ParallelBatchCpuHelper;
import com.moakiee.thunderbolt.ae2.mixin.ElapsedTimeTrackerAccessor;
import com.moakiee.thunderbolt.ae2.overload.cpu.OverloadClaimResult;
import com.moakiee.thunderbolt.ae2.overload.cpu.OverloadCpuStateManager;
import com.moakiee.thunderbolt.ae2.overload.cpu.OverloadPatternReference;
import com.moakiee.thunderbolt.ae2.overload.pattern.OverloadedProviderOnlyPatternDetails;
import com.moakiee.thunderbolt.ae2.crafting.PatternFiringExpander;

public final class Ae2LtTimeWheelCraftingCpuLogic {
    private static final int WHEEL_SIZE = 64;
    private static final int WHEEL_MASK = WHEEL_SIZE - 1;
    private static final int MAX_TASK_PROBES_PER_TICK = 262_144;
    private static final int RETRY_DELAY_TICKS = 4;
    private static final int PARKED_TASK_SAFETY_DELAY_TICKS = 32;
    private static final String TAG_INVENTORY = "inventory";
    private static final String TAG_JOB = "job";
    private static final String TAG_OVERLOAD_STATE = "ae2ltOverloadState";
    private static final String TAG_SEED_RETURN_QUOTA = "reusableSeedReturnQuota";

    private static final String NBT_LINK = "link";
    private static final String NBT_PLAYER_ID = "playerId";
    private static final String NBT_FINAL_OUTPUT = "finalOutput";
    private static final String NBT_WAITING_FOR = "waitingFor";
    private static final String NBT_TIME_TRACKER = "timeTracker";
    private static final String NBT_REMAINING_AMOUNT = "remainingAmount";
    private static final String NBT_TASKS = "tasks";
    private static final String NBT_SUSPENDED = "suspended";
    private static final String NBT_SOFT_CANCELLING = "softCancelling";
    private static final String NBT_CRAFTING_PROGRESS = "#craftingProgress";

    private final TimeWheelCraftingCPU cpu;
    private final ListCraftingInventory inventory = new ListCraftingInventory(this::postChange);
    private final Set<Consumer<AEKey>> listeners = new HashSet<>();
    private final Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> batchedByTask = new HashMap<>();
    private final ArrayDeque<IPatternDetails>[] taskWheel = createWheel();
    private final Set<IPatternDetails> queuedTasks = Collections.newSetFromMap(new IdentityHashMap<>());
    // Parked tasks are indexed by AEKey#getPrimaryKey() (item id, ignoring components/damage), not the
    // exact declared input key: providers like the matter warping matrix return container items as a
    // DIFFERENT exact key (e.g. a durability tool at damage+1), and an exact-key index would never see
    // the return and leave the task to the 32-tick safety poll alone.
    private final TimeWheelTaskWakeIndex<IPatternDetails> tasksParkedByMissingKey = new TimeWheelTaskWakeIndex<>();
    private final Set<AEKey> batchedStatusChanges = new HashSet<>();
    private final List<AEKey> statusChangeScratch = new ArrayList<>();
    private final Set<IPatternDetails> nonBatchTasksThisTick = Collections.newSetFromMap(new IdentityHashMap<>());
    private final KeyCounter scratchExpectedOutputs = new KeyCounter();
    private final KeyCounter scratchExpectedContainerItems = new KeyCounter();
    private final SingleTaskBatchJobView scratchBatchJobView = new SingleTaskBatchJobView();
    // Pattern power depends only on the (fixed) input amounts of a pattern, so it is constant across
    // every copy pushed for a given task. Caching it removes calculatePatternPower from the hot
    // per-copy dispatch loop (it was ~7% of the CPU tick in profiling). Keyed by pattern identity;
    // cleared on every job lifecycle transition below.
    private final Map<IPatternDetails, Double> patternPowerCache = new IdentityHashMap<>();
    private final KeyCounter seedReturnQuota = new KeyCounter();

    @Nullable
    private TimeWheelJob job;
    @Nullable
    private CompoundTag pendingJobTag;
    @Nullable
    private CompoundTag pendingOverloadTag;
    private long lastModifiedOnTick = TickHandler.instance().getCurrentTick();
    private long waitingKeysModifiedOnTick = TickHandler.instance().getCurrentTick();
    private long schedulerTick = Long.MIN_VALUE;
    private long batchTick = Long.MIN_VALUE;
    private int wheelCursor;
    private boolean queueRebuildNeeded = true;
    private boolean cantStoreItems;
    private boolean batchingStatusChanges;

    public Ae2LtTimeWheelCraftingCpuLogic(TimeWheelCraftingCPU cpu) {
        this.cpu = cpu;
    }

    public ICraftingSubmitResult trySubmitJob(IGrid grid, ICraftingPlan plan, IActionSource src,
                                              @Nullable ICraftingRequester requester) {
        resolvePendingLoad();
        if (this.job != null || this.pendingJobTag != null) {
            return CraftingSubmitResult.CPU_BUSY;
        }
        if (!cpu.isActive()) {
            return CraftingSubmitResult.CPU_OFFLINE;
        }
        if (cpu.getAvailableStorage() < plan.bytes()) {
            return CraftingSubmitResult.CPU_TOO_SMALL;
        }

        if (!inventory.list.isEmpty()) {
            AELog.warn("Time wheel crafting CPU inventory is not empty yet a job was submitted.");
        }

        var seedRequirements = collectReusableSeeds(plan);
        var adjustedUsedItems = copyCounter(plan.usedItems());
        var hostSeeds = new KeyCounter();
        for (var entry : seedRequirements) {
            long plannedUsed = adjustedUsedItems.get(entry.getKey());
            long requested = ReusableSeedAllocation.hostRequest(
                    plannedUsed, entry.getLongValue());
            if (requested <= 0) continue;
            long extracted = cpu.getHost().extractReusableSeed(
                    entry.getKey(), requested, Actionable.MODULATE);
            if (extracted <= 0) continue;
            inventory.insert(entry.getKey(), extracted, Actionable.MODULATE);
            long networkRemainder = ReusableSeedAllocation.networkRemainder(plannedUsed, extracted);
            adjustedUsedItems.remove(entry.getKey(), plannedUsed - networkRemainder);
            hostSeeds.add(entry.getKey(), extracted);
        }

        ICraftingPlan extractionPlan = hostSeeds.isEmpty()
                ? plan : new UsedItemsOverridePlan(plan, adjustedUsedItems);
        var missingIngredient = CraftingCpuHelper.tryExtractInitialItems(extractionPlan, grid, inventory, src);
        if (missingIngredient != null) {
            rollbackHostSeeds(hostSeeds);
            return CraftingSubmitResult.missingIngredient(missingIngredient);
        }

        var playerId = src.player()
                .map(p -> p instanceof ServerPlayer serverPlayer ? IPlayerRegistry.getPlayerId(serverPlayer) : null)
                .orElse(null);
        var craftId = UUID.randomUUID();
        var linkCpu = new CraftingLink(CraftingCpuHelper.generateLinkData(craftId, requester == null, false), cpu);
        this.job = new TimeWheelJob(plan, this::postChange, linkCpu, playerId);
        seedReturnQuota.clear();
        seedReturnQuota.addAll(seedRequirements);
        patternPowerCache.clear();
        markWaitingKeysChanged();
        cpu.updateOutput(plan.finalOutput());
        cpu.markDirty();
        rebuildTaskWheel();

        notifyJobOwner(job, CraftingJobStatusPacket.Status.STARTED);

        if (requester != null) {
            var linkReq = new CraftingLink(CraftingCpuHelper.generateLinkData(craftId, false, true), requester);
            var craftingService = (CraftingService) grid.getCraftingService();
            craftingService.addLink(linkCpu);
            craftingService.addLink(linkReq);
            return CraftingSubmitResult.successful(linkReq);
        }

        return CraftingSubmitResult.successful(null);
    }

    public void tickCraftingLogic(IEnergyService energyService, CraftingService craftingService) {
        resolvePendingLoad();
        if (this.pendingJobTag != null) {
            return;
        }
        if (!cpu.isActive()) {
            return;
        }

        long now = TickHandler.instance().getCurrentTick();
        if (now != batchTick) {
            batchTick = now;
            batchedByTask.clear();
            nonBatchTasksThisTick.clear();
        }

        cantStoreItems = false;
        if (this.job == null) {
            storeItems();
            if (!this.inventory.list.isEmpty()) {
                cantStoreItems = true;
            }
            return;
        }

        if (job.link.isCanceled()) {
            if (!job.softCancelling) {
                if (hasReusableSeedPattern(job)) beginSoftCancel(job);
                else cancel();
            }
            return;
        }

        if (job.softCancelling) {
            return;
        }

        if (job.suspended) {
            return;
        }

        var level = cpu.getLevel();
        if (level == null) {
            return;
        }

        int remainingOperations = Math.max(1, cpu.getCoProcessors() + 1);
        executeCrafting(remainingOperations, craftingService, energyService, level);
    }

    public int executeCrafting(int maxOps, CraftingService craftingService, IEnergyService energyService,
                               Level level) {
        var activeJob = this.job;
        if (activeJob == null || maxOps <= 0) {
            return 0;
        }

        prepareScheduler(activeJob);

        int usedOps = 0;
        int probes = 0;
        int probeBudget = (int) Math.min(Math.max(1024L, (long) maxOps * 2L), MAX_TASK_PROBES_PER_TICK);

        beginStatusChangeBatch();
        try {
            while (usedOps < maxOps && probes < probeBudget) {
                var details = pollDueTask(activeJob);
                if (details == null) {
                    break;
                }

                var task = activeJob.tasks.get(details);
                if (task == null || task.value <= 0) {
                    removeTask(activeJob, details);
                    postPatternOutputsChange(details);
                    continue;
                }

                probes++;
                int remainingOps = maxOps - usedOps;
                var batchResult = runBatchForTask(details, remainingOps, craftingService, energyService, level);
                if (batchResult.consumedCpuOps() > 0) {
                    usedOps += batchResult.consumedCpuOps();
                    rescheduleIfStillPending(activeJob, details, 0);
                    continue;
                }

                var bulk = pushBulkForTask(
                        activeJob, task, details, remainingOps, craftingService, energyService);
                if (bulk != null) {
                    usedOps += bulk.dispatched();
                    rescheduleIfStillPending(activeJob, details, bulk.retryDelayTicks());
                    continue;
                }

                var outcome = pushOnePattern(activeJob, task, details, craftingService, energyService, level);
                if (outcome == DispatchOutcome.PUSHED) {
                    usedOps++;
                    unparkTask(details);
                    rescheduleIfStillPending(activeJob, details, 0);
                } else {
                    rescheduleFailedTask(activeJob, details, outcome);
                }
            }
        } finally {
            carryOverDueTasks();
            endStatusChangeBatch();
        }

        return usedOps;
    }

    private BatchExecutor.BatchRunResult runBatchForTask(IPatternDetails details,
                                                         int remainingOps,
                                                         CraftingService craftingService,
                                                         IEnergyService energyService,
                                                         Level level) {
        var activeJob = this.job;
        if (activeJob == null) {
            return BatchExecutor.BatchRunResult.EMPTY;
        }
        if (nonBatchTasksThisTick.contains(details)) {
            return BatchExecutor.BatchRunResult.EMPTY;
        }

        var result = BatchExecutor.runBatchOnly(
                remainingOps,
                BatchCpuAccounting.Mode.QUADRATIC,
                craftingService,
                energyService,
                level,
                scratchBatchJobView.bind(activeJob, details),
                inventory,
                batchedByTask,
                () -> {
                    cpu.markDirty();
                    postPatternOutputsChange(details);
                });
        if (!result.shouldRetryBatchThisTick()) {
            nonBatchTasksThisTick.add(details);
        }
        return result;
    }

    private DispatchOutcome pushOnePattern(TimeWheelJob activeJob,
                                           TaskProgress task,
                                           IPatternDetails details,
                                           CraftingService craftingService,
                                           IEnergyService energyService,
                                           Level level) {
        if (hasAmbiguousOverloadOutput(details)) {
            return DispatchOutcome.RETRY_LATER;
        }

        var expectedOutputs = scratchExpectedOutputs;
        var expectedContainerItems = scratchExpectedContainerItems;
        // Scratch counters are always emptied on exit (both branches below), so they are clean on
        // entry and need no leading clear. This halves the per-copy KeyCounter clearing that showed
        // up as ~7% of the CPU tick in profiling.
        KeyCounter[] craftingContainer = CraftingCpuHelper.extractPatternInputs(
                details, inventory, level, expectedOutputs, expectedContainerItems);
        if (craftingContainer == null) {
            clearScratchCounter(expectedOutputs);
            clearScratchCounter(expectedContainerItems);
            return DispatchOutcome.RETRY_MISSING_INPUT;
        }

        boolean pushed = false;
        try {
            var patternPower = patternPowerFor(details, craftingContainer);
            for (var provider : providersForSinglePush(craftingService, details)) {
                if (provider.isBusy()) {
                    continue;
                }

                if (energyService.extractAEPower(patternPower, Actionable.SIMULATE,
                        PowerMultiplier.CONFIG) < patternPower - 0.01D) {
                    return DispatchOutcome.RETRY_NO_POWER;
                }

                if (!provider.pushPattern(details, craftingContainer)) {
                    continue;
                }

                pushed = true;
                craftingContainer = null;
                energyService.extractAEPower(patternPower, Actionable.MODULATE, PowerMultiplier.CONFIG);
                recordPushedPattern(activeJob, details, expectedOutputs, expectedContainerItems, 1L);

                consumeTaskCopies(activeJob, details, 1L);
                return DispatchOutcome.PUSHED;
            }
                return DispatchOutcome.RETRY_SOON;
        } finally {
            if (!pushed && craftingContainer != null) {
                CraftingCpuHelper.reinjectPatternInputs(inventory, craftingContainer);
            }
            clearScratchCounter(expectedOutputs);
            clearScratchCounter(expectedContainerItems);
        }
    }

    /**
     * Fast path for homogeneous (non-overload) patterns. Instead of paying AE2's full per-copy
     * extraction (template resolution via {@code getValidItemTemplates}, input extraction and
     * pattern-power computation) once per copy, this extracts every copy it intends to push this
     * visit in a single {@link ParallelBatchCpuHelper#bulkExtract} call and then hands the
     * pre-resolved copies to the (non-batch) providers one {@code pushPattern} at a time.
     *
     * @return the visit result, or {@code null} when the pattern needs AE2's one-copy substitution
     *         path (overload patterns, non-homogeneous/fuzzy inputs, or a variant a free provider
     *         rejected before anything was dispatched).
     */
    @Nullable
    private BulkPush pushBulkForTask(TimeWheelJob activeJob,
                                     TaskProgress task,
                                     IPatternDetails details,
                                     int maxCopies,
                                     CraftingService craftingService,
                                     IEnergyService energyService) {
        if (details instanceof OverloadedProviderOnlyPatternDetails) {
            return null;
        }
        if (task.value <= 0 || maxCopies <= 0) {
            return new BulkPush(0, 1);
        }
        // Skip the (SIMULATE + MODULATE) extraction entirely if nothing can accept a push right
        // now. Busy providers typically free up within a tick, so retry on the next one (same
        // cadence as the vanilla path's RETRY_SOON, NOT the 4-tick energy/missing-input backoff).
        var providers = providersForSinglePush(craftingService, details).iterator();
        var firstProvider = nextFreeProvider(providers);
        if (firstProvider == null) {
            return new BulkPush(0, 1);
        }

        int budget = (int) Math.min(task.value, (long) maxCopies);
        var result = ParallelBatchCpuHelper.bulkExtract(details, inventory, budget);
        if (result == null) {
            return null;
        }

        int actual = result.actualCopies;
        // The first clone doubles as the power probe and the first container handed to a provider.
        KeyCounter[] pending = ParallelBatchCpuHelper.cloneSingleCopy(result);
        double powerOne = patternPowerFor(details, pending);

        // One SIMULATE for the whole visit (instead of one per copy) caps how many copies we can
        // afford; each dispatched copy still MODULATEs individually, so accounting matches the
        // vanilla path. BatchExecutor batches its energy checks the same way.
        int affordable = actual;
        double wanted = powerOne * actual;
        if (wanted > 0) {
            double avail = energyService.extractAEPower(wanted, Actionable.SIMULATE, PowerMultiplier.CONFIG);
            if (avail < wanted - 0.01D) {
                affordable = (int) Math.min((long) actual, (long) Math.floor(avail / powerOne));
            }
        }
        if (affordable <= 0) {
            ParallelBatchCpuHelper.reinject(result, actual, inventory);
            return new BulkPush(0, RETRY_DELAY_TICKS);
        }

        int dispatched = 0;
        boolean freeProviderRejected = false;
        try {
            var provider = firstProvider;
            while (provider != null && dispatched < affordable) {
                while (dispatched < affordable && !provider.isBusy()) {
                    if (pending == null) {
                        pending = ParallelBatchCpuHelper.cloneSingleCopy(result);
                    }
                    if (!provider.pushPattern(details, pending)) {
                        // A rejecting provider must not consume the container, so the clone stays
                        // valid and is reused for the next provider instead of re-cloning.
                        freeProviderRejected = true;
                        break;
                    }
                    pending = null; // ownership transferred to the provider
                    energyService.extractAEPower(powerOne, Actionable.MODULATE, PowerMultiplier.CONFIG);
                    ParallelBatchCpuHelper.markDispatched(result, 1);
                    dispatched++;
                }
                provider = nextFreeProvider(providers);
            }
        } finally {
            int leftover = actual - dispatched;
            if (leftover > 0) {
                ParallelBatchCpuHelper.reinject(result, leftover, inventory);
            }
        }

        if (dispatched > 0) {
            var jobView = scratchBatchJobView.bind(activeJob, details);
            ParallelBatchCpuHelper.registerExpectedOutputs(jobView, details, result, dispatched);
            consumeTaskCopies(activeJob, details, dispatched);
            cpu.markDirty();
            // Energy-capped visits back off on the energy cadence; otherwise re-poll immediately
            // (delay 0) so the remaining copies keep filling providers this tick.
            return new BulkPush(dispatched, affordable < actual ? RETRY_DELAY_TICKS : 0);
        }

        // Nothing dispatched: if a free provider actually rejected the chosen variant, let AE2's
        // vanilla substitution path try (avoids stalling on a template the provider won't take).
        if (freeProviderRejected) {
            return null;
        }
        // Every provider turned busy since the pre-scan: plain contention, retry next tick.
        return new BulkPush(0, 1);
    }

    @Nullable
    private ICraftingProvider nextFreeProvider(Iterator<ICraftingProvider> providers) {
        while (providers.hasNext()) {
            var provider = providers.next();
            if (!provider.isBusy()) {
                return provider;
            }
        }
        return null;
    }

    public long insert(AEKey what, long amount, Actionable type) {
        var activeJob = this.job;
        if (what == null || activeJob == null || amount <= 0) {
            return 0;
        }

        long strictMatched = activeJob.waitingFor.extract(what, amount, Actionable.SIMULATE);
        long acceptedStrict = Math.min(amount, strictMatched);
        long returned = 0;
        if (acceptedStrict > 0) {
            returned += acceptStrictWaitingItem(activeJob, what, acceptedStrict, type);
        }

        long overloadRemainder = Math.max(0L, amount - strictMatched);
        if (overloadRemainder <= 0 || !OverloadCpuStateManager.INSTANCE.hasAnyPending(this)) {
            return returned;
        }

        var claims = OverloadCpuStateManager.INSTANCE.claim(this, what, overloadRemainder, type);
        if (!claims.claimedAnything()) {
            return returned;
        }

        if (type == Actionable.MODULATE) {
            deductClaimedWaitingFor(activeJob, claims);
            if (activeJob.softCancelling) {
                long claimed = claims.claimedAmount();
                decrementItems(activeJob.timeTracker, claimed, what.getType());
                inventory.insert(what, claimed, Actionable.MODULATE);
                returned += claimed;
                if (activeJob.waitingKeys.isEmpty()
                        && !OverloadCpuStateManager.INSTANCE.hasAnyPending(this)) {
                    finishJob(false);
                    cpu.updateOutput(null);
                }
            } else {
                returned += applyInventoryClaims(activeJob, what, claims);
                returned += applyRequesterClaims(activeJob, what, claims);
            }
            cpu.markDirty();
        } else {
            returned += claims.claimedAmount();
        }

        return returned;
    }

    private long acceptStrictWaitingItem(TimeWheelJob activeJob, AEKey what, long amount, Actionable type) {
        if (type == Actionable.MODULATE) {
            decrementItems(activeJob.timeTracker, amount, what.getType());
            extractWaitingFor(activeJob, what, amount);
            cpu.markDirty();
        }

        if (activeJob.softCancelling) {
            if (type == Actionable.MODULATE) {
                inventory.insert(what, amount, Actionable.MODULATE);
                if (activeJob.waitingKeys.isEmpty()
                        && !OverloadCpuStateManager.INSTANCE.hasAnyPending(this)) {
                    finishJob(false);
                    cpu.updateOutput(null);
                }
            }
            return amount;
        }

        long inserted = amount;
        if (what.matches(activeJob.finalOutput)) {
            inserted = acceptFinalOutputWithReusableSeed(activeJob, what, amount, type);
        } else if (type == Actionable.MODULATE) {
            inventory.insert(what, amount, Actionable.MODULATE);
            wakeSchedulerForReturnedInput(what);
        }

        return inserted;
    }

    private long acceptFinalOutputWithReusableSeed(
            TimeWheelJob activeJob, AEKey what, long amount, Actionable type) {
        long seedQuota = seedReturnQuota.get(what);
        if (seedQuota <= 0) {
            long inserted = activeJob.link.insert(what, amount, type);
            if (type == Actionable.MODULATE && inserted > 0) {
                finishDeliveredFinalOutput(activeJob, what, inserted);
            }
            return inserted;
        }

        long accepted = 0L;
        long remaining = amount;
        // Seed ownership always wins when the returned key is also the requested output. Only the
        // amount left after restoring the seed quota may satisfy the final-output request.
        long reserved = reserveReturnedSeed(what, remaining, seedQuota, type);
        accepted += reserved;
        remaining -= reserved;

        long finalOffer = Math.min(remaining, activeJob.remainingAmount);
        long delivered = finalOffer > 0
                ? activeJob.link.insert(what, finalOffer, type) : 0L;
        accepted += delivered;
        // Keep the unaccepted part of finalOffer with the caller so it can retry when the output
        // destination becomes available. Only the physical tail beyond that offer is seed/excess.
        long tail = remaining - finalOffer;

        // Any deterministic output beyond both the requested final amount and the one retained seed
        // is a normal byproduct/net surplus and belongs in CPU inventory for ordinary return to ME.
        if (tail > 0) {
            if (type == Actionable.MODULATE) inventory.insert(what, tail, Actionable.MODULATE);
            accepted += tail;
        }

        if (type == Actionable.MODULATE && delivered > 0) {
            finishDeliveredFinalOutput(activeJob, what, delivered);
        } else if (type == Actionable.MODULATE && reserved > 0) {
            wakeSchedulerForReturnedInput(what);
        }
        return accepted;
    }

    private long reserveReturnedSeed(
            AEKey what, long amount, long quota, Actionable type) {
        if (amount <= 0 || quota <= 0) return 0L;
        long alreadyHeld = inventory.extract(what, Long.MAX_VALUE, Actionable.SIMULATE);
        long reserved = Math.min(amount, Math.max(0L, quota - alreadyHeld));
        if (reserved > 0 && type == Actionable.MODULATE) {
            inventory.insert(what, reserved, Actionable.MODULATE);
            wakeSchedulerForReturnedInput(what);
        }
        return reserved;
    }

    private void finishDeliveredFinalOutput(TimeWheelJob activeJob, AEKey what, long delivered) {
        postChange(what);
        activeJob.remainingAmount = Math.max(0L, activeJob.remainingAmount - delivered);
        if (activeJob.remainingAmount <= 0) {
            finishJob(true);
            cpu.updateOutput(null);
        } else {
            cpu.updateOutput(new GenericStack(activeJob.finalOutput.what(), activeJob.remainingAmount));
        }
    }

    private void finishJob(boolean success) {
        var activeJob = this.job;
        if (activeJob == null) {
            return;
        }

        OverloadCpuStateManager.INSTANCE.clear(this);

        if (success) {
            activeJob.link.markDone();
        } else {
            activeJob.link.cancel();
        }

        clearWaitingFor(activeJob);
        for (var entry : activeJob.tasks.entrySet()) {
            postPatternOutputsChange(entry.getKey());
        }

        notifyJobOwner(
                activeJob,
                success ? CraftingJobStatusPacket.Status.FINISHED : CraftingJobStatusPacket.Status.CANCELLED);

        this.job = null;
        patternPowerCache.clear();
        clearTaskWheel();
        storeItems();
    }

    public void cancel() {
        if (job == null) {
            return;
        }
        if (!job.softCancelling && hasReusableSeedPattern(job)) {
            beginSoftCancel(job);
            return;
        }
        seedReturnQuota.clear();
        cpu.updateOutput(null);
        finishJob(false);
    }

    private boolean hasReusableSeedPattern(TimeWheelJob activeJob) {
        return !seedReturnQuota.isEmpty() || activeJob.closedLoopJob;
    }

    private void beginSoftCancel(TimeWheelJob activeJob) {
        activeJob.softCancelling = true;
        activeJob.suspended = false;
        for (var details : List.copyOf(activeJob.tasks.keySet())) {
            removeTask(activeJob, details);
            postPatternOutputsChange(details);
        }
        clearTaskWheel();
        cpu.updateOutput(null);
        cpu.markDirty();
        if (activeJob.waitingKeys.isEmpty()
                && !OverloadCpuStateManager.INSTANCE.hasAnyPending(this)) {
            finishJob(false);
        }
    }

    public void prepareForRemoval() {
        if (this.job != null) {
            // A removed CPU cannot remain in the first-stage "wait for seed" state. Removal is the
            // destructive second cancellation: stop tracking late returns, cancel the link and
            // return/drop only content that is still physically in the CPU.
            seedReturnQuota.clear();
            finishJob(false);
        }
        this.pendingJobTag = null;
        this.pendingOverloadTag = null;
        OverloadCpuStateManager.INSTANCE.clear(this);
        seedReturnQuota.clear();
        clearTaskWheel();
        cpu.updateOutput(null);
        cantStoreItems = false;
    }

    public void addStoredDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        prepareForRemoval();
        for (var entry : this.inventory.list) {
            if (entry.getLongValue() > 0) {
                entry.getKey().addDrops(entry.getLongValue(), drops, level, pos);
            }
        }
    }

    public void clearRemovedContent() {
        prepareForRemoval();
        this.inventory.clear();
        this.inventory.list.removeEmptySubmaps();
    }

    public void storeItems() {
        Preconditions.checkState(job == null, "CPU should not have a job to prevent re-insertion when dumping items");
        if (this.inventory.list.isEmpty()) {
            return;
        }

        var grid = cpu.getGrid();
        if (grid == null) {
            return;
        }

        var storage = grid.getStorageService().getInventory();
        for (var entry : this.inventory.list) {
            postChange(entry.getKey());
            long intercept = Math.min(entry.getLongValue(), seedReturnQuota.get(entry.getKey()));
            long seedInserted = intercept > 0
                    ? cpu.getHost().insertReusableSeed(entry.getKey(), intercept, Actionable.MODULATE)
                    : 0L;
            if (intercept > 0) {
                seedReturnQuota.remove(entry.getKey(), intercept);
                entry.setValue(entry.getLongValue() - seedInserted);
            }
            var inserted = storage.insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE, cpu.getSrc());
            entry.setValue(entry.getLongValue() - inserted);
        }
        this.inventory.list.removeZeros();
        if (this.inventory.list.isEmpty()) {
            seedReturnQuota.clear();
        }
        cpu.markDirty();
    }

    private KeyCounter collectReusableSeeds(ICraftingPlan plan) {
        var result = new KeyCounter();
        for (var details : plan.patternTimes().keySet()) {
            if (!(details instanceof ReusableSeedPattern seeded)) continue;
            for (var entry : seeded.reusableSeedRequirements().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0) {
                    result.add(entry.getKey(), entry.getValue());
                }
            }
        }
        return result;
    }

    private static KeyCounter copyCounter(KeyCounter source) {
        var copy = new KeyCounter();
        copy.addAll(source);
        return copy;
    }

    private void rollbackHostSeeds(KeyCounter hostSeeds) {
        for (var entry : hostSeeds) {
            long removed = inventory.extract(entry.getKey(), entry.getLongValue(), Actionable.MODULATE);
            if (removed > 0) {
                cpu.getHost().insertReusableSeed(entry.getKey(), removed, Actionable.MODULATE);
            }
        }
    }

    public void readFromNBT(CompoundTag data, HolderLookup.Provider registries) {
        this.inventory.readFromNBT(data.getList(TAG_INVENTORY, Tag.TAG_COMPOUND), registries);
        this.job = null;
        this.pendingJobTag = null;
        this.pendingOverloadTag = null;
        OverloadCpuStateManager.INSTANCE.clear(this);
        seedReturnQuota.clear();
        if (data.contains(TAG_SEED_RETURN_QUOTA, Tag.TAG_LIST)) {
            var seeds = data.getList(TAG_SEED_RETURN_QUOTA, Tag.TAG_COMPOUND);
            for (int i = 0; i < seeds.size(); i++) {
                var stack = GenericStack.readTag(registries, seeds.getCompound(i));
                if (stack != null && stack.amount() > 0) seedReturnQuota.add(stack.what(), stack.amount());
            }
        }
        clearTaskWheel();

        if (data.contains(TAG_JOB, Tag.TAG_COMPOUND)) {
            var jobTag = data.getCompound(TAG_JOB);
            var overloadTag = data.contains(TAG_OVERLOAD_STATE, Tag.TAG_COMPOUND)
                    ? data.getCompound(TAG_OVERLOAD_STATE)
                    : null;
            if (cpu.getLevel() == null) {
                this.pendingJobTag = jobTag.copy();
                this.pendingOverloadTag = overloadTag != null ? overloadTag.copy() : null;
                updatePendingDisplayedOutput(jobTag, registries);
            } else {
                restoreJobFromNBT(jobTag, overloadTag, registries);
            }
        } else {
            cpu.updateOutput(null);
        }
    }

    public void writeToNBT(CompoundTag data, HolderLookup.Provider registries) {
        if (!this.inventory.list.isEmpty()) {
            data.put(TAG_INVENTORY, this.inventory.writeToNBT(registries));
        } else {
            data.remove(TAG_INVENTORY);
        }
        if (!seedReturnQuota.isEmpty()) {
            var seeds = new ListTag();
            for (var entry : seedReturnQuota) {
                seeds.add(GenericStack.writeTag(registries,
                        new GenericStack(entry.getKey(), entry.getLongValue())));
            }
            data.put(TAG_SEED_RETURN_QUOTA, seeds);
        } else {
            data.remove(TAG_SEED_RETURN_QUOTA);
        }
        if (this.job != null) {
            data.put(TAG_JOB, this.job.writeToNBT(registries));
            var overloadTag = OverloadCpuStateManager.INSTANCE.writeToTag(this, registries);
            if (overloadTag != null) {
                data.put(TAG_OVERLOAD_STATE, overloadTag);
            } else {
                data.remove(TAG_OVERLOAD_STATE);
            }
        } else if (this.pendingJobTag != null) {
            data.put(TAG_JOB, this.pendingJobTag.copy());
            if (this.pendingOverloadTag != null) {
                data.put(TAG_OVERLOAD_STATE, this.pendingOverloadTag.copy());
            } else {
                data.remove(TAG_OVERLOAD_STATE);
            }
        } else {
            data.remove(TAG_JOB);
            data.remove(TAG_OVERLOAD_STATE);
        }
    }

    public void resolvePendingLoad() {
        if (this.pendingJobTag == null) {
            return;
        }

        var level = cpu.getLevel();
        if (level == null) {
            return;
        }

        var jobTag = this.pendingJobTag;
        var overloadTag = this.pendingOverloadTag;
        this.pendingJobTag = null;
        this.pendingOverloadTag = null;
        restoreJobFromNBT(jobTag, overloadTag, level.registryAccess());
    }

    private void restoreJobFromNBT(CompoundTag jobTag,
                                   @Nullable CompoundTag overloadTag,
                                   HolderLookup.Provider registries) {
        this.job = readJobFromNBT(jobTag, registries);
        patternPowerCache.clear();
        if (this.job == null || this.job.finalOutput == null) {
            cpu.updateOutput(null);
            finishJob(false);
            return;
        }

        cpu.updateOutput(new GenericStack(job.finalOutput.what(), job.remainingAmount));
        markWaitingKeysChanged();
        if (overloadTag != null) {
            OverloadCpuStateManager.INSTANCE.readFromTag(
                    this,
                    job.link.getCraftingID(),
                    overloadTag,
                    registries);
        }
        rebuildTaskWheel();
    }

    private void updatePendingDisplayedOutput(CompoundTag jobTag, HolderLookup.Provider registries) {
        var finalOutput = GenericStack.readTag(registries, jobTag.getCompound(NBT_FINAL_OUTPUT));
        if (finalOutput == null) {
            cpu.updateOutput(null);
            return;
        }

        var remainingAmount = jobTag.getLong(NBT_REMAINING_AMOUNT);
        cpu.updateOutput(remainingAmount > 0 ? new GenericStack(finalOutput.what(), remainingAmount) : null);
    }

    @Nullable
    private TimeWheelJob readJobFromNBT(CompoundTag data, HolderLookup.Provider registries) {
        var loadedJob = TimeWheelJob.readFromNBT(data, registries, this::postChange, cpu);
        if (loadedJob == null) {
            return null;
        }

        var grid = cpu.getGrid();
        if (grid != null) {
            ((CraftingService) grid.getCraftingService()).addLink(loadedJob.link);
        }
        return loadedJob;
    }

    @Nullable
    public ICraftingLink getLastLink() {
        return this.job != null ? this.job.link : null;
    }

    public ListCraftingInventory getInventory() {
        return inventory;
    }

    public long getLastModifiedOnTick() {
        return lastModifiedOnTick;
    }

    public long getWaitingKeysModifiedOnTick() {
        return waitingKeysModifiedOnTick;
    }

    public boolean hasJob() {
        return this.job != null || this.pendingJobTag != null;
    }

    public boolean hasPersistentState() {
        return this.job != null
                || this.pendingJobTag != null
                || this.pendingOverloadTag != null
                || !this.inventory.list.isEmpty()
                || !seedReturnQuota.isEmpty()
                || OverloadCpuStateManager.INSTANCE.hasAnyPending(this);
    }

    @Nullable
    public GenericStack getFinalJobOutput() {
        return this.job != null ? this.job.finalOutput : null;
    }

    public ElapsedTimeTracker getElapsedTimeTracker() {
        return this.job != null ? this.job.timeTracker : new ElapsedTimeTracker();
    }

    public void addListener(Consumer<AEKey> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<AEKey> listener) {
        listeners.remove(listener);
    }

    public long getStored(AEKey template) {
        return this.inventory.extract(template, Long.MAX_VALUE, Actionable.SIMULATE);
    }

    public long getWaitingFor(AEKey template) {
        return this.job != null
                ? this.job.waitingFor.extract(template, Long.MAX_VALUE, Actionable.SIMULATE)
                : 0L;
    }

    public void getAllWaitingFor(Set<AEKey> waitingFor) {
        if (this.job == null) {
            return;
        }
        waitingFor.addAll(this.job.waitingKeys);
    }

    public long getPendingOutputs(AEKey template) {
        return this.job != null ? this.job.pendingOutputs.get(template) : 0L;
    }

    public void getAllItems(KeyCounter out) {
        out.addAll(this.inventory.list);
        if (this.job == null) {
            return;
        }

        out.addAll(job.waitingFor.list);
        out.addAll(job.pendingOutputs);
    }

    public boolean isCantStoreItems() {
        return cantStoreItems;
    }

    public boolean isJobSuspended() {
        return job != null && job.suspended;
    }

    public boolean isSoftCancelling() {
        return job != null && job.softCancelling;
    }

    public void setJobSuspended(boolean suspended) {
        if (job != null && job.suspended != suspended) {
            job.suspended = suspended;
            cpu.markDirty();
        }
    }

    private Iterable<ICraftingProvider> providersForSinglePush(CraftingService craftingService,
                                                               IPatternDetails details) {
        var skipped = batchedByTask.get(details);
        if (skipped == null || skipped.isEmpty()) {
            return craftingService.getProviders(details);
        }
        return () -> new Iterator<>() {
            private final Iterator<ICraftingProvider> raw = craftingService.getProviders(details).iterator();
            @Nullable
            private ICraftingProvider next;

            @Override
            public boolean hasNext() {
                while (next == null && raw.hasNext()) {
                    var candidate = raw.next();
                    if (!skipped.containsKey(candidate)) {
                        next = candidate;
                    }
                }
                return next != null;
            }

            @Override
            public ICraftingProvider next() {
                if (!hasNext()) {
                    throw new java.util.NoSuchElementException();
                }
                var result = next;
                next = null;
                return result;
            }
        };
    }

    private boolean hasAmbiguousOverloadOutput(IPatternDetails details) {
        if (!(details instanceof OverloadedProviderOnlyPatternDetails overloadDetails)) {
            return false;
        }

        var reference = new OverloadPatternReference(
                overloadDetails.overloadPatternIdentity(),
                overloadDetails.overloadPatternDetailsView().sourcePattern());
        return OverloadCpuStateManager.INSTANCE.hasAmbiguousOutputRegistration(
                this,
                reference,
                overloadDetails.overloadPatternDetailsView());
    }

    private void recordPushedPattern(TimeWheelJob activeJob,
                                     IPatternDetails details,
                                     KeyCounter expectedOutputs,
                                     KeyCounter expectedContainerItems,
                                     long copies) {
        for (var expectedOutput : expectedOutputs) {
            insertWaitingFor(
                    activeJob,
                    expectedOutput.getKey(),
                    multiplySaturated(expectedOutput.getLongValue(), copies));
        }
        for (var expectedContainerItem : expectedContainerItems) {
            long amount = multiplySaturated(expectedContainerItem.getLongValue(), copies);
            insertWaitingFor(activeJob, expectedContainerItem.getKey(), amount);
            addMaxItems(activeJob.timeTracker, amount, expectedContainerItem.getKey().getType());
        }

        registerOverloadExpectedOutputs(activeJob, details, copies);
        cpu.markDirty();
    }

    private void registerOverloadExpectedOutputs(TimeWheelJob activeJob,
                                                 IPatternDetails details,
                                                 long copies) {
        if (!(details instanceof OverloadedProviderOnlyPatternDetails overloadDetails) || copies <= 0) {
            return;
        }

        var reference = new OverloadPatternReference(
                overloadDetails.overloadPatternIdentity(),
                overloadDetails.overloadPatternDetailsView().sourcePattern());
        var finalOutputKey = activeJob.finalOutput != null ? activeJob.finalOutput.what() : null;
        OverloadCpuStateManager.INSTANCE.registerExpectedOutputs(
                this,
                activeJob.link.getCraftingID(),
                reference,
                overloadDetails.overloadPatternDetailsView(),
                details.getOutputs(),
                finalOutputKey,
                copies);
    }

    private long applyInventoryClaims(TimeWheelJob activeJob, AEKey incoming, OverloadClaimResult claims) {
        long claimed = claims.claimedForInventory();
        if (claimed <= 0) {
            return 0;
        }

        decrementItems(activeJob.timeTracker, claimed, incoming.getType());
        inventory.insert(incoming, claimed, Actionable.MODULATE);
        wakeSchedulerForReturnedInput(incoming);
        return claimed;
    }

    private long applyRequesterClaims(TimeWheelJob activeJob, AEKey incoming, OverloadClaimResult claims) {
        long claimed = claims.claimedForRequester();
        if (claimed <= 0) {
            return 0;
        }

        decrementItems(activeJob.timeTracker, claimed, incoming.getType());
        long inserted = activeJob.link.insert(incoming, claimed, Actionable.MODULATE);
        postChange(incoming);

        activeJob.remainingAmount = Math.max(0L, activeJob.remainingAmount - claimed);
        if (activeJob.remainingAmount <= 0) {
            finishJob(true);
            cpu.updateOutput(null);
        } else {
            cpu.updateOutput(new GenericStack(activeJob.finalOutput.what(), activeJob.remainingAmount));
        }
        return inserted;
    }

    private void consumeTaskCopies(TimeWheelJob activeJob, IPatternDetails details, long copies) {
        if (copies <= 0) {
            return;
        }

        var task = activeJob.tasks.get(details);
        if (task == null || task.value <= 0) {
            return;
        }

        long consumed = Math.min(task.value, copies);
        task.value -= consumed;
        activeJob.removePendingOutputs(details, consumed);
        postPatternOutputsChange(details);

        if (task.value <= 0) {
            activeJob.tasks.remove(details);
        }
    }

    private void setTaskValue(TimeWheelJob activeJob, IPatternDetails details, long value) {
        var task = activeJob.tasks.get(details);
        if (task == null) {
            return;
        }

        long normalized = Math.max(0L, value);
        long oldValue = task.value;
        if (normalized == oldValue) {
            return;
        }

        task.value = normalized;
        if (normalized < oldValue) {
            activeJob.removePendingOutputs(details, oldValue - normalized);
        } else {
            activeJob.addPendingOutputs(details, normalized - oldValue);
        }
        postPatternOutputsChange(details);
    }

    private void removeTask(TimeWheelJob activeJob, IPatternDetails details) {
        unparkTask(details);
        var removed = activeJob.tasks.remove(details);
        if (removed != null && removed.value > 0) {
            activeJob.removePendingOutputs(details, removed.value);
        }
    }

    private void deductClaimedWaitingFor(TimeWheelJob activeJob, OverloadClaimResult claims) {
        for (var claim : claims.claims()) {
            extractWaitingFor(activeJob, claim.exactExpectedKey(), claim.claimedAmount());
        }
    }

    private void insertWaitingFor(TimeWheelJob activeJob, AEKey what, long amount) {
        if (activeJob.insertWaitingFor(what, amount)) {
            markWaitingKeysChanged();
        }
    }

    private long extractWaitingFor(TimeWheelJob activeJob, AEKey what, long amount) {
        var result = activeJob.extractWaitingFor(what, amount);
        if (result.removedKey()) {
            markWaitingKeysChanged();
        }
        return result.extracted();
    }

    private void clearWaitingFor(TimeWheelJob activeJob) {
        if (activeJob.clearWaitingFor()) {
            markWaitingKeysChanged();
        }
    }

    private void markWaitingKeysChanged() {
        waitingKeysModifiedOnTick = TickHandler.instance().getCurrentTick();
    }

    private void prepareScheduler(TimeWheelJob activeJob) {
        advanceWheel();
        if (queueRebuildNeeded || needsSchedulerRebuild(activeJob)) {
            rebuildTaskWheel();
        }
    }

    private boolean needsSchedulerRebuild(TimeWheelJob activeJob) {
        return !activeJob.tasks.isEmpty()
                && queuedTasks.isEmpty()
                && tasksParkedByMissingKey.isEmpty()
                && !hasScheduledWheelEntries();
    }

    private boolean hasScheduledWheelEntries() {
        for (var bucket : taskWheel) {
            if (!bucket.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void advanceWheel() {
        long now = TickHandler.instance().getCurrentTick();
        if (schedulerTick == Long.MIN_VALUE) {
            schedulerTick = now;
            return;
        }

        int delta = (int) Math.max(0L, Math.min(WHEEL_SIZE, now - schedulerTick));
        schedulerTick = now;
        if (delta == 0) {
            return;
        }

        int newCursor = (wheelCursor + delta) & WHEEL_MASK;
        // Everything in a bucket the cursor moves off of is due by now, INCLUDING the old cursor
        // bucket itself: wakes and job (re)builds that run outside executeCrafting (e.g. a provider
        // returning outputs during the post-tick sweep) schedule with delay 0 into the bucket that
        // was already polled this tick. Without draining it, such a task would silently sleep a
        // whole wheel revolution (~64 ticks) — and a tick gap (server stall, chunk reload) would
        // likewise strand every skipped bucket's tasks.
        var dest = taskWheel[newCursor];
        for (int i = 0; i < delta; i++) {
            var skipped = taskWheel[(wheelCursor + i) & WHEEL_MASK];
            if (skipped == dest) {
                continue;
            }
            while (!skipped.isEmpty()) {
                dest.addLast(skipped.pollFirst());
            }
        }
        wheelCursor = newCursor;
    }

    private void rebuildTaskWheel() {
        clearTaskWheel();
        var activeJob = this.job;
        if (activeJob == null) {
            return;
        }

        for (var entry : activeJob.tasks.entrySet()) {
            if (entry.getValue().value > 0) {
                scheduleRebuiltTask(activeJob, entry.getKey());
            }
        }
        queueRebuildNeeded = false;
    }

    private void scheduleRebuiltTask(TimeWheelJob activeJob, IPatternDetails details) {
        if (!hasOnlyExactInputs(details)) {
            scheduleTask(details, 0);
            return;
        }

        var missingKeys = findMissingExactInputKeys(details);
        if (missingKeys.isEmpty()) {
            scheduleTask(details, 0);
            return;
        }

        if (parkTaskForMissingInputs(activeJob, details, missingKeys)) {
            rescheduleIfStillPending(activeJob, details, PARKED_TASK_SAFETY_DELAY_TICKS);
        } else {
            scheduleTask(details, 0);
        }
    }

    private boolean hasOnlyExactInputs(IPatternDetails details) {
        for (var input : details.getInputs()) {
            var possibles = input.getPossibleInputs();
            if (possibles.length != 1) {
                return false;
            }
            if (possibles[0].what() == null) {
                return false;
            }
        }
        return true;
    }

    private void clearTaskWheel() {
        for (var bucket : taskWheel) {
            bucket.clear();
        }
        queuedTasks.clear();
        clearParkedTasks();
        queueRebuildNeeded = true;
    }

    private void scheduleTask(IPatternDetails details, int delayTicks) {
        var activeJob = this.job;
        if (activeJob == null || !activeJob.tasks.containsKey(details)) {
            return;
        }
        if (!queuedTasks.add(details)) {
            return;
        }
        int slot = (wheelCursor + Math.max(0, delayTicks)) & WHEEL_MASK;
        taskWheel[slot].addLast(details);
    }

    private void unscheduleTask(IPatternDetails details) {
        boolean removed = false;
        for (var bucket : taskWheel) {
            for (var iterator = bucket.iterator(); iterator.hasNext(); ) {
                if (iterator.next() == details) {
                    iterator.remove();
                    removed = true;
                }
            }
        }
        if (removed) {
            queuedTasks.remove(details);
        }
    }

    private boolean parkTaskForMissingInputs(TimeWheelJob activeJob, IPatternDetails details) {
        if (!activeJob.tasks.containsKey(details) || !hasOnlyExactInputs(details)) {
            return false;
        }

        return parkTaskForMissingInputs(activeJob, details, findMissingExactInputKeys(details));
    }

    private boolean parkTaskForMissingInputs(TimeWheelJob activeJob, IPatternDetails details, Set<AEKey> missingKeys) {
        if (missingKeys.isEmpty()) {
            return false;
        }

        unparkTask(details);
        var taskKeys = new ArrayList<Object>(missingKeys.size());
        for (var key : missingKeys) {
            taskKeys.add(key != null ? key.getPrimaryKey() : null);
        }
        return tasksParkedByMissingKey.park(details, taskKeys);
    }

    private void unparkTask(IPatternDetails details) {
        tasksParkedByMissingKey.unpark(details);
    }

    private void clearParkedTasks() {
        tasksParkedByMissingKey.clear();
    }

    private Set<AEKey> findMissingExactInputKeys(IPatternDetails details) {
        var exactRequired = new HashMap<AEKey, Long>();

        for (var input : details.getInputs()) {
            long multiplier = input.getMultiplier();
            var possibles = input.getPossibleInputs();
            if (possibles.length != 1) {
                return Set.of();
            }

            var possible = possibles[0];
            var key = possible.what();
            long perCopy = multiplySaturated(possible.amount(), multiplier);
            if (key == null || perCopy <= 0) {
                continue;
            }
            exactRequired.merge(key, perCopy, Ae2LtTimeWheelCraftingCpuLogic::addSaturated);
        }

        var missing = new HashSet<AEKey>();
        for (var entry : exactRequired.entrySet()) {
            long required = entry.getValue();
            if (required > 0 && inventory.extract(entry.getKey(), required, Actionable.SIMULATE) < required) {
                missing.add(entry.getKey());
            }
        }
        return missing;
    }

    @Nullable
    private IPatternDetails pollDueTask(TimeWheelJob activeJob) {
        var bucket = taskWheel[wheelCursor];
        while (!bucket.isEmpty()) {
            var details = bucket.pollFirst();
            queuedTasks.remove(details);
            var task = activeJob.tasks.get(details);
            if (task != null && task.value > 0) {
                unparkTask(details);
                return details;
            }
        }
        return null;
    }

    private void rescheduleIfStillPending(TimeWheelJob activeJob, IPatternDetails details, int delayTicks) {
        var task = activeJob.tasks.get(details);
        if (task != null && task.value > 0) {
            scheduleTask(details, delayTicks);
        }
    }

    private void rescheduleFailedTask(TimeWheelJob activeJob, IPatternDetails details, DispatchOutcome outcome) {
        if (outcome == DispatchOutcome.RETRY_MISSING_INPUT && parkTaskForMissingInputs(activeJob, details)) {
            rescheduleIfStillPending(activeJob, details, PARKED_TASK_SAFETY_DELAY_TICKS);
            return;
        }

        unparkTask(details);
        rescheduleIfStillPending(activeJob, details, retryDelayTicks(outcome));
    }

    private int retryDelayTicks(DispatchOutcome outcome) {
        return switch (outcome) {
            case RETRY_SOON -> 1;
            case RETRY_MISSING_INPUT, RETRY_NO_POWER, RETRY_LATER -> RETRY_DELAY_TICKS;
            case PUSHED -> 0;
        };
    }

    private void carryOverDueTasks() {
        var bucket = taskWheel[wheelCursor];
        if (bucket.isEmpty()) {
            return;
        }

        var nextBucket = taskWheel[(wheelCursor + 1) & WHEEL_MASK];
        while (!bucket.isEmpty()) {
            nextBucket.addLast(bucket.pollFirst());
        }
    }

    private void wakeSchedulerForReturnedInput(AEKey what) {
        if (this.job == null || what == null) {
            return;
        }

        var tasksToWake = tasksParkedByMissingKey.wake(what.getPrimaryKey());
        if (tasksToWake.isEmpty()) {
            return;
        }

        for (var details : tasksToWake) {
            unparkTask(details);
            unscheduleTask(details);
            scheduleTask(details, 0);
        }
    }

    private void postChange(@Nullable AEKey what) {
        if (what == null) {
            return;
        }

        if (batchingStatusChanges) {
            batchedStatusChanges.add(what);
            return;
        }

        lastModifiedOnTick = TickHandler.instance().getCurrentTick();
        for (var listener : listeners) {
            listener.accept(what);
        }
    }

    private void beginStatusChangeBatch() {
        batchingStatusChanges = true;
        batchedStatusChanges.clear();
    }

    private void endStatusChangeBatch() {
        batchingStatusChanges = false;
        if (batchedStatusChanges.isEmpty()) {
            return;
        }

        lastModifiedOnTick = TickHandler.instance().getCurrentTick();
        statusChangeScratch.clear();
        statusChangeScratch.addAll(batchedStatusChanges);
        batchedStatusChanges.clear();
        for (var key : statusChangeScratch) {
            for (var listener : listeners) {
                listener.accept(key);
            }
        }
        statusChangeScratch.clear();
    }

    private void postPatternOutputsChange(IPatternDetails details) {
        for (var output : details.getOutputs()) {
            postChange(output.what());
        }
    }

    private void notifyJobOwner(TimeWheelJob activeJob, CraftingJobStatusPacket.Status status) {
        this.lastModifiedOnTick = TickHandler.instance().getCurrentTick();
        var playerId = activeJob.playerId;
        if (playerId == null || cpu.getLevel() == null || cpu.getLevel().getServer() == null) {
            return;
        }

        var connectedPlayer = IPlayerRegistry.getConnected(cpu.getLevel().getServer(), playerId);
        if (connectedPlayer != null) {
            ClientboundPacket message = new CraftingJobStatusPacket(
                    activeJob.link.getCraftingID(),
                    activeJob.finalOutput.what(),
                    activeJob.finalOutput.amount(),
                    activeJob.remainingAmount,
                    status);
            connectedPlayer.connection.send(message);
        }
    }

    @SuppressWarnings("unchecked")
    private static ArrayDeque<IPatternDetails>[] createWheel() {
        var wheel = new ArrayDeque[WHEEL_SIZE];
        for (int i = 0; i < wheel.length; i++) {
            wheel[i] = new ArrayDeque<IPatternDetails>();
        }
        return wheel;
    }

    private static void addMaxItems(ElapsedTimeTracker tracker, long count, AEKeyType type) {
        ((ElapsedTimeTrackerAccessor) tracker).invokeAddMaxItems(count, type);
    }

    private static void decrementItems(ElapsedTimeTracker tracker, long count, AEKeyType type) {
        ((ElapsedTimeTrackerAccessor) tracker).invokeDecrementItems(count, type);
    }

    private static void clearScratchCounter(KeyCounter counter) {
        // Only zero the entries; keep the per-type submaps allocated. These counters are reused every
        // dispatch, so removeEmptySubmaps() would just churn allocations for no benefit.
        counter.clear();
    }

    private double patternPowerFor(IPatternDetails details, KeyCounter[] craftingContainer) {
        var cached = patternPowerCache.get(details);
        if (cached != null) {
            return cached;
        }
        double power = CraftingCpuHelper.calculatePatternPower(craftingContainer);
        patternPowerCache.put(details, power);
        return power;
    }

    private static long multiplySaturated(long left, long right) {
        if (left <= 0 || right <= 0) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static long addSaturated(long left, long right) {
        if (right <= 0) {
            return left;
        }
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private enum DispatchOutcome {
        PUSHED,
        RETRY_SOON,
        RETRY_MISSING_INPUT,
        RETRY_NO_POWER,
        RETRY_LATER
    }

    /**
     * Result of one {@link #pushBulkForTask} visit. {@code retryDelayTicks} tells the scheduler
     * when to look at the task again: 0 = re-poll this tick (more copies may still fit), 1 =
     * provider contention, {@link #RETRY_DELAY_TICKS} = out of energy.
     */
    private record BulkPush(int dispatched, int retryDelayTicks) {
    }

    private static final class TaskProgress {
        private long value;
    }

    private record WaitingExtract(long extracted, boolean removedKey) {
    }

    private static final class TimeWheelJob {
        private final CraftingLink link;
        private final ListCraftingInventory waitingFor;
        private final Set<AEKey> waitingKeys = new HashSet<>();
        private final KeyCounter pendingOutputs = new KeyCounter();
        private final Map<IPatternDetails, TaskProgress> tasks = new HashMap<>();
        private final ElapsedTimeTracker timeTracker;
        private GenericStack finalOutput;
        private long remainingAmount;
        @Nullable
        private Integer playerId;
        private boolean suspended;
        private boolean softCancelling;
        private boolean closedLoopJob;

        private TimeWheelJob(ICraftingPlan plan,
                             Consumer<AEKey> postCraftingDifference,
                             CraftingLink link,
                             @Nullable Integer playerId) {
            this(plan, postCraftingDifference, link, playerId, new ElapsedTimeTracker());
        }

        private TimeWheelJob(ICraftingPlan plan,
                             Consumer<AEKey> postCraftingDifference,
                             CraftingLink link,
                             @Nullable Integer playerId,
                             ElapsedTimeTracker timeTracker) {
            this.finalOutput = plan.finalOutput();
            this.remainingAmount = this.finalOutput.amount();
            this.waitingFor = new ListCraftingInventory(postCraftingDifference::accept);
            this.timeTracker = timeTracker;

            for (var entry : plan.emittedItems()) {
                insertWaitingFor(entry.getKey(), entry.getLongValue());
                addMaxItems(timeTracker, entry.getLongValue(), entry.getKey().getType());
            }
            for (var entry : plan.patternTimes().entrySet()) {
                if (entry.getKey() instanceof ReusableSeedPattern) closedLoopJob = true;
                var expanded = entry.getKey() instanceof PatternFiringExpander expander
                        ? expander.expandPatternFirings(entry.getValue())
                        : Map.of(entry.getKey(), entry.getValue());
                for (var concrete : expanded.entrySet()) {
                    tasks.computeIfAbsent(concrete.getKey(), ignored -> new TaskProgress()).value += concrete.getValue();
                    addPendingOutputs(concrete.getKey(), concrete.getValue());
                    for (var output : concrete.getKey().getOutputs()) {
                    var amount = multiplySaturated(
                            multiplySaturated(output.amount(), concrete.getValue()),
                            output.what().getAmountPerUnit());
                    addMaxItems(timeTracker, amount, output.what().getType());
                    }
                }
            }
            this.link = link;
            this.playerId = playerId;
        }

        @Nullable
        private static TimeWheelJob readFromNBT(CompoundTag data,
                                                HolderLookup.Provider registries,
                                                Consumer<AEKey> postCraftingDifference,
                                                TimeWheelCraftingCPU cpu) {
            var finalOutput = GenericStack.readTag(registries, data.getCompound(NBT_FINAL_OUTPUT));
            if (finalOutput == null) {
                return null;
            }

            var link = new CraftingLink(data.getCompound(NBT_LINK), cpu);
            var emptyPlan = new TimeWheelPlan(finalOutput);
            var job = new TimeWheelJob(
                    emptyPlan,
                    postCraftingDifference,
                    link,
                    data.contains(NBT_PLAYER_ID, Tag.TAG_INT) ? data.getInt(NBT_PLAYER_ID) : null,
                    new ElapsedTimeTracker(data.getCompound(NBT_TIME_TRACKER)));
            job.remainingAmount = data.getLong(NBT_REMAINING_AMOUNT);
            job.suspended = data.getBoolean(NBT_SUSPENDED);
            job.softCancelling = data.getBoolean(NBT_SOFT_CANCELLING);
            job.closedLoopJob = job.softCancelling;
            job.waitingFor.readFromNBT(data.getList(NBT_WAITING_FOR, Tag.TAG_COMPOUND), registries);
            job.rebuildWaitingKeys();
            job.tasks.clear();
            job.pendingOutputs.clear();
            job.readTasks(data.getList(NBT_TASKS, Tag.TAG_COMPOUND), registries, cpu.getLevel());
            job.rebuildPendingOutputs();
            return job;
        }

        private void readTasks(ListTag tasksTag, HolderLookup.Provider registries, @Nullable Level level) {
            if (level == null) {
                return;
            }

            for (int i = 0; i < tasksTag.size(); i++) {
                var item = tasksTag.getCompound(i);
                var pattern = AEItemKey.fromTag(registries, item);
                if (pattern == null) {
                    continue;
                }
                var details = PatternDetailsHelper.decodePattern(pattern, level);
                var remaining = item.getLong(NBT_CRAFTING_PROGRESS);
                if (details != null && remaining > 0) {
                    tasks.computeIfAbsent(details, ignored -> new TaskProgress()).value += remaining;
                }
            }
        }

        private void rebuildPendingOutputs() {
            pendingOutputs.clear();
            for (var entry : tasks.entrySet()) {
                addPendingOutputs(entry.getKey(), entry.getValue().value);
            }
        }

        private void rebuildWaitingKeys() {
            waitingKeys.clear();
            for (var entry : waitingFor.list) {
                if (entry.getLongValue() > 0) {
                    waitingKeys.add(entry.getKey());
                }
            }
        }

        private boolean insertWaitingFor(AEKey what, long amount) {
            if (amount <= 0) {
                return false;
            }

            boolean wasAbsent = waitingFor.list.get(what) <= 0;
            waitingFor.insert(what, amount, Actionable.MODULATE);
            boolean added = wasAbsent && waitingFor.list.get(what) > 0;
            if (added) {
                waitingKeys.add(what);
            }
            return added;
        }

        private WaitingExtract extractWaitingFor(AEKey what, long amount) {
            if (amount <= 0) {
                return new WaitingExtract(0L, false);
            }

            long before = waitingFor.list.get(what);
            long extracted = waitingFor.extract(what, amount, Actionable.MODULATE);
            boolean removed = before > 0 && waitingFor.list.get(what) <= 0;
            if (removed) {
                waitingKeys.remove(what);
            }
            return new WaitingExtract(extracted, removed);
        }

        private boolean clearWaitingFor() {
            boolean changed = !waitingKeys.isEmpty();
            waitingFor.clear();
            waitingKeys.clear();
            return changed;
        }

        private void addPendingOutputs(IPatternDetails details, long copies) {
            if (copies <= 0) {
                return;
            }
            for (var output : details.getOutputs()) {
                pendingOutputs.add(output.what(), multiplySaturated(output.amount(), copies));
            }
        }

        private void removePendingOutputs(IPatternDetails details, long copies) {
            if (copies <= 0) {
                return;
            }
            for (var output : details.getOutputs()) {
                long amount = multiplySaturated(output.amount(), copies);
                long current = pendingOutputs.get(output.what());
                if (current <= amount) {
                    pendingOutputs.remove(output.what());
                } else {
                    pendingOutputs.remove(output.what(), amount);
                }
            }
        }

        private CompoundTag writeToNBT(HolderLookup.Provider registries) {
            var data = new CompoundTag();

            var linkData = new CompoundTag();
            link.writeToNBT(linkData);
            data.put(NBT_LINK, linkData);

            data.put(NBT_FINAL_OUTPUT, GenericStack.writeTag(registries, finalOutput));
            data.put(NBT_WAITING_FOR, waitingFor.writeToNBT(registries));
            data.put(NBT_TIME_TRACKER, timeTracker.writeToNBT());

            var list = new ListTag();
            for (var entry : tasks.entrySet()) {
                var definition = entry.getKey() instanceof TimeWheelTaskPersistenceDefinition persistent
                        ? persistent.timeWheelPersistenceDefinition()
                        : entry.getKey().getDefinition();
                var item = definition.toTag(registries);
                item.putLong(NBT_CRAFTING_PROGRESS, entry.getValue().value);
                list.add(item);
            }
            data.put(NBT_TASKS, list);

            data.putLong(NBT_REMAINING_AMOUNT, remainingAmount);
            if (playerId != null) {
                data.putInt(NBT_PLAYER_ID, playerId);
            }
            data.putBoolean(NBT_SUSPENDED, suspended);
            data.putBoolean(NBT_SOFT_CANCELLING, softCancelling);
            return data;
        }
    }

    private record TimeWheelPlan(GenericStack finalOutput) implements ICraftingPlan {
        @Override
        public long bytes() {
            return 0;
        }

        @Override
        public boolean simulation() {
            return false;
        }

        @Override
        public boolean multiplePaths() {
            return false;
        }

        @Override
        public KeyCounter usedItems() {
            return new KeyCounter();
        }

        @Override
        public KeyCounter emittedItems() {
            return new KeyCounter();
        }

        @Override
        public KeyCounter missingItems() {
            return new KeyCounter();
        }

        @Override
        public Map<IPatternDetails, Long> patternTimes() {
            return Map.of();
        }
    }

    private record UsedItemsOverridePlan(ICraftingPlan delegate, KeyCounter usedItems)
            implements ICraftingPlan {
        @Override public GenericStack finalOutput() { return delegate.finalOutput(); }
        @Override public long bytes() { return delegate.bytes(); }
        @Override public boolean simulation() { return delegate.simulation(); }
        @Override public boolean multiplePaths() { return delegate.multiplePaths(); }
        @Override public KeyCounter emittedItems() { return delegate.emittedItems(); }
        @Override public KeyCounter missingItems() { return delegate.missingItems(); }
        @Override public Map<IPatternDetails, Long> patternTimes() { return delegate.patternTimes(); }
    }

    private final class SingleTaskBatchJobView implements BatchJobView, BatchTaskHandle, Iterator<BatchTaskHandle> {
        private TimeWheelJob activeJob;
        private IPatternDetails details;
        private boolean consumed;

        private SingleTaskBatchJobView bind(TimeWheelJob activeJob, IPatternDetails details) {
            this.activeJob = activeJob;
            this.details = details;
            this.consumed = false;
            return this;
        }

        @Override
        public Iterator<BatchTaskHandle> taskIterator() {
            consumed = false;
            return this;
        }

        @Override
        public boolean hasNext() {
            var task = activeJob.tasks.get(details);
            return !consumed && task != null && task.value > 0;
        }

        @Override
        public BatchTaskHandle next() {
            if (!hasNext()) {
                throw new java.util.NoSuchElementException();
            }
            consumed = true;
            return this;
        }

        @Override
        public void remove() {
            removeTask(activeJob, details);
            postPatternOutputsChange(details);
        }

        @Override
        public IPatternDetails details() {
            return details;
        }

        @Override
        public long getValue() {
            var task = activeJob.tasks.get(details);
            return task != null ? task.value : 0L;
        }

        @Override
        public void setValue(long value) {
            setTaskValue(activeJob, details, value);
        }

        @Override
        public ListCraftingInventory waitingFor() {
            return activeJob.waitingFor;
        }

        @Override
        public void insertWaitingFor(AEKey what, long amount) {
            Ae2LtTimeWheelCraftingCpuLogic.this.insertWaitingFor(activeJob, what, amount);
        }

        @Override
        public void addContainerMaxItems(long count, AEKeyType type) {
            addMaxItems(activeJob.timeTracker, count, type);
        }
    }
}
