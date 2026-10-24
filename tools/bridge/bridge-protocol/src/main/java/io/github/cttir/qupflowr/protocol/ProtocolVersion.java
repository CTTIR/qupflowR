/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol;

import java.util.Objects;

/**
 * Version of the local bridge protocol.
 *
 * <p>The protocol version is a {@code major.minor} string. Clients and hosts are compatible when
 * the major versions are equal; minor extensions are negotiated through capabilities. The major
 * version also appears in every request path ({@code /v1/...}).
 *
 * @param major major version (breaking changes)
 * @param minor minor version (compatible extensions)
 */
public record ProtocolVersion(int major, int minor) {

    /** Protocol version served by this bridge. */
    public static final String CURRENT_STRING = "1.0";

    /** Parsed form of {@link #CURRENT_STRING}. */
    public static final ProtocolVersion CURRENT = parse(CURRENT_STRING);

    /** Path prefix of all endpoints of the current major version. */
    public static final String PATH_PREFIX = "/v" + CURRENT.major();

    /**
     * Creates a version.
     *
     * @param major major version, not negative
     * @param minor minor version, not negative
     */
    public ProtocolVersion {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("Protocol version components must not be negative");
        }
    }

    /**
     * Parses a strict {@code major.minor} version string (ASCII digits, no sign, no leading zeros
     * except a single {@code 0}).
     *
     * @param text version string such as {@code "1.0"}
     * @return the parsed version
     * @throws IllegalArgumentException if the text is not a valid protocol version
     */
    public static ProtocolVersion parse(String text) {
        Objects.requireNonNull(text, "text");
        int dot = text.indexOf('.');
        if (dot <= 0 || dot != text.lastIndexOf('.') || dot == text.length() - 1) {
            throw new IllegalArgumentException("Invalid protocol version: " + quote(text));
        }
        return new ProtocolVersion(
                component(text, 0, dot), component(text, dot + 1, text.length()));
    }

    /**
     * Checks whether a peer version string can talk to this bridge.
     *
     * @param text version string of the peer, may be null
     * @return true if the text is a valid version with the same major version as {@link #CURRENT}
     */
    public static boolean isCompatible(String text) {
        if (text == null) {
            return false;
        }
        try {
            return parse(text).major() == CURRENT.major();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int component(String text, int from, int to) {
        String part = text.substring(from, to);
        if (part.length() > 4 || (part.length() > 1 && part.charAt(0) == '0')) {
            throw new IllegalArgumentException("Invalid protocol version: " + quote(text));
        }
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("Invalid protocol version: " + quote(text));
            }
        }
        return Integer.parseInt(part);
    }

    private static String quote(String text) {
        String shortened = text.length() > 32 ? text.substring(0, 32) + "..." : text;
        return "'" + shortened + "'";
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
