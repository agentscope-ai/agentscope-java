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
package io.agentscope.core.tool.search;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** Read-only discovery tool. Register through {@link ToolSearchMiddleware}. */
public final class ToolSearchTool extends ToolBase {
    public static final String NAME = "tool_search";
    public static final String METADATA_MODE = "agentscope.tool_search.mode";
    public static final String METADATA_TOOLS = "agentscope.tool_search.tools";
    public static final int DEFAULT_LIMIT = 5;
    private final ToolSearchStrategy strategy;

    public ToolSearchTool(ToolSearchStrategy strategy) {
        super(
                ToolBase.builder()
                        .name(NAME)
                        .description(
                                "Search for tools by capability. Discover tools before calling"
                                        + " them.")
                        .inputSchema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "query",
                                                        Map.of(
                                                                "type",
                                                                "string",
                                                                "description",
                                                                "The capability to find"),
                                                "limit",
                                                        Map.of(
                                                                "type",
                                                                "integer",
                                                                "minimum",
                                                                1,
                                                                "description",
                                                                "Maximum results (default 5)")),
                                        "required",
                                        List.of("query"),
                                        "additionalProperties",
                                        false))
                        .readOnly(true)
                        .concurrencySafe(true));
        this.strategy = Objects.requireNonNull(strategy, "strategy");
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.defer(
                () -> {
                    RuntimeContext ctx = param.getRuntimeContext();
                    SearchContext search = ctx == null ? null : ctx.get(SearchContext.class);
                    AgentState state = ctx == null ? null : ctx.getAgentState();
                    if (search == null || state == null) {
                        return Mono.just(
                                ToolResultBlock.error(
                                        "Tool search requires ToolSearchMiddleware and call-scoped"
                                                + " agent state"));
                    }
                    Object query = param.getInput().get("query");
                    Object rawLimit = param.getInput().getOrDefault("limit", DEFAULT_LIMIT);
                    if (!(query instanceof String text) || text.isBlank()) {
                        return Mono.just(ToolResultBlock.error("query must be a non-blank string"));
                    }
                    if (!(rawLimit instanceof Number number)
                            || !Double.isFinite(number.doubleValue())
                            || number.doubleValue() < 1
                            || number.doubleValue() > Integer.MAX_VALUE
                            || number.doubleValue() != number.intValue()) {
                        return Mono.just(ToolResultBlock.error("limit must be a positive integer"));
                    }
                    int limit = number.intValue();
                    return strategy.search(
                                    new ToolSearchRequest(
                                            search.candidates(), search.messages(), text, limit))
                            .switchIfEmpty(
                                    Mono.error(
                                            new IllegalStateException(
                                                    "Search strategy returned no result")))
                            .map(
                                    result -> {
                                        Map<String, ToolSchema> candidates = new LinkedHashMap<>();
                                        search.candidates()
                                                .forEach(
                                                        tool ->
                                                                candidates.put(
                                                                        tool.getName(), tool));
                                        var names = new LinkedHashSet<String>();
                                        for (String name : result.toolNames()) {
                                            if (!candidates.containsKey(name)) {
                                                throw new IllegalArgumentException(
                                                        "Search returned an ineligible tool: "
                                                                + name);
                                            }
                                            if (names.size() < limit) names.add(name);
                                        }
                                        var tools = new ArrayList<Map<String, Object>>();
                                        for (String name : names)
                                            tools.add(describe(candidates.get(name)));
                                        Map<String, Object> metadata =
                                                new LinkedHashMap<>(result.metadata());
                                        metadata.put(METADATA_MODE, search.mode().name());
                                        metadata.put(METADATA_TOOLS, List.copyOf(tools));
                                        String output =
                                                result.resultText() != null
                                                        ? result.resultText()
                                                        : names.isEmpty()
                                                                ? "No matching tools found."
                                                                : "Discovered tools: "
                                                                        + String.join(", ", names);
                                        ToolResultBlock response =
                                                ToolResultBlock.builder()
                                                        .output(
                                                                TextBlock.builder()
                                                                        .text(output)
                                                                        .build())
                                                        .metadata(metadata)
                                                        .state(ToolResultState.SUCCESS)
                                                        .build();
                                        state.getToolContext()
                                                .addDiscoveredTools(List.copyOf(names));
                                        return response;
                                    });
                });
    }

    static Map<String, Object> describe(ToolSchema schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", schema.getName());
        result.put("description", schema.getDescription());
        result.put("parameters", schema.getParameters());
        if (schema.getStrict() != null) result.put("strict", schema.getStrict());
        if (schema.getOutputSchema() != null) result.put("output_schema", schema.getOutputSchema());
        return result;
    }

    record SearchContext(List<ToolSchema> candidates, List<Msg> messages, ToolSearchMode mode) {}
}
