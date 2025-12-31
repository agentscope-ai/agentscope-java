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

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Builder for creating {@link HigressMcpClientWrapper} instances.
 *
 * <p>This builder follows the same pattern as {@link McpClientBuilder} in agentscope-core,
 * providing a fluent API for configuring and building Higress MCP clients.
 *
 * <p>Supports two transport types:
 * <ul>
 *   <li><b>SSE (Server-Sent Events)</b> - for stateful connections with server push</li>
 *   <li><b>StreamableHTTP</b> - for stateless HTTP streaming</li>
 * </ul>
 *
 * <p>Example usage with SSE transport:
 * <pre>{@code
 * HigressMcpClientWrapper client = HigressMcpClientBuilder
 *     .create("higress-mcp")
 *     .sseEndpoint("http://higress-gateway/mcp-servers/union-tools-search/sse")
 *     .build();
 * }</pre>
 *
 * <p>Example usage with StreamableHTTP transport:
 * <pre>{@code
 * HigressMcpClientWrapper client = HigressMcpClientBuilder
 *     .create("higress-mcp")
 *     .streamableHttpEndpoint("http://higress-gateway/mcp-servers/union-tools-search")
 *     .build();
 * }</pre>
 *
 * <p>Example with authentication:
 * <pre>{@code
 * HigressMcpClientWrapper client = HigressMcpClientBuilder
 *     .create("higress-mcp")
 *     .sseEndpoint("http://higress-gateway/mcp-servers/union-tools-search/sse")
 *     .header("Authorization", "Bearer " + token)
 *     .header("X-Api-Key", apiKey)
 *     .timeout(Duration.ofSeconds(60))
 *     .buildAsync()
 *     .block();
 * }</pre>
 *
 * @see HigressMcpClientWrapper
 * @see McpClientBuilder
 */
public class HigressMcpClientBuilder {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration DEFAULT_INIT_TIMEOUT = Duration.ofSeconds(30);

    private final String clientName;
    private String endpoint;
    private TransportType transportType;
    private final Map<String, String> headers = new HashMap<>();
    private final Map<String, String> queryParams = new HashMap<>();
    private Duration timeout = DEFAULT_TIMEOUT;
    private Duration initializationTimeout = DEFAULT_INIT_TIMEOUT;
    private boolean enableToolSearch = false;
    private String toolSearchQuery;
    private int toolSearchTopK = 10;

    /**
     * Private constructor. Use static factory methods to create instances.
     *
     * @param clientName the unique name for the MCP client
     */
    private HigressMcpClientBuilder(String clientName) {
        this.clientName = clientName;
    }

    /**
     * Creates a new builder with the specified client name.
     *
     * @param clientName unique identifier for the MCP client
     * @return new builder instance
     * @throws IllegalArgumentException if clientName is null or empty
     */
    public static HigressMcpClientBuilder create(String clientName) {
        if (clientName == null || clientName.trim().isEmpty()) {
            throw new IllegalArgumentException("Client name cannot be null or empty");
        }
        return new HigressMcpClientBuilder(clientName);
    }

    /**
     * Configures SSE (Server-Sent Events) transport endpoint.
     *
     * <p>SSE transport is recommended for scenarios requiring real-time server push
     * and stateful connections.
     *
     * @param endpoint the SSE endpoint URL
     *                 (e.g., "http://higress-gateway/mcp-servers/union-tools-search/sse")
     * @return this builder for method chaining
     */
    public HigressMcpClientBuilder sseEndpoint(String endpoint) {
        this.endpoint = endpoint;
        this.transportType = TransportType.SSE;
        return this;
    }

    /**
     * Configures StreamableHTTP transport endpoint.
     *
     * <p>StreamableHTTP transport is suitable for stateless HTTP streaming scenarios.
     *
     * @param endpoint the StreamableHTTP endpoint URL
     *                 (e.g., "http://higress-gateway/mcp-servers/union-tools-search")
     * @return this builder for method chaining
     */
    public HigressMcpClientBuilder streamableHttpEndpoint(String endpoint) {
        this.endpoint = endpoint;
        this.transportType = TransportType.STREAMABLE_HTTP;
        return this;
    }

    /**
     * Adds an HTTP header to be sent with each request.
     *
     * <p>Common use cases include authentication headers:
     * <ul>
     *   <li>{@code header("Authorization", "Bearer " + token)}</li>
     *   <li>{@code header("X-Api-Key", apiKey)}</li>
     * </ul>
     *
     * @param key header name
     * @param value header value
     * @return this builder for method chaining
     */
    public HigressMcpClientBuilder header(String key, String value) {
        this.headers.put(key, value);
        return this;
    }

    /**
     * Sets multiple HTTP headers at once.
     *
     * @param headers map of header name-value pairs
     * @return this builder for method chaining
     */
    public HigressMcpClientBuilder headers(Map<String, String> headers) {
        if (headers != null) {
            this.headers.putAll(headers);
        }
        return this;
    }

    /**
     * Adds a query parameter to the URL.
     *
     * <p>Query parameters added via this method will be merged with any existing
     * query parameters in the URL.
     *
     * <p>Example:
     * <pre>{@code
     * HigressMcpClientWrapper client = HigressMcpClientBuilder
     *     .create("higress")
     *     .streamableHttpEndpoint("http://gateway/mcp")
     *     .queryParam("token", "abc123")
     *     .queryParam("env", "prod")
     *     .build();
     * }</pre>
     *
     * @param key query parameter name
     * @param value query parameter value
     * @return this builder for method chaining
     */
    public HigressMcpClientBuilder queryParam(String key, String value) {
        this.queryParams.put(key, value);
        return this;
    }

    /**
     * Sets multiple query parameters at once.
     *
     * @param queryParams map of query parameter name-value pairs
     * @return this builder for method chaining
     */
    public HigressMcpClientBuilder queryParams(Map<String, String> queryParams) {
        if (queryParams != null) {
            this.queryParams.putAll(queryParams);
        }
        return this;
    }

    /**
     * Sets the request timeout duration.
     *
     * <p>This timeout applies to individual MCP requests (tool calls, list tools, etc.).
     * Default is 120 seconds.
     *
     * @param timeout timeout duration
     * @return this builder for method chaining
     */
    public HigressMcpClientBuilder timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * Sets the initialization timeout duration.
     *
     * <p>This timeout applies to the client initialization process.
     * Default is 30 seconds.
     *
     * @param timeout timeout duration
     * @return this builder for method chaining
     */
    public HigressMcpClientBuilder initializationTimeout(Duration timeout) {
        this.initializationTimeout = timeout;
        return this;
    }

    /**
     * Enables tool search with the specified query.
     *
     * <p>When enabled, listTools() will call x_higress_tool_search with the query,
     * and return only the semantically relevant tools (default top 10).
     *
     * <p>Example:
     * <pre>{@code
     * HigressMcpClientWrapper client = HigressMcpClientBuilder
     *     .create("higress")
     *     .streamableHttpEndpoint(endpoint)
     *     .toolSearch("查询天气")
     *     .build();
     * }</pre>
     *
     * @param query the search query describing what tools are needed
     * @return this builder for method chaining
     */
    public HigressMcpClientBuilder toolSearch(String query) {
        this.enableToolSearch = true;
        this.toolSearchQuery = query;
        return this;
    }

    /**
     * Enables tool search with the specified query and topK.
     *
     * <p>Example:
     * <pre>{@code
     * HigressMcpClientWrapper client = HigressMcpClientBuilder
     *     .create("higress")
     *     .streamableHttpEndpoint(endpoint)
     *     .toolSearch("查询天气", 5)
     *     .build();
     * }</pre>
     *
     * @param query the search query describing what tools are needed
     * @param topK the maximum number of tools to return (must be positive)
     * @return this builder for method chaining
     * @throws IllegalArgumentException if topK is not positive
     */
    public HigressMcpClientBuilder toolSearch(String query, int topK) {
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be a positive integer, got: " + topK);
        }
        this.enableToolSearch = true;
        this.toolSearchQuery = query;
        this.toolSearchTopK = topK;
        return this;
    }

    /**
     * Builds an asynchronous {@link HigressMcpClientWrapper} instance.
     *
     * <p>This method returns a {@link Mono} that, when subscribed:
     * <ol>
     *   <li>Validates the configuration</li>
     *   <li>Creates the underlying MCP client using {@link McpClientBuilder}</li>
     *   <li>Wraps it in a {@link HigressMcpClientWrapper}</li>
     *   <li>Initializes the client</li>
     * </ol>
     *
     * <p>Example usage:
     * <pre>{@code
     * HigressMcpClientWrapper client = HigressMcpClientBuilder
     *     .create("higress-mcp")
     *     .streamableHttpEndpoint(endpoint)
     *     .buildAsync()
     *     .block();
     * }</pre>
     *
     * @return Mono emitting the configured HigressMcpClientWrapper instance
     * @throws IllegalArgumentException if endpoint is not configured
     * @throws IllegalStateException if transport type is not configured
     */
    public Mono<HigressMcpClientWrapper> buildAsync() {
        // Validate configuration
        validateConfiguration();

        // Build the underlying MCP client using agentscope's McpClientBuilder
        McpClientBuilder mcpClientBuilder = createMcpClientBuilder();

        // Build async delegate client, wrap it and initialize
        return mcpClientBuilder
                .buildAsync()
                .flatMap(
                        delegateClient -> {
                            HigressMcpClientWrapper wrapper =
                                    new HigressMcpClientWrapper(
                                            clientName,
                                            delegateClient,
                                            enableToolSearch,
                                            toolSearchQuery,
                                            toolSearchTopK);
                            return wrapper.initialize().thenReturn(wrapper);
                        });
    }

    /**
     * Builds a synchronous {@link HigressMcpClientWrapper} instance (blocking operation).
     *
     * <p>This method:
     * <ol>
     *   <li>Validates the configuration</li>
     *   <li>Creates the underlying synchronous MCP client using {@link McpClientBuilder}</li>
     *   <li>Wraps it in a {@link HigressMcpClientWrapper}</li>
     *   <li>Initializes the client</li>
     * </ol>
     *
     * <p>Example usage:
     * <pre>{@code
     * HigressMcpClientWrapper client = HigressMcpClientBuilder
     *     .create("higress-mcp")
     *     .streamableHttpEndpoint(endpoint)
     *     .buildSync();
     * }</pre>
     *
     * @return configured and initialized HigressMcpClientWrapper instance
     * @throws IllegalArgumentException if endpoint is not configured
     * @throws IllegalStateException if transport type is not configured
     */
    public HigressMcpClientWrapper buildSync() {
        // Validate configuration
        validateConfiguration();

        // Build the underlying MCP client using agentscope's McpClientBuilder
        McpClientBuilder mcpClientBuilder = createMcpClientBuilder();

        // Build sync delegate client
        McpClientWrapper delegateClient = mcpClientBuilder.buildSync();

        // Create the Higress wrapper and initialize
        HigressMcpClientWrapper wrapper =
                new HigressMcpClientWrapper(
                        clientName,
                        delegateClient,
                        enableToolSearch,
                        toolSearchQuery,
                        toolSearchTopK);

        wrapper.initialize().block();

        return wrapper;
    }

    /**
     * Validates the builder configuration.
     *
     * @throws IllegalArgumentException if endpoint is not configured or tool search query is
     *     missing
     * @throws IllegalStateException if transport type is not configured
     */
    private void validateConfiguration() {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Endpoint must be configured via sseEndpoint() or streamableHttpEndpoint()");
        }
        if (transportType == null) {
            throw new IllegalStateException(
                    "Transport type must be configured via sseEndpoint() or"
                            + " streamableHttpEndpoint()");
        }
        if (enableToolSearch && (toolSearchQuery == null || toolSearchQuery.trim().isEmpty())) {
            throw new IllegalArgumentException(
                    "Query is required for tool search. Use toolSearch(query) to configure.");
        }
    }

    /**
     * Creates and configures the underlying McpClientBuilder.
     *
     * @return configured McpClientBuilder instance
     */
    private McpClientBuilder createMcpClientBuilder() {
        McpClientBuilder mcpClientBuilder = McpClientBuilder.create(clientName);

        // Configure transport based on type
        switch (transportType) {
            case SSE -> mcpClientBuilder.sseTransport(endpoint);
            case STREAMABLE_HTTP -> mcpClientBuilder.streamableHttpTransport(endpoint);
        }

        // Configure headers
        if (!headers.isEmpty()) {
            mcpClientBuilder.headers(headers);
        }

        // Configure query parameters
        if (!queryParams.isEmpty()) {
            mcpClientBuilder.queryParams(queryParams);
        }

        // Configure timeouts
        mcpClientBuilder.timeout(timeout);
        mcpClientBuilder.initializationTimeout(initializationTimeout);

        return mcpClientBuilder;
    }

    /**
     * Transport type enumeration.
     */
    private enum TransportType {
        /**
         * Server-Sent Events transport for stateful connections.
         */
        SSE,

        /**
         * Streamable HTTP transport for stateless connections.
         */
        STREAMABLE_HTTP
    }
}
