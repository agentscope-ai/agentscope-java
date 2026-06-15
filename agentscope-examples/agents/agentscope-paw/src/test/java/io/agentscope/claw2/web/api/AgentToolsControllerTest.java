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
package io.agentscope.claw2.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.web.catalog.AgentCatalogService;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link AgentToolsController}. Covers the parts that don't require a real model:
 * {@code /config} read/write/validate and {@code /catalog/*}. Live introspection ({@code /active})
 * is exercised only on the fallback path — building a real {@code HarnessAgent} requires a working
 * model and is out of scope for a unit test.
 */
class AgentToolsControllerTest {

    @TempDir Path tempDir;

    private static final String AGENT_ID = "demo";

    private Path workspace;
    private AgentToolsController controller;

    @BeforeEach
    void setUp() throws Exception {
        workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);

        ClawBootstrap bootstrap = mock(ClawBootstrap.class);
        when(bootstrap.resolveWorkspace(AGENT_ID)).thenReturn(workspace);
        when(bootstrap.clawHome()).thenReturn(tempDir);

        AgentCatalogService catalog = mock(AgentCatalogService.class);
        when(catalog.isBuiltin(AGENT_ID)).thenReturn(true);

        controller = new AgentToolsController(bootstrap, catalog);
    }

    // -----------------------------------------------------------------
    //  /config GET
    // -----------------------------------------------------------------

    @Test
    void getConfig_emptyWhenFileMissing() {
        ToolsConfig cfg = controller.getConfig(AGENT_ID).block();
        assertNotNull(cfg);
        // All fields null/empty when there's no file on disk.
        assertTrue(cfg.getAllow() == null || cfg.getAllow().isEmpty());
        assertTrue(cfg.getDeny() == null || cfg.getDeny().isEmpty());
        assertTrue(cfg.getMcpServers() == null || cfg.getMcpServers().isEmpty());
    }

    @Test
    void getConfig_roundTripsExistingFile() throws Exception {
        Files.writeString(
                workspace.resolve("tools.json"),
                "{\n  \"allow\": [\"read_file\"],\n  \"deny\": [\"execute\"]\n}\n",
                StandardCharsets.UTF_8);

        ToolsConfig cfg = controller.getConfig(AGENT_ID).block();
        assertNotNull(cfg);
        assertEquals(List.of("read_file"), cfg.getAllow());
        assertEquals(List.of("execute"), cfg.getDeny());
    }

    // -----------------------------------------------------------------
    //  /config PUT
    // -----------------------------------------------------------------

    @Test
    void putConfig_writesFileAndReturnsBody() throws Exception {
        ToolsConfig body = new ToolsConfig();
        body.setAllow(List.of("read_file", "list_files"));
        body.setDeny(List.of("execute"));

        ToolsConfig saved = controller.putConfig(AGENT_ID, body).block();
        assertNotNull(saved);
        assertEquals(List.of("read_file", "list_files"), saved.getAllow());

        String onDisk = Files.readString(workspace.resolve("tools.json"), StandardCharsets.UTF_8);
        assertTrue(onDisk.contains("\"allow\""));
        assertTrue(onDisk.contains("read_file"));
        assertTrue(onDisk.contains("execute"));
    }

    @Test
    void putConfig_withMcpServerRoundTrips() throws Exception {
        ToolsConfig body = new ToolsConfig();
        McpServerConfig server = new McpServerConfig();
        server.setTransport("sse");
        server.setUrl("https://example.test/sse");
        Map<String, McpServerConfig> map = new LinkedHashMap<>();
        map.put("example", server);
        body.setMcpServers(map);

        controller.putConfig(AGENT_ID, body).block();

        ToolsConfig reread = controller.getConfig(AGENT_ID).block();
        assertNotNull(reread);
        assertNotNull(reread.getMcpServers());
        assertTrue(reread.getMcpServers().containsKey("example"));
        assertEquals("sse", reread.getMcpServers().get("example").getTransport());
    }

    @Test
    void putConfig_rejectsNullBody() {
        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.putConfig(AGENT_ID, null).block());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void putConfig_rejectsBlankAllowEntry() {
        ToolsConfig body = new ToolsConfig();
        body.setAllow(List.of(" "));
        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.putConfig(AGENT_ID, body).block());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void putConfig_rejectsStdioMcpWithoutCommand() {
        ToolsConfig body = new ToolsConfig();
        McpServerConfig server = new McpServerConfig();
        server.setTransport("stdio");
        body.setMcpServers(Map.of("bad", server));

        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.putConfig(AGENT_ID, body).block());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason() != null && ex.getReason().contains("command"));
    }

    @Test
    void putConfig_rejectsSseMcpWithoutUrl() {
        ToolsConfig body = new ToolsConfig();
        McpServerConfig server = new McpServerConfig();
        server.setTransport("sse");
        body.setMcpServers(Map.of("bad", server));

        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.putConfig(AGENT_ID, body).block());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason() != null && ex.getReason().contains("url"));
    }

    @Test
    void putConfig_rejectsUnknownTransport() {
        ToolsConfig body = new ToolsConfig();
        McpServerConfig server = new McpServerConfig();
        server.setTransport("carrier-pigeon");
        body.setMcpServers(Map.of("bad", server));

        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.putConfig(AGENT_ID, body).block());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void putConfig_acceptsHttpStreamableTransport() {
        ToolsConfig body = new ToolsConfig();
        McpServerConfig server = new McpServerConfig();
        server.setTransport("streamable-http");
        server.setUrl("https://example.test/mcp");
        body.setMcpServers(Map.of("ok", server));

        // Should not throw.
        ToolsConfig saved = controller.putConfig(AGENT_ID, body).block();
        assertNotNull(saved);
    }

    // -----------------------------------------------------------------
    //  /catalog/builtins
    // -----------------------------------------------------------------

    @Test
    void catalogBuiltins_returnsCanonicalList() {
        List<AgentToolsController.BuiltinToolInfo> list =
                controller.catalogBuiltins(AGENT_ID).block();
        assertNotNull(list);
        assertFalse(list.isEmpty(), "built-in catalog must not be empty");
        // Sanity check a couple of well-known entries.
        boolean hasReadFile = list.stream().anyMatch(b -> "read_file".equals(b.id()));
        boolean hasExecute = list.stream().anyMatch(b -> "execute".equals(b.id()));
        assertTrue(hasReadFile, "expected read_file in built-in catalog");
        assertTrue(hasExecute, "expected execute in built-in catalog");
    }

    // -----------------------------------------------------------------
    //  /catalog/mcp-servers
    // -----------------------------------------------------------------

    @Test
    void catalogMcpServers_returnsBundledEntries() {
        List<AgentToolsController.McpCatalogEntry> list =
                controller.catalogMcpServers(AGENT_ID).block();
        assertNotNull(list);
        // The bundled catalog has the Chinese-market entries; we don't pin the count to keep this
        // test forward-compatible, but at least one entry with a non-blank id must be present.
        assertFalse(list.isEmpty(), "MCP catalog must load from classpath");
        for (AgentToolsController.McpCatalogEntry e : list) {
            assertNotNull(e.id(), "catalog entry id must be set");
            assertNotNull(e.transport(), "catalog entry transport must be set");
        }
    }

    // -----------------------------------------------------------------
    //  /active
    // -----------------------------------------------------------------

    @Test
    void active_excludesDeniedBuiltins() throws Exception {
        // No mcpServers in the fixture so introspection completes quickly — what we're actually
        // asserting on is the deny list filtering, not MCP behavior.
        Files.writeString(
                workspace.resolve("tools.json"),
                "{\n  \"deny\": [\"execute\"]\n}\n",
                StandardCharsets.UTF_8);

        AgentToolsController.ActiveToolsResponse resp = controller.active(AGENT_ID).block();
        assertNotNull(resp);
        assertNotNull(resp.tools());
        boolean hasExecute =
                resp.tools().stream()
                        .anyMatch(t -> "execute".equals(t.name()) && "built-in".equals(t.source()));
        assertFalse(hasExecute, "denied built-in must not be reported as active");
    }
}
