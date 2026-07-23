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

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers MCP servers declared under {@code mcpServers} in {@code workspace/tools.json} into a
 * {@link Toolkit}.
 *
 * <p>Each entry is built into an {@link McpClientWrapper} via {@link McpClientBuilder} according
 * to its {@code transport} ({@code stdio} / {@code sse} / {@code http}) and then registered through
 * {@link Toolkit#registration()} so that per-server {@code enableTools} allowlists are honoured.
 *
 * <p>Failures during a single server's setup are caught and logged; remaining servers still
 * register so that one bad entry never aborts the agent's bootstrap.
 */
public final class McpServerRegistrar {

    private static final Logger log = LoggerFactory.getLogger(McpServerRegistrar.class);

    private McpServerRegistrar() {}

    /**
     * Registers every entry in {@code servers} into {@code toolkit}. Synchronous: each server is
     * built and registered before the next is attempted. {@code servers} may be {@code null} or
     * empty (no-op).
     */
    public static void register(Toolkit toolkit, Map<String, McpServerConfig> servers) {
        register(toolkit, servers, null);
    }

    /** Registers MCP servers with workspace policy available for stdio working directories. */
    public static void register(
            Toolkit toolkit,
            Map<String, McpServerConfig> servers,
            WorkspaceManager workspaceManager) {
        if (toolkit == null || servers == null || servers.isEmpty()) {
            return;
        }
        for (Map.Entry<String, McpServerConfig> entry : servers.entrySet()) {
            String name = entry.getKey();
            McpServerConfig cfg = entry.getValue();
            if (name == null || name.isBlank() || cfg == null) {
                log.warn("Skipping MCP server with blank name or null config.");
                continue;
            }
            try {
                registerOne(toolkit, name, cfg, workspaceManager);
            } catch (Exception e) {
                log.warn(
                        "Failed to register MCP server '{}' ({}): {}",
                        name,
                        cfg.getTransport(),
                        e.getMessage());
            }
        }
    }

    private static void registerOne(
            Toolkit toolkit, String name, McpServerConfig cfg, WorkspaceManager workspaceManager) {
        McpClientWrapper wrapper = buildClient(name, cfg, workspaceManager);
        Toolkit.ToolRegistration reg = toolkit.registration().mcpClient(wrapper);
        List<String> enableTools = cfg.getEnableTools();
        if (enableTools != null && !enableTools.isEmpty()) {
            reg.enableTools(enableTools);
        }
        reg.apply();
        log.info(
                "Registered MCP server '{}' (transport={}, enableTools={}).",
                name,
                cfg.getTransport(),
                enableTools);
    }

    private static McpClientWrapper buildClient(
            String name, McpServerConfig cfg, WorkspaceManager workspaceManager) {
        String transport = cfg.getTransport();
        if (transport == null || transport.isBlank()) {
            throw new IllegalArgumentException(
                    "MCP server '" + name + "' is missing required 'transport' field.");
        }
        McpClientBuilder builder = McpClientBuilder.create(name);
        switch (transport.toLowerCase(Locale.ROOT)) {
            case "stdio" -> configureStdio(builder, name, cfg, workspaceManager);
            case "sse" -> configureSse(builder, name, cfg);
            case "http", "streamable-http", "streamablehttp" ->
                    configureStreamableHttp(builder, name, cfg);
            default ->
                    throw new IllegalArgumentException(
                            "MCP server '"
                                    + name
                                    + "' has unsupported transport '"
                                    + transport
                                    + "' (expected: stdio, sse, http).");
        }
        if (cfg.getTimeout() != null) {
            builder.timeout(cfg.getTimeout());
        }
        if (cfg.getInitializationTimeout() != null) {
            builder.initializationTimeout(cfg.getInitializationTimeout());
        }
        return builder.buildAsync().block();
    }

    private static void configureStdio(
            McpClientBuilder builder,
            String name,
            McpServerConfig cfg,
            WorkspaceManager workspaceManager) {
        if (cfg.getCommand() == null || cfg.getCommand().isBlank()) {
            throw new IllegalArgumentException(
                    "stdio MCP server '" + name + "' requires a 'command'.");
        }
        List<String> args = cfg.getArgs() != null ? cfg.getArgs() : List.of();
        Map<String, String> env = cfg.getEnv() != null ? cfg.getEnv() : Map.of();
        Path workingDirectory = resolveStdioWorkingDirectory(workspaceManager, cfg.getCwd());
        if (workingDirectory == null) {
            builder.stdioTransport(cfg.getCommand(), args, env);
        } else {
            builder.stdioTransport(cfg.getCommand(), args, env, workingDirectory);
        }
    }

    static Path resolveStdioWorkingDirectory(
            WorkspaceManager workspaceManager, String configuredCwd) {
        if (configuredCwd == null || configuredCwd.isBlank()) {
            return null;
        }
        if (workspaceManager == null) {
            throw new IllegalArgumentException(
                    "stdio MCP cwd requires a Harness WorkspaceManager.");
        }
        Path relative = Path.of(configuredCwd);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("stdio MCP cwd must be workspace-relative.");
        }
        Path workspace = workspaceManager.getWorkspace().toAbsolutePath().normalize();
        Path resolved = workspace.resolve(relative).normalize();
        if (!resolved.startsWith(workspace)) {
            throw new IllegalArgumentException("stdio MCP cwd must stay inside the workspace.");
        }
        return resolved;
    }

    private static void configureSse(McpClientBuilder builder, String name, McpServerConfig cfg) {
        if (cfg.getUrl() == null || cfg.getUrl().isBlank()) {
            throw new IllegalArgumentException("sse MCP server '" + name + "' requires a 'url'.");
        }
        builder.sseTransport(cfg.getUrl());
        if (cfg.getHeaders() != null && !cfg.getHeaders().isEmpty()) {
            builder.headers(cfg.getHeaders());
        }
        if (cfg.getQueryParams() != null && !cfg.getQueryParams().isEmpty()) {
            builder.queryParams(cfg.getQueryParams());
        }
    }

    private static void configureStreamableHttp(
            McpClientBuilder builder, String name, McpServerConfig cfg) {
        if (cfg.getUrl() == null || cfg.getUrl().isBlank()) {
            throw new IllegalArgumentException("http MCP server '" + name + "' requires a 'url'.");
        }
        builder.streamableHttpTransport(cfg.getUrl());
        if (cfg.getHeaders() != null && !cfg.getHeaders().isEmpty()) {
            builder.headers(cfg.getHeaders());
        }
        if (cfg.getQueryParams() != null && !cfg.getQueryParams().isEmpty()) {
            builder.queryParams(cfg.getQueryParams());
        }
    }
}
