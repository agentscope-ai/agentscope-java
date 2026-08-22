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
import io.agentscope.core.agent.Event;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.HintBlockEvent;
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
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Objects;
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
            List<Msg> inputMessages =
                    MessageConvertUtil.convertFromMessageToMsgs(context.getMessage());
            AgentRequestOptions requestOptions = buildAgentRequestOptions(context);
            Flux<AgentEvent> resultFlux = streamAgentEvents(inputMessages, requestOptions);

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

    private AgentRequestOptions buildAgentRequestOptions(RequestContext context) {
        Message message = context.getParams().message();
        AgentRequestOptions requestOptions = new AgentRequestOptions();
        requestOptions.setTaskId(context.getTaskId());
        requestOptions.setUserId(getUserId(message));
        requestOptions.setSessionId(getSessionId(message));
        return requestOptions;
    }

    private String getUserId(Message message) {
        if (message.getMetadata() != null && message.getMetadata().containsKey("userId")) {
            return String.valueOf(message.getMetadata().get("userId"));
        }
        return "";
    }

    /**
     * Prefer the fine-grained event stream while keeping custom legacy runners source-compatible.
     *
     * <p>{@link AgentRunner#streamEvents(List, AgentRequestOptions)} has a default unsupported
     * implementation, so the fallback is only selected when a runner has not migrated yet. The
     * legacy events are wrapped as {@link AgentEvent}s before they reach the handlers, which keeps
     * the A2A processing path on one event model.
     */
    private Flux<AgentEvent> streamAgentEvents(
            List<Msg> inputMessages, AgentRequestOptions requestOptions) {
        // Catch only a synchronous UnsupportedOperationException from the compatibility default.
        // An exception emitted by the returned Flux must propagate; falling back then could execute
        // the agent twice.
        return Flux.defer(
                () -> {
                    try {
                        Flux<AgentEvent> fineGrainedStream =
                                agentRunner.streamEvents(inputMessages, requestOptions);
                        if (fineGrainedStream == null) {
                            return streamLegacyEvents(inputMessages, requestOptions);
                        }
                        AtomicBoolean emitted = new AtomicBoolean();
                        return fineGrainedStream
                                .doOnNext(event -> emitted.set(true))
                                .onErrorResume(
                                        UnsupportedOperationException.class,
                                        error -> {
                                            if (emitted.get()) {
                                                return Flux.error(error);
                                            }
                                            log.debug(
                                                    "Falling back to legacy AgentRunner.stream() for task {}",
                                                    requestOptions.getTaskId());
                                            return streamLegacyEvents(inputMessages, requestOptions);
                                        });
                    } catch (UnsupportedOperationException error) {
                        log.debug(
                                "Falling back to legacy AgentRunner.stream() for task {}",
                                requestOptions.getTaskId());
                        return streamLegacyEvents(inputMessages, requestOptions);
                    }
                });
    }

    private Flux<AgentEvent> streamLegacyEvents(
            List<Msg> inputMessages, AgentRequestOptions requestOptions) {
        return agentRunner.stream(inputMessages, requestOptions)
                .flatMapIterable(AgentScopeAgentExecutor::adaptLegacyEvent);
    }

    private static List<AgentEvent> adaptLegacyEvent(Event event) {
        if (event == null || event.getType() == null || event.getMessage() == null) {
            return List.of();
        }
        AgentEventType eventType =
                switch (event.getType()) {
                    case REASONING, SUMMARY -> AgentEventType.TEXT_BLOCK_DELTA;
                    case TOOL_RESULT -> AgentEventType.TOOL_RESULT_TEXT_DELTA;
                    case HINT -> AgentEventType.HINT_BLOCK;
                    case AGENT_RESULT -> AgentEventType.AGENT_RESULT;
                    default -> null;
                };
        return eventType == null ? List.of() : List.of(new LegacyAgentEvent(event, eventType));
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
                new BlockingFluxEventHandler(context, agentExecuteProperties, eventQueue);
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
            eventQueue.enqueueEvent(task);
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

        private String lastLegacyEventMsgId;

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
            LoggerUtil.debug(log, "[{}] Handle Agent execute outputs: ", context.getTaskId());
            LoggerUtil.debug(log, "[{}] Agent event: {}", context.getTaskId(), output);
            appendToAccumulatedOutput(output);
            handleEvent(output);
            if (output instanceof LegacyAgentEvent legacyEvent) {
                lastLegacyEventMsgId = legacyEvent.legacyEvent.getMessageId();
            }
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
                    A2A.createAgentTextMessage(
                            errorMessage, context.getContextId(), context.getTaskId()));
        }

        private void appendToAccumulatedOutput(AgentEvent output) {
            if (isNoResponseEvent(output)) {
                return;
            }
            Msg outputMessage = convertToMsg(output);
            if (outputMessage != null) {
                accumulatedOutput.add(outputMessage);
            }
        }

        /**
         * Determines whether the given event should not be sent as a response to the A2A client,
         * for example, tool-call-related events or duplicate result messages.
         *
         * <p>These events will be ignored and no response will be sent to the A2A client when this
         * method returns {@code true}:
         *
         * <ul>
         *     <li>The event type is not in the required event set that is generated from properties.</li>
         *     <li>The event is not one of the response-bearing fine-grained event types.</li>
         * </ul>
         *
         * @param output agent output event
         * @return {@code true} if the event should not be responded to, otherwise {@code false}.
         */
        protected boolean isNoResponseEvent(AgentEvent output) {
            if (!requiredEventTypes.contains(output.getType())) {
                return true;
            }
            if (!(output instanceof LegacyAgentEvent legacyEvent)
                    || !legacyEvent.legacyEvent.isLast()) {
                return false;
            }
            return Objects.equals(lastLegacyEventMsgId, legacyEvent.legacyEvent.getMessageId());
        }

        /**
         * Handle the event.
         *
         * @param output output event from agent stream execute.
         */
        protected abstract void handleEvent(AgentEvent output);

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

        private BlockingFluxEventHandler(
                RequestContext context,
                AgentExecuteProperties executeProperties,
                EventQueue eventQueue) {
            super(context, executeProperties);
            this.eventQueue = eventQueue;
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
            eventQueue.enqueueEvent(resultMessage);
        }

        @Override
        protected void handleEvent(AgentEvent output) {
            if (!AgentEventType.AGENT_RESULT.equals(output.getType())) {
                // Non-AGENT_RESULT messages should be ignored and saved into accumulatedOutput
                // according to properties.
                return;
            }
            Msg outputMessage = convertToMsg(output);
            if (outputMessage == null) {
                return;
            }
            Message message =
                    MessageConvertUtil.convertFromMsgToMessage(
                            outputMessage, context.getTaskId(), context.getContextId());
            resultMessageRef.set(message);
        }

        @Override
        protected void sendErrorMessage(Message errorMessage) {
            eventQueue.enqueueEvent(errorMessage);
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
        protected void handleEvent(AgentEvent output) {
            if (isNoResponseEvent(output)) {
                return;
            }
            Msg outputMessage = convertToMsg(output);
            if (outputMessage == null) {
                return;
            }
            List<Part<?>> responseParts =
                    MessageConvertUtil.convertFromContentBlocks(
                            outputMessage, isStreamingChunk(output));
            taskUpdater.addArtifact(
                    responseParts,
                    artifactId,
                    "agent-response",
                    outputMessage.getMetadata(),
                    !isFirstArtifact.getAndSet(false),
                    false);
        }

        @Override
        protected void sendErrorMessage(Message errorMessage) {
            taskUpdater.fail(errorMessage);
        }
    }

    private static boolean isStreamingChunk(AgentEvent output) {
        if (output instanceof LegacyAgentEvent legacyEvent) {
            return !legacyEvent.legacyEvent.isLast();
        }
        return !AgentEventType.AGENT_RESULT.equals(output.getType());
    }

    private static Msg convertToMsg(AgentEvent output) {
        if (output instanceof LegacyAgentEvent legacyEvent) {
            return legacyEvent.legacyEvent.getMessage();
        }
        if (output instanceof AgentResultEvent resultEvent) {
            return resultEvent.getResult();
        }
        if (output instanceof TextBlockDeltaEvent textDeltaEvent) {
            return messageWithContent(
                    textDeltaEvent.getReplyId(),
                    MsgRole.ASSISTANT,
                    TextBlock.builder().text(textDeltaEvent.getDelta()).build());
        }
        if (output instanceof ThinkingBlockDeltaEvent thinkingDeltaEvent) {
            return messageWithContent(
                    thinkingDeltaEvent.getReplyId(),
                    MsgRole.ASSISTANT,
                    ThinkingBlock.builder().thinking(thinkingDeltaEvent.getDelta()).build());
        }
        if (output instanceof HintBlockEvent hintBlockEvent) {
            return messageWithContent(
                    hintBlockEvent.getReplyId(),
                    MsgRole.USER,
                    new HintBlock(
                            hintBlockEvent.getBlockId(),
                            hintBlockEvent.getHint(),
                            hintBlockEvent.getHintSource()));
        }
        if (output instanceof ToolResultTextDeltaEvent toolTextDeltaEvent) {
            return messageWithContent(
                    toolTextDeltaEvent.getReplyId(),
                    MsgRole.TOOL,
                    ToolResultBlock.of(
                            toolTextDeltaEvent.getToolCallId(),
                            toolTextDeltaEvent.getToolCallName(),
                            TextBlock.builder().text(toolTextDeltaEvent.getDelta()).build()));
        }
        if (output instanceof ToolResultDataDeltaEvent toolDataDeltaEvent) {
            ContentBlock data = toolDataDeltaEvent.getData();
            if (data == null) {
                return null;
            }
            return messageWithContent(
                    toolDataDeltaEvent.getReplyId(),
                    MsgRole.TOOL,
                    ToolResultBlock.of(
                            toolDataDeltaEvent.getToolCallId(),
                            toolDataDeltaEvent.getToolCallName(),
                            data));
        }
        return null;
    }

    private static Msg messageWithContent(String id, MsgRole role, ContentBlock content) {
        return Msg.builder().id(id).role(role).content(content).build();
    }

    /** Adapter that lets legacy custom runners use the AgentEvent-only handler path. */
    private static final class LegacyAgentEvent extends AgentEvent {

        private final Event legacyEvent;

        private final AgentEventType type;

        private LegacyAgentEvent(Event legacyEvent, AgentEventType type) {
            this.legacyEvent = legacyEvent;
            this.type = type;
        }

        @Override
        public AgentEventType getType() {
            return type;
        }
    }
}
