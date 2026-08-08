package com.moakiee.thunderbolt.ae2.crafting;

public final class CraftingCpuSelectionOrder {
   public static int compare(
      boolean firstPreferred,
      int firstCoProcessors,
      long firstAvailableStorage,
      boolean secondPreferred,
      int secondCoProcessors,
      long secondAvailableStorage,
      boolean prioritizePower
   ) {
      if (firstPreferred != secondPreferred) {
         return Boolean.compare(secondPreferred, firstPreferred);
      } else {
         int coProcessorOrder = prioritizePower
            ? Integer.compare(secondCoProcessors, firstCoProcessors)
            : Integer.compare(firstCoProcessors, secondCoProcessors);
         return coProcessorOrder != 0 ? coProcessorOrder : Long.compare(firstAvailableStorage, secondAvailableStorage);
      }
   }

   private CraftingCpuSelectionOrder() {
   }
}
