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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Optional binary-shape check; set {@code NEOECO_JAR} to the real NeoECO 1.3.4 artifact. */
class NeoEco134BinaryShapeTest {
    @Test
    void realJarMatchesEveryReflectiveAndInjectionTarget() throws IOException {
        String configured = System.getenv("NEOECO_JAR");
        Assumptions.assumeTrue(configured != null && Files.isRegularFile(Path.of(configured)));

        try (var jar = new JarFile(configured)) {
            var logic = shape(jar, "cn/dancingsnow/neoecoae/api/me/ECOCraftingCPULogic.class");
            assertTrue(logic.fields.contains("job:Lcn/dancingsnow/neoecoae/api/me/ExecutingCraftingJob;"));
            assertTrue(logic.fields.contains("inventory:Lappeng/crafting/inv/ListCraftingInventory;"));
            assertTrue(logic.fields.contains("cpu:Lcn/dancingsnow/neoecoae/api/me/ECOCraftingCPU;"));
            assertTrue(logic.methods.contains("insert(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;)J"));
            assertTrue(logic.methods.contains("executeCrafting(ILappeng/me/service/CraftingService;"
                    + "Lappeng/api/networking/energy/IEnergyService;Lnet/minecraft/world/level/Level;)I"));
            assertTrue(logic.methods.contains("finishJob(Z)V"));
            assertTrue(logic.methods.contains("postChange(Lappeng/api/stacks/AEKey;)V"));
            assertTrue(logic.methods.contains("readFromNBT(Lnet/minecraft/nbt/CompoundTag;"
                    + "Lnet/minecraft/core/HolderLookup$Provider;)V"));
            assertTrue(logic.methods.contains("writeToNBT(Lnet/minecraft/nbt/CompoundTag;"
                    + "Lnet/minecraft/core/HolderLookup$Provider;)V"));

            // Keep the two WrapOperation targets pinned to the real 1.3.4 bytecode, not merely
            // to methods that happen to exist somewhere in the dependency.
            assertInvocationCount(
                    logic,
                    "executeCrafting(ILappeng/me/service/CraftingService;"
                            + "Lappeng/api/networking/energy/IEnergyService;"
                            + "Lnet/minecraft/world/level/Level;)I",
                    Opcodes.INVOKEINTERFACE,
                    "appeng/api/networking/crafting/ICraftingProvider",
                    "pushPattern",
                    "(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z",
                    true,
                    1);
            // insert has SIMULATE then MODULATE calls. The mixin deliberately wraps ordinal 0.
            assertInvocationCount(
                    logic,
                    "insert(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;)J",
                    Opcodes.INVOKEVIRTUAL,
                    "appeng/crafting/inv/ListCraftingInventory",
                    "extract",
                    "(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;)J",
                    false,
                    2);

            var job = shape(jar, "cn/dancingsnow/neoecoae/api/me/ExecutingCraftingJob.class");
            assertTrue(job.fields.contains("link:Lappeng/crafting/CraftingLink;"));
            assertTrue(job.fields.contains("waitingFor:Lappeng/crafting/inv/ListCraftingInventory;"));
            assertTrue(job.fields.contains("timeTracker:Lcn/dancingsnow/neoecoae/api/me/ElapsedTimeTracker;"));
            assertTrue(job.fields.contains("finalOutput:Lappeng/api/stacks/GenericStack;"));
            assertTrue(job.fields.contains("remainingAmount:J"));

            var tracker = shape(jar, "cn/dancingsnow/neoecoae/api/me/ElapsedTimeTracker.class");
            assertTrue(tracker.methods.contains("decrementItems(JLappeng/api/stacks/AEKeyType;)V"));

            var cpu = shape(jar, "cn/dancingsnow/neoecoae/api/me/ECOCraftingCPU.class");
            assertTrue(cpu.methods.contains("markDirty()V"));
        }
    }

    private static Shape shape(JarFile jar, String entryName) throws IOException {
        var entry = jar.getJarEntry(entryName);
        assertTrue(entry != null, "missing " + entryName);
        var result = new Shape();
        try (var input = jar.getInputStream(entry)) {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(int access, String name, String descriptor,
                                               String signature, Object value) {
                    result.fields.add(name + ":" + descriptor);
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    String method = name + descriptor;
                    result.methods.add(method);
                    var calls = result.callsByMethod.computeIfAbsent(method, ignored -> new ArrayList<>());
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String invokedName,
                                                    String invokedDescriptor, boolean isInterface) {
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
