/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HTTP/1.x request head parsing and validation.
 *
 * <p>Precedence (each step runs before any later one and before any body byte is read): request
 * line syntax and version, header syntax and limits, message framing ({@code Transfer-Encoding},
 * {@code Content-Length}), {@code Host} policy, request target, {@code Expect}. Origin, token,
 * route, method and body size are checked afterwards by the handler and the connection.
 */
final class RequestParser {

    private static final String BAD = TransportCodes.MALFORMED_REQUEST;

    private RequestParser() {}

    /** Request line components. */
    record RequestLine(String method, String target, String version) {}

    /** Path and raw query of an origin-form target. */
    record Target(String path, String query) {}

    /**
     * Reads and validates a complete request head.
     *
     * @return the head, or null if the client closed before sending a byte
     */
    static RequestHead readHead(
            ConnectionInput in, Limits limits, HostPolicy hostPolicy, int port, long headerDeadline)
            throws IOException, HttpFailure {
        String requestLine = in.readLine(limits.maxRequestLineBytes(), headerDeadline, true);
        if (requestLine == null) {
            return null;
        }
        RequestLine line = parseRequestLine(requestLine);
        List<String[]> fields = new ArrayList<>();
        int headerBytes = 0;
        while (true) {
            int budget = limits.maxHeaderBlockBytes() - headerBytes;
            if (budget <= 0) {
                throw HttpFailure.of(
                        431, TransportCodes.HEADERS_TOO_LARGE, "header_block_too_large");
            }
            String header = in.readLine(budget, headerDeadline, false);
            if (header == null) {
                throw HttpFailure.clientClosed();
            }
            headerBytes += in.lastLineBytes();
            if (header.isEmpty()) {
                break;
            }
            if (fields.size() >= limits.maxHeaderCount()) {
                throw HttpFailure.of(431, TransportCodes.HEADERS_TOO_LARGE, "too_many_headers");
            }
            fields.add(parseHeaderLine(header));
        }
        Headers headers = new Headers(fields);
        long contentLength = contentLength(headers);
        TransportError hostError = hostPolicy.check(headers.all("Host"), line.version(), port);
        if (hostError != null) {
            throw new HttpFailure(hostError, ServerEvent.Type.RESPONSE);
        }
        Target target = validateTarget(line.target());
        checkExpect(headers, line.version());
        return new RequestHead(
                line.method(),
                line.target(),
                target.path(),
                target.query(),
                line.version(),
                headers,
                contentLength);
    }

    static RequestLine parseRequestLine(String line) throws HttpFailure {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c < 0x20 || c >= 0x7F) {
                throw HttpFailure.of(400, BAD, "invalid_request_line");
            }
        }
        int sp1 = line.indexOf(' ');
        int sp2 = sp1 < 0 ? -1 : line.indexOf(' ', sp1 + 1);
        if (sp1 <= 0
                || sp2 < 0
                || sp2 == sp1 + 1
                || line.indexOf(' ', sp2 + 1) >= 0
                || sp2 == line.length() - 1) {
            throw HttpFailure.of(400, BAD, "invalid_request_line");
        }
        String method = line.substring(0, sp1);
        if (!isToken(method)) {
            throw HttpFailure.of(400, BAD, "invalid_method");
        }
        String version = line.substring(sp2 + 1);
        if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
            if (version.length() == 8
                    && version.startsWith("HTTP/")
                    && isDigit(version.charAt(5))
                    && version.charAt(6) == '.'
                    && isDigit(version.charAt(7))) {
                throw HttpFailure.of(
                        505,
                        TransportCodes.HTTP_VERSION_NOT_SUPPORTED,
                        "http_version_not_supported");
            }
            throw HttpFailure.of(400, BAD, "invalid_http_version");
        }
        return new RequestLine(method, line.substring(sp1 + 1, sp2), version);
    }

    static String[] parseHeaderLine(String line) throws HttpFailure {
        char first = line.charAt(0);
        if (first == ' ' || first == '\t') {
            throw HttpFailure.of(400, BAD, "obsolete_line_folding");
        }
        int colon = line.indexOf(':');
        if (colon <= 0) {
            throw HttpFailure.of(400, BAD, "invalid_header_line");
        }
        String name = line.substring(0, colon);
        if (!isToken(name)) {
            throw HttpFailure.of(400, BAD, "invalid_header_name");
        }
        int start = colon + 1;
        int end = line.length();
        while (start < end && isOws(line.charAt(start))) {
            start++;
        }
        while (end > start && isOws(line.charAt(end - 1))) {
            end--;
        }
        String value = line.substring(start, end);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7F) {
                throw HttpFailure.of(400, BAD, "invalid_header_value");
            }
        }
        return new String[] {name, value};
    }

    /** Validates an origin-form target without decoding it. */
    static Target validateTarget(String target) throws HttpFailure {
        if (target.isEmpty() || target.charAt(0) != '/') {
            throw HttpFailure.of(400, BAD, "request_target_not_origin_form");
        }
        if (target.indexOf('#') >= 0) {
            throw HttpFailure.of(400, BAD, "fragment_not_allowed");
        }
        int q = target.indexOf('?');
        String path = q < 0 ? target : target.substring(0, q);
        String query = q < 0 ? null : target.substring(q + 1);
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '%') {
                String encoded =
                        path.substring(i, Math.min(path.length(), i + 3)).toLowerCase(Locale.ROOT);
                boolean separatorOrDot =
                        encoded.equals("%2f")
                                || encoded.equals("%2e")
                                || encoded.equals("%5c")
                                || encoded.equals("%00");
                throw HttpFailure.of(
                        400,
                        BAD,
                        separatorOrDot
                                ? "encoded_separator_or_dot"
                                : "percent_encoding_not_allowed");
            }
            boolean unreserved =
                    (c >= 'a' && c <= 'z')
                            || (c >= 'A' && c <= 'Z')
                            || isDigit(c)
                            || c == '-'
                            || c == '.'
                            || c == '_'
                            || c == '~'
                            || c == '/';
            if (!unreserved) {
                throw HttpFailure.of(400, BAD, "invalid_path_character");
            }
        }
        if (!path.equals("/")) {
            for (String segment : path.substring(1).split("/", -1)) {
                if (segment.isEmpty()) {
                    throw HttpFailure.of(400, BAD, "empty_path_segment");
                }
                if (segment.equals(".") || segment.equals("..")) {
                    throw HttpFailure.of(400, BAD, "dot_segment");
                }
            }
        }
        return new Target(path, query);
    }

    /**
     * Message framing: body length from a single valid Content-Length; Transfer-Encoding refused.
     */
    static long contentLength(Headers headers) throws HttpFailure {
        List<String> lengths = headers.all("Content-Length");
        boolean transferEncoding = headers.contains("Transfer-Encoding");
        if (transferEncoding && !lengths.isEmpty()) {
            throw HttpFailure.of(400, BAD, "transfer_encoding_with_content_length");
        }
        if (transferEncoding) {
            throw HttpFailure.of(
                    411, TransportCodes.LENGTH_REQUIRED, "transfer_encoding_not_supported");
        }
        if (lengths.size() > 1) {
            throw HttpFailure.of(400, BAD, "duplicate_content_length");
        }
        if (lengths.isEmpty()) {
            return 0;
        }
        String value = lengths.get(0);
        if (value.isEmpty()) {
            throw HttpFailure.of(400, BAD, "invalid_content_length");
        }
        for (int i = 0; i < value.length(); i++) {
            if (!isDigit(value.charAt(i))) {
                throw HttpFailure.of(400, BAD, "invalid_content_length");
            }
        }
        int firstSignificant = 0;
        while (firstSignificant < value.length() - 1 && value.charAt(firstSignificant) == '0') {
            firstSignificant++;
        }
        String digits = value.substring(firstSignificant);
        // More than 18 digits cannot be represented exactly; it is larger than any limit anyway.
        return digits.length() > 18 ? Long.MAX_VALUE : Long.parseLong(digits);
    }

    static void checkExpect(Headers headers, String version) throws HttpFailure {
        List<String> expect = headers.all("Expect");
        if (expect.isEmpty() || !"HTTP/1.1".equals(version)) {
            return; // RFC 9110 10.1.1: expectations of HTTP/1.0 requests are ignored
        }
        if (expect.size() != 1 || !expect.get(0).equalsIgnoreCase("100-continue")) {
            throw HttpFailure.of(417, TransportCodes.EXPECTATION_FAILED, "unsupported_expectation");
        }
    }

    static boolean isToken(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok =
                    (c >= 'a' && c <= 'z')
                            || (c >= 'A' && c <= 'Z')
                            || isDigit(c)
                            || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isOws(char c) {
        return c == ' ' || c == '\t';
    }
}
