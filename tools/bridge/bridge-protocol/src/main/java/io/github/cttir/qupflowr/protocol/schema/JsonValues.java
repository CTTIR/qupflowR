/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.schema;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import io.github.cttir.qupflowr.protocol.json.ProtocolJson;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** JSON value helpers with the value semantics of the shared validator rules. */
final class JsonValues {

    private JsonValues() {}

    /** JSON kind: {@code null}, {@code boolean}, {@code number}, {@code string}, ... */
    static String kind(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "null";
        }
        if (value.isJsonObject()) {
            return "object";
        }
        if (value.isJsonArray()) {
            return "array";
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return "boolean";
        }
        return primitive.isNumber() ? "number" : "string";
    }

    static boolean isString(JsonElement value) {
        return value instanceof JsonPrimitive p && p.isString();
    }

    static boolean isBoolean(JsonElement value) {
        return value instanceof JsonPrimitive p && p.isBoolean();
    }

    static boolean isNumber(JsonElement value) {
        return value instanceof JsonPrimitive p && p.isNumber();
    }

    static boolean isTrue(JsonElement value) {
        return isBoolean(value) && value.getAsBoolean();
    }

    static boolean isFalse(JsonElement value) {
        return isBoolean(value) && !value.getAsBoolean();
    }

    /**
     * The number as a double, correctly rounded from its decimal text. Validation compares doubles,
     * like the R validator.
     */
    static double number(JsonElement value) {
        return Double.parseDouble(value.getAsJsonPrimitive().getAsNumber().toString());
    }

    /** True for a finite double without fractional part. */
    static boolean isWhole(double value) {
        return Double.isFinite(value) && value == Math.floor(value);
    }

    /** Array of unique strings (possibly empty). */
    static boolean isUniqueStringArray(JsonElement value) {
        if (!(value instanceof JsonArray array)) {
            return false;
        }
        List<String> seen = new ArrayList<>();
        for (JsonElement item : array) {
            if (!isString(item) || seen.contains(item.getAsString())) {
                return false;
            }
            seen.add(item.getAsString());
        }
        return true;
    }

    /**
     * A text key that is equal for equal JSON values: numbers compare by double value (so {@code 1}
     * equals {@code 1.0} and {@code -0} equals {@code 0}), object members independent of order.
     */
    static String key(JsonElement value) {
        StringBuilder out = new StringBuilder();
        appendKey(out, value);
        return out.toString();
    }

    private static void appendKey(StringBuilder out, JsonElement value) {
        switch (kind(value)) {
            case "null" -> out.append("null");
            case "boolean" -> out.append(value.getAsBoolean());
            case "number" -> {
                double d = number(value);
                out.append(d == 0 ? "0" : Double.toString(d));
            }
            case "string" -> appendQuoted(out, value.getAsString());
            case "array" -> {
                out.append('[');
                JsonArray array = value.getAsJsonArray();
                for (int i = 0; i < array.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    appendKey(out, array.get(i));
                }
                out.append(']');
            }
            default -> {
                JsonObject object = value.getAsJsonObject();
                List<Map.Entry<String, JsonElement>> members = new ArrayList<>(object.entrySet());
                members.sort((a, b) -> compareCodePoints(a.getKey(), b.getKey()));
                out.append('{');
                for (int i = 0; i < members.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    appendQuoted(out, members.get(i).getKey());
                    out.append(':');
                    appendKey(out, members.get(i).getValue());
                }
                out.append('}');
            }
        }
    }

    private static void appendQuoted(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        out.append('"');
    }

    /** Number text for messages: whole numbers without a fraction. */
    static String displayNumber(double value) {
        if (isWhole(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    /**
     * Compares two strings as sequences of Unicode code points (the order of their UTF-8 bytes).
     */
    static int compareCodePoints(String a, String b) {
        return Arrays.compareUnsigned(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** Escapes one JSON pointer token. */
    static String escape(String token) {
        return ProtocolJson.escapePointerToken(token);
    }
}
