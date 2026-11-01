/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mergetool;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

final class DefaultMemberMerger implements MemberMerger {
    private static final boolean DEBUG = false;
    private final AnnotationVersion annotation;

    DefaultMemberMerger(AnnotationVersion annotation) {
        this.annotation = annotation;
    }

    @Override
    public void process(ClassNode client, ClassNode server) {
        Members<FieldNode> fields = MemberMerger.fields(this.annotation);
        Members<MethodNode> methods = MemberMerger.methods(this.annotation);
        MethodOrder order = new MethodOrder(client, server);
        merge(client.name, server.name, client.fields, server.fields, fields, fields);
        merge(client.name, server.name, client.methods, server.methods, methods, order);
    }

    private <T> void merge(String cName, String sName, List<T> client, List<T> server, Members<T> members, Comparator<T> compare) {
        BiPredicate<T, T> eq = (first, second) -> Objects.equals(members.apply(first), members.apply(second));

        client.add(null);
        server.add(null);
        Set<String> serverMembers = new HashSet<>();
        for (T member : server)
            serverMembers.add(members.apply(member));
        List<T> common = new ArrayList<>();
        for (T member : client) {
            if (member != null && serverMembers.contains(members.apply(member)))
                common.add(member);
        }
        common.add(null);

        int i = 0, mi = 0;
        for (; i < client.size(); i++) {
            T ct = client.get(i);
            T st = server.get(i);
            T mt = common.get(mi);

            if (eq.test(ct, st)) {
                mi++;
                if (!eq.test(ct, mt))
                    throw new IllegalStateException("merged list is in bad state: " + members.apply(ct) + " " + members.apply(st) + " " + members.apply(mt));
                if (DEBUG)
                    System.out.printf("%d/%d %d/%d Both Shared  : %s %s\n", i, client.size(), mi, common.size(), sName, members.apply(st));
            } else if (eq.test(st, mt)) {
                server.add(i, members.annotate(ct, true));
                if (DEBUG)
                    System.out.printf("%d/%d %d/%d Server *add* : %s %s\n", i, client.size(), mi, common.size(), sName, members.apply(ct));
            } else if (eq.test(ct, mt)) {
                client.add(i, members.annotate(st, false));
                if (DEBUG)
                    System.out.printf("%d/%d %d/%d Client *add* : %s %s\n", i, client.size(), mi, common.size(), cName, members.apply(st));
            } else {
                int diff = compare.compare(ct, st);
                if (diff > 0) {
                    client.add(i, members.annotate(st, false));
                    if (DEBUG)
                        System.out.printf("%d/%d %d/%d Client *add* : %s %s\n", i, client.size(), mi, common.size(), cName, members.apply(st));
                } else {
                    server.add(i, members.annotate(ct, true));
                    if (DEBUG)
                        System.out.printf("%d/%d %d/%d Server *add* : %s %s\n", i, client.size(), mi, common.size(), sName, members.apply(ct));
                }
            }
        }

        if (i < server.size() || mi < common.size() || client.size() != server.size())
            throw new IllegalStateException("merged list is in bad state: " + i + " " + mi);

        client.remove(client.size() - 1);
        server.remove(server.size() - 1);
    }

    private static final class MethodOrder implements Comparator<MethodNode>, Function<MethodNode, String> {
        private final Map<MethodNode, Integer> firstLines = new IdentityHashMap<>();

        private MethodOrder(ClassNode client, ClassNode server) {
            findLines(client.methods);
            findLines(server.methods);
        }

        private void findLines(List<MethodNode> methods) {
            int firstLine = Integer.MAX_VALUE;
            for (int x = methods.size() - 1; x >= 0; x--) {
                MethodNode method = methods.get(x);
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof LineNumberNode) {
                        firstLine = ((LineNumberNode)insn).line;
                        break;
                    }
                }
                this.firstLines.put(method, firstLine);
            }
        }

        @Override
        public String apply(MethodNode method) {
            return method == null ? "null" : method.name + method.desc;
        }

        @Override
        public int compare(MethodNode first, MethodNode second) {
            if (first == second) return 0;
            if (first == null) return 1;
            if (second == null) return -1;
            return this.firstLines.get(first) - this.firstLines.get(second);
        }
    }
}
