package com.moakiee.thunderbolt.core.craft;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.moakiee.thunderbolt.ae2.timewheel.TimeWheelCraftingCpuHost;

class CrossJarHostMethodNameTest {
    @Test
    void hostContractsDoNotReuseMappedMinecraftMethodNames() {
        var mappedHostMethodNames = new HashSet<String>();
        addMethodNames(mappedHostMethodNames, BlockEntity.class);
        addMethodNames(mappedHostMethodNames, MenuProvider.class);

        assertNoMethodNameCollisions(CraftingCoreHost.class, mappedHostMethodNames);
        assertNoMethodNameCollisions(TimeWheelCraftingCpuHost.class, mappedHostMethodNames);
    }

    private static void addMethodNames(Set<String> names, Class<?> type) {
        Arrays.stream(type.getMethods()).map(Method::getName).forEach(names::add);
    }

    private static void assertNoMethodNameCollisions(Class<?> contract, Set<String> mappedNames) {
        for (var method : contract.getDeclaredMethods()) {
            assertTrue(
                    !mappedNames.contains(method.getName()),
                    () -> contract.getName() + "." + method.getName()
                            + " collides with a mapped Minecraft host method; use a domain-specific name");
        }
    }
}
