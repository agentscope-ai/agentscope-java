/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.spring.boot.agui.webflux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for AguiAgentRegistry.
 */
class AguiAgentRegistryTest {

    private AguiAgentRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AguiAgentRegistry();
    }

    @Test
    void testRegisterAndGetSingletonAgent() {
        Agent mockAgent = mock(Agent.class);
        registry.register("test-agent", mockAgent);

        Optional<Agent> result = registry.getAgent("test-agent");

        assertTrue(result.isPresent());
        assertSame(mockAgent, result.get());
    }

    @Test
    void testSingletonAgentReturnsSameInstance() {
        Agent mockAgent = mock(Agent.class);
        registry.register("test-agent", mockAgent);

        Agent agent1 = registry.getAgent("test-agent").orElse(null);
        Agent agent2 = registry.getAgent("test-agent").orElse(null);

        assertSame(agent1, agent2);
    }

    @Test
    void testRegisterAndGetFactoryAgent() {
        registry.registerFactory("factory-agent", () -> mock(Agent.class));

        Optional<Agent> result = registry.getAgent("factory-agent");

        assertTrue(result.isPresent());
    }

    @Test
    void testFactoryAgentReturnsNewInstances() {
        registry.registerFactory("factory-agent", () -> mock(Agent.class));

        Agent agent1 = registry.getAgent("factory-agent").orElse(null);
        Agent agent2 = registry.getAgent("factory-agent").orElse(null);

        assertNotSame(agent1, agent2);
    }

    @Test
    void testFactoryTakesPriorityOverSingleton() {
        Agent singletonAgent = mock(Agent.class);
        registry.register("agent", singletonAgent);
        registry.registerFactory("agent", () -> mock(Agent.class));

        Agent result = registry.getAgent("agent").orElse(null);

        assertNotSame(singletonAgent, result);
    }

    @Test
    void testGetNonExistentAgentReturnsEmpty() {
        Optional<Agent> result = registry.getAgent("non-existent");

        assertFalse(result.isPresent());
    }

    @Test
    void testHasAgent() {
        Agent mockAgent = mock(Agent.class);
        registry.register("test-agent", mockAgent);

        assertTrue(registry.hasAgent("test-agent"));
        assertFalse(registry.hasAgent("non-existent"));
    }

    @Test
    void testUnregister() {
        Agent mockAgent = mock(Agent.class);
        registry.register("test-agent", mockAgent);

        assertTrue(registry.unregister("test-agent"));
        assertFalse(registry.hasAgent("test-agent"));
    }

    @Test
    void testUnregisterNonExistent() {
        assertFalse(registry.unregister("non-existent"));
    }

    @Test
    void testClear() {
        registry.register("agent1", mock(Agent.class));
        registry.registerFactory("agent2", () -> mock(Agent.class));

        registry.clear();

        assertEquals(0, registry.size());
    }

    @Test
    void testSize() {
        assertEquals(0, registry.size());

        registry.register("agent1", mock(Agent.class));
        assertEquals(1, registry.size());

        registry.registerFactory("agent2", () -> mock(Agent.class));
        assertEquals(2, registry.size());
    }

    @Test
    void testRegisterNullAgentIdThrows() {
        assertThrows(
                IllegalArgumentException.class, () -> registry.register(null, mock(Agent.class)));
    }

    @Test
    void testRegisterEmptyAgentIdThrows() {
        assertThrows(
                IllegalArgumentException.class, () -> registry.register("", mock(Agent.class)));
    }

    @Test
    void testRegisterNullAgentThrows() {
        assertThrows(IllegalArgumentException.class, () -> registry.register("agent", null));
    }

    @Test
    void testRegisterNullFactoryThrows() {
        assertThrows(IllegalArgumentException.class, () -> registry.registerFactory("agent", null));
    }
}
