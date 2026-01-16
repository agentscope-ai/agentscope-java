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

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Wrapper for asynchronous MCP clients using Project Reactor. This implementation delegates to
 * {@link McpAsyncClient} and provides reactive operations that return Mono types.
 *
 * <p>Example usage: <pre>{@code
 * McpAsyncClient client = ... // created via McpClient.async()
 * McpAsyncClientWrapper wrapper = new McpAsyncClientWrapper("my-mcp", client);
 * wrapper.initialize()
 *     .then(wrapper.callTool("tool_name", Map.of("arg1", "value1")))
 *     .subscribe(result -> System.out.println(result));
 * }</pre>
 */
public class McpAsyncClientWrapper extends McpClientWrapper {

    private static final Logger logger = LoggerFactory.getLogger(McpAsyncClientWrapper.class);

    private final AtomicReference<McpAsyncClient> clientRef;

    /**
     * Constructs a new asynchronous MCP client wrapper.
     *
     * @param name unique identifier for this client
     * @param client the underlying async MCP client
     */
    public McpAsyncClientWrapper(String name, McpAsyncClient client) {
        super(name);
        this.clientRef = new AtomicReference<>(client);
    }

    /**
     * Sets the underlying MCP async client. This is called by McpClientBuilder after the client
     * is created with notification handlers.
     *
     * @param client the MCP async client
     */
    void setClient(McpAsyncClient client) {
        this.clientRef.set(client);
    }

    /**
     * Updates the cached tools map with new tools from the server. This method is called when the
     * server sends a tools/list_changed notification. This method is thread-safe and can be
     * called concurrently from notification handlers.
     *
     * @param tools the new list of tools from the server (empty list clears cache)
     */
    void updateCachedTools(List<McpSchema.Tool> tools) {
        if (tools != null) {
            // Build new map first, then atomically replace via volatile assignment
            Map<String, McpSchema.Tool> newTools =
                    tools.stream().collect(Collectors.toMap(McpSchema.Tool::name, t -> t));
            cachedTools = new ConcurrentHashMap<>(newTools);
            logger.info("[MCP-{}] Updated cached tools, total: {}", name, tools.size());
        }
    }

    /**
     * Initializes the async MCP client connection and caches available tools.
     *
     * <p>This method connects to the MCP server, discovers available tools, and caches them for
     * later use. If already initialized, this method returns immediately without re-initializing.
     *
     * @return a Mono that completes when initialization is finished
     */
    @Override
    public Mono<Void> initialize() {
        if (initialized) {
            return Mono.empty();
        }

        McpAsyncClient client = clientRef.get();
        if (client == null) {
            return Mono.error(new IllegalStateException("MCP client '" + name + "' not available"));
        }

        logger.info("Initializing MCP async client: {}", name);

        return client.initialize()
                .doOnSuccess(
                        result ->
                                logger.debug(
                                        "MCP client '{}' initialized with server: {}",
                                        name,
                                        result.serverInfo().name()))
                .then(client.listTools())
                .doOnNext(
                        result -> {
                            logger.debug(
                                    "MCP client '{}' discovered {} tools",
                                    name,
                                    result.tools().size());
                            // Cache all tools - build new map then atomically replace
                            Map<String, McpSchema.Tool> newTools =
                                    result.tools().stream()
                                            .collect(
                                                    Collectors.toMap(McpSchema.Tool::name, t -> t));
                            cachedTools = new ConcurrentHashMap<>(newTools);
                        })
                .doOnSuccess(v -> initialized = true)
                .doOnError(e -> logger.error("Failed to initialize MCP client: {}", name, e))
                .then();
    }

    /**
     * Lists all tools available from the MCP server.
     *
     * <p>This method queries the MCP server for its current list of tools. The client must be
     * initialized before calling this method.
     *
     * @return a Mono emitting the list of available tools
     */
    @Override
    public Mono<List<McpSchema.Tool>> listTools() {
        if (!initialized) {
            return Mono.error(
                    new IllegalStateException("MCP client '" + name + "' not initialized"));
        }

        McpAsyncClient client = clientRef.get();
        if (client == null) {
            return Mono.error(new IllegalStateException("MCP client '" + name + "' not available"));
        }

        return client.listTools().map(McpSchema.ListToolsResult::tools);
    }

    /**
     * Invokes a tool on the MCP server asynchronously.
     *
     * <p>This method sends a tool call request to the MCP server and returns the result
     * asynchronously. The client must be initialized before calling this method.
     *
     * @param toolName the name of the tool to call
     * @param arguments the arguments to pass to the tool
     * @return a Mono emitting the tool call result (may contain error information)
     */
    @Override
    public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
        if (!initialized) {
            return Mono.error(
                    new IllegalStateException("MCP client '" + name + "' not initialized"));
        }

        McpAsyncClient client = clientRef.get();
        if (client == null) {
            return Mono.error(new IllegalStateException("MCP client '" + name + "' not available"));
        }

        logger.debug("Calling MCP tool '{}' on client '{}'", toolName, name);

        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, arguments);

        return client.callTool(request)
                .doOnSuccess(
                        result -> {
                            if (Boolean.TRUE.equals(result.isError())) {
                                logger.warn(
                                        "MCP tool '{}' returned error: {}",
                                        toolName,
                                        result.content());
                            } else {
                                logger.debug("MCP tool '{}' completed successfully", toolName);
                            }
                        })
                .doOnError(
                        e ->
                                logger.error(
                                        "Failed to call MCP tool '{}': {}",
                                        toolName,
                                        e.getMessage()));
    }

    /**
     * Closes the MCP client connection and releases all resources.
     *
     * <p>This method attempts to close the client gracefully, falling back to forceful closure if
     * graceful closure fails. This method is idempotent and can be called multiple times safely.
     */
    @Override
    public void close() {
        McpAsyncClient toClose = clientRef.getAndSet(null);
        if (toClose != null) {
            logger.info("Closing MCP async client: {}", name);
            try {
                toClose.closeGracefully()
                        .doOnSuccess(v -> logger.debug("MCP client '{}' closed", name))
                        .doOnError(e -> logger.error("Error closing MCP client '{}'", name, e))
                        .block();
            } catch (Exception e) {
                logger.error("Exception during MCP client close", e);
                toClose.close();
            }
        }
        initialized = false;
        cachedTools = new ConcurrentHashMap<>();
    }
}
