/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.fixtures;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the committed expected-value tables of tests/fixtures/qp-v1. The tables were written in R
 * from hand constants and formulas, so they are independent of the Java specification classes.
 */
final class Tables {

    private Tables() {}

    static Path dir() {
        String dir = System.getProperty("qupflowr.test.fixtureTables");
        if (dir == null || !Files.isDirectory(Path.of(dir))) {
            throw new IllegalStateException(
                    "Committed fixture tables not found (system property "
                            + "qupflowr.test.fixtureTables = " + dir + ")");
        }
        return Path.of(dir);
    }

    /**
     * Parses an R {@code write.csv} file: comma separated, double-quoted strings with doubled
     * quotes, unquoted {@code NA} as missing.
     */
    static List<Map<String, String>> csv(String name) {
        String text;
        try {
            text = Files.readString(dir().resolve(name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean wasQuoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
                wasQuoted = true;
            } else if (ch == ',' || ch == '\n') {
                row.add(finish(field, wasQuoted));
                field.setLength(0);
                wasQuoted = false;
                if (ch == '\n') {
                    rows.add(row);
                    row = new ArrayList<>();
                }
            } else if (ch != '\r') {
                field.append(ch);
            }
        }
        if (!row.isEmpty() || field.length() > 0) {
            row.add(finish(field, wasQuoted));
            rows.add(row);
        }
        List<String> header = rows.get(0);
        List<Map<String, String>> out = new ArrayList<>();
        for (List<String> r : rows.subList(1, rows.size())) {
            if (r.size() != header.size()) {
                throw new IllegalStateException(name + ": row has " + r.size() + " fields");
            }
            Map<String, String> m = new LinkedHashMap<>();
            for (int k = 0; k < header.size(); k++) {
                m.put(header.get(k), r.get(k));
            }
            out.add(m);
        }
        return out;
    }

    private static String finish(StringBuilder field, boolean wasQuoted) {
        String value = field.toString();
        return !wasQuoted && value.equals("NA") ? null : value;
    }

    static JsonElement json(String name) {
        try {
            return JsonParser.parseString(
                    Files.readString(dir().resolve(name), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
