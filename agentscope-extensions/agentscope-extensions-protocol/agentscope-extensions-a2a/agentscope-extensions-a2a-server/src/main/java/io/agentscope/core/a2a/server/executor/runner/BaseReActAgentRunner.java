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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import reactor.core.publisher.Flux;

/**
 * Abstract Implementation for {@link AgentRunner} by {@link ReActAgent}.
 *
 * <p>Use {@link ReActAgent} directly to handler request from A2A client. In this implementation, {@link ReActAgent}
 * should be created for each request and be cached to intercept when the request is stopped.
 */
public abstract class BaseReActAgentRunner implements AgentRunner {

    private final ConcurrentMap<String, RunningTask> agentCache;

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
        if (agentCache.containsKey(taskId)) {
            throw new IllegalStateException("Agent already exists for taskId: " + taskId);
        }
        ReActAgent agent = buildReActAgent();
        if (agent.getStateStore() == null && carriesConfirmResults(requestMessages)) {
            // Without a state store the agent cannot restore the paused run and would handle
            // the confirmation as the start of a new conversation.
            return Flux.error(
                    new IllegalStateException(
                            "The request confirms tool calls of a paused run, but the agent has"
                                    + " no AgentStateStore to restore that run from. Configure"
                                    + " stateStore(...) on the agent."));
        }
        RunningTask task = new RunningTask(agent, buildRuntimeContext(options));
        if (agentCache.putIfAbsent(taskId, task) != null) {
            throw new IllegalStateException("Agent already exists for taskId: " + taskId);
        }
        try {
            return agent.streamEvents(requestMessages, task.context())
                    .doFinally(signal -> agentCache.remove(taskId, task));
        } catch (RuntimeException | Error e) {
            agentCache.remove(taskId, task);
            throw e;
        }
    }

    private static boolean carriesConfirmResults(List<Msg> messages) {
        return messages != null
                && messages.stream()
                        .anyMatch(
                                msg ->
                                        msg.getMetadata() != null
                                                && msg.getMetadata()
                                                                .get(Msg.METADATA_CONFIRM_RESULTS)
                                                        instanceof List<?> results
                                                && !results.isEmpty());
    }

    /**
     * Builds the per-call {@link RuntimeContext} from the request options. Blank ids mean "not
     * found" and are left unset, so an anonymous call keeps the null session fields the agent sees
     * without a context.
     */
    private static RuntimeContext buildRuntimeContext(AgentRequestOptions options) {
        RuntimeContext.Builder builder = RuntimeContext.builder();
        if (null != options.getUserId() && !options.getUserId().isBlank()) {
            builder.userId(options.getUserId());
        }
        if (null != options.getSessionId() && !options.getSessionId().isBlank()) {
            builder.sessionId(options.getSessionId());
        }
        return builder.build();
    }

    @Override
    public void stop(String taskId) {
        RunningTask task = agentCache.remove(taskId);
        if (null != task) {
            // Interrupt the session the task runs in; the no-arg interrupt() targets the default
            // session, which may belong to another caller of a shared agent.
            task.agent().interrupt(task.context());
        }
    }

    /** The agent running a task and the context it was started with. */
    private record RunningTask(ReActAgent agent, RuntimeContext context) {}

    /**
     * Build {@link ReActAgent} to run new request.
     *
     * @return {@link ReActAgent} instance
     */
    protected abstract ReActAgent buildReActAgent();
}
