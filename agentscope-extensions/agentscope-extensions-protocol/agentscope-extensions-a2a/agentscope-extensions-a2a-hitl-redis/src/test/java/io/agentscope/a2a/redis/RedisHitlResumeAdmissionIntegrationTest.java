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
package io.agentscope.a2a.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import io.agentscope.core.a2a.server.request.AgentScopeA2aRequestHandler;
import io.agentscope.core.event.AgentResultEvent;
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
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

class RedisHitlResumeAdmissionIntegrationTest {

    private static RedisServerSupport redis;

    @BeforeAll
    static void startRedis() throws Exception {
        redis = RedisServerSupport.start();
    }

    @AfterAll
    static void stopRedis() {
        if (redis != null) {
            redis.close();
            assertThat(redis.processAlive()).isFalse();
        }
    }

    @Test
    void wrongTokenLeavesTaskOpenAndFreshObjectsResumeExactlyOnce() {
        String namespace = "a2a:test:admission:" + System.nanoTime() + ':';
        String taskId = "redis-admission-task";
        String contextId = "redis-admission-context";
        String token = "redis-correct-token-never-store";
        RedisTaskStore taskStoreA =
                new RedisTaskStore(redis.client(), namespace, Duration.ofDays(1));
        taskStoreA.save(pausedTask(taskId, contextId), false);
        RedisHitlResumeCoordinator coordinatorA =
                new RedisHitlResumeCoordinator(redis.client(), namespace, Duration.ofDays(1));
        HitlHandoffRecord handoff = open(coordinatorA, taskId, contextId, token);

        RedisTaskStore taskStoreB =
                new RedisTaskStore(redis.client(), namespace, Duration.ofDays(1));
        RedisHitlResumeCoordinator coordinatorB =
                new RedisHitlResumeCoordinator(redis.client(), namespace, Duration.ofDays(1));
        RedisHitlSessionLease leaseB = new RedisHitlSessionLease(redis.client(), namespace);
        AtomicInteger executions = new AtomicInteger();
        AgentScopeA2aRequestHandler handler = handler(taskStoreB, coordinatorB, leaseB, executions);
        try {
            Task before = taskStoreB.get(taskId);
            assertThatThrownBy(
                            () ->
                                    handler.onMessageSendStream(
                                            resume(
                                                    taskId,
                                                    contextId,
                                                    handoff.handoffId(),
                                                    "redis-wrong-token-never-store"),
                                            callContext(true)))
                    .isInstanceOf(InvalidParamsError.class);
            assertThat(taskStoreB.get(taskId)).isEqualTo(before);
            assertThat(coordinatorB.get(handoff.handoffId()).orElseThrow().status())
                    .isEqualTo(HitlHandoffStatus.OPEN);
            assertThat(executions).hasValue(0);

            Flow.Publisher<StreamingEventKind> publisher =
                    handler.onMessageSendStream(
                            resume(taskId, contextId, handoff.handoffId(), token),
                            callContext(true));
            List<StreamingEventKind> events =
                    JdkFlowAdapter.flowPublisherToFlux(publisher)
                            .collectList()
                            .block(Duration.ofSeconds(5));
            assertThat(events).isNotEmpty();
            assertThat(executions).hasValue(1);
            assertThat(taskStoreB.get(taskId).status().state())
                    .isEqualTo(TaskState.TASK_STATE_COMPLETED);
            assertThat(coordinatorB.get(handoff.handoffId()).orElseThrow().status())
                    .isEqualTo(HitlHandoffStatus.COMPLETED);
            RedisNamespaceAssertions.containsNone(
                    redis.client(),
                    namespace,
                    token,
                    "redis-wrong-token-never-store",
                    "redis-next-token-never-store",
                    "authenticated",
                    "AgentState");
        } finally {
            leaseB.close();
        }
    }

    @Test
    void deferredCancelSurvivesSdkSchedulingRejectionAndRetries() throws Exception {
        String namespace = "a2a:test:cancel:" + System.nanoTime() + ':';
        String taskId = "redis-cancel-task";
        String contextId = "redis-cancel-context";
        String token = "redis-cancel-token-never-store";
        RedisTaskStore taskStore =
                new RedisTaskStore(redis.client(), namespace, Duration.ofDays(1));
        taskStore.save(pausedTask(taskId, contextId), false);
        RedisHitlResumeCoordinator coordinator =
                new RedisHitlResumeCoordinator(redis.client(), namespace, Duration.ofDays(1));
        HitlHandoffRecord handoff = open(coordinator, taskId, contextId, token);
        RedisHitlSessionLease lease = new RedisHitlSessionLease(redis.client(), namespace);
        AgentRunner runner = mock(AgentRunner.class);
        when(runner.getAgentName()).thenReturn("redis-admission-agent");
        HitlServerProperties properties = HitlServerProperties.builder().enabled(true).build();
        AgentScopeAgentExecutor executor =
                new AgentScopeAgentExecutor(
                        runner,
                        AgentExecuteProperties.builder().build(),
                        coordinator,
                        lease,
                        properties);
        HitlTurnAdmission admission =
                new HitlTurnAdmission(
                        runner.getAgentName(), taskStore, coordinator, lease, properties);
        AgentScopeA2aRequestHandler rejecting =
                AgentScopeA2aRequestHandler.builder()
                        .agentExecutor(executor)
                        .taskStore(taskStore)
                        .hitlTurnAdmission(admission)
                        .build();
        Field executorField = DefaultRequestHandler.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        executorField.set(
                rejecting,
                (Executor)
                        command -> {
                            throw new RejectedExecutionException("simulated scheduling rejection");
                        });
        try {
            assertThatThrownBy(
                            () ->
                                    rejecting.onMessageSend(
                                            cancel(taskId, contextId, handoff.handoffId(), token),
                                            callContext(false)))
                    .isInstanceOf(RejectedExecutionException.class);
            assertThat(taskStore.get(taskId).status().state())
                    .isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
            assertThat(coordinator.get(handoff.handoffId()).orElseThrow().status())
                    .isEqualTo(HitlHandoffStatus.OPEN);

            AgentScopeA2aRequestHandler retry =
                    AgentScopeA2aRequestHandler.builder()
                            .agentExecutor(executor)
                            .taskStore(taskStore)
                            .hitlTurnAdmission(admission)
                            .build();
            EventKind result =
                    retry.onMessageSend(
                            cancel(taskId, contextId, handoff.handoffId(), token),
                            callContext(false));
            assertThat(result).isInstanceOf(Task.class);
            assertThat(taskStore.get(taskId).status().state())
                    .isEqualTo(TaskState.TASK_STATE_CANCELED);
            assertThat(coordinator.get(handoff.handoffId()).orElseThrow().status())
                    .isEqualTo(HitlHandoffStatus.CANCELED);
            verify(runner, never()).streamEvents(anyList(), any(AgentRequestOptions.class));
        } finally {
            lease.close();
        }
    }

    private static AgentScopeA2aRequestHandler handler(
            RedisTaskStore taskStore,
            RedisHitlResumeCoordinator coordinator,
            RedisHitlSessionLease lease,
            AtomicInteger executions) {
        AgentRunner runner = mock(AgentRunner.class);
        when(runner.getAgentName()).thenReturn("redis-admission-agent");
        when(runner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                .thenAnswer(
                        invocation -> {
                            executions.incrementAndGet();
                            return Flux.just(
                                    new AgentResultEvent(
                                            Msg.builder().textContent("approved").build()));
                        });
        HitlServerProperties properties = HitlServerProperties.builder().enabled(true).build();
        AgentScopeAgentExecutor executor =
                new AgentScopeAgentExecutor(
                        runner,
                        AgentExecuteProperties.builder().build(),
                        coordinator,
                        lease,
                        properties);
        return AgentScopeA2aRequestHandler.builder()
                .agentExecutor(executor)
                .taskStore(taskStore)
                .hitlTurnAdmission(
                        new HitlTurnAdmission(
                                runner.getAgentName(), taskStore, coordinator, lease, properties))
                .build();
    }

    private static HitlHandoffRecord open(
            RedisHitlResumeCoordinator coordinator, String taskId, String contextId, String token) {
        return coordinator.open(
                new HitlOpenRequest(
                        taskId,
                        contextId,
                        new HitlExecutionKey("alice", "redis-admission-agent", contextId),
                        A2aHandoffType.USER_CONFIRM,
                        List.of(
                                new ToolUseBlock(
                                        "redis-call-1", "approval_probe", Map.of("value", 1))),
                        token,
                        Duration.ofHours(1),
                        null));
    }

    private static Task pausedTask(String taskId, String contextId) {
        Message statusMessage =
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
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, statusMessage, null))
                .history(List.of(statusMessage))
                .build();
    }

    private static MessageSendParams resume(
            String taskId, String contextId, String handoffId, String token) {
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
                                        List.of(
                                                new A2aUserConfirmation(
                                                        "redis-call-1",
                                                        true,
                                                        Map.of("value", 2),
                                                        List.of()))))
                        .build();
        return MessageSendParams.builder()
                .message(message)
                .metadata(
                        Map.of(
                                MessageConstants.RESUME_TOKEN_METADATA_KEY,
                                token,
                                MessageConstants.NEXT_RESUME_TOKEN_METADATA_KEY,
                                "redis-next-token-never-store"))
                .build();
    }

    private static MessageSendParams cancel(
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

    private static ServerCallContext callContext(boolean streaming) {
        ServerCallContext context =
                new ServerCallContext(null, new ConcurrentHashMap<>(), Set.of(), null);
        context.getState().put(A2aServerConstants.ContextKeys.IS_STREAM_KEY, streaming);
        return context;
    }
}
