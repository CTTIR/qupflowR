/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

import java.time.Duration;
import java.util.Objects;

/**
 * Resource limits and deadlines of {@link LoopbackHttpServer}.
 *
 * @param maxConnections connections counted against the cap (header phase plus admitted requests);
 *     lingering connections are not counted
 * @param maxBusyResponders concurrent {@code 503 HOST_BUSY} writers when nothing can be evicted
 * @param maxLingering connections allowed to linger after their response
 * @param idleReadTimeout longest silence between two reads
 * @param headerDeadline time from accept until the header block must be complete
 * @param requestDeadline time from accept until the request (incl. body) must be complete
 * @param maxRequestLineBytes request line limit including CRLF
 * @param maxHeaderBlockBytes header block limit including every CRLF and the empty line
 * @param maxHeaderCount maximum number of header lines
 * @param maxBodyBytes absolute body limit; routes may declare smaller limits
 * @param lingerTimeout longest time unread input is discarded after a response
 * @param lingerMaxBytes most bytes discarded while lingering
 * @param evictionGrace age before a connection that has not sent any byte becomes evictable
 */
public record Limits(
        int maxConnections,
        int maxBusyResponders,
        int maxLingering,
        Duration idleReadTimeout,
        Duration headerDeadline,
        Duration requestDeadline,
        int maxRequestLineBytes,
        int maxHeaderBlockBytes,
        int maxHeaderCount,
        long maxBodyBytes,
        Duration lingerTimeout,
        int lingerMaxBytes,
        Duration evictionGrace) {

    /** Default body limit: 16 MiB. */
    public static final long DEFAULT_MAX_BODY_BYTES = 16L * 1024 * 1024;

    /**
     * Validates the limits.
     *
     * @param maxConnections connection cap
     * @param maxBusyResponders busy responder cap
     * @param maxLingering linger cap
     * @param idleReadTimeout idle timeout
     * @param headerDeadline header deadline
     * @param requestDeadline request deadline
     * @param maxRequestLineBytes request line limit
     * @param maxHeaderBlockBytes header block limit
     * @param maxHeaderCount header count limit
     * @param maxBodyBytes body limit
     * @param lingerTimeout linger timeout
     * @param lingerMaxBytes linger byte limit
     * @param evictionGrace grace period for silent connections
     */
    public Limits {
        Objects.requireNonNull(idleReadTimeout, "idleReadTimeout");
        Objects.requireNonNull(headerDeadline, "headerDeadline");
        Objects.requireNonNull(requestDeadline, "requestDeadline");
        Objects.requireNonNull(lingerTimeout, "lingerTimeout");
        Objects.requireNonNull(evictionGrace, "evictionGrace");
        if (maxConnections < 1
                || maxBusyResponders < 0
                || maxLingering < 0
                || maxHeaderCount < 1
                || maxRequestLineBytes < 16
                || maxHeaderBlockBytes < 2
                || maxBodyBytes < 0
                || lingerMaxBytes < 0) {
            throw new IllegalArgumentException("Invalid transport limits");
        }
        if (!positive(idleReadTimeout)
                || !positive(headerDeadline)
                || !positive(requestDeadline)
                || lingerTimeout.isNegative()
                || evictionGrace.isNegative()
                || headerDeadline.compareTo(requestDeadline) > 0) {
            throw new IllegalArgumentException("Invalid transport deadlines");
        }
    }

    /**
     * Returns the limits proven in the T00 spike SP01b: 64 connections, idle 5 s, headers 10 s,
     * request 30 s, request line 8 KiB, header block 16 KiB, 100 headers, body 16 MiB, linger 1 s
     * or 64 KiB; silent connections become evictable after 250 ms.
     *
     * @return default limits
     */
    public static Limits defaults() {
        return new Limits(
                64,
                256,
                256,
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                8 * 1024,
                16 * 1024,
                100,
                DEFAULT_MAX_BODY_BYTES,
                Duration.ofSeconds(1),
                64 * 1024,
                Duration.ofMillis(250));
    }

    /**
     * Returns a copy with another connection cap.
     *
     * @param value new cap
     * @return modified limits
     */
    public Limits withMaxConnections(int value) {
        return new Limits(
                value,
                maxBusyResponders,
                maxLingering,
                idleReadTimeout,
                headerDeadline,
                requestDeadline,
                maxRequestLineBytes,
                maxHeaderBlockBytes,
                maxHeaderCount,
                maxBodyBytes,
                lingerTimeout,
                lingerMaxBytes,
                evictionGrace);
    }

    /**
     * Returns a copy with other deadlines.
     *
     * @param idle idle read timeout
     * @param headers header deadline
     * @param request request deadline
     * @return modified limits
     */
    public Limits withDeadlines(Duration idle, Duration headers, Duration request) {
        return new Limits(
                maxConnections,
                maxBusyResponders,
                maxLingering,
                idle,
                headers,
                request,
                maxRequestLineBytes,
                maxHeaderBlockBytes,
                maxHeaderCount,
                maxBodyBytes,
                lingerTimeout,
                lingerMaxBytes,
                evictionGrace);
    }

    /**
     * Returns a copy with other size limits.
     *
     * @param requestLine request line limit
     * @param headerBlock header block limit
     * @param headerCount header count limit
     * @param body body limit
     * @return modified limits
     */
    public Limits withSizes(int requestLine, int headerBlock, int headerCount, long body) {
        return new Limits(
                maxConnections,
                maxBusyResponders,
                maxLingering,
                idleReadTimeout,
                headerDeadline,
                requestDeadline,
                requestLine,
                headerBlock,
                headerCount,
                body,
                lingerTimeout,
                lingerMaxBytes,
                evictionGrace);
    }

    /**
     * Returns a copy with other responder and linger settings.
     *
     * @param busyResponders busy responder cap
     * @param lingering linger cap
     * @param linger linger timeout
     * @return modified limits
     */
    public Limits withLinger(int busyResponders, int lingering, Duration linger) {
        return new Limits(
                maxConnections,
                busyResponders,
                lingering,
                idleReadTimeout,
                headerDeadline,
                requestDeadline,
                maxRequestLineBytes,
                maxHeaderBlockBytes,
                maxHeaderCount,
                maxBodyBytes,
                linger,
                lingerMaxBytes,
                evictionGrace);
    }

    /**
     * Returns a copy with another eviction grace period.
     *
     * @param grace age before a silent connection becomes evictable
     * @return modified limits
     */
    public Limits withEvictionGrace(Duration grace) {
        return new Limits(
                maxConnections,
                maxBusyResponders,
                maxLingering,
                idleReadTimeout,
                headerDeadline,
                requestDeadline,
                maxRequestLineBytes,
                maxHeaderBlockBytes,
                maxHeaderCount,
                maxBodyBytes,
                lingerTimeout,
                lingerMaxBytes,
                grace);
    }

    private static boolean positive(Duration duration) {
        return !duration.isNegative() && !duration.isZero();
    }
}
