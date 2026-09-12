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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Recognizes copies of simple annotated Kotlin inline methods in the same class.
 * Debug scopes alone do not identify a declaration: require a unique method, the original
 * source location, and an exact body match. In particular, never suppress an entire caller
 * or an arbitrary inline range, which could include caller-supplied code.
 */
final class KotlinInlineMethodAnalyzer {
    private static final String FUNCTION_PREFIX = "$i$f$";
    private static final String LAMBDA_PREFIX = "$i$a$";
    private static final Pattern SCOPE_NUMBERS = Pattern.compile("(?:\\\\[0-9]+){1,2}");

    private KotlinInlineMethodAnalyzer() {}

    static Map<String, Set<Integer>> analyze(ClassReader reader, Collection<String> ignoredAnnotations) {
        // Inspect class annotations first; ordinary Java classes need no buffered instruction tree.
        boolean[] kotlin = {false};
        reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        kotlin[0] |= "Lkotlin/Metadata;".equals(descriptor);
                        return null;
                    }
                },
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!kotlin[0]) {
            return Collections.emptyMap();
        }
        // Buffer every method so matching does not depend on whether the declaration precedes its caller.
        ClassNode clazz = new ClassNode();
        reader.accept(clazz, ClassReader.SKIP_FRAMES);
        // Without source mappings, a debug scope name cannot establish where copied instructions came from.
        KotlinSourceMap sourceMap = KotlinSourceMap.parse(clazz.sourceDebug);
        if (sourceMap == null || clazz.sourceFile == null) {
            return Collections.emptyMap();
        }

        // Inline markers carry names, not method descriptors, so even an unannotated overload is ambiguous.
        Map<String, Integer> methodCounts = new HashMap<>();
        for (MethodNode method : clazz.methods) {
            methodCounts.merge(method.name, 1, Integer::sum);
        }
        // Index only annotated declarations whose complete bodies fit the restricted shape checked below.
        Map<String, Candidate> candidates = new HashMap<>();
        for (MethodNode method : clazz.methods) {
            if (methodCounts.get(method.name) == 1
                    && (hasAnnotation(method.visibleAnnotations, ignoredAnnotations)
                            || hasAnnotation(method.invisibleAnnotations, ignoredAnnotations))) {
                Candidate candidate = candidate(method);
                if (candidate != null) {
                    candidates.put(method.name, candidate);
                }
            }
        }
        if (candidates.isEmpty()) {
            return Collections.emptyMap();
        }

        // Search this class's methods for copies of those declarations; no dependency classes are consulted.
        Map<String, Set<Integer>> result = new HashMap<>();
        for (MethodNode method : clazz.methods) {
            if (method.localVariables == null) {
                continue;
            }
            for (LocalVariableNode scope : method.localVariables) {
                // Kotlin's synthetic integer local marks the instruction range of an inline expansion.
                Candidate candidate = candidates.get(functionName(scope.name));
                if (candidate == null || !"I".equals(scope.desc) || !initializedMarker(scope)) {
                    continue;
                }
                // In a copy, the receiver may use another local slot and the result stays on the stack:
                // require just its load and invocation, with no extra caller-supplied instructions.
                List<AbstractInsnNode> body = instructions(scope.start, scope.end);
                if (body.size() != 2
                        || body.get(0).getOpcode() != Opcodes.ALOAD
                        || !(body.get(1) instanceof MethodInsnNode)
                        || ((VarInsnNode) body.get(0)).var == scope.index) {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) body.get(1);
                int line = lineAt(call);
                // Match both the invoked member and its origin. A copied line must map back to the
                // declaration in this class, rather than merely sharing its original line number.
                if (!sameCall(candidate.call, call)
                        || line == candidate.line
                        || sourceMap.sourceLine(line, clazz.name, clazz.sourceFile) != candidate.line
                        || ambiguousScope(method, scope)) {
                    continue;
                }
                // SignatureChecker counts visitMethodInsn events in the same order. Recording only
                // this invocation leaves other references on the same line or in the same caller checked.
                int ordinal = 0;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction == call) {
                        result.computeIfAbsent(method.name + method.desc, key -> new HashSet<>())
                                .add(ordinal);
                        break;
                    }
                    if (instruction instanceof MethodInsnNode) {
                        ordinal++;
                    }
                }
            }
        }
        return result;
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations, Collection<String> ignored) {
        if (annotations != null) {
            for (AnnotationNode annotation : annotations) {
                if (ignored.contains(annotation.desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Candidate candidate(MethodNode method) {
        if (method.localVariables == null) {
            return null;
        }
        // Limit declarations to static methods taking one object, which becomes the instance-call receiver.
        Type[] arguments = Type.getArgumentTypes(method.desc);
        Type result = Type.getReturnType(method.desc);
        if ((method.access & Opcodes.ACC_STATIC) == 0
                || arguments.length != 1
                || arguments[0].getSort() != Type.OBJECT
                || result.getSort() == Type.VOID
                || !method.tryCatchBlocks.isEmpty()) {
            return null;
        }
        // Require the declaration's own inline marker and reject nested function or lambda expansions.
        LocalVariableNode marker = null;
        for (LocalVariableNode local : method.localVariables) {
            if (isInlineScope(local.name)) {
                if (marker != null || !method.name.equals(functionName(local.name)) || !"I".equals(local.desc)) {
                    return null;
                }
                marker = local;
            }
        }
        if (marker == null || marker.index == 0 || !initializedMarker(marker)) {
            return null;
        }
        // The original body must load parameter zero, invoke a member, and immediately return its result.
        List<AbstractInsnNode> body = instructions(marker.start, marker.end);
        if (body.size() != 3
                || body.get(0).getOpcode() != Opcodes.ALOAD
                || ((VarInsnNode) body.get(0)).var != 0
                || !(body.get(1) instanceof MethodInsnNode)
                || body.get(2).getOpcode() != result.getOpcode(Opcodes.IRETURN)) {
            return null;
        }
        // Accept only a no-argument instance invocation with the declared return type and no trailing code.
        MethodInsnNode call = (MethodInsnNode) body.get(1);
        int line;
        if ((call.getOpcode() != Opcodes.INVOKEVIRTUAL && call.getOpcode() != Opcodes.INVOKEINTERFACE)
                || Type.getArgumentTypes(call.desc).length != 0
                || !result.equals(Type.getReturnType(call.desc))
                || (line = lineAt(call)) <= 0
                || !instructions(marker.end, null).isEmpty()) {
            return null;
        }

        List<AbstractInsnNode> prologue = instructions(method.instructions.getFirst(), marker.start);
        // Outside the body, allow only the two marker-initialization instructions, optionally preceded
        // by Kotlin's three-instruction non-null parameter check. Any other prologue is unsupported.
        if (prologue.size() == 5) {
            if (prologue.get(0).getOpcode() != Opcodes.ALOAD
                    || ((VarInsnNode) prologue.get(0)).var != 0
                    || !(prologue.get(1) instanceof LdcInsnNode)
                    || !(((LdcInsnNode) prologue.get(1)).cst instanceof String)
                    || !(prologue.get(2) instanceof MethodInsnNode)) {
                return null;
            }
            MethodInsnNode check = (MethodInsnNode) prologue.get(2);
            if (check.getOpcode() != Opcodes.INVOKESTATIC
                    || check.itf
                    || !"kotlin/jvm/internal/Intrinsics".equals(check.owner)
                    || !"checkNotNullParameter".equals(check.name)
                    || !"(Ljava/lang/Object;Ljava/lang/String;)V".equals(check.desc)) {
                return null;
            }
        } else if (prologue.size() != 2) {
            return null;
        }
        return new Candidate(call, line);
    }

    private static boolean initializedMarker(LocalVariableNode scope) {
        // A local-variable name is insufficient: require ICONST_0 / ISTORE immediately before its scope starts.
        AbstractInsnNode store = previousInstruction(scope.start);
        AbstractInsnNode initializer;
        return store instanceof VarInsnNode
                && store.getOpcode() == Opcodes.ISTORE
                && ((VarInsnNode) store).var == scope.index
                && (initializer = previousInstruction(store)) != null
                && initializer.getOpcode() == Opcodes.ICONST_0;
    }

    private static boolean ambiguousScope(MethodNode method, LocalVariableNode scope) {
        int start = method.instructions.indexOf(scope.start);
        int end = method.instructions.indexOf(scope.end);
        if (start < 0 || end <= start) {
            return true;
        }
        // Allow an outer expansion that starts earlier and contains this copy. Nested, coincident,
        // or crossing scopes make ownership of the candidate instructions ambiguous.
        for (LocalVariableNode other : method.localVariables) {
            if (other == scope || !isInlineScope(other.name)) {
                continue;
            }
            int otherStart = method.instructions.indexOf(other.start);
            int otherEnd = method.instructions.indexOf(other.end);
            if (otherStart < 0 || otherEnd <= otherStart) {
                return true;
            }
            if (otherStart < end && otherEnd > start && !(otherStart < start && otherEnd >= end)) {
                return true;
            }
        }
        // Exception regions or handler entries overlapping the copy need analysis beyond this simple shape.
        for (TryCatchBlockNode handler : method.tryCatchBlocks) {
            if (method.instructions.indexOf(handler.start) < end && method.instructions.indexOf(handler.end) > start
                    || inside(method, handler.handler, start, end)) {
                return true;
            }
        }
        // Reject branches into the copy: linear instruction matching cannot establish their entry state.
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode && inside(method, ((JumpInsnNode) instruction).label, start, end)) {
                return true;
            }
            if (instruction instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode table = (TableSwitchInsnNode) instruction;
                if (inside(method, table.dflt, start, end) || anyInside(method, table.labels, start, end)) {
                    return true;
                }
            }
            if (instruction instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode table = (LookupSwitchInsnNode) instruction;
                if (inside(method, table.dflt, start, end) || anyInside(method, table.labels, start, end)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean anyInside(MethodNode method, List<LabelNode> labels, int start, int end) {
        for (LabelNode label : labels) {
            if (inside(method, label, start, end)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inside(MethodNode method, LabelNode label, int start, int end) {
        int position = method.instructions.indexOf(label);
        return position >= start && position < end;
    }

    private static boolean sameCall(MethodInsnNode a, MethodInsnNode b) {
        return a.getOpcode() == b.getOpcode()
                && a.itf == b.itf
                && a.owner.equals(b.owner)
                && a.name.equals(b.name)
                && a.desc.equals(b.desc);
    }

    private static String functionName(String marker) {
        if (!marker.startsWith(FUNCTION_PREFIX)) {
            return null;
        }
        String name = marker.substring(FUNCTION_PREFIX.length());
        // Strip only recognized numeric scope suffixes; unfamiliar marker formats remain unsupported.
        int suffix = name.indexOf('\\');
        if (suffix >= 0) {
            return SCOPE_NUMBERS.matcher(name.substring(suffix)).matches() ? name.substring(0, suffix) : null;
        }
        return name;
    }

    private static boolean isInlineScope(String name) {
        return name.startsWith(FUNCTION_PREFIX) || name.startsWith(LAMBDA_PREFIX);
    }

    private static int lineAt(AbstractInsnNode instruction) {
        // ASM line entries apply to the following instructions until another line entry appears.
        for (AbstractInsnNode previous = instruction; previous != null; previous = previous.getPrevious()) {
            if (previous instanceof LineNumberNode) {
                return ((LineNumberNode) previous).line;
            }
        }
        return -1;
    }

    private static AbstractInsnNode previousInstruction(AbstractInsnNode instruction) {
        AbstractInsnNode previous = instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) {
            previous = previous.getPrevious();
        }
        return previous;
    }

    private static List<AbstractInsnNode> instructions(AbstractInsnNode start, AbstractInsnNode end) {
        // Compare executable instructions in the half-open scope, ignoring labels, line entries, and frames.
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction = start; instruction != end; instruction = instruction.getNext()) {
            if (instruction == null) {
                return Collections.emptyList();
            }
            if (instruction.getOpcode() >= 0) {
                result.add(instruction);
            }
        }
        return result;
    }

    private static final class Candidate {
        final MethodInsnNode call;
        final int line;

        Candidate(MethodInsnNode call, int line) {
            this.call = call;
            this.line = line;
        }
    }
}
