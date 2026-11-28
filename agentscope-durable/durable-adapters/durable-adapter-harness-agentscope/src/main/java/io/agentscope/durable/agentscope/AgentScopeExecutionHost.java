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

package io.agentscope.durable.agentscope;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.agent.AgentExecutionHost;
import io.agentscope.core.agent.AgentExecutionPosition;
import io.agentscope.core.agent.RunOutcome;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.journal.InvocationRecorder;
import io.agentscope.core.journal.ModelInvocation;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelRequestObserver;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.Signal;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.DurableTypes.TurnView;
import io.agentscope.durable.DurableTypes.WaitCondition;
import io.agentscope.durable.HarnessContext;
import io.agentscope.durable.ModelExecutions;
import io.agentscope.durable.ModelReplyProtocol;
import io.agentscope.durable.TransactionalSessionBackend;
import io.agentscope.durable.codec.agentscope.DurableEventSchemas;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/** Coordinates input/control, finalization and completed model replay within one fenced invocation. */
final class AgentScopeExecutionHost implements AgentExecutionHost, ModelInvocation {
    private record Frame(
            String id,
            long startedAt,
            String zone,
            Msg result,
            String stateJson,
            AgentExecutionPosition position) {}

    private final HarnessContext durable;
    private final BackendThreadJournal log;
    private final QueuedModelRequest.Publisher publisher;
    private final ModelExecutions modelExecutions;
    private final String modelRoute;
    private final ToolHookJournal toolHooks;
    private QueuedModelRequest pendingRequest;
    private boolean queuedFallback;

    private static final class RemoteModelFailure extends IllegalStateException {
        RemoteModelFailure(String message) {
            super(message);
        }
    }

    private int pendingIteration;
    private long replyDeadline;
    private long replyProgress = -1;
    private long replyIdleMillis;
    private String workerAttempt = "0";
    private String replyProgressAttempt = "0";
    private WaitCondition waitingForModel;

    WaitCondition waitingForModel() {
        return waitingForModel;
    }

    private Frame frame;
    private long replayAfter;
    private boolean replayingModelReply;
    private final Set<String> replayed = new HashSet<>();
    private boolean completed;
    private boolean yieldingForDrain;
    private boolean waitingForNativeReconciliation;
    private volatile boolean active;
    private RuntimeContext context;
    private final AtomicInteger slot = new AtomicInteger();
    private final AtomicInteger storageSlot = new AtomicInteger();

    @Override
    public Mono<Void> beforeBoundary(Boundary boundary, RuntimeContext runtime, AgentState state) {
        return Mono.defer(
                        () -> {
                            var current = durable.frame();
                            checkControl(current.turn());
                            if (boundary == Boundary.REASONING) {
                                context = runtime;
                                if (pendingRequest != null
                                        && InvocationRecorder.from(runtime)
                                                        .executionPosition()
                                                        .reasoningIteration()
                                                > pendingIteration) pendingRequest = null;
                                if (pendingRequest == null) {
                                    queuedFallback = false;
                                    return toolHooks
                                            .normalizeRecovered(runtime, state)
                                            .defaultIfEmpty(false)
                                            .doOnNext(
                                                    normalized -> {
                                                        var ready =
                                                                normalized
                                                                        ? durable.frame()
                                                                        : current;
                                                        checkControl(ready.turn());
                                                        if (!replayingModelReply)
                                                            AgentScopeToolResults
                                                                    .joinCompletedBatch(
                                                                            state.contextMutable());
                                                        replayingModelReply = false;
                                                        applyInputs(
                                                                runtime, state, ready.signals());
                                                    })
                                            .then();
                                }
                            }
                            return Mono.<Void>empty();
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void checkControl() {
        checkControl(durable.frame().turn());
    }

    boolean yieldingForDrain() {
        return yieldingForDrain;
    }

    boolean waitingForNativeReconciliation() {
        return waitingForNativeReconciliation;
    }

    private void checkControl(TurnView turn) {
        if (turn.cancellationRequested())
            throw new DurableException(
                    DurableException.Code.FENCED, "Cancellation requests a durable handoff");
        if (turn.status() == TurnStatus.PAUSING) throw new AgentExecutionHost.Paused();
        if (durable.draining()) {
            yieldingForDrain = true;
            throw new AgentExecutionHost.Paused();
        }
    }

    private void applyInputs(RuntimeContext runtime, AgentState state, List<Signal> signals) {
        var pending =
                signals.stream()
                        .filter(
                                signal ->
                                        Set.of("input:steer", "input:inject")
                                                .contains(signal.key()))
                        .toList();
        if (pending.isEmpty()) return;
        var recorder = InvocationRecorder.from(runtime);
        if (recorder == null)
            throw new IllegalStateException("Durable input requires a native recorder");
        var known = new HashSet<String>();
        state.getContext().forEach(message -> known.add(message.getId()));
        var ids = new ArrayList<String>();
        for (var signal : pending) {
            String messageId =
                    signal.messageId() == null ? "signal:" + signal.id() : signal.messageId();
            var message = AgentScopeMessages.input(signal.value(), messageId);
            if (known.add(message.getId())) state.contextMutable().add(message);
            ids.add(signal.id());
        }
        log.consuming(ids);
        try {
            recorder.checkpointedRecordNow(
                    state, "durable_input", "input/applied", Map.of("commandIds", ids));
        } catch (RuntimeException conflict) {
            var remaining = durable.frame().signals().stream().map(signal -> signal.id()).toList();
            if (!remaining.containsAll(ids)) {
                durable.stop();
                throw new DurableException(
                        DurableException.Code.FENCED, "Input changed before application", conflict);
            }
            throw conflict;
        }
    }

    String resourceTimestamp() {
        return active ? Instant.ofEpochMilli(frame.startedAt()).toString() : null;
    }

    String resourceIdentity() {
        return active
                ? "finalization:" + frame.id() + ":io:" + storageSlot.getAndIncrement()
                : UUID.randomUUID().toString();
    }

    @Override
    public Mono<ToolPlan> prepareTools(
            RuntimeContext runtime, AgentState state, List<ToolUseBlock> calls) {
        return toolHooks.prepare(runtime, state, calls);
    }

    @Override
    public Mono<ToolResultBlock> normalizeToolResult(
            RuntimeContext runtime, ToolUseBlock call, ToolResultBlock result) {
        var lease = durable.lease();
        if (log.unsettledNativeTool(lease.turnId(), call.getId())) {
            waitingForNativeReconciliation = true;
            return Mono.error(new AgentExecutionHost.Paused());
        }
        String executionId =
                TransactionalSessionBackend.executionId(
                        lease.thread(), lease.turnId(), call.getId());
        return toolHooks.normalize(
                executionId,
                call,
                result,
                fact ->
                        InvocationRecorder.from(runtime)
                                .recordNow(DurableEventSchemas.TOOL_NORMALIZED, fact));
    }

    AgentScopeExecutionHost(
            HarnessContext durable,
            BackendThreadJournal log,
            QueuedModelRequest.Publisher publisher,
            ModelExecutions modelExecutions,
            String modelRoute,
            Duration replyTimeout,
            ToolHookJournal toolHooks) {
        this.durable = durable;
        this.log = log;
        this.publisher = publisher;
        this.modelExecutions = modelExecutions;
        this.modelRoute = modelRoute;
        this.toolHooks = toolHooks;
        this.replyIdleMillis = replyTimeout.toMillis();
        for (var event : log.scan(0, log.head().seq())) {
            if ("state/checkpoint".equals(event.type())) {
                replayAfter = event.seq();
                replayingModelReply = false;
            }
            if (!durable.lease().turnId().equals(event.turnId())) continue;
            var data = event.data();
            // A recorded local reply must replay against its original request, including on
            // upgrade.
            if ("model/end".equals(event.type())
                    && "completed".equals(data.get("status"))
                    && data.containsKey(ModelCapture.OUTCOME)) replayingModelReply = true;
            if (DurableEventSchemas.QUEUED_MODEL.equals(event.type())) {
                pendingRequest =
                        DurableJson.convert(
                                DurableJson.value(data.get("request")), QueuedModelRequest.class);
                pendingIteration = ((Number) data.get("iteration")).intValue();
                queuedFallback = Boolean.TRUE.equals(data.get("fallback"));
                replyDeadline = 0;
                replyProgress = -1;
            } else if (isQueued(data.get("modelCallId"))) {
                if (DurableEventSchemas.MODEL_ACCEPTED.equals(event.type())) {
                    replyDeadline = ((Number) data.get("replyDeadline")).longValue();
                    replyIdleMillis =
                            ((Number) data.getOrDefault("idleMillis", replyIdleMillis)).longValue();
                    replyProgress = ((Number) data.getOrDefault("sequence", -1L)).longValue();
                    replyProgressAttempt = String.valueOf(data.getOrDefault("workerAttempt", "0"));
                }
            }
            // A completed response is still pending until the native state has applied it.
            if (pendingRequest != null
                    && "state/checkpoint".equals(event.type())
                    && data.get("executionPosition") instanceof Map<?, ?> position
                    && position.get("reasoningIteration") instanceof Number iteration
                    && iteration.intValue() > pendingIteration) pendingRequest = null;
            if (DurableEventSchemas.START.equals(event.type())) {
                frame =
                        new Frame(
                                (String) data.get("id"),
                                ((Number) data.get("startedAt")).longValue(),
                                (String) data.get("zone"),
                                JsonUtils.getJsonCodec()
                                        .convertValue(data.get("result"), Msg.class),
                                (String) data.get("stateJson"),
                                JsonUtils.getJsonCodec()
                                        .convertValue(
                                                data.get("executionPosition"),
                                                AgentExecutionPosition.class));
                completed = false;
            } else if (DurableEventSchemas.END.equals(event.type())
                    && frame != null
                    && frame.id().equals(data.get("id"))) completed = true;
            if ("run/end".equals(event.type()) && completed) {
                // A later invocation for accepted input must not replay this finished reply.
                frame = null;
                completed = false;
            }
        }
    }

    boolean pending() {
        return frame != null && !completed;
    }

    void restoreEntry() {
        if (pending())
            log.append(
                    durable.lease().turnId(),
                    List.of(
                            Map.entry(
                                    "state/checkpoint",
                                    Map.of(
                                            "stateJson",
                                            frame.stateJson(),
                                            "reason",
                                            "durable_finalization_replay",
                                            "executionPosition",
                                            frame.position()))));
    }

    public Msg resumeResult() {
        return frame == null ? null : frame.result();
    }

    public Mono<Void> finish(
            RuntimeContext runtime, Msg result, Supplier<Mono<Void>> continuation) {
        if (RunOutcome.fromResult(result).status() != RunOutcome.Status.COMPLETED)
            return continuation.get();
        return Mono.<Void>defer(
                        () -> {
                            if (completed) return Mono.empty();
                            // Preserve an already generated reply before the continuation checks
                            // pause. Replaying this frame must not invoke the primary model again.
                            if (durable.frame().turn().cancellationRequested())
                                throw new DurableException(
                                        DurableException.Code.FENCED,
                                        "Cancellation requests a durable handoff");
                            context = runtime;
                            var recorder = InvocationRecorder.from(runtime);
                            recorder.finalizationStep();
                            if (frame == null) {
                                frame =
                                        new Frame(
                                                UUID.randomUUID().toString(),
                                                System.currentTimeMillis(),
                                                ZoneId.systemDefault().getId(),
                                                result,
                                                runtime.getAgentState().toJson(),
                                                recorder.executionPosition());
                                recorder.append(
                                        DurableEventSchemas.START,
                                        Map.of(
                                                "id",
                                                frame.id(),
                                                "startedAt",
                                                frame.startedAt(),
                                                "zone",
                                                frame.zone(),
                                                "result",
                                                result,
                                                "stateJson",
                                                frame.stateJson(),
                                                "executionPosition",
                                                frame.position()),
                                        true);
                                recorder.checkpointNow(
                                        runtime.getAgentState(), "durable_finalization_start");
                            }
                            Clock previous = runtime.get(Clock.class);
                            runtime.put(
                                    Clock.class,
                                    Clock.fixed(
                                            Instant.ofEpochMilli(frame.startedAt()),
                                            ZoneId.of(frame.zone())));
                            slot.set(0);
                            storageSlot.set(0);
                            active = true;
                            return Mono.defer(continuation)
                                    .then(
                                            Mono.<Void>fromRunnable(
                                                    () -> {
                                                        durable.frame();
                                                        recorder.append(
                                                                DurableEventSchemas.END,
                                                                Map.of("id", frame.id()),
                                                                true);
                                                        recorder.captureState(
                                                                runtime.getAgentState(),
                                                                "durable_finalization_completed");
                                                        completed = true;
                                                    }))
                                    .doFinally(
                                            signal -> {
                                                active = false;
                                                runtime.put(Clock.class, previous);
                                            });
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Flux<ChatResponse> stream(
            String model,
            String purpose,
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions options,
            Function<String, Flux<ChatResponse>> next) {
        return Flux.defer(
                        () -> {
                            checkControl();
                            String hash = requestHash(model, purpose, messages, tools, options);
                            String id = null;
                            long checkpoint = 0;
                            var history = log.scan(0, log.head().seq());
                            if (active) {
                                int index = slot.getAndIncrement();
                                for (var event : history) {
                                    var data = event.data();
                                    if (DurableEventSchemas.MODEL.equals(event.type())
                                            && frame.id().equals(data.get("frame"))
                                            && ((Number) data.get("slot")).intValue() == index) {
                                        if (!hash.equals(data.get("requestHash")))
                                            throw new DurableException(
                                                    DurableException.Code.CONFLICT,
                                                    "Finalization replay request differs");
                                        id = (String) data.get("modelCallId");
                                    }
                                }
                                if (id == null) {
                                    id = UUID.randomUUID().toString();
                                    var recorder = InvocationRecorder.from(context);
                                    recorder.append(
                                            DurableEventSchemas.MODEL,
                                            Map.of(
                                                    "frame",
                                                    frame.id(),
                                                    "slot",
                                                    index,
                                                    "requestHash",
                                                    hash,
                                                    "modelCallId",
                                                    id),
                                            true);
                                    recorder.flushNow();
                                }
                            } else checkpoint = replayAfter;
                            for (var event : history) {
                                if (!durable.lease().turnId().equals(event.turnId())
                                        || !"model/end".equals(event.type())
                                        || !"completed".equals(event.data().get("status")))
                                    continue;
                                var data = event.data();
                                if (!(data.get(ModelCapture.OUTCOME) instanceof Map<?, ?> outcome))
                                    continue;
                                boolean pendingReasoning =
                                        !active
                                                && "REASONING".equals(purpose)
                                                && isQueued(data.get("modelCallId"))
                                                && model.equals(pendingRequest.model())
                                                && InvocationRecorder.from(context)
                                                                .executionPosition()
                                                                .reasoningIteration()
                                                        == pendingIteration;
                                if (!replayed.contains(event.eventId())
                                        && (pendingReasoning
                                                || hash.equals(outcome.get("replayHash"))
                                                        && (active
                                                                ? id.equals(data.get("modelCallId"))
                                                                : event.seq() > checkpoint))) {
                                    replayed.add(event.eventId());
                                    return Flux.fromIterable((List<?>) outcome.get("responses"))
                                            .map(value -> response((Map<?, ?>) value));
                                }
                            }
                            String candidate = id == null ? UUID.randomUUID().toString() : id;
                            String call =
                                    "REASONING".equals(purpose)
                                                    && (publisher != null
                                                            || modelExecutions != null
                                                            || pendingRequest != null)
                                            ? prepareQueued(
                                                    candidate, model, messages, tools, options)
                                            : candidate;
                            log.modelStarted(
                                    call,
                                    new ModelCapture(
                                            model, purpose, messages, tools, options, hash));
                            return Flux.defer(() -> next.apply(call))
                                    .doOnNext(chunk -> log.modelChunk(call, chunk));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private String prepareQueued(
            String id,
            String model,
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions options) {
        var recorder = InvocationRecorder.from(context);
        int iteration = recorder.executionPosition().reasoningIteration();
        if (pendingRequest == null) {
            pendingRequest =
                    QueuedModelRequest.prepare(
                            durable.lease().thread(),
                            durable.lease().turnId(),
                            id,
                            model,
                            messages,
                            tools,
                            options);
            recorder.checkpointedRecordNow(
                    context.getAgentState(),
                    "queued_model",
                    DurableEventSchemas.QUEUED_MODEL,
                    Map.of(
                            "request",
                            pendingRequest,
                            "iteration",
                            iteration,
                            "fallback",
                            queuedFallback));
            pendingIteration = iteration;
            replyDeadline = 0;
            replyProgress = -1;
        }
        if (iteration != pendingIteration || !model.equals(pendingRequest.model()))
            throw new DurableException(
                    DurableException.Code.CONFLICT,
                    "Recovered reasoning position changed for call " + pendingRequest.callId());
        if (replyDeadline > 0 && modelExecutions != null) {
            var key = pendingRequest.executionKey();
            var view = modelExecutions.query(key);
            if (view == null)
                throw new DurableException(
                        DurableException.Code.INCOMPATIBLE, "Admitted model execution is missing");
            if (!view.available()) {
                checkReplyDeadline(durable.frame().now(), 0);
                waitingForModel = new WaitCondition(key.wakeKey(), replyDeadline);
                throw new AgentExecutionHost.Paused();
            }
        } else if (replyDeadline > 0) {
            var frame = durable.frame();
            String first =
                    QueuedModelRequest.key(
                            pendingRequest.callId(),
                            ModelReplyProtocol.active(frame.signals(), pendingRequest.callId()),
                            0);
            if (frame.signals().stream().noneMatch(s -> s.key().equals(first))) {
                checkReplyDeadline(frame.now(), 0);
                waitingForModel = new WaitCondition(first, replyDeadline);
                throw new AgentExecutionHost.Paused();
            }
        }
        return pendingRequest.callId();
    }

    private boolean isQueued(Object callId) {
        return pendingRequest != null && pendingRequest.callId().equals(callId);
    }

    @Override
    public boolean resumingFallback(String purpose) {
        return "REASONING".equals(purpose) && pendingRequest != null && queuedFallback;
    }

    @Override
    public boolean beginFallback(String purpose, Throwable cause) {
        if (!"REASONING".equals(purpose)
                || publisher == null && modelExecutions == null && pendingRequest == null)
            return true;
        if (pendingRequest != null && !(cause instanceof RemoteModelFailure)) return false;
        pendingRequest = null;
        queuedFallback = true;
        return true;
    }

    @Override
    public Route route(Call call) {
        String callId = call.callId();
        if (!isQueued(callId)) return new Direct();
        if (replyDeadline > 0)
            return new Resume(
                    Flux.defer(
                            () -> modelExecutions == null ? responses(callId) : storedResponses()));
        return new Suspend(
                Mono.<Void>fromRunnable(
                                () -> {
                                    if (publisher == null && modelExecutions == null)
                                        throw new DurableException(
                                                DurableException.Code.INCOMPATIBLE,
                                                "Model publisher missing");
                                    try {
                                        if (modelExecutions == null)
                                            publisher.publish(pendingRequest);
                                        else
                                            modelExecutions.submit(
                                                    new ModelExecutions.Request(
                                                            pendingRequest.executionKey(),
                                                            modelRoute,
                                                            DurableJson.value(pendingRequest)));
                                    } catch (DurableException error) {
                                        throw error;
                                    } catch (RuntimeException error) {
                                        throw new DurableException(
                                                DurableException.Code.UNAVAILABLE,
                                                "Model publish acceptance unknown for call "
                                                        + callId,
                                                error);
                                    }
                                    replyDeadline =
                                            Math.addExact(durable.frame().now(), replyIdleMillis);
                                    var recorder = InvocationRecorder.from(context);
                                    recorder.append(
                                            DurableEventSchemas.MODEL_ACCEPTED,
                                            Map.of(
                                                    "modelCallId",
                                                    callId,
                                                    "replyDeadline",
                                                    replyDeadline,
                                                    "idleMillis",
                                                    replyIdleMillis),
                                            true);
                                    recorder.flushNow();
                                    waitingForModel =
                                            new WaitCondition(
                                                    modelExecutions == null
                                                            ? QueuedModelRequest.key(callId, 0)
                                                            : pendingRequest
                                                                    .executionKey()
                                                                    .wakeKey(),
                                                    replyDeadline);
                                })
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private Flux<ChatResponse> responses(String callId) {
        workerAttempt = ModelReplyProtocol.active(durable.frame().signals(), callId);
        if (!workerAttempt.equals(replyProgressAttempt)) {
            replyProgress = -1;
            replyProgressAttempt = workerAttempt;
        }
        var seq = new AtomicLong();
        var buffered = new HashMap<String, Signal>();
        var emptyPolls = new AtomicLong();
        var changed = new AtomicLong();
        var observed = new AtomicLong();
        reactor.core.publisher.Sinks.Many<Long> hints =
                reactor.core.publisher.Sinks.many().replay().latest();
        Flux<ChatResponse> replies =
                Mono.defer(
                                () -> {
                                    String key =
                                            QueuedModelRequest.key(
                                                    callId, workerAttempt, seq.get());
                                    {
                                        observed.set(changed.get());
                                        var frame = durable.frame();
                                        checkControl(frame.turn());
                                        if (!workerAttempt.equals(
                                                ModelReplyProtocol.active(frame.signals(), callId)))
                                            throw new DurableException(
                                                    DurableException.Code.UNAVAILABLE,
                                                    "Model worker attempt replaced for call "
                                                            + callId);
                                        buffered.clear();
                                        for (var signal : frame.signals())
                                            buffered.put(signal.key(), signal);
                                        if (!buffered.containsKey(key))
                                            checkReplyDeadline(frame.now(), seq.get());
                                    }
                                    Signal signal = buffered.remove(key);
                                    if (signal != null && signal.value().path("last").asBoolean())
                                        durable.sealModelReply(
                                                QueuedModelRequest.seal(callId, workerAttempt));
                                    if (signal != null) {
                                        emptyPolls.set(0);
                                        if (seq.get() > replyProgress) {
                                            replyProgress = seq.get();
                                            replyDeadline =
                                                    Math.addExact(
                                                            durable.frame().now(), replyIdleMillis);
                                            var recorder = InvocationRecorder.from(context);
                                            recorder.append(
                                                    DurableEventSchemas.MODEL_ACCEPTED,
                                                    Map.of(
                                                            "modelCallId",
                                                            callId,
                                                            "replyDeadline",
                                                            replyDeadline,
                                                            "sequence",
                                                            replyProgress,
                                                            "idleMillis",
                                                            replyIdleMillis,
                                                            "workerAttempt",
                                                            workerAttempt),
                                                    true);
                                            recorder.flushNow();
                                        }
                                    }
                                    return Mono.justOrEmpty(signal);
                                })
                        .subscribeOn(Schedulers.boundedElastic())
                        .repeatWhenEmpty(
                                empty ->
                                        empty.concatMap(
                                                ignored ->
                                                        Mono.firstWithSignal(
                                                                hints.asFlux()
                                                                        .filter(
                                                                                version ->
                                                                                        version
                                                                                                > observed
                                                                                                        .get())
                                                                        .next(),
                                                                Mono.delay(
                                                                        Duration.ofMillis(
                                                                                Math.min(
                                                                                        1000,
                                                                                        25L
                                                                                                << Math
                                                                                                        .min(
                                                                                                                6,
                                                                                                                emptyPolls
                                                                                                                        .getAndIncrement())))))))
                        .doOnNext(QueuedModelRequest::validateReply)
                        .doOnNext(
                                s -> {
                                    if (s.value().path("retryable").asBoolean())
                                        throw retryQueued(callId);
                                    seq.incrementAndGet();
                                })
                        .repeat()
                        .takeUntil(
                                s -> s.value().path("last").asBoolean() || s.value().has("error"))
                        .handle(
                                (s, sink) -> {
                                    var data = s.value();
                                    if (data.has("error")) {
                                        sink.error(
                                                new RemoteModelFailure(
                                                        "Remote model call "
                                                                + callId
                                                                + " failed: "
                                                                + data.path("error").asText()));
                                    } else if (data.hasNonNull("response"))
                                        sink.next(
                                                response(
                                                        DurableJson.convert(
                                                                data.get("response"), Map.class)));
                                });
        Flux<ChatResponse> notified =
                Flux.using(
                        () -> {
                            try {
                                return durable.wakeups()
                                        .subscribe(
                                                session -> {
                                                    if (session.equals(durable.lease().session()))
                                                        hints.tryEmitNext(
                                                                changed.incrementAndGet());
                                                });
                            } catch (RuntimeException error) {
                                System.getLogger(AgentScopeExecutionHost.class.getName())
                                        .log(
                                                System.Logger.Level.WARNING,
                                                "Model wakeup subscription failed; polling callId="
                                                        + callId,
                                                error);
                                return (AutoCloseable) () -> {};
                            }
                        },
                        subscription -> replies,
                        subscription -> {
                            try {
                                subscription.close();
                            } catch (Exception error) {
                                System.getLogger(AgentScopeExecutionHost.class.getName())
                                        .log(
                                                System.Logger.Level.WARNING,
                                                "Model wakeup subscription close failed callId="
                                                        + callId,
                                                error);
                            }
                        });
        return Flux.deferContextual(
                reactorContext -> {
                    io.agentscope.core.model.ModelRequestObserver observer =
                            reactorContext.getOrDefault(
                                    io.agentscope.core.model.ModelRequestObserver.class, null);
                    return observer == null
                            ? notified
                            : observer.observe(notified, pendingRequest.model(), "queued");
                });
    }

    private record StoredReply(ModelExecutions.Batch batch, ModelExecutions.Outcome end) {}

    private Flux<ChatResponse> storedResponses() {
        var key = pendingRequest.executionKey();
        var seq = new AtomicLong();
        var version = new AtomicLong();
        var generation = new AtomicLong();
        Sinks.Many<Long> hints = Sinks.many().replay().latest();
        var observed = new AtomicLong();
        Flux<ChatResponse> replies =
                Mono.defer(
                                () -> {
                                    observed.set(version.get());
                                    var frame = durable.frame();
                                    checkControl(frame.turn());
                                    var view = modelExecutions.query(key);
                                    if (view == null)
                                        throw new DurableException(
                                                DurableException.Code.INCOMPATIBLE,
                                                "Model execution disappeared");
                                    if (generation.get() == 0) generation.set(view.generation());
                                    if (generation.get() != view.generation())
                                        throw new DurableException(
                                                DurableException.Code.UNAVAILABLE,
                                                "Model generation changed");
                                    if (seq.get() < view.nextSequence()) {
                                        var batch =
                                                modelExecutions.readOutput(
                                                        key, generation.get(), seq.get());
                                        if (batch == null)
                                            throw new DurableException(
                                                    DurableException.Code.INCOMPATIBLE,
                                                    "Committed model batch is missing");
                                        seq.set(batch.nextSequence());
                                        noteStoredProgress(
                                                key.callId(),
                                                generation.get(),
                                                seq.get() - 1,
                                                frame.now());
                                        return Mono.just(new StoredReply(batch, null));
                                    }
                                    if (view.outcome() != null)
                                        return Mono.just(new StoredReply(null, view.outcome()));
                                    checkReplyDeadline(frame.now(), seq.get());
                                    return Mono.<StoredReply>empty();
                                })
                        .subscribeOn(Schedulers.boundedElastic())
                        .repeatWhenEmpty(
                                empty ->
                                        empty.concatMap(
                                                ignored ->
                                                        Mono.firstWithSignal(
                                                                hints.asFlux()
                                                                        .filter(
                                                                                value ->
                                                                                        value
                                                                                                > observed
                                                                                                        .get())
                                                                        .next(),
                                                                Mono.delay(
                                                                        Duration.ofMillis(250)))))
                        .repeat()
                        .takeUntil(reply -> reply.end() != null)
                        .concatMap(
                                reply -> {
                                    if (reply.batch() != null)
                                        return Flux.fromIterable(reply.batch().chunks())
                                                .map(
                                                        chunk ->
                                                                response(
                                                                        DurableJson.convert(
                                                                                chunk, Map.class)));
                                    var end = reply.end();
                                    if (end.status() == ModelExecutions.Status.SUCCEEDED)
                                        return Flux.empty();
                                    if (end.retryable())
                                        return Flux.error(retryQueued(key.callId()));
                                    if (end.status() == ModelExecutions.Status.FAILED)
                                        return Flux.error(
                                                new RemoteModelFailure(
                                                        "Remote model call "
                                                                + key.callId()
                                                                + " failed: "
                                                                + end.error()));
                                    return Flux.error(
                                            new DurableException(
                                                    DurableException.Code.INVALID,
                                                    "Model execution "
                                                            + end.status()
                                                            + " for call "
                                                            + key.callId()
                                                            + ": "
                                                            + end.error()));
                                },
                                1);
        var notified =
                Flux.using(
                        () -> subscribeOutputHints(key, version, hints),
                        ignored -> replies,
                        subscription -> {
                            try {
                                subscription.close();
                            } catch (Exception error) {
                                System.getLogger(AgentScopeExecutionHost.class.getName())
                                        .log(
                                                System.Logger.Level.WARNING,
                                                "Unable to close model wakeups",
                                                error);
                            }
                        });
        return Flux.deferContextual(
                reactorContext -> {
                    ModelRequestObserver observer =
                            reactorContext.getOrDefault(ModelRequestObserver.class, null);
                    return observer == null
                            ? notified
                            : observer.observe(notified, pendingRequest.model(), "queued");
                });
    }

    private AutoCloseable subscribeOutputHints(
            ModelExecutions.Key key, AtomicLong version, Sinks.Many<Long> hints) {
        try {
            return durable.wakeups()
                    .subscribe(
                            session -> {
                                if (session.equals(key.thread().session()))
                                    hints.tryEmitNext(version.incrementAndGet());
                            });
        } catch (RuntimeException error) {
            System.getLogger(AgentScopeExecutionHost.class.getName())
                    .log(System.Logger.Level.WARNING, "Model wakeups unavailable; polling", error);
            return () -> {};
        }
    }

    private void noteStoredProgress(String callId, long generation, long sequence, long now) {
        String attempt = Long.toString(generation);
        if (attempt.equals(replyProgressAttempt) && sequence <= replyProgress) return;
        replyProgressAttempt = attempt;
        replyProgress = sequence;
        replyDeadline = Math.addExact(now, replyIdleMillis);
        var recorder = InvocationRecorder.from(context);
        recorder.append(
                DurableEventSchemas.MODEL_ACCEPTED,
                Map.of(
                        "modelCallId",
                        callId,
                        "replyDeadline",
                        replyDeadline,
                        "idleMillis",
                        replyIdleMillis,
                        "workerAttempt",
                        attempt,
                        "sequence",
                        replyProgress),
                true);
        recorder.flushNow();
    }

    private DurableException retryQueued(String callId) {
        var old = pendingRequest;
        pendingRequest =
                new QueuedModelRequest(
                        old.thread(),
                        old.turnId(),
                        UUID.randomUUID().toString(),
                        old.model(),
                        old.messages(),
                        old.tools(),
                        old.options());
        InvocationRecorder.from(context)
                .checkpointedRecordNow(
                        context.getAgentState(),
                        "queued_model_retry",
                        DurableEventSchemas.QUEUED_MODEL,
                        Map.of(
                                "request",
                                pendingRequest,
                                "iteration",
                                pendingIteration,
                                "fallback",
                                queuedFallback));
        return new DurableException(
                DurableException.Code.UNAVAILABLE, "Retryable provider failure for call " + callId);
    }

    private void checkReplyDeadline(long now, long sequence) {
        if (now >= replyDeadline)
            throw new DurableException(
                    DurableException.Code.INVALID,
                    "Model reply deadline exceeded for call "
                            + pendingRequest.callId()
                            + " at sequence "
                            + sequence);
    }

    static String requestHash(
            String model,
            String purpose,
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions options) {
        var normalized = new ArrayList<Object>();
        for (var message : messages) {
            var node = (ObjectNode) DurableJson.parse(JsonUtils.getJsonCodec().toJson(message));
            normalize(node);
            normalized.add(node);
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("model", model);
        data.put("purpose", purpose);
        data.put("messages", normalized);
        data.put("tools", tools);
        data.put("options", options);
        return DurableJson.hash(data);
    }

    private static void normalize(ObjectNode message) {
        message.remove(List.of("id", "timestamp"));
        var originals = message.path("metadata").path("agentscope.context.original_system_inputs");
        if (originals.isArray())
            for (var original : originals) if (original instanceof ObjectNode node) normalize(node);
    }

    private static ChatResponse response(Map<?, ?> data) {
        var content = new ArrayList<ContentBlock>();
        if (data.get("content") instanceof List<?> blocks)
            for (var block : blocks)
                content.add(JsonUtils.getJsonCodec().convertValue(block, ContentBlock.class));
        @SuppressWarnings("unchecked")
        var metadata = (Map<String, Object>) data.get("metadata");
        return ChatResponse.builder()
                .id((String) data.get("id"))
                .content(content)
                .usage(
                        data.get("usage") == null
                                ? null
                                : JsonUtils.getJsonCodec()
                                        .convertValue(data.get("usage"), ChatUsage.class))
                .metadata(metadata)
                .finishReason((String) data.get("finishReason"))
                .build();
    }
}
