/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.extensions.judge.jev.integration;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** Register with Toolkit.registerTool; session identity comes from injected runtime context. */
@SuppressWarnings("removal")
public final class JevKnowledgeTool {
    public record Passage(String id, String text) {}

    private final JevKnowledge knowledge;
    private final RetrieveConfig config;

    public JevKnowledgeTool(JevKnowledge knowledge, RetrieveConfig config) {
        this.knowledge = Objects.requireNonNull(knowledge);
        this.config = Objects.requireNonNull(config);
    }

    @Tool(
            name = "search_knowledge",
            description =
                    "Retrieve authorized evidence passages; an empty result means no supporting"
                            + " evidence was found.")
    public Mono<List<Passage>> search(
            @ToolParam(name = "query", description = "Search question") String query,
            RuntimeContext ctx) {
        return knowledge
                .retrieve(ctx, query, config)
                .map(
                        docs ->
                                docs.stream()
                                        .map(
                                                d ->
                                                        new Passage(
                                                                d.getId(),
                                                                d.getMetadata().getContentText()))
                                        .toList());
    }
}
