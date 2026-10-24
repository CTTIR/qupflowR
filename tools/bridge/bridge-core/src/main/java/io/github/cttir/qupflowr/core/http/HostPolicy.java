/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

import java.util.List;
import java.util.Locale;

/**
 * Decides whether the {@code Host} header addresses this server. Evaluated after message framing
 * and before the request target, as required by the transport precedence.
 */
@FunctionalInterface
public interface HostPolicy {

    /**
     * Checks the Host header values.
     *
     * @param values all Host header values in arrival order
     * @param version {@code HTTP/1.1} or {@code HTTP/1.0}
     * @param port port the server is bound to
     * @return null to accept, otherwise the rejection
     */
    TransportError check(List<String> values, String version, int port);

    /**
     * Loopback policy: exactly one Host header equal (case-insensitively) to {@code
     * 127.0.0.1:<port>} or {@code localhost:<port>}. A duplicate or, in HTTP/1.1, a missing Host is
     * malformed (400); any other value, and a missing Host in HTTP/1.0, is 403.
     *
     * @return the policy
     */
    static HostPolicy loopback() {
        return (values, version, port) -> {
            if (values.size() > 1) {
                return TransportError.of(400, TransportCodes.MALFORMED_REQUEST, "duplicate_host");
            }
            if (values.isEmpty()) {
                return "HTTP/1.1".equals(version)
                        ? TransportError.of(400, TransportCodes.MALFORMED_REQUEST, "missing_host")
                        : TransportError.of(403, TransportCodes.SCOPE_DENIED, "host_not_allowed");
            }
            String host = values.get(0).toLowerCase(Locale.ROOT);
            if (host.equals("127.0.0.1:" + port) || host.equals("localhost:" + port)) {
                return null;
            }
            return TransportError.of(403, TransportCodes.SCOPE_DENIED, "host_not_allowed");
        };
    }
}
