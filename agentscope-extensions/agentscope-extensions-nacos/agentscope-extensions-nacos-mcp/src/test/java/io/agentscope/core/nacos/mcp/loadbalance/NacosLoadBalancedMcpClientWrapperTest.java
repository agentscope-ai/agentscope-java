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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.ai.constant.AiConstants;
import com.alibaba.nacos.api.ai.listener.AbstractNacosMcpServerListener;
import com.alibaba.nacos.api.ai.listener.NacosMcpServerEvent;
import com.alibaba.nacos.api.ai.model.mcp.McpEndpointInfo;
import com.alibaba.nacos.api.ai.model.mcp.McpServerDetailInfo;
import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.nacos.mcp.discovery.NacosMcpDiscoveryClient;
import io.agentscope.core.nacos.mcp.discovery.NacosMcpEndpoint;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link NacosLoadBalancedMcpClientWrapper}.
 *
 * <p>Endpoint connections go through the package-private {@link
 * NacosLoadBalancedMcpClientWrapper.EndpointClientFactory} seam, so the reconciliation is observed
 * on real {@link McpClientWrapper} instances (one per endpoint, closed on scale-in, rebuilt on a
 * protocol change) instead of on the registry snapshot. One test keeps the production factory to
 * cover the real transport path, using an ephemeral closed loopback port so the connection is
 * refused immediately and deterministically.
 */
class NacosLoadBalancedMcpClientWrapperTest {

    private static final String SERVER_NAME = "weather-mcp-server";

    private final NacosMcpDiscoveryClient discoveryClient = mock(NacosMcpDiscoveryClient.class);

    /** The clients handed out by the factory, keyed by endpoint. */
    private final Map<String, StubMcpClient> stubs = new ConcurrentHashMap<>();

    /** Every factory invocation, so duplicate connections per endpoint are detectable. */
    private final List<ConnectedAttempt> attempts = Collections.synchronizedList(new ArrayList<>());

    /** Overrides the client created for an endpoint; all endpoints succeed by default. */
    private Function<NacosMcpEndpoint, StubMcpClient> stubProvider;

    private String version;

    private static McpServerDetailInfo detail(String protocol, McpEndpointInfo... endpoints) {
        McpServerDetailInfo detail = new McpServerDetailInfo();
        detail.setName(SERVER_NAME);
        detail.setProtocol(protocol);
        detail.setBackendEndpoints(List.of(endpoints));
        return detail;
    }

    private static McpEndpointInfo endpoint(String address, int port) {
        McpEndpointInfo info = new McpEndpointInfo();
        info.setAddress(address);
        info.setPort(port);
        info.setPath("/mcp");
        return info;
    }

    /**
     * Returns a loopback port that is guaranteed to refuse connections: the ephemeral port is
     * closed again as soon as the try-with-resources block exits.
     */
    private static McpEndpointInfo unreachableEndpoint() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return endpoint("127.0.0.1", probe.getLocalPort());
        }
    }

    private static NacosMcpEndpoint resolved(McpEndpointInfo info) {
        return NacosMcpEndpoint.from(info, "/mcp");
    }

    private NacosLoadBalancedMcpClientWrapper wrapper() {
        NacosLoadBalancedMcpClientWrapper.Builder builder =
                NacosLoadBalancedMcpClientWrapper.builder("weather")
                        .serverName(SERVER_NAME)
                        .version(version)
                        .discoveryClient(discoveryClient)
                        .requestTimeout(Duration.ofMillis(200))
                        .initializationTimeout(Duration.ofMillis(200));
        return new NacosLoadBalancedMcpClientWrapper(
                builder,
                (clientName, endpoint, protocol) -> {
                    attempts.add(new ConnectedAttempt(clientName, endpoint, protocol));
                    StubMcpClient stub =
                            stubProvider == null
                                    ? new StubMcpClient(endpoint)
                                    : stubProvider.apply(endpoint);
                    stubs.put(endpoint.key(), stub);
                    return Mono.just(stub);
                });
    }

    private NacosLoadBalancedMcpClientWrapper realTransportWrapper() {
        return NacosLoadBalancedMcpClientWrapper.builder("weather")
                .serverName(SERVER_NAME)
                .version(version)
                .discoveryClient(discoveryClient)
                .requestTimeout(Duration.ofMillis(200))
                .initializationTimeout(Duration.ofMillis(200))
                .build();
    }

    private void stubSubscription(McpServerDetailInfo detail) throws NacosException {
        when(discoveryClient.subscribe(
                        eq(SERVER_NAME), any(), any(AbstractNacosMcpServerListener.class)))
                .thenReturn(detail);
    }

    private AbstractNacosMcpServerListener captureListener() throws NacosException {
        ArgumentCaptor<AbstractNacosMcpServerListener> captor =
                ArgumentCaptor.forClass(AbstractNacosMcpServerListener.class);
        verify(discoveryClient).subscribe(eq(SERVER_NAME), any(), captor.capture());
        return captor.getValue();
    }

    private void push(AbstractNacosMcpServerListener listener, McpServerDetailInfo detail) {
        listener.onEvent(new NacosMcpServerEvent(detail));
    }

    /**
     * Pushes a snapshot and waits until the wrapper has applied it. A push is reconciled on the
     * background reconcile thread, so the effect is not visible when {@code onEvent} returns.
     */
    private void pushAndAwait(
            AbstractNacosMcpServerListener listener,
            McpServerDetailInfo detail,
            BooleanSupplier applied) {
        push(listener, detail);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!applied.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("Timed out waiting for the pushed snapshot to be reconciled");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for the pushed snapshot to be reconciled");
            }
        }
    }

    private StubMcpClient stub(McpEndpointInfo info) {
        return stubs.get(resolved(info).key());
    }

    private static Set<String> keys(List<NacosMcpEndpoint> endpoints) {
        return endpoints.stream().map(NacosMcpEndpoint::key).collect(Collectors.toSet());
    }

    private long connectionsFor(McpEndpointInfo info) {
        String key = resolved(info).key();
        synchronized (attempts) {
            return attempts.stream()
                    .filter(attempt -> attempt.endpoint().key().equals(key))
                    .count();
        }
    }

    private record ConnectedAttempt(
            String clientName, NacosMcpEndpoint endpoint, String protocol) {}

    /**
     * A stub MCP client that records its lifecycle, so that reconciliation can be asserted on the
     * connections rather than on the registry snapshot.
     */
    private static final class StubMcpClient extends McpClientWrapper {

        private final boolean failInitialize;

        private final boolean failCall;

        private final AtomicInteger initializeCount = new AtomicInteger();

        private final AtomicInteger listToolsCount = new AtomicInteger();

        private final AtomicInteger callCount = new AtomicInteger();

        private final AtomicInteger closeCount = new AtomicInteger();

        StubMcpClient(NacosMcpEndpoint endpoint) {
            this(endpoint, false, false);
        }

        StubMcpClient(NacosMcpEndpoint endpoint, boolean failInitialize, boolean failCall) {
            super(endpoint.key());
            this.failInitialize = failInitialize;
            this.failCall = failCall;
        }

        @Override
        public Mono<Void> initialize() {
            initializeCount.incrementAndGet();
            if (failInitialize) {
                return Mono.error(new IllegalStateException("endpoint is down"));
            }
            return Mono.fromRunnable(() -> initialized = true);
        }

        @Override
        public Mono<List<McpSchema.Tool>> listTools() {
            listToolsCount.incrementAndGet();
            return Mono.just(List.of());
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(
                String toolName, Map<String, Object> arguments) {
            return callTool(toolName, arguments, null);
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(
                String toolName, Map<String, Object> arguments, Map<String, Object> meta) {
            callCount.incrementAndGet();
            if (failCall) {
                return Mono.error(new IllegalStateException("endpoint is down"));
            }
            return Mono.just(mock(McpSchema.CallToolResult.class));
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
            initialized = false;
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderValidation {

        @Test
        @DisplayName("Should reject a null client name")
        void shouldRejectNullName() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> NacosLoadBalancedMcpClientWrapper.builder(null));
        }

        @Test
        @DisplayName("Should reject a blank client name")
        void shouldRejectBlankName() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> NacosLoadBalancedMcpClientWrapper.builder("   "));
        }

        @Test
        @DisplayName("Should require a discovery client")
        void shouldRequireDiscoveryClient() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            NacosLoadBalancedMcpClientWrapper.builder("weather")
                                    .serverName(SERVER_NAME)
                                    .build());
        }

        @Test
        @DisplayName("Should require a server name")
        void shouldRequireServerName() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            NacosLoadBalancedMcpClientWrapper.builder("weather")
                                    .discoveryClient(discoveryClient)
                                    .build());
        }

        @Test
        @DisplayName("Should reject a blank server name")
        void shouldRejectBlankServerName() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            NacosLoadBalancedMcpClientWrapper.builder("weather")
                                    .discoveryClient(discoveryClient)
                                    .serverName(" ")
                                    .build());
        }

        @Test
        @DisplayName("Should expose the configured identity before initialization")
        void shouldExposeConfiguredIdentity() {
            version = "1.0.0";
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();

            assertEquals("weather", wrapper.getName());
            assertEquals(SERVER_NAME, wrapper.getServerName());
            assertEquals("1.0.0", wrapper.getVersion());
            assertTrue(wrapper.getRegisteredEndpoints().isEmpty());
            assertEquals(0, wrapper.getConnectedEndpointCount());
            assertFalse(wrapper.isInitialized());
        }
    }

    @Nested
    @DisplayName("Initialization")
    class Initialization {

        @Test
        @DisplayName("Should stay empty when the server is not registered yet")
        void shouldStayEmptyWhenServerMissing() throws NacosException {
            stubSubscription(null);
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();

            wrapper.initialize().block();

            assertTrue(wrapper.isInitialized());
            assertTrue(wrapper.getRegisteredEndpoints().isEmpty());
            assertEquals(0, wrapper.getConnectedEndpointCount());
            verify(discoveryClient)
                    .subscribe(eq(SERVER_NAME), any(), any(AbstractNacosMcpServerListener.class));
        }

        @Test
        @DisplayName("Should open exactly one connection per registered endpoint")
        void shouldConnectOneClientPerRegisteredEndpoint() throws NacosException {
            McpEndpointInfo first = endpoint("127.0.0.1", 18081);
            McpEndpointInfo second = endpoint("127.0.0.1", 18082);
            stubSubscription(detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, first, second));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();

            wrapper.initialize().block();

            assertEquals(
                    Set.of(resolved(first).key(), resolved(second).key()),
                    keys(wrapper.getRegisteredEndpoints()));
            assertEquals(2, wrapper.getConnectedEndpointCount());
            assertEquals(1, connectionsFor(first));
            assertEquals(1, connectionsFor(second));
            assertEquals(1, stub(first).initializeCount.get());
        }

        @Test
        @DisplayName("Should subscribe only once when initialized repeatedly")
        void shouldSubscribeOnlyOnce() throws NacosException {
            stubSubscription(
                    detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, endpoint("127.0.0.1", 18081)));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();

            wrapper.initialize().block();
            wrapper.initialize().block();

            verify(discoveryClient, times(1))
                    .subscribe(eq(SERVER_NAME), any(), any(AbstractNacosMcpServerListener.class));
        }

        @Test
        @DisplayName("Should forward the configured version to the registry")
        void shouldForwardConfiguredVersion() throws NacosException {
            stubSubscription(null);
            version = "3.2.1";
            wrapper().initialize().block();

            verify(discoveryClient)
                    .subscribe(
                            eq(SERVER_NAME),
                            eq("3.2.1"),
                            any(AbstractNacosMcpServerListener.class));
        }

        @Test
        @DisplayName("Should connect endpoints that appear after initialization")
        void shouldConnectEndpointsAppearingAfterInitialization() throws NacosException {
            stubSubscription(null);
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();
            wrapper.initialize().block();
            AbstractNacosMcpServerListener listener = captureListener();
            McpEndpointInfo late = endpoint("127.0.0.1", 18081);

            pushAndAwait(
                    listener,
                    detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, late),
                    () -> wrapper.getConnectedEndpointCount() == 1);

            assertEquals(1, wrapper.getConnectedEndpointCount());
            assertEquals(Set.of(resolved(late).key()), keys(wrapper.getRegisteredEndpoints()));
        }

        @Test
        @DisplayName("Should ignore the initial snapshot when a push arrived while subscribing")
        void shouldIgnoreStaleInitialSnapshot() throws NacosException {
            McpEndpointInfo stale = endpoint("127.0.0.1", 18081);
            McpEndpointInfo pushed = endpoint("127.0.0.1", 18082);
            when(discoveryClient.subscribe(
                            eq(SERVER_NAME), any(), any(AbstractNacosMcpServerListener.class)))
                    .thenAnswer(
                            invocation -> {
                                AbstractNacosMcpServerListener listener = invocation.getArgument(2);
                                push(
                                        listener,
                                        detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, pushed));
                                return detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, stale);
                            });
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();

            wrapper.initialize().block();

            // the pushed snapshot is newer than the one subscribe() returned
            assertEquals(Set.of(resolved(pushed).key()), keys(wrapper.getRegisteredEndpoints()));
            assertEquals(1, wrapper.getConnectedEndpointCount());
            assertEquals(0, connectionsFor(stale));
        }

        @Test
        @DisplayName("Should retry an endpoint that failed to connect and close the failed client")
        void shouldRetryEndpointThatFailedToConnect() throws NacosException {
            McpEndpointInfo flaky = endpoint("127.0.0.1", 18081);
            McpEndpointInfo healthy = endpoint("127.0.0.1", 18082);
            AtomicInteger provided = new AtomicInteger();
            stubProvider =
                    endpoint -> {
                        boolean firstAttempt = provided.getAndIncrement() == 0;
                        return endpoint.key().equals(resolved(flaky).key()) && firstAttempt
                                ? new StubMcpClient(endpoint, true, false)
                                : new StubMcpClient(endpoint);
                    };
            stubSubscription(detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, flaky, healthy));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();
            wrapper.initialize().block();

            assertEquals(1, wrapper.getConnectedEndpointCount());
            StubMcpClient failed = stub(flaky);
            assertEquals(1, failed.closeCount.get());

            AbstractNacosMcpServerListener listener = captureListener();
            pushAndAwait(
                    listener,
                    detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, flaky, healthy),
                    () -> wrapper.getConnectedEndpointCount() == 2);

            assertEquals(2, wrapper.getConnectedEndpointCount());
            assertEquals(2, connectionsFor(flaky));
        }
    }

    @Nested
    @DisplayName("Tool operations")
    class ToolOperations {

        @Test
        @DisplayName("Should refuse to list tools before initialization")
        void shouldRefuseListToolsBeforeInitialize() {
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();

            assertThrows(IllegalStateException.class, () -> wrapper.listTools().block());
        }

        @Test
        @DisplayName("Should refuse tool calls before initialization")
        void shouldRefuseCallToolBeforeInitialize() {
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();

            assertThrows(
                    IllegalStateException.class,
                    () -> wrapper.callTool("get_weather", Map.of()).block());
        }

        @Test
        @DisplayName("Should refuse tool calls when no endpoint is registered")
        void shouldRefuseCallToolWithoutEndpoint() throws NacosException {
            stubSubscription(null);
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();
            wrapper.initialize().block();

            assertThrows(
                    IllegalStateException.class,
                    () -> wrapper.callTool("get_weather", Map.of()).block());
        }

        @Test
        @DisplayName("Should list tools from a connected endpoint")
        void shouldListToolsFromConnectedEndpoint() throws NacosException {
            stubSubscription(
                    detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, endpoint("127.0.0.1", 18081)));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();
            wrapper.initialize().block();

            assertTrue(wrapper.listTools().block().isEmpty());
            assertEquals(1, stub(endpoint("127.0.0.1", 18081)).listToolsCount.get());
        }

        @Test
        @DisplayName("Should list no tools while no endpoint is connected")
        void shouldListNoToolsWithoutConnectedEndpoint() throws NacosException {
            stubProvider = endpoint -> new StubMcpClient(endpoint, true, false);
            stubSubscription(
                    detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, endpoint("127.0.0.1", 18081)));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();
            wrapper.initialize().block();

            assertTrue(wrapper.listTools().block().isEmpty());
            assertEquals(0, wrapper.getConnectedEndpointCount());
        }

        @Test
        @DisplayName("Should fail over to a healthy endpoint when the selected one fails")
        void shouldFailOverToHealthyEndpoint() throws NacosException {
            McpEndpointInfo dead = endpoint("127.0.0.1", 18081);
            McpEndpointInfo healthy = endpoint("127.0.0.1", 18082);
            stubProvider =
                    endpoint ->
                            endpoint.key().equals(resolved(dead).key())
                                    ? new StubMcpClient(endpoint, false, true)
                                    : new StubMcpClient(endpoint);
            stubSubscription(detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, dead, healthy));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();
            wrapper.initialize().block();

            // round-robin selects the first endpoint, which fails the call
            assertDoesNotThrow(() -> wrapper.callTool("get_weather", Map.of()).block());

            assertEquals(1, stub(dead).callCount.get());
            assertEquals(1, stub(healthy).callCount.get());
        }

        @Test
        @DisplayName("Should fail over for tool calls carrying metadata")
        void shouldFailOverForToolCallsWithMeta() throws NacosException {
            McpEndpointInfo dead = endpoint("127.0.0.1", 18081);
            McpEndpointInfo healthy = endpoint("127.0.0.1", 18082);
            stubProvider =
                    endpoint ->
                            endpoint.key().equals(resolved(dead).key())
                                    ? new StubMcpClient(endpoint, false, true)
                                    : new StubMcpClient(endpoint);
            stubSubscription(detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, dead, healthy));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();
            wrapper.initialize().block();

            assertDoesNotThrow(
                    () ->
                            wrapper.callTool("get_weather", Map.of("city", "Hangzhou"), Map.of())
                                    .block());

            assertEquals(1, stub(healthy).callCount.get());
        }

        @Test
        @DisplayName("Should fail the call only when every connected endpoint fails")
        void shouldFailWhenEveryEndpointFails() throws NacosException {
            McpEndpointInfo first = endpoint("127.0.0.1", 18081);
            McpEndpointInfo second = endpoint("127.0.0.1", 18082);
            stubProvider = endpoint -> new StubMcpClient(endpoint, false, true);
            stubSubscription(detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, first, second));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();
            wrapper.initialize().block();

            assertThrows(
                    IllegalStateException.class,
                    () -> wrapper.callTool("get_weather", Map.of()).block());

            assertEquals(1, stub(first).callCount.get());
            assertEquals(1, stub(second).callCount.get());
        }

        @Test
        @DisplayName("Should skip an endpoint that never connected")
        void shouldSkipEndpointWithoutConnection() throws NacosException {
            McpEndpointInfo dead = endpoint("127.0.0.1", 18081);
            McpEndpointInfo healthy = endpoint("127.0.0.1", 18082);
            stubProvider =
                    endpoint ->
                            endpoint.key().equals(resolved(dead).key())
                                    ? new StubMcpClient(endpoint, true, false)
                                    : new StubMcpClient(endpoint);
            stubSubscription(detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, dead, healthy));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();
            wrapper.initialize().block();

            assertDoesNotThrow(() -> wrapper.callTool("get_weather", Map.of()).block());

            assertEquals(0, stub(dead).callCount.get());
            assertEquals(1, stub(healthy).callCount.get());
        }

        @Test
        @DisplayName("Should fail the tool call when no endpoint can be reached")
        void shouldFailCallToolWhenNoEndpointConnected() throws NacosException, IOException {
            stubSubscription(
                    detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, unreachableEndpoint()));
            NacosLoadBalancedMcpClientWrapper wrapper = realTransportWrapper();
            wrapper.initialize().block();

            assertThrows(
                    IllegalStateException.class,
                    () -> wrapper.callTool("get_weather", Map.of()).block());
            wrapper.close();
        }
    }

    @Nested
    @DisplayName("Endpoint updates pushed by Nacos")
    class EndpointUpdates {

        @Test
        @DisplayName("Should ignore an event without detail info")
        void shouldIgnoreEventWithoutDetailInfo() throws NacosException {
            stubSubscription(null);
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();
            wrapper.initialize().block();
            AbstractNacosMcpServerListener listener = captureListener();

            NacosMcpServerEvent event = mock(NacosMcpServerEvent.class);
            when(event.getMcpServerDetailInfo()).thenReturn(null);
            listener.onEvent(event);

            assertTrue(wrapper.getRegisteredEndpoints().isEmpty());
            assertEquals(0, wrapper.getConnectedEndpointCount());
        }

        @Test
        @DisplayName("Should connect only the endpoints added by a push")
        void shouldAddEndpointsOnPush() throws NacosException {
            McpEndpointInfo existing = endpoint("127.0.0.1", 18081);
            McpEndpointInfo added = endpoint("127.0.0.1", 18082);
            stubSubscription(detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, existing));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();
            wrapper.initialize().block();
            StubMcpClient existingStub = stub(existing);
            AbstractNacosMcpServerListener listener = captureListener();

            pushAndAwait(
                    listener,
                    detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, existing, added),
                    () -> wrapper.getConnectedEndpointCount() == 2);

            assertEquals(2, wrapper.getConnectedEndpointCount());
            assertEquals(1, connectionsFor(added));
            // the already connected endpoint is reused, not reconnected
            assertEquals(1, existingStub.initializeCount.get());
            assertEquals(0, existingStub.closeCount.get());
        }

        @Test
        @DisplayName("Should keep the endpoints when a push reports no change")
        void shouldKeepEndpointsOnUnchangedPush() throws NacosException, InterruptedException {
            McpEndpointInfo first = endpoint("127.0.0.1", 18081);
            McpEndpointInfo second = endpoint("127.0.0.1", 18082);
            stubSubscription(detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, first, second));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();
            wrapper.initialize().block();
            AbstractNacosMcpServerListener listener = captureListener();

            push(listener, detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, first, second));
            // An unchanged push produces no positive signal to wait for, so let the reconcile
            // thread finish and then assert that nothing was reconnected or closed.
            Thread.sleep(200);

            assertEquals(2, attempts.size());
            assertEquals(2, wrapper.getConnectedEndpointCount());
            assertEquals(0, stub(first).closeCount.get());
            assertEquals(0, stub(second).closeCount.get());
        }

        @Test
        @DisplayName("Should close the connection of an endpoint scaled in")
        void shouldCloseConnectionOfRemovedEndpoint() throws NacosException {
            McpEndpointInfo removed = endpoint("127.0.0.1", 18081);
            McpEndpointInfo kept = endpoint("127.0.0.1", 18082);
            stubSubscription(detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, removed, kept));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();
            wrapper.initialize().block();
            StubMcpClient removedStub = stub(removed);
            AbstractNacosMcpServerListener listener = captureListener();

            pushAndAwait(
                    listener,
                    detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, kept),
                    () -> wrapper.getRegisteredEndpoints().size() == 1);

            assertEquals(Set.of(resolved(kept).key()), keys(wrapper.getRegisteredEndpoints()));
            assertEquals(1, wrapper.getConnectedEndpointCount());
            assertEquals(1, removedStub.closeCount.get());
            assertEquals(0, stub(kept).closeCount.get());
        }

        @Test
        @DisplayName("Should rebuild every connection when the protocol changes")
        void shouldRebuildOnProtocolChange() throws NacosException {
            McpEndpointInfo first = endpoint("127.0.0.1", 18081);
            McpEndpointInfo second = endpoint("127.0.0.1", 18082);
            stubSubscription(detail(AiConstants.Mcp.MCP_PROTOCOL_SSE, first));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();
            wrapper.initialize().block();
            StubMcpClient sseStub = stub(first);
            AbstractNacosMcpServerListener listener = captureListener();

            pushAndAwait(
                    listener,
                    detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, first, second),
                    () -> wrapper.getConnectedEndpointCount() == 2);

            assertEquals(1, sseStub.closeCount.get());
            assertEquals(2, wrapper.getConnectedEndpointCount());
            synchronized (attempts) {
                assertEquals(
                        List.of(
                                AiConstants.Mcp.MCP_PROTOCOL_SSE,
                                AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE,
                                AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE),
                        attempts.stream().map(ConnectedAttempt::protocol).toList());
            }
        }

        @Test
        @DisplayName("Should connect a duplicated endpoint only once")
        void shouldIgnoreDuplicatedEndpoints() throws NacosException {
            McpEndpointInfo duplicated = endpoint("127.0.0.1", 18081);
            stubSubscription(
                    detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, duplicated, duplicated));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();

            wrapper.initialize().block();

            assertEquals(1, connectionsFor(duplicated));
            assertEquals(1, wrapper.getConnectedEndpointCount());
        }
    }

    @Nested
    @DisplayName("Real transport")
    class RealTransport {

        @Test
        @DisplayName("Should leave an endpoint unconnected when the transport cannot reach it")
        void shouldNotConnectUnreachableEndpoint() throws NacosException, IOException {
            McpEndpointInfo unreachable = unreachableEndpoint();
            stubSubscription(detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, unreachable));
            NacosLoadBalancedMcpClientWrapper wrapper = realTransportWrapper();

            wrapper.initialize().block();

            assertEquals(
                    Set.of(resolved(unreachable).key()), keys(wrapper.getRegisteredEndpoints()));
            assertEquals(0, wrapper.getConnectedEndpointCount());
            assertTrue(wrapper.listTools().block().isEmpty());
            wrapper.close();
        }
    }

    @Nested
    @DisplayName("Waiting for a connection")
    class AwaitingConnection {

        @Test
        @DisplayName("Should give up when no endpoint connects within the timeout")
        void shouldGiveUpAfterTimeout() throws NacosException {
            stubSubscription(null);
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();
            wrapper.initialize().block();

            assertFalse(wrapper.awaitConnectedEndpoint(Duration.ofMillis(50)));
        }

        @Test
        @DisplayName("Should return as soon as an endpoint connects while waiting")
        void shouldReturnWhenEndpointConnects() throws Exception {
            stubSubscription(null);
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();
            wrapper.initialize().block();
            AbstractNacosMcpServerListener listener = captureListener();

            Thread pusher =
                    new Thread(
                            () -> {
                                try {
                                    Thread.sleep(100);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                push(
                                        listener,
                                        detail(
                                                AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE,
                                                endpoint("127.0.0.1", 18081)));
                            });
            pusher.start();
            try {
                assertTrue(wrapper.awaitConnectedEndpoint(Duration.ofSeconds(5)));
            } finally {
                pusher.join();
            }
            assertEquals(1, wrapper.getConnectedEndpointCount());
        }

        @Test
        @DisplayName("Should stop waiting after close")
        void shouldStopWaitingAfterClose() throws NacosException {
            stubSubscription(null);
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();
            wrapper.initialize().block();

            wrapper.close();

            assertFalse(wrapper.awaitConnectedEndpoint(Duration.ofSeconds(1)));
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("Should unsubscribe and close every connection on close")
        void shouldUnsubscribeAndResetOnClose() throws NacosException {
            McpEndpointInfo first = endpoint("127.0.0.1", 18081);
            McpEndpointInfo second = endpoint("127.0.0.1", 18082);
            stubSubscription(detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, first, second));
            version = "1.0.0";
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();
            wrapper.initialize().block();
            StubMcpClient firstStub = stub(first);
            StubMcpClient secondStub = stub(second);

            wrapper.close();

            verify(discoveryClient)
                    .unsubscribe(
                            eq(SERVER_NAME),
                            eq("1.0.0"),
                            any(AbstractNacosMcpServerListener.class));
            assertEquals(1, firstStub.closeCount.get());
            assertEquals(1, secondStub.closeCount.get());
            assertFalse(wrapper.isInitialized());
            assertTrue(wrapper.getRegisteredEndpoints().isEmpty());
            assertEquals(0, wrapper.getConnectedEndpointCount());
        }

        @Test
        @DisplayName("Should not unsubscribe when the wrapper was never initialized")
        void shouldNotUnsubscribeWhenNeverInitialized() throws NacosException {
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();

            wrapper.close();

            verify(discoveryClient, never())
                    .unsubscribe(anyString(), any(), any(AbstractNacosMcpServerListener.class));
            assertFalse(wrapper.isInitialized());
        }

        @Test
        @DisplayName("Should swallow a Nacos failure while unsubscribing")
        void shouldSwallowUnsubscribeFailure() throws NacosException {
            stubSubscription(
                    detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, endpoint("127.0.0.1", 18081)));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();
            wrapper.initialize().block();
            doThrow(new NacosException(500, "registry unavailable"))
                    .when(discoveryClient)
                    .unsubscribe(anyString(), any(), any(AbstractNacosMcpServerListener.class));

            assertDoesNotThrow(wrapper::close);
            assertFalse(wrapper.isInitialized());
        }

        @Test
        @DisplayName("Should close each connection only once when closed repeatedly")
        void shouldUnsubscribeOnlyOnceOnRepeatedClose() throws NacosException {
            McpEndpointInfo first = endpoint("127.0.0.1", 18081);
            stubSubscription(detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, first));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();
            wrapper.initialize().block();
            StubMcpClient stub = stub(first);

            wrapper.close();
            wrapper.close();

            verify(discoveryClient, times(1))
                    .unsubscribe(anyString(), any(), any(AbstractNacosMcpServerListener.class));
            assertEquals(1, stub.closeCount.get());
        }

        @Test
        @DisplayName("Should refuse to initialize a closed wrapper")
        void shouldRefuseToInitializeAfterClose() {
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();
            wrapper.close();

            RuntimeException error =
                    assertThrows(RuntimeException.class, () -> wrapper.initialize().block());

            assertTrue(
                    error instanceof IllegalStateException
                            || error.getCause() instanceof IllegalStateException,
                    "expected an IllegalStateException, got " + error);
        }

        @Test
        @DisplayName("Should ignore pushes that arrive after close")
        void shouldIgnorePushesAfterClose() throws NacosException {
            McpEndpointInfo first = endpoint("127.0.0.1", 18081);
            McpEndpointInfo late = endpoint("127.0.0.1", 18082);
            stubSubscription(detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, first));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper();
            wrapper.initialize().block();
            AbstractNacosMcpServerListener listener = captureListener();
            int attemptsBeforeClose = attempts.size();

            wrapper.close();
            push(listener, detail(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE, first, late));

            assertEquals(attemptsBeforeClose, attempts.size());
            assertEquals(0, wrapper.getConnectedEndpointCount());
            verify(discoveryClient, times(1))
                    .unsubscribe(anyString(), any(), any(AbstractNacosMcpServerListener.class));
        }
    }
}
