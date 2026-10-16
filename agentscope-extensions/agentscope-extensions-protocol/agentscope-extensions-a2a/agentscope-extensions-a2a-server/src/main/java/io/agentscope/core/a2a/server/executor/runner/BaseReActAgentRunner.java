/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.a2a.server.executor.runner;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

/**
 * Abstract Implementation for {@link AgentRunner} by {@link ReActAgent}.
 *
 * <p>Use {@link ReActAgent} directly to handle requests from A2A clients. An agent is created per task and retained
 * while the task is paused for user confirmation; it is removed when execution completes, the task is stopped, or the
 * configured pause retention expires.
 */
public abstract class BaseReActAgentRunner implements AgentRunner {

    private static final Duration DEFAULT_PAUSED_AGENT_RETENTION = Duration.ofMinutes(30);

    private final Map<String, CachedAgent> agentCache;

    private final Duration pausedAgentRetention;

    protected BaseReActAgentRunner() {
        this(DEFAULT_PAUSED_AGENT_RETENTION);
    }

    protected BaseReActAgentRunner(Duration pausedAgentRetention) {
        this.pausedAgentRetention =
                Objects.requireNonNull(pausedAgentRetention, "pausedAgentRetention");
        if (pausedAgentRetention.isNegative() || pausedAgentRetention.toMillis() == 0) {
            throw new IllegalArgumentException(
                    "pausedAgentRetention must be at least one millisecond.");
        }
        this.agentCache = new ConcurrentHashMap<>();
    }

    @Override
    public String getAgentName() {
        return buildReActAgent().getName();
    }

    @Override
    public String getAgentDescription() {
        return buildReActAgent().getDescription();
    }

    @Override
    public Flux<AgentEvent> streamEvents(List<Msg> requestMessages, AgentRequestOptions options) {
        String taskId = options.getTaskId();
        return Flux.defer(
                () -> {
                    CachedAgent cachedAgent =
                            agentCache.compute(
                                    taskId,
                                    (id, existing) -> {
                                        if (existing == null) {
                                            if (options.isResume()) {
                                                throw new IllegalStateException(
                                                        "No paused agent is available for taskId: "
                                                                + taskId);
                                            }
                                            return new CachedAgent(buildReActAgent());
                                        }
                                        if (!existing.paused) {
                                            throw new IllegalStateException(
                                                    "Agent already exists for taskId: " + taskId);
                                        }
                                        existing.cancelExpiry();
                                        // A new registration owns each execution, so a late expiry
                                        // or terminal signal cannot remove a subsequent resume.
                                        return new CachedAgent(existing.agent);
                                    });
                    AtomicReference<RequireUserConfirmEvent> confirmationRequest =
                            new AtomicReference<>();
                    try {
                        return cachedAgent
                                .agent
                                .streamEvents(requestMessages)
                                .concatMap(
                                        event -> {
                                            if (event instanceof RequireUserConfirmEvent request) {
                                                if (!confirmationRequest.compareAndSet(
                                                        null, request)) {
                                                    return Mono.error(
                                                            new IllegalStateException(
                                                                    "An agent stream requested"
                                                                            + " user confirmation"
                                                                            + " more than once."));
                                                }
                                                return Mono.empty();
                                            }
                                            return Mono.just(event);
                                        })
                                // Let the agent finish pausing before exposing the request to the
                                // client.
                                .concatWith(
                                        Flux.defer(
                                                () -> {
                                                    RequireUserConfirmEvent request =
                                                            confirmationRequest.get();
                                                    if (request == null) {
                                                        return Flux.empty();
                                                    }
                                                    AtomicBoolean retained = new AtomicBoolean();
                                                    agentCache.computeIfPresent(
                                                            taskId,
                                                            (id, current) -> {
                                                                if (current == cachedAgent) {
                                                                    current.paused = true;
                                                                    current.scheduleExpiry(
                                                                            Schedulers.parallel()
                                                                                    .schedule(
                                                                                            () ->
                                                                                                    expirePausedAgent(
                                                                                                            taskId,
                                                                                                            cachedAgent),
                                                                                            pausedAgentRetention
                                                                                                    .toMillis(),
                                                                                            TimeUnit
                                                                                                    .MILLISECONDS));
                                                                    retained.set(true);
                                                                }
                                                                return current;
                                                            });
                                                    if (!retained.get()) {
                                                        return Flux.error(
                                                                new IllegalStateException(
                                                                        "Agent was stopped before"
                                                                            + " confirmation could"
                                                                            + " be requested."));
                                                    }
                                                    return Flux.just(request);
                                                }))
                                .doFinally(
                                        signal -> {
                                            if (signal != SignalType.ON_COMPLETE
                                                    || confirmationRequest.get() == null) {
                                                removeAgent(taskId, cachedAgent);
                                            }
                                        });
                    } catch (RuntimeException | Error error) {
                        removeAgent(taskId, cachedAgent);
                        return Flux.error(error);
                    }
                });
    }

    @Override
    public void stop(String taskId) {
        CachedAgent cachedAgent = agentCache.remove(taskId);
        if (cachedAgent != null) {
            cachedAgent.cancelExpiry();
            try {
                cachedAgent.agent.interrupt();
            } finally {
                cachedAgent.agent.close();
            }
        }
    }

    private void expirePausedAgent(String taskId, CachedAgent cachedAgent) {
        AtomicBoolean expired = new AtomicBoolean();
        agentCache.computeIfPresent(
                taskId,
                (id, current) -> {
                    if (current == cachedAgent && current.paused) {
                        current.expiry = null;
                        expired.set(true);
                        return null;
                    }
                    return current;
                });
        if (expired.get()) {
            try {
                cachedAgent.agent.interrupt();
            } finally {
                cachedAgent.agent.close();
            }
        }
    }

    private void removeAgent(String taskId, CachedAgent cachedAgent) {
        if (agentCache.remove(taskId, cachedAgent)) {
            cachedAgent.cancelExpiry();
            cachedAgent.agent.close();
        }
    }

    /**
     * Build {@link ReActAgent} to run new request.
     *
     * @return {@link ReActAgent} instance
     */
    protected abstract ReActAgent buildReActAgent();

    private static final class CachedAgent {

        private final ReActAgent agent;

        private volatile boolean paused;

        private volatile Disposable expiry;

        private CachedAgent(ReActAgent agent) {
            this.agent = agent;
        }

        private void scheduleExpiry(Disposable expiry) {
            this.expiry = expiry;
        }

        private void cancelExpiry() {
            Disposable currentExpiry = expiry;
            if (currentExpiry != null) {
                currentExpiry.dispose();
                expiry = null;
            }
        }
    }
}
