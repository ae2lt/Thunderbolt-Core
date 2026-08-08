package com.moakiee.thunderbolt.ae2.batch;

public final class BatchCpuAccounting {
   private BatchCpuAccounting() {
   }

   public static long maxCopiesForCpuOps(int cpuOps, BatchCpuAccounting.Mode mode) {
      if (cpuOps <= 0) {
         return 0L;
      } else if (mode == BatchCpuAccounting.Mode.LINEAR) {
         return (long)cpuOps;
      } else {
         return mode == BatchCpuAccounting.Mode.SUCCESSFUL_DISPATCH ? Long.MAX_VALUE : (long)cpuOps * (long)cpuOps;
      }
   }

   public static long maxCopiesForBatch(int remainingCpuOps, int maxBatchOps, long remainingCopies, BatchCpuAccounting.Mode mode) {
      if (remainingCpuOps <= 0 || maxBatchOps <= 0 || remainingCopies <= 0L) {
         return 0L;
      } else if (mode == BatchCpuAccounting.Mode.SUCCESSFUL_DISPATCH) {
         return remainingCopies;
      } else {
         int batchOps = Math.min(remainingCpuOps, maxBatchOps);
         return Math.min(maxCopiesForCpuOps(batchOps, mode), remainingCopies);
      }
   }

   public static int cpuOpsForCopies(int copies, BatchCpuAccounting.Mode mode) {
      return cpuOpsForCopies((long)copies, mode);
   }

   public static int cpuOpsForCopies(long copies, BatchCpuAccounting.Mode mode) {
      if (copies <= 0L) {
         return 0;
      } else if (mode == BatchCpuAccounting.Mode.LINEAR) {
         return copies >= 2147483647L ? Integer.MAX_VALUE : (int)copies;
      } else {
         return mode == BatchCpuAccounting.Mode.SUCCESSFUL_DISPATCH ? 1 : cpuOpsForCopies(copies);
      }
   }

   public static long maxCopiesForCpuOps(int cpuOps) {
      return cpuOps <= 0 ? 0L : (long)cpuOps * (long)cpuOps;
   }

   public static int cpuOpsForCopies(int copies) {
      return cpuOpsForCopies((long)copies);
   }

   public static int cpuOpsForCopies(long copies) {
      if (copies <= 0L) {
         return 0;
      } else {
         long maxRepresentableCopies = 4611686014132420609L;
         if (copies >= maxRepresentableCopies) {
            return Integer.MAX_VALUE;
         } else {
            long root = (long)Math.sqrt((double)copies);

            while (root * root < copies) {
               root++;
            }

            while (root > 0L && (root - 1L) * (root - 1L) >= copies) {
               root--;
            }

            return (int)root;
         }
      }
   }

   public static enum Mode {
      LINEAR,
      QUADRATIC,
      SUCCESSFUL_DISPATCH;
   }
}
