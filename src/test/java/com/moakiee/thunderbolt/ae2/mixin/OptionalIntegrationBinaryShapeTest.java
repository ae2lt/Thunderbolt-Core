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

/** Locks Thunderbolt's optional mixins to the published Forge 1.20.1 addon binaries. */
class OptionalIntegrationBinaryShapeTest {
    private static final String PUSH_PATTERN_DESCRIPTOR =
            "(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z";
    private static final String ADV_EXECUTE_CRAFTING =
            "executeCrafting(ILappeng/me/service/CraftingService;"
                    + "Lappeng/api/networking/energy/IEnergyService;"
                    + "Lnet/minecraft/world/level/Level;)I";

    @Test
    void advancedAe136MatchesCpuInjectionTargets() throws IOException {
        try (var jar = preparedJar("advancedae-1.3.6-1.20.1.jar")) {
            var logic = shape(jar,
                    "net/pedroksl/advanced_ae/common/logic/AdvCraftingCPULogic.class");
            assertTrue(logic.fields.contains(
                    "cpu:Lnet/pedroksl/advanced_ae/common/cluster/AdvCraftingCPU;"));
            assertTrue(logic.fields.contains(
                    "job:Lnet/pedroksl/advanced_ae/common/logic/ExecutingCraftingJob;"));
            assertTrue(logic.fields.contains(
                    "inventory:Lappeng/crafting/inv/ListCraftingInventory;"));
            assertTrue(logic.methods.contains(ADV_EXECUTE_CRAFTING));
            assertTrue(logic.methods.contains(
                    "insert(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;)J"));
            assertTrue(logic.methods.contains("finishJob(Z)V"));
            assertTrue(logic.methods.contains("postChange(Lappeng/api/stacks/AEKey;)V"));
            assertTrue(logic.methods.contains("readFromNBT(Lnet/minecraft/nbt/CompoundTag;)V"));
            assertTrue(logic.methods.contains("writeToNBT(Lnet/minecraft/nbt/CompoundTag;)V"));

            assertCallCount(
                    logic,
                    "tickCraftingLogic(Lappeng/api/networking/energy/IEnergyService;"
                            + "Lappeng/me/service/CraftingService;)V",
                    Opcodes.INVOKEVIRTUAL,
                    "net/pedroksl/advanced_ae/common/logic/AdvCraftingCPULogic",
                    "executeCrafting",
                    "(ILappeng/me/service/CraftingService;"
                            + "Lappeng/api/networking/energy/IEnergyService;"
                            + "Lnet/minecraft/world/level/Level;)I",
                    false,
                    1);
            assertCallCount(
                    logic,
                    ADV_EXECUTE_CRAFTING,
                    Opcodes.INVOKEVIRTUAL,
                    "appeng/me/service/CraftingService",
                    "getProviders",
                    "(Lappeng/api/crafting/IPatternDetails;)Ljava/lang/Iterable;",
                    false,
                    1);
            assertCallCount(
                    logic,
                    ADV_EXECUTE_CRAFTING,
                    Opcodes.INVOKEINTERFACE,
                    "appeng/api/networking/crafting/ICraftingProvider",
                    "pushPattern",
                    PUSH_PATTERN_DESCRIPTOR,
                    true,
                    1);
            assertCallCount(
                    logic,
                    "insert(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;)J",
                    Opcodes.INVOKEVIRTUAL,
                    "appeng/crafting/inv/ListCraftingInventory",
                    "extract",
                    "(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;)J",
                    false,
                    2);

            var job = shape(jar,
                    "net/pedroksl/advanced_ae/common/logic/ExecutingCraftingJob.class");
            assertTrue(job.fields.contains("link:Lappeng/crafting/CraftingLink;"));
            assertTrue(job.fields.contains(
                    "waitingFor:Lappeng/crafting/inv/ListCraftingInventory;"));
            assertTrue(job.fields.contains("tasks:Ljava/util/Map;"));
            assertTrue(job.fields.contains(
                    "timeTracker:Lnet/pedroksl/advanced_ae/common/logic/ElapsedTimeTracker;"));
            assertTrue(job.fields.contains("finalOutput:Lappeng/api/stacks/GenericStack;"));
            assertTrue(job.fields.contains("remainingAmount:J"));

            var tracker = shape(jar,
                    "net/pedroksl/advanced_ae/common/logic/ElapsedTimeTracker.class");
            assertTrue(tracker.methods.contains(
                    "decrementItems(JLappeng/api/stacks/AEKeyType;)V"));

            var cpu = shape(jar,
                    "net/pedroksl/advanced_ae/common/cluster/AdvCraftingCPU.class");
            assertTrue(cpu.methods.contains("markDirty()V"));
            assertTrue(cpu.methods.contains("updateOutput(Lappeng/api/stacks/GenericStack;)V"));
        }
    }

    @Test
    void ae2CraftingTree101StillCastsSummaryPlansToConcreteAe2Plans() throws IOException {
        try (var jar = preparedJar("ae2-crafting-tree-refreshed-1.0.1.jar")) {
            var mixin = shape(jar,
                    "com/vcwdfca/ae2ct/mixin/AE2CraftingPlanSummary.class");
            String handler = "buildEX(Lappeng/api/networking/IGrid;"
                    + "Lappeng/api/networking/security/IActionSource;"
                    + "Lappeng/api/networking/crafting/ICraftingPlan;"
                    + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V";
            assertTrue(mixin.methods.contains(handler));
            assertCallCount(
                    mixin,
                    handler,
                    Opcodes.INVOKESTATIC,
                    "com/vcwdfca/ae2ct/api/RecipeHelper",
                    "fromCraftingPlan",
                    "(Lappeng/crafting/CraftingPlan;)Lcom/vcwdfca/ae2ct/api/RecipeHelper;",
                    false,
                    1);
        }
    }

    @Test
    void extendedAePlus155ExposesTheVirtualCompletionBridgeUsedBySuppression() throws IOException {
        try (var jar = preparedJar("extendedae-plus-1.5.5.jar")) {
            var compat = shape(jar,
                    "com/extendedae_plus/mixin/ae2/compat/PatternProviderLogicCompatMixin.class");
            assertTrue(compat.methods.contains("eap$compatIsVirtualCraftingEnabled()Z"));

            assertVirtualCompletionCallback(jar,
                    "com/extendedae_plus/mixin/ae2/compat/"
                            + "PatternProviderLogicVirtualCompletionMixin.class",
                    "eap$ae2VirtualCompletion");
            assertVirtualCompletionCallback(jar,
                    "com/extendedae_plus/mixin/advancedae/compat/"
                            + "PatternProviderLogicVirtualCompletionMixin.class",
                    "eap$advancedaeVirtualCompletion");
            assertVirtualCompletionCallback(jar,
                    "com/extendedae_plus/mixin/neoecoae/compat/"
                            + "PatternProviderLogicVirtualCompletionMixin.class",
                    "eap$neoecoaeVirtualCompletion");
        }
    }

    private static void assertVirtualCompletionCallback(
            JarFile jar, String className, String handlerName) throws IOException {
        var mixin = shape(jar, className);
        String handler = handlerName
                + "(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;"
                + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V";
        assertTrue(mixin.methods.contains(handler));
        assertCallCount(
                mixin,
                handler,
                Opcodes.INVOKEINTERFACE,
                "com/extendedae_plus/compat/PatternProviderLogicVirtualCompatBridge",
                "eap$compatIsVirtualCraftingEnabled",
                "()Z",
                true,
                1);
    }

    private static JarFile preparedJar(String fileName) throws IOException {
        String directory = System.getProperty("thunderbolt.optionalModShapeDir");
        assertTrue(directory != null && !directory.isBlank(),
                "thunderbolt.optionalModShapeDir must identify the prepared optional-mod artifacts");
        Path path = Path.of(directory).resolve(fileName);
        assertTrue(Files.isRegularFile(path), "missing prepared optional-mod artifact: " + path);
        return new JarFile(path.toFile());
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

    private static void assertCallCount(
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
