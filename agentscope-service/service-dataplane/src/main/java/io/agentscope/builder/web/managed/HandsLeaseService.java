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
package io.agentscope.builder.web.managed;

import io.agentscope.builder.web.managed.service.HandsMetrics;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Brokers hands work-queue signaling for {@code self_hosted} managed sessions.
 *
 * <p>Under the Claude-style event-driven model, Brain does <em>not</em> hold a live sandbox.
 * Built-in hands tools are schema-only and suspend; an Environment Worker claims the work item,
 * executes tools locally, and posts {@code user.tool_result} to resume the turn. This service only
 * enqueues a work item so workers can discover the session (poll/stats/stop), and never blocks
 * waiting for a local {@code Sandbox} registration.
 */
@Service
public class HandsLeaseService {

    private static final Logger log = LoggerFactory.getLogger(HandsLeaseService.class);

    /** Default time to wait for a worker to attach a sandbox before failing the turn. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final EnvironmentWorkQueue workQueue;
    private final ExternalSandboxRegistry registry;
    private final HandsMetrics metrics;
    private final ConcurrentHashMap<String, ActiveHandsLease> activeLeasesBySession =
            new ConcurrentHashMap<>();

    public HandsLeaseService(
            EnvironmentWorkQueue workQueue,
            ExternalSandboxRegistry registry,
            HandsMetrics metrics) {
        this.workQueue = workQueue;
        this.registry = registry;
        this.metrics = metrics;
    }

    /**
     * Signals that a {@code self_hosted} session needs a worker. Enqueues a work item for poll
     * discovery and returns {@link Optional#empty()} — Brain does not use a Priority-1
     * {@code externalSandbox} in the event-driven model. Non-{@code self_hosted} environments
     * return empty without enqueueing.
     */
    public Optional<io.agentscope.harness.agent.sandbox.Sandbox> acquire(
            ManagedSessionDto session, EnvironmentDto environment) {
        return acquire(session, environment, DEFAULT_TIMEOUT, null);
    }

    /** Acquires hands resources owned by one exact physical turn. */
    public Optional<io.agentscope.harness.agent.sandbox.Sandbox> acquire(
            ManagedSessionDto session, EnvironmentDto environment, String turnOwnerId) {
        return acquire(session, environment, DEFAULT_TIMEOUT, turnOwnerId);
    }

    /**
     * Overload of {@link #acquire(ManagedSessionDto, EnvironmentDto)} with an explicit timeout
     * (retained for API compatibility; the timeout is unused because Brain no longer waits for a
     * local sandbox).
     */
    public Optional<io.agentscope.harness.agent.sandbox.Sandbox> acquire(
            ManagedSessionDto session, EnvironmentDto environment, Duration timeout) {
        return acquire(session, environment, timeout, null);
    }

    private Optional<io.agentscope.harness.agent.sandbox.Sandbox> acquire(
            ManagedSessionDto session,
            EnvironmentDto environment,
            Duration timeout,
            String turnOwnerId) {
        if (environment == null || !EnvironmentTypes.TYPE_SELF_HOSTED.equals(environment.type())) {
            return Optional.empty();
        }
        String sessionId = session.id();

        // Fast path: legacy in-process sandbox still registered (dev convenience). Prefer it when
        // present so older paths keep working, but event-driven workers do not require it.
        io.agentscope.harness.agent.sandbox.Sandbox existing = registry.get(sessionId);
        if (existing != null) {
            metrics.recordAcquire(sessionId);
            return Optional.of(existing);
        }

        activeLeasesBySession.computeIfAbsent(
                sessionId,
                ignored -> {
                    EnvironmentWorkQueue.WorkItem item =
                            workQueue.enqueue(sessionId, environment.id(), session.ownerId());
                    log.debug(
                            "[hands] enqueued work item {} for session={}, environment={}"
                                    + " (event-driven; no local sandbox wait)",
                            item.leaseId(),
                            sessionId,
                            environment.id());
                    return new ActiveHandsLease(turnOwnerId, item.leaseId());
                });
        metrics.recordAcquire(sessionId);
        return Optional.empty();
    }

    /**
     * Releases the hands lease for a session: completes the outstanding work item and removes any
     * registry entry. Does not call {@code stop()}/{@code shutdown()} on a sandbox.
     */
    public void release(String sessionId) {
        if (sessionId == null) {
            return;
        }
        ActiveHandsLease lease = activeLeasesBySession.remove(sessionId);
        if (lease != null) {
            workQueue.stop(lease.workLeaseId());
        }
        registry.remove(sessionId);
        metrics.recordRelease(sessionId);
    }

    /** Releases only when the caller still owns this session's hands work item. */
    public void release(String sessionId, String expectedTurnOwnerId) {
        if (sessionId == null) {
            return;
        }
        AtomicBoolean removed = new AtomicBoolean(false);
        activeLeasesBySession.computeIfPresent(
                sessionId,
                (ignored, active) -> {
                    if (!Objects.equals(active.turnOwnerId(), expectedTurnOwnerId)) {
                        return active;
                    }
                    workQueue.stop(active.workLeaseId());
                    removed.set(true);
                    return null;
                });
        if (removed.get()) {
            registry.remove(sessionId);
            metrics.recordRelease(sessionId);
        }
    }

    private record ActiveHandsLease(String turnOwnerId, String workLeaseId) {}
}
