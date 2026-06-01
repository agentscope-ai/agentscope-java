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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendConfiguration;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TextPart;
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
    private AgentEmitter mockAgentEmitter;
    private ServerCallContext serverCallContext;

    @BeforeEach
    void setUp() {
        AgentExecuteProperties agentExecuteProperties = AgentExecuteProperties.builder().build();
        mockAgentRunner = mock(AgentRunner.class);
        executor = new AgentScopeAgentExecutor(mockAgentRunner, agentExecuteProperties);
        mockContext = mock(RequestContext.class);
        mockAgentEmitter = mock(AgentEmitter.class);
        serverCallContext = mock(ServerCallContext.class);
    }

    private String doMockForContext(
            boolean isStreaming, boolean blockingByState, boolean blockingByConfig) {
        String taskId = UUID.randomUUID().toString();
        String contextId = UUID.randomUUID().toString();

        when(mockContext.getTaskId()).thenReturn(taskId);
        when(mockContext.getContextId()).thenReturn(contextId);

        Message mockMessage = mock(Message.class);
        when(mockMessage.taskId()).thenReturn(taskId);
        when(mockMessage.contextId()).thenReturn(contextId);
        when(mockMessage.parts()).thenReturn(List.of());
        when(mockContext.getMessage()).thenReturn(mockMessage);

        MessageSendParams mockParams = mock(MessageSendParams.class);
        when(mockContext.getConfiguration()).thenReturn(null);
        when(mockParams.message()).thenReturn(mockMessage);

        when(mockContext.getCallContext()).thenReturn(serverCallContext);
        if (isStreaming || blockingByState) {
            when(serverCallContext.getState())
                    .thenReturn(Map.of(A2aServerConstants.ContextKeys.IS_STREAM_KEY, isStreaming));
        }
        if (blockingByConfig) {
            MessageSendConfiguration messageSendConfiguration =
                    MessageSendConfiguration.builder().build();
            when(mockParams.configuration()).thenReturn(messageSendConfiguration);
        }
        return taskId;
    }

    @Nested
    @DisplayName("Execute For Blocking Request")
    class ExecuteForBlockingRequestTests {
        @Test
        @DisplayName("Should execute agent and process blocking request")
        void testExecuteAgentWithBlockingRequest() throws A2AError {
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
                    .when(mockAgentEmitter)
                    .emitEvent(any(Message.class));
            executor.execute(mockContext, mockAgentEmitter);

            assertNotNull(messageRef.get());
            assertBlockResultMessage(
                    messageRef.get(),
                    List.of("streaming result 1 2"),
                    mockContext.getTaskId(),
                    mockContext.getContextId());
        }

        @Test
        @DisplayName("Should execute agent and process blocking request without agent result event")
        void testExecuteAgentWithBlockingRequestWithoutAgentResultEvent() throws A2AError {
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
                    .when(mockAgentEmitter)
                    .emitEvent(any(Message.class));
            executor.execute(mockContext, mockAgentEmitter);

            assertNotNull(messageRef.get());
            assertBlockResultMessage(
                    messageRef.get(),
                    List.of("streaming result 1", " 2"),
                    mockContext.getTaskId(),
                    mockContext.getContextId());
        }

        @Test
        @DisplayName("Should execute agent and process blocking request with inner event")
        void testExecuteAgentWithBlockingRequestWithInnerEvent() throws A2AError {
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
                    .when(mockAgentEmitter)
                    .emitEvent(any(Message.class));
            executor.execute(mockContext, mockAgentEmitter);

            assertNotNull(messageRef.get());
            Message message = messageRef.get();
            assertEquals(mockContext.getTaskId(), message.taskId());
            assertEquals(mockContext.getContextId(), message.contextId());
            assertEquals(3, message.parts().size());
            assertInstanceOf(DataPart.class, message.parts().get(0));
        }

        @Test
        @DisplayName(
                "Should execute agent and process blocking request with inner event but disabled")
        void testExecuteAgentWithBlockingRequestDisabledInnerEvent() throws A2AError {
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
                    .when(mockAgentEmitter)
                    .emitEvent(any(Message.class));
            executor.execute(mockContext, mockAgentEmitter);

            assertNotNull(messageRef.get());
            assertBlockResultMessage(
                    messageRef.get(),
                    List.of("streaming result 1", " 2"),
                    mockContext.getTaskId(),
                    mockContext.getContextId());
        }

        @Test
        @DisplayName("Should execute error agent and process blocking request")
        void testExecuteAgentWithError() throws A2AError {
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
                    .when(mockAgentEmitter)
                    .emitEvent(any(Message.class));
            executor.execute(mockContext, mockAgentEmitter);

            assertNotNull(messageRef.get());
            assertBlockResultMessage(
                    messageRef.get(),
                    List.of("Handle Agent execute error: mock test"),
                    mockContext.getTaskId(),
                    mockContext.getContextId());
        }

        private void assertBlockResultMessage(
                Message message, List<String> expectedBlocks, String taskId, String contextId) {
            assertEquals(taskId, message.taskId());
            assertEquals(contextId, message.contextId());
            assertEquals(expectedBlocks.size(), message.parts().size());
            for (int i = 0; i < expectedBlocks.size(); i++) {
                assertInstanceOf(TextPart.class, message.parts().get(i));
                assertEquals(expectedBlocks.get(i), ((TextPart) message.parts().get(i)).text());
            }
        }
    }

    @Nested
    @DisplayName("Execute For Streaming Request")
    class ExecuteForStreamingRequestTests {

        @Test
        @DisplayName("Should execute agent and process streaming request")
        void testExecuteAgentWithStreamingRequest() throws A2AError {
            String taskId = doMockForContext(true, false, false);
            String contextId = mockContext.getContextId();
            when(mockAgentEmitter.getTaskId()).thenReturn(taskId);
            when(mockAgentEmitter.getContextId()).thenReturn(contextId);

            Flux<Event> mockFlux = mockFlux(false, true, false);
            when(mockAgentRunner.stream(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingAgentEmitterRef();
            executor.execute(mockContext, mockAgentEmitter);

            assertFalse(messageRef.get().isEmpty());
            verify(mockAgentEmitter).emitEvent(any(Task.class));
            verify(mockAgentEmitter).startWork();
            verify(mockAgentEmitter, times(2))
                    .addArtifact(
                            anyList(), anyString(), anyString(), any(), anyBoolean(), anyBoolean());
            verify(mockAgentEmitter).complete((Message) any());
        }

        @Test
        @DisplayName("Should execute agent and process streaming request with inner event")
        void testExecuteAgentWithStreamingRequestWithInnerEvent() throws A2AError {
            AgentExecuteProperties agentExecuteProperties =
                    AgentExecuteProperties.builder().requireInnerMessage(true).build();
            executor = new AgentScopeAgentExecutor(mockAgentRunner, agentExecuteProperties);
            String taskId = doMockForContext(true, false, false);
            String contextId = mockContext.getContextId();
            when(mockAgentEmitter.getTaskId()).thenReturn(taskId);
            when(mockAgentEmitter.getContextId()).thenReturn(contextId);

            Flux<Event> mockFlux = mockFlux(true, true, false);
            when(mockAgentRunner.stream(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingAgentEmitterRef();
            executor.execute(mockContext, mockAgentEmitter);

            assertFalse(messageRef.get().isEmpty());
            verify(mockAgentEmitter).emitEvent(any(Task.class));
            verify(mockAgentEmitter).startWork();
            verify(mockAgentEmitter, times(3))
                    .addArtifact(
                            anyList(), anyString(), anyString(), any(), anyBoolean(), anyBoolean());
            verify(mockAgentEmitter).complete((Message) any());
        }

        @Test
        @DisplayName(
                "Should execute agent and process streaming request with inner event but disabled")
        void testExecuteAgentWithStreamingRequestDisabledInnerEvent() throws A2AError {
            String taskId = doMockForContext(true, false, false);
            String contextId = mockContext.getContextId();
            when(mockAgentEmitter.getTaskId()).thenReturn(taskId);
            when(mockAgentEmitter.getContextId()).thenReturn(contextId);

            Flux<Event> mockFlux = mockFlux(true, true, false);
            when(mockAgentRunner.stream(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingAgentEmitterRef();
            executor.execute(mockContext, mockAgentEmitter);

            assertFalse(messageRef.get().isEmpty());
            verify(mockAgentEmitter).emitEvent(any(Task.class));
            verify(mockAgentEmitter).startWork();
            verify(mockAgentEmitter, times(2))
                    .addArtifact(
                            anyList(), anyString(), anyString(), any(), anyBoolean(), anyBoolean());
            verify(mockAgentEmitter).complete((Message) any());
        }

        @Test
        @DisplayName("Should execute agent and process streaming request with completed message")
        void testExecuteAgentWithStreamingRequestCompletedMessage() throws A2AError {
            AgentExecuteProperties agentExecuteProperties =
                    AgentExecuteProperties.builder().completeWithMessage(true).build();
            executor = new AgentScopeAgentExecutor(mockAgentRunner, agentExecuteProperties);
            String taskId = doMockForContext(true, false, false);
            String contextId = mockContext.getContextId();
            when(mockAgentEmitter.getTaskId()).thenReturn(taskId);
            when(mockAgentEmitter.getContextId()).thenReturn(contextId);

            Flux<Event> mockFlux = mockFlux(false, true, false);
            when(mockAgentRunner.stream(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingAgentEmitterRef();
            executor.execute(mockContext, mockAgentEmitter);

            assertFalse(messageRef.get().isEmpty());
            verify(mockAgentEmitter).emitEvent(any(Task.class));
            verify(mockAgentEmitter).startWork();
            verify(mockAgentEmitter, times(2))
                    .addArtifact(
                            anyList(), anyString(), anyString(), any(), anyBoolean(), anyBoolean());
            verify(mockAgentEmitter).complete(any(Message.class));
        }

        @Test
        @DisplayName("Should execute fail agent and process streaming request")
        void testExecuteAgentWithStreamingRequestFailure() throws A2AError {
            String taskId = doMockForContext(true, false, false);
            String contextId = mockContext.getContextId();
            when(mockAgentEmitter.getTaskId()).thenReturn(taskId);
            when(mockAgentEmitter.getContextId()).thenReturn(contextId);

            Flux<Event> mockFlux = mockFlux(false, false, true);
            when(mockAgentRunner.stream(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingAgentEmitterRef();
            executor.execute(mockContext, mockAgentEmitter);

            assertFalse(messageRef.get().isEmpty());
            verify(mockAgentEmitter).emitEvent(any(Task.class));
            verify(mockAgentEmitter).startWork();
            verify(mockAgentEmitter).fail(any(Message.class));
        }

        private AtomicReference<List<StreamingEventKind>> mockStreamingAgentEmitterRef() {
            AtomicReference<List<StreamingEventKind>> messageRef =
                    new AtomicReference<>(new LinkedList<>());
            doAnswer(
                            (Answer<Void>)
                                    invocationOnMock -> {
                                        Object arg = invocationOnMock.getArgument(0);
                                        messageRef.get().add((StreamingEventKind) arg);
                                        return null;
                                    })
                    .when(mockAgentEmitter)
                    .emitEvent(any(StreamingEventKind.class));
            return messageRef;
        }
    }

    @Nested
    @DisplayName("Cancel Task Tests")
    class CancelTaskTests {

        @Test
        @DisplayName("Should cancel task successfully")
        void testCancelTaskSuccessfully()
                throws A2AError, ExecutionException, InterruptedException, TimeoutException {
            // Given
            String taskId = doMockForContext(false, true, false);
            when(mockAgentEmitter.getTaskId()).thenReturn(taskId);

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

            Thread taskThread = new Thread(() -> executor.execute(mockContext, mockAgentEmitter));
            try {
                taskThread.start();

                TimeUnit.MILLISECONDS.sleep(500);

                // When
                executor.cancel(mockContext, mockAgentEmitter);

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
        void testCancelTaskSuccessfullyNoTaskFound() throws A2AError {
            // Given
            String taskId = doMockForContext(false, true, false);
            when(mockAgentEmitter.getTaskId()).thenReturn(taskId);

            // When
            executor.cancel(mockContext, mockAgentEmitter);

            // Then
            verify(mockAgentRunner).stop(taskId);
        }

        @Test
        @DisplayName("Should handle exception during task cancellation")
        void testHandleExceptionDuringTaskCancellation() throws A2AError {
            // Given
            String taskId = doMockForContext(true, false, false);
            when(mockAgentEmitter.getTaskId()).thenReturn(taskId);

            when(mockContext.getTaskId()).thenReturn(taskId);
            doThrow(new RuntimeException("Cancellation error")).when(mockAgentRunner).stop(taskId);

            // When
            executor.cancel(mockContext, mockAgentEmitter);

            // Then
            verify(mockAgentRunner).stop(taskId);
        }
    }

    @Test
    @DisplayName("Should handle exception during execution")
    void testHandleExceptionDuringExecution() throws A2AError {
        doMockForContext(true, false, false);
        when(mockContext.getTask()).thenThrow(new RuntimeException("Context error"));
        when(mockContext.getTaskId()).thenReturn("mock Task Id");

        Message mockMsg = mock(Message.class);
        when(mockAgentEmitter.newAgentMessage(anyList(), any(Map.class))).thenReturn(mockMsg);

        executor.execute(mockContext, mockAgentEmitter);

        verify(mockAgentEmitter).fail(any(Message.class));
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
