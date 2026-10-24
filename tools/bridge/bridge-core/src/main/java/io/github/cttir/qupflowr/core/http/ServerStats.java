/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

/**
 * Counters since start.
 *
 * @param accepted accepted sockets
 * @param active connections counted against the cap now
 * @param headerPhase connections currently in the header phase
 * @param open open sockets including lingering ones
 * @param responses responses written
 * @param busyRejected {@code 503 HOST_BUSY} answers because nothing was evictable
 * @param busyDropped connections closed without response (busy responders exhausted)
 * @param evicted header-phase connections evicted for newer connections
 * @param headerTimeouts header phase timeouts
 * @param bodyTimeouts body timeouts
 * @param deadlineClosed sockets closed by the request deadline
 * @param clientClosed clients that closed before completing a request
 * @param handlerFailed handler failures
 * @param lingerSkipped responses closed without linger because no linger slot was free
 */
public record ServerStats(
        long accepted,
        long active,
        long headerPhase,
        long open,
        long responses,
        long busyRejected,
        long busyDropped,
        long evicted,
        long headerTimeouts,
        long bodyTimeouts,
        long deadlineClosed,
        long clientClosed,
        long handlerFailed,
        long lingerSkipped) {}
