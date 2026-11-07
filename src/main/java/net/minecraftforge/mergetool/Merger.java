/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mergetool;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class Merger {
    private static final boolean DEBUG = false;

    private final File client;
    private final File server;
    private final File merged;
    private AnnotationVersion annotation = null;
    private boolean annotationInject = true;
    private MemberMerger members = new DefaultMemberMerger(null);
    private MemberMerger fg1Members = new FG1MemberMerger(null);
    private HashSet<String> whitelist = new HashSet<>();
    private Predicate<String> filter = name -> this.whitelist.isEmpty() || this.whitelist.contains(name);
    private boolean copyData = false;
    private boolean keepMeta = false;
    private boolean bundledServerJar = false;
    private boolean sortInterfaces = false;
    private boolean fg1 = false;

    public Merger(File client, File server, File merged) {
        this.client = client;
        this.server = server;
        this.merged = merged;
    }

    public Merger annotate(AnnotationVersion ano, boolean inject) {
        this.annotation = ano;
        this.annotationInject = inject;
        this.members = new DefaultMemberMerger(ano);
        this.fg1Members = new FG1MemberMerger(ano);
        return this;
    }

    public Merger whitelist(String file) {
        this.whitelist.add(file);
        return this;
    }

    /*
     * The predicate is called with the class name in binary format {essentially the path to the class file, minus the .class suffix)
     * Anything that does not return true from this predicate will be filtered out.
     */
    public Merger filter(Predicate<String> filter) {
        this.filter = filter;
        return this;
    }

    public Merger keepData() {
        this.copyData = true;
        return this;
    }

    public Merger skipData() {
        this.copyData = false;
        return this;
    }

    public Merger keepMeta() {
        this.keepMeta = true;
        return this;
    }

    public Merger skipMeta() {
        this.keepMeta = false;
        return this;
    }

    public Merger bundledServerJar() {
        this.bundledServerJar = true;
        return this;
    }

    public Merger sortInterfaces() {
        this.sortInterfaces = true;
        return this;
    }

    public Merger fg1() {
        this.fg1 = true;
        return this;
    }

    public void process() throws IOException {
        try (ZipOutputStream outJar = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(this.merged)))) {
            Set<String> added = new HashSet<>();
            if (this.annotation != null) {
                for (String cls : this.annotation.getClasses())
                    added.add(cls + ".class");
            }
            Map<String, byte[]> cClasses = getClassEntries(this.client, outJar, added);
            Map<String, byte[]> sClasses;
            if (this.bundledServerJar)
                sClasses = getBundledClassEntries(this.server, outJar, null);
            else
                sClasses = getClassEntries(this.server, outJar, null); //Skip data from the server, as it contains libraries.

            for (Entry<String, byte[]> entry : cClasses.entrySet()) {
                String name = entry.getKey();

                if (!this.filter.test(name) || added.contains(name + ".class"))
                    continue;

                byte[] cData = entry.getValue();
                byte[] sData = sClasses.get(name);

                if (sData == null) {
                    if (DEBUG)
                        System.out.println("Copy class c->s : " + name);
                    copyClass(name, cData, outJar, true);
                } else {
                    if (DEBUG)
                        System.out.println("Processing class: " + name);

                    sClasses.remove(name);

                    byte[] data = processClass(cData, sData);

                    outJar.putNextEntry(getNewEntry(name + ".class"));
                    outJar.write(data);
                }
            }

            for (Entry<String, byte[]> entry : sClasses.entrySet()) {
                String name = entry.getKey();
                if (!this.filter.test(name))
                    continue;

                if (DEBUG)
                    System.out.println("Copy class s->c : " + name);
                copyClass(name, entry.getValue(), outJar, false);
            }

            if (this.annotation != null && this.annotationInject) {
                for (String cls : this.annotation.getClasses()) {
                    byte[] data = getResourceBytes(cls);

                    outJar.putNextEntry(getNewEntry(cls + ".class"));
                    outJar.write(data);
                }
            }

        }
    }

    private ZipEntry getNewEntry(String name) {
        ZipEntry ret = new ZipEntry(name);
        ret.setTime(0x92D6688800L); //Stabilize output as java will use current time if we don't set this, we can't use 0 as older java versions output different jars for values less then 1980
        return ret;
    }

    private void copyClass(String name, byte[] entry, ZipOutputStream outJar, boolean isClientOnly) throws IOException {
        ClassReader reader = new ClassReader(entry);
        ClassNode classNode = new ClassNode();

        reader.accept(classNode, 0);

        if (this.annotation != null)
            this.annotation.add(classNode, isClientOnly);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);

        byte[] data = writer.toByteArray();

        outJar.putNextEntry(getNewEntry(name + ".class"));
        outJar.write(data);
    }

    private Map<String, byte[]> getClassEntries(File inFile, ZipOutputStream output, Set<String> added) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(inFile))) {
            return getClassEntries(zin, output, added);
        }
    }

    private Map<String, byte[]> getClassEntries(ZipInputStream input, ZipOutputStream output, Set<String> added) throws IOException {
        Map<String, byte[]> ret = new HashMap<>();
        for (ZipEntry entry; (entry = input.getNextEntry()) != null; ) {
            String entryName = entry.getName();
            if (!entry.isDirectory() && entryName.endsWith(".class") && !entryName.startsWith(".")) {
                entryName = entryName.substring(0, entryName.length() - 6);
                byte[] data = readFully(input);
                ret.put(entryName, data);
            } else if (this.copyData && added != null && !added.contains(entryName)) {
                if (!this.keepMeta && entryName.startsWith("META-INF"))
                    continue;

                if (entry.isDirectory()) {
                    //Skip directories, they arnt required.
                    //output.putNextEntry(getNewEntry(entryName)); //New entry to reset time
                    added.add(entryName);
                } else {
                    output.putNextEntry(getNewEntry(entryName));
                    copy(input, output);
                    added.add(entryName);
                }
            }
        }
        return ret;
    }

    private static final Attributes.Name BUNDLER_FORMAT = new Attributes.Name("Bundler-Format");
    private static final String VERSIONS_LIST = "META-INF/versions.list";
    private Map<String, byte[]> getBundledClassEntries(File inFile, ZipOutputStream output, Set<String> added) throws IOException {
        try (ZipFile zin = new ZipFile(inFile)) {
            ZipEntry mfEntry = zin.getEntry(JarFile.MANIFEST_NAME);
            if (mfEntry == null)
                throw new IOException("Invalid bundled server jar, Missing " + JarFile.MANIFEST_NAME);

            Manifest mf = new Manifest(zin.getInputStream(mfEntry));
            String format = mf.getMainAttributes().getValue(BUNDLER_FORMAT);
            if (format == null)
                throw new IOException("Invalid bundled server jar, Missing " + BUNDLER_FORMAT + " manifest entry");

            if (!"1.0".equals(format))
                throw new IOException("Unsupported Bundler-Format: " + format);

            ZipEntry verEntry = zin.getEntry(VERSIONS_LIST);
            if (verEntry == null)
                throw new IllegalStateException("Bundled Jar missing " + VERSIONS_LIST);

            List<BundleEntry> verList = readList(zin.getInputStream(verEntry));
            if (verList.size() != 1)
                throw new IllegalStateException("Invalid bundler " + VERSIONS_LIST + " file, " + verList.size() + " entries, expected 1");

            String serverJarName = "META-INF/versions/" + verList.get(0).path;
            ZipEntry serverJarEntry = zin.getEntry(serverJarName);
            if (serverJarEntry == null)
                throw new IOException("Invalid bundled server jar, Missing jar entry " + serverJarName);

            try (ZipInputStream server = new ZipInputStream(zin.getInputStream(serverJarEntry))) {
                return getClassEntries(server, output, added);
            }
        }
    }

    private static List<BundleEntry> readList(InputStream in) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            List<BundleEntry> ret = new ArrayList<>();
            while (reader.ready()) {
                String line = reader.readLine();
                String[] pts = line.split("\t");
                if (pts.length != 3)
                    throw new IOException("Invalid bunder list line: " + line);
                ret.add(new BundleEntry(pts[0], pts[1], pts[2]));
            }
            return ret;
        }
    }

    private byte[] readFully(InputStream stream) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        copy(stream, buf);
        return buf.toByteArray();
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] data = new byte[4096];
        int len;
        do {
            len = input.read(data);
            if (len > 0)
                output.write(data, 0, len);
        } while (len != -1);
    }

    private byte[] processClass(byte[] cIn, byte[] sIn) {
        ClassNode cClassNode = getClassNode(cIn);
        ClassNode sClassNode = getClassNode(sIn);

        (this.fg1 ? this.fg1Members : this.members).process(cClassNode, sClassNode);
        processInners(cClassNode, sClassNode);
        processInterfaces(cClassNode, sClassNode);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cClassNode.accept(writer);
        return writer.toByteArray();
    }

    private boolean innerMatches(InnerClassNode o, InnerClassNode o2) {
        return equals(o.innerName, o2.innerName) &&
               equals(o.name,      o2.name) &&
               equals(o.outerName, o2.outerName);
    }

    private boolean equals(Object o1, Object o2) {
        return o1 == null ? o2 == null : o2 == null ? false : o1.equals(o2);
    }

    private void processInners(ClassNode cClass, ClassNode sClass) {
        List<InnerClassNode> cIners = cClass.innerClasses;
        List<InnerClassNode> sIners = sClass.innerClasses;

        for (InnerClassNode n : cIners) {
            if (!sIners.stream().anyMatch(e -> innerMatches(e, n)))
                sIners.add(n);
        }

        for (InnerClassNode n : sIners) {
            if (!cIners.stream().anyMatch(e -> innerMatches(e, n)))
                cIners.add(n);
        }
    }

    private void processInterfaces(ClassNode cClass, ClassNode sClass) {
        List<String> cIntfs = cClass.interfaces;
        List<String> sIntfs = sClass.interfaces;
        List<String> cOnly = new ArrayList<>();
        List<String> sOnly = new ArrayList<>();

        for (String n : cIntfs) {
            if (!sIntfs.contains(n)) {
                sIntfs.add(n);
                cOnly.add(n);
            }
        }

        for (String n : sIntfs) {
            if (!cIntfs.contains(n)) {
                cIntfs.add(n);
                sOnly.add(n);
            }
        }

        if (sortInterfaces) {
            Collections.sort(cIntfs); //Sort things, we're in obf territory but should stabilize things.
            Collections.sort(sIntfs);
        }

        if (this.annotation != null && (!cOnly.isEmpty() || !sOnly.isEmpty())) {
            this.annotation.add(cClass, cOnly, sOnly);
            this.annotation.add(sClass, cOnly, sOnly);
        }
    }

    private ClassNode getClassNode(byte[] data) {
        ClassReader reader = new ClassReader(data);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);
        return classNode;
    }

    private byte[] getResourceBytes(String path) throws IOException {
        // If we're in the built jar, use the relocated classes {prevents them being
        InputStream stream = Merger.class.getResourceAsStream("/markers/" + path + ".marker");
        // If not, then try and get them from the classpath
        if (stream == null)
            stream = Merger.class.getResourceAsStream("/" + path + ".class");
        if (stream == null)
            throw new IllegalStateException("Could not find marker files: " + path);

        try {
            return readFully(stream);
        } finally {
            stream.close();
        }
    }


    @SuppressWarnings("unused")
    private static class BundleEntry {
        public final String hash;
        public final String artifact;
        public final String path;

        private BundleEntry(String hash, String artifact, String path) {
            this.hash = hash;
            this.artifact = artifact;
            this.path = path;
        }
    }
}
