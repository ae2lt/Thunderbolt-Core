package com.moakiee.thunderbolt.ae2.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Binary-shape checks against the real supported NeoECO artifacts. */
class NeoEcoBinaryShapeTest {
    private static final String LOGIC_CLASS =
            "cn/dancingsnow/neoecoae/api/me/ECOCraftingCPULogic.class";
    private static final String FORGE_1201_EXECUTE_CRAFTING =
            "executeCrafting(IILappeng/me/service/CraftingService;"
                    + "Lappeng/api/networking/energy/IEnergyService;"
                    + "Lnet/minecraft/world/level/Level;"
                    + "Lcn/dancingsnow/neoecoae/api/me/"
                    + "ECOCraftingCPULogic$FastPathBatchBudget;)I";
    private static final String COLLECT_PROVIDERS =
            "collectProviders(Lappeng/me/service/CraftingService;"
                    + "Lappeng/api/crafting/IPatternDetails;)"
                    + "Lcn/dancingsnow/neoecoae/api/me/ECOCraftingCPULogic$ProviderSelection;";
    private static final String TRY_PUSH_SLOW_PATTERN =
            "tryPushSlowPattern(Lappeng/api/crafting/IPatternDetails;"
                    + "Lcn/dancingsnow/neoecoae/api/me/ExecutingCraftingJob$TaskProgress;"
                    + "Lcn/dancingsnow/neoecoae/api/me/ECOCraftingCPULogic$ProviderSelection;"
                    + "Lcn/dancingsnow/neoecoae/api/me/ECOCraftingCPULogic$ExtractedPatternAttempt;"
                    + "Lcn/dancingsnow/neoecoae/api/me/ECOCraftingCPULogic$CraftingExecutionProgress;"
                    + "Lappeng/api/networking/energy/IEnergyService;)"
                    + "Lcn/dancingsnow/neoecoae/api/me/ECOCraftingCPULogic$PushResult;";
    private static final String INSERT =
            "insert(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;)J";

    @Test
    void real2030Forge1201JarMatchesInjectionAndBridgeTargets() throws IOException {
        try (var jar = preparedJar("neoecoae-20.3.0.jar")) {
            var logic = shape(jar, LOGIC_CLASS);
            assertCoreStateShape(jar, logic);
            assertTrue(logic.methods.contains(FORGE_1201_EXECUTE_CRAFTING));
            assertTrue(logic.methods.contains(COLLECT_PROVIDERS));
            assertTrue(logic.methods.contains(TRY_PUSH_SLOW_PATTERN));

            assertInvocationCount(
                    logic,
                    "tickCraftingLogic(Lappeng/api/networking/energy/IEnergyService;"
                            + "Lappeng/me/service/CraftingService;)V",
                    Opcodes.INVOKEVIRTUAL,
                    "cn/dancingsnow/neoecoae/api/me/ECOCraftingCPULogic",
                    "executeCrafting",
                    "(IILappeng/me/service/CraftingService;"
                            + "Lappeng/api/networking/energy/IEnergyService;"
                            + "Lnet/minecraft/world/level/Level;"
                            + "Lcn/dancingsnow/neoecoae/api/me/"
                            + "ECOCraftingCPULogic$FastPathBatchBudget;)I",
                    false,
                    1);
            assertProviderLookupCount(logic, COLLECT_PROVIDERS, 1);
            assertInvocationCount(
                    logic,
                    TRY_PUSH_SLOW_PATTERN,
                    Opcodes.INVOKEINTERFACE,
                    "appeng/api/networking/crafting/ICraftingProvider",
                    "pushPattern",
                    "(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z",
                    true,
                    1);
            assertWaitingForExtractCount(logic, 3);
            assertFastPathBusShape(jar);

            var execution = shape(jar,
                    "cn/dancingsnow/neoecoae/impl/crafting/fastpath/"
                            + "ECOExtractedPatternExecution.class");
            assertTrue(execution.methods.contains(
                    "create(Lappeng/api/crafting/IPatternDetails;"
                            + "Lcn/dancingsnow/neoecoae/impl/crafting/fastpath/"
                            + "ECOCompiledFastPathPattern;"
                            + "Lcn/dancingsnow/neoecoae/impl/crafting/fastpath/"
                            + "ECOFastPathPatternMetadata;"
                            + "[Lappeng/api/stacks/KeyCounter;Ljava/util/List;Z"
                            + "Lnet/minecraft/world/level/Level;)"
                            + "Lcn/dancingsnow/neoecoae/impl/crafting/fastpath/"
                            + "ECOExtractedPatternExecution;"));
            assertTrue(execution.methods.contains("fastPathEligible()Z"));
            assertTrue(execution.methods.contains(
                    "key()Lcn/dancingsnow/neoecoae/impl/crafting/fastpath/ECOFastPathKey;"));
            assertTrue(execution.methods.contains("inputItems()Ljava/util/List;"));
            assertTrue(execution.methods.contains("expectedOutputs()Ljava/util/List;"));
            assertTrue(execution.methods.contains("expectedContainerItems()Ljava/util/List;"));

            var compiled = shape(jar,
                    "cn/dancingsnow/neoecoae/impl/crafting/fastpath/"
                            + "ECOCompiledFastPathPattern.class");
            assertTrue(compiled.methods.contains(
                    "compile(Lappeng/api/crafting/IPatternDetails;)"
                            + "Lcn/dancingsnow/neoecoae/impl/crafting/fastpath/"
                            + "ECOCompiledFastPathPattern;"));
            assertTrue(compiled.methods.contains("canBuildFastPath(Ljava/util/List;)Z"));

            var metadata = shape(jar,
                    "cn/dancingsnow/neoecoae/impl/crafting/fastpath/"
                            + "ECOFastPathPatternMetadata.class");
            assertTrue(metadata.methods.contains(
                    "create(Lcn/dancingsnow/neoecoae/impl/crafting/fastpath/"
                            + "ECOCompiledFastPathPattern;[Lappeng/api/stacks/KeyCounter;"
                            + "Lnet/minecraft/world/level/Level;)"
                            + "Lcn/dancingsnow/neoecoae/impl/crafting/fastpath/"
                            + "ECOFastPathPatternMetadata;"));

            var eligibility = shape(jar,
                    "cn/dancingsnow/neoecoae/api/me/ECOFastPathEligibility.class");
            assertTrue(eligibility.methods.contains("isGloballyEnabled()Z"));
        }
    }

    private static JarFile preparedJar(String fileName) throws IOException {
        String directory = System.getProperty("thunderbolt.optionalModShapeDir");
        assertTrue(directory != null && !directory.isBlank(),
                "thunderbolt.optionalModShapeDir must identify the prepared optional-mod artifacts");
        Path path = Path.of(directory).resolve(fileName);
        assertTrue(Files.isRegularFile(path), "missing prepared optional-mod artifact: " + path);
        return new JarFile(path.toFile());
    }

    private static void assertCoreStateShape(JarFile jar, Shape logic) throws IOException {
        assertTrue(logic.fields.contains("job:Lcn/dancingsnow/neoecoae/api/me/ExecutingCraftingJob;"));
        assertTrue(logic.fields.contains("inventory:Lappeng/crafting/inv/ListCraftingInventory;"));
        assertTrue(logic.fields.contains("cpu:Lcn/dancingsnow/neoecoae/api/me/ECOCraftingCPU;"));
        assertTrue(logic.methods.contains(INSERT));
        assertTrue(logic.methods.contains(
                "tickCraftingLogic(Lappeng/api/networking/energy/IEnergyService;"
                        + "Lappeng/me/service/CraftingService;)V"));
        assertTrue(logic.methods.contains("finishJob(Z)V"));
        assertTrue(logic.methods.contains("postChange(Lappeng/api/stacks/AEKey;)V"));
        assertTrue(logic.methods.contains("readFromNBT(Lnet/minecraft/nbt/CompoundTag;"
                + "Lnet/minecraft/core/HolderLookup$Provider;)V"));
        assertTrue(logic.methods.contains("writeToNBT(Lnet/minecraft/nbt/CompoundTag;"
                + "Lnet/minecraft/core/HolderLookup$Provider;)V"));

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
    }

    private static void assertFastPathBusShape(JarFile jar) throws IOException {
        var bus = shape(jar,
                "cn/dancingsnow/neoecoae/blocks/entity/crafting/"
                        + "ECOCraftingPatternBusBlockEntity.class");
        assertTrue(bus.methods.contains("getAvailableThreadSlots()I"));
        assertTrue(bus.methods.contains(
                "findBatchFastPathOffer("
                        + "Lcn/dancingsnow/neoecoae/impl/crafting/fastpath/"
                        + "ECOExtractedPatternExecution;I)"
                        + "Lcn/dancingsnow/neoecoae/blocks/entity/crafting/"
                        + "ECOCraftingPatternBusBlockEntity$BatchFastPathOffer;"));
        assertTrue(bus.methods.contains(
                "pushPattern("
                        + "Lcn/dancingsnow/neoecoae/impl/crafting/fastpath/"
                        + "ECOExtractedPatternExecution;Ljava/util/UUID;)Z"));
        assertTrue(bus.methods.contains(
                "pushBatch("
                        + "Lcn/dancingsnow/neoecoae/impl/crafting/fastpath/"
                        + "ECOBatchCraftingRequest;"
                        + "Lcn/dancingsnow/neoecoae/blocks/entity/crafting/"
                        + "ECOCraftingPatternBusBlockEntity$BatchFastPathOffer;)Z"));
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
