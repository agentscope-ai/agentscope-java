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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolsConfigLoaderTest {

    @TempDir Path workspace;

    @Test
    void load_returnsEmpty_whenFileMissing() {
        WorkspaceManager wsm = new WorkspaceManager(workspace);
        Optional<ToolsConfig> result = ToolsConfigLoader.load(wsm);
        assertTrue(result.isEmpty());
    }

    @Test
    void load_returnsEmpty_whenFileBlank() throws Exception {
        Files.writeString(workspace.resolve(WorkspaceConstants.TOOLS_JSON), "   \n  ");
        WorkspaceManager wsm = new WorkspaceManager(workspace);
        assertTrue(ToolsConfigLoader.load(wsm).isEmpty());
    }

    @Test
    void load_returnsEmpty_whenJsonMalformed() throws Exception {
        Files.writeString(workspace.resolve(WorkspaceConstants.TOOLS_JSON), "{not json");
        WorkspaceManager wsm = new WorkspaceManager(workspace);
        // Must NOT throw — falls back to default toolkit.
        assertTrue(ToolsConfigLoader.load(wsm).isEmpty());
    }

    @Test
    void load_parsesAllowAndDeny() throws Exception {
        String json =
                """
                {
                  "allow": ["read_file", "list_files"],
                  "deny":  ["execute"]
                }
                """;
        Files.writeString(workspace.resolve(WorkspaceConstants.TOOLS_JSON), json);
        ToolsConfig cfg = ToolsConfigLoader.load(new WorkspaceManager(workspace)).orElseThrow();
        assertEquals(2, cfg.getAllow().size());
        assertTrue(cfg.getAllow().contains("read_file"));
        assertEquals(1, cfg.getDeny().size());
        assertEquals("execute", cfg.getDeny().get(0));
    }

    @Test
    void load_toleratesCommentKeyEntries() throws Exception {
        String json =
                """
                {
                  "// description": "the keys with // are JSON-valid identifiers",
                  "// available":   ["read_file"],
                  "allow": ["read_file"],
                  "deny":  []
                }
                """;
        Files.writeString(workspace.resolve(WorkspaceConstants.TOOLS_JSON), json);
        ToolsConfig cfg = ToolsConfigLoader.load(new WorkspaceManager(workspace)).orElseThrow();
        assertEquals(1, cfg.getAllow().size());
    }

    @Test
    void load_parsesMcpServers_allTransports() throws Exception {
        String json =
                """
                {
                  "mcpServers": {
                    "fs": {
                      "transport": "stdio",
                      "command": "npx",
                      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/srv"],
                      "env": { "LOG_LEVEL": "info" },
                      "enableTools": ["read_file"],
                      "timeout": "PT30S",
                      "initializationTimeout": "PT15S"
                    },
                    "http": {
                      "transport": "http",
                      "url": "https://mcp.example.com/http",
                      "resumableStreams": false,
                      "openConnectionOnStartup": false,
                      "headers": { "X-Tenant": "acme" },
                      "queryParams": { "region": "us" }
                    },
                    "sse": {
                      "transport": "sse",
                      "url": "https://mcp.example.com/sse"
                    }
                  }
                }
                """;
        Files.writeString(workspace.resolve(WorkspaceConstants.TOOLS_JSON), json);
        ToolsConfig cfg = ToolsConfigLoader.load(new WorkspaceManager(workspace)).orElseThrow();
        assertNotNull(cfg.getMcpServers());
        assertEquals(3, cfg.getMcpServers().size());

        McpServerConfig fs = cfg.getMcpServers().get("fs");
        assertEquals("stdio", fs.getTransport());
        assertEquals("npx", fs.getCommand());
        assertEquals(3, fs.getArgs().size());
        assertEquals("info", fs.getEnv().get("LOG_LEVEL"));
        assertEquals(Duration.ofSeconds(30), fs.getTimeout());
        assertEquals(Duration.ofSeconds(15), fs.getInitializationTimeout());

        McpServerConfig http = cfg.getMcpServers().get("http");
        assertEquals("http", http.getTransport());
        assertEquals("https://mcp.example.com/http", http.getUrl());
        assertEquals(Boolean.FALSE, http.getResumableStreams());
        assertEquals(Boolean.FALSE, http.getOpenConnectionOnStartup());
        assertEquals("acme", http.getHeaders().get("X-Tenant"));
        assertEquals("us", http.getQueryParams().get("region"));

        McpServerConfig sse = cfg.getMcpServers().get("sse");
        assertEquals("sse", sse.getTransport());
        assertNull(sse.getHeaders());
    }

    @Test
    void substituteEnv_replacesKnownVar() {
        String homeKey = pickAnExistingEnvVar();
        String homeValue = System.getenv(homeKey);
        String result = ToolsConfigLoader.substituteEnv("token=${" + homeKey + "}");
        assertEquals("token=" + homeValue, result);
    }

    @Test
    void substituteEnv_unsetVarBecomesEmptyString() {
        String result =
                ToolsConfigLoader.substituteEnv("h=${THIS_VAR_SHOULD_NOT_EXIST_AT_ALL_4f9c2a8b}");
        assertEquals("h=", result);
    }

    @Test
    void substituteEnv_passesThroughUnchanged_whenNoPlaceholder() {
        String input = "{ \"allow\": [\"read_file\"] }";
        assertEquals(input, ToolsConfigLoader.substituteEnv(input));
    }

    @Test
    void load_substitutesEnvBeforeParse() throws Exception {
        String homeKey = pickAnExistingEnvVar();
        String homeValue = System.getenv(homeKey);
        String json =
                """
                { "allow": ["${%s}"] }
                """
                        .formatted(homeKey);
        Files.writeString(workspace.resolve(WorkspaceConstants.TOOLS_JSON), json);
        ToolsConfig cfg = ToolsConfigLoader.load(new WorkspaceManager(workspace)).orElseThrow();
        assertEquals(1, cfg.getAllow().size());
        assertEquals(homeValue, cfg.getAllow().get(0));
    }

    @Test
    void load_returnsEmpty_whenWsManagerNull() {
        assertTrue(ToolsConfigLoader.load(null).isEmpty());
    }

    /**
     * Returns the name of a JSON-safe environment variable that's reliably set in our test
     * environment. If none of the candidates is present, fail the test rather than
     * masking with a fallback.
     */
    private static String pickAnExistingEnvVar() {
        for (String candidate : new String[] {"PATH", "HOME", "USER", "USERNAME"}) {
            String value = System.getenv(candidate);
            if (value != null && !value.contains("\"") && !value.contains("\\")) {
                return candidate;
            }
        }
        assertFalse(true, "no JSON-safe environment variable found for test");
        return "PATH";
    }
}
