/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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

    /**
     * Constructs a new synchronous MCP client wrapper.
     *
     * @param name unique identifier for this client
     * @param client the underlying sync MCP client
     */
    public McpSyncClientWrapper(String name, McpSyncClient client) {
        super(name);
        this.client = client;
    }

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

    @Override
    public Mono<List<McpSchema.Tool>> listTools() {
        if (!initialized) {
            return Mono.error(
                    new IllegalStateException("MCP client '" + name + "' not initialized"));
        }

        return Mono.fromCallable(() -> client.listTools().tools())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
        if (!initialized) {
            return Mono.error(
                    new IllegalStateException("MCP client '" + name + "' not initialized"));
        }

        logger.debug("Calling MCP tool '{}' on client '{}'", toolName, name);

        return Mono.fromCallable(
                        () -> {
                            McpSchema.CallToolRequest request =
                                    new McpSchema.CallToolRequest(toolName, arguments);
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

    @Override
    public void close() {
        if (client != null) {
            logger.info("Closing MCP sync client: {}", name);
            try {
                client.closeGracefully();
                logger.debug("MCP client '{}' closed", name);
            } catch (Exception e) {
                logger.error("Exception during MCP client close", e);
                client.close();
            }
        }
        initialized = false;
        cachedTools.clear();
    }
}
