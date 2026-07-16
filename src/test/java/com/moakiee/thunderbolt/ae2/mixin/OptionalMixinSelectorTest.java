package com.moakiee.thunderbolt.ae2.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OptionalMixinSelectorTest {
    @Test
    void skipsAdvancedAeTargetsWhenAddonIsMissing() {
        assertFalse(OptionalMixinSelector.shouldApply(
                "com.moakiee.thunderbolt.ae2.mixin.AdvCraftingCpuLogicMixin",
                ignored -> false));
        assertFalse(OptionalMixinSelector.shouldApply("AaeTaskProgressAccessor", ignored -> false));
    }

    @Test
    void skipsNeoEcoTargetsWhenAddonIsMissing() {
        assertFalse(OptionalMixinSelector.shouldApply("ECOCraftingCpuLogicMixin", ignored -> false));
        assertFalse(OptionalMixinSelector.shouldApply("ECOCraftingCpuAccessor", ignored -> false));
    }

    @Test
    void appliesOptionalTargetsWhenTheirModIsLoaded() {
        assertTrue(OptionalMixinSelector.shouldApply("AdvCraftingCpuAccessor", "advanced_ae"::equals));
        assertTrue(OptionalMixinSelector.shouldApply("ECOCraftingCpuLogicMixin", "neoecoae"::equals));
    }

    @Test
    void neverGatesRequiredMixins() {
        assertTrue(OptionalMixinSelector.shouldApply("CraftingCalculationMixin", ignored -> false));
        assertTrue(OptionalMixinSelector.shouldApply("ExtendedCraftingCpuServiceMixin", ignored -> false));
    }
}
