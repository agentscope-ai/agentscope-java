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
package io.agentscope.builder.web.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.builder.web.share.AgentAccessGuard;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Read-only tool discovery endpoints for an agent under the platform's multi-tenant guardrails.
 * Every entrypoint is gated by {@link AgentAccessGuard} so cross-user access fails with 404.
 *
 * <p>Definition-level tool configuration ({@code tools[]}, {@code mcpServers[]}) now lives on the
 * Agent body itself (see {@code docs/API_REFACTOR.md}); the former {@code GET/PUT .../tools/config}
 * and {@code GET .../tools/active} endpoints have been removed in favor of {@code GET/PUT
 * /api/agents/{id}}.
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>{@code GET /catalog/builtins} — static list mirroring harness built-ins (RUN).
 *   <li>{@code GET /catalog/mcp-servers} — bundled MCP server templates from
 *       {@code classpath:catalog/mcp-servers.json} (RUN).
 * </ul>
 */
@RestController
@RequestMapping("/api/agents/{agentId}/tools")
public class AgentToolsController {

    private static final Logger log = LoggerFactory.getLogger(AgentToolsController.class);

    /**
     * Canonical list of harness built-in tools. Mirrors registrations performed in
     * {@code HarnessAgent.Builder.build()}; served as the {@code /catalog/builtins} response.
     */
    private static final List<BuiltinToolInfo> BUILTIN_TOOLS =
            List.of(
                    new BuiltinToolInfo(
                            "read_file", "Read a file from the workspace.", "filesystem"),
                    new BuiltinToolInfo(
                            "write_file",
                            "Write or overwrite a file in the workspace.",
                            "filesystem"),
                    new BuiltinToolInfo(
                            "edit_file", "Apply a diff to an existing file.", "filesystem"),
                    new BuiltinToolInfo(
                            "list_files", "List files under a directory.", "filesystem"),
                    new BuiltinToolInfo(
                            "grep_files", "Search file contents with a regex.", "filesystem"),
                    new BuiltinToolInfo(
                            "glob_files", "Find files matching a glob pattern.", "filesystem"),
                    new BuiltinToolInfo(
                            "memory_search",
                            "Semantic search across the agent's long-term memory.",
                            "memory"),
                    new BuiltinToolInfo(
                            "memory_get", "Fetch a specific memory entry by id.", "memory"),
                    new BuiltinToolInfo(
                            "session_search",
                            "Search prior session transcripts for context.",
                            "memory"),
                    new BuiltinToolInfo(
                            "execute",
                            "Execute a shell command (sandbox / local-shell modes only).",
                            "shell"));

    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .enable(SerializationFeature.INDENT_OUTPUT);

    private final AgentAccessGuard guard;
    private final List<McpCatalogEntry> mcpCatalog;

    public AgentToolsController(AgentAccessGuard guard) {
        this.guard = guard;
        this.mcpCatalog = loadMcpCatalog();
    }

    // -----------------------------------------------------------------
    //  Catalogs
    // -----------------------------------------------------------------

    @GetMapping("/catalog/builtins")
    public Mono<List<BuiltinToolInfo>> catalogBuiltins(
            @PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.RUN);
                    return BUILTIN_TOOLS;
                });
    }

    @GetMapping("/catalog/mcp-servers")
    public Mono<List<McpCatalogEntry>> catalogMcpServers(
            @PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.RUN);
                    return mcpCatalog;
                });
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private static List<McpCatalogEntry> loadMcpCatalog() {
        ClassPathResource r = new ClassPathResource("catalog/mcp-servers.json");
        if (!r.exists()) {
            log.warn("catalog/mcp-servers.json not found on classpath; MCP catalog is empty.");
            return List.of();
        }
        try (InputStream in = r.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            String json = new String(bytes, StandardCharsets.UTF_8);
            McpCatalogEntry[] arr = MAPPER.readValue(json, McpCatalogEntry[].class);
            return List.of(arr);
        } catch (IOException e) {
            log.warn("Failed to load catalog/mcp-servers.json: {}", e.getMessage());
            return List.of();
        }
    }

    // -----------------------------------------------------------------
    //  DTOs
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BuiltinToolInfo(String id, String description, String group) {}

    /**
     * Bundled MCP server template loaded from {@code classpath:catalog/mcp-servers.json}. Mirrors
     * {@code McpServerConfig} plus a small bit of UI metadata.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record McpCatalogEntry(
            String id,
            String name,
            String description,
            String transport,
            String url,
            String command,
            List<String> args,
            Map<String, String> env,
            Map<String, String> headers,
            Map<String, String> queryParams,
            List<String> requiredEnv,
            String docsUrl) {}
}
