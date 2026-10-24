/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.stream.Stream;

class BridgeRuntimeFilesTest {

    @TempDir Path tmp;

    @Test
    void writesPrivateFilesAtomicallyAndCleansUp() throws IOException {
        String id = UUID.randomUUID().toString();
        BridgeRuntimeFiles files = BridgeRuntimeFiles.prepare(tmp.resolve("a/b/runtime"), id);
        BridgeToken token = BridgeToken.generate(new SecureRandom());
        files.writeToken(token);
        files.writeManifest("{\"x\":1}\n");
        files.writeManifest("{\"x\":2}\n");
        assertEquals("{\"x\":2}\n", Files.readString(files.manifestPath()));
        assertEquals(token.value() + "\n", Files.readString(files.tokenPath()));
        assertEquals("rw-------", mode(files.manifestPath()));
        assertEquals("rw-------", mode(files.tokenPath()));
        assertEquals("rwx------", mode(tmp.resolve("a/b/runtime")));
        assertEquals("rwx------", mode(tmp.resolve("a/b/runtime/instances")));
        try (Stream<Path> list = Files.list(files.manifestPath().getParent())) {
            assertEquals(2, list.count(), "no temporary or probe files remain");
        }
        assertTrue(files.deleteManifest());
        assertFalse(files.deleteManifest());
        assertTrue(files.deleteToken());
        assertFalse(Files.exists(files.tokenPath()));
    }

    @Test
    void tightensPermissionsOfExistingDirectories() throws IOException {
        Path runtime = Files.createDirectories(tmp.resolve("runtime/instances")).getParent();
        Files.setPosixFilePermissions(runtime, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(
                runtime.resolve("instances"), PosixFilePermissions.fromString("rwxrwxrwx"));
        BridgeRuntimeFiles.prepare(runtime, UUID.randomUUID().toString());
        assertEquals("rwx------", mode(runtime));
        assertEquals("rwx------", mode(runtime.resolve("instances")));
    }

    @Test
    void refusesSymbolicLinksAndFiles() throws IOException {
        Path real = Files.createDirectory(tmp.resolve("real"));
        Path link = Files.createSymbolicLink(tmp.resolve("link"), real);
        assertThrows(
                IOException.class,
                () -> BridgeRuntimeFiles.prepare(link, UUID.randomUUID().toString()));
        Path instancesLink = Files.createDirectory(tmp.resolve("runtime2"));
        Files.createSymbolicLink(instancesLink.resolve("instances"), real);
        assertThrows(
                IOException.class,
                () -> BridgeRuntimeFiles.prepare(instancesLink, UUID.randomUUID().toString()));
        Path file = Files.writeString(tmp.resolve("file"), "x");
        assertThrows(
                IOException.class,
                () -> BridgeRuntimeFiles.prepare(file, UUID.randomUUID().toString()));
    }

    @Test
    void refusesNonCanonicalInstanceIds() {
        for (String bad :
                new String[] {"../../x", "abc", UUID.randomUUID().toString().toUpperCase()}) {
            assertThrows(
                    IllegalArgumentException.class, () -> BridgeRuntimeFiles.prepare(tmp, bad));
        }
    }

    private static String mode(Path path) throws IOException {
        return PosixFilePermissions.toString(
                Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
    }
}
