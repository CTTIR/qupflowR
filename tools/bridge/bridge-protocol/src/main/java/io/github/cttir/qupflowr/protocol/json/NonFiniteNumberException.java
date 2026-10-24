/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.json;

import java.io.Serial;

/**
 * A NaN or infinite number was about to be written. Protocol JSON never contains such literals;
 * special measurement values use {@code {"value": null, "value_state": ...}} instead.
 */
public final class NonFiniteNumberException extends ProtocolJsonException {

    @Serial private static final long serialVersionUID = 1L;

    /**
     * Creates an exception.
     *
     * @param pointer JSON pointer of the non-finite value
     * @param literal textual form of the rejected number ({@code NaN}, {@code Infinity}, ...)
     */
    public NonFiniteNumberException(String pointer, String literal) {
        super(pointer, "Non-finite number " + literal + " cannot be written as protocol JSON");
    }
}
