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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.scheduler.VirtualTimeScheduler;

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

        when(mockAgent.streamEvents(messages)).thenReturn(Flux.never());

        // When
        Flux<AgentEvent> result = runner.streamEvents(messages, requestOptions);

        // Then
        assertNotNull(result);
        verify(mockBuilder, times(0)).build();
        result.subscribe();
        verify(mockBuilder, times(1)).build();
        verify(mockAgent, times(1)).streamEvents(messages);
    }

    @Test
    @DisplayName("Should throw exception when agent already exists for task ID")
    void testThrowExceptionWhenAgentAlreadyExists() {
        // Given
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);

        List<Msg> messages = List.of(mock(Msg.class));

        when(mockAgent.streamEvents(messages)).thenReturn(Flux.never());

        // First subscription populates the cache.
        runner.streamEvents(messages, requestOptions).subscribe();

        // When & Then
        assertThrows(
                IllegalStateException.class,
                () -> runner.streamEvents(messages, requestOptions).blockLast());
    }

    @Test
    @DisplayName("Should remove agent from cache when stream completes")
    void testRemoveAgentFromCacheOnComplete() {
        // Given
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);

        List<Msg> messages = List.of(mock(Msg.class));

        // Setup mock flux that simulates completion
        Flux<AgentEvent> mockFlux = Flux.empty();
        when(mockAgent.streamEvents(messages)).thenReturn(mockFlux);

        // When
        Flux<AgentEvent> result = runner.streamEvents(messages, requestOptions);

        // Subscribe to trigger the doFinally block
        result.subscribe();

        // Give reactive stream time to complete
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            fail("Test interrupted");
        }

        // Try to stream again with the same taskId - should succeed since agent was removed
        Flux<AgentEvent> secondResult = runner.streamEvents(messages, requestOptions);
        assertNotNull(secondResult);
        secondResult.blockLast();
        verify(mockBuilder, times(2)).build();
    }

    @Test
    @DisplayName("Should retain a paused agent until the confirmation response completes")
    void testRetainAgentWhileWaitingForConfirmation() {
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);
        List<Msg> firstRequest = List.of(Msg.builder().textContent("Run the tool").build());
        List<Msg> confirmation = List.of(Msg.builder().textContent("Confirmed").build());
        when(mockAgent.streamEvents(firstRequest))
                .thenReturn(
                        Flux.just(
                                new RequireUserConfirmEvent(
                                        "reply-1",
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("tool-call-1")
                                                        .name("delete_file")
                                                        .input(Map.of("path", "report.txt"))
                                                        .build()))));
        when(mockAgent.streamEvents(confirmation)).thenReturn(Flux.empty());

        runner.streamEvents(firstRequest, requestOptions).blockLast();
        verify(mockBuilder, times(1)).build();

        runner.streamEvents(confirmation, requestOptions).blockLast();
        verify(mockAgent, times(1)).streamEvents(firstRequest);
        verify(mockAgent, times(1)).streamEvents(confirmation);

        when(mockAgent.streamEvents(firstRequest)).thenReturn(Flux.empty());
        runner.streamEvents(firstRequest, requestOptions).blockLast();
        verify(mockBuilder, times(2)).build();
    }

    @Test
    @DisplayName("Should expire a paused agent and reject a stale resume")
    void testExpirePausedAgentAndRejectStaleResume() {
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.getOrSet();
        try {
            runner = ReActAgentWithBuilderRunner.newInstance(mockBuilder, Duration.ofMinutes(30));
            String taskId = UUID.randomUUID().toString();
            requestOptions.setTaskId(taskId);
            List<Msg> firstRequest = List.of(Msg.builder().textContent("Run the tool").build());
            List<Msg> confirmation = List.of(Msg.builder().textContent("Confirmed").build());
            when(mockAgent.streamEvents(firstRequest))
                    .thenReturn(
                            Flux.just(
                                    new RequireUserConfirmEvent(
                                            "reply-1",
                                            List.of(
                                                    ToolUseBlock.builder()
                                                            .id("tool-call-1")
                                                            .name("delete_file")
                                                            .input(Map.of("path", "report.txt"))
                                                            .build()))));

            runner.streamEvents(firstRequest, requestOptions).blockLast();

            scheduler.advanceTimeBy(Duration.ofMinutes(30));
            verify(mockAgent).interrupt();
            verify(mockAgent).close();
            requestOptions.setResume(true);
            assertThrows(
                    IllegalStateException.class,
                    () -> runner.streamEvents(confirmation, requestOptions).blockLast());
            verify(mockBuilder, times(1)).build();
        } finally {
            VirtualTimeScheduler.reset();
        }
    }

    @Test
    @DisplayName("Should give a resumed confirmation batch its own expiry")
    void testResumeDoesNotExpireAtPreviousPauseDeadline() {
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.getOrSet();
        try {
            requestOptions.setTaskId(UUID.randomUUID().toString());
            List<Msg> messages = List.of(Msg.builder().textContent("Continue").build());
            RequireUserConfirmEvent request =
                    new RequireUserConfirmEvent(
                            "reply-1",
                            List.of(
                                    ToolUseBlock.builder()
                                            .id("tool-1")
                                            .name("delete_file")
                                            .build()));
            when(mockAgent.streamEvents(messages)).thenReturn(Flux.just(request));
            runner.streamEvents(messages, requestOptions).blockLast();
            scheduler.advanceTimeBy(Duration.ofMinutes(20));
            requestOptions.setResume(true);
            runner.streamEvents(messages, requestOptions).blockLast();
            scheduler.advanceTimeBy(Duration.ofMinutes(10));
            verify(mockAgent, never()).interrupt();
            verify(mockAgent, never()).close();
            scheduler.advanceTimeBy(Duration.ofMinutes(20));
            verify(mockAgent).interrupt();
            verify(mockAgent).close();
            verify(mockBuilder).build();
        } finally {
            VirtualTimeScheduler.reset();
        }
    }

    @Test
    @DisplayName("Should cancel the expiry when a paused task is stopped")
    void testStopPausedAgentCancelsExpiry() {
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.getOrSet();
        try {
            requestOptions.setTaskId(UUID.randomUUID().toString());
            List<Msg> messages = List.of(Msg.builder().textContent("Run").build());
            when(mockAgent.streamEvents(messages))
                    .thenReturn(
                            Flux.just(
                                    new RequireUserConfirmEvent(
                                            "reply-1",
                                            List.of(
                                                    ToolUseBlock.builder()
                                                            .id("tool-1")
                                                            .name("delete_file")
                                                            .build()))));
            runner.streamEvents(messages, requestOptions).blockLast();
            runner.stop(requestOptions.getTaskId());
            scheduler.advanceTimeBy(Duration.ofHours(1));
            verify(mockAgent).interrupt();
            verify(mockAgent).close();
            requestOptions.setResume(true);
            assertThrows(
                    IllegalStateException.class,
                    () -> runner.streamEvents(messages, requestOptions).blockLast());
            verify(mockBuilder).build();
        } finally {
            VirtualTimeScheduler.reset();
        }
    }

    @Test
    @DisplayName("Should allow resuming as soon as the confirmation event is emitted")
    void testResumeImmediatelyAfterConfirmationEvent() {
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);
        List<Msg> firstRequest = List.of(Msg.builder().textContent("Run the tool").build());
        List<Msg> confirmation = List.of(Msg.builder().textContent("Confirmed").build());
        when(mockAgent.streamEvents(firstRequest))
                .thenReturn(
                        Flux.just(
                                new RequireUserConfirmEvent(
                                        "reply-1",
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("tool-call-1")
                                                        .name("delete_file")
                                                        .input(Map.of("path", "report.txt"))
                                                        .build()))));
        when(mockAgent.streamEvents(confirmation)).thenReturn(Flux.empty());
        boolean[] resumed = {false};

        runner.streamEvents(firstRequest, requestOptions)
                .doOnNext(
                        event -> {
                            if (event instanceof RequireUserConfirmEvent) {
                                runner.streamEvents(confirmation, requestOptions).blockLast();
                                resumed[0] = true;
                            }
                        })
                .blockLast();

        assertTrue(resumed[0]);
        verify(mockBuilder, times(1)).build();
        verify(mockAgent, times(1)).streamEvents(firstRequest);
        verify(mockAgent, times(1)).streamEvents(confirmation);
    }

    @Test
    @DisplayName("Should stop agent and remove from cache")
    void testStopAgentAndRemoveFromCache() {
        // Given
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);

        List<Msg> messages = List.of(mock(Msg.class));

        when(mockAgent.streamEvents(messages)).thenReturn(Flux.never());
        runner.streamEvents(messages, requestOptions).subscribe();

        runner.stop(taskId);
        verify(mockAgent, times(1)).interrupt();

        // Try to stream again with the same taskId - should succeed since agent was removed
        Flux<AgentEvent> result = runner.streamEvents(messages, requestOptions);
        assertNotNull(result);
        result.subscribe();
    }

    @Test
    @DisplayName("Should handle stop for non-existent task ID")
    void testStopNonExistentTaskId() {
        assertDoesNotThrow(() -> runner.stop("non-existent-task-id"));
    }
}
