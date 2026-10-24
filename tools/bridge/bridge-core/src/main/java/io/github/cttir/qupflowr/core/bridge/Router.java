/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.bridge;

import io.github.cttir.qupflowr.core.http.RequestBody;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Method and path table of the bridge API.
 *
 * <p>Templates consist of literal segments and {@code {name}} parameter segments, e.g. {@code
 * /v1/sessions/{sid}}. Paths reaching the router are already normalised by the transport (no
 * percent-encoding, dot or empty segments), so matching is a plain segment comparison. When several
 * templates match, the one with the most literal segments wins; if it does not support the method
 * the result is "method not allowed" with that template's methods.
 */
public final class Router {

    private static final Pattern METHOD = Pattern.compile("[A-Z]+");
    private static final Pattern LITERAL = Pattern.compile("[A-Za-z0-9._~-]+");
    private static final Pattern PARAMETER = Pattern.compile("\\{[a-z][a-z0-9_]*}");

    /** Produces the {@code data} member of a successful response. */
    @FunctionalInterface
    public interface RouteHandler {
        /**
         * Handles a request.
         *
         * @param context request context
         * @param body request body (at most the route's body limit)
         * @return payload for the success envelope
         * @throws Exception any failure; rendered as {@code INTERNAL_ERROR}
         */
        Object handle(RequestContext context, RequestBody body) throws Exception;
    }

    /**
     * One route.
     *
     * @param method HTTP method
     * @param template path template
     * @param maxBodyBytes largest accepted body (0 for requests without body)
     * @param handler handler
     */
    public record Route(String method, String template, long maxBodyBytes, RouteHandler handler) {
        /**
         * Validates a route.
         *
         * @param method method
         * @param template template
         * @param maxBodyBytes body limit
         * @param handler handler
         */
        public Route {
            Objects.requireNonNull(handler, "handler");
            if (method == null || !METHOD.matcher(method).matches()) {
                throw new IllegalArgumentException("Invalid method " + method);
            }
            if (maxBodyBytes < 0) {
                throw new IllegalArgumentException("Negative body limit for " + template);
            }
            segments(template);
        }
    }

    /** Result of {@link #match(String, String)}. */
    public sealed interface Match {
        /**
         * A route matched.
         *
         * @param route route
         * @param parameters path parameters by name
         */
        record Found(Route route, Map<String, String> parameters) implements Match {}

        /** No template matches the path. */
        record NotFound() implements Match {}

        /**
         * A template matches but not for this method.
         *
         * @param template matching template
         * @param allowed methods of that template, sorted
         */
        record MethodNotAllowed(String template, List<String> allowed) implements Match {}
    }

    private final List<Route> routes;

    private Router(List<Route> routes) {
        this.routes = List.copyOf(routes);
    }

    /**
     * Creates a router.
     *
     * @param routes routes; a method may appear only once per template
     * @return router
     * @throws IllegalArgumentException for duplicate method/template pairs
     */
    public static Router of(List<Route> routes) {
        List<String> seen = new ArrayList<>();
        for (Route route : routes) {
            String key = route.method() + " " + String.join("/", shape(route.template()));
            if (seen.contains(key)) {
                throw new IllegalArgumentException(
                        "Duplicate route " + route.method() + " " + route.template());
            }
            seen.add(key);
        }
        return new Router(routes);
    }

    /**
     * Returns the routes in registration order.
     *
     * @return routes
     */
    public List<Route> routes() {
        return routes;
    }

    /**
     * Matches a request.
     *
     * @param method request method
     * @param path normalised request path
     * @return match result
     */
    public Match match(String method, String path) {
        String best = null;
        int bestLiterals = -1;
        Map<String, String> bestParameters = null;
        for (Route route : routes) {
            Map<String, String> parameters = bind(route.template(), path);
            if (parameters == null) {
                continue;
            }
            int literals = literalCount(route.template());
            if (literals > bestLiterals) {
                best = route.template();
                bestLiterals = literals;
                bestParameters = parameters;
            }
        }
        if (best == null) {
            return new Match.NotFound();
        }
        TreeSet<String> allowed = new TreeSet<>();
        for (Route route : routes) {
            if (route.template().equals(best)) {
                if (route.method().equals(method)) {
                    return new Match.Found(route, Collections.unmodifiableMap(bestParameters));
                }
                allowed.add(route.method());
            }
        }
        return new Match.MethodNotAllowed(best, List.copyOf(allowed));
    }

    /**
     * Returns the template that matches a path, for logging without raw path values.
     *
     * @param path normalised path, may be null
     * @return best matching template, or null
     */
    public String templateFor(String path) {
        if (path == null) {
            return null;
        }
        Match match = match("", path);
        if (match instanceof Match.MethodNotAllowed notAllowed) {
            return notAllowed.template();
        }
        return null;
    }

    private static Map<String, String> bind(String template, String path) {
        List<String> expected = segments(template);
        if (path.isEmpty() || path.charAt(0) != '/') {
            return null;
        }
        String[] actual = path.substring(1).split("/", -1);
        if (actual.length != expected.size()) {
            return null;
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        for (int i = 0; i < actual.length; i++) {
            String segment = expected.get(i);
            if (PARAMETER.matcher(segment).matches()) {
                if (actual[i].isEmpty()) {
                    return null;
                }
                parameters.put(segment.substring(1, segment.length() - 1), actual[i]);
            } else if (!segment.equals(actual[i])) {
                return null;
            }
        }
        return parameters;
    }

    private static List<String> segments(String template) {
        if (template == null || template.length() < 2 || template.charAt(0) != '/') {
            throw new IllegalArgumentException("Invalid route template " + template);
        }
        List<String> segments = List.of(template.substring(1).split("/", -1));
        List<String> names = new ArrayList<>();
        for (String segment : segments) {
            boolean parameter = PARAMETER.matcher(segment).matches();
            if (!parameter
                    && (!LITERAL.matcher(segment).matches()
                            || segment.equals(".")
                            || segment.equals(".."))) {
                throw new IllegalArgumentException("Invalid route template " + template);
            }
            if (parameter) {
                if (names.contains(segment)) {
                    throw new IllegalArgumentException("Duplicate parameter in " + template);
                }
                names.add(segment);
            }
        }
        return segments;
    }

    private static List<String> shape(String template) {
        List<String> shape = new ArrayList<>();
        for (String segment : segments(template)) {
            shape.add(PARAMETER.matcher(segment).matches() ? "{}" : segment);
        }
        return shape;
    }

    private static int literalCount(String template) {
        int count = 0;
        for (String segment : segments(template)) {
            if (!PARAMETER.matcher(segment).matches()) {
                count++;
            }
        }
        return count;
    }
}
