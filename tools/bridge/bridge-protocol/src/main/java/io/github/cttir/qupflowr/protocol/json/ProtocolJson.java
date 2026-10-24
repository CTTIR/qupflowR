/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;

/**
 * JSON rules shared by every bridge component.
 *
 * <ul>
 *   <li>Writing never emits {@code NaN} or {@code Infinity}: a non-finite number anywhere in the
 *       document raises {@link NonFiniteNumberException} naming its JSON pointer.
 *   <li>{@code null} members are written (so {@code "fx_thread_blocked": null} stays explicit);
 *       absent optional members must therefore be modelled by leaving them out of the DTO.
 *   <li>No HTML escaping; UTF-8 text without byte order mark.
 *   <li>Reading follows the decoding rules shared with the R package ({@code qp_json_decode()}):
 *       {@link #parse(String)} delegates to {@link CanonicalJsonReader}, so a request body that R
 *       would reject is rejected here with the same code and reason (strict RFC 8259, no leading
 *       byte order mark, no duplicate or empty member names, no escaped U+0000 or unpaired
 *       surrogate, numbers inside the double range, fewer than 128 nesting levels).
 * </ul>
 *
 * Canonical JSON (digest input) has its own, stricter writing rules; see {@link CanonicalJson}.
 */
public final class ProtocolJson {

    /**
     * Maximum number of nesting levels when reading or writing; a value at this depth (the root has
     * depth 0) is rejected, as in {@link CanonicalJsonReader#MAX_DEPTH} and the R package.
     */
    public static final int MAX_DEPTH = CanonicalJsonReader.MAX_DEPTH;

    // Builds trees permissively so non-finite values can be located precisely before writing.
    private static final Gson TREE_GSON =
            new GsonBuilder()
                    .serializeNulls()
                    .disableHtmlEscaping()
                    .serializeSpecialFloatingPointValues()
                    .create();

    // Final writer: strict mode also rejects non-finite numbers as a second line of defence.
    private static final Gson STRICT_GSON =
            new GsonBuilder()
                    .serializeNulls()
                    .disableHtmlEscaping()
                    .setStrictness(Strictness.STRICT)
                    .create();

    private ProtocolJson() {}

    /**
     * Serialises a value (typically a DTO record) as compact protocol JSON.
     *
     * @param value value to serialise, may be null
     * @return JSON text without trailing newline
     * @throws NonFiniteNumberException if the value contains NaN or an infinite number
     */
    public static String toJson(Object value) {
        return write(toTree(value), false);
    }

    /**
     * Serialises a value as protocol JSON indented by two spaces (for human-readable files).
     *
     * @param value value to serialise, may be null
     * @return JSON text without trailing newline
     * @throws NonFiniteNumberException if the value contains NaN or an infinite number
     */
    public static String toPrettyJson(Object value) {
        return write(toTree(value), true);
    }

    /**
     * Converts a value into a JSON tree and checks that every number is finite.
     *
     * @param value value to convert, may be null
     * @return the checked tree
     * @throws NonFiniteNumberException if the value contains NaN or an infinite number
     */
    public static JsonElement toTree(Object value) {
        JsonElement tree =
                value instanceof JsonElement element ? element : TREE_GSON.toJsonTree(value);
        requireFinite(tree, "", 0);
        return tree;
    }

    /**
     * Parses protocol JSON under the shared decoding rules.
     *
     * @param text JSON text
     * @return the parsed tree; numbers are {@link BigDecimal} values so no precision is lost
     * @throws CanonicalJsonException if the text is not acceptable protocol JSON; {@link
     *     CanonicalJsonException#code()} is {@code JSON_INVALID} or {@code JSON_UNREPRESENTABLE}
     *     and {@link CanonicalJsonException#reason()} names the rule, as in R
     */
    public static JsonElement parse(String text) {
        Objects.requireNonNull(text, "text");
        return CanonicalJsonReader.read(text);
    }

    /**
     * Returns a Gson instance configured with the protocol writing rules, for callers that need
     * streaming or type adapters. Prefer {@link #toJson(Object)}, which also reports the pointer of
     * a non-finite number.
     *
     * @return strict Gson instance (thread-safe)
     */
    public static Gson gson() {
        return STRICT_GSON;
    }

    /**
     * Escapes one JSON pointer reference token (RFC 6901).
     *
     * @param token object key or array index
     * @return escaped token
     */
    public static String escapePointerToken(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private static String write(JsonElement tree, boolean pretty) {
        StringWriter out = new StringWriter();
        try (JsonWriter writer = new JsonWriter(out)) {
            writer.setStrictness(Strictness.STRICT);
            writer.setSerializeNulls(true);
            writer.setHtmlSafe(false);
            if (pretty) {
                writer.setIndent("  ");
            }
            STRICT_GSON.toJson(tree, writer);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    private static void requireFinite(JsonElement element, String pointer, int depth) {
        if (depth >= MAX_DEPTH) {
            throw new ProtocolJsonException(pointer, "Nesting deeper than " + MAX_DEPTH);
        }
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isNumber() && !isFinite(primitive.getAsNumber())) {
                throw new NonFiniteNumberException(pointer, primitive.getAsNumber().toString());
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                requireFinite(array.get(i), pointer + "/" + i, depth + 1);
            }
        } else {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                requireFinite(
                        entry.getValue(),
                        pointer + "/" + escapePointerToken(entry.getKey()),
                        depth + 1);
            }
        }
    }

    static boolean isFinite(Number number) {
        if (number instanceof Double d) {
            return Double.isFinite(d);
        }
        if (number instanceof Float f) {
            return Float.isFinite(f);
        }
        if (number instanceof BigDecimal
                || number instanceof BigInteger
                || number instanceof Long
                || number instanceof Integer
                || number instanceof Short
                || number instanceof Byte) {
            return true;
        }
        // Other Number implementations (e.g. lazily parsed numbers): accept only decimal literals.
        try {
            new BigDecimal(number.toString());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
