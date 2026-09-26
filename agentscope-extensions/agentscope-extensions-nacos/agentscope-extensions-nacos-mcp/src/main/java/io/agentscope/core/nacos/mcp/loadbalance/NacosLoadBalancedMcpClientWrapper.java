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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * A load-balanced {@link McpClientWrapper} that discovers MCP server endpoints from the Nacos MCP
 * registry and distributes tool calls across them.
 *
 * <p>This wrapper subscribes to an MCP server registered in Nacos, maintains one underlying MCP
 * client connection per backend endpoint, and keeps the connection set in sync with endpoint
 * changes pushed by Nacos (instances scaling in/out). Each {@link #callTool} invocation picks one
 * of the <em>connected</em> endpoints through the configured {@link EndpointSelector} (round-robin
 * by default) and delegates to the corresponding connection, so an instance that never connected
 * is never selected.
 *
 * <p>A failing call is not replayed on another endpoint. The wrapper therefore guarantees that a
 * tool is executed at most once, and the error is propagated to the caller; retrying is left to
 * the layer above, which knows whether a tool is idempotent (see {@code Toolkit}'s execution
 * configuration, and the {@code retryOn} predicate it accepts).
 *
 * <p>Endpoint changes propagate at runtime, but the tool list published into a {@code Toolkit} is a
 * snapshot taken at registration time: a server that only becomes available after the application
 * started (or whose tool set changes later) needs the wrapper to be re-registered. Endpoint scale
 * in/out alone does not require a re-registration.
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

    private final EndpointClientFactory clientFactory;

    /** One MCP connection per endpoint key; only mutated while holding {@link #reconcileLock}. */
    private final Map<String, McpClientWrapper> endpointClients = new ConcurrentHashMap<>();

    /**
     * Serializes every mutation of {@link #endpointClients} and {@link #registeredEndpoints}. The
     * mutations themselves run on {@link #reconciler}, so this lock is uncontended in practice; it
     * also gives {@link #close()} and {@link #initialize()} a single monitor to synchronize on.
     */
    private final ReentrantLock reconcileLock = new ReentrantLock();

    /**
     * Applies registry snapshots one at a time, off the caller thread. Nacos delivers pushes on
     * its own dispatcher thread, and connecting an endpoint blocks on the transport handshake, so
     * doing that work inline would stall event delivery for every other subscriber of the same
     * Nacos client. A single thread also guarantees that snapshots are applied in submission
     * order.
     */
    private final ExecutorService reconciler =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "nacos-mcp-reconciler-" + name);
                        thread.setDaemon(true);
                        return thread;
                    });

    private final AbstractNacosMcpServerListener listener =
            new AbstractNacosMcpServerListener() {
                @Override
                public void onEvent(NacosMcpServerEvent event) {
                    McpServerDetailInfo detailInfo = event.getMcpServerDetailInfo();
                    if (detailInfo == null) {
                        return;
                    }
                    // Never reconcile on the Nacos dispatcher thread. The future is not awaited
                    // anywhere, so observe it here: a failing push must not vanish silently.
                    submitReconcile(detailInfo, false)
                            .whenComplete(
                                    (ignored, error) -> {
                                        if (error != null) {
                                            logger.error(
                                                    "Failed to apply the endpoint snapshot of MCP"
                                                            + " server '{}', the previous endpoints"
                                                            + " stay in place",
                                                    serverName,
                                                    error);
                                        }
                                    });
                }
            };

    /**
     * The endpoints as last reported by Nacos, whether or not their connections are established.
     */
    private volatile List<NacosMcpEndpoint> registeredEndpoints = Collections.emptyList();

    private volatile String currentProtocol;

    private volatile boolean subscribed;

    private volatile boolean closed;

    /** Guarded by {@link #reconcileLock}; set when a Nacos push has been applied. */
    private boolean pushApplied;

    /**
     * Creates a wrapper, allowing the test suite to substitute the per-endpoint MCP client factory.
     *
     * @param builder the configured builder
     * @param clientFactory the factory creating one MCP client per endpoint
     */
    NacosLoadBalancedMcpClientWrapper(Builder builder, EndpointClientFactory clientFactory) {
        super(builder.name);
        if (builder.discoveryClient == null) {
            throw new IllegalArgumentException("discoveryClient must be configured");
        }
        if (builder.serverName == null || builder.serverName.trim().isEmpty()) {
            throw new IllegalArgumentException("serverName must be configured");
        }
        this.discoveryClient = builder.discoveryClient;
        this.serverName = builder.serverName;
        this.version = builder.version;
        this.endpointSelector = builder.endpointSelector;
        this.clientFactory = clientFactory != null ? clientFactory : defaultClientFactory(builder);
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
     * Endpoints that appear later are connected in the background, but the tools published into a
     * Toolkit are the ones discovered at registration time.
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

        return Mono.fromCallable(this::subscribe)
                .flatMap(
                        detailInfo -> {
                            if (detailInfo == null) {
                                return Mono.<Void>empty();
                            }
                            // Route the initial snapshot through the reconciler so that it cannot
                            // race a push that was delivered while subscribe() was in flight.
                            return Mono.defer(
                                    () -> Mono.fromFuture(submitReconcile(detailInfo, true)));
                        })
                .doOnSuccess(v -> initialized = true)
                .doOnError(e -> logger.error("Failed to initialize MCP client '{}'", name, e));
    }

    /**
     * Activates the wrapper by handing the initial registry snapshot to the reconciler, which is
     * also used for Nacos pushes. A push that arrives while the subscription is being established
     * is newer than the snapshot, so the snapshot is dropped in that case.
     */
    private McpServerDetailInfo subscribe() throws NacosException {
        McpServerDetailInfo detailInfo;
        reconcileLock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("MCP client '" + name + "' is closed");
            }
            detailInfo = discoveryClient.subscribe(serverName, version, listener);
            subscribed = true;
        } finally {
            reconcileLock.unlock();
        }
        if (detailInfo == null) {
            logger.warn(
                    "No MCP server '{}' found in Nacos during initialization,"
                            + " waiting for endpoints to be pushed",
                    serverName);
        }
        return detailInfo;
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
     * Invokes a tool on a connected endpoint selected by the configured {@link EndpointSelector}.
     *
     * <p>Only endpoints with an established connection are candidates, so an instance that never
     * connected is never selected. A failing call is not replayed on another endpoint: the error is
     * propagated to the caller, which guarantees that a tool is executed at most once by this
     * wrapper. Retrying is the caller's decision, so a caller that knows its tools are idempotent
     * can opt into retries through its own execution configuration.
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

        List<NacosMcpEndpoint> connected = connectedEndpoints();
        if (connected.isEmpty()) {
            return Mono.error(
                    new IllegalStateException(
                            "No connected endpoint available for MCP server '"
                                    + serverName
                                    + "' ("
                                    + registeredEndpoints.size()
                                    + " endpoint(s) registered)"));
        }

        NacosMcpEndpoint endpoint = endpointSelector.select(connected);
        McpClientWrapper client = endpointClients.get(endpoint.key());
        if (!isUsable(client)) {
            return Mono.error(
                    new IllegalStateException(
                            "Endpoint '"
                                    + endpoint
                                    + "' of MCP server '"
                                    + serverName
                                    + "' is no longer connected"));
        }

        logger.debug(
                "Calling MCP tool '{}' on client '{}', endpoint '{}'", toolName, name, endpoint);
        return client.callTool(toolName, arguments, meta);
    }

    /**
     * Returns the endpoints whose connection is established, which are the only candidates a tool
     * call may be dispatched to.
     */
    private List<NacosMcpEndpoint> connectedEndpoints() {
        List<NacosMcpEndpoint> endpoints = registeredEndpoints;
        if (endpoints.isEmpty()) {
            return Collections.emptyList();
        }

        List<NacosMcpEndpoint> connected = new ArrayList<>(endpoints.size());
        for (NacosMcpEndpoint endpoint : endpoints) {
            if (isUsable(endpointClients.get(endpoint.key()))) {
                connected.add(endpoint);
            }
        }
        return connected;
    }

    /**
     * Unsubscribes from Nacos and closes all endpoint connections.
     */
    @Override
    public void close() {
        closed = true;
        reconcileLock.lock();
        try {
            if (subscribed) {
                try {
                    discoveryClient.unsubscribe(serverName, version, listener);
                } catch (NacosException e) {
                    logger.warn("Failed to unsubscribe MCP server '{}' from Nacos", serverName, e);
                }
                subscribed = false;
            }
            closeAllEndpointClients();
            registeredEndpoints = Collections.emptyList();
            initialized = false;
            cachedTools.clear();
        } finally {
            reconcileLock.unlock();
        }
        reconciler.shutdown();
        logger.info("Closed Nacos load-balanced MCP client '{}'", name);
    }

    /**
     * Returns the endpoints currently reported by Nacos for this MCP server, whether or not their
     * connections are established.
     *
     * <p>An endpoint that fails to connect (instance down, transport handshake timeout) stays in
     * this list and is retried on the next Nacos push, but it is not used to dispatch tool calls.
     * Use {@link #getConnectedEndpointCount()} for the number of endpoints actually serving calls.
     *
     * @return an unmodifiable view of the endpoints reported by the registry
     */
    public List<NacosMcpEndpoint> getRegisteredEndpoints() {
        return Collections.unmodifiableList(registeredEndpoints);
    }

    /**
     * Returns the number of endpoints that currently have an established connection.
     *
     * @return the number of connected endpoints
     */
    public int getConnectedEndpointCount() {
        return connectedEndpointCount();
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

    /**
     * Applies one registry snapshot: connects endpoints that appeared, closes endpoints that were
     * scaled in, and rebuilds every connection when the transport protocol changed.
     *
     * <p>Runs on {@link #reconciler} while holding {@link #reconcileLock}, so it is the only writer
     * of {@link #endpointClients} and may block on connection setup.
     */
    private void reconcile(McpServerDetailInfo detailInfo, boolean initialSnapshot) {
        reconcileLock.lock();
        try {
            if (closed) {
                return;
            }
            if (initialSnapshot && pushApplied) {
                // A push delivered while subscribe() was in flight is newer than the snapshot we
                // hold; applying it would resurrect endpoints that were just scaled in.
                logger.debug(
                        "Skipping the initial snapshot of MCP server '{}',"
                                + " a newer push was already applied",
                        serverName);
                return;
            }
            if (!initialSnapshot) {
                pushApplied = true;
            }

            String newProtocol = detailInfo.getProtocol();
            List<NacosMcpEndpoint> newEndpoints =
                    deduplicate(NacosMcpDiscoveryClient.resolveEndpoints(detailInfo));

            if (currentProtocol != null && !Objects.equals(currentProtocol, newProtocol)) {
                logger.info(
                        "MCP server '{}' protocol changed from '{}' to '{}', rebuilding all"
                                + " endpoint connections",
                        serverName,
                        currentProtocol,
                        newProtocol);
                closeAllEndpointClients();
            }
            currentProtocol = newProtocol;

            Set<String> newKeys = new LinkedHashSet<>();
            for (NacosMcpEndpoint endpoint : newEndpoints) {
                newKeys.add(endpoint.key());
            }
            for (String key : new ArrayList<>(endpointClients.keySet())) {
                if (!newKeys.contains(key)) {
                    logger.info(
                            "Endpoint '{}' of MCP server '{}' was removed from the registry,"
                                    + " closing its connection",
                            key,
                            serverName);
                    closeEndpoint(key);
                }
            }

            registeredEndpoints = newEndpoints;

            for (NacosMcpEndpoint endpoint : newEndpoints) {
                if (closed) {
                    return;
                }
                McpClientWrapper existing = endpointClients.get(endpoint.key());
                if (existing != null && existing.isInitialized()) {
                    continue;
                }
                if (existing != null) {
                    // A previous connect attempt never came up; drop it and retry.
                    closeEndpoint(endpoint.key());
                }
                connectEndpoint(endpoint);
            }
        } finally {
            reconcileLock.unlock();
        }
    }

    private void connectEndpoint(NacosMcpEndpoint endpoint) {
        McpClientWrapper client = null;
        boolean registered = false;
        try {
            client =
                    clientFactory
                            .create(endpointClientName(endpoint), endpoint, currentProtocol)
                            .block();
            if (client == null) {
                throw new IllegalStateException(
                        "Endpoint client factory returned null for '" + endpoint + "'");
            }
            client.initialize().block();

            McpClientWrapper winner = endpointClients.putIfAbsent(endpoint.key(), client);
            registered = winner == null;
            if (winner != null) {
                // Another pass connected this endpoint first; never leak the loser.
                logger.debug(
                        "Endpoint '{}' of MCP server '{}' was already connected,"
                                + " closing the duplicate connection",
                        endpoint,
                        serverName);
                closeQuietly(client, endpoint);
            } else {
                logger.info("Connected endpoint '{}' of MCP server '{}'", endpoint, serverName);
            }
        } catch (Exception e) {
            if (client != null && !registered) {
                closeQuietly(client, endpoint);
            }
            logger.warn(
                    "Failed to connect endpoint '{}' of MCP server '{}', tool calls stay on the"
                            + " connected endpoints until Nacos reports this endpoint again",
                    endpoint,
                    serverName,
                    e);
        }
    }

    private CompletableFuture<Void> submitReconcile(
            McpServerDetailInfo detailInfo, boolean initialSnapshot) {
        if (closed) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            return CompletableFuture.runAsync(
                    () -> reconcile(detailInfo, initialSnapshot), reconciler);
        } catch (RejectedExecutionException e) {
            // close() shut the reconciler down in the meantime.
            return CompletableFuture.completedFuture(null);
        }
    }

    private static List<NacosMcpEndpoint> deduplicate(List<NacosMcpEndpoint> endpoints) {
        Map<String, NacosMcpEndpoint> byKey = new LinkedHashMap<>();
        for (NacosMcpEndpoint endpoint : endpoints) {
            byKey.putIfAbsent(endpoint.key(), endpoint);
        }
        return new ArrayList<>(byKey.values());
    }

    private String endpointClientName(NacosMcpEndpoint endpoint) {
        return name + "-" + endpoint.key();
    }

    private static boolean isUsable(McpClientWrapper client) {
        return client != null && client.isInitialized();
    }

    private McpClientWrapper anyInitializedClient() {
        for (McpClientWrapper client : endpointClients.values()) {
            if (client.isInitialized()) {
                return client;
            }
        }
        return null;
    }

    private int connectedEndpointCount() {
        int count = 0;
        for (McpClientWrapper client : endpointClients.values()) {
            if (client.isInitialized()) {
                count++;
            }
        }
        return count;
    }

    private void closeEndpoint(String key) {
        McpClientWrapper removed = endpointClients.remove(key);
        if (removed != null) {
            closeQuietly(removed, removed.getName());
        }
    }

    private void closeAllEndpointClients() {
        for (String key : new ArrayList<>(endpointClients.keySet())) {
            closeEndpoint(key);
        }
    }

    private void closeQuietly(McpClientWrapper client, Object endpoint) {
        try {
            client.close();
        } catch (Exception e) {
            logger.warn("Error closing MCP connection for endpoint '{}'", endpoint, e);
        }
    }

    private static EndpointClientFactory defaultClientFactory(Builder builder) {
        return (clientName, endpoint, protocol) -> {
            McpClientBuilder mcpClientBuilder =
                    McpClientBuilder.create(clientName)
                            .timeout(builder.requestTimeout)
                            .initializationTimeout(builder.initializationTimeout);
            if (AiConstants.Mcp.MCP_PROTOCOL_SSE.equals(protocol)) {
                mcpClientBuilder.sseTransport(endpoint.url());
            } else {
                mcpClientBuilder.streamableHttpTransport(endpoint.url());
            }
            return mcpClientBuilder.buildAsync();
        };
    }

    /**
     * Creates an MCP client for one endpoint. Exists so that tests can observe reconciliation
     * without opening real connections.
     */
    @FunctionalInterface
    interface EndpointClientFactory {

        /**
         * Creates the MCP client serving one endpoint.
         *
         * @param clientName the name of the client to create
         * @param endpoint the endpoint to connect to
         * @param protocol the MCP transport protocol, either {@code sse} or streamable HTTP
         * @return a Mono emitting the created client
         */
        Mono<McpClientWrapper> create(
                String clientName, NacosMcpEndpoint endpoint, String protocol);
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
            return new NacosLoadBalancedMcpClientWrapper(this, null);
        }
    }
}
