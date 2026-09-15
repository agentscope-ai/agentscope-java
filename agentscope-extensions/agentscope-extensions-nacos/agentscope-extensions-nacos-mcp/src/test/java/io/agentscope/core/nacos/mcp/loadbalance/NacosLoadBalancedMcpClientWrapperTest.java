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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link NacosLoadBalancedMcpClientWrapper}.
 *
 * <p>Endpoint connections are pointed at a closed loopback port so that the underlying MCP transport
 * fails immediately: this exercises the wrapper's endpoint bookkeeping, selection and failure
 * handling without requiring a live MCP server.
 */
class NacosLoadBalancedMcpClientWrapperTest {

    private static final String SERVER_NAME = "weather-mcp-server";

    private final NacosMcpDiscoveryClient discoveryClient = mock(NacosMcpDiscoveryClient.class);

    private static McpEndpointInfo unreachableEndpoint(String address) {
        McpEndpointInfo info = new McpEndpointInfo();
        info.setAddress(address);
        // port 1 on loopback refuses connections immediately
        info.setPort(1);
        info.setPath("/mcp");
        return info;
    }

    private static McpServerDetailInfo detail(String protocol, McpEndpointInfo... endpoints) {
        McpServerDetailInfo detail = new McpServerDetailInfo();
        detail.setName(SERVER_NAME);
        detail.setProtocol(protocol);
        detail.setBackendEndpoints(List.of(endpoints));
        return detail;
    }

    private NacosLoadBalancedMcpClientWrapper wrapper(String version) {
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
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper("1.0.0");

            assertEquals("weather", wrapper.getName());
            assertEquals(SERVER_NAME, wrapper.getServerName());
            assertEquals("1.0.0", wrapper.getVersion());
            assertTrue(wrapper.getCurrentEndpoints().isEmpty());
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
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper(null);

            wrapper.initialize().block();

            assertTrue(wrapper.isInitialized());
            assertTrue(wrapper.getCurrentEndpoints().isEmpty());
            verify(discoveryClient)
                    .subscribe(eq(SERVER_NAME), any(), any(AbstractNacosMcpServerListener.class));
        }

        @Test
        @DisplayName("Should track the endpoints pushed by the registry")
        void shouldTrackPushedEndpoints() throws NacosException {
            stubSubscription(
                    detail(
                            AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE,
                            unreachableEndpoint("10.0.0.1"),
                            unreachableEndpoint("10.0.0.2")));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper(null);

            wrapper.initialize().block();

            List<NacosMcpEndpoint> endpoints = wrapper.getCurrentEndpoints();
            assertEquals(2, endpoints.size());
            assertEquals("10.0.0.1", endpoints.get(0).getAddress());
            assertEquals("10.0.0.2", endpoints.get(1).getAddress());
        }

        @Test
        @DisplayName("Should subscribe only once when initialized repeatedly")
        void shouldSubscribeOnlyOnce() throws NacosException {
            stubSubscription(
                    detail(
                            AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE,
                            unreachableEndpoint("10.0.0.1")));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper(null);

            wrapper.initialize().block();
            wrapper.initialize().block();

            verify(discoveryClient, times(1))
                    .subscribe(eq(SERVER_NAME), any(), any(AbstractNacosMcpServerListener.class));
        }

        @Test
        @DisplayName("Should forward the configured version to the registry")
        void shouldForwardConfiguredVersion() throws NacosException {
            stubSubscription(null);
            wrapper("3.2.1").initialize().block();

            verify(discoveryClient)
                    .subscribe(
                            eq(SERVER_NAME),
                            eq("3.2.1"),
                            any(AbstractNacosMcpServerListener.class));
        }
    }

    @Nested
    @DisplayName("Tool operations")
    class ToolOperations {

        @Test
        @DisplayName("Should refuse to list tools before initialization")
        void shouldRefuseListToolsBeforeInitialize() {
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper(null);

            assertThrows(IllegalStateException.class, () -> wrapper.listTools().block());
        }

        @Test
        @DisplayName("Should refuse tool calls before initialization")
        void shouldRefuseCallToolBeforeInitialize() {
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper(null);

            assertThrows(
                    IllegalStateException.class,
                    () -> wrapper.callTool("get_weather", Map.of()).block());
        }

        @Test
        @DisplayName("Should refuse tool calls when no endpoint is available")
        void shouldRefuseCallToolWithoutEndpoint() throws NacosException {
            stubSubscription(null);
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper(null);
            wrapper.initialize().block();

            assertThrows(
                    IllegalStateException.class,
                    () -> wrapper.callTool("get_weather", Map.of()).block());
        }

        @Test
        @DisplayName("Should list no tools while no endpoint is connected")
        void shouldListNoToolsWithoutConnectedEndpoint() throws NacosException {
            stubSubscription(
                    detail(
                            AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE,
                            unreachableEndpoint("10.0.0.1")));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper(null);
            wrapper.initialize().block();

            assertTrue(wrapper.listTools().block().isEmpty());
        }

        @Test
        @DisplayName("Should fail the tool call when the selected endpoint is not connected")
        void shouldFailCallToolWhenSelectedEndpointNotConnected() throws NacosException {
            stubSubscription(
                    detail(
                            AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE,
                            unreachableEndpoint("10.0.0.1"),
                            unreachableEndpoint("10.0.0.2")));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper(null);
            wrapper.initialize().block();

            assertThrows(
                    IllegalStateException.class,
                    () -> wrapper.callTool("get_weather", Map.of()).block());
        }

        @Test
        @DisplayName("Should fail the tool call carrying metadata when no endpoint is connected")
        void shouldFailCallToolWithMetaWhenNotConnected() throws NacosException {
            stubSubscription(
                    detail(
                            AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE,
                            unreachableEndpoint("10.0.0.1")));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper(null);
            wrapper.initialize().block();

            assertThrows(
                    IllegalStateException.class,
                    () ->
                            wrapper.callTool("get_weather", Map.of("city", "Hangzhou"), Map.of())
                                    .block());
        }
    }

    @Nested
    @DisplayName("Endpoint updates pushed by Nacos")
    class EndpointUpdates {

        @Test
        @DisplayName("Should ignore an event without detail info")
        void shouldIgnoreEventWithoutDetailInfo() throws NacosException {
            stubSubscription(null);
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper(null);
            wrapper.initialize().block();
            AbstractNacosMcpServerListener listener = captureListener();

            NacosMcpServerEvent event = mock(NacosMcpServerEvent.class);
            when(event.getMcpServerDetailInfo()).thenReturn(null);
            listener.onEvent(event);

            assertTrue(wrapper.getCurrentEndpoints().isEmpty());
        }

        @Test
        @DisplayName("Should connect endpoints added by a Nacos push")
        void shouldAddEndpointsOnPush() throws NacosException {
            stubSubscription(
                    detail(
                            AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE,
                            unreachableEndpoint("10.0.0.1")));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper(null);
            wrapper.initialize().block();
            AbstractNacosMcpServerListener listener = captureListener();

            listener.onEvent(
                    new NacosMcpServerEvent(
                            detail(
                                    AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE,
                                    unreachableEndpoint("10.0.0.1"),
                                    unreachableEndpoint("10.0.0.2"))));

            assertEquals(2, wrapper.getCurrentEndpoints().size());
        }

        @Test
        @DisplayName("Should keep the endpoints when a Nacos push reports no change")
        void shouldKeepEndpointsOnUnchangedPush() throws NacosException {
            stubSubscription(
                    detail(
                            AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE,
                            unreachableEndpoint("10.0.0.1"),
                            unreachableEndpoint("10.0.0.2")));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper(null);
            wrapper.initialize().block();
            AbstractNacosMcpServerListener listener = captureListener();

            listener.onEvent(
                    new NacosMcpServerEvent(
                            detail(
                                    AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE,
                                    unreachableEndpoint("10.0.0.1"),
                                    unreachableEndpoint("10.0.0.2"))));

            assertEquals(2, wrapper.getCurrentEndpoints().size());
        }

        @Test
        @DisplayName("Should drop endpoints scaled in from Nacos")
        void shouldDropRemovedEndpoints() throws NacosException {
            stubSubscription(
                    detail(
                            AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE,
                            unreachableEndpoint("10.0.0.1"),
                            unreachableEndpoint("10.0.0.2")));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper(null);
            wrapper.initialize().block();
            AbstractNacosMcpServerListener listener = captureListener();

            listener.onEvent(
                    new NacosMcpServerEvent(
                            detail(
                                    AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE,
                                    unreachableEndpoint("10.0.0.2"))));

            List<NacosMcpEndpoint> endpoints = wrapper.getCurrentEndpoints();
            assertEquals(1, endpoints.size());
            assertEquals("10.0.0.2", endpoints.get(0).getAddress());
        }

        @Test
        @DisplayName("Should rebuild the endpoints when the protocol changes")
        void shouldRebuildOnProtocolChange() throws NacosException {
            stubSubscription(
                    detail(
                            AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE,
                            unreachableEndpoint("10.0.0.1")));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper(null);
            wrapper.initialize().block();
            AbstractNacosMcpServerListener listener = captureListener();

            listener.onEvent(
                    new NacosMcpServerEvent(
                            detail(
                                    AiConstants.Mcp.MCP_PROTOCOL_SSE,
                                    unreachableEndpoint("10.0.0.1"),
                                    unreachableEndpoint("10.0.0.2"))));

            assertEquals(2, wrapper.getCurrentEndpoints().size());
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("Should unsubscribe and reset the state on close")
        void shouldUnsubscribeAndResetOnClose() throws NacosException {
            stubSubscription(
                    detail(
                            AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE,
                            unreachableEndpoint("10.0.0.1")));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper("1.0.0");
            wrapper.initialize().block();

            wrapper.close();

            verify(discoveryClient)
                    .unsubscribe(
                            eq(SERVER_NAME),
                            eq("1.0.0"),
                            any(AbstractNacosMcpServerListener.class));
            assertFalse(wrapper.isInitialized());
            assertTrue(wrapper.getCurrentEndpoints().isEmpty());
        }

        @Test
        @DisplayName("Should not unsubscribe when the wrapper was never initialized")
        void shouldNotUnsubscribeWhenNeverInitialized() throws NacosException {
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper(null);

            wrapper.close();

            verify(discoveryClient, never())
                    .unsubscribe(anyString(), any(), any(AbstractNacosMcpServerListener.class));
            assertFalse(wrapper.isInitialized());
        }

        @Test
        @DisplayName("Should swallow a Nacos failure while unsubscribing")
        void shouldSwallowUnsubscribeFailure() throws NacosException {
            stubSubscription(
                    detail(
                            AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE,
                            unreachableEndpoint("10.0.0.1")));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper(null);
            wrapper.initialize().block();
            doThrow(new NacosException(500, "registry unavailable"))
                    .when(discoveryClient)
                    .unsubscribe(anyString(), any(), any(AbstractNacosMcpServerListener.class));

            assertDoesNotThrow(wrapper::close);
            assertFalse(wrapper.isInitialized());
        }

        @Test
        @DisplayName("Should unsubscribe only once when closed repeatedly")
        void shouldUnsubscribeOnlyOnceOnRepeatedClose() throws NacosException {
            stubSubscription(
                    detail(
                            AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE,
                            unreachableEndpoint("10.0.0.1")));
            NacosLoadBalancedMcpClientWrapper wrapper = wrapper(null);
            wrapper.initialize().block();

            wrapper.close();
            wrapper.close();

            verify(discoveryClient, times(1))
                    .unsubscribe(anyString(), any(), any(AbstractNacosMcpServerListener.class));
        }
    }
}
