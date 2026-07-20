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

import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskNotFoundError;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys;

/** Internal, normalized request coordinates shared by admission and execution. */
public final class ResolvedAgentRequestMetadata {

    private final MessageSendParams requestParams;
    private final String taskId;
    private final String contextId;
    private final AgentRequestOptions requestOptions;
    private final HitlExecutionKey executionKey;
    private final String operation;
    private final String resumeToken;
    private final String nextResumeToken;
    private final boolean freshTask;

    private ResolvedAgentRequestMetadata(
            MessageSendParams requestParams,
            String taskId,
            String contextId,
            AgentRequestOptions requestOptions,
            HitlExecutionKey executionKey,
            String operation,
            String resumeToken,
            String nextResumeToken,
            boolean freshTask) {
        this.requestParams = requestParams;
        this.taskId = taskId;
        this.contextId = contextId;
        this.requestOptions = requestOptions;
        this.executionKey = executionKey;
        this.operation = operation;
        this.resumeToken = resumeToken;
        this.nextResumeToken = nextResumeToken;
        this.freshTask = freshTask;
    }

    public static ResolvedAgentRequestMetadata resolve(
            MessageSendParams params,
            ServerCallContext callContext,
            TaskStore taskStore,
            String logicalAgentId) {
        if (params == null || params.message() == null) {
            throw new InvalidParamsError("message is required");
        }
        Message original = params.message();
        String requestedTaskId = trimToNull(original.taskId());
        Task existing = requestedTaskId == null ? null : taskStore.get(requestedTaskId);
        if (requestedTaskId != null && existing == null) {
            throw new TaskNotFoundError();
        }
        if (existing != null) {
            boolean strict = strictContextValidation(callContext);
            String requestedContextId = trimToNull(original.contextId());
            if (strict
                    && requestedContextId != null
                    && !requestedContextId.equals(existing.contextId())) {
                throw new InvalidParamsError("Message has a mismatched context ID");
            }
            if (existing.status() != null
                    && existing.status().state() != null
                    && existing.status().state().isFinal()) {
                throw new UnsupportedOperationError(
                        null, "Cannot send message to a task in terminal state", null);
            }
        }

        boolean freshTask = requestedTaskId == null;
        String taskId = requestedTaskId;
        String contextId =
                existing != null
                        ? existing.contextId()
                        : firstText(original.contextId(), UUID.randomUUID().toString());
        Message normalizedMessage = original;
        if (!Objects.equals(taskId, original.taskId()) || !contextId.equals(original.contextId())) {
            normalizedMessage =
                    Message.builder(original).taskId(taskId).contextId(contextId).build();
        }
        MessageSendParams normalizedParams =
                MessageSendParams.builder()
                        .message(normalizedMessage)
                        .configuration(params.configuration())
                        .metadata(params.metadata())
                        .tenant(params.tenant())
                        .build();
        String sessionId = sessionId(original, params.metadata(), contextId);
        return create(
                normalizedParams,
                taskId,
                contextId,
                callContext,
                logicalAgentId,
                sessionId,
                freshTask);
    }

    public static ResolvedAgentRequestMetadata resolve(
            RequestContext context, String logicalAgentId) {
        Message message = context.getMessage();
        MessageSendParams params =
                MessageSendParams.builder()
                        .message(message)
                        .configuration(context.getConfiguration())
                        .metadata(context.getMetadata())
                        .tenant(context.getTenant())
                        .build();
        return create(
                params,
                context.getTaskId(),
                context.getContextId(),
                context.getCallContext(),
                logicalAgentId,
                sessionId(message, context.getMetadata(), context.getContextId()),
                false);
    }

    private static ResolvedAgentRequestMetadata create(
            MessageSendParams params,
            String taskId,
            String contextId,
            ServerCallContext callContext,
            String logicalAgentId,
            String sessionId,
            boolean freshTask) {
        Message message = params.message();
        AgentRequestOptions options = new AgentRequestOptions();
        options.setTaskId(taskId);
        options.setUserId(
                nullIfBlank(
                        firstText(
                                metadataValue(message.metadata(), "userId"),
                                metadataValue(params.metadata(), "userId"))));
        options.setSessionId(sessionId);
        options.setAgentId(
                firstText(
                        metadataValue(message.metadata(), "agentId"),
                        metadataValue(params.metadata(), "agentId"),
                        metadataValue(message.metadata(), "agent_id"),
                        metadataValue(params.metadata(), "agent_id")));
        options.setMetadata(mergedSafeMetadata(params, message));
        options.setHeaders(headers(callContext));
        String agentId = firstText(logicalAgentId, "agent");
        HitlExecutionKey key =
                new HitlExecutionKey(options.getUserId(), agentId, options.getSessionId());
        return new ResolvedAgentRequestMetadata(
                params,
                taskId,
                contextId,
                options,
                key,
                metadataValue(message.metadata(), MessageConstants.HITL_OPERATION_METADATA_KEY),
                metadataValue(params.metadata(), MessageConstants.RESUME_TOKEN_METADATA_KEY),
                metadataValue(params.metadata(), MessageConstants.NEXT_RESUME_TOKEN_METADATA_KEY),
                freshTask);
    }

    private static boolean strictContextValidation(ServerCallContext callContext) {
        if (callContext == null || callContext.getState() == null) {
            return true;
        }
        Object configured =
                callContext.getState().get(ServerCallContext.STRICT_CONTEXT_VALIDATION_KEY);
        return !(configured instanceof Boolean value) || value;
    }

    private static Map<String, Object> mergedSafeMetadata(
            MessageSendParams params, Message message) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (params.metadata() != null) {
            metadata.putAll(params.metadata());
        }
        if (message.metadata() != null) {
            metadata.putAll(message.metadata());
        }
        return io.agentscope.core.a2a.agent.utils.MessageConvertUtil.stripSensitiveMetadata(
                metadata);
    }

    private static Map<String, String> headers(ServerCallContext callContext) {
        if (callContext == null || callContext.getState() == null) {
            return Map.of();
        }
        Object raw = callContext.getState().get(JSONRPCContextKeys.HEADERS_KEY);
        if (!(raw instanceof Map<?, ?> values)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach(
                (key, value) -> {
                    if (key != null && value != null) {
                        result.put(String.valueOf(key), String.valueOf(value));
                    }
                });
        return Map.copyOf(result);
    }

    private static String metadataValue(Map<String, Object> metadata, String key) {
        Object value = metadata == null ? null : metadata.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String sessionId(
            Message message, Map<String, Object> requestMetadata, String fallbackContextId) {
        return firstText(
                message == null ? "" : message.contextId(),
                message == null ? "" : metadataValue(message.metadata(), "sessionId"),
                metadataValue(requestMetadata, "sessionId"),
                message == null ? "" : metadataValue(message.metadata(), "threadId"),
                metadataValue(requestMetadata, "threadId"),
                message == null ? "" : metadataValue(message.metadata(), "thread_id"),
                metadataValue(requestMetadata, "thread_id"),
                fallbackContextId);
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String trimToNull(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public MessageSendParams requestParams() {
        return requestParams;
    }

    public AgentRequestOptions requestOptions() {
        return requestOptions;
    }

    public HitlExecutionKey executionKey() {
        return executionKey;
    }

    public String operation() {
        return operation;
    }

    public String resumeToken() {
        return resumeToken;
    }

    public String nextResumeToken() {
        return nextResumeToken;
    }

    public String taskId() {
        return taskId;
    }

    public String contextId() {
        return contextId;
    }

    boolean matches(RequestContext context) {
        if (context == null || !contextId.equals(context.getContextId())) {
            return false;
        }
        if (!freshTask) {
            return taskId != null && taskId.equals(context.getTaskId());
        }
        return context.getTask() == null
                && context.getTaskId() != null
                && !context.getTaskId().isBlank();
    }

    void bind(RequestContext context) {
        if (freshTask) {
            requestOptions.setTaskId(context.getTaskId());
        }
    }

    boolean freshTask() {
        return freshTask;
    }
}
