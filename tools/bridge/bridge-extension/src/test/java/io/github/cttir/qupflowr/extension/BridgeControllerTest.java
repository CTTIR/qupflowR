/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.cttir.qupflowr.core.bridge.BridgeServer;
import io.github.cttir.qupflowr.core.qupath.QuPath07Adapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Life cycle without JavaFX: the publisher records states instead of updating FX properties. */
class BridgeControllerTest {

    @TempDir Path tmp;

    private final List<BridgeController.State> published = new CopyOnWriteArrayList<>();
    private BridgeController controller;

    @AfterEach
    void cleanUp() throws Exception {
        if (controller != null) {
            controller.stopAsync("test").get(10, TimeUnit.SECONDS);
            Runtime.getRuntime().removeShutdownHook(controller.shutdownHook());
        }
    }

    private BridgeController controller(
            String qupathVersion, BridgeController.ServerStarter starter) {
        controller =
                new BridgeController(
                        new QuPath07Adapter(() -> Optional.ofNullable(qupathVersion)),
                        tmp.resolve("runtime"),
                        "9.9.9-test",
                        () -> false,
                        Messages.forLanguage("de"),
                        published::add,
                        starter);
        return controller;
    }

    @Test
    void startsOnceAndStopsCleanly() throws Exception {
        BridgeController c = controller("0.7.0", BridgeServer::start);
        assertEquals(BridgeController.Phase.STOPPED, c.state().phase());
        c.startAsync("test").get(10, TimeUnit.SECONDS);
        BridgeServer server = c.currentServer();
        assertTrue(server.isRunning());
        assertEquals(
                List.of(BridgeController.Phase.STARTING, BridgeController.Phase.RUNNING),
                published.stream().map(BridgeController.State::phase).toList());
        assertEquals("Status: läuft auf 127.0.0.1:" + server.port(), c.state().text());
        assertEquals(server.port(), c.state().port());
        assertTrue(Files.exists(server.manifestPath()));
        assertTrue(Files.readString(server.manifestPath()).contains("\"host_type\": \"live\""));

        c.startAsync("again").get(10, TimeUnit.SECONDS);
        assertSame(server, c.currentServer(), "a second start does not create another instance");
        assertEquals(2, published.size());

        c.stopAsync("test").get(10, TimeUnit.SECONDS);
        assertNull(c.currentServer());
        assertEquals(BridgeController.Phase.STOPPED, c.state().phase());
        assertEquals("Status: gestoppt", c.state().text());
        assertFalse(Files.exists(server.manifestPath()));
        assertFalse(Files.exists(server.tokenPath()));
        assertThrows(IOException.class, () -> new Socket("127.0.0.1", server.port()).close());
    }

    @Test
    void refusesUnsupportedQuPathVersions() throws Exception {
        BridgeController c =
                controller(
                        "0.6.0",
                        host -> {
                            throw new AssertionError("must not start");
                        });
        c.startAsync("test").get(10, TimeUnit.SECONDS);
        assertEquals(BridgeController.Phase.UNSUPPORTED, c.state().phase());
        assertEquals(
                "Status: QuPath 0.6.0 wird nicht unterstützt (benötigt 0.7.0)", c.state().text());
        assertFalse(Files.exists(tmp.resolve("runtime")));

        BridgeController unknown =
                new BridgeController(
                        new QuPath07Adapter(Optional::empty),
                        tmp.resolve("r2"),
                        "t",
                        () -> false,
                        Messages.forLanguage("en"),
                        s -> {},
                        host -> {
                            throw new AssertionError("must not start");
                        });
        unknown.startAsync("test").get(10, TimeUnit.SECONDS);
        assertEquals("Status: QuPath ? is not supported (requires 0.7.0)", unknown.state().text());
    }

    @Test
    void reportsStartFailures() throws Exception {
        Files.writeString(tmp.resolve("runtime"), "not a directory");
        BridgeController c = controller("0.7.0", BridgeServer::start);
        c.startAsync("test").get(10, TimeUnit.SECONDS);
        assertEquals(BridgeController.Phase.FAILED, c.state().phase());
        assertNull(c.currentServer());
    }

    @Test
    void shutdownHookRemovesDiscoveryFiles() throws Exception {
        BridgeController c = controller("0.7.0", BridgeServer::start);
        c.startAsync("test").get(10, TimeUnit.SECONDS);
        Path instances = c.currentServer().manifestPath().getParent();
        c.shutdownHook().run();
        try (Stream<Path> files = Files.list(instances)) {
            assertEquals(0, files.count());
        }
        assertFalse(c.currentServer().isRunning());
    }

    @Test
    void failingPublisherDoesNotBreakTheLifecycle() throws Exception {
        controller =
                new BridgeController(
                        new QuPath07Adapter(() -> Optional.of("0.7.0")),
                        tmp.resolve("runtime"),
                        "t",
                        () -> false,
                        Messages.forLanguage("de"),
                        s -> {
                            throw new IllegalStateException("fx gone");
                        },
                        BridgeServer::start);
        controller.startAsync("test").get(10, TimeUnit.SECONDS);
        assertEquals(BridgeController.Phase.RUNNING, controller.state().phase());
    }
}
