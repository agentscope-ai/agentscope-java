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

package io.agentscope.core.nacos.a2a.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.ability.constant.AbilityStatus;
import com.alibaba.nacos.api.ai.A2aService;
import com.alibaba.nacos.api.ai.AgentService;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.constant.AiConstants;
import com.alibaba.nacos.api.ai.model.a2a.AgentCardDetailInfo;
import com.alibaba.nacos.api.ai.model.a2a.AgentEndpoint;
import com.alibaba.nacos.api.ai.model.agent.EndpointSource;
import com.alibaba.nacos.api.ai.model.agent.client.AgentPublishRequest;
import com.alibaba.nacos.api.ai.utils.A2aEndpointUtils;
import com.alibaba.nacos.api.exception.NacosException;
import java.util.List;
import java.util.Properties;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link NacosA2aRegistry}.
 */
@ExtendWith(MockitoExtension.class)
class NacosA2aRegistryTest {

    @Mock private A2aService a2aService;

    private NacosA2aRegistry nacosA2aRegistry;

    @BeforeEach
    void setUp() {
        nacosA2aRegistry = new NacosA2aRegistry(a2aService);
    }

    @Test
    @DisplayName("Should create NacosA2aRegistry with Properties successfully")
    void testCreateWithProperties() throws NacosException {
        Properties properties = new Properties();
        properties.put("serverAddr", "localhost:8848");
        AiService service = mock(AiService.class);

        try (MockedStatic<AiFactory> mockedFactory = mockStatic(AiFactory.class)) {
            mockedFactory.when(() -> AiFactory.createAiService(eq(properties))).thenReturn(service);

            NacosA2aRegistry registry = new NacosA2aRegistry(properties);

            assertNotNull(registry);
            mockedFactory.verify(() -> AiFactory.createAiService(eq(properties)));
        }
    }

    @Test
    @DisplayName("Should register agent card and endpoints successfully")
    void testRegisterAgent() throws NacosException {
        AgentCard agentCard = mockAgentCard();

        NacosA2aRegistryProperties properties =
                NacosA2aRegistryProperties.builder().enabledRegisterEndpoint(true).build();

        nacosA2aRegistry.registerAgent(agentCard, properties);

        verify(a2aService).releaseAgentCard(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("Should handle NacosException during agent card registration")
    void testRegisterAgentHandlesNacosException() throws NacosException {
        AgentCard agentCard = mockAgentCard();

        NacosA2aRegistryProperties properties =
                NacosA2aRegistryProperties.builder().enabledRegisterEndpoint(true).build();

        NacosException nacosException = new NacosException(500, "Test error");
        doThrow(nacosException).when(a2aService).releaseAgentCard(any(), any(), anyBoolean());

        assertThrows(
                RuntimeException.class,
                () -> nacosA2aRegistry.registerAgent(agentCard, properties));
    }

    @Test
    @DisplayName("Should register endpoint when enabledRegisterEndpoint is true")
    void testRegisterEndpointWhenEnabled() throws NacosException {
        AgentCard agentCard = mockAgentCard();

        NacosA2aRegistryTransportProperties transportProperties =
                NacosA2aRegistryTransportProperties.builder()
                        .transport("HTTP")
                        .host("localhost")
                        .port(8080)
                        .build();

        NacosA2aRegistryProperties properties =
                NacosA2aRegistryProperties.builder().enabledRegisterEndpoint(true).build();
        properties.addTransport(transportProperties);

        nacosA2aRegistry.registerAgent(agentCard, properties);

        verify(a2aService).releaseAgentCard(any(), any(), anyBoolean());
        verify(a2aService).registerAgentEndpoint(eq("test-agent"), any(AgentEndpoint.class));
    }

    @Test
    @DisplayName("Should skip endpoint registration when enabledRegisterEndpoint is false")
    void testSkipEndpointRegistrationWhenDisabled() throws NacosException {
        AgentCard agentCard = mockAgentCard();

        NacosA2aRegistryProperties properties =
                NacosA2aRegistryProperties.builder().enabledRegisterEndpoint(false).build();

        nacosA2aRegistry.registerAgent(agentCard, properties);

        verify(a2aService).releaseAgentCard(any(), any(), anyBoolean());
        verify(a2aService, never()).registerAgentEndpoint(anyString(), any(AgentEndpoint.class));
    }

    @Test
    @DisplayName("Should skip endpoint registration when transport properties is empty")
    void testSkipEndpointRegistrationWhenTransportPropertiesEmpty() throws NacosException {
        AgentCard agentCard = mockAgentCard();

        NacosA2aRegistryProperties properties =
                NacosA2aRegistryProperties.builder().enabledRegisterEndpoint(true).build();

        nacosA2aRegistry.registerAgent(agentCard, properties);

        verify(a2aService).releaseAgentCard(any(), any(), anyBoolean());
        verify(a2aService, never()).registerAgentEndpoint(anyString(), any(AgentEndpoint.class));
    }

    @Test
    @DisplayName("Should handle agent card when setAsLatest is true")
    void testHandleAgentCardWhenSetAsLatestIsTrue() throws NacosException {
        AgentCard agentCard = mockAgentCard();
        NacosA2aRegistryProperties properties =
                NacosA2aRegistryProperties.builder().setAsLatest(true).build();
        NacosA2aRegistryTransportProperties transportProperties =
                NacosA2aRegistryTransportProperties.builder()
                        .transport("HTTP")
                        .host("localhost")
                        .port(8080)
                        .build();
        properties.addTransport(transportProperties);
        nacosA2aRegistry.registerAgent(agentCard, properties);
        verify(a2aService).releaseAgentCard(any(), any(), eq(true));
        verify(a2aService).registerAgentEndpoint(eq("test-agent"), any(AgentEndpoint.class));
    }

    @Test
    @DisplayName("Should handle multiple endpoints registration")
    void testRegisterMultipleEndpoints() throws NacosException {
        AgentCard agentCard = mockAgentCard();

        NacosA2aRegistryTransportProperties transportProperties1 =
                NacosA2aRegistryTransportProperties.builder()
                        .transport("HTTP")
                        .host("localhost")
                        .port(8080)
                        .build();

        NacosA2aRegistryTransportProperties transportProperties2 =
                NacosA2aRegistryTransportProperties.builder()
                        .transport("WEBSOCKET")
                        .host("localhost")
                        .port(8081)
                        .build();

        NacosA2aRegistryProperties properties =
                NacosA2aRegistryProperties.builder().enabledRegisterEndpoint(true).build();
        properties.addTransport(transportProperties1);
        properties.addTransport(transportProperties2);

        nacosA2aRegistry.registerAgent(agentCard, properties);

        verify(a2aService).releaseAgentCard(any(), any(), anyBoolean());
        verify(a2aService).registerAgentEndpoint(eq("test-agent"), anyCollection());
    }

    @Test
    @DisplayName("Should handle agent card that already exists")
    void testHandleExistingAgentCard() throws NacosException {
        AgentCard agentCard = mockAgentCard();

        NacosA2aRegistryProperties properties =
                NacosA2aRegistryProperties.builder().enabledRegisterEndpoint(true).build();

        // Mock the getAgentCard method to return a non-null value (indicating agent card already
        // exists)
        when(a2aService.getAgentCard(eq("test-agent"), eq("1.0.0")))
                .thenReturn(mock(AgentCardDetailInfo.class));

        nacosA2aRegistry.registerAgent(agentCard, properties);

        verify(a2aService).getAgentCard(eq("test-agent"), eq("1.0.0"));
        verify(a2aService).releaseAgentCard(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("Should handle NacosException in tryGetAgentCardFromNacos gracefully")
    void testHandleNacosExceptionInGetAgentCard() throws NacosException {
        AgentCard agentCard = mockAgentCard();

        NacosA2aRegistryProperties properties =
                NacosA2aRegistryProperties.builder().enabledRegisterEndpoint(true).build();

        NacosException nacosException = new NacosException(404, "Agent card not found");
        when(a2aService.getAgentCard(eq("test-agent"), eq("1.0.0"))).thenThrow(nacosException);

        nacosA2aRegistry.registerAgent(agentCard, properties);

        verify(a2aService).getAgentCard(eq("test-agent"), eq("1.0.0"));
        verify(a2aService).releaseAgentCard(any(), any(), anyBoolean());
    }

    @Test
    void publishesNativeDefinitionWithTenantAndPerInterfaceVersions() throws Exception {
        AiService client = mock(AiService.class);
        AgentService nativeService = mock(AgentService.class);
        when(client.getAgentDiscoveryCapability()).thenReturn(AbilityStatus.SUPPORTED);
        when(client.agent()).thenReturn(nativeService);
        AgentCard card =
                AgentCard.builder(tenantCard())
                        .provider(
                                new org.a2aproject.sdk.spec.AgentProvider(
                                        "Test Org", "https://example.test"))
                        .iconUrl("https://example.test/icon.svg")
                        .build();
        new NacosA2aRegistry(client)
                .registerAgent(
                        card,
                        NacosA2aRegistryProperties.builder()
                                .setAsLatest(true)
                                .enabledRegisterEndpoint(false)
                                .build());
        ArgumentCaptor<AgentPublishRequest> request =
                ArgumentCaptor.forClass(AgentPublishRequest.class);
        verify(nativeService).publishAgent(request.capture());
        verify(client, never()).releaseAgentCard(any(), any(), anyBoolean());
        assertEquals("test-agent", request.getValue().getAgentName());
        assertEquals("Test Org", request.getValue().getProvider().getName());
        assertEquals("https://example.test", request.getValue().getProvider().getUrl());
        assertEquals("https://example.test/icon.svg", request.getValue().getIconUrl());
        assertEquals("1.0.0", request.getValue().getVersion());
        assertEquals(true, request.getValue().isAutoSubmit());
        var call = request.getValue().getCallInterfaces().get(0);
        assertEquals("a2a", call.getProtocol());
        assertEquals(
                List.of(EndpointSource.RUNTIME, EndpointSource.DECLARED),
                call.getEndpointSourceOrder());
        assertEquals(EndpointSource.DECLARED, call.getEndpointSets().get(0).getSource());
        var endpoints = call.getEndpointSets().get(0).getEndpoints();
        assertEquals(2, endpoints.size());
        assertEquals("team-a", endpoints.get(0).getMetadata().get(AiConstants.A2a.ENDPOINT_TENANT));
        assertEquals(
                "1.0",
                endpoints.get(0).getMetadata().get(AiConstants.A2a.ENDPOINT_PROTOCOL_VERSION));
        assertEquals("team-b", endpoints.get(1).getMetadata().get(AiConstants.A2a.ENDPOINT_TENANT));
        assertEquals(
                "1.1",
                endpoints.get(1).getMetadata().get(AiConstants.A2a.ENDPOINT_PROTOCOL_VERSION));
        assertEquals("team-a", card.supportedInterfaces().get(0).tenant());
    }

    @Test
    void explicitNativeServicePreservesEmptyTenantAndSubmissionChoice() throws Exception {
        AgentService service = mock(AgentService.class);
        AgentCard card =
                AgentCard.builder(mockAgentCard())
                        .supportedInterfaces(
                                List.of(
                                        new AgentInterface(
                                                "JSONRPC", "http://in.card:8080", "", "1.0")))
                        .build();
        new NacosA2aRegistry(service)
                .registerAgent(
                        card,
                        NacosA2aRegistryProperties.builder()
                                .setAsLatest(false)
                                .enabledRegisterEndpoint(false)
                                .build());
        ArgumentCaptor<AgentPublishRequest> request =
                ArgumentCaptor.forClass(AgentPublishRequest.class);
        verify(service).publishAgent(request.capture());
        assertEquals(false, request.getValue().isAutoSubmit());
        assertEquals(
                "",
                request.getValue()
                        .getCallInterfaces()
                        .get(0)
                        .getEndpointSets()
                        .get(0)
                        .getEndpoints()
                        .get(0)
                        .getMetadata()
                        .get(AiConstants.A2a.ENDPOINT_TENANT));
    }

    @Test
    void oldServerKeepsLegacyPublication() throws Exception {
        AiService client = mock(AiService.class);
        when(client.getAgentDiscoveryCapability()).thenReturn(AbilityStatus.NOT_SUPPORTED);
        new NacosA2aRegistry(client)
                .registerAgent(
                        tenantCard(),
                        NacosA2aRegistryProperties.builder()
                                .enabledRegisterEndpoint(false)
                                .build());
        verify(client).releaseAgentCard(any(), any(), eq(false));
        verify(client, never()).agent();
    }

    @Test
    void unknownCapabilityDoesNotSilentlyDropNativeMetadata() throws Exception {
        AiService client = mock(AiService.class);
        when(client.getAgentDiscoveryCapability()).thenReturn(AbilityStatus.UNKNOWN);
        assertThrows(
                IllegalStateException.class,
                () ->
                        new NacosA2aRegistry(client)
                                .registerAgent(
                                        tenantCard(),
                                        NacosA2aRegistryProperties.builder().build()));
        verify(client, never()).releaseAgentCard(any(), any(), anyBoolean());
        verify(client, never()).agent();
    }

    @Test
    void configuredRuntimeEndpointInheritsInterfaceMetadata() throws Exception {
        var config = NacosA2aRegistryProperties.builder().build();
        config.addTransport(
                NacosA2aRegistryTransportProperties.builder()
                        .transport("HTTP+JSON")
                        .host("published.example")
                        .port(8443)
                        .supportTls(true)
                        .path("/remote")
                        .build());
        nacosA2aRegistry.registerAgent(tenantCard(), config);
        ArgumentCaptor<AgentEndpoint> endpoint = ArgumentCaptor.forClass(AgentEndpoint.class);
        verify(a2aService).registerAgentEndpoint(eq("test-agent"), endpoint.capture());
        assertEquals("team-b", endpoint.getValue().getTenant());
        assertEquals("1.1", endpoint.getValue().getProtocolVersion());
        var nativeEndpoint = A2aEndpointUtils.toEndpoint(endpoint.getValue());
        assertEquals("https://published.example:8443/remote", nativeEndpoint.getUri());
        assertEquals("team-b", nativeEndpoint.getMetadata().get(AiConstants.A2a.ENDPOINT_TENANT));
    }

    @Test
    void ambiguousRuntimeInterfaceMetadataIsRejectedBeforePublication() throws Exception {
        AgentCard card =
                AgentCard.builder(mockAgentCard())
                        .supportedInterfaces(
                                List.of(
                                        new AgentInterface(
                                                "JSONRPC", "http://first:8080", "team-a", "1.0"),
                                        new AgentInterface(
                                                "JSONRPC", "http://second:8080", "team-b", "1.0")))
                        .build();
        var config = NacosA2aRegistryProperties.builder().build();
        config.addTransport(
                NacosA2aRegistryTransportProperties.builder()
                        .transport("JSONRPC")
                        .host("external")
                        .port(8080)
                        .build());
        assertThrows(
                IllegalArgumentException.class, () -> nacosA2aRegistry.registerAgent(card, config));
        verify(a2aService, never()).releaseAgentCard(any(), any(), anyBoolean());
    }

    @Test
    void runtimeUrlSelectsTheMatchingInterfaceWhenTransportIsShared() throws Exception {
        AgentCard card =
                AgentCard.builder(mockAgentCard())
                        .supportedInterfaces(
                                List.of(
                                        new AgentInterface(
                                                "JSONRPC", "http://first:8080", "team-a", "1.0"),
                                        new AgentInterface(
                                                "JSONRPC", "http://second:8080", "team-b", "1.1")))
                        .build();
        var config = NacosA2aRegistryProperties.builder().build();
        config.addTransport(
                NacosA2aRegistryTransportProperties.builder()
                        .transport("JSONRPC")
                        .host("second")
                        .port(8080)
                        .build());
        nacosA2aRegistry.registerAgent(card, config);
        ArgumentCaptor<AgentEndpoint> endpoint = ArgumentCaptor.forClass(AgentEndpoint.class);
        verify(a2aService).registerAgentEndpoint(eq("test-agent"), endpoint.capture());
        assertEquals("team-b", endpoint.getValue().getTenant());
        assertEquals("1.1", endpoint.getValue().getProtocolVersion());
    }

    @Test
    void nativePublicationFailurePreservesErrorWithoutLegacyFallback() throws Exception {
        AiService client = mock(AiService.class);
        AgentService service = mock(AgentService.class);
        when(client.getAgentDiscoveryCapability()).thenReturn(AbilityStatus.SUPPORTED);
        when(client.agent()).thenReturn(service);
        NacosException denied = new NacosException(403, "publication denied");
        doThrow(denied).when(service).publishAgent(any());
        var failure =
                assertThrows(
                        com.alibaba.nacos.api.exception.runtime.NacosRuntimeException.class,
                        () ->
                                new NacosA2aRegistry(client)
                                        .registerAgent(
                                                tenantCard(),
                                                NacosA2aRegistryProperties.builder()
                                                        .enabledRegisterEndpoint(false)
                                                        .build()));
        assertEquals(403, failure.getErrCode());
        assertEquals(denied, failure.getCause());
        verify(client, never()).releaseAgentCard(any(), any(), anyBoolean());
    }

    private AgentCard tenantCard() {
        return AgentCard.builder(mockAgentCard())
                .supportedInterfaces(
                        List.of(
                                new AgentInterface(
                                        "JSONRPC", "http://in.card:8080/agent", "team-a", "1.0"),
                                new AgentInterface(
                                        "HTTP+JSON", "http://in.card:8080/rest", "team-b", "1.1")))
                .build();
    }

    private AgentCard mockAgentCard() {
        return AgentCard.builder()
                .name("test-agent")
                .description("test")
                .capabilities(AgentCapabilities.builder().build())
                .defaultInputModes(List.of())
                .defaultOutputModes(List.of())
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", "http://in.card:8080")))
                .version("1.0.0")
                .skills(List.of())
                .build();
    }

    @Test
    void doesNotPublishAfterUnauthorizedLookup() throws NacosException {
        when(a2aService.getAgentCard("test-agent", "1.0.0"))
                .thenThrow(new NacosException(403, "permission denied"));
        assertThrows(
                RuntimeException.class,
                () ->
                        nacosA2aRegistry.registerAgent(
                                mockAgentCard(), NacosA2aRegistryProperties.builder().build()));
        verify(a2aService, never()).releaseAgentCard(any(), any(), anyBoolean());
    }
}
