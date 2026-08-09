package com.moakiee.thunderbolt.ae2.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ExtendedAePlusMixinPriorityContractTest {
    @Test
    void suppressionRunsAfterEaepAddsItsSyntheticTargetMethods() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/moakiee/thunderbolt/ae2/mixin/"
                        + "ExtendedAePlusVirtualCompletionSuppressionMixin.java"));

        // EAEP 1.5.5 adds the bridge method from a priority-900 mixin. Mixin applies higher
        // priorities first, so Thunderbolt must use a lower priority to resolve the merged method.
        assertTrue(source.contains("@Mixin(value = PatternProviderLogic.class, priority = 800, remap = false)"));
        assertFalse(source.contains("method = \"eap$compatTryVirtualCompletion\""));
        assertTrue(source.contains("method = \"eap$compatIsVirtualCraftingEnabled\""));
    }
}
