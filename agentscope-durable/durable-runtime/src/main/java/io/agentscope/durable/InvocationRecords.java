/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.durable;

import io.agentscope.durable.DurableTypes.EventInput;
import io.agentscope.durable.DurableTypes.ModelCallView;
import io.agentscope.durable.DurableTypes.ModelObservation;
import io.agentscope.durable.DurableTypes.ModelRequestView;
import io.agentscope.durable.DurableTypes.ModelStatus;
import io.agentscope.durable.DurableTypes.RunChange;
import io.agentscope.durable.DurableTypes.RunStatus;
import io.agentscope.durable.DurableTypes.RunStepRef;
import io.agentscope.durable.DurableTypes.RunView;
import io.agentscope.durable.DurableTypes.StepChange;
import io.agentscope.durable.DurableTypes.StepStatus;
import io.agentscope.durable.DurableTypes.StepView;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.TokenUsage;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.TransactionalSessionBackend.TurnData;
import io.agentscope.durable.store.RecordStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Invocation history within the owning Backend transaction, never a task scheduler. */
final class InvocationRecords {
    public static final class Invocation {
        public RunView view;
        public int stepCount;
        public String activeStep;
        public long modelCount;
    }

    public static final class ModelCall {
        public String turnId;
        public String runId;
        public String stepId;
        public String id;
        public String model;
        public String purpose;
        public int iteration;
        public long startedAt;
        public Long endedAt;
        public ModelStatus status = ModelStatus.RUNNING;
        public TokenUsage usage;
        public List<ModelRequestView> requests = new ArrayList<>();
    }

    private static String modelKey(String runId, String id) {
        return "model-call:" + DurableJson.hash(List.of(runId, id));
    }

    String modelOrder(String runId, String stepId) {
        require(runId);
        if (stepId != null) step(runId, stepId);
        return "model-order:"
                + DurableJson.hash(List.of(runId, stepId == null ? "" : stepId))
                + ":";
    }

    private ModelCall requireModel(String runId, String id) {
        require(runId);
        var value = tx.get(modelKey(runId, id));
        if (value == null) throw failure(DurableException.Code.NOT_FOUND, "Model call not found");
        var call = DurableJson.convert(value, ModelCall.class);
        if (!turn.id.equals(call.turnId) || !runId.equals(call.runId))
            throw failure(DurableException.Code.NOT_FOUND, "Model call belongs to another Turn");
        return call;
    }

    ModelCallView modelCall(String runId, String id) {
        var call = requireModel(runId, id);
        boolean lost =
                call.status == ModelStatus.RUNNING && require(runId).view.status().terminal();
        var requests =
                call.requests.stream()
                        .map(
                                request ->
                                        lost && request.status() == ModelStatus.RUNNING
                                                ? new ModelRequestView(
                                                        request.requestId(),
                                                        request.attempt(),
                                                        request.provider(),
                                                        ModelStatus.LOST,
                                                        request.startedAt(),
                                                        null,
                                                        null,
                                                        request.usage(),
                                                        false,
                                                        null)
                                                : request)
                        .toList();
        var usage =
                requests.isEmpty()
                        ? call.usage
                        : requests.stream()
                                .map(ModelRequestView::usage)
                                .filter(Objects::nonNull)
                                .reduce(TokenUsage::plus)
                                .orElse(null);
        boolean complete =
                !lost
                        && call.status == ModelStatus.COMPLETED
                        && !requests.isEmpty()
                        && requests.stream().allMatch(ModelRequestView::usageComplete);
        return new ModelCallView(
                thread,
                turn.id,
                runId,
                call.stepId,
                call.id,
                call.model,
                call.purpose,
                call.iteration,
                call.startedAt,
                call.endedAt,
                lost ? ModelStatus.LOST : call.status,
                requests.isEmpty()
                        ? ModelObservation.MODEL_CALL
                        : ModelObservation.PROVIDER_ATTEMPTS,
                usage,
                complete,
                requests);
    }

    private void modelEvent(EventInput event, List<StepChange> changes) {
        var run = require(event.runId());
        owned(run);
        if (run.view.status() != RunStatus.RUNNING)
            throw failure(
                    DurableException.Code.CONFLICT, "Model facts require a running invocation");
        if (event.stepId() != null) {
            var step = step(event.runId(), event.stepId());
            if (step.status().terminal()
                    && changes.stream().noneMatch(change -> change.stepId().equals(event.stepId())))
                throw failure(DurableException.Code.CONFLICT, "Model Step has already ended");
        }
        var data = event.data();
        String id = data.required("modelCallId").asText();
        String key = modelKey(event.runId(), id);
        ModelCall call;
        if (event.type().equals("model.call.started")) {
            if (tx.get(key) != null)
                throw failure(DurableException.Code.CONFLICT, "Model call identity reused");
            call = new ModelCall();
            call.turnId = turn.id;
            call.runId = event.runId();
            call.stepId = event.stepId();
            call.id = id;
            call.model = data.required("model").asText();
            call.purpose = data.required("purpose").asText();
            call.iteration = data.required("iteration").asInt();
            call.startedAt = data.required("startedAt").asLong();
            long index = ++run.modelCount;
            tx.put(modelOrder(call.runId, null) + position(index), DurableJson.value(id));
            if (call.stepId != null)
                tx.put(
                        modelOrder(call.runId, call.stepId) + position(index),
                        DurableJson.value(id));
            tx.put(runKey(call.runId), DurableJson.value(run));
        } else {
            call = requireModel(event.runId(), id);
            if (!Objects.equals(call.stepId, event.stepId()) || call.status != ModelStatus.RUNNING)
                throw failure(
                        DurableException.Code.CONFLICT, "Model call has ended or changed Step");
            if (event.type().equals("model.call.ended")) {
                if (call.requests.stream()
                        .anyMatch(request -> request.status() == ModelStatus.RUNNING))
                    throw failure(DurableException.Code.CONFLICT, "Model requests have not ended");
                call.status = ModelStatus.valueOf(data.required("status").asText());
                call.endedAt = data.required("endedAt").asLong();
                if (call.endedAt < call.startedAt)
                    throw failure(
                            DurableException.Code.INVALID, "Model call ended before its start");
                call.usage = DurableJson.convert(data.get("usage"), TokenUsage.class);
            } else {
                String requestId = data.required("requestId").asText();
                var old =
                        call.requests.stream()
                                .filter(request -> request.requestId().equals(requestId))
                                .findFirst()
                                .orElse(null);
                if (event.type().equals("model.request.started")) {
                    int attempt = data.required("attempt").asInt();
                    if (old != null
                            || attempt != call.requests.size() + 1
                            || call.requests.stream()
                                    .anyMatch(request -> request.status() == ModelStatus.RUNNING))
                        throw failure(
                                DurableException.Code.CONFLICT,
                                "Model request order or identity changed");
                    call.requests.add(
                            new ModelRequestView(
                                    requestId,
                                    attempt,
                                    data.required("provider").asText(),
                                    ModelStatus.RUNNING,
                                    data.required("startedAt").asLong(),
                                    null,
                                    null,
                                    null,
                                    false,
                                    null));
                } else {
                    if (old == null || old.status() != ModelStatus.RUNNING)
                        throw failure(
                                DurableException.Code.CONFLICT,
                                "Model request is missing or ended");
                    var usage = DurableJson.convert(data.get("usage"), TokenUsage.class);
                    boolean ended = event.type().equals("model.request.ended");
                    var status =
                            ended
                                    ? ModelStatus.valueOf(data.required("status").asText())
                                    : ModelStatus.RUNNING;
                    Long endedAt = ended ? data.required("endedAt").asLong() : null;
                    boolean complete = ended && data.required("usageComplete").asBoolean();
                    if (endedAt != null && endedAt < old.startedAt()
                            || complete
                                    && (status != ModelStatus.COMPLETED
                                            || usage == null
                                            || usage.inputTokens() == null
                                            || usage.outputTokens() == null))
                        throw failure(
                                DurableException.Code.INVALID, "Invalid model request completion");
                    var next =
                            new ModelRequestView(
                                    requestId,
                                    old.attempt(),
                                    old.provider(),
                                    status,
                                    old.startedAt(),
                                    endedAt,
                                    ended ? data.required("durationMillis").asLong() : null,
                                    usage,
                                    complete,
                                    ended ? data.path("errorType").asText(null) : null);
                    call.requests.set(call.requests.indexOf(old), next);
                }
            }
        }
        tx.put(key, DurableJson.value(call));
    }

    private final RecordStore.Transaction tx;
    private final ThreadKey thread;
    private final TurnData turn;
    private final Consumer<EventInput> events;

    InvocationRecords(
            RecordStore.Transaction tx,
            ThreadKey thread,
            TurnData turn,
            Consumer<EventInput> events) {
        this.tx = tx;
        this.thread = thread;
        this.turn = turn;
        this.events = events;
    }

    private static String runKey(String runId) {
        return "invocation:" + DurableJson.hash(runId);
    }

    private static String stepKey(String stepId) {
        return "step:" + DurableJson.hash(stepId);
    }

    String runOrder() {
        return "invocation-order:" + DurableJson.hash(turn.id) + ":";
    }

    String stepOrder(String runId) {
        require(runId);
        return "step-order:" + DurableJson.hash(runId) + ":";
    }

    private static String position(long position) {
        return String.format(Locale.ROOT, "%020d", position);
    }

    private Invocation require(String runId) {
        DurableJson.text(runId, "runId");
        var data = tx.get(runKey(runId));
        if (data == null) throw failure(DurableException.Code.NOT_FOUND, "Run not found");
        var run = DurableJson.convert(data, Invocation.class);
        if (!thread.equals(run.view.thread()) || !turn.id.equals(run.view.turnId()))
            throw failure(DurableException.Code.NOT_FOUND, "Run does not belong to this Turn");
        return run;
    }

    RunView run(String runId) {
        return require(runId).view;
    }

    private StepView step(String stepId) {
        var data = tx.get(stepKey(stepId));
        if (data == null) throw failure(DurableException.Code.NOT_FOUND, "Step not found");
        var step = DurableJson.convert(data, StepView.class);
        if (!thread.equals(step.thread()) || !turn.id.equals(step.turnId()))
            throw failure(DurableException.Code.NOT_FOUND, "Step does not belong to this Turn");
        return step;
    }

    StepView step(String runId, String stepId) {
        require(runId);
        var step = step(stepId);
        if (!runId.equals(step.runId()))
            throw failure(DurableException.Code.NOT_FOUND, "Step belongs to another Run");
        return step;
    }

    RunView prepare(String requestId, long now) {
        DurableJson.text(requestId, "invocation requestId");
        String receiptKey = "invocation-request:" + DurableJson.hash(List.of(turn.id, requestId));
        var receipt = tx.get(receiptKey);
        if (receipt != null) {
            var previous = require(receipt.asText());
            owned(previous);
            return previous.view;
        }
        if (turn.cancel
                || turn.status == TurnStatus.PAUSING
                || turn.failedEpoch == turn.epoch
                || turn.recovery != null && !turn.recovery.retryAllowed())
            throw failure(DurableException.Code.CONFLICT, "Turn is stopping");
        if (turn.lastRunId != null && !require(turn.lastRunId).view.status().terminal())
            throw failure(DurableException.Code.CONFLICT, "Previous Run has not ended");
        var run = new Invocation();
        String id = "run_" + UUID.randomUUID();
        run.view =
                new RunView(
                        thread,
                        turn.id,
                        id,
                        requestId,
                        turn.owner,
                        turn.epoch,
                        turn.lastRunId,
                        RunStatus.PREPARED,
                        now,
                        null,
                        null,
                        null,
                        null);
        turn.lastRunId = id;
        tx.put(runKey(id), DurableJson.value(run));
        tx.put(runOrder() + position(++turn.invocationCount), DurableJson.value(id));
        tx.put(receiptKey, DurableJson.value(id));
        emitRun(run.view, "prepared");
        return run.view;
    }

    private void owned(Invocation run) {
        if (run.view.epoch() != turn.epoch || !Objects.equals(run.view.owner(), turn.owner))
            throw failure(DurableException.Code.FENCED, "Run belongs to a previous owner");
    }

    void commit(
            RunChange change,
            List<StepChange> steps,
            List<EventInput> modelEvents,
            List<RunStepRef> effectPositions,
            long now) {
        if (change != null && change.status() == RunStatus.RUNNING) changeRun(change, now);
        for (var step : steps) changeStep(step, now);
        for (var event : modelEvents) modelEvent(event, steps);
        for (var position : effectPositions) validateEffectPosition(position, steps);
        if (change != null && change.status().terminal()) changeRun(change, now);
    }

    private void validateEffectPosition(RunStepRef position, List<StepChange> changes) {
        if (position == null) {
            if (turn.lastRunId != null && !require(turn.lastRunId).view.status().terminal())
                throw failure(
                        DurableException.Code.INVALID,
                        "Active invocation requires a tool position");
            // Native cleanup outside an Agent invocation must not fabricate a Run or Step.
            return;
        }
        var run = require(position.runId());
        owned(run);
        if (run.view.status() != RunStatus.RUNNING)
            throw failure(
                    DurableException.Code.CONFLICT, "Tool facts require a running invocation");
        if (position.stepId() != null) {
            var step = step(position.runId(), position.stepId());
            if (step.status().terminal()
                    && changes.stream()
                            .noneMatch(change -> change.stepId().equals(position.stepId())))
                throw failure(DurableException.Code.CONFLICT, "Tool Step has already ended");
        }
    }

    private void changeRun(RunChange change, long now) {
        var run = require(change.runId());
        owned(run);
        var old = run.view;
        if (old.status().terminal())
            throw failure(DurableException.Code.CONFLICT, "A terminal Run cannot reopen");
        if (change.status() == RunStatus.RUNNING && old.status() != RunStatus.PREPARED)
            throw failure(DurableException.Code.CONFLICT, "Run has already started");
        if (change.status().terminal() && old.status() != RunStatus.RUNNING)
            throw failure(DurableException.Code.CONFLICT, "Run has not started");
        if (change.status().terminal() && run.activeStep != null)
            throw failure(DurableException.Code.CONFLICT, "Active Step must end with its Run");
        run.view =
                new RunView(
                        thread,
                        turn.id,
                        old.runId(),
                        old.requestId(),
                        old.owner(),
                        old.epoch(),
                        old.previousRunId(),
                        change.status(),
                        old.preparedAt(),
                        change.status() == RunStatus.RUNNING ? now : old.startedAt(),
                        change.status().terminal() ? now : null,
                        change.outcome(),
                        change.reason());
        tx.put(runKey(old.runId()), DurableJson.value(run));
        emitRun(run.view, change.status().terminal() ? "ended" : "started");
    }

    private void changeStep(StepChange change, long now) {
        var run = require(change.runId());
        owned(run);
        if (run.view.status() != RunStatus.RUNNING)
            throw failure(DurableException.Code.CONFLICT, "Step requires a running invocation");
        var value = tx.get(stepKey(change.stepId()));
        StepView old = value == null ? null : step(change.stepId());
        if (old != null && (!old.runId().equals(change.runId()) || old.status().terminal()))
            throw failure(DurableException.Code.CONFLICT, "Step identity cannot be reused");
        if (old == null) {
            if (change.status() != StepStatus.RUNNING || run.activeStep != null)
                throw failure(
                        DurableException.Code.CONFLICT,
                        "Step must start after the prior Step ends");
            if (change.resumedFromStepId() != null) {
                var source = step(change.resumedFromStepId());
                if (source.runId().equals(change.runId())
                        || source.status() == StepStatus.RUNNING
                        || source.status() == StepStatus.COMPLETED)
                    throw failure(
                            DurableException.Code.CONFLICT, "Invalid Step continuation source");
            }
            run.activeStep = change.stepId();
            run.stepCount++;
            tx.put(
                    stepOrder(change.runId()) + position(run.stepCount),
                    DurableJson.value(change.stepId()));
        } else if (!Objects.equals(old.resumedFromStepId(), change.resumedFromStepId())
                || change.phase().ordinal() < old.phase().ordinal()) {
            throw failure(DurableException.Code.CONFLICT, "Step phase or continuation changed");
        }
        var next =
                new StepView(
                        thread,
                        turn.id,
                        change.runId(),
                        change.stepId(),
                        old == null ? run.stepCount : old.index(),
                        change.status(),
                        change.phase(),
                        change.resumedFromStepId(),
                        old == null ? now : old.startedAt(),
                        change.status().terminal() ? now : null,
                        change.reason());
        if (change.status().terminal()) run.activeStep = null;
        tx.put(stepKey(change.stepId()), DurableJson.value(next));
        tx.put(runKey(change.runId()), DurableJson.value(run));
        emitStep(
                next,
                old == null ? "started" : change.status().terminal() ? "ended" : "phase_changed");
    }

    /** Ownership expiry cannot fabricate a native completion; an explicit release records a stop. */
    void abandon(long now, boolean lost) {
        stop(now, lost, false);
    }

    void fail(long now) {
        stop(now, false, true);
    }

    private void stop(long now, boolean lost, boolean failed) {
        if (turn.lastRunId == null) return;
        var run = require(turn.lastRunId);
        if (run.view.status().terminal()) return;
        boolean paused =
                !lost && (turn.status == TurnStatus.PAUSING || turn.status == TurnStatus.PAUSED);
        String reason =
                lost
                        ? "ownership_lost"
                        : failed ? "invocation_failed" : paused ? "paused" : "host_released";
        if (run.activeStep != null) {
            var old = step(run.activeStep);
            var ended =
                    new StepView(
                            thread,
                            turn.id,
                            old.runId(),
                            old.stepId(),
                            old.index(),
                            lost
                                    ? StepStatus.LOST
                                    : failed ? StepStatus.FAILED : StepStatus.STOPPED,
                            old.phase(),
                            old.resumedFromStepId(),
                            old.startedAt(),
                            now,
                            reason);
            tx.put(stepKey(old.stepId()), DurableJson.value(ended));
            run.activeStep = null;
            emitStep(ended, "ended");
        }
        var old = run.view;
        run.view =
                new RunView(
                        thread,
                        turn.id,
                        old.runId(),
                        old.requestId(),
                        old.owner(),
                        old.epoch(),
                        old.previousRunId(),
                        lost
                                ? RunStatus.LOST
                                : failed && old.startedAt() != null
                                        ? RunStatus.FAILED
                                        : RunStatus.STOPPED,
                        old.preparedAt(),
                        old.startedAt(),
                        now,
                        failed ? "failed" : paused ? "suspended" : "interrupted",
                        reason);
        tx.put(runKey(old.runId()), DurableJson.value(run));
        emitRun(run.view, lost ? "lost" : "ended");
    }

    private void emitRun(RunView run, String action) {
        events.accept(
                new EventInput(
                        "run:" + run.runId() + ":" + action,
                        "run." + action,
                        DurableJson.value(run),
                        run.runId(),
                        null));
    }

    private void emitStep(StepView step, String action) {
        events.accept(
                new EventInput(
                        "step:" + step.stepId() + ":" + action + ":" + step.phase(),
                        "step." + action,
                        DurableJson.value(step),
                        step.runId(),
                        step.stepId()));
    }

    private static DurableException failure(DurableException.Code code, String message) {
        return new DurableException(code, message);
    }
}
