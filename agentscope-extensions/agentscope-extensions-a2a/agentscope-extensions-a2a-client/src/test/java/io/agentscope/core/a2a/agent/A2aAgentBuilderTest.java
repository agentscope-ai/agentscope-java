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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.a2a.agent.card.FixedAgentCardResolver;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for Builder of A2aAgent.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>A2aAgent Builder pattern functionality</li>
 *   <li>Agent creation with various configurations by Builder</li>
 *   <li>A2aAgent Builder method chaining</li>
 *   <li>Error handling for invalid configurations</li>
 * </ul>
 *
 * <p>In this Unit test, will only test to build A2aAgent, about Unit test for using A2aAgent, see {@link A2aAgentTest}.
 */
@DisplayName("A2aAgent Builder Tests")
class A2aAgentBuilderTest {

    private AgentCardResolver agentCardResolver;
    private A2aAgentConfig a2aAgentConfig;
    private AgentCard agentCard;

    @BeforeEach
    void setUp() {
        agentCardResolver = mock(AgentCardResolver.class);
        a2aAgentConfig = mock(A2aAgentConfig.class);
        agentCard = mock(AgentCard.class);

        lenient().when(agentCardResolver.getAgentCard(anyString())).thenReturn(agentCard);
        lenient().when(a2aAgentConfig.clientTransports()).thenReturn(new java.util.HashMap<>());
        lenient().when(a2aAgentConfig.clientConfig()).thenReturn(null);
    }

    @Test
    @DisplayName("Should create builder instance")
    void testBuilderCreation() {
        A2aAgent.Builder builder = A2aAgent.builder();
        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should build agent with required parameters")
    void testBuildAgentWithRequiredParameters() {
        A2aAgent agent =
                A2aAgent.builder().name("test-agent").agentCardResolver(agentCardResolver).build();

        assertNotNull(agent);
    }

    @Test
    @DisplayName("Should throw exception when agentCardResolver is not set")
    void testBuildWithoutAgentCardResolver() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    A2aAgent.builder().name("test-agent").a2aAgentConfig(a2aAgentConfig).build();
                });
    }

    @Test
    @DisplayName("Should set agent card and create resolver")
    void testAgentCard() throws Exception {
        A2aAgent.Builder builder = A2aAgent.builder();
        A2aAgent.Builder result = builder.agentCard(agentCard);

        assertSame(builder, result);

        A2aAgent agent = builder.name("test-agent").build();
        assertInstanceOf(
                FixedAgentCardResolver.class,
                getFieldValue(agent, "agentCardResolver", AgentCardResolver.class));
        assertSame(
                agentCard,
                getFieldValue(agent, "agentCardResolver", AgentCardResolver.class)
                        .getAgentCard("test-agent"));
    }

    @Test
    @DisplayName("Should set memory")
    void testMemory() throws Exception {
        Memory memory = new InMemoryMemory();
        A2aAgent.Builder builder = A2aAgent.builder();
        A2aAgent.Builder result = builder.memory(memory);

        assertSame(builder, result);

        // Build the agent and verify memory field using reflection
        A2aAgent agent =
                builder.name("test-agent")
                        .agentCardResolver(agentCardResolver)
                        .a2aAgentConfig(a2aAgentConfig)
                        .build();

        assertEquals(memory, getFieldValue(agent, "memory", Memory.class));
    }

    @Test
    @DisplayName("Should set checkRunning flag")
    void testCheckRunning() throws Exception {
        A2aAgent.Builder builder = A2aAgent.builder();
        A2aAgent.Builder result = builder.checkRunning(false);

        assertSame(builder, result);

        // Build the agent and verify checkRunning field using reflection
        A2aAgent agent =
                builder.name("test-agent")
                        .agentCardResolver(agentCardResolver)
                        .a2aAgentConfig(a2aAgentConfig)
                        .build();

        assertEquals(false, getFieldValue(agent, "checkRunning", Boolean.class));
    }

    @Test
    @DisplayName("Should add hook")
    void testHook() throws Exception {
        A2aAgent.Builder builder = A2aAgent.builder();
        Hook hook = mock(Hook.class);
        A2aAgent.Builder result = builder.hook(hook);

        assertSame(builder, result);

        // Build the agent and verify hooks using reflection
        A2aAgent agent =
                builder.name("test-agent")
                        .agentCardResolver(agentCardResolver)
                        .a2aAgentConfig(a2aAgentConfig)
                        .build();

        @SuppressWarnings("unchecked")
        List<Hook> hooks = getFieldValue(agent, "hooks", List.class);
        assertTrue(hooks.contains(hook));
    }

    @Test
    @DisplayName("Should add multiple hooks")
    void testHooks() throws Exception {
        A2aAgent.Builder builder = A2aAgent.builder();
        List<Hook> inputHooks = new ArrayList<>();
        Hook hook1 = mock(Hook.class);
        Hook hook2 = mock(Hook.class);
        inputHooks.add(hook1);
        inputHooks.add(hook2);
        A2aAgent.Builder result = builder.hooks(inputHooks);

        assertSame(builder, result);

        // Build the agent and verify hooks using reflection
        A2aAgent agent =
                builder.name("test-agent")
                        .agentCardResolver(agentCardResolver)
                        .a2aAgentConfig(a2aAgentConfig)
                        .build();

        @SuppressWarnings("unchecked")
        List<Hook> hooks = getFieldValue(agent, "hooks", List.class);
        assertTrue(hooks.containsAll(inputHooks));
    }

    @Test
    @DisplayName("Should support builder method chaining")
    void testBuilderMethodChaining() throws Exception {
        Hook hook = mock(Hook.class);
        List<Hook> inputHooks = new ArrayList<>();
        inputHooks.add(mock(Hook.class));

        A2aAgent.Builder builder = A2aAgent.builder();
        A2aAgent.Builder result =
                builder.name("test-agent")
                        .agentCardResolver(agentCardResolver)
                        .a2aAgentConfig(a2aAgentConfig)
                        .memory(new InMemoryMemory())
                        .checkRunning(true)
                        .hook(hook)
                        .hooks(inputHooks);

        assertNotNull(result);
        assertSame(builder, result);

        // Build the agent and verify fields using reflection
        A2aAgent agent = builder.build();

        assertEquals("test-agent", getFieldValue(agent, "name", String.class));
        assertEquals(
                agentCardResolver,
                getFieldValue(agent, "agentCardResolver", AgentCardResolver.class));
        assertEquals(a2aAgentConfig, getFieldValue(agent, "a2aAgentConfig", A2aAgentConfig.class));
        assertNotNull(getFieldValue(agent, "memory", Memory.class));
        assertEquals(true, getFieldValue(agent, "checkRunning", Boolean.class));

        @SuppressWarnings("unchecked")
        List<Hook> hooks = getFieldValue(agent, "hooks", List.class);
        assertTrue(hooks.contains(hook));
        assertTrue(hooks.containsAll(inputHooks));
    }

    @Test
    @DisplayName("Should build multiple independent agent instances")
    void testMultipleAgentInstances() {
        A2aAgent agent1 =
                A2aAgent.builder()
                        .name("test-agent-1")
                        .agentCardResolver(agentCardResolver)
                        .a2aAgentConfig(a2aAgentConfig)
                        .build();

        A2aAgent agent2 =
                A2aAgent.builder()
                        .name("test-agent-2")
                        .agentCardResolver(agentCardResolver)
                        .a2aAgentConfig(a2aAgentConfig)
                        .build();

        assertNotNull(agent1);
        assertNotNull(agent2);
        assertNotSame(agent1, agent2);
    }

    @Test
    @DisplayName("Should get description from agent card")
    void testGetDescriptionFromAgentCard() {
        String description = "Test Agent Description";
        when(agentCard.description()).thenReturn(description);

        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCardResolver(agentCardResolver)
                        .a2aAgentConfig(a2aAgentConfig)
                        .build();

        assertEquals(description, agent.getDescription());
    }

    @Test
    @DisplayName("Should handle exception when getting description from agent card")
    void testGetDescriptionFromAgentCardException() {
        when(agentCardResolver.getAgentCard(anyString()))
                .thenThrow(new RuntimeException("Test Exception"));

        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCardResolver(agentCardResolver)
                        .a2aAgentConfig(a2aAgentConfig)
                        .build();

        // Should not throw exception and handle gracefully
        assertNotNull(agent.getDescription());
        assertTrue(agent.getDescription().startsWith("Agent"));
    }

    @Test
    @DisplayName(
            "Should replace agent card resolver when first set agentCard then set"
                    + " agentCardResolver")
    void testReplaceAgentCardResolverFirstAgentCard() throws Exception {
        A2aAgent.Builder builder =
                A2aAgent.builder().agentCard(agentCard).agentCardResolver(agentCardResolver);
        A2aAgent agent = builder.build();

        AgentCardResolver actualAgentCardResolver =
                getFieldValue(agent, "agentCardResolver", AgentCardResolver.class);
        assertSame(agentCardResolver, actualAgentCardResolver);
    }

    @Test
    @DisplayName(
            "Should replace agent card resolver when first set agentCard then set"
                    + " agentCardResolver")
    void testReplaceAgentCardResolverFirstAgentCardResolver() throws Exception {
        A2aAgent.Builder builder =
                A2aAgent.builder().agentCardResolver(agentCardResolver).agentCard(agentCard);
        A2aAgent agent = builder.build();

        AgentCardResolver actualAgentCardResolver =
                getFieldValue(agent, "agentCardResolver", AgentCardResolver.class);
        assertNotSame(agentCardResolver, actualAgentCardResolver);
        assertInstanceOf(FixedAgentCardResolver.class, actualAgentCardResolver);
    }

    @Test
    @DisplayName("Should ignore null agent card resolver")
    void testIgnoreNullAgentCardResolver() throws Exception {
        A2aAgent.Builder builder = A2aAgent.builder().agentCard(agentCard);
        builder.agentCardResolver(null);
        A2aAgent agent = builder.build();

        AgentCardResolver actualAgentCardResolver =
                getFieldValue(agent, "agentCardResolver", AgentCardResolver.class);
        assertNotSame(agentCardResolver, actualAgentCardResolver);
        assertInstanceOf(FixedAgentCardResolver.class, actualAgentCardResolver);
    }

    private <T> T getFieldValue(Object obj, String fieldName, Class<T> fieldType) throws Exception {
        Class<?> clazz = obj.getClass();
        // Traverse up the class hierarchy to find the field
        while (clazz != null) {
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return fieldType.cast(field.get(obj));
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field '" + fieldName + "' not found in class hierarchy");
    }
}
