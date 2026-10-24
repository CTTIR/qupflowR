/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class BridgeTokenTest {

    @Test
    void matchingRequiresExactlyOneBearerHeader() {
        BridgeToken t = BridgeToken.generate(new SecureRandom());
        assertTrue(t.matchesAuthorizationHeaders(List.of("Bearer " + t.value())));
        assertTrue(t.matchesAuthorizationHeaders(List.of("bearer " + t.value())));
        assertTrue(t.matchesAuthorizationHeaders(List.of("BEARER   " + t.value() + "  ")));
        assertFalse(t.matchesAuthorizationHeaders(null));
        assertFalse(t.matchesAuthorizationHeaders(List.of()));
        assertFalse(t.matchesAuthorizationHeaders(List.of("Bearer " + t.value() + "x")));
        assertFalse(t.matchesAuthorizationHeaders(List.of("Bearer " + t.value().substring(1))));
        assertFalse(t.matchesAuthorizationHeaders(List.of(t.value())));
        assertFalse(t.matchesAuthorizationHeaders(List.of("Basic " + t.value())));
        assertFalse(t.matchesAuthorizationHeaders(List.of("Bearer")));
        assertFalse(t.matchesAuthorizationHeaders(List.of("Bearer ")));
        assertFalse(
                t.matchesAuthorizationHeaders(
                        List.of("Bearer " + t.value(), "Bearer " + t.value())));
        assertFalse(t.matchesAuthorizationHeaders(Arrays.asList((String) null)));
    }

    @Test
    void tokensAreRandomAndRedactedInToString() {
        Set<String> seen = new HashSet<>();
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 100; i++) {
            BridgeToken token = BridgeToken.generate(random);
            assertTrue(seen.add(token.value()));
            assertEquals(43, token.value().length());
            assertTrue(token.value().matches("[A-Za-z0-9_-]{43}"));
            assertEquals("BridgeToken[redacted]", token.toString());
        }
    }
}
