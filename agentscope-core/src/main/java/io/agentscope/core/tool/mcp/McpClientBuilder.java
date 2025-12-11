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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * // SSE transport with headers
 * McpClientWrapper client = McpClientBuilder.create("remote-mcp")
 *     .sseTransport("https://mcp.example.com/sse")
 *     .header("Authorization", "Bearer " + token)
 *     .timeout(Duration.ofSeconds(60))
 *     .buildAsync()
 *     .block();
 *
 * // Synchronous client
 * McpClientWrapper client = McpClientBuilder.create("sync-mcp")
 *     .streamableHttpTransport("https://mcp.example.com/http")
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
                                    "1.0.2-SNAPSHOT");

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
                        "agentscope-java", "AgentScope Java Framework", "1.0.2-SNAPSHOT");

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

        protected HttpTransportConfig(String url) {
            this.url = url;
        }

        public void addHeader(String key, String value) {
            headers.put(key, value);
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = new HashMap<>(headers);
        }
    }

    private static class SseTransportConfig extends HttpTransportConfig {
        public SseTransportConfig(String url) {
            super(url);
        }

        @Override
        public McpClientTransport createTransport() {
            HttpClientSseClientTransport.Builder builder =
                    HttpClientSseClientTransport.builder(url).sseEndpoint(extractEndpoint(url));

            if (!headers.isEmpty()) {
                builder.customizeRequest(
                        requestBuilder -> {
                            headers.forEach(requestBuilder::header);
                        });
            }

            return builder.build();
        }
    }

    private static class StreamableHttpTransportConfig extends HttpTransportConfig {
        public StreamableHttpTransportConfig(String url) {
            super(url);
        }

        @Override
        public McpClientTransport createTransport() {
            HttpClientStreamableHttpTransport.Builder builder =
                    HttpClientStreamableHttpTransport.builder(url).endpoint(extractEndpoint(url));

            if (!headers.isEmpty()) {
                builder.customizeRequest(
                        requestBuilder -> {
                            headers.forEach(requestBuilder::header);
                        });
            }

            return builder.build();
        }
    }

    /**
     * Extracts the endpoint path from URL, preserving query parameters.
     *
     * @param url the full URL
     * @return endpoint path with query parameters (e.g., "/api/sse?token=abc")
     */
    private static String extractEndpoint(String url) {
        URI uri = URI.create(url);
        String endpoint = uri.getPath();
        if (uri.getQuery() != null) {
            endpoint += "?" + uri.getQuery();
        }
        return endpoint;
    }
}
