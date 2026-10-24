/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.fixtures;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers. */
public final class Digests {

    private Digests() {}

    /**
     * SHA-256 of bytes.
     *
     * @param bytes content
     * @return lower-case hex digest
     */
    public static String sha256(byte[] bytes) {
        MessageDigest md = newDigest();
        md.update(bytes);
        return HexFormat.of().formatHex(md.digest());
    }

    /**
     * SHA-256 of a file, streamed.
     *
     * @param path file
     * @return lower-case hex digest
     * @throws IOException if the file cannot be read
     */
    public static String sha256(Path path) throws IOException {
        MessageDigest md = newDigest();
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                md.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }
}
