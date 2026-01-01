/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.extensions.higress;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * MCP client wrapper for Higress AI Gateway.
 *
 * <p>This wrapper extends {@link McpClientWrapper} to connect to Higress's unified MCP service
 * endpoint (union-tools-search). When semantic search is enabled in Higress, the gateway provides
 * a special tool {@code x_higress_tool_search} that can automatically select the most suitable
 * MCP tools based on user requests.
 *
 * <p>The wrapper delegates all MCP operations to an underlying {@link McpClientWrapper} instance
 * (either async or sync), providing a consistent interface for Higress-specific functionality.
 *
 * <p>Example usage:
 * <pre>{@code
 * HigressMcpClientWrapper client = HigressMcpClientBuilder
 *     .create("higress-mcp")
 *     .sseEndpoint("http://higress-gateway/mcp-servers/union-tools-search/sse")
 *     .build();
 *
 * // Initialize and list tools
 * client.initialize().block();
 * List<McpSchema.Tool> tools = client.listTools().block();
 *
 * // Call x_higress_tool_search to get recommended tools
 * McpSchema.CallToolResult result = client.callTool("x_higress_tool_search",
 *     Map.of("query", "查询北京天气")).block();
 * }</pre>
 *
 * @see HigressMcpClientBuilder
 * @see McpClientWrapper
 */
public class HigressMcpClientWrapper extends McpClientWrapper {

    private static final Logger logger = LoggerFactory.getLogger(HigressMcpClientWrapper.class);

    /**
     * The name of the Higress tool search tool.
     */
    public static final String TOOL_SEARCH_NAME = "x_higress_tool_search";

    /**
     * The underlying MCP client that handles actual MCP protocol communication.
     */
    private final McpClientWrapper delegateClient;

    /**
     * Whether x_higress_tool_search is enabled.
     */
    private final boolean enableToolSearch;

    /**
     * The query for x_higress_tool_search.
     */
    private final String toolSearchQuery;

    /**
     * The maximum number of tools to return from x_higress_tool_search.
     */
    private final int toolSearchTopK;

    /**
     * Constructs a new HigressMcpClientWrapper.
     *
     * @param name the unique name for this client
     * @param delegateClient the underlying MCP client wrapper to delegate operations to
     * @param enableToolSearch whether to enable x_higress_tool_search tool
     * @param toolSearchQuery the query for x_higress_tool_search
     * @param toolSearchTopK the maximum number of tools to return
     */
    HigressMcpClientWrapper(
            String name,
            McpClientWrapper delegateClient,
            boolean enableToolSearch,
            String toolSearchQuery,
            int toolSearchTopK) {
        super(name);
        this.delegateClient = delegateClient;
        this.enableToolSearch = enableToolSearch;
        this.toolSearchQuery = toolSearchQuery;
        this.toolSearchTopK = toolSearchTopK;
    }

    /**
     * Initializes the Higress MCP client connection.
     *
     * <p>This method delegates to the underlying MCP client to establish connection
     * with the Higress gateway and discover available tools.
     *
     * @return a Mono that completes when initialization is finished
     */
    @Override
    public Mono<Void> initialize() {
        if (isInitialized()) {
            logger.debug("Higress MCP client '{}' already initialized", name);
            return Mono.empty();
        }

        logger.info("Initializing Higress MCP client: {}", name);

        return delegateClient
                .initialize()
                .doOnSuccess(
                        unused -> {
                            this.initialized = true;
                            logger.info("Higress MCP client '{}' initialized successfully", name);
                        })
                .doOnError(
                        error ->
                                logger.error(
                                        "Failed to initialize Higress MCP client '{}': {}",
                                        name,
                                        error.getMessage(),
                                        error));
    }

    /**
     * Lists tools available from the Higress gateway.
     *
     * <p>If {@code enableToolSearch} is true, this method will:
     * <ol>
     *   <li>Call x_higress_tool_search with the configured query</li>
     *   <li>Parse the result to get recommended tools</li>
     *   <li>Convert and return these tools</li>
     * </ol>
     *
     * <p>If {@code enableToolSearch} is false, this method returns all tools from the MCP server.
     *
     * @return a Mono emitting the list of available tools
     */
    @Override
    public Mono<List<McpSchema.Tool>> listTools() {
        if (enableToolSearch) {
            // Call x_higress_tool_search and convert results to McpSchema.Tool
            logger.info(
                    "Tool search enabled, calling {} with query: '{}'",
                    TOOL_SEARCH_NAME,
                    toolSearchQuery);

            return searchTools(toolSearchQuery, toolSearchTopK)
                    .map(
                            result -> {
                                if (!result.isSuccess()) {
                                    logger.error(
                                            "Tool search failed: {}", result.getErrorMessage());
                                    throw new RuntimeException(
                                            "Tool search failed: " + result.getErrorMessage());
                                }

                                // Convert ToolInfo to McpSchema.Tool
                                List<McpSchema.Tool> tools =
                                        result.getTools().stream()
                                                .map(this::convertToMcpTool)
                                                .toList();

                                logger.info(
                                        "Tool search returned {} tools: {}",
                                        tools.size(),
                                        result.getToolNames());
                                return tools;
                            })
                    .doOnNext(
                            tools -> {
                                // Cache tools locally
                                tools.forEach(tool -> cachedTools.put(tool.name(), tool));
                            });
        } else {
            // Return all tools from delegate
            return delegateClient
                    .listTools()
                    .doOnNext(
                            tools -> {
                                // Cache tools locally
                                tools.forEach(tool -> cachedTools.put(tool.name(), tool));
                                logger.debug(
                                        "Higress MCP client '{}' discovered {} tools",
                                        name,
                                        tools.size());
                            });
        }
    }

    /**
     * Converts a HigressToolSearchResult.ToolInfo to McpSchema.Tool.
     */
    @SuppressWarnings("unchecked")
    private McpSchema.Tool convertToMcpTool(HigressToolSearchResult.ToolInfo toolInfo) {
        // Convert inputSchema Map to McpSchema.JsonSchema
        McpSchema.JsonSchema inputSchema = null;
        Map<String, Object> inputSchemaMap = toolInfo.inputSchema();
        if (inputSchemaMap != null) {
            Map<String, Object> properties =
                    inputSchemaMap.get("properties") instanceof Map<?, ?>
                            ? (Map<String, Object>) inputSchemaMap.get("properties")
                            : null;
            List<String> required =
                    inputSchemaMap.get("required") instanceof List<?>
                            ? ((List<?>) inputSchemaMap.get("required"))
                                    .stream()
                                            .filter(item -> item instanceof String)
                                            .map(item -> (String) item)
                                            .toList()
                            : null;
            inputSchema =
                    new McpSchema.JsonSchema(
                            (String) inputSchemaMap.get("type"),
                            properties,
                            required,
                            null, // additionalProperties
                            null, // definitions
                            null // defs
                            );
        }

        return new McpSchema.Tool(
                toolInfo.name(),
                toolInfo.title(), // title
                toolInfo.description(),
                inputSchema,
                null, // outputSchema
                null, // annotations
                null // extensions
                );
    }

    /**
     * Invokes a tool on the Higress gateway.
     *
     * <p>This method can be used to call any tool available on the gateway, including
     * the {@code x_higress_tool_search} tool for intelligent tool selection.
     *
     * @param toolName the name of the tool to call
     * @param arguments the arguments to pass to the tool
     * @return a Mono emitting the tool call result
     */
    @Override
    public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
        logger.debug(
                "Calling tool '{}' on Higress MCP client '{}' with arguments: {}",
                toolName,
                name,
                arguments);

        return delegateClient
                .callTool(toolName, arguments)
                .doOnSuccess(
                        result -> {
                            if (Boolean.TRUE.equals(result.isError())) {
                                logger.warn(
                                        "Higress tool '{}' returned error: {}",
                                        toolName,
                                        result.content());
                            } else {
                                logger.debug("Higress tool '{}' completed successfully", toolName);
                            }
                        })
                .doOnError(
                        error ->
                                logger.error(
                                        "Failed to call Higress tool '{}': {}",
                                        toolName,
                                        error.getMessage()));
    }

    /**
     * Closes the Higress MCP client and releases all resources.
     *
     * <p>This method closes the underlying delegate client and clears all cached data.
     */
    @Override
    public void close() {
        logger.info("Closing Higress MCP client: {}", name);

        if (delegateClient != null) {
            try {
                delegateClient.close();
                logger.debug("Higress MCP client '{}' closed successfully", name);
            } catch (Exception e) {
                logger.error("Error closing Higress MCP client '{}': {}", name, e.getMessage(), e);
            }
        }

        this.initialized = false;
        this.cachedTools.clear();
    }

    /**
     * Checks if x_higress_tool_search mode is enabled in this client.
     *
     * <p>When enabled, listTools() will call x_higress_tool_search to get
     * semantically relevant tools instead of returning all tools.
     *
     * @return true if tool search mode is enabled
     */
    public boolean isToolSearchEnabled() {
        return enableToolSearch;
    }

    /**
     * Searches for tools matching the query and returns parsed result.
     *
     * <p>This method calls x_higress_tool_search and parses the response into
     * a structured {@link HigressToolSearchResult}.
     *
     * @param query the user query to find matching tools (required)
     * @return a Mono emitting the parsed tool search result
     */
    public Mono<HigressToolSearchResult> searchTools(String query) {
        return callTool(TOOL_SEARCH_NAME, Map.of("query", query))
                .map(HigressToolSearchResult::parse);
    }

    /**
     * Searches for tools matching the query with topK limit.
     *
     * @param query the user query to find matching tools (required)
     * @param topK the maximum number of tools to return
     * @return a Mono emitting the parsed tool search result
     */
    public Mono<HigressToolSearchResult> searchTools(String query, int topK) {
        return callTool(TOOL_SEARCH_NAME, Map.of("query", query, "topK", topK))
                .map(HigressToolSearchResult::parse);
    }
}
