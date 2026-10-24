/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

import java.util.ArrayList;
import java.util.List;

/** Request header fields with case-insensitive names, in arrival order. */
public final class Headers {

    private final List<String[]> entries;

    /**
     * Creates headers from name/value pairs.
     *
     * @param entries pairs {@code {name, value}}
     */
    Headers(List<String[]> entries) {
        List<String[]> copy = new ArrayList<>(entries.size());
        for (String[] entry : entries) {
            copy.add(new String[] {entry[0], entry[1]});
        }
        this.entries = List.copyOf(copy);
    }

    /**
     * Returns all values of a header.
     *
     * @param name header name (case-insensitive)
     * @return values in arrival order, empty if absent
     */
    public List<String> all(String name) {
        List<String> values = new ArrayList<>(1);
        for (String[] entry : entries) {
            if (entry[0].equalsIgnoreCase(name)) {
                values.add(entry[1]);
            }
        }
        return values;
    }

    /**
     * Returns the first value of a header.
     *
     * @param name header name (case-insensitive)
     * @return first value, or null if absent
     */
    public String first(String name) {
        for (String[] entry : entries) {
            if (entry[0].equalsIgnoreCase(name)) {
                return entry[1];
            }
        }
        return null;
    }

    /**
     * Tells whether a header is present.
     *
     * @param name header name (case-insensitive)
     * @return true if present at least once
     */
    public boolean contains(String name) {
        return first(name) != null;
    }

    /**
     * Returns the number of header lines.
     *
     * @return header line count
     */
    public int size() {
        return entries.size();
    }
}
