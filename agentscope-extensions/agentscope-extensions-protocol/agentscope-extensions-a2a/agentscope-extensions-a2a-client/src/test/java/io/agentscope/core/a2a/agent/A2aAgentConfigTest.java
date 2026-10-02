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
package io.agentscope.core.a2a.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.a2a.client.config.ClientConfig;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfig;
import io.a2a.client.transport.spi.ClientTransport;
import io.a2a.client.transport.spi.ClientTransportConfig;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for A2aAgentConfig.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Builder pattern functionality</li>
 *   <li>Client transports configuration</li>
 *   <li>Client config setting</li>
 *   <li>Builder method chaining</li>
 * </ul>
 */
@DisplayName("A2aAgentConfig Tests")
class A2aAgentConfigTest {

    @Test
    @DisplayName("Should create builder instance")
    void testBuilderCreation() {
        A2aAgentConfig.A2aAgentConfigBuilder builder = A2aAgentConfig.builder();
        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should build config with default values using builder")
    void testBuilderWithDefaults() {
        A2aAgentConfig config = A2aAgentConfig.builder().build();

        assertNotNull(config.clientTransports());
        assertTrue(config.clientTransports().isEmpty());
        assertNull(config.clientConfig());
    }

    @Test
    @DisplayName("Should set client transport configuration")
    void testWithTransport() {
        A2aAgentConfig.A2aAgentConfigBuilder builder = A2aAgentConfig.builder();
        Class<ClientTransport> transportClass = ClientTransport.class;
        ClientTransportConfig<ClientTransport> transportConfig = mock(ClientTransportConfig.class);

        A2aAgentConfig.A2aAgentConfigBuilder result =
                builder.withTransport(transportClass, transportConfig);

        assertSame(builder, result); // Check method chaining
        A2aAgentConfig config = builder.build();
        @SuppressWarnings("rawtypes")
        Map<Class, ClientTransportConfig> transports = config.clientTransports();
        assertNotNull(transports);
        assertEquals(1, transports.size());
        assertTrue(transports.containsKey(transportClass));
        assertEquals(transportConfig, transports.get(transportClass));
    }

    @Test
    @DisplayName("Should set client config")
    void testClientConfig() {
        A2aAgentConfig.A2aAgentConfigBuilder builder = A2aAgentConfig.builder();
        ClientConfig clientConfig = mock(ClientConfig.class);

        A2aAgentConfig.A2aAgentConfigBuilder result = builder.clientConfig(clientConfig);

        assertSame(builder, result); // Check method chaining
        A2aAgentConfig config = builder.build();
        assertEquals(clientConfig, config.clientConfig());
    }

    @Test
    @DisplayName("Should support builder method chaining")
    void testBuilderMethodChaining() {
        A2aAgentConfig.A2aAgentConfigBuilder builder = A2aAgentConfig.builder();
        Class<ClientTransport> transportClass = ClientTransport.class;
        ClientTransportConfig<ClientTransport> transportConfig = mock(ClientTransportConfig.class);
        ClientConfig clientConfig = mock(ClientConfig.class);

        A2aAgentConfig.A2aAgentConfigBuilder result =
                builder.withTransport(transportClass, transportConfig).clientConfig(clientConfig);

        assertNotNull(result);
        assertSame(builder, result);

        A2aAgentConfig config = builder.build();
        assertNotNull(config.clientTransports());
        assertEquals(1, config.clientTransports().size());
        assertTrue(config.clientTransports().containsKey(transportClass));
        assertEquals(transportConfig, config.clientTransports().get(transportClass));
        assertEquals(clientConfig, config.clientConfig());
    }

    @Test
    @DisplayName("Should build multiple independent config instances")
    void testMultipleConfigInstances() {
        Class<ClientTransport> transportClass1 = ClientTransport.class;
        ClientTransportConfig<ClientTransport> transportConfig1 = mock(ClientTransportConfig.class);
        ClientConfig clientConfig1 = mock(ClientConfig.class);

        Class<ClientTransport> transportClass2 = ClientTransport.class;
        ClientTransportConfig<ClientTransport> transportConfig2 = mock(ClientTransportConfig.class);
        ClientConfig clientConfig2 = mock(ClientConfig.class);

        A2aAgentConfig config1 =
                A2aAgentConfig.builder()
                        .withTransport(transportClass1, transportConfig1)
                        .clientConfig(clientConfig1)
                        .build();

        A2aAgentConfig config2 =
                A2aAgentConfig.builder()
                        .withTransport(transportClass2, transportConfig2)
                        .clientConfig(clientConfig2)
                        .build();

        assertNotNull(config1.clientTransports());
        assertEquals(1, config1.clientTransports().size());
        assertEquals(clientConfig1, config1.clientConfig());

        assertNotNull(config2.clientTransports());
        assertEquals(1, config2.clientTransports().size());
        assertEquals(clientConfig2, config2.clientConfig());
    }

    @Test
    @DisplayName("Should allow adding multiple transports")
    void testMultipleTransports() {
        A2aAgentConfig.A2aAgentConfigBuilder builder = A2aAgentConfig.builder();
        Class<JSONRPCTransport> transportClass1 = JSONRPCTransport.class;
        JSONRPCTransportConfig transportConfig1 = mock(JSONRPCTransportConfig.class);

        Class<ClientTransport> transportClass2 = ClientTransport.class;
        ClientTransportConfig<ClientTransport> transportConfig2 = mock(ClientTransportConfig.class);

        builder.withTransport(transportClass1, transportConfig1)
                .withTransport(transportClass2, transportConfig2);
        A2aAgentConfig config = builder.build();

        @SuppressWarnings("rawtypes")
        Map<Class, ClientTransportConfig> transports = config.clientTransports();
        assertNotNull(transports);
        assertEquals(2, transports.size());
        assertTrue(transports.containsKey(transportClass1));
        assertTrue(transports.containsKey(transportClass2));
        assertEquals(transportConfig1, transports.get(transportClass1));
        assertEquals(transportConfig2, transports.get(transportClass2));
    }
}
