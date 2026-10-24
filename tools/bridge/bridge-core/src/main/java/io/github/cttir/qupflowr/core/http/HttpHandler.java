/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

/**
 * Request processing callbacks of {@link LoopbackHttpServer}. Implementations must be thread-safe.
 */
public interface HttpHandler {

    /**
     * Inspects a request head before any body byte is read and before {@code 100 Continue} is sent
     * (origin, authentication, routing and route body limit).
     *
     * @param head parsed and validated head
     * @return rejection or acceptance, never null
     */
    Admission admit(RequestHead head);

    /**
     * Renders an error found by the transport.
     *
     * @param head parsed head, or null if the failure happened before the head was complete
     * @param error transport error
     * @return response, never null
     */
    Response transportError(RequestHead head, TransportError error);
}
