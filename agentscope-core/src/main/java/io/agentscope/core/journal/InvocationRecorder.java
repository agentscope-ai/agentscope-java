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
package io.agentscope.core.journal;

import io.agentscope.core.agent.AgentExecutionPosition;
import io.agentscope.core.agent.RunOutcome;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/** Per-execution ordered acceptance and acknowledged checkpoints. Buffer retained on failure. */
public final class InvocationRecorder {
    public static final String CONTEXT_KEY = "agentscope.journal.recorder";
    public static final String PARENT_KEY = "agentscope.journal.parent";
    private final AtomicBoolean exporting = new AtomicBoolean();
    private static final Duration LEASE = Duration.ofMinutes(2);
    private final ThreadJournal log;
    private final ThreadJournal.Writer writer;
    private final String runId;
    private final String turnId;
    private final List<JournalEvent> pending = new ArrayList<>();
    private long durableSeq;
    private int pendingBytes;
    private Throwable failure;
    private boolean closed;
    private RunOutcome resultOutcome;
    private String resultJson;
    private String stepId;
    private String stepPhase;
    private String stepSource;
    private String interruptedStep;
    private AgentExecutionPosition executionPosition = new AgentExecutionPosition(0, 0);
    private AgentExecutionPosition previousPosition;
    private String batchId;
    private final ReentrantLock writerLock = new ReentrantLock();
    private final AtomicBoolean renewing = new AtomicBoolean();
    private long acceptedVersion;
    private long flushedVersion;
    private Mono<Void> flushing;
    private Disposable delayedFlush;
    private final Map<String, String> actionIds = new ConcurrentHashMap<>();
    private final Map<String, String> messageModelCalls = new ConcurrentHashMap<>();
    private String previousState;
    private List<String> previousMessages = List.of();
    private final Set<String> recordedMessages = new HashSet<>();
    private final Set<String> inputMessages = new HashSet<>();
    private final Disposable heartbeat;
    private final JournalProjection initial;
    private final JournalExporter exporter;
    private static final Logger LOGGER = LoggerFactory.getLogger(InvocationRecorder.class);

    public InvocationRecorder(ThreadJournal log, String turnId) {
        this(log, turnId, null);
    }

    public InvocationRecorder(ThreadJournal log, String turnId, JournalExportSink sink) {
        this(log, turnId, UUID.randomUUID().toString(), sink);
    }

    public InvocationRecorder(
            ThreadJournal log, String turnId, String runId, JournalExportSink sink) {
        this.runId = Objects.requireNonNull(runId, "runId");
        if (runId.isBlank() || (turnId != null && turnId.isBlank()))
            throw new IllegalArgumentException("Run and turn IDs must not be blank");
        this.exporter = sink == null ? null : new JournalExporter(log, sink);
        this.log = log;
        this.turnId = turnId;
        this.writer = log.acquire(runId, LEASE);
        try {
            var history = new InvocationHistory(turnId);
            initial =
                    JournalProjection.read(
                            log,
                            event -> {
                                if (runId.equals(event.executionRunId()))
                                    throw new JournalException(
                                            "Run ID already used; prepare a new execution to"
                                                    + " resume");
                                history.accept(event);
                                for (var message : ThreadHistory.messages(log, event))
                                    recordedMessages.add(message.getId());
                            });
            durableSeq = initial.asOfSeq();
            if (!initial.uncertainToolCalls().isEmpty())
                throw new JournalException(
                        "Explicit reconciliation required for unknown tool outcomes: "
                                + initial.uncertainToolCalls());
            previousState = initial.stateJson();
            boolean continuing = history.continues(initial);
            interruptedStep = continuing ? history.interruptedStep : null;
            if (continuing && initial.executionPosition() != null) {
                executionPosition = initial.executionPosition();
                previousPosition = executionPosition;
            }
            if (previousState != null) previousMessages = messageJson(initial.restore());
        } catch (RuntimeException e) {
            log.release(writer);
            throw e;
        }
        // Lease renewal is independent of dirty data and never runs on model-request workers.
        heartbeat =
                JournalSchedulers.timer()
                        .schedulePeriodically(this::renewLater, 30, 30, TimeUnit.SECONDS);
    }

    /** Reads committed invocation boundaries; absent Turn IDs never establish continuity. */
    private static final class InvocationHistory {
        private final String turnId;
        private String latestRun;
        private String latestTurn;
        private boolean finished;
        private String interruptedStep;

        private InvocationHistory(String turnId) {
            this.turnId = turnId;
        }

        private void accept(JournalEvent event) {
            var data = event.data();
            if (Set.of("state/initialized", "state/restored").contains(event.type())) {
                latestRun = null;
                latestTurn = null;
                interruptedStep = null;
                finished = true;
            }
            if ("run/start".equals(event.type())) {
                if (turnId == null && (finished || event.turnId() != null)) interruptedStep = null;
                latestRun = event.executionRunId();
                latestTurn = event.turnId();
                finished = false;
            }
            boolean belongs =
                    turnId != null
                            ? turnId.equals(event.turnId())
                            : latestRun != null
                                    && latestTurn == null
                                    && latestRun.equals(event.executionRunId());
            if (!belongs) return;
            if ("step/start".equals(event.type())) interruptedStep = (String) data.get("stepId");
            if ("step/end".equals(event.type())
                    && Set.of("completed", "continued").contains(data.get("status")))
                interruptedStep = null;
            if ("run/end".equals(event.type())
                    && Objects.equals(latestRun, event.executionRunId())) {
                finished =
                        Set.of("completed", "cancelled").contains(data.get("status"))
                                || "MAX_ITERATIONS".equals(data.get("reason"))
                                || "ALL_TOOLS_DENIED".equals(data.get("reason"));
                if (turnId == null && finished) interruptedStep = null;
            }
        }

        private boolean continues(JournalProjection projection) {
            return turnId != null
                    ? turnId.equals(projection.positionTurnId())
                    : latestRun != null
                            && latestTurn == null
                            && !finished
                            && latestRun.equals(projection.positionRunId());
        }
    }

    private void renewLater() {
        synchronized (this) {
            if (closed || failure != null || !renewing.compareAndSet(false, true)) return;
        }
        try {
            JournalSchedulers.leases()
                    .schedule(
                            () -> {
                                try {
                                    if (!writerLock.tryLock())
                                        return; // A commit already validates/renews this writer.
                                    try {
                                        synchronized (this) {
                                            if (closed || failure != null) return;
                                        }
                                        log.renew(writer, LEASE);
                                    } finally {
                                        writerLock.unlock();
                                    }
                                } catch (Throwable error) {
                                    failed(error);
                                } finally {
                                    renewing.set(false);
                                }
                            });
        } catch (RuntimeException rejected) {
            renewing.set(false);
            failed(rejected);
        }
    }

    private synchronized void failed(Throwable error) {
        if (failure == null) failure = error;
    }

    // Called under the short recorder monitor; at most one delayed dirty notification exists.
    private void scheduleFlush() {
        if (delayedFlush != null) return;
        delayedFlush =
                JournalSchedulers.timer()
                        .schedule(
                                () -> {
                                    synchronized (this) {
                                        delayedFlush = null;
                                        if (closed || failure != null || pending.isEmpty()) return;
                                    }
                                    flush().subscribe(ignored -> {}, this::failed);
                                },
                                1,
                                TimeUnit.SECONDS);
    }

    private void cancelDelayedFlush() {
        if (delayedFlush != null) delayedFlush.dispose();
        delayedFlush = null;
    }

    public static InvocationRecorder from(RuntimeContext rc) {
        return rc != null && rc.get(CONTEXT_KEY) instanceof InvocationRecorder recorder
                ? recorder
                : null;
    }

    /** Parent completion is accepted only while that parent's writer is still active. */
    public void recordIfOpen(String type, Object payload) {
        writerLock.lock();
        try {
            synchronized (this) {
                if (closed) return;
            }
            recordNow(type, payload);
        } finally {
            writerLock.unlock();
        }
    }

    public Set<String> acceptedMessageIds() {
        return Set.copyOf(recordedMessages);
    }

    public JournalProjection initial() {
        return initial;
    }

    /**
     * Seeds an empty main Thread from state written by an AgentScope 2.0 AgentStateStore. The
     * baseline is committed with the invocation start, so a crash cannot leave a partial import.
     */
    public synchronized void importBaseline(AgentState state, String source, long sourceVersion) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(source, "source");
        if (initial.stateJson() != null
                || pending.stream()
                        .anyMatch(
                                event ->
                                        event.type().equals("migration/baseline")
                                                || event.type().equals("state/initialized")))
            throw new JournalException("Thread already has a state baseline");
        append(
                "migration/baseline",
                Map.of(
                        "stateJson",
                        state.toJson(),
                        "source",
                        source,
                        "sourceVersion",
                        sourceVersion));
    }

    public String runId() {
        return runId;
    }

    public String turnId() {
        return turnId;
    }

    /** The active Step, or null outside a recorded reasoning/acting/finalization boundary. */
    public synchronized String stepId() {
        return stepId;
    }

    public synchronized long durableSeq() {
        return durableSeq;
    }

    private void check() {
        if (closed) throw new JournalException("Session recorder closed");
        if (failure != null) throw new JournalException("Session persistence failed", failure);
    }

    public synchronized long append(String type, Object payload) {
        return append(type, payload, true);
    }

    public synchronized long append(String type, Object payload, boolean required) {
        check();
        if (payload instanceof Map<?, ?> data) {
            if (type.equals("model/chunk") && data.get("chunk") instanceof ChatResponse chunk)
                messageModelCalls.put(chunk.getId(), String.valueOf(data.get("modelCallId")));
            Object message = data.get("message");
            String messageId =
                    message instanceof Msg msg
                            ? msg.getId()
                            : message instanceof Map<?, ?> map
                                    ? String.valueOf(map.get("id"))
                                    : null;
            if (messageId != null && messageModelCalls.containsKey(messageId)) {
                var enriched = new LinkedHashMap<String, Object>();
                data.forEach((key, value) -> enriched.put(String.valueOf(key), value));
                enriched.put("modelCallId", messageModelCalls.get(messageId));
                payload = enriched;
            }
        }
        if (stepId != null && payload instanceof Map<?, ?> data) {
            var scoped = new LinkedHashMap<String, Object>();
            data.forEach((key, value) -> scoped.put(String.valueOf(key), value));
            scoped.putIfAbsent("stepId", stepId);
            payload = scoped;
        }
        String json = JsonUtils.getJsonCodec().toJson(typedPayload(payload));
        int byteLength = json.getBytes(StandardCharsets.UTF_8).length;
        if (pendingBytes + byteLength > 16 * 1024 * 1024)
            throw new JournalException("Thread pending buffer exhausted");
        long seq = durableSeq + pending.size() + 1;
        var accepted =
                new JournalEvent(
                        JournalEvent.SCHEMA_VERSION,
                        UUID.randomUUID().toString(),
                        seq,
                        System.currentTimeMillis(),
                        type,
                        runId,
                        turnId,
                        required,
                        json);
        JournalEventTypes.validate(accepted);
        pending.add(accepted);
        pendingBytes += byteLength;
        acceptedVersion++;
        scheduleFlush();
        return seq;
    }

    // Map<String, Object> erases polymorphic block types in Jackson. Preserve their discriminator
    // before freezing native payloads, including action results and external tool requests.
    private static Object typedPayload(Object value) {
        if (value instanceof ContentBlock block)
            return JsonUtils.getJsonCodec()
                    .fromJson(JsonUtils.getJsonCodec().toJson(block), Map.class);
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<Object, Object>();
            map.forEach((key, item) -> copy.put(key, typedPayload(item)));
            return copy;
        }
        if (value instanceof List<?> list)
            return list.stream().map(InvocationRecorder::typedPayload).toList();
        return value;
    }

    public Mono<Void> flush() {
        return flushOn(JournalSchedulers.commits());
    }

    private Mono<Void> flushOn(Scheduler scheduler) {
        return Mono.defer(
                () -> {
                    long target;
                    synchronized (this) {
                        check();
                        target = acceptedVersion;
                    }
                    return flushThrough(target, scheduler);
                });
    }

    private Mono<Void> flushThrough(long target, Scheduler scheduler) {
        Mono<Void> active;
        synchronized (this) {
            check();
            if (flushing == null) {
                var identity = new AtomicReference<Mono<Void>>();
                var work =
                        Mono.<Void>fromRunnable(this::flushNow)
                                .subscribeOn(scheduler)
                                .doOnSuccess(ignored -> clearFlush(identity.get()))
                                .doOnError(
                                        error -> {
                                            failed(error);
                                            clearFlush(identity.get());
                                        })
                                // One cancelled observer must not cancel another observer's durable
                                // barrier.
                                .cache();
                identity.set(work);
                flushing = work;
            }
            active = flushing;
        }
        return active.then(
                Mono.defer(
                        () -> {
                            synchronized (this) {
                                // A later close/failure cannot revoke an acknowledged prefix.
                                if (flushedVersion >= target) return Mono.empty();
                                check();
                            }
                            return flushThrough(target, scheduler);
                        }));
    }

    private synchronized void clearFlush(Mono<Void> identity) {
        if (flushing == identity) flushing = null;
    }

    /** Blocking boundary for synchronous state and tool integrations; remote IO never holds the recorder state monitor. */
    public void flushNow() {
        writerLock.lock();
        try {
            List<JournalEvent> batch;
            long expected;
            long target;
            String identity;
            synchronized (this) {
                check();
                cancelDelayedFlush();
                batch = List.copyOf(pending);
                expected = durableSeq;
                target = acceptedVersion;
                if (!batch.isEmpty() && batchId == null) batchId = UUID.randomUUID().toString();
                identity = batchId;
            }
            log.renew(writer, LEASE);
            if (batch.isEmpty()) return;
            var head = log.commit(writer, identity, expected, batch);
            synchronized (this) {
                durableSeq = head.seq();
                flushedVersion = target;
                pending.subList(0, batch.size()).clear();
                // Hosted logs may discard diagnostic events. Rebase only the uncommitted suffix.
                pendingBytes = 0;
                for (int i = 0; i < pending.size(); i++) {
                    var event = pending.get(i);
                    pending.set(
                            i,
                            new JournalEvent(
                                    event.schemaVersion(),
                                    event.eventId(),
                                    durableSeq + i + 1,
                                    event.occurredAt(),
                                    event.type(),
                                    event.executionRunId(),
                                    event.turnId(),
                                    event.required(),
                                    event.payloadJson()));
                    pendingBytes += event.payloadJson().getBytes(StandardCharsets.UTF_8).length;
                }
                batchId = null;
            }
        } catch (RuntimeException error) {
            failed(error);
            throw new JournalException("Failed to commit session prefix", error);
        } finally {
            writerLock.unlock();
        }
        exportCommitted();
    }

    private void exportCommitted() {
        if (exporter == null) return;
        if (!exporting.compareAndSet(false, true)) return;
        JournalSchedulers.maintenance()
                .schedule(
                        () -> {
                            try {
                                exporter.drain();
                            } catch (RuntimeException error) {
                                LOGGER.warn(
                                        "Committed session export pending retry: {}",
                                        error.toString());
                            } finally {
                                exporting.set(false);
                            }
                        });
    }

    /** Explicit acknowledged boundary for existing synchronous domain mutation code. */
    public void recordNow(String type, Object payload) {
        writerLock.lock();
        try {
            append(type, payload);
            flushNow();
        } finally {
            writerLock.unlock();
        }
    }

    /** Reads a durable domain fact, never an in-flight buffer. */
    public Optional<JournalEvent> findLast(String type, Predicate<JournalEvent> predicate) {
        long cursor = 0;
        JournalEvent found = null;
        for (var event : log.scan(0, log.head().seq()))
            if (event.type().equals(type) && predicate.test(event)) found = event;
        return Optional.ofNullable(found);
    }

    public void actionStarted(String toolCallId, String actionId) {
        actionIds.put(toolCallId, actionId);
    }

    public String actionId(String toolCallId) {
        return actionIds.getOrDefault(toolCallId, "");
    }

    public Mono<Void> record(String type, Object payload) {
        return Mono.defer(
                () -> {
                    append(type, payload);
                    return flush();
                });
    }

    public synchronized void captureState(AgentState state, String reason) {
        check();
        String json = state.toJson();
        if (json.equals(previousState)
                && executionPosition.equals(previousPosition)
                && state.getContext().stream()
                        .allMatch(message -> recordedMessages.contains(message.getId()))) return;
        var messages = messageJson(state);
        int prefix = 0;
        while (prefix < previousMessages.size()
                && prefix < messages.size()
                && previousMessages.get(prefix).equals(messages.get(prefix))) prefix++;
        if (prefix < previousMessages.size())
            append(
                    "context/replaced",
                    Map.of(
                            "reason",
                            reason,
                            "messages",
                            state.getContext(),
                            "previousAsOfSeq",
                            durableSeq));
        for (int i = 0; i < messages.size(); i++) {
            Msg message = state.getContext().get(i);
            if (!recordedMessages.add(message.getId())) continue;
            if (inputMessages.contains(message.getId()))
                append("input/applied", Map.of("inputId", message.getId()));
            String type =
                    message.hasContentBlocks(ToolResultBlock.class)
                            ? "tool/result"
                            : message.getRole() == MsgRole.ASSISTANT
                                    ? "message/assistant"
                                    : message.getRole() == MsgRole.SYSTEM
                                            ? "message/system"
                                            : "message/user";
            append(type, Map.of("message", message));
            for (ToolUseBlock use : message.getContentBlocks(ToolUseBlock.class))
                append(
                        "tool/requested",
                        Map.of(
                                "messageId",
                                message.getId(),
                                "toolCallId",
                                use.getId(),
                                "call",
                                use));
        }
        var current = JsonUtils.getJsonCodec().fromJson(json, Map.class);
        var old =
                previousState == null
                        ? Map.of()
                        : JsonUtils.getJsonCodec().fromJson(previousState, Map.class);
        for (String field : List.of("tasks_context", "plan_mode_context", "permission_context")) {
            Object value = current.get(field);
            if (value != null && !Objects.equals(value, old.get(field)))
                append(
                        field.equals("tasks_context")
                                ? "task/changed"
                                : field.equals("plan_mode_context")
                                        ? "plan/changed"
                                        : "permission/changed",
                        Map.of("state", value));
        }
        append("state/checkpoint", checkpointData(json, reason));
        previousState = json;
        previousPosition = executionPosition;
        previousMessages = messages;
    }

    private Map<String, Object> checkpointData(String json, String reason) {
        return Map.of(
                "projectorVersion",
                1,
                "stateJson",
                json,
                "reason",
                reason,
                "executionPosition",
                executionPosition);
    }

    /** Native progress is committed with state, never inferred from Run/Step counts. */
    public synchronized AgentExecutionPosition executionPosition() {
        return executionPosition;
    }

    /** The host may provide a position before execution starts. */
    public synchronized void executionPosition(AgentExecutionPosition position) {
        if (stepId != null) throw new JournalException("Execution has already started");
        executionPosition = Objects.requireNonNull(position);
    }

    private static List<String> messageJson(AgentState state) {
        return state.getContext().stream().map(m -> JsonUtils.getJsonCodec().toJson(m)).toList();
    }

    /** Acknowledged checkpoint for synchronous callers already inside this execution. */
    public void checkpointNow(AgentState state, String reason) {
        writerLock.lock();
        try {
            captureState(state, reason);
            flushNow();
        } finally {
            writerLock.unlock();
        }
    }

    /** Commits a recovery-critical fact with its state, even when state capture deduplicates. */
    public void checkpointedRecordNow(
            AgentState state, String reason, String type, Object payload) {
        writerLock.lock();
        try {
            synchronized (this) {
                captureState(state, reason);
                boolean checkpoint =
                        pending.stream().anyMatch(event -> "state/checkpoint".equals(event.type()));
                append(type, payload);
                if (!checkpoint) append("state/checkpoint", checkpointData(state.toJson(), reason));
            }
            flushNow();
        } finally {
            writerLock.unlock();
        }
    }

    public Mono<Void> checkpoint(AgentState state, String reason) {
        return Mono.defer(
                () -> {
                    synchronized (this) {
                        captureState(state, reason);
                    }
                    return flush();
                });
    }

    public Mono<Void> start(List<Msg> input, AgentState state) {
        return Mono.defer(
                () -> {
                    boolean pendingBaseline =
                            pending.stream()
                                    .anyMatch(
                                            event ->
                                                    event.type().equals("migration/baseline")
                                                            || event.type()
                                                                    .equals("state/initialized"));
                    if (initial.stateJson() == null && !pendingBaseline)
                        append(
                                "state/initialized",
                                Map.of("stateJson", state.toJson(), "coverage", "baseline_only"));
                    if (!initial.activeRuns().isEmpty())
                        append(
                                "recovery/applied",
                                Map.of(
                                        "interruptedRuns",
                                        initial.activeRuns(),
                                        "sourceSeq",
                                        initial.asOfSeq()));
                    append(
                            "run/start",
                            Map.of("executionRunId", runId, "coverage", "adapter_chunks"));
                    if (input != null) for (var message : input) inputMessages.add(message.getId());
                    append("input/received", Map.of("messages", input == null ? List.of() : input));
                    // A crash before the first model boundary must recover this invocation's
                    // starting position, not counters from an earlier completed call.
                    append("state/checkpoint", checkpointData(state.toJson(), "run_start"));
                    return flush();
                });
    }

    public synchronized void step(int iteration) {
        executionPosition = new AgentExecutionPosition(iteration, Math.max(0, iteration - 1));
        startStep(iteration, "REASONING", stepId == null ? interruptedStep : null);
    }

    /** A logical decision has entered native state; transport retries do not call this again. */
    public synchronized void reasoningCompleted(int iteration) {
        executionPosition = new AgentExecutionPosition(Math.addExact(iteration, 1), iteration);
    }

    /** A new Step in this Run continues the prior acting phase without another model decision. */
    public synchronized void resumeStep(int iteration) {
        executionPosition = new AgentExecutionPosition(Math.addExact(iteration, 1), iteration);
        startStep(iteration, "ACTING", interruptedStep);
    }

    /** Finalization continues the current Step, or starts a new continuation after recovery. */
    public synchronized void finalizationStep() {
        if (stepId == null) {
            startStep(executionPosition.reasoningIteration(), "FINALIZATION", interruptedStep);
        } else if (!"FINALIZATION".equals(stepPhase)) {
            stepPhase = "FINALIZATION";
            append("step/phase", stepData(null));
        }
    }

    private void startStep(int iteration, String phase, String source) {
        if (stepId != null) append("step/end", stepData("continued"));
        stepId = UUID.randomUUID().toString();
        stepPhase = phase;
        stepSource = source;
        var data = stepData(null);
        data.put("iteration", iteration);
        append("step/start", data);
    }

    public synchronized void actingStep() {
        if (stepId == null || "ACTING".equals(stepPhase)) return;
        stepPhase = "ACTING";
        append("step/phase", stepData(null));
    }

    private Map<String, Object> stepData(String status) {
        var data = new LinkedHashMap<String, Object>();
        data.put("stepId", stepId);
        data.put("phase", stepPhase);
        data.put("resumedFromStepId", stepSource);
        if (status != null) data.put("status", status);
        return data;
    }

    public synchronized void result(Msg result) {
        if (result != null) resultJson = JsonUtils.getJsonCodec().toJson(result);
        resultOutcome = RunOutcome.fromResult(result);
    }

    public Mono<Void> finish(AgentState state, String status) {
        return Mono.defer(
                        () -> {
                            synchronized (this) {
                                captureState(state, "run_" + status);
                                RunOutcome invocation =
                                        "completed".equals(status) && resultOutcome != null
                                                ? resultOutcome
                                                : new RunOutcome(
                                                        RunOutcome.Status.fromValue(status), null);
                                String terminal = invocation.status().value();
                                if (resultJson != null && !terminal.equals("suspended"))
                                    append(
                                            "run/output",
                                            Map.of(
                                                    "message",
                                                    JsonUtils.getJsonCodec()
                                                            .fromJson(resultJson, Map.class),
                                                    "status",
                                                    terminal.equals("completed")
                                                            ? "completed"
                                                            : "incomplete"));
                                Map<String, Object> outcome = new LinkedHashMap<>();
                                outcome.put("status", terminal);
                                outcome.put("reason", invocation.reason());
                                if (stepId != null) {
                                    append("step/end", stepData(terminal));
                                    stepId = null;
                                }
                                append("run/end", outcome);
                            }
                            return flush();
                        })
                .then(Mono.<Void>fromRunnable(this::close).subscribeOn(JournalSchedulers.commits()))
                .onErrorResume(
                        error ->
                                Mono.<Void>fromRunnable(this::close)
                                        .subscribeOn(JournalSchedulers.commits())
                                        .then(Mono.error(error)));
    }

    public void close() {
        writerLock.lock();
        try {
            synchronized (this) {
                if (closed) return;
                closed = true;
                heartbeat.dispose();
                cancelDelayedFlush();
            }
            try {
                log.release(writer);
            } catch (RuntimeException error) {
                synchronized (this) {
                    if (failure == null) throw error;
                }
            }
        } finally {
            writerLock.unlock();
        }
    }

    /** Capture only runtime facts that are not already captured at the model/tool boundary. */
    public void observe(AgentEvent event) {
        // A forwarded child's requests belong to its own journal, never the parent's inbox.
        if (event.getExecution() != null && !runId.equals(event.getExecution().runId())) return;
        if (event.getSource() != null && !event.getSource().isBlank()) return;
        if (event instanceof TextBlockDeltaEvent delta) {
            if (delta.getDelta() != null && !delta.getDelta().isEmpty())
                append(
                        "presentation/text",
                        Map.of(
                                "replyId", delta.getReplyId(),
                                "blockId", delta.getBlockId(),
                                "text", delta.getDelta()));
            return;
        }
        if (event instanceof ToolCallDeltaEvent delta) {
            if (delta.getDelta() != null && !delta.getDelta().isEmpty()) {
                var data = new LinkedHashMap<String, Object>();
                data.put("replyId", delta.getReplyId());
                data.put("toolCallId", delta.getToolCallId());
                if (delta.getToolCallName() != null) data.put("name", delta.getToolCallName());
                data.put("text", delta.getDelta());
                append("presentation/tool_arguments", data);
            }
            return;
        }
        if (event instanceof ToolResultEndEvent result) {
            append(
                    "presentation/tool_result",
                    Map.of(
                            "toolCallId",
                            result.getToolCallId(),
                            "status",
                            result.getState().name()));
            return;
        }
        if (event instanceof RequireUserConfirmEvent confirmation) {
            for (var call : confirmation.getToolCalls())
                append(
                        "interaction/requested",
                        Map.of("requestId", call.getId(), "kind", "confirmation", "call", call));
            return;
        }
        if (event instanceof RequireExternalExecutionEvent external) {
            for (var call : external.getToolCalls())
                append(
                        "interaction/requested",
                        Map.of(
                                "requestId",
                                call.getId(),
                                "kind",
                                "external_execution",
                                "call",
                                call));
            return;
        }
        if (event instanceof ExternalExecutionResultEvent external) {
            for (var result : external.getToolResults())
                append(
                        "interaction/resolved",
                        Map.of(
                                "requestId",
                                result.getId(),
                                "kind",
                                "external_execution",
                                "result",
                                result));
            return;
        }
        if (event instanceof UserConfirmResultEvent confirmed) {
            for (var result : confirmed.getConfirmResults())
                append(
                        "interaction/resolved",
                        Map.of(
                                "requestId",
                                result.getToolCall().getId(),
                                "kind",
                                "confirmation",
                                "result",
                                result));
            return;
        }

        String type =
                switch (event.getType().name()) {
                    case "REQUEST_STOP" -> "run/stop_requested";
                    case "REQUIRE_USER_CONFIRM", "REQUIRE_EXTERNAL_EXECUTION" ->
                            "interaction/requested";
                    case "USER_CONFIRM_RESULT", "EXTERNAL_EXECUTION_RESULT" ->
                            "interaction/resolved";
                    case "HINT_BLOCK" -> "presentation/hint";
                    case "SUBAGENT_EXPOSED" -> "presentation/hint";
                    default -> null;
                };
        if (event instanceof CustomEvent custom) {
            type =
                    switch (custom.getName()) {
                        case "context_build" -> "context/build";
                        case "task_verification" -> "verification/result";
                        case "action_observation" -> null;
                        default -> "extension/" + custom.getName();
                    };
        }
        if (type != null) append(type, Map.of("event", event), !type.startsWith("extension/"));
    }

    public Mono<Void> prepareModel(
            ModelCallInput input, String callId, String purpose, AgentState state) {
        return Mono.defer(
                () -> {
                    synchronized (this) {
                        captureState(state, "before_model");
                        var payload = new LinkedHashMap<String, Object>();
                        payload.put("modelCallId", callId);
                        payload.put("purpose", purpose);
                        payload.put("iteration", executionPosition.reasoningIteration());
                        payload.put("model", input.model().getModelName());
                        payload.put("messages", input.messages());
                        payload.put("tools", input.tools());
                        payload.put("options", input.options());
                        append("request/prepared", payload);
                        append(
                                "model/dispatch",
                                Map.of("modelCallId", callId, "attemptId", callId));
                    }
                    return flush();
                });
    }
}
