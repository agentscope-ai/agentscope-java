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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ToolContextState;
import io.agentscope.core.tool.SchemaOnlyTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolMergeMode;
import io.agentscope.core.tool.ToolRequestConfig;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ToolSearchTest {
    private static ToolSchema schema(String name, String description) {
        return ToolSchema.builder()
                .name(name)
                .description(description)
                .parameters(Map.of("type", "object", "properties", Map.of()))
                .build();
    }

    private static RuntimeContext context() {
        return RuntimeContext.builder()
                .agentState(AgentState.builder().sessionId(UUID.randomUUID().toString()).build())
                .build();
    }

    private static List<ToolSchema> expose(
            ToolSearchMiddleware middleware,
            Toolkit toolkit,
            RuntimeContext ctx,
            ToolSearchMode mode,
            GenerateOptions options) {
        Model model = mock(Model.class);
        when(model.getToolSearchMode()).thenReturn(mode);
        List<ToolSchema> exposed = new ArrayList<>();
        middleware
                .onModelCall(
                        null,
                        ctx,
                        new ModelCallInput(
                                ctx.getAgentState().getContext(),
                                toolkit.getToolSchemas(List.of(), ctx.getToolRequestConfig()),
                                options,
                                model),
                        input -> {
                            exposed.addAll(input.tools());
                            return Flux.<AgentEvent>empty();
                        })
                .blockLast();
        return exposed;
    }

    private static ToolResultBlock search(
            Toolkit toolkit, RuntimeContext ctx, Map<String, Object> input) {
        return toolkit.getTool(ToolSearchTool.NAME)
                .callAsync(ToolCallParam.builder().runtimeContext(ctx).input(input).build())
                .block();
    }

    @Test
    void keywordRankingLimitsAndEmptyResults() {
        var strategy = new KeywordToolSearchStrategy();
        var candidates =
                List.of(
                        schema("get_order", "Lookup purchases"),
                        schema("other", "Get order details"),
                        schema("calendar", "Dates"));
        StepVerifier.create(
                        strategy.search(new ToolSearchRequest(candidates, List.of(), "ORDER", 1)))
                .assertNext(result -> assertEquals(List.of("get_order"), result.toolNames()))
                .verifyComplete();
        assertTrue(
                strategy.search(new ToolSearchRequest(candidates, List.of(), "weather", 5))
                        .block()
                        .toolNames()
                        .isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolSearchRequest(candidates, List.of(), "", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolSearchRequest(candidates, List.of(), "order", 0));
    }

    @Test
    void discoveriesAccumulateDeduplicatePersistAndStaySessionScoped() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(schema("get_order", "orders"));
        toolkit.registerSchema(schema("get_weather", "weather"));
        var middleware = new ToolSearchMiddleware(toolkit);
        RuntimeContext first = context(), second = context();
        assertEquals(1, expose(middleware, toolkit, first, ToolSearchMode.FUNCTION, null).size());
        assertEquals(
                ToolResultState.SUCCESS,
                search(toolkit, first, Map.of("query", "order")).getState());
        search(toolkit, first, Map.of("query", "order"));
        search(toolkit, first, Map.of("query", "weather"));
        assertEquals(
                Set.of("get_order", "get_weather", "tool_search"),
                new HashSet<>(
                        expose(middleware, toolkit, first, ToolSearchMode.FUNCTION, null).stream()
                                .map(ToolSchema::getName)
                                .toList()));
        assertEquals(
                List.of("get_order", "get_weather"),
                first.getAgentState().getToolContext().getDiscoveredTools());
        assertEquals(1, expose(middleware, toolkit, second, ToolSearchMode.FUNCTION, null).size());
        String json = JsonUtils.getJsonCodec().toJson(first.getAgentState().getToolContext());
        assertEquals(
                List.of("get_order", "get_weather"),
                JsonUtils.getJsonCodec()
                        .fromJson(json, ToolContextState.class)
                        .getDiscoveredTools());
        assertTrue(
                JsonUtils.getJsonCodec()
                        .fromJson("{}", ToolContextState.class)
                        .getDiscoveredTools()
                        .isEmpty());
        toolkit.removeTool("get_order");
        assertFalse(
                expose(middleware, toolkit, first, ToolSearchMode.FUNCTION, null).stream()
                        .anyMatch(tool -> tool.getName().equals("get_order")));
    }

    @Test
    void clearDiscoveredToolsResetsSessionDiscoveries() {
        ToolContextState state = ToolContextState.builder().build();
        state.addDiscoveredTools(List.of("one", "one", "two"));

        assertEquals(List.of("one", "two"), state.getDiscoveredTools());
        state.clearDiscoveredTools();
        assertTrue(state.getDiscoveredTools().isEmpty());
    }

    @Test
    void nativeModesKeepStableDeclarationsAndGenericFallbackIsAvailable() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(schema("get_order", "orders"));
        var middleware = new ToolSearchMiddleware(toolkit);
        RuntimeContext ctx = context();
        var deferred = expose(middleware, toolkit, ctx, ToolSearchMode.DEFERRED, null);
        assertTrue(
                deferred.stream()
                        .filter(tool -> tool.getName().equals("get_order"))
                        .findFirst()
                        .orElseThrow()
                        .getDeferLoading());
        var result = search(toolkit, ctx, Map.of("query", "order"));
        ctx.getAgentState()
                .contextMutable()
                .add(
                        ToolResultMessage.builder()
                                .content(result.withIdAndName("search", "tool_search"))
                                .build());
        assertTrue(
                expose(middleware, toolkit, ctx, ToolSearchMode.DEFERRED, null).stream()
                        .filter(tool -> tool.getName().equals("get_order"))
                        .findFirst()
                        .orElseThrow()
                        .getDeferLoading());
        var client = expose(middleware, toolkit, ctx, ToolSearchMode.CLIENT, null);
        // Switching native protocols must retain tools found under the previous protocol.
        assertEquals(2, client.size());
        assertTrue(client.stream().anyMatch(ToolSchema::isToolSearch));
        var clientResult = search(toolkit, ctx, Map.of("query", "order"));
        ctx.getAgentState()
                .contextMutable()
                .add(
                        ToolResultMessage.builder()
                                .content(clientResult.withIdAndName("client-search", "tool_search"))
                                .build());
        assertEquals(1, expose(middleware, toolkit, ctx, ToolSearchMode.CLIENT, null).size());
        // If compaction drops the native result, persisted discovery names restore visibility.
        ctx.getAgentState().contextMutable().clear();
        assertEquals(2, expose(middleware, toolkit, ctx, ToolSearchMode.CLIENT, null).size());
        var forced =
                expose(
                        middleware,
                        toolkit,
                        ctx,
                        ToolSearchMode.FUNCTION,
                        GenerateOptions.builder()
                                .toolChoice(new ToolChoice.Specific("get_order"))
                                .build());
        assertEquals(2, forced.size());
    }

    @Test
    void invalidInputAndIneligibleStrategyResultsNeverChangeDiscoveryState() {
        Toolkit toolkit = new Toolkit();
        var middleware =
                new ToolSearchMiddleware(
                        toolkit,
                        request -> Mono.just(new ToolSearchResult(List.of("hidden"), "", Map.of())),
                        Set.of(),
                        true);
        RuntimeContext ctx = context();
        expose(middleware, toolkit, ctx, ToolSearchMode.FUNCTION, null);
        assertEquals(ToolResultState.ERROR, search(toolkit, ctx, Map.of("query", " ")).getState());
        assertEquals(
                ToolResultState.ERROR,
                search(toolkit, ctx, Map.of("query", "test", "limit", 1.5)).getState());
        StepVerifier.create(
                        toolkit.getTool(ToolSearchTool.NAME)
                                .callAsync(
                                        ToolCallParam.builder()
                                                .runtimeContext(ctx)
                                                .input(Map.of("query", "test"))
                                                .build()))
                .expectErrorMatches(
                        error ->
                                error instanceof IllegalArgumentException
                                        && error.getMessage().contains("ineligible"))
                .verify();
        assertTrue(ctx.getAgentState().getToolContext().getDiscoveredTools().isEmpty());
    }

    @Test
    void externalOnlyRequestsDoNotExposeBackendTools() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(schema("get_order", "orders"));
        var middleware = new ToolSearchMiddleware(toolkit);
        RuntimeContext ctx = context();
        ctx.setToolRequestConfig(
                new ToolRequestConfig(
                        Map.of("external", new SchemaOnlyTool(schema("external", "client"))),
                        ToolMergeMode.EXTERNAL_ONLY));
        assertEquals(
                List.of("external"),
                expose(middleware, toolkit, ctx, ToolSearchMode.FUNCTION, null).stream()
                        .map(ToolSchema::getName)
                        .toList());
    }

    @Test
    void inactiveRequestsWithoutSearchHistoryAreNotRefreshed() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(schema("get_order", "orders"));
        var middleware = new ToolSearchMiddleware(toolkit);
        RuntimeContext ctx = context();
        ToolResultBlock result = ToolResultBlock.success("Found order");
        Msg history = ToolResultMessage.builder().content(result).build();
        List<Msg> inputMessages = List.of(history);
        ctx.getAgentState().contextMutable().add(history);
        ctx.setToolRequestConfig(
                new ToolRequestConfig(
                        Map.of("external", new SchemaOnlyTool(schema("external", "client"))),
                        ToolMergeMode.EXTERNAL_ONLY));
        Model model = mock(Model.class);
        List<List<Msg>> actual = new ArrayList<>();
        middleware
                .onModelCall(
                        null,
                        ctx,
                        new ModelCallInput(
                                inputMessages,
                                toolkit.getToolSchemas(List.of(), ctx.getToolRequestConfig()),
                                null,
                                model),
                        input -> {
                            actual.add(input.messages());
                            return Flux.empty();
                        })
                .blockLast();
        assertEquals(1, actual.size());
        assertSame(inputMessages, actual.get(0));
    }

    @Test
    void inactiveGroupsAreNotSearchCandidates() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(schema("get_order", "orders"));
        toolkit.createToolGroup("private", "private orders", false);
        toolkit.addToolToGroup("private", "get_order");
        var middleware = new ToolSearchMiddleware(toolkit);
        RuntimeContext ctx = context();
        expose(middleware, toolkit, ctx, ToolSearchMode.FUNCTION, null);
        search(toolkit, ctx, Map.of("query", "order"));
        assertTrue(ctx.getAgentState().getToolContext().getDiscoveredTools().isEmpty());
    }

    @Test
    void customResultsAreDeduplicatedAndLimitedAndCancellationDoesNotDiscover() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(schema("one", "first"));
        toolkit.registerSchema(schema("two", "second"));
        var middleware =
                new ToolSearchMiddleware(
                        toolkit,
                        request ->
                                Mono.just(
                                        new ToolSearchResult(
                                                List.of("one", "one", "two"), "matched", Map.of())),
                        Set.of(),
                        false);
        RuntimeContext ctx = context();
        expose(middleware, toolkit, ctx, ToolSearchMode.CLIENT, null);
        assertEquals(
                "matched",
                ((TextBlock)
                                search(toolkit, ctx, Map.of("query", "test", "limit", 1))
                                        .getOutput()
                                        .get(0))
                        .getText());
        assertEquals(List.of("one"), ctx.getAgentState().getToolContext().getDiscoveredTools());
        Toolkit pendingToolkit = new Toolkit();
        var pending =
                new ToolSearchMiddleware(pendingToolkit, request -> Mono.never(), Set.of(), true);
        RuntimeContext pendingContext = context();
        expose(pending, pendingToolkit, pendingContext, ToolSearchMode.FUNCTION, null);
        StepVerifier.create(
                        pendingToolkit
                                .getTool("tool_search")
                                .callAsync(
                                        ToolCallParam.builder()
                                                .runtimeContext(pendingContext)
                                                .input(Map.of("query", "test"))
                                                .build()))
                .thenCancel()
                .verify();
        assertTrue(pendingContext.getAgentState().getToolContext().getDiscoveredTools().isEmpty());
    }

    @Test
    void nativeHistoryDropsRemovedToolsAndRefreshesOverriddenSchemas() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(schema("get_order", "old description"));
        var middleware = new ToolSearchMiddleware(toolkit);
        RuntimeContext ctx = context();
        expose(middleware, toolkit, ctx, ToolSearchMode.DEFERRED, null);
        var result = search(toolkit, ctx, Map.of("query", "order"));
        ctx.getAgentState()
                .contextMutable()
                .add(
                        ToolResultMessage.builder()
                                .content(result.withIdAndName("search", "tool_search"))
                                .build());
        toolkit.removeTool("get_order");
        Model model = mock(Model.class);
        when(model.getToolSearchMode()).thenReturn(ToolSearchMode.DEFERRED);
        List<ToolResultBlock> actual = new ArrayList<>();
        for (int iteration = 0; iteration < 3; iteration++) {
            middleware
                    .onModelCall(
                            null,
                            ctx,
                            new ModelCallInput(
                                    ctx.getAgentState().getContext(),
                                    toolkit.getToolSchemas(List.of(), ctx.getToolRequestConfig()),
                                    null,
                                    model),
                            input -> {
                                actual.add(
                                        input.messages()
                                                .get(0)
                                                .getFirstContentBlock(ToolResultBlock.class));
                                return Flux.empty();
                            })
                    .blockLast();
            toolkit.registerSchema(schema("get_order", "new description"));
            if (iteration == 1)
                ctx.setToolRequestConfig(
                        new ToolRequestConfig(
                                Map.of(
                                        "external",
                                        new SchemaOnlyTool(schema("external", "client"))),
                                ToolMergeMode.EXTERNAL_ONLY));
        }
        assertEquals(List.of(), actual.get(0).getMetadata().get(ToolSearchTool.METADATA_TOOLS));
        assertEquals(List.of(), actual.get(2).getMetadata().get(ToolSearchTool.METADATA_TOOLS));
        var refreshed = (List<?>) actual.get(1).getMetadata().get(ToolSearchTool.METADATA_TOOLS);
        assertEquals("new description", ((Map<?, ?>) refreshed.get(0)).get("description"));
        var stored = (List<?>) result.getMetadata().get(ToolSearchTool.METADATA_TOOLS);
        assertEquals("old description", ((Map<?, ?>) stored.get(0)).get("description"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realAgentDiscoversThenCallsToolThroughPermissionChecks(boolean denied) {
        Toolkit toolkit = new Toolkit();
        AtomicInteger executions = new AtomicInteger();
        ToolBase order =
                new ToolBase(
                        ToolBase.builder()
                                .name("get_order")
                                .description("Query order")
                                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                                .readOnly(true)) {
                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        executions.incrementAndGet();
                        return Mono.just(ToolResultBlock.success("order 42"));
                    }
                };
        toolkit.registerAgentTool(order);
        List<List<String>> surfaces = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ChatModelBase model =
                new ChatModelBase() {
                    @Override
                    public String getModelName() {
                        return "fake";
                    }

                    @Override
                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        surfaces.add(tools.stream().map(ToolSchema::getName).toList());
                        int call = calls.getAndIncrement();
                        ContentBlock block =
                                call == 0
                                        ? ToolUseBlock.builder()
                                                .id("search")
                                                .name("tool_search")
                                                .input(Map.of("query", "order"))
                                                .content("{\"query\":\"order\"}")
                                                .build()
                                        : call == 1
                                                ? ToolUseBlock.builder()
                                                        .id("order")
                                                        .name("get_order")
                                                        .input(Map.of())
                                                        .build()
                                                : TextBlock.builder().text("done").build();
                        return Flux.just(ChatResponse.builder().content(List.of(block)).build());
                    }
                };
        var permissions = PermissionContextState.builder().mode(PermissionMode.EXPLORE);
        if (denied)
            permissions.addDenyRule(
                    "get_order",
                    new PermissionRule("get_order", null, PermissionBehavior.DENY, "test"));
        var agent =
                ReActAgent.builder()
                        .name("test")
                        .sysPrompt("test")
                        .model(model)
                        .toolkit(toolkit)
                        .middleware(new ToolSearchMiddleware(toolkit))
                        .permissionContext(permissions.build())
                        .build();
        assertNotNull(
                agent.call("Find the order", RuntimeContext.builder().sessionId("a").build())
                        .block());
        assertEquals(List.of("tool_search"), surfaces.get(0));
        assertTrue(
                surfaces.get(1).contains("get_order"),
                () ->
                        "Surfaces: "
                                + surfaces
                                + " Context: "
                                + JsonUtils.getJsonCodec()
                                        .toJson(agent.getAgentState().getContext()));
        assertEquals(denied ? 0 : 1, executions.get());
        var engine =
                new PermissionEngine(
                        PermissionContextState.builder().mode(PermissionMode.EXPLORE).build());
        assertEquals(
                PermissionBehavior.ALLOW,
                engine.checkPermission(
                                (ToolBase) toolkit.getTool("tool_search"), Map.of("query", "order"))
                        .block()
                        .getBehavior());
        ToolBase write =
                new ToolBase(
                        ToolBase.builder()
                                .name("cancel_order")
                                .description("Cancel")
                                .inputSchema(Map.of())) {
                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.just(ToolResultBlock.success("cancelled"));
                    }
                };
        assertEquals(
                PermissionBehavior.DENY,
                engine.checkPermission(write, Map.of()).block().getBehavior());
    }
}
