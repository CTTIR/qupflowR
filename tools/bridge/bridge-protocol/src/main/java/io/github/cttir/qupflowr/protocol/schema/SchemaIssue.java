/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.schema;

import java.util.Objects;

/**
 * A structural problem of a schema document found when it is loaded.
 *
 * @param pointer JSON pointer of the schema node inside its file ({@code ""} for the root)
 * @param keyword keyword with the problem, {@code ""} when the node itself is the problem
 * @param problem machine-readable problem, for example {@code unsupported_keyword}
 */
public record SchemaIssue(String pointer, String keyword, String problem) {

    /**
     * Creates an issue.
     *
     * @param pointer JSON pointer of the schema node
     * @param keyword keyword with the problem
     * @param problem machine-readable problem
     */
    public SchemaIssue {
        Objects.requireNonNull(pointer, "pointer");
        Objects.requireNonNull(keyword, "keyword");
        Objects.requireNonNull(problem, "problem");
    }
}
