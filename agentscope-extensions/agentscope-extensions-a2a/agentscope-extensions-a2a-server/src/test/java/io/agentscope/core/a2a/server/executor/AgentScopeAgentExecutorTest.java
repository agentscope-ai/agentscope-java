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

package io.agentscope.core.a2a.server.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.a2a.server.ServerCallContext;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.spec.Artifact;
import io.a2a.spec.DataPart;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendConfiguration;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.StreamingEventKind;
import io.a2a.spec.Task;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.spec.TextPart;
import io.agentscope.core.a2a.server.constants.A2aServerConstants;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

@DisplayName("AgentScopeAgentExecutor Tests")
class AgentScopeAgentExecutorTest {

    private AgentScopeAgentExecutor executor;
    private AgentRunner mockAgentRunner;
    private RequestContext mockContext;
    private EventQueue mockEventQueue;
    private ServerCallContext serverCallContext;

    @BeforeEach
    void setUp() {
        AgentExecuteProperties agentExecuteProperties = AgentExecuteProperties.builder().build();
        mockAgentRunner = mock(AgentRunner.class);
        executor = new AgentScopeAgentExecutor(mockAgentRunner, agentExecuteProperties);
        mockContext = mock(RequestContext.class);
        mockEventQueue = mock(EventQueue.class);
        serverCallContext = mock(ServerCallContext.class);
    }

    private String doMockForContext(
            boolean isStreaming, boolean blockingByState, boolean blockingByConfig) {
        String taskId = UUID.randomUUID().toString();
        String contextId = UUID.randomUUID().toString();

        when(mockContext.getTaskId()).thenReturn(taskId);
        when(mockContext.getContextId()).thenReturn(contextId);

        Message mockMessage = mock(Message.class);
        when(mockMessage.getTaskId()).thenReturn(taskId);
        when(mockMessage.getContextId()).thenReturn(contextId);
        when(mockMessage.getParts()).thenReturn(List.of());
        when(mockContext.getMessage()).thenReturn(mockMessage);

        MessageSendParams mockParams = mock(MessageSendParams.class);
        when(mockContext.getParams()).thenReturn(mockParams);
        when(mockParams.message()).thenReturn(mockMessage);

        when(mockContext.getCallContext()).thenReturn(serverCallContext);
        if (isStreaming || blockingByState) {
            when(serverCallContext.getState())
                    .thenReturn(Map.of(A2aServerConstants.ContextKeys.IS_STREAM_KEY, isStreaming));
        }
        if (blockingByConfig) {
            MessageSendConfiguration messageSendConfiguration =
                    new MessageSendConfiguration.Builder().build();
            when(mockParams.configuration()).thenReturn(messageSendConfiguration);
        }
        return taskId;
    }

    @Nested
    @DisplayName("Execute For Blocking Request")
    class ExecuteForBlockingRequestTests {
        @Test
        @DisplayName("Should execute agent and process blocking request")
        void testExecuteAgentWithBlockingRequest() throws JSONRPCError {
            doMockForContext(false, false, true);
            Flux<Event> mockFlux = mockFlux(false, true, false);
            when(mockAgentRunner.stream(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            AtomicReference<Message> messageRef = new AtomicReference<>();
            doAnswer(
                            (Answer<Void>)
                                    invocationOnMock -> {
                                        Object arg = invocationOnMock.getArgument(0);
                                        messageRef.set((Message) arg);
                                        return null;
                                    })
                    .when(mockEventQueue)
                    .enqueueEvent(any(Message.class));
            executor.execute(mockContext, mockEventQueue);

            assertNotNull(messageRef.get());
            assertBlockResultMessage(
                    messageRef.get(),
                    List.of("streaming result 1 2"),
                    mockContext.getTaskId(),
                    mockContext.getContextId());
        }

        @Test
        @DisplayName("Should execute agent and process blocking request without agent result event")
        void testExecuteAgentWithBlockingRequestWithoutAgentResultEvent() throws JSONRPCError {
            doMockForContext(false, true, false);
            Flux<Event> mockFlux = mockFlux(false, false, false);
            when(mockAgentRunner.stream(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            AtomicReference<Message> messageRef = new AtomicReference<>();
            doAnswer(
                            (Answer<Void>)
                                    invocationOnMock -> {
                                        Object arg = invocationOnMock.getArgument(0);
                                        messageRef.set((Message) arg);
                                        return null;
                                    })
                    .when(mockEventQueue)
                    .enqueueEvent(any(Message.class));
            executor.execute(mockContext, mockEventQueue);

            assertNotNull(messageRef.get());
            assertBlockResultMessage(
                    messageRef.get(),
                    List.of("streaming result 1", " 2"),
                    mockContext.getTaskId(),
                    mockContext.getContextId());
        }

        @Test
        @DisplayName("Should execute agent and process blocking request with inner event")
        void testExecuteAgentWithBlockingRequestWithInnerEvent() throws JSONRPCError {
            AgentExecuteProperties agentExecuteProperties =
                    AgentExecuteProperties.builder().requireInnerMessage(true).build();
            executor = new AgentScopeAgentExecutor(mockAgentRunner, agentExecuteProperties);
            doMockForContext(false, true, false);
            Flux<Event> mockFlux = mockFlux(true, false, false);
            when(mockAgentRunner.stream(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);
            AtomicReference<Message> messageRef = new AtomicReference<>();
            doAnswer(
                            (Answer<Void>)
                                    invocationOnMock -> {
                                        Object arg = invocationOnMock.getArgument(0);
                                        messageRef.set((Message) arg);
                                        return null;
                                    })
                    .when(mockEventQueue)
                    .enqueueEvent(any(Message.class));
            executor.execute(mockContext, mockEventQueue);

            assertNotNull(messageRef.get());
            Message message = messageRef.get();
            assertEquals(mockContext.getTaskId(), message.getTaskId());
            assertEquals(mockContext.getContextId(), message.getContextId());
            assertEquals(3, message.getParts().size());
            assertInstanceOf(DataPart.class, message.getParts().get(0));
        }

        @Test
        @DisplayName(
                "Should execute agent and process blocking request with inner event but disabled")
        void testExecuteAgentWithBlockingRequestDisabledInnerEvent() throws JSONRPCError {
            doMockForContext(false, true, false);
            Flux<Event> mockFlux = mockFlux(true, false, false);
            when(mockAgentRunner.stream(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);
            AtomicReference<Message> messageRef = new AtomicReference<>();
            doAnswer(
                            (Answer<Void>)
                                    invocationOnMock -> {
                                        Object arg = invocationOnMock.getArgument(0);
                                        messageRef.set((Message) arg);
                                        return null;
                                    })
                    .when(mockEventQueue)
                    .enqueueEvent(any(Message.class));
            executor.execute(mockContext, mockEventQueue);

            assertNotNull(messageRef.get());
            assertBlockResultMessage(
                    messageRef.get(),
                    List.of("streaming result 1", " 2"),
                    mockContext.getTaskId(),
                    mockContext.getContextId());
        }

        @Test
        @DisplayName("Should execute error agent and process blocking request")
        void testExecuteAgentWithError() throws JSONRPCError {
            doMockForContext(false, false, false);
            Flux<Event> mockFlux = mockFlux(false, true, true);
            when(mockAgentRunner.stream(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);
            AtomicReference<Message> messageRef = new AtomicReference<>();
            doAnswer(
                            (Answer<Void>)
                                    invocationOnMock -> {
                                        Object arg = invocationOnMock.getArgument(0);
                                        messageRef.set((Message) arg);
                                        return null;
                                    })
                    .when(mockEventQueue)
                    .enqueueEvent(any(Message.class));
            executor.execute(mockContext, mockEventQueue);

            assertNotNull(messageRef.get());
            assertBlockResultMessage(
                    messageRef.get(),
                    List.of("Agent execution failed: mock test"),
                    mockContext.getTaskId(),
                    mockContext.getContextId());
        }

        private void assertBlockResultMessage(
                Message message, List<String> expectedBlocks, String taskId, String contextId) {
            assertEquals(taskId, message.getTaskId());
            assertEquals(contextId, message.getContextId());
            assertEquals(expectedBlocks.size(), message.getParts().size());
            for (int i = 0; i < expectedBlocks.size(); i++) {
                assertInstanceOf(TextPart.class, message.getParts().get(i));
                assertEquals(
                        expectedBlocks.get(i), ((TextPart) message.getParts().get(i)).getText());
            }
        }
    }

    @Nested
    @DisplayName("Execute For Streaming Request")
    class ExecuteForStreamingRequestTests {

        @Test
        @DisplayName("Should execute agent and process streaming request")
        void testExecuteAgentWithStreamingRequest() throws JSONRPCError {
            doMockForContext(true, false, false);
            Flux<Event> mockFlux = mockFlux(false, true, false);
            when(mockAgentRunner.stream(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();
            executor.execute(mockContext, mockEventQueue);

            assertFalse(messageRef.get().isEmpty());
            assertStreamingEventKind(
                    messageRef.get(),
                    List.of("streaming result 1", " 2"),
                    mockContext.getTaskId(),
                    mockContext.getContextId(),
                    false,
                    false);
        }

        @Test
        @DisplayName("Should execute agent and process streaming request with inner event")
        void testExecuteAgentWithStreamingRequestWithInnerEvent() throws JSONRPCError {
            AgentExecuteProperties agentExecuteProperties =
                    AgentExecuteProperties.builder().requireInnerMessage(true).build();
            executor = new AgentScopeAgentExecutor(mockAgentRunner, agentExecuteProperties);
            doMockForContext(true, false, false);
            Flux<Event> mockFlux = mockFlux(true, true, false);
            when(mockAgentRunner.stream(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);
            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();
            executor.execute(mockContext, mockEventQueue);

            assertFalse(messageRef.get().isEmpty());
            assertStreamingEventKind(
                    messageRef.get(),
                    List.of("streaming result 1", " 2"),
                    mockContext.getTaskId(),
                    mockContext.getContextId(),
                    true,
                    false);
        }

        @Test
        @DisplayName(
                "Should execute agent and process streaming request with inner event but disabled")
        void testExecuteAgentWithStreamingRequestDisabledInnerEvent() throws JSONRPCError {
            doMockForContext(true, false, false);
            Flux<Event> mockFlux = mockFlux(true, true, false);
            when(mockAgentRunner.stream(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);
            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();
            executor.execute(mockContext, mockEventQueue);

            assertFalse(messageRef.get().isEmpty());
            assertStreamingEventKind(
                    messageRef.get(),
                    List.of("streaming result 1", " 2"),
                    mockContext.getTaskId(),
                    mockContext.getContextId(),
                    false,
                    false);
        }

        @Test
        @DisplayName("Should execute agent and process streaming request with completed message")
        void testExecuteAgentWithStreamingRequestCompletedMessage() throws JSONRPCError {
            AgentExecuteProperties agentExecuteProperties =
                    AgentExecuteProperties.builder().completeWithMessage(true).build();
            executor = new AgentScopeAgentExecutor(mockAgentRunner, agentExecuteProperties);
            doMockForContext(true, false, false);
            Flux<Event> mockFlux = mockFlux(false, true, false);
            when(mockAgentRunner.stream(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);
            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();
            executor.execute(mockContext, mockEventQueue);

            assertFalse(messageRef.get().isEmpty());
            assertStreamingEventKind(
                    messageRef.get(),
                    List.of("streaming result 1", " 2"),
                    mockContext.getTaskId(),
                    mockContext.getContextId(),
                    false,
                    true);
        }

        @Test
        @DisplayName("Should execute fail agent and process streaming request")
        void testExecuteAgentWithStreamingRequestFailure() throws JSONRPCError {
            doMockForContext(true, false, false);
            Flux<Event> mockFlux = mockFlux(false, false, true);
            when(mockAgentRunner.stream(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();
            executor.execute(mockContext, mockEventQueue);

            assertFalse(messageRef.get().isEmpty());
            assertTrue(
                    messageRef.get().stream()
                            .filter(event -> event instanceof TaskStatusUpdateEvent)
                            .map(event -> (TaskStatusUpdateEvent) event)
                            .anyMatch(event -> TaskState.FAILED.equals(event.getStatus().state())));
        }

        private AtomicReference<List<StreamingEventKind>> mockStreamingEventQueueRef() {
            AtomicReference<List<StreamingEventKind>> messageRef =
                    new AtomicReference<>(new LinkedList<>());
            doAnswer(
                            (Answer<Void>)
                                    invocationOnMock -> {
                                        Object arg = invocationOnMock.getArgument(0);
                                        messageRef.get().add((StreamingEventKind) arg);
                                        return null;
                                    })
                    .when(mockEventQueue)
                    .enqueueEvent(any(StreamingEventKind.class));
            return messageRef;
        }

        private void assertStreamingEventKind(
                List<StreamingEventKind> streamingEventKinds,
                List<String> expectedBlocks,
                String taskId,
                String contextId,
                boolean withToolResult,
                boolean completeWithMessage) {
            int additionalEventSize = withToolResult ? 4 : 3;
            assertEquals(expectedBlocks.size() + additionalEventSize, streamingEventKinds.size());
            assertInstanceOf(Task.class, streamingEventKinds.get(0));
            assertEquals(taskId, ((Task) streamingEventKinds.get(0)).getId());
            assertEquals(contextId, ((Task) streamingEventKinds.get(0)).getContextId());
            assertEquals(
                    TaskState.SUBMITTED, ((Task) streamingEventKinds.get(0)).getStatus().state());
            assertInstanceOf(TaskStatusUpdateEvent.class, streamingEventKinds.get(1));
            assertEquals(taskId, ((TaskStatusUpdateEvent) streamingEventKinds.get(1)).getTaskId());
            assertEquals(
                    TaskState.WORKING,
                    ((TaskStatusUpdateEvent) streamingEventKinds.get(1)).getStatus().state());
            assertEquals(
                    contextId, ((TaskStatusUpdateEvent) streamingEventKinds.get(1)).getContextId());
            if (withToolResult) {
                assertInstanceOf(TaskArtifactUpdateEvent.class, streamingEventKinds.get(2));
                TaskArtifactUpdateEvent artifactUpdateEvent =
                        (TaskArtifactUpdateEvent) streamingEventKinds.get(2);
                assertEquals(taskId, artifactUpdateEvent.getTaskId());
                assertEquals(contextId, artifactUpdateEvent.getContextId());
                assertInstanceOf(DataPart.class, artifactUpdateEvent.getArtifact().parts().get(0));
            }
            List<StreamingEventKind> subEvent =
                    streamingEventKinds.subList(
                            withToolResult ? 3 : 2, streamingEventKinds.size() - 1);
            for (int i = 0; i < expectedBlocks.size(); i++) {
                assertInstanceOf(TaskArtifactUpdateEvent.class, subEvent.get(i));
                TaskArtifactUpdateEvent artifactUpdateEvent =
                        (TaskArtifactUpdateEvent) subEvent.get(i);
                assertEquals(taskId, artifactUpdateEvent.getTaskId());
                assertEquals(contextId, artifactUpdateEvent.getContextId());
                Artifact artifact = artifactUpdateEvent.getArtifact();
                assertEquals(1, artifact.parts().size());
                assertInstanceOf(TextPart.class, artifact.parts().get(0));
                assertEquals(expectedBlocks.get(i), ((TextPart) artifact.parts().get(0)).getText());
            }
            StreamingEventKind completedEvent =
                    streamingEventKinds.get(streamingEventKinds.size() - 1);
            assertInstanceOf(TaskStatusUpdateEvent.class, completedEvent);
            assertEquals(taskId, ((TaskStatusUpdateEvent) completedEvent).getTaskId());
            assertEquals(
                    TaskState.COMPLETED,
                    ((TaskStatusUpdateEvent) completedEvent).getStatus().state());
            assertEquals(contextId, ((TaskStatusUpdateEvent) completedEvent).getContextId());
            if (completeWithMessage) {
                assertNotNull(((TaskStatusUpdateEvent) completedEvent).getStatus().message());
            } else {
                assertNull(((TaskStatusUpdateEvent) completedEvent).getStatus().message());
            }
        }
    }

    @Nested
    @DisplayName("Cancel Task Tests")
    class CancelTaskTests {

        @Test
        @DisplayName("Should cancel task successfully")
        void testCancelTaskSuccessfully()
                throws JSONRPCError, ExecutionException, InterruptedException, TimeoutException {
            // Given
            String taskId = doMockForContext(false, true, false);

            AtomicBoolean isCancelled = new AtomicBoolean(false);
            Flux<Event> mockFlux =
                    Flux.fromIterable(
                                    List.of(
                                            new Event(
                                                    EventType.REASONING,
                                                    Msg.builder().textContent("test").build(),
                                                    true),
                                            new Event(
                                                    EventType.AGENT_RESULT,
                                                    Msg.builder().textContent("test").build(),
                                                    true)))
                            .zipWith(Flux.range(0, 2))
                            .delayUntil(
                                    tuple -> {
                                        int index = tuple.getT2();
                                        if (index == 0) {
                                            return Mono.empty();
                                        } else {
                                            return Mono.delay(Duration.ofSeconds(1));
                                        }
                                    })
                            .map(Tuple2::getT1)
                            .doOnCancel(() -> isCancelled.set(true));
            when(mockAgentRunner.stream(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            Thread taskThread = new Thread(() -> executor.execute(mockContext, mockEventQueue));
            try {
                taskThread.start();

                TimeUnit.MILLISECONDS.sleep(500);

                // When
                executor.cancel(mockContext, mockEventQueue);

                // Then
                verify(mockAgentRunner).stop(taskId);
                TimeUnit.MILLISECONDS.sleep(500);
                assertTrue(isCancelled.get());
            } finally {
                taskThread.interrupt();
            }
        }

        @Test
        @DisplayName("Should cancel task successfully when no task found")
        void testCancelTaskSuccessfullyNoTaskFound() throws JSONRPCError {
            // Given
            String taskId = doMockForContext(false, true, false);

            // When
            executor.cancel(mockContext, mockEventQueue);

            // Then
            verify(mockAgentRunner).stop(taskId);
        }

        @Test
        @DisplayName("Should handle exception during task cancellation")
        void testHandleExceptionDuringTaskCancellation() throws JSONRPCError {
            // Given
            String taskId = doMockForContext(true, false, false);

            when(mockContext.getTaskId()).thenReturn(taskId);
            doThrow(new RuntimeException("Cancellation error")).when(mockAgentRunner).stop(taskId);

            // When
            executor.cancel(mockContext, mockEventQueue);

            // Then
            verify(mockAgentRunner).stop(taskId);
        }
    }

    @Test
    @DisplayName("Should handle exception during execution")
    void testHandleExceptionDuringExecution() throws JSONRPCError {
        doMockForContext(true, false, false);
        // Given
        when(mockContext.getTask()).thenThrow(new RuntimeException("Context error"));
        when(mockContext.getTaskId()).thenReturn("mock Task Id");

        // When
        executor.execute(mockContext, mockEventQueue);

        // Then
        verify(mockEventQueue).enqueueEvent(any(Message.class));
    }

    private Flux<Event> mockFlux(
            boolean withToolResult, boolean withResultEvent, boolean withError) {
        List<Event> mockEvents = new LinkedList<>();
        if (withError) {
            return Flux.error(new RuntimeException("mock test"));
        }
        String resultMsgId = UUID.randomUUID().toString();
        if (withToolResult) {
            ContentBlock mockToolResultBlock = ToolResultBlock.text("mock tool result");
            mockEvents.add(
                    new Event(
                            EventType.TOOL_RESULT,
                            Msg.builder().content(mockToolResultBlock).build(),
                            true));
        }
        mockEvents.add(
                new Event(
                        EventType.REASONING,
                        Msg.builder().textContent("streaming result 1").id(resultMsgId).build(),
                        false));
        mockEvents.add(
                new Event(
                        EventType.REASONING,
                        Msg.builder().textContent(" 2").id(resultMsgId).build(),
                        false));
        mockEvents.add(
                new Event(
                        EventType.REASONING,
                        Msg.builder().textContent("streaming result 1 2").id(resultMsgId).build(),
                        true));
        if (withResultEvent) {
            mockEvents.add(
                    new Event(
                            EventType.AGENT_RESULT,
                            Msg.builder().textContent("streaming result 1 2").build(),
                            true));
        }
        return Flux.fromIterable(mockEvents).delayElements(Duration.ofMillis(10));
    }
}
