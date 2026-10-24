/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Connection accounting with header-phase eviction.
 *
 * <p>At most {@code max} connections are counted. A connection is counted from accept until its
 * response has been written (lingering does not count). While it is still reading its header block
 * and has no unread input pending it is <em>evictable</em>: when the cap is reached, the oldest
 * such connection gives its slot to the newly accepted one. Connections with pending input are
 * about to be parsed and are skipped (closing them would also turn the unread request into a TCP
 * reset), as are connections that have not sent a byte yet and are younger than {@link
 * Limits#evictionGrace()} (their request may still be in flight). Connections whose head was
 * complete and admitted are never evicted, so an unauthenticated client that stalls in the header
 * phase can only occupy evictable slots.
 */
final class ConnectionTable {

    /** Result of {@link #admit(Connection)}. */
    enum Outcome {
        /** A free slot was used. */
        ADMITTED,
        /** The oldest header-phase connection was evicted to make room. */
        ADMITTED_BY_EVICTION,
        /** No free slot and nothing evictable. */
        FULL
    }

    /**
     * Admission result.
     *
     * @param outcome outcome
     * @param victim evicted connection for {@link Outcome#ADMITTED_BY_EVICTION}, otherwise null
     */
    record Admit(Outcome outcome, Connection victim) {}

    private final int max;
    private final Set<Connection> counted = new HashSet<>();
    private final LinkedHashSet<Connection> headerPhase = new LinkedHashSet<>();

    ConnectionTable(int max) {
        this.max = max;
    }

    synchronized Admit admit(Connection connection) {
        long now = System.nanoTime();
        if (counted.size() < max) {
            counted.add(connection);
            headerPhase.add(connection);
            return new Admit(Outcome.ADMITTED, null);
        }
        Iterator<Connection> oldestFirst = headerPhase.iterator();
        while (oldestFirst.hasNext()) {
            Connection victim = oldestFirst.next();
            if (!victim.isEvictable(now)) {
                continue;
            }
            oldestFirst.remove();
            counted.remove(victim);
            victim.markEvicted();
            counted.add(connection);
            headerPhase.add(connection);
            return new Admit(Outcome.ADMITTED_BY_EVICTION, victim);
        }
        return new Admit(Outcome.FULL, null);
    }

    /**
     * Ends the evictable phase.
     *
     * @return false if the connection was evicted before its head completed
     */
    synchronized boolean leaveHeaderPhase(Connection connection) {
        if (connection.isEvicted()) {
            return false;
        }
        headerPhase.remove(connection);
        return true;
    }

    /** Releases the slot of a connection; idempotent and a no-op for evicted connections. */
    synchronized void release(Connection connection) {
        if (counted.remove(connection)) {
            headerPhase.remove(connection);
        }
    }

    synchronized int countedSize() {
        return counted.size();
    }

    synchronized int headerPhaseSize() {
        return headerPhase.size();
    }
}
