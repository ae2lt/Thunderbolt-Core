package com.moakiee.thunderbolt.ae2.timewheel;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.crafting.IPatternDetails.IInput;
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
import appeng.api.storage.MEStorage;
import appeng.core.AELog;
import appeng.core.sync.packets.CraftingJobStatusPacket;
import appeng.core.sync.packets.CraftingJobStatusPacket.Status;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.crafting.inv.ICraftingInventory;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.hooks.ticking.TickHandler;
import appeng.me.service.CraftingService;
import com.google.common.base.Preconditions;
import com.moakiee.thunderbolt.ae2.api.crafting.CraftingPatternDelegates;
import com.moakiee.thunderbolt.ae2.api.crafting.CraftingTaskPriorities;
import com.moakiee.thunderbolt.ae2.api.crafting.ISeedPreservingCraftingTask;
import com.moakiee.thunderbolt.ae2.batch.BatchCpuAccounting;
import com.moakiee.thunderbolt.ae2.batch.BatchExecutor;
import com.moakiee.thunderbolt.ae2.batch.BatchJobView;
import com.moakiee.thunderbolt.ae2.batch.BatchTaskHandle;
import com.moakiee.thunderbolt.ae2.batch.ParallelBatchCpuHelper;
import com.moakiee.thunderbolt.ae2.batch.SharedBatchInputPattern;
import com.moakiee.thunderbolt.ae2.batch.TickProviderDispatchSchedule;
import com.moakiee.thunderbolt.ae2.crafting.ExecuteLoopPattern;
import com.moakiee.thunderbolt.ae2.crafting.FinalOutputAccounting;
import com.moakiee.thunderbolt.ae2.crafting.LoopCraftingPlan;
import com.moakiee.thunderbolt.ae2.crafting.PatternFiringExpander;
import com.moakiee.thunderbolt.ae2.mixin.ElapsedTimeTrackerAccessor;
import com.moakiee.thunderbolt.ae2.overload.cpu.OverloadClaimResult;
import com.moakiee.thunderbolt.ae2.overload.cpu.OverloadConsumerCredit;
import com.moakiee.thunderbolt.ae2.overload.cpu.OverloadCpuStateManager;
import com.moakiee.thunderbolt.ae2.overload.cpu.OverloadPatternReference;
import com.moakiee.thunderbolt.ae2.overload.cpu.OverloadReusableSeedMetadata;
import com.moakiee.thunderbolt.ae2.overload.cpu.PendingOverloadClaim;
import com.moakiee.thunderbolt.ae2.overload.model.MatchMode;
import com.moakiee.thunderbolt.ae2.overload.pattern.OverloadPatternDetails;
import com.moakiee.thunderbolt.ae2.overload.pattern.OverloadedProviderOnlyPatternDetails;
import com.moakiee.thunderbolt.core.planner.Sat;
import it.unimi.dsi.fastutil.objects.Object2LongMap.Entry;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public final class Ae2LtTimeWheelCraftingCpuLogic {
   private static final int WHEEL_SIZE = 64;
   private static final int WHEEL_MASK = 63;
   private static final int MAX_TASK_PROBES_PER_TICK = 262144;
   private static final int RETRY_DELAY_TICKS = 4;
   private static final int PARKED_TASK_SAFETY_DELAY_TICKS = 32;
   private static final String TAG_INVENTORY = "inventory";
   private static final String TAG_JOB = "job";
   private static final String TAG_OVERLOAD_STATE = "ae2ltOverloadState";
   private static final String TAG_SEED_RETURN_QUOTA = "reusableSeedReturnQuota";
   private static final String TAG_SEED_RETURN_QUOTA_FINALIZED = "reusableSeedReturnQuotaFinalized";
   private static final String TAG_RETAINED_FINAL_OUTPUTS = "retainedLoopFinalOutputs";
   private static final String TAG_PENDING_REQUESTER_OUTPUTS = "pendingRequesterFinalOutputs";
   private static final String NBT_LINK = "link";
   private static final String NBT_PLAYER_ID = "playerId";
   private static final String NBT_FINAL_OUTPUT = "finalOutput";
   private static final String NBT_WAITING_FOR = "waitingFor";
   private static final String NBT_TIME_TRACKER = "timeTracker";
   private static final String NBT_REMAINING_AMOUNT = "remainingAmount";
   private static final String NBT_TASKS = "tasks";
   private static final String NBT_SUSPENDED = "suspended";
   private static final String NBT_SOFT_CANCELLING = "softCancelling";
   private static final String NBT_CLOSED_LOOP_JOB = "closedLoopJob";
   private static final String NBT_CRAFTING_PROGRESS = "#craftingProgress";
   private static final String NBT_INPUT_SEED = "#inputSeed";
   private static final String NBT_INITIAL_SEED = "#initialSeed";
   private static final String NBT_OUTPUT_SEED = "#outputSeed";
   private static final String NBT_SEED_CONSUMER = "#seedConsumer";
   private static final String NBT_OUTPUT_SEED_CREDITS = "#outputSeedCredits";
   private static final String NBT_SHARED_OUTPUT_SEED_CREDITS = "#sharedOutputSeedCredits";
   private static final String NBT_CREDIT_CONSUMER = "consumer";
   private static final String NBT_CREDIT_ITEMS = "items";
   private final TimeWheelCraftingCPU cpu;
   private final ListCraftingInventory inventory = new ListCraftingInventory(this::postChange);
   private final Set<Consumer<AEKey>> listeners = new HashSet<>();
   private final Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> batchedByTask = new HashMap<>();
   private final ArrayDeque<IPatternDetails>[] taskWheel = createWheel();
   private final Set<IPatternDetails> queuedTasks = Collections.newSetFromMap(new IdentityHashMap<>());
   private final TimeWheelTaskWakeIndex<IPatternDetails> tasksParkedByMissingKey = new TimeWheelTaskWakeIndex<>();
   private final Set<AEKey> batchedStatusChanges = new HashSet<>();
   private final List<AEKey> statusChangeScratch = new ArrayList<>();
   private final Set<IPatternDetails> nonBatchTasksThisTick = Collections.newSetFromMap(new IdentityHashMap<>());
   private final KeyCounter scratchExpectedOutputs = new KeyCounter();
   private final KeyCounter scratchExpectedContainerItems = new KeyCounter();
   private final Ae2LtTimeWheelCraftingCpuLogic.SingleTaskBatchJobView scratchBatchJobView = new Ae2LtTimeWheelCraftingCpuLogic.SingleTaskBatchJobView();
   private final Map<IPatternDetails, Double> patternPowerCache = new IdentityHashMap<>();
   private final TickProviderDispatchSchedule standaloneDispatchSchedule = new TickProviderDispatchSchedule();
   private final KeyCounter seedReturnQuota = new KeyCounter();
   private final KeyCounter retainedFinalOutputs = new KeyCounter();
   private final KeyCounter pendingRequesterOutputs = new KeyCounter();
   private final PendingRequesterOutputWarning pendingRequesterOutputWarning = new PendingRequesterOutputWarning();
   private final LoopSeedLedgerBook loopSeedLedgers = new LoopSeedLedgerBook();
   @Nullable
   private Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob job;
   @Nullable
   private CompoundTag pendingJobTag;
   @Nullable
   private CompoundTag pendingOverloadTag;
   private long lastModifiedOnTick = TickHandler.instance().getCurrentTick();
   private long waitingKeysModifiedOnTick = TickHandler.instance().getCurrentTick();
   private long schedulerTick = Long.MIN_VALUE;
   private long batchTick = Long.MIN_VALUE;
   private int wheelCursor;
   @Nullable
   private IPatternDetails preferredTask;
   private boolean queueRebuildNeeded = true;
   private boolean cantStoreItems;
   private boolean batchingStatusChanges;
   private boolean seedReturnQuotaFinalized;

   public Ae2LtTimeWheelCraftingCpuLogic(TimeWheelCraftingCPU cpu) {
      this.cpu = cpu;
   }

   public ICraftingSubmitResult trySubmitJob(IGrid grid, ICraftingPlan plan, IActionSource src, @Nullable ICraftingRequester requester) {
      this.resolvePendingLoad();
      if (this.job == null && this.pendingJobTag == null) {
         if (!this.cpu.isActive()) {
            return CraftingSubmitResult.CPU_OFFLINE;
         } else if (this.cpu.getAvailableStorage() < plan.bytes()) {
            return CraftingSubmitResult.CPU_TOO_SMALL;
         } else {
            if (!this.inventory.list.isEmpty()) {
               AELog.warn("Time wheel crafting CPU inventory is not empty yet a job was submitted.", new Object[0]);
            }

            LoopCraftingPlan loopPlan = plan instanceof LoopCraftingPlan loop ? loop : null;
            KeyCounter seedRequirements = loopPlan != null ? copyToCounter(loopPlan.totalReusableSeeds()) : new KeyCounter();
            List<LoopCraftingPlan.HostReusableSeedAllocation> hostSeedAllocations = loopPlan != null ? loopPlan.hostReusableSeedAllocations() : List.of();
            Integer playerId = src.player().map(p -> p instanceof ServerPlayer serverPlayer ? IPlayerRegistry.getPlayerId(serverPlayer) : null).orElse(null);
            UUID craftId = UUID.randomUUID();
            CraftingLink linkCpu = new CraftingLink(CraftingCpuHelper.generateLinkData(craftId, requester == null, false), this.cpu);
            Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob candidateJob = new Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob(
               plan, this::postChange, linkCpu, playerId
            );
            this.loopSeedLedgers.initialize(candidateJob.loopPatterns());
            KeyCounter adjustedUsedItems = copyCounter(plan.usedItems());
            KeyCounter hostSeeds = new KeyCounter();
            boolean hostShortfall = false;

            for (LoopCraftingPlan.HostReusableSeedAllocation allocation : hostSeedAllocations) {
               long requested = allocation.amount();
               AEKey extractionKey = allocation.bootstrap() ? allocation.actualKey() : allocation.plannedKey();
               KeyCounter extractedVariants = this.cpu
                  .getHost()
                  .extractReusableSeedVariants(
                     extractionKey,
                     requested,
                     actualx -> allocation.bootstrap()
                           ? allocation.actualKey().equals(actualx)
                           : loopPlan != null && loopPlan.acceptsReusableSeedVariant(allocation, actualx),
                     Actionable.MODULATE
                  );
               KeyCounter offered = new KeyCounter();
               long offeredAmount = 0L;

               for (Entry<AEKey> actual : extractedVariants) {
                  long amount = Math.min(actual.getLongValue(), requested - offeredAmount);
                  if (amount > 0L) {
                     offered.add((AEKey)actual.getKey(), amount);
                     offeredAmount = addSaturated(offeredAmount, amount);
                  }

                  long surplus = actual.getLongValue() - Math.max(0L, amount);
                  if (surplus > 0L) {
                     long returned = this.cpu.getHost().insertReusableSeed((AEKey)actual.getKey(), surplus, Actionable.MODULATE);
                     long held = surplus - Math.max(0L, returned);
                     if (held > 0L) {
                        this.inventory.insert((AEKey)actual.getKey(), held, Actionable.MODULATE);
                        hostSeeds.add((AEKey)actual.getKey(), held);
                     }
                  }
               }

               KeyCounter acceptedVariants = allocation.bootstrap()
                  ? offered
                  : this.loopSeedLedgers
                     .assignHostVariantsForGroup(allocation.reusableSeedGroupId(), allocation.sharedPool(), allocation.plannedKey(), offered);
               long extracted = 0L;

               for (Entry<AEKey> actual : offered) {
                  long accepted = Math.min(actual.getLongValue(), acceptedVariants.get((AEKey)actual.getKey()));
                  if (accepted > 0L) {
                     this.inventory.insert((AEKey)actual.getKey(), accepted, Actionable.MODULATE);
                     hostSeeds.add((AEKey)actual.getKey(), accepted);
                     extracted = addSaturated(extracted, accepted);
                  }

                  long rejected = actual.getLongValue() - accepted;
                  if (rejected > 0L) {
                     long returned = this.cpu.getHost().insertReusableSeed((AEKey)actual.getKey(), rejected, Actionable.MODULATE);
                     if (returned < rejected) {
                        long held = rejected - Math.max(0L, returned);
                        this.inventory.insert((AEKey)actual.getKey(), held, Actionable.MODULATE);
                        hostSeeds.add((AEKey)actual.getKey(), held);
                     }
                  }
               }

               if (extracted < requested) {
                  adjustedUsedItems.add(allocation.bootstrap() ? allocation.actualKey() : allocation.plannedKey(), requested - extracted);
                  hostShortfall = true;
               }
            }

            ICraftingPlan extractionPlan = (ICraftingPlan)(hostShortfall
               ? new Ae2LtTimeWheelCraftingCpuLogic.UsedItemsOverridePlan(plan, adjustedUsedItems)
               : plan);
            GenericStack missingIngredient = CraftingCpuHelper.tryExtractInitialItems(extractionPlan, grid, this.inventory, src);
            if (missingIngredient != null) {
               this.rollbackHostSeeds(hostSeeds);
               this.loopSeedLedgers.clear();
               return CraftingSubmitResult.missingIngredient(missingIngredient);
            } else {
               this.job = candidateJob;
               this.seedReturnQuota.clear();
               this.retainedFinalOutputs.clear();
               this.pendingRequesterOutputs.clear();
               this.pendingRequesterOutputWarning.reset();

               for (Entry<AEKey> entry : seedRequirements) {
                  this.seedReturnQuota.add((AEKey)entry.getKey(), entry.getLongValue());
               }

               this.seedReturnQuotaFinalized = false;
               this.patternPowerCache.clear();
               this.markWaitingKeysChanged();
               this.cpu.updateOutput(plan.finalOutput());
               this.cpu.markDirty();
               this.rebuildTaskWheel();
               this.notifyJobOwner(this.job, Status.STARTED);
               if (requester != null) {
                  CraftingLink linkReq = new CraftingLink(CraftingCpuHelper.generateLinkData(craftId, false, true), requester);
                  CraftingService craftingService = (CraftingService)grid.getCraftingService();
                  craftingService.addLink(linkCpu);
                  craftingService.addLink(linkReq);
                  return CraftingSubmitResult.successful(linkReq);
               } else {
                  return CraftingSubmitResult.successful(null);
               }
            }
         }
      } else {
         return CraftingSubmitResult.CPU_BUSY;
      }
   }

   public void tickCraftingLogic(IEnergyService energyService, CraftingService craftingService) {
      this.standaloneDispatchSchedule.beginTick(TickHandler.instance().getCurrentTick());

      try {
         this.tickCraftingLogic(
            energyService,
            craftingService,
            this.cpu.getSuccessfulDispatchesPerTick(),
            this.cpu.hasUnboundedBatch() ? Long.MAX_VALUE : this.cpu.getMaxCopiesPerTick(),
            this.standaloneDispatchSchedule
         );
      } finally {
         this.finishPhysicalSchedulingTick();
      }
   }

   public Ae2LtTimeWheelCraftingCpuLogic.TickUsage tickCraftingLogic(IEnergyService energyService, CraftingService craftingService, int maxOps, long maxCopies) {
      this.standaloneDispatchSchedule.beginTick(TickHandler.instance().getCurrentTick());

      Ae2LtTimeWheelCraftingCpuLogic.TickUsage var6;
      try {
         var6 = this.tickCraftingLogic(energyService, craftingService, maxOps, maxCopies, this.standaloneDispatchSchedule);
      } finally {
         this.finishPhysicalSchedulingTick();
      }

      return var6;
   }

   public Ae2LtTimeWheelCraftingCpuLogic.TickUsage tickCraftingLogic(
      IEnergyService energyService, CraftingService craftingService, int maxOps, long maxCopies, TickProviderDispatchSchedule dispatchSchedule
   ) {
      this.resolvePendingLoad();
      if (this.pendingJobTag != null) {
         return Ae2LtTimeWheelCraftingCpuLogic.TickUsage.EMPTY;
      } else if (!this.cpu.isActive()) {
         return Ae2LtTimeWheelCraftingCpuLogic.TickUsage.EMPTY;
      } else {
         long now = TickHandler.instance().getCurrentTick();
         if (now != this.batchTick) {
            this.batchTick = now;
            this.batchedByTask.clear();
            this.nonBatchTasksThisTick.clear();
         }

         this.cantStoreItems = false;
         if (this.job == null) {
            this.storeItems();
            if (!this.inventory.list.isEmpty()) {
               this.cantStoreItems = true;
            }

            return Ae2LtTimeWheelCraftingCpuLogic.TickUsage.EMPTY;
         } else {
            Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob = this.job;
            if (activeJob.softCancelling) {
               this.finishSoftCancelIfReady(activeJob);
               return Ae2LtTimeWheelCraftingCpuLogic.TickUsage.EMPTY;
            } else if (activeJob.link.isCanceled()) {
               if (!activeJob.softCancelling) {
                  if (this.hasReusableSeedPattern(activeJob)) {
                     this.beginSoftCancel(activeJob);
                  } else {
                     this.cancel();
                  }
               }

               return Ae2LtTimeWheelCraftingCpuLogic.TickUsage.EMPTY;
            } else {
               this.flushPendingRequesterOutputs(activeJob);
               if (this.job != activeJob) {
                  return Ae2LtTimeWheelCraftingCpuLogic.TickUsage.EMPTY;
               } else if (activeJob.link.isCanceled()) {
                  if (this.hasReusableSeedPattern(activeJob)) {
                     this.beginSoftCancel(activeJob);
                  } else {
                     this.cancel();
                  }

                  return Ae2LtTimeWheelCraftingCpuLogic.TickUsage.EMPTY;
               } else {
                  this.flushUnusedRetainedFinalOutputs(activeJob);
                  if (this.job == activeJob && !activeJob.link.isCanceled()) {
                     this.recoverTerminalFinalOutputFromInventory(activeJob);
                     if (this.job != activeJob || activeJob.link.isCanceled()) {
                        return Ae2LtTimeWheelCraftingCpuLogic.TickUsage.EMPTY;
                     } else if (activeJob.remainingAmount <= 0L) {
                        this.finishSuccessfulIfReady(activeJob);
                        return Ae2LtTimeWheelCraftingCpuLogic.TickUsage.EMPTY;
                     } else if (activeJob.suspended) {
                        return Ae2LtTimeWheelCraftingCpuLogic.TickUsage.EMPTY;
                     } else {
                        Level level = this.cpu.getLevel();
                        if (level == null) {
                           return Ae2LtTimeWheelCraftingCpuLogic.TickUsage.EMPTY;
                        } else {
                           int remainingOperations = Math.min(Math.max(0, maxOps), this.cpu.getSuccessfulDispatchesPerTick());
                           long remainingCopies = Math.min(
                              Math.max(0L, maxCopies), this.cpu.hasUnboundedBatch() ? Long.MAX_VALUE : this.cpu.getMaxCopiesPerTick()
                           );
                           return this.executeCraftingBudgeted(remainingOperations, remainingCopies, craftingService, energyService, level, dispatchSchedule);
                        }
                     }
                  } else {
                     return Ae2LtTimeWheelCraftingCpuLogic.TickUsage.EMPTY;
                  }
               }
            }
         }
      }
   }

   public int executeCrafting(int maxOps, CraftingService craftingService, IEnergyService energyService, Level level) {
      this.standaloneDispatchSchedule.beginTick(TickHandler.instance().getCurrentTick());

      int var5;
      try {
         var5 = this.executeCraftingBudgeted(
               maxOps,
               this.cpu.hasUnboundedBatch() ? Long.MAX_VALUE : this.cpu.getMaxCopiesPerTick(),
               craftingService,
               energyService,
               level,
               this.standaloneDispatchSchedule
            )
            .successfulDispatches();
      } finally {
         this.finishPhysicalSchedulingTick();
      }

      return var5;
   }

   private Ae2LtTimeWheelCraftingCpuLogic.TickUsage executeCraftingBudgeted(
      int maxOps,
      long requestedCopyLimit,
      CraftingService craftingService,
      IEnergyService energyService,
      Level level,
      TickProviderDispatchSchedule dispatchSchedule
   ) {
      Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob = this.job;
      if (activeJob != null && maxOps > 0 && requestedCopyLimit > 0L) {
         this.prepareScheduler(activeJob);
         int usedOps = 0;
         long usedCopies = 0L;
         long cpuCopyLimit = this.cpu.hasUnboundedBatch() ? Long.MAX_VALUE : this.cpu.getMaxCopiesPerTick();
         long copyLimit = Math.min(cpuCopyLimit, requestedCopyLimit);
         int probes = 0;
         int probeBudget = (int)Math.min(Math.max(1024L, (long)maxOps * 2L), 262144L);
         this.beginStatusChangeBatch();

         try {
            while (usedOps < maxOps && usedCopies < copyLimit && probes < probeBudget) {
               IPatternDetails details = this.pollDueTask(activeJob);
               if (details == null) {
                  break;
               }

               Ae2LtTimeWheelCraftingCpuLogic.TaskProgress task = activeJob.tasks.get(details);
               if (task != null && task.value > 0L) {
                  probes++;
                  int remainingOps = maxOps - usedOps;
                  long remainingCopies = copyLimit == Long.MAX_VALUE ? Long.MAX_VALUE : copyLimit - usedCopies;
                  BatchExecutor.BatchRunResult batchResult = this.runBatchForTask(
                     details, remainingOps, remainingCopies, craftingService, energyService, level, dispatchSchedule
                  );
                  if (batchResult.consumedCpuOps() > 0) {
                     usedOps += batchResult.consumedCpuOps();
                     usedCopies = saturatingAdd(usedCopies, batchResult.dispatchedCopies());
                     this.preferTaskWhilePending(activeJob, details);
                     this.rescheduleIfStillPending(activeJob, details, 0);
                  } else {
                     int ordinaryBudget = (int)Math.min((long)remainingOps, remainingCopies);
                     Ae2LtTimeWheelCraftingCpuLogic.BulkPush bulk = this.pushBulkForTask(
                        activeJob, task, details, ordinaryBudget, craftingService, energyService, dispatchSchedule
                     );
                     if (bulk != null) {
                        usedOps += bulk.dispatched();
                        usedCopies = saturatingAdd(usedCopies, (long)bulk.dispatched());
                        if (bulk.dispatched() > 0) {
                           this.preferTaskWhilePending(activeJob, details);
                        }

                        this.rescheduleIfStillPending(activeJob, details, bulk.retryDelayTicks());
                     } else {
                        Ae2LtTimeWheelCraftingCpuLogic.DispatchOutcome outcome = this.pushOnePattern(
                           activeJob, task, details, craftingService, energyService, level, dispatchSchedule
                        );
                        if (outcome == Ae2LtTimeWheelCraftingCpuLogic.DispatchOutcome.PUSHED) {
                           usedOps++;
                           usedCopies = saturatingAdd(usedCopies, 1L);
                           this.unparkTask(details);
                           this.preferTaskWhilePending(activeJob, details);
                           this.rescheduleIfStillPending(activeJob, details, 0);
                        } else {
                           this.rescheduleFailedTask(activeJob, details, outcome);
                        }
                     }
                  }
               } else {
                  this.removeTask(activeJob, details);
                  this.postPatternOutputsChange(details);
               }
            }
         } finally {
            this.endStatusChangeBatch();
         }

         if (this.job == activeJob && activeJob.virtualCompletionRequested) {
            this.finishVirtualCompletionIfRequested(activeJob);
         }

         if (this.job == activeJob) {
            this.flushUnusedRetainedFinalOutputs(activeJob);
         }

         return new Ae2LtTimeWheelCraftingCpuLogic.TickUsage(usedOps, usedCopies);
      } else {
         return Ae2LtTimeWheelCraftingCpuLogic.TickUsage.EMPTY;
      }
   }

   private BatchExecutor.BatchRunResult runBatchForTask(
      IPatternDetails details,
      int remainingOps,
      long remainingCopies,
      CraftingService craftingService,
      IEnergyService energyService,
      Level level,
      TickProviderDispatchSchedule dispatchSchedule
   ) {
      Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob = this.job;
      if (activeJob == null) {
         return BatchExecutor.BatchRunResult.EMPTY;
      } else if (CraftingPatternDelegates.forProviderLookup(details) instanceof OverloadedProviderOnlyPatternDetails) {
         this.nonBatchTasksThisTick.add(details);
         return BatchExecutor.BatchRunResult.EMPTY;
      } else {
         if (details instanceof ExecuteLoopPattern loop && loop.requiresActualSeedKeyTracking()) {
            this.nonBatchTasksThisTick.add(details);
            return BatchExecutor.BatchRunResult.EMPTY;
         }

         if (this.nonBatchTasksThisTick.contains(details)) {
            return BatchExecutor.BatchRunResult.EMPTY;
         } else {
            BatchExecutor.BatchRunResult result = BatchExecutor.runBatchOnly(
               remainingOps,
               BatchCpuAccounting.Mode.SUCCESSFUL_DISPATCH,
               craftingService,
               energyService,
               level,
               this.scratchBatchJobView.bind(activeJob, details),
               this.inventory,
               this.batchedByTask,
               () -> {
                  this.cpu.markDirty();
                  this.postPatternOutputsChange(details);
               },
               this.reservedSeedStock(details),
               1,
               remainingCopies,
               this.cpu.hasUnboundedBatch(),
               dispatchSchedule
            );
            if (result.dispatchedCopies() > 0L) {
               this.batchedByTask.remove(details);
               boolean sharedSeedBatch = (details instanceof ExecuteLoopPattern loop ? loop.delegate() : details) instanceof SharedBatchInputPattern;
               this.recordLoopPatternDispatch(details, result.dispatchedCopies(), sharedSeedBatch);
            }

            if (!result.shouldRetryBatchThisTick()) {
               this.nonBatchTasksThisTick.add(details);
            }

            return result;
         }
      }
   }

   private Ae2LtTimeWheelCraftingCpuLogic.DispatchOutcome pushOnePattern(
      Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob,
      Ae2LtTimeWheelCraftingCpuLogic.TaskProgress task,
      IPatternDetails details,
      CraftingService craftingService,
      IEnergyService energyService,
      Level level,
      TickProviderDispatchSchedule dispatchSchedule
   ) {
      if (this.hasAmbiguousOverloadOutput(details)) {
         return Ae2LtTimeWheelCraftingCpuLogic.DispatchOutcome.RETRY_LATER;
      } else {
         KeyCounter expectedOutputs = this.scratchExpectedOutputs;
         KeyCounter expectedContainerItems = this.scratchExpectedContainerItems;
         ICraftingInventory extractionInventory = this.reservedCraftingInventory(details);
         KeyCounter[] craftingContainer = CraftingCpuHelper.extractPatternInputs(details, extractionInventory, level, expectedOutputs, expectedContainerItems);
         if (craftingContainer == null) {
            clearScratchCounter(expectedOutputs);
            clearScratchCounter(expectedContainerItems);
            return Ae2LtTimeWheelCraftingCpuLogic.DispatchOutcome.RETRY_MISSING_INPUT;
         } else {
            boolean pushed = false;

            try {
               ExecuteLoopPattern.ActualSeedResolution actualLoopSeedResolution = details instanceof ExecuteLoopPattern loop
                  ? loop.resolveActualInputSeedUses(craftingContainer)
                  : null;
               if (actualLoopSeedResolution != null && !actualLoopSeedResolution.complete()) {
                  return Ae2LtTimeWheelCraftingCpuLogic.DispatchOutcome.RETRY_MISSING_INPUT;
               } else {
                  List<ExecuteLoopPattern.ActualSeedUse> actualLoopSeedInput = actualLoopSeedResolution != null ? actualLoopSeedResolution.uses() : null;
                  if (details instanceof ExecuteLoopPattern loopx && !this.loopSeedLedgers.canRouteActualSeedUses(loopx, actualLoopSeedInput)) {
                     return Ae2LtTimeWheelCraftingCpuLogic.DispatchOutcome.RETRY_MISSING_INPUT;
                  }

                  if (details instanceof ExecuteLoopPattern loopx) {
                     Map<UUID, KeyCounter> remainderCredits = this.loopSeedLedgers.previewRemainderCredits(loopx, 1L, false, actualLoopSeedInput);
                     if (this.hasAmbiguousOverloadOutput(details, remainderCredits)) {
                        return Ae2LtTimeWheelCraftingCpuLogic.DispatchOutcome.RETRY_LATER;
                     }
                  }

                  double patternPower = this.patternPowerFor(details, craftingContainer);

                  for (Ae2LtTimeWheelCraftingCpuLogic.ResolvedProvider resolvedProvider : this.providersForSinglePush(
                     craftingService, details, dispatchSchedule
                  )) {
                     ICraftingProvider provider = resolvedProvider.provider();
                     if (!provider.isBusy()) {
                        if (energyService.extractAEPower(patternPower, Actionable.SIMULATE, PowerMultiplier.CONFIG) < patternPower - 0.01) {
                           return Ae2LtTimeWheelCraftingCpuLogic.DispatchOutcome.RETRY_NO_POWER;
                        }

                        Ae2LtTimeWheelCraftingCpuLogic.ProviderPushResult pushResult = this.tryPushPattern(
                           resolvedProvider, craftingContainer, dispatchSchedule
                        );
                        if (pushResult.accepted()) {
                           pushed = true;
                           craftingContainer = null;
                           energyService.extractAEPower(patternPower, Actionable.MODULATE, PowerMultiplier.CONFIG);
                           Map<UUID, KeyCounter> remainderLoopCredits = this.recordLoopPatternDispatch(details, 1L, false, actualLoopSeedInput);
                           this.recordPushedPattern(activeJob, details, expectedOutputs, expectedContainerItems, 1L, remainderLoopCredits);
                           this.consumeTaskCopies(activeJob, details, 1L);
                           this.requestVirtualCompletionIfReady(activeJob, pushResult.virtualCraftingEnabled());
                           return Ae2LtTimeWheelCraftingCpuLogic.DispatchOutcome.PUSHED;
                        }
                     }
                  }

                  return Ae2LtTimeWheelCraftingCpuLogic.DispatchOutcome.RETRY_SOON;
               }
            } finally {
               if (!pushed && craftingContainer != null) {
                  CraftingCpuHelper.reinjectPatternInputs(extractionInventory, craftingContainer);
               }

               clearScratchCounter(expectedOutputs);
               clearScratchCounter(expectedContainerItems);
            }
         }
      }
   }

   @Nullable
   private Ae2LtTimeWheelCraftingCpuLogic.BulkPush pushBulkForTask(
      Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob,
      Ae2LtTimeWheelCraftingCpuLogic.TaskProgress task,
      IPatternDetails details,
      int maxCopies,
      CraftingService craftingService,
      IEnergyService energyService,
      TickProviderDispatchSchedule dispatchSchedule
   ) {
      if (CraftingPatternDelegates.forProviderLookup(details) instanceof OverloadedProviderOnlyPatternDetails) {
         return null;
      } else if (task.value > 0L && maxCopies > 0) {
         if (details instanceof ExecuteLoopPattern loop && loop.requiresActualSeedKeyTracking()) {
            return null;
         }

         Iterator<Ae2LtTimeWheelCraftingCpuLogic.ResolvedProvider> providers = this.providersForSinglePush(craftingService, details, dispatchSchedule)
            .iterator();
         Ae2LtTimeWheelCraftingCpuLogic.ResolvedProvider firstProvider = this.nextFreeProvider(providers);
         if (firstProvider == null) {
            return new Ae2LtTimeWheelCraftingCpuLogic.BulkPush(0, 1);
         } else {
            int budget = (int)Math.min(task.value, (long)maxCopies);
            ParallelBatchCpuHelper.BulkResult result = ParallelBatchCpuHelper.bulkExtract(
               details, this.inventory, (long)budget, false, this.reservedSeedStock(details)
            );
            if (result == null) {
               return null;
            } else {
               int actual = (int)result.actualCopies;
               KeyCounter[] pending = ParallelBatchCpuHelper.cloneSingleCopy(result);
               double powerOne = this.patternPowerFor(details, pending);
               int affordable = actual;
               double wanted = powerOne * (double)actual;
               if (wanted > 0.0) {
                  double avail = energyService.extractAEPower(wanted, Actionable.SIMULATE, PowerMultiplier.CONFIG);
                  if (avail < wanted - 0.01) {
                     affordable = (int)Math.min((long)actual, (long)Math.floor(avail / powerOne));
                  }
               }

               if (affordable <= 0) {
                  ParallelBatchCpuHelper.reinject(result, (long)actual, this.inventory);
                  return new Ae2LtTimeWheelCraftingCpuLogic.BulkPush(0, 4);
               } else {
                  int dispatched = 0;
                  boolean freeProviderRejected = false;
                  boolean lastDispatchedWasVirtual = false;

                  try {
                     for (Ae2LtTimeWheelCraftingCpuLogic.ResolvedProvider resolvedProvider = firstProvider;
                        resolvedProvider != null && dispatched < affordable;
                        resolvedProvider = this.nextFreeProvider(providers)
                     ) {
                        for (ICraftingProvider provider = resolvedProvider.provider(); dispatched < affordable && !provider.isBusy(); dispatched++) {
                           if (pending == null) {
                              pending = ParallelBatchCpuHelper.cloneSingleCopy(result);
                           }

                           Ae2LtTimeWheelCraftingCpuLogic.ProviderPushResult pushResult = this.tryPushPattern(resolvedProvider, pending, dispatchSchedule);
                           if (!pushResult.accepted()) {
                              freeProviderRejected = true;
                              break;
                           }

                           lastDispatchedWasVirtual = pushResult.virtualCraftingEnabled();
                           pending = null;
                           energyService.extractAEPower(powerOne, Actionable.MODULATE, PowerMultiplier.CONFIG);
                           ParallelBatchCpuHelper.markDispatched(result, 1L);
                        }
                     }
                  } finally {
                     int leftover = actual - dispatched;
                     if (leftover > 0) {
                        ParallelBatchCpuHelper.reinject(result, (long)leftover, this.inventory);
                     }
                  }

                  if (dispatched > 0) {
                     Ae2LtTimeWheelCraftingCpuLogic.SingleTaskBatchJobView jobView = this.scratchBatchJobView.bind(activeJob, details);
                     ParallelBatchCpuHelper.registerExpectedOutputs(jobView, details, result, (long)dispatched);
                     this.recordLoopPatternDispatch(details, (long)dispatched, false);
                     this.consumeTaskCopies(activeJob, details, (long)dispatched);
                     this.requestVirtualCompletionIfReady(activeJob, lastDispatchedWasVirtual);
                     this.cpu.markDirty();
                     return new Ae2LtTimeWheelCraftingCpuLogic.BulkPush(dispatched, affordable < actual ? 4 : 0);
                  } else {
                     return freeProviderRejected ? new Ae2LtTimeWheelCraftingCpuLogic.BulkPush(0, 1) : new Ae2LtTimeWheelCraftingCpuLogic.BulkPush(0, 1);
                  }
               }
            }
         }
      } else {
         return new Ae2LtTimeWheelCraftingCpuLogic.BulkPush(0, 1);
      }
   }

   @Nullable
   private Ae2LtTimeWheelCraftingCpuLogic.ResolvedProvider nextFreeProvider(Iterator<Ae2LtTimeWheelCraftingCpuLogic.ResolvedProvider> providers) {
      while (providers.hasNext()) {
         Ae2LtTimeWheelCraftingCpuLogic.ResolvedProvider resolved = providers.next();
         if (!resolved.provider().isBusy()) {
            return resolved;
         }
      }

      return null;
   }

   private Ae2LtTimeWheelCraftingCpuLogic.ProviderPushResult tryPushPattern(
      Ae2LtTimeWheelCraftingCpuLogic.ResolvedProvider resolvedProvider, KeyCounter[] inputs, TickProviderDispatchSchedule dispatchSchedule
   ) {
      ICraftingProvider provider = resolvedProvider.provider();

      try {
         boolean accepted;
         try (ExtendedAePlusVirtualCraftingCompat.DispatchScope ignored = ExtendedAePlusVirtualCraftingCompat.enterTimeWheelProviderPush()) {
            accepted = provider.pushPattern(resolvedProvider.pattern(), inputs);
         }

         if (!accepted) {
            dispatchSchedule.recordFailure(resolvedProvider.pattern(), provider);
            return Ae2LtTimeWheelCraftingCpuLogic.ProviderPushResult.REJECTED;
         }
      } catch (Throwable var11) {
         AELog.warn("[ae2lt] ICraftingProvider %s threw during pushPattern; blocking this pattern for the current tick. %s", new Object[]{provider, var11});
         dispatchSchedule.recordFailure(resolvedProvider.pattern(), provider);
         return Ae2LtTimeWheelCraftingCpuLogic.ProviderPushResult.REJECTED;
      }

      dispatchSchedule.recordSuccess(resolvedProvider.pattern(), provider);
      return new Ae2LtTimeWheelCraftingCpuLogic.ProviderPushResult(true, ExtendedAePlusVirtualCraftingCompat.isVirtualCraftingEnabled(provider));
   }

   public long insert(AEKey what, long amount, Actionable type) {
      Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob = this.job;
      if (what == null || activeJob == null || amount <= 0L) {
         return 0L;
      } else if (!activeJob.waitingKeys.contains(what) && !OverloadCpuStateManager.INSTANCE.hasAnyPending(this)) {
         if (type == Actionable.MODULATE) {
            this.finishSuccessfulIfReady(activeJob);
         }

         return 0L;
      } else {
         long returned = 0L;
         long remaining = amount;
         long simulatedExactOverload = 0L;
         long simulatedStrictPrefix = 0L;
         long exactOverloadWaiting = OverloadCpuStateManager.INSTANCE.getRemainingForExactKey(this, what);
         if (exactOverloadWaiting > 0L) {
            long strictPrefix = OverloadInsertAccounting.strictPrefixBeforeExactOverload(amount, activeJob.waitingFor.list.get(what), exactOverloadWaiting);
            if (strictPrefix > 0L) {
               returned += this.acceptStrictWaitingItem(activeJob, what, strictPrefix, type);
               remaining = amount - strictPrefix;
               if (type == Actionable.SIMULATE) {
                  simulatedStrictPrefix = strictPrefix;
               }

               if (this.job != activeJob || remaining <= 0L) {
                  if (type == Actionable.MODULATE && this.job == activeJob) {
                     this.finishSuccessfulIfReady(activeJob);
                  }

                  return returned;
               }
            }
         }

         if (OverloadCpuStateManager.INSTANCE.hasExactPending(this, what)) {
            Ae2LtTimeWheelCraftingCpuLogic.OverloadInsert overload = this.acceptOverloadWaitingItem(activeJob, what, remaining, type);
            returned += overload.accepted();
            remaining -= overload.claimed();
            if (type == Actionable.SIMULATE) {
               simulatedExactOverload = overload.claimed();
            }

            if (this.job != activeJob || remaining <= 0L) {
               if (type == Actionable.MODULATE && this.job == activeJob) {
                  this.finishSuccessfulIfReady(activeJob);
               }

               return returned;
            }
         }

         long simulatedOverlap = addSaturated(simulatedExactOverload, simulatedStrictPrefix);
         long strictProbeAmount = OverloadInsertAccounting.strictProbeAmount(remaining, simulatedOverlap);
         long strictMatched = activeJob.waitingFor.extract(what, strictProbeAmount, Actionable.SIMULATE);
         strictMatched = OverloadInsertAccounting.strictMatchAfterExactOverload(remaining, strictMatched, simulatedOverlap);
         long acceptedStrict = Math.min(remaining, strictMatched);
         if (acceptedStrict > 0L) {
            long accepted = this.acceptStrictWaitingItem(activeJob, what, acceptedStrict, type);
            returned += accepted;
         }

         remaining -= strictMatched;
         if (remaining > 0L
            && OverloadInsertAccounting.mayClaimOverloadRemainder(simulatedExactOverload)
            && OverloadCpuStateManager.INSTANCE.hasAnyPending(this)) {
            Ae2LtTimeWheelCraftingCpuLogic.OverloadInsert overloadx = this.acceptOverloadWaitingItem(activeJob, what, remaining, type);
            returned += overloadx.accepted();
            if (type == Actionable.MODULATE && this.job == activeJob) {
               this.finishSuccessfulIfReady(activeJob);
            }

            return returned;
         } else {
            if (type == Actionable.MODULATE) {
               this.finishSuccessfulIfReady(activeJob);
            }

            return returned;
         }
      }
   }

   private Ae2LtTimeWheelCraftingCpuLogic.OverloadInsert acceptOverloadWaitingItem(
      Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, AEKey what, long amount, Actionable type
   ) {
      if (amount <= 0L) {
         return Ae2LtTimeWheelCraftingCpuLogic.OverloadInsert.EMPTY;
      } else {
         OverloadClaimResult preview = OverloadCpuStateManager.INSTANCE
            .claim(this, what, amount, Actionable.SIMULATE, (consumer, expected) -> this.loopSeedLedgers.acceptsReturnedVariant(consumer, expected, what));
         if (!preview.claimedAnything()) {
            return Ae2LtTimeWheelCraftingCpuLogic.OverloadInsert.EMPTY;
         } else {
            long retainedRequester = 0L;
            long requesterAccepted = 0L;
            long deferredRequester = 0L;
            OverloadClaimResult limited = preview;
            if (!activeJob.softCancelling && preview.claimedForRequester() > 0L) {
               retainedRequester = this.retainableFinalOutputAmount(activeJob, what, preview.claimedForRequester());
               long requesterLimit = Math.min(Math.max(0L, preview.claimedForRequester() - retainedRequester), activeJob.remainingAmount);
               requesterAccepted = requesterLimit > 0L ? activeJob.link.insert(what, requesterLimit, type) : 0L;
               boolean fallsThroughToNetwork = activeJob.link.isStandalone();
               long requesterCompleted = FinalOutputAccounting.completedAmount(fallsThroughToNetwork, requesterLimit, requesterAccepted);
               deferredRequester = FinalOutputAccounting.deferredAmount(fallsThroughToNetwork, requesterLimit, requesterAccepted);
               limited = preview.partitionRequester(requesterCompleted, requesterCompleted);
            }

            OverloadClaimResult claims = limited;
            if (type == Actionable.MODULATE) {
               claims = OverloadCpuStateManager.INSTANCE.commitPreview(this, limited);
            }

            if (!claims.claimedAnything()) {
               return Ae2LtTimeWheelCraftingCpuLogic.OverloadInsert.EMPTY;
            } else {
               long accepted = 0L;
               if (type == Actionable.MODULATE) {
                  this.deductClaimedWaitingFor(activeJob, claims);
                  this.rekeyOverloadReusableSeeds(what, claims);
                  if (activeJob.softCancelling) {
                     long claimed = claims.claimedAmount();
                     decrementItems(activeJob.timeTracker, claimed, what.getType());
                     this.inventory.insert(what, claimed, Actionable.MODULATE);
                     accepted += claimed;
                     if (activeJob.waitingKeys.isEmpty() && !OverloadCpuStateManager.INSTANCE.hasAnyPending(this)) {
                        this.finishSoftCancelIfReady(activeJob);
                     }
                  } else {
                     long publicInventory = overloadPublicInventory(claims);
                     retainedRequester = Math.min(retainedRequester, publicInventory);
                     long inventoryAccepted = this.applyInventoryClaims(activeJob, what, claims);
                     this.markRetainedRequesterClaim(what, retainedRequester);
                     long deferredCommitted = Math.min(deferredRequester, Math.max(0L, publicInventory - retainedRequester));
                     if (deferredCommitted > 0L && this.job == activeJob && !activeJob.link.isCanceled()) {
                        this.pendingRequesterOutputs.add(what, deferredCommitted);
                     }

                     this.applyRequesterClaims(activeJob, what, claims);
                     accepted += FinalOutputAccounting.physicallyAcceptedAmount(inventoryAccepted, claims.claimedForRequester(), requesterAccepted);
                  }

                  this.cpu.markDirty();
               } else {
                  accepted += FinalOutputAccounting.physicallyAcceptedAmount(claims.claimedForInventory(), claims.claimedForRequester(), requesterAccepted);
               }

               return new Ae2LtTimeWheelCraftingCpuLogic.OverloadInsert(claims.claimedAmount(), accepted);
            }
         }
      }
   }

   private long acceptStrictWaitingItem(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, AEKey what, long amount, Actionable type) {
      if (type == Actionable.MODULATE) {
         decrementItems(activeJob.timeTracker, amount, what.getType());
         this.extractWaitingFor(activeJob, what, amount);
         this.cpu.markDirty();
      }

      if (activeJob.softCancelling) {
         if (type == Actionable.MODULATE) {
            this.inventory.insert(what, amount, Actionable.MODULATE);
            if (activeJob.waitingKeys.isEmpty() && !OverloadCpuStateManager.INSTANCE.hasAnyPending(this)) {
               this.finishSoftCancelIfReady(activeJob);
            }
         }

         return amount;
      } else {
         long inserted = amount;
         if (what.matches(activeJob.finalOutput)) {
            inserted = this.acceptFinalOutputWithReusableSeed(activeJob, what, amount, type);
         } else if (type == Actionable.MODULATE) {
            this.inventory.insert(what, amount, Actionable.MODULATE);
            this.wakeSchedulerForReturnedInput(what);
         }

         return inserted;
      }
   }

   private long acceptFinalOutputWithReusableSeed(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, AEKey what, long amount, Actionable type) {
      long seedQuota = Math.max(this.seedReturnQuota.get(what), this.loopSeedLedgers.totalReserved(what));
      long accepted = 0L;
      long reserved = this.reserveReturnedSeed(what, amount, seedQuota, type);
      accepted += reserved;
      long remaining = amount - reserved;
      long retained = this.retainFinalOutputForLoop(activeJob, what, remaining, type);
      accepted += retained;
      remaining -= retained;
      long finalOffer = Math.min(remaining, activeJob.remainingAmount);
      long delivered = offerToRequester(activeJob, what, finalOffer, type);
      accepted += delivered;
      long completedFinalOutput = FinalOutputAccounting.completedAmount(activeJob.link.isStandalone(), finalOffer, delivered);
      long deferredRequesterOutput = FinalOutputAccounting.deferredAmount(activeJob.link.isStandalone(), finalOffer, delivered);
      if (deferredRequesterOutput > 0L) {
         if (type == Actionable.MODULATE) {
            this.inventory.insert(what, deferredRequesterOutput, Actionable.MODULATE);
            if (this.job == activeJob && !activeJob.link.isCanceled()) {
               this.pendingRequesterOutputs.add(what, deferredRequesterOutput);
            }

            this.cpu.markDirty();
         }

         accepted += deferredRequesterOutput;
      }

      long tail = remaining - finalOffer;
      if (tail > 0L) {
         if (type == Actionable.MODULATE) {
            this.inventory.insert(what, tail, Actionable.MODULATE);
            this.wakeSchedulerForReturnedInput(what);
         }

         accepted += tail;
      }

      if (type == Actionable.MODULATE && completedFinalOutput > 0L) {
         this.finishCompletedFinalOutput(activeJob, what, completedFinalOutput);
      } else if (type == Actionable.MODULATE && reserved > 0L) {
         this.wakeSchedulerForReturnedInput(what);
      }

      return accepted;
   }

   private long reserveReturnedSeed(AEKey what, long amount, long quota, Actionable type) {
      if (amount > 0L && quota > 0L) {
         long alreadyHeld = this.inventory.extract(what, Long.MAX_VALUE, Actionable.SIMULATE);
         long reserved = Math.min(amount, Math.max(0L, quota - alreadyHeld));
         if (reserved > 0L && type == Actionable.MODULATE) {
            this.inventory.insert(what, reserved, Actionable.MODULATE);
            this.wakeSchedulerForReturnedInput(what);
         }

         return reserved;
      } else {
         return 0L;
      }
   }

   private void finishCompletedFinalOutput(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, AEKey what, long completed) {
      if (this.job == activeJob && completed > 0L) {
         this.postChange(what);
         activeJob.remainingAmount = Math.max(0L, activeJob.remainingAmount - completed);
         this.capPendingRequesterOutputsToRemaining(activeJob.remainingAmount, what);
         if (activeJob.remainingAmount > 0L) {
            this.cpu.updateOutput(new GenericStack(activeJob.finalOutput.what(), activeJob.remainingAmount));
         } else {
            this.cpu.updateOutput(null);
         }
      }
   }

   private void finishSuccessfulIfReady(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob) {
      if (this.job == activeJob && !activeJob.softCancelling) {
         this.capPendingRequesterOutputsToRemaining(activeJob.remainingAmount, null);
         if (activeJob.remainingAmount <= 0L
            && activeJob.tasks.isEmpty()
            && activeJob.waitingKeys.isEmpty()
            && this.pendingRequesterOutputs.isEmpty()
            && !OverloadCpuStateManager.INSTANCE.hasAnyPending(this)) {
            this.finalizeSeedReturnQuota();
            if (this.returnReusableSeedsToHost()) {
               this.finishJob(true);
               this.cpu.updateOutput(null);
            }
         }
      }
   }

   private void requestVirtualCompletionIfReady(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, boolean virtualCraftingEnabled) {
      if (ExtendedAePlusVirtualCraftingCompat.shouldRequestCompletion(
         virtualCraftingEnabled, this.job == activeJob, activeJob.closedLoopJob, activeJob.softCancelling, activeJob.tasks.isEmpty()
      )) {
         activeJob.virtualCompletionRequested = true;
      }
   }

   private void finishVirtualCompletionIfRequested(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob) {
      if (activeJob.virtualCompletionRequested
         && ExtendedAePlusVirtualCraftingCompat.shouldRequestCompletion(
            true, this.job == activeJob, activeJob.closedLoopJob, activeJob.softCancelling, activeJob.tasks.isEmpty()
         )) {
         this.finishJob(true);
         this.cpu.updateOutput(null);
      } else {
         activeJob.virtualCompletionRequested = false;
      }
   }

   private void finishSoftCancelIfReady(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob) {
      if (this.job == activeJob && activeJob.softCancelling && activeJob.waitingKeys.isEmpty() && !OverloadCpuStateManager.INSTANCE.hasAnyPending(this)) {
         this.finalizeSeedReturnQuota();
         if (this.returnReusableSeedsToHost()) {
            this.finishJob(false);
            this.cpu.updateOutput(null);
         }
      }
   }

   private boolean returnReusableSeedsToHost() {
      if (this.seedReturnQuota.isEmpty()) {
         return true;
      } else {
         for (Entry<AEKey> seed : this.seedReturnQuota) {
            if (this.inventory.extract((AEKey)seed.getKey(), Long.MAX_VALUE, Actionable.SIMULATE) < seed.getLongValue()) {
               return false;
            }
         }

         ArrayList<GenericStack> returnedSeeds = new ArrayList<>();

         for (Entry<AEKey> seedx : this.seedReturnQuota) {
            returnedSeeds.add(new GenericStack((AEKey)seedx.getKey(), seedx.getLongValue()));
         }

         boolean changed = false;

         for (GenericStack seedx : returnedSeeds) {
            long held = this.inventory.extract(seedx.what(), Long.MAX_VALUE, Actionable.SIMULATE);
            long acceptable = this.cpu.getHost().insertReusableSeed(seedx.what(), seedx.amount(), Actionable.SIMULATE);
            long transferable = ReusableSeedStorageProgress.transferable(seedx.amount(), held, acceptable);
            if (transferable > 0L) {
               long removed = this.inventory.extract(seedx.what(), transferable, Actionable.MODULATE);
               long inserted = removed > 0L ? this.cpu.getHost().insertReusableSeed(seedx.what(), removed, Actionable.MODULATE) : 0L;
               if (inserted < removed) {
                  this.inventory.insert(seedx.what(), removed - inserted, Actionable.MODULATE);
               }

               if (inserted > 0L) {
                  removeUpTo(this.seedReturnQuota, seedx.what(), inserted);
                  changed = true;
               }
            }
         }

         if (!this.seedReturnQuota.isEmpty()) {
            this.cantStoreItems = true;
         }

         if (changed) {
            this.cpu.markDirty();
         }

         return this.seedReturnQuota.isEmpty();
      }
   }

   private void finalizeSeedReturnQuota() {
      if (!this.seedReturnQuotaFinalized) {
         this.seedReturnQuota.clear();

         for (java.util.Map.Entry<AEKey, Long> entry : this.loopSeedLedgers.positiveSnapshot().entrySet()) {
            this.seedReturnQuota.add(entry.getKey(), entry.getValue());
         }

         this.seedReturnQuotaFinalized = true;
         this.cpu.markDirty();
      }
   }

   private void finishJob(boolean success) {
      Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob = this.job;
      if (activeJob != null) {
         OverloadCpuStateManager.INSTANCE.clear(this);
         if (success) {
            activeJob.link.markDone();
         } else {
            activeJob.link.cancel();
         }

         this.clearWaitingFor(activeJob);

         for (java.util.Map.Entry<IPatternDetails, Ae2LtTimeWheelCraftingCpuLogic.TaskProgress> entry : activeJob.tasks.entrySet()) {
            this.postPatternOutputsChange(entry.getKey());
         }

         this.notifyJobOwner(activeJob, success ? Status.FINISHED : Status.CANCELLED);
         this.job = null;
         this.seedReturnQuotaFinalized = false;
         this.retainedFinalOutputs.clear();
         this.pendingRequesterOutputs.clear();
         this.pendingRequesterOutputWarning.reset();
         this.clearLoopSeedState();
         this.patternPowerCache.clear();
         this.clearTaskWheel();
         this.storeItems();
      }
   }

   public void cancel() {
      if (this.job != null) {
         if (!this.job.softCancelling && this.hasReusableSeedPattern(this.job)) {
            this.beginSoftCancel(this.job);
         } else {
            this.seedReturnQuota.clear();
            this.seedReturnQuotaFinalized = false;
            this.clearLoopSeedState();
            this.cpu.updateOutput(null);
            this.finishJob(false);
         }
      }
   }

   void tryReleaseContents() {
      this.cancel();
      if (this.job == null) {
         this.storeItems();
      }
   }

   private boolean hasReusableSeedPattern(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob) {
      return !this.seedReturnQuota.isEmpty() || activeJob.closedLoopJob;
   }

   private void beginSoftCancel(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob) {
      activeJob.softCancelling = true;
      activeJob.suspended = false;

      for (IPatternDetails details : List.copyOf(activeJob.tasks.keySet())) {
         this.removeTask(activeJob, details);
         this.postPatternOutputsChange(details);
      }

      this.clearTaskWheel();
      this.cpu.updateOutput(null);
      this.cpu.markDirty();
      if (activeJob.waitingKeys.isEmpty() && !OverloadCpuStateManager.INSTANCE.hasAnyPending(this)) {
         this.finishSoftCancelIfReady(activeJob);
      }
   }

   public void prepareForRemoval() {
      if (this.job != null) {
         this.seedReturnQuota.clear();
         this.seedReturnQuotaFinalized = false;
         this.clearLoopSeedState();
         this.finishJob(false);
      }

      this.pendingJobTag = null;
      this.pendingOverloadTag = null;
      OverloadCpuStateManager.INSTANCE.clear(this);
      this.seedReturnQuota.clear();
      this.pendingRequesterOutputs.clear();
      this.pendingRequesterOutputWarning.reset();
      this.seedReturnQuotaFinalized = false;
      this.clearLoopSeedState();
      this.clearTaskWheel();
      this.cpu.updateOutput(null);
      this.cantStoreItems = false;
   }

   public void addStoredDrops(Level level, BlockPos pos, List<ItemStack> drops) {
      this.prepareForRemoval();

      for (Entry<AEKey> entry : this.inventory.list) {
         if (entry.getLongValue() > 0L) {
            ((AEKey)entry.getKey()).addDrops(entry.getLongValue(), drops, level, pos);
         }
      }
   }

   public void clearRemovedContent() {
      this.prepareForRemoval();
      this.inventory.clear();
      this.inventory.list.removeEmptySubmaps();
   }

   public void storeItems() {
      Preconditions.checkState(this.job == null, "CPU should not have a job to prevent re-insertion when dumping items");
      if (!this.inventory.list.isEmpty()) {
         IGrid grid = this.cpu.getGrid();
         if (grid != null) {
            MEStorage storage = grid.getStorageService().getInventory();

            for (Entry<AEKey> entry : this.inventory.list) {
               this.postChange((AEKey)entry.getKey());
               long intercept = Math.min(entry.getLongValue(), this.seedReturnQuota.get((AEKey)entry.getKey()));
               long seedInserted = intercept > 0L ? this.cpu.getHost().insertReusableSeed((AEKey)entry.getKey(), intercept, Actionable.MODULATE) : 0L;
               if (seedInserted > 0L) {
                  removeUpTo(this.seedReturnQuota, (AEKey)entry.getKey(), seedInserted);
                  entry.setValue(entry.getLongValue() - seedInserted);
               }

               long inserted = storage.insert((AEKey)entry.getKey(), entry.getLongValue(), Actionable.MODULATE, this.cpu.getSrc());
               entry.setValue(entry.getLongValue() - inserted);
            }

            this.inventory.list.removeZeros();
            if (this.inventory.list.isEmpty()) {
               this.seedReturnQuota.clear();
               this.pendingRequesterOutputs.clear();
               this.clearLoopSeedState();
            }

            this.cpu.markDirty();
         }
      }
   }

   private static KeyCounter copyToCounter(Map<AEKey, Long> source) {
      KeyCounter result = new KeyCounter();

      for (java.util.Map.Entry<AEKey, Long> entry : source.entrySet()) {
         if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L) {
            result.add(entry.getKey(), entry.getValue());
         }
      }

      return result;
   }

   private Map<AEKey, Long> reservedSeedStock(IPatternDetails details) {
      UUID ownConsumer = null;
      Predicate<AEKey> allowedSeedInput = ignored -> false;
      ExecuteLoopPattern ownLoop = null;
      if (details instanceof ExecuteLoopPattern loopPattern) {
         ownLoop = loopPattern;
         ownConsumer = loopPattern.seedConsumerId();
         allowedSeedInput = loopPattern::isInputSeedKey;
      }

      boolean var10003;
      label15: {
         if (details instanceof ExecuteLoopPattern loop && loop.hasSingleSeedInputPerMember()) {
            var10003 = true;
            break label15;
         }

         var10003 = false;
      }

      final Map<AEKey, Long> ledgerReservations = this.loopSeedLedgers.reservationView(ownConsumer, allowedSeedInput, var10003);
      final ExecuteLoopPattern allowedLoop = ownLoop;
      return new AbstractMap<AEKey, Long>() {
         public Long get(Object key) {
            if (!(key instanceof AEKey aeKey)) {
               return null;
            } else {
               long reserved = ledgerReservations.getOrDefault(aeKey, 0L);
               reserved = Ae2LtTimeWheelCraftingCpuLogic.addSaturated(reserved, Ae2LtTimeWheelCraftingCpuLogic.this.pendingRequesterOutputs.get(aeKey));
               if (allowedLoop == null || !allowedLoop.isInputSeedKey(aeKey)) {
                  reserved = Ae2LtTimeWheelCraftingCpuLogic.addSaturated(reserved, Ae2LtTimeWheelCraftingCpuLogic.this.retainedFinalOutputs.get(aeKey));
               }

               return reserved > 0L ? reserved : null;
            }
         }

         @Override
         public Set<java.util.Map.Entry<AEKey, Long>> entrySet() {
            return Set.of();
         }
      };
   }

   private ICraftingInventory reservedCraftingInventory(IPatternDetails details) {
      return (ICraftingInventory)(!this.loopSeedLedgers.hasReservations() && this.retainedFinalOutputs.isEmpty() && this.pendingRequesterOutputs.isEmpty()
         ? this.inventory
         : new Ae2LtTimeWheelCraftingCpuLogic.ReservedCraftingInventory(this.inventory, this.reservedSeedStock(details)));
   }

   private Map<UUID, KeyCounter> recordLoopPatternDispatch(IPatternDetails details, long copies, boolean sharedBatch) {
      return this.recordLoopPatternDispatch(details, copies, sharedBatch, null);
   }

   private Map<UUID, KeyCounter> recordLoopPatternDispatch(
      IPatternDetails details, long copies, boolean sharedBatch, @Nullable List<ExecuteLoopPattern.ActualSeedUse> actualInputSeed
   ) {
      if (details instanceof ExecuteLoopPattern loopPattern && copies > 0L) {
         Map<UUID, KeyCounter> changedCredits = this.loopSeedLedgers.recordDispatch(loopPattern, copies, sharedBatch, actualInputSeed);
         this.consumeRetainedFinalOutput(loopPattern, copies, sharedBatch, actualInputSeed);
         this.cpu.markDirty();
         return changedCredits;
      }

      return Map.of();
   }

   private void consumeRetainedFinalOutput(
      ExecuteLoopPattern pattern, long copies, boolean sharedBatch, @Nullable List<ExecuteLoopPattern.ActualSeedUse> actualInputSeed
   ) {
      if (!this.retainedFinalOutputs.isEmpty()) {
         if (actualInputSeed != null) {
            for (ExecuteLoopPattern.ActualSeedUse use : actualInputSeed) {
               removeUpTo(this.retainedFinalOutputs, use.actual(), use.amount());
            }
         } else {
            long scale = sharedBatch ? 1L : copies;

            for (Entry<AEKey> input : pattern.inputSeed()) {
               removeUpTo(this.retainedFinalOutputs, (AEKey)input.getKey(), multiplySaturated(input.getLongValue(), scale));
            }
         }
      }
   }

   private long pendingLoopSeedDemand(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, AEKey key) {
      if (activeJob != null && key != null) {
         long demand = 0L;

         for (java.util.Map.Entry<IPatternDetails, Ae2LtTimeWheelCraftingCpuLogic.TaskProgress> task : activeJob.tasks.entrySet()) {
            Object perCopy = task.getKey();
            if (perCopy instanceof ExecuteLoopPattern) {
               ExecuteLoopPattern loop = (ExecuteLoopPattern)perCopy;
               if (task.getValue().value > 0L) {
                  long perCopyx = loop.inputSeedAmountFor(key);
                  if (perCopyx > 0L) {
                     demand = addSaturated(demand, multiplySaturated(perCopyx, task.getValue().value));
                  }
               }
            }
         }

         return demand;
      } else {
         return 0L;
      }
   }

   private long retainFinalOutputForLoop(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, AEKey what, long amount, Actionable type) {
      long retained = this.retainableFinalOutputAmount(activeJob, what, amount);
      if (retained > 0L && type == Actionable.MODULATE) {
         this.retainLoopFinalOutput(what, retained);
      }

      return retained;
   }

   private long retainableFinalOutputAmount(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, AEKey what, long amount) {
      if (amount > 0L
         && activeJob != null
         && what != null
         && activeJob.finalOutput != null
         && what.dropSecondary().equals(activeJob.finalOutput.what().dropSecondary())) {
         long demand = this.pendingLoopSeedDemand(activeJob, what);
         long alreadyRetained = 0L;

         for (Entry<AEKey> retained : this.retainedFinalOutputs) {
            if (this.sharesPendingLoopConsumer(activeJob, what, (AEKey)retained.getKey())) {
               alreadyRetained = addSaturated(alreadyRetained, retained.getLongValue());
            }
         }

         return Math.min(amount, Math.max(0L, demand - alreadyRetained));
      } else {
         return 0L;
      }
   }

   private boolean sharesPendingLoopConsumer(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, AEKey left, AEKey right) {
      if (activeJob != null && left != null && right != null) {
         if (left.equals(right)) {
            return true;
         } else {
            for (java.util.Map.Entry<IPatternDetails, Ae2LtTimeWheelCraftingCpuLogic.TaskProgress> task : activeJob.tasks.entrySet()) {
               if (task.getKey() instanceof ExecuteLoopPattern loop
                  && task.getValue().value > 0L
                  && loop.inputSeedAmountFor(left) > 0L
                  && loop.inputSeedAmountFor(right) > 0L) {
                  return true;
               }
            }

            return false;
         }
      } else {
         return false;
      }
   }

   private void retainLoopFinalOutput(AEKey what, long amount) {
      if (what != null && amount > 0L) {
         this.inventory.insert(what, amount, Actionable.MODULATE);
         this.retainedFinalOutputs.add(what, amount);
         this.wakeSchedulerForReturnedInput(what);
         this.cpu.markDirty();
      }
   }

   private void flushPendingRequesterOutputs(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob) {
      if (activeJob != null && !activeJob.link.isStandalone() && !activeJob.softCancelling) {
         this.capPendingRequesterOutputsToRemaining(activeJob.remainingAmount, null);
         if (this.pendingRequesterOutputs.isEmpty()) {
            this.pendingRequesterOutputWarning.reset();
         } else {
            ArrayList<GenericStack> pending = new ArrayList<>();

            for (Entry<AEKey> entry : this.pendingRequesterOutputs) {
               if (entry.getLongValue() > 0L) {
                  pending.add(new GenericStack((AEKey)entry.getKey(), entry.getLongValue()));
               }
            }

            for (GenericStack entryx : pending) {
               if (this.job == activeJob && !activeJob.link.isCanceled()) {
                  long held = this.inventory.extract(entryx.what(), Long.MAX_VALUE, Actionable.SIMULATE);
                  long reusableReserve = Math.max(this.seedReturnQuota.get(entryx.what()), this.loopSeedLedgers.totalReserved(entryx.what()));
                  long protectedAmount = addSaturated(reusableReserve, this.retainedFinalOutputs.get(entryx.what()));
                  long deliverable = Math.max(0L, held - protectedAmount);
                  long offer = Math.min(entryx.amount(), Math.min(deliverable, activeJob.remainingAmount));
                  if (offer <= 0L) {
                     continue;
                  }

                  long acceptable = offerToRequester(activeJob, entryx.what(), offer, Actionable.SIMULATE);
                  if (acceptable <= 0L) {
                     continue;
                  }

                  if (this.job == activeJob && !activeJob.link.isCanceled()) {
                     long removed = this.inventory.extract(entryx.what(), acceptable, Actionable.MODULATE);
                     if (removed > 0L) {
                        long delivered = offerToRequester(activeJob, entryx.what(), removed, Actionable.MODULATE);
                        if (delivered < removed) {
                           this.inventory.insert(entryx.what(), removed - delivered, Actionable.MODULATE);
                        }

                        if (this.job != activeJob) {
                           return;
                        }

                        if (delivered > 0L) {
                           removeUpTo(this.pendingRequesterOutputs, entryx.what(), delivered);
                           this.finishCompletedFinalOutput(activeJob, entryx.what(), delivered);
                           this.cpu.markDirty();
                        }
                     }
                     continue;
                  }

                  return;
               }

               return;
            }

            if (this.pendingRequesterOutputWarning.update(TickHandler.instance().getCurrentTick(), !this.pendingRequesterOutputs.isEmpty())) {
               this.cantStoreItems = true;
            }
         }
      } else {
         this.pendingRequesterOutputWarning.reset();
      }
   }

   private void capPendingRequesterOutputsToRemaining(long remainingDemand, @Nullable AEKey newlyCompletedKey) {
      if (!this.pendingRequesterOutputs.isEmpty()) {
         ArrayList<PendingRequesterOutputAccounting.Credit<AEKey>> pending = new ArrayList<>();

         for (Entry<AEKey> entry : this.pendingRequesterOutputs) {
            pending.add(new PendingRequesterOutputAccounting.Credit<>((AEKey)entry.getKey(), entry.getLongValue()));
         }

         PendingRequesterOutputAccounting.Reconciliation<AEKey> reconciliation = PendingRequesterOutputAccounting.capToRemainingDemand(
            pending, remainingDemand, newlyCompletedKey
         );
         this.pendingRequesterOutputs.clear();

         for (PendingRequesterOutputAccounting.Credit<AEKey> retained : reconciliation.retained()) {
            this.pendingRequesterOutputs.add(retained.key(), retained.amount());
         }
      }
   }

   private static long offerToRequester(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, AEKey what, long amount, Actionable type) {
      if (activeJob != null && what != null && amount > 0L) {
         long accepted = activeJob.link.insert(what, amount, type);
         return Math.min(amount, Math.max(0L, accepted));
      } else {
         return 0L;
      }
   }

   private void flushUnusedRetainedFinalOutputs(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob) {
      if (activeJob != null && !this.retainedFinalOutputs.isEmpty()) {
         ArrayList<GenericStack> retained = new ArrayList<>();

         for (Entry<AEKey> entry : this.retainedFinalOutputs) {
            if (entry.getLongValue() > 0L && this.pendingLoopSeedDemand(activeJob, (AEKey)entry.getKey()) <= 0L) {
               retained.add(new GenericStack((AEKey)entry.getKey(), entry.getLongValue()));
            }
         }

         for (GenericStack entryx : retained) {
            long held = this.inventory.extract(entryx.what(), Long.MAX_VALUE, Actionable.SIMULATE);
            long free = Math.max(0L, held - this.loopSeedLedgers.totalReserved(entryx.what()));
            long offer = Math.min(entryx.amount(), Math.min(free, activeJob.remainingAmount));
            if (offer > 0L) {
               long accepted = offerToRequester(activeJob, entryx.what(), offer, Actionable.SIMULATE);
               if (accepted > 0L) {
                  if (this.job != activeJob || activeJob.link.isCanceled()) {
                     return;
                  }

                  long removed = this.inventory.extract(entryx.what(), accepted, Actionable.MODULATE);
                  if (removed > 0L) {
                     long delivered = offerToRequester(activeJob, entryx.what(), removed, Actionable.MODULATE);
                     if (delivered < removed) {
                        this.inventory.insert(entryx.what(), removed - delivered, Actionable.MODULATE);
                     }

                     if (this.job != activeJob) {
                        return;
                     }

                     if (delivered > 0L) {
                        removeUpTo(this.retainedFinalOutputs, entryx.what(), delivered);
                        this.finishCompletedFinalOutput(activeJob, entryx.what(), delivered);
                        this.cpu.markDirty();
                     }
                  }
               }
            }
         }
      }
   }

   private void recoverTerminalFinalOutputFromInventory(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob) {
      if (activeJob != null
         && !activeJob.softCancelling
         && activeJob.remainingAmount > 0L
         && activeJob.finalOutput != null
         && activeJob.tasks.isEmpty()
         && activeJob.waitingKeys.isEmpty()
         && activeJob.pendingOutputs.isEmpty()
         && this.pendingRequesterOutputs.isEmpty()
         && !OverloadCpuStateManager.INSTANCE.hasAnyPending(this)) {
         AEKey finalKey = activeJob.finalOutput.what();
         long held = this.inventory.extract(finalKey, Long.MAX_VALUE, Actionable.SIMULATE);
         long reusableReserve = Math.max(this.seedReturnQuota.get(finalKey), this.loopSeedLedgers.totalReserved(finalKey));
         long recoverable = FinalOutputProgress.recoverableInventoryAmount(
            held, reusableReserve, this.retainedFinalOutputs.get(finalKey), activeJob.remainingAmount
         );
         if (recoverable > 0L) {
            if (activeJob.link.isStandalone()) {
               this.finishCompletedFinalOutput(activeJob, finalKey, recoverable);
               this.cpu.markDirty();
            } else {
               long acceptable = offerToRequester(activeJob, finalKey, recoverable, Actionable.SIMULATE);
               if (acceptable > 0L && this.job == activeJob && !activeJob.link.isCanceled()) {
                  long removed = this.inventory.extract(finalKey, acceptable, Actionable.MODULATE);
                  if (removed > 0L) {
                     long delivered = offerToRequester(activeJob, finalKey, removed, Actionable.MODULATE);
                     if (delivered < removed) {
                        this.inventory.insert(finalKey, removed - delivered, Actionable.MODULATE);
                     }

                     if (this.job == activeJob) {
                        if (delivered > 0L) {
                           this.finishCompletedFinalOutput(activeJob, finalKey, delivered);
                           AELog.warn(
                              "[ae2lt] Recovered %d terminal final-output units from CPU inventory for crafting job %s.",
                              new Object[]{delivered, activeJob.link.getCraftingID()}
                           );
                           this.cpu.markDirty();
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static void removeUpTo(KeyCounter counter, AEKey key, long amount) {
      if (counter != null && key != null && amount > 0L) {
         long current = Math.max(0L, counter.get(key));
         if (current <= 0L) {
            counter.remove(key);
         } else {
            long remaining = Math.max(0L, current - Math.max(0L, amount));
            if (remaining != current) {
               if (remaining <= 0L) {
                  counter.remove(key);
               } else {
                  counter.set(key, remaining);
               }
            }
         }
      }
   }

   private void clearLoopSeedState() {
      this.loopSeedLedgers.clear();
   }

   private void readLoopSeedState(CompoundTag data, Provider registries) {
      this.loopSeedLedgers.readFromNBT(data, registries);
   }

   private void writeLoopSeedState(CompoundTag data, Provider registries) {
      this.loopSeedLedgers.writeToNBT(data, registries);
   }

   private static KeyCounter readCounter(ListTag tags, Provider registries) {
      KeyCounter result = new KeyCounter();

      for (int i = 0; i < tags.size(); i++) {
         GenericStack stack = GenericStack.readTag(tags.getCompound(i));
         if (stack != null && stack.amount() > 0L) {
            result.add(stack.what(), stack.amount());
         }
      }

      return result;
   }

   private static ListTag writeCounter(KeyCounter counter, Provider registries) {
      ListTag result = new ListTag();

      for (Entry<AEKey> entry : counter) {
         if (entry.getLongValue() > 0L) {
            result.add(GenericStack.writeTag(new GenericStack((AEKey)entry.getKey(), entry.getLongValue())));
         }
      }

      return result;
   }

   private static Map<UUID, KeyCounter> readSeedCredits(ListTag tags, Provider registries) {
      LinkedHashMap<UUID, KeyCounter> result = new LinkedHashMap<>();

      for (int i = 0; i < tags.size(); i++) {
         CompoundTag creditTag = tags.getCompound(i);
         if (creditTag.hasUUID("consumer")) {
            KeyCounter items = readCounter(creditTag.getList("items", 10), registries);
            if (!items.isEmpty()) {
               result.put(creditTag.getUUID("consumer"), items);
            }
         }
      }

      return Map.copyOf(result);
   }

   private static ListTag writeSeedCredits(Map<UUID, KeyCounter> credits, Provider registries) {
      ListTag result = new ListTag();
      ArrayList<UUID> consumers = new ArrayList<>(credits.keySet());
      consumers.sort(UUID::compareTo);

      for (UUID consumer : consumers) {
         KeyCounter items = credits.get(consumer);
         if (items != null && !items.isEmpty()) {
            CompoundTag creditTag = new CompoundTag();
            creditTag.putUUID("consumer", consumer);
            creditTag.put("items", writeCounter(items, registries));
            result.add(creditTag);
         }
      }

      return result;
   }

   private static KeyCounter copyCounter(KeyCounter source) {
      KeyCounter copy = new KeyCounter();
      copy.addAll(source);
      return copy;
   }

   private void rollbackHostSeeds(KeyCounter hostSeeds) {
      for (Entry<AEKey> entry : hostSeeds) {
         long removed = this.inventory.extract((AEKey)entry.getKey(), entry.getLongValue(), Actionable.MODULATE);
         if (removed > 0L) {
            long returned = this.cpu.getHost().insertReusableSeed((AEKey)entry.getKey(), removed, Actionable.MODULATE);
            if (returned < removed) {
               this.inventory.insert((AEKey)entry.getKey(), removed - Math.max(0L, returned), Actionable.MODULATE);
            }
         }
      }
   }

   public void readFromNBT(CompoundTag data, Provider registries) {
      this.inventory.readFromNBT(data.getList("inventory", 10));
      this.job = null;
      this.pendingJobTag = null;
      this.pendingOverloadTag = null;
      OverloadCpuStateManager.INSTANCE.clear(this);
      this.seedReturnQuota.clear();
      this.retainedFinalOutputs.clear();
      this.pendingRequesterOutputs.clear();
      this.pendingRequesterOutputWarning.reset();
      this.seedReturnQuotaFinalized = data.getBoolean("reusableSeedReturnQuotaFinalized");
      this.clearLoopSeedState();
      if (data.contains("reusableSeedReturnQuota", 9)) {
         ListTag seeds = data.getList("reusableSeedReturnQuota", 10);

         for (int i = 0; i < seeds.size(); i++) {
            GenericStack stack = GenericStack.readTag(seeds.getCompound(i));
            if (stack != null && stack.amount() > 0L) {
               this.seedReturnQuota.add(stack.what(), stack.amount());
            }
         }
      }

      if (data.contains("retainedLoopFinalOutputs", 9)) {
         ListTag retained = data.getList("retainedLoopFinalOutputs", 10);

         for (int ix = 0; ix < retained.size(); ix++) {
            GenericStack stack = GenericStack.readTag(retained.getCompound(ix));
            if (stack != null && stack.amount() > 0L) {
               this.retainedFinalOutputs.add(stack.what(), stack.amount());
            }
         }
      }

      if (data.contains("pendingRequesterFinalOutputs", 9)) {
         this.pendingRequesterOutputs.addAll(readCounter(data.getList("pendingRequesterFinalOutputs", 10), registries));
      }

      this.readLoopSeedState(data, registries);
      this.clearTaskWheel();
      if (data.contains("job", 10)) {
         CompoundTag jobTag = data.getCompound("job");
         CompoundTag overloadTag = data.contains("ae2ltOverloadState", 10) ? data.getCompound("ae2ltOverloadState") : null;
         if (this.cpu.getLevel() == null) {
            this.pendingJobTag = jobTag.copy();
            this.pendingOverloadTag = overloadTag != null ? overloadTag.copy() : null;
            this.updatePendingDisplayedOutput(jobTag, registries);
         } else {
            this.restoreJobFromNBT(jobTag, overloadTag, registries);
         }
      } else {
         this.cpu.updateOutput(null);
      }
   }

   public void writeToNBT(CompoundTag data, Provider registries) {
      if (!this.inventory.list.isEmpty()) {
         data.put("inventory", this.inventory.writeToNBT());
      } else {
         data.remove("inventory");
      }

      if (!this.seedReturnQuota.isEmpty()) {
         ListTag seeds = new ListTag();

         for (Entry<AEKey> entry : this.seedReturnQuota) {
            seeds.add(GenericStack.writeTag(new GenericStack((AEKey)entry.getKey(), entry.getLongValue())));
         }

         data.put("reusableSeedReturnQuota", seeds);
      } else {
         data.remove("reusableSeedReturnQuota");
      }

      if (this.seedReturnQuotaFinalized) {
         data.putBoolean("reusableSeedReturnQuotaFinalized", true);
      } else {
         data.remove("reusableSeedReturnQuotaFinalized");
      }

      if (!this.retainedFinalOutputs.isEmpty()) {
         data.put("retainedLoopFinalOutputs", writeCounter(this.retainedFinalOutputs, registries));
      } else {
         data.remove("retainedLoopFinalOutputs");
      }

      if (!this.pendingRequesterOutputs.isEmpty()) {
         data.put("pendingRequesterFinalOutputs", writeCounter(this.pendingRequesterOutputs, registries));
      } else {
         data.remove("pendingRequesterFinalOutputs");
      }

      this.writeLoopSeedState(data, registries);
      if (this.job != null) {
         data.put("job", this.job.writeToNBT(registries));
         CompoundTag overloadTag = OverloadCpuStateManager.INSTANCE.writeToTag(this, registries);
         if (overloadTag != null) {
            data.put("ae2ltOverloadState", overloadTag);
         } else {
            data.remove("ae2ltOverloadState");
         }
      } else if (this.pendingJobTag != null) {
         data.put("job", this.pendingJobTag.copy());
         if (this.pendingOverloadTag != null) {
            data.put("ae2ltOverloadState", this.pendingOverloadTag.copy());
         } else {
            data.remove("ae2ltOverloadState");
         }
      } else {
         data.remove("job");
         data.remove("ae2ltOverloadState");
      }
   }

   public void resolvePendingLoad() {
      if (this.pendingJobTag != null) {
         Level level = this.cpu.getLevel();
         if (level != null) {
            CompoundTag jobTag = this.pendingJobTag;
            CompoundTag overloadTag = this.pendingOverloadTag;
            this.pendingJobTag = null;
            this.pendingOverloadTag = null;
            this.restoreJobFromNBT(jobTag, overloadTag, level.registryAccess());
         }
      }
   }

   private void restoreJobFromNBT(CompoundTag jobTag, @Nullable CompoundTag overloadTag, Provider registries) {
      this.job = this.readJobFromNBT(jobTag, registries);
      this.patternPowerCache.clear();
      if (this.job != null && this.job.finalOutput != null) {
         this.loopSeedLedgers.registerConsumers(this.job.loopPatterns());
         this.cpu.updateOutput(new GenericStack(this.job.finalOutput.what(), this.job.remainingAmount));
         this.markWaitingKeysChanged();
         if (overloadTag != null) {
            OverloadCpuStateManager.INSTANCE.readFromTag(this, this.job.link.getCraftingID(), overloadTag, registries);
         }

         this.rebuildTaskWheel();
      } else {
         this.cpu.updateOutput(null);
         this.finishJob(false);
      }
   }

   private void updatePendingDisplayedOutput(CompoundTag jobTag, Provider registries) {
         GenericStack finalOutput = GenericStack.readTag(jobTag.getCompound("finalOutput"));
      if (finalOutput == null) {
         this.cpu.updateOutput(null);
      } else {
         long remainingAmount = jobTag.getLong("remainingAmount");
         this.cpu.updateOutput(remainingAmount > 0L ? new GenericStack(finalOutput.what(), remainingAmount) : null);
      }
   }

   @Nullable
   private Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob readJobFromNBT(CompoundTag data, Provider registries) {
      Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob loadedJob = Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob.readFromNBT(
         data, registries, this::postChange, this.cpu
      );
      if (loadedJob == null) {
         return null;
      } else {
         IGrid grid = this.cpu.getGrid();
         if (grid != null) {
            ((CraftingService)grid.getCraftingService()).addLink(loadedJob.link);
         }

         return loadedJob;
      }
   }

   @Nullable
   public ICraftingLink getLastLink() {
      return this.job != null ? this.job.link : null;
   }

   public ListCraftingInventory getInventory() {
      return this.inventory;
   }

   public long getLastModifiedOnTick() {
      return this.lastModifiedOnTick;
   }

   public long getWaitingKeysModifiedOnTick() {
      return this.waitingKeysModifiedOnTick;
   }

   public boolean hasJob() {
      return this.job != null || this.pendingJobTag != null;
   }

   public boolean hasPersistentState() {
      return this.job != null
         || this.pendingJobTag != null
         || this.pendingOverloadTag != null
         || !this.inventory.list.isEmpty()
         || !this.seedReturnQuota.isEmpty()
         || !this.pendingRequesterOutputs.isEmpty()
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
      this.listeners.add(listener);
   }

   public void removeListener(Consumer<AEKey> listener) {
      this.listeners.remove(listener);
   }

   public long getStored(AEKey template) {
      return this.inventory.extract(template, Long.MAX_VALUE, Actionable.SIMULATE);
   }

   public long getWaitingFor(AEKey template) {
      return this.job != null ? this.job.waitingFor.extract(template, Long.MAX_VALUE, Actionable.SIMULATE) : 0L;
   }

   public void getAllWaitingFor(Set<AEKey> waitingFor) {
      if (this.job != null) {
         waitingFor.addAll(this.job.waitingKeys);
      }
   }

   public long getPendingOutputs(AEKey template) {
      return this.job != null ? this.job.pendingOutputs.get(template) : 0L;
   }

   public void getAllItems(KeyCounter out) {
      out.addAll(this.inventory.list);
      if (this.job != null) {
         out.addAll(this.job.waitingFor.list);
         out.addAll(this.job.pendingOutputs);
      }
   }

   public boolean isCantStoreItems() {
      return this.cantStoreItems;
   }

   public boolean isJobSuspended() {
      return this.job != null && this.job.suspended;
   }

   public boolean isSoftCancelling() {
      return this.job != null && this.job.softCancelling;
   }

   public void setJobSuspended(boolean suspended) {
      if (this.job != null && this.job.suspended != suspended) {
         this.job.suspended = suspended;
         this.cpu.markDirty();
      }
   }

   private Iterable<Ae2LtTimeWheelCraftingCpuLogic.ResolvedProvider> providersForSinglePush(
      CraftingService craftingService, IPatternDetails details, TickProviderDispatchSchedule dispatchSchedule
   ) {
      IPatternDetails providerPattern = CraftingPatternDelegates.forProviderLookup(details);
      IdentityHashMap<ICraftingProvider, Boolean> skipped = this.batchedByTask.get(details);
      return () -> new Iterator<Ae2LtTimeWheelCraftingCpuLogic.ResolvedProvider>() {
            private final Iterator<ICraftingProvider> raw = dispatchSchedule.candidates(craftingService, providerPattern, providerPattern).iterator();
            @Nullable
            private Ae2LtTimeWheelCraftingCpuLogic.ResolvedProvider next;

            @Override
            public boolean hasNext() {
               while (this.next == null && this.raw.hasNext()) {
                  ICraftingProvider candidate = this.raw.next();
                  if (skipped == null || !skipped.containsKey(candidate)) {
                     this.next = new Ae2LtTimeWheelCraftingCpuLogic.ResolvedProvider(candidate, providerPattern);
                  }
               }

               return this.next != null;
            }

            public Ae2LtTimeWheelCraftingCpuLogic.ResolvedProvider next() {
               if (!this.hasNext()) {
                  throw new NoSuchElementException();
               } else {
                  Ae2LtTimeWheelCraftingCpuLogic.ResolvedProvider result = this.next;
                  this.next = null;
                  return result;
               }
            }
         };
   }

   private boolean hasAmbiguousOverloadOutput(IPatternDetails details) {
      return this.hasAmbiguousOverloadOutput(details, null);
   }

   private boolean hasAmbiguousOverloadOutput(IPatternDetails details, @Nullable Map<UUID, KeyCounter> preallocatedRemainderCredits) {
      if (!(CraftingPatternDelegates.forProviderLookup(details) instanceof OverloadedProviderOnlyPatternDetails overloadDetails)) {
         return false;
      } else {
         OverloadPatternReference reference = this.overloadPatternReference(details, overloadDetails);
         if (details instanceof ExecuteLoopPattern && preallocatedRemainderCredits != null) {
            Map<Integer, OverloadReusableSeedMetadata> seedMetadata = this.reusableSeedOverloadOutputs(
               details, overloadDetails, 1L, preallocatedRemainderCredits
            );
            GenericStack[] outputs = details.getOutputs();

            for (java.util.Map.Entry<Integer, OverloadReusableSeedMetadata> entry : seedMetadata.entrySet()) {
               if (entry.getKey() < 0 || entry.getKey() >= outputs.length) {
                  return true;
               }

               AEKey planned = outputs[entry.getKey()].what();

               for (OverloadConsumerCredit credit : entry.getValue().consumerCredits()) {
                  if (!this.loopSeedLedgers.acceptsLateBoundVariantCredit(credit.consumerId(), planned)) {
                     return true;
                  }
               }
            }
         }

         return OverloadCpuStateManager.INSTANCE.hasAmbiguousOutputRegistration(this, reference, overloadDetails.overloadPatternDetailsView());
      }
   }

   private void recordPushedPattern(
      Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob,
      IPatternDetails details,
      KeyCounter expectedOutputs,
      KeyCounter expectedContainerItems,
      long copies,
      Map<UUID, KeyCounter> remainderLoopCredits
   ) {
      for (Entry<AEKey> expectedOutput : expectedOutputs) {
         this.insertWaitingFor(activeJob, (AEKey)expectedOutput.getKey(), multiplySaturated(expectedOutput.getLongValue(), copies));
      }

      for (Entry<AEKey> expectedContainerItem : expectedContainerItems) {
         long amount = multiplySaturated(expectedContainerItem.getLongValue(), copies);
         this.insertWaitingFor(activeJob, (AEKey)expectedContainerItem.getKey(), amount);
         addMaxItems(activeJob.timeTracker, amount, ((AEKey)expectedContainerItem.getKey()).getType());
      }

      this.registerOverloadExpectedOutputs(activeJob, details, copies, remainderLoopCredits);
      this.cpu.markDirty();
   }

   private void registerOverloadExpectedOutputs(
      Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, IPatternDetails details, long copies, Map<UUID, KeyCounter> remainderLoopCredits
   ) {
      if (CraftingPatternDelegates.forProviderLookup(details) instanceof OverloadedProviderOnlyPatternDetails overloadDetails && copies > 0L) {
         OverloadPatternReference reference = this.overloadPatternReference(details, overloadDetails);
         AEKey finalOutputKey = activeJob.finalOutput != null ? activeJob.finalOutput.what() : null;
         OverloadCpuStateManager.INSTANCE
            .registerExpectedOutputs(
               this,
               activeJob.link.getCraftingID(),
               reference,
               overloadDetails.overloadPatternDetailsView(),
               Arrays.asList(details.getOutputs()),
               finalOutputKey,
               copies,
               this.reusableSeedOverloadOutputs(details, overloadDetails, copies, remainderLoopCredits)
            );
         return;
      }
   }

   private OverloadPatternReference overloadPatternReference(IPatternDetails details, OverloadedProviderOnlyPatternDetails overloadDetails) {
      String identity = overloadDetails.overloadPatternIdentity();
      if (details instanceof ExecuteLoopPattern loop) {
         identity = identity + "#loop-seed:" + loop.reusableSeedGroupId();
      }

      return new OverloadPatternReference(identity, overloadDetails.overloadPatternDetailsView().sourcePattern());
   }

   private Map<Integer, OverloadReusableSeedMetadata> reusableSeedOverloadOutputs(
      IPatternDetails details, OverloadedProviderOnlyPatternDetails overloadDetails, long copies, Map<UUID, KeyCounter> remainderLoopCredits
   ) {
      if (details instanceof ExecuteLoopPattern loop && copies > 0L) {
         LinkedHashMap<AEKey, LinkedHashMap<UUID, Long>> remainingCredits = new LinkedHashMap<>();

         for (java.util.Map.Entry<UUID, KeyCounter> target : loop.runtimeOutputSeedCredits().entrySet()) {
            for (Entry<AEKey> output : target.getValue()) {
               long amount = Sat.mul(output.getLongValue(), copies);
               if (amount > 0L) {
                  remainingCredits.computeIfAbsent((AEKey)output.getKey(), ignored -> new LinkedHashMap<>())
                     .merge(target.getKey(), Long.valueOf(amount), Ae2LtTimeWheelCraftingCpuLogic::addSaturated);
               }
            }
         }

         if (remainingCredits.isEmpty()) {
            return Map.of();
         }

         consumePreallocatedLoopCredits(remainingCredits, remainderLoopCredits);
         GenericStack[] actualOutputs = details.getOutputs();

         for (OverloadPatternDetails.OutputSlot outputx : overloadDetails.overloadPatternDetailsView().outputs()) {
            if (outputx.matchMode() != MatchMode.ID_ONLY) {
               int slot = outputx.slotIndex();
               if (slot >= 0 && slot < actualOutputs.length) {
                  LinkedHashMap<UUID, Long> byConsumer = remainingCredits.get(actualOutputs[slot].what());
                  if (byConsumer != null && !byConsumer.isEmpty()) {
                     takeConsumerCredits(byConsumer, multiplySaturated((long)outputx.amountPerCraft(), copies));
                  }
               }
            }
         }

         HashMap<Integer, OverloadReusableSeedMetadata> result = new HashMap<>();

         for (OverloadPatternDetails.OutputSlot outputxx : overloadDetails.overloadPatternDetailsView().outputs()) {
            if (outputxx.matchMode() == MatchMode.ID_ONLY) {
               int slot = outputxx.slotIndex();
               if (slot >= 0 && slot < actualOutputs.length) {
                  AEKey expected = actualOutputs[slot].what();
                  LinkedHashMap<UUID, Long> byConsumer = remainingCredits.get(expected);
                  if (byConsumer != null && !byConsumer.isEmpty()) {
                     long slotAmount = multiplySaturated((long)outputxx.amountPerCraft(), copies);
                     List<OverloadConsumerCredit> credits = takeConsumerCredits(byConsumer, slotAmount);
                     if (!credits.isEmpty()) {
                        result.put(slot, new OverloadReusableSeedMetadata(credits, loop.hasSingleSeedInputPerMember()));
                     }
                  }
               }
            }
         }

         return result.isEmpty() ? Map.of() : Map.copyOf(result);
      }

      return Map.of();
   }

   private static void consumePreallocatedLoopCredits(
      LinkedHashMap<AEKey, LinkedHashMap<UUID, Long>> remainingCredits, Map<UUID, KeyCounter> preallocatedLoopCredits
   ) {
      if (preallocatedLoopCredits != null && !preallocatedLoopCredits.isEmpty()) {
         for (java.util.Map.Entry<UUID, KeyCounter> consumer : preallocatedLoopCredits.entrySet()) {
            for (Entry<AEKey> credit : consumer.getValue()) {
               LinkedHashMap<UUID, Long> byConsumer = remainingCredits.get(credit.getKey());
               if (byConsumer == null) {
                  throw new IllegalStateException("preallocated loop credit has no matching planned output");
               }

               long available = byConsumer.getOrDefault(consumer.getKey(), 0L);
               if (credit.getLongValue() > available) {
                  throw new IllegalStateException("preallocated loop credit exceeds its fixed consumer allocation");
               }

               long left = available - credit.getLongValue();
               if (left > 0L) {
                  byConsumer.put(consumer.getKey(), Long.valueOf(left));
               } else {
                  byConsumer.remove(consumer.getKey());
               }

               if (byConsumer.isEmpty()) {
                  remainingCredits.remove(credit.getKey());
               }
            }
         }
      }
   }

   private static List<OverloadConsumerCredit> takeConsumerCredits(LinkedHashMap<UUID, Long> remainingByConsumer, long maximumAmount) {
      if (remainingByConsumer != null && maximumAmount > 0L) {
         long remaining = maximumAmount;
         ArrayList<OverloadConsumerCredit> result = new ArrayList<>();
         Iterator<java.util.Map.Entry<UUID, Long>> iterator = remainingByConsumer.entrySet().iterator();

         while (iterator.hasNext() && remaining > 0L) {
            java.util.Map.Entry<UUID, Long> entry = iterator.next();
            long amount = Math.min(Math.max(0L, entry.getValue()), remaining);
            if (amount > 0L) {
               result.add(new OverloadConsumerCredit(entry.getKey(), amount));
               remaining -= amount;
            }

            long left = entry.getValue() - amount;
            if (left <= 0L) {
               iterator.remove();
            } else {
               entry.setValue(left);
            }
         }

         return List.copyOf(result);
      } else {
         return List.of();
      }
   }

   private void rekeyOverloadReusableSeeds(AEKey incoming, OverloadClaimResult claims) {
      for (PendingOverloadClaim claim : claims.claims()) {
         for (OverloadConsumerCredit credit : claim.consumerCredits()) {
            this.loopSeedLedgers.rekeyAvailable(credit.consumerId(), claim.exactExpectedKey(), incoming, credit.amount());
         }
      }
   }

   private long applyInventoryClaims(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, AEKey incoming, OverloadClaimResult claims) {
      long claimed = claims.claimedForInventory();
      if (claimed <= 0L) {
         return 0L;
      } else {
         decrementItems(activeJob.timeTracker, claimed, incoming.getType());
         this.inventory.insert(incoming, claimed, Actionable.MODULATE);
         this.wakeSchedulerForReturnedInput(incoming);
         return claimed;
      }
   }

   private static long overloadPublicInventory(OverloadClaimResult claims) {
      long result = 0L;

      for (PendingOverloadClaim claim : claims.claims()) {
         if (claim.routesToRequester()) {
            long amount = claim.claimedAmount() - claim.reusableSeedAmount() - claim.requesterAmount();
            if (amount > 0L) {
               result = addSaturated(result, amount);
            }
         }
      }

      return result;
   }

   private void markRetainedRequesterClaim(AEKey incoming, long retained) {
      if (retained > 0L) {
         this.retainedFinalOutputs.add(incoming, retained);
         this.cpu.markDirty();
      }
   }

   private void applyRequesterClaims(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, AEKey incoming, OverloadClaimResult claims) {
      long claimed = claims.claimedForRequester();
      if (claimed > 0L) {
         decrementItems(activeJob.timeTracker, claimed, incoming.getType());
         this.postChange(incoming);
         activeJob.remainingAmount = Math.max(0L, activeJob.remainingAmount - claimed);
         if (activeJob.remainingAmount > 0L) {
            this.cpu.updateOutput(new GenericStack(activeJob.finalOutput.what(), activeJob.remainingAmount));
         } else {
            this.cpu.updateOutput(null);
         }
      }
   }

   private void consumeTaskCopies(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, IPatternDetails details, long copies) {
      if (copies > 0L) {
         Ae2LtTimeWheelCraftingCpuLogic.TaskProgress task = activeJob.tasks.get(details);
         if (task != null && task.value > 0L) {
            long consumed = Math.min(task.value, copies);
            task.value -= consumed;
            activeJob.removePendingOutputs(details, consumed);
            this.postPatternOutputsChange(details);
            if (task.value <= 0L) {
               activeJob.tasks.remove(details);
               this.clearTaskPreference(details);
            }
         }
      }
   }

   private void setTaskValue(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, IPatternDetails details, long value) {
      Ae2LtTimeWheelCraftingCpuLogic.TaskProgress task = activeJob.tasks.get(details);
      if (task != null) {
         long normalized = Math.max(0L, value);
         long oldValue = task.value;
         if (normalized != oldValue) {
            task.value = normalized;
            if (normalized <= 0L) {
               this.clearTaskPreference(details);
            }

            if (normalized < oldValue) {
               activeJob.removePendingOutputs(details, oldValue - normalized);
            } else {
               activeJob.addPendingOutputs(details, normalized - oldValue);
            }

            this.postPatternOutputsChange(details);
         }
      }
   }

   private void removeTask(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, IPatternDetails details) {
      this.unparkTask(details);
      Ae2LtTimeWheelCraftingCpuLogic.TaskProgress removed = activeJob.tasks.remove(details);
      this.clearTaskPreference(details);
      if (removed != null && removed.value > 0L) {
         activeJob.removePendingOutputs(details, removed.value);
      }
   }

   private void deductClaimedWaitingFor(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, OverloadClaimResult claims) {
      for (PendingOverloadClaim claim : claims.claims()) {
         this.extractWaitingFor(activeJob, claim.exactExpectedKey(), claim.claimedAmount());
      }
   }

   private void insertWaitingFor(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, AEKey what, long amount) {
      if (activeJob.insertWaitingFor(what, amount)) {
         this.markWaitingKeysChanged();
      }
   }

   private long extractWaitingFor(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, AEKey what, long amount) {
      Ae2LtTimeWheelCraftingCpuLogic.WaitingExtract result = activeJob.extractWaitingFor(what, amount);
      if (result.removedKey()) {
         this.markWaitingKeysChanged();
      }

      return result.extracted();
   }

   private void clearWaitingFor(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob) {
      if (activeJob.clearWaitingFor()) {
         this.markWaitingKeysChanged();
      }
   }

   private void markWaitingKeysChanged() {
      this.waitingKeysModifiedOnTick = TickHandler.instance().getCurrentTick();
   }

   private void prepareScheduler(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob) {
      this.advanceWheel();
      if (this.queueRebuildNeeded || this.needsSchedulerRebuild(activeJob)) {
         this.rebuildTaskWheel();
      }
   }

   private boolean needsSchedulerRebuild(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob) {
      return !activeJob.tasks.isEmpty() && this.queuedTasks.isEmpty() && this.tasksParkedByMissingKey.isEmpty() && !this.hasScheduledWheelEntries();
   }

   private boolean hasScheduledWheelEntries() {
      for (ArrayDeque<IPatternDetails> bucket : this.taskWheel) {
         if (!bucket.isEmpty()) {
            return true;
         }
      }

      return false;
   }

   private void advanceWheel() {
      long now = TickHandler.instance().getCurrentTick();
      if (this.schedulerTick == Long.MIN_VALUE) {
         this.schedulerTick = now;
      } else {
         int delta = (int)Math.max(0L, Math.min(64L, now - this.schedulerTick));
         this.schedulerTick = now;
         if (delta != 0) {
            int newCursor = this.wheelCursor + delta & 63;
            ArrayDeque<IPatternDetails> dest = this.taskWheel[newCursor];

            for (int i = 0; i < delta; i++) {
               ArrayDeque<IPatternDetails> skipped = this.taskWheel[this.wheelCursor + i & 63];
               if (skipped != dest) {
                  while (!skipped.isEmpty()) {
                     dest.addLast(skipped.pollFirst());
                  }
               }
            }

            this.wheelCursor = newCursor;
         }
      }
   }

   private void rebuildTaskWheel() {
      this.clearTaskWheel();
      Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob = this.job;
      if (activeJob != null) {
         ArrayList<java.util.Map.Entry<IPatternDetails, Ae2LtTimeWheelCraftingCpuLogic.TaskProgress>> entries = new ArrayList<>(activeJob.tasks.entrySet());
         entries.sort((left, right) -> CraftingTaskPriorities.compare(left.getKey(), right.getKey()));

         for (java.util.Map.Entry<IPatternDetails, Ae2LtTimeWheelCraftingCpuLogic.TaskProgress> entry : entries) {
            if (entry.getValue().value > 0L) {
               this.scheduleRebuiltTask(activeJob, entry.getKey());
            }
         }

         this.queueRebuildNeeded = false;
      }
   }

   private void scheduleRebuiltTask(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, IPatternDetails details) {
      if (!this.hasOnlyExactInputs(details)) {
         this.scheduleTask(details, 0);
      } else {
         Set<AEKey> missingKeys = this.findMissingExactInputKeys(details);
         if (missingKeys.isEmpty()) {
            this.scheduleTask(details, 0);
         } else {
            if (this.parkTaskForMissingInputs(activeJob, details, missingKeys)) {
               this.rescheduleIfStillPending(activeJob, details, 32);
            } else {
               this.scheduleTask(details, 0);
            }
         }
      }
   }

   private boolean hasOnlyExactInputs(IPatternDetails details) {
      for (IInput input : details.getInputs()) {
         GenericStack[] possibles = input.getPossibleInputs();
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
      for (ArrayDeque<IPatternDetails> bucket : this.taskWheel) {
         bucket.clear();
      }

      this.queuedTasks.clear();
      this.clearParkedTasks();
      this.preferredTask = null;
      this.queueRebuildNeeded = true;
   }

   private void scheduleTask(IPatternDetails details, int delayTicks) {
      Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob = this.job;
      if (activeJob != null && activeJob.tasks.containsKey(details)) {
         if (this.queuedTasks.add(details)) {
            int slot = this.wheelCursor + Math.max(0, delayTicks) & 63;
            this.taskWheel[slot].addLast(details);
         }
      }
   }

   private void unscheduleTask(IPatternDetails details) {
      boolean removed = false;

      for (ArrayDeque<IPatternDetails> bucket : this.taskWheel) {
         Iterator<IPatternDetails> iterator = bucket.iterator();

         while (iterator.hasNext()) {
            if (iterator.next() == details) {
               iterator.remove();
               removed = true;
            }
         }
      }

      if (removed) {
         this.queuedTasks.remove(details);
      }
   }

   private boolean parkTaskForMissingInputs(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, IPatternDetails details) {
      return activeJob.tasks.containsKey(details) && this.hasOnlyExactInputs(details)
         ? this.parkTaskForMissingInputs(activeJob, details, this.findMissingExactInputKeys(details))
         : false;
   }

   private boolean parkTaskForMissingInputs(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, IPatternDetails details, Set<AEKey> missingKeys) {
      if (missingKeys.isEmpty()) {
         return false;
      } else {
         this.unparkTask(details);
         ArrayList<Object> taskKeys = new ArrayList<>(missingKeys.size());

         for (AEKey key : missingKeys) {
            taskKeys.add(key != null ? key.getPrimaryKey() : null);
         }

         return this.tasksParkedByMissingKey.park(details, taskKeys);
      }
   }

   private void unparkTask(IPatternDetails details) {
      this.tasksParkedByMissingKey.unpark(details);
   }

   private void clearParkedTasks() {
      this.tasksParkedByMissingKey.clear();
   }

   private Set<AEKey> findMissingExactInputKeys(IPatternDetails details) {
      HashMap<AEKey, Long> exactRequired = new HashMap<>();

      for (IInput input : details.getInputs()) {
         long multiplier = input.getMultiplier();
         GenericStack[] possibles = input.getPossibleInputs();
         if (possibles.length != 1) {
            return Set.of();
         }

         GenericStack possible = possibles[0];
         AEKey key = possible.what();
         long perCopy = multiplySaturated(possible.amount(), multiplier);
         if (key != null && perCopy > 0L) {
            exactRequired.merge(key, perCopy, Ae2LtTimeWheelCraftingCpuLogic::addSaturated);
         }
      }

      HashSet<AEKey> missing = new HashSet<>();

      for (java.util.Map.Entry<AEKey, Long> entry : exactRequired.entrySet()) {
         long required = entry.getValue();
         if (required > 0L && this.inventory.extract(entry.getKey(), required, Actionable.SIMULATE) < required) {
            missing.add(entry.getKey());
         }
      }

      return missing;
   }

   @Nullable
   private IPatternDetails pollDueTask(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob) {
      ArrayDeque<IPatternDetails> bucket = this.taskWheel[this.wheelCursor];
      IPatternDetails selected = null;
      Iterator<IPatternDetails> iterator = bucket.iterator();

      while (iterator.hasNext()) {
         IPatternDetails details = iterator.next();
         Ae2LtTimeWheelCraftingCpuLogic.TaskProgress task = activeJob.tasks.get(details);
         if (task == null || task.value <= 0L) {
            iterator.remove();
            this.queuedTasks.remove(details);
            this.clearTaskPreference(details);
         } else if (selected == null || CraftingTaskPriorities.compare(details, selected, this.preferredTask) < 0) {
            selected = details;
         }
      }

      if (selected == null) {
         return null;
      } else {
         iterator = bucket.iterator();

         while (iterator.hasNext()) {
            if (iterator.next() == selected) {
               iterator.remove();
               break;
            }
         }

         this.queuedTasks.remove(selected);
         this.unparkTask(selected);
         return selected;
      }
   }

   private void rescheduleIfStillPending(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, IPatternDetails details, int delayTicks) {
      Ae2LtTimeWheelCraftingCpuLogic.TaskProgress task = activeJob.tasks.get(details);
      if (task != null && task.value > 0L) {
         this.scheduleTask(details, delayTicks);
      }
   }

   private void preferTaskWhilePending(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, IPatternDetails details) {
      if (this.preferredTask == null) {
         if (!(details instanceof ISeedPreservingCraftingTask)) {
            Ae2LtTimeWheelCraftingCpuLogic.TaskProgress task = activeJob.tasks.get(details);
            if (task != null && task.value > 0L) {
               this.preferredTask = details;
            }
         }
      }
   }

   private void clearTaskPreference(IPatternDetails details) {
      if (this.preferredTask == details) {
         this.preferredTask = null;
      }
   }

   private void rescheduleFailedTask(
      Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, IPatternDetails details, Ae2LtTimeWheelCraftingCpuLogic.DispatchOutcome outcome
   ) {
      if (outcome == Ae2LtTimeWheelCraftingCpuLogic.DispatchOutcome.RETRY_MISSING_INPUT && this.parkTaskForMissingInputs(activeJob, details)) {
         this.rescheduleIfStillPending(activeJob, details, 32);
      } else {
         this.unparkTask(details);
         this.rescheduleIfStillPending(activeJob, details, this.retryDelayTicks(outcome));
      }
   }

   private int retryDelayTicks(Ae2LtTimeWheelCraftingCpuLogic.DispatchOutcome outcome) {
      return switch (outcome) {
         case PUSHED -> 0;
         case RETRY_SOON -> 1;
         case RETRY_MISSING_INPUT, RETRY_NO_POWER, RETRY_LATER -> 4;
      };
   }

   void finishPhysicalSchedulingTick() {
      this.carryOverDueTasks();
   }

   private void carryOverDueTasks() {
      ArrayDeque<IPatternDetails> bucket = this.taskWheel[this.wheelCursor];
      if (!bucket.isEmpty()) {
         ArrayDeque<IPatternDetails> nextBucket = this.taskWheel[this.wheelCursor + 1 & 63];

         while (!bucket.isEmpty()) {
            nextBucket.addLast(bucket.pollFirst());
         }
      }
   }

   private void wakeSchedulerForReturnedInput(AEKey what) {
      if (this.job != null && what != null) {
         List<IPatternDetails> tasksToWake = this.tasksParkedByMissingKey.wake(what.getPrimaryKey());
         if (!tasksToWake.isEmpty()) {
            for (IPatternDetails details : tasksToWake) {
               this.unparkTask(details);
               this.unscheduleTask(details);
               this.scheduleTask(details, 0);
            }
         }
      }
   }

   private void postChange(@Nullable AEKey what) {
      if (what != null) {
         if (this.batchingStatusChanges) {
            this.batchedStatusChanges.add(what);
         } else {
            this.lastModifiedOnTick = TickHandler.instance().getCurrentTick();

            for (Consumer<AEKey> listener : this.listeners) {
               listener.accept(what);
            }
         }
      }
   }

   private void beginStatusChangeBatch() {
      this.batchingStatusChanges = true;
      this.batchedStatusChanges.clear();
   }

   private void endStatusChangeBatch() {
      this.batchingStatusChanges = false;
      if (!this.batchedStatusChanges.isEmpty()) {
         this.lastModifiedOnTick = TickHandler.instance().getCurrentTick();
         this.statusChangeScratch.clear();
         this.statusChangeScratch.addAll(this.batchedStatusChanges);
         this.batchedStatusChanges.clear();

         for (AEKey key : this.statusChangeScratch) {
            for (Consumer<AEKey> listener : this.listeners) {
               listener.accept(key);
            }
         }

         this.statusChangeScratch.clear();
      }
   }

   private void postPatternOutputsChange(IPatternDetails details) {
      for (GenericStack output : details.getOutputs()) {
         this.postChange(output.what());
      }
   }

   private void notifyJobOwner(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, Status status) {
      this.lastModifiedOnTick = TickHandler.instance().getCurrentTick();
      Integer playerId = activeJob.playerId;
      if (playerId != null && this.cpu.getLevel() != null && this.cpu.getLevel().getServer() != null) {
         ServerPlayer connectedPlayer = IPlayerRegistry.getConnected(this.cpu.getLevel().getServer(), playerId);
         if (connectedPlayer != null) {
            CraftingJobStatusPacket message = new CraftingJobStatusPacket(
               activeJob.link.getCraftingID(), activeJob.finalOutput.what(), activeJob.finalOutput.amount(), activeJob.remainingAmount, status
            );
            connectedPlayer.connection.send(message.toPacket(NetworkDirection.PLAY_TO_CLIENT));
         }
      }
   }

   private static ArrayDeque<IPatternDetails>[] createWheel() {
      ArrayDeque[] wheel = new ArrayDeque[64];

      for (int i = 0; i < wheel.length; i++) {
         wheel[i] = new ArrayDeque();
      }

      return wheel;
   }

   private static void addMaxItems(ElapsedTimeTracker tracker, long count, AEKeyType type) {
      ((ElapsedTimeTrackerAccessor)tracker).invokeAddMaxItems(count, type);
   }

   private static void decrementItems(ElapsedTimeTracker tracker, long count, AEKeyType type) {
      ((ElapsedTimeTrackerAccessor)tracker).invokeDecrementItems(count, type);
   }

   private static void clearScratchCounter(KeyCounter counter) {
      counter.clear();
   }

   private double patternPowerFor(IPatternDetails details, KeyCounter[] craftingContainer) {
      Double cached = this.patternPowerCache.get(details);
      if (cached != null) {
         return cached;
      } else {
         double power = CraftingCpuHelper.calculatePatternPower(craftingContainer);
         this.patternPowerCache.put(details, power);
         return power;
      }
   }

   private static long multiplySaturated(long left, long right) {
      if (left <= 0L || right <= 0L) {
         return 0L;
      } else {
         return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
      }
   }

   private static long addSaturated(long left, long right) {
      if (right <= 0L) {
         return left;
      } else {
         return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
      }
   }

   private static long saturatingAdd(long left, long right) {
      if (right <= 0L) {
         return left;
      } else {
         return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
      }
   }

   private static record BulkPush(int dispatched, int retryDelayTicks) {
   }

   private static enum DispatchOutcome {
      PUSHED,
      RETRY_SOON,
      RETRY_MISSING_INPUT,
      RETRY_NO_POWER,
      RETRY_LATER;
   }

   private static record OverloadInsert(long claimed, long accepted) {
      private static final Ae2LtTimeWheelCraftingCpuLogic.OverloadInsert EMPTY = new Ae2LtTimeWheelCraftingCpuLogic.OverloadInsert(0L, 0L);
   }

   private static record ProviderPushResult(boolean accepted, boolean virtualCraftingEnabled) {
      private static final Ae2LtTimeWheelCraftingCpuLogic.ProviderPushResult REJECTED = new Ae2LtTimeWheelCraftingCpuLogic.ProviderPushResult(false, false);
   }

   private static final class ReservedCraftingInventory implements ICraftingInventory {
      private final ICraftingInventory delegate;
      private final Map<AEKey, Long> reserved;

      private ReservedCraftingInventory(ICraftingInventory delegate, Map<AEKey, Long> reserved) {
         this.delegate = delegate;
         this.reserved = reserved;
      }

      public void insert(AEKey what, long amount, Actionable mode) {
         this.delegate.insert(what, amount, mode);
      }

      public long extract(AEKey what, long amount, Actionable mode) {
         long held = this.delegate.extract(what, Long.MAX_VALUE, Actionable.SIMULATE);
         long available = Math.max(0L, held - this.reserved.getOrDefault(what, 0L));
         long requested = Math.min(Math.max(0L, amount), available);
         return requested > 0L ? this.delegate.extract(what, requested, mode) : 0L;
      }

      public Iterable<AEKey> findFuzzyTemplates(AEKey input) {
         return this.delegate.findFuzzyTemplates(input);
      }
   }

   private static record ResolvedProvider(ICraftingProvider provider, IPatternDetails pattern) {
   }

   private final class SingleTaskBatchJobView implements BatchJobView, BatchTaskHandle, Iterator<BatchTaskHandle> {
      private Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob;
      private IPatternDetails details;
      private boolean consumed;

      private Ae2LtTimeWheelCraftingCpuLogic.SingleTaskBatchJobView bind(Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob activeJob, IPatternDetails details) {
         this.activeJob = activeJob;
         this.details = details;
         this.consumed = false;
         return this;
      }

      @Override
      public Iterator<BatchTaskHandle> taskIterator() {
         this.consumed = false;
         return this;
      }

      @Override
      public boolean hasNext() {
         Ae2LtTimeWheelCraftingCpuLogic.TaskProgress task = this.activeJob.tasks.get(this.details);
         return !this.consumed && task != null && task.value > 0L;
      }

      public BatchTaskHandle next() {
         if (!this.hasNext()) {
            throw new NoSuchElementException();
         } else {
            this.consumed = true;
            return this;
         }
      }

      @Override
      public void remove() {
         Ae2LtTimeWheelCraftingCpuLogic.this.removeTask(this.activeJob, this.details);
         Ae2LtTimeWheelCraftingCpuLogic.this.postPatternOutputsChange(this.details);
      }

      @Override
      public IPatternDetails details() {
         return this.details;
      }

      @Override
      public long getValue() {
         Ae2LtTimeWheelCraftingCpuLogic.TaskProgress task = this.activeJob.tasks.get(this.details);
         return task != null ? task.value : 0L;
      }

      @Override
      public void setValue(long value) {
         Ae2LtTimeWheelCraftingCpuLogic.this.setTaskValue(this.activeJob, this.details, value);
      }

      @Override
      public ListCraftingInventory waitingFor() {
         return this.activeJob.waitingFor;
      }

      @Override
      public UUID craftingId() {
         return this.activeJob.link.getCraftingID();
      }

      @Override
      public void insertWaitingFor(AEKey what, long amount) {
         Ae2LtTimeWheelCraftingCpuLogic.this.insertWaitingFor(this.activeJob, what, amount);
      }

      @Override
      public void addContainerMaxItems(long count, AEKeyType type) {
         Ae2LtTimeWheelCraftingCpuLogic.addMaxItems(this.activeJob.timeTracker, count, type);
      }
   }

   private static final class TaskProgress {
      private long value;
   }

   public static record TickUsage(int successfulDispatches, long dispatchedCopies) {
      public static final Ae2LtTimeWheelCraftingCpuLogic.TickUsage EMPTY = new Ae2LtTimeWheelCraftingCpuLogic.TickUsage(0, 0L);
   }

   private static final class TimeWheelJob {
      private final CraftingLink link;
      private final ListCraftingInventory waitingFor;
      private final Set<AEKey> waitingKeys = new HashSet<>();
      private final KeyCounter pendingOutputs = new KeyCounter();
      private final Map<IPatternDetails, Ae2LtTimeWheelCraftingCpuLogic.TaskProgress> tasks = new HashMap<>();
      private final ElapsedTimeTracker timeTracker;
      private GenericStack finalOutput;
      private long remainingAmount;
      @Nullable
      private Integer playerId;
      private boolean suspended;
      private boolean softCancelling;
      private boolean closedLoopJob;
      private boolean virtualCompletionRequested;

      private TimeWheelJob(ICraftingPlan plan, Consumer<AEKey> postCraftingDifference, CraftingLink link, @Nullable Integer playerId) {
         this(plan, postCraftingDifference, link, playerId, new ElapsedTimeTracker());
      }

      private TimeWheelJob(
         ICraftingPlan plan, Consumer<AEKey> postCraftingDifference, CraftingLink link, @Nullable Integer playerId, ElapsedTimeTracker timeTracker
      ) {
         this.finalOutput = plan.finalOutput();
         this.remainingAmount = this.finalOutput.amount();
         this.waitingFor = new ListCraftingInventory(postCraftingDifference::accept);
         this.timeTracker = timeTracker;

         for (Entry<AEKey> entry : plan.emittedItems()) {
            this.insertWaitingFor((AEKey)entry.getKey(), entry.getLongValue());
            Ae2LtTimeWheelCraftingCpuLogic.addMaxItems(timeTracker, entry.getLongValue(), ((AEKey)entry.getKey()).getType());
         }

         for (java.util.Map.Entry<IPatternDetails, Long> entry : plan.patternTimes().entrySet()) {
            if (entry.getKey() instanceof ReusableSeedPattern) {
               this.closedLoopJob = true;
            }

            Map<IPatternDetails, Long> expanded = entry.getKey() instanceof PatternFiringExpander expander
               ? expander.expandPatternFirings(entry.getValue())
               : Map.of(entry.getKey(), entry.getValue());

            for (java.util.Map.Entry<IPatternDetails, Long> concrete : expanded.entrySet()) {
               Ae2LtTimeWheelCraftingCpuLogic.TaskProgress task = this.tasks
                  .computeIfAbsent(concrete.getKey(), ignored -> new Ae2LtTimeWheelCraftingCpuLogic.TaskProgress());
               task.value = Sat.add(task.value, concrete.getValue());
               this.addPendingOutputs(concrete.getKey(), concrete.getValue());

               for (GenericStack output : concrete.getKey().getOutputs()) {
                  long amount = Ae2LtTimeWheelCraftingCpuLogic.multiplySaturated(
                     Ae2LtTimeWheelCraftingCpuLogic.multiplySaturated(output.amount(), concrete.getValue()), (long)output.what().getAmountPerUnit()
                  );
                  Ae2LtTimeWheelCraftingCpuLogic.addMaxItems(timeTracker, amount, output.what().getType());
               }
            }
         }

         this.link = link;
         this.playerId = playerId;
      }

      @Nullable
      private static Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob readFromNBT(
         CompoundTag data, Provider registries, Consumer<AEKey> postCraftingDifference, TimeWheelCraftingCPU cpu
      ) {
         GenericStack finalOutput = GenericStack.readTag(data.getCompound("finalOutput"));
         if (finalOutput == null) {
            return null;
         } else {
            CraftingLink link = new CraftingLink(data.getCompound("link"), cpu);
            Ae2LtTimeWheelCraftingCpuLogic.TimeWheelPlan emptyPlan = new Ae2LtTimeWheelCraftingCpuLogic.TimeWheelPlan(finalOutput);
            Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob job = new Ae2LtTimeWheelCraftingCpuLogic.TimeWheelJob(
               emptyPlan,
               postCraftingDifference,
               link,
               data.contains("playerId", 3) ? data.getInt("playerId") : null,
               new ElapsedTimeTracker(data.getCompound("timeTracker"))
            );
            job.remainingAmount = data.getLong("remainingAmount");
            job.suspended = data.getBoolean("suspended");
            job.softCancelling = data.getBoolean("softCancelling");
            job.closedLoopJob = data.getBoolean("closedLoopJob") || job.softCancelling;
            job.waitingFor.readFromNBT(data.getList("waitingFor", 10));
            job.rebuildWaitingKeys();
            job.tasks.clear();
            job.pendingOutputs.clear();
            job.readTasks(data.getList("tasks", 10), registries, cpu.getLevel());
            job.rebuildPendingOutputs();
            if (!job.closedLoopJob) {
               for (IPatternDetails details : job.tasks.keySet()) {
                  if (details instanceof ExecuteLoopPattern) {
                     job.closedLoopJob = true;
                     break;
                  }
               }
            }

            return job;
         }
      }

      private void readTasks(ListTag tasksTag, Provider registries, @Nullable Level level) {
         if (level != null) {
            for (int i = 0; i < tasksTag.size(); i++) {
               CompoundTag item = tasksTag.getCompound(i);
               AEItemKey pattern = AEItemKey.fromTag(item);
               if (pattern != null) {
                  IPatternDetails details = PatternDetailsHelper.decodePattern(pattern, level);
                  long remaining = item.getLong("#craftingProgress");
                  if (details != null && remaining > 0L) {
                     KeyCounter inputSeed = Ae2LtTimeWheelCraftingCpuLogic.readCounter(item.getList("#inputSeed", 10), registries);
                     KeyCounter initialSeed = Ae2LtTimeWheelCraftingCpuLogic.readCounter(item.getList("#initialSeed", 10), registries);
                     KeyCounter outputSeed = Ae2LtTimeWheelCraftingCpuLogic.readCounter(item.getList("#outputSeed", 10), registries);
                     UUID consumerId = item.hasUUID("#seedConsumer") ? item.getUUID("#seedConsumer") : null;
                     Map<UUID, KeyCounter> outputCredits = Ae2LtTimeWheelCraftingCpuLogic.readSeedCredits(item.getList("#outputSeedCredits", 10), registries);
                     Map<UUID, KeyCounter> sharedOutputCredits = Ae2LtTimeWheelCraftingCpuLogic.readSeedCredits(
                        item.getList("#sharedOutputSeedCredits", 10), registries
                     );
                     boolean hasRoutedCreditTags = item.contains("#outputSeedCredits", 9) || item.contains("#sharedOutputSeedCredits", 9);
                     if (consumerId == null && details instanceof ISeedPreservingCraftingTask seeded) {
                        consumerId = seeded.reusableSeedGroupId();
                     }

                     if (!hasRoutedCreditTags && outputCredits.isEmpty() && consumerId != null && !outputSeed.isEmpty()) {
                        outputCredits = Map.of(consumerId, outputSeed);
                     }

                     if (consumerId != null
                        && (!inputSeed.isEmpty() || !initialSeed.isEmpty() || !outputCredits.isEmpty() || !sharedOutputCredits.isEmpty())
                        && details instanceof ISeedPreservingCraftingTask) {
                        details = new ExecuteLoopPattern(details, consumerId, initialSeed, inputSeed, outputCredits, sharedOutputCredits);
                     }

                     Ae2LtTimeWheelCraftingCpuLogic.TaskProgress task = this.tasks
                        .computeIfAbsent(details, ignored -> new Ae2LtTimeWheelCraftingCpuLogic.TaskProgress());
                     task.value = Sat.add(task.value, remaining);
                  }
               }
            }
         }
      }

      private void rebuildPendingOutputs() {
         this.pendingOutputs.clear();

         for (java.util.Map.Entry<IPatternDetails, Ae2LtTimeWheelCraftingCpuLogic.TaskProgress> entry : this.tasks.entrySet()) {
            this.addPendingOutputs(entry.getKey(), entry.getValue().value);
         }
      }

      private void rebuildWaitingKeys() {
         this.waitingKeys.clear();

         for (Entry<AEKey> entry : this.waitingFor.list) {
            if (entry.getLongValue() > 0L) {
               this.waitingKeys.add((AEKey)entry.getKey());
            }
         }
      }

      private boolean insertWaitingFor(AEKey what, long amount) {
         if (amount <= 0L) {
            return false;
         } else {
            boolean wasAbsent = this.waitingFor.list.get(what) <= 0L;
            this.waitingFor.insert(what, amount, Actionable.MODULATE);
            boolean added = wasAbsent && this.waitingFor.list.get(what) > 0L;
            if (added) {
               this.waitingKeys.add(what);
            }

            return added;
         }
      }

      private Ae2LtTimeWheelCraftingCpuLogic.WaitingExtract extractWaitingFor(AEKey what, long amount) {
         if (amount <= 0L) {
            return new Ae2LtTimeWheelCraftingCpuLogic.WaitingExtract(0L, false);
         } else {
            long before = this.waitingFor.list.get(what);
            long extracted = this.waitingFor.extract(what, amount, Actionable.MODULATE);
            boolean removed = before > 0L && this.waitingFor.list.get(what) <= 0L;
            if (removed) {
               this.waitingKeys.remove(what);
            }

            return new Ae2LtTimeWheelCraftingCpuLogic.WaitingExtract(extracted, removed);
         }
      }

      private boolean clearWaitingFor() {
         boolean changed = !this.waitingKeys.isEmpty();
         this.waitingFor.clear();
         this.waitingKeys.clear();
         return changed;
      }

      private void addPendingOutputs(IPatternDetails details, long copies) {
         if (copies > 0L) {
            for (GenericStack output : details.getOutputs()) {
               this.pendingOutputs.add(output.what(), Ae2LtTimeWheelCraftingCpuLogic.multiplySaturated(output.amount(), copies));
            }
         }
      }

      private void removePendingOutputs(IPatternDetails details, long copies) {
         if (copies > 0L) {
            for (GenericStack output : details.getOutputs()) {
               long amount = Ae2LtTimeWheelCraftingCpuLogic.multiplySaturated(output.amount(), copies);
               long current = this.pendingOutputs.get(output.what());
               if (current <= amount) {
                  this.pendingOutputs.remove(output.what());
               } else {
                  this.pendingOutputs.remove(output.what(), amount);
               }
            }
         }
      }

      private CompoundTag writeToNBT(Provider registries) {
         CompoundTag data = new CompoundTag();
         CompoundTag linkData = new CompoundTag();
         this.link.writeToNBT(linkData);
         data.put("link", linkData);
         data.put("finalOutput", GenericStack.writeTag(this.finalOutput));
         data.put("waitingFor", this.waitingFor.writeToNBT());
         data.put("timeTracker", this.timeTracker.writeToNBT());
         ListTag list = new ListTag();

         for (java.util.Map.Entry<IPatternDetails, Ae2LtTimeWheelCraftingCpuLogic.TaskProgress> entry : this.tasks.entrySet()) {
            AEItemKey definition = entry.getKey() instanceof TimeWheelTaskPersistenceDefinition persistent
               ? persistent.timeWheelPersistenceDefinition()
               : entry.getKey().getDefinition();
            CompoundTag item = definition.toTag();
            item.putLong("#craftingProgress", entry.getValue().value);
            if (entry.getKey() instanceof ExecuteLoopPattern loopPattern) {
               item.putUUID("#seedConsumer", loopPattern.seedConsumerId());
               KeyCounter initialSeed = loopPattern.initialSeed();
               KeyCounter inputSeed = loopPattern.inputSeed();
               KeyCounter outputSeed = loopPattern.outputSeed();
               Map<UUID, KeyCounter> outputCredits = loopPattern.outputSeedCredits();
               Map<UUID, KeyCounter> sharedOutputCredits = loopPattern.sharedOutputSeedCredits();
               if (!initialSeed.isEmpty()) {
                  item.put("#initialSeed", Ae2LtTimeWheelCraftingCpuLogic.writeCounter(initialSeed, registries));
               }

               if (!inputSeed.isEmpty()) {
                  item.put("#inputSeed", Ae2LtTimeWheelCraftingCpuLogic.writeCounter(inputSeed, registries));
               }

               if (!outputSeed.isEmpty()) {
                  item.put("#outputSeed", Ae2LtTimeWheelCraftingCpuLogic.writeCounter(outputSeed, registries));
               }

               if (!outputCredits.isEmpty()) {
                  item.put("#outputSeedCredits", Ae2LtTimeWheelCraftingCpuLogic.writeSeedCredits(outputCredits, registries));
               }

               if (!sharedOutputCredits.isEmpty()) {
                  item.put("#sharedOutputSeedCredits", Ae2LtTimeWheelCraftingCpuLogic.writeSeedCredits(sharedOutputCredits, registries));
               }
            }

            list.add(item);
         }

         data.put("tasks", list);
         data.putLong("remainingAmount", this.remainingAmount);
         if (this.playerId != null) {
            data.putInt("playerId", this.playerId);
         }

         data.putBoolean("suspended", this.suspended);
         data.putBoolean("softCancelling", this.softCancelling);
         data.putBoolean("closedLoopJob", this.closedLoopJob);
         return data;
      }

      private List<ExecuteLoopPattern> loopPatterns() {
         ArrayList<ExecuteLoopPattern> result = new ArrayList<>();

         for (IPatternDetails details : this.tasks.keySet()) {
            if (details instanceof ExecuteLoopPattern loop) {
               result.add(loop);
            }
         }

         return List.copyOf(result);
      }
   }

   private static record TimeWheelPlan(GenericStack finalOutput) implements ICraftingPlan {
      public long bytes() {
         return 0L;
      }

      public boolean simulation() {
         return false;
      }

      public boolean multiplePaths() {
         return false;
      }

      public KeyCounter usedItems() {
         return new KeyCounter();
      }

      public KeyCounter emittedItems() {
         return new KeyCounter();
      }

      public KeyCounter missingItems() {
         return new KeyCounter();
      }

      public Map<IPatternDetails, Long> patternTimes() {
         return Map.of();
      }
   }

   private static record UsedItemsOverridePlan(ICraftingPlan delegate, KeyCounter usedItems) implements ICraftingPlan {
      public GenericStack finalOutput() {
         return this.delegate.finalOutput();
      }

      public long bytes() {
         return this.delegate.bytes();
      }

      public boolean simulation() {
         return this.delegate.simulation();
      }

      public boolean multiplePaths() {
         return this.delegate.multiplePaths();
      }

      public KeyCounter emittedItems() {
         return this.delegate.emittedItems();
      }

      public KeyCounter missingItems() {
         return this.delegate.missingItems();
      }

      public Map<IPatternDetails, Long> patternTimes() {
         return this.delegate.patternTimes();
      }
   }

   private static record WaitingExtract(long extracted, boolean removedKey) {
   }
}
