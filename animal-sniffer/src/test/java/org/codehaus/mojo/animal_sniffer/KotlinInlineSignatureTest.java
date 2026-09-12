/*
 * The MIT License
 *
 * Copyright (c) 2026 MojoHaus.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.codehaus.mojo.animal_sniffer;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import junit.framework.TestCase;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Exercises real signature checking, including negative controls for otherwise similar inline copies. */
public class KotlinInlineSignatureTest extends TestCase implements Opcodes {
    private static final String IGNORE = "Lorg/codehaus/mojo/animal_sniffer/IgnoreJRERequirement;";
    private static final String SMAP = "SMAP\nExample.kt\nKotlin\n*S Kotlin\n*F\n"
            + "+ 1 Example.kt\nsample/ExampleKt\n*L\n1#1,20:1\n4#1:21\n"
            + "*S KotlinDebug\n*F\n+ 1 Example.kt\nsample/ExampleKt\n*L\n7#1:21\n*E\n";

    public void testAnnotatedCopy() throws Exception {
        assertEquals(Collections.emptyList(), check(fixture(false)));
    }

    public void testDeclarationVisitedAfterCaller() throws Exception {
        ClassNode clazz = fixture(false);
        Collections.reverse(clazz.methods);
        assertEquals(Collections.emptyList(), check(clazz));
    }

    public void testUnrelatedAbstractMethod() throws Exception {
        ClassNode clazz = fixture(false);
        clazz.access |= ACC_ABSTRACT;
        clazz.methods.add(new MethodNode(ACC_PUBLIC | ACC_ABSTRACT, "unrelated", "()V", null, null));
        assertEquals(Collections.emptyList(), check(clazz));
    }

    public void testWithoutParameterAssertions() throws Exception {
        ClassNode clazz = fixture(false);
        MethodNode method = declaration(clazz);
        for (int i = 0; i < 3; i++) {
            method.instructions.remove(method.instructions.getFirst());
        }
        assertEquals(Collections.emptyList(), check(clazz));
    }

    public void testDirectCallOnSameMappedLineIsStillReported() throws Exception {
        ClassNode clazz = fixture(true);
        for (org.objectweb.asm.tree.AbstractInsnNode instruction : caller(clazz).instructions) {
            if (instruction instanceof LineNumberNode) {
                ((LineNumberNode) instruction).line = 21;
            }
        }
        assertViolationCount(1, clazz);
    }

    public void testDirectCallBeforeInlineCopyIsStillReported() throws Exception {
        ClassNode clazz = fixture(false);
        InsnList direct = new InsnList();
        direct.add(new VarInsnNode(ALOAD, 0));
        direct.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Thread", "threadId", "()J", false));
        direct.add(new InsnNode(POP2));
        caller(clazz).instructions.insert(direct);
        assertViolationCount(1, clazz);
    }

    public void testAnnotationRemovalReportsDeclarationAndCopy() throws Exception {
        ClassNode clazz = fixture(false);
        declaration(clazz).invisibleAnnotations.clear();
        assertViolationCount(2, clazz);
    }

    public void testCustomAndLegacyAnnotations() throws Exception {
        for (String annotation : Arrays.asList("test.Ignore", SignatureChecker.PREVIOUS_ANNOTATION_FQN)) {
            ClassNode clazz = fixture(false);
            declaration(clazz).invisibleAnnotations =
                    Collections.singletonList(new AnnotationNode(SignatureChecker.toAnnotationDescriptor(annotation)));
            assertEquals(Collections.emptyList(), check(clazz, Collections.singletonList(annotation)));
        }
    }

    public void testDisabledAnnotationDoesNotPropagate() throws Exception {
        assertEquals(
                2,
                check(fixture(false), Collections.singletonList("test.Ignore")).size());
    }

    public void testNumberedScopes() throws Exception {
        ClassNode clazz = fixture(false);
        declaration(clazz).localVariables.get(0).name += "\\0";
        scope(clazz).name += "\\1\\7";
        assertEquals(Collections.emptyList(), check(clazz));
    }

    public void testMalformedScopeName() throws Exception {
        ClassNode clazz = fixture(false);
        scope(clazz).name += "\\not-a-scope";
        assertViolationCount(1, clazz);
    }

    public void testMissingKotlinMetadata() throws Exception {
        ClassNode clazz = fixture(false);
        clazz.visibleAnnotations.clear();
        assertViolationCount(1, clazz);
    }

    public void testMissingSourceInformation() throws Exception {
        ClassNode clazz = fixture(false);
        clazz.sourceDebug = null;
        assertViolationCount(1, clazz);
        clazz.sourceDebug = SMAP;
        clazz.sourceFile = null;
        assertViolationCount(1, clazz);
    }

    public void testMissingLocalVariables() throws Exception {
        ClassNode clazz = fixture(false);
        caller(clazz).localVariables.clear();
        assertViolationCount(1, clazz);
    }

    public void testMissingDeclarationMarker() throws Exception {
        ClassNode clazz = fixture(false);
        declaration(clazz).localVariables.clear();
        assertViolationCount(1, clazz);
    }

    public void testPropertyCarrierIsNotAGetterAnnotation() throws Exception {
        ClassNode clazz = fixture(false);
        declaration(clazz).invisibleAnnotations.clear();
        MethodNode carrier = new MethodNode(
                ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC, "getIdApi$annotations", "(Ljava/lang/Thread;)V", null, null);
        carrier.visitAnnotation(IGNORE, false).visitEnd();
        carrier.visitInsn(RETURN);
        carrier.visitMaxs(0, 1);
        clazz.methods.add(carrier);
        assertViolationCount(2, clazz);
    }

    public void testForeignOriginDespiteMatchingLocalMethod() throws Exception {
        ClassNode clazz = fixture(false);
        clazz.sourceDebug = SMAP.replace("sample/ExampleKt", "library/ExampleKt");
        assertViolationCount(1, clazz);
    }

    public void testWrongSourceFileOrLine() throws Exception {
        for (String debug :
                Arrays.asList(SMAP.replace("+ 1 Example.kt", "+ 1 Other.kt"), SMAP.replace("4#1:21", "5#1:21"))) {
            ClassNode clazz = fixture(false);
            clazz.sourceDebug = debug;
            assertViolationCount(1, clazz);
        }
    }

    public void testMalformedAndOverlappingMappings() throws Exception {
        for (String debug : Arrays.asList(
                SMAP.replace("4#1:21", "4#1,0:21"),
                SMAP.replace("4#1:21", "4#1:21\n5#1:21"),
                SMAP.replace("4#1:21", "4#1:999999999999999999999"),
                SMAP.replace("4#1:21", "4#1:21,2147483647"),
                SMAP.replace("4#1:21", "4#7:21"),
                SMAP.replace("*L\n1#1", "*L\ninvalid\n1#1"))) {
            ClassNode clazz = fixture(false);
            clazz.sourceDebug = debug;
            assertViolationCount(1, clazz);
        }
    }

    public void testOverloadedNameIsAmbiguous() throws Exception {
        ClassNode clazz = fixture(false);
        MethodNode overload = new MethodNode(ACC_PUBLIC | ACC_STATIC, "getIdApi", "(Ljava/lang/Object;)J", null, null);
        overload.visitInsn(LCONST_0);
        overload.visitInsn(LRETURN);
        overload.visitMaxs(2, 1);
        clazz.methods.add(overload);
        assertViolationCount(1, clazz);
    }

    public void testComplexDeclarationOrCopy() throws Exception {
        ClassNode clazz = fixture(false);
        declaration(clazz).instructions.insert(declaration(clazz).localVariables.get(0).start, new InsnNode(NOP));
        assertViolationCount(1, clazz);
        clazz = fixture(false);
        caller(clazz).instructions.insert(scope(clazz).start, new InsnNode(NOP));
        assertViolationCount(1, clazz);
    }

    public void testInvocationMustMatch() throws Exception {
        ClassNode clazz = fixture(false);
        for (org.objectweb.asm.tree.AbstractInsnNode instruction : caller(clazz).instructions) {
            if (instruction instanceof MethodInsnNode) {
                ((MethodInsnNode) instruction).name = "otherId";
            }
        }
        assertEquals(1, check(clazz).size());
    }

    public void testNestedCallerLambdaCannotBeAbsorbed() throws Exception {
        ClassNode clazz = fixture(false);
        LocalVariableNode scope = scope(clazz);
        caller(clazz)
                .localVariables
                .add(new LocalVariableNode("$i$a$-getIdApi-caller$1", "I", null, scope.start, scope.end, 3));
        assertViolationCount(1, clazz);
    }

    public void testOverlappingExceptionRegionRemainsChecked() throws Exception {
        ClassNode clazz = fixture(false);
        LocalVariableNode scope = scope(clazz);
        caller(clazz).tryCatchBlocks.add(new TryCatchBlockNode(scope.start, scope.end, scope.end, null));
        assertViolationCount(1, clazz);
    }

    public void testBranchIntoInlineScopeRemainsChecked() throws Exception {
        ClassNode clazz = fixture(false);
        caller(clazz)
                .instructions
                .insert(caller(clazz).instructions.get(1), new JumpInsnNode(GOTO, scope(clazz).start));
        assertViolationCount(1, clazz);
    }

    public void testReturnTypeSuppressionIsSpecificToInvocation() throws Exception {
        ClassNode clazz = fixture(false);
        declaration(clazz).desc = "(Ljava/lang/Thread;)Lapi/NewType;";
        caller(clazz).desc = "(Ljava/lang/Thread;)Ljava/lang/Object;";
        for (MethodNode method : clazz.methods) {
            for (org.objectweb.asm.tree.AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode && ((MethodInsnNode) instruction).name.equals("threadId")) {
                    ((MethodInsnNode) instruction).desc = "()Lapi/NewType;";
                }
                if (instruction.getOpcode() == LRETURN) {
                    method.instructions.set(instruction, new InsnNode(ARETURN));
                }
            }
        }
        assertEquals(Collections.emptyList(), check(clazz));
        // A method's own descriptor is not covered by an inline invocation's suppression.
        caller(clazz).desc = "(Ljava/lang/Thread;)Lapi/NewType;";
        assertEquals(1, check(clazz).size());
    }

    public void testSourceMapRepeatAndIncrement() {
        KotlinSourceMap map = KotlinSourceMap.parse(SMAP.replace("4#1:21", "4#1,2:21,2"));
        assertNotNull(map);
        assertEquals(4, map.sourceLine(21, "sample/ExampleKt", "Example.kt"));
        assertEquals(4, map.sourceLine(22, "sample/ExampleKt", "Example.kt"));
        assertEquals(5, map.sourceLine(23, "sample/ExampleKt", "Example.kt"));
        assertEquals(-1, map.sourceLine(25, "sample/ExampleKt", "Example.kt"));
    }

    private void assertViolationCount(int count, ClassNode clazz) throws Exception {
        List<String> errors = check(clazz);
        assertEquals(errors.toString(), count, errors.size());
        for (String error : errors) {
            assertTrue(error, error.contains("Undefined reference: long Thread.threadId()"));
        }
    }

    private List<String> check(ClassNode clazz) throws Exception {
        return check(clazz, Arrays.asList(SignatureChecker.ANNOTATION_FQN, SignatureChecker.PREVIOUS_ANNOTATION_FQN));
    }

    private List<String> check(ClassNode clazz, List<String> annotations) throws Exception {
        List<String> errors = new ArrayList<>();
        Map<String, Clazz> signatures = new HashMap<>();
        signatures.put(
                "java/lang/Thread",
                new Clazz("java/lang/Thread", Collections.singleton("getId()J"), "java/lang/Object", new String[0]));
        SignatureChecker checker = new SignatureChecker(
                signatures,
                new HashSet<>(Arrays.asList("sample.*", "kotlin.*", "java.lang.Object")),
                new SignatureCheckerTest.TestLogger() {
                    @Override
                    public void error(String message) {
                        errors.add(message);
                    }
                });
        checker.setAnnotationTypes(annotations);
        checker.setSourcePath(Collections.emptyList());
        ClassWriter writer = new ClassWriter(0);
        clazz.accept(writer);
        checker.process("sample/ExampleKt.class", new ByteArrayInputStream(writer.toByteArray()));
        assertEquals(!errors.isEmpty(), checker.isSignatureBroken());
        return errors;
    }

    private static MethodNode declaration(ClassNode clazz) {
        return clazz.methods.get(0);
    }

    private static MethodNode caller(ClassNode clazz) {
        return clazz.methods.get(1);
    }

    private static LocalVariableNode scope(ClassNode clazz) {
        return caller(clazz).localVariables.get(0);
    }

    private static ClassNode fixture(boolean directCall) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V1_8, ACC_PUBLIC, "sample/ExampleKt", null, "java/lang/Object", null);
        writer.visitSource("Example.kt", SMAP);
        writer.visitAnnotation("Lkotlin/Metadata;", true).visitEnd();
        MethodVisitor method =
                writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "getIdApi", "(Ljava/lang/Thread;)J", null, null);
        method.visitAnnotation(IGNORE, false).visitEnd();
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitLdcInsn("<this>");
        method.visitMethodInsn(
                INVOKESTATIC,
                "kotlin/jvm/internal/Intrinsics",
                "checkNotNullParameter",
                "(Ljava/lang/Object;Ljava/lang/String;)V",
                false);
        method.visitInsn(ICONST_0);
        method.visitVarInsn(ISTORE, 1);
        Label start = new Label();
        Label end = new Label();
        method.visitLabel(start);
        method.visitLineNumber(4, start);
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Thread", "threadId", "()J", false);
        method.visitInsn(LRETURN);
        method.visitLabel(end);
        method.visitLocalVariable("$i$f$getIdApi", "I", null, start, end, 1);
        method.visitMaxs(2, 2);
        method.visitEnd();

        method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "caller", "(Ljava/lang/Thread;)J", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitVarInsn(ASTORE, 1);
        method.visitInsn(ICONST_0);
        method.visitVarInsn(ISTORE, 2);
        start = new Label();
        end = new Label();
        method.visitLabel(start);
        method.visitLineNumber(21, start);
        method.visitVarInsn(ALOAD, 1);
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Thread", "threadId", "()J", false);
        method.visitLabel(end);
        method.visitLineNumber(7, end);
        if (directCall) {
            method.visitInsn(POP2);
            method.visitVarInsn(ALOAD, 0);
            method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Thread", "threadId", "()J", false);
        }
        method.visitInsn(LRETURN);
        method.visitLocalVariable("$i$f$getIdApi", "I", null, start, end, 2);
        method.visitMaxs(2, 4);
        method.visitEnd();
        writer.visitEnd();
        ClassNode result = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(result, 0);
        return result;
    }
}
