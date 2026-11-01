/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mergetool;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Comparator;
import java.util.function.Function;

interface MemberMerger {
    void process(ClassNode client, ClassNode server);

    interface Members<T> extends Function<T, String>, Comparator<T> {
        default String apply(T member) {
            return member == null ? "null" : key(member);
        }

        String key(T member);
        T annotate(T member, boolean isClient);

        @Override
        default int compare(T first, T second) {
            if (first == second) return 0;
            if (first == null) return 1;
            if (second == null) return -1;
            return key(first).compareTo(key(second));
        }
    }

    static Members<FieldNode> fields(AnnotationVersion annotation) {
        return new Members<FieldNode>() {
            @Override
            public String key(FieldNode field) {
                return field.name;
            }

            @Override
            public FieldNode annotate(FieldNode field, boolean isClient) {
                if (annotation != null)
                    annotation.add(field, isClient);
                return field;
            }
        };
    }

    static Members<MethodNode> methods(AnnotationVersion annotation) {
        return new Members<MethodNode>() {
            @Override
            public String key(MethodNode method) {
                return method.name + method.desc;
            }

            @Override
            public MethodNode annotate(MethodNode method, boolean isClient) {
                if (annotation != null)
                    annotation.add(method, isClient);
                return method;
            }
        };
    }
}
