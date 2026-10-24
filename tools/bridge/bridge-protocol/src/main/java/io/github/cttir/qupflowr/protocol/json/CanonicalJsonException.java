/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.json;

import java.io.Serial;
import java.util.Objects;

/**
 * JSON text or a JSON value rejected by the shared decoding rules or by the canonical JSON rules.
 *
 * <p>{@link #code()} and {@link #reason()} are the values the R package reports for the same input
 * (condition code and {@code details$reason}), so the shared vectors under {@code
 * inst/schema/protocol/v1/fixtures/canonical} check both languages. {@link #pointer()} is the JSON
 * pointer of the offending value where the R package reports one, otherwise {@code ""}.
 */
public final class CanonicalJsonException extends ProtocolJsonException {

    @Serial private static final long serialVersionUID = 1L;

    /** Code of text that is not acceptable JSON. */
    public static final String JSON_INVALID = "JSON_INVALID";

    /** Code of a value that JSON or canonical JSON cannot represent. */
    public static final String JSON_UNREPRESENTABLE = "JSON_UNREPRESENTABLE";

    /** Reason: the text violates the JSON grammar (RFC 8259). */
    public static final String SYNTAX = "syntax";

    /** Reason: an object has two members with the same name. */
    public static final String DUPLICATE_KEY = "duplicate_key";

    /** Reason: an object member name is empty. */
    public static final String EMPTY_MEMBER_NAME = "empty_member_name";

    /** Reason: the text starts with a byte order mark. */
    public static final String BYTE_ORDER_MARK = "byte_order_mark";

    /** Reason: the bytes are not valid UTF-8 (or a string holds an unpaired surrogate). */
    public static final String INVALID_UTF8 = "invalid_utf8";

    /** Reason: the bytes contain a NUL byte. */
    public static final String NUL_BYTE = "nul_byte";

    /** Reason: a string contains the character U+0000. */
    public static final String NUL_CHARACTER = "nul_character";

    /** Reason: a backslash-u escape is an unpaired UTF-16 surrogate. */
    public static final String UNPAIRED_SURROGATE = "unpaired_surrogate";

    /** Reason: the document nests deeper than 128 levels. */
    public static final String NESTING_TOO_DEEP = "nesting_too_deep";

    /** Reason: a number is outside the range of an IEEE 754 double. */
    public static final String NUMBER_OUT_OF_RANGE = "number_out_of_range";

    /** Reason: canonical JSON only holds integers. */
    public static final String NON_INTEGER_NUMBER = "non_integer_number";

    /** Reason: canonical JSON only holds integers with a magnitude of at most 2^53 - 1. */
    public static final String INTEGER_OUT_OF_RANGE = "integer_out_of_range";

    /** Reason: NaN or an infinite number. */
    public static final String NON_FINITE_NUMBER = "non_finite_number";

    private final String code;
    private final String reason;

    /**
     * Creates an exception.
     *
     * @param code {@link #JSON_INVALID} or {@link #JSON_UNREPRESENTABLE}
     * @param reason machine-readable reason, one of the reason constants of this class
     * @param pointer JSON pointer of the offending value, or null when the rules name no location
     */
    public CanonicalJsonException(String code, String reason, String pointer) {
        super(pointer, Objects.requireNonNull(code, "code") + " (" + reason + ")");
        this.code = code;
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns the error code.
     *
     * @return {@link #JSON_INVALID} or {@link #JSON_UNREPRESENTABLE}
     */
    public String code() {
        return code;
    }

    /**
     * Returns the machine-readable reason.
     *
     * @return reason such as {@link #DUPLICATE_KEY}
     */
    public String reason() {
        return reason;
    }

    static CanonicalJsonException invalid(String reason, String pointer) {
        return new CanonicalJsonException(JSON_INVALID, reason, pointer);
    }

    static CanonicalJsonException unrepresentable(String reason, String pointer) {
        return new CanonicalJsonException(JSON_UNREPRESENTABLE, reason, pointer);
    }
}
