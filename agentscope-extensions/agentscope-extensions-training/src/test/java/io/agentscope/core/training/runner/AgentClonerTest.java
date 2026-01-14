/*
 * Copyright 2024-2025 the original author or authors.
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

package io.agentscope.core.training.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for AgentCloner.
 */
@DisplayName("AgentCloner Tests")
class AgentClonerTest {

    @Test
    @DisplayName("Should throw for unsupported agent type")
    void shouldThrowForUnsupportedAgentType() {
        Agent unknownAgent = mock(Agent.class);
        when(unknownAgent.getName()).thenReturn("UnknownAgent");
        Model newModel = mock(Model.class);

        UnsupportedOperationException exception =
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> AgentCloner.cloneWithModel(unknownAgent, newModel));

        assertTrue(exception.getMessage().contains("Agent cloning not implemented"));
    }

    @Test
    @DisplayName("Should clone ReActAgent with new model")
    void shouldCloneReActAgentWithNewModel() {
        Model originalModel = mock(Model.class);
        Model newModel = mock(Model.class);

        ReActAgent originalAgent =
                ReActAgent.builder()
                        .name("OriginalAgent")
                        .description("Test agent")
                        .sysPrompt("You are a helpful assistant")
                        .model(originalModel)
                        .build();

        Agent clonedAgent = AgentCloner.cloneWithModel(originalAgent, newModel);

        assertNotNull(clonedAgent);
        assertTrue(clonedAgent instanceof ReActAgent);
        assertEquals("OriginalAgent-shadow", clonedAgent.getName());
        assertEquals("Test agent", clonedAgent.getDescription());
    }

    @Test
    @DisplayName("Should clone ReActAgent with maxIters")
    void shouldCloneReActAgentWithMaxIters() {
        Model originalModel = mock(Model.class);
        Model newModel = mock(Model.class);

        ReActAgent originalAgent =
                ReActAgent.builder()
                        .name("AgentWithIters")
                        .description("Agent with max iterations")
                        .sysPrompt("You are a helpful assistant")
                        .model(originalModel)
                        .maxIters(5)
                        .build();

        Agent clonedAgent = AgentCloner.cloneWithModel(originalAgent, newModel);

        assertNotNull(clonedAgent);
        assertEquals("AgentWithIters-shadow", clonedAgent.getName());
    }

    @Test
    @DisplayName("Should handle null fields gracefully")
    void shouldHandleNullFieldsGracefully() {
        Model originalModel = mock(Model.class);
        Model newModel = mock(Model.class);

        ReActAgent originalAgent =
                ReActAgent.builder()
                        .name("MinimalAgent")
                        .model(originalModel)
                        .sysPrompt("System prompt")
                        .build();

        Agent clonedAgent = AgentCloner.cloneWithModel(originalAgent, newModel);

        assertNotNull(clonedAgent);
        assertEquals("MinimalAgent-shadow", clonedAgent.getName());
    }
}
