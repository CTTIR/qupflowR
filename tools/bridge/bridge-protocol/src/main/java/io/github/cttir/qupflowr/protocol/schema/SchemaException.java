/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.schema;

import java.io.Serial;
import java.util.Objects;

/**
 * A schema that is unsupported, invalid or cannot be resolved (code {@code SCHEMA_INVALID}).
 *
 * <p>{@link #reason()} is the machine-readable reason the R package reports in {@code
 * details$reason}, for example {@code unsupported_keyword}, {@code unresolved_ref} or {@code
 * ref_outside_schema_root}.
 */
public final class SchemaException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    /** Error code of every schema problem. */
    public static final String CODE = "SCHEMA_INVALID";

    private final String reason;
    private final String schema;
    private final String detail;

    /**
     * Creates an exception.
     *
     * @param reason machine-readable reason
     * @param schema file name of the schema that has the problem
     * @param detail additional information (a reference, a pointer or an issue), may be null
     */
    public SchemaException(String reason, String schema, String detail) {
        super(CODE + " (" + reason + ") in " + schema + (detail == null ? "" : ": " + detail));
        this.reason = Objects.requireNonNull(reason, "reason");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.detail = detail;
    }

    /**
     * Returns the error code.
     *
     * @return {@value #CODE}
     */
    public String code() {
        return CODE;
    }

    /**
     * Returns the machine-readable reason.
     *
     * @return reason such as {@code unsupported_keyword}
     */
    public String reason() {
        return reason;
    }

    /**
     * Returns the file name of the schema with the problem.
     *
     * @return file name without directory
     */
    public String schema() {
        return schema;
    }

    /**
     * Returns additional information.
     *
     * @return detail text, or null
     */
    public String detail() {
        return detail;
    }
}
