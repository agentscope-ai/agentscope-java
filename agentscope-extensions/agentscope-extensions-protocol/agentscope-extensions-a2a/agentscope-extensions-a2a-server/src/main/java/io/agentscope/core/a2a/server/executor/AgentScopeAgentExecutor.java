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

import io.agentscope.core.a2a.server.constants.A2aServerConstants;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.utils.MessageConvertUtil;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.auth.User;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys;
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
    public void cancel(RequestContext context, AgentEmitter emitter) throws A2AError {
        try {
            log.info("[{}] Start to Cancel Task", context.getTaskId());
            emitter.cancel();
            agentRunner.stop(emitter.getTaskId());
            Subscription subscription = subscriptions.get(emitter.getTaskId());
            if (null == subscription) {
                log.warn("[{}] Not found Subscription for Task.", emitter.getTaskId());
                return;
            }
            subscription.cancel();
        } catch (Exception e) {
            log.error("[{}] Error while cancelling task.", context.getTaskId(), e);
        }
    }

    @Override
    public void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
        try {
            List<Msg> inputMessages =
                    MessageConvertUtil.convertFromMessageToMsgs(context.getMessage());
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
                processTaskBlocking(context, emitter, resultFlux);
            } else {
                processTaskNonBlocking(context, emitter, task, resultFlux);
            }
            log.info("[{}] Agent execution completed successfully", context.getTaskId());
        } catch (Exception e) {
            log.error("[{}] Agent execution failed", context.getTaskId(), e);
            emitter.fail(
                    A2A.createAgentTextMessage(
                            "Agent execution failed: " + e.getMessage(),
                            context.getContextId(),
                            context.getTaskId()));
        }
    }

    private AgentRequestOptions buildAgentRequestOptions(RequestContext context) {
        Message message = context.getMessage();
        AgentRequestOptions requestOptions = new AgentRequestOptions();
        requestOptions.setTaskId(context.getTaskId());
        requestOptions.setUserId(getUserId(context, message));
        requestOptions.setSessionId(getSessionId(context, message));
        requestOptions.setAgentId(getAgentId(context, message));
        requestOptions.setMetadata(mergeMetadata(context, message));
        requestOptions.setHeaders(getHeaders(context));
        return requestOptions;
    }

    private String getUserId(RequestContext context, Message message) {
        String authenticatedUser = getAuthenticatedUsername(context);
        if (hasText(authenticatedUser)) {
            return authenticatedUser.trim();
        }
        return firstText(
                getMetadataValue(message, "userId"),
                getMetadataValue(context, "userId"),
                getMetadataValue(message, "username"),
                getMetadataValue(context, "username"));
    }

    private String getSessionId(RequestContext context, Message message) {
        return firstText(
                message != null ? message.contextId() : "",
                getMetadataValue(message, "sessionId"),
                getMetadataValue(context, "sessionId"),
                getMetadataValue(message, "threadId"),
                getMetadataValue(context, "threadId"),
                getMetadataValue(message, "thread_id"),
                getMetadataValue(context, "thread_id"),
                context.getContextId());
    }

    private String getAgentId(RequestContext context, Message message) {
        return firstText(
                getMetadataValue(message, "agentId"),
                getMetadataValue(context, "agentId"),
                getMetadataValue(message, "agent_id"),
                getMetadataValue(context, "agent_id"));
    }

    private Map<String, Object> mergeMetadata(RequestContext context, Message message) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (context != null && context.getMetadata() != null) {
            result.putAll(context.getMetadata());
        }
        if (message != null && message.metadata() != null) {
            result.putAll(message.metadata());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> getHeaders(RequestContext context) {
        ServerCallContext callContext = context == null ? null : context.getCallContext();
        Map<String, Object> state = callContext == null ? null : callContext.getState();
        if (state == null) {
            return Map.of();
        }
        Object headers = state.get(JSONRPCContextKeys.HEADERS_KEY);
        if (!(headers instanceof Map<?, ?> headerMap)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        headerMap.forEach(
                (key, value) -> {
                    if (key != null && value != null) {
                        result.put(String.valueOf(key), String.valueOf(value));
                    }
                });
        return result;
    }

    private String getAuthenticatedUsername(RequestContext context) {
        ServerCallContext callContext = context == null ? null : context.getCallContext();
        User user = callContext == null ? null : callContext.getUser();
        if (user != null && user.isAuthenticated() && hasText(user.getUsername())) {
            return user.getUsername();
        }
        return "";
    }

    private String getMetadataValue(Message message, String key) {
        if (message == null || message.metadata() == null) {
            return "";
        }
        return stringValue(message.metadata().get(key));
    }

    private String getMetadataValue(RequestContext context, String key) {
        if (context == null || context.getMetadata() == null) {
            return "";
        }
        return stringValue(context.getMetadata().get(key));
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private Task newTask(RequestContext context) {
        return new Task(
                context.getTaskId(),
                context.getContextId(),
                new TaskStatus(TaskState.TASK_STATE_SUBMITTED),
                null,
                context.getMessage() == null ? List.of() : List.of(context.getMessage()),
                null);
    }

    private boolean isBlockRequest(RequestContext context) {
        // Streaming request must non-block.
        ServerCallContext callContext = context.getCallContext();
        Map<String, Object> state = callContext == null ? null : callContext.getState();
        Object isStreaming =
                state == null
                        ? Boolean.FALSE
                        : state.getOrDefault(
                                A2aServerConstants.ContextKeys.IS_STREAM_KEY, Boolean.FALSE);
        if (Boolean.TRUE.equals(isStreaming)) {
            return false;
        }
        if (null == context.getConfiguration()) {
            return true;
        }
        return !Boolean.TRUE.equals(context.getConfiguration().returnImmediately());
    }

    private void processTaskBlocking(
            RequestContext context, AgentEmitter emitter, Flux<AgentEvent> resultFlux) {
        AgentEventA2aEncoder encoder =
                AgentEventA2aEncoder.blocking(context, agentExecuteProperties, emitter);
        log.info("[{}] Starting blocking request processing", context.getTaskId());
        applyStreamMergeIfEnabled(resultFlux, context)
                .doOnSubscribe(s -> saveSubscription(context.getTaskId(), s))
                .doOnNext(encoder::onNext)
                .doOnError(encoder::onError)
                .onErrorComplete()
                .doOnComplete(encoder::onComplete)
                .doFinally(signal -> removeSubscription(context.getTaskId(), signal))
                .blockLast();
    }

    private void processTaskNonBlocking(
            RequestContext context, AgentEmitter emitter, Task task, Flux<AgentEvent> resultFlux) {
        try {
            emitter.addTask(task);
            log.info("[{}] Starting streaming request processing", context.getTaskId());
            processStreamingOutput(resultFlux, emitter, context);
        } catch (Exception e) {
            log.error("[{}] Error processing streaming output", context.getTaskId(), e);
            emitter.fail(
                    emitter.newAgentMessage(
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
            Flux<AgentEvent> resultFlux, AgentEmitter emitter, RequestContext context) {
        AgentEventA2aEncoder encoder =
                AgentEventA2aEncoder.streaming(context, agentExecuteProperties, emitter);
        applyStreamMergeIfEnabled(resultFlux, context)
                .doOnSubscribe(
                        s -> {
                            saveSubscription(emitter.getTaskId(), s);
                            emitter.startWork();
                        })
                .doOnNext(encoder::onNext)
                .doOnError(encoder::onError)
                .onErrorComplete()
                .doOnComplete(encoder::onComplete)
                .doFinally(signal -> removeSubscription(emitter.getTaskId(), signal))
                .blockLast();
    }

    private Flux<AgentEvent> applyStreamMergeIfEnabled(
            Flux<AgentEvent> resultFlux, RequestContext context) {
        if (!agentExecuteProperties.isStreamMergeEnabled()) {
            return resultFlux;
        }
        int maxSize = Math.max(1, agentExecuteProperties.getStreamMergeMaxSize());
        long intervalMs = Math.max(1L, agentExecuteProperties.getStreamMergeIntervalMs());
        log.info(
                "[{}] A2A stream merge enabled: intervalMs={}, maxSize={}",
                context.getTaskId(),
                intervalMs,
                maxSize);
        AtomicInteger windowIndex = new AtomicInteger(0);
        return AgentEventStreamMergeOperator.merge(
                resultFlux,
                maxSize,
                Duration.ofMillis(intervalMs),
                window -> {
                    int index = windowIndex.incrementAndGet();
                    if (window.reducedEvents() > 0 || window.hitMaxSize()) {
                        log.info(
                                "[{}] A2A stream merge window: index={}, inputEvents={},"
                                        + " outputEvents={}, reducedEvents={}, hitMaxSize={}",
                                context.getTaskId(),
                                index,
                                window.inputEvents(),
                                window.outputEvents(),
                                window.reducedEvents(),
                                window.hitMaxSize());
                    }
                });
    }

    private void saveSubscription(String taskId, Subscription subscription) {
        log.info("[{}] Subscribed to executeFunction result stream", taskId);
        subscriptions.put(taskId, subscription);
    }

    private void removeSubscription(String taskId, SignalType signal) {
        log.info("[{}] Subscribe and process stream output terminated: {}", taskId, signal);
        subscriptions.remove(taskId);
    }
}
