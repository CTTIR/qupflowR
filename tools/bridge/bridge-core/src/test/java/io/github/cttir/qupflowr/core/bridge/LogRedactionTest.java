/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import io.github.cttir.qupflowr.core.http.RawHttp;
import io.github.cttir.qupflowr.protocol.HostType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The token must never reach a log line, even when clients place it in the path, query, method,
 * request id, Origin or header names, or send it with a wrong prefix.
 */
class LogRedactionTest {

    @TempDir Path tmp;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger root;
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        previousLevel = root.getLevel();
        root.setLevel(Level.DEBUG);
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void restoreLogs() {
        root.detachAppender(appender);
        root.setLevel(previousLevel);
    }

    @Test
    void tokenNeverAppearsInLogs() throws IOException {
        BridgeServer server =
                BridgeServer.start(
                        new BridgeHostInfo(
                                tmp.resolve("runtime"), HostType.WORKER, "0.7.0", "t", null));
        String token;
        try {
            BridgeClient client = new BridgeClient(server);
            token = client.token();
            String host = "Host: " + client.host() + "\r\n";
            String auth = "Authorization: Bearer " + token + "\r\n";
            List<String> requests =
                    List.of(
                            "GET /v1/health HTTP/1.1\r\n" + host + auth + "\r\n",
                            "GET /v1/" + token + " HTTP/1.1\r\n" + host + auth + "\r\n",
                            "GET /v1/health?token="
                                    + token
                                    + " HTTP/1.1\r\n"
                                    + host
                                    + auth
                                    + "\r\n",
                            token + " /v1/health HTTP/1.1\r\n" + host + auth + "\r\n",
                            "GET /v1/health HTTP/1.1\r\n"
                                    + host
                                    + "Authorization: Bearer "
                                    + token
                                    + "x\r\n\r\n",
                            "GET /v1/health HTTP/1.1\r\n"
                                    + host
                                    + auth
                                    + "X-Request-Id: "
                                    + token
                                    + "\r\n\r\n",
                            "GET /v1/health HTTP/1.1\r\n"
                                    + host
                                    + auth
                                    + "Origin: http://"
                                    + token
                                    + "\r\n\r\n",
                            "GET /v1/health HTTP/1.1\r\n"
                                    + host
                                    + token
                                    + ": 1\r\n"
                                    + auth
                                    + "\r\n",
                            "GET /v1/health HTTP/1.1\r\nHost: " + token + "\r\n" + auth + "\r\n",
                            "GET /v1/"
                                    + token
                                    + " HTTP/1.1\r\n"
                                    + host
                                    + auth
                                    + "Content-Length: 99\r\n\r\n");
            for (String request : requests) {
                RawHttp.Result r =
                        RawHttp.exchange(
                                client.port(), request.getBytes(StandardCharsets.ISO_8859_1), 5000);
                assertFalse(r.raw().contains(token), "response echoes the token: " + r.raw());
            }
        } finally {
            server.stop();
        }
        String logText =
                appender.list.stream()
                        .map(
                                e ->
                                        e.getFormattedMessage()
                                                + " "
                                                + e.getLoggerName()
                                                + " "
                                                + (e.getThrowableProxy() == null
                                                        ? ""
                                                        : e.getThrowableProxy().getMessage()))
                        .collect(Collectors.joining("\n"));
        // Non-vacuous: request events were logged, including unmatched routes and unknown methods.
        assertTrue(
                logText.contains("bridge event=RESPONSE status=200 method=GET route=/v1/health"),
                logText);
        assertTrue(logText.contains("route=(unmatched)"), logText);
        assertTrue(logText.contains("method=(other)"), logText);
        assertTrue(logText.contains("qupflowR bridge started"), logText);
        assertTrue(logText.contains("qupflowR bridge stopped"), logText);
        assertEquals(-1, logText.indexOf(token), "token found in logs");
        assertFalse(
                Files.exists(tmp.resolve("runtime/instances/" + server.instanceId() + ".token")));
    }
}
