package com.moakiee.thunderbolt.ae2.batch;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetails.IInput;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ListCraftingInventory;
import com.moakiee.thunderbolt.ae2.crafting.ExecuteLoopPattern;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import org.jetbrains.annotations.Nullable;

public final class ParallelBatchCpuHelper {
   private ParallelBatchCpuHelper() {
   }

   @Nullable
   public static ParallelBatchCpuHelper.BulkResult bulkExtract(IPatternDetails details, ListCraftingInventory inv, long maxCraft) {
      return bulkExtract(details, inv, maxCraft, true, Map.of());
   }

   @Nullable
   public static ParallelBatchCpuHelper.BulkResult bulkExtract(
      IPatternDetails details, ListCraftingInventory inv, long maxCraft, boolean allowSharedInputs, Map<AEKey, Long> reservedStock
   ) {
      if (maxCraft <= 0L) {
         return null;
      } else {
         IInput[] inputs = details.getInputs();
         int slots = inputs.length;
         AEKey[] chosenKeys = new AEKey[slots];
         long[] units = new long[slots];
         long[] available = new long[slots];
         boolean[] shared = new boolean[slots];
         Map<AEKey, Long> reserved = reservedStock != null ? reservedStock : Map.of();

         for (int slot = 0; slot < slots; slot++) {
            IInput input = inputs[slot];
            GenericStack[] possibles = input.getPossibleInputs();
            AEKey bestKey = null;
            long bestUnits = 0L;
            long bestAvailable = 0L;
            long bestCopies = 0L;
            boolean bestShared = false;

            for (GenericStack possible : possibles) {
               if (possible.what() != null) {
                  long perCopy = saturatingMultiply(possible.amount(), input.getMultiplier());
                  if (perCopy > 0L) {
                     long inInventory = Math.max(
                        0L, inv.extract(possible.what(), Long.MAX_VALUE, Actionable.SIMULATE) - Math.max(0L, reserved.getOrDefault(possible.what(), 0L))
                     );
                     boolean isShared = allowSharedInputs && SharedBatchInputs.isSharedInput(details, slot, possible.what());
                     long copies = isShared ? (inInventory >= perCopy ? maxCraft : 0L) : inInventory / perCopy;
                     if (copies > bestCopies) {
                        bestKey = possible.what();
                        bestUnits = perCopy;
                        bestAvailable = inInventory;
                        bestCopies = copies;
                        bestShared = isShared;
                        if (copies >= maxCraft) {
                           break;
                        }
                     }
                  }
               }
            }

            if (bestKey == null || bestCopies <= 0L) {
               return null;
            }

            chosenKeys[slot] = bestKey;
            units[slot] = bestUnits;
            available[slot] = bestAvailable;
            shared[slot] = bestShared;
         }

         HashMap<AEKey, Long> fixedByKey = new HashMap<>(slots * 2);
         HashMap<AEKey, Long> variableByKey = new HashMap<>(slots * 2);
         HashMap<AEKey, Long> availableByKey = new HashMap<>(slots * 2);

         for (int slot = 0; slot < slots; slot++) {
            availableByKey.put(chosenKeys[slot], available[slot]);
            (shared[slot] ? fixedByKey : variableByKey).merge(chosenKeys[slot], units[slot], ParallelBatchCpuHelper::saturatingAdd);
         }

         long actual = maxCraft;

         for (Entry<AEKey, Long> entry : availableByKey.entrySet()) {
            long fixed = fixedByKey.getOrDefault(entry.getKey(), 0L);
            long variable = variableByKey.getOrDefault(entry.getKey(), 0L);
            if (fixed > entry.getValue()) {
               return null;
            }

            if (variable > 0L) {
               actual = Math.min(actual, (entry.getValue() - fixed) / variable);
            }

            if (actual <= 0L) {
               return null;
            }
         }

         HashMap<AEKey, Long> extractedByKey = new HashMap<>(availableByKey.size() * 2);

         for (Entry<AEKey, Long> entry : availableByKey.entrySet()) {
            long need = saturatingAdd(fixedByKey.getOrDefault(entry.getKey(), 0L), saturatingMultiply(variableByKey.getOrDefault(entry.getKey(), 0L), actual));
            long got = inv.extract(entry.getKey(), need, Actionable.MODULATE);
            extractedByKey.put(entry.getKey(), got);
            if (got < need) {
               for (Entry<AEKey, Long> rollback : extractedByKey.entrySet()) {
                  if (rollback.getValue() > 0L) {
                     inv.insert(rollback.getKey(), rollback.getValue(), Actionable.MODULATE);
                  }
               }

               return null;
            }
         }

         KeyCounter[] scaled = new KeyCounter[slots];

         for (int slot = 0; slot < slots; slot++) {
            scaled[slot] = new KeyCounter();
            long amount = shared[slot] ? units[slot] : saturatingMultiply(units[slot], actual);
            if (amount > 0L) {
               scaled[slot].add(chosenKeys[slot], amount);
            }
         }

         return new ParallelBatchCpuHelper.BulkResult(scaled, actual, chosenKeys, units, shared);
      }
   }

   public static void reinject(ParallelBatchCpuHelper.BulkResult result, long leftoverCopies, ListCraftingInventory inv) {
      if (leftoverCopies > 0L) {
         long returnedCopies = Math.min(leftoverCopies, result.remainingCopies);

         for (int slot = 0; slot < result.scaledInputs.length; slot++) {
            if (!result.sharedInputs[slot]) {
               long amount = saturatingMultiply(result.units[slot], returnedCopies);
               if (amount > 0L && result.keys[slot] != null) {
                  inv.insert(result.keys[slot], amount, Actionable.MODULATE);
                  result.scaledInputs[slot].remove(result.keys[slot], amount);
               }
            }
         }

         result.remainingCopies -= returnedCopies;
         if (result.remainingCopies == 0L && !result.sharedDispatched) {
            result.reinjectShared(inv);
         }
      }
   }

   public static void registerExpectedOutputs(BatchJobView job, IPatternDetails details, ParallelBatchCpuHelper.BulkResult result, long dispatched) {
      registerExpectedOutputs(job, details, result.keys, result.sharedInputs, dispatched);
   }

   public static void registerExpectedOutputs(BatchJobView job, IPatternDetails details, AEKey[] chosenKeys, long dispatched) {
      registerExpectedOutputs(job, details, chosenKeys, null, dispatched);
   }

   private static void registerExpectedOutputs(BatchJobView job, IPatternDetails details, AEKey[] chosenKeys, boolean[] shared, long dispatched) {
      if (dispatched > 0L) {
         SharedBatchInputPattern sharedPattern = (details instanceof ExecuteLoopPattern loop ? loop.delegate() : details) instanceof SharedBatchInputPattern pattern
            ? pattern
            : null;
         HashMap<AEKey, Long> sharedOutputsLeft = new HashMap<>();

         for (GenericStack output : details.getOutputs()) {
            long sharedAmount = 0L;
            if (sharedPattern != null) {
               long remainingShared = sharedOutputsLeft.computeIfAbsent(output.what(), sharedPattern::sharedBatchOutputAmount);
               sharedAmount = Math.min(output.amount(), Math.max(0L, remainingShared));
               sharedOutputsLeft.put(output.what(), remainingShared - sharedAmount);
            }

            long scalable = Math.max(0L, output.amount() - sharedAmount);
            job.insertWaitingFor(output.what(), saturatingAdd(sharedAmount, saturatingMultiply(scalable, dispatched)));
         }

         IInput[] inputs = details.getInputs();

         for (int slot = 0; slot < inputs.length; slot++) {
            IInput input = inputs[slot];
            GenericStack[] possibles = input.getPossibleInputs();
            if (possibles.length != 0) {
               AEKey consumed = chosenKeys != null && slot < chosenKeys.length && chosenKeys[slot] != null ? chosenKeys[slot] : possibles[0].what();
               AEKey remaining = input.getRemainingKey(consumed);
               if (remaining != null) {
                  boolean sharedInput = shared != null && slot < shared.length ? shared[slot] : SharedBatchInputs.isSharedInput(details, slot, consumed);
                  long copies = sharedInput ? 1L : dispatched;
                  long perCopy = input.getMultiplier();
                  long count = saturatingMultiply(perCopy, copies);
                  job.insertWaitingFor(remaining, count);
                  job.addContainerMaxItems(count, remaining.getType());
               }
            }
         }
      }
   }

   public static KeyCounter[] cloneSingleCopy(ParallelBatchCpuHelper.BulkResult result) {
      return copySlice(result, 1L);
   }

   public static KeyCounter[] copySlice(ParallelBatchCpuHelper.BulkResult result, long sliceCount) {
      KeyCounter[] slice = new KeyCounter[result.scaledInputs.length];

      for (int slot = 0; slot < slice.length; slot++) {
         slice[slot] = new KeyCounter();
         long amount = result.sharedInputs[slot] ? result.units[slot] : saturatingMultiply(Math.max(0L, sliceCount), result.units[slot]);
         if (amount > 0L && result.keys[slot] != null) {
            slice[slot].add(result.keys[slot], amount);
         }
      }

      return slice;
   }

   public static void markDispatched(ParallelBatchCpuHelper.BulkResult result, long dispatchedCopies) {
      if (dispatchedCopies > 0L) {
         long accepted = Math.min(dispatchedCopies, result.remainingCopies);

         for (int slot = 0; slot < result.scaledInputs.length; slot++) {
            long amount;
            if (result.sharedInputs[slot]) {
               amount = result.sharedDispatched ? 0L : result.units[slot];
            } else {
               amount = saturatingMultiply(result.units[slot], accepted);
            }

            if (amount > 0L && result.keys[slot] != null) {
               result.scaledInputs[slot].remove(result.keys[slot], amount);
            }
         }

         result.sharedDispatched = true;
         result.remainingCopies -= accepted;
      }
   }

   private static long saturatingAdd(long left, long right) {
      return left >= Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
   }

   private static long saturatingMultiply(long left, long right) {
      if (left > 0L && right > 0L) {
         return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
      } else {
         return 0L;
      }
   }

   public static final class BulkResult {
      public final KeyCounter[] scaledInputs;
      public final long actualCopies;
      final AEKey[] keys;
      final long[] units;
      final boolean[] sharedInputs;
      long remainingCopies;
      boolean sharedDispatched;

      public BulkResult(KeyCounter[] scaledInputs, long actualCopies, AEKey[] keys, long[] units, boolean[] sharedInputs) {
         this.scaledInputs = scaledInputs;
         this.actualCopies = actualCopies;
         this.keys = Arrays.copyOf(keys, keys.length);
         this.units = Arrays.copyOf(units, units.length);
         this.sharedInputs = Arrays.copyOf(sharedInputs, sharedInputs.length);
         this.remainingCopies = actualCopies;
      }

      private void reinjectShared(ListCraftingInventory inv) {
         for (int slot = 0; slot < this.scaledInputs.length; slot++) {
            if (this.sharedInputs[slot] && this.keys[slot] != null) {
               inv.insert(this.keys[slot], this.units[slot], Actionable.MODULATE);
               this.scaledInputs[slot].remove(this.keys[slot], this.units[slot]);
            }
         }
      }
   }
}
