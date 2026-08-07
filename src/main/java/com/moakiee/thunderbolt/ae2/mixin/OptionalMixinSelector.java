package com.moakiee.thunderbolt.ae2.mixin;

import java.util.Map;
import java.util.function.Predicate;

/** Pure target-to-mod mapping used by the early Mixin config plugin. */
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
            Map.entry("NeoEcoPatternBusBatchMixin", "neoecoae"));

    /** Mixins that must be skipped when the listed mod is loaded. */
    private static final Map<String, String> SKIP_IF_MOD_LOADED = Map.ofEntries(
            // AdvancedAE 1.3.6 @Overwrite-s CraftingService.insertIntoCpus; Mixin 0.8.5 rejects
            // any injector on an overwritten method at prepare time ("cannot inject into ...
            // merged by ..."), and require = 0 does not suppress that failure. The extended-CPU
            // insert bridge lives in its own mixin class so it can be dropped while the rest of
            // ExtendedCraftingCpuServiceMixin stays active.
            Map.entry("ExtendedCraftingCpuInsertMixin", "advanced_ae"));

    private OptionalMixinSelector() {
    }

    public static boolean shouldApply(String mixinClassName, Predicate<String> modLoaded) {
        int separator = mixinClassName.lastIndexOf('.');
        String simpleName = separator >= 0 ? mixinClassName.substring(separator + 1) : mixinClassName;
        String skipIf = SKIP_IF_MOD_LOADED.get(simpleName);
        if (skipIf != null && modLoaded.test(skipIf)) {
            return false;
        }
        String requiredMod = REQUIRED_MODS.get(simpleName);
        return requiredMod == null || modLoaded.test(requiredMod);
    }
}
