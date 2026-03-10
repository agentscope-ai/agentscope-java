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
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Retrieves documents from the AgentScope Knowledge base (embedding + vector store) based on the rewritten query.
 */
public class RetrieveNode implements NodeAction {

    private static final int TOP_K = 5;

    private final Knowledge knowledge;

    public RetrieveNode(Knowledge knowledge) {
        this.knowledge = knowledge;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String query = state.value("rewritten_query").map(Object::toString).orElse("");
        List<Document> docs =
                knowledge.retrieve(query, RetrieveConfig.builder().limit(TOP_K).build()).block();
        List<String> docContents =
                docs != null
                        ? docs.stream()
                                .map(
                                        d ->
                                                d.getMetadata() != null
                                                        ? d.getMetadata().getContentText()
                                                        : "")
                                .filter(s -> !s.isEmpty())
                                .collect(Collectors.toList())
                        : List.of();
        return Map.of("documents", docContents);
    }
}
