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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

class McpToolManagerTest {

    private static final String BASELINE_TOOL = "baselineTool";

    private static class BaselineTool {
        @Tool(description = "baseline")
        public String baselineTool(String text) {
            return text;
        }
    }

    /** In-memory MCP server: advertises one tool per server name and counts connections. */
    private static final class FakeClient extends McpClientWrapper {
        private final List<McpSchema.Tool> tools;
        private final AtomicInteger initializations = new AtomicInteger();
        private volatile boolean closed;

        private FakeClient(String name, String toolName) {
            super(name);
            this.tools =
                    List.of(
                            McpSchema.Tool.builder(toolName, Map.of("type", "object"))
                                    .description("fake")
                                    .build());
        }

        @Override
        public Mono<Void> initialize() {
            initializations.incrementAndGet();
            return Mono.empty();
        }

        @Override
        public Mono<List<McpSchema.Tool>> listTools() {
            return Mono.just(tools);
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(
                String toolName, Map<String, Object> arguments) {
            return Mono.just(
                    McpSchema.CallToolResult.builder().content(List.of()).isError(false).build());
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(
                String toolName, Map<String, Object> arguments, Map<String, Object> meta) {
            return callTool(toolName, arguments);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private final Map<String, FakeClient> clients = new java.util.LinkedHashMap<>();

    private McpToolManager manager(
            Toolkit baseline, Map<String, McpServerConfig> baselineServers, WorkspaceManager ws) {
        Function<String, McpClientWrapper> factory =
                name -> {
                    FakeClient client = new FakeClient(name, name + "Tool");
                    clients.put(name, client);
                    return client;
                };
        return McpToolManager.create(
                baseline, baselineServers, ws, null, (name, config) -> factory.apply(name));
    }

    private static McpServerConfig config() {
        McpServerConfig config = new McpServerConfig();
        config.setTransport("stdio");
        config.setCommand("fake");
        return config;
    }

    private static RuntimeContext user(String userId) {
        return RuntimeContext.builder().userId(userId).sessionId("s").build();
    }

    private static Toolkit baselineToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new BaselineTool());
        return toolkit;
    }

    @Test
    void contextsWithoutRuntimeServersShareTheBaselineToolkit() {
        Toolkit baseline = baselineToolkit();
        try (McpToolManager manager = manager(baseline, Map.of(), null)) {
            assertSame(baseline, manager.toolkitFor(user("alice")));
            assertSame(baseline, manager.toolkitFor(RuntimeContext.empty()));
        }
        assertTrue(baseline.getToolNames().contains(BASELINE_TOOL));
    }

    @Test
    void runtimeServersAreInvisibleToOtherContextsAndToTheBaseline() {
        Toolkit baseline = baselineToolkit();
        try (McpToolManager manager = manager(baseline, Map.of(), null)) {
            manager.register(user("alice"), "alice-mcp", config());

            Toolkit alice = manager.toolkitFor(user("alice"));
            assertNotSame(baseline, alice);
            assertTrue(alice.getToolNames().contains("alice-mcpTool"));
            assertTrue(alice.getToolNames().contains(BASELINE_TOOL));

            assertSame(baseline, manager.toolkitFor(user("bob")));
            assertFalse(baseline.getToolNames().contains("alice-mcpTool"));
            assertEquals(Set.of("alice-mcp"), manager.servers(user("alice")).keySet());
            assertTrue(manager.servers(user("bob")).isEmpty());
        }
    }

    @Test
    void unregisterRemovesTheServerToolAndClearsTheScopeToolkit() {
        Toolkit baseline = baselineToolkit();
        try (McpToolManager manager = manager(baseline, Map.of(), null)) {
            manager.register(user("alice"), "alice-mcp", config());
            Toolkit scoped = manager.toolkitFor(user("alice"));

            assertTrue(manager.unregister(user("alice"), "alice-mcp"));
            assertFalse(manager.unregister(user("alice"), "alice-mcp"));
            assertFalse(scoped.getToolNames().contains("alice-mcpTool"));
            assertSame(baseline, manager.toolkitFor(user("alice")));
            assertTrue(clients.get("alice-mcp").closed);
        }
    }

    @Test
    void reloadReconnectsTheServerWithoutRebuildingTheAgent() {
        Toolkit baseline = baselineToolkit();
        try (McpToolManager manager = manager(baseline, Map.of(), null)) {
            manager.register(user("alice"), "alice-mcp", config());
            FakeClient first = clients.get("alice-mcp");
            assertTrue(first.initializations.get() > 0);

            manager.reload(user("alice"), "alice-mcp");

            FakeClient second = clients.get("alice-mcp");
            assertNotSame(first, second);
            assertTrue(first.closed);
            assertTrue(second.initializations.get() > 0);
            assertTrue(manager.toolkitFor(user("alice")).getToolNames().contains("alice-mcpTool"));
        }
    }

    @Test
    void reloadAllReportsTheNumberOfServers() {
        Toolkit baseline = baselineToolkit();
        try (McpToolManager manager = manager(baseline, Map.of(), null)) {
            manager.register(user("alice"), "one", config());
            manager.register(user("alice"), "two", config());
            assertEquals(2, manager.reloadAll(user("alice")));
            assertEquals(0, manager.reloadAll(user("bob")));
        }
    }

    @Test
    void baselineServersCannotBeReplacedPerContext() {
        Toolkit baseline = baselineToolkit();
        try (McpToolManager manager = manager(baseline, Map.of("base", config()), null)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.register(user("alice"), "base", config()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.unregister(user("alice"), "base"));
            assertThrows(IllegalStateException.class, () -> manager.reload(user("alice"), "base"));
        }
    }

    @Test
    void registerRejectsBlankNamesAndUnknownReloads() {
        try (McpToolManager manager = manager(baselineToolkit(), Map.of(), null)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.register(user("alice"), " ", config()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.register(user("alice"), "srv", null));
            assertThrows(IllegalStateException.class, () -> manager.reload(user("alice"), "srv"));
        }
    }

    @Test
    void runtimeServersArePersistedPerScopeAndRestoredByAFreshManager(@TempDir Path workspace)
            throws Exception {
        WorkspaceManager wsm = new WorkspaceManager(workspace);
        McpServerConfig serverConfig = config();
        serverConfig.setCommand("fake-command");

        try (McpToolManager manager = manager(baselineToolkit(), Map.of(), wsm)) {
            manager.register(user("alice"), "alice-mcp", serverConfig);
        }

        Path persisted = workspace.resolve(McpToolManager.MCP_DIR).resolve("user-alice.json");
        assertTrue(Files.exists(persisted));
        String json = Files.readString(persisted);
        assertTrue(json.contains("alice-mcp"));
        assertTrue(json.contains("fake-command"));

        Toolkit baseline = baselineToolkit();
        try (McpToolManager restored = manager(baseline, Map.of(), wsm)) {
            Toolkit alice = restored.toolkitFor(user("alice"));
            assertTrue(alice.getToolNames().contains("alice-mcpTool"));
            assertSame(baseline, restored.toolkitFor(user("bob")));
        }
    }

    @Test
    void persistedServerShadowingABaselineNameIsIgnored(@TempDir Path workspace) throws Exception {
        Path dir = Files.createDirectories(workspace.resolve(McpToolManager.MCP_DIR));
        Files.writeString(
                dir.resolve("user-alice.json"),
                "{\"mcpServers\":{\"base\":{\"transport\":\"stdio\",\"command\":\"fake\"}}}");
        WorkspaceManager wsm = new WorkspaceManager(workspace);

        Toolkit baseline = baselineToolkit();
        try (McpToolManager manager = manager(baseline, Map.of("base", config()), wsm)) {
            assertSame(baseline, manager.toolkitFor(user("alice")));
            assertTrue(manager.servers(user("alice")).containsKey("base"));
        }
    }

    @Test
    void closeReleasesScopeClientsAndRejectsFurtherMutations() {
        Toolkit baseline = baselineToolkit();
        McpToolManager manager = manager(baseline, Map.of(), null);
        manager.register(user("alice"), "alice-mcp", config());

        manager.close();

        assertTrue(clients.get("alice-mcp").closed);
        assertThrows(
                IllegalStateException.class,
                () -> manager.register(user("alice"), "other", config()));
        assertTrue(baseline.getToolNames().contains(BASELINE_TOOL));
    }
}
