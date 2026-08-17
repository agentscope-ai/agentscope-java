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

package io.agentscope.core.a2a.server.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("AgentRegistryService Tests")
class AgentRegistryServiceTest {

    private List<AgentRegistry> agentRegistries;
    private AgentRegistryService agentRegistryService;
    private AgentCard agentCard;
    private Set<TransportProperties> transportPropertiesSet;
    private TransportProperties transportProperties;

    @BeforeEach
    void setUp() {
        agentRegistries = new ArrayList<>();
        agentRegistryService = new AgentRegistryService(agentRegistries);

        agentCard = mock(AgentCard.class);
        when(agentCard.name()).thenReturn("TestAgent");

        transportProperties = mock(TransportProperties.class);
        transportPropertiesSet = new HashSet<>();
        transportPropertiesSet.add(transportProperties);
    }

    @Test
    @DisplayName("Should create AgentRegistryService with registries")
    void testConstructor() {
        List<AgentRegistry> registries = new ArrayList<>();
        AgentRegistryService service = new AgentRegistryService(registries);

        assertNotNull(service);
    }

    @Test
    @DisplayName("Should not register when agentCard is null")
    void testRegisterWithNullAgentCard() {
        agentRegistryService.register(null, transportPropertiesSet);

        // No exception should be thrown
        assertEquals(0, agentRegistries.size());
    }

    @Test
    @DisplayName("Should not register when transportProperties is null")
    void testRegisterWithNullTransportProperties() {
        agentRegistryService.register(agentCard, null);

        // No exception should be thrown
        assertEquals(0, agentRegistries.size());
    }

    @Test
    @DisplayName("Should not register when transportProperties is empty")
    void testRegisterWithEmptyTransportProperties() {
        agentRegistryService.register(agentCard, new HashSet<>());

        // No exception should be thrown
        assertEquals(0, agentRegistries.size());
    }

    @Test
    @DisplayName("Should register agent with single registry")
    void testRegisterWithSingleRegistry() {
        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.registryName()).thenReturn("TestRegistry");
        agentRegistries.add(registry);

        agentRegistryService.register(agentCard, transportPropertiesSet);

        // Verify register method was called once
        ArgumentCaptor<AgentCard> agentCardCaptor = ArgumentCaptor.forClass(AgentCard.class);
        ArgumentCaptor<List<TransportProperties>> transportPropertiesCaptor =
                ArgumentCaptor.forClass(List.class);

        verify(registry, times(1))
                .register(agentCardCaptor.capture(), transportPropertiesCaptor.capture());

        assertEquals(agentCard, agentCardCaptor.getValue());
        assertEquals(1, transportPropertiesCaptor.getValue().size());
        assertEquals(transportProperties, transportPropertiesCaptor.getValue().get(0));
    }

    @Test
    @DisplayName("Should register agent with multiple registries")
    void testRegisterWithMultipleRegistries() {
        AgentRegistry registry1 = mock(AgentRegistry.class);
        when(registry1.registryName()).thenReturn("TestRegistry1");
        agentRegistries.add(registry1);

        AgentRegistry registry2 = mock(AgentRegistry.class);
        when(registry2.registryName()).thenReturn("TestRegistry2");
        agentRegistries.add(registry2);

        agentRegistryService.register(agentCard, transportPropertiesSet);

        // Verify register method was called for both registries
        verify(registry1, times(1)).register(agentCard, List.of(transportProperties));
        verify(registry2, times(1)).register(agentCard, List.of(transportProperties));
    }

    @Test
    @DisplayName("Should continue registration when one registry fails")
    void testRegisterContinuesWhenRegistryFails() {
        AgentRegistry registry1 = mock(AgentRegistry.class);
        when(registry1.registryName()).thenReturn("TestRegistry1");
        doThrow(new RuntimeException("Registration failed"))
                .when(registry1)
                .register(agentCard, List.of(transportProperties));
        agentRegistries.add(registry1);

        AgentRegistry registry2 = mock(AgentRegistry.class);
        when(registry2.registryName()).thenReturn("TestRegistry2");
        agentRegistries.add(registry2);

        agentRegistryService.register(agentCard, transportPropertiesSet);

        // Verify register method was called for both registries despite the first one failing
        verify(registry1, times(1)).register(agentCard, List.of(transportProperties));
        verify(registry2, times(1)).register(agentCard, List.of(transportProperties));
    }
}
