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

import io.agentscope.core.a2a.agent.utils.LoggerUtil;
import io.agentscope.core.a2a.server.constants.A2aServerConstants;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.utils.MessageConvertUtil;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
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
    public void cancel(RequestContext context, AgentEmitter agentEmitter) throws A2AError {
        try {
            log.info("[{}] Start to Cancel Task", context.getTaskId());
            agentEmitter.cancel();
            agentRunner.stop(agentEmitter.getTaskId());
            Subscription subscription = subscriptions.get(agentEmitter.getTaskId());
            if (null == subscription) {
                log.warn("[{}] Not found Subscription for Task.", agentEmitter.getTaskId());
                return;
            }
            subscription.cancel();
        } catch (Exception e) {
            log.error("[{}] Error while cancelling task.", context.getTaskId(), e);
        }
    }

    @Override
    public void execute(RequestContext context, AgentEmitter agentEmitter) throws A2AError {
        try {
            List<Msg> inputMessages =
                    MessageConvertUtil.convertFromMessageToMsgs(context.getMessage());
            AgentRequestOptions requestOptions = buildAgentRequestOptions(context);
            Flux<Event> resultFlux = agentRunner.stream(inputMessages, requestOptions);

            Task task = context.getTask();
            if (task == null) {
                task = newTask(context.getMessage());
                log.info("[{}] Created new task.", task.id());
            } else {
                log.info("[{}] Using existing task.", task.id());
            }
            if (isBlockRequest(context)) {
                processTaskBlocking(context, agentEmitter, task, resultFlux);
            } else {
                processTaskNonBlocking(context, agentEmitter, task, resultFlux);
            }
            log.info("[{}] Agent execution completed successfully", context.getTaskId());
        } catch (Exception e) {
            log.error("[{}] Agent execution failed", context.getTaskId(), e);
            try {
                agentEmitter.fail(
                        agentEmitter.newAgentMessage(
                                List.of(new TextPart("Agent execution failed: " + e.getMessage())),
                                Map.of()));
            } catch (IllegalStateException ex) {
                log.warn(
                        "[{}] Task already in terminal state, skipping fail signal",
                        context.getTaskId());
            }
        }
    }

    private AgentRequestOptions buildAgentRequestOptions(RequestContext context) {
        Message message = context.getMessage();
        AgentRequestOptions requestOptions = new AgentRequestOptions();
        requestOptions.setTaskId(context.getTaskId());
        requestOptions.setUserId(getUserId(message));
        requestOptions.setSessionId(getSessionId(message));
        return requestOptions;
    }

    private String getUserId(Message message) {
        if (message.metadata() != null && message.metadata().containsKey("userId")) {
            return String.valueOf(message.metadata().get("userId"));
        }
        return "";
    }

    private String getSessionId(Message message) {
        if (message.metadata() != null && message.metadata().containsKey("sessionId")) {
            return String.valueOf(message.metadata().get("sessionId"));
        }
        return "";
    }

    private Task newTask(Message request) {
        String contextId = request.contextId();
        String taskId = request.taskId();
        return new Task(
                taskId,
                contextId,
                new TaskStatus(TaskState.TASK_STATE_SUBMITTED),
                null,
                List.of(request),
                null);
    }

    private boolean isBlockRequest(RequestContext context) {
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
            RequestContext context, AgentEmitter agentEmitter, Task task, Flux<Event> resultFlux) {
        BlockingFluxEventHandler eventHandler =
                new BlockingFluxEventHandler(context, agentExecuteProperties, agentEmitter);
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
            RequestContext context, AgentEmitter agentEmitter, Task task, Flux<Event> resultFlux) {
        try {
            agentEmitter.emitEvent(task);
            log.info("[{}] Starting streaming request processing", context.getTaskId());
            processStreamingOutput(resultFlux, agentEmitter, context);
        } catch (Exception e) {
            log.error("[{}] Error processing streaming output", context.getTaskId(), e);
            try {
                agentEmitter.fail(
                        agentEmitter.newAgentMessage(
                                List.of(
                                        new TextPart(
                                                "Error processing streaming output: "
                                                        + e.getMessage())),
                                Map.of()));
            } catch (IllegalStateException ex) {
                log.warn(
                        "[{}] Task already in terminal state, skipping fail signal",
                        context.getTaskId());
            }
        }
    }

    /**
     * Process streaming output data
     */
    private void processStreamingOutput(
            Flux<Event> resultFlux, AgentEmitter agentEmitter, RequestContext context) {
        StreamingFluxEventHandler eventHandler =
                new StreamingFluxEventHandler(context, agentExecuteProperties, agentEmitter);
        resultFlux
                .doOnSubscribe(
                        s -> {
                            saveSubscription(agentEmitter.getTaskId(), s);
                            agentEmitter.startWork();
                        })
                .doOnNext(eventHandler::doOnNext)
                .doOnComplete(eventHandler::doOnComplete)
                .doOnError(eventHandler::doOnError)
                .doFinally(signal -> removeSubscription(agentEmitter.getTaskId(), signal))
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

        private final Set<EventType> requiredEventTypes;

        private String lastEventMsgId;

        private BaseFluxEventHandler(
                RequestContext context, AgentExecuteProperties executeProperties) {
            this.context = context;
            this.executeProperties = executeProperties;
            this.accumulatedOutput = new LinkedList<>();
            this.requiredEventTypes = generateRequiredEventTypes(executeProperties);
        }

        private Set<EventType> generateRequiredEventTypes(
                AgentExecuteProperties executeProperties) {
            if (executeProperties.isRequireInnerMessage()) {
                return Set.of(
                        EventType.REASONING,
                        EventType.TOOL_RESULT,
                        EventType.HINT,
                        EventType.SUMMARY);
            }
            return Set.of(EventType.REASONING, EventType.SUMMARY);
        }

        /**
         * Template for Flux doOnNext to handle event.
         *
         * @param output output event from agent stream execute.
         */
        void doOnNext(Event output) {
            LoggerUtil.debug(log, "[{}] Handle Agent execute outputs: ", context.getTaskId());
            LoggerUtil.logAgentEventDetail(log, output);
            appendToAccumulatedOutput(output);
            handleEvent(output);
            lastEventMsgId = output.getMessageId();
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
            try {
                String errorMessage = "Handle Agent execute error: " + t.getMessage();
                sendErrorMessage(
                        A2A.createAgentTextMessage(
                                errorMessage, context.getContextId(), context.getTaskId()));
            } catch (IllegalStateException ex) {
                log.warn(
                        "[{}] Task already in terminal state, skipping error message",
                        context.getTaskId());
            }
        }

        private void appendToAccumulatedOutput(Event output) {
            if (isNoResponseEvent(output)) {
                return;
            }
            accumulatedOutput.add(output.getMessage());
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
         *     <li>The event is the last event ({@link Event#isLast()} is {@code true}) and the
         *         {@code messageId} of the event is the same as the previous last event.</li>
         * </ul>
         *
         * @param output agent output event
         * @return {@code true} if the event should not be responded to, otherwise {@code false}.
         */
        protected boolean isNoResponseEvent(Event output) {
            if (!requiredEventTypes.contains(output.getType())) {
                return true;
            }
            if (!output.isLast()) {
                return false;
            }
            return Objects.equals(lastEventMsgId, output.getMessageId());
        }

        /**
         * Handle the event.
         *
         * @param output output event from agent stream execute.
         */
        protected abstract void handleEvent(Event output);

        /**
         * Send error message to A2A Client.
         *
         * @param errorMessage error message to send to A2A Client.
         */
        protected abstract void sendErrorMessage(Message errorMessage);
    }

    private static class BlockingFluxEventHandler extends BaseFluxEventHandler {

        private final AtomicReference<Message> resultMessageRef;

        private final AgentEmitter agentEmitter;

        private BlockingFluxEventHandler(
                RequestContext context,
                AgentExecuteProperties executeProperties,
                AgentEmitter agentEmitter) {
            super(context, executeProperties);
            this.agentEmitter = agentEmitter;
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
                                    accumulatedOutput, context.getTaskId(), context.getContextId());
            agentEmitter.emitEvent(resultMessage);
        }

        @Override
        protected void handleEvent(Event output) {
            if (!EventType.AGENT_RESULT.equals(output.getType())) {
                // Non-AGENT_RESULT messages should be ignored and saved into accumulatedOutput
                // according to properties.
                return;
            }
            Msg outputMessage = output.getMessage();
            Message message =
                    MessageConvertUtil.convertFromMsgToMessage(
                            outputMessage, context.getTaskId(), context.getContextId());
            resultMessageRef.set(message);
        }

        @Override
        protected void sendErrorMessage(Message errorMessage) {
            agentEmitter.emitEvent(errorMessage);
        }
    }

    private static class StreamingFluxEventHandler extends BaseFluxEventHandler {

        private final AgentEmitter agentEmitter;

        private final String artifactId;

        private final AtomicBoolean isFirstArtifact;

        private StreamingFluxEventHandler(
                RequestContext context,
                AgentExecuteProperties executeProperties,
                AgentEmitter agentEmitter) {
            super(context, executeProperties);
            this.agentEmitter = agentEmitter;
            this.artifactId = UUID.randomUUID().toString();
            this.isFirstArtifact = new AtomicBoolean(true);
        }

        @Override
        void doOnComplete() {
            log.info(
                    "[{}] Process agent output for non-blocking request completed.",
                    agentEmitter.getTaskId());
            Message completeMessage =
                    executeProperties.isCompleteWithMessage()
                            ? MessageConvertUtil.convertFromMsgToMessage(
                                    accumulatedOutput,
                                    agentEmitter.getTaskId(),
                                    agentEmitter.getContextId())
                            : null;
            agentEmitter.complete(completeMessage);
        }

        @Override
        protected void handleEvent(Event output) {
            if (isNoResponseEvent(output)) {
                return;
            }
            Msg outputMessage = output.getMessage();
            List<Part<?>> responseParts =
                    MessageConvertUtil.convertFromContentBlocks(outputMessage);
            agentEmitter.addArtifact(
                    responseParts,
                    artifactId,
                    "agent-response",
                    outputMessage.getMetadata(),
                    !isFirstArtifact.getAndSet(false),
                    false);
        }

        @Override
        protected void sendErrorMessage(Message errorMessage) {
            agentEmitter.fail(errorMessage);
        }
    }
}
