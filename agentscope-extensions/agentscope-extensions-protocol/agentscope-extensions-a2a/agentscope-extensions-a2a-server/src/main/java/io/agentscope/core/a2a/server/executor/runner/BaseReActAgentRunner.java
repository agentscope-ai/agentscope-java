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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * Abstract Implementation for {@link AgentRunner} by {@link ReActAgent}.
 *
 * <p>Use {@link ReActAgent} directly to handle requests from A2A clients. An agent is created per task and retained
 * while the task is paused for user confirmation; it is removed when execution completes or the task is stopped.
 */
public abstract class BaseReActAgentRunner implements AgentRunner {

    private final Map<String, CachedAgent> agentCache;

    protected BaseReActAgentRunner() {
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
                                            return new CachedAgent(buildReActAgent());
                                        }
                                        if (!existing.paused) {
                                            throw new IllegalStateException(
                                                    "Agent already exists for taskId: " + taskId);
                                        }
                                        existing.paused = false;
                                        return existing;
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
                                                agentCache.remove(taskId, cachedAgent);
                                            }
                                        });
                    } catch (RuntimeException | Error error) {
                        agentCache.remove(taskId, cachedAgent);
                        return Flux.error(error);
                    }
                });
    }

    @Override
    public void stop(String taskId) {
        CachedAgent cachedAgent = agentCache.remove(taskId);
        if (cachedAgent != null) {
            cachedAgent.agent.interrupt();
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

        private boolean paused;

        private CachedAgent(ReActAgent agent) {
            this.agent = agent;
        }
    }
}
