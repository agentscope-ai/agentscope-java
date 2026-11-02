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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes the {@code mcpServers} shape used by {@code mcp.json} and by the per-scope
 * runtime override files under {@code mcp/}.
 *
 * <p>Same schema as {@code tools.json#mcpServers}, so one entry can be moved between the baseline
 * file and a runtime override unchanged. {@code ${ENV_VAR}} substitution applies on read, exactly
 * like {@code tools.json}, so secrets stay out of the workspace.
 *
 * <p>Never throws on unreadable or unparseable input: a workspace that cannot be read at build
 * time (sandbox / remote) must not prevent the agent from building.
 */
final class McpConfigFile {

    private static final Logger log = LoggerFactory.getLogger(McpConfigFile.class);

    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private static final String MCP_SERVERS = "mcpServers";

    private McpConfigFile() {}

    /** Parsed file shape; unknown keys are ignored so the file can carry comments-like extras. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class Document {
        @JsonProperty(MCP_SERVERS)
        private Map<String, McpServerConfig> mcpServers;
    }

    /**
     * Loads {@code relativePath} from the workspace. Returns an empty map when the file is
     * missing, blank, unreadable, or carries no {@code mcpServers} entries.
     */
    static Map<String, McpServerConfig> load(WorkspaceManager workspace, String relativePath) {
        if (workspace == null || relativePath == null || relativePath.isBlank()) {
            return Map.of();
        }
        String raw;
        try {
            raw = workspace.readManagedWorkspaceFileUtf8(RuntimeContext.empty(), relativePath);
        } catch (Exception e) {
            log.debug(
                    "Could not read {} via workspace manager ({}); skipping.",
                    relativePath,
                    e.getMessage());
            return Map.of();
        }
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Document document =
                    MAPPER.readValue(ToolsConfigLoader.substituteEnv(raw), Document.class);
            if (document == null || document.mcpServers == null) {
                return Map.of();
            }
            Map<String, McpServerConfig> servers = new LinkedHashMap<>();
            document.mcpServers.forEach(
                    (name, config) -> {
                        if (name != null && !name.isBlank() && config != null) {
                            servers.put(name, config);
                        }
                    });
            return servers;
        } catch (Exception e) {
            log.warn("Failed to parse {}: {}. Ignoring it.", relativePath, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Loads the declarative workspace baseline at {@code mcp.json}. The result is merged with
     * {@code tools.json#mcpServers} at build time; entries declared here win on name clashes.
     */
    static Map<String, McpServerConfig> loadDeclarative(WorkspaceManager workspace) {
        return load(workspace, McpToolManager.MCP_JSON);
    }

    /** Serializes a server map into the {@code mcpServers} shape, stable in declaration order. */
    static String toJson(Map<String, McpServerConfig> servers) {
        Document document = new Document();
        document.mcpServers = servers == null ? Map.of() : new LinkedHashMap<>(servers);
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(document);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize MCP configuration", e);
        }
    }
}
