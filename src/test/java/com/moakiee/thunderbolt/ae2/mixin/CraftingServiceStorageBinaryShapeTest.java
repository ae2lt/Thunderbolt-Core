package com.moakiee.thunderbolt.ae2.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class CraftingServiceStorageBinaryShapeTest {
    @Test
    void storageInsertHasTheStableCallerSideBridgeTarget() throws IOException {
        String resource = "/appeng/me/service/helpers/CraftingServiceStorage$1.class";
        try (var input = CraftingServiceStorageBinaryShapeTest.class.getResourceAsStream(resource)) {
            assertNotNull(input, "missing AE2 storage implementation " + resource);
            var calls = new int[1];
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(
                        int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals("insert")
                            || !descriptor.equals("(Lappeng/api/stacks/AEKey;"
                                    + "JLappeng/api/config/Actionable;"
                                    + "Lappeng/api/networking/security/IActionSource;)J")) {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(
                                int opcode,
                                String owner,
                                String invokedName,
                                String invokedDescriptor,
                                boolean isInterface) {
                            if (opcode == Opcodes.INVOKEVIRTUAL
                                    && owner.equals("appeng/me/service/CraftingService")
                                    && invokedName.equals("insertIntoCpus")
                                    && invokedDescriptor.equals("(Lappeng/api/stacks/AEKey;"
                                            + "JLappeng/api/config/Actionable;)J")) {
                                calls[0]++;
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            assertEquals(1, calls[0], "AE2 crafting storage insertion call site drifted");
        }
    }
}
