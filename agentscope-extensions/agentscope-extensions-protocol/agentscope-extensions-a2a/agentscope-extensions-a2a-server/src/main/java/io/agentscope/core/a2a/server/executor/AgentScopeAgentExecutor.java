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

import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.a2a.agent.utils.LoggerUtil;
import io.agentscope.core.a2a.server.constants.A2aServerConstants;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.utils.MessageConvertUtil;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolProgressEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;

/**
 * Implementation of A2A {@link AgentExecutor} for AgentScope.
 *
 * <p>For Current Implementation, will create a new {@link io.agentscope.core.agent.Agent} for each request.
 */
public class AgentScopeAgentExecutor implements AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeAgentExecutor.class);

    /** Ends the execution of each running task when the task is cancelled. */
    private final ConcurrentMap<String, Sinks.Empty<Void>> cancelSignals;

    private final AgentRunner agentRunner;

    private final AgentExecuteProperties agentExecuteProperties;

    /** Deployment-side adjudication of the user identity, or {@code null} to trust the envelope. */
    private final A2aContextResolver contextResolver;

    /** Whether the unverified-identity warning was logged already; it fires once per executor. */
    private final AtomicBoolean unverifiedIdentityWarned = new AtomicBoolean();

    public AgentScopeAgentExecutor(
            AgentRunner agentRunner, AgentExecuteProperties agentExecuteProperties) {
        this(agentRunner, agentExecuteProperties, null);
    }

    public AgentScopeAgentExecutor(
            AgentRunner agentRunner,
            AgentExecuteProperties agentExecuteProperties,
            A2aContextResolver contextResolver) {
        this.agentRunner = agentRunner;
        this.agentExecuteProperties = agentExecuteProperties;
        this.contextResolver = contextResolver;
        this.cancelSignals = new ConcurrentHashMap<>();
    }

    @Override
    public void cancel(RequestContext context, AgentEmitter emitter) throws A2AError {
        String taskId = context.getTaskId();
        Sinks.Empty<Void> cancelSignal = cancelSignals.remove(taskId);
        try {
            log.info("[{}] Start to Cancel Task", taskId);
            emitter.cancel();
            agentRunner.stop(taskId);
        } catch (Exception e) {
            log.error("[{}] Error while cancelling task.", context.getTaskId(), e);
        } finally {
            // End the execution even if stop() failed or left the agent stream running.
            if (null == cancelSignal) {
                log.warn("[{}] Not found Subscription for Task.", taskId);
            } else {
                cancelSignal.tryEmitEmpty();
            }
        }
    }

    @Override
    public void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
        try {
            List<Msg> inputMessages =
                    MessageConvertUtil.convertFromMessageToMsgs(context.getMessage());
            if (isWaitingForInput(context.getTask())) {
                Message pending = pendingRequest(context.getTask());
                if (!confirmsPendingToolCalls(inputMessages, pending)) {
                    // The agent would reject the run and the task could not be confirmed any
                    // more. Repeat the pending request instead.
                    log.info(
                            "[{}] Task still waits for a confirmation of its tool calls.",
                            context.getTaskId());
                    emitter.requiresInput(pending);
                    return;
                }
            }
            AgentRequestOptions requestOptions = buildAgentRequestOptions(context);
            Flux<AgentEvent> resultFlux = agentRunner.streamEvents(inputMessages, requestOptions);

            Task task = context.getTask();
            if (task == null) {
                task = newTask(context);
                log.info("[{}] Created new task.", task.id());
            } else {
                log.info("[{}] Using existing task.", task.id());
            }
            if (isBlockRequest(context)) {
                processTaskBlocking(context, emitter, task, resultFlux);
            } else {
                processTaskNonBlocking(context, emitter, task, resultFlux);
            }
            log.info("[{}] Agent execution completed successfully", context.getTaskId());
        } catch (Exception e) {
            log.error("[{}] Agent execution failed", context.getTaskId(), e);
            emitter.sendMessage("Agent execution failed: " + e.getMessage());
        }
    }

    private AgentRequestOptions buildAgentRequestOptions(RequestContext context) {
        Message message = context.getMessage();
        AgentRequestOptions requestOptions = new AgentRequestOptions();
        requestOptions.setTaskId(context.getTaskId());
        requestOptions.setUserId(resolveUserId(context, message));
        requestOptions.setSessionId(getSessionId(context));
        return requestOptions;
    }

    /**
     * Resolves the user id of the call: the resolver's transport-verified identity wins over the
     * envelope-asserted one, which wins over none. See {@link A2aContextResolver}.
     */
    private String resolveUserId(RequestContext context, Message message) {
        String asserted = getUserId(message);
        if (null == contextResolver) {
            if (!asserted.isBlank() && unverifiedIdentityWarned.compareAndSet(false, true)) {
                log.warn(
                        "Using the envelope-asserted user id of A2A messages without a configured"
                                + " {} — the identity is unverified and any caller can claim any"
                                + " user id. Configure AgentScopeA2aServer.Builder#contextResolver"
                                + " when the transport verifies callers.",
                        A2aContextResolver.class.getSimpleName());
            }
            return asserted;
        }
        RuntimeContext adjudicated = contextResolver.resolve(context);
        if (null == adjudicated
                || null == adjudicated.getUserId()
                || adjudicated.getUserId().isBlank()) {
            return asserted;
        }
        String verified = adjudicated.getUserId();
        if (!asserted.isBlank() && !verified.equals(asserted)) {
            log.warn(
                    "[{}] Transport-verified user id '{}' overrides the envelope-asserted '{}'.",
                    context.getTaskId(),
                    verified,
                    asserted);
        }
        return verified;
    }

    private String getUserId(Message message) {
        if (message.metadata() != null) {
            Object envelopeValue = message.metadata().get(MessageConstants.USER_ID_METADATA_KEY);
            if (envelopeValue != null) {
                return String.valueOf(envelopeValue);
            }
            // Legacy flat key from callers that predate the envelope key; deprecated.
            Object legacyValue = message.metadata().get("userId");
            if (legacyValue != null) {
                return String.valueOf(legacyValue);
            }
        }
        return "";
    }

    private String getSessionId(RequestContext context) {
        Message message = context.getMessage();
        Object legacySessionId =
                message.metadata() == null ? null : message.metadata().get("sessionId");
        if (legacySessionId != null) {
            String sessionId = String.valueOf(legacySessionId);
            if (!sessionId.isBlank()) {
                return sessionId;
            }
        }
        String contextId = context.getContextId();
        return contextId == null ? "" : contextId;
    }

    private Task newTask(RequestContext context) {
        return new Task(
                context.getTaskId(),
                context.getContextId(),
                new TaskStatus(TaskState.TASK_STATE_SUBMITTED),
                null,
                List.of(context.getMessage()),
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
        if (null == context.getConfiguration()) {
            return true;
        }
        return !Boolean.TRUE.equals(context.getConfiguration().returnImmediately());
    }

    private void processTaskBlocking(
            RequestContext context, AgentEmitter emitter, Task task, Flux<AgentEvent> resultFlux) {
        BlockingFluxEventHandler eventHandler =
                new BlockingFluxEventHandler(context, agentExecuteProperties, emitter);
        Sinks.Empty<Void> cancelSignal = Sinks.empty();
        log.info("[{}] Starting blocking request processing", context.getTaskId());
        resultFlux
                .doOnSubscribe(s -> saveCancelSignal(context.getTaskId(), cancelSignal))
                .doOnNext(eventHandler::doOnNext)
                .doOnComplete(eventHandler::doOnComplete)
                .doOnError(eventHandler::doOnError)
                .doFinally(signal -> removeCancelSignal(context.getTaskId(), cancelSignal, signal))
                .takeUntilOther(cancelSignal.asMono())
                .blockLast();
    }

    private void processTaskNonBlocking(
            RequestContext context, AgentEmitter emitter, Task task, Flux<AgentEvent> resultFlux) {
        try {
            // The first event is what a returnImmediately caller gets back; a resumed task is no
            // longer waiting for input.
            emitter.addTask(
                    isWaitingForInput(task)
                            ? Task.builder(task)
                                    .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
                                    .build()
                            : task);
            log.info("[{}] Starting streaming request processing", context.getTaskId());
            processStreamingOutput(resultFlux, emitter, context);
        } catch (Exception e) {
            log.error("[{}] Error processing streaming output", context.getTaskId(), e);
            try {
                emitter.fail(
                        emitter.newAgentMessage(
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
            Flux<AgentEvent> resultFlux, AgentEmitter emitter, RequestContext context) {
        StreamingFluxEventHandler eventHandler =
                new StreamingFluxEventHandler(context, agentExecuteProperties, emitter);
        Sinks.Empty<Void> cancelSignal = Sinks.empty();
        resultFlux
                .doOnSubscribe(
                        s -> {
                            saveCancelSignal(context.getTaskId(), cancelSignal);
                            emitter.startWork();
                        })
                .doOnNext(eventHandler::doOnNext)
                .doOnComplete(eventHandler::doOnComplete)
                .doOnError(eventHandler::doOnError)
                .doFinally(signal -> removeCancelSignal(context.getTaskId(), cancelSignal, signal))
                .takeUntilOther(cancelSignal.asMono())
                .blockLast();
    }

    private void saveCancelSignal(String taskId, Sinks.Empty<Void> cancelSignal) {
        log.info("[{}] Subscribed to executeFunction result stream", taskId);
        cancelSignals.put(taskId, cancelSignal);
    }

    /**
     * Runs when the agent stream ends, also when a cancel ends it: {@code takeUntilOther} cancels
     * the stream and completes the downstream, so {@code blockLast()} returns. Cancelling only the
     * stream would leave {@code blockLast()} waiting for a terminal signal that never comes.
     */
    private void removeCancelSignal(
            String taskId, Sinks.Empty<Void> cancelSignal, SignalType signal) {
        log.info("[{}] Subscribe and process stream output terminated: {}", taskId, signal);
        cancelSignals.remove(taskId, cancelSignal);
    }

    /** Whether the run stopped to ask the user to confirm tool calls instead of finishing. */
    private static boolean isWaitingForConfirmation(Msg result) {
        return result != null && result.getGenerateReason() == GenerateReason.PERMISSION_ASKING;
    }

    private static boolean isWaitingForInput(Task task) {
        return task != null && task.status().state() == TaskState.TASK_STATE_INPUT_REQUIRED;
    }

    /**
     * Finds the request a paused task waits on. A follow-up moves the status message of the task
     * into its history, so the request is the latest agent message there.
     */
    private static Message pendingRequest(Task task) {
        List<Message> history = task.history();
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).role() == Message.Role.ROLE_AGENT) {
                return history.get(i);
            }
        }
        return null;
    }

    /**
     * Whether the messages confirm at least one tool call and only tool calls that the pending
     * request asks about.
     */
    private static boolean confirmsPendingToolCalls(List<Msg> messages, Message pending) {
        List<?> confirmedIds =
                messages.stream()
                        .map(Msg::getMetadata)
                        .filter(Objects::nonNull)
                        .map(metadata -> metadata.get(Msg.METADATA_CONFIRM_RESULTS))
                        .filter(List.class::isInstance)
                        .flatMap(results -> ((List<?>) results).stream())
                        .map(
                                result ->
                                        result instanceof Map<?, ?> map
                                                        && map.get("toolCall")
                                                                instanceof Map<?, ?> toolCall
                                                ? toolCall.get("id")
                                                : null)
                        .toList();
        if (confirmedIds.isEmpty() || pending == null) {
            return !confirmedIds.isEmpty();
        }
        Set<Object> pendingIds =
                pending.parts().stream()
                        .filter(DataPart.class::isInstance)
                        .map(part -> ((DataPart) part).metadata())
                        .filter(Objects::nonNull)
                        .map(metadata -> metadata.get(MessageConstants.TOOL_CALL_ID_METADATA_KEY))
                        .collect(Collectors.toSet());
        return pendingIds.containsAll(confirmedIds);
    }

    private abstract static class BaseFluxEventHandler {

        protected final RequestContext context;

        protected final List<Msg> accumulatedOutput;

        protected final AgentExecuteProperties executeProperties;

        private final Set<AgentEventType> requiredEventTypes;

        private BaseFluxEventHandler(
                RequestContext context, AgentExecuteProperties executeProperties) {
            this.context = context;
            this.executeProperties = executeProperties;
            this.accumulatedOutput = new LinkedList<>();
            this.requiredEventTypes = generateRequiredEventTypes(executeProperties);
        }

        private Set<AgentEventType> generateRequiredEventTypes(
                AgentExecuteProperties executeProperties) {
            if (executeProperties.isRequireInnerMessage()) {
                return Set.of(
                        AgentEventType.TEXT_BLOCK_DELTA,
                        AgentEventType.THINKING_BLOCK_DELTA,
                        AgentEventType.TOOL_PROGRESS,
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
            Msg responseMessage = convertToResponseMessage(output);
            // Progress is a live update, not part of the tool result: accumulating it would let
            // compactStreamingChunks merge it into the complete message's result text.
            if (responseMessage != null && !(output instanceof ToolProgressEvent)) {
                accumulatedOutput.add(responseMessage);
            }
            handleEvent(output, responseMessage);
        }

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
                    Message.builder()
                            .role(Message.Role.ROLE_AGENT)
                            .parts(new TextPart(errorMessage))
                            .contextId(context.getContextId())
                            .taskId(context.getTaskId())
                            .build());
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
            if (output instanceof ToolProgressEvent event) {
                ContentBlock progress = event.getContent();
                if (progress == null) {
                    return null;
                }
                return toolResultMessage(
                        event,
                        event.getReplyId(),
                        ToolResultBlock.builder()
                                .id(event.getToolCallId())
                                .name(event.getToolCallName())
                                .output(progress)
                                .metadata(event.getMetadata())
                                .build());
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

        private final AgentEmitter emitter;

        /** Whether this request continues a task that was waiting for a tool confirmation. */
        private final boolean resumesPausedTask;

        private boolean waitsForConfirmation;

        private BlockingFluxEventHandler(
                RequestContext context,
                AgentExecuteProperties executeProperties,
                AgentEmitter emitter) {
            super(context, executeProperties);
            this.emitter = emitter;
            this.resumesPausedTask = isWaitingForInput(context.getTask());
            this.resultMessageRef = new AtomicReference<>();
        }

        @Override
        void doOnComplete() {
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
            if (waitsForConfirmation) {
                emitter.requiresInput(resultMessage);
            } else if (resumesPausedTask) {
                // A bare message would leave the resumed task input-required forever.
                emitter.complete(resultMessage);
            } else {
                emitter.sendMessage(resultMessage);
            }
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
            waitsForConfirmation = isWaitingForConfirmation(outputMessage);
        }

        @Override
        protected void sendErrorMessage(Message errorMessage) {
            if (resumesPausedTask) {
                // A bare message would leave the resumed task input-required forever.
                emitter.fail(errorMessage);
            } else {
                emitter.sendMessage(errorMessage);
            }
        }
    }

    private static class StreamingFluxEventHandler extends BaseFluxEventHandler {

        private final AgentEmitter emitter;

        private final String artifactId;

        private final AtomicBoolean isFirstArtifact;

        private Msg finalResult;

        private StreamingFluxEventHandler(
                RequestContext context,
                AgentExecuteProperties executeProperties,
                AgentEmitter emitter) {
            super(context, executeProperties);
            this.emitter = emitter;
            this.artifactId = UUID.randomUUID().toString();
            this.isFirstArtifact = new AtomicBoolean(true);
        }

        @Override
        void doOnComplete() {
            log.info(
                    "[{}] Process agent output for non-blocking request completed.",
                    context.getTaskId());
            if (isWaitingForConfirmation(finalResult)) {
                // The status message carries the tool calls that wait for the confirmation.
                emitter.requiresInput(
                        MessageConvertUtil.convertFromMsgToMessage(
                                finalResult, context.getTaskId(), context.getContextId()));
                return;
            }
            // A runner may emit only its final result. Wait for successful completion before
            // publishing it, and do not append a snapshot to artifacts already streamed.
            if (isFirstArtifact.get() && finalResult != null) {
                List<Part<?>> parts = MessageConvertUtil.convertFromContentBlocks(finalResult);
                if (!parts.isEmpty()) {
                    addArtifact(finalResult, parts, true);
                    accumulatedOutput.add(finalResult);
                }
            }
            Message completeMessage =
                    executeProperties.isCompleteWithMessage()
                            ? MessageConvertUtil.convertFromMsgToMessage(
                                    MessageConvertUtil.compactStreamingChunks(accumulatedOutput),
                                    context.getTaskId(),
                                    context.getContextId())
                            : null;
            emitter.complete(completeMessage);
        }

        @Override
        protected void handleEvent(AgentEvent output, Msg responseMessage) {
            if (output instanceof AgentResultEvent resultEvent) {
                finalResult = resultEvent.getResult();
                return;
            }
            if (responseMessage == null) {
                return;
            }
            addArtifact(
                    responseMessage,
                    MessageConvertUtil.convertFromContentBlocks(
                            responseMessage, isStreamingChunk(output)),
                    false);
        }

        private void addArtifact(
                Msg responseMessage, List<Part<?>> responseParts, boolean lastChunk) {
            emitter.addArtifact(
                    responseParts,
                    artifactId,
                    "agent-response",
                    MessageConvertUtil.convertMetadata(responseMessage),
                    !isFirstArtifact.getAndSet(false),
                    lastChunk);
        }

        private boolean isStreamingChunk(AgentEvent output) {
            return output instanceof TextBlockDeltaEvent
                    || output instanceof ThinkingBlockDeltaEvent
                    || output instanceof ToolProgressEvent
                    || output instanceof ToolResultTextDeltaEvent
                    || output instanceof ToolResultDataDeltaEvent;
        }

        @Override
        protected void sendErrorMessage(Message errorMessage) {
            emitter.fail(errorMessage);
        }
    }
}
