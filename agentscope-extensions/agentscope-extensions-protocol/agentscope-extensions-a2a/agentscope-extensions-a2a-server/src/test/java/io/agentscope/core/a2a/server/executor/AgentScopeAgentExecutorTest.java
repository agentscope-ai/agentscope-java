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

import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.a2a.server.constants.A2aServerConstants;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolProgressEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatUsage;
import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.events.EventQueue;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendConfiguration;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
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
        when(mockMessage.taskId()).thenReturn(taskId);
        when(mockMessage.contextId()).thenReturn(contextId);
        when(mockMessage.parts()).thenReturn(List.of());
        when(mockContext.getMessage()).thenReturn(mockMessage);

        when(mockContext.getCallContext()).thenReturn(serverCallContext);
        if (isStreaming || blockingByState) {
            when(serverCallContext.getState())
                    .thenReturn(Map.of(A2aServerConstants.ContextKeys.IS_STREAM_KEY, isStreaming));
        }
        if (blockingByConfig) {
            MessageSendConfiguration messageSendConfiguration =
                    MessageSendConfiguration.builder().build();
            when(mockContext.getConfiguration()).thenReturn(messageSendConfiguration);
        }
        return taskId;
    }

    /**
     * An emitter that writes to {@code mockEventQueue}, as the SDK creates one for each execute and
     * cancel call. Create it after the context is mocked: it reads the task and context ids.
     */
    private AgentEmitter emitter() {
        return new AgentEmitter(mockContext, mockEventQueue);
    }

    @Nested
    @DisplayName("Execute For Blocking Request")
    class ExecuteForBlockingRequestTests {
        @Test
        @DisplayName("Should execute agent and process blocking request")
        void testExecuteAgentWithBlockingRequest() throws A2AError {
            doMockForContext(false, false, true);
            Flux<AgentEvent> mockFlux = mockFlux(false, true, false);
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
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
            executor.execute(mockContext, emitter());

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
            Flux<AgentEvent> mockFlux = mockFlux(false, false, false);
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
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
            executor.execute(mockContext, emitter());

            assertNotNull(messageRef.get());
            assertBlockResultMessage(
                    messageRef.get(),
                    List.of("streaming result 1 2"),
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
            Flux<AgentEvent> mockFlux = mockFlux(true, false, false);
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
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
            executor.execute(mockContext, emitter());

            assertNotNull(messageRef.get());
            Message message = messageRef.get();
            assertEquals(mockContext.getTaskId(), message.taskId());
            assertEquals(mockContext.getContextId(), message.contextId());
            assertEquals(2, message.parts().size());
            assertInstanceOf(DataPart.class, message.parts().get(0));
            assertInstanceOf(TextPart.class, message.parts().get(1));
            assertEquals("streaming result 1 2", ((TextPart) message.parts().get(1)).text());
        }

        @Test
        @DisplayName("Should preserve hint semantics in blocking fallback")
        void testExecuteAgentWithBlockingRequestPreservesHint() throws A2AError {
            AgentExecuteProperties agentExecuteProperties =
                    AgentExecuteProperties.builder().requireInnerMessage(true).build();
            executor = new AgentScopeAgentExecutor(mockAgentRunner, agentExecuteProperties);
            doMockForContext(false, true, false);
            Flux<AgentEvent> mockFlux =
                    Flux.just(
                            new HintBlockEvent(
                                    "reply-id", "hint-block-id", "alice", "Check the inbox"),
                            new TextBlockDeltaEvent("reply-id", "text-block-id", "Final answer"));
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            AtomicReference<Message> messageRef = new AtomicReference<>();
            doAnswer(
                            (Answer<Void>)
                                    invocationOnMock -> {
                                        messageRef.set(invocationOnMock.getArgument(0));
                                        return null;
                                    })
                    .when(mockEventQueue)
                    .enqueueEvent(any(Message.class));
            executor.execute(mockContext, emitter());

            Message message = messageRef.get();
            assertNotNull(message);
            assertEquals(2, message.parts().size());
            TextPart hintPart = assertInstanceOf(TextPart.class, message.parts().get(0));
            assertEquals("Check the inbox", hintPart.text());
            assertEquals(
                    MessageConstants.BlockContent.TYPE_HINT,
                    hintPart.metadata().get(MessageConstants.BLOCK_TYPE_METADATA_KEY));
            assertEquals(
                    "hint-block-id",
                    hintPart.metadata().get(MessageConstants.HINT_ID_METADATA_KEY));
            assertEquals(
                    "alice", hintPart.metadata().get(MessageConstants.HINT_SOURCE_METADATA_KEY));
            TextPart answerPart = assertInstanceOf(TextPart.class, message.parts().get(1));
            assertEquals("Final answer", answerPart.text());
        }

        @Test
        @DisplayName(
                "Should execute agent and process blocking request with inner event but disabled")
        void testExecuteAgentWithBlockingRequestDisabledInnerEvent() throws A2AError {
            doMockForContext(false, true, false);
            Flux<AgentEvent> mockFlux = mockFlux(true, false, false);
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
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
            executor.execute(mockContext, emitter());

            assertNotNull(messageRef.get());
            assertBlockResultMessage(
                    messageRef.get(),
                    List.of("streaming result 1 2"),
                    mockContext.getTaskId(),
                    mockContext.getContextId());
        }

        @Test
        @DisplayName("Should execute error agent and process blocking request")
        void testExecuteAgentWithError() throws A2AError {
            doMockForContext(false, false, false);
            Flux<AgentEvent> mockFlux = mockFlux(false, true, true);
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
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
            executor.execute(mockContext, emitter());

            assertNotNull(messageRef.get());
            assertBlockResultMessage(
                    messageRef.get(),
                    List.of("Agent execution failed: mock test"),
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
        @DisplayName("Should send event metadata objects as JSON values")
        void testStreamingEventMetadataIsJson() throws A2AError {
            doMockForContext(true, false, false);
            ChatUsage usage = ChatUsage.builder().inputTokens(3).outputTokens(5).build();
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(
                            Flux.just(
                                    new TextBlockDeltaEvent("reply-id", "block-id", "done")
                                            .withMetadata(Map.of("usage", usage))));
            List<Object> events = new CopyOnWriteArrayList<>();
            doAnswer(
                            invocation -> {
                                events.add(invocation.getArgument(0));
                                return null;
                            })
                    .when(mockEventQueue)
                    .enqueueEvent(any());

            executor.execute(mockContext, emitter());

            TaskArtifactUpdateEvent artifactUpdate =
                    events.stream()
                            .filter(TaskArtifactUpdateEvent.class::isInstance)
                            .map(TaskArtifactUpdateEvent.class::cast)
                            .findFirst()
                            .orElseThrow();
            Map<?, ?> json =
                    assertInstanceOf(Map.class, artifactUpdate.artifact().metadata().get("usage"));
            assertEquals(3, json.get("inputTokens"));
            assertEquals(5, json.get("outputTokens"));
        }

        @Test
        @DisplayName("Should execute agent and process streaming request")
        void testExecuteAgentWithStreamingRequest() throws A2AError {
            doMockForContext(true, false, false);
            Flux<AgentEvent> mockFlux = mockFlux(false, true, false);
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();
            executor.execute(mockContext, emitter());

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
        void testExecuteAgentWithStreamingRequestWithInnerEvent() throws A2AError {
            AgentExecuteProperties agentExecuteProperties =
                    AgentExecuteProperties.builder().requireInnerMessage(true).build();
            executor = new AgentScopeAgentExecutor(mockAgentRunner, agentExecuteProperties);
            doMockForContext(true, false, false);
            Flux<AgentEvent> mockFlux = mockFlux(true, true, false);
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);
            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();
            executor.execute(mockContext, emitter());

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
        @DisplayName("Should preserve hint and tool metadata in streaming artifacts")
        void testExecuteAgentWithStreamingRequestPreservesEventMetadata() throws A2AError {
            AgentExecuteProperties agentExecuteProperties =
                    AgentExecuteProperties.builder().requireInnerMessage(true).build();
            executor = new AgentScopeAgentExecutor(mockAgentRunner, agentExecuteProperties);
            doMockForContext(true, false, false);
            Map<String, Object> hintMetadata = Map.of("channel", "inbox");
            Map<String, Object> toolMetadata = Map.of("traceId", "trace-1");
            HintBlockEvent hintEvent =
                    new HintBlockEvent("reply-id", "hint-block-id", "alice", "Check the inbox");
            hintEvent.withMetadata(hintMetadata);
            ToolResultTextDeltaEvent toolEvent =
                    new ToolResultTextDeltaEvent(
                            "reply-id", "tool-call-id", "search", "tool result");
            toolEvent.withMetadata(toolMetadata);
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(Flux.just(hintEvent, toolEvent));
            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();

            executor.execute(mockContext, emitter());

            List<Artifact> artifacts =
                    messageRef.get().stream()
                            .filter(TaskArtifactUpdateEvent.class::isInstance)
                            .map(TaskArtifactUpdateEvent.class::cast)
                            .map(TaskArtifactUpdateEvent::artifact)
                            .toList();
            assertEquals(2, artifacts.size());

            Artifact hintArtifact = artifacts.get(0);
            assertEquals(hintMetadata, hintArtifact.metadata());
            TextPart hintPart = assertInstanceOf(TextPart.class, hintArtifact.parts().get(0));
            assertEquals(
                    MessageConstants.BlockContent.TYPE_HINT,
                    hintPart.metadata().get(MessageConstants.BLOCK_TYPE_METADATA_KEY));
            assertEquals(
                    "hint-block-id",
                    hintPart.metadata().get(MessageConstants.HINT_ID_METADATA_KEY));
            assertEquals(
                    "alice", hintPart.metadata().get(MessageConstants.HINT_SOURCE_METADATA_KEY));
            assertFalse(
                    hintPart.metadata().containsKey(MessageConstants.STREAM_CHUNK_METADATA_KEY));

            Artifact toolArtifact = artifacts.get(1);
            assertEquals(toolMetadata, toolArtifact.metadata());
            DataPart toolPart = assertInstanceOf(DataPart.class, toolArtifact.parts().get(0));
            assertEquals(
                    Boolean.TRUE,
                    toolPart.metadata().get(MessageConstants.STREAM_CHUNK_METADATA_KEY));
            assertEquals("trace-1", toolPart.metadata().get("traceId"));
        }

        @Test
        @DisplayName("Should forward tool progress while inner events are enabled")
        void testToolProgressReachesClientWhenInnerEventsEnabled() throws A2AError {
            AgentExecuteProperties properties =
                    AgentExecuteProperties.builder().requireInnerMessage(true).build();
            executor = new AgentScopeAgentExecutor(mockAgentRunner, properties);
            doMockForContext(true, false, false);
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(
                            Flux.just(
                                    new ToolProgressEvent(
                                            "reply-id",
                                            "tool-call-id",
                                            "search",
                                            TextBlock.builder().text("scanning 3 dirs").build())));
            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();

            executor.execute(mockContext, emitter());

            List<Artifact> artifacts =
                    messageRef.get().stream()
                            .filter(TaskArtifactUpdateEvent.class::isInstance)
                            .map(TaskArtifactUpdateEvent.class::cast)
                            .map(TaskArtifactUpdateEvent::artifact)
                            .toList();
            assertEquals(
                    1,
                    artifacts.size(),
                    "progress moved to its own event type but still reaches the client");
            DataPart part = assertInstanceOf(DataPart.class, artifacts.get(0).parts().get(0));
            assertEquals(
                    Boolean.TRUE, part.metadata().get(MessageConstants.STREAM_CHUNK_METADATA_KEY));
        }

        @Test
        @DisplayName("Should withhold tool progress when inner events are disabled")
        void testToolProgressIsWithheldWhenInnerEventsDisabled() throws A2AError {
            doMockForContext(true, false, false);
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(
                            Flux.just(
                                    new ToolProgressEvent(
                                            "reply-id",
                                            "tool-call-id",
                                            "search",
                                            TextBlock.builder().text("scanning 3 dirs").build())));
            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();

            executor.execute(mockContext, emitter());

            List<Artifact> artifacts =
                    messageRef.get().stream()
                            .filter(TaskArtifactUpdateEvent.class::isInstance)
                            .map(TaskArtifactUpdateEvent.class::cast)
                            .map(TaskArtifactUpdateEvent::artifact)
                            .toList();
            assertTrue(
                    artifacts.isEmpty(),
                    "the default configuration must not start streaming progress: " + artifacts);
        }

        @Test
        @DisplayName("Should exclude tool progress from the completed message")
        void testToolProgressIsExcludedFromCompletedMessage() throws A2AError {
            AgentExecuteProperties properties =
                    AgentExecuteProperties.builder()
                            .requireInnerMessage(true)
                            .completeWithMessage(true)
                            .build();
            executor = new AgentScopeAgentExecutor(mockAgentRunner, properties);
            doMockForContext(true, false, false);
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(
                            Flux.just(
                                    new ToolProgressEvent(
                                            "reply-id",
                                            "tool-call-id",
                                            "search",
                                            TextBlock.builder().text("scanning 3 dirs").build()),
                                    new ToolResultTextDeltaEvent(
                                            "reply-id", "tool-call-id", "search", "3 files found"),
                                    new AgentResultEvent(
                                            Msg.builder().textContent("3 files found").build())));
            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();

            executor.execute(mockContext, emitter());

            // The artifact carries progress for live rendering, but the completed message
            // must contain only the tool result.
            List<Artifact> artifacts =
                    messageRef.get().stream()
                            .filter(TaskArtifactUpdateEvent.class::isInstance)
                            .map(TaskArtifactUpdateEvent.class::cast)
                            .map(TaskArtifactUpdateEvent::artifact)
                            .toList();
            assertTrue(artifacts.size() >= 1, "progress is still forwarded as an artifact");

            TaskStatusUpdateEvent completed =
                    messageRef.get().stream()
                            .filter(TaskStatusUpdateEvent.class::isInstance)
                            .map(TaskStatusUpdateEvent.class::cast)
                            .filter(
                                    event ->
                                            TaskState.TASK_STATE_COMPLETED.equals(
                                                    event.status().state()))
                            .findFirst()
                            .orElseThrow();
            DataPart toolPart =
                    assertInstanceOf(DataPart.class, completed.status().message().parts().get(0));
            String messageText =
                    String.valueOf(
                            ((Map<?, ?>) toolPart.data())
                                    .get(MessageConstants.TOOL_RESULT_OUTPUT_METADATA_KEY));
            assertTrue(
                    messageText.contains("3 files found"),
                    "the completed message carries the tool result: " + messageText);
            assertFalse(
                    messageText.contains("scanning 3 dirs"),
                    "progress must not be merged into the completed message: " + messageText);
        }

        @Test
        @DisplayName(
                "Should execute agent and process streaming request with inner event but disabled")
        void testExecuteAgentWithStreamingRequestDisabledInnerEvent() throws A2AError {
            doMockForContext(true, false, false);
            Flux<AgentEvent> mockFlux = mockFlux(true, true, false);
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);
            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();
            executor.execute(mockContext, emitter());

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
        void testExecuteAgentWithStreamingRequestCompletedMessage() throws A2AError {
            AgentExecuteProperties agentExecuteProperties =
                    AgentExecuteProperties.builder().completeWithMessage(true).build();
            executor = new AgentScopeAgentExecutor(mockAgentRunner, agentExecuteProperties);
            doMockForContext(true, false, false);
            Flux<AgentEvent> mockFlux = mockFlux(false, true, false);
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);
            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();
            executor.execute(mockContext, emitter());

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
        void testExecuteAgentWithStreamingRequestFailure() throws A2AError {
            doMockForContext(true, false, false);
            Flux<AgentEvent> mockFlux = mockFlux(false, false, true);
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();
            executor.execute(mockContext, emitter());

            assertFalse(messageRef.get().isEmpty());
            assertTrue(
                    messageRef.get().stream()
                            .filter(event -> event instanceof TaskStatusUpdateEvent)
                            .map(event -> (TaskStatusUpdateEvent) event)
                            .anyMatch(
                                    event ->
                                            TaskState.TASK_STATE_FAILED.equals(
                                                    event.status().state())));
        }

        @Test
        @DisplayName("Should ignore lifecycle events for streaming artifacts")
        void testExecuteAgentWithStreamingRequestIgnoresLifecycleEvents() throws A2AError {
            doMockForContext(true, false, false);
            Flux<AgentEvent> mockFlux =
                    Flux.just(
                            new AgentStartEvent("session-id", "reply-id", "agent"),
                            new TextBlockDeltaEvent("reply-id", "block-id", "streaming result"),
                            new AgentResultEvent(
                                    Msg.builder().textContent("streaming result").build()),
                            new AgentEndEvent("reply-id"));
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();
            executor.execute(mockContext, emitter());

            List<TaskArtifactUpdateEvent> artifactEvents =
                    messageRef.get().stream()
                            .filter(TaskArtifactUpdateEvent.class::isInstance)
                            .map(TaskArtifactUpdateEvent.class::cast)
                            .toList();
            assertEquals(1, artifactEvents.size());
            assertInstanceOf(TextPart.class, artifactEvents.get(0).artifact().parts().get(0));
            assertEquals(
                    "streaming result",
                    ((TextPart) artifactEvents.get(0).artifact().parts().get(0)).text());
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
            assertEquals(taskId, ((Task) streamingEventKinds.get(0)).id());
            assertEquals(contextId, ((Task) streamingEventKinds.get(0)).contextId());
            assertEquals(
                    TaskState.TASK_STATE_SUBMITTED,
                    ((Task) streamingEventKinds.get(0)).status().state());
            assertInstanceOf(TaskStatusUpdateEvent.class, streamingEventKinds.get(1));
            assertEquals(taskId, ((TaskStatusUpdateEvent) streamingEventKinds.get(1)).taskId());
            assertEquals(
                    TaskState.TASK_STATE_WORKING,
                    ((TaskStatusUpdateEvent) streamingEventKinds.get(1)).status().state());
            assertEquals(
                    contextId, ((TaskStatusUpdateEvent) streamingEventKinds.get(1)).contextId());
            if (withToolResult) {
                assertInstanceOf(TaskArtifactUpdateEvent.class, streamingEventKinds.get(2));
                TaskArtifactUpdateEvent artifactUpdateEvent =
                        (TaskArtifactUpdateEvent) streamingEventKinds.get(2);
                assertEquals(taskId, artifactUpdateEvent.taskId());
                assertEquals(contextId, artifactUpdateEvent.contextId());
                assertInstanceOf(DataPart.class, artifactUpdateEvent.artifact().parts().get(0));
            }
            List<StreamingEventKind> subEvent =
                    streamingEventKinds.subList(
                            withToolResult ? 3 : 2, streamingEventKinds.size() - 1);
            for (int i = 0; i < expectedBlocks.size(); i++) {
                assertInstanceOf(TaskArtifactUpdateEvent.class, subEvent.get(i));
                TaskArtifactUpdateEvent artifactUpdateEvent =
                        (TaskArtifactUpdateEvent) subEvent.get(i);
                assertEquals(taskId, artifactUpdateEvent.taskId());
                assertEquals(contextId, artifactUpdateEvent.contextId());
                Artifact artifact = artifactUpdateEvent.artifact();
                assertEquals(1, artifact.parts().size());
                assertInstanceOf(TextPart.class, artifact.parts().get(0));
                assertEquals(expectedBlocks.get(i), ((TextPart) artifact.parts().get(0)).text());
            }
            StreamingEventKind completedEvent =
                    streamingEventKinds.get(streamingEventKinds.size() - 1);
            assertInstanceOf(TaskStatusUpdateEvent.class, completedEvent);
            assertEquals(taskId, ((TaskStatusUpdateEvent) completedEvent).taskId());
            assertEquals(
                    TaskState.TASK_STATE_COMPLETED,
                    ((TaskStatusUpdateEvent) completedEvent).status().state());
            assertEquals(contextId, ((TaskStatusUpdateEvent) completedEvent).contextId());
            if (completeWithMessage) {
                assertNotNull(((TaskStatusUpdateEvent) completedEvent).status().message());
            } else {
                assertNull(((TaskStatusUpdateEvent) completedEvent).status().message());
            }
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

            AtomicBoolean isCancelled = new AtomicBoolean(false);
            Flux<AgentEvent> mockFlux =
                    Flux.fromIterable(
                                    List.of(
                                            new TextBlockDeltaEvent("reply-id", "block-id", "test"),
                                            new AgentResultEvent(
                                                    Msg.builder().textContent("test").build())))
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
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            Thread taskThread = new Thread(() -> executor.execute(mockContext, emitter()));
            try {
                taskThread.start();

                TimeUnit.MILLISECONDS.sleep(500);

                // When
                executor.cancel(mockContext, emitter());

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

            // When
            executor.cancel(mockContext, emitter());

            // Then
            verify(mockAgentRunner).stop(taskId);
        }

        @Test
        @DisplayName("Should handle exception during task cancellation")
        void testHandleExceptionDuringTaskCancellation() throws A2AError {
            // Given
            String taskId = doMockForContext(true, false, false);

            when(mockContext.getTaskId()).thenReturn(taskId);
            doThrow(new RuntimeException("Cancellation error")).when(mockAgentRunner).stop(taskId);

            // When
            executor.cancel(mockContext, emitter());

            // Then
            verify(mockAgentRunner).stop(taskId);
        }
    }

    @Test
    @DisplayName("Should handle exception during execution")
    void testHandleExceptionDuringExecution() throws A2AError {
        doMockForContext(true, false, false);
        // Given
        when(mockContext.getTask()).thenThrow(new RuntimeException("Context error"));
        when(mockContext.getTaskId()).thenReturn("mock Task Id");

        // When
        executor.execute(mockContext, emitter());

        // Then
        verify(mockEventQueue).enqueueEvent(any(Message.class));
    }

    private Flux<AgentEvent> mockFlux(
            boolean withToolResult, boolean withResultEvent, boolean withError) {
        List<AgentEvent> mockEvents = new LinkedList<>();
        if (withError) {
            return Flux.error(new RuntimeException("mock test"));
        }
        String resultMsgId = UUID.randomUUID().toString();
        if (withToolResult) {
            mockEvents.add(
                    new ToolResultTextDeltaEvent(
                            resultMsgId, "tool-call-id", "mock-tool", "mock tool result"));
        }
        mockEvents.add(new TextBlockDeltaEvent(resultMsgId, "block-id", "streaming result 1"));
        mockEvents.add(new TextBlockDeltaEvent(resultMsgId, "block-id", " 2"));
        if (withResultEvent) {
            mockEvents.add(
                    new AgentResultEvent(
                            Msg.builder().textContent("streaming result 1 2").build()));
        }
        return Flux.fromIterable(mockEvents).delayElements(Duration.ofMillis(10));
    }
}
