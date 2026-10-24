/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.bridge;

import io.github.cttir.qupflowr.core.http.Response;
import io.github.cttir.qupflowr.protocol.ErrorCode;
import io.github.cttir.qupflowr.protocol.ErrorCodeRegistry;
import io.github.cttir.qupflowr.protocol.ProtocolVersion;
import io.github.cttir.qupflowr.protocol.dto.ErrorEnvelope;
import io.github.cttir.qupflowr.protocol.dto.ErrorObject;
import io.github.cttir.qupflowr.protocol.dto.SuccessEnvelope;
import io.github.cttir.qupflowr.protocol.json.ProtocolJson;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Renders protocol envelopes as HTTP responses. Status, retry hint and message key of an error come
 * from the {@link ErrorCodeRegistry}; details carry a machine-readable {@code reason} and, for
 * {@code 413}, {@code limit_bytes}. No request data is echoed except a valid UUID request id.
 */
final class ErrorResponses {

    private static final Map<String, String> MESSAGES =
            Map.ofEntries(
                    Map.entry("MALFORMED_REQUEST", "Malformed or unsupported HTTP request"),
                    Map.entry("AUTH_REQUIRED", "Missing or invalid bearer token"),
                    Map.entry(
                            "SCOPE_DENIED",
                            "Request not allowed for this host, origin or token scope"),
                    Map.entry("ENDPOINT_NOT_FOUND", "Unknown endpoint"),
                    Map.entry("METHOD_NOT_ALLOWED", "Method not allowed for this endpoint"),
                    Map.entry("REQUEST_TIMEOUT", "Request was not received in time"),
                    Map.entry("LENGTH_REQUIRED", "Request bodies need a Content-Length header"),
                    Map.entry(
                            "PAYLOAD_TOO_LARGE", "Request body exceeds the limit of this endpoint"),
                    Map.entry("REQUEST_LINE_TOO_LONG", "Request line exceeds the limit"),
                    Map.entry("EXPECTATION_FAILED", "Unsupported Expect header"),
                    Map.entry("HEADERS_TOO_LARGE", "Request headers exceed the limit"),
                    Map.entry(
                            "INTERNAL_ERROR",
                            "Internal error; see the host log for this request id"),
                    Map.entry("HOST_BUSY", "Too many open connections; retry later"),
                    Map.entry(
                            "HTTP_VERSION_NOT_SUPPORTED",
                            "Only HTTP/1.0 and HTTP/1.1 are supported"));

    private final ErrorCodeRegistry registry;
    private final String instanceId;

    ErrorResponses(ErrorCodeRegistry registry, String instanceId) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    }

    /** Fails fast if the registry lacks a code this bridge can emit. */
    void requireCodes(List<String> codes) {
        for (String code : codes) {
            if (registry.require(code).status().isEmpty()) {
                throw new IllegalStateException(
                        "Error code " + code + " has no HTTP status in the registry");
            }
        }
    }

    Response error(
            String requestId,
            String code,
            String reason,
            Long limitBytes,
            Map<String, String> headers) {
        ErrorCode entry = registry.require(code);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        if (limitBytes != null) {
            details.put("limit_bytes", limitBytes);
        }
        String message = MESSAGES.getOrDefault(code, "Request failed");
        ErrorEnvelope envelope =
                new ErrorEnvelope(
                        ProtocolVersion.CURRENT_STRING,
                        requestId,
                        instanceId,
                        ErrorObject.of(entry, message, details),
                        List.of());
        return json(entry.httpStatus(), requestId, ProtocolJson.toJson(envelope), headers);
    }

    Response success(String requestId, Object data) {
        SuccessEnvelope<Object> envelope =
                new SuccessEnvelope<>(
                        ProtocolVersion.CURRENT_STRING, requestId, instanceId, data, List.of());
        return json(200, requestId, ProtocolJson.toJson(envelope), Map.of());
    }

    private static Response json(
            int status, String requestId, String json, Map<String, String> extra) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("Cache-Control", "no-store");
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("X-Request-Id", requestId);
        headers.putAll(extra);
        return new Response(status, headers, (json + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** Returns the client's request id when it is a canonical UUID, otherwise a new random one. */
    static String requestId(String header) {
        if (header != null && header.length() == 36) {
            try {
                String parsed = UUID.fromString(header).toString();
                if (parsed.equalsIgnoreCase(header)) {
                    return parsed;
                }
            } catch (IllegalArgumentException e) {
                // not a UUID: never echo untrusted text
            }
        }
        return UUID.randomUUID().toString();
    }
}
