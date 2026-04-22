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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.ai.AiService;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for {@link NacosAgentRegistry}.
 */
@ExtendWith(MockitoExtension.class)
class NacosAgentRegistryTest {

    @Mock private NacosA2aRegistry nacosA2aRegistry;

    private NacosA2aRegistryProperties nacosA2aProperties;

    private NacosAgentRegistry nacosAgentRegistry;

    @BeforeEach
    void setUp() throws Exception {
        nacosA2aProperties = NacosA2aRegistryProperties.builder().build();
        // Create NacosAgentRegistry using builder with a mock AiService
        nacosAgentRegistry =
                NacosAgentRegistry.builder(mock(AiService.class))
                        .nacosA2aProperties(nacosA2aProperties)
                        .build();

        injectMockNacosA2aRegistry(nacosAgentRegistry);
    }

    private void injectMockNacosA2aRegistry(NacosAgentRegistry nacosAgentRegistry)
            throws NoSuchFieldException, IllegalAccessException {
        // Use reflection to replace the private nacosA2aRegistry field with our mock
        Field registryField = NacosAgentRegistry.class.getDeclaredField("nacosA2aRegistry");
        registryField.setAccessible(true);
        registryField.set(nacosAgentRegistry, nacosA2aRegistry);
    }

    @Test
    @DisplayName("Should create NacosAgentRegistry with Properties using builder")
    void testBuilderWithProperties() throws Exception {
        Properties properties = new Properties();
        properties.put("serverAddr", "localhost:8848");
        NacosA2aRegistryProperties registryProperties = mock(NacosA2aRegistryProperties.class);

        try (MockedConstruction<NacosA2aRegistry> mockedRegistry =
                mockConstruction(NacosA2aRegistry.class)) {
            NacosAgentRegistry registry =
                    NacosAgentRegistry.builder(properties)
                            .nacosA2aProperties(registryProperties)
                            .build();

            assertNotNull(registry);
            assertEquals("Nacos", registry.registryName());
            assertEquals(1, mockedRegistry.constructed().size());
        }
    }

    @Test
    @DisplayName("Should create registry with default properties when not provided")
    void testBuilderWithDefaultProperties() {
        AiService aiService = mock(AiService.class);

        try (MockedConstruction<NacosA2aRegistry> mockedRegistry =
                mockConstruction(NacosA2aRegistry.class)) {
            NacosAgentRegistry registry = NacosAgentRegistry.builder(aiService).build();

            assertNotNull(registry);
            assertEquals("Nacos", registry.registryName());
            assertEquals(1, mockedRegistry.constructed().size());
        }
    }

    @Test
    @DisplayName("Should throw exception when building without AiService")
    void testBuilderWithoutAiService() {
        NacosA2aRegistryProperties properties = mock(NacosA2aRegistryProperties.class);

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                NacosAgentRegistry.builder((AiService) null)
                                        .nacosA2aProperties(properties)
                                        .build());

        assertEquals("Nacos AI Client can not be null.", exception.getMessage());
    }

    @Test
    @DisplayName("Should return correct registry name")
    void testRegistryName() {
        String registryName = nacosAgentRegistry.registryName();

        assertEquals("Nacos", registryName);
    }

    @Test
    @DisplayName("Should register agent card with transport properties")
    void testRegister() {
        AgentCard agentCard = mockAgentCard();
        List<TransportProperties> transportPropertiesList =
                mockTransportProperties("JSONRPC", "REST");

        AtomicReference<NacosA2aRegistryProperties> targetProperties = new AtomicReference<>();
        doAnswer(
                        (Answer<Void>)
                                invocationOnMock -> {
                                    targetProperties.set(invocationOnMock.getArgument(1));
                                    return null;
                                })
                .when(nacosA2aRegistry)
                .registerAgent(any(AgentCard.class), any(NacosA2aRegistryProperties.class));
        nacosAgentRegistry.register(agentCard, transportPropertiesList);
        assertNotNull(targetProperties.get());
        assertEquals(2, targetProperties.get().transportProperties().size());
        assertNotNull(targetProperties.get().transportProperties().get("JSONRPC"));
        assertNotNull(targetProperties.get().transportProperties().get("REST"));
        verify(nacosA2aRegistry)
                .registerAgent(eq(agentCard), any(NacosA2aRegistryProperties.class));
    }

    @Test
    @DisplayName("Should handle register with empty transport properties list")
    void testRegisterWithEmptyTransportProperties() {
        AgentCard agentCard = mockAgentCard();
        List<TransportProperties> emptyTransportProperties = List.of();

        nacosAgentRegistry.register(agentCard, emptyTransportProperties);

        assertTrue(nacosA2aProperties.transportProperties().isEmpty());
        verify(nacosA2aRegistry).registerAgent(eq(agentCard), eq(nacosA2aProperties));
    }

    @Test
    @DisplayName("Should handle overwrite preferredProtocol in agent card")
    void testOverwritePreferredProtocolInAgentCard()
            throws NoSuchFieldException, IllegalAccessException {
        nacosA2aProperties =
                NacosA2aRegistryProperties.builder().overwritePreferredTransport("JSONRPC").build();
        nacosAgentRegistry =
                NacosAgentRegistry.builder(mock(AiService.class))
                        .nacosA2aProperties(nacosA2aProperties)
                        .build();
        injectMockNacosA2aRegistry(nacosAgentRegistry);

        AgentCard agentCard = mockAgentCard();
        List<TransportProperties> transportPropertiesList = mockTransportProperties("JSONRPC");

        doAnswer(
                        (Answer<Void>)
                                invocationOnMock -> {
                                    AgentCard target = invocationOnMock.getArgument(0);
                                    assertAgentCard(
                                            target, "https://localhost:8080/agent", "JSONRPC");
                                    return null;
                                })
                .when(nacosA2aRegistry)
                .registerAgent(any(AgentCard.class), any(NacosA2aRegistryProperties.class));
        nacosAgentRegistry.register(agentCard, transportPropertiesList);
        verify(nacosA2aRegistry)
                .registerAgent(any(AgentCard.class), any(NacosA2aRegistryProperties.class));
    }

    @Test
    @DisplayName("Should handle overwrite non-exist preferredProtocol in agent card")
    void testOverwriteNonExistPreferredProtocolInAgentCard()
            throws NoSuchFieldException, IllegalAccessException {
        nacosA2aProperties =
                NacosA2aRegistryProperties.builder()
                        .overwritePreferredTransport("ROCKETMQ")
                        .build();
        nacosAgentRegistry =
                NacosAgentRegistry.builder(mock(AiService.class))
                        .nacosA2aProperties(nacosA2aProperties)
                        .build();
        injectMockNacosA2aRegistry(nacosAgentRegistry);

        AgentCard agentCard = mockAgentCard();
        List<TransportProperties> transportPropertiesList = mockTransportProperties("JSONRPC");

        doAnswer(
                        (Answer<Void>)
                                invocationOnMock -> {
                                    AgentCard target = invocationOnMock.getArgument(0);
                                    assertAgentCard(target, "http://in.card:8080", "JSONRPC");
                                    return null;
                                })
                .when(nacosA2aRegistry)
                .registerAgent(any(AgentCard.class), any(NacosA2aRegistryProperties.class));
        nacosAgentRegistry.register(agentCard, transportPropertiesList);
        verify(nacosA2aRegistry)
                .registerAgent(eq(agentCard), any(NacosA2aRegistryProperties.class));
    }

    @Test
    @DisplayName("Should handle overwrite transport by env")
    void testOverwriteTransportByEnv() {
        try (MockedConstruction<NacosA2aTransportPropertiesEnvParser> mockedParser =
                mockConstruction(
                        NacosA2aTransportPropertiesEnvParser.class,
                        (mock, ctx) ->
                                when(mock.getTransportProperties())
                                        .thenReturn(mockTransportMap("JSONRPC", "REST")))) {
            AgentCard agentCard = mockAgentCard();
            List<TransportProperties> transportPropertiesList = mockTransportProperties("JSONRPC");
            AtomicReference<NacosA2aRegistryProperties> targetProperties = new AtomicReference<>();
            doAnswer(
                            (Answer<Void>)
                                    invocationOnMock -> {
                                        targetProperties.set(invocationOnMock.getArgument(1));
                                        return null;
                                    })
                    .when(nacosA2aRegistry)
                    .registerAgent(any(AgentCard.class), any(NacosA2aRegistryProperties.class));
            nacosAgentRegistry.register(agentCard, transportPropertiesList);
            assertNotNull(targetProperties.get());
            assertEquals(2, targetProperties.get().transportProperties().size());
            assertNotNull(targetProperties.get().transportProperties().get("JSONRPC"));
            assertEquals(8888, targetProperties.get().transportProperties().get("JSONRPC").port());
            assertEquals(
                    "key1=value1",
                    targetProperties.get().transportProperties().get("JSONRPC").query());
            assertFalse(targetProperties.get().transportProperties().get("JSONRPC").supportTls());
            assertNotNull(targetProperties.get().transportProperties().get("REST"));
            verify(nacosA2aRegistry)
                    .registerAgent(eq(agentCard), any(NacosA2aRegistryProperties.class));
            assertEquals(1, mockedParser.constructed().size());
        }
    }

    @Test
    @DisplayName("Should handle overwrite transport by env and overwrite agent card")
    void testOverwriteTransportByEnvAndOverwriteAgentCard()
            throws NoSuchFieldException, IllegalAccessException {
        nacosA2aProperties =
                NacosA2aRegistryProperties.builder().overwritePreferredTransport("CUSTOM").build();
        nacosAgentRegistry =
                NacosAgentRegistry.builder(mock(AiService.class))
                        .nacosA2aProperties(nacosA2aProperties)
                        .build();
        injectMockNacosA2aRegistry(nacosAgentRegistry);
        try (MockedConstruction<NacosA2aTransportPropertiesEnvParser> mockedParser =
                mockConstruction(
                        NacosA2aTransportPropertiesEnvParser.class,
                        (mock, ctx) ->
                                when(mock.getTransportProperties())
                                        .thenReturn(mockTransportMap("CUSTOM")))) {
            AgentCard agentCard = mockAgentCard();
            List<TransportProperties> transportPropertiesList = mockTransportProperties("CUSTOM");
            AtomicReference<NacosA2aRegistryProperties> targetProperties = new AtomicReference<>();
            doAnswer(
                            (Answer<Void>)
                                    invocationOnMock -> {
                                        targetProperties.set(invocationOnMock.getArgument(1));
                                        return null;
                                    })
                    .when(nacosA2aRegistry)
                    .registerAgent(any(AgentCard.class), any(NacosA2aRegistryProperties.class));
            nacosAgentRegistry.register(agentCard, transportPropertiesList);
            assertNotNull(targetProperties.get());
            assertEquals(1, targetProperties.get().transportProperties().size());
            assertNotNull(targetProperties.get().transportProperties().get("CUSTOM"));
            NacosA2aRegistryTransportProperties customProperties =
                    targetProperties.get().transportProperties().get("CUSTOM");
            assertEquals(8888, customProperties.port());
            assertEquals("key1=value1", customProperties.query());
            assertFalse(customProperties.supportTls());
            assertEquals(1, mockedParser.constructed().size());
        }
    }

    private List<TransportProperties> mockTransportProperties(String... transportTypes) {
        List<TransportProperties> mock = new ArrayList<>(transportTypes.length);

        for (String transportType : transportTypes) {
            mock.add(
                    TransportProperties.builder(transportType)
                            .host("localhost")
                            .port(8080)
                            .supportTls(true)
                            .path("/agent")
                            .build());
        }
        return mock;
    }

    private AgentCard mockAgentCard() {
        return new AgentCard.Builder()
                .name("test")
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

    private void assertAgentCard(AgentCard target, String url, String transport) {
        assertEquals("test", target.name());
        assertEquals("test", target.description());
        assertEquals(url, target.url());
        assertEquals(transport, target.preferredTransport());
        assertEquals(new AgentCapabilities.Builder().build(), target.capabilities());
        assertEquals(List.of(), target.defaultInputModes());
        assertEquals(List.of(), target.defaultOutputModes());
        assertEquals(List.of(), target.skills());
        assertEquals("1.0.0", target.version());
    }

    private Map<String, NacosA2aRegistryTransportProperties> mockTransportMap(
            String... transportTypes) {
        Map<String, NacosA2aRegistryTransportProperties> mock = new HashMap<>();
        for (String transportType : transportTypes) {
            mock.put(
                    transportType,
                    NacosA2aRegistryTransportProperties.builder()
                            .host("localhost")
                            .port(8888)
                            .path("/agent")
                            .supportTls(false)
                            .transport("JSONRPC")
                            .query("key1=value1")
                            .protocol("HTTP")
                            .build());
        }
        return mock;
    }
}
