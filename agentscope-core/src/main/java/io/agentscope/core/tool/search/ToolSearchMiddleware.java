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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** Opt-in discovery over the current model-call tool surface, without changing tool execution. */
public final class ToolSearchMiddleware implements MiddlewareBase {
    private final ToolSearchTool tool;
    private final Toolkit toolkit;
    private final Set<String> alwaysVisible;
    private final boolean nativeLoading;

    /** Register keyword search with native loading when the selected model supports it. */
    public ToolSearchMiddleware(Toolkit toolkit) {
        this(toolkit, new KeywordToolSearchStrategy(), Set.of(), true);
    }

    /**
     * @param toolkit the shared toolkit; the search tool is registered once during construction
     * @param strategy retrieval over the eligible request-scoped catalog
     * @param alwaysVisible names to expose without discovery, when eligible
     * @param nativeLoading use the model's native protocol, or ordinary function calling if false
     */
    public ToolSearchMiddleware(
            Toolkit toolkit,
            ToolSearchStrategy strategy,
            Set<String> alwaysVisible,
            boolean nativeLoading) {
        this.toolkit = Objects.requireNonNull(toolkit, "toolkit");
        this.alwaysVisible = Set.copyOf(alwaysVisible);
        this.nativeLoading = nativeLoading;
        if (toolkit.getTool(ToolSearchTool.NAME) != null) {
            throw new IllegalArgumentException("A tool named tool_search is already registered");
        }
        this.tool = new ToolSearchTool(strategy);
        toolkit.registerAgentTool(tool);
    }

    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_MODEL_CALL);
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    // External-only requests and tools shadowing tool_search retain their own
                    // surface.
                    if (ctx == null || ctx.getAgentState() == null) {
                        return Flux.error(
                                new IllegalStateException(
                                        "Tool search requires call-scoped agent state"));
                    }
                    List<Msg> messages = refreshSearchResults(input.messages(), input.tools());
                    if (toolkit.getTool(ToolSearchTool.NAME, ctx.getToolRequestConfig()) != tool
                            || input.tools().stream()
                                    .noneMatch(
                                            schema ->
                                                    ToolSearchTool.NAME.equals(schema.getName()))) {
                        ctx.put(ToolSearchTool.SearchContext.class, null);
                        return next.apply(
                                new ModelCallInput(
                                        messages, input.tools(), input.options(), input.model()));
                    }
                    ToolSearchMode mode =
                            nativeLoading
                                    ? input.model().getToolSearchMode()
                                    : ToolSearchMode.FUNCTION;
                    Set<String> visible = new HashSet<>(alwaysVisible);
                    visible.add(ToolSearchTool.NAME);
                    visible.add("generate_response");
                    Set<String> loaded = loadedInHistory(messages, mode);
                    for (String name : ctx.getAgentState().getToolContext().getDiscoveredTools()) {
                        // Restore visibility after history compaction or a model/protocol change.
                        if (mode == ToolSearchMode.FUNCTION || !loaded.contains(name))
                            visible.add(name);
                    }
                    if (input.options() != null
                            && input.options().getToolChoice()
                                    instanceof ToolChoice.Specific specific) {
                        visible.add(specific.toolName());
                    }
                    List<ToolSchema> candidates =
                            input.tools().stream()
                                    .filter(schema -> !ToolSearchTool.NAME.equals(schema.getName()))
                                    .filter(schema -> !"generate_response".equals(schema.getName()))
                                    .toList();
                    ctx.put(
                            ToolSearchTool.SearchContext.class,
                            new ToolSearchTool.SearchContext(candidates, messages, mode));
                    List<ToolSchema> exposed = new ArrayList<>();
                    for (ToolSchema schema : input.tools()) {
                        boolean search = ToolSearchTool.NAME.equals(schema.getName());
                        if (mode == ToolSearchMode.DEFERRED || visible.contains(schema.getName())) {
                            exposed.add(
                                    ToolSchema.builder()
                                            .name(schema.getName())
                                            .description(schema.getDescription())
                                            .parameters(schema.getParameters())
                                            .outputSchema(schema.getOutputSchema())
                                            .strict(schema.getStrict())
                                            .toolSearch(search && mode == ToolSearchMode.CLIENT)
                                            .deferLoading(
                                                    mode == ToolSearchMode.DEFERRED
                                                            && !visible.contains(schema.getName()))
                                            .build());
                        }
                    }
                    return next.apply(
                            new ModelCallInput(
                                    messages,
                                    List.copyOf(exposed),
                                    input.options(),
                                    input.model()));
                });
    }

    /** Revalidate native history against the current catalog without changing stored messages. */
    private static List<Msg> refreshSearchResults(List<Msg> messages, List<ToolSchema> tools) {
        Map<String, ToolSchema> eligible = new LinkedHashMap<>();
        tools.forEach(tool -> eligible.put(tool.getName(), tool));
        List<Msg> refreshed = new ArrayList<>();
        for (Msg message : messages) {
            List<ContentBlock> content = new ArrayList<>();
            for (ContentBlock block : message.getContent()) {
                if (block instanceof ToolResultBlock result
                        && result.getMetadata().get(ToolSearchTool.METADATA_TOOLS)
                                instanceof List<?> matches) {
                    List<Map<String, Object>> current = new ArrayList<>();
                    for (Object match : matches) {
                        if (match instanceof Map<?, ?> schema
                                && schema.get("name") instanceof String name
                                && eligible.containsKey(name))
                            current.add(ToolSearchTool.describe(eligible.get(name)));
                    }
                    if (!current.equals(matches)) {
                        Map<String, Object> metadata = new LinkedHashMap<>(result.getMetadata());
                        metadata.put(ToolSearchTool.METADATA_TOOLS, List.copyOf(current));
                        block =
                                new ToolResultBlock(
                                        result.getId(),
                                        result.getName(),
                                        result.getOutput(),
                                        metadata,
                                        result.getState(),
                                        result.getExecutionDetails());
                    }
                }
                content.add(block);
            }
            refreshed.add(
                    content.equals(message.getContent()) ? message : message.withContent(content));
        }
        return List.copyOf(refreshed);
    }

    private static Set<String> loadedInHistory(List<Msg> messages, ToolSearchMode mode) {
        Set<String> loaded = new HashSet<>();
        for (Msg message : messages) {
            for (ToolResultBlock result : message.getContentBlocks(ToolResultBlock.class)) {
                if (result.getState() != ToolResultState.SUCCESS
                        || !mode.name()
                                .equals(result.getMetadata().get(ToolSearchTool.METADATA_MODE)))
                    continue;
                Object tools = result.getMetadata().get(ToolSearchTool.METADATA_TOOLS);
                if (tools instanceof List<?> list) {
                    for (Object tool : list) {
                        if (tool instanceof Map<?, ?> schema
                                && schema.get("name") instanceof String name) loaded.add(name);
                    }
                }
            }
        }
        return loaded;
    }
}
