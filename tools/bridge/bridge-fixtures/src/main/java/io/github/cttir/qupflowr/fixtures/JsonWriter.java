/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.fixtures;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic, dependency-free JSON writer for fixture documents.
 *
 * <p>Maps are written in their iteration order (callers use insertion-ordered maps), with two
 * spaces of indentation and a final newline. Arrays that contain only numbers are written on one
 * line. Non-finite numbers are rejected, so special values must be encoded explicitly as value
 * states. Strings are written as UTF-8 text; only {@code "}, {@code \} and control characters are
 * escaped.
 */
public final class JsonWriter {

    private JsonWriter() {}

    /**
     * Serialises a value.
     *
     * @param value map, collection, primitive array, string, number, boolean or null
     * @return JSON text with a trailing newline
     * @throws IllegalArgumentException for non-finite numbers, non-string keys or unsupported types
     */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof CharSequence cs) {
            writeString(sb, cs.toString());
        } else if (value instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (value instanceof Number n) {
            writeNumber(sb, n);
        } else if (value instanceof Map<?, ?> map) {
            writeMap(sb, map, indent);
        } else if (value instanceof Collection<?> list) {
            writeList(sb, list.toArray(), indent);
        } else if (value instanceof int[] ints) {
            Object[] boxed = new Object[ints.length];
            for (int i = 0; i < ints.length; i++) {
                boxed[i] = ints[i];
            }
            writeList(sb, boxed, indent);
        } else if (value instanceof long[] longs) {
            Object[] boxed = new Object[longs.length];
            for (int i = 0; i < longs.length; i++) {
                boxed[i] = longs[i];
            }
            writeList(sb, boxed, indent);
        } else if (value instanceof double[] doubles) {
            Object[] boxed = new Object[doubles.length];
            for (int i = 0; i < doubles.length; i++) {
                boxed[i] = doubles[i];
            }
            writeList(sb, boxed, indent);
        } else if (value instanceof Object[] array) {
            writeList(sb, array, indent);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported JSON value type " + value.getClass().getName());
        }
    }

    private static void writeNumber(StringBuilder sb, Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (!Double.isFinite(d)) {
                throw new IllegalArgumentException("Non-finite number cannot be written: " + d);
            }
            sb.append(Double.toString(d));
        } else if (n instanceof Integer || n instanceof Long || n instanceof Short
                || n instanceof Byte) {
            sb.append(n.longValue());
        } else {
            throw new IllegalArgumentException("Unsupported number type " + n.getClass().getName());
        }
    }

    private static void writeMap(StringBuilder sb, Map<?, ?> map, int indent) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("JSON object keys must be strings");
            }
            indent(sb, indent + 1);
            writeString(sb, key);
            sb.append(": ");
            write(sb, entry.getValue(), indent + 1);
            if (++i < map.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append('}');
    }

    private static void writeList(StringBuilder sb, Object[] items, int indent) {
        if (items.length == 0) {
            sb.append("[]");
            return;
        }
        boolean numeric = true;
        for (Object item : items) {
            if (!(item instanceof Number)) {
                numeric = false;
                break;
            }
        }
        if (numeric) {
            sb.append('[');
            for (int i = 0; i < items.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                writeNumber(sb, (Number) items[i]);
            }
            sb.append(']');
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < items.length; i++) {
            indent(sb, indent + 1);
            write(sb, items[i], indent + 1);
            if (i + 1 < items.length) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append(']');
    }

    private static void indent(StringBuilder sb, int indent) {
        sb.append("  ".repeat(indent));
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        sb.append('"');
    }
}
