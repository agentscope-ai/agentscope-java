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
package io.agentscope.core.tool.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Mono;

/**
 * Abstract wrapper for MCP (Model Context Protocol) clients.
 * This class manages the lifecycle of MCP client connections and provides
 * a unified interface for both asynchronous and synchronous client implementations.
 *
 * <p>The wrapper handles:
 * <ul>
 *   <li>Client initialization and connection management</li>
 *   <li>Tool discovery and caching</li>
 *   <li>Tool invocation through the MCP protocol</li>
 *   <li>Resource cleanup on close</li>
 * </ul>
 *
 * @see McpAsyncClientWrapper
 * @see McpSyncClientWrapper
 */
public abstract class McpClientWrapper implements AutoCloseable {

    /** Unique identifier for this MCP client */
    protected final String name;

    /** Cache of tools available from this MCP server */
    protected final Map<String, McpSchema.Tool> cachedTools;

    /** Flag indicating whether the client has been initialized */
    protected volatile boolean initialized = false;

    /**
     * Whether request metadata (user {@link McpMeta} entries and the framework tool-call id) is
     * propagated to the MCP server behind this connection.
     *
     * <p>Defaults to {@code true} for backward compatibility. This is a live switch: it is read
     * on every tool call, so changing it takes effect immediately for all tools of this
     * connection, including ones registered earlier. Set it to {@code false} for MCP servers that
     * are not fully trusted, so that internal metadata such as trace ids or callback URLs is
     * never sent over the wire.
     */
    protected volatile boolean propagateMeta = true;

    /**
     * Constructs a new MCP client wrapper.
     *
     * @param name unique identifier for this client
     */
    protected McpClientWrapper(String name) {
        this.name = name;
        this.cachedTools = new ConcurrentHashMap<>();
    }

    /**
     * Gets the unique name of this MCP client.
     *
     * @return the client name
     */
    public String getName() {
        return name;
    }

    /**
     * Checks if this client has been initialized.
     *
     * @return true if initialized, false otherwise
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Checks whether request metadata is propagated to this MCP server.
     *
     * <p>This is the connection-level half of the decision: every tool call ANDs this value with
     * the per-tool {@code McpTool.isPropagateMeta()} restriction, so {@code false} here always
     * wins and stops metadata for the whole connection.
     *
     * @return true if metadata ({@link McpMeta} entries and the tool-call id) may be included in
     *     tool call requests over this connection, false otherwise
     */
    public boolean isPropagateMeta() {
        return propagateMeta;
    }

    /**
     * Configures whether request metadata is propagated to this MCP server.
     *
     * <p>This is a live switch: it takes effect immediately for every tool registered from this
     * client, including tools registered before the call. Disable it for MCP servers that are
     * not fully trusted to avoid leaking internal metadata over the wire.
     *
     * @param propagateMeta true to propagate metadata (default), false to omit the {@code meta}
     *     field entirely from tool call requests
     */
    public void setPropagateMeta(boolean propagateMeta) {
        this.propagateMeta = propagateMeta;
    }

    /**
     * Initializes the MCP client connection and caches available tools.
     * This method must be called before any tool operations.
     *
     * @return a Mono that completes when initialization is finished
     */
    public abstract Mono<Void> initialize();

    /**
     * Lists all tools available from this MCP server.
     *
     * @return a Mono emitting the list of available tools
     */
    public abstract Mono<List<McpSchema.Tool>> listTools();

    /**
     * Invokes a tool on the MCP server.
     *
     * @param toolName the name of the tool to call
     * @param arguments the arguments to pass to the tool
     * @return a Mono emitting the tool call result
     */
    public abstract Mono<McpSchema.CallToolResult> callTool(
            String toolName, Map<String, Object> arguments);

    /**
     * Invokes a tool on the MCP server.
     *
     * @param toolName the name of the tool to call
     * @param arguments the arguments to pass to the tool
     * @param meta the metadata to pass to the tool
     * @return a Mono emitting the tool call result
     */
    public abstract Mono<McpSchema.CallToolResult> callTool(
            String toolName, Map<String, Object> arguments, Map<String, Object> meta);

    /**
     * Gets a cached tool definition by name.
     *
     * @param toolName the name of the tool
     * @return the tool definition, or null if not found
     */
    public McpSchema.Tool getCachedTool(String toolName) {
        return cachedTools.get(toolName);
    }

    /**
     * Closes this MCP client and releases all resources.
     * This method is idempotent and can be called multiple times safely.
     */
    @Override
    public abstract void close();
}
