/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.bridge;

import io.github.cttir.qupflowr.core.http.RequestHead;

import java.util.Map;
import java.util.Objects;

/**
 * Information about an admitted request available to route handlers.
 *
 * @param requestId request id used in the response envelope
 * @param template matched route template
 * @param parameters path parameters by name
 * @param head request head
 */
public record RequestContext(
        String requestId, String template, Map<String, String> parameters, RequestHead head) {

    /**
     * Validates the context.
     *
     * @param requestId request id
     * @param template template
     * @param parameters parameters
     * @param head head
     */
    public RequestContext {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(template, "template");
        parameters = Map.copyOf(parameters);
        Objects.requireNonNull(head, "head");
    }
}
