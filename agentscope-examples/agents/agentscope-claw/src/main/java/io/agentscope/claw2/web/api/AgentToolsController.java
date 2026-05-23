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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.web.catalog.AgentCatalogService;
import io.agentscope.claw2.web.catalog.UserAgentDefinitionStore;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Tools management endpoints for an agent. Surfaces three layers:
 *
 * <ul>
 *   <li>{@code GET /active} — live tool list, derived by building a transient {@link HarnessAgent}
 *       against the workspace and reading {@code Toolkit.getToolSchemas()}.
 *   <li>{@code GET /config} / {@code PUT /config} — read / overwrite {@code workspace/tools.json}.
 *       Comment-keys ({@code "// description"}) are not preserved across UI writes; users who want
 *       inline comments should hand-edit via the workspace tab.
 *   <li>{@code GET /catalog/builtins} and {@code GET /catalog/mcp-servers} — pickers for the UI.
 *       Built-ins are a fixed list mirroring what {@link HarnessAgent.Builder#build} registers;
 *       MCP servers come from {@code classpath:catalog/mcp-servers.json}.
 * </ul>
 */
@RestController
@RequestMapping("/api/agents/{agentId}/tools")
public class AgentToolsController {

    private static final Logger log = LoggerFactory.getLogger(AgentToolsController.class);

    /**
     * Canonical list of harness built-in tools. Mirrors the registrations performed in
     * {@code HarnessAgent.Builder.build()}. Used both for source attribution on {@code /active}
     * and as the {@code /catalog/builtins} response.
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

    private static final Set<String> BUILTIN_NAMES;

    static {
        BUILTIN_NAMES = new java.util.HashSet<>();
        for (BuiltinToolInfo b : BUILTIN_TOOLS) BUILTIN_NAMES.add(b.id());
    }

    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .enable(SerializationFeature.INDENT_OUTPUT);

    private final ClawBootstrap bootstrap;
    private final AgentCatalogService catalogService;
    private final List<McpCatalogEntry> mcpCatalog;

    public AgentToolsController(ClawBootstrap bootstrap, AgentCatalogService catalogService) {
        this.bootstrap = bootstrap;
        this.catalogService = catalogService;
        this.mcpCatalog = loadMcpCatalog();
    }

    // -----------------------------------------------------------------
    //  Live tool list
    // -----------------------------------------------------------------

    @GetMapping("/active")
    public Mono<ActiveToolsResponse> active(@PathVariable String agentId) {
        return Mono.fromCallable(
                () -> {
                    Path ws = resolveWorkspace(agentId);
                    return introspect(ws);
                });
    }

    private ActiveToolsResponse introspect(Path workspace) {
        List<String> warnings = new ArrayList<>();
        HarnessAgent agent = null;
        try {
            agent =
                    HarnessAgent.builder()
                            .name("__tools_introspect__")
                            .model(new NoopModel())
                            .workspace(workspace)
                            .abstractFilesystem(new LocalFilesystem(workspace))
                            .build();
        } catch (Exception e) {
            // Build can fail if MCP servers in tools.json are unreachable. Surface as a warning
            // and fall back to a config-only view so the UI can still render the workspace.
            log.warn("Transient agent build failed for {}: {}", workspace, e.getMessage());
            warnings.add(
                    "Live introspection failed ("
                            + e.getMessage()
                            + "). Showing config-only view.");
            return configOnlyView(workspace, warnings);
        }
        try {
            List<ToolSchema> schemas = agent.getDelegate().getToolkit().getToolSchemas();
            List<ActiveTool> tools = new ArrayList<>();
            for (ToolSchema s : schemas) {
                String source = BUILTIN_NAMES.contains(s.getName()) ? "built-in" : "mcp";
                tools.add(new ActiveTool(s.getName(), s.getDescription(), source));
            }
            return new ActiveToolsResponse(tools, warnings);
        } finally {
            // Best-effort close: HarnessAgent doesn't expose a close hook for the toolkit, so MCP
            // wrappers stay alive until GC. Acceptable for a single-shot introspect call.
        }
    }

    private ActiveToolsResponse configOnlyView(Path workspace, List<String> warnings) {
        ToolsConfig cfg = readConfig(workspace);
        List<ActiveTool> tools = new ArrayList<>();
        Set<String> deny =
                cfg != null && cfg.getDeny() != null
                        ? new java.util.HashSet<>(cfg.getDeny())
                        : Set.of();
        Set<String> allow =
                cfg != null && cfg.getAllow() != null && !cfg.getAllow().isEmpty()
                        ? new java.util.HashSet<>(cfg.getAllow())
                        : null;
        for (BuiltinToolInfo b : BUILTIN_TOOLS) {
            if (deny.contains(b.id())) continue;
            if (allow != null && !allow.contains(b.id())) continue;
            tools.add(new ActiveTool(b.id(), b.description(), "built-in"));
        }
        if (cfg != null && cfg.getMcpServers() != null) {
            for (Map.Entry<String, McpServerConfig> e : cfg.getMcpServers().entrySet()) {
                tools.add(
                        new ActiveTool(
                                e.getKey(),
                                "MCP server (" + e.getValue().getTransport() + ")",
                                "mcp"));
            }
        }
        return new ActiveToolsResponse(tools, warnings);
    }

    // -----------------------------------------------------------------
    //  tools.json read/write
    // -----------------------------------------------------------------

    @GetMapping("/config")
    public Mono<ToolsConfig> getConfig(@PathVariable String agentId) {
        return Mono.fromCallable(() -> readConfigOrEmpty(resolveWorkspace(agentId)));
    }

    @PutMapping("/config")
    public Mono<ToolsConfig> putConfig(
            @PathVariable String agentId, @RequestBody ToolsConfig body) {
        return Mono.fromCallable(
                () -> {
                    if (body == null) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "Request body is required");
                    }
                    validate(body);
                    WorkspaceContext ctx = resolveContext(agentId);
                    String json;
                    try {
                        json = MAPPER.writeValueAsString(body);
                    } catch (IOException e) {
                        throw new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                "Failed to serialize tools.json: " + e.getMessage());
                    }
                    ctx.manager().writeUtf8WorkspaceRelative("tools.json", json + "\n");
                    return body;
                });
    }

    private ToolsConfig readConfigOrEmpty(Path workspace) {
        ToolsConfig cfg = readConfig(workspace);
        return cfg != null ? cfg : new ToolsConfig();
    }

    private ToolsConfig readConfig(Path workspace) {
        try {
            WorkspaceManager wsm = new WorkspaceManager(workspace, new LocalFilesystem(workspace));
            String raw = wsm.readManagedWorkspaceFileUtf8("tools.json");
            if (raw == null || raw.isBlank()) return null;
            return MAPPER.readValue(raw, ToolsConfig.class);
        } catch (Exception e) {
            log.debug("tools.json missing or unreadable for {}: {}", workspace, e.getMessage());
            return null;
        }
    }

    private static void validate(ToolsConfig cfg) {
        if (cfg.getAllow() != null) {
            for (String n : cfg.getAllow()) {
                if (n == null || n.isBlank()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "allow contains a blank entry");
                }
            }
        }
        if (cfg.getDeny() != null) {
            for (String n : cfg.getDeny()) {
                if (n == null || n.isBlank()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "deny contains a blank entry");
                }
            }
        }
        if (cfg.getMcpServers() != null) {
            for (Map.Entry<String, McpServerConfig> e : cfg.getMcpServers().entrySet()) {
                String name = e.getKey();
                McpServerConfig s = e.getValue();
                if (name == null || name.isBlank()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "mcpServers contains a blank server name");
                }
                if (s == null) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "mcpServers." + name + " is null");
                }
                String t = s.getTransport();
                if (t == null || t.isBlank()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "mcpServers." + name + ": transport is required");
                }
                String tl = t.toLowerCase(Locale.ROOT);
                switch (tl) {
                    case "stdio" -> {
                        if (s.getCommand() == null || s.getCommand().isBlank()) {
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST,
                                    "mcpServers." + name + ": stdio requires 'command'");
                        }
                    }
                    case "sse", "http", "streamable-http", "streamablehttp" -> {
                        if (s.getUrl() == null || s.getUrl().isBlank()) {
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST,
                                    "mcpServers." + name + ": " + tl + " requires 'url'");
                        }
                    }
                    default ->
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST,
                                    "mcpServers."
                                            + name
                                            + ": unsupported transport '"
                                            + t
                                            + "' (expected stdio, sse, http)");
                }
            }
        }
    }

    // -----------------------------------------------------------------
    //  Catalogs
    // -----------------------------------------------------------------

    @GetMapping("/catalog/builtins")
    public Mono<List<BuiltinToolInfo>> catalogBuiltins(@PathVariable String agentId) {
        // agentId is unused; kept for URL symmetry with other endpoints (and to allow a future
        // per-agent built-in pruning if the harness ever exposes one).
        return Mono.just(BUILTIN_TOOLS);
    }

    @GetMapping("/catalog/mcp-servers")
    public Mono<List<McpCatalogEntry>> catalogMcpServers(@PathVariable String agentId) {
        return Mono.just(mcpCatalog);
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private Path resolveWorkspace(String agentId) {
        return resolveContext(agentId).workspace();
    }

    private WorkspaceContext resolveContext(String agentId) {
        if (catalogService.isBuiltin(agentId)) {
            Path ws = bootstrap.resolveWorkspace(agentId).normalize();
            return new WorkspaceContext(ws, newWorkspaceManager(ws));
        }
        UserAgentDefinitionStore.StoredEntry entry =
                catalogService
                        .findStoredEntry(agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));
        Path ws = customAgentWorkspace(entry);
        return new WorkspaceContext(ws, newWorkspaceManager(ws));
    }

    private Path customAgentWorkspace(UserAgentDefinitionStore.StoredEntry entry) {
        Path clawHome = bootstrap.clawHome();
        if (entry.workspacePath() != null && !entry.workspacePath().isBlank()) {
            Path p = Paths.get(entry.workspacePath());
            return p.isAbsolute() ? p.normalize() : clawHome.resolve(p).normalize();
        }
        return ClawBootstrap.defaultAgentWorkspace(clawHome, entry.id());
    }

    private static WorkspaceManager newWorkspaceManager(Path workspace) {
        return new WorkspaceManager(workspace, new LocalFilesystem(workspace));
    }

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
        } catch (Exception e) {
            log.warn("Failed to load catalog/mcp-servers.json: {}", e.getMessage());
            return List.of();
        }
    }

    // -----------------------------------------------------------------
    //  DTOs
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActiveTool(String name, String description, String source) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ActiveToolsResponse(List<ActiveTool> tools, List<String> warnings) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BuiltinToolInfo(String id, String description, String group) {}

    /**
     * Bundled MCP server template loaded from {@code classpath:catalog/mcp-servers.json}. Mirrors
     * {@link McpServerConfig} plus a small bit of UI metadata ({@code id}, {@code name},
     * {@code description}, {@code requiredEnv}, {@code docsUrl}).
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

    private record WorkspaceContext(Path workspace, WorkspaceManager manager) {}

    /**
     * Stub model used while building the transient introspection agent. {@code stream()} is never
     * invoked — we only need {@link io.agentscope.core.tool.Toolkit#getToolSchemas()}.
     */
    private static final class NoopModel implements Model {
        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.empty();
        }

        @Override
        public String getModelName() {
            return "noop";
        }
    }
}
