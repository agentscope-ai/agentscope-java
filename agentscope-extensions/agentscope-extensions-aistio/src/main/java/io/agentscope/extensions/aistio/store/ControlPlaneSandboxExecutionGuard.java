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
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Control-plane hosted {@link SandboxExecutionGuard} over {@code /api/v1/dp/locks/*}.
 *
 * <p>On acquire success the returned lease renews every 20s with a 60s TTL. If the control plane
 * is unreachable for ~30s the guard falls back to a process-local {@link ReentrantLock} and logs a
 * warning.
 */
public final class ControlPlaneSandboxExecutionGuard implements SandboxExecutionGuard {

    private static final Logger LOG =
            Logger.getLogger(ControlPlaneSandboxExecutionGuard.class.getName());

    private static final long RETRY_INTERVAL_MS = 500L;
    private static final long UNREACHABLE_FALLBACK_MS = 30_000L;
    private static final long LEASE_TTL_SECONDS = 60L;
    private static final long RENEW_INTERVAL_SECONDS = 20L;

    private final ControlPlaneHttpClient http;
    private final String agentName;
    private final String namespace;
    private final ConcurrentHashMap<String, ReentrantLock> localLocks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService renewScheduler =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "aistio-lock-renew");
                        t.setDaemon(true);
                        return t;
                    });

    ControlPlaneSandboxExecutionGuard(
            ControlPlaneHttpClient http, String agentName, String namespace) {
        this.http = Objects.requireNonNull(http, "http");
        this.agentName = Objects.requireNonNull(agentName, "agentName");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    @Override
    public SandboxLease tryEnter(SandboxIsolationKey key) throws InterruptedException {
        String lockName = composeLockName(key);
        String holder = UUID.randomUUID().toString();
        long unreachableDeadline = -1L;

        while (true) {
            if (Thread.interrupted()) {
                throw new InterruptedException(
                        "Interrupted while waiting for sandbox execution guard on " + lockName);
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
                    Thread.sleep(RETRY_INTERVAL_MS);
                    continue;
                }
                // Unexpected status — treat like unreachable for fallback timing.
                if (unreachableDeadline < 0) {
                    unreachableDeadline =
                            System.nanoTime()
                                    + TimeUnit.MILLISECONDS.toNanos(UNREACHABLE_FALLBACK_MS);
                }
                if (System.nanoTime() >= unreachableDeadline) {
                    return fallbackLocal(lockName, "HTTP " + resp.status());
                }
                Thread.sleep(RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                throw e;
            } catch (IOException e) {
                if (unreachableDeadline < 0) {
                    unreachableDeadline =
                            System.nanoTime()
                                    + TimeUnit.MILLISECONDS.toNanos(UNREACHABLE_FALLBACK_MS);
                }
                if (System.nanoTime() >= unreachableDeadline) {
                    return fallbackLocal(lockName, e.getMessage());
                }
                Thread.sleep(RETRY_INTERVAL_MS);
            }
        }
    }

    private SandboxLease fallbackLocal(String lockName, String reason) throws InterruptedException {
        LOG.warning(
                () ->
                        "aistio: control plane unreachable for locks ("
                                + reason
                                + "); falling back to process-local lock for "
                                + lockName);
        ReentrantLock lock = localLocks.computeIfAbsent(lockName, k -> new ReentrantLock());
        lock.lockInterruptibly();
        return () -> {
            try {
                lock.unlock();
            } catch (Exception e) {
                LOG.log(Level.FINE, "aistio: local lock unlock failed for " + lockName, e);
            }
        };
    }

    private static String composeLockName(SandboxIsolationKey key) {
        return key.getScope().name().toLowerCase() + ":" + key.getValue();
    }

    private final class RenewingLease implements SandboxLease {

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
                                    "aistio: lock renew conflict for "
                                            + lockName
                                            + " — lease may have been stolen");
                } else if (resp.status() < 200 || resp.status() >= 300) {
                    LOG.log(
                            Level.FINE,
                            () -> "aistio: lock renew HTTP " + resp.status() + " for " + lockName);
                }
            } catch (Exception e) {
                LOG.log(Level.FINE, "aistio: lock renew failed for " + lockName, e);
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
                LOG.log(Level.FINE, "aistio: lock release failed for " + lockName, e);
            }
        }
    }
}
