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
package com.alibaba.cloud.ai.examples.multiagents.workflow.ragagent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * Rewrites the user query for better retrieval. Uses AgentScope Model (ReActAgent with no tools).
 */
public class RewriteNode implements NodeAction {

    private final Model model;
    private final String systemPromptTemplate;

    public RewriteNode(Model model, String systemPromptTemplate) {
        this.model = model;
        this.systemPromptTemplate = systemPromptTemplate;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String question = state.value("question").map(Object::toString).orElse("");
        String prompt = systemPromptTemplate.formatted(question);

        ReActAgent rewriter =
                ReActAgent.builder()
                        .name("rewriter")
                        .sysPrompt("Respond with only the rewritten query, nothing else.")
                        .model(model)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .build();

        Msg userMsg = Msg.builder().role(MsgRole.USER).textContent(prompt).build();
        Msg response = rewriter.call(userMsg).block();
        String rewritten = response != null ? response.getTextContent() : null;
        return Map.of(
                "rewritten_query", StringUtils.hasText(rewritten) ? rewritten.trim() : question);
    }
}
