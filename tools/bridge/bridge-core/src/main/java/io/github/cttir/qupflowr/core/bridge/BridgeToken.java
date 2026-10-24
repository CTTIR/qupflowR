/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.bridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 256-bit bearer token of the local master API.
 *
 * <p>The value is only exposed to write the private token file. Verification hashes the presented
 * value first, so the comparison always runs over two 32-byte SHA-256 digests with {@link
 * MessageDigest#isEqual(byte[], byte[])} (constant time for equal lengths) and neither the token
 * length nor a matching prefix is observable through timing. One digest comparison is performed
 * even when no credentials were presented.
 */
public final class BridgeToken {

    /** Number of random bytes (256 bits). */
    public static final int TOKEN_BYTES = 32;

    private static final String BEARER_PREFIX = "bearer ";

    private final String value;
    private final byte[] digest;

    private BridgeToken(String value) {
        this.value = value;
        this.digest = sha256(value);
    }

    /**
     * Generates a token.
     *
     * @param random cryptographically strong random source
     * @return token encoded as base64url without padding (43 characters)
     */
    public static BridgeToken generate(SecureRandom random) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return new BridgeToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    /**
     * Returns the token value; only for writing the private token file. Never log or return it.
     *
     * @return encoded token
     */
    String value() {
        return value;
    }

    /**
     * Checks the Authorization header values of a request. Exactly one header with the
     * case-insensitive {@code Bearer} scheme is accepted.
     *
     * @param authorizationHeaders all values of the Authorization header (may be null)
     * @return true if the single header carries this token
     */
    public boolean matchesAuthorizationHeaders(List<String> authorizationHeaders) {
        String presented = null;
        if (authorizationHeaders != null && authorizationHeaders.size() == 1) {
            String header = authorizationHeaders.get(0);
            if (header != null
                    && header.length() > BEARER_PREFIX.length()
                    && header.substring(0, BEARER_PREFIX.length())
                            .toLowerCase(Locale.ROOT)
                            .equals(BEARER_PREFIX)) {
                presented = header.substring(BEARER_PREFIX.length()).trim();
            }
        }
        byte[] presentedDigest = sha256(presented == null ? "" : presented);
        boolean equal = MessageDigest.isEqual(presentedDigest, digest);
        return presented != null && equal;
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Override
    public String toString() {
        return "BridgeToken[redacted]";
    }
}
