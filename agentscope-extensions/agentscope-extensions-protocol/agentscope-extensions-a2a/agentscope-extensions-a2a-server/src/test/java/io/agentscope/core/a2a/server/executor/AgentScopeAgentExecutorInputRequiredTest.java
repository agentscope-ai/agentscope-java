/*
 * Copyright 2024-2026 the original author or authors.
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

package io.agentscope.core.a2a.server.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.a2a.server.constants.A2aServerConstants;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.executor.runner.BaseReActAgentRunner;
import io.agentscope.core.a2a.server.request.AgentScopeA2aRequestHandler;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendConfiguration;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A run that pauses for a tool confirmation is reported as {@code input-required} and resumed by
 * a follow-up message on the same task. Every request gets a fresh agent, as the shipped runners
 * build one, so the paused run is only reachable through the agent state store.
 */
@Timeout(60)
@DisplayName("AgentScopeAgentExecutor input-required Tests")
class AgentScopeAgentExecutorInputRequiredTest {

    private static final String CONTEXT_ID = "ctx-input-required";

    private static final String TOOL_CALL_ID = "call-1";

    private static final String TOOL_NAME = "ask";

    private PausingAgentRunner runner;

    private InMemoryTaskStore taskStore;

    private AgentScopeA2aRequestHandler handler;

    @AfterEach
    void tearDown() {
        if (handler != null) {
            handler.close();
        }
    }

    @Test
    @DisplayName("Streaming: a pause for confirmation is reported as input-required")
    void streamingPauseReportsInputRequired() {
        startServer(new InMemoryAgentStateStore());

        TaskStatusUpdateEvent paused = stream(firstMessage(), TaskState.TASK_STATE_INPUT_REQUIRED);

        assertCarriesPendingToolCall(paused.status().message());
        assertEquals(TaskState.TASK_STATE_INPUT_REQUIRED, storedState(paused.taskId()));
        assertEquals(0, runner.tool.executed.get());
    }

    @Test
    @DisplayName("Streaming: an approval resumes the paused run on a fresh agent")
    void streamingApprovalResumesAndCompletes() {
        startServer(new InMemoryAgentStateStore());
        String taskId = stream(firstMessage(), TaskState.TASK_STATE_INPUT_REQUIRED).taskId();

        stream(followUp(taskId, Boolean.TRUE), TaskState.TASK_STATE_COMPLETED);

        assertEquals(1, runner.tool.executed.get());
        assertEquals(2, runner.built.get());
        assertEquals(TaskState.TASK_STATE_COMPLETED, storedState(taskId));
    }

    @Test
    @DisplayName("Streaming: a resumed stream does not start with the paused task snapshot")
    void streamingResumeDoesNotStartInputRequired() {
        startServer(new InMemoryAgentStateStore());
        String taskId = stream(firstMessage(), TaskState.TASK_STATE_INPUT_REQUIRED).taskId();

        Object first =
                JdkFlowAdapter.flowPublisherToFlux(
                                handler.onMessageSendStream(
                                        new MessageSendParams(
                                                followUp(taskId, Boolean.TRUE), null, null),
                                        callContext(true)))
                        .blockFirst(Duration.ofSeconds(20));

        Task snapshot = assertInstanceOf(Task.class, first);
        assertEquals(TaskState.TASK_STATE_WORKING, snapshot.status().state());
    }

    @Test
    @DisplayName("Streaming: a runner that only emits the final result can resume")
    void streamingFinalOnlyRunnerResumes() {
        startServer(new InMemoryAgentStateStore(), true);
        String taskId = stream(firstMessage(), TaskState.TASK_STATE_INPUT_REQUIRED).taskId();

        stream(followUp(taskId, Boolean.TRUE), TaskState.TASK_STATE_COMPLETED);

        assertEquals(1, runner.tool.executed.get());
        assertEquals(TaskState.TASK_STATE_COMPLETED, storedState(taskId));
    }

    @Test
    @DisplayName("Streaming: a confirmation for another tool call keeps the task input-required")
    void streamingMismatchedConfirmationKeepsTaskInputRequired() {
        startServer(new InMemoryAgentStateStore());
        String taskId = stream(firstMessage(), TaskState.TASK_STATE_INPUT_REQUIRED).taskId();

        TaskStatusUpdateEvent stillPaused =
                stream(
                        followUp(taskId, Boolean.TRUE, "call-unknown"),
                        TaskState.TASK_STATE_INPUT_REQUIRED);

        assertCarriesPendingToolCall(stillPaused.status().message());
        assertEquals(1, runner.built.get(), "the agent must not run on a mismatched confirmation");
        stream(followUp(taskId, Boolean.TRUE), TaskState.TASK_STATE_COMPLETED);
        assertEquals(1, runner.tool.executed.get());
    }

    @Test
    @DisplayName("Streaming: a denial resumes the paused run without running the tool")
    void streamingDenialResumesWithoutRunningTool() {
        startServer(new InMemoryAgentStateStore());
        String taskId = stream(firstMessage(), TaskState.TASK_STATE_INPUT_REQUIRED).taskId();

        stream(followUp(taskId, Boolean.FALSE), TaskState.TASK_STATE_COMPLETED);

        assertEquals(0, runner.tool.executed.get());
        assertEquals(2, runner.model.calls.get());
    }

    @Test
    @DisplayName("Streaming: a follow-up without a confirmation keeps the task input-required")
    void streamingFollowUpWithoutConfirmationKeepsTaskInputRequired() {
        startServer(new InMemoryAgentStateStore());
        String taskId = stream(firstMessage(), TaskState.TASK_STATE_INPUT_REQUIRED).taskId();

        TaskStatusUpdateEvent stillPaused =
                stream(followUp(taskId, null), TaskState.TASK_STATE_INPUT_REQUIRED);

        assertCarriesPendingToolCall(stillPaused.status().message());
        assertEquals(1, runner.built.get(), "the agent must not run without a confirmation");
        stream(followUp(taskId, Boolean.TRUE), TaskState.TASK_STATE_COMPLETED);
        assertEquals(1, runner.tool.executed.get());
    }

    @Test
    @DisplayName("Streaming: a confirmation the agent never saw fails the task")
    void streamingConfirmationWithoutStateStoreFails() {
        startServer(null);
        String taskId = stream(firstMessage(), TaskState.TASK_STATE_INPUT_REQUIRED).taskId();

        TaskStatusUpdateEvent failed =
                stream(followUp(taskId, Boolean.TRUE), TaskState.TASK_STATE_FAILED);

        assertTrue(text(failed.status().message()).contains("AgentStateStore"));
        assertEquals(0, runner.tool.executed.get());
        assertEquals(1, runner.model.calls.get(), "the agent must not start a new conversation");
    }

    @Test
    @DisplayName("Blocking: a pause for confirmation returns an input-required task")
    void blockingPauseReturnsInputRequiredTask() {
        startServer(new InMemoryAgentStateStore());

        Task paused = assertInstanceOf(Task.class, send(firstMessage()));

        assertEquals(TaskState.TASK_STATE_INPUT_REQUIRED, paused.status().state());
        assertCarriesPendingToolCall(paused.status().message());
        assertEquals(TaskState.TASK_STATE_INPUT_REQUIRED, storedState(paused.id()));
        assertEquals(0, runner.tool.executed.get());
    }

    @Test
    @DisplayName("Blocking: an approval completes the paused task")
    void blockingApprovalCompletesTheTask() {
        startServer(new InMemoryAgentStateStore());
        String taskId = assertInstanceOf(Task.class, send(firstMessage())).id();

        Task completed = assertInstanceOf(Task.class, send(followUp(taskId, Boolean.TRUE)));

        assertEquals(TaskState.TASK_STATE_COMPLETED, completed.status().state());
        assertEquals("done", text(completed.status().message()));
        assertEquals(1, runner.tool.executed.get());
        assertEquals(TaskState.TASK_STATE_COMPLETED, storedState(taskId));
    }

    @Test
    @DisplayName("Blocking: a denial completes the paused task without running the tool")
    void blockingDenialCompletesWithoutRunningTool() {
        startServer(new InMemoryAgentStateStore());
        String taskId = assertInstanceOf(Task.class, send(firstMessage())).id();

        Task completed = assertInstanceOf(Task.class, send(followUp(taskId, Boolean.FALSE)));

        assertEquals(TaskState.TASK_STATE_COMPLETED, completed.status().state());
        assertEquals(0, runner.tool.executed.get());
    }

    @Test
    @DisplayName("Blocking: a follow-up without a confirmation keeps the task input-required")
    void blockingFollowUpWithoutConfirmationKeepsTaskInputRequired() {
        startServer(new InMemoryAgentStateStore());
        String taskId = assertInstanceOf(Task.class, send(firstMessage())).id();

        Task stillPaused = assertInstanceOf(Task.class, send(followUp(taskId, null)));

        assertEquals(TaskState.TASK_STATE_INPUT_REQUIRED, stillPaused.status().state());
        assertCarriesPendingToolCall(stillPaused.status().message());
        assertEquals(1, runner.built.get(), "the agent must not run without a confirmation");
        Task completed = assertInstanceOf(Task.class, send(followUp(taskId, Boolean.TRUE)));
        assertEquals(TaskState.TASK_STATE_COMPLETED, completed.status().state());
    }

    @Test
    @DisplayName("Blocking: a confirmation the agent never saw fails the task")
    void blockingConfirmationWithoutStateStoreFails() {
        startServer(null);
        String taskId = assertInstanceOf(Task.class, send(firstMessage())).id();

        Task failed = assertInstanceOf(Task.class, send(followUp(taskId, Boolean.TRUE)));

        assertEquals(TaskState.TASK_STATE_FAILED, failed.status().state());
        assertTrue(text(failed.status().message()).contains("AgentStateStore"));
        assertEquals(TaskState.TASK_STATE_FAILED, storedState(taskId));
        assertEquals(0, runner.tool.executed.get());
        assertEquals(1, runner.model.calls.get(), "the agent must not start a new conversation");
    }

    @Test
    @DisplayName("Blocking: a runner that only emits the final result can resume")
    void blockingFinalOnlyRunnerResumes() {
        startServer(new InMemoryAgentStateStore(), true);
        String taskId = assertInstanceOf(Task.class, send(firstMessage())).id();

        Task completed = assertInstanceOf(Task.class, send(followUp(taskId, Boolean.TRUE)));

        assertEquals(TaskState.TASK_STATE_COMPLETED, completed.status().state());
        assertEquals(1, runner.tool.executed.get());
    }

    @Test
    @DisplayName("Blocking: a confirmation for another tool call keeps the task input-required")
    void blockingMismatchedConfirmationKeepsTaskInputRequired() {
        startServer(new InMemoryAgentStateStore());
        String taskId = assertInstanceOf(Task.class, send(firstMessage())).id();

        Task stillPaused =
                assertInstanceOf(Task.class, send(followUp(taskId, Boolean.TRUE, "call-unknown")));

        assertEquals(TaskState.TASK_STATE_INPUT_REQUIRED, stillPaused.status().state());
        assertCarriesPendingToolCall(stillPaused.status().message());
        assertEquals(1, runner.built.get(), "the agent must not run on a mismatched confirmation");
        Task completed = assertInstanceOf(Task.class, send(followUp(taskId, Boolean.TRUE)));
        assertEquals(TaskState.TASK_STATE_COMPLETED, completed.status().state());
    }

    @Test
    @DisplayName("Return immediately: a resumed task is not reported as input-required")
    void returnImmediatelyResumeIsNotReportedInputRequired() {
        startServer(new InMemoryAgentStateStore());
        String taskId = assertInstanceOf(Task.class, send(firstMessage())).id();

        Task accepted =
                assertInstanceOf(
                        Task.class,
                        handler.onMessageSend(
                                new MessageSendParams(
                                        followUp(taskId, Boolean.TRUE),
                                        MessageSendConfiguration.builder()
                                                .returnImmediately(true)
                                                .build(),
                                        null),
                                callContext(false)));

        assertEquals(TaskState.TASK_STATE_WORKING, accepted.status().state());
    }

    private void startServer(AgentStateStore stateStore) {
        startServer(stateStore, false);
    }

    /**
     * @param finalOnly serve the agent through a runner that forwards only the final result, as
     *     a custom runner may do
     */
    private void startServer(AgentStateStore stateStore, boolean finalOnly) {
        runner = new PausingAgentRunner(stateStore);
        AgentRunner served = finalOnly ? new FinalResultOnlyRunner(runner) : runner;
        taskStore = new InMemoryTaskStore();
        handler =
                AgentScopeA2aRequestHandler.builder()
                        .agentExecutor(
                                new AgentScopeAgentExecutor(
                                        served, AgentExecuteProperties.builder().build()))
                        .taskStore(taskStore)
                        .build();
    }

    /**
     * Sends a streaming request and returns the first status update that either reaches {@code
     * expected} or ends the task. An {@code input-required} task keeps its stream open, so the
     * stream is left as soon as that update arrives.
     */
    private TaskStatusUpdateEvent stream(Message message, TaskState expected) {
        TaskStatusUpdateEvent update =
                JdkFlowAdapter.flowPublisherToFlux(
                                handler.onMessageSendStream(
                                        new MessageSendParams(message, null, null),
                                        callContext(true)))
                        .ofType(TaskStatusUpdateEvent.class)
                        .filter(event -> event.status().state() == expected || event.isFinal())
                        .blockFirst(Duration.ofSeconds(20));
        assertNotNull(update, "the stream ended without reaching " + expected);
        assertEquals(expected, update.status().state(), () -> text(update.status().message()));
        return update;
    }

    private EventKind send(Message message) {
        return handler.onMessageSend(
                new MessageSendParams(message, null, null), callContext(false));
    }

    private TaskState storedState(String taskId) {
        return taskStore.get(taskId).status().state();
    }

    private static ServerCallContext callContext(boolean streaming) {
        Map<String, Object> state = new HashMap<>();
        state.put(A2aServerConstants.ContextKeys.IS_STREAM_KEY, streaming);
        return new ServerCallContext(UnauthenticatedUser.INSTANCE, state, Set.of());
    }

    private static Message firstMessage() {
        return Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(CONTEXT_ID)
                .parts(new TextPart("hello"))
                .build();
    }

    /**
     * A follow-up on the paused task. With {@code confirmed} set it carries the confirmation the
     * way it travels over the wire: plain JSON under the metadata of the AgentScope message.
     */
    private static Message followUp(String taskId, Boolean confirmed) {
        return followUp(taskId, confirmed, TOOL_CALL_ID);
    }

    private static Message followUp(String taskId, Boolean confirmed, String toolCallId) {
        String msgId = "follow-up";
        Message.Builder builder =
                Message.builder()
                        .role(Message.Role.ROLE_USER)
                        .messageId(UUID.randomUUID().toString())
                        .contextId(CONTEXT_ID)
                        .taskId(taskId)
                        .parts(
                                new TextPart(
                                        "go on",
                                        Map.of(MessageConstants.MSG_ID_METADATA_KEY, msgId)));
        if (confirmed != null) {
            Map<String, Object> toolCall =
                    Map.of(
                            "type",
                            "tool_use",
                            "id",
                            toolCallId,
                            "name",
                            TOOL_NAME,
                            "input",
                            Map.of("query", "ping"));
            Map<String, Object> result = Map.of("confirmed", confirmed, "toolCall", toolCall);
            builder.metadata(Map.of(msgId, Map.of(Msg.METADATA_CONFIRM_RESULTS, List.of(result))));
        }
        return builder.build();
    }

    private static void assertCarriesPendingToolCall(Message message) {
        assertNotNull(message, "the status must carry the pending request");
        boolean carried =
                message.parts().stream()
                        .filter(DataPart.class::isInstance)
                        .map(part -> ((DataPart) part).metadata())
                        .anyMatch(
                                metadata ->
                                        TOOL_CALL_ID.equals(
                                                        metadata.get(
                                                                MessageConstants
                                                                        .TOOL_CALL_ID_METADATA_KEY))
                                                && TOOL_NAME.equals(
                                                        metadata.get(
                                                                MessageConstants
                                                                        .TOOL_NAME_METADATA_KEY)));
        assertTrue(carried, () -> "no pending tool call in " + message.parts());
    }

    private static String text(Message message) {
        if (message == null) {
            return "";
        }
        return message.parts().stream()
                .filter(TextPart.class::isInstance)
                .map(part -> ((TextPart) part).text())
                .collect(Collectors.joining());
    }

    /** Forwards only the final result of the wrapped runner. */
    private static final class FinalResultOnlyRunner implements AgentRunner {

        private final AgentRunner delegate;

        private FinalResultOnlyRunner(AgentRunner delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getAgentName() {
            return delegate.getAgentName();
        }

        @Override
        public String getAgentDescription() {
            return delegate.getAgentDescription();
        }

        @Override
        public Flux<AgentEvent> streamEvents(
                List<Msg> requestMessages, AgentRequestOptions options) {
            return delegate.streamEvents(requestMessages, options)
                    .filter(AgentResultEvent.class::isInstance);
        }

        @Override
        public void stop(String taskId) {
            delegate.stop(taskId);
        }
    }

    /** Builds a new agent for every request and shares the model and the tool between them. */
    private static final class PausingAgentRunner extends BaseReActAgentRunner {

        private final ScriptedModel model = new ScriptedModel();

        private final AskingTool tool = new AskingTool();

        private final AtomicInteger built = new AtomicInteger();

        private final AgentStateStore stateStore;

        private PausingAgentRunner(AgentStateStore stateStore) {
            this.stateStore = stateStore;
        }

        @Override
        protected ReActAgent buildReActAgent() {
            built.incrementAndGet();
            Toolkit toolkit = new Toolkit();
            toolkit.registerAgentTool(tool);
            ReActAgent.Builder builder =
                    ReActAgent.builder().name("assistant").model(model).toolkit(toolkit);
            if (stateStore != null) {
                builder.stateStore(stateStore);
            }
            return builder.build();
        }
    }

    /** Asks for the tool on the first call and answers with text afterwards. */
    private static final class ScriptedModel extends ChatModelBase {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String getModelName() {
            return "scripted";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            ContentBlock content =
                    calls.getAndIncrement() == 0
                            ? ToolUseBlock.builder()
                                    .id(TOOL_CALL_ID)
                                    .name(TOOL_NAME)
                                    .input(Map.of("query", "ping"))
                                    .build()
                            : TextBlock.builder().text("done").build();
            return Flux.just(ChatResponse.builder().content(List.of(content)).build());
        }
    }

    /** A tool whose permission check always asks the user. */
    private static final class AskingTool extends ToolBase {

        private final AtomicInteger executed = new AtomicInteger();

        private AskingTool() {
            super(
                    TOOL_NAME,
                    "asks for permission",
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("query", Map.of("type", "string"))),
                    false,
                    true,
                    false,
                    null,
                    false,
                    false);
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.ask("ask: " + getName()));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            executed.incrementAndGet();
            return Mono.just(ToolResultBlock.text("executed"));
        }
    }
}
