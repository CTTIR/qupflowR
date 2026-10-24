/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import io.github.cttir.qupflowr.protocol.json.ProtocolJson;
import io.github.cttir.qupflowr.protocol.json.ProtocolJsonException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of protocol error codes, read from the registry file shared with the R package.
 *
 * <p>The Gradle build copies {@code inst/schema/protocol/v1/error-codes.json} into this module's
 * resources ({@link #RESOURCE}); there is no second, hand-maintained list in Java. The document is
 * either a JSON array of entries or an object whose {@code codes} member is that array. Every entry
 * has the members {@code code}, {@code http_status} (integer or null), {@code r_class}, {@code
 * retryable}, {@code message_key} and optionally {@code source}; unknown members are ignored.
 */
public final class ErrorCodeRegistry {

    /** Class path location of the bundled registry. */
    public static final String RESOURCE = "/io/github/cttir/qupflowr/protocol/v1/error-codes.json";

    private static final class Holder {
        private static final ErrorCodeRegistry BUNDLED = loadBundled();
    }

    private final Map<String, ErrorCode> codes;

    private ErrorCodeRegistry(Map<String, ErrorCode> codes) {
        this.codes = Collections.unmodifiableMap(codes);
    }

    /**
     * Returns the registry bundled with the bridge.
     *
     * @return bundled registry
     * @throws IllegalStateException if the resource is missing or invalid
     */
    public static ErrorCodeRegistry bundled() {
        return Holder.BUNDLED;
    }

    /**
     * Reads and validates a registry document.
     *
     * @param json registry JSON text
     * @return the registry
     * @throws IllegalArgumentException if the document or an entry is invalid
     */
    public static ErrorCodeRegistry parse(String json) {
        JsonElement root = ProtocolJson.parse(json);
        JsonArray entries;
        if (root.isJsonArray()) {
            entries = root.getAsJsonArray();
        } else if (root.isJsonObject()
                && root.getAsJsonObject().get("codes") instanceof JsonArray a) {
            entries = a;
        } else {
            throw new ProtocolJsonException(
                    "", "Registry must be an array or an object with 'codes'");
        }
        Map<String, ErrorCode> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            String pointer = (root.isJsonArray() ? "" : "/codes") + "/" + i;
            if (!entries.get(i).isJsonObject()) {
                throw new ProtocolJsonException(pointer, "Registry entry must be an object");
            }
            ErrorCode code = entry(entries.get(i).getAsJsonObject(), pointer);
            if (map.putIfAbsent(code.code(), code) != null) {
                throw new ProtocolJsonException(
                        pointer + "/code", "Duplicate error code " + code.code());
            }
        }
        if (map.isEmpty()) {
            throw new ProtocolJsonException("", "Registry contains no error codes");
        }
        return new ErrorCodeRegistry(map);
    }

    /**
     * Looks up a code.
     *
     * @param code error code
     * @return the entry, or empty if the registry does not define it
     */
    public Optional<ErrorCode> find(String code) {
        return Optional.ofNullable(codes.get(code));
    }

    /**
     * Looks up a code that must exist.
     *
     * @param code error code
     * @return the entry
     * @throws IllegalStateException if the registry does not define the code
     */
    public ErrorCode require(String code) {
        ErrorCode entry = codes.get(code);
        if (entry == null) {
            throw new IllegalStateException(
                    "Error code " + code + " is not in the protocol registry");
        }
        return entry;
    }

    /**
     * Returns all entries in registry order.
     *
     * @return unmodifiable collection of entries
     */
    public Collection<ErrorCode> all() {
        return codes.values();
    }

    private static ErrorCode entry(JsonObject object, String pointer) {
        String code = string(object, "code", pointer, false);
        Integer status = null;
        JsonElement statusElement = object.get("http_status");
        if (statusElement == null) {
            throw new ProtocolJsonException(pointer + "/http_status", "Missing member");
        }
        if (!statusElement.isJsonNull()) {
            status = integer(statusElement, pointer + "/http_status");
        }
        JsonElement retryable = object.get("retryable");
        if (!(retryable instanceof JsonPrimitive p) || !p.isBoolean()) {
            throw new ProtocolJsonException(pointer + "/retryable", "Expected a boolean");
        }
        String rClass = string(object, "r_class", pointer, false);
        String messageKey = string(object, "message_key", pointer, false);
        String source = string(object, "source", pointer, true);
        try {
            return new ErrorCode(
                    code, status, rClass, retryable.getAsBoolean(), messageKey, source);
        } catch (IllegalArgumentException e) {
            throw new ProtocolJsonException(pointer, e.getMessage());
        }
    }

    private static String string(JsonObject object, String name, String pointer, boolean optional) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            if (optional) {
                return null;
            }
            throw new ProtocolJsonException(pointer + "/" + name, "Missing member");
        }
        if (!(element instanceof JsonPrimitive p) || !p.isString()) {
            throw new ProtocolJsonException(pointer + "/" + name, "Expected a string");
        }
        return p.getAsString();
    }

    private static int integer(JsonElement element, String pointer) {
        if (!(element instanceof JsonPrimitive p) || !p.isNumber()) {
            throw new ProtocolJsonException(pointer, "Expected an integer");
        }
        try {
            return new BigDecimal(p.getAsNumber().toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new ProtocolJsonException(pointer, "Expected an integer");
        }
    }

    private static ErrorCodeRegistry loadBundled() {
        try (InputStream in = ErrorCodeRegistry.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing bundled error-code registry " + RESOURCE);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read " + RESOURCE, e);
        }
    }
}
