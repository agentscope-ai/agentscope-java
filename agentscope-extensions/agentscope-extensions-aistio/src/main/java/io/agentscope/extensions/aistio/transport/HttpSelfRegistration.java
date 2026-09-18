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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * v5 external-application registration against aistiod:
 * {@code POST /api/v1/agent-registrations} + periodic instance heartbeats.
 *
 * <p>The control plane then polls this instance's {@code /agentscope/*} contract at {@code
 * baseUrl}. Failures are swallowed and retried — registration must never disturb the agent.
 */
public final class HttpSelfRegistration implements AutoCloseable {

    /** Stable identity returned by the Catalog registration transaction. */
    public record RegisteredIdentity(
            String agentId,
            String agentKey,
            String bindingId,
            String instanceId,
            String instanceKey,
            long generation,
            String registrationCredential) {}

    private static final Logger LOG = Logger.getLogger(HttpSelfRegistration.class.getName());

    private final ControlPlaneHttpClient http;
    private final String controlPlaneHttp;
    private final String agentKey;
    private final String configuredRegistrationCredential;
    private final String tenant;
    private final String namespace;
    private final String instanceKey;
    private final String baseUrl;
    private final String runtime;
    private final String framework;
    private final int contractLevel;
    private final List<String> capabilities;
    private final long heartbeatIntervalMs;

    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final AtomicReference<String> registeredInstanceId = new AtomicReference<>();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<RegisteredIdentity> identity = new AtomicReference<>();
    private volatile Consumer<RegisteredIdentity> identityListener = ignored -> {};
    private ScheduledExecutorService scheduler;

    public HttpSelfRegistration(
            String controlPlaneHttp,
            String internalToken,
            String registrationCredential,
            String agentKey,
            String tenant,
            String namespace,
            String instanceKey,
            String baseUrl,
            String runtime,
            String framework,
            int contractLevel,
            List<String> capabilities,
            long heartbeatIntervalMs) {
        this.http =
                new ControlPlaneHttpClient(
                        Objects.requireNonNull(controlPlaneHttp, "controlPlaneHttp"),
                        Objects.requireNonNull(internalToken, "internalToken"));
        this.controlPlaneHttp = this.http.baseUrl();
        this.configuredRegistrationCredential =
                registrationCredential == null ? "" : registrationCredential.trim();
        this.agentKey = Objects.requireNonNull(agentKey, "agentKey");
        this.tenant = (tenant == null || tenant.isBlank()) ? "default" : tenant;
        this.namespace = (namespace == null || namespace.isBlank()) ? "default" : namespace;
        this.instanceKey = Objects.requireNonNull(instanceKey, "instanceKey");
        this.baseUrl = ControlPlaneHttpClient.trimSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
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

    /** Returns the last successfully registered stable identity, or {@code null}. */
    public RegisteredIdentity identity() {
        return identity.get();
    }

    /** Receives every successfully refreshed identity, including generation changes. */
    public void setIdentityListener(Consumer<RegisteredIdentity> listener) {
        identityListener = listener == null ? ignored -> {} : listener;
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
            String id = registeredInstanceId.get();
            if (id != null && !id.isBlank()) {
                request(
                        "DELETE",
                        "/api/v1/dataplanes/" + id,
                        Map.of("generation", generation.get()));
            }
            LOG.info(() -> "aistio: unregistered instance " + instanceKey);
        } catch (Exception e) {
            LOG.log(Level.FINE, "aistio: unregister failed", e);
        } finally {
            registered.set(false);
            registeredInstanceId.set(null);
        }
    }

    private void heartbeatSafe() {
        try {
            if (!registered.get()) {
                tryRegister();
                return;
            }
            String id = registeredInstanceId.get();
            if (id == null || id.isBlank()) {
                registered.set(false);
                tryRegister();
                return;
            }
            int code =
                    request(
                            "POST",
                            "/api/v1/dataplanes/" + id + "/heartbeat",
                            Map.of("generation", generation.get()));
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
        body.put("tenant", tenant);
        body.put("agentKey", agentKey);
        body.put("namespace", namespace);
        body.put("instanceKey", instanceKey);
        body.put("routingKey", baseUrl);
        body.put("framework", framework);
        body.put("sdkVersion", runtime);
        body.put("capacity", 1);
        body.put("capabilities", capabilities);
        try {
            String claimCredential =
                    identity.get() != null
                            ? identity.get().registrationCredential()
                            : configuredRegistrationCredential;
            Map<String, String> headers =
                    claimCredential.isBlank()
                            ? Map.of()
                            : Map.of("X-Agent-Registration-Credential", claimCredential);
            ControlPlaneHttpClient.Response response =
                    http.send("POST", "/api/v1/agent-registrations", body, headers);
            int code = response.status();
            if (code >= 200 && code < 300) {
                JsonNode document = ControlPlaneHttpClient.mapper().readTree(response.body());
                String agentId = document.path("agent").path("id").asText("");
                String bindingId = document.path("binding").path("id").asText("");
                String durableId = document.path("instance").path("id").asText("");
                long currentGeneration = document.path("instance").path("generation").asLong();
                String issuedCredential =
                        document.path("registrationCredential").asText(claimCredential);
                if (agentId.isBlank()
                        || bindingId.isBlank()
                        || durableId.isBlank()
                        || currentGeneration <= 0
                        || issuedCredential.isBlank()) {
                    throw new IllegalStateException(
                            "registration response is missing stable identity or credential");
                }
                registeredInstanceId.set(durableId);
                generation.set(currentGeneration);
                RegisteredIdentity registeredIdentity =
                        new RegisteredIdentity(
                                agentId,
                                agentKey,
                                bindingId,
                                durableId,
                                instanceKey,
                                currentGeneration,
                                issuedCredential);
                identity.set(registeredIdentity);
                identityListener.accept(registeredIdentity);
                registered.set(true);
                LOG.info(
                        () ->
                                "aistio: registered "
                                        + instanceKey
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
        ControlPlaneHttpClient.Response resp = http.send(method, path, body);
        if (resp.status() >= 200 && resp.status() < 300 && resp.body() != null) {
            // Touch response for future heartbeatInterval parsing; ignore unknown shapes.
            try {
                JsonNode node = ControlPlaneHttpClient.mapper().readTree(resp.body());
                if (node.has("heartbeatInterval")) {
                    // reserved for adaptive interval; fixed schedule is fine for now
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        return resp.status();
    }
}
