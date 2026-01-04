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

package io.agentscope.core.a2a.server.executor.runner;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

@DisplayName("Agent Runner Tests")
class AgentRunnerTest {

    private AgentRequestOptions requestOptions;
    private ReActAgent.Builder mockBuilder;
    private ReActAgent mockAgent;
    private ReActAgentWithBuilderRunner runner;

    @BeforeEach
    void setUp() {
        requestOptions = new AgentRequestOptions();
        mockBuilder = mock(ReActAgent.Builder.class);
        mockAgent = mock(ReActAgent.class);
        when(mockBuilder.build()).thenReturn(mockAgent);
        runner = ReActAgentWithBuilderRunner.newInstance(mockBuilder);
    }

    @Test
    @DisplayName("Should set and get task ID")
    void testSetAndGetTaskId() {
        String taskId = "test-task-id";
        requestOptions.setTaskId(taskId);
        assertEquals(taskId, requestOptions.getTaskId());
    }

    @Test
    @DisplayName("Should set and get session ID")
    void testSetAndGetSessionId() {
        String sessionId = "test-session-id";
        requestOptions.setSessionId(sessionId);
        assertEquals(sessionId, requestOptions.getSessionId());
    }

    @Test
    @DisplayName("Should set and get user ID")
    void testSetAndGetUserId() {
        String userId = "test-user-id";
        requestOptions.setUserId(userId);
        assertEquals(userId, requestOptions.getUserId());
    }

    @Test
    @DisplayName("Should return null for unset fields")
    void testReturnNullForUnsetFields() {
        assertNull(requestOptions.getTaskId());
        assertNull(requestOptions.getSessionId());
        assertNull(requestOptions.getUserId());
    }

    @Test
    @DisplayName("Should handle empty string values")
    void testHandleEmptyStringValues() {
        requestOptions.setTaskId("");
        requestOptions.setSessionId("");
        requestOptions.setUserId("");

        assertEquals("", requestOptions.getTaskId());
        assertEquals("", requestOptions.getSessionId());
        assertEquals("", requestOptions.getUserId());
    }

    // ReActAgentWithBuilderRunner Tests

    @Test
    @DisplayName("Should create new instance with builder")
    void testCreateNewInstanceWithBuilder() {
        assertNotNull(runner);
    }

    @Test
    @DisplayName("Should build agent from builder")
    void testBuildAgentFromBuilder() {
        // When
        ReActAgent agent = runner.buildReActAgent();

        // Then
        assertNotNull(agent);
        verify(mockBuilder, times(1)).build();
    }

    @Test
    @DisplayName("Should return agent name from built agent")
    void testGetAgentName() {
        // Given
        String agentName = "Test Agent";
        when(mockAgent.getName()).thenReturn(agentName);

        // When
        String name = runner.getAgentName();

        // Then
        assertEquals(agentName, name);
        verify(mockAgent, times(1)).getName();
    }

    @Test
    @DisplayName("Should return agent description from built agent")
    void testGetAgentDescription() {
        // Given
        String agentDescription = "Test Agent Description";
        when(mockAgent.getDescription()).thenReturn(agentDescription);

        // When
        String description = runner.getAgentDescription();

        // Then
        assertEquals(agentDescription, description);
        verify(mockAgent, times(1)).getDescription();
    }

    @Test
    @DisplayName("Should stream messages and cache agent")
    void testStreamMessagesAndCacheAgent() {
        // Given
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);

        List<Msg> messages = List.of(mock(Msg.class));

        Flux<Event> mockFlux = mock(Flux.class);
        when(mockAgent.stream(messages)).thenReturn(mockFlux);
        when(mockFlux.doFinally(any())).thenReturn(mockFlux);

        // When
        Flux<Event> result = runner.stream(messages, requestOptions);

        // Then
        assertNotNull(result);
        verify(mockBuilder, times(1)).build();
        verify(mockAgent, times(1)).stream(messages);
    }

    @Test
    @DisplayName("Should throw exception when agent already exists for task ID")
    void testThrowExceptionWhenAgentAlreadyExists() {
        // Given
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);

        List<Msg> messages = List.of(mock(Msg.class));

        Flux<Event> mockFlux = mock(Flux.class);
        when(mockAgent.stream(messages)).thenReturn(mockFlux);

        // First call to populate the cache
        runner.stream(messages, requestOptions);

        // When & Then
        assertThrows(IllegalStateException.class, () -> runner.stream(messages, requestOptions));
    }

    @Test
    @DisplayName("Should remove agent from cache when stream completes")
    void testRemoveAgentFromCacheOnComplete() {
        // Given
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);

        List<Msg> messages = List.of(mock(Msg.class));

        // Setup mock flux that simulates completion
        Flux<Event> mockFlux = Flux.empty();
        when(mockAgent.stream(messages)).thenReturn(mockFlux);

        // When
        Flux<Event> result = runner.stream(messages, requestOptions);

        // Subscribe to trigger the doFinally block
        result.subscribe();

        // Give reactive stream time to complete
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            fail("Test interrupted");
        }

        // Try to stream again with the same taskId - should succeed since agent was removed
        Flux<Event> secondResult = runner.stream(messages, requestOptions);
        assertNotNull(secondResult);
    }

    @Test
    @DisplayName("Should stop agent and remove from cache")
    void testStopAgentAndRemoveFromCache() {
        // Given
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);

        List<Msg> messages = List.of(mock(Msg.class));

        Flux<Event> mockFlux = mock(Flux.class);
        when(mockAgent.stream(messages)).thenReturn(mockFlux);
        when(mockFlux.doFinally(any())).thenReturn(mockFlux);
        runner.stream(messages, requestOptions);

        runner.stop(taskId);
        verify(mockAgent, times(1)).interrupt();

        // Try to stream again with the same taskId - should succeed since agent was removed
        Flux<Event> result = runner.stream(messages, requestOptions);
        assertNotNull(result);
    }

    @Test
    @DisplayName("Should handle stop for non-existent task ID")
    void testStopNonExistentTaskId() {
        assertDoesNotThrow(() -> runner.stop("non-existent-task-id"));
    }
}
