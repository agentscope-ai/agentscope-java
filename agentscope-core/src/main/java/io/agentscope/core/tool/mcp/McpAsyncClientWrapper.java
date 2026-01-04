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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Wrapper for asynchronous MCP clients using Project Reactor.
 * This implementation delegates to {@link McpAsyncClient} and provides
 * reactive operations that return Mono types.
 *
 * <p>Example usage:
 * <pre>{@code
 * McpAsyncClient client = ... // created via McpClient.async()
 * McpAsyncClientWrapper wrapper = new McpAsyncClientWrapper("my-mcp", client);
 * wrapper.initialize()
 *     .then(wrapper.callTool("tool_name", Map.of("arg1", "value1")))
 *     .subscribe(result -> System.out.println(result));
 * }</pre>
 */
public class McpAsyncClientWrapper extends McpClientWrapper {

    private static final Logger logger = LoggerFactory.getLogger(McpAsyncClientWrapper.class);

    private final McpAsyncClient client;

    /**
     * Constructs a new asynchronous MCP client wrapper.
     *
     * @param name unique identifier for this client
     * @param client the underlying async MCP client
     */
    public McpAsyncClientWrapper(String name, McpAsyncClient client) {
        super(name);
        this.client = client;
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
                            // Cache all tools
                            result.tools().forEach(tool -> cachedTools.put(tool.name(), tool));
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
     * @throws IllegalStateException if the client is not initialized
     */
    @Override
    public Mono<List<McpSchema.Tool>> listTools() {
        if (!initialized) {
            return Mono.error(
                    new IllegalStateException("MCP client '" + name + "' not initialized"));
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
     * @throws IllegalStateException if the client is not initialized
     */
    @Override
    public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
        if (!initialized) {
            return Mono.error(
                    new IllegalStateException("MCP client '" + name + "' not initialized"));
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
        if (client != null) {
            logger.info("Closing MCP async client: {}", name);
            try {
                client.closeGracefully()
                        .doOnSuccess(v -> logger.debug("MCP client '{}' closed", name))
                        .doOnError(e -> logger.error("Error closing MCP client '{}'", name, e))
                        .block();
            } catch (Exception e) {
                logger.error("Exception during MCP client close", e);
                client.close();
            }
        }
        initialized = false;
        cachedTools.clear();
    }
}
