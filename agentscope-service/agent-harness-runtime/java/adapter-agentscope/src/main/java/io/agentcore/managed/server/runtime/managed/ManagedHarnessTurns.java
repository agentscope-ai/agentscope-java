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
package io.agentcore.managed.server.runtime.managed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore.Input;
import io.agentcore.managed.server.runtime.managed.ManagedInputInbox.Type;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeBatch.InputReceipt;
import io.agentcore.managed.server.web.managed.AgentSessionInput;
import io.agentcore.managed.server.web.managed.SessionToolResultContent;
import io.agentscope.core.agent.AgentExecutionPosition;
import io.agentscope.core.agent.AgentFinalization;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionInteractions;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.core.session.SessionModelInvocation;
import io.agentscope.core.session.SessionProjection;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.session.SessionTurns;
import io.agentscope.core.shutdown.AgentShuttingDownException;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Service lifecycle for one execution attempt; long tools yield a durable continuation. */
public final class ManagedHarnessTurns {
    private static final Logger log = LoggerFactory.getLogger(ManagedHarnessTurns.class);
    private static final String AGENT_FAILURE = "managed_agent_execution_failed";
    private final ObjectMapper mapper;
    private final ManagedInputDelivery inputs;
    private final ScheduledExecutorService controls;

    public ManagedHarnessTurns(
            ObjectMapper mapper, ManagedInputDelivery inputs, ScheduledExecutorService controls) {
        this.mapper = mapper;
        this.inputs = inputs;
        this.controls = controls;
    }

    /** Blocking entry point retained for direct callers; MQ uses runAsync. */
    public void run(ManagedTurnExecution execution, HarnessAgent agent, RuntimeContext base) {
        ManagedExecutionAwait.run(
                stop -> runAsync(execution, agent, base, Schedulers.boundedElastic(), stop));
    }

    /** A cancelled, externally settled Turn must not depend on MCP/model initialization. */
    public boolean cancelBeforePreparation(ManagedTurnExecution execution) {
        return cancelBeforePreparation(execution, Set::of);
    }

    public boolean cancelBeforePreparation(
            ManagedTurnExecution execution, Supplier<Set<String>> pinnedMcpTools) {
        if (!execution.journal().cancelRequested(execution.scope(), execution.turnId())
                || !execution.local().pendingTools().isEmpty()
                || execution.journal().toolsUnsettled(execution.scope(), execution.turnId()))
            return false;
        if (!SessionProjection.read(execution.log()).uncertainToolCalls().isEmpty()
                && !ManagedNativeCancellation.cancelUnadmittedMcp(execution, pinnedMcpTools.get()))
            return false;
        inputs.refill(execution.scope(), execution.turnId(), 0, 256);
        finish(execution, "cancelled");
        return true;
    }

    public Mono<Void> runAsync(
            ManagedTurnExecution execution,
            HarnessAgent agent,
            RuntimeContext base,
            Scheduler scheduler,
            Mono<Throwable> interrupted) {
        return ManagedExecutionTrace.observe(
                execution.scope(),
                execution.turnId(),
                () -> {
                    var attempt = new Attempt(execution, agent, base, scheduler, interrupted);
                    return Mono.defer(
                                    () -> {
                                        attempt.initialize();
                                        return attempt.next();
                                    })
                            .subscribeOn(scheduler)
                            .onErrorResume(
                                    RuntimeException.class,
                                    stopped ->
                                            Mono.<Void>fromRunnable(
                                                            () ->
                                                                    handleFailure(
                                                                            execution,
                                                                            agent,
                                                                            base,
                                                                            attempt.currentRun,
                                                                            stopped))
                                                    .subscribeOn(scheduler));
                });
    }

    private void handleFailure(
            ManagedTurnExecution execution,
            HarnessAgent agent,
            RuntimeContext base,
            AtomicReference<String> currentRun,
            RuntimeException stopped) {
        if (ManagedToolPublicationPending.contains(stopped)) throw stopped;
        execution.check();
        var waiting = ManagedToolWaiting.find(stopped);
        if (waiting != null) {
            execution.suspend(waiting.taskId());
            return;
        }
        ManagedRuntimeLog.info(
                log,
                "harness_runtime.run.exception",
                execution.scope(),
                execution.turnId(),
                "run_id",
                currentRun.get(),
                "exception_type",
                stopped.getClass().getName(),
                "cause_type",
                stopped.getCause() == null ? null : stopped.getCause().getClass().getName());
        // Core records failed runs for transport errors and internal yields too. Only an
        // Agent failure with a complete checkpoint becomes a Service failure decision.
        for (Throwable cause = stopped; cause != null; cause = cause.getCause())
            if (cause instanceof AgentShuttingDownException
                    || cause instanceof RejectedExecutionException
                    || cause instanceof SessionLogException
                    || cause instanceof ManagedStateException
                    || cause instanceof ManagedTransportException
                    || cause instanceof SessionServiceClient.Unavailable
                    || cause instanceof SessionServiceClient.Rejected
                    || cause instanceof InterruptedException
                    || cause instanceof CancellationException) throw stopped;
        var latest = SessionTurns.read(execution.log()).latest();
        if (currentRun.get() == null
                || latest == null
                || !currentRun.get().equals(latest.runId())
                || !execution.turnId().equals(latest.turnId())
                || !"failed".equals(latest.status())
                || execution.head().checkpoint() == null) throw stopped;
        if (!hasFailureDecision(execution)) recordFailure(execution);
        finishFailure(execution, agent, base);
    }

    private final class Attempt {
        private final ManagedTurnExecution execution;
        private final HarnessAgent agent;
        private RuntimeContext base;
        private final Scheduler scheduler;
        private final Mono<Throwable> interrupted;
        private final AtomicReference<String> currentRun = new AtomicReference<>();
        private ManagedFinalization finalization;
        private List<Msg> messages;

        Attempt(
                ManagedTurnExecution execution,
                HarnessAgent agent,
                RuntimeContext base,
                Scheduler scheduler,
                Mono<Throwable> interrupted) {
            this.execution = execution;
            this.agent = agent;
            this.base = base;
            this.scheduler = scheduler;
            this.interrupted = interrupted;
        }

        void initialize() {
            execution.check();
            finalization = new ManagedFinalization(execution, mapper);
            finalization.restoreEntry();
            base =
                    RuntimeContext.builder(base)
                            .put(AgentFinalization.class, finalization)
                            .put(SessionModelInvocation.class, finalization)
                            .build();
            // Framework middleware can use Tool Worker before Core opens its first run recorder.
            // Give those effects the same durable recovery baseline as normal tool calls.
            if (execution.head().checkpoint() == null) {
                var head = execution.head();
                if (head.sequence() != 0)
                    throw new ManagedStateException(
                            "checkpoint_required",
                            "Existing native events have no recovery baseline");
                String batch = "initial_" + UUID.randomUUID();
                var state =
                        base.getAgentState() == null
                                ? agent.getDelegate().getAgentState(base)
                                : base.getAgentState();
                var checkpoint =
                        new SessionEvent(
                                1,
                                batch,
                                1,
                                System.currentTimeMillis(),
                                "state/checkpoint",
                                batch,
                                execution.turnId(),
                                true,
                                JsonUtils.getJsonCodec()
                                        .toJson(
                                                Map.of(
                                                        "stateJson",
                                                        state.toJson(),
                                                        "reason",
                                                        "managed_initialization")));
                execution.boundary(batch, List.of(checkpoint), List.of(), List.of(), null);
            }
            if (hasFailureDecision(execution)) {
                finishFailure(execution, agent, base);
                return;
            }
            var turn = execution.admission().turn(execution.scope(), execution.turnId());
            messages =
                    AgentSessionInput.messages(
                            execution
                                    .journal()
                                    .resolveInput(execution.scope(), payload(turn.input())),
                            turn.id());
        }

        Mono<Void> next() {
            return Mono.defer(
                            () -> {
                                if (execution.handedOff()) return Mono.empty();
                                execution.check();
                                var latest =
                                        SessionTurns.read(execution.log()).turns().stream()
                                                .filter(
                                                        value ->
                                                                execution
                                                                        .turnId()
                                                                        .equals(value.turnId()))
                                                .reduce((a, b) -> b);
                                if (latest.isPresent()
                                        && "completed".equals(latest.get().status())
                                        && !finalization.pending()) {
                                    finish(execution, "succeeded");
                                    return Mono.empty();
                                }
                                if (execution
                                        .journal()
                                        .cancelRequested(execution.scope(), execution.turnId())) {
                                    inputs.refill(execution.scope(), execution.turnId(), 0, 256);
                                    ManagedToolRecovery.reconcile(execution, agent, base);
                                    finish(execution, "cancelled");
                                    return Mono.empty();
                                }
                                ManagedToolRecovery.reconcile(execution, agent, base);
                                var replyInputs = new ArrayList<Input>();
                                if (!SessionInteractions.pending(execution.log()).isEmpty()) {
                                    messages = awaitReplies(execution, replyInputs);
                                    if (execution.handedOff()) return Mono.empty();
                                    if (messages.isEmpty()) return next();
                                }
                                var context =
                                        RuntimeContext.builder(base)
                                                .runId(UUID.randomUUID().toString())
                                                .put(
                                                        AgentExecutionPosition.class,
                                                        execution.local().executionPosition())
                                                .build();
                                var result = new AtomicReference<Msg>();
                                var failure = new AtomicReference<Throwable>();
                                var outcome = new AtomicReference<>("aborted");
                                ManagedRuntimeLog.checkpoint(
                                        log,
                                        "harness_runtime.run.started",
                                        execution.scope(),
                                        execution.turnId(),
                                        execution.head().checkpoint(),
                                        "run_id",
                                        context.getRunId(),
                                        "epoch",
                                        execution.epoch());
                                currentRun.set(context.getRunId());
                                var run = agent.prepareRun(messages, context);
                                var nextRefill = new AtomicLong();
                                var control =
                                        controls.scheduleWithFixedDelay(
                                                () -> {
                                                    try {
                                                        execution.guard().check();
                                                        if (System.nanoTime() >= nextRefill.get()) {
                                                            inputs.refill(
                                                                    execution.scope(),
                                                                    execution.turnId(),
                                                                    0,
                                                                    256);
                                                            if (execution
                                                                    .journal()
                                                                    .cancelRequested(
                                                                            execution.scope(),
                                                                            execution.turnId()))
                                                                run.cancel();
                                                            nextRefill.set(
                                                                    System.nanoTime()
                                                                            + Duration.ofSeconds(2)
                                                                                    .toNanos());
                                                        }
                                                        if (inputs.cancelled(
                                                                execution.scope(),
                                                                execution.turnId())) run.cancel();
                                                    } catch (RuntimeException unavailable) {
                                                        failure.compareAndSet(null, unavailable);
                                                        run.cancel();
                                                    }
                                                },
                                                100,
                                                200,
                                                TimeUnit.MILLISECONDS);
                                var stop =
                                        interrupted.subscribe(
                                                error -> {
                                                    failure.compareAndSet(null, error);
                                                    run.cancel();
                                                });
                                return run.stream()
                                        .doOnNext(
                                                event -> {
                                                    if (event instanceof AgentResultEvent value
                                                            && (event.getSource() == null
                                                                    || event.getSource().isBlank()))
                                                        result.set(value.getResult());
                                                })
                                        .then()
                                        .doOnCancel(run::cancel)
                                        .publishOn(scheduler)
                                        .doOnSuccess(ignored -> outcome.set("returned"))
                                        .onErrorResume(
                                                RuntimeException.class,
                                                stopped -> {
                                                    if (failure.get() != null)
                                                        return Mono.error(failure.get());
                                                    execution.check();
                                                    var waiting = ManagedToolWaiting.find(stopped);
                                                    if (waiting != null) {
                                                        outcome.set("suspended");
                                                        if (!replyInputs.isEmpty())
                                                            settleReplies(execution, replyInputs);
                                                        execution.suspend(waiting.taskId());
                                                        return Mono.empty();
                                                    }
                                                    return execution
                                                                    .journal()
                                                                    .cancelRequested(
                                                                            execution.scope(),
                                                                            execution.turnId())
                                                            ? Mono.empty()
                                                            : Mono.error(stopped);
                                                })
                                        .doOnTerminate(
                                                () -> {
                                                    control.cancel(false);
                                                    stop.dispose();
                                                    ManagedRuntimeLog.checkpoint(
                                                            log,
                                                            "harness_runtime.run.exited",
                                                            execution.scope(),
                                                            execution.turnId(),
                                                            execution.head().checkpoint(),
                                                            "run_id",
                                                            context.getRunId(),
                                                            "epoch",
                                                            execution.epoch(),
                                                            "outcome",
                                                            outcome.get());
                                                })
                                        .doOnCancel(
                                                () -> {
                                                    control.cancel(false);
                                                    stop.dispose();
                                                })
                                        .then(
                                                Mono.defer(
                                                        () -> {
                                                            if (failure.get() != null)
                                                                return Mono.error(failure.get());
                                                            if (execution.handedOff())
                                                                return Mono.empty();
                                                            execution.check();
                                                            if (!replyInputs.isEmpty())
                                                                settleReplies(
                                                                        execution, replyInputs);
                                                            if (execution
                                                                    .journal()
                                                                    .cancelRequested(
                                                                            execution.scope(),
                                                                            execution.turnId())) {
                                                                inputs.refill(
                                                                        execution.scope(),
                                                                        execution.turnId(),
                                                                        0,
                                                                        256);
                                                                ManagedToolRecovery.reconcile(
                                                                        execution, agent, base);
                                                                finish(execution, "cancelled");
                                                                return Mono.empty();
                                                            }
                                                            if (!SessionInteractions.pending(
                                                                            execution.log())
                                                                    .isEmpty()) {
                                                                messages = List.of();
                                                                return next();
                                                            }
                                                            String status =
                                                                    result.get() != null
                                                                                    && "completed"
                                                                                            .equals(
                                                                                                    SessionRecorder
                                                                                                            .outcome(
                                                                                                                    result.get()
                                                                                                                            .getGenerateReason()))
                                                                            ? "succeeded"
                                                                            : "failed";
                                                            if ("failed".equals(status)) {
                                                                recordFailure(execution);
                                                                finishFailure(
                                                                        execution, agent, base);
                                                            } else finish(execution, status);
                                                            return Mono.empty();
                                                        }));
                            })
                    .then()
                    .subscribeOn(scheduler);
        }
    }

    private boolean hasFailureDecision(ManagedTurnExecution execution) {
        var log = execution.log();
        for (var event : log.scanMetadata(0, log.head().seq()))
            if (execution.turnId().equals(event.turnId())
                    && "turn/failed".equals(event.type())
                    && AGENT_FAILURE.equals(event.data().get("reason"))) return true;
        return false;
    }

    private void recordFailure(ManagedTurnExecution execution) {
        var head = execution.head();
        if (head.checkpoint() == null)
            throw new ManagedStateException(
                    "checkpoint_required", "Agent failure needs a complete checkpoint");
        String batch = "agent_failure_" + UUID.randomUUID();
        var checkpoint =
                new SessionEvent(
                        1,
                        batch + "_state",
                        head.sequence() + 1,
                        System.currentTimeMillis(),
                        "state/checkpoint",
                        batch,
                        execution.turnId(),
                        true,
                        JsonUtils.getJsonCodec()
                                .toJson(
                                        Map.of(
                                                "stateJson",
                                                execution
                                                        .journal()
                                                        .objects()
                                                        .state(head.checkpoint()),
                                                "reason",
                                                AGENT_FAILURE)));
        var failure =
                new SessionEvent(
                        1,
                        batch + "_failure",
                        head.sequence() + 2,
                        System.currentTimeMillis(),
                        "turn/failed",
                        batch,
                        execution.turnId(),
                        true,
                        JsonUtils.getJsonCodec()
                                .toJson(Map.of("status", "failed", "reason", AGENT_FAILURE)));
        execution.boundary(batch, List.of(checkpoint, failure), List.of(), List.of(), null);
    }

    private void finishFailure(
            ManagedTurnExecution execution, HarnessAgent agent, RuntimeContext base) {
        try {
            ManagedToolRecovery.reconcile(execution, agent, base);
        } catch (RuntimeException stopped) {
            var waiting = ManagedToolWaiting.find(stopped);
            if (waiting == null) throw stopped;
            execution.suspend(waiting.taskId());
            return;
        }
        var pending = execution.local().pendingTools();
        if (!pending.isEmpty()) {
            execution.suspend(pending.get(0));
            return;
        }
        finish(execution, "failed");
    }

    private List<Msg> awaitReplies(ManagedTurnExecution execution, List<Input> accepted) {
        var requests = SessionInteractions.pending(execution.log());
        execution.check();
        if (execution.journal().cancelRequested(execution.scope(), execution.turnId()))
            return List.of();
        var pending =
                inputs.pending(execution.scope(), execution.turnId(), 0, 256).stream()
                        .filter(input -> !execution.inputSettled(input.id()))
                        .toList();
        if (pending.stream().anyMatch(value -> value.type() == Type.CANCEL)) return List.of();
        var messages = new ArrayList<Msg>();
        for (var input : pending) {
            if (input.type() != Type.REPLY) continue;
            execution.local().inputPosition(input.id(), input.sequence());
            var request = requests.get(input.actionId());
            if (request == null) continue;
            var call =
                    JsonUtils.getJsonCodec()
                            .convertValue(request.data().get("call"), ToolUseBlock.class);
            var answer = payload(input.payload());
            String id = "reply_" + input.id() + "_" + execution.epoch();
            if ("confirmation".equals(request.data().get("kind"))) {
                if (!(answer.get("allow") instanceof Boolean))
                    throw new ManagedStateException(
                            "invalid_confirmation",
                            "Confirmation reply requires allow=true or false");
                messages.add(
                        UserMessage.builder()
                                .id(id)
                                .metadata(
                                        Map.of(
                                                Msg.METADATA_CONFIRM_RESULTS,
                                                List.of(
                                                        new ConfirmResult(
                                                                Boolean.TRUE.equals(
                                                                        answer.get("allow")),
                                                                call,
                                                                null,
                                                                answer.get("reason")
                                                                                instanceof
                                                                                String reason
                                                                        ? reason
                                                                        : null))))
                                .build());
            } else {
                var result = SessionToolResultContent.blocks(answer);
                boolean error = Boolean.TRUE.equals(answer.get("is_error"));
                messages.add(
                        ToolResultMessage.builder()
                                .id(id)
                                .result(
                                        ToolResultBlock.of(
                                                        call.getId(),
                                                        call.getName(),
                                                        result,
                                                        Map.of("error", error))
                                                .withState(
                                                        error
                                                                ? ToolResultState.ERROR
                                                                : ToolResultState.SUCCESS))
                                .build());
            }
            accepted.add(input);
        }
        if (!messages.isEmpty()) return List.copyOf(messages);
        execution.suspendInputs(List.copyOf(requests.keySet()));
        return List.of();
    }

    private void settleReplies(ManagedTurnExecution execution, List<Input> replied) {
        var pending = SessionInteractions.pending(execution.log());
        var receipts =
                replied.stream()
                        .filter(value -> !pending.containsKey(value.actionId()))
                        .map(value -> new InputReceipt(value.id(), "applied", null))
                        .toList();
        if (!receipts.isEmpty()) commitReceipts(execution, receipts);
    }

    private void finish(ManagedTurnExecution execution, String status) {
        execution.closeInputs();
        while (true) {
            var pending =
                    inputs.pending(execution.scope(), execution.turnId(), 0, 256).stream()
                            .filter(input -> !execution.inputSettled(input.id()))
                            .toList();
            if (pending.isEmpty()) break;
            for (var input : pending) execution.local().inputPosition(input.id(), input.sequence());
            var receipts =
                    pending.stream()
                            .map(
                                    value -> {
                                        boolean cancelled =
                                                value.type() == Type.CANCEL
                                                        && "cancelled".equals(status);
                                        return new InputReceipt(
                                                value.id(),
                                                cancelled ? "applied" : "rejected",
                                                cancelled
                                                        ? null
                                                        : "turn_finished_before_input_boundary");
                                    })
                            .toList();
            commitReceipts(execution, receipts);
        }
        // Release the active Harness while Tool Worker settles remaining work. Its result
        // transaction
        // schedules Resume; terminal commit still independently verifies all external outcomes.
        var pendingTools = execution.local().pendingTools();
        if (!pendingTools.isEmpty()) {
            execution.suspend(pendingTools.get(0));
            return;
        }
        if ("cancelled".equals(status)) ManagedNativeCancellation.settle(execution);
        execution.finish(status);
    }

    private void commitReceipts(ManagedTurnExecution execution, List<InputReceipt> receipts) {
        var head = execution.head();
        String state = execution.journal().objects().state(head.checkpoint());
        if (state == null) state = AgentState.builder().build().toJson();
        String batch = "inputs_" + UUID.randomUUID();
        var checkpoint =
                new SessionEvent(
                        1,
                        batch,
                        head.sequence() + 1,
                        System.currentTimeMillis(),
                        "state/checkpoint",
                        batch,
                        execution.turnId(),
                        true,
                        JsonUtils.getJsonCodec()
                                .toJson(
                                        Map.of(
                                                "stateJson",
                                                state,
                                                "reason",
                                                "managed_settlement")));
        execution.boundary(batch, List.of(checkpoint), List.of(), receipts, null);
    }

    private Map<String, Object> payload(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception invalid) {
            throw new IllegalStateException("Invalid accepted payload", invalid);
        }
    }
}
