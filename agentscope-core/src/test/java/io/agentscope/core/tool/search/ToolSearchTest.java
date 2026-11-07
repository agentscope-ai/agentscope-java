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
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.MiddlewareBase;
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
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.session.SessionHistoryMode;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ToolContextState;
import io.agentscope.core.tool.SchemaOnlyTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolMergeMode;
import io.agentscope.core.tool.ToolRequestConfig;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
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

    private static ChatModelBase deferredModel(List<List<ToolSchema>> surfaces) {
        return new ChatModelBase() {
            @Override
            public String getModelName() {
                return "fake";
            }

            @Override
            public ToolSearchMode getToolSearchMode() {
                return ToolSearchMode.DEFERRED;
            }

            @Override
            protected Flux<ChatResponse> doStream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                surfaces.add(tools);
                return Flux.just(
                        ChatResponse.builder()
                                .content(List.of(TextBlock.builder().text("done").build()))
                                .build());
            }
        };
    }

    /**
     * The session-recording wrapper ({@code SessionModels.Recorded}) must forward the model's
     * native tool-search mode; falling back to the interface default silently degraded
     * DEFERRED/CLIENT models to FUNCTION mode and hid the catalog instead of deferring its
     * loading.
     */
    @Test
    void sessionRecordingWrapperPreservesNativeToolSearchMode() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(schema("get_order", "orders"));
        List<List<ToolSchema>> surfaces = new ArrayList<>();
        var agent =
                ReActAgent.builder()
                        .name("test")
                        .sysPrompt("test")
                        .model(deferredModel(surfaces))
                        .toolkit(toolkit)
                        .middleware(new ToolSearchMiddleware(toolkit))
                        .sessionLogStore(new InMemorySessionLogStore())
                        .sessionHistoryMode(SessionHistoryMode.EVENT_LOG)
                        .build();
        assertNotNull(agent.call("hi", RuntimeContext.builder().sessionId("a").build()).block());
        assertEquals(1, surfaces.size());
        assertTrue(
                surfaces.get(0).stream()
                        .anyMatch(
                                tool ->
                                        "get_order".equals(tool.getName())
                                                && Boolean.TRUE.equals(tool.getDeferLoading())),
                () -> "Surface: " + surfaces.get(0));
    }

    /** The failover wrapper must forward the active model's native tool-search mode likewise. */
    @Test
    void failoverWrapperPreservesNativeToolSearchMode() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(schema("get_order", "orders"));
        List<List<ToolSchema>> surfaces = new ArrayList<>();
        ChatModelBase fallback =
                new ChatModelBase() {
                    @Override
                    public String getModelName() {
                        return "fallback";
                    }

                    @Override
                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text("done").build()))
                                        .build());
                    }
                };
        var agent =
                ReActAgent.builder()
                        .name("test")
                        .sysPrompt("test")
                        .model(deferredModel(surfaces))
                        .fallbackModel(fallback)
                        .toolkit(toolkit)
                        .middleware(new ToolSearchMiddleware(toolkit))
                        .build();
        assertNotNull(agent.call("hi", RuntimeContext.builder().sessionId("a").build()).block());
        assertEquals(1, surfaces.size());
        assertTrue(
                surfaces.get(0).stream()
                        .anyMatch(
                                tool ->
                                        "get_order".equals(tool.getName())
                                                && Boolean.TRUE.equals(tool.getDeferLoading())),
                () -> "Surface: " + surfaces.get(0));
    }

    /**
     * Capability probes must survive the agent's model wrappers: {@code ModelCallInput.model()}
     * is the wrapper that {@code ON_MODEL_CALL} middlewares see, so a dropped probe silently
     * reports the interface default to every middleware. Same omission class as the
     * tool-search-mode forwarding, pinned for {@code supportsToolChoiceSpecific}.
     *
     * <p>With a fallback configured, {@code modelForCall} returns the failover wrapper and the
     * session-recording wrap happens inside its {@code stream()} (below the middleware), so the
     * two wrappers need separate scenarios: no-fallback exercises {@code SessionModels.Recorded},
     * fallback-only exercises the failover wrapper.
     */
    @Test
    void modelWrappersForwardSupportsToolChoiceSpecific() {
        List<Boolean> recorded = new ArrayList<>();
        assertNotNull(
                ReActAgent.builder()
                        .name("test")
                        .sysPrompt("test")
                        .model(toolChoiceCapableModel(true))
                        .middleware(
                                captureModel(
                                        model -> recorded.add(model.supportsToolChoiceSpecific())))
                        .sessionLogStore(new InMemorySessionLogStore())
                        .sessionHistoryMode(SessionHistoryMode.EVENT_LOG)
                        .build()
                        .call("hi", RuntimeContext.builder().sessionId("a").build())
                        .block());
        assertEquals(
                List.of(Boolean.TRUE), recorded, "the Recorded wrapper must forward the probe");

        List<Boolean> failover = new ArrayList<>();
        assertNotNull(
                ReActAgent.builder()
                        .name("test")
                        .sysPrompt("test")
                        .model(toolChoiceCapableModel(true))
                        .fallbackModel(toolChoiceCapableModel(false))
                        .middleware(
                                captureModel(
                                        model -> failover.add(model.supportsToolChoiceSpecific())))
                        .build()
                        .call("hi", RuntimeContext.builder().sessionId("a").build())
                        .block());
        assertEquals(
                List.of(Boolean.TRUE),
                failover,
                "the failover wrapper must forward the active (primary) model's probe");

        List<Boolean> negative = new ArrayList<>();
        assertNotNull(
                ReActAgent.builder()
                        .name("test")
                        .sysPrompt("test")
                        .model(toolChoiceCapableModel(false))
                        .middleware(
                                captureModel(
                                        model -> negative.add(model.supportsToolChoiceSpecific())))
                        .sessionLogStore(new InMemorySessionLogStore())
                        .sessionHistoryMode(SessionHistoryMode.EVENT_LOG)
                        .build()
                        .call("hi", RuntimeContext.builder().sessionId("a").build())
                        .block());
        assertEquals(
                List.of(Boolean.FALSE),
                negative,
                "a probe the delegate does not declare must still read false (no hardcoded"
                        + " true)");

        // False direction through the failover wrapper: the active (primary) model declares
        // false, so a wrapper hardcoding true must fail here.
        List<Boolean> failoverNegative = new ArrayList<>();
        assertNotNull(
                ReActAgent.builder()
                        .name("test")
                        .sysPrompt("test")
                        .model(toolChoiceCapableModel(false))
                        .fallbackModel(toolChoiceCapableModel(true))
                        .middleware(
                                captureModel(
                                        model ->
                                                failoverNegative.add(
                                                        model.supportsToolChoiceSpecific())))
                        .build()
                        .call("hi", RuntimeContext.builder().sessionId("a").build())
                        .block());
        assertEquals(
                List.of(Boolean.FALSE),
                failoverNegative,
                "the failover wrapper must forward the primary's false, not hardcode true");
    }

    private static ChatModelBase toolChoiceCapableModel(boolean supportsToolChoice) {
        return new ChatModelBase() {
            @Override
            public String getModelName() {
                return "fake";
            }

            @Override
            public boolean supportsToolChoiceSpecific() {
                return supportsToolChoice;
            }

            @Override
            protected Flux<ChatResponse> doStream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(
                        ChatResponse.builder()
                                .content(List.of(TextBlock.builder().text("done").build()))
                                .build());
            }
        };
    }

    /**
     * A model whose plain and with-tools structured-output capabilities diverge; the interface
     * default derives {@code WithTools} from the plain flag, so a wrapper that only forwards the
     * plain flag reports the wrong with-tools value through the middleware SPI.
     */
    private static ChatModelBase divergentStructuredOutputModel(boolean plain, boolean withTools) {
        return new ChatModelBase() {
            @Override
            public String getModelName() {
                return "fake";
            }

            @Override
            public boolean supportsNativeStructuredOutput() {
                return plain;
            }

            @Override
            public boolean supportsNativeStructuredOutputWithTools() {
                return withTools;
            }

            @Override
            protected Flux<ChatResponse> doStream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(
                        ChatResponse.builder()
                                .content(List.of(TextBlock.builder().text("done").build()))
                                .build());
            }
        };
    }

    /** The failover wrapper must forward {@code supportsNativeStructuredOutputWithTools} itself. */
    @Test
    void failoverWrapperForwardsStructuredOutputWithTools() {
        List<Boolean> failover = new ArrayList<>();
        Consumer<Model> probePlain = model -> failover.add(model.supportsNativeStructuredOutput());
        assertNotNull(
                ReActAgent.builder()
                        .name("test")
                        .sysPrompt("test")
                        .model(divergentStructuredOutputModel(true, false))
                        .fallbackModel(divergentStructuredOutputModel(true, false))
                        .middleware(captureModel(probePlain))
                        .build()
                        .call("hi", RuntimeContext.builder().sessionId("a").build())
                        .block());
        assertEquals(
                List.of(Boolean.TRUE), failover, "the plain flag must stay forwarded (regression)");

        List<Boolean> failoverWithTools = new ArrayList<>();
        Consumer<Model> probeFailoverWithTools =
                model -> failoverWithTools.add(model.supportsNativeStructuredOutputWithTools());
        assertNotNull(
                ReActAgent.builder()
                        .name("test")
                        .sysPrompt("test")
                        .model(divergentStructuredOutputModel(true, false))
                        .fallbackModel(divergentStructuredOutputModel(true, false))
                        .middleware(captureModel(probeFailoverWithTools))
                        .build()
                        .call("hi", RuntimeContext.builder().sessionId("a").build())
                        .block());
        assertEquals(
                List.of(Boolean.FALSE),
                failoverWithTools,
                "the failover wrapper must forward WithTools itself; deriving it from the"
                        + " forwarded plain flag reports the wrong value");

        List<Boolean> recordedWithTools = new ArrayList<>();
        Consumer<Model> probeRecordedWithTools =
                model -> recordedWithTools.add(model.supportsNativeStructuredOutputWithTools());
        assertNotNull(
                ReActAgent.builder()
                        .name("test")
                        .sysPrompt("test")
                        .model(divergentStructuredOutputModel(true, false))
                        .middleware(captureModel(probeRecordedWithTools))
                        .sessionLogStore(new InMemorySessionLogStore())
                        .sessionHistoryMode(SessionHistoryMode.EVENT_LOG)
                        .build()
                        .call("hi", RuntimeContext.builder().sessionId("a").build())
                        .block());
        assertEquals(
                List.of(Boolean.FALSE),
                recordedWithTools,
                "the Recorded wrapper keeps forwarding WithTools (regression)");

        // True direction through the failover wrapper: with-tools=true, plain=false — a wrapper
        // hardcoding false must fail here.
        List<Boolean> failoverWithToolsTrue = new ArrayList<>();
        Consumer<Model> probeFailoverWithToolsTrue =
                model -> failoverWithToolsTrue.add(model.supportsNativeStructuredOutputWithTools());
        assertNotNull(
                ReActAgent.builder()
                        .name("test")
                        .sysPrompt("test")
                        .model(divergentStructuredOutputModel(false, true))
                        .fallbackModel(divergentStructuredOutputModel(false, true))
                        .middleware(captureModel(probeFailoverWithToolsTrue))
                        .build()
                        .call("hi", RuntimeContext.builder().sessionId("a").build())
                        .block());
        assertEquals(
                List.of(Boolean.TRUE),
                failoverWithToolsTrue,
                "the failover wrapper must forward the delegate's true WithTools, not hardcode"
                        + " false");
    }

    private static MiddlewareBase captureModel(Consumer<Model> sink) {
        return new MiddlewareBase() {
            @Override
            public Flux<AgentEvent> onModelCall(
                    Agent agent,
                    RuntimeContext ctx,
                    ModelCallInput input,
                    Function<ModelCallInput, Flux<AgentEvent>> next) {
                sink.accept(input.model());
                return next.apply(input);
            }
        };
    }

    /**
     * Mechanical drift guard: every {@code default} capability probe on {@link Model} must be
     * overridden by both agent-internal wrappers, so probes never silently leak the interface
     * default through {@code ModelCallInput.model()}. Three omissions of exactly this shape
     * accumulated before this guard; a future probe added to {@code Model} without wrapper
     * forwarding turns this test red without any manual checklist.
     *
     * <p>Shape-based by design: the per-probe tests above pin the delegated values (including
     * false directions); this test pins only that an override exists.
     */
    @Test
    void modelWrappersOverrideEveryModelDefaultProbe() throws Exception {
        List<Model> wrappers = new ArrayList<>();
        Model plain = toolChoiceCapableModel(false);
        Consumer<Model> collect = wrappers::add;
        // The Recorded wrapper: session recording on, no fallback.
        assertNotNull(
                ReActAgent.builder()
                        .name("test")
                        .sysPrompt("test")
                        .model(plain)
                        .middleware(captureModel(collect))
                        .sessionLogStore(new InMemorySessionLogStore())
                        .sessionHistoryMode(SessionHistoryMode.EVENT_LOG)
                        .build()
                        .call("hi", RuntimeContext.builder().sessionId("a").build())
                        .block());
        // The failover wrapper: fallback configured, no session store.
        assertNotNull(
                ReActAgent.builder()
                        .name("test")
                        .sysPrompt("test")
                        .model(plain)
                        .fallbackModel(toolChoiceCapableModel(false))
                        .middleware(captureModel(collect))
                        .build()
                        .call("hi", RuntimeContext.builder().sessionId("a").build())
                        .block());

        assertTrue(wrappers.size() >= 2, () -> "wrappers: " + wrappers);
        // Deduplicate by class: the invariant is per wrapper kind, not this fixture's exact call
        // count (a future internal extra ON_MODEL_CALL must not fail the guard).
        Map<Class<?>, Model> wrappersByClass = new LinkedHashMap<>();
        wrappers.forEach(wrapper -> wrappersByClass.putIfAbsent(wrapper.getClass(), wrapper));
        assertTrue(
                wrappersByClass.size() >= 2, () -> "wrapper classes: " + wrappersByClass.keySet());
        for (Model wrapper : wrappersByClass.values()) {
            for (Method probe : Model.class.getDeclaredMethods()) {
                if (!probe.isDefault()) continue;
                Method resolved = null;
                for (Method candidate : wrapper.getClass().getMethods()) {
                    if (candidate.getName().equals(probe.getName())
                            && Arrays.equals(
                                    candidate.getParameterTypes(), probe.getParameterTypes())) {
                        resolved = candidate;
                        break;
                    }
                }
                // Inherited forwarding (e.g. a shared delegating base class) counts; only an
                // unforwarded probe — resolved straight to the interface default — fails.
                assertTrue(
                        resolved != null && resolved.getDeclaringClass() != Model.class,
                        () ->
                                wrapper.getClass().getName()
                                        + " must override or inherit a forwarding of Model."
                                        + probe.getName()
                                        + "(); default probes must not leak through wrappers");
            }
        }
    }
}
