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
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

/**
 * Builder for creating MCP client wrappers with fluent configuration.
 *
 * <p>Supports three transport types:
 * <ul>
 *   <li>StdIO - for local process communication</li>
 *   <li>SSE - for HTTP Server-Sent Events (stateful)</li>
 *   <li>StreamableHTTP - for HTTP streaming (stateless)</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // StdIO transport
 * McpClientWrapper client = McpClientBuilder.create("git-mcp")
 *     .stdioTransport("python", "-m", "mcp_server_git")
 *     .buildAsync()
 *     .block();
 *
 * // SSE transport with headers and query parameters
 * McpClientWrapper client = McpClientBuilder.create("remote-mcp")
 *     .sseTransport("https://mcp.example.com/sse")
 *     .header("Authorization", "Bearer " + token)
 *     .queryParam("queryKey", "queryValue")
 *     .timeout(Duration.ofSeconds(60))
 *     .buildAsync()
 *     .block();
 *
 * // HTTP transport with multiple query parameters
 * McpClientWrapper client = McpClientBuilder.create("http-mcp")
 *     .streamableHttpTransport("https://mcp.example.com/http")
 *     .queryParams(Map.of("token", "abc123", "env", "prod"))
 *     .buildSync();
 * }</pre>
 */
public class McpClientBuilder {

    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration DEFAULT_INIT_TIMEOUT = Duration.ofSeconds(30);

    private final String name;
    private TransportConfig transportConfig;
    private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
    private Duration initializationTimeout = DEFAULT_INIT_TIMEOUT;

    private McpClientBuilder(String name) {
        this.name = name;
    }

    /**
     * Creates a new MCP client builder with the specified name.
     *
     * @param name unique identifier for the MCP client
     * @return new builder instance
     */
    public static McpClientBuilder create(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("MCP client name cannot be null or empty");
        }
        return new McpClientBuilder(name);
    }

    /**
     * Configures StdIO transport for local process communication.
     *
     * @param command the executable command
     * @param args command arguments
     * @return this builder
     */
    public McpClientBuilder stdioTransport(String command, String... args) {
        this.transportConfig = new StdioTransportConfig(command, Arrays.asList(args));
        return this;
    }

    /**
     * Configures StdIO transport with environment variables.
     *
     * @param command the executable command
     * @param args command arguments list
     * @param env environment variables
     * @return this builder
     */
    public McpClientBuilder stdioTransport(
            String command, List<String> args, Map<String, String> env) {
        this.transportConfig = new StdioTransportConfig(command, args, env);
        return this;
    }

    /**
     * Configures HTTP SSE (Server-Sent Events) transport for stateful connections.
     *
     * @param url the server URL
     * @return this builder
     */
    public McpClientBuilder sseTransport(String url) {
        this.transportConfig = new SseTransportConfig(url);
        return this;
    }

    /**
     * Customizes the HTTP client for SSE transport (only applicable after calling sseTransport).
     * This allows advanced HTTP client configuration like HTTP/2, custom timeouts, SSL settings, etc.
     *
     * <p>Example usage for HTTP/2:
     * <pre>{@code
     * McpClientWrapper client = McpClientBuilder.create("mcp")
     *     .sseTransport("https://example.com/sse")
     *     .customizeSseClient(clientBuilder ->
     *         clientBuilder.version(java.net.http.HttpClient.Version.HTTP_2))
     *     .buildAsync()
     *     .block();
     * }</pre>
     *
     * @param customizer consumer to customize the HttpClient.Builder
     * @return this builder
     */
    public McpClientBuilder customizeSseClient(Consumer<HttpClient.Builder> customizer) {
        if (transportConfig instanceof SseTransportConfig) {
            ((SseTransportConfig) transportConfig).customizeHttpClient(customizer);
        }
        return this;
    }

    /**
     * Configures HTTP StreamableHTTP transport for stateless connections.
     *
     * @param url the server URL
     * @return this builder
     */
    public McpClientBuilder streamableHttpTransport(String url) {
        this.transportConfig = new StreamableHttpTransportConfig(url);
        return this;
    }

    /**
     * Customizes the HTTP client for StreamableHTTP transport (only applicable after calling streamableHttpTransport).
     * This allows advanced HTTP client configuration like HTTP/2, custom timeouts, SSL settings, etc.
     *
     * <p>Example usage for HTTP/2:
     * <pre>{@code
     * McpClientWrapper client = McpClientBuilder.create("mcp")
     *     .streamableHttpTransport("https://example.com/http")
     *     .customizeStreamableHttpClient(clientBuilder ->
     *         clientBuilder.version(java.net.http.HttpClient.Version.HTTP_2))
     *     .buildAsync()
     *     .block();
     * }</pre>
     *
     * @param customizer consumer to customize the HttpClient.Builder
     * @return this builder
     */
    public McpClientBuilder customizeStreamableHttpClient(Consumer<HttpClient.Builder> customizer) {
        if (transportConfig instanceof StreamableHttpTransportConfig) {
            ((StreamableHttpTransportConfig) transportConfig).customizeHttpClient(customizer);
        }
        return this;
    }

    /**
     * Adds an HTTP header (only applicable for HTTP transports).
     *
     * @param key header name
     * @param value header value
     * @return this builder
     */
    public McpClientBuilder header(String key, String value) {
        if (transportConfig instanceof HttpTransportConfig) {
            ((HttpTransportConfig) transportConfig).addHeader(key, value);
        }
        return this;
    }

    /**
     * Sets multiple HTTP headers (only applicable for HTTP transports).
     *
     * @param headers map of header name-value pairs
     * @return this builder
     */
    public McpClientBuilder headers(Map<String, String> headers) {
        if (transportConfig instanceof HttpTransportConfig) {
            ((HttpTransportConfig) transportConfig).setHeaders(headers);
        }
        return this;
    }

    /**
     * Adds a query parameter to the URL (only applicable for HTTP transports).
     *
     * <p>Query parameters added via this method will be merged with any existing
     * query parameters in the URL. If the same parameter key exists in both the URL
     * and the added parameters, the added parameter will take precedence.
     *
     * @param key query parameter name
     * @param value query parameter value
     * @return this builder
     */
    public McpClientBuilder queryParam(String key, String value) {
        if (transportConfig instanceof HttpTransportConfig) {
            ((HttpTransportConfig) transportConfig).addQueryParam(key, value);
        }
        return this;
    }

    /**
     * Sets multiple query parameters (only applicable for HTTP transports).
     *
     * <p>This method replaces any previously added query parameters.
     * Query parameters in the original URL are still preserved and merged.
     *
     * @param queryParams map of query parameter name-value pairs
     * @return this builder
     */
    public McpClientBuilder queryParams(Map<String, String> queryParams) {
        if (transportConfig instanceof HttpTransportConfig) {
            ((HttpTransportConfig) transportConfig).setQueryParams(queryParams);
        }
        return this;
    }

    /**
     * Sets the request timeout duration.
     *
     * @param timeout timeout duration
     * @return this builder
     */
    public McpClientBuilder timeout(Duration timeout) {
        this.requestTimeout = timeout;
        return this;
    }

    /**
     * Sets the initialization timeout duration.
     *
     * @param timeout timeout duration
     * @return this builder
     */
    public McpClientBuilder initializationTimeout(Duration timeout) {
        this.initializationTimeout = timeout;
        return this;
    }

    /**
     * Builds an asynchronous MCP client wrapper.
     *
     * @return Mono emitting the async client wrapper
     */
    public Mono<McpClientWrapper> buildAsync() {
        if (transportConfig == null) {
            return Mono.error(new IllegalStateException("Transport must be configured"));
        }

        return Mono.fromCallable(
                () -> {
                    McpClientTransport transport = transportConfig.createTransport();

                    McpSchema.Implementation clientInfo =
                            new McpSchema.Implementation(
                                    "agentscope-java",
                                    "AgentScope Java Framework",
                                    "1.0.8-SNAPSHOT");

                    McpSchema.ClientCapabilities clientCapabilities =
                            McpSchema.ClientCapabilities.builder().build();

                    McpAsyncClient mcpClient =
                            McpClient.async(transport)
                                    .requestTimeout(requestTimeout)
                                    .initializationTimeout(initializationTimeout)
                                    .clientInfo(clientInfo)
                                    .capabilities(clientCapabilities)
                                    .build();

                    return new McpAsyncClientWrapper(name, mcpClient);
                });
    }

    /**
     * Builds a synchronous MCP client wrapper (blocking operations).
     *
     * @return synchronous client wrapper
     */
    public McpClientWrapper buildSync() {
        if (transportConfig == null) {
            throw new IllegalStateException("Transport must be configured");
        }

        McpClientTransport transport = transportConfig.createTransport();

        McpSchema.Implementation clientInfo =
                new McpSchema.Implementation(
                        "agentscope-java", "AgentScope Java Framework", "1.0.8-SNAPSHOT");

        McpSchema.ClientCapabilities clientCapabilities =
                McpSchema.ClientCapabilities.builder().build();

        McpSyncClient mcpClient =
                McpClient.sync(transport)
                        .requestTimeout(requestTimeout)
                        .initializationTimeout(initializationTimeout)
                        .clientInfo(clientInfo)
                        .capabilities(clientCapabilities)
                        .build();

        return new McpSyncClientWrapper(name, mcpClient);
    }

    // ==================== Internal Transport Configuration Classes ====================

    private interface TransportConfig {
        McpClientTransport createTransport();
    }

    private static class StdioTransportConfig implements TransportConfig {
        private final String command;
        private final List<String> args;
        private final Map<String, String> env;

        public StdioTransportConfig(String command, List<String> args) {
            this(command, args, new HashMap<>());
        }

        public StdioTransportConfig(String command, List<String> args, Map<String, String> env) {
            this.command = command;
            this.args = new ArrayList<>(args);
            this.env = new HashMap<>(env);
        }

        @Override
        public McpClientTransport createTransport() {
            ServerParameters.Builder paramsBuilder = ServerParameters.builder(command);

            if (!args.isEmpty()) {
                paramsBuilder.args(args);
            }

            if (!env.isEmpty()) {
                paramsBuilder.env(env);
            }

            ServerParameters params = paramsBuilder.build();
            return new StdioClientTransport(params, McpJsonMapper.getDefault());
        }
    }

    private abstract static class HttpTransportConfig implements TransportConfig {
        protected final String url;
        protected Map<String, String> headers = new HashMap<>();
        protected Map<String, String> queryParams = new HashMap<>();

        protected HttpTransportConfig(String url) {
            this.url = url;
        }

        public void addHeader(String key, String value) {
            headers.put(key, value);
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = new HashMap<>(headers);
        }

        public void addQueryParam(String key, String value) {
            if (key == null) {
                throw new IllegalArgumentException("Query parameter key cannot be null");
            }
            if (value == null) {
                throw new IllegalArgumentException("Query parameter value cannot be null");
            }
            queryParams.put(key, value);
        }

        public void setQueryParams(Map<String, String> queryParams) {
            if (queryParams == null) {
                throw new IllegalArgumentException("Query parameters map cannot be null");
            }
            this.queryParams = new HashMap<>(queryParams);
        }

        /**
         * Extracts the endpoint path from URL, merging with additional query parameters.
         * Query parameters from the original URL are merged with additionally configured parameters.
         * Additional parameters take precedence over URL parameters with the same key.
         *
         * @return endpoint path with query parameters (e.g., "/api/sse?token=abc")
         */
        protected String extractEndpoint() {
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid URL format: " + url, e);
            }

            String endpoint = uri.getPath();
            if (endpoint == null || endpoint.isEmpty()) {
                endpoint = "/";
            }

            // Parse existing query parameters from URL
            Map<String, String> mergedParams = new HashMap<>();
            String existingQuery = uri.getQuery();
            if (existingQuery != null && !existingQuery.isEmpty()) {
                for (String param : existingQuery.split("&")) {
                    // Skip empty parameters
                    if (param.isEmpty()) {
                        continue;
                    }

                    String[] keyValue = param.split("=", 2);
                    String key = keyValue[0];
                    String value = keyValue.length == 2 ? keyValue[1] : "";

                    // URL decode the key and value
                    key = URLDecoder.decode(key, StandardCharsets.UTF_8);
                    value = URLDecoder.decode(value, StandardCharsets.UTF_8);

                    mergedParams.put(key, value);
                }
            }

            // Merge with additional query parameters (additional params take precedence)
            mergedParams.putAll(queryParams);

            // Build query string
            if (!mergedParams.isEmpty()) {
                String queryString =
                        mergedParams.entrySet().stream()
                                .map(
                                        e ->
                                                URLEncoder.encode(
                                                                e.getKey(), StandardCharsets.UTF_8)
                                                        + "="
                                                        + URLEncoder.encode(
                                                                e.getValue(),
                                                                StandardCharsets.UTF_8))
                                .collect(Collectors.joining("&"));
                endpoint += "?" + queryString;
            }

            return endpoint;
        }
    }

    private static class SseTransportConfig extends HttpTransportConfig {
        private HttpClientSseClientTransport.Builder clientTransportBuilder = null;
        private Consumer<HttpClient.Builder> httpClientCustomizer = null;

        public SseTransportConfig(String url) {
            super(url);
        }

        public void clientTransportBuilder(
                HttpClientSseClientTransport.Builder clientTransportBuilder) {
            this.clientTransportBuilder = clientTransportBuilder;
        }

        public void customizeHttpClient(Consumer<HttpClient.Builder> customizer) {
            this.httpClientCustomizer = customizer;
        }

        @Override
        public McpClientTransport createTransport() {
            if (clientTransportBuilder == null) {
                clientTransportBuilder = HttpClientSseClientTransport.builder(url);
            }

            // Apply HTTP client customization if provided
            if (httpClientCustomizer != null) {
                clientTransportBuilder.customizeClient(httpClientCustomizer);
            }

            clientTransportBuilder.sseEndpoint(extractEndpoint());

            if (!headers.isEmpty()) {
                clientTransportBuilder.customizeRequest(
                        requestBuilder -> {
                            headers.forEach(requestBuilder::header);
                        });
            }

            return clientTransportBuilder.build();
        }
    }

    private static class StreamableHttpTransportConfig extends HttpTransportConfig {
        private HttpClientStreamableHttpTransport.Builder clientTransportBuilder = null;
        private Consumer<HttpClient.Builder> httpClientCustomizer = null;

        public StreamableHttpTransportConfig(String url) {
            super(url);
        }

        public void clientTransportBuilder(
                HttpClientStreamableHttpTransport.Builder clientTransportBuilder) {
            this.clientTransportBuilder = clientTransportBuilder;
        }

        public void customizeHttpClient(Consumer<HttpClient.Builder> customizer) {
            this.httpClientCustomizer = customizer;
        }

        @Override
        public McpClientTransport createTransport() {
            if (clientTransportBuilder == null) {
                clientTransportBuilder = HttpClientStreamableHttpTransport.builder(url);
            }

            // Apply HTTP client customization if provided
            if (httpClientCustomizer != null) {
                clientTransportBuilder.customizeClient(httpClientCustomizer);
            }

            clientTransportBuilder.endpoint(extractEndpoint());

            if (!headers.isEmpty()) {
                clientTransportBuilder.customizeRequest(
                        requestBuilder -> {
                            headers.forEach(requestBuilder::header);
                        });
            }

            return clientTransportBuilder.build();
        }
    }
}
