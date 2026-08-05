package com.moakiee.thunderbolt.ae2.mixin;

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

        // EAEP's PatternProviderLogicCompatMixin has priority 500. Lower priorities are applied
        // later, after eap$compatTryVirtualCompletion and eap$compatIsVirtualCraftingEnabled exist.
        assertTrue(source.contains("@Mixin(value = PatternProviderLogic.class, priority = 400, remap = false)"));
        assertTrue(source.contains("method = \"eap$compatTryVirtualCompletion\""));
        assertTrue(source.contains("method = \"eap$compatIsVirtualCraftingEnabled\""));
    }
}
