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
package io.agentscope.harness.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.Version;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.middleware.PlanModeMiddleware;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import io.agentscope.harness.agent.workspace.plan.PlanModeManager;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@HarnessQuiescence
class HarnessAgentParallelWebSearchTest {

    private static final String PARALLEL_SERVER = "parallel-search";
    private static final String PARALLEL_URL = "https://search.parallel.ai/mcp";

    /** What the live endpoint advertises today: canonical name, annotated read-only. */
    private static final String ANNOTATED_WEB_SEARCH =
            """
            {"tools":[{"name":"web_search","description":"Search the web",
             "inputSchema":{"type":"object","properties":{
             "objective":{"type":"string"},
             "search_queries":{"type":"array","items":{"type":"string"}}},
             "required":["objective","search_queries"]},
             "annotations":{"readOnlyHint":true}},
             {"name":"web_fetch","description":"Remote fetch must not be imported",
              "inputSchema":{"type":"object","properties":{"urls":{"type":"array"}}}}]}
            """;

    /** Same tool, but the server leaves the read-only hint off. */
    private static final String UNANNOTATED_WEB_SEARCH =
            """
            {"tools":[{"name":"web_search","description":"Search the web",
             "inputSchema":{"type":"object","properties":{
             "objective":{"type":"string"},
             "search_queries":{"type":"array","items":{"type":"string"}}},
             "required":["objective","search_queries"]}}]}
            """;

    /** A provider-side rename: nothing matches the {@code enableTools} allowlist any more. */
    private static final String RENAMED_WEB_SEARCH =
            """
            {"tools":[{"name":"parallel_web_search","description":"Search the web",
             "inputSchema":{"type":"object","properties":{
             "objective":{"type":"string"},
             "search_queries":{"type":"array","items":{"type":"string"}}},
             "required":["objective","search_queries"]},
             "annotations":{"readOnlyHint":true}}]}
            """;

    private static final String SEARCH_PAYLOAD =
            """
            {"search_id":"search_fixture","session_id":"fixture-session",
             "results":[{"title":"AgentScope Java documentation",
             "url":"https://java.agentscope.io/",
             "excerpts":["Official Java agent framework documentation."]}]}
            """;

    @TempDir Path workspace;

    @Test
    void tavilyRemainsDefaultWithoutConnectingToParallel() {
        try (MockedStatic<McpClientBuilder> builders = mockStatic(McpClientBuilder.class);
                HarnessAgent agent = builder().build()) {
            Toolkit toolkit = agent.getDelegate().getToolkit();
            assertTrue(toolkit.getTool("web_search").getDescription().contains("TAVILY_API_KEY"));
            assertTrue(toolkit.getTool("web_search").getParameters().toString().contains("query"));
            assertNotNull(toolkit.getTool("web_fetch"));
            builders.verifyNoInteractions();
        }
    }

    @Test
    void disabledWebToolsSuppressSearchInParentAndChildrenRegardlessOfSelectionOrder() {
        try (MockedStatic<McpClientBuilder> builders = mockStatic(McpClientBuilder.class)) {
            for (boolean disableFirst : List.of(true, false)) {
                HarnessAgent.Builder selected = builder().subagent(declaredWorker());
                if (disableFirst) selected.disableWebTools().parallelWebSearch();
                else selected.parallelWebSearch().disableWebTools();
                try (HarnessAgent agent = selected.build()) {
                    assertFalse(
                            agent.getDelegate().getToolkit().getToolNames().contains("web_search"));
                    assertFalse(
                            agent.getDelegate().getToolkit().getToolNames().contains("web_fetch"));
                }
                // Turning web tools off must not hand children the keyless-failing Tavily tool
                // that the parent just opted out of — on either subagent path.
                List<SubagentEntry> children = selected.buildSubagentEntries(workspace);
                assertEquals(2, children.size());
                for (SubagentEntry entry : children) {
                    try (HarnessAgent child =
                            (HarnessAgent) entry.factory().create(RuntimeContext.empty())) {
                        assertFalse(
                                child.getDelegate()
                                        .getToolkit()
                                        .getToolNames()
                                        .contains("web_search"),
                                entry.name() + " must not re-enable the disabled search tool");
                        assertFalse(
                                child.getDelegate()
                                        .getToolkit()
                                        .getToolNames()
                                        .contains("web_fetch"),
                                entry.name() + " must not re-enable the disabled fetch tool");
                    }
                }
            }
            builders.verifyNoInteractions();
        }
    }

    @Test
    void selectedParallelConnectionFailureFallsBackToTavilyWithPinnedTimeouts() {
        McpClientBuilder mcpBuilder = mock(McpClientBuilder.class, RETURNS_SELF);
        McpClientWrapper client = mock(McpClientWrapper.class);
        IllegalStateException failure = new IllegalStateException("connection unavailable");
        when(mcpBuilder.buildAsync()).thenReturn(Mono.just(client));
        when(client.initialize()).thenReturn(Mono.error(failure));
        List<McpServerRegistrationResult> results = new ArrayList<>();
        try (MockedStatic<McpClientBuilder> builders = mockStatic(McpClientBuilder.class)) {
            builders.when(() -> McpClientBuilder.create(PARALLEL_SERVER)).thenReturn(mcpBuilder);
            try (HarnessAgent agent =
                    builder()
                            .parallelWebSearch()
                            .mcpServerRegistrationListener(results::add)
                            .build()) {
                // An outage on a third-party endpoint degrades search; it does not make the
                // agent unbuildable.
                AgentTool search = agent.getDelegate().getToolkit().getTool("web_search");
                assertNotNull(search, "build() must not leave the agent without web_search");
                assertTrue(
                        search.getDescription().contains("TAVILY_API_KEY"),
                        "a failed Parallel handshake must fall back to the built-in tool");
            }
        }
        // build() must not be able to block for the McpClientBuilder defaults (30s / 120s).
        verify(mcpBuilder).initializationTimeout(Duration.ofSeconds(10));
        verify(mcpBuilder).timeout(Duration.ofSeconds(30));
        assertEquals(1, results.size());
        assertEquals(McpServerRegistrationResult.Status.FAILED, results.get(0).status());
        assertSame(failure, results.get(0).cause());
        verify(client).close();
    }

    @Test
    void selectedParallelFallsBackWhenRemoteStopsAdvertisingWebSearch() throws Exception {
        ParallelFixture fixture = new ParallelFixture(RENAMED_WEB_SEARCH);
        try {
            fixture.run(
                    () -> {
                        try (HarnessAgent agent = builder().parallelWebSearch().build()) {
                            Toolkit toolkit = agent.getDelegate().getToolkit();
                            AgentTool search = toolkit.getTool("web_search");
                            // McpServerRegistrar closes the client and returns normally when
                            // nothing matches enableTools, so an empty match must not silently
                            // leave the agent with no search tool at all.
                            assertNotNull(
                                    search, "a provider-side rename must not remove web_search");
                            assertTrue(
                                    search.getDescription().contains("TAVILY_API_KEY"),
                                    "an unmatched allowlist must fall back to the built-in tool");
                            assertFalse(toolkit.getToolNames().contains("parallel_web_search"));
                        }
                    });
            assertTrue(fixture.methods.contains("tools/list"), "the handshake itself succeeded");
        } finally {
            fixture.stop();
        }
    }

    @Test
    void selectedParallelPinsReadOnlyWhenServerOmitsAnnotations(@TempDir Path project)
            throws Exception {
        ParallelFixture fixture = new ParallelFixture(UNANNOTATED_WEB_SEARCH);
        try {
            fixture.run(
                    () -> {
                        try (HarnessAgent agent = builder().parallelWebSearch().build()) {
                            Toolkit toolkit = agent.getDelegate().getToolkit();
                            AgentTool search = toolkit.getTool("web_search");
                            assertEquals(
                                    List.of("objective", "search_queries"),
                                    search.getParameters().get("required"),
                                    "the Parallel tool must be the one registered");
                            // Inherited from annotations.readOnlyHint this would be false.
                            assertTrue(
                                    search.isReadOnly(),
                                    "read-only must not depend on the remote annotation");
                            assertPlanModeAllows(toolkit, project);
                        }
                    });
        } finally {
            fixture.stop();
        }
    }

    @Test
    void selectedParallelUsesNativeMcpResultsAndProjectUserAgent() throws Exception {
        ParallelFixture fixture = new ParallelFixture(ANNOTATED_WEB_SEARCH);
        try {
            fixture.run(
                    () -> {
                        try (HarnessAgent agent = builder().parallelWebSearch().build()) {
                            Toolkit toolkit = agent.getDelegate().getToolkit();
                            assertEquals(
                                    1,
                                    toolkit.getToolNames().stream()
                                            .filter("web_search"::equals)
                                            .count());
                            assertTrue(toolkit.getTool("web_search").isReadOnly());
                            assertEquals(
                                    List.of("objective", "search_queries"),
                                    toolkit.getTool("web_search").getParameters().get("required"));
                            assertTrue(
                                    toolkit.getTool("web_fetch")
                                            .getParameters()
                                            .toString()
                                            .contains("max_chars"));
                            assertFalse(
                                    toolkit.getToolNames().contains("parallel-search__web_search"));
                            Map<String, Object> input =
                                    Map.of(
                                            "objective",
                                            "Find AgentScope documentation",
                                            "search_queries",
                                            List.of("AgentScope Java docs"));
                            ToolResultBlock result = search(toolkit, input);
                            assertNotNull(result);
                            assertFalse(
                                    result.getState() == ToolResultState.ERROR,
                                    result.getOutput().toString());
                            assertEquals(
                                    1,
                                    result.getOutput().size(),
                                    "text and structured content must not be duplicated");
                            assertEquals(
                                    SEARCH_PAYLOAD,
                                    ((TextBlock) result.getOutput().get(0)).getText());
                            assertEquals(
                                    new ObjectMapper().valueToTree(input),
                                    fixture.searchInputs.peek());
                            ToolResultBlock error =
                                    search(
                                            toolkit,
                                            Map.of(
                                                    "objective",
                                                    "quota error",
                                                    "search_queries",
                                                    List.of("quota")));
                            assertNotNull(error);
                            assertEquals(ToolResultState.ERROR, error.getState());
                            assertTrue(
                                    error.getOutput().toString().contains("Rate limit exceeded"));
                        }
                    });
            assertTrue(fixture.methods.contains("initialize"));
            assertTrue(fixture.methods.contains("tools/list"));
            assertEquals(2, fixture.searchInputs.size());
            assertFalse(fixture.userAgents.isEmpty());
            assertTrue(
                    fixture.userAgents.stream()
                            .allMatch(ua -> ua.equals("agentscope-java/" + Version.VERSION)));
        } finally {
            fixture.stop();
        }
    }

    @Test
    void subagentsShareTheParentParallelConnectionInsteadOfOnePerSpawn() throws Exception {
        // What a single registration costs, measured rather than hard-coded: the MCP client is
        // free to change how many round trips one handshake takes, but spawning subagents must
        // not multiply whatever that number is.
        long soloInitialize;
        long soloListTools;
        ParallelFixture solo = new ParallelFixture(ANNOTATED_WEB_SEARCH);
        try {
            solo.run(
                    () -> {
                        try (HarnessAgent agent = builder().parallelWebSearch().build()) {
                            assertNotNull(agent.getDelegate().getToolkit().getTool("web_search"));
                        }
                    });
            soloInitialize = solo.count("initialize");
            soloListTools = solo.count("tools/list");
            assertTrue(soloInitialize > 0, "the fixture must have seen a real handshake");
            assertTrue(soloListTools > 0, "the fixture must have seen a real tool listing");
        } finally {
            solo.stop();
        }

        ParallelFixture fixture = new ParallelFixture(ANNOTATED_WEB_SEARCH);
        try {
            fixture.run(
                    () -> {
                        HarnessAgent.Builder parent =
                                builder().parallelWebSearch().subagent(declaredWorker());
                        try (HarnessAgent agent = parent.build()) {
                            assertTrue(
                                    agent.getDelegate()
                                            .getToolkit()
                                            .getTool("web_search")
                                            .isReadOnly());
                            // general-purpose + the declared worker: both subagent paths.
                            List<SubagentEntry> children = parent.buildSubagentEntries(workspace);
                            assertEquals(2, children.size());
                            for (SubagentEntry entry : children) {
                                try (HarnessAgent child =
                                        (HarnessAgent)
                                                entry.factory().create(RuntimeContext.empty())) {
                                    Toolkit toolkit = child.getDelegate().getToolkit();
                                    assertEquals(
                                            List.of("objective", "search_queries"),
                                            toolkit.getTool("web_search")
                                                    .getParameters()
                                                    .get("required"),
                                            entry.name() + " must keep the selected provider");
                                    ToolResultBlock result =
                                            search(
                                                    toolkit,
                                                    Map.of(
                                                            "objective",
                                                            "Find AgentScope documentation",
                                                            "search_queries",
                                                            List.of("AgentScope Java docs")));
                                    assertNotNull(result);
                                    assertFalse(result.getState() == ToolResultState.ERROR);
                                    assertEquals(
                                            SEARCH_PAYLOAD,
                                            ((TextBlock) result.getOutput().get(0)).getText());
                                }
                            }
                        }
                    });
            // The whole point: spawning costs nothing on the wire beyond the searches themselves.
            assertEquals(
                    soloInitialize,
                    fixture.count("initialize"),
                    "parent + 2 children must share the parent's connection");
            assertEquals(
                    soloListTools,
                    fixture.count("tools/list"),
                    "spawning must not re-list the remote tools");
            assertEquals(2, fixture.searchInputs.size(), "both children searched over it");
        } finally {
            fixture.stop();
        }
    }

    /**
     * Drives the real {@link PlanModeMiddleware} with the resolver {@code HarnessAgent} wires, so
     * a pinned read-only flag is checked where it actually matters.
     */
    private void assertPlanModeAllows(Toolkit toolkit, Path project) {
        AbstractFilesystem fs =
                new LocalFilesystemSpec().project(project).toFilesystem(workspace, null);
        try (WorkspaceManager wm = new WorkspaceManager(workspace, fs)) {
            PlanModeManager manager = new PlanModeManager(wm, null);
            AgentState state = AgentState.builder().build();
            manager.enter(state);
            Agent agent = mock(Agent.class);
            when(agent.getAgentState()).thenReturn(state);
            when(agent.getAgentId()).thenReturn("plan-mode-probe");
            when(agent.getName()).thenReturn("plan-mode-probe");
            PlanModeMiddleware middleware =
                    new PlanModeMiddleware(
                            manager,
                            toolName -> {
                                AgentTool t = toolkit.getTool(toolName);
                                return t != null && t.isReadOnly();
                            });
            AtomicReference<ActingInput> forwarded = new AtomicReference<>();
            middleware
                    .onActing(
                            agent,
                            null,
                            new ActingInput(
                                    List.of(
                                            ToolUseBlock.builder()
                                                    .id("plan-search")
                                                    .name("web_search")
                                                    .input(Map.of())
                                                    .build())),
                            ai -> {
                                forwarded.set(ai);
                                return Flux.empty();
                            })
                    .collectList()
                    .block();
            assertNotNull(forwarded.get(), "plan mode denied web_search instead of forwarding it");
            assertEquals(
                    List.of("web_search"),
                    forwarded.get().toolCalls().stream().map(ToolUseBlock::getName).toList());
        }
    }

    private SubagentDeclaration declaredWorker() {
        return SubagentDeclaration.builder()
                .name("search-worker")
                .description("Searches the web")
                .inlineAgentsBody("Find useful sources.")
                .workspaceMode(WorkspaceMode.SHARED)
                .build();
    }

    private ToolResultBlock search(Toolkit toolkit, Map<String, Object> input) {
        return toolkit.callTool(
                        ToolCallParam.builder()
                                .toolUseBlock(
                                        ToolUseBlock.builder()
                                                .id("fixture-search")
                                                .name("web_search")
                                                .input(input)
                                                .content(JsonUtils.getJsonCodec().toJson(input))
                                                .build())
                                .build())
                .block();
    }

    private HarnessAgent.Builder builder() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        return HarnessAgent.builder()
                .name("parallel-search-test")
                .model(model)
                .workspace(workspace)
                .disableToolsConfig()
                .disableFilesystemTools()
                .disableShellTool()
                .disableDynamicSkills();
    }

    /**
     * A local Streamable HTTP MCP server standing in for {@code search.parallel.ai}.
     *
     * <p>Keeping the endpoint local is deliberate: a smoke test against the hosted service would
     * put an external dependency, and its rate limit, in the test suite.
     */
    private static final class ParallelFixture {

        private final ObjectMapper mapper = new ObjectMapper();
        private final HttpServer server;
        final ConcurrentLinkedQueue<String> userAgents = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<String> methods = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<JsonNode> searchInputs = new ConcurrentLinkedQueue<>();

        ParallelFixture(String toolsListResult) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", exchange -> handle(exchange, toolsListResult));
            server.start();
        }

        private void handle(com.sun.net.httpserver.HttpExchange exchange, String toolsListResult)
                throws java.io.IOException {
            userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            assertFalse(exchange.getRequestHeaders().containsKey("Authorization"));
            assertFalse(exchange.getRequestHeaders().containsKey("x-api-key"));
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.sendResponseHeaders(
                        exchange.getRequestMethod().equals("DELETE") ? 200 : 405, -1);
                exchange.close();
                return;
            }
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            methods.add(method);
            if (!request.has("id")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            JsonNode result;
            if (method.equals("initialize")) {
                result =
                        mapper.valueToTree(
                                Map.of(
                                        "protocolVersion",
                                                request.path("params")
                                                        .path("protocolVersion")
                                                        .asText(),
                                        "capabilities", Map.of("tools", Map.of()),
                                        "serverInfo",
                                                Map.of(
                                                        "name", "parallel-fixture",
                                                        "version", "1")));
            } else if (method.equals("tools/list")) {
                result = mapper.readTree(toolsListResult);
            } else {
                assertEquals("tools/call", method);
                assertEquals("web_search", request.path("params").path("name").asText());
                JsonNode arguments = request.path("params").path("arguments");
                searchInputs.add(arguments);
                boolean quota = arguments.path("objective").asText().equals("quota error");
                ObjectNode toolResult = mapper.createObjectNode();
                toolResult.put("isError", quota);
                toolResult
                        .putArray("content")
                        .addObject()
                        .put("type", "text")
                        .put("text", quota ? "Rate limit exceeded" : SEARCH_PAYLOAD);
                if (!quota) toolResult.set("structuredContent", mapper.readTree(SEARCH_PAYLOAD));
                result = toolResult;
            }
            ObjectNode response = mapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.get("id"));
            response.set("result", result);
            byte[] bytes = mapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        /**
         * Runs {@code body} with the production URL redirected to this fixture. Only the endpoint
         * is swapped; the real headers, transport and registrar still run.
         */
        void run(Runnable body) {
            McpClientBuilder nativeBuilder =
                    spy(
                            McpClientBuilder.create(PARALLEL_SERVER)
                                    .streamableHttpTransport(
                                            "http://127.0.0.1:"
                                                    + server.getAddress().getPort()
                                                    + "/mcp"));
            doReturn(nativeBuilder).when(nativeBuilder).streamableHttpTransport(PARALLEL_URL);
            try (MockedStatic<McpClientBuilder> builders = mockStatic(McpClientBuilder.class)) {
                builders.when(() -> McpClientBuilder.create(PARALLEL_SERVER))
                        .thenReturn(nativeBuilder);
                body.run();
            }
        }

        long count(String method) {
            return methods.stream().filter(method::equals).count();
        }

        void stop() {
            server.stop(0);
        }
    }
}
