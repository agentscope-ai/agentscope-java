/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
 * Default Implementation for {@link AgentRunner} by {@link ReActAgent}.
 *
 * <p>Use {@link ReActAgent} directly to handler request from A2A client. In this implementation, {@link ReActAgent}
 * will be created for each request and be cached to intercept when the request is stopped.
 *
 * <p> {@link ReActAgent} should be created from {@link ReActAgent.Builder}, which input and configured by developers.
 */
public class ReActAgentRunner implements AgentRunner {

    private final ReActAgent.Builder agentBuilder;

    private final Map<String, ReActAgent> agentCache;

    private ReActAgentRunner(ReActAgent.Builder agentBuilder) {
        this.agentBuilder = agentBuilder;
        this.agentCache = new ConcurrentHashMap<>();
    }

    @Override
    public String getAgentName() {
        return agentBuilder.build().getName();
    }

    @Override
    public String getAgentDescription() {
        return agentBuilder.build().getDescription();
    }

    @Override
    public Flux<Event> stream(List<Msg> requestMessages, AgentRequestOptions options) {
        if (agentCache.containsKey(options.getTaskId())) {
            throw new IllegalStateException(
                    "Agent already exists for taskId: " + options.getTaskId());
        }
        ReActAgent agent = agentBuilder.build();
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
     * Build new {@link ReActAgentRunner} instance from {@link ReActAgent.Builder}.
     *
     * @param agentBuilder builder of {@link ReActAgent}
     * @return new {@link ReActAgentRunner} instance
     */
    public static ReActAgentRunner newInstance(ReActAgent.Builder agentBuilder) {
        return new ReActAgentRunner(agentBuilder);
    }
}
