/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

/**
 * Observed connection outcome, for logging and tests. Carries no header values or bodies.
 *
 * @param type outcome type
 * @param status response status, 0 if nothing was sent
 * @param method request method, or null before the request line was parsed
 * @param path validated request path, or null
 * @param elapsedMillis time since accept
 * @param reason transport reason code, or null
 */
public record ServerEvent(
        Type type, int status, String method, String path, double elapsedMillis, String reason) {

    /** Event types. */
    public enum Type {
        /** A response was written. */
        RESPONSE,
        /** Cap reached and nothing evictable: {@code 503 HOST_BUSY} written. */
        BUSY_REJECTED,
        /** Cap reached and all busy responders in use: connection closed without response. */
        BUSY_DROPPED,
        /** A connection still in the header phase was evicted to admit a new one. */
        EVICTED,
        /** Header block not complete within the idle timeout or header deadline. */
        HEADER_TIMEOUT,
        /** Body not complete within the idle timeout or request deadline. */
        BODY_TIMEOUT,
        /** The request deadline closed the socket. */
        DEADLINE_CLOSED,
        /** The client closed before a complete request was received. */
        CLIENT_CLOSED,
        /** The handler failed; {@code 500 INTERNAL_ERROR} written. */
        HANDLER_FAILED,
        /** No linger slot was free; the connection was closed right after the response. */
        LINGER_SKIPPED
    }

    /** Receives events; must be fast and must not throw. */
    @FunctionalInterface
    public interface Listener {
        /**
         * Called for every event.
         *
         * @param event the event
         */
        void onEvent(ServerEvent event);
    }
}
