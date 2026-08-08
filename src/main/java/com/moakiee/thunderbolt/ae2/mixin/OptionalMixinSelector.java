package com.moakiee.thunderbolt.ae2.mixin;

import java.util.Map;
import java.util.function.Predicate;

public final class OptionalMixinSelector {
   private static final Map<String, String> REQUIRED_MODS = Map.ofEntries(
      Map.entry("Ae2CraftingTreeCompatibilityMixin", "ae2ct"),
      Map.entry("AdvCraftingCpuLogicBatchMixin", "advanced_ae"),
      Map.entry("AdvCraftingCpuLogicMixin", "advanced_ae"),
      Map.entry("AdvCraftingCpuAccessor", "advanced_ae"),
      Map.entry("AaeExecutingCraftingJobAccessor", "advanced_ae"),
      Map.entry("AaeElapsedTimeTrackerAccessor", "advanced_ae"),
      Map.entry("AaeTaskProgressAccessor", "advanced_ae"),
      Map.entry("ECOCraftingCpuLogicBatchMixin", "neoecoae"),
      Map.entry("ECOCraftingCpuLogicMixin", "neoecoae"),
      Map.entry("ECOCraftingCpuAccessor", "neoecoae"),
      Map.entry("ExtendedAePlusVirtualCompletionSuppressionMixin", "extendedae_plus"),
      Map.entry("NeoEcoPatternBusBatchMixin", "neoecoae")
   );

   private OptionalMixinSelector() {
   }

   public static boolean shouldApply(String mixinClassName, Predicate<String> modLoaded) {
      int separator = mixinClassName.lastIndexOf(46);
      String simpleName = separator >= 0 ? mixinClassName.substring(separator + 1) : mixinClassName;
      String requiredMod = REQUIRED_MODS.get(simpleName);
      return requiredMod == null || modLoaded.test(requiredMod);
   }
}
