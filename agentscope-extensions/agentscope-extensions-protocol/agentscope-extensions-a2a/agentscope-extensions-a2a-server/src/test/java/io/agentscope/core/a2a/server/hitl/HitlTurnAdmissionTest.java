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

package io.agentscope.core.a2a.server.hitl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.a2a.agent.hitl.A2aExternalToolResponse;
import io.agentscope.core.a2a.agent.hitl.A2aHandoffType;
import io.agentscope.core.a2a.agent.hitl.A2aUserConfirmation;
import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HitlTurnAdmissionTest {

    private static final String TASK_ID = "task-admission";
    private static final String CONTEXT_ID = "context-admission";
    private static final String TOKEN = "admission-token-never-log";

    private InMemoryTaskStore taskStore;
    private LocalHitlResumeCoordinator coordinator;
    private HitlHandoffRecord handoff;
    private HitlTurnAdmission admission;
    private Task pausedTask;

    @BeforeEach
    void setUp() {
        taskStore = new InMemoryTaskStore();
        pausedTask =
                Task.builder()
                        .id(TASK_ID)
                        .contextId(CONTEXT_ID)
                        .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED))
                        .build();
        taskStore.save(pausedTask, false);
        coordinator = new LocalHitlResumeCoordinator();
        handoff =
                coordinator.open(
                        new HitlOpenRequest(
                                TASK_ID,
                                CONTEXT_ID,
                                new HitlExecutionKey("alice", "admission-agent", CONTEXT_ID),
                                A2aHandoffType.USER_CONFIRM,
                                List.of(tool()),
                                TOKEN,
                                Duration.ofDays(1),
                                null));
        admission =
                new HitlTurnAdmission(
                        "admission-agent",
                        taskStore,
                        coordinator,
                        new LocalHitlSessionLease(),
                        HitlServerProperties.builder().enabled(true).build());
    }

    @Test
    void rejectsWrongTaskContextAndHandoffBeforeMutation() {
        assertRejected(
                resumeParams("unknown-task", CONTEXT_ID, handoff.handoffId(), validResponses()));
        assertRejected(
                resumeParams(TASK_ID, "wrong-context", handoff.handoffId(), validResponses()));
        assertRejected(resumeParams(TASK_ID, CONTEXT_ID, "unknown-handoff", validResponses()));
    }

    @Test
    void rejectsMissingExtraDuplicateAndWrongTypeResponsesBeforeMutation() {
        assertRejected(resumeParams(TASK_ID, CONTEXT_ID, handoff.handoffId(), null));
        A2aUserConfirmation valid = validResponse();
        assertRejected(
                resumeParams(TASK_ID, CONTEXT_ID, handoff.handoffId(), List.of(valid, valid)));
        assertRejected(
                resumeParams(
                        TASK_ID,
                        CONTEXT_ID,
                        handoff.handoffId(),
                        List.of(new A2aUserConfirmation("extra-call", true, null, List.of()))));
        assertRejected(
                resumeParams(
                        TASK_ID,
                        CONTEXT_ID,
                        handoff.handoffId(),
                        List.of(
                                new A2aExternalToolResponse(
                                        "call-admission",
                                        ToolResultState.SUCCESS,
                                        List.of(),
                                        Map.of()))));
    }

    @Test
    void rejectsLeaseContentionBeforeClaimAndReleasesNoTaskMutation() {
        HitlSessionLease unavailableLease =
                new HitlSessionLease() {
                    @Override
                    public HitlLeaseHandle acquire(HitlExecutionKey key, Duration ttl) {
                        return null;
                    }

                    @Override
                    public HitlDurabilityCapability durabilityCapability() {
                        return HitlDurabilityCapability.LOCAL;
                    }
                };
        admission =
                new HitlTurnAdmission(
                        "admission-agent",
                        taskStore,
                        coordinator,
                        unavailableLease,
                        HitlServerProperties.builder().enabled(true).build());

        assertRejected(resumeParams(TASK_ID, CONTEXT_ID, handoff.handoffId(), validResponses()));
    }

    @Test
    void twoConcurrentCorrectResumesAdmitExactlyOneWithoutMutatingTask() throws Exception {
        MessageSendParams params =
                resumeParams(TASK_ID, CONTEXT_ID, handoff.handoffId(), validResponses());
        CountDownLatch start = new CountDownLatch(1);
        List<HitlAdmissionTicket> admitted =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        List<Throwable> rejected =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures =
                    List.of(
                            pool.submit(() -> admitAfter(start, params, admitted, rejected)),
                            pool.submit(() -> admitAfter(start, params, admitted, rejected)));
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, admitted.size());
        assertEquals(1, rejected.size());
        assertEquals(pausedTask, taskStore.get(TASK_ID));
        assertEquals(
                HitlHandoffStatus.CLAIMED,
                coordinator.get(handoff.handoffId()).orElseThrow().status());
        admitted.get(0).abort();
        assertEquals(
                HitlHandoffStatus.RECOVERY_REQUIRED,
                coordinator.get(handoff.handoffId()).orElseThrow().status());
    }

    private Void admitAfter(
            CountDownLatch start,
            MessageSendParams params,
            List<HitlAdmissionTicket> admitted,
            List<Throwable> rejected) {
        try {
            start.await();
            admitted.add(admission.admit(params, callContext()));
        } catch (Throwable failure) {
            rejected.add(failure);
        }
        return null;
    }

    private void assertRejected(MessageSendParams params) {
        assertThrows(
                HitlResumeRejectedException.class, () -> admission.admit(params, callContext()));
        assertEquals(pausedTask, taskStore.get(TASK_ID));
        assertEquals(
                HitlHandoffStatus.OPEN,
                coordinator.get(handoff.handoffId()).orElseThrow().status());
    }

    private MessageSendParams resumeParams(
            String taskId, String contextId, String handoffId, Object responses) {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put(MessageConstants.HITL_OPERATION_METADATA_KEY, "resume");
        metadata.put(MessageConstants.HANDOFF_ID_METADATA_KEY, handoffId);
        if (responses != null) {
            metadata.put(MessageConstants.HITL_RESPONSES_METADATA_KEY, responses);
        }
        Message message =
                Message.builder()
                        .role(Message.Role.ROLE_USER)
                        .parts(new TextPart("resume"))
                        .messageId("resume-admission")
                        .taskId(taskId)
                        .contextId(contextId)
                        .metadata(metadata)
                        .build();
        return MessageSendParams.builder()
                .message(message)
                .metadata(
                        Map.of(
                                MessageConstants.RESUME_TOKEN_METADATA_KEY,
                                TOKEN,
                                MessageConstants.NEXT_RESUME_TOKEN_METADATA_KEY,
                                "next-admission-token-never-log"))
                .build();
    }

    private List<A2aUserConfirmation> validResponses() {
        return List.of(validResponse());
    }

    private A2aUserConfirmation validResponse() {
        return new A2aUserConfirmation("call-admission", true, null, List.of());
    }

    private ToolUseBlock tool() {
        return new ToolUseBlock("call-admission", "approval_probe", Map.of("value", 1));
    }

    private ServerCallContext callContext() {
        return new ServerCallContext(null, new ConcurrentHashMap<>(), Set.of(), null);
    }
}
