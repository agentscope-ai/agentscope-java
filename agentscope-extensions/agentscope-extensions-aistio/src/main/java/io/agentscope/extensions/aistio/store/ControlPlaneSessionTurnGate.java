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
package io.agentscope.extensions.aistio.store;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import io.agentscope.harness.agent.gateway.LocalSessionTurnGate;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.gateway.TurnBusyException;
import io.agentscope.harness.agent.gateway.TurnLease;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Control-plane hosted {@link SessionTurnGate} over {@code /api/v1/dp/locks/*}.
 *
 * <p>Lock names are {@code turn:<gateKey>}. On acquire success the returned lease renews every 20s
 * with a 60s TTL. Acquire uses a short timeout ({@value #ACQUIRE_TIMEOUT_MS}ms) and throws {@link
 * TurnBusyException} on conflict so the gateway can skip duplicate wakeups. If the control plane is
 * unreachable for ~30s the gate falls back to a process-local {@link LocalSessionTurnGate}.
 *
 * <p>When using this gate, configure {@link io.agentscope.core.ReActAgent} {@code conflictPolicy}
 * to {@code FAIL} so concurrent state writes surface as errors rather than silent overwrites.
 */
public final class ControlPlaneSessionTurnGate implements SessionTurnGate {

    private static final Logger LOG = Logger.getLogger(ControlPlaneSessionTurnGate.class.getName());

    private static final long RETRY_INTERVAL_MS = 500L;
    private static final long ACQUIRE_TIMEOUT_MS = 3_000L;
    private static final long UNREACHABLE_FALLBACK_MS = 30_000L;
    private static final long LEASE_TTL_SECONDS = 60L;
    private static final long RENEW_INTERVAL_SECONDS = 20L;

    private final ControlPlaneHttpClient http;
    private final String agentName;
    private final String namespace;
    private final LocalSessionTurnGate localFallback = new LocalSessionTurnGate();
    private final ScheduledExecutorService renewScheduler =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "aistio-turn-lock-renew");
                        t.setDaemon(true);
                        return t;
                    });

    ControlPlaneSessionTurnGate(ControlPlaneHttpClient http, String agentName, String namespace) {
        this.http = Objects.requireNonNull(http, "http");
        this.agentName = Objects.requireNonNull(agentName, "agentName");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    @Override
    public TurnLease acquire(String gateKey) throws InterruptedException, TurnBusyException {
        String lockName = composeLockName(gateKey);
        String holder = UUID.randomUUID().toString();
        long acquireDeadline =
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ACQUIRE_TIMEOUT_MS);
        long unreachableDeadline = -1L;

        while (true) {
            if (Thread.interrupted()) {
                throw new InterruptedException(
                        "Interrupted while waiting for session turn gate on " + lockName);
            }
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("agentName", agentName);
                body.put("namespace", namespace);
                body.put("name", lockName);
                body.put("ttlSeconds", LEASE_TTL_SECONDS);
                body.put("holder", holder);

                ControlPlaneHttpClient.Response resp =
                        http.send("POST", "/api/v1/dp/locks/acquire", body);
                if (resp.status() == 200) {
                    JsonNode node = ControlPlaneHttpClient.mapper().readTree(resp.body());
                    String ownerToken = node.path("ownerToken").asText();
                    return new RenewingLease(lockName, ownerToken);
                }
                if (resp.status() == 409) {
                    unreachableDeadline = -1L;
                    if (System.nanoTime() >= acquireDeadline) {
                        throw new TurnBusyException(gateKey);
                    }
                    Thread.sleep(RETRY_INTERVAL_MS);
                    continue;
                }
                if (unreachableDeadline < 0) {
                    unreachableDeadline =
                            System.nanoTime()
                                    + TimeUnit.MILLISECONDS.toNanos(UNREACHABLE_FALLBACK_MS);
                }
                if (System.nanoTime() >= unreachableDeadline) {
                    LOG.warning(
                            () ->
                                    "aistio: control plane unreachable for turn locks (HTTP "
                                            + resp.status()
                                            + "); falling back to process-local gate for "
                                            + lockName);
                    return localFallback.acquire(gateKey);
                }
                Thread.sleep(RETRY_INTERVAL_MS);
            } catch (TurnBusyException e) {
                throw e;
            } catch (InterruptedException e) {
                throw e;
            } catch (IOException e) {
                if (unreachableDeadline < 0) {
                    unreachableDeadline =
                            System.nanoTime()
                                    + TimeUnit.MILLISECONDS.toNanos(UNREACHABLE_FALLBACK_MS);
                }
                if (System.nanoTime() >= unreachableDeadline) {
                    LOG.warning(
                            () ->
                                    "aistio: control plane unreachable for turn locks ("
                                            + e.getMessage()
                                            + "); falling back to process-local gate for "
                                            + lockName);
                    return localFallback.acquire(gateKey);
                }
                Thread.sleep(RETRY_INTERVAL_MS);
            }
        }
    }

    @Override
    public boolean isRunning(String gateKey) {
        String lockName = composeLockName(gateKey);
        try {
            String path =
                    "/api/v1/dp/locks?agentName="
                            + urlEncode(agentName)
                            + "&namespace="
                            + urlEncode(namespace)
                            + "&name="
                            + urlEncode(lockName);
            ControlPlaneHttpClient.Response resp = http.send("GET", path, null);
            if (resp.status() == 200) {
                return true;
            }
            if (resp.status() == 404) {
                return false;
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "aistio: turn lock peek failed for " + lockName, e);
        }
        return localFallback.isRunning(gateKey);
    }

    private static String composeLockName(String gateKey) {
        return "turn:" + gateKey;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private final class RenewingLease implements TurnLease {

        private final String lockName;
        private final String ownerToken;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final ScheduledFuture<?> renewFuture;

        RenewingLease(String lockName, String ownerToken) {
            this.lockName = lockName;
            this.ownerToken = ownerToken;
            this.renewFuture =
                    renewScheduler.scheduleAtFixedRate(
                            this::renewSafe,
                            RENEW_INTERVAL_SECONDS,
                            RENEW_INTERVAL_SECONDS,
                            TimeUnit.SECONDS);
        }

        private void renewSafe() {
            if (closed.get()) {
                return;
            }
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("agentName", agentName);
                body.put("namespace", namespace);
                body.put("name", lockName);
                body.put("ownerToken", ownerToken);
                body.put("ttlSeconds", LEASE_TTL_SECONDS);
                ControlPlaneHttpClient.Response resp =
                        http.send("POST", "/api/v1/dp/locks/renew", body);
                if (resp.status() == 409) {
                    LOG.warning(
                            () ->
                                    "aistio: turn lock renew conflict for "
                                            + lockName
                                            + " — lease may have been stolen");
                } else if (resp.status() < 200 || resp.status() >= 300) {
                    LOG.log(
                            Level.FINE,
                            () ->
                                    "aistio: turn lock renew HTTP "
                                            + resp.status()
                                            + " for "
                                            + lockName);
                }
            } catch (Exception e) {
                LOG.log(Level.FINE, "aistio: turn lock renew failed for " + lockName, e);
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            renewFuture.cancel(false);
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("agentName", agentName);
                body.put("namespace", namespace);
                body.put("name", lockName);
                body.put("ownerToken", ownerToken);
                http.send("POST", "/api/v1/dp/locks/release", body);
            } catch (Exception e) {
                LOG.log(Level.FINE, "aistio: turn lock release failed for " + lockName, e);
            }
        }
    }
}
