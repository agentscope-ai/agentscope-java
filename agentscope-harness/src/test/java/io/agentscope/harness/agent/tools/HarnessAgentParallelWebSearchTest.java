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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import reactor.core.publisher.Mono;

@HarnessQuiescence
class HarnessAgentParallelWebSearchTest {

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
    void disabledWebToolsSuppressParallelConnectionRegardlessOfSelectionOrder() {
        try (MockedStatic<McpClientBuilder> builders = mockStatic(McpClientBuilder.class)) {
            for (boolean disableFirst : List.of(true, false)) {
                HarnessAgent.Builder selected = builder();
                if (disableFirst) selected.disableWebTools().parallelWebSearch();
                else selected.parallelWebSearch().disableWebTools();
                try (HarnessAgent agent = selected.build()) {
                    assertFalse(
                            agent.getDelegate().getToolkit().getToolNames().contains("web_search"));
                    assertFalse(
                            agent.getDelegate().getToolkit().getToolNames().contains("web_fetch"));
                }
                for (var entry : selected.buildSubagentEntries(workspace)) {
                    try (HarnessAgent child =
                            (HarnessAgent) entry.factory().create(RuntimeContext.empty())) {
                        assertTrue(
                                child.getDelegate()
                                        .getToolkit()
                                        .getTool("web_search")
                                        .getDescription()
                                        .contains("TAVILY_API_KEY"));
                    }
                }
            }
            builders.verifyNoInteractions();
        }
    }

    @Test
    void selectedParallelFailureAbortsBuildAndClosesFailedClient() {
        McpClientBuilder mcpBuilder = mock(McpClientBuilder.class, RETURNS_SELF);
        McpClientWrapper client = mock(McpClientWrapper.class);
        IllegalStateException failure = new IllegalStateException("connection unavailable");
        when(mcpBuilder.buildAsync()).thenReturn(Mono.just(client));
        when(client.initialize()).thenReturn(Mono.error(failure));
        List<McpServerRegistrationResult> results = new ArrayList<>();
        try (MockedStatic<McpClientBuilder> builders = mockStatic(McpClientBuilder.class)) {
            builders.when(() -> McpClientBuilder.create("parallel-search")).thenReturn(mcpBuilder);
            McpConnectionException error =
                    assertThrows(
                            McpConnectionException.class,
                            () ->
                                    builder()
                                            .parallelWebSearch()
                                            .mcpServerRegistrationListener(results::add)
                                            .build());
            assertEquals("parallel-search", error.getServerName());
        }
        assertEquals(1, results.size());
        assertEquals(McpServerRegistrationResult.Status.FAILED, results.get(0).status());
        assertSame(failure, results.get(0).cause());
        verify(client).close();
    }

    @Test
    void selectedParallelUsesNativeMcpResultsAndProjectUserAgent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ConcurrentLinkedQueue<String> userAgents = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> methods = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<JsonNode> searchInputs = new ConcurrentLinkedQueue<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String payload =
                """
                {"search_id":"search_fixture","session_id":"fixture-session",
                 "results":[{"title":"AgentScope Java documentation",
                 "url":"https://java.agentscope.io/",
                 "excerpts":["Official Java agent framework documentation."]}]}
                """;
        server.createContext(
                "/mcp",
                exchange -> {
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
                                                                "name",
                                                                "parallel-fixture",
                                                                "version",
                                                                "1")));
                    } else if (method.equals("tools/list")) {
                        result =
                                mapper.readTree(
                                        """
                                        {"tools":[{"name":"web_search","description":"Search the web",
                                         "inputSchema":{"type":"object","properties":{
                                         "objective":{"type":"string"},
                                         "search_queries":{"type":"array","items":{"type":"string"}}},
                                         "required":["objective","search_queries"]},
                                         "annotations":{"readOnlyHint":true}},
                                         {"name":"web_fetch","description":"Remote fetch must not be imported",
                                         "inputSchema":{"type":"object","properties":{"urls":{"type":"array"}}}}]}
                                        """);
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
                                .put("text", quota ? "Rate limit exceeded" : payload);
                        if (!quota) toolResult.set("structuredContent", mapper.readTree(payload));
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
                });
        server.start();
        try {
            McpClientBuilder nativeBuilder =
                    spy(
                            McpClientBuilder.create("parallel-search")
                                    .streamableHttpTransport(
                                            "http://127.0.0.1:"
                                                    + server.getAddress().getPort()
                                                    + "/mcp"));
            // Redirect only the endpoint in the test; native headers, transport and registrar run.
            doReturn(nativeBuilder)
                    .when(nativeBuilder)
                    .streamableHttpTransport("https://search.parallel.ai/mcp");
            try (MockedStatic<McpClientBuilder> builders = mockStatic(McpClientBuilder.class)) {
                builders.when(() -> McpClientBuilder.create("parallel-search"))
                        .thenReturn(nativeBuilder);
                try (HarnessAgent agent = builder().parallelWebSearch().build()) {
                    Toolkit toolkit = agent.getDelegate().getToolkit();
                    assertEquals(
                            1,
                            toolkit.getToolNames().stream().filter("web_search"::equals).count());
                    assertTrue(toolkit.getTool("web_search").isReadOnly());
                    assertEquals(
                            List.of("objective", "search_queries"),
                            toolkit.getTool("web_search").getParameters().get("required"));
                    assertTrue(
                            toolkit.getTool("web_fetch")
                                    .getParameters()
                                    .toString()
                                    .contains("max_chars"));
                    assertFalse(toolkit.getToolNames().contains("parallel-search__web_search"));
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
                    assertEquals(payload, ((TextBlock) result.getOutput().get(0)).getText());
                    assertEquals(mapper.valueToTree(input), searchInputs.peek());
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
                    assertTrue(error.getOutput().toString().contains("Rate limit exceeded"));
                }
                SubagentDeclaration worker =
                        SubagentDeclaration.builder()
                                .name("search-worker")
                                .description("Searches the web")
                                .inlineAgentsBody("Find useful sources.")
                                .workspaceMode(WorkspaceMode.SHARED)
                                .build();
                var children =
                        builder()
                                .parallelWebSearch()
                                .subagent(worker)
                                .buildSubagentEntries(workspace);
                for (var entry : children) {
                    try (HarnessAgent child =
                            (HarnessAgent) entry.factory().create(RuntimeContext.empty())) {
                        Toolkit toolkit = child.getDelegate().getToolkit();
                        assertEquals(
                                List.of("objective", "search_queries"),
                                toolkit.getTool("web_search").getParameters().get("required"),
                                entry.name() + " must preserve the selected search provider");
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
                        assertEquals(payload, ((TextBlock) result.getOutput().get(0)).getText());
                    }
                }
            }
            assertTrue(methods.contains("initialize"));
            assertTrue(methods.contains("tools/list"));
            assertEquals(4, searchInputs.size());
            assertFalse(userAgents.isEmpty());
            assertTrue(
                    userAgents.stream()
                            .allMatch(
                                    (userAgent) ->
                                            userAgent.equals(
                                                    "agentscope-java/" + Version.VERSION)));
        } finally {
            server.stop(0);
        }
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
}
