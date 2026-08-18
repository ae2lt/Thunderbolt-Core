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

        assertTrue(source.contains("@Mixin(value = CapabilityProvider.class, priority = 2000, remap = false)"));
        assertTrue(source.contains("@Inject(method = \"getCapability(Lnet/minecraftforge/common/capabilities/Capability;Lnet/minecraft/core/Direction;)Lnet/minecraftforge/common/util/LazyOptional;\", at = @At(\"HEAD\"), cancellable = true)"));
    }
}
