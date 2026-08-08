package com.moakiee.thunderbolt.ae2.timewheel;

import java.util.ArrayDeque;
import java.util.List;

public final class ProductiveDispatchScheduler {
   private ProductiveDispatchScheduler() {
   }

   public static <T> int run(int totalSuccessfulDispatches, int productiveQuantum, List<T> candidates, ProductiveDispatchScheduler.DispatchWorker<T> worker) {
      int total = Math.max(0, totalSuccessfulDispatches);
      int remaining = total;
      int quantum = Math.max(1, productiveQuantum);
      ArrayDeque<T> productive = new ArrayDeque<>();

      for (T candidate : candidates) {
         int allowance = remaining > 0 ? 1 : 0;
         int used = checkedUsage(worker.dispatch(candidate, allowance), allowance);
         remaining -= used;
         if (used > 0) {
            productive.addLast(candidate);
         }
      }

      while (remaining > 0 && !productive.isEmpty()) {
         int roundSize = productive.size();
         boolean roundProgress = false;

         for (int i = 0; i < roundSize && remaining > 0; i++) {
            T candidatex = productive.removeFirst();
            int allowance = Math.min(quantum, remaining);
            int used = checkedUsage(worker.dispatch(candidatex, allowance), allowance);
            if (used > 0) {
               remaining -= used;
               roundProgress = true;
               productive.addLast(candidatex);
            }
         }

         if (!roundProgress) {
            break;
         }
      }

      return total - remaining;
   }

   private static int checkedUsage(int used, int allowance) {
      if (used >= 0 && used <= allowance) {
         return used;
      } else {
         throw new IllegalStateException("Dispatch worker used " + used + " successful dispatches from allowance " + allowance);
      }
   }

   @FunctionalInterface
   public interface DispatchWorker<T> {
      int dispatch(T var1, int var2);
   }
}
