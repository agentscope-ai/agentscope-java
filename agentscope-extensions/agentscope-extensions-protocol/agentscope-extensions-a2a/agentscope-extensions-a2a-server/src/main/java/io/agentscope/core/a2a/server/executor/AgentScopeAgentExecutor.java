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

import io.a2a.A2A;
import io.a2a.server.ServerCallContext;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.server.tasks.TaskUpdater;
import io.a2a.spec.DataPart;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TextPart;
import io.agentscope.core.a2a.agent.utils.LoggerUtil;
import io.agentscope.core.a2a.server.constants.A2aServerConstants;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.utils.MessageConvertUtil;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionRule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/**
 * Implementation of A2A {@link AgentExecutor} for AgentScope.
 *
 * <p>For Current Implementation, will create a new {@link io.agentscope.core.agent.Agent} for each request.
 */
public class AgentScopeAgentExecutor implements AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeAgentExecutor.class);

    static final String CONFIRMATION_REQUEST_TYPE = "agentscope.confirmation_request";

    static final String CONFIRMATION_RESPONSE_TYPE = "agentscope.confirmation_response";

    private static final String CONFIRMATION_TYPE_KEY = "type";

    private final Map<String, Subscription> subscriptions;

    private final AgentRunner agentRunner;

    private final AgentExecuteProperties agentExecuteProperties;

    public AgentScopeAgentExecutor(
            AgentRunner agentRunner, AgentExecuteProperties agentExecuteProperties) {
        this.agentRunner = agentRunner;
        this.agentExecuteProperties = agentExecuteProperties;
        this.subscriptions = new ConcurrentHashMap<>();
    }

    @Override
    public void cancel(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
        try {
            log.info("[{}] Start to Cancel Task", context.getTaskId());
            TaskUpdater taskUpdater = new TaskUpdater(context, eventQueue);
            taskUpdater.cancel();
            agentRunner.stop(taskUpdater.getTaskId());
            Subscription subscription = subscriptions.get(taskUpdater.getTaskId());
            if (null == subscription) {
                log.warn("[{}] Not found Subscription for Task.", taskUpdater.getTaskId());
                return;
            }
            subscription.cancel();
        } catch (Exception e) {
            log.error("[{}] Error while cancelling task.", context.getTaskId(), e);
        }
    }

    @Override
    public void execute(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
        try {
            List<Msg> inputMessages;
            try {
                inputMessages = convertInputMessage(context);
            } catch (IllegalArgumentException e) {
                handleExecutionFailure(context, eventQueue, e);
                return;
            }
            AgentRequestOptions requestOptions = buildAgentRequestOptions(context);
            Flux<AgentEvent> resultFlux = agentRunner.streamEvents(inputMessages, requestOptions);

            Task task = context.getTask();
            if (task == null) {
                task = newTask(context.getMessage());
                log.info("[{}] Created new task.", task.getId());
            } else {
                log.info("[{}] Using existing task.", task.getId());
            }
            if (isBlockRequest(context)) {
                processTaskBlocking(context, eventQueue, task, resultFlux);
            } else {
                processTaskNonBlocking(context, eventQueue, task, resultFlux);
            }
            log.info("[{}] Agent execution completed successfully", context.getTaskId());
        } catch (Exception e) {
            log.error("[{}] Agent execution failed", context.getTaskId(), e);
            eventQueue.enqueueEvent(
                    A2A.createAgentTextMessage(
                            "Agent execution failed: " + e.getMessage(),
                            context.getContextId(),
                            context.getTaskId()));
        }
    }

    private void handleExecutionFailure(
            RequestContext context, EventQueue eventQueue, Exception error) {
        Task task = context.getTask();
        if (task != null
                && task.getStatus() != null
                && task.getStatus().state() == TaskState.INPUT_REQUIRED) {
            TaskUpdater taskUpdater = new TaskUpdater(context, eventQueue);
            try {
                taskUpdater.fail(
                        taskUpdater.newAgentMessage(
                                List.of(
                                        new TextPart(
                                                "Agent execution failed: " + error.getMessage())),
                                Map.of()));
            } finally {
                agentRunner.stop(task.getId());
            }
            return;
        }
        eventQueue.enqueueEvent(
                A2A.createAgentTextMessage(
                        "Agent execution failed: " + error.getMessage(),
                        context.getContextId(),
                        context.getTaskId()));
    }

    private List<Msg> convertInputMessage(RequestContext context) {
        Message request = context.getMessage();
        DataPart confirmationResponse = findControlPart(request, CONFIRMATION_RESPONSE_TYPE);
        Task task = context.getTask();
        boolean taskRequiresInput =
                task != null
                        && task.getStatus() != null
                        && task.getStatus().state() == TaskState.INPUT_REQUIRED;

        if (taskRequiresInput && confirmationResponse == null) {
            throw new IllegalArgumentException(
                    "This task requires an AgentScope confirmation_response DataPart.");
        }
        if (confirmationResponse != null && !taskRequiresInput) {
            throw new IllegalArgumentException(
                    "AgentScope confirmation responses are only valid for INPUT_REQUIRED tasks.");
        }
        if (confirmationResponse == null) {
            return MessageConvertUtil.convertFromMessageToMsgs(request);
        }

        PendingConfirmation pending = findPendingConfirmation(task);
        List<ConfirmResult> confirmationResults =
                parseConfirmationResults(confirmationResponse, pending);
        List<Part<?>> userParts =
                request.getParts().stream()
                        .filter(
                                part ->
                                        !(part instanceof DataPart dataPart
                                                && isControlPart(
                                                        dataPart, CONFIRMATION_RESPONSE_TYPE)))
                        .toList();
        Message userMessage = new Message.Builder(request).parts(userParts).build();
        List<Msg> inputMessages =
                new ArrayList<>(MessageConvertUtil.convertFromMessageToMsgs(userMessage));
        if (inputMessages.isEmpty()) {
            inputMessages.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, confirmationResults))
                            .build());
        } else {
            int lastIndex = inputMessages.size() - 1;
            Msg lastMessage = inputMessages.get(lastIndex);
            Map<String, Object> metadata =
                    lastMessage.getMetadata() == null
                            ? new HashMap<>()
                            : new HashMap<>(lastMessage.getMetadata());
            metadata.put(Msg.METADATA_CONFIRM_RESULTS, confirmationResults);
            inputMessages.set(lastIndex, lastMessage.withMetadata(metadata));
        }
        return inputMessages;
    }

    private static DataPart findControlPart(Message message, String expectedType) {
        if (message == null || message.getParts() == null) {
            return null;
        }
        List<DataPart> matchingParts =
                message.getParts().stream()
                        .filter(DataPart.class::isInstance)
                        .map(DataPart.class::cast)
                        .filter(part -> isControlPart(part, expectedType))
                        .toList();
        if (matchingParts.size() > 1) {
            throw new IllegalArgumentException(
                    "An A2A message must contain at most one " + expectedType + " DataPart.");
        }
        return matchingParts.isEmpty() ? null : matchingParts.get(0);
    }

    private static boolean isControlPart(DataPart dataPart, String expectedType) {
        return dataPart.getData() != null
                && expectedType.equals(dataPart.getData().get(CONFIRMATION_TYPE_KEY));
    }

    private static PendingConfirmation findPendingConfirmation(Task task) {
        Message statusMessage = task.getStatus().message();
        DataPart requestPart = findControlPart(statusMessage, CONFIRMATION_REQUEST_TYPE);
        if (requestPart == null) {
            throw new IllegalArgumentException(
                    "The INPUT_REQUIRED task does not contain an AgentScope confirmation request.");
        }
        Map<String, Object> requestData = requestPart.getData();
        String replyId = requiredString(requestData.get("replyId"), "replyId");
        Object rawToolCalls = requestData.get("toolCalls");
        if (!(rawToolCalls instanceof List<?> toolCalls) || toolCalls.isEmpty()) {
            throw new IllegalArgumentException("The confirmation request has no toolCalls.");
        }
        Map<String, Map<String, Object>> toolCallsById = new LinkedHashMap<>();
        for (Object rawToolCall : toolCalls) {
            Map<String, Object> toolCall = toStringMap(rawToolCall, "toolCall");
            String id = requiredString(toolCall.get("id"), "toolCall.id");
            if (toolCallsById.putIfAbsent(id, toolCall) != null) {
                throw new IllegalArgumentException("Duplicate toolCall id in request: " + id);
            }
        }
        return new PendingConfirmation(replyId, toolCallsById);
    }

    private static List<ConfirmResult> parseConfirmationResults(
            DataPart responsePart, PendingConfirmation pending) {
        Map<String, Object> responseData = responsePart.getData();
        if (!pending.replyId().equals(responseData.get("replyId"))) {
            throw new IllegalArgumentException(
                    "Confirmation replyId does not match the pending request.");
        }
        Object rawResults = responseData.get("results");
        if (!(rawResults instanceof List<?> results) || results.isEmpty()) {
            throw new IllegalArgumentException(
                    "Confirmation response must include non-empty results.");
        }

        Set<String> resultIds = new java.util.HashSet<>();
        List<ConfirmResult> confirmationResults = new ArrayList<>();
        for (Object rawResult : results) {
            Map<String, Object> result = toStringMap(rawResult, "result");
            String toolCallId = requiredString(result.get("toolCallId"), "result.toolCallId");
            Object rawConfirmed = result.get("confirmed");
            if (!(rawConfirmed instanceof Boolean confirmed)) {
                throw new IllegalArgumentException("result.confirmed must be a boolean.");
            }
            if (!resultIds.add(toolCallId)) {
                throw new IllegalArgumentException(
                        "Duplicate confirmation result for toolCallId: " + toolCallId);
            }
            Map<String, Object> requestedToolCall = pending.toolCallsById().get(toolCallId);
            if (requestedToolCall == null) {
                throw new IllegalArgumentException(
                        "Confirmation result references an unknown toolCallId: " + toolCallId);
            }

            Map<String, Object> toolCall = new LinkedHashMap<>(requestedToolCall);
            Object rawModifiedToolCall = result.get("toolCall");
            if (rawModifiedToolCall != null) {
                Map<String, Object> modifiedToolCall = toStringMap(rawModifiedToolCall, "toolCall");
                if (!toolCallId.equals(modifiedToolCall.get("id"))) {
                    throw new IllegalArgumentException(
                            "result.toolCall.id must match result.toolCallId.");
                }
                String requestedToolName =
                        requiredString(requestedToolCall.get("name"), "toolCall.name");
                String modifiedToolName =
                        requiredString(modifiedToolCall.get("name"), "toolCall.name");
                if (!requestedToolName.equals(modifiedToolName)) {
                    throw new IllegalArgumentException(
                            "result.toolCall.name must match the requested tool call.");
                }
                if (modifiedToolCall.containsKey("input")) {
                    toolCall.put("input", modifiedToolCall.get("input"));
                }
            }
            String reason = optionalString(result.get("reason"), "result.reason");
            List<PermissionRule> rules = parsePermissionRules(result.get("rules"));
            confirmationResults.add(
                    new ConfirmResult(
                            confirmed, toToolUseBlock(toolCall, toolCallId), rules, reason));
        }
        if (!resultIds.equals(pending.toolCallsById().keySet())) {
            Set<String> missingToolCallIds =
                    new java.util.HashSet<>(pending.toolCallsById().keySet());
            missingToolCallIds.removeAll(resultIds);
            throw new IllegalArgumentException(
                    "Confirmation response is missing results for toolCallIds: "
                            + missingToolCallIds);
        }
        return confirmationResults;
    }

    private static List<PermissionRule> parsePermissionRules(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> rules)) {
            throw new IllegalArgumentException("result.rules must be an array.");
        }
        List<PermissionRule> parsedRules = new ArrayList<>();
        for (Object rawRule : rules) {
            Map<String, Object> rule = toStringMap(rawRule, "result.rules entry");
            String behavior = requiredString(rule.get("behavior"), "rule.behavior");
            parsedRules.add(
                    new PermissionRule(
                            requiredString(rule.get("tool_name"), "rule.tool_name"),
                            optionalString(rule.get("rule_content"), "rule.rule_content"),
                            PermissionBehavior.fromString(behavior),
                            requiredString(rule.get("source"), "rule.source")));
        }
        return parsedRules;
    }

    private static ToolUseBlock toToolUseBlock(Map<String, Object> data, String expectedId) {
        String id = requiredString(data.get("id"), "toolCall.id");
        if (!expectedId.equals(id)) {
            throw new IllegalArgumentException("toolCall.id does not match the pending request.");
        }
        String name = requiredString(data.get("name"), "toolCall.name");
        return ToolUseBlock.builder()
                .id(id)
                .name(name)
                .input(optionalStringMap(data.get("input"), "toolCall.input"))
                .content(optionalString(data.get("content"), "toolCall.content"))
                .metadata(optionalStringMap(data.get("metadata"), "toolCall.metadata"))
                .build();
    }

    private static Map<String, Object> optionalStringMap(Object value, String field) {
        return value == null ? Map.of() : toStringMap(value, field);
    }

    private static Map<String, Object> toStringMap(Object value, String field) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException(field + " must be an object.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(field + " keys must be strings.");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static String optionalString(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof String string) {
            return string;
        }
        throw new IllegalArgumentException(field + " must be a string.");
    }

    private static String requiredString(Object value, String field) {
        String string = optionalString(value, field);
        if (string == null || string.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return string;
    }

    private record PendingConfirmation(
            String replyId, Map<String, Map<String, Object>> toolCallsById) {}

    private AgentRequestOptions buildAgentRequestOptions(RequestContext context) {
        Message message = context.getParams().message();
        AgentRequestOptions requestOptions = new AgentRequestOptions();
        requestOptions.setTaskId(context.getTaskId());
        requestOptions.setUserId(getUserId(message));
        requestOptions.setSessionId(getSessionId(message));
        Task task = context.getTask();
        requestOptions.setResume(
                task != null
                        && task.getStatus() != null
                        && task.getStatus().state() == TaskState.INPUT_REQUIRED);
        return requestOptions;
    }

    private String getUserId(Message message) {
        if (message.getMetadata() != null && message.getMetadata().containsKey("userId")) {
            return String.valueOf(message.getMetadata().get("userId"));
        }
        return "";
    }

    private String getSessionId(Message message) {
        if (message.getMetadata() != null && message.getMetadata().containsKey("sessionId")) {
            return String.valueOf(message.getMetadata().get("sessionId"));
        }
        return "";
    }

    private Task newTask(Message request) {
        String contextId = request.getContextId();
        String taskId = request.getTaskId();
        return new Task(
                taskId,
                contextId,
                new TaskStatus(TaskState.SUBMITTED),
                null,
                List.of(request),
                null);
    }

    private boolean isBlockRequest(RequestContext context) {
        // Streaming request must non-block.
        ServerCallContext callContext = context.getCallContext();
        Object isStreaming =
                callContext
                        .getState()
                        .getOrDefault(A2aServerConstants.ContextKeys.IS_STREAM_KEY, Boolean.FALSE);
        if (Boolean.TRUE.equals(isStreaming)) {
            return false;
        }
        if (null == context.getParams().configuration()) {
            return true;
        }
        return Boolean.TRUE.equals(context.getParams().configuration().blocking());
    }

    private void processTaskBlocking(
            RequestContext context, EventQueue eventQueue, Task task, Flux<AgentEvent> resultFlux) {
        BlockingFluxEventHandler eventHandler =
                new BlockingFluxEventHandler(context, agentExecuteProperties, task, eventQueue);
        log.info("[{}] Starting blocking request processing", context.getTaskId());
        resultFlux
                .doOnSubscribe(s -> saveSubscription(context.getTaskId(), s))
                .doOnNext(eventHandler::doOnNext)
                .doOnComplete(eventHandler::doOnComplete)
                .doOnError(eventHandler::doOnError)
                .doFinally(signal -> removeSubscription(context.getTaskId(), signal))
                .blockLast();
    }

    private void processTaskNonBlocking(
            RequestContext context, EventQueue eventQueue, Task task, Flux<AgentEvent> resultFlux) {
        TaskUpdater taskUpdater = new TaskUpdater(context, eventQueue);
        try {
            if (context.getTask() == null) {
                eventQueue.enqueueEvent(task);
            }
            log.info("[{}] Starting streaming request processing", context.getTaskId());
            processStreamingOutput(resultFlux, taskUpdater, context);
        } catch (Exception e) {
            log.error("[{}] Error processing streaming output", context.getTaskId(), e);
            try {
                taskUpdater.fail(
                        taskUpdater.newAgentMessage(
                                List.of(
                                        new TextPart(
                                                "Error processing streaming output: "
                                                        + e.getMessage())),
                                Map.of()));
            } catch (IllegalStateException ignored) {
                // doOnError already transitioned the task to a terminal state; nothing to do.
            }
        }
    }

    /**
     * Process streaming output data
     */
    private void processStreamingOutput(
            Flux<AgentEvent> resultFlux, TaskUpdater taskUpdater, RequestContext context) {
        StreamingFluxEventHandler eventHandler =
                new StreamingFluxEventHandler(context, agentExecuteProperties, taskUpdater);
        resultFlux
                .doOnSubscribe(
                        s -> {
                            saveSubscription(taskUpdater.getTaskId(), s);
                            taskUpdater.startWork();
                        })
                .doOnNext(eventHandler::doOnNext)
                .doOnComplete(eventHandler::doOnComplete)
                .doOnError(eventHandler::doOnError)
                .doFinally(signal -> removeSubscription(taskUpdater.getTaskId(), signal))
                .blockLast();
    }

    private void saveSubscription(String taskId, Subscription subscription) {
        log.info("[{}] Subscribed to executeFunction result stream", taskId);
        subscriptions.put(taskId, subscription);
    }

    private void removeSubscription(String taskId, SignalType signal) {
        log.info("[{}] Subscribe and process stream output terminated: {}", taskId, signal);
        subscriptions.remove(taskId);
    }

    private abstract static class BaseFluxEventHandler {

        protected final RequestContext context;

        protected final List<Msg> accumulatedOutput;

        protected final AgentExecuteProperties executeProperties;

        private final Set<AgentEventType> requiredEventTypes;

        private final AtomicBoolean waitingForInput;

        private BaseFluxEventHandler(
                RequestContext context, AgentExecuteProperties executeProperties) {
            this.context = context;
            this.executeProperties = executeProperties;
            this.accumulatedOutput = new LinkedList<>();
            this.requiredEventTypes = generateRequiredEventTypes(executeProperties);
            this.waitingForInput = new AtomicBoolean();
        }

        private Set<AgentEventType> generateRequiredEventTypes(
                AgentExecuteProperties executeProperties) {
            if (executeProperties.isRequireInnerMessage()) {
                return Set.of(
                        AgentEventType.TEXT_BLOCK_DELTA,
                        AgentEventType.THINKING_BLOCK_DELTA,
                        AgentEventType.TOOL_RESULT_TEXT_DELTA,
                        AgentEventType.TOOL_RESULT_DATA_DELTA,
                        AgentEventType.HINT_BLOCK);
            }
            return Set.of(AgentEventType.TEXT_BLOCK_DELTA, AgentEventType.THINKING_BLOCK_DELTA);
        }

        /**
         * Template for Flux doOnNext to handle event.
         *
         * @param output output event from agent stream execute.
         */
        void doOnNext(AgentEvent output) {
            LoggerUtil.debug(
                    log, "[{}] Handle Agent execute output event: {}", context.getTaskId(), output);
            if (output instanceof RequireUserConfirmEvent request) {
                if (!waitingForInput.compareAndSet(false, true)) {
                    throw new IllegalStateException(
                            "An agent stream requested user confirmation more than once.");
                }
                handleInputRequired(request);
                return;
            }
            Msg responseMessage = convertToResponseMessage(output);
            if (responseMessage != null) {
                accumulatedOutput.add(responseMessage);
            }
            handleEvent(output, responseMessage);
        }

        protected final boolean isWaitingForInput() {
            return waitingForInput.get();
        }

        protected abstract void handleInputRequired(RequireUserConfirmEvent request);

        /**
         * Handle agent execute complete with Flux doOnComplete.
         */
        abstract void doOnComplete();

        /**
         * Handle agent execute error with Flux doOnError.
         *
         * @param t the error during Flux execution
         */
        void doOnError(Throwable t) {
            log.error("[{}] Handle Agent execute error: ", context.getTaskId(), t);
            String errorMessage = "Handle Agent execute error: " + t.getMessage();
            sendErrorMessage(
                    A2A.createAgentTextMessage(
                            errorMessage, context.getContextId(), context.getTaskId()));
        }

        /**
         * Determines whether the given event should not be sent as a response to the A2A client,
         * for example, lifecycle events or inner events disabled by configuration.
         *
         * @param output agent output event
         * @return {@code true} if the event should not be responded to, otherwise {@code false}.
         */
        protected boolean isNoResponseEvent(AgentEvent output) {
            return !requiredEventTypes.contains(output.getType());
        }

        private Msg convertToResponseMessage(AgentEvent output) {
            if (isNoResponseEvent(output)) {
                return null;
            }
            if (output instanceof TextBlockDeltaEvent event) {
                return assistantMessage(
                        event,
                        event.getReplyId(),
                        TextBlock.builder().text(event.getDelta()).build());
            }
            if (output instanceof ThinkingBlockDeltaEvent event) {
                return assistantMessage(
                        event,
                        event.getReplyId(),
                        ThinkingBlock.builder().thinking(event.getDelta()).build());
            }
            if (output instanceof ToolResultTextDeltaEvent event) {
                return toolResultMessage(
                        event,
                        event.getReplyId(),
                        ToolResultBlock.builder()
                                .id(event.getToolCallId())
                                .name(event.getToolCallName())
                                .output(TextBlock.builder().text(event.getDelta()).build())
                                .metadata(event.getMetadata())
                                .build());
            }
            if (output instanceof ToolResultDataDeltaEvent event) {
                ContentBlock data = event.getData();
                if (data == null) {
                    return null;
                }
                return toolResultMessage(
                        event,
                        event.getReplyId(),
                        ToolResultBlock.builder()
                                .id(event.getToolCallId())
                                .name(event.getToolCallName())
                                .output(data)
                                .metadata(event.getMetadata())
                                .build());
            }
            if (output instanceof HintBlockEvent event) {
                return assistantMessage(
                        event,
                        event.getReplyId(),
                        new HintBlock(event.getBlockId(), event.getHint(), event.getHintSource()));
            }
            LoggerUtil.warn(
                    log,
                    "[{}] Event passed required check but matched no conversion branch: {} ({})",
                    context.getTaskId(),
                    output.getType(),
                    output.getClass().getName());
            return null;
        }

        private Msg assistantMessage(AgentEvent event, String replyId, ContentBlock contentBlock) {
            return Msg.builder()
                    .id(replyId)
                    .role(MsgRole.ASSISTANT)
                    .content(contentBlock)
                    .metadata(event.getMetadata())
                    .build();
        }

        private Msg toolResultMessage(
                AgentEvent event, String replyId, ToolResultBlock toolResultBlock) {
            return Msg.builder()
                    .id(replyId)
                    .role(MsgRole.TOOL)
                    .content(toolResultBlock)
                    .metadata(event.getMetadata())
                    .build();
        }

        /**
         * Handle the event.
         *
         * @param output output event from agent stream execute.
         * @param responseMessage converted response message, or {@code null} if the event should
         *     not be sent to the A2A client.
         */
        protected abstract void handleEvent(AgentEvent output, Msg responseMessage);

        /**
         * Send error message to A2A Client.
         *
         * @param errorMessage error message to send to A2A Client.
         */
        protected abstract void sendErrorMessage(Message errorMessage);
    }

    private static class BlockingFluxEventHandler extends BaseFluxEventHandler {

        private final AtomicReference<Message> resultMessageRef;

        private final EventQueue eventQueue;

        private final Task task;

        private final TaskUpdater taskUpdater;

        private BlockingFluxEventHandler(
                RequestContext context,
                AgentExecuteProperties executeProperties,
                Task task,
                EventQueue eventQueue) {
            super(context, executeProperties);
            this.eventQueue = eventQueue;
            this.task = task;
            this.taskUpdater = new TaskUpdater(context, eventQueue);
            this.resultMessageRef = new AtomicReference<>();
        }

        @Override
        void doOnComplete() {
            if (isWaitingForInput()) {
                return;
            }
            log.info(
                    "[{}] Process agent output for blocking request completed.",
                    context.getTaskId());
            Message resultMessage =
                    null != resultMessageRef.get()
                            ? resultMessageRef.get()
                            : MessageConvertUtil.convertFromMsgToMessage(
                                    MessageConvertUtil.compactStreamingChunks(accumulatedOutput),
                                    context.getTaskId(),
                                    context.getContextId());
            eventQueue.enqueueEvent(resultMessage);
        }

        @Override
        protected void handleInputRequired(RequireUserConfirmEvent request) {
            if (context.getTask() == null) {
                eventQueue.enqueueEvent(task);
            }
            taskUpdater.startWork();
            taskUpdater.requiresInput(confirmationRequestMessage(taskUpdater, request));
        }

        @Override
        protected void handleEvent(AgentEvent output, Msg responseMessage) {
            if (!(output instanceof AgentResultEvent resultEvent)) {
                // Non-AGENT_RESULT messages should be ignored and saved into accumulatedOutput
                // according to properties.
                return;
            }
            Msg outputMessage = resultEvent.getResult();
            Message message =
                    MessageConvertUtil.convertFromMsgToMessage(
                            outputMessage, context.getTaskId(), context.getContextId());
            resultMessageRef.set(message);
        }

        @Override
        protected void sendErrorMessage(Message errorMessage) {
            if (isWaitingForInput()) {
                taskUpdater.fail(errorMessage);
            } else {
                eventQueue.enqueueEvent(errorMessage);
            }
        }
    }

    private static class StreamingFluxEventHandler extends BaseFluxEventHandler {

        private final TaskUpdater taskUpdater;

        private final String artifactId;

        private final AtomicBoolean isFirstArtifact;

        private StreamingFluxEventHandler(
                RequestContext context,
                AgentExecuteProperties executeProperties,
                TaskUpdater taskUpdater) {
            super(context, executeProperties);
            this.taskUpdater = taskUpdater;
            this.artifactId = UUID.randomUUID().toString();
            this.isFirstArtifact = new AtomicBoolean(true);
        }

        @Override
        void doOnComplete() {
            if (isWaitingForInput()) {
                return;
            }
            log.info(
                    "[{}] Process agent output for non-blocking request completed.",
                    taskUpdater.getTaskId());
            Message completeMessage =
                    executeProperties.isCompleteWithMessage()
                            ? MessageConvertUtil.convertFromMsgToMessage(
                                    MessageConvertUtil.compactStreamingChunks(accumulatedOutput),
                                    taskUpdater.getTaskId(),
                                    taskUpdater.getContextId())
                            : null;
            taskUpdater.complete(completeMessage);
        }

        @Override
        protected void handleInputRequired(RequireUserConfirmEvent request) {
            taskUpdater.requiresInput(confirmationRequestMessage(taskUpdater, request));
        }

        @Override
        protected void handleEvent(AgentEvent output, Msg responseMessage) {
            if (responseMessage == null) {
                return;
            }
            List<Part<?>> responseParts =
                    MessageConvertUtil.convertFromContentBlocks(
                            responseMessage, isStreamingChunk(output));
            taskUpdater.addArtifact(
                    responseParts,
                    artifactId,
                    "agent-response",
                    responseMessage.getMetadata(),
                    !isFirstArtifact.getAndSet(false),
                    false);
        }

        private boolean isStreamingChunk(AgentEvent output) {
            return output instanceof TextBlockDeltaEvent
                    || output instanceof ThinkingBlockDeltaEvent
                    || output instanceof ToolResultTextDeltaEvent
                    || output instanceof ToolResultDataDeltaEvent;
        }

        @Override
        protected void sendErrorMessage(Message errorMessage) {
            taskUpdater.fail(errorMessage);
        }
    }

    private static Message confirmationRequestMessage(
            TaskUpdater taskUpdater, RequireUserConfirmEvent request) {
        String replyId = requiredString(request.getReplyId(), "RequireUserConfirmEvent.replyId");
        if (request.getToolCalls().isEmpty()) {
            throw new IllegalStateException("RequireUserConfirmEvent did not include tool calls.");
        }
        List<Map<String, Object>> toolCalls =
                request.getToolCalls().stream()
                        .map(AgentScopeAgentExecutor::confirmationToolCallData)
                        .toList();
        Map<String, Object> data =
                Map.of(
                        CONFIRMATION_TYPE_KEY,
                        CONFIRMATION_REQUEST_TYPE,
                        "replyId",
                        replyId,
                        "toolCalls",
                        toolCalls);
        return taskUpdater.newAgentMessage(
                List.of(
                        new TextPart("User confirmation is required before these tool calls run."),
                        new DataPart(data)),
                Map.of());
    }

    private static Map<String, Object> confirmationToolCallData(ToolUseBlock toolCall) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", requiredString(toolCall.getId(), "toolCall.id"));
        data.put("name", requiredString(toolCall.getName(), "toolCall.name"));
        data.put("input", toolCall.getInput());
        if (toolCall.getContent() != null) {
            data.put("content", toolCall.getContent());
        }
        if (!toolCall.getMetadata().isEmpty()) {
            data.put("metadata", toolCall.getMetadata());
        }
        return data;
    }
}
