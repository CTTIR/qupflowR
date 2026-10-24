/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.schema;

import java.util.Objects;

/**
 * One failing keyword.
 *
 * @param path JSON pointer of the instance location ({@code ""} for the root)
 * @param keyword failing keyword ({@code false} for a false schema)
 * @param message English description; the R package localises its messages, so only path, keyword
 *     and schema path are compared between the languages
 * @param schemaPath file name and JSON pointer of the failing keyword, {@code <file>#<pointer>}
 */
public record ValidationError(String path, String keyword, String message, String schemaPath) {

    /**
     * Creates an error.
     *
     * @param path instance JSON pointer
     * @param keyword failing keyword
     * @param message description
     * @param schemaPath schema location of the keyword
     */
    public ValidationError {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(keyword, "keyword");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(schemaPath, "schemaPath");
    }
}
