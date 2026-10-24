/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Decision D019: the transport runs on QuPath's bundled runtime, which lacks {@code
 * jdk.httpserver}. Every class referenced by the compiled transport package must belong to {@code
 * java.base} or to the package itself.
 */
class TransportJavaBaseTest {

    private static final String OWN = "io/github/cttir/qupflowr/core/http/";

    @Test
    void transportReferencesOnlyJavaBase() throws IOException {
        Path classes = Path.of(System.getProperty("qupflowr.test.mainClassesDir")).resolve(OWN);
        List<Path> files;
        try (Stream<Path> stream = Files.list(classes)) {
            files = stream.filter(p -> p.toString().endsWith(".class")).sorted().toList();
        }
        assertTrue(files.size() >= 15, "found the transport classes: " + files.size());
        Set<String> foreign = new TreeSet<>();
        Set<String> javaClasses = new TreeSet<>();
        for (Path file : files) {
            ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
            for (PoolEntry entry : model.constantPool()) {
                if (entry instanceof ClassEntry classEntry) {
                    String name = classEntry.asInternalName();
                    while (name.startsWith("[")) {
                        name = name.substring(1);
                    }
                    if (name.startsWith("L") && name.endsWith(";")) {
                        name = name.substring(1, name.length() - 1);
                    }
                    if (name.length() == 1) {
                        continue; // primitive array component
                    }
                    if (name.startsWith(OWN)) {
                        continue;
                    }
                    javaClasses.add(name);
                }
            }
        }
        for (String name : javaClasses) {
            try {
                Class<?> type =
                        Class.forName(
                                name.replace('/', '.'),
                                false,
                                ClassLoader.getPlatformClassLoader());
                if (!"java.base".equals(type.getModule().getName())) {
                    foreign.add(name + " (" + type.getModule().getName() + ")");
                }
            } catch (ClassNotFoundException e) {
                foreign.add(name + " (not a platform class)");
            }
        }
        assertEquals(Set.of(), foreign);
        assertTrue(
                javaClasses.contains("java/nio/channels/ServerSocketChannel"),
                "non-vacuous: " + javaClasses);
    }
}
