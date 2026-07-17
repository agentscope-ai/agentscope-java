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
import io.agentscope.core.a2a.server.hitl.HitlDurabilityCapability;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;

/**
 * Abstract Implementation for {@link AgentRunner} by {@link ReActAgent}.
 *
 * <p>Use {@link ReActAgent} directly to handler request from A2A client. In this implementation, {@link ReActAgent}
 * should be created for each request and be cached to intercept when the request is stopped.
 */
public abstract class BaseReActAgentRunner implements AgentRunner {

    private final Map<String, RunningAgent> agentCache;

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
        if (agentCache.containsKey(options.getTaskId())) {
            throw new IllegalStateException(
                    "Agent already exists for taskId: " + options.getTaskId());
        }
        ReActAgent agent = buildReActAgent();
        RuntimeContext runtimeContext = buildRuntimeContext(options);
        agentCache.put(options.getTaskId(), new RunningAgent(agent, runtimeContext));
        return agent.streamEvents(requestMessages, runtimeContext)
                .doFinally(signal -> agentCache.remove(options.getTaskId()));
    }

    @Override
    public void stop(String taskId) {
        RunningAgent runningAgent = agentCache.remove(taskId);
        if (null != runningAgent) {
            runningAgent.agent().interrupt(runningAgent.runtimeContext());
        }
    }

    @Override
    public HitlDurabilityCapability hitlDurabilityCapability() {
        AgentStateStore stateStore = actualAgentStateStore().orElse(null);
        return stateStore == null
                        || stateStore instanceof InMemoryAgentStateStore
                        || stateStore instanceof JsonFileAgentStateStore
                ? HitlDurabilityCapability.LOCAL
                : HitlDurabilityCapability.DURABLE;
    }

    @Override
    public Optional<AgentStateStore> actualAgentStateStore() {
        return Optional.ofNullable(buildReActAgent().getStateStore());
    }

    private RuntimeContext buildRuntimeContext(AgentRequestOptions options) {
        RuntimeContext.Builder builder = RuntimeContext.builder();
        String sessionId = trimToNull(options.getSessionId());
        if (sessionId != null) {
            builder.sessionId(sessionId);
        }
        String userId = trimToNull(options.getUserId());
        if (userId != null) {
            builder.userId(userId);
        }
        return builder.build();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Build {@link ReActAgent} to run new request.
     *
     * @return {@link ReActAgent} instance
     */
    protected abstract ReActAgent buildReActAgent();

    private record RunningAgent(ReActAgent agent, RuntimeContext runtimeContext) {}
}
