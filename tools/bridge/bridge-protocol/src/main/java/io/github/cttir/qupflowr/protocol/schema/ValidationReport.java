/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.schema;

import java.util.List;
import java.util.Objects;

/**
 * Result of validating one JSON value.
 *
 * @param schema file name of the schema
 * @param errors failing keywords without duplicates, sorted by instance path and keyword (code
 *     point order, stable); empty when the value is valid
 */
public record ValidationReport(String schema, List<ValidationError> errors) {

    /**
     * Creates a report.
     *
     * @param schema schema file name
     * @param errors errors in report order
     */
    public ValidationReport {
        Objects.requireNonNull(schema, "schema");
        errors = List.copyOf(errors);
    }

    /**
     * Returns whether the value is valid.
     *
     * @return true when there are no errors
     */
    public boolean valid() {
        return errors.isEmpty();
    }

    @Override
    public String toString() {
        if (valid()) {
            return "Valid against " + schema + ".";
        }
        StringBuilder out = new StringBuilder();
        out.append("Invalid against ").append(schema).append(": ").append(errors.size());
        out.append(errors.size() == 1 ? " error." : " errors.");
        for (ValidationError error : errors) {
            String path = error.path().isEmpty() ? "(root)" : error.path();
            out.append("\n* ").append(path).append(" [").append(error.keyword()).append("] ");
            out.append(error.message());
        }
        return out.toString();
    }
}
