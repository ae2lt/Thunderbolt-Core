package com.moakiee.thunderbolt.ae2.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Optional binary-shape checks against the real supported NeoECO artifacts. */
class NeoEcoBinaryShapeTest {
    private static final String LOGIC_CLASS =
            "cn/dancingsnow/neoecoae/api/me/ECOCraftingCPULogic.class";
    private static final String EXECUTE_CRAFTING =
            "executeCrafting(ILappeng/me/service/CraftingService;"
                    + "Lappeng/api/networking/energy/IEnergyService;"
                    + "Lnet/minecraft/world/level/Level;)I";
    private static final String COLLECT_AVAILABLE_PROVIDERS =
            "collectAvailableProviders(Lappeng/me/service/CraftingService;"
                    + "Lappeng/api/crafting/IPatternDetails;)Ljava/util/List;";
    private static final String INSERT =
            "insert(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;)J";

    @Test
    void real134JarMatchesLegacyInjectionTargets() throws IOException {
        try (var jar = configuredJar("NEOECO_134_JAR")) {
            var logic = assertCommonShape(jar);

            assertFalse(logic.methods.contains(COLLECT_AVAILABLE_PROVIDERS));
            assertProviderLookupCount(logic, EXECUTE_CRAFTING, 1);
            assertProviderLookupCount(logic, COLLECT_AVAILABLE_PROVIDERS, 0);
            assertWaitingForExtractCount(logic, 2);
        }
    }

    @Test
    void real2110JarMatchesFastPathInjectionTargets() throws IOException {
        try (var jar = configuredJar("NEOECO_2110_JAR")) {
            var logic = assertCommonShape(jar);

            assertTrue(logic.methods.contains(COLLECT_AVAILABLE_PROVIDERS));
            assertProviderLookupCount(logic, EXECUTE_CRAFTING, 0);
            assertProviderLookupCount(logic, COLLECT_AVAILABLE_PROVIDERS, 1);
            assertWaitingForExtractCount(logic, 3);
        }
    }

    private static JarFile configuredJar(String environmentVariable) throws IOException {
        String configured = System.getenv(environmentVariable);
        Path path = configured != null ? Path.of(configured) : null;
        Assumptions.assumeTrue(
                path != null && Files.isRegularFile(path),
                environmentVariable + " must point to the matching real NeoECO artifact");
        return new JarFile(path.toFile());
    }

    private static Shape assertCommonShape(JarFile jar) throws IOException {
        var logic = shape(jar, LOGIC_CLASS);
        assertTrue(logic.fields.contains("job:Lcn/dancingsnow/neoecoae/api/me/ExecutingCraftingJob;"));
        assertTrue(logic.fields.contains("inventory:Lappeng/crafting/inv/ListCraftingInventory;"));
        assertTrue(logic.fields.contains("cpu:Lcn/dancingsnow/neoecoae/api/me/ECOCraftingCPU;"));
        assertTrue(logic.methods.contains(INSERT));
        assertTrue(logic.methods.contains(EXECUTE_CRAFTING));
        assertTrue(logic.methods.contains(
                "tickCraftingLogic(Lappeng/api/networking/energy/IEnergyService;"
                        + "Lappeng/me/service/CraftingService;)V"));
        assertTrue(logic.methods.contains("finishJob(Z)V"));
        assertTrue(logic.methods.contains("postChange(Lappeng/api/stacks/AEKey;)V"));
        assertTrue(logic.methods.contains("readFromNBT(Lnet/minecraft/nbt/CompoundTag;"
                + "Lnet/minecraft/core/HolderLookup$Provider;)V"));
        assertTrue(logic.methods.contains("writeToNBT(Lnet/minecraft/nbt/CompoundTag;"
                + "Lnet/minecraft/core/HolderLookup$Provider;)V"));

        assertInvocationCount(
                logic,
                "tickCraftingLogic(Lappeng/api/networking/energy/IEnergyService;"
                        + "Lappeng/me/service/CraftingService;)V",
                Opcodes.INVOKEVIRTUAL,
                "cn/dancingsnow/neoecoae/api/me/ECOCraftingCPULogic",
                "executeCrafting",
                "(ILappeng/me/service/CraftingService;"
                        + "Lappeng/api/networking/energy/IEnergyService;"
                        + "Lnet/minecraft/world/level/Level;)I",
                false,
                1);
        assertInvocationCount(
                logic,
                EXECUTE_CRAFTING,
                Opcodes.INVOKEINTERFACE,
                "appeng/api/networking/crafting/ICraftingProvider",
                "pushPattern",
                "(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z",
                true,
                1);

        var job = shape(jar, "cn/dancingsnow/neoecoae/api/me/ExecutingCraftingJob.class");
        assertTrue(job.fields.contains("link:Lappeng/crafting/CraftingLink;"));
        assertTrue(job.fields.contains("waitingFor:Lappeng/crafting/inv/ListCraftingInventory;"));
        assertTrue(job.fields.contains("tasks:Ljava/util/Map;"));
        assertTrue(job.fields.contains("timeTracker:Lcn/dancingsnow/neoecoae/api/me/ElapsedTimeTracker;"));
        assertTrue(job.fields.contains("finalOutput:Lappeng/api/stacks/GenericStack;"));
        assertTrue(job.fields.contains("remainingAmount:J"));

        var tracker = shape(jar, "cn/dancingsnow/neoecoae/api/me/ElapsedTimeTracker.class");
        assertTrue(tracker.methods.contains("decrementItems(JLappeng/api/stacks/AEKeyType;)V"));
        assertTrue(tracker.methods.contains("addMaxItems(JLappeng/api/stacks/AEKeyType;)V"));

        var taskProgress = shape(
                jar, "cn/dancingsnow/neoecoae/api/me/ExecutingCraftingJob$TaskProgress.class");
        assertTrue(taskProgress.fields.contains("value:J"));

        var cpu = shape(jar, "cn/dancingsnow/neoecoae/api/me/ECOCraftingCPU.class");
        assertTrue(cpu.methods.contains("markDirty()V"));
        return logic;
    }

    private static void assertProviderLookupCount(Shape logic, String containingMethod, long expectedCount) {
        assertInvocationCount(
                logic,
                containingMethod,
                Opcodes.INVOKEVIRTUAL,
                "appeng/me/service/CraftingService",
                "getProviders",
                "(Lappeng/api/crafting/IPatternDetails;)Ljava/lang/Iterable;",
                false,
                expectedCount);
    }

    private static void assertWaitingForExtractCount(Shape logic, long expectedCount) {
        assertInvocationCount(
                logic,
                INSERT,
                Opcodes.INVOKEVIRTUAL,
                "appeng/crafting/inv/ListCraftingInventory",
                "extract",
                "(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;)J",
                false,
                expectedCount);
    }

    private static Shape shape(JarFile jar, String entryName) throws IOException {
        var entry = jar.getJarEntry(entryName);
        assertTrue(entry != null, "missing " + entryName);
        var result = new Shape();
        try (var input = jar.getInputStream(entry)) {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(
                        int access, String name, String descriptor, String signature, Object value) {
                    result.fields.add(name + ":" + descriptor);
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(
                        int access, String name, String descriptor, String signature, String[] exceptions) {
                    String method = name + descriptor;
                    result.methods.add(method);
                    var calls = result.callsByMethod.computeIfAbsent(method, ignored -> new ArrayList<>());
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(
                                int opcode,
                                String owner,
                                String invokedName,
                                String invokedDescriptor,
                                boolean isInterface) {
                            calls.add(new MethodCall(
                                    opcode, owner, invokedName, invokedDescriptor, isInterface));
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return result;
    }

    private static void assertInvocationCount(
            Shape shape,
            String containingMethod,
            int opcode,
            String owner,
            String name,
            String descriptor,
            boolean isInterface,
            long expectedCount) {
        long actualCount = shape.callsByMethod.getOrDefault(containingMethod, List.of()).stream()
                .filter(call -> call.opcode == opcode
                        && call.owner.equals(owner)
                        && call.name.equals(name)
                        && call.descriptor.equals(descriptor)
                        && call.isInterface == isInterface)
                .count();
        assertEquals(expectedCount, actualCount,
                containingMethod + " invocation drifted: " + owner + "." + name + descriptor);
    }

    private record MethodCall(
            int opcode, String owner, String name, String descriptor, boolean isInterface) {
    }

    private static final class Shape {
        private final Set<String> fields = new HashSet<>();
        private final Set<String> methods = new HashSet<>();
        private final Map<String, List<MethodCall>> callsByMethod = new HashMap<>();
    }
}
