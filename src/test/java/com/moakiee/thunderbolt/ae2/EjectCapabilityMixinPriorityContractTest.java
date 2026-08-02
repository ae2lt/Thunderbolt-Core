package com.moakiee.thunderbolt.ae2;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class EjectCapabilityMixinPriorityContractTest {
    @Test
    void thunderboltEjectInterceptorRunsBeforeDefaultPriorityMixins() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/moakiee/thunderbolt/ae2/mixin/EjectCapabilityMixin.java"));

        assertTrue(source.contains("@Mixin(value = BlockCapability.class, priority = 2000)"));
        assertTrue(source.contains("@Inject(method = \"getCapability\", at = @At(\"HEAD\"), cancellable = true)"));
    }
}
