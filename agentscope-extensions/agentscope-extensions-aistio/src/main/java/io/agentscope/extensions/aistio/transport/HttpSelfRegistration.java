/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.aistio.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Standalone (no-Kubernetes) self-registration against aistiod:
 * {@code POST /api/v1/dataplanes/register} + periodic heartbeats.
 *
 * <p>The control plane then polls this instance's {@code /agentscope/*} contract at {@code
 * baseUrl}. Failures are swallowed and retried — registration must never disturb the agent.
 */
public final class HttpSelfRegistration implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(HttpSelfRegistration.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INTERNAL_TOKEN_HEADER = "X-Builder-Internal-Token";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final String controlPlaneHttp;
    private final String internalToken;
    private final String agentName;
    private final String namespace;
    private final String instanceId;
    private final String baseUrl;
    private final String runtime;
    private final String framework;
    private final int contractLevel;
    private final List<String> capabilities;
    private final long heartbeatIntervalMs;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    private final AtomicBoolean registered = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    public HttpSelfRegistration(
            String controlPlaneHttp,
            String internalToken,
            String agentName,
            String namespace,
            String instanceId,
            String baseUrl,
            String runtime,
            String framework,
            int contractLevel,
            List<String> capabilities,
            long heartbeatIntervalMs) {
        this.controlPlaneHttp =
                trimSlash(Objects.requireNonNull(controlPlaneHttp, "controlPlaneHttp"));
        this.internalToken = Objects.requireNonNull(internalToken, "internalToken");
        this.agentName = Objects.requireNonNull(agentName, "agentName");
        this.namespace = (namespace == null || namespace.isBlank()) ? "default" : namespace;
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.baseUrl = trimSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.runtime = runtime == null || runtime.isBlank() ? "agentscope-java" : runtime;
        this.framework = framework == null || framework.isBlank() ? runtime : framework;
        this.contractLevel = contractLevel > 0 ? contractLevel : 3;
        this.capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        this.heartbeatIntervalMs = heartbeatIntervalMs > 0 ? heartbeatIntervalMs : 15_000L;
    }

    public void start() {
        if (scheduler != null) {
            return;
        }
        scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "aistio-http-register");
                            t.setDaemon(true);
                            return t;
                        });
        tryRegister();
        scheduler.scheduleWithFixedDelay(
                this::heartbeatSafe,
                heartbeatIntervalMs,
                heartbeatIntervalMs,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (!registered.get()) {
            return;
        }
        try {
            request("DELETE", "/api/v1/dataplanes/" + instanceId, null);
            LOG.info(() -> "aistio: unregistered instance " + instanceId);
        } catch (Exception e) {
            LOG.log(Level.FINE, "aistio: unregister failed", e);
        } finally {
            registered.set(false);
        }
    }

    private void heartbeatSafe() {
        try {
            if (!registered.get()) {
                tryRegister();
                return;
            }
            int code = request("POST", "/api/v1/dataplanes/" + instanceId + "/heartbeat", Map.of());
            if (code == 404) {
                registered.set(false);
                tryRegister();
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "aistio: heartbeat failed; will re-register", e);
            registered.set(false);
            try {
                tryRegister();
            } catch (Exception ignored) {
                // swallowed
            }
        }
    }

    private void tryRegister() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agentName", agentName);
        body.put("namespace", namespace);
        body.put("instanceId", instanceId);
        body.put("baseUrl", baseUrl);
        body.put("runtime", runtime);
        body.put("framework", framework);
        body.put("contractLevel", contractLevel);
        body.put("capabilities", capabilities);
        body.put("source", "self-register");
        try {
            int code = request("POST", "/api/v1/dataplanes/register", body);
            if (code >= 200 && code < 300) {
                registered.set(true);
                LOG.info(
                        () ->
                                "aistio: registered "
                                        + instanceId
                                        + " at "
                                        + baseUrl
                                        + " with "
                                        + controlPlaneHttp);
            } else {
                registered.set(false);
                LOG.warning("aistio: register returned HTTP " + code);
            }
        } catch (Exception e) {
            registered.set(false);
            LOG.log(Level.WARNING, "aistio: register failed (will retry): " + e.getMessage(), e);
        }
    }

    private int request(String method, String path, Object body) throws Exception {
        HttpRequest.Builder b =
                HttpRequest.newBuilder()
                        .uri(URI.create(controlPlaneHttp + path))
                        .timeout(HTTP_TIMEOUT)
                        .header(INTERNAL_TOKEN_HEADER, internalToken);
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            byte[] json = MAPPER.writeValueAsBytes(body);
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(json));
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 200 && resp.statusCode() < 300 && resp.body() != null) {
            // Touch response for future heartbeatInterval parsing; ignore unknown shapes.
            try {
                JsonNode node = MAPPER.readTree(resp.body());
                if (node.has("heartbeatInterval")) {
                    // reserved for adaptive interval; fixed schedule is fine for now
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        return resp.statusCode();
    }

    private static String trimSlash(String url) {
        String t = url.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
