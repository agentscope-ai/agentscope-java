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
package io.agentscope.core.nacos.mcp.loadbalance;

import com.alibaba.nacos.api.ai.constant.AiConstants;
import com.alibaba.nacos.api.ai.listener.AbstractNacosMcpServerListener;
import com.alibaba.nacos.api.ai.listener.NacosMcpServerEvent;
import com.alibaba.nacos.api.ai.model.mcp.McpServerDetailInfo;
import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.nacos.mcp.discovery.NacosMcpDiscoveryClient;
import io.agentscope.core.nacos.mcp.discovery.NacosMcpEndpoint;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A load-balanced {@link McpClientWrapper} that discovers MCP server endpoints from the Nacos MCP
 * registry and distributes tool calls across them.
 *
 * <p>This wrapper subscribes to an MCP server registered in Nacos, maintains one underlying MCP
 * client connection per backend endpoint, and keeps the connection set in sync with endpoint
 * changes pushed by Nacos (instances scaling in/out). Each {@link #callTool} invocation picks an
 * endpoint through the configured {@link EndpointSelector} (round-robin by default) and delegates
 * to the corresponding connection.
 *
 * <p>Because the wrapper is itself a {@link McpClientWrapper}, it can be registered into a
 * {@code Toolkit} directly, and all remote MCP tools are converted into AgentScope tools
 * automatically:
 * <pre>{@code
 * NacosMcpDiscoveryClient discoveryClient = new NacosMcpDiscoveryClient(nacosProperties);
 * McpClientWrapper wrapper = NacosLoadBalancedMcpClientWrapper.builder("weather")
 *         .serverName("weather-mcp-server")
 *         .version("1.0.0")
 *         .discoveryClient(discoveryClient)
 *         .build();
 * toolkit.registerMcpClient(wrapper).block();
 * }</pre>
 *
 * <p>Requires a Nacos 3.x server with the MCP registry capability enabled.
 */
public class NacosLoadBalancedMcpClientWrapper extends McpClientWrapper {

    private static final Logger logger =
            LoggerFactory.getLogger(NacosLoadBalancedMcpClientWrapper.class);

    private final NacosMcpDiscoveryClient discoveryClient;

    private final String serverName;

    private final String version;

    private final EndpointSelector endpointSelector;

    private final Duration requestTimeout;

    private final Duration initializationTimeout;

    private final Map<String, McpClientWrapper> endpointClients = new ConcurrentHashMap<>();

    private final AbstractNacosMcpServerListener listener =
            new AbstractNacosMcpServerListener() {
                @Override
                public void onEvent(NacosMcpServerEvent event) {
                    McpServerDetailInfo detailInfo = event.getMcpServerDetailInfo();
                    if (detailInfo == null) {
                        return;
                    }
                    try {
                        updateEndpoints(detailInfo);
                    } catch (Exception e) {
                        logger.error(
                                "Failed to update endpoints for MCP server '{}'", serverName, e);
                    }
                }
            };

    private volatile List<NacosMcpEndpoint> currentEndpoints = Collections.emptyList();

    private volatile String currentProtocol;

    private volatile boolean subscribed;

    private NacosLoadBalancedMcpClientWrapper(Builder builder) {
        super(builder.name);
        this.discoveryClient = builder.discoveryClient;
        this.serverName = builder.serverName;
        this.version = builder.version;
        this.endpointSelector = builder.endpointSelector;
        this.requestTimeout = builder.requestTimeout;
        this.initializationTimeout = builder.initializationTimeout;
    }

    /**
     * Creates a new builder for a load-balanced MCP client wrapper.
     *
     * @param name unique identifier for this client wrapper
     * @return a new builder instance
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * Subscribes to the MCP server in the Nacos registry, connects to all current endpoints and
     * caches their tools.
     *
     * <p>If no endpoint is available yet, initialization still succeeds and the wrapper stays
     * empty until Nacos pushes endpoints; tool calls fail until at least one endpoint is up.
     *
     * @return a Mono that completes when initialization is finished
     */
    @Override
    public Mono<Void> initialize() {
        if (initialized) {
            return Mono.empty();
        }

        logger.info(
                "Initializing Nacos load-balanced MCP client '{}' for server '{}'",
                name,
                serverName);

        return Mono.fromCallable(
                        () -> {
                            McpServerDetailInfo detailInfo =
                                    discoveryClient.subscribe(serverName, version, listener);
                            subscribed = true;
                            if (detailInfo == null) {
                                logger.warn(
                                        "No MCP server '{}' found in Nacos during initialization,"
                                                + " waiting for endpoints to be pushed",
                                        serverName);
                                return Collections.<NacosMcpEndpoint>emptyList();
                            }
                            currentProtocol = detailInfo.getProtocol();
                            return NacosMcpDiscoveryClient.resolveEndpoints(detailInfo);
                        })
                .flatMap(
                        endpoints ->
                                addEndpoints(endpoints)
                                        .doOnSuccess(v -> currentEndpoints = endpoints))
                .doOnSuccess(v -> initialized = true)
                .doOnError(e -> logger.error("Failed to initialize MCP client '{}'", name, e))
                .then();
    }

    /**
     * Lists tools from one of the connected endpoints.
     *
     * @return a Mono emitting the list of available tools
     */
    @Override
    public Mono<List<McpSchema.Tool>> listTools() {
        if (!initialized) {
            return Mono.error(
                    new IllegalStateException("MCP client '" + name + "' not initialized"));
        }
        McpClientWrapper client = anyInitializedClient();
        if (client == null) {
            return Mono.just(Collections.emptyList());
        }
        return client.listTools();
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
        return callTool(toolName, arguments, null);
    }

    /**
     * Invokes a tool on one endpoint selected by the configured {@link EndpointSelector}.
     *
     * @param toolName the name of the tool to call
     * @param arguments the arguments to pass to the tool
     * @param meta the metadata to pass to the tool
     * @return a Mono emitting the tool call result
     */
    @Override
    public Mono<McpSchema.CallToolResult> callTool(
            String toolName, Map<String, Object> arguments, Map<String, Object> meta) {
        if (!initialized) {
            return Mono.error(
                    new IllegalStateException("MCP client '" + name + "' not initialized"));
        }

        List<NacosMcpEndpoint> endpoints = currentEndpoints;
        if (endpoints.isEmpty()) {
            return Mono.error(
                    new IllegalStateException(
                            "No endpoint available for MCP server '" + serverName + "'"));
        }

        NacosMcpEndpoint endpoint = endpointSelector.select(endpoints);
        McpClientWrapper client = endpointClients.get(endpoint.key());
        if (client == null || !client.isInitialized()) {
            return Mono.error(
                    new IllegalStateException(
                            "Selected endpoint '" + endpoint + "' is not connected"));
        }

        logger.debug(
                "Calling MCP tool '{}' on client '{}', endpoint '{}'", toolName, name, endpoint);
        return client.callTool(toolName, arguments, meta);
    }

    /**
     * Unsubscribes from Nacos and closes all endpoint connections.
     */
    @Override
    public void close() {
        if (subscribed) {
            try {
                discoveryClient.unsubscribe(serverName, version, listener);
            } catch (NacosException e) {
                logger.warn("Failed to unsubscribe MCP server '{}' from Nacos", serverName, e);
            }
            subscribed = false;
        }
        endpointClients.values().forEach(McpClientWrapper::close);
        endpointClients.clear();
        currentEndpoints = Collections.emptyList();
        initialized = false;
        cachedTools.clear();
        logger.info("Closed Nacos load-balanced MCP client '{}'", name);
    }

    /**
     * Returns the endpoints currently connected by this wrapper.
     *
     * @return an unmodifiable view of the current endpoints
     */
    public List<NacosMcpEndpoint> getCurrentEndpoints() {
        return Collections.unmodifiableList(currentEndpoints);
    }

    /**
     * Returns the name of the MCP server this wrapper subscribes to in the Nacos MCP registry.
     *
     * @return the registered MCP server name
     */
    public String getServerName() {
        return serverName;
    }

    /**
     * Returns the version of the MCP server this wrapper subscribes to.
     *
     * @return the registered MCP server version, may be empty for the default version
     */
    public String getVersion() {
        return version;
    }

    private synchronized void updateEndpoints(McpServerDetailInfo detailInfo) {
        String newProtocol = detailInfo.getProtocol();
        List<NacosMcpEndpoint> newEndpoints = NacosMcpDiscoveryClient.resolveEndpoints(detailInfo);

        if (!Objects.equals(currentProtocol, newProtocol)) {
            logger.info(
                    "MCP server '{}' protocol changed from '{}' to '{}', rebuilding all endpoint"
                            + " connections",
                    serverName,
                    currentProtocol,
                    newProtocol);
            currentProtocol = newProtocol;
            closeAllEndpointClients();
            currentEndpoints = Collections.emptyList();
            addEndpoints(newEndpoints).doOnSuccess(v -> currentEndpoints = newEndpoints).block();
            return;
        }

        List<NacosMcpEndpoint> toAdd = new ArrayList<>();
        for (NacosMcpEndpoint endpoint : newEndpoints) {
            if (!endpointClients.containsKey(endpoint.key())) {
                toAdd.add(endpoint);
            }
        }
        for (NacosMcpEndpoint endpoint : currentEndpoints) {
            if (!containsKey(newEndpoints, endpoint.key())) {
                logger.info(
                        "Endpoint '{}' of MCP server '{}' removed from Nacos, closing connection",
                        endpoint,
                        serverName);
                McpClientWrapper removed = endpointClients.remove(endpoint.key());
                if (removed != null) {
                    removed.close();
                }
            }
        }

        if (!toAdd.isEmpty()) {
            logger.info(
                    "Adding {} new endpoint(s) for MCP server '{}': {}",
                    toAdd.size(),
                    serverName,
                    toAdd);
            addEndpoints(toAdd).block();
        }
        currentEndpoints = newEndpoints;
    }

    private Mono<Void> addEndpoints(List<NacosMcpEndpoint> endpoints) {
        return Flux.fromIterable(endpoints)
                .filter(endpoint -> !endpointClients.containsKey(endpoint.key()))
                .flatMap(
                        endpoint ->
                                createEndpointClient(endpoint)
                                        .flatMap(client -> client.initialize().thenReturn(client))
                                        .doOnNext(
                                                client ->
                                                        endpointClients.put(endpoint.key(), client))
                                        .onErrorResume(
                                                e -> {
                                                    logger.warn(
                                                            "Failed to connect endpoint '{}' of"
                                                                    + " MCP server '{}', it will be"
                                                                    + " retried on next Nacos push",
                                                            endpoint,
                                                            serverName,
                                                            e);
                                                    return Mono.empty();
                                                }))
                .then();
    }

    private Mono<McpClientWrapper> createEndpointClient(NacosMcpEndpoint endpoint) {
        String clientName = name + "-" + endpoint.key();
        McpClientBuilder builder =
                McpClientBuilder.create(clientName)
                        .timeout(requestTimeout)
                        .initializationTimeout(initializationTimeout);
        if (AiConstants.Mcp.MCP_PROTOCOL_SSE.equals(currentProtocol)) {
            builder.sseTransport(endpoint.url());
        } else {
            builder.streamableHttpTransport(endpoint.url());
        }
        return builder.buildAsync();
    }

    private McpClientWrapper anyInitializedClient() {
        for (McpClientWrapper client : endpointClients.values()) {
            if (client.isInitialized()) {
                return client;
            }
        }
        return null;
    }

    private void closeAllEndpointClients() {
        endpointClients.values().forEach(McpClientWrapper::close);
        endpointClients.clear();
    }

    private static boolean containsKey(List<NacosMcpEndpoint> endpoints, String key) {
        for (NacosMcpEndpoint endpoint : endpoints) {
            if (endpoint.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builder for {@link NacosLoadBalancedMcpClientWrapper}.
     */
    public static class Builder {

        private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(120);

        private static final Duration DEFAULT_INIT_TIMEOUT = Duration.ofSeconds(30);

        private final String name;

        private NacosMcpDiscoveryClient discoveryClient;

        private String serverName;

        private String version;

        private EndpointSelector endpointSelector = new RoundRobinEndpointSelector();

        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

        private Duration initializationTimeout = DEFAULT_INIT_TIMEOUT;

        private Builder(String name) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("MCP client name cannot be null or empty");
            }
            this.name = name;
        }

        /**
         * Sets the discovery client used to subscribe to the Nacos MCP registry.
         *
         * @param discoveryClient the Nacos MCP discovery client
         * @return this builder
         */
        public Builder discoveryClient(NacosMcpDiscoveryClient discoveryClient) {
            this.discoveryClient = discoveryClient;
            return this;
        }

        /**
         * Sets the name of the MCP server registered in the Nacos MCP registry.
         *
         * @param serverName the MCP server name
         * @return this builder
         */
        public Builder serverName(String serverName) {
            this.serverName = serverName;
            return this;
        }

        /**
         * Sets the version of the MCP server; null or empty means the default version.
         *
         * @param version the MCP server version
         * @return this builder
         */
        public Builder version(String version) {
            this.version = version;
            return this;
        }

        /**
         * Sets the endpoint selection (load balancing) strategy; round-robin by default.
         *
         * @param endpointSelector the endpoint selector
         * @return this builder
         */
        public Builder endpointSelector(EndpointSelector endpointSelector) {
            this.endpointSelector = endpointSelector;
            return this;
        }

        /**
         * Sets the request timeout for tool calls.
         *
         * @param requestTimeout the request timeout
         * @return this builder
         */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        /**
         * Sets the initialization timeout for each endpoint connection.
         *
         * @param initializationTimeout the initialization timeout
         * @return this builder
         */
        public Builder initializationTimeout(Duration initializationTimeout) {
            this.initializationTimeout = initializationTimeout;
            return this;
        }

        /**
         * Builds the load-balanced MCP client wrapper.
         *
         * @return a new wrapper instance
         */
        public NacosLoadBalancedMcpClientWrapper build() {
            if (discoveryClient == null) {
                throw new IllegalArgumentException("discoveryClient must be configured");
            }
            if (serverName == null || serverName.trim().isEmpty()) {
                throw new IllegalArgumentException("serverName must be configured");
            }
            return new NacosLoadBalancedMcpClientWrapper(this);
        }
    }
}
