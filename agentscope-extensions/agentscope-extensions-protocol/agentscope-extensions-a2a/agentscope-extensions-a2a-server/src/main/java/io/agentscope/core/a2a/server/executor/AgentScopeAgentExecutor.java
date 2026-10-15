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
import io.a2a.spec.TaskNotCancelableError;
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
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolProgressEvent;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;

/**
 * Implementation of A2A {@link AgentExecutor} for AgentScope.
 *
 * <p>For Current Implementation, will create a new {@link io.agentscope.core.agent.Agent} for each request.
 */
public class AgentScopeAgentExecutor implements AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeAgentExecutor.class);

    private final Map<String, RunningExecution> executions;

    private final AgentRunner agentRunner;

    private final AgentExecuteProperties agentExecuteProperties;

    public AgentScopeAgentExecutor(
            AgentRunner agentRunner, AgentExecuteProperties agentExecuteProperties) {
        this.agentRunner = agentRunner;
        this.agentExecuteProperties = agentExecuteProperties;
        this.executions = new ConcurrentHashMap<>();
    }

    @Override
    public void cancel(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
        String taskId = context.getTaskId();
        RunningExecution execution = executions.get(taskId);
        AtomicBoolean cancelledHere = new AtomicBoolean();
        try {
            log.info("[{}] Start to Cancel Task", taskId);
            TaskUpdater taskUpdater = new TaskUpdater(context, eventQueue);
            if (null == execution) {
                log.warn("[{}] Not found Subscription for Task.", taskId);
                taskUpdater.cancel();
            } else if (!execution.cancel(
                    () -> {
                        cancelledHere.set(true);
                        taskUpdater.cancel();
                    })) {
                // Completion or failure was published first. The SDK only drains and closes this
                // queue when cancel returns, so close it here. A JSONRPCError, not caught below.
                eventQueue.close();
                throw new TaskNotCancelableError(
                        "Task cannot be canceled - it has already finished");
            }
            agentRunner.stop(taskId);
        } catch (Exception e) {
            log.error("[{}] Error while cancelling task.", taskId, e);
        } finally {
            if (cancelledHere.get()) {
                // End the execution even if stop() failed or left the agent stream running.
                execution.end();
            }
        }
    }

    @Override
    public void execute(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
        RunningExecution execution = new RunningExecution();
        executions.put(context.getTaskId(), execution);
        try {
            List<Msg> inputMessages =
                    MessageConvertUtil.convertFromMessageToMsgs(context.getMessage());
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
                processTaskBlocking(context, eventQueue, task, resultFlux, execution);
            } else {
                processTaskNonBlocking(context, eventQueue, task, resultFlux, execution);
            }
            log.info("[{}] Agent execution completed successfully", context.getTaskId());
        } catch (Exception e) {
            log.error("[{}] Agent execution failed", context.getTaskId(), e);
            execution.failUnlessCancelled(
                    () ->
                            eventQueue.enqueueEvent(
                                    A2A.createAgentTextMessage(
                                            "Agent execution failed: " + e.getMessage(),
                                            context.getContextId(),
                                            context.getTaskId())));
        } finally {
            executions.remove(context.getTaskId(), execution);
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
            RequestContext context,
            EventQueue eventQueue,
            Task task,
            Flux<AgentEvent> resultFlux,
            RunningExecution execution) {
        BlockingFluxEventHandler eventHandler =
                new BlockingFluxEventHandler(
                        context, agentExecuteProperties, execution, eventQueue);
        log.info("[{}] Starting blocking request processing", context.getTaskId());
        resultFlux
                .doOnSubscribe(s -> logSubscribed(context.getTaskId()))
                .doOnNext(eventHandler::doOnNext)
                .doOnComplete(eventHandler::complete)
                .doOnError(eventHandler::fail)
                .takeUntilOther(execution.ended())
                .doFinally(signal -> logTerminated(context.getTaskId(), signal))
                .blockLast();
    }

    private void processTaskNonBlocking(
            RequestContext context,
            EventQueue eventQueue,
            Task task,
            Flux<AgentEvent> resultFlux,
            RunningExecution execution) {
        TaskUpdater taskUpdater = new TaskUpdater(context, eventQueue);
        try {
            execution.publishIfRunning(() -> eventQueue.enqueueEvent(task));
            log.info("[{}] Starting streaming request processing", context.getTaskId());
            processStreamingOutput(resultFlux, taskUpdater, context, execution);
        } catch (Exception e) {
            log.error("[{}] Error processing streaming output", context.getTaskId(), e);
            // Skipped when doOnError or cancel already decided the terminal state.
            execution.finish(
                    () ->
                            taskUpdater.fail(
                                    taskUpdater.newAgentMessage(
                                            List.of(
                                                    new TextPart(
                                                            "Error processing streaming output: "
                                                                    + e.getMessage())),
                                            Map.of())));
        }
    }

    /**
     * Process streaming output data
     */
    private void processStreamingOutput(
            Flux<AgentEvent> resultFlux,
            TaskUpdater taskUpdater,
            RequestContext context,
            RunningExecution execution) {
        StreamingFluxEventHandler eventHandler =
                new StreamingFluxEventHandler(
                        context, agentExecuteProperties, execution, taskUpdater);
        resultFlux
                .doOnSubscribe(
                        s -> {
                            logSubscribed(taskUpdater.getTaskId());
                            execution.publishIfRunning(taskUpdater::startWork);
                        })
                .doOnNext(eventHandler::doOnNext)
                .doOnComplete(eventHandler::complete)
                .doOnError(eventHandler::fail)
                .takeUntilOther(execution.ended())
                .doFinally(signal -> logTerminated(taskUpdater.getTaskId(), signal))
                .blockLast();
    }

    private void logSubscribed(String taskId) {
        log.info("[{}] Subscribed to executeFunction result stream", taskId);
    }

    private void logTerminated(String taskId, SignalType signal) {
        log.info("[{}] Subscribe and process stream output terminated: {}", taskId, signal);
    }

    /**
     * State of one execution, shared by {@link #execute} and {@link #cancel}.
     *
     * <p>Cancelling, completing and failing each publish a terminal state for the task. State
     * checks and the events they guard are published under this object's lock: once the task is
     * cancelled nothing else is published, and cancelling after completion or failure is rejected.
     * Never call the agent runner while holding the lock: it may call back into the event
     * handlers.
     *
     * <p>{@link #ended()} is applied with {@code takeUntilOther} after the event handlers, so
     * {@link #end()} stops the agent stream and completes the downstream: {@code blockLast()}
     * returns without running the handlers' completion. Cancelling the upstream subscription alone
     * would leave {@code blockLast()} waiting forever.
     */
    private static final class RunningExecution {

        private enum State {
            RUNNING,
            FINISHED,
            CANCELLED
        }

        private final Sinks.Empty<Void> endSignal = Sinks.empty();

        private State state = State.RUNNING;

        /** Publishes output while the task is running; drops it once the task has ended. */
        synchronized void publishIfRunning(Runnable publish) {
            if (state == State.RUNNING) {
                publish.run();
            }
        }

        /** Completes or fails the running task with {@code publish}; {@code false} if it ended. */
        synchronized boolean finish(Runnable publish) {
            if (state != State.RUNNING) {
                return false;
            }
            state = State.FINISHED;
            publish.run();
            return true;
        }

        /** Cancels the running task with {@code publish}; {@code false} if it already ended. */
        synchronized boolean cancel(Runnable publish) {
            if (state != State.RUNNING) {
                return false;
            }
            state = State.CANCELLED;
            publish.run();
            return true;
        }

        /** Reports a failure with {@code publish} unless the task was cancelled. */
        synchronized void failUnlessCancelled(Runnable publish) {
            if (state == State.CANCELLED) {
                return;
            }
            state = State.FINISHED;
            publish.run();
        }

        void end() {
            endSignal.tryEmitEmpty();
        }

        Mono<Void> ended() {
            return endSignal.asMono();
        }
    }

    private abstract static class BaseFluxEventHandler {

        protected final RequestContext context;

        protected final List<Msg> accumulatedOutput;

        protected final AgentExecuteProperties executeProperties;

        protected final RunningExecution execution;

        private final Set<AgentEventType> requiredEventTypes;

        private BaseFluxEventHandler(
                RequestContext context,
                AgentExecuteProperties executeProperties,
                RunningExecution execution) {
            this.context = context;
            this.executeProperties = executeProperties;
            this.execution = execution;
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

        /** Completes the task, unless cancel or an error already ended it. */
        void complete() {
            execution.finish(this::doOnComplete);
        }

        /** Fails the task, unless cancel or completion already ended it. */
        void fail(Throwable t) {
            execution.finish(() -> doOnError(t));
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

        private final EventQueue eventQueue;

        private BlockingFluxEventHandler(
                RequestContext context,
                AgentExecuteProperties executeProperties,
                RunningExecution execution,
                EventQueue eventQueue) {
            super(context, executeProperties, execution);
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
                RunningExecution execution,
                TaskUpdater taskUpdater) {
            super(context, executeProperties, execution);
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
        protected void handleEvent(AgentEvent output, Msg responseMessage) {
            if (responseMessage == null) {
                return;
            }
            List<Part<?>> responseParts =
                    MessageConvertUtil.convertFromContentBlocks(
                            responseMessage, isStreamingChunk(output));
            // Dropped if the task was cancelled while this event was being converted.
            execution.publishIfRunning(
                    () ->
                            taskUpdater.addArtifact(
                                    responseParts,
                                    artifactId,
                                    "agent-response",
                                    responseMessage.getMetadata(),
                                    !isFirstArtifact.getAndSet(false),
                                    false));
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
            taskUpdater.fail(errorMessage);
        }
    }
}
