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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

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

        Flux<AgentEvent> mockFlux = mock(Flux.class);
        when(mockAgent.streamEvents(eq(messages), any(RuntimeContext.class))).thenReturn(mockFlux);
        when(mockFlux.doFinally(any())).thenReturn(mockFlux);

        // When
        Flux<AgentEvent> result = runner.streamEvents(messages, requestOptions);

        // Then
        assertNotNull(result);
        verify(mockBuilder, times(1)).build();
        verify(mockAgent, times(1)).streamEvents(eq(messages), any(RuntimeContext.class));
    }

    @Test
    @DisplayName("Should pass the user and session id of the request to the agent context")
    void testPassIdentityToAgentContext() {
        // Given
        requestOptions.setTaskId(UUID.randomUUID().toString());
        requestOptions.setUserId("alice");
        requestOptions.setSessionId("session-1");
        List<Msg> messages = List.of(mock(Msg.class));
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.empty());

        // When
        runner.streamEvents(messages, requestOptions);

        // Then
        ArgumentCaptor<RuntimeContext> contextCaptor =
                ArgumentCaptor.forClass(RuntimeContext.class);
        verify(mockAgent).streamEvents(eq(messages), contextCaptor.capture());
        assertEquals("alice", contextCaptor.getValue().getUserId());
        assertEquals("session-1", contextCaptor.getValue().getSessionId());
    }

    @Test
    @DisplayName("Should stop only the session of the task, not the default session")
    void testStopInterruptsTaskSessionOnly() throws Exception {
        CountDownLatch modelCalls = new CountDownLatch(2);
        ReActAgent sharedAgent =
                ReActAgent.builder()
                        .name("shared")
                        .sysPrompt("hi")
                        .model(new EndlessModel(modelCalls))
                        .build();
        // Like the starter, every request gets the same agent instance.
        BaseReActAgentRunner sharedRunner =
                new BaseReActAgentRunner() {
                    @Override
                    protected ReActAgent buildReActAgent() {
                        return sharedAgent;
                    }
                };
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);
        requestOptions.setUserId("alice");
        requestOptions.setSessionId("context-a");
        List<Msg> messages = List.of(userMsg("hello"));

        CompletableFuture<AgentEvent> taskRun =
                sharedRunner
                        .streamEvents(messages, requestOptions)
                        .subscribeOn(Schedulers.parallel())
                        .last()
                        .toFuture();
        CompletableFuture<Msg> defaultSessionCall =
                sharedAgent.call(messages).subscribeOn(Schedulers.parallel()).toFuture();
        try {
            assertTrue(modelCalls.await(5, TimeUnit.SECONDS), "both calls should reach the model");

            sharedRunner.stop(taskId);

            assertNotNull(taskRun.get(5, TimeUnit.SECONDS), "the stopped task should end");
            assertFalse(defaultSessionCall.isDone(), "the default session must keep running");
        } finally {
            taskRun.cancel(true);
            defaultSessionCall.cancel(true);
        }
    }

    @Test
    @DisplayName("Should leave the agent context identity unset when options are blank")
    void testBlankIdentityLeftUnsetInAgentContext() {
        // Given
        requestOptions.setTaskId(UUID.randomUUID().toString());
        requestOptions.setUserId("  ");
        requestOptions.setSessionId("");
        List<Msg> messages = List.of(mock(Msg.class));
        when(mockAgent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.empty());

        // When
        runner.streamEvents(messages, requestOptions);

        // Then
        ArgumentCaptor<RuntimeContext> contextCaptor =
                ArgumentCaptor.forClass(RuntimeContext.class);
        verify(mockAgent).streamEvents(eq(messages), contextCaptor.capture());
        assertNull(contextCaptor.getValue().getUserId());
        assertNull(contextCaptor.getValue().getSessionId());
    }

    @Test
    @DisplayName("Should throw exception when agent already exists for task ID")
    void testThrowExceptionWhenAgentAlreadyExists() {
        // Given
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);

        List<Msg> messages = List.of(mock(Msg.class));

        Flux<AgentEvent> mockFlux = mock(Flux.class);
        when(mockAgent.streamEvents(eq(messages), any(RuntimeContext.class))).thenReturn(mockFlux);

        // First call to populate the cache
        runner.streamEvents(messages, requestOptions);

        // When & Then
        assertThrows(
                IllegalStateException.class, () -> runner.streamEvents(messages, requestOptions));
    }

    @Test
    @DisplayName("Should atomically reject concurrent streams for the same task ID")
    void testRejectConcurrentStreamsForSameTaskId() throws Exception {
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);
        List<Msg> messages = List.of(mock(Msg.class));
        ReActAgent firstAgent = mock(ReActAgent.class);
        ReActAgent secondAgent = mock(ReActAgent.class);
        when(firstAgent.streamEvents(eq(messages), any(RuntimeContext.class)))
                .thenReturn(Flux.never());
        when(secondAgent.streamEvents(eq(messages), any(RuntimeContext.class)))
                .thenReturn(Flux.never());
        List<ReActAgent> agents = List.of(firstAgent, secondAgent);
        AtomicInteger nextAgent = new AtomicInteger();
        CyclicBarrier buildBarrier = new CyclicBarrier(2);
        BaseReActAgentRunner concurrentRunner =
                new BaseReActAgentRunner() {
                    @Override
                    protected ReActAgent buildReActAgent() {
                        ReActAgent agent = agents.get(nextAgent.getAndIncrement());
                        try {
                            buildBarrier.await(5, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        return agent;
                    }
                };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Flux<AgentEvent>>> calls =
                    List.of(
                            executor.submit(
                                    () -> concurrentRunner.streamEvents(messages, requestOptions)),
                            executor.submit(
                                    () -> concurrentRunner.streamEvents(messages, requestOptions)));
            List<Flux<AgentEvent>> successes = new ArrayList<>();
            List<Throwable> failures = new ArrayList<>();
            for (Future<Flux<AgentEvent>> call : calls) {
                try {
                    successes.add(call.get(5, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }

            assertEquals(1, successes.size());
            assertEquals(1, failures.size());
            assertInstanceOf(IllegalStateException.class, failures.get(0));
            assertTrue(failures.get(0).getMessage().contains(taskId));
        } finally {
            concurrentRunner.stop(taskId);
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Should not let an old stream remove a restarted task")
    void testOldStreamCleanupDoesNotRemoveRestartedTask() {
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);
        List<Msg> messages = List.of(mock(Msg.class));
        ReActAgent firstAgent = mock(ReActAgent.class);
        ReActAgent secondAgent = mock(ReActAgent.class);
        Sinks.Empty<AgentEvent> firstCompletion = Sinks.empty();
        when(firstAgent.streamEvents(eq(messages), any(RuntimeContext.class)))
                .thenReturn(firstCompletion.asMono().flux());
        when(secondAgent.streamEvents(eq(messages), any(RuntimeContext.class)))
                .thenReturn(Flux.never());
        when(mockBuilder.build()).thenReturn(firstAgent, secondAgent);

        Disposable firstRun = runner.streamEvents(messages, requestOptions).subscribe();
        Disposable secondRun = null;
        try {
            runner.stop(taskId);
            secondRun = runner.streamEvents(messages, requestOptions).subscribe();

            assertEquals(Sinks.EmitResult.OK, firstCompletion.tryEmitEmpty());
            runner.stop(taskId);

            verify(firstAgent, times(1)).interrupt(any(RuntimeContext.class));
            verify(secondAgent, times(1)).interrupt(any(RuntimeContext.class));
        } finally {
            firstRun.dispose();
            if (secondRun != null) {
                secondRun.dispose();
            }
        }
    }

    @Test
    @DisplayName("Should remove the cached agent when stream creation fails")
    void testRemoveAgentFromCacheWhenStreamCreationFails() {
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);
        List<Msg> messages = List.of(mock(Msg.class));
        when(mockAgent.streamEvents(eq(messages), any(RuntimeContext.class)))
                .thenThrow(new IllegalStateException("stream creation failed"))
                .thenReturn(Flux.empty());

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> runner.streamEvents(messages, requestOptions));

        assertEquals("stream creation failed", error.getMessage());
        assertDoesNotThrow(() -> runner.streamEvents(messages, requestOptions));
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
        when(mockAgent.streamEvents(eq(messages), any(RuntimeContext.class))).thenReturn(mockFlux);

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
    }

    @Test
    @DisplayName("Should stop agent and remove from cache")
    void testStopAgentAndRemoveFromCache() {
        // Given
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);

        List<Msg> messages = List.of(mock(Msg.class));

        Flux<AgentEvent> mockFlux = mock(Flux.class);
        when(mockAgent.streamEvents(eq(messages), any(RuntimeContext.class))).thenReturn(mockFlux);
        when(mockFlux.doFinally(any())).thenReturn(mockFlux);
        runner.streamEvents(messages, requestOptions);

        runner.stop(taskId);
        ArgumentCaptor<RuntimeContext> startContext = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(mockAgent).streamEvents(eq(messages), startContext.capture());
        verify(mockAgent, times(1)).interrupt(startContext.getValue());

        // Try to stream again with the same taskId - should succeed since agent was removed
        Flux<AgentEvent> result = runner.streamEvents(messages, requestOptions);
        assertNotNull(result);
    }

    @Test
    @DisplayName("Should handle stop for non-existent task ID")
    void testStopNonExistentTaskId() {
        assertDoesNotThrow(() -> runner.stop("non-existent-task-id"));
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    /**
     * A model that streams chunks forever, so a call keeps running until it is interrupted. The
     * agent checks for an interrupt on each chunk.
     */
    private static final class EndlessModel extends ChatModelBase {
        private final CountDownLatch calls;

        private EndlessModel(CountDownLatch calls) {
            this.calls = calls;
        }

        @Override
        public String getModelName() {
            return "endless";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(
                    () -> {
                        calls.countDown();
                        return Flux.interval(Duration.ofMillis(20))
                                .map(
                                        tick ->
                                                ChatResponse.builder()
                                                        .content(
                                                                List.of(
                                                                        TextBlock.builder()
                                                                                .text(".")
                                                                                .build()))
                                                        .build());
                    });
        }
    }
}
