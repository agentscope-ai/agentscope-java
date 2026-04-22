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
import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;

/**
 * Abstract Implementation for {@link AgentRunner} by {@link ReActAgent}.
 *
 * <p>Use {@link ReActAgent} directly to handler request from A2A client. In this implementation, {@link ReActAgent}
 * should be created for each request and be cached to intercept when the request is stopped.
 */
public abstract class BaseReActAgentRunner implements AgentRunner {

    private final Map<String, ReActAgent> agentCache;

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
    public Flux<Event> stream(List<Msg> requestMessages, AgentRequestOptions options) {
        if (agentCache.containsKey(options.getTaskId())) {
            throw new IllegalStateException(
                    "Agent already exists for taskId: " + options.getTaskId());
        }
        ReActAgent agent = buildReActAgent();
        agentCache.put(options.getTaskId(), agent);
        return agent.stream(requestMessages)
                .doFinally(signal -> agentCache.remove(options.getTaskId()));
    }

    @Override
    public void stop(String taskId) {
        ReActAgent agent = agentCache.remove(taskId);
        if (null != agent) {
            agent.interrupt();
        }
    }

    /**
     * Build {@link ReActAgent} to run new request.
     *
     * @return {@link ReActAgent} instance
     */
    protected abstract ReActAgent buildReActAgent();
}
