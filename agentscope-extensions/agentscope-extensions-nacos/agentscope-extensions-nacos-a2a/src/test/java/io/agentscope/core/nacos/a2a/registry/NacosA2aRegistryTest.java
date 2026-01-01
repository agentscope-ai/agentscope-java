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

import com.alibaba.nacos.api.ai.A2aService;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.a2a.AgentCardDetailInfo;
import com.alibaba.nacos.api.ai.model.a2a.AgentEndpoint;
import com.alibaba.nacos.api.exception.NacosException;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    private AgentCard mockAgentCard() {
        return new AgentCard.Builder()
                .name("test-agent")
                .description("test")
                .capabilities(new AgentCapabilities.Builder().build())
                .defaultInputModes(List.of())
                .defaultOutputModes(List.of())
                .url("http://in.card:8080")
                .preferredTransport("JSONRPC")
                .version("1.0.0")
                .skills(List.of())
                .build();
    }
}
