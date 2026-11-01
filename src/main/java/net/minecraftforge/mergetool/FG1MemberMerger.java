/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mergetool;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FG1MemberMerger implements MemberMerger {
    private final Members<FieldNode> fields;
    private final Members<MethodNode> methods;

    FG1MemberMerger(AnnotationVersion annotation) {
        this.fields = MemberMerger.fields(annotation);
        this.methods = MemberMerger.methods(annotation);
    }

    @Override
    public void process(ClassNode client, ClassNode server) {
        processFields(client.fields, server.fields);
        processMethods(client.methods, server.methods);
    }

    private void processFields(List<FieldNode> client, List<FieldNode> server) {
        int serverIndex = 0;
        for (int clientIndex = 0; clientIndex < client.size(); clientIndex++) {
            FieldNode clientField = client.get(clientIndex);
            if (serverIndex < server.size()) {
                FieldNode serverField = server.get(serverIndex);
                if (!this.fields.key(clientField).equals(this.fields.key(serverField))) {
                    boolean serverHasClientField = contains(server, clientField, serverIndex + 1);
                    if (serverHasClientField) {
                        boolean clientHasServerField = contains(client, serverField, clientIndex + 1);
                        if (!clientHasServerField) {
                            this.fields.annotate(serverField, false);
                            client.add(clientIndex, serverField);
                        }
                    } else {
                        this.fields.annotate(clientField, true);
                        server.add(serverIndex, clientField);
                    }
                }
            } else {
                this.fields.annotate(clientField, true);
                server.add(serverIndex, clientField);
            }
            serverIndex++;
        }

        for (int index = client.size(); index < server.size(); index++) {
            FieldNode serverField = server.get(index);
            this.fields.annotate(serverField, true);
            client.add(index++, serverField);
        }
    }

    private boolean contains(List<FieldNode> fields, FieldNode target, int start) {
        for (int index = start; index < fields.size(); index++) {
            if (this.fields.key(fields.get(index)).equals(this.fields.key(target)))
                return true;
        }
        return false;
    }

    private void processMethods(List<MethodNode> client, List<MethodNode> server) {
        LinkedHashMap<String, MergedMethod> merged = new LinkedHashMap<>();
        int clientPosition = 0;
        int serverPosition = 0;
        String clientName = "";
        String lastName = clientName;

        while (clientPosition < client.size() || serverPosition < server.size()) {
            while (serverPosition < server.size()) {
                MethodNode method = server.get(serverPosition);
                if (!method.name.equals(lastName) && clientPosition != client.size())
                    break;

                addMethod(merged, method, false);
                serverPosition++;
            }

            while (clientPosition < client.size()) {
                MethodNode method = client.get(clientPosition);
                lastName = clientName;
                clientName = method.name;
                if (!clientName.equals(lastName) && serverPosition != server.size())
                    break;

                addMethod(merged, method, true);
                clientPosition++;
            }
        }

        client.clear();
        server.clear();
        for (MergedMethod method : merged.values()) {
            client.add(method.node);
            server.add(method.node);
            if (!method.client || !method.server)
                this.methods.annotate(method.node, method.client);
        }
    }

    private void addMethod(Map<String, MergedMethod> merged, MethodNode method, boolean client) {
        MergedMethod match = merged.get(this.methods.key(method));
        if (match == null) {
            merged.put(this.methods.key(method), new MergedMethod(method, client));
            return;
        }
        else if (client)
            match.client = true;
        else
            match.server = true;
    }

    private static final class MergedMethod {
        private final MethodNode node;
        private boolean client;
        private boolean server;

        private MergedMethod(MethodNode node, boolean client) {
            this.node = node;
            this.client = client;
            this.server = !client;
        }
    }
}
