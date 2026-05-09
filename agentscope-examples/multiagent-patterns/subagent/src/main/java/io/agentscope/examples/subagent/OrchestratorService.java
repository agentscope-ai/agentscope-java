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
package io.agentscope.examples.subagent;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.messages.Message;

/**
 * Service that invokes the orchestrator graph (single node: AgentScopeAgent) and returns the response text.
 */
public class OrchestratorService {

    private final CompiledGraph graph;

    public OrchestratorService(CompiledGraph graph) {
        this.graph = graph;
    }

    public String run(String userInput) throws GraphRunnerException {
        Map<String, Object> inputs = Map.of("input", userInput);
        Optional<OverAllState> resultOpt = graph.invoke(inputs, RunnableConfig.builder().build());
        if (resultOpt.isEmpty()) {
            return "(no response)";
        }
        OverAllState state = resultOpt.get();
        Optional<Object> messagesOpt = state.value("messages");
        if (messagesOpt.isEmpty()) {
            return "";
        }
        @SuppressWarnings("unchecked")
        List<Message> messages = (List<Message>) messagesOpt.get();
        if (messages.isEmpty()) {
            return "";
        }
        Message last = messages.get(messages.size() - 1);
        return last.getText() != null ? last.getText() : "";
    }
}
