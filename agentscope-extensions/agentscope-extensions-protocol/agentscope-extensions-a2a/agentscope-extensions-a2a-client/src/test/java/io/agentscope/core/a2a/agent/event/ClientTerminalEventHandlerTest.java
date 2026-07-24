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

package io.agentscope.core.a2a.agent.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

@DisplayName("A2A client terminal event handlers")
class ClientTerminalEventHandlerTest {

    @Test
    @DisplayName("Should share one terminal gate across message, task and status events")
    void shouldShareOneTerminalGateAcrossAllTerminalEntrypoints() {
        TestContext test = context();
        AtomicInteger postReasoningCount = new AtomicInteger();
        test.context()
                .setHooks(
                        List.of(
                                new Hook() {
                                    @Override
                                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                                        if (event instanceof PostReasoningEvent) {
                                            postReasoningCount.incrementAndGet();
                                        }
                                        return Mono.just(event);
                                    }
                                }));
        Message message = A2A.toAgentMessage("message result");
        new MessageEventHandler().handle(new MessageEvent(message), test.context());

        Task task = completedTask("task result");
        new TaskEventHandler().handle(new TaskEvent(task), test.context());
        new TaskUpdateEventHandler()
                .handle(
                        new TaskUpdateEvent(
                                task,
                                new TaskStatusUpdateEvent(
                                        task.id(), task.status(), task.contextId(), Map.of())),
                        test.context());

        assertTrue(test.context().isTerminalDelivered());
        assertEquals(1, postReasoningCount.get());
        verify(test.sink()).success(any(Msg.class));
    }

    @Test
    @DisplayName("Should complete from a standalone final TaskEvent")
    void shouldCompleteFromStandaloneFinalTaskEvent() {
        TestContext test = context();

        new TaskEventHandler().handle(new TaskEvent(completedTask("task result")), test.context());

        ArgumentCaptor<Msg> result = ArgumentCaptor.forClass(Msg.class);
        verify(test.sink()).success(result.capture());
        assertEquals("task result", result.getValue().getTextContent());
        assertEquals(
                "completed",
                result.getValue().getMetadata().get(MessageConstants.A2A_TASK_STATE_METADATA_KEY));
    }

    @Test
    @DisplayName("Should return input-required as a handoff Msg")
    void shouldReturnInputRequiredAsHandoffMsg() {
        TestContext test = context();
        Message prompt = A2A.toAgentMessage("Please confirm");
        TaskStatus status = new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, prompt, null);
        Task task = Task.builder().id("task-1").contextId("context-1").status(status).build();

        new TaskUpdateEventHandler()
                .handle(
                        new TaskUpdateEvent(
                                task,
                                new TaskStatusUpdateEvent(
                                        task.id(), status, task.contextId(), Map.of())),
                        test.context());

        ArgumentCaptor<Msg> result = ArgumentCaptor.forClass(Msg.class);
        verify(test.sink()).success(result.capture());
        assertEquals("Please confirm", result.getValue().getTextContent());
        assertEquals(
                "input-required",
                result.getValue().getMetadata().get(MessageConstants.A2A_TASK_STATE_METADATA_KEY));
    }

    @Test
    @DisplayName("Should deliver a new input-required Task after resume progress")
    void shouldDeliverNewPauseAfterResumeProgress() {
        TestContext test = context();
        test.context().ignorePriorHandoffSnapshot("task-1", "context-1", "handoff-1");
        Task working =
                Task.builder()
                        .id("task-1")
                        .contextId("context-1")
                        .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
                        .build();
        new TaskEventHandler().handle(new TaskEvent(working), test.context());

        Message prompt =
                Message.builder(A2A.toAgentMessage("Confirm again"))
                        .metadata(Map.of(MessageConstants.HANDOFF_ID_METADATA_KEY, "handoff-2"))
                        .build();
        Task repaused =
                Task.builder()
                        .id("task-1")
                        .contextId("context-1")
                        .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, prompt, null))
                        .build();
        new TaskEventHandler().handle(new TaskEvent(repaused), test.context());

        ArgumentCaptor<Msg> delivered = ArgumentCaptor.forClass(Msg.class);
        verify(test.sink()).success(delivered.capture());
        assertEquals(
                "handoff-2",
                delivered.getValue().getMetadata().get(MessageConstants.HANDOFF_ID_METADATA_KEY));
    }

    @Test
    @DisplayName("Should fail auth-required exactly once without publishing a post hook or Msg")
    void shouldFailAuthRequiredExactlyOnceWithoutPostHookOrMsg() {
        TestContext test = context();
        AtomicInteger postReasoningCount = new AtomicInteger();
        test.context()
                .setHooks(
                        List.of(
                                new Hook() {
                                    @Override
                                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                                        if (event instanceof PostReasoningEvent) {
                                            postReasoningCount.incrementAndGet();
                                        }
                                        return Mono.just(event);
                                    }
                                }));
        Message prompt = A2A.toAgentMessage("Authenticate first");
        TaskStatus status = new TaskStatus(TaskState.TASK_STATE_AUTH_REQUIRED, prompt, null);
        Task task = Task.builder().id("task-1").contextId("context-1").status(status).build();

        new TaskUpdateEventHandler()
                .handle(
                        new TaskUpdateEvent(
                                task,
                                new TaskStatusUpdateEvent(
                                        task.id(), status, task.contextId(), Map.of())),
                        test.context());
        new TaskEventHandler().handle(new TaskEvent(task), test.context());

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(test.sink(), times(1)).error(error.capture());
        assertTrue(
                error.getValue()
                        .getMessage()
                        .contains("AgentScope 2.0 has no in-task authentication resume contract"));
        verify(test.sink(), never()).success(any(Msg.class));
        assertEquals(0, postReasoningCount.get());
    }

    @Test
    @DisplayName("Should complete from a standalone final status update")
    void shouldCompleteFromStandaloneFinalStatusUpdate() {
        TestContext test = context();
        Task task = completedTask("status result");
        TaskStatusUpdateEvent update =
                new TaskStatusUpdateEvent(task.id(), task.status(), task.contextId(), Map.of());

        new TaskUpdateEventHandler().handle(new TaskUpdateEvent(task, update), test.context());

        ArgumentCaptor<Msg> result = ArgumentCaptor.forClass(Msg.class);
        verify(test.sink()).success(result.capture());
        assertEquals("status result", result.getValue().getTextContent());
        assertEquals(
                "completed",
                result.getValue().getMetadata().get(MessageConstants.A2A_TASK_STATE_METADATA_KEY));
    }

    @Test
    @DisplayName("Should preserve failed task state in a terminal Msg")
    void shouldPreserveFailedTaskStateInTerminalMsg() {
        TestContext test = context();
        TaskStatus status = new TaskStatus(TaskState.TASK_STATE_FAILED);
        Task task = Task.builder().id("task-1").contextId("context-1").status(status).build();

        new TaskUpdateEventHandler()
                .handle(
                        new TaskUpdateEvent(
                                task,
                                new TaskStatusUpdateEvent(
                                        task.id(), status, task.contextId(), Map.of())),
                        test.context());

        ArgumentCaptor<Msg> result = ArgumentCaptor.forClass(Msg.class);
        verify(test.sink()).success(result.capture());
        assertEquals(
                "failed",
                result.getValue().getMetadata().get(MessageConstants.A2A_TASK_STATE_METADATA_KEY));
        assertTrue(result.getValue().getTextContent().contains("failed"));
    }

    @Test
    @DisplayName("Should deliver success or error only once")
    void shouldDeliverSuccessOrErrorOnlyOnce() {
        TestContext test = context();
        Msg first = Msg.builder().textContent("first").build();

        assertTrue(test.context().complete(first));
        assertFalse(test.context().complete(Msg.builder().textContent("second").build()));
        test.context().completeExceptionally(new IllegalStateException("late"));

        verify(test.sink()).success(first);
    }

    @Test
    @DisplayName("Should abandon once without hooks or sink terminals")
    void shouldAbandonOnceWithoutHooksOrSinkTerminals() {
        TestContext test = context();
        AtomicInteger chunkCount = new AtomicInteger();
        AtomicInteger postCount = new AtomicInteger();
        test.context()
                .setHooks(
                        List.of(
                                new Hook() {
                                    @Override
                                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                                        if (event instanceof ReasoningChunkEvent) {
                                            chunkCount.incrementAndGet();
                                        } else if (event instanceof PostReasoningEvent) {
                                            postCount.incrementAndGet();
                                        }
                                        return Mono.just(event);
                                    }
                                }));

        assertTrue(test.context().abandon());
        assertFalse(test.context().abandon());
        test.context().publishReasoningChunk(Msg.builder().textContent("late chunk").build());
        assertFalse(test.context().complete(Msg.builder().textContent("late result").build()));
        test.context().completeExceptionally(new IllegalStateException("late error"));

        assertTrue(test.context().isTerminalDelivered());
        assertEquals(0, chunkCount.get());
        assertEquals(0, postCount.get());
        verify(test.sink(), never()).success(any(Msg.class));
        verify(test.sink(), never()).error(any(Throwable.class));
    }

    private Task completedTask(String text) {
        Artifact artifact =
                Artifact.builder()
                        .artifactId("artifact-1")
                        .name("agent")
                        .parts(new TextPart(text))
                        .build();
        return Task.builder()
                .id("task-1")
                .contextId("context-1")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                .artifacts(List.of(artifact))
                .build();
    }

    @SuppressWarnings("unchecked")
    private TestContext context() {
        A2aAgent agent = mock(A2aAgent.class);
        when(agent.getName()).thenReturn("agent");
        MonoSink<Msg> sink = mock(MonoSink.class);
        ClientEventContext context = new ClientEventContext("request-1", agent);
        context.setSink(sink);
        return new TestContext(context, sink);
    }

    private record TestContext(ClientEventContext context, MonoSink<Msg> sink) {}
}
