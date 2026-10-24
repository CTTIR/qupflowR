/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.json;

import java.io.Serial;

/**
 * JSON that violates the protocol JSON rules (malformed input, duplicate object keys, excessive
 * nesting, non-finite numbers).
 */
public class ProtocolJsonException extends IllegalArgumentException {

    @Serial private static final long serialVersionUID = 1L;

    /** JSON pointer (RFC 6901) of the offending value; empty for the document root. */
    private final String pointer;

    /**
     * Creates an exception.
     *
     * @param pointer JSON pointer of the offending location ({@code ""} for the root)
     * @param message description without untrusted document content
     */
    public ProtocolJsonException(String pointer, String message) {
        super(message + (pointer == null ? "" : " at '" + pointer + "'"));
        this.pointer = pointer == null ? "" : pointer;
    }

    /**
     * Creates an exception with a cause.
     *
     * @param pointer JSON pointer of the offending location ({@code ""} for the root)
     * @param message description without untrusted document content
     * @param cause underlying parser exception
     */
    public ProtocolJsonException(String pointer, String message, Throwable cause) {
        super(message + (pointer == null ? "" : " at '" + pointer + "'"), cause);
        this.pointer = pointer == null ? "" : pointer;
    }

    /**
     * Returns the JSON pointer of the offending value.
     *
     * @return RFC 6901 pointer, {@code ""} for the root
     */
    public String pointer() {
        return pointer;
    }
}
