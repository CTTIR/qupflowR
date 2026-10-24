/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.bridge;

import io.github.cttir.qupflowr.core.http.Admission;
import io.github.cttir.qupflowr.core.http.HostPolicy;
import io.github.cttir.qupflowr.core.http.HttpHandler;
import io.github.cttir.qupflowr.core.http.Limits;
import io.github.cttir.qupflowr.core.http.LoopbackHttpServer;
import io.github.cttir.qupflowr.core.http.RequestHead;
import io.github.cttir.qupflowr.core.http.Response;
import io.github.cttir.qupflowr.core.http.ServerEvent;
import io.github.cttir.qupflowr.core.http.ServerStats;
import io.github.cttir.qupflowr.core.http.TransportError;
import io.github.cttir.qupflowr.protocol.ErrorCodeRegistry;
import io.github.cttir.qupflowr.protocol.ErrorCodes;
import io.github.cttir.qupflowr.protocol.HostType;
import io.github.cttir.qupflowr.protocol.ProtocolVersion;
import io.github.cttir.qupflowr.protocol.dto.DiscoveryManifest;
import io.github.cttir.qupflowr.protocol.dto.HealthData;
import io.github.cttir.qupflowr.protocol.json.ProtocolJson;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Local master API of one host (protocol v1), served by {@link LoopbackHttpServer}.
 *
 * <p>Admission order for every request, before any body byte is read: message framing, Host and
 * target (transport), then {@code Origin} (browsers are rejected), bearer token, route and method,
 * then the route body limit. The token exists only in memory and in the private token file; it is
 * never logged, returned or placed in the manifest. Request logs name the matched route template,
 * never the raw path, query or header values.
 */
public final class BridgeServer {

    private static final Logger logger = LoggerFactory.getLogger(BridgeServer.class);

    /** Listening address. */
    public static final String LOOPBACK = "127.0.0.1";

    /** System property: log every transport event at INFO instead of DEBUG. */
    public static final String PROPERTY_LOG_REQUESTS = "qupflowr.bridge.logRequests";

    private static final Set<String> LOGGED_METHODS =
            Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    private final BridgeHostInfo host;
    private final String instanceId;
    private final BridgeToken token;
    private final BridgeRuntimeFiles files;
    private final ErrorResponses responses;
    private final Router router;
    private final boolean logRequests = Boolean.getBoolean(PROPERTY_LOG_REQUESTS);
    private volatile LoopbackHttpServer http;
    private volatile DiscoveryManifest manifest;
    private volatile boolean running;

    private BridgeServer(
            BridgeHostInfo host,
            String instanceId,
            BridgeToken token,
            BridgeRuntimeFiles files,
            ErrorResponses responses) {
        this.host = host;
        this.instanceId = instanceId;
        this.token = token;
        this.files = files;
        this.responses = responses;
        this.router =
                Router.of(
                        List.of(
                                new Router.Route(
                                        "GET",
                                        ProtocolVersion.PATH_PREFIX + "/health",
                                        0,
                                        (context, body) -> health()),
                                new Router.Route(
                                        "GET",
                                        ProtocolVersion.PATH_PREFIX + "/capabilities",
                                        0,
                                        (context, body) ->
                                                Capabilities.report(
                                                        host.hostType(), http.limits()))));
    }

    /**
     * Starts a bridge with the default limits and the bundled error-code registry. Must not be
     * called on the JavaFX application thread.
     *
     * @param host host information
     * @return running bridge
     * @throws IOException if binding or writing the discovery files fails (nothing is left behind)
     */
    public static BridgeServer start(BridgeHostInfo host) throws IOException {
        return start(host, Limits.defaults(), ErrorCodeRegistry.bundled());
    }

    /**
     * Starts a bridge: bind, write the token file, then publish the manifest.
     *
     * @param host host information
     * @param limits transport limits
     * @param registry error-code registry
     * @return running bridge
     * @throws IOException if binding or writing the discovery files fails (nothing is left behind)
     */
    public static BridgeServer start(BridgeHostInfo host, Limits limits, ErrorCodeRegistry registry)
            throws IOException {
        Objects.requireNonNull(host, "host");
        String instanceId = UUID.randomUUID().toString();
        ErrorResponses responses = new ErrorResponses(registry, instanceId);
        responses.requireCodes(ErrorCodes.emitted());
        BridgeRuntimeFiles files = BridgeRuntimeFiles.prepare(host.runtimeDir(), instanceId);
        BridgeServer bridge =
                new BridgeServer(
                        host,
                        instanceId,
                        BridgeToken.generate(new SecureRandom()),
                        files,
                        responses);
        try {
            bridge.http =
                    LoopbackHttpServer.start(
                            bridge.new Handler(), bridge::onEvent, limits, HostPolicy.loopback());
            files.writeToken(bridge.token);
            bridge.manifest = bridge.buildManifest();
            files.writeManifest(ProtocolJson.toPrettyJson(bridge.manifest) + "\n");
            bridge.running = true;
        } catch (IOException | RuntimeException e) {
            bridge.stop();
            throw e;
        }
        logger.info(
                "qupflowR bridge started: host_type={} instance={} address={}:{} manifest={}",
                host.hostType().wireValue(),
                instanceId,
                LOOPBACK,
                bridge.port(),
                files.manifestPath());
        return bridge;
    }

    /**
     * Stops the bridge: removes the manifest, closes the listener and connections, removes the
     * token file. Safe to call repeatedly and from a shutdown hook.
     */
    public synchronized void stop() {
        boolean wasRunning = running;
        running = false;
        files.deleteManifest();
        LoopbackHttpServer server = http;
        if (server != null) {
            server.close();
        }
        files.deleteToken();
        if (wasRunning && server != null) {
            logger.info(
                    "qupflowR bridge stopped: instance={} port={} {}",
                    instanceId,
                    server.port(),
                    server.stats());
        }
    }

    /**
     * Tells whether the bridge is serving requests.
     *
     * @return true between a successful start and stop
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the bound port.
     *
     * @return port on 127.0.0.1
     */
    public int port() {
        return http.port();
    }

    /**
     * Returns the instance id.
     *
     * @return instance UUID
     */
    public String instanceId() {
        return instanceId;
    }

    /**
     * Returns the manifest path.
     *
     * @return absolute manifest path
     */
    public Path manifestPath() {
        return files.manifestPath();
    }

    /**
     * Returns the token file path.
     *
     * @return absolute token file path
     */
    public Path tokenPath() {
        return files.tokenPath();
    }

    /**
     * Returns the published manifest.
     *
     * @return manifest
     */
    public DiscoveryManifest manifest() {
        return manifest;
    }

    /**
     * Returns transport statistics.
     *
     * @return statistics
     */
    public ServerStats stats() {
        return http.stats();
    }

    private DiscoveryManifest buildManifest() {
        ProcessHandle self = ProcessHandle.current();
        String startTime =
                self.info()
                        .startInstant()
                        .map(i -> i.truncatedTo(ChronoUnit.MILLIS).toString())
                        .orElse(null);
        return new DiscoveryManifest(
                host.hostType().wireValue(),
                self.pid(),
                startTime,
                instanceId,
                LOOPBACK,
                port(),
                host.qupathVersion(),
                host.bridgeVersion(),
                ProtocolVersion.CURRENT_STRING,
                files.tokenPath().toString(),
                Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
    }

    private HealthData health() {
        Boolean blocked = null;
        if (host.hostType() == HostType.LIVE) {
            boolean value;
            try {
                value = host.fxThreadBlocked().getAsBoolean();
            } catch (RuntimeException e) {
                value = true;
            }
            blocked = value;
        }
        return new HealthData(
                "ready",
                host.hostType().wireValue(),
                host.qupathVersion(),
                host.bridgeVersion(),
                ProtocolVersion.CURRENT_STRING,
                Runtime.version().toString(),
                blocked);
    }

    private void onEvent(ServerEvent event) {
        if (!logRequests && !logger.isDebugEnabled()) {
            return;
        }
        String route =
                event.path() == null
                        ? "-"
                        : Objects.requireNonNullElse(
                                router.templateFor(event.path()), "(unmatched)");
        String method =
                event.method() == null
                        ? "-"
                        : LOGGED_METHODS.contains(event.method()) ? event.method() : "(other)";
        String message =
                String.format(
                        Locale.ROOT,
                        "bridge event=%s status=%d method=%s route=%s elapsed_ms=%.2f reason=%s",
                        event.type(),
                        event.status(),
                        method,
                        route,
                        event.elapsedMillis(),
                        event.reason());
        if (logRequests) {
            logger.info(message);
        } else {
            logger.debug(message);
        }
    }

    private final class Handler implements HttpHandler {

        @Override
        public Admission admit(RequestHead head) {
            String requestId = ErrorResponses.requestId(head.headers().first("X-Request-Id"));
            if (head.headers().contains("Origin")) {
                return Admission.reject(
                        responses.error(
                                requestId,
                                ErrorCodes.SCOPE_DENIED,
                                "origin_not_allowed",
                                null,
                                Map.of()));
            }
            if (!token.matchesAuthorizationHeaders(head.headers().all("Authorization"))) {
                return Admission.reject(
                        responses.error(
                                requestId,
                                ErrorCodes.AUTH_REQUIRED,
                                "bearer_token_required",
                                null,
                                Map.of("WWW-Authenticate", "Bearer realm=\"qupflowr\"")));
            }
            Router.Match match = router.match(head.method(), head.path());
            return switch (match) {
                case Router.Match.NotFound notFound ->
                        Admission.reject(
                                responses.error(
                                        requestId,
                                        ErrorCodes.ENDPOINT_NOT_FOUND,
                                        "unknown_endpoint",
                                        null,
                                        Map.of()));
                case Router.Match.MethodNotAllowed notAllowed ->
                        Admission.reject(
                                responses.error(
                                        requestId,
                                        ErrorCodes.METHOD_NOT_ALLOWED,
                                        "method_not_allowed",
                                        null,
                                        Map.of("Allow", String.join(", ", notAllowed.allowed()))));
                case Router.Match.Found found ->
                        Admission.accept(
                                found.route().maxBodyBytes(),
                                (h, body) -> {
                                    RequestContext context =
                                            new RequestContext(
                                                    requestId,
                                                    found.route().template(),
                                                    found.parameters(),
                                                    h);
                                    return responses.success(
                                            requestId,
                                            found.route().handler().handle(context, body));
                                });
            };
        }

        @Override
        public Response transportError(RequestHead head, TransportError error) {
            String requestId =
                    ErrorResponses.requestId(
                            head == null ? null : head.headers().first("X-Request-Id"));
            return responses.error(
                    requestId, error.code(), error.reason(), error.limitBytes(), Map.of());
        }
    }
}
