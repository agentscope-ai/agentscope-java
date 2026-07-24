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

package io.agentscope.core.a2a.server.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.a2a.agent.hitl.A2aHandoffType;
import io.agentscope.core.a2a.agent.hitl.A2aUserConfirmation;
import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.a2a.server.constants.A2aServerConstants;
import io.agentscope.core.a2a.server.executor.AgentExecuteProperties;
import io.agentscope.core.a2a.server.executor.AgentScopeAgentExecutor;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.hitl.HitlExecutionKey;
import io.agentscope.core.a2a.server.hitl.HitlHandoffRecord;
import io.agentscope.core.a2a.server.hitl.HitlHandoffStatus;
import io.agentscope.core.a2a.server.hitl.HitlOpenRequest;
import io.agentscope.core.a2a.server.hitl.HitlServerProperties;
import io.agentscope.core.a2a.server.hitl.HitlTurnAdmission;
import io.agentscope.core.a2a.server.hitl.LocalHitlResumeCoordinator;
import io.agentscope.core.a2a.server.hitl.LocalHitlSessionLease;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

@DisplayName("HITL resume admission integration")
class HitlResumeAdmissionIntegrationTest {

    @ParameterizedTest(name = "streaming={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("Fresh request without taskId lets SDK create the HITL task")
    void freshRequestWithoutTaskIdLetsSdkCreateHitlTask(boolean streaming) {
        String contextId = "fresh-hitl-context-" + streaming;
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        LocalHitlResumeCoordinator coordinator = new LocalHitlResumeCoordinator();
        LocalHitlSessionLease lease = new LocalHitlSessionLease();
        HitlServerProperties properties = HitlServerProperties.builder().enabled(true).build();
        AgentRunner runner = mock(AgentRunner.class);
        when(runner.getAgentName()).thenReturn("fresh-hitl-agent");
        AtomicReference<AgentRequestOptions> observedOptions = new AtomicReference<>();
        ToolUseBlock pending = new ToolUseBlock("fresh-call", "approval_probe", Map.of("value", 1));
        when(runner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                .thenAnswer(
                        invocation -> {
                            observedOptions.set(invocation.getArgument(1));
                            return Flux.just(
                                    new RequireUserConfirmEvent("fresh-reply", List.of(pending)));
                        });
        AgentScopeAgentExecutor executor =
                new AgentScopeAgentExecutor(
                        runner,
                        AgentExecuteProperties.builder().build(),
                        coordinator,
                        lease,
                        properties);
        AgentScopeA2aRequestHandler handler =
                AgentScopeA2aRequestHandler.builder()
                        .agentExecutor(executor)
                        .taskStore(taskStore)
                        .hitlTurnAdmission(
                                new HitlTurnAdmission(
                                        runner.getAgentName(),
                                        taskStore,
                                        coordinator,
                                        lease,
                                        properties))
                        .build();
        Message freshMessage =
                Message.builder()
                        .role(Message.Role.ROLE_USER)
                        .parts(new TextPart("pause for approval"))
                        .messageId("fresh-message-" + streaming)
                        .contextId(contextId)
                        .metadata(Map.of("userId", "alice"))
                        .build();
        MessageSendParams freshParams =
                MessageSendParams.builder()
                        .message(freshMessage)
                        .metadata(
                                Map.of(
                                        MessageConstants.NEXT_RESUME_TOKEN_METADATA_KEY,
                                        "fresh-next-token-never-log"))
                        .build();

        Object result = invoke(handler, freshParams, streaming);

        String createdTaskId;
        if (streaming) {
            @SuppressWarnings("unchecked")
            Flow.Publisher<StreamingEventKind> publisher =
                    (Flow.Publisher<StreamingEventKind>) result;
            TaskStatusUpdateEvent inputRequired =
                    JdkFlowAdapter.flowPublisherToFlux(publisher)
                            .filter(TaskStatusUpdateEvent.class::isInstance)
                            .map(TaskStatusUpdateEvent.class::cast)
                            .filter(
                                    event ->
                                            event.status().state()
                                                    == TaskState.TASK_STATE_INPUT_REQUIRED)
                            .next()
                            .block(Duration.ofSeconds(5));
            assertNotNull(inputRequired);
            createdTaskId = inputRequired.taskId();
        } else {
            Task inputRequired = assertInstanceOf(Task.class, result);
            assertEquals(TaskState.TASK_STATE_INPUT_REQUIRED, inputRequired.status().state());
            createdTaskId = inputRequired.id();
        }
        assertTrue(createdTaskId != null && !createdTaskId.isBlank());
        assertEquals(
                TaskState.TASK_STATE_INPUT_REQUIRED, taskStore.get(createdTaskId).status().state());
        AgentRequestOptions actualOptions = observedOptions.get();
        assertNotNull(actualOptions);
        assertEquals(createdTaskId, actualOptions.getTaskId());
        assertEquals(contextId, actualOptions.getSessionId());
        assertNull(freshParams.message().taskId());
    }

    @Test
    @DisplayName("TaskStore setup failure after claim releases lease and requires recovery")
    void taskStoreSetupFailureAfterClaimReleasesLeaseAndRequiresRecovery() {
        String taskId = "task-store-setup-failure";
        String contextId = "context-store-setup-failure";
        String token = "setup-failure-token-never-log";
        Task pausedTask = pausedTask(taskId, contextId);
        AtomicInteger reads = new AtomicInteger();
        TaskStore taskStore = mock(TaskStore.class);
        when(taskStore.get(taskId))
                .thenAnswer(
                        invocation -> {
                            if (reads.incrementAndGet() <= 2) {
                                return pausedTask;
                            }
                            throw new IllegalStateException("simulated TaskStore setup failure");
                        });
        LocalHitlResumeCoordinator coordinator = new LocalHitlResumeCoordinator();
        HitlHandoffRecord handoff = openHandoff(coordinator, taskId, contextId, token);
        LocalHitlSessionLease lease = new LocalHitlSessionLease();
        HitlServerProperties properties = HitlServerProperties.builder().enabled(true).build();
        HitlTurnAdmission admission =
                new HitlTurnAdmission(
                        "retry-safe-agent", taskStore, coordinator, lease, properties);
        AgentExecutor agentExecutor = mock(AgentExecutor.class);
        AgentScopeA2aRequestHandler handler =
                AgentScopeA2aRequestHandler.builder()
                        .agentExecutor(agentExecutor)
                        .taskStore(taskStore)
                        .hitlTurnAdmission(admission)
                        .build();

        assertThrows(
                IllegalStateException.class,
                () ->
                        handler.onMessageSend(
                                resumeParams(taskId, contextId, handoff.handoffId(), token),
                                callContext(false)));

        assertEquals(
                HitlHandoffStatus.RECOVERY_REQUIRED,
                coordinator.get(handoff.handoffId()).orElseThrow().status());
        verify(agentExecutor, never()).execute(any(), any());
        lease.acquire(handoff.executionKey(), Duration.ofSeconds(1)).close();
    }

    @Test
    @DisplayName("Rejected SDK scheduling after claim releases lease and requires recovery")
    void rejectedSdkSchedulingAfterClaimReleasesLeaseAndRequiresRecovery() throws Exception {
        String taskId = "task-scheduling-failure";
        String contextId = "context-scheduling-failure";
        String token = "scheduling-failure-token-never-log";
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        taskStore.save(pausedTask(taskId, contextId), false);
        LocalHitlResumeCoordinator coordinator = new LocalHitlResumeCoordinator();
        HitlHandoffRecord handoff = openHandoff(coordinator, taskId, contextId, token);
        LocalHitlSessionLease lease = new LocalHitlSessionLease();
        HitlServerProperties properties = HitlServerProperties.builder().enabled(true).build();
        HitlTurnAdmission admission =
                new HitlTurnAdmission(
                        "retry-safe-agent", taskStore, coordinator, lease, properties);
        AgentExecutor agentExecutor = mock(AgentExecutor.class);
        AgentScopeA2aRequestHandler handler =
                AgentScopeA2aRequestHandler.builder()
                        .agentExecutor(agentExecutor)
                        .taskStore(taskStore)
                        .hitlTurnAdmission(admission)
                        .build();
        Field executorField = DefaultRequestHandler.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        Executor rejecting =
                command -> {
                    throw new RejectedExecutionException("simulated SDK scheduling rejection");
                };
        executorField.set(handler, rejecting);

        assertThrows(
                RejectedExecutionException.class,
                () ->
                        handler.onMessageSend(
                                resumeParams(taskId, contextId, handoff.handoffId(), token),
                                callContext(false)));

        assertEquals(
                HitlHandoffStatus.RECOVERY_REQUIRED,
                coordinator.get(handoff.handoffId()).orElseThrow().status());
        verify(agentExecutor, never()).execute(any(), any());
        lease.acquire(handoff.executionKey(), Duration.ofSeconds(1)).close();
    }

    @Test
    @DisplayName("Rejected SDK scheduling before cancel ownership leaves handoff retryable")
    void rejectedSdkSchedulingBeforeCancelOwnershipLeavesHandoffRetryable() throws Exception {
        String taskId = "task-cancel-scheduling-failure";
        String contextId = "context-cancel-scheduling-failure";
        String token = "cancel-scheduling-failure-token-never-log";
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        Task paused = pausedTask(taskId, contextId);
        taskStore.save(paused, false);
        LocalHitlResumeCoordinator coordinator = new LocalHitlResumeCoordinator();
        HitlHandoffRecord handoff = openHandoff(coordinator, taskId, contextId, token);
        LocalHitlSessionLease lease = new LocalHitlSessionLease();
        HitlServerProperties properties = HitlServerProperties.builder().enabled(true).build();
        AgentRunner runner = mock(AgentRunner.class);
        when(runner.getAgentName()).thenReturn("retry-safe-agent");
        AgentScopeAgentExecutor agentExecutor =
                new AgentScopeAgentExecutor(
                        runner,
                        AgentExecuteProperties.builder().build(),
                        coordinator,
                        lease,
                        properties);
        HitlTurnAdmission admission =
                new HitlTurnAdmission(
                        runner.getAgentName(), taskStore, coordinator, lease, properties);
        AgentScopeA2aRequestHandler rejectingHandler =
                AgentScopeA2aRequestHandler.builder()
                        .agentExecutor(agentExecutor)
                        .taskStore(taskStore)
                        .hitlTurnAdmission(admission)
                        .build();
        Field executorField = DefaultRequestHandler.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        executorField.set(
                rejectingHandler,
                (Executor)
                        command -> {
                            throw new RejectedExecutionException(
                                    "simulated cancel scheduling rejection");
                        });

        assertThrows(
                RejectedExecutionException.class,
                () ->
                        rejectingHandler.onMessageSend(
                                cancelParams(taskId, contextId, handoff.handoffId(), token),
                                callContext(false)));

        assertEquals(TaskState.TASK_STATE_INPUT_REQUIRED, taskStore.get(taskId).status().state());
        assertEquals(
                HitlHandoffStatus.OPEN,
                coordinator.get(handoff.handoffId()).orElseThrow().status());
        lease.acquire(handoff.executionKey(), Duration.ofSeconds(1)).close();

        AgentScopeA2aRequestHandler retryHandler =
                AgentScopeA2aRequestHandler.builder()
                        .agentExecutor(agentExecutor)
                        .taskStore(taskStore)
                        .hitlTurnAdmission(admission)
                        .build();
        EventKind result =
                retryHandler.onMessageSend(
                        cancelParams(taskId, contextId, handoff.handoffId(), token),
                        callContext(false));

        assertInstanceOf(Task.class, result);
        assertEquals(TaskState.TASK_STATE_CANCELED, taskStore.get(taskId).status().state());
        assertEquals(
                HitlHandoffStatus.CANCELED,
                coordinator.get(handoff.handoffId()).orElseThrow().status());
        verify(runner, never()).streamEvents(anyList(), any(AgentRequestOptions.class));
    }

    @Test
    @DisplayName("RequestContext drift after claim aborts ticket and releases lease")
    void requestContextDriftAfterClaimAbortsTicketAndReleasesLease() {
        String taskId = "task-context-drift";
        String contextId = "context-before-drift";
        String token = "context-drift-token-never-log";
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        taskStore.save(pausedTask(taskId, contextId), false);
        LocalHitlResumeCoordinator coordinator = new LocalHitlResumeCoordinator();
        HitlHandoffRecord handoff = openHandoff(coordinator, taskId, contextId, token);
        LocalHitlSessionLease lease = new LocalHitlSessionLease();
        HitlServerProperties properties = HitlServerProperties.builder().enabled(true).build();
        AgentRunner runner = mock(AgentRunner.class);
        when(runner.getAgentName()).thenReturn("retry-safe-agent");
        AgentScopeAgentExecutor executor =
                new AgentScopeAgentExecutor(
                        runner,
                        AgentExecuteProperties.builder().build(),
                        coordinator,
                        lease,
                        properties);
        MessageSendParams params = resumeParams(taskId, contextId, handoff.handoffId(), token);
        ServerCallContext callContext = callContext(true);
        new HitlTurnAdmission(runner.getAgentName(), taskStore, coordinator, lease, properties)
                .admit(params, callContext)
                .attach(callContext);
        RequestContext drifted = mock(RequestContext.class);
        when(drifted.getTaskId()).thenReturn(taskId);
        when(drifted.getContextId()).thenReturn("context-after-drift");
        when(drifted.getCallContext()).thenReturn(callContext);
        when(drifted.getMessage()).thenReturn(params.message());
        AgentEmitter emitter = mock(AgentEmitter.class);
        when(emitter.getTaskId()).thenReturn(taskId);
        when(emitter.getContextId()).thenReturn(contextId);

        executor.execute(drifted, emitter);

        assertEquals(
                HitlHandoffStatus.RECOVERY_REQUIRED,
                coordinator.get(handoff.handoffId()).orElseThrow().status());
        lease.acquire(handoff.executionKey(), Duration.ofSeconds(1)).close();
        verify(runner, never()).streamEvents(anyList(), any(AgentRequestOptions.class));
        verify(emitter).fail(any(Message.class));
    }

    @Test
    @DisplayName("Wrong-token cancel leaves the original handoff cancelable")
    void wrongTokenCancelLeavesOriginalHandoffCancelable() {
        String taskId = "task-retry-safe-cancel";
        String contextId = "context-retry-safe-cancel";
        String token = "cancel-token-never-log";
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        Task paused = pausedTask(taskId, contextId);
        taskStore.save(paused, false);
        LocalHitlResumeCoordinator coordinator = new LocalHitlResumeCoordinator();
        HitlHandoffRecord handoff = openHandoff(coordinator, taskId, contextId, token);
        LocalHitlSessionLease lease = new LocalHitlSessionLease();
        HitlServerProperties properties = HitlServerProperties.builder().enabled(true).build();
        AgentRunner runner = mock(AgentRunner.class);
        when(runner.getAgentName()).thenReturn("retry-safe-agent");
        HitlTurnAdmission admission =
                new HitlTurnAdmission(
                        runner.getAgentName(), taskStore, coordinator, lease, properties);
        AgentScopeAgentExecutor executor =
                new AgentScopeAgentExecutor(
                        runner,
                        AgentExecuteProperties.builder().build(),
                        coordinator,
                        lease,
                        properties);
        AgentScopeA2aRequestHandler handler =
                AgentScopeA2aRequestHandler.builder()
                        .agentExecutor(executor)
                        .taskStore(taskStore)
                        .hitlTurnAdmission(admission)
                        .build();

        assertThrows(
                InvalidParamsError.class,
                () ->
                        handler.onMessageSend(
                                cancelParams(
                                        taskId,
                                        contextId,
                                        handoff.handoffId(),
                                        "wrong-cancel-token-never-log"),
                                callContext(false)));
        assertEquals(paused, taskStore.get(taskId));
        assertEquals(
                HitlHandoffStatus.OPEN,
                coordinator.get(handoff.handoffId()).orElseThrow().status());

        handler.onMessageSend(
                cancelParams(taskId, contextId, handoff.handoffId(), token), callContext(false));

        assertEquals(
                HitlHandoffStatus.CANCELED,
                coordinator.get(handoff.handoffId()).orElseThrow().status());
        assertEquals(TaskState.TASK_STATE_CANCELED, taskStore.get(taskId).status().state());
        verify(runner, never()).streamEvents(anyList(), any(AgentRequestOptions.class));
    }

    @Test
    @DisplayName("Post-claim runner failure fails Task and requires recovery")
    void postClaimRunnerFailureFailsTaskAndRequiresRecovery() {
        String taskId = "task-runner-failure";
        String contextId = "context-runner-failure";
        String token = "runner-failure-token-never-log";
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        taskStore.save(pausedTask(taskId, contextId), false);
        LocalHitlResumeCoordinator coordinator = new LocalHitlResumeCoordinator();
        HitlHandoffRecord handoff = openHandoff(coordinator, taskId, contextId, token);
        LocalHitlSessionLease lease = new LocalHitlSessionLease();
        HitlServerProperties properties = HitlServerProperties.builder().enabled(true).build();
        AgentRunner runner = mock(AgentRunner.class);
        when(runner.getAgentName()).thenReturn("retry-safe-agent");
        when(runner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                .thenThrow(new IllegalStateException("simulated runner failure"));
        AgentScopeAgentExecutor executor =
                new AgentScopeAgentExecutor(
                        runner,
                        AgentExecuteProperties.builder().build(),
                        coordinator,
                        lease,
                        properties);
        AgentScopeA2aRequestHandler handler =
                AgentScopeA2aRequestHandler.builder()
                        .agentExecutor(executor)
                        .taskStore(taskStore)
                        .hitlTurnAdmission(
                                new HitlTurnAdmission(
                                        runner.getAgentName(),
                                        taskStore,
                                        coordinator,
                                        lease,
                                        properties))
                        .build();

        @SuppressWarnings("unchecked")
        Flow.Publisher<StreamingEventKind> publisher =
                (Flow.Publisher<StreamingEventKind>)
                        invoke(
                                handler,
                                resumeParams(taskId, contextId, handoff.handoffId(), token),
                                true);
        List<StreamingEventKind> events =
                JdkFlowAdapter.flowPublisherToFlux(publisher)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertTrue(events != null && !events.isEmpty());
        assertEquals(TaskState.TASK_STATE_FAILED, taskStore.get(taskId).status().state());
        assertEquals(
                HitlHandoffStatus.RECOVERY_REQUIRED,
                coordinator.get(handoff.handoffId()).orElseThrow().status());
        lease.acquire(handoff.executionKey(), Duration.ofSeconds(1)).close();
    }

    @Test
    @DisplayName("Streaming addTask failure after claim fails once and requires recovery")
    void streamingAddTaskFailureAfterClaimFailsOnceAndRequiresRecovery() {
        assertStreamingSetupFailureRequiresRecovery(
                "add-task",
                emitter ->
                        doThrow(new IllegalStateException("addTask failed"))
                                .when(emitter)
                                .addTask(any(Task.class)));
    }

    @Test
    @DisplayName("Streaming startWork failure after claim fails once and requires recovery")
    void streamingStartWorkFailureAfterClaimFailsOnceAndRequiresRecovery() {
        assertStreamingSetupFailureRequiresRecovery(
                "start-work",
                emitter ->
                        doThrow(new IllegalStateException("startWork failed"))
                                .when(emitter)
                                .startWork());
    }

    @Test
    @DisplayName("Cancel emission failure after claim fails once and requires recovery")
    void cancelEmissionFailureAfterClaimFailsOnceAndRequiresRecovery() {
        String taskId = "task-cancel-emission-failure";
        String contextId = "context-cancel-emission-failure";
        String token = "cancel-emission-failure-token-never-log";
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        Task paused = pausedTask(taskId, contextId);
        taskStore.save(paused, false);
        LocalHitlResumeCoordinator coordinator = new LocalHitlResumeCoordinator();
        HitlHandoffRecord handoff = openHandoff(coordinator, taskId, contextId, token);
        LocalHitlSessionLease lease = new LocalHitlSessionLease();
        HitlServerProperties properties = HitlServerProperties.builder().enabled(true).build();
        AgentRunner runner = mock(AgentRunner.class);
        when(runner.getAgentName()).thenReturn("retry-safe-agent");
        AgentScopeAgentExecutor executor =
                new AgentScopeAgentExecutor(
                        runner,
                        AgentExecuteProperties.builder().build(),
                        coordinator,
                        lease,
                        properties);
        MessageSendParams params = cancelParams(taskId, contextId, handoff.handoffId(), token);
        ServerCallContext callContext = callContext(false);
        new HitlTurnAdmission(runner.getAgentName(), taskStore, coordinator, lease, properties)
                .admit(params, callContext)
                .attach(callContext);
        RequestContext requestContext = requestContext(params, paused, callContext);
        AgentEmitter emitter = mock(AgentEmitter.class);
        when(emitter.getTaskId()).thenReturn(taskId);
        when(emitter.getContextId()).thenReturn(contextId);
        doThrow(new IllegalStateException("cancel emission failed")).when(emitter).cancel();

        executor.execute(requestContext, emitter);

        assertEquals(
                HitlHandoffStatus.RECOVERY_REQUIRED,
                coordinator.get(handoff.handoffId()).orElseThrow().status());
        verify(emitter, times(1)).cancel();
        verify(emitter, times(1)).fail(nullable(Message.class));
        lease.acquire(handoff.executionKey(), Duration.ofSeconds(1)).close();
        verify(runner, never()).streamEvents(anyList(), any(AgentRequestOptions.class));
    }

    @ParameterizedTest(name = "streaming={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("Wrong token must not poison task and original token remains retryable")
    void wrongTokenDoesNotPoisonTaskAndOriginalTokenRemainsRetryable(boolean streaming) {
        String taskId = "task-retry-safe";
        String contextId = "context-retry-safe";
        String handoffToken = "correct-token-never-log";
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        Message pausedMessage =
                Message.builder()
                        .role(Message.Role.ROLE_AGENT)
                        .parts(new TextPart("approval required"))
                        .messageId("paused-message")
                        .taskId(taskId)
                        .contextId(contextId)
                        .build();
        Task pausedTask =
                Task.builder()
                        .id(taskId)
                        .contextId(contextId)
                        .status(
                                new TaskStatus(
                                        TaskState.TASK_STATE_INPUT_REQUIRED, pausedMessage, null))
                        .history(List.of(pausedMessage))
                        .build();
        taskStore.save(pausedTask, false);

        LocalHitlResumeCoordinator coordinator = new LocalHitlResumeCoordinator();
        ToolUseBlock pending =
                new ToolUseBlock("call-retry-safe", "approval_probe", Map.of("value", 1));
        HitlHandoffRecord handoff =
                coordinator.open(
                        new HitlOpenRequest(
                                taskId,
                                contextId,
                                new HitlExecutionKey("alice", "retry-safe-agent", contextId),
                                A2aHandoffType.USER_CONFIRM,
                                List.of(pending),
                                handoffToken,
                                Duration.ofDays(1),
                                null));

        AgentRunner runner = mock(AgentRunner.class);
        when(runner.getAgentName()).thenReturn("retry-safe-agent");
        AtomicInteger executions = new AtomicInteger();
        when(runner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                .thenAnswer(
                        invocation -> {
                            executions.incrementAndGet();
                            AgentRequestOptions options = invocation.getArgument(1);
                            assertEquals("alice", options.getUserId());
                            assertEquals(contextId, options.getSessionId());
                            return Flux.just(
                                    new AgentResultEvent(
                                            Msg.builder().textContent("approved").build()));
                        });

        HitlServerProperties hitlProperties = HitlServerProperties.builder().enabled(true).build();
        LocalHitlSessionLease sessionLease = new LocalHitlSessionLease();
        HitlTurnAdmission admission =
                new HitlTurnAdmission(
                        runner.getAgentName(),
                        taskStore,
                        coordinator,
                        sessionLease,
                        hitlProperties);
        AgentScopeAgentExecutor executor =
                new AgentScopeAgentExecutor(
                        runner,
                        AgentExecuteProperties.builder().build(),
                        coordinator,
                        sessionLease,
                        hitlProperties);
        AgentScopeA2aRequestHandler handler =
                AgentScopeA2aRequestHandler.builder()
                        .agentExecutor(executor)
                        .taskStore(taskStore)
                        .hitlTurnAdmission(admission)
                        .build();

        Task beforeRejectedResume = taskStore.get(taskId);
        MessageSendParams wrong =
                resumeParams(taskId, contextId, handoff.handoffId(), "wrong-token-never-log");
        InvalidParamsError rejection =
                assertThrows(InvalidParamsError.class, () -> invoke(handler, wrong, streaming));

        assertEquals("A2A HITL resume request was rejected", rejection.getMessage());
        assertEquals(beforeRejectedResume, taskStore.get(taskId));
        assertEquals(
                HitlHandoffStatus.OPEN,
                coordinator.get(handoff.handoffId()).orElseThrow().status());
        assertEquals(0, executions.get());
        verify(runner, never()).streamEvents(anyList(), any(AgentRequestOptions.class));

        MessageSendParams correct =
                resumeParams(taskId, contextId, handoff.handoffId(), handoffToken);
        Object result = invoke(handler, correct, streaming);
        if (streaming) {
            @SuppressWarnings("unchecked")
            Flow.Publisher<StreamingEventKind> publisher =
                    (Flow.Publisher<StreamingEventKind>) result;
            List<StreamingEventKind> events =
                    JdkFlowAdapter.flowPublisherToFlux(publisher)
                            .collectList()
                            .block(Duration.ofSeconds(5));
            assertTrue(events != null && !events.isEmpty());
            assertEquals(
                    1,
                    events.stream()
                            .filter(TaskStatusUpdateEvent.class::isInstance)
                            .map(TaskStatusUpdateEvent.class::cast)
                            .filter(
                                    event ->
                                            event.status().state()
                                                    == TaskState.TASK_STATE_COMPLETED)
                            .count());
        } else {
            Task completed = assertInstanceOf(Task.class, result);
            assertEquals(TaskState.TASK_STATE_COMPLETED, completed.status().state());
            assertNotNull(completed.status().message());
        }

        Task stored = taskStore.get(taskId);
        assertEquals(TaskState.TASK_STATE_COMPLETED, stored.status().state());
        if (!streaming) {
            assertNotNull(stored.status().message());
        }
        assertEquals(
                HitlHandoffStatus.COMPLETED,
                coordinator.get(handoff.handoffId()).orElseThrow().status());
        assertEquals(1, executions.get());
    }

    private void assertStreamingSetupFailureRequiresRecovery(
            String suffix, java.util.function.Consumer<AgentEmitter> failEmitterSetup) {
        String taskId = "task-streaming-setup-failure-" + suffix;
        String contextId = "context-streaming-setup-failure-" + suffix;
        String token = "streaming-setup-failure-token-" + suffix + "-never-log";
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        Task paused = pausedTask(taskId, contextId);
        taskStore.save(paused, false);
        LocalHitlResumeCoordinator coordinator = new LocalHitlResumeCoordinator();
        HitlHandoffRecord handoff = openHandoff(coordinator, taskId, contextId, token);
        LocalHitlSessionLease lease = new LocalHitlSessionLease();
        HitlServerProperties properties = HitlServerProperties.builder().enabled(true).build();
        AgentRunner runner = mock(AgentRunner.class);
        when(runner.getAgentName()).thenReturn("retry-safe-agent");
        when(runner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                .thenReturn(
                        Flux.just(
                                new AgentResultEvent(
                                        Msg.builder().textContent("approved").build())));
        AgentScopeAgentExecutor executor =
                new AgentScopeAgentExecutor(
                        runner,
                        AgentExecuteProperties.builder().build(),
                        coordinator,
                        lease,
                        properties);
        MessageSendParams params = resumeParams(taskId, contextId, handoff.handoffId(), token);
        ServerCallContext callContext = callContext(true);
        new HitlTurnAdmission(runner.getAgentName(), taskStore, coordinator, lease, properties)
                .admit(params, callContext)
                .attach(callContext);
        RequestContext requestContext = requestContext(params, paused, callContext);
        AgentEmitter emitter = mock(AgentEmitter.class);
        when(emitter.getTaskId()).thenReturn(taskId);
        when(emitter.getContextId()).thenReturn(contextId);
        failEmitterSetup.accept(emitter);

        executor.execute(requestContext, emitter);

        assertEquals(
                HitlHandoffStatus.RECOVERY_REQUIRED,
                coordinator.get(handoff.handoffId()).orElseThrow().status());
        ArgumentCaptor<Message> failure = ArgumentCaptor.forClass(Message.class);
        verify(emitter, times(1)).fail(failure.capture());
        String operation = "add-task".equals(suffix) ? "addTask" : "startWork";
        assertEquals(
                "Agent execution failed: " + operation + " failed",
                ((TextPart) failure.getValue().parts().get(0)).text());
        lease.acquire(handoff.executionKey(), Duration.ofSeconds(1)).close();
    }

    private RequestContext requestContext(
            MessageSendParams params, Task task, ServerCallContext callContext) {
        RequestContext requestContext = mock(RequestContext.class);
        when(requestContext.getTaskId()).thenReturn(task.id());
        when(requestContext.getContextId()).thenReturn(task.contextId());
        when(requestContext.getTask()).thenReturn(task);
        when(requestContext.getMessage()).thenReturn(params.message());
        when(requestContext.getMetadata()).thenReturn(params.metadata());
        when(requestContext.getConfiguration()).thenReturn(params.configuration());
        when(requestContext.getCallContext()).thenReturn(callContext);
        return requestContext;
    }

    private Object invoke(
            AgentScopeA2aRequestHandler handler, MessageSendParams params, boolean streaming) {
        ServerCallContext context = callContext(streaming);
        return streaming
                ? handler.onMessageSendStream(params, context)
                : handler.onMessageSend(params, context);
    }

    private ServerCallContext callContext(boolean streaming) {
        ServerCallContext context =
                new ServerCallContext(null, new ConcurrentHashMap<>(), Set.of(), null);
        context.getState().put(A2aServerConstants.ContextKeys.IS_STREAM_KEY, streaming);
        return context;
    }

    private Task pausedTask(String taskId, String contextId) {
        Message pausedMessage =
                Message.builder()
                        .role(Message.Role.ROLE_AGENT)
                        .parts(new TextPart("approval required"))
                        .messageId("paused-" + taskId)
                        .taskId(taskId)
                        .contextId(contextId)
                        .build();
        return Task.builder()
                .id(taskId)
                .contextId(contextId)
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, pausedMessage, null))
                .history(List.of(pausedMessage))
                .build();
    }

    private HitlHandoffRecord openHandoff(
            LocalHitlResumeCoordinator coordinator, String taskId, String contextId, String token) {
        ToolUseBlock pending =
                new ToolUseBlock("call-retry-safe", "approval_probe", Map.of("value", 1));
        return coordinator.open(
                new HitlOpenRequest(
                        taskId,
                        contextId,
                        new HitlExecutionKey("alice", "retry-safe-agent", contextId),
                        A2aHandoffType.USER_CONFIRM,
                        List.of(pending),
                        token,
                        Duration.ofDays(1),
                        null));
    }

    private MessageSendParams resumeParams(
            String taskId, String contextId, String handoffId, String token) {
        A2aUserConfirmation response =
                new A2aUserConfirmation("call-retry-safe", true, Map.of("value", 2), List.of());
        Message message =
                Message.builder()
                        .role(Message.Role.ROLE_USER)
                        .parts(new TextPart("approve"))
                        .messageId("resume-" + token.hashCode())
                        .taskId(taskId)
                        .contextId(contextId)
                        .metadata(
                                Map.of(
                                        MessageConstants.HITL_OPERATION_METADATA_KEY,
                                        "resume",
                                        MessageConstants.HANDOFF_ID_METADATA_KEY,
                                        handoffId,
                                        MessageConstants.HITL_RESPONSES_METADATA_KEY,
                                        List.of(response)))
                        .build();
        return MessageSendParams.builder()
                .message(message)
                .metadata(
                        Map.of(
                                MessageConstants.RESUME_TOKEN_METADATA_KEY,
                                token,
                                MessageConstants.NEXT_RESUME_TOKEN_METADATA_KEY,
                                "next-token-never-log"))
                .build();
    }

    private MessageSendParams cancelParams(
            String taskId, String contextId, String handoffId, String token) {
        Message message =
                Message.builder()
                        .role(Message.Role.ROLE_USER)
                        .parts(new TextPart("cancel"))
                        .messageId("cancel-" + token.hashCode())
                        .taskId(taskId)
                        .contextId(contextId)
                        .metadata(
                                Map.of(
                                        MessageConstants.HITL_OPERATION_METADATA_KEY,
                                        "cancel",
                                        MessageConstants.HANDOFF_ID_METADATA_KEY,
                                        handoffId))
                        .build();
        return MessageSendParams.builder()
                .message(message)
                .metadata(Map.of(MessageConstants.RESUME_TOKEN_METADATA_KEY, token))
                .build();
    }
}
