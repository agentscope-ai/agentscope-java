/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.a2a.server.executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.a2a.server.ServerCallContext;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendParams;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

@DisplayName("AgentScopeAgentExecutor Tests")
@SuppressWarnings("unchecked")
class AgentScopeAgentExecutorTest {

    private AgentScopeAgentExecutor executor;
    private AgentRunner mockAgentRunner;
    private RequestContext mockContext;
    private EventQueue mockEventQueue;
    private ServerCallContext serverCallContext;
    private Flux<Event> mockFlux;

    @BeforeEach
    void setUp() {
        mockAgentRunner = mock(AgentRunner.class);
        executor = new AgentScopeAgentExecutor(mockAgentRunner);
        mockContext = mock(RequestContext.class);
        mockEventQueue = mock(EventQueue.class);
        serverCallContext = mock(ServerCallContext.class);
        mockFlux = mock(Flux.class);
        lenient().when(mockContext.getCallContext()).thenReturn(serverCallContext);
        lenient().when(mockFlux.doOnSubscribe(any())).thenReturn(mockFlux);
        lenient().when(mockFlux.doOnNext(any())).thenReturn(mockFlux);
        lenient().when(mockFlux.doOnComplete(any())).thenReturn(mockFlux);
        lenient().when(mockFlux.doOnError(any())).thenReturn(mockFlux);
        lenient().when(mockFlux.doFinally(any())).thenReturn(mockFlux);
    }

    private String doMockForContext() {
        String taskId = UUID.randomUUID().toString();
        String contextId = UUID.randomUUID().toString();

        when(mockContext.getTaskId()).thenReturn(taskId);
        when(mockContext.getContextId()).thenReturn(contextId);

        Message mockMessage = mock(Message.class);
        when(mockMessage.getContextId()).thenReturn(contextId);
        when(mockMessage.getParts()).thenReturn(List.of());
        when(mockContext.getMessage()).thenReturn(mockMessage);

        MessageSendParams mockParams = mock(MessageSendParams.class);
        when(mockContext.getParams()).thenReturn(mockParams);
        when(mockParams.message()).thenReturn(mockMessage);

        return taskId;
    }

    @Test
    @DisplayName("Should execute agent and process blocking request")
    void testExecuteAgentWithBlockingRequest() throws JSONRPCError {
        doMockForContext();
        // Mock AgentRunner
        when(mockFlux.blockLast()).thenReturn(null);
        when(mockAgentRunner.stream(
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.any(AgentRequestOptions.class)))
                .thenReturn(mockFlux);

        // When
        executor.execute(mockContext, mockEventQueue);

        // Then
        ArgumentCaptor<List<Msg>> messageCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<AgentRequestOptions> optionsCaptor =
                ArgumentCaptor.forClass(AgentRequestOptions.class);

        verify(mockAgentRunner).stream(messageCaptor.capture(), optionsCaptor.capture());
        verify(mockFlux).blockLast();
    }

    @Test
    @DisplayName("Should cancel task successfully")
    void testCancelTaskSuccessfully() throws JSONRPCError {
        // Given
        String taskId = doMockForContext();

        when(mockContext.getTaskId()).thenReturn(taskId);
        doNothing().when(mockAgentRunner).stop(taskId);

        // When
        executor.cancel(mockContext, mockEventQueue);

        // Then
        verify(mockAgentRunner).stop(taskId);
    }

    @Test
    @DisplayName("Should handle exception during task cancellation")
    void testHandleExceptionDuringTaskCancellation() throws JSONRPCError {
        // Given
        String taskId = doMockForContext();

        when(mockContext.getTaskId()).thenReturn(taskId);
        doThrow(new RuntimeException("Cancellation error")).when(mockAgentRunner).stop(taskId);

        // When
        executor.cancel(mockContext, mockEventQueue);

        // Then
        verify(mockAgentRunner).stop(taskId);
    }

    @Test
    @DisplayName("Should handle exception during execution")
    void testHandleExceptionDuringExecution() throws JSONRPCError {
        doMockForContext();
        // Given
        when(mockContext.getTaskId()).thenThrow(new RuntimeException("Context error"));

        // When
        executor.execute(mockContext, mockEventQueue);

        // Then
        verify(mockEventQueue).enqueueEvent(org.mockito.ArgumentMatchers.any(Message.class));
    }
}
