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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.subagent.task.TaskCompletionEvent;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests for the callback lambda registered by {@link HarnessAgent.Builder#wireTaskRepositoryMessageBus}
 * on {@link WorkspaceTaskRepository}. The lambda formats a system-notification hint based on the
 * task's terminal status and pushes it to the {@link MessageBus}.
 */
class HarnessAgentCallbackTest {

    private WorkspaceTaskRepository mockRepo;
    private MessageBus mockBus;

    @BeforeEach
    void setUp() {
        mockRepo = mock(WorkspaceTaskRepository.class);
        mockBus = mock(MessageBus.class);
        when(mockBus.inboxPush(anyString(), anyMap()))
                .thenReturn(reactor.core.publisher.Mono.empty());
        when(mockBus.enqueueWakeup(anyString(), anyString(), anyString()))
                .thenReturn(reactor.core.publisher.Mono.empty());
    }

    @SuppressWarnings("unchecked")
    private void invokeCallback(TaskStatus status, String result, String errorMessage) {
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
                                status,
                                result,
                                errorMessage));
    }

    @Test
    @DisplayName("COMPLETED event pushes result notification to inbox")
    void wireTaskRepositoryMessageBus_completedPushesResult() {
        HarnessAgent.Builder.wireTaskRepositoryMessageBus(mockRepo, mockBus, "agent-1");

        invokeCallback(TaskStatus.COMPLETED, "the-result", null);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(mockBus).inboxPush(eq("sess-1"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertEquals("hint", payload.get("type"));
        assertTrue(((String) payload.get("hint")).contains("completed"));
        assertTrue(((String) payload.get("hint")).contains("the-result"));
        verify(mockBus).enqueueWakeup(eq(""), eq("sess-1"), eq("agent-1"));
    }

    @Test
    @DisplayName("FAILED event pushes error notification to inbox")
    void wireTaskRepositoryMessageBus_failedPushesError() {
        HarnessAgent.Builder.wireTaskRepositoryMessageBus(mockRepo, mockBus, "agent-1");

        invokeCallback(TaskStatus.FAILED, null, "something broke");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(mockBus).inboxPush(eq("sess-1"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertTrue(((String) payload.get("hint")).contains("FAILED"));
        assertTrue(((String) payload.get("hint")).contains("something broke"));
    }

    @Test
    @DisplayName("CANCELLED event pushes cancellation notification to inbox")
    void wireTaskRepositoryMessageBus_cancelledPushesCancelled() {
        HarnessAgent.Builder.wireTaskRepositoryMessageBus(mockRepo, mockBus, "agent-1");

        invokeCallback(TaskStatus.CANCELLED, null, null);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(mockBus).inboxPush(eq("sess-1"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertTrue(((String) payload.get("hint")).contains("cancelled"));
    }

    @Test
    @DisplayName("non-WorkspaceTaskRepository does not register callback")
    void wireTaskRepositoryMessageBus_nonWorkspaceRepo_isNoop() {
        TaskRepository plainRepo = mock(TaskRepository.class);
        HarnessAgent.Builder.wireTaskRepositoryMessageBus(plainRepo, mockBus, "agent-1");
        // No setCompletionCallback should be invoked on a plain TaskRepository
        verify(mockBus, never()).inboxPush(anyString(), anyMap());
        verify(mockBus, never()).enqueueWakeup(anyString(), anyString(), anyString());
    }
}
