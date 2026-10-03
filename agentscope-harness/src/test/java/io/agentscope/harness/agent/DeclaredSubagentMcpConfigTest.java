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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import reactor.core.publisher.Mono;

/** Pins the server connections selected before a declared child's tool allowlist is applied. */
class DeclaredSubagentMcpConfigTest {

    @TempDir Path workspace;

    @ParameterizedTest
    @ValueSource(strings = {"workspace", "override", "empty-override"})
    void declaredToolsPreserveEffectiveMcpServerConfiguration(String source) throws Exception {
        Path childWorkspace = workspace.resolve("child");
        Files.createDirectories(childWorkspace);
        Files.writeString(
                childWorkspace.resolve("tools.json"),
                """
                {
                  "deny": ["workspace_delete"],
                  "mcpServers": {
                    "workspace-server": {"transport": "stdio", "command": "workspace-command", "required": true}
                  }
                }
                """);
        HarnessAgent.Builder parent =
                HarnessAgent.builder()
                        .name("parent")
                        .model(new MockModel("done"))
                        .workspace(workspace)
                        .disableDefaultWorkspaceSkills()
                        .disableSessionPersistence()
                        .subagent(
                                SubagentDeclaration.builder()
                                        .name("helper")
                                        .description("MCP config regression")
                                        .workspace(childWorkspace)
                                        .tools(
                                                List.of(
                                                        "workspace_query",
                                                        "workspace_delete",
                                                        "override_query"))
                                        .build());
        if (!"workspace".equals(source)) {
            ToolsConfig override = new ToolsConfig();
            if ("override".equals(source)) {
                McpServerConfig server = new McpServerConfig();
                server.setTransport("stdio");
                server.setCommand("override-command");
                server.setRequired(true);
                override.setMcpServers(Map.of("override-server", server));
            }
            parent.toolsConfig(override);
        }
        SubagentEntry entry =
                parent.buildSubagentEntries(workspace).stream()
                        .filter(candidate -> "helper".equals(candidate.name()))
                        .findFirst()
                        .orElseThrow();
        List<String> connectedServers = new ArrayList<>();
        List<McpClientWrapper> clients = new ArrayList<>();
        // Keep workspace loading, server registration, tool selection and cleanup real. Only the
        // transport client is mocked, so this never starts a process or reaches an external server.
        try (MockedStatic<McpClientBuilder> builders = mockStatic(McpClientBuilder.class)) {
            for (String configSource : List.of("workspace", "override")) {
                String serverName = configSource + "-server";
                McpClientBuilder builder = mock(McpClientBuilder.class);
                builders.when(() -> McpClientBuilder.create(serverName))
                        .thenAnswer(
                                ignored -> {
                                    connectedServers.add(serverName);
                                    return builder;
                                });
                McpClientWrapper client = mock(McpClientWrapper.class);
                when(client.getName()).thenReturn(serverName);
                when(client.initialize()).thenReturn(Mono.empty());
                List<McpSchema.Tool> tools = new ArrayList<>();
                for (String action : List.of("query", "delete")) {
                    McpSchema.Tool tool = mock(McpSchema.Tool.class);
                    when(tool.name()).thenReturn(configSource + "_" + action);
                    tools.add(tool);
                }
                when(client.listTools()).thenReturn(Mono.just(tools));
                when(builder.buildAsync())
                        .thenAnswer(
                                ignored -> {
                                    clients.add(client);
                                    return Mono.just(client);
                                });
            }
            try (HarnessAgent child =
                    (HarnessAgent) entry.factory().create(RuntimeContext.empty())) {
                assertEquals(
                        "empty-override".equals(source) ? List.of() : List.of(source + "-server"),
                        connectedServers);
                var toolkit = child.getDelegate().getToolkit();
                for (String configSource : List.of("workspace", "override")) {
                    if (configSource.equals(source)) {
                        assertNotNull(toolkit.getTool(configSource + "_query"));
                    } else {
                        assertNull(toolkit.getTool(configSource + "_query"));
                    }
                    // Workspace deny excludes the allowlisted workspace_delete; the declaration
                    // excludes override_delete. Both policies still apply after MCP registration.
                    assertNull(toolkit.getTool(configSource + "_delete"));
                }
            }
            for (McpClientWrapper client : clients) {
                verify(client, atLeastOnce()).initialize();
                verify(client, atLeastOnce()).listTools();
                verify(client).close();
            }
        }
    }
}
