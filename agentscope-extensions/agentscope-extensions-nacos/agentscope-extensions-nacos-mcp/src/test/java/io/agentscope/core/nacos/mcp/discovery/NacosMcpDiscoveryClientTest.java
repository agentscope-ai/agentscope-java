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
package io.agentscope.core.nacos.mcp.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.constant.AiConstants;
import com.alibaba.nacos.api.ai.listener.AbstractNacosMcpServerListener;
import com.alibaba.nacos.api.ai.listener.NacosMcpServerEvent;
import com.alibaba.nacos.api.ai.model.mcp.McpEndpointInfo;
import com.alibaba.nacos.api.ai.model.mcp.McpServerDetailInfo;
import com.alibaba.nacos.api.ai.model.mcp.McpServerRemoteServiceConfig;
import com.alibaba.nacos.api.exception.NacosException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NacosMcpDiscoveryClient}, covering the Nacos subscription overloads and the
 * endpoint resolution rules applied to {@code McpServerDetailInfo} pushed by the registry.
 */
class NacosMcpDiscoveryClientTest {

    private static final String SERVER_NAME = "weather-mcp-server";

    private final AiService aiService = mock(AiService.class);

    private final AbstractNacosMcpServerListener listener =
            new AbstractNacosMcpServerListener() {
                @Override
                public void onEvent(NacosMcpServerEvent event) {
                    // no-op listener, only used to verify subscription routing
                }
            };

    private NacosMcpDiscoveryClient client() {
        return new NacosMcpDiscoveryClient(aiService);
    }

    private static McpEndpointInfo endpoint(String address, int port) {
        McpEndpointInfo info = new McpEndpointInfo();
        info.setAddress(address);
        info.setPort(port);
        return info;
    }

    private static McpServerDetailInfo detail(
            List<McpEndpointInfo> backend, List<McpEndpointInfo> frontend) {
        McpServerDetailInfo detail = new McpServerDetailInfo();
        detail.setName(SERVER_NAME);
        detail.setProtocol(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE);
        detail.setBackendEndpoints(backend);
        detail.setFrontendEndpoints(frontend);
        return detail;
    }

    @Nested
    @DisplayName("Subscription")
    class Subscription {

        @Test
        @DisplayName("Should subscribe without a version when the version is null")
        void shouldSubscribeWithoutVersionWhenNull() throws NacosException {
            McpServerDetailInfo detail = detail(List.of(endpoint("10.0.0.1", 8080)), null);
            when(aiService.subscribeMcpServer(
                            eq(SERVER_NAME), any(AbstractNacosMcpServerListener.class)))
                    .thenReturn(detail);

            McpServerDetailInfo result = client().subscribe(SERVER_NAME, null, listener);

            assertSame(detail, result);
            verify(aiService)
                    .subscribeMcpServer(eq(SERVER_NAME), any(AbstractNacosMcpServerListener.class));
            verify(aiService, never())
                    .subscribeMcpServer(
                            anyString(), anyString(), any(AbstractNacosMcpServerListener.class));
        }

        @Test
        @DisplayName("Should subscribe without a version when the version is empty")
        void shouldSubscribeWithoutVersionWhenEmpty() throws NacosException {
            when(aiService.subscribeMcpServer(
                            eq(SERVER_NAME), any(AbstractNacosMcpServerListener.class)))
                    .thenReturn(null);

            client().subscribe(SERVER_NAME, "", listener);

            verify(aiService)
                    .subscribeMcpServer(eq(SERVER_NAME), any(AbstractNacosMcpServerListener.class));
        }

        @Test
        @DisplayName("Should subscribe with the version when one is given")
        void shouldSubscribeWithVersion() throws NacosException {
            McpServerDetailInfo detail = detail(List.of(endpoint("10.0.0.1", 8080)), null);
            when(aiService.subscribeMcpServer(
                            eq(SERVER_NAME),
                            eq("1.0.0"),
                            any(AbstractNacosMcpServerListener.class)))
                    .thenReturn(detail);

            McpServerDetailInfo result = client().subscribe(SERVER_NAME, "1.0.0", listener);

            assertSame(detail, result);
            verify(aiService)
                    .subscribeMcpServer(
                            eq(SERVER_NAME),
                            eq("1.0.0"),
                            any(AbstractNacosMcpServerListener.class));
            verify(aiService, never())
                    .subscribeMcpServer(anyString(), any(AbstractNacosMcpServerListener.class));
        }

        @Test
        @DisplayName("Should propagate the Nacos exception when subscription fails")
        void shouldPropagateSubscribeFailure() throws NacosException {
            when(aiService.subscribeMcpServer(
                            eq(SERVER_NAME), any(AbstractNacosMcpServerListener.class)))
                    .thenThrow(new NacosException(500, "registry unavailable"));

            assertThrows(
                    NacosException.class, () -> client().subscribe(SERVER_NAME, null, listener));
        }

        @Test
        @DisplayName("Should unsubscribe without a version when the version is null")
        void shouldUnsubscribeWithoutVersionWhenNull() throws NacosException {
            client().unsubscribe(SERVER_NAME, null, listener);

            verify(aiService)
                    .unsubscribeMcpServer(
                            eq(SERVER_NAME), any(AbstractNacosMcpServerListener.class));
        }

        @Test
        @DisplayName("Should unsubscribe without a version when the version is empty")
        void shouldUnsubscribeWithoutVersionWhenEmpty() throws NacosException {
            client().unsubscribe(SERVER_NAME, "", listener);

            verify(aiService)
                    .unsubscribeMcpServer(
                            eq(SERVER_NAME), any(AbstractNacosMcpServerListener.class));
        }

        @Test
        @DisplayName("Should unsubscribe with the version when one is given")
        void shouldUnsubscribeWithVersion() throws NacosException {
            client().unsubscribe(SERVER_NAME, "2.1.0", listener);

            verify(aiService)
                    .unsubscribeMcpServer(
                            eq(SERVER_NAME),
                            eq("2.1.0"),
                            any(AbstractNacosMcpServerListener.class));
        }
    }

    @Nested
    @DisplayName("Endpoint resolution")
    class EndpointResolution {

        @Test
        @DisplayName("Should return no endpoint when the detail info is null")
        void shouldReturnEmptyForNullDetailInfo() {
            assertTrue(NacosMcpDiscoveryClient.resolveEndpoints(null).isEmpty());
        }

        @Test
        @DisplayName("Should prefer backend endpoints")
        void shouldPreferBackendEndpoints() {
            McpServerDetailInfo detail =
                    detail(
                            List.of(endpoint("10.0.0.1", 8080)),
                            List.of(endpoint("10.0.0.9", 9090)));

            List<NacosMcpEndpoint> endpoints = NacosMcpDiscoveryClient.resolveEndpoints(detail);

            assertEquals(1, endpoints.size());
            assertEquals("10.0.0.1", endpoints.get(0).getAddress());
            assertEquals(8080, endpoints.get(0).getPort());
        }

        @Test
        @DisplayName("Should fall back to frontend endpoints when backend endpoints are absent")
        void shouldFallBackToFrontendWhenBackendAbsent() {
            McpServerDetailInfo detail = detail(null, List.of(endpoint("10.0.0.9", 9090)));

            List<NacosMcpEndpoint> endpoints = NacosMcpDiscoveryClient.resolveEndpoints(detail);

            assertEquals(1, endpoints.size());
            assertEquals("10.0.0.9", endpoints.get(0).getAddress());
        }

        @Test
        @DisplayName("Should fall back to frontend endpoints when backend endpoints are empty")
        void shouldFallBackToFrontendWhenBackendEmpty() {
            McpServerDetailInfo detail = detail(List.of(), List.of(endpoint("10.0.0.9", 9090)));

            List<NacosMcpEndpoint> endpoints = NacosMcpDiscoveryClient.resolveEndpoints(detail);

            assertEquals(1, endpoints.size());
            assertEquals("10.0.0.9", endpoints.get(0).getAddress());
        }

        @Test
        @DisplayName("Should return no endpoint when neither backend nor frontend endpoints exist")
        void shouldReturnEmptyWhenNoEndpoints() {
            assertTrue(NacosMcpDiscoveryClient.resolveEndpoints(detail(null, null)).isEmpty());
        }

        @Test
        @DisplayName("Should skip null endpoint entries and entries without an address")
        void shouldSkipUnusableEndpointEntries() {
            McpEndpointInfo withoutAddress = new McpEndpointInfo();
            withoutAddress.setPort(8080);
            McpServerDetailInfo detail =
                    detail(Arrays.asList(endpoint("10.0.0.1", 8080), null, withoutAddress), null);

            List<NacosMcpEndpoint> endpoints = NacosMcpDiscoveryClient.resolveEndpoints(detail);

            assertEquals(1, endpoints.size());
            assertEquals("10.0.0.1", endpoints.get(0).getAddress());
        }

        @Test
        @DisplayName("Should use the export path from the remote server config")
        void shouldUseExportPathFromRemoteServerConfig() {
            McpEndpointInfo info = endpoint("10.0.0.1", 8080);
            info.setPath("/from-endpoint");
            McpServerDetailInfo detail = detail(List.of(info), null);
            McpServerRemoteServiceConfig remoteConfig = new McpServerRemoteServiceConfig();
            remoteConfig.setExportPath("/mcp-from-config");
            detail.setRemoteServerConfig(remoteConfig);

            List<NacosMcpEndpoint> endpoints = NacosMcpDiscoveryClient.resolveEndpoints(detail);

            assertEquals("/mcp-from-config", endpoints.get(0).getPath());
            assertEquals("http://10.0.0.1:8080/mcp-from-config", endpoints.get(0).url());
        }

        @Test
        @DisplayName("Should fall back to the endpoint path when no export path is configured")
        void shouldFallBackToEndpointPath() {
            McpEndpointInfo info = endpoint("10.0.0.1", 8080);
            info.setPath("/from-endpoint");
            McpServerDetailInfo detail = detail(List.of(info), null);

            List<NacosMcpEndpoint> endpoints = NacosMcpDiscoveryClient.resolveEndpoints(detail);

            assertEquals("/from-endpoint", endpoints.get(0).getPath());
        }

        @Test
        @DisplayName("Should infer https for the well-known TLS ports")
        void shouldInferHttpsForTlsPorts() {
            McpServerDetailInfo detail =
                    detail(List.of(endpoint("10.0.0.1", 443), endpoint("10.0.0.2", 8443)), null);

            List<NacosMcpEndpoint> endpoints = NacosMcpDiscoveryClient.resolveEndpoints(detail);

            assertEquals("https", endpoints.get(0).getScheme());
            assertEquals("https", endpoints.get(1).getScheme());
        }

        @Test
        @DisplayName("Should default to http for other ports")
        void shouldDefaultToHttpForOtherPorts() {
            McpServerDetailInfo detail = detail(List.of(endpoint("10.0.0.1", 8080)), null);

            assertEquals(
                    "http", NacosMcpDiscoveryClient.resolveEndpoints(detail).get(0).getScheme());
        }

        @Test
        @DisplayName("Should keep the protocol declared by the registry")
        void shouldKeepDeclaredProtocol() {
            McpEndpointInfo info = endpoint("10.0.0.1", 8080);
            info.setProtocol("https");
            McpServerDetailInfo detail = detail(List.of(info), null);

            assertEquals(
                    "https", NacosMcpDiscoveryClient.resolveEndpoints(detail).get(0).getScheme());
        }
    }
}
