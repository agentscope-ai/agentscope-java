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
import static org.mockito.Mockito.never;
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
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.spec.TextPart;
import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.a2a.server.constants.A2aServerConstants;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionRule;
import java.time.Duration;
import java.time.OffsetDateTime;
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
            executor.execute(mockContext, mockEventQueue);

            assertNotNull(messageRef.get());
            assertBlockResultMessage(
                    messageRef.get(),
                    List.of("streaming result 1 2"),
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
            executor.execute(mockContext, mockEventQueue);

            assertNotNull(messageRef.get());
            Message message = messageRef.get();
            assertEquals(mockContext.getTaskId(), message.getTaskId());
            assertEquals(mockContext.getContextId(), message.getContextId());
            assertEquals(2, message.getParts().size());
            assertInstanceOf(DataPart.class, message.getParts().get(0));
            assertInstanceOf(TextPart.class, message.getParts().get(1));
            assertEquals("streaming result 1 2", ((TextPart) message.getParts().get(1)).getText());
        }

        @Test
        @DisplayName("Should preserve hint semantics in blocking fallback")
        void testExecuteAgentWithBlockingRequestPreservesHint() throws JSONRPCError {
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
            executor.execute(mockContext, mockEventQueue);

            Message message = messageRef.get();
            assertNotNull(message);
            assertEquals(2, message.getParts().size());
            TextPart hintPart = assertInstanceOf(TextPart.class, message.getParts().get(0));
            assertEquals("Check the inbox", hintPart.getText());
            assertEquals(
                    MessageConstants.BlockContent.TYPE_HINT,
                    hintPart.getMetadata().get(MessageConstants.BLOCK_TYPE_METADATA_KEY));
            assertEquals(
                    "hint-block-id",
                    hintPart.getMetadata().get(MessageConstants.HINT_ID_METADATA_KEY));
            assertEquals(
                    "alice", hintPart.getMetadata().get(MessageConstants.HINT_SOURCE_METADATA_KEY));
            TextPart answerPart = assertInstanceOf(TextPart.class, message.getParts().get(1));
            assertEquals("Final answer", answerPart.getText());
        }

        @Test
        @DisplayName(
                "Should execute agent and process blocking request with inner event but disabled")
        void testExecuteAgentWithBlockingRequestDisabledInnerEvent() throws JSONRPCError {
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
            executor.execute(mockContext, mockEventQueue);

            assertNotNull(messageRef.get());
            assertBlockResultMessage(
                    messageRef.get(),
                    List.of("streaming result 1 2"),
                    mockContext.getTaskId(),
                    mockContext.getContextId());
        }

        @Test
        @DisplayName("Should execute error agent and process blocking request")
        void testExecuteAgentWithError() throws JSONRPCError {
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
            executor.execute(mockContext, mockEventQueue);

            assertNotNull(messageRef.get());
            assertBlockResultMessage(
                    messageRef.get(),
                    List.of("Agent execution failed: mock test"),
                    mockContext.getTaskId(),
                    mockContext.getContextId());
        }

        @Test
        @DisplayName("Should return an input-required task for a blocking confirmation request")
        void testBlockingRequestRequiresUserConfirmation() throws JSONRPCError {
            doMockForContext(false, false, true);
            ToolUseBlock toolCall =
                    ToolUseBlock.builder()
                            .id("tool-call-1")
                            .name("delete_file")
                            .input(Map.of("path", "report.txt"))
                            .build();
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(
                            Flux.just(new RequireUserConfirmEvent("reply-1", List.of(toolCall))));

            AtomicReference<List<StreamingEventKind>> events =
                    new AtomicReference<>(new LinkedList<>());
            doAnswer(
                            (Answer<Void>)
                                    invocation -> {
                                        events.get().add(invocation.getArgument(0));
                                        return null;
                                    })
                    .when(mockEventQueue)
                    .enqueueEvent(any(StreamingEventKind.class));

            executor.execute(mockContext, mockEventQueue);

            List<TaskStatusUpdateEvent> statuses =
                    events.get().stream()
                            .filter(TaskStatusUpdateEvent.class::isInstance)
                            .map(TaskStatusUpdateEvent.class::cast)
                            .toList();
            assertEquals(2, statuses.size());
            assertEquals(TaskState.WORKING, statuses.get(0).getStatus().state());
            TaskStatusUpdateEvent inputRequired = statuses.get(1);
            assertEquals(TaskState.INPUT_REQUIRED, inputRequired.getStatus().state());
            assertFalse(inputRequired.isFinal());
            DataPart payload =
                    inputRequired.getStatus().message().getParts().stream()
                            .filter(DataPart.class::isInstance)
                            .map(DataPart.class::cast)
                            .findFirst()
                            .orElseThrow();
            assertEquals(
                    AgentScopeAgentExecutor.CONFIRMATION_REQUEST_TYPE,
                    payload.getData().get("type"));
            assertEquals("reply-1", payload.getData().get("replyId"));
            assertFalse(
                    statuses.stream()
                            .anyMatch(
                                    status ->
                                            TaskState.COMPLETED.equals(
                                                    status.getStatus().state())));
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
            Flux<AgentEvent> mockFlux = mockFlux(false, true, false);
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
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
        @DisplayName("Should pause an A2A task for user confirmation")
        void testStreamingRequestRequiresUserConfirmation() throws JSONRPCError {
            doMockForContext(true, false, false);
            ToolUseBlock toolCall =
                    ToolUseBlock.builder()
                            .id("tool-call-1")
                            .name("delete_file")
                            .input(Map.of("path", "report.txt"))
                            .metadata(Map.of("provider", "value"))
                            .build();
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(
                            Flux.just(new RequireUserConfirmEvent("reply-1", List.of(toolCall))));
            AtomicReference<List<StreamingEventKind>> events = mockStreamingEventQueueRef();

            executor.execute(mockContext, mockEventQueue);

            List<TaskStatusUpdateEvent> statuses =
                    events.get().stream()
                            .filter(TaskStatusUpdateEvent.class::isInstance)
                            .map(TaskStatusUpdateEvent.class::cast)
                            .toList();
            assertEquals(2, statuses.size());
            TaskStatusUpdateEvent inputRequired = statuses.get(1);
            assertEquals(TaskState.INPUT_REQUIRED, inputRequired.getStatus().state());
            assertFalse(inputRequired.isFinal());
            DataPart payload =
                    inputRequired.getStatus().message().getParts().stream()
                            .filter(DataPart.class::isInstance)
                            .map(DataPart.class::cast)
                            .findFirst()
                            .orElseThrow();
            assertEquals("reply-1", payload.getData().get("replyId"));
            Object rawRequestedCalls = payload.getData().get("toolCalls");
            assertTrue(rawRequestedCalls instanceof List<?>);
            List<?> requestedCalls = (List<?>) rawRequestedCalls;
            assertTrue(requestedCalls.get(0) instanceof Map<?, ?>);
            Map<?, ?> requestedCall = (Map<?, ?>) requestedCalls.get(0);
            assertEquals("tool-call-1", requestedCall.get("id"));
            assertEquals("delete_file", requestedCall.get("name"));
            assertEquals(Map.of("path", "report.txt"), requestedCall.get("input"));
            assertEquals(Map.of("provider", "value"), requestedCall.get("metadata"));
            assertFalse(
                    statuses.stream()
                            .anyMatch(
                                    status ->
                                            TaskState.COMPLETED.equals(
                                                    status.getStatus().state())));
        }

        @Test
        @DisplayName("Should convert a confirmation response into validated AgentScope metadata")
        void testStreamingRequestConvertsConfirmationResponse() throws JSONRPCError {
            String taskId = doMockForContext(false, false, true);
            String contextId = mockContext.getContextId();
            String replyId = "reply-1";
            Map<String, Object> requestedToolCall =
                    Map.of(
                            "id",
                            "tool-call-1",
                            "name",
                            "delete_file",
                            "input",
                            Map.of("path", "report.txt"),
                            "metadata",
                            Map.of("provider", "value"));
            Message pendingMessage =
                    new Message.Builder()
                            .role(Message.Role.AGENT)
                            .parts(
                                    new DataPart(
                                            Map.of(
                                                    "type",
                                                    AgentScopeAgentExecutor
                                                            .CONFIRMATION_REQUEST_TYPE,
                                                    "replyId",
                                                    replyId,
                                                    "toolCalls",
                                                    List.of(
                                                            requestedToolCall,
                                                            Map.of(
                                                                    "id",
                                                                    "tool-call-2",
                                                                    "name",
                                                                    "run_shell",
                                                                    "input",
                                                                    Map.of(
                                                                            "command",
                                                                            "rm -rf /tmp/x"))))))
                            .taskId(taskId)
                            .contextId(contextId)
                            .build();
            Task task =
                    new Task(
                            taskId,
                            contextId,
                            new TaskStatus(
                                    TaskState.INPUT_REQUIRED, pendingMessage, OffsetDateTime.now()),
                            null,
                            List.of(),
                            null);
            Message request =
                    new Message.Builder()
                            .role(Message.Role.USER)
                            .parts(
                                    new TextPart("Proceed"),
                                    new DataPart(
                                            Map.of(
                                                    "type",
                                                    AgentScopeAgentExecutor
                                                            .CONFIRMATION_RESPONSE_TYPE,
                                                    "replyId",
                                                    replyId,
                                                    "results",
                                                    List.of(
                                                            Map.of(
                                                                    "toolCallId",
                                                                    "tool-call-1",
                                                                    "confirmed",
                                                                    true,
                                                                    "toolCall",
                                                                    Map.of(
                                                                            "id",
                                                                            "tool-call-1",
                                                                            "name",
                                                                            "delete_file",
                                                                            "input",
                                                                            Map.of(
                                                                                    "path",
                                                                                    "approved.txt")),
                                                                    "rules",
                                                                    List.of(
                                                                            Map.of(
                                                                                    "tool_name",
                                                                                    "delete_file",
                                                                                    "rule_content",
                                                                                    "report.txt",
                                                                                    "behavior",
                                                                                    "allow",
                                                                                    "source",
                                                                                    "user"))),
                                                            Map.of(
                                                                    "toolCallId",
                                                                    "tool-call-2",
                                                                    "confirmed",
                                                                    false,
                                                                    "reason",
                                                                    "The path is outside the"
                                                                            + " approved"
                                                                            + " directory.")))))
                            .taskId(taskId)
                            .contextId(contextId)
                            .build();
            when(mockContext.getTask()).thenReturn(task);
            when(mockContext.getMessage()).thenReturn(request);
            when(mockContext.getParams().message()).thenReturn(request);
            AtomicReference<List<Msg>> agentInput = new AtomicReference<>();
            doAnswer(
                            (Answer<Flux<AgentEvent>>)
                                    invocation -> {
                                        agentInput.set(invocation.getArgument(0));
                                        return Flux.empty();
                                    })
                    .when(mockAgentRunner)
                    .streamEvents(anyList(), any(AgentRequestOptions.class));
            mockStreamingEventQueueRef();

            executor.execute(mockContext, mockEventQueue);

            assertEquals(1, agentInput.get().size());
            Msg confirmationMessage = agentInput.get().get(0);
            assertEquals(MsgRole.USER, confirmationMessage.getRole());
            assertEquals(1, confirmationMessage.getContent().size());
            assertEquals(
                    "Proceed", ((TextBlock) confirmationMessage.getContent().get(0)).getText());
            Object rawResults = confirmationMessage.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
            assertTrue(rawResults instanceof List<?>);
            List<?> results = (List<?>) rawResults;
            assertEquals(2, results.size());
            ConfirmResult confirmedResult = assertInstanceOf(ConfirmResult.class, results.get(0));
            ConfirmResult deniedResult = assertInstanceOf(ConfirmResult.class, results.get(1));
            assertTrue(confirmedResult.isConfirmed());
            assertEquals("tool-call-1", confirmedResult.getToolCall().getId());
            assertEquals("delete_file", confirmedResult.getToolCall().getName());
            assertEquals(Map.of("path", "approved.txt"), confirmedResult.getToolCall().getInput());
            assertEquals(Map.of("provider", "value"), confirmedResult.getToolCall().getMetadata());
            assertEquals(
                    List.of(
                            new PermissionRule(
                                    "delete_file", "report.txt", PermissionBehavior.ALLOW, "user")),
                    confirmedResult.getRules());
            assertFalse(deniedResult.isConfirmed());
            assertEquals("tool-call-2", deniedResult.getToolCall().getId());
            assertEquals("The path is outside the approved directory.", deniedResult.getReason());
        }

        @Test
        @DisplayName("Should reject confirmation replies that change the requested tool")
        void testStreamingRequestRejectsChangedToolIdentity() throws JSONRPCError {
            String taskId = doMockForContext(true, false, false);
            String contextId = mockContext.getContextId();
            String replyId = "reply-current";
            Message pendingMessage =
                    new Message.Builder()
                            .role(Message.Role.AGENT)
                            .parts(
                                    new DataPart(
                                            Map.of(
                                                    "type",
                                                    AgentScopeAgentExecutor
                                                            .CONFIRMATION_REQUEST_TYPE,
                                                    "replyId",
                                                    replyId,
                                                    "toolCalls",
                                                    List.of(
                                                            Map.of(
                                                                    "id",
                                                                    "tool-call-1",
                                                                    "name",
                                                                    "delete_file",
                                                                    "input",
                                                                    Map.of(
                                                                            "path",
                                                                            "report.txt"))))))
                            .build();
            Task task =
                    new Task(
                            taskId,
                            contextId,
                            new TaskStatus(
                                    TaskState.INPUT_REQUIRED, pendingMessage, OffsetDateTime.now()),
                            null,
                            List.of(),
                            null);
            Message request =
                    new Message.Builder()
                            .role(Message.Role.USER)
                            .parts(
                                    new DataPart(
                                            Map.of(
                                                    "type",
                                                    AgentScopeAgentExecutor
                                                            .CONFIRMATION_RESPONSE_TYPE,
                                                    "replyId",
                                                    replyId,
                                                    "results",
                                                    List.of(
                                                            Map.of(
                                                                    "toolCallId",
                                                                    "tool-call-1",
                                                                    "confirmed",
                                                                    true,
                                                                    "toolCall",
                                                                    Map.of(
                                                                            "id",
                                                                            "tool-call-1",
                                                                            "name",
                                                                            "run_shell",
                                                                            "input",
                                                                            Map.of(
                                                                                    "command",
                                                                                    "rm -rf"
                                                                                        + " /tmp/x")))))))
                            .taskId(taskId)
                            .contextId(contextId)
                            .build();
            when(mockContext.getTask()).thenReturn(task);
            when(mockContext.getMessage()).thenReturn(request);
            when(mockContext.getParams().message()).thenReturn(request);

            executor.execute(mockContext, mockEventQueue);

            verify(mockAgentRunner, never())
                    .streamEvents(anyList(), any(AgentRequestOptions.class));
        }

        @Test
        @DisplayName("Should reject stale confirmation replies before invoking the runner")
        void testStreamingRequestRejectsStaleConfirmationReply() throws JSONRPCError {
            String taskId = doMockForContext(true, false, false);
            String contextId = mockContext.getContextId();
            Message pendingMessage =
                    new Message.Builder()
                            .role(Message.Role.AGENT)
                            .parts(
                                    new DataPart(
                                            Map.of(
                                                    "type",
                                                    AgentScopeAgentExecutor
                                                            .CONFIRMATION_REQUEST_TYPE,
                                                    "replyId",
                                                    "reply-current",
                                                    "toolCalls",
                                                    List.of(
                                                            Map.of(
                                                                    "id",
                                                                    "tool-call-1",
                                                                    "name",
                                                                    "delete_file",
                                                                    "input",
                                                                    Map.of(
                                                                            "path",
                                                                            "report.txt"))))))
                            .build();
            Task task =
                    new Task(
                            taskId,
                            contextId,
                            new TaskStatus(
                                    TaskState.INPUT_REQUIRED, pendingMessage, OffsetDateTime.now()),
                            null,
                            List.of(),
                            null);
            Message staleRequest =
                    new Message.Builder()
                            .role(Message.Role.USER)
                            .parts(
                                    new DataPart(
                                            Map.of(
                                                    "type",
                                                    AgentScopeAgentExecutor
                                                            .CONFIRMATION_RESPONSE_TYPE,
                                                    "replyId",
                                                    "reply-stale",
                                                    "results",
                                                    List.of(
                                                            Map.of(
                                                                    "toolCallId",
                                                                    "tool-call-1",
                                                                    "confirmed",
                                                                    true)))))
                            .taskId(taskId)
                            .contextId(contextId)
                            .build();
            when(mockContext.getTask()).thenReturn(task);
            when(mockContext.getMessage()).thenReturn(staleRequest);
            when(mockContext.getParams().message()).thenReturn(staleRequest);

            executor.execute(mockContext, mockEventQueue);

            verify(mockAgentRunner, never())
                    .streamEvents(anyList(), any(AgentRequestOptions.class));
        }

        @Test
        @DisplayName("Should execute agent and process streaming request with inner event")
        void testExecuteAgentWithStreamingRequestWithInnerEvent() throws JSONRPCError {
            AgentExecuteProperties agentExecuteProperties =
                    AgentExecuteProperties.builder().requireInnerMessage(true).build();
            executor = new AgentScopeAgentExecutor(mockAgentRunner, agentExecuteProperties);
            doMockForContext(true, false, false);
            Flux<AgentEvent> mockFlux = mockFlux(true, true, false);
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
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
        @DisplayName("Should preserve hint and tool metadata in streaming artifacts")
        void testExecuteAgentWithStreamingRequestPreservesEventMetadata() throws JSONRPCError {
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

            executor.execute(mockContext, mockEventQueue);

            List<Artifact> artifacts =
                    messageRef.get().stream()
                            .filter(TaskArtifactUpdateEvent.class::isInstance)
                            .map(TaskArtifactUpdateEvent.class::cast)
                            .map(TaskArtifactUpdateEvent::getArtifact)
                            .toList();
            assertEquals(2, artifacts.size());

            Artifact hintArtifact = artifacts.get(0);
            assertEquals(hintMetadata, hintArtifact.metadata());
            TextPart hintPart = assertInstanceOf(TextPart.class, hintArtifact.parts().get(0));
            assertEquals(
                    MessageConstants.BlockContent.TYPE_HINT,
                    hintPart.getMetadata().get(MessageConstants.BLOCK_TYPE_METADATA_KEY));
            assertEquals(
                    "hint-block-id",
                    hintPart.getMetadata().get(MessageConstants.HINT_ID_METADATA_KEY));
            assertEquals(
                    "alice", hintPart.getMetadata().get(MessageConstants.HINT_SOURCE_METADATA_KEY));
            assertFalse(
                    hintPart.getMetadata().containsKey(MessageConstants.STREAM_CHUNK_METADATA_KEY));

            Artifact toolArtifact = artifacts.get(1);
            assertEquals(toolMetadata, toolArtifact.metadata());
            DataPart toolPart = assertInstanceOf(DataPart.class, toolArtifact.parts().get(0));
            assertEquals(
                    Boolean.TRUE,
                    toolPart.getMetadata().get(MessageConstants.STREAM_CHUNK_METADATA_KEY));
            assertEquals("trace-1", toolPart.getMetadata().get("traceId"));
        }

        @Test
        @DisplayName(
                "Should execute agent and process streaming request with inner event but disabled")
        void testExecuteAgentWithStreamingRequestDisabledInnerEvent() throws JSONRPCError {
            doMockForContext(true, false, false);
            Flux<AgentEvent> mockFlux = mockFlux(true, true, false);
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
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
            Flux<AgentEvent> mockFlux = mockFlux(false, true, false);
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
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
            Flux<AgentEvent> mockFlux = mockFlux(false, false, true);
            when(mockAgentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
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

        @Test
        @DisplayName("Should ignore lifecycle events for streaming artifacts")
        void testExecuteAgentWithStreamingRequestIgnoresLifecycleEvents() throws JSONRPCError {
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
            executor.execute(mockContext, mockEventQueue);

            List<TaskArtifactUpdateEvent> artifactEvents =
                    messageRef.get().stream()
                            .filter(TaskArtifactUpdateEvent.class::isInstance)
                            .map(TaskArtifactUpdateEvent.class::cast)
                            .toList();
            assertEquals(1, artifactEvents.size());
            assertInstanceOf(TextPart.class, artifactEvents.get(0).getArtifact().parts().get(0));
            assertEquals(
                    "streaming result",
                    ((TextPart) artifactEvents.get(0).getArtifact().parts().get(0)).getText());
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
