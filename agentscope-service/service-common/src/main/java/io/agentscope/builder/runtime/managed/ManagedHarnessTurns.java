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
package io.agentscope.builder.runtime.managed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.managed.ManagedAdmissionStore.Input;
import io.agentscope.builder.runtime.managed.ManagedInputInbox.Type;
import io.agentscope.builder.runtime.managed.ManagedRuntimeBatch.InputReceipt;
import io.agentscope.builder.web.managed.AgentSessionInput;
import io.agentscope.builder.web.managed.SessionToolResultContent;
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
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.session.SessionTurns;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.middleware.MemoryFlushMiddleware;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public void run(ManagedTurnExecution execution, HarnessAgent agent, RuntimeContext base) {
        try {
            runLoop(execution, agent, base);
        } catch (RuntimeException stopped) {
            if (ManagedToolPublicationPending.contains(stopped)) throw stopped;
            execution.check();
            var waiting = ManagedToolWaiting.find(stopped);
            if (waiting != null) {
                execution.suspend(waiting.taskId());
                return;
            }
            // Core records failed runs for transport errors and internal yields too. Only an
            // Agent failure with a complete checkpoint becomes a Service failure decision.
            for (Throwable cause = stopped; cause != null; cause = cause.getCause())
                if (cause instanceof SessionLogException
                        || cause instanceof ManagedStateException
                        || cause instanceof ManagedTransportException
                        || cause instanceof InterruptedException
                        || cause instanceof CancellationException) throw stopped;
            var latest = SessionTurns.read(execution.log()).latest();
            if (latest == null
                    || !execution.turnId().equals(latest.turnId())
                    || !"failed".equals(latest.status())
                    || execution.head().checkpoint() == null) throw stopped;
            if (!hasFailureDecision(execution)) recordFailure(execution);
            finishFailure(execution, agent, base);
        }
    }

    private void runLoop(ManagedTurnExecution execution, HarnessAgent agent, RuntimeContext base) {
        execution.check();
        var finalization = new ManagedFinalization(execution, mapper);
        finalization.restoreEntry();
        base =
                RuntimeContext.builder(base)
                        .put(MemoryFlushMiddleware.INLINE_CONTEXT_KEY, true)
                        .put(AgentFinalization.class, finalization)
                        .put(SessionModelInvocation.class, finalization)
                        .build();
        // Framework middleware can use Hands before Core opens its first run recorder.
        // Give those effects the same durable recovery baseline as normal tool calls.
        if (execution.head().checkpoint() == null) {
            var head = execution.head();
            if (head.sequence() != 0)
                throw new ManagedStateException(
                        "checkpoint_required", "Existing native events have no recovery baseline");
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
        var messages =
                AgentSessionInput.messages(
                        execution
                                .journal()
                                .files()
                                .resolveInput(execution.scope(), payload(turn.input())),
                        turn.id());
        while (true) {
            execution.check();
            var latest =
                    SessionTurns.read(execution.log()).turns().stream()
                            .filter(value -> execution.turnId().equals(value.turnId()))
                            .reduce((a, b) -> b);
            if (latest.isPresent()
                    && "completed".equals(latest.get().status())
                    && !finalization.pending()) {
                finish(execution, "succeeded");
                return;
            }
            if (execution.journal().cancelRequested(execution.scope(), execution.turnId())) {
                inputs.refill(execution.scope(), execution.turnId(), 0, 256);
                ManagedToolRecovery.reconcile(execution, agent, base);
                finish(execution, "cancelled");
                return;
            }
            ManagedToolRecovery.reconcile(execution, agent, base);
            var replyInputs = new ArrayList<Input>();
            if (!SessionInteractions.pending(execution.log()).isEmpty()) {
                messages = awaitReplies(execution, replyInputs);
                if (execution.handedOff()) return;
                if (messages.isEmpty()) continue;
            }
            var context =
                    RuntimeContext.builder(base)
                            .runId(UUID.randomUUID().toString())
                            .put(
                                    AgentExecutionPosition.class,
                                    execution.local().executionPosition())
                            .build();
            var result = new AtomicReference<Msg>();
            var failure = new AtomicReference<RuntimeException>();
            ManagedRuntimeLog.checkpoint(
                    log,
                    "brain.run.started",
                    execution.scope(),
                    execution.turnId(),
                    execution.head().checkpoint(),
                    "run_id",
                    context.getRunId(),
                    "epoch",
                    execution.epoch());
            var run = agent.prepareRun(messages, context);
            var nextRefill = new AtomicLong();
            var control =
                    controls.scheduleWithFixedDelay(
                            () -> {
                                try {
                                    execution.guard().check();
                                    if (System.nanoTime() >= nextRefill.get()) {
                                        inputs.refill(
                                                execution.scope(), execution.turnId(), 0, 256);
                                        if (execution
                                                .journal()
                                                .cancelRequested(
                                                        execution.scope(), execution.turnId()))
                                            run.interrupt();
                                        nextRefill.set(
                                                System.nanoTime()
                                                        + Duration.ofSeconds(2).toNanos());
                                    }
                                    if (inputs.cancelled(execution.scope(), execution.turnId()))
                                        run.interrupt();
                                } catch (RuntimeException unavailable) {
                                    failure.compareAndSet(null, unavailable);
                                    run.cancel();
                                }
                            },
                            100,
                            200,
                            TimeUnit.MILLISECONDS);
            String runOutcome = "aborted";
            try {
                run.stream()
                        .doOnNext(
                                event -> {
                                    if (event instanceof AgentResultEvent value
                                            && (event.getSource() == null
                                                    || event.getSource().isBlank()))
                                        result.set(value.getResult());
                                })
                        .then()
                        .block();
                runOutcome = "returned";
            } catch (RuntimeException stopped) {
                if (failure.get() != null) throw failure.get();
                execution.check();
                var waiting = ManagedToolWaiting.find(stopped);
                if (waiting != null) {
                    runOutcome = "suspended";
                    if (!replyInputs.isEmpty()) settleReplies(execution, replyInputs);
                    execution.suspend(waiting.taskId());
                    return;
                }
                if (!execution.journal().cancelRequested(execution.scope(), execution.turnId()))
                    throw stopped;
            } finally {
                control.cancel(false);
                ManagedRuntimeLog.checkpoint(
                        log,
                        "brain.run.exited",
                        execution.scope(),
                        execution.turnId(),
                        execution.head().checkpoint(),
                        "run_id",
                        context.getRunId(),
                        "epoch",
                        execution.epoch(),
                        "outcome",
                        runOutcome);
            }
            execution.check();
            if (!replyInputs.isEmpty()) settleReplies(execution, replyInputs);
            if (execution.journal().cancelRequested(execution.scope(), execution.turnId())) {
                inputs.refill(execution.scope(), execution.turnId(), 0, 256);
                ManagedToolRecovery.reconcile(execution, agent, base);
                finish(execution, "cancelled");
                return;
            }
            if (!SessionInteractions.pending(execution.log()).isEmpty()) {
                messages = List.of();
                continue;
            }
            String outcome =
                    result.get() == null
                            ? "failed"
                            : "completed"
                                            .equals(
                                                    SessionRecorder.outcome(
                                                            result.get().getGenerateReason()))
                                    ? "succeeded"
                                    : "failed";
            if ("failed".equals(outcome)) {
                recordFailure(execution);
                finishFailure(execution, agent, base);
            } else finish(execution, outcome);
            return;
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
        // Release the active Harness while Hands settles remaining work. Its result
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
