/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.List;
import java.util.Map;
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

    public AgentScopeAgentExecutor(AgentRunner agentRunner) {
        this.agentRunner = agentRunner;
        this.subscriptions = new ConcurrentHashMap<>();
    }

    @Override
    public void cancel(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
        try {
            TaskUpdater taskUpdater = new TaskUpdater(context, eventQueue);
            taskUpdater.cancel();
            agentRunner.stop(taskUpdater.getTaskId());
            Subscription subscription = subscriptions.get(taskUpdater.getTaskId());
            if (null == subscription) {
                log.warn("Not found Subscription for Task `{}`.", taskUpdater.getTaskId());
                return;
            }
            subscription.cancel();
        } catch (Exception e) {
            log.error("Error while cancelling task `{}`.", context.getTaskId(), e);
        }
    }

    @Override
    public void execute(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
        try {
            List<Msg> inputMessages = getInputMessages(context);
            AgentRequestOptions requestOptions = buildAgentRequestOptions(context);
            Flux<Event> resultFlux = agentRunner.stream(inputMessages, requestOptions);

            Task task = context.getTask();
            if (task == null) {
                task = newTask(context.getMessage());
                log.info("Created new task: {}", task.getId());
            } else {
                log.info("Using existing task: {}", task.getId());
            }
            if (isBlockRequest(context)) {
                processTaskBlocking(context, eventQueue, task, resultFlux);
            } else {
                processTaskNonBlocking(context, eventQueue, task, resultFlux);
            }
            log.info("Agent execution completed successfully");
        } catch (Exception e) {
            log.error("Agent execution failed", e);
            eventQueue.enqueueEvent(
                    A2A.toAgentMessage("Agent execution failed: " + e.getMessage()));
        }
    }

    private List<Msg> getInputMessages(RequestContext context) {
        Message message = context.getParams().message();
        // TODO only support text message for now, it should be follow the revert convert by Msg to
        // Message.
        String inputText = getTextFromMessageParts(message);
        Msg msg = Msg.builder().role(MsgRole.USER).textContent(inputText).build();
        return List.of(msg);
    }

    private AgentRequestOptions buildAgentRequestOptions(RequestContext context) {
        Message message = context.getParams().message();
        AgentRequestOptions requestOptions = new AgentRequestOptions();
        requestOptions.setTaskId(context.getTaskId());
        requestOptions.setUserId(getUserId(message));
        requestOptions.setSessionId(getSessionId(message));
        return requestOptions;
    }

    private String getUserId(io.a2a.spec.Message message) {
        if (message.getMetadata() != null && message.getMetadata().containsKey("userId")) {
            return String.valueOf(message.getMetadata().get("userId"));
        }
        return "";
    }

    private String getSessionId(io.a2a.spec.Message message) {
        if (message.getMetadata() != null && message.getMetadata().containsKey("sessionId")) {
            return String.valueOf(message.getMetadata().get("sessionId"));
        }
        return "";
    }

    private Task newTask(io.a2a.spec.Message request) {
        String contextId = request.getContextId();
        if (contextId == null || contextId.isEmpty()) {
            contextId = UUID.randomUUID().toString();
        }
        String taskId = UUID.randomUUID().toString();
        if (request.getTaskId() != null && !request.getTaskId().isEmpty()) {
            taskId = request.getTaskId();
        }
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
        // If not Streaming request, according to the request parameter configuration.
        if (null == context.getParams()) {
            return true;
        }
        if (null == context.getParams().configuration()) {
            return true;
        }
        return Boolean.TRUE.equals(context.getParams().configuration().blocking());
    }

    private void processTaskBlocking(
            RequestContext context, EventQueue eventQueue, Task task, Flux<Event> resultFlux) {
        StringBuilder accumulatedOutput = new StringBuilder();
        AtomicReference<Message> resultMessageRef = new AtomicReference<>();
        log.info("Starting blocking output processing");
        resultFlux
                .doOnSubscribe(
                        s -> {
                            saveSubscription(context.getTaskId(), s);
                        })
                .doOnNext(
                        output -> {
                            try {
                                if (!output.isLast()) {
                                    // From Agentscope EventType comment, the last event is the
                                    // whole result message.
                                    // TODO, debug print handling events or tmp save handling events
                                    // to avoid without last event.
                                    return;
                                }
                                Msg outputMessage = output.getMessage();
                                Message message =
                                        MessageConvertUtil.convertFromMsgToMessage(
                                                outputMessage,
                                                context.getTaskId(),
                                                context.getContextId());
                                resultMessageRef.set(message);
                            } catch (Exception ignored) {
                            }
                        })
                .doOnComplete(
                        () -> {
                            log.info("Subscribe and process stream output completed successfully");
                            Message resultMessage = resultMessageRef.get();
                            // Todo: Still need to decide whether to send the accumulated output as
                            // a final message in blocking mode
                            eventQueue.enqueueEvent(resultMessage);
                        })
                .doOnError(
                        e -> {
                            io.a2a.spec.Message errorMessage =
                                    A2A.createAgentTextMessage(
                                            "Subscribe and process stream output failed: "
                                                    + e.getMessage(),
                                            context.getContextId(),
                                            context.getTaskId());
                            eventQueue.enqueueEvent(errorMessage);
                        })
                .doFinally(signal -> removeSubscription(context.getTaskId(), signal))
                .blockLast();
    }

    private void processTaskNonBlocking(
            RequestContext context, EventQueue eventQueue, Task task, Flux<Event> resultFlux) {
        TaskUpdater taskUpdater = new TaskUpdater(context, eventQueue);
        StringBuilder accumulatedOutput = new StringBuilder();
        try {
            eventQueue.enqueueEvent(task);
            log.info("Starting streaming output processing");
            processStreamingOutput(resultFlux, taskUpdater, accumulatedOutput);
            log.info(
                    "Streaming output processing completed. Total output length: {}",
                    accumulatedOutput.length());
        } catch (Exception e) {
            log.error("Error processing streaming output", e);
            taskUpdater.fail(
                    taskUpdater.newAgentMessage(
                            List.of(
                                    new TextPart(
                                            "Error processing streaming output: "
                                                    + e.getMessage())),
                            Map.of()));
        }
    }

    /**
     * Process streaming output data
     */
    private void processStreamingOutput(
            Flux<Event> resultFlux, TaskUpdater taskUpdater, StringBuilder accumulatedOutput) {
        String artifactId = UUID.randomUUID().toString();
        AtomicBoolean isFirstArtifact = new AtomicBoolean(true);
        try {
            resultFlux
                    .doOnSubscribe(
                            s -> {
                                saveSubscription(taskUpdater.getTaskId(), s);
                                taskUpdater.startWork();
                            })
                    .doOnNext(
                            output -> {
                                try {
                                    if (output.isLast()) {
                                        // From Agentscope EventType comment, this event is NOT
                                        // included in the stream to avoid duplication since it's
                                        // the return value by default.
                                        return;
                                    }
                                    Msg outputMessage = output.getMessage();
                                    if (null == outputMessage) {
                                        LoggerUtil.debug(
                                                log,
                                                "Ignored null message output event: {}",
                                                output);
                                        return;
                                    }
                                    List<Part<?>> responseParts =
                                            MessageConvertUtil.convertFromContentBlocks(
                                                    outputMessage);
                                    // TODO, add to accumulatedOutput
                                    taskUpdater.addArtifact(
                                            responseParts,
                                            artifactId,
                                            "agent-response",
                                            outputMessage.getMetadata(),
                                            !isFirstArtifact.getAndSet(false),
                                            false);
                                } catch (Exception ignored) {
                                }
                            })
                    .doOnComplete(
                            () -> {
                                log.info(
                                        "Subscribe and process stream output completed"
                                                + " successfully");
                                io.a2a.spec.Message finalMessage =
                                        taskUpdater.newAgentMessage(
                                                List.of(new TextPart(accumulatedOutput.toString())),
                                                Map.of("type", "final_response"));
                                taskUpdater.complete(finalMessage);
                            })
                    .doOnError(
                            e -> {
                                io.a2a.spec.Message errorMessage =
                                        taskUpdater.newAgentMessage(
                                                List.of(
                                                        new TextPart(
                                                                "Subscribe and process stream"
                                                                        + " output failed: "
                                                                        + e.getMessage())),
                                                Map.of());
                                taskUpdater.fail(errorMessage);
                            })
                    .doFinally(signal -> removeSubscription(taskUpdater.getTaskId(), signal))
                    .blockLast();

        } catch (Exception e) {
            taskUpdater.fail(
                    taskUpdater.newAgentMessage(
                            List.of(new TextPart("Critical error: " + e.getMessage())), Map.of()));
        }
    }

    private void saveSubscription(String taskId, Subscription subscription) {
        log.info("Subscribed to executeFunction result stream");
        subscriptions.put(taskId, subscription);
    }

    private void removeSubscription(String taskId, SignalType signal) {
        log.info("Subscribe and process stream output terminated: {}", signal);
        subscriptions.remove(taskId);
    }

    private String getTextFromMessageParts(io.a2a.spec.Message message) {
        StringBuilder sb = new StringBuilder();
        for (Part<?> each : message.getParts()) {
            if (Part.Kind.TEXT.equals(each.getKind())) {
                sb.append(((TextPart) each).getText()).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
