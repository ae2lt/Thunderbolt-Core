package com.moakiee.thunderbolt.ae2.batch;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.core.AELog;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.service.CraftingService;
import com.moakiee.thunderbolt.ae2.api.crafting.BatchDispatchContext;
import com.moakiee.thunderbolt.ae2.api.crafting.BatchDispatchMode;
import com.moakiee.thunderbolt.ae2.api.crafting.CraftingPatternDelegates;
import com.moakiee.thunderbolt.ae2.api.crafting.IBatchCraftingProvider;
import com.moakiee.thunderbolt.ae2.crafting.ExecuteLoopPattern;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.world.level.Level;

public final class BatchExecutor {
   private static volatile Predicate<IPatternDetails> skipRule = details -> false;
   private static volatile Predicate<IPatternDetails> batchEligibleRule = details -> true;

   private BatchExecutor() {
   }

   public static void setSkipRule(Predicate<IPatternDetails> rule) {
      skipRule = rule != null ? rule : details -> false;
   }

   public static void setBatchEligibleRule(Predicate<IPatternDetails> rule) {
      batchEligibleRule = rule != null ? rule : details -> true;
   }

   public static BatchExecutor.BatchRunResult runBatchOnly(
      int remainingOps,
      BatchCpuAccounting.Mode accountingMode,
      CraftingService cs,
      IEnergyService es,
      Level level,
      BatchJobView job,
      ListCraftingInventory inv,
      Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> batchedByTask,
      Runnable markDirty
   ) {
      return runBatchOnly(remainingOps, accountingMode, cs, es, level, job, inv, batchedByTask, markDirty, Map.of());
   }

   public static BatchExecutor.BatchRunResult runBatchOnly(
      int remainingOps,
      BatchCpuAccounting.Mode accountingMode,
      CraftingService cs,
      IEnergyService es,
      Level level,
      BatchJobView job,
      ListCraftingInventory inv,
      Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> batchedByTask,
      Runnable markDirty,
      Map<AEKey, Long> reservedStock
   ) {
      return runBatchOnly(remainingOps, accountingMode, cs, es, level, job, inv, batchedByTask, markDirty, reservedStock, Integer.MAX_VALUE, Long.MAX_VALUE);
   }

   public static BatchExecutor.BatchRunResult runBatchOnly(
      int remainingOps,
      BatchCpuAccounting.Mode accountingMode,
      CraftingService cs,
      IEnergyService es,
      Level level,
      BatchJobView job,
      ListCraftingInventory inv,
      Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> batchedByTask,
      Runnable markDirty,
      Map<AEKey, Long> reservedStock,
      int maxBatchOps,
      long maxCopies
   ) {
      return runBatchOnly(remainingOps, accountingMode, cs, es, level, job, inv, batchedByTask, markDirty, reservedStock, maxBatchOps, maxCopies, false, null);
   }

   public static BatchExecutor.BatchRunResult runBatchOnly(
      int remainingOps,
      BatchCpuAccounting.Mode accountingMode,
      CraftingService cs,
      IEnergyService es,
      Level level,
      BatchJobView job,
      ListCraftingInventory inv,
      Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> batchedByTask,
      Runnable markDirty,
      Map<AEKey, Long> reservedStock,
      int maxBatchOps,
      long maxCopies,
      boolean unboundedCpuBatch
   ) {
      return runBatchOnly(
         remainingOps, accountingMode, cs, es, level, job, inv, batchedByTask, markDirty, reservedStock, maxBatchOps, maxCopies, unboundedCpuBatch, null
      );
   }

   public static BatchExecutor.BatchRunResult runBatchOnly(
      int remainingOps,
      BatchCpuAccounting.Mode accountingMode,
      CraftingService cs,
      IEnergyService es,
      Level level,
      BatchJobView job,
      ListCraftingInventory inv,
      Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> batchedByTask,
      Runnable markDirty,
      Map<AEKey, Long> reservedStock,
      int maxBatchOps,
      long maxCopies,
      boolean unboundedCpuBatch,
      TickProviderDispatchSchedule dispatchSchedule
   ) {
      if (job == null) {
         return BatchExecutor.BatchRunResult.EMPTY;
      } else {
         Iterator<BatchTaskHandle> taskIter = job.taskIterator();
         if (!taskIter.hasNext()) {
            return BatchExecutor.BatchRunResult.EMPTY;
         } else {
            long totalPushed = 0L;
            int consumedOps = 0;
            int opsBudget = remainingOps;
            long copiesBudget = Math.max(0L, maxCopies);
            maxBatchOps = Math.max(1, maxBatchOps);
            if (remainingOps > 0 && copiesBudget > 0L) {
               if (accountingMode == null) {
                  accountingMode = BatchCpuAccounting.Mode.LINEAR;
               }

               boolean dirty = false;
               boolean sawBatchProvider = false;

               label309:
               while (taskIter.hasNext()) {
                  BatchTaskHandle task = taskIter.next();
                  long taskValue = task.getValue();
                  if (taskValue > 0L) {
                     IPatternDetails details = task.details();
                     IPatternDetails executionDetails = details instanceof ExecuteLoopPattern loop ? loop.delegate() : details;
                     if (!skipRule.test(executionDetails) && batchEligibleRule.test(executionDetails)) {
                        boolean hasSharedInputs = SharedBatchInputs.hasSharedInputs(details);
                        IPatternDetails providerPattern = CraftingPatternDelegates.forProviderLookup(details);
                        IdentityHashMap<ICraftingProvider, Boolean> perTaskBatched = batchedByTask.get(details);
                        ArrayList<BatchExecutor.EligibleProvider> eligible = null;
                        Iterable<ICraftingProvider> providerCandidates = dispatchSchedule != null
                           ? dispatchSchedule.candidates(cs, providerPattern, providerPattern)
                           : cs.getProviders(providerPattern);
                        Iterator hasUnboundedProvider = providerCandidates.iterator();

                        while (true) {
                           ICraftingProvider provider;
                           long capacity;
                           BatchDispatchMode dispatchMode;
                           while (true) {
                              if (!hasUnboundedProvider.hasNext()) {
                                 if (eligible != null) {
                                    if (hasSharedInputs && eligible.size() > 1) {
                                       eligible.sort(
                                          Comparator.<BatchExecutor.EligibleProvider, Boolean>comparing(
                                                providerx -> providerx.mode() != BatchDispatchMode.UNBOUNDED
                                             )
                                             .thenComparing(BatchExecutor.EligibleProvider::capacity, Comparator.reverseOrder())
                                       );
                                       eligible.subList(1, eligible.size()).clear();
                                    }

                                    boolean hasUnboundedProviderx = eligible.stream().anyMatch(providerx -> providerx.mode() == BatchDispatchMode.UNBOUNDED);
                                    long availableBatchCapacity = 0L;

                                    for (BatchExecutor.EligibleProvider providerx : eligible) {
                                       availableBatchCapacity = saturatingAdd(availableBatchCapacity, providerx.capacity());
                                    }

                                    if (availableBatchCapacity > 0L) {
                                       eligible.sort(
                                          Comparator.<BatchExecutor.EligibleProvider, Boolean>comparing(
                                                providerx -> providerx.mode() != BatchDispatchMode.UNBOUNDED
                                             )
                                             .thenComparingLong(BatchExecutor.EligibleProvider::capacity)
                                       );
                                       capacity = !hasUnboundedProviderx && !unboundedCpuBatch && accountingMode != BatchCpuAccounting.Mode.SUCCESSFUL_DISPATCH
                                          ? BatchCpuAccounting.maxCopiesForBatch(opsBudget, maxBatchOps, copiesBudget, accountingMode)
                                          : copiesBudget;
                                       if (capacity <= 0L) {
                                          if (dirty) {
                                             markDirty.run();
                                          }

                                          return new BatchExecutor.BatchRunResult(totalPushed, consumedOps, sawBatchProvider);
                                       }

                                       long budget = Math.min(Math.min(taskValue, availableBatchCapacity), capacity);
                                       if (details instanceof BatchCopyLimitPattern limited) {
                                          budget = Math.min(budget, Math.max(1L, limited.maxBatchCopies()));
                                       }

                                       if (budget > 0L) {
                                          ParallelBatchCpuHelper.BulkResult result = ParallelBatchCpuHelper.bulkExtract(
                                             details, inv, budget, true, reservedStock
                                          );
                                          if (result != null) {
                                             long realCraft = result.actualCopies;
                                             double powerForReal = CraftingCpuHelper.calculatePatternPower(result.scaledInputs);
                                             double powerOne = realCraft > 0L ? powerForReal / (double)realCraft : 0.0;
                                             double availablePower = es.extractAEPower(powerForReal, Actionable.SIMULATE, PowerMultiplier.CONFIG);
                                             if (availablePower < powerForReal - 0.01) {
                                                long affordable = powerOne > 0.0 ? floorToLong(availablePower / powerOne) : 0L;
                                                if (affordable <= 0L) {
                                                   ParallelBatchCpuHelper.reinject(result, realCraft, inv);
                                                   if (dirty) {
                                                      markDirty.run();
                                                   }

                                                   return new BatchExecutor.BatchRunResult(totalPushed, consumedOps, sawBatchProvider);
                                                }

                                                long scaleDown = realCraft - affordable;
                                                if (scaleDown > 0L) {
                                                   ParallelBatchCpuHelper.reinject(result, scaleDown, inv);
                                                   realCraft = affordable;
                                                }
                                             }

                                             long initialRealCraft = realCraft;
                                             long leftover = realCraft;
                                             KeyCounter[] oneCopy = ParallelBatchCpuHelper.cloneSingleCopy(result);
                                             int i = 0;

                                             while (true) {
                                                label304: {
                                                   if (i < eligible.size() && leftover > 0L) {
                                                      BatchExecutor.EligibleProvider eligibleProvider = eligible.get(i);
                                                      IBatchCraftingProvider batch = eligibleProvider.provider();
                                                      boolean unbounded = unboundedCpuBatch || eligibleProvider.mode() == BatchDispatchMode.UNBOUNDED;
                                                      long sliceCap = !unbounded && accountingMode != BatchCpuAccounting.Mode.SUCCESSFUL_DISPATCH
                                                         ? BatchCpuAccounting.maxCopiesForBatch(opsBudget, maxBatchOps, copiesBudget, accountingMode)
                                                         : copiesBudget;
                                                      if (sliceCap > 0L) {
                                                         long slice;
                                                         if (unbounded) {
                                                            slice = leftover;
                                                         } else {
                                                            int remainingProviders = eligible.size() - i;
                                                            slice = Math.max(1L, leftover / (long)remainingProviders);
                                                         }

                                                         slice = Math.min(slice, leftover);
                                                         slice = Math.min(slice, sliceCap);
                                                         slice = Math.min(slice, eligibleProvider.capacity());

                                                         long subLeftover;
                                                         try {
                                                            subLeftover = batch.pushBatch(
                                                               new BatchDispatchContext(executionDetails, oneCopy, slice, level, job.craftingId())
                                                            );
                                                         } catch (Throwable var70) {
                                                            AELog.warn(
                                                               "[ae2lt] IBatchCraftingProvider %s threw during pushBatch; treating as full leftover. %s",
                                                               new Object[]{batch, var70}
                                                            );
                                                            subLeftover = slice;
                                                         }

                                                         if (subLeftover < 0L || subLeftover > slice) {
                                                            AELog.warn(
                                                               "[ae2lt] IBatchCraftingProvider %s returned out-of-range leftover %d for slice=%d; treating as full leftover.",
                                                               new Object[]{batch, subLeftover, slice}
                                                            );
                                                            subLeftover = slice;
                                                         }

                                                         long dispatched = slice - subLeftover;
                                                         if (dispatched <= 0L) {
                                                            if (dispatchSchedule != null) {
                                                               dispatchSchedule.recordFailure(providerPattern, eligibleProvider.identity());
                                                            }
                                                            break label304;
                                                         }

                                                         if (dispatchSchedule != null) {
                                                            dispatchSchedule.recordSuccess(providerPattern, eligibleProvider.identity());
                                                         }

                                                         ParallelBatchCpuHelper.markDispatched(result, dispatched);
                                                         es.extractAEPower(powerOne * (double)dispatched, Actionable.MODULATE, PowerMultiplier.CONFIG);
                                                         ParallelBatchCpuHelper.registerExpectedOutputs(job, details, result, dispatched);
                                                         dirty = true;
                                                         int opsCost = !unbounded && accountingMode != BatchCpuAccounting.Mode.SUCCESSFUL_DISPATCH
                                                            ? BatchCpuAccounting.cpuOpsForCopies(dispatched, accountingMode)
                                                            : 1;
                                                         consumedOps += opsCost;
                                                         opsBudget -= opsCost;
                                                         long newValue = task.getValue() - dispatched;
                                                         task.setValue(newValue);
                                                         totalPushed = saturatingAdd(totalPushed, dispatched);
                                                         copiesBudget -= dispatched;
                                                         leftover -= dispatched;
                                                         if (initialRealCraft > 1L) {
                                                            if (perTaskBatched == null) {
                                                               perTaskBatched = batchedByTask.computeIfAbsent(details, key -> new IdentityHashMap<>());
                                                            }

                                                            perTaskBatched.put(eligibleProvider.identity(), Boolean.TRUE);
                                                         }

                                                         if (newValue > 0L) {
                                                            if (opsBudget > 0 && copiesBudget > 0L) {
                                                               break label304;
                                                            }

                                                            if (leftover > 0L) {
                                                               ParallelBatchCpuHelper.reinject(result, leftover, inv);
                                                               leftover = 0L;
                                                            }

                                                            if (dirty) {
                                                               markDirty.run();
                                                            }

                                                            return new BatchExecutor.BatchRunResult(totalPushed, consumedOps, sawBatchProvider);
                                                         }

                                                         taskIter.remove();
                                                         if (leftover > 0L) {
                                                            ParallelBatchCpuHelper.reinject(result, leftover, inv);
                                                            leftover = 0L;
                                                         }

                                                         if (opsBudget <= 0 || copiesBudget <= 0L) {
                                                            if (dirty) {
                                                               markDirty.run();
                                                            }

                                                            return new BatchExecutor.BatchRunResult(totalPushed, consumedOps, sawBatchProvider);
                                                         }
                                                      }
                                                   }

                                                   if (leftover > 0L) {
                                                      ParallelBatchCpuHelper.reinject(result, leftover, inv);
                                                   }
                                                   continue label309;
                                                }

                                                i++;
                                             }
                                          }
                                       }
                                    }
                                 }
                                 continue label309;
                              }

                              provider = (ICraftingProvider)hasUnboundedProvider.next();
                              if (provider instanceof IBatchCraftingProvider batch) {
                                 sawBatchProvider = true;
                                 if (perTaskBatched == null || !perTaskBatched.containsKey(provider)) {
                                    try {
                                       if (!hasSharedInputs || batch.supportsSingleSeedBatch()) {
                                          capacity = batch.getBatchCapacity(executionDetails);
                                          dispatchMode = batch.getBatchDispatchMode(executionDetails);
                                          break;
                                       }
                                    } catch (Throwable var71) {
                                       AELog.warn(
                                          "[ae2lt] IBatchCraftingProvider %s threw while reporting capacity; blocking this pattern for the current tick. %s",
                                          new Object[]{batch, var71}
                                       );
                                       if (dispatchSchedule != null) {
                                          dispatchSchedule.recordFailure(providerPattern, provider);
                                       }
                                    }
                                 }
                              }
                           }

                           if (usesBatchPath(capacity)) {
                              if (dispatchMode == null) {
                                 dispatchMode = BatchDispatchMode.NORMAL;
                              }

                              if (eligible == null) {
                                 eligible = new ArrayList<>();
                              }

                              eligible.add(new BatchExecutor.EligibleProvider((IBatchCraftingProvider)provider, provider, capacity, dispatchMode));
                           }
                        }
                     }
                  } else {
                     taskIter.remove();
                  }
               }

               if (dirty) {
                  markDirty.run();
               }

               return new BatchExecutor.BatchRunResult(totalPushed, consumedOps, sawBatchProvider);
            } else {
               return BatchExecutor.BatchRunResult.EMPTY;
            }
         }
      }
   }

   private static long saturatingAdd(long left, long right) {
      if (right <= 0L) {
         return left;
      } else {
         return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
      }
   }

   private static long floorToLong(double value) {
      if (Double.isFinite(value) && !(value >= 9.223372E18F)) {
         return value <= 0.0 ? 0L : (long)Math.floor(value);
      } else {
         return Long.MAX_VALUE;
      }
   }

   static boolean usesBatchPath(long capacity) {
      return capacity > 1L;
   }

   public static record BatchRunResult(long dispatchedCopies, int consumedCpuOps, boolean sawBatchProvider) {
      public static final BatchExecutor.BatchRunResult EMPTY = new BatchExecutor.BatchRunResult(0L, 0, false);

      public boolean shouldRetryBatchThisTick() {
         return this.dispatchedCopies > 0L;
      }
   }

   private static record EligibleProvider(IBatchCraftingProvider provider, ICraftingProvider identity, long capacity, BatchDispatchMode mode) {
   }
}
