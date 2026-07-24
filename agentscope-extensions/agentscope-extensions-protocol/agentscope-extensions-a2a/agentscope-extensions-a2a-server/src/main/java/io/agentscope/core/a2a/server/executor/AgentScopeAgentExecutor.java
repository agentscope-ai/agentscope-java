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
import io.agentscope.core.a2a.server.hitl.HitlAdmissionTicket;
import io.agentscope.core.a2a.server.hitl.HitlEncodingContext;
import io.agentscope.core.a2a.server.hitl.HitlLeaseHandle;
import io.agentscope.core.a2a.server.hitl.HitlResumeCoordinator;
import io.agentscope.core.a2a.server.hitl.HitlResumeRejectedException;
import io.agentscope.core.a2a.server.hitl.HitlServerProperties;
import io.agentscope.core.a2a.server.hitl.HitlSessionLease;
import io.agentscope.core.a2a.server.hitl.LocalHitlSessionLease;
import io.agentscope.core.a2a.server.hitl.ResolvedAgentRequestMetadata;
import io.agentscope.core.a2a.server.utils.MessageConvertUtil;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
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

    private final HitlResumeCoordinator hitlCoordinator;

    private final HitlServerProperties hitlProperties;

    private final HitlSessionLease hitlSessionLease;

    public AgentScopeAgentExecutor(
            AgentRunner agentRunner, AgentExecuteProperties agentExecuteProperties) {
        this(
                agentRunner,
                agentExecuteProperties,
                null,
                null,
                HitlServerProperties.builder().build());
    }

    public AgentScopeAgentExecutor(
            AgentRunner agentRunner,
            AgentExecuteProperties agentExecuteProperties,
            HitlResumeCoordinator hitlCoordinator,
            HitlServerProperties hitlProperties) {
        this(
                agentRunner,
                agentExecuteProperties,
                hitlCoordinator,
                new LocalHitlSessionLease(),
                hitlProperties);
    }

    public AgentScopeAgentExecutor(
            AgentRunner agentRunner,
            AgentExecuteProperties agentExecuteProperties,
            HitlResumeCoordinator hitlCoordinator,
            HitlSessionLease hitlSessionLease,
            HitlServerProperties hitlProperties) {
        this.agentRunner = agentRunner;
        this.agentExecuteProperties = agentExecuteProperties;
        this.hitlCoordinator = hitlCoordinator;
        this.hitlSessionLease = hitlSessionLease;
        this.hitlProperties =
                hitlProperties == null ? HitlServerProperties.builder().build() : hitlProperties;
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
        HitlAdmissionTicket ticket = null;
        try {
            ticket = executionTicket(context);
            AgentRequestOptions requestOptions = ticket.request().requestOptions();
            HitlLeaseHandle activeLease = ticket.lease();
            if (activeLease != null) {
                activeLease.onLost(() -> agentRunner.stop(context.getTaskId()));
                requireLease(activeLease);
            }
            if (ticket.operation() == HitlAdmissionTicket.Operation.CANCEL) {
                ticket.claimForExecution();
                emitter.cancel();
                ticket.completeCancel();
                return;
            }
            List<Msg> inputMessages =
                    ticket.preparedMessages() != null
                            ? ticket.preparedMessages()
                            : MessageConvertUtil.convertFromMessageToMsgs(context.getMessage());
            Flux<AgentEvent> resultFlux = agentRunner.streamEvents(inputMessages, requestOptions);
            if (activeLease != null) {
                resultFlux =
                        resultFlux
                                .doOnNext(ignored -> requireLease(activeLease))
                                .doOnComplete(() -> requireLease(activeLease));
            }

            Task task = context.getTask();
            if (task == null) {
                task = newTask(context);
                log.info("[{}] Created new task.", task.id());
            } else {
                log.info("[{}] Using existing task.", task.id());
            }
            if (isBlockRequest(context)) {
                processTaskBlocking(context, emitter, resultFlux, ticket.encodingContext());
            } else {
                processTaskNonBlocking(
                        context, emitter, task, resultFlux, ticket.encodingContext());
            }
            log.info("[{}] Agent execution completed successfully", context.getTaskId());
        } catch (Exception e) {
            if (ticket != null) {
                ticket.markRecoveryRequired();
            }
            log.error("[{}] Agent execution failed", context.getTaskId(), e);
            emitter.fail(
                    A2A.createAgentTextMessage(
                            "Agent execution failed: " + e.getMessage(),
                            context.getContextId(),
                            context.getTaskId()));
        } finally {
            if (ticket != null) {
                ticket.closeExecution();
            }
        }
    }

    private HitlAdmissionTicket executionTicket(RequestContext context) {
        HitlAdmissionTicket prepared = HitlAdmissionTicket.find(context).orElse(null);
        if (prepared == null) {
            return legacyNormalTicket(context);
        }
        try {
            return prepared.take(context);
        } catch (RuntimeException | Error takeFailure) {
            prepared.abort();
            throw takeFailure;
        }
    }

    private HitlAdmissionTicket legacyNormalTicket(RequestContext context) {
        ResolvedAgentRequestMetadata request =
                ResolvedAgentRequestMetadata.resolve(context, agentRunner.getAgentName());
        if (hasText(request.operation())) {
            throw new HitlResumeRejectedException(
                    "HITL resume and cancel require AgentScopeA2aRequestHandler admission");
        }
        HitlLeaseHandle lease = null;
        try {
            HitlEncodingContext encodingContext = null;
            if (hitlProperties.enabled()) {
                lease =
                        hitlSessionLease.acquire(
                                request.executionKey(), hitlProperties.executionLeaseTtl());
                requireLease(lease);
                if (hitlCoordinator.hasOpenHandoff(request.executionKey())) {
                    throw new HitlResumeRejectedException(
                            "Session has an open HITL handoff; resume or cancel it first");
                }
                encodingContext =
                        new HitlEncodingContext(
                                hitlCoordinator,
                                request.executionKey(),
                                request.nextResumeToken(),
                                hitlProperties.handoffTtl(),
                                null);
            }
            return HitlAdmissionTicket.normal(request, encodingContext, lease, hitlCoordinator)
                    .take(context);
        } catch (RuntimeException failure) {
            if (lease != null) {
                lease.close();
            }
            throw failure;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private void requireLease(HitlLeaseHandle lease) {
        if (lease == null || !lease.isValid()) {
            throw new HitlResumeRejectedException("A2A session execution lease was lost");
        }
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
            RequestContext context,
            AgentEmitter emitter,
            Flux<AgentEvent> resultFlux,
            HitlEncodingContext hitlEncodingContext) {
        AgentEventA2aEncoder encoder =
                AgentEventA2aEncoder.blocking(
                        context, agentExecuteProperties, emitter, hitlEncodingContext);
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
            RequestContext context,
            AgentEmitter emitter,
            Task task,
            Flux<AgentEvent> resultFlux,
            HitlEncodingContext hitlEncodingContext) {
        emitter.addTask(task);
        emitter.startWork();
        log.info("[{}] Starting streaming request processing", context.getTaskId());
        processStreamingOutput(resultFlux, emitter, context, hitlEncodingContext);
    }

    /**
     * Process streaming output data
     */
    private void processStreamingOutput(
            Flux<AgentEvent> resultFlux,
            AgentEmitter emitter,
            RequestContext context,
            HitlEncodingContext hitlEncodingContext) {
        AgentEventA2aEncoder encoder =
                AgentEventA2aEncoder.streaming(
                        context, agentExecuteProperties, emitter, hitlEncodingContext);
        applyStreamMergeIfEnabled(resultFlux, context)
                .doOnSubscribe(s -> saveSubscription(emitter.getTaskId(), s))
                .doOnNext(encoder::onNext)
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
