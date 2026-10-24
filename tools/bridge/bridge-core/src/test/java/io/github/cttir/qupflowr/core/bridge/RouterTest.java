/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class RouterTest {

    private static final Router.RouteHandler NONE = (context, body) -> null;

    private final Router router =
            Router.of(
                    List.of(
                            new Router.Route("GET", "/v1/health", 0, NONE),
                            new Router.Route("GET", "/v1/sessions/{sid}", 0, NONE),
                            new Router.Route("DELETE", "/v1/sessions/{sid}", 0, NONE),
                            new Router.Route("GET", "/v1/sessions/current", 0, NONE),
                            new Router.Route(
                                    "POST", "/v1/sessions/{sid}/jobs/{jid}/cancel", 1024, NONE)));

    @Test
    void matchesLiteralAndParameterSegments() {
        Router.Match.Found health =
                assertInstanceOf(Router.Match.Found.class, router.match("GET", "/v1/health"));
        assertEquals("/v1/health", health.route().template());
        assertEquals(Map.of(), health.parameters());
        Router.Match.Found job =
                assertInstanceOf(
                        Router.Match.Found.class,
                        router.match("POST", "/v1/sessions/s-1/jobs/j_2/cancel"));
        assertEquals(Map.of("sid", "s-1", "jid", "j_2"), job.parameters());
        assertEquals(1024, job.route().maxBodyBytes());
    }

    @Test
    void mostLiteralTemplateWins() {
        Router.Match.Found current =
                assertInstanceOf(
                        Router.Match.Found.class, router.match("GET", "/v1/sessions/current"));
        assertEquals("/v1/sessions/current", current.route().template());
        Router.Match.MethodNotAllowed delete =
                assertInstanceOf(
                        Router.Match.MethodNotAllowed.class,
                        router.match("DELETE", "/v1/sessions/current"));
        assertEquals(List.of("GET"), delete.allowed());
    }

    @Test
    void reportsNotFoundAndSortedAllowedMethods() {
        assertInstanceOf(Router.Match.NotFound.class, router.match("GET", "/v1/healthz"));
        assertInstanceOf(Router.Match.NotFound.class, router.match("GET", "/v1/health/x"));
        assertInstanceOf(Router.Match.NotFound.class, router.match("GET", "/v1"));
        Router.Match.MethodNotAllowed put =
                assertInstanceOf(
                        Router.Match.MethodNotAllowed.class,
                        router.match("PUT", "/v1/sessions/abc"));
        assertEquals(List.of("DELETE", "GET"), put.allowed());
        assertEquals("/v1/sessions/{sid}", router.templateFor("/v1/sessions/abc"));
        assertNull(router.templateFor("/v1/unknown"));
        assertNull(router.templateFor(null));
    }

    @Test
    void rejectsInvalidRouteTables() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        Router.of(
                                List.of(
                                        new Router.Route("GET", "/v1/a/{x}", 0, NONE),
                                        new Router.Route("GET", "/v1/a/{y}", 0, NONE))));
        assertThrows(
                IllegalArgumentException.class, () -> new Router.Route("get", "/v1/a", 0, NONE));
        assertThrows(
                IllegalArgumentException.class, () -> new Router.Route("GET", "v1/a", 0, NONE));
        assertThrows(
                IllegalArgumentException.class, () -> new Router.Route("GET", "/v1//a", 0, NONE));
        assertThrows(
                IllegalArgumentException.class, () -> new Router.Route("GET", "/v1/../a", 0, NONE));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Router.Route("GET", "/v1/{a}/{a}", 0, NONE));
        assertThrows(
                IllegalArgumentException.class, () -> new Router.Route("GET", "/v1/{A}", 0, NONE));
        assertThrows(
                IllegalArgumentException.class, () -> new Router.Route("GET", "/v1/a", -1, NONE));
    }
}
