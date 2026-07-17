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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.a2a.server.constants.A2aServerConstants;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.grpc.mapper.TaskArtifactUpdateEventMapper;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.server.auth.User;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendConfiguration;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys;
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
    private AgentEmitter mockEmitter;
    private ServerCallContext serverCallContext;

    @BeforeEach
    void setUp() {
        AgentExecuteProperties agentExecuteProperties = AgentExecuteProperties.builder().build();
        mockAgentRunner = mock(AgentRunner.class);
        executor = new AgentScopeAgentExecutor(mockAgentRunner, agentExecuteProperties);
        mockContext = mock(RequestContext.class);
        mockEmitter = mock(AgentEmitter.class);
        serverCallContext = mock(ServerCallContext.class);
    }

    private String doMockForContext(
            boolean isStreaming, boolean blockingByState, boolean blockingByConfig) {
        String taskId = UUID.randomUUID().toString();
        String contextId = UUID.randomUUID().toString();

        when(mockContext.getTaskId()).thenReturn(taskId);
        when(mockContext.getContextId()).thenReturn(contextId);
        when(mockEmitter.getTaskId()).thenReturn(taskId);
        when(mockEmitter.getContextId()).thenReturn(contextId);

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

    @Nested
    @DisplayName("Execute For Blocking Request")
    class ExecuteForBlockingRequestTests {
        @Test
        @DisplayName("Should use message context ID as session ID before metadata")
        void testContextIdTakesPrecedenceForSessionId() throws A2AError {
            doMockForContext(false, true, false);
            Message message = mockContext.getMessage();
            when(message.metadata())
                    .thenReturn(
                            Map.of(
                                    "sessionId", "metadata-session",
                                    "threadId", "metadata-thread",
                                    "userId", "test-user"));

            AtomicReference<AgentRequestOptions> optionsRef = new AtomicReference<>();
            doAnswer(
                            invocationOnMock -> {
                                optionsRef.set(invocationOnMock.getArgument(1));
                                return Flux.empty();
                            })
                    .when(mockAgentRunner)
                    .streamEvents(anyList(), any(AgentRequestOptions.class));

            executor.execute(mockContext, mockEmitter);

            assertNotNull(optionsRef.get());
            assertEquals(mockContext.getContextId(), optionsRef.get().getSessionId());
            assertEquals("test-user", optionsRef.get().getUserId());
        }

        @Test
        @DisplayName("Should use explicit metadata session before generated context ID")
        void testMetadataSessionPrecedesGeneratedContextId() throws A2AError {
            doMockForContext(false, true, false);
            Message message = mockContext.getMessage();
            when(message.contextId()).thenReturn(null);
            when(message.metadata())
                    .thenReturn(
                            Map.of(
                                    "sessionId", "metadata-session",
                                    "threadId", "metadata-thread"));

            AtomicReference<AgentRequestOptions> optionsRef = new AtomicReference<>();
            doAnswer(
                            invocationOnMock -> {
                                optionsRef.set(invocationOnMock.getArgument(1));
                                return Flux.empty();
                            })
                    .when(mockAgentRunner)
                    .streamEvents(anyList(), any(AgentRequestOptions.class));

            executor.execute(mockContext, mockEmitter);

            assertNotNull(optionsRef.get());
            assertEquals("metadata-session", optionsRef.get().getSessionId());
        }

        @Test
        @DisplayName("Should use SDK call-context user before metadata user")
        void testSdkCallContextUserPrecedence() throws A2AError {
            doMockForContext(false, true, false);
            User user = mock(User.class);
            when(user.isAuthenticated()).thenReturn(true);
            when(user.getUsername()).thenReturn("sdk-user");
            when(serverCallContext.getUser()).thenReturn(user);
            Message message = mockContext.getMessage();
            when(message.metadata()).thenReturn(Map.of("userId", "metadata-user"));

            AtomicReference<AgentRequestOptions> optionsRef = new AtomicReference<>();
            doAnswer(
                            invocationOnMock -> {
                                optionsRef.set(invocationOnMock.getArgument(1));
                                return Flux.empty();
                            })
                    .when(mockAgentRunner)
                    .streamEvents(anyList(), any(AgentRequestOptions.class));

            executor.execute(mockContext, mockEmitter);

            assertNotNull(optionsRef.get());
            assertEquals("sdk-user", optionsRef.get().getUserId());
        }

        @Test
        @DisplayName("Should keep metadata user when authentication is anonymous")
        void testAnonymousAuthenticationDoesNotOverrideMetadataUser() throws A2AError {
            doMockForContext(false, true, false);
            when(serverCallContext.getUser()).thenReturn(UnauthenticatedUser.INSTANCE);
            Message message = mockContext.getMessage();
            when(message.metadata()).thenReturn(Map.of("userId", "alice"));

            AtomicReference<AgentRequestOptions> optionsRef = new AtomicReference<>();
            doAnswer(
                            invocationOnMock -> {
                                optionsRef.set(invocationOnMock.getArgument(1));
                                return Flux.empty();
                            })
                    .when(mockAgentRunner)
                    .streamEvents(anyList(), any(AgentRequestOptions.class));

            executor.execute(mockContext, mockEmitter);

            assertNotNull(optionsRef.get());
            assertEquals("alice", optionsRef.get().getUserId());
        }

        @Test
        @DisplayName("Should pass request headers to runner")
        void testHeaders() throws A2AError {
            doMockForContext(false, false, false);
            when(serverCallContext.getUser()).thenReturn(UnauthenticatedUser.INSTANCE);
            when(serverCallContext.getState())
                    .thenReturn(
                            Map.of(
                                    A2aServerConstants.ContextKeys.IS_STREAM_KEY,
                                    Boolean.FALSE,
                                    JSONRPCContextKeys.HEADERS_KEY,
                                    Map.of("Authorization", "Bearer token")));

            AtomicReference<AgentRequestOptions> optionsRef = new AtomicReference<>();
            doAnswer(
                            invocationOnMock -> {
                                optionsRef.set(invocationOnMock.getArgument(1));
                                return Flux.empty();
                            })
                    .when(mockAgentRunner)
                    .streamEvents(anyList(), any(AgentRequestOptions.class));

            executor.execute(mockContext, mockEmitter);

            AgentRequestOptions options = optionsRef.get();
            assertNotNull(options);
            assertEquals("Bearer token", options.getHeaders().get("Authorization"));
        }

        @Test
        @DisplayName("Should use username metadata as user ID fallback")
        void testUsernameMetadataFallbackForUserId() throws A2AError {
            doMockForContext(false, true, false);
            Message message = mockContext.getMessage();
            when(message.metadata()).thenReturn(Map.of("username", "liz hen06"));

            AtomicReference<AgentRequestOptions> optionsRef = new AtomicReference<>();
            doAnswer(
                            invocationOnMock -> {
                                optionsRef.set(invocationOnMock.getArgument(1));
                                return Flux.empty();
                            })
                    .when(mockAgentRunner)
                    .streamEvents(anyList(), any(AgentRequestOptions.class));

            executor.execute(mockContext, mockEmitter);

            assertNotNull(optionsRef.get());
            assertEquals("liz hen06", optionsRef.get().getUserId());
        }

        @Test
        @DisplayName("Should extract agent ID and merge request metadata")
        void testAgentIdAndMetadataExtraction() throws A2AError {
            doMockForContext(false, true, false);
            Message message = mockContext.getMessage();
            when(mockContext.getMetadata())
                    .thenReturn(
                            Map.of(
                                    "agentId", "context-agent",
                                    "contextOnly", "context-value",
                                    "shared", "context-value"));
            when(message.metadata())
                    .thenReturn(
                            Map.of(
                                    "agent_id", "message-agent",
                                    "messageOnly", "message-value",
                                    "shared", "message-value"));

            AtomicReference<AgentRequestOptions> optionsRef = new AtomicReference<>();
            doAnswer(
                            invocationOnMock -> {
                                optionsRef.set(invocationOnMock.getArgument(1));
                                return Flux.empty();
                            })
                    .when(mockAgentRunner)
                    .streamEvents(anyList(), any(AgentRequestOptions.class));

            executor.execute(mockContext, mockEmitter);

            AgentRequestOptions options = optionsRef.get();
            assertNotNull(options);
            assertEquals("context-agent", options.getAgentId());
            assertEquals("context-value", options.getMetadata().get("contextOnly"));
            assertEquals("message-value", options.getMetadata().get("messageOnly"));
            assertEquals("message-value", options.getMetadata().get("shared"));
        }

        @Test
        @DisplayName("Should use message agentId before context agentId")
        void testMessageAgentIdPrecedence() throws A2AError {
            doMockForContext(false, true, false);
            Message message = mockContext.getMessage();
            when(mockContext.getMetadata()).thenReturn(Map.of("agentId", "context-agent"));
            when(message.metadata()).thenReturn(Map.of("agentId", "message-agent"));

            AtomicReference<AgentRequestOptions> optionsRef = new AtomicReference<>();
            doAnswer(
                            invocationOnMock -> {
                                optionsRef.set(invocationOnMock.getArgument(1));
                                return Flux.empty();
                            })
                    .when(mockAgentRunner)
                    .streamEvents(anyList(), any(AgentRequestOptions.class));

            executor.execute(mockContext, mockEmitter);

            assertNotNull(optionsRef.get());
            assertEquals("message-agent", optionsRef.get().getAgentId());
        }

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
                    .when(mockEmitter)
                    .sendMessage(any(Message.class));
            executor.execute(mockContext, mockEmitter);

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
                    .when(mockEmitter)
                    .sendMessage(any(Message.class));
            executor.execute(mockContext, mockEmitter);

            assertNotNull(messageRef.get());
            assertBlockResultMessage(
                    messageRef.get(),
                    List.of("Agent stream completed without AgentResultEvent or handoff"),
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
            Flux<AgentEvent> mockFlux = mockFlux(true, true, false);
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
                    .when(mockEmitter)
                    .sendMessage(any(Message.class));
            executor.execute(mockContext, mockEmitter);

            assertNotNull(messageRef.get());
            Message message = messageRef.get();
            assertEquals(mockContext.getTaskId(), message.taskId());
            assertEquals(mockContext.getContextId(), message.contextId());
            assertEquals(2, message.parts().size());
            assertInstanceOf(DataPart.class, message.parts().get(0));
        }

        @Test
        @DisplayName(
                "Should execute agent and process blocking request with inner event but disabled")
        void testExecuteAgentWithBlockingRequestDisabledInnerEvent() throws A2AError {
            executor =
                    new AgentScopeAgentExecutor(
                            mockAgentRunner,
                            AgentExecuteProperties.builder().requireInnerMessage(false).build());
            doMockForContext(false, true, false);
            Flux<AgentEvent> mockFlux = mockFlux(true, true, false);
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
                    .when(mockEmitter)
                    .sendMessage(any(Message.class));
            executor.execute(mockContext, mockEmitter);

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
                    .when(mockEmitter)
                    .sendMessage(any(Message.class));
            executor.execute(mockContext, mockEmitter);

            assertNotNull(messageRef.get());
            assertBlockResultMessage(
                    messageRef.get(),
                    List.of("Agent stream failed: mock test"),
                    mockContext.getTaskId(),
                    mockContext.getContextId());
        }

        @Test
        @DisplayName("Should map blocking input-required metadata to A2A requires input")
        void testExecuteAgentWithBlockingInputRequired() throws A2AError {
            doMockForContext(false, true, false);
            Flux<AgentEvent> mockFlux =
                    Flux.just(
                            new AgentResultEvent(
                                    Msg.builder()
                                            .textContent("需要用户补充信息")
                                            .id(UUID.randomUUID().toString())
                                            .metadata(Map.of("taskState", "input-required"))
                                            .build()));
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            executor.execute(mockContext, mockEmitter);

            verify(mockEmitter).requiresInput(any(Message.class), eq(true));
            verify(mockEmitter, never()).sendMessage(any(Message.class));
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
            doMockForContext(true, false, false);
            Flux<AgentEvent> mockFlux = mockFlux(false, true, false);
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();
            executor.execute(mockContext, mockEmitter);

            assertFalse(messageRef.get().isEmpty());
            assertStreamingEventKind(
                    messageRef.get(),
                    List.of("streaming result 1 2", "streaming result 1 2"),
                    mockContext.getTaskId(),
                    mockContext.getContextId(),
                    false,
                    false);
        }

        @Test
        @DisplayName("Should keep old streaming artifact behavior when stream merge disabled")
        void testExecuteAgentWithStreamingRequestStreamMergeDisabled() throws A2AError {
            AgentExecuteProperties agentExecuteProperties =
                    AgentExecuteProperties.builder().streamMergeEnabled(false).build();
            executor = new AgentScopeAgentExecutor(mockAgentRunner, agentExecuteProperties);
            doMockForContext(true, false, false);
            Flux<AgentEvent> mockFlux = mockFlux(false, true, false);
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();
            executor.execute(mockContext, mockEmitter);

            assertFalse(messageRef.get().isEmpty());
            assertStreamingEventKind(
                    messageRef.get(),
                    List.of("streaming result 1", " 2", "streaming result 1 2"),
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
            executor.execute(mockContext, mockEmitter);

            assertFalse(messageRef.get().isEmpty());
            assertStreamingEventKind(
                    messageRef.get(),
                    List.of("streaming result 1 2", "streaming result 1 2"),
                    mockContext.getTaskId(),
                    mockContext.getContextId(),
                    true,
                    false);
        }

        @Test
        @DisplayName(
                "Should execute agent and process streaming request with inner event but disabled")
        void testExecuteAgentWithStreamingRequestDisabledInnerEvent() throws A2AError {
            executor =
                    new AgentScopeAgentExecutor(
                            mockAgentRunner,
                            AgentExecuteProperties.builder().requireInnerMessage(false).build());
            doMockForContext(true, false, false);
            Flux<AgentEvent> mockFlux = mockFlux(true, true, false);
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);
            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();
            executor.execute(mockContext, mockEmitter);

            assertFalse(messageRef.get().isEmpty());
            assertStreamingEventKind(
                    messageRef.get(),
                    List.of("streaming result 1 2", "streaming result 1 2"),
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
            executor.execute(mockContext, mockEmitter);

            assertFalse(messageRef.get().isEmpty());
            assertStreamingEventKind(
                    messageRef.get(),
                    List.of("streaming result 1 2", "streaming result 1 2"),
                    mockContext.getTaskId(),
                    mockContext.getContextId(),
                    false,
                    true);
        }

        @Test
        @DisplayName("Should reduce artifact updates for many same-id reasoning deltas")
        void testStreamingReasoningDeltasAreMergedBeforeArtifactUpdates() throws A2AError {
            doMockForContext(true, false, false);
            String messageId = UUID.randomUUID().toString();
            List<AgentEvent> events = new LinkedList<>();
            for (int i = 0; i < 100; i++) {
                events.add(mockTextEvent("REASONING", "x", messageId, false));
            }
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(Flux.fromIterable(events));

            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();
            executor.execute(mockContext, mockEmitter);

            List<TaskArtifactUpdateEvent> artifactUpdates = artifactUpdates(messageRef.get());
            assertEquals(1, artifactUpdates.size());
            assertTextArtifact(artifactUpdates.get(0), "x".repeat(100), false, false);
        }

        @Test
        @DisplayName("Should mark final reasoning snapshot as last chunk without append")
        void testFinalReasoningSnapshotArtifactFlags() throws A2AError {
            doMockForContext(true, false, false);
            String messageId = UUID.randomUUID().toString();
            Flux<AgentEvent> mockFlux =
                    Flux.just(
                            mockTextEvent("REASONING", "streaming result 1", messageId, false),
                            mockTextEvent("REASONING", "streaming result 1 2", messageId, true));
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();
            executor.execute(mockContext, mockEmitter);

            List<TaskArtifactUpdateEvent> artifactUpdates = artifactUpdates(messageRef.get());
            assertEquals(2, artifactUpdates.size());
            assertTextArtifact(artifactUpdates.get(0), "streaming result 1", false, false);
            assertTextArtifact(artifactUpdates.get(1), "streaming result 1 2", false, true);
            assertEquals(
                    artifactUpdates.get(0).artifact().artifactId(),
                    artifactUpdates.get(1).artifact().artifactId());
        }

        @Test
        @DisplayName("Should mark single final reasoning snapshot as last chunk")
        void testOnlyFinalReasoningSnapshotArtifactFlags() throws A2AError {
            doMockForContext(true, false, false);
            String messageId = UUID.randomUUID().toString();
            Flux<AgentEvent> mockFlux =
                    Flux.just(mockTextEvent("REASONING", "streaming result 1 2", messageId, true));
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();
            executor.execute(mockContext, mockEmitter);

            List<TaskArtifactUpdateEvent> artifactUpdates = artifactUpdates(messageRef.get());
            assertEquals(1, artifactUpdates.size());
            assertTextArtifact(artifactUpdates.get(0), "streaming result 1 2", false, true);
        }

        @Test
        @DisplayName("Should mark final summary snapshot as last chunk without append")
        void testFinalSummarySnapshotArtifactFlags() throws A2AError {
            doMockForContext(true, false, false);
            String messageId = UUID.randomUUID().toString();
            Flux<AgentEvent> mockFlux =
                    Flux.just(
                            mockTextEvent("SUMMARY", "summary chunk", messageId, false),
                            mockTextEvent("SUMMARY", "summary final", messageId, true));
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();
            executor.execute(mockContext, mockEmitter);

            List<TaskArtifactUpdateEvent> artifactUpdates = artifactUpdates(messageRef.get());
            assertEquals(2, artifactUpdates.size());
            assertTextArtifact(artifactUpdates.get(0), "summary chunk", false, false);
            assertTextArtifact(artifactUpdates.get(1), "summary final", false, true);
            assertEquals(
                    artifactUpdates.get(0).artifact().artifactId(),
                    artifactUpdates.get(1).artifact().artifactId());
        }

        @Test
        @DisplayName("Should strip null artifact metadata values before protobuf mapping")
        void testStreamingArtifactMetadataDropsNullValues() throws A2AError {
            doMockForContext(true, false, false);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", "agent");
            metadata.put("nullable", null);
            Map<String, Object> nested = new HashMap<>();
            nested.put("name", "child");
            nested.put("empty", null);
            metadata.put("nested", nested);
            List<Object> values = new LinkedList<>();
            values.add("kept");
            values.add(null);
            metadata.put("values", values);
            metadata.put("state", TaskState.TASK_STATE_WORKING);
            Flux<AgentEvent> mockFlux =
                    Flux.just(
                            mockTextEvent(
                                    "REASONING",
                                    "streaming result",
                                    UUID.randomUUID().toString(),
                                    true,
                                    metadata));
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();
            executor.execute(mockContext, mockEmitter);

            List<TaskArtifactUpdateEvent> artifactUpdates = artifactUpdates(messageRef.get());
            assertEquals(1, artifactUpdates.size());
            Map<String, Object> artifactMetadata = artifactUpdates.get(0).artifact().metadata();
            assertEquals("agent", artifactMetadata.get("source"));
            assertFalse(artifactMetadata.containsKey("nullable"));
            @SuppressWarnings("unchecked")
            Map<String, Object> nestedMetadata =
                    (Map<String, Object>) artifactMetadata.get("nested");
            assertEquals("child", nestedMetadata.get("name"));
            assertFalse(nestedMetadata.containsKey("empty"));
            @SuppressWarnings("unchecked")
            List<Object> listMetadata = (List<Object>) artifactMetadata.get("values");
            assertEquals(List.of("kept"), listMetadata);
            assertEquals("TASK_STATE_WORKING", artifactMetadata.get("state"));
            Map<String, Object> roundTripMetadata =
                    TaskArtifactUpdateEventMapper.INSTANCE
                            .fromProto(
                                    TaskArtifactUpdateEventMapper.INSTANCE.toProto(
                                            artifactUpdates.get(0)))
                            .artifact()
                            .metadata();
            assertEquals("agent", roundTripMetadata.get("source"));
            assertEquals("TASK_STATE_WORKING", roundTripMetadata.get("state"));
        }

        @Test
        @DisplayName("Should execute fail agent and process streaming request")
        void testExecuteAgentWithStreamingRequestFailure() throws A2AError {
            doMockForContext(true, false, false);
            Flux<AgentEvent> mockFlux = mockFlux(false, false, true);
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();
            executor.execute(mockContext, mockEmitter);

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
        @DisplayName("Should not map arbitrary auth metadata to A2A requires auth")
        void testExecuteAgentDoesNotInventStreamingAuthRequired() throws A2AError {
            doMockForContext(true, false, false);
            Flux<AgentEvent> mockFlux =
                    Flux.just(
                            mockTextEvent(
                                    "REASONING",
                                    "需要完成授权",
                                    UUID.randomUUID().toString(),
                                    true,
                                    Map.of("requiresAuth", true)));
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(mockFlux);

            AtomicReference<List<StreamingEventKind>> messageRef = mockStreamingEventQueueRef();
            executor.execute(mockContext, mockEmitter);

            assertTrue(
                    messageRef.get().stream()
                            .filter(TaskStatusUpdateEvent.class::isInstance)
                            .map(TaskStatusUpdateEvent.class::cast)
                            .anyMatch(
                                    event ->
                                            TaskState.TASK_STATE_COMPLETED.equals(
                                                    event.status().state())));
            verify(mockEmitter, never()).requiresAuth(any(Message.class), anyBoolean());
        }

        private AtomicReference<List<StreamingEventKind>> mockStreamingEventQueueRef() {
            AtomicReference<List<StreamingEventKind>> messageRef =
                    new AtomicReference<>(new LinkedList<>());
            doAnswer(
                            (Answer<Void>)
                                    invocationOnMock -> {
                                        messageRef.get().add(invocationOnMock.getArgument(0));
                                        return null;
                                    })
                    .when(mockEmitter)
                    .addTask(any(Task.class));
            doAnswer(
                            (Answer<Void>)
                                    invocationOnMock -> {
                                        messageRef
                                                .get()
                                                .add(
                                                        new TaskStatusUpdateEvent(
                                                                mockEmitter.getTaskId(),
                                                                new TaskStatus(
                                                                        TaskState
                                                                                .TASK_STATE_WORKING),
                                                                mockEmitter.getContextId(),
                                                                Map.of()));
                                        return null;
                                    })
                    .when(mockEmitter)
                    .startWork();
            doAnswer(
                            (Answer<Void>)
                                    invocationOnMock -> {
                                        @SuppressWarnings("unchecked")
                                        List<Part<?>> parts = invocationOnMock.getArgument(0);
                                        Artifact artifact =
                                                Artifact.builder()
                                                        .artifactId(invocationOnMock.getArgument(1))
                                                        .name(invocationOnMock.getArgument(2))
                                                        .metadata(invocationOnMock.getArgument(3))
                                                        .parts(parts)
                                                        .build();
                                        messageRef
                                                .get()
                                                .add(
                                                        TaskArtifactUpdateEvent.builder()
                                                                .taskId(mockEmitter.getTaskId())
                                                                .contextId(
                                                                        mockEmitter.getContextId())
                                                                .artifact(artifact)
                                                                .append(
                                                                        invocationOnMock
                                                                                .getArgument(4))
                                                                .lastChunk(
                                                                        invocationOnMock
                                                                                .getArgument(5))
                                                                .build());
                                        return null;
                                    })
                    .when(mockEmitter)
                    .addArtifact(anyList(), any(), any(), nullable(Map.class), any(), any());
            doAnswer(
                            (Answer<Void>)
                                    invocationOnMock -> {
                                        messageRef
                                                .get()
                                                .add(
                                                        new TaskStatusUpdateEvent(
                                                                mockEmitter.getTaskId(),
                                                                new TaskStatus(
                                                                        TaskState
                                                                                .TASK_STATE_COMPLETED,
                                                                        invocationOnMock
                                                                                .getArgument(0),
                                                                        null),
                                                                mockEmitter.getContextId(),
                                                                Map.of()));
                                        return null;
                                    })
                    .when(mockEmitter)
                    .complete(nullable(Message.class));
            doAnswer(
                            (Answer<Void>)
                                    invocationOnMock -> {
                                        messageRef
                                                .get()
                                                .add(
                                                        new TaskStatusUpdateEvent(
                                                                mockEmitter.getTaskId(),
                                                                new TaskStatus(
                                                                        TaskState
                                                                                .TASK_STATE_COMPLETED,
                                                                        null,
                                                                        null),
                                                                mockEmitter.getContextId(),
                                                                Map.of()));
                                        return null;
                                    })
                    .when(mockEmitter)
                    .complete();
            doAnswer(
                            (Answer<Void>)
                                    invocationOnMock -> {
                                        messageRef
                                                .get()
                                                .add(
                                                        new TaskStatusUpdateEvent(
                                                                mockEmitter.getTaskId(),
                                                                new TaskStatus(
                                                                        TaskState.TASK_STATE_FAILED,
                                                                        invocationOnMock
                                                                                .getArgument(0),
                                                                        null),
                                                                mockEmitter.getContextId(),
                                                                Map.of()));
                                        return null;
                                    })
                    .when(mockEmitter)
                    .fail(any(Message.class));
            doAnswer(
                            (Answer<Void>)
                                    invocationOnMock -> {
                                        messageRef
                                                .get()
                                                .add(
                                                        new TaskStatusUpdateEvent(
                                                                mockEmitter.getTaskId(),
                                                                new TaskStatus(
                                                                        TaskState
                                                                                .TASK_STATE_INPUT_REQUIRED,
                                                                        invocationOnMock
                                                                                .getArgument(0),
                                                                        null),
                                                                mockEmitter.getContextId(),
                                                                Map.of()));
                                        return null;
                                    })
                    .when(mockEmitter)
                    .requiresInput(any(Message.class), eq(true));
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

        private List<TaskArtifactUpdateEvent> artifactUpdates(
                List<StreamingEventKind> streamingEventKinds) {
            List<TaskArtifactUpdateEvent> artifactUpdates = new LinkedList<>();
            for (StreamingEventKind streamingEventKind : streamingEventKinds) {
                if (streamingEventKind instanceof TaskArtifactUpdateEvent) {
                    artifactUpdates.add((TaskArtifactUpdateEvent) streamingEventKind);
                }
            }
            return artifactUpdates;
        }

        private AgentEvent mockTextEvent(
                String eventType, String textContent, String messageId, boolean isLast) {
            return mockTextEvent(eventType, textContent, messageId, isLast, Map.of());
        }

        private AgentEvent mockTextEvent(
                String eventType,
                String textContent,
                String messageId,
                boolean isLast,
                Map<String, Object> metadata) {
            if (isLast) {
                return new AgentResultEvent(
                        Msg.builder()
                                .textContent(textContent)
                                .id(messageId)
                                .metadata(metadata)
                                .build());
            }
            TextBlockDeltaEvent event = new TextBlockDeltaEvent(messageId, eventType, textContent);
            event.withMetadata(metadata);
            return event;
        }

        private void assertTextArtifact(
                TaskArtifactUpdateEvent artifactUpdateEvent,
                String expectedText,
                boolean expectedAppend,
                boolean expectedLastChunk) {
            Artifact artifact = artifactUpdateEvent.artifact();
            assertEquals(1, artifact.parts().size());
            assertInstanceOf(TextPart.class, artifact.parts().get(0));
            assertEquals(expectedText, ((TextPart) artifact.parts().get(0)).text());
            assertEquals(expectedAppend, artifactUpdateEvent.append());
            assertEquals(expectedLastChunk, artifactUpdateEvent.lastChunk());
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
                                            new TextBlockDeltaEvent("reply-1", "block-1", "test"),
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

            Thread taskThread = new Thread(() -> executor.execute(mockContext, mockEmitter));
            try {
                taskThread.start();

                TimeUnit.MILLISECONDS.sleep(500);

                // When
                executor.cancel(mockContext, mockEmitter);

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
            executor.cancel(mockContext, mockEmitter);

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
            executor.cancel(mockContext, mockEmitter);

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
        executor.execute(mockContext, mockEmitter);

        // Then
        verify(mockEmitter).fail(any(Message.class));
    }

    private Flux<AgentEvent> mockFlux(
            boolean withToolResult, boolean withResultEvent, boolean withError) {
        List<AgentEvent> mockEvents = new LinkedList<>();
        if (withError) {
            return Flux.error(new RuntimeException("mock test"));
        }
        String resultMsgId = UUID.randomUUID().toString();
        if (withToolResult) {
            mockEvents.add(new ToolResultStartEvent("reply-1", "call-1", "mock-tool"));
            mockEvents.add(
                    new ToolResultTextDeltaEvent(
                            "reply-1", "call-1", "mock-tool", "mock tool result"));
            mockEvents.add(
                    new ToolResultEndEvent(
                            "reply-1",
                            "call-1",
                            "mock-tool",
                            io.agentscope.core.message.ToolResultState.SUCCESS));
        }
        mockEvents.add(new TextBlockDeltaEvent(resultMsgId, "text-block", "streaming result 1"));
        mockEvents.add(new TextBlockDeltaEvent(resultMsgId, "text-block", " 2"));
        if (withResultEvent) {
            mockEvents.add(
                    new AgentResultEvent(
                            Msg.builder()
                                    .id(resultMsgId)
                                    .textContent("streaming result 1 2")
                                    .build()));
        }
        return Flux.fromIterable(mockEvents).delayElements(Duration.ofMillis(10));
    }
}
