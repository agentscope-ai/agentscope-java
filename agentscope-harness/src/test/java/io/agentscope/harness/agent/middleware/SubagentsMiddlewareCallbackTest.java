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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.subagent.task.TaskCompletionEvent;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests for the callback lambda registered by {@link SubagentsMiddleware#wireMessageBus} on
 * {@link WorkspaceTaskRepository}. The lambda formats a notification hint and pushes it to the
 * {@link MessageBus} based on the task's terminal status.
 */
class SubagentsMiddlewareCallbackTest {

    private WorkspaceTaskRepository mockRepo;
    private MessageBus mockBus;
    private SubagentsMiddleware middleware;

    @BeforeEach
    void setUp() {
        mockRepo = mock(WorkspaceTaskRepository.class);
        mockBus = mock(MessageBus.class);
        when(mockBus.inboxPush(anyString(), anyMap()))
                .thenReturn(reactor.core.publisher.Mono.empty());
        when(mockBus.enqueueWakeup(anyString(), anyString(), anyString()))
                .thenReturn(reactor.core.publisher.Mono.empty());
        middleware =
                new SubagentsMiddleware(
                        List.of(),
                        mockRepo,
                        (io.agentscope.harness.agent.workspace.WorkspaceManager) null);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> captureInbox() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(mockBus, atLeastOnce()).inboxPush(anyString(), captor.capture());
        return captor;
    }

    @Test
    @DisplayName("COMPLETED event pushes result to inbox with 'completed' hint")
    void wireMessageBus_completed_eventPushesResultToInbox() {
        middleware.wireMessageBus(mockBus, "agent-1");

        ArgumentCaptor<WorkspaceTaskRepository.TaskCompletionCallback> cbCaptor =
                ArgumentCaptor.forClass(WorkspaceTaskRepository.TaskCompletionCallback.class);
        verify(mockRepo).setCompletionCallback(cbCaptor.capture());

        cbCaptor.getValue()
                .onCompleted(
                        new TaskCompletionEvent(
                                RuntimeContext.empty(),
                                "t1",
                                "worker",
                                "sess-1",
                                TaskStatus.COMPLETED,
                                "the-result",
                                null));

        ArgumentCaptor<Map<String, Object>> inboxCaptor = captureInbox();
        Map<String, Object> payload = inboxCaptor.getValue();
        assertEquals("hint", payload.get("type"));
        assertTrue(((String) payload.get("hint")).contains("completed"));
        assertTrue(((String) payload.get("hint")).contains("the-result"));
        verify(mockBus).enqueueWakeup(eq(""), eq("sess-1"), eq("agent-1"));
    }

    @Test
    @DisplayName("FAILED event pushes error to inbox with 'FAILED' hint")
    void wireMessageBus_failed_eventPushesErrorToInbox() {
        middleware.wireMessageBus(mockBus, "agent-1");

        ArgumentCaptor<WorkspaceTaskRepository.TaskCompletionCallback> cbCaptor =
                ArgumentCaptor.forClass(WorkspaceTaskRepository.TaskCompletionCallback.class);
        verify(mockRepo).setCompletionCallback(cbCaptor.capture());

        cbCaptor.getValue()
                .onCompleted(
                        new TaskCompletionEvent(
                                RuntimeContext.empty(),
                                "t2",
                                "worker",
                                "sess-2",
                                TaskStatus.FAILED,
                                null,
                                "something broke"));

        Map<String, Object> payload = captureInbox().getValue();
        assertTrue(((String) payload.get("hint")).contains("FAILED"));
        assertTrue(((String) payload.get("hint")).contains("something broke"));
    }

    @Test
    @DisplayName("CANCELLED event pushes cancellation notice to inbox")
    void wireMessageBus_cancelled_eventPushesCancelledToInbox() {
        middleware.wireMessageBus(mockBus, "agent-1");

        ArgumentCaptor<WorkspaceTaskRepository.TaskCompletionCallback> cbCaptor =
                ArgumentCaptor.forClass(WorkspaceTaskRepository.TaskCompletionCallback.class);
        verify(mockRepo).setCompletionCallback(cbCaptor.capture());

        cbCaptor.getValue()
                .onCompleted(
                        new TaskCompletionEvent(
                                RuntimeContext.empty(),
                                "t3",
                                "worker",
                                "sess-3",
                                TaskStatus.CANCELLED,
                                null,
                                null));

        Map<String, Object> payload = captureInbox().getValue();
        assertTrue(((String) payload.get("hint")).contains("cancelled"));
    }

    @Test
    @DisplayName("COMPLETED with null result uses '(no output)' fallback")
    void wireMessageBus_completedNullResult_usesFallback() {
        middleware.wireMessageBus(mockBus, "agent-1");

        ArgumentCaptor<WorkspaceTaskRepository.TaskCompletionCallback> cbCaptor =
                ArgumentCaptor.forClass(WorkspaceTaskRepository.TaskCompletionCallback.class);
        verify(mockRepo).setCompletionCallback(cbCaptor.capture());

        cbCaptor.getValue()
                .onCompleted(
                        new TaskCompletionEvent(
                                RuntimeContext.empty(),
                                "t4",
                                "worker",
                                "sess-4",
                                TaskStatus.COMPLETED,
                                null,
                                null));

        Map<String, Object> payload = captureInbox().getValue();
        assertTrue(((String) payload.get("hint")).contains("(no output)"));
    }

    @Test
    @DisplayName("FAILED with null errorMessage uses '(no error message)' fallback")
    void wireMessageBus_failedNullError_usesFallback() {
        middleware.wireMessageBus(mockBus, "agent-1");

        ArgumentCaptor<WorkspaceTaskRepository.TaskCompletionCallback> cbCaptor =
                ArgumentCaptor.forClass(WorkspaceTaskRepository.TaskCompletionCallback.class);
        verify(mockRepo).setCompletionCallback(cbCaptor.capture());

        cbCaptor.getValue()
                .onCompleted(
                        new TaskCompletionEvent(
                                RuntimeContext.empty(),
                                "t5",
                                "worker",
                                "sess-5",
                                TaskStatus.FAILED,
                                null,
                                null));

        Map<String, Object> payload = captureInbox().getValue();
        assertTrue(((String) payload.get("hint")).contains("(no error message)"));
    }

    @Test
    @DisplayName("null messageBus is a no-op")
    void wireMessageBus_nullMessageBus_isNoop() {
        middleware.wireMessageBus(null, "agent-1");
        verify(mockRepo, never()).setCompletionCallback(any());
    }
}
