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

        // Forge 1.20.1 EAEP 1.5.5 adds its methods at priorities 500 and 450. Mixin applies
        // higher priorities first, so Thunderbolt must run below both synthetic target owners.
        assertTrue(source.contains("@Mixin(value = PatternProviderLogic.class, priority = 400, remap = false)"));
        assertTrue(source.contains("method = \"eap$compatTryVirtualCompletion\""));
        assertTrue(source.contains("method = \"eap$compatIsVirtualCraftingEnabled\""));
    }
}
