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

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Wrapper for synchronous MCP clients that converts blocking calls to reactive Mono types.
 * This implementation delegates to {@link McpSyncClient} and wraps blocking operations
 * in Reactor's boundedElastic scheduler to avoid blocking the event loop.
 *
 * <p>Example usage:
 * <pre>{@code
 * McpSyncClient client = ... // created via McpClient.sync()
 * McpSyncClientWrapper wrapper = new McpSyncClientWrapper("my-mcp", client);
 * wrapper.initialize()
 *     .then(wrapper.callTool("tool_name", Map.of("arg1", "value1")))
 *     .subscribe(result -> System.out.println(result));
 * }</pre>
 */
public class McpSyncClientWrapper extends McpClientWrapper {

    private static final Logger logger = LoggerFactory.getLogger(McpSyncClientWrapper.class);

    private final McpSyncClient client;
    private final Duration closeTimeout;

    /**
     * Constructs a new synchronous MCP client wrapper using the default close timeout.
     *
     * @param name unique identifier for this client
     * @param client the underlying sync MCP client
     */
    public McpSyncClientWrapper(String name, McpSyncClient client) {
        this(name, client, DEFAULT_CLOSE_TIMEOUT);
    }

    /**
     * Constructs a new synchronous MCP client wrapper.
     *
     * @param name unique identifier for this client
     * @param client the underlying sync MCP client
     * @param closeTimeout upper bound for the graceful close attempt in {@link #close()}
     */
    public McpSyncClientWrapper(String name, McpSyncClient client, Duration closeTimeout) {
        super(name);
        this.client = client;
        this.closeTimeout = closeTimeout;
    }

    /**
     * Initializes the sync MCP client connection and caches available tools.
     *
     * <p>This method wraps the blocking synchronous client operations in a reactive Mono that runs
     * on the boundedElastic scheduler to avoid blocking the event loop. If already initialized,
     * this method returns immediately without re-initializing.
     *
     * @return a Mono that completes when initialization is finished
     */
    @Override
    public Mono<Void> initialize() {
        if (initialized) {
            return Mono.empty();
        }

        logger.info("Initializing MCP sync client: {}", name);

        return Mono.fromCallable(
                        () -> {
                            // Initialize the client (blocking)
                            McpSchema.InitializeResult result = client.initialize();
                            logger.debug(
                                    "MCP client '{}' initialized with server: {}",
                                    name,
                                    result.serverInfo().name());

                            // List and cache tools (blocking)
                            McpSchema.ListToolsResult toolsResult = client.listTools();
                            logger.debug(
                                    "MCP client '{}' discovered {} tools",
                                    name,
                                    toolsResult.tools().size());

                            toolsResult.tools().forEach(tool -> cachedTools.put(tool.name(), tool));

                            initialized = true;
                            return null;
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> logger.error("Failed to initialize MCP client: {}", name, e))
                .then();
    }

    /**
     * Lists all tools available from the MCP server.
     *
     * <p>This method wraps the blocking synchronous listTools call in a reactive Mono. The client
     * must be initialized before calling this method.
     *
     * @return a Mono emitting the list of available tools
     * @throws IllegalStateException if the client is not initialized
     */
    @Override
    public Mono<List<McpSchema.Tool>> listTools() {
        if (!initialized) {
            return Mono.error(
                    new IllegalStateException("MCP client '" + name + "' not initialized"));
        }

        return Mono.fromCallable(() -> client.listTools().tools())
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Invokes a tool on the MCP server, wrapping the blocking call in a reactive Mono.
     *
     * <p>This method wraps the blocking synchronous callTool operation in a Mono that runs on the
     * boundedElastic scheduler. The client must be initialized before calling this method.
     *
     * @param toolName the name of the tool to call
     * @param arguments the arguments to pass to the tool
     * @return a Mono emitting the tool call result (may contain error information)
     * @throws IllegalStateException if the client is not initialized
     */
    @Override
    public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
        return callTool(toolName, arguments, null);
    }

    /**
     * Invokes a tool on the MCP server, wrapping the blocking call in a reactive Mono.
     *
     * <p>This method wraps the blocking synchronous callTool operation in a Mono that runs on the
     * boundedElastic scheduler. The client must be initialized before calling this method.
     *
     * @param toolName the name of the tool to call
     * @param arguments the arguments to pass to the tool
     * @param meta additional metadata to pass to the tool
     * @return a Mono emitting the tool call result (may contain error information)
     * @throws IllegalStateException if the client is not initialized
     * */
    @Override
    public Mono<McpSchema.CallToolResult> callTool(
            String toolName, Map<String, Object> arguments, Map<String, Object> meta) {
        if (!initialized) {
            return Mono.error(
                    new IllegalStateException("MCP client '" + name + "' not initialized"));
        }

        logger.debug("Calling MCP tool '{}' on client '{}'", toolName, name);

        return Mono.fromCallable(
                        () -> {
                            McpSchema.CallToolRequest request =
                                    new McpSchema.CallToolRequest(toolName, arguments, meta);
                            McpSchema.CallToolResult result = client.callTool(request);

                            if (Boolean.TRUE.equals(result.isError())) {
                                logger.warn(
                                        "MCP tool '{}' returned error: {}",
                                        toolName,
                                        result.content());
                            } else {
                                logger.debug("MCP tool '{}' completed successfully", toolName);
                            }

                            return result;
                        })
                .subscribeOn(Schedulers.boundedElastic())
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
     * <p>{@code McpSyncClient.closeGracefully()} is a blocking call with no timeout of its own, so
     * it is run on {@link Schedulers#boundedElastic()} and awaited for at most the configured close
     * timeout ({@link #DEFAULT_CLOSE_TIMEOUT} unless overridden). On timeout or failure the
     * graceful attempt is cancelled and a forceful close releases the underlying transport,
     * keeping this method aligned with {@link McpAsyncClientWrapper#close()}. This method is
     * idempotent and can be called multiple times safely.
     *
     * <p>Note: cancelling the subscription does not interrupt a blocking graceful close already
     * running on the scheduler, so such a call may still finish in the background; the forceful
     * close is what guarantees the transport is released.
     */
    @Override
    public void close() {
        if (client != null) {
            logger.info("Closing MCP sync client: {}", name);
            Mono<Void> gracefulClose =
                    Mono.<Void>fromRunnable(client::closeGracefully)
                            .subscribeOn(Schedulers.boundedElastic());
            closeWithTimeout(gracefulClose, client::close, closeTimeout);
        }
        initialized = false;
        cachedTools.clear();
    }
}
