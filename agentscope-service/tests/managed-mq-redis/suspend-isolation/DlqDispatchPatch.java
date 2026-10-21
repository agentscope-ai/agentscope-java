/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.JarFile;

/** Diagnostic A/B patch for one isolated Broker; this is not a production fix. */
public final class DlqDispatchPatch {
    public static void main(String[] args) throws Exception {
        String entry = "org/apache/rocketmq/store/queue/MultiDispatchUtils.class";
        byte[] original;
        try (var jar = new JarFile(args[0])) {
            original = jar.getInputStream(jar.getJarEntry(entry)).readAllBytes();
        }
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        int[] returns = {0};
        new ClassReader(original)
                .accept(
                        new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                if (name.equals("<clinit>"))
                                    throw new IllegalStateException("Unexpected class initializer");
                                var delegate =
                                        super.visitMethod(
                                                access, name, descriptor, signature, exceptions);
                                if (!name.equals("isNeedHandleMultiDispatch")) return delegate;
                                if (!descriptor.equals(
                                        "(Lorg/apache/rocketmq/store/config/MessageStoreConfig;Ljava/lang/String;)Z"))
                                    throw new IllegalStateException("Unexpected method descriptor");
                                return new MethodVisitor(Opcodes.ASM9, delegate) {
                                    @Override
                                    public void visitInsn(int opcode) {
                                        if (opcode == Opcodes.IRETURN) {
                                            // Preserve the existing result and add only &&
                                            // !topic.startsWith("%DLQ%").
                                            super.visitVarInsn(Opcodes.ALOAD, 1);
                                            super.visitLdcInsn("%DLQ%");
                                            super.visitMethodInsn(
                                                    Opcodes.INVOKEVIRTUAL,
                                                    "java/lang/String",
                                                    "startsWith",
                                                    "(Ljava/lang/String;)Z",
                                                    false);
                                            super.visitInsn(Opcodes.ICONST_1);
                                            super.visitInsn(Opcodes.IXOR);
                                            super.visitInsn(Opcodes.IAND);
                                            returns[0]++;
                                        }
                                        super.visitInsn(opcode);
                                    }
                                };
                            }

                            @Override
                            public void visitEnd() {
                                var method =
                                        super.visitMethod(
                                                Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
                                method.visitCode();
                                method.visitFieldInsn(
                                        Opcodes.GETSTATIC,
                                        "java/lang/System",
                                        "err",
                                        "Ljava/io/PrintStream;");
                                method.visitLdcInsn("DIAGNOSTIC_DLQ_DISPATCH_FILTER_LOADED");
                                method.visitMethodInsn(
                                        Opcodes.INVOKEVIRTUAL,
                                        "java/io/PrintStream",
                                        "println",
                                        "(Ljava/lang/String;)V",
                                        false);
                                method.visitInsn(Opcodes.RETURN);
                                method.visitMaxs(0, 0);
                                method.visitEnd();
                                super.visitEnd();
                            }
                        },
                        0);
        if (returns[0] != 1)
            throw new IllegalStateException("Unexpected patch count: " + returns[0]);
        byte[] patched = writer.toByteArray();
        Path target = Path.of(args[1]).resolve(entry);
        Files.createDirectories(target.getParent());
        Files.write(target, patched);
        System.out.println(
                "Added only a DLQ exclusion to the multi-dispatch predicate, plus a load marker.");
        System.out.println("Original SHA256: " + sha256(original));
        System.out.println("Patched SHA256: " + sha256(patched));
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
