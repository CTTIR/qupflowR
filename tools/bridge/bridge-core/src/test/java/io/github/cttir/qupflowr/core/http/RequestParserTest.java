/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

/** Unit rules of the head parser (RFC 9112 framing plus the SP01b target and Host rules). */
class RequestParserTest {

    private static final char CTRL_A = (char) 0x01;
    private static final char DEL = (char) 0x7F;

    @Test
    void parsesRequestLine() throws HttpFailure {
        RequestParser.RequestLine line =
                RequestParser.parseRequestLine("GET /v1/health?x=1 HTTP/1.1");
        assertEquals("GET", line.method());
        assertEquals("/v1/health?x=1", line.target());
        assertEquals("HTTP/1.1", line.version());
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "HELLO|400|invalid_request_line",
                "GET /ok|400|invalid_request_line",
                "GET  /ok HTTP/1.1|400|invalid_request_line",
                "'GET /ok HTTP/1.1 '|400|invalid_request_line",
                "' GET /ok HTTP/1.1'|400|invalid_request_line",
                "G(T /ok HTTP/1.1|400|invalid_method",
                "GET /ok HTTP/2.0|505|http_version_not_supported",
                "GET /ok HTTP/0.9|505|http_version_not_supported",
                "GET /ok HTTP/1.11|400|invalid_http_version",
                "GET /ok http/1.1|400|invalid_http_version",
                "GET /é HTTP/1.1|400|invalid_request_line"
            })
    void rejectsBadRequestLines(String line, int status, String reason) {
        assertFailure(status, reason, () -> RequestParser.parseRequestLine(line));
    }

    @Test
    void rejectsControlCharactersInRequestLine() {
        assertFailure(
                400,
                "invalid_request_line",
                () -> RequestParser.parseRequestLine("GET /o" + CTRL_A + "k HTTP/1.1"));
        assertFailure(
                400,
                "invalid_request_line",
                () -> RequestParser.parseRequestLine("GET\t/ok HTTP/1.1"));
    }

    @Test
    void parsesHeaderLinesWithOptionalWhitespace() throws HttpFailure {
        String[] field = RequestParser.parseHeaderLine("X-Name: \t value with  spaces \t");
        assertEquals("X-Name", field[0]);
        assertEquals("value with  spaces", field[1]);
        assertEquals("", RequestParser.parseHeaderLine("Empty:")[1]);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "' folded'|obsolete_line_folding",
                "'NoColon'|invalid_header_line",
                "':value'|invalid_header_line",
                "'Host : x'|invalid_header_name",
                "'X(Y): x'|invalid_header_name"
            })
    void rejectsBadHeaderLines(String line, String reason) {
        assertFailure(400, reason, () -> RequestParser.parseHeaderLine(line));
    }

    @Test
    void rejectsControlCharactersInHeaders() {
        assertFailure(
                400, "obsolete_line_folding", () -> RequestParser.parseHeaderLine("\tfolded"));
        assertFailure(
                400,
                "invalid_header_value",
                () -> RequestParser.parseHeaderLine("X: a" + CTRL_A + "b"));
        assertFailure(
                400,
                "invalid_header_value",
                () -> RequestParser.parseHeaderLine("X: a" + DEL + "b"));
    }

    @Test
    void acceptsNormalisedTargets() throws HttpFailure {
        assertEquals(new RequestParser.Target("/", null), RequestParser.validateTarget("/"));
        assertEquals(
                new RequestParser.Target("/v1/a-b_c.d~e", "q=%2e%2e&x"),
                RequestParser.validateTarget("/v1/a-b_c.d~e?q=%2e%2e&x"));
        assertEquals(new RequestParser.Target("/v1/x", ""), RequestParser.validateTarget("/v1/x?"));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "http://127.0.0.1/ok|request_target_not_origin_form",
                "*|request_target_not_origin_form",
                "''|request_target_not_origin_form",
                "/ok#frag|fragment_not_allowed",
                "/%2e%2e/ok|encoded_separator_or_dot",
                "/%2E%2E/ok|encoded_separator_or_dot",
                "/v1%2Fok|encoded_separator_or_dot",
                "/v1%5cok|encoded_separator_or_dot",
                "/ok%00|encoded_separator_or_dot",
                "/%252e|percent_encoding_not_allowed",
                "/%6Fk|percent_encoding_not_allowed",
                "/%c0%ae|percent_encoding_not_allowed",
                "/ok%|percent_encoding_not_allowed",
                "/x/../ok|dot_segment",
                "/./ok|dot_segment",
                "/..|dot_segment",
                "//ok|empty_path_segment",
                "/ok/|empty_path_segment",
                "/ok\\x|invalid_path_character",
                "'/o k'|invalid_path_character",
                "/ok;x|invalid_path_character"
            })
    void rejectsUnnormalisedTargets(String target, String reason) {
        assertFailure(400, reason, () -> RequestParser.validateTarget(target));
    }

    @Test
    void contentLengthFraming() throws HttpFailure {
        assertEquals(0, RequestParser.contentLength(headers()));
        assertEquals(5, RequestParser.contentLength(headers("Content-Length", "5")));
        assertEquals(5, RequestParser.contentLength(headers("Content-Length", "00000005")));
        assertEquals(0, RequestParser.contentLength(headers("Content-Length", "000")));
        assertEquals(
                999_999_999_999_999_999L,
                RequestParser.contentLength(headers("Content-Length", "999999999999999999")));
        assertEquals(
                Long.MAX_VALUE,
                RequestParser.contentLength(headers("Content-Length", "99999999999999999999999")));
        assertFailure(
                400,
                "duplicate_content_length",
                () ->
                        RequestParser.contentLength(
                                headers("Content-Length", "1", "Content-Length", "1")));
        for (String bad : List.of("", "-1", "+5", "5 5", "1, 1", "0x10", "5.0", "٥")) {
            assertFailure(
                    400,
                    "invalid_content_length",
                    () -> RequestParser.contentLength(headers("Content-Length", bad)));
        }
        assertFailure(
                411,
                "transfer_encoding_not_supported",
                () -> RequestParser.contentLength(headers("Transfer-Encoding", "chunked")));
        assertFailure(
                400,
                "transfer_encoding_with_content_length",
                () ->
                        RequestParser.contentLength(
                                headers("Transfer-Encoding", "identity", "Content-Length", "3")));
    }

    @Test
    void expectHandling() throws HttpFailure {
        RequestParser.checkExpect(headers("Expect", "100-continue"), "HTTP/1.1");
        RequestParser.checkExpect(headers("Expect", "100-CONTINUE"), "HTTP/1.1");
        RequestParser.checkExpect(headers("Expect", "anything"), "HTTP/1.0");
        assertFailure(
                417,
                "unsupported_expectation",
                () -> RequestParser.checkExpect(headers("Expect", "gimme"), "HTTP/1.1"));
        assertFailure(
                417,
                "unsupported_expectation",
                () ->
                        RequestParser.checkExpect(
                                headers("Expect", "100-continue", "Expect", "100-continue"),
                                "HTTP/1.1"));
    }

    @Test
    void loopbackHostPolicy() {
        HostPolicy policy = HostPolicy.loopback();
        assertNull(policy.check(List.of("127.0.0.1:8080"), "HTTP/1.1", 8080));
        assertNull(policy.check(List.of("LOCALHOST:8080"), "HTTP/1.0", 8080));
        assertEquals(
                TransportError.of(400, "MALFORMED_REQUEST", "duplicate_host"),
                policy.check(List.of("127.0.0.1:8080", "127.0.0.1:8080"), "HTTP/1.1", 8080));
        assertEquals(
                TransportError.of(400, "MALFORMED_REQUEST", "missing_host"),
                policy.check(List.of(), "HTTP/1.1", 8080));
        assertEquals(
                TransportError.of(403, "SCOPE_DENIED", "host_not_allowed"),
                policy.check(List.of(), "HTTP/1.0", 8080));
        for (String foreign :
                List.of(
                        "evil.example",
                        "127.0.0.1",
                        "127.0.0.1:1",
                        "0.0.0.0:8080",
                        "[::1]:8080",
                        "127.0.0.1:8080@evil.example",
                        "localhost.:8080",
                        "127.0.0.1:08080",
                        " 127.0.0.1:8080")) {
            assertEquals(
                    TransportError.of(403, "SCOPE_DENIED", "host_not_allowed"),
                    policy.check(List.of(foreign), "HTTP/1.1", 8080),
                    foreign);
        }
    }

    @Test
    void tokenCharacters() {
        assertTrue(RequestParser.isToken("GET"));
        assertTrue(RequestParser.isToken("X-A_b.c~!#$%&'*+^`|"));
        assertFalse(RequestParser.isToken(""));
        assertFalse(RequestParser.isToken("A B"));
        assertFalse(RequestParser.isToken("A:B"));
        assertFalse(RequestParser.isToken("A\"B"));
    }

    private static Headers headers(String... nameValues) {
        List<String[]> entries = new ArrayList<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            entries.add(new String[] {nameValues[i], nameValues[i + 1]});
        }
        return new Headers(entries);
    }

    private static void assertFailure(int status, String reason, Executable executable) {
        HttpFailure failure = assertThrows(HttpFailure.class, executable);
        assertEquals(status, failure.error.status(), reason);
        assertEquals(reason, failure.error.reason());
    }
}
