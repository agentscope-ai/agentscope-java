/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* Copyright 2024-2026 the original author or authors. Licensed under Apache License, Version 2.0. */
package io.agentscope.extensions.aistio.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HarnessWorkspaceAgentFactoryTest {
    private final ObjectMapper json = new ObjectMapper();
    @TempDir Path root;

    @Test
    void materializesDefinitionAndRejectsPrivateStateAndTraversal() throws Exception {
        HarnessWorkspaceAgentFactory.materialize(
                root,
                json.readTree(
                        "{\"AGENTS.md\":\"instructions\",\"skills/review/SKILL.md\":\"review\"}"));
        assertEquals("review", Files.readString(root.resolve("skills/review/SKILL.md")));
        for (String path :
                new String[] {
                    "../escape",
                    "/tmp/escape",
                    "sessions/log",
                    "memory/private",
                    "MEMORY.md",
                    "logs/run",
                    "outputs/result"
                }) {
            var files = json.createObjectNode().put(path, "private");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> HarnessWorkspaceAgentFactory.materialize(root, files),
                    path);
        }
    }

    @Test
    void rejectsSymlinkDestinations() throws Exception {
        Path outside = Files.createTempDirectory("workspace-outside-");
        try {
            Files.createSymbolicLink(root.resolve("reference"), outside);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            HarnessWorkspaceAgentFactory.materialize(
                                    root, json.readTree("{\"reference/secret\":\"overwrite\"}")));
            assertFalse(Files.exists(outside.resolve("secret")));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void appliesToolSelectionMcpAllowlistAndPermissions() throws Exception {
        var definition =
                json.readTree(
                        """
                        {"mcpServers":[{"name":"repo","command":"server","enableTools":["read"]}],
                         "tools":[{"type":"agent_toolset","defaultConfig":{"enabled":false},"configs":[{"name":"read","enabled":true,"permissionPolicy":{"type":"always_ask"}},{"name":"bash","enabled":false,"permissionPolicy":{"type":"deny"}}]},
                                  {"type":"mcp_toolset","mcpServerName":"repo","defaultConfig":{"enabled":false},"configs":[{"name":"read","enabled":true},{"name":"write","enabled":true}]}]}
                        """);
        var tools = HarnessWorkspaceAgentFactory.tools(definition);
        assertFalse(tools.isDefaultToolsEnabled());
        assertTrue(tools.getAllow().contains("read_file"));
        assertTrue(tools.getDeny().contains("execute"));
        assertEquals(java.util.List.of("read"), tools.getMcpServers().get("repo").getEnableTools());
        var permissions = HarnessWorkspaceAgentFactory.permissions(definition);
        assertTrue(permissions.getDenyRules().containsKey("execute"));
        assertTrue(permissions.getAskRules().containsKey("read_file"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HarnessWorkspaceAgentFactory.tools(
                                json.readTree("{\"tools\":[{\"type\":\"unknown\"}]}")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HarnessWorkspaceAgentFactory.tools(
                                json.readTree("{\"mcpServers\":[{\"name\":\"bad__name\"}]}")));
    }
}
