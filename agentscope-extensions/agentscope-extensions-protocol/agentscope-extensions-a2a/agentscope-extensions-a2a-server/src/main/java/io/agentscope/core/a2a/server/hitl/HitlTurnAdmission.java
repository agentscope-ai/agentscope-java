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

import io.agentscope.core.a2a.agent.hitl.A2aHitlResponse;
import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.message.Msg;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskNotFoundError;
import org.a2aproject.sdk.spec.TaskState;

/** Synchronous HITL admission that runs before the SDK mutates or schedules a Task. */
public final class HitlTurnAdmission {

    private final String logicalAgentId;
    private final TaskStore taskStore;
    private final HitlResumeCoordinator coordinator;
    private final HitlSessionLease sessionLease;
    private final HitlServerProperties properties;

    public HitlTurnAdmission(
            String logicalAgentId,
            TaskStore taskStore,
            HitlResumeCoordinator coordinator,
            HitlSessionLease sessionLease,
            HitlServerProperties properties) {
        this.logicalAgentId = hasText(logicalAgentId) ? logicalAgentId.trim() : "agent";
        this.taskStore = Objects.requireNonNull(taskStore, "taskStore");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.sessionLease = Objects.requireNonNull(sessionLease, "sessionLease");
        this.properties = properties == null ? HitlServerProperties.builder().build() : properties;
    }

    public HitlAdmissionTicket admit(MessageSendParams params, ServerCallContext callContext) {
        String requestedOperation =
                metadataValue(
                        params == null ? null : params.message(),
                        MessageConstants.HITL_OPERATION_METADATA_KEY);
        ResolvedAgentRequestMetadata request;
        try {
            request =
                    ResolvedAgentRequestMetadata.resolve(
                            params, callContext, taskStore, logicalAgentId);
        } catch (TaskNotFoundError | InvalidParamsError validationFailure) {
            if (hasText(requestedOperation)) {
                throw new HitlResumeRejectedException("HITL request coordinates were rejected");
            }
            throw validationFailure;
        }

        if (!properties.enabled()) {
            if (hasText(request.operation())) {
                throw new HitlResumeRejectedException("A2A HITL resume is disabled");
            }
            return normalTicket(request, null, null);
        }
        if ("resume".equalsIgnoreCase(request.operation())) {
            requireExistingTaskCoordinate(request);
            return admitResume(request);
        }
        if ("cancel".equalsIgnoreCase(request.operation())) {
            requireExistingTaskCoordinate(request);
            return admitCancel(request);
        }
        if (hasText(request.operation())) {
            throw new HitlResumeRejectedException("Unsupported HITL operation");
        }
        Task currentTask = request.freshTask() ? null : taskStore.get(request.taskId());
        if (currentTask != null
                && currentTask.status() != null
                && currentTask.status().state() == TaskState.TASK_STATE_INPUT_REQUIRED) {
            throw new HitlResumeRejectedException(
                    "Input-required task must be resumed or canceled explicitly");
        }
        HitlLeaseHandle lease = acquire(request.executionKey());
        try {
            if (coordinator.hasOpenHandoff(request.executionKey())) {
                throw new HitlResumeRejectedException(
                        "Session has an open HITL handoff; resume or cancel it first");
            }
            return normalTicket(request, lease, encodingContext(request, null));
        } catch (RuntimeException failure) {
            lease.close();
            throw failure;
        }
    }

    private HitlAdmissionTicket admitResume(ResolvedAgentRequestMetadata request) {
        requireInputRequiredTask(request.taskId());
        String handoffId =
                requiredMessageMetadata(
                        request.requestParams().message(),
                        MessageConstants.HANDOFF_ID_METADATA_KEY);
        String token = requiredCredential(request.resumeToken());
        requiredCredential(request.nextResumeToken());
        HitlHandoffRecord record = requireMatchingOpenRecord(request, handoffId);
        List<A2aHitlResponse> responses = parseResponses(request.requestParams().message());
        List<Msg> resumeMessages;
        try {
            resumeMessages = HitlResumeMessageFactory.create(record, responses);
        } catch (IllegalArgumentException invalidResponses) {
            throw new HitlResumeRejectedException(invalidResponses.getMessage());
        }

        HitlLeaseHandle lease = acquire(record.executionKey());
        try {
            HitlHandoffRecord claimed =
                    coordinator.claim(
                            new HitlClaimRequest(
                                    request.taskId(), request.contextId(), handoffId, token));
            request.requestOptions().setUserId(claimed.executionKey().userId());
            request.requestOptions().setSessionId(claimed.executionKey().contextId());
            HitlEncodingContext encodingContext =
                    new HitlEncodingContext(
                            coordinator,
                            claimed.executionKey(),
                            request.nextResumeToken(),
                            properties.handoffTtl(),
                            claimed.handoffId());
            return new HitlAdmissionTicket(
                    request,
                    HitlAdmissionTicket.Operation.RESUME,
                    resumeMessages,
                    claimed.handoffId(),
                    encodingContext,
                    lease,
                    coordinator);
        } catch (RuntimeException failure) {
            lease.close();
            throw failure;
        }
    }

    private HitlAdmissionTicket admitCancel(ResolvedAgentRequestMetadata request) {
        requireInputRequiredTask(request.taskId());
        String handoffId =
                requiredMessageMetadata(
                        request.requestParams().message(),
                        MessageConstants.HANDOFF_ID_METADATA_KEY);
        String token = requiredCredential(request.resumeToken());
        HitlHandoffRecord record = requireMatchingOpenRecord(request, handoffId);
        HitlLeaseHandle lease = acquire(record.executionKey());
        try {
            HitlClaimRequest cancelClaim =
                    new HitlClaimRequest(request.taskId(), request.contextId(), handoffId, token);
            coordinator.validateClaim(cancelClaim);
            return HitlAdmissionTicket.cancel(request, cancelClaim, lease, coordinator);
        } catch (RuntimeException failure) {
            lease.close();
            throw failure;
        }
    }

    private HitlAdmissionTicket normalTicket(
            ResolvedAgentRequestMetadata request,
            HitlLeaseHandle lease,
            HitlEncodingContext encodingContext) {
        return new HitlAdmissionTicket(
                request,
                HitlAdmissionTicket.Operation.NORMAL,
                null,
                null,
                encodingContext,
                lease,
                coordinator);
    }

    private HitlHandoffRecord requireMatchingOpenRecord(
            ResolvedAgentRequestMetadata request, String handoffId) {
        HitlHandoffRecord record =
                coordinator
                        .get(handoffId)
                        .orElseThrow(() -> new HitlResumeRejectedException("Unknown HITL handoff"));
        if (!record.taskId().equals(request.taskId())
                || !record.contextId().equals(request.contextId())) {
            throw new HitlResumeRejectedException("HITL handoff coordinates do not match");
        }
        if (record.status() != HitlHandoffStatus.OPEN) {
            throw new HitlResumeRejectedException("HITL handoff is no longer open");
        }
        return record;
    }

    private void requireInputRequiredTask(String taskId) {
        Task task = taskStore.get(taskId);
        if (task == null) {
            throw new HitlResumeRejectedException("Unknown HITL task");
        }
        if (task.status() == null || task.status().state() != TaskState.TASK_STATE_INPUT_REQUIRED) {
            throw new HitlResumeRejectedException("HITL task is not input-required");
        }
    }

    private void requireExistingTaskCoordinate(ResolvedAgentRequestMetadata request) {
        if (request.freshTask()) {
            throw new HitlResumeRejectedException("HITL request taskId is required");
        }
    }

    private HitlLeaseHandle acquire(HitlExecutionKey executionKey) {
        HitlLeaseHandle lease = sessionLease.acquire(executionKey, properties.executionLeaseTtl());
        if (lease == null || !lease.isValid()) {
            if (lease != null) {
                lease.close();
            }
            throw new HitlResumeRejectedException("A2A session execution lease was not acquired");
        }
        return lease;
    }

    private HitlEncodingContext encodingContext(
            ResolvedAgentRequestMetadata request, String claimedHandoffId) {
        return new HitlEncodingContext(
                coordinator,
                request.executionKey(),
                request.nextResumeToken(),
                properties.handoffTtl(),
                claimedHandoffId);
    }

    private List<A2aHitlResponse> parseResponses(Message message) {
        Object raw =
                message.metadata() == null
                        ? null
                        : message.metadata().get(MessageConstants.HITL_RESPONSES_METADATA_KEY);
        if (!(raw instanceof List<?> values)) {
            throw new HitlResumeRejectedException("HITL resume responses are missing");
        }
        List<A2aHitlResponse> result = new ArrayList<>();
        try {
            for (Object value : values) {
                result.add(
                        value instanceof A2aHitlResponse response
                                ? response
                                : JsonUtils.getJsonCodec()
                                        .convertValue(value, A2aHitlResponse.class));
            }
        } catch (RuntimeException invalidResponse) {
            throw new HitlResumeRejectedException("HITL resume responses are invalid");
        }
        return List.copyOf(result);
    }

    private String requiredMessageMetadata(Message message, String key) {
        String value = metadataValue(message, key);
        if (!hasText(value)) {
            throw new HitlResumeRejectedException("Missing HITL request field: " + key);
        }
        return value;
    }

    private String requiredCredential(String value) {
        if (!hasText(value)) {
            throw new HitlResumeRejectedException("Missing HITL request credential");
        }
        return value;
    }

    private String metadataValue(Message message, String key) {
        Object value =
                message == null || message.metadata() == null ? null : message.metadata().get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
