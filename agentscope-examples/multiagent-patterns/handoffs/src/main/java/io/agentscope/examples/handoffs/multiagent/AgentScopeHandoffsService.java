/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.handoffs.multiagent;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.messages.Message;

/**
 * Service that invokes the AgentScope multi-agent handoffs graph (sales + support with handoff tools).
 */
public class AgentScopeHandoffsService {

    private final CompiledGraph graph;

    public AgentScopeHandoffsService(CompiledGraph graph) {
        this.graph = graph;
    }

    /**
     * Run the multi-agent handoffs pipeline with the given user message.
     */
    public AgentScopeHandoffsResult run(String userMessage) throws GraphRunnerException {
        Map<String, Object> inputs = Map.of("input", userMessage);
        Optional<OverAllState> resultOpt = graph.invoke(inputs);

        if (resultOpt.isEmpty()) {
            return new AgentScopeHandoffsResult(userMessage, null, List.of());
        }

        OverAllState state = resultOpt.get();
        @SuppressWarnings("unchecked")
        List<Message> messages = (List<Message>) state.value("messages").orElse(List.of());

        return new AgentScopeHandoffsResult(userMessage, state, messages);
    }

    public record AgentScopeHandoffsResult(
            String query, OverAllState state, List<Message> messages) {}
}
