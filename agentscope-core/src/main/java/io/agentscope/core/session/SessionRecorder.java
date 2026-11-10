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
package io.agentscope.core.session;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
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
import java.util.HashMap;
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
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Per-execution ordered acceptance and acknowledged checkpoints. Buffer retained on failure. */
public final class SessionRecorder {
    public static final String CONTEXT_KEY = "agentscope.session.recorder";
    public static final String PARENT_KEY = "agentscope.session.parent";
    private final AtomicBoolean exporting = new AtomicBoolean();
    public static final String TURN_ID_KEY = "agentscope.session.turnId";

    /**
     * Metadata key carrying the SessionLog seq assigned to the source event or chunk. The value is
     * assigned when the event is appended and becomes committed on the next flush, so consumers
     * must not assume the committed head already covers it.
     */
    public static final String SESSION_SEQ_KEY = "agentscope.session.seq";

    /**
     * Metadata key carrying the seq of the event a frame belongs to. One model chunk can produce
     * several agent events that share one seq; only the last of them carries {@link
     * #SESSION_SEQ_KEY} as the cursor, so a consumer can advance the cursor once the whole event
     * has been delivered.
     */
    public static final String SESSION_SOURCE_SEQ_KEY = "agentscope.session.sourceSeq";

    /**
     * Reactor context key holding an {@code AtomicReference<SessionRecorder>} that the execution
     * fills once the recorder exists. The recorder is created below the event stream, so an event
     * emitted outside the call scope (the terminal end event) can still read the recorder without
     * copying it through a {@code RuntimeContext}, whose builder would isolate the copy.
     */
    public static final String CONTEXT_REF_KEY = "agentscope.session.recorderRef";

    private static final Duration LEASE = Duration.ofMinutes(2);
    private final SessionLog log;
    private final SessionLog.Writer writer;
    private final String runId;
    private final boolean resumedTurn;
    private final String turnId;
    private final List<SessionEvent> pending = new ArrayList<>();
    private long durableSeq;
    private long lastAppendedSeq;
    private String lastAppendedType;
    private int pendingBytes;
    private Throwable failure;
    private boolean closed;
    private String resultStatus;
    private String resultReason;
    private String resultJson;
    private String stepId;
    private String batchId;
    private final Map<String, String> actionIds = new ConcurrentHashMap<>();
    private final Map<String, String> messageModelCalls = new ConcurrentHashMap<>();
    private String previousState;
    private List<String> previousMessages = List.of();
    private final Set<String> recordedMessages = new HashSet<>();
    private final Map<String, Long> recordedMessageSeqs = new HashMap<>();
    private final Set<String> inputMessages = new HashSet<>();
    private final Disposable heartbeat;
    private final SessionProjection initial;
    private final SessionLogExporter exporter;
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionRecorder.class);

    public SessionRecorder(SessionLog log, String turnId) {
        this(log, turnId, null);
    }

    public SessionRecorder(SessionLog log, String turnId, SessionExportSink sink) {
        this(log, turnId, UUID.randomUUID().toString(), sink);
    }

    public SessionRecorder(SessionLog log, String turnId, String runId, SessionExportSink sink) {
        this.runId = Objects.requireNonNull(runId, "runId");
        if (runId.isBlank() || (turnId != null && turnId.isBlank()))
            throw new IllegalArgumentException("Run and turn IDs must not be blank");
        this.exporter = sink == null ? null : new SessionLogExporter(log, sink);
        this.log = log;
        this.turnId = turnId == null ? UUID.randomUUID().toString() : turnId;
        this.writer = log.acquire(runId, LEASE);
        try {
            initial = SessionProjection.read(log);
            durableSeq = initial.asOfSeq();
            boolean seenTurn = false;
            for (var event : log.scan(0, durableSeq)) {
                if (runId.equals(event.executionRunId()))
                    throw new SessionLogException(
                            "Run ID already used; prepare a new execution to resume");
                if (!this.turnId.equals(event.turnId())) continue;
                seenTurn = true;
                if (event.type().equals("turn/completed")
                        || (event.type().equals("turn/end")
                                && "completed".equals(event.data().get("status"))))
                    throw new SessionLogException("Turn already completed; use a new turn ID");
            }
            resumedTurn = seenTurn;
            if (!initial.uncertainToolCalls().isEmpty())
                throw new SessionLogException(
                        "Explicit reconciliation required for unknown tool outcomes: "
                                + initial.uncertainToolCalls());
            previousState = initial.stateJson();
            if (previousState != null) previousMessages = messageJson(initial.restore());
            for (var message : SessionViews.transcript(log).messages())
                recordedMessages.add(message.getId());
        } catch (RuntimeException e) {
            log.release(writer);
            throw e;
        }
        heartbeat =
                Schedulers.boundedElastic()
                        .schedulePeriodically(
                                () -> {
                                    synchronized (this) {
                                        if (closed || failure != null) return;
                                        try {
                                            flushNow();
                                            exportCommitted();
                                        } catch (Throwable e) {
                                            failure = e;
                                        }
                                    }
                                },
                                1,
                                1,
                                TimeUnit.SECONDS);
    }

    public static SessionRecorder from(RuntimeContext rc) {
        return rc != null && rc.get(CONTEXT_KEY) instanceof SessionRecorder recorder
                ? recorder
                : null;
    }

    /** Parent completion is accepted only while that parent's writer is still active. */
    public synchronized void recordIfOpen(String type, Object payload) {
        if (!closed) recordNow(type, payload);
    }

    public Set<String> acceptedMessageIds() {
        return Set.copyOf(recordedMessages);
    }

    public SessionProjection initial() {
        return initial;
    }

    public String runId() {
        return runId;
    }

    public String turnId() {
        return turnId;
    }

    public synchronized long durableSeq() {
        return durableSeq;
    }

    /**
     * Returns the sequence assigned to the most recently appended event, committed or not.
     *
     * @return the last assigned sequence, or {@code 0} when nothing was appended yet
     */
    public synchronized long lastAppendedSeq() {
        return lastAppendedSeq;
    }

    /**
     * Returns the sequence of the last append only when it has the requested type.
     *
     * <p>Event tagging reads this after a record was appended. If an unrelated record slipped in
     * between, returning {@code 0} is safer than advancing the client cursor past an event that
     * was not delivered yet.
     *
     * @param type expected event type
     * @return the matching sequence, or {@code 0}
     */
    public synchronized long lastAppendedSeq(String type) {
        return type != null && type.equals(lastAppendedType) ? lastAppendedSeq : 0;
    }

    /**
     * Records a message at the moment it is produced and returns the assigned sequence.
     *
     * <p>State capture later skips messages already recorded here, so the record order follows
     * the event order instead of the next checkpoint. That lets the event carry the exact
     * sequence it belongs to.
     *
     * @param message the produced message
     * @return the record sequence, or the previous one when the message was already recorded
     */
    public synchronized long recordMessage(Msg message) {
        if (message == null) {
            return 0;
        }
        Long recordedSeq = recordedMessageSeqs.get(message.getId());
        if (recordedSeq != null) {
            return recordedSeq;
        }
        // A message may have been registered by a checkpoint without a stored sequence. In that
        // case its original seq is unknown, so return 0 rather than a later unrelated sequence.
        if (recordedMessages.contains(message.getId())) {
            return 0;
        }
        String type =
                message.hasContentBlocks(ToolResultBlock.class)
                        ? "tool/result"
                        : message.getRole() == MsgRole.ASSISTANT
                                ? "message/assistant"
                                : message.getRole() == MsgRole.SYSTEM
                                        ? "message/system"
                                        : "message/user";
        long seq = append(type, Map.of("message", message));
        // Register only after the append succeeded: a failed append must leave the message
        // eligible for the next state capture instead of silently dropping it.
        recordedMessages.add(message.getId());
        recordedMessageSeqs.put(message.getId(), seq);
        return seq;
    }

    /**
     * Records an assistant message together with its tool requests at the moment it is produced.
     *
     * <p>Tools may finish and record their results before the next checkpoint captures the
     * assistant message. Recording the message here keeps the log in conversation order, and the
     * explicit {@code tool/requested} records preserve what a checkpoint would otherwise have
     * appended for this message.
     *
     * @param message the assistant message
     * @return the message record sequence, or the previous one when already recorded
     */
    public synchronized long recordAssistantMessage(Msg message) {
        if (message == null) {
            return 0;
        }
        Long recordedSeq = recordedMessageSeqs.get(message.getId());
        if (recordedSeq != null) {
            return recordedSeq;
        }
        if (recordedMessages.contains(message.getId())) {
            return 0;
        }
        long seq = append("message/assistant", Map.of("message", message));
        recordedMessages.add(message.getId());
        recordedMessageSeqs.put(message.getId(), seq);
        for (ToolUseBlock use : message.getContentBlocks(ToolUseBlock.class)) {
            append(
                    "tool/requested",
                    Map.of("messageId", message.getId(), "toolCallId", use.getId(), "call", use));
        }
        return seq;
    }

    /**
     * Records a provisional tool result for the live cursor.
     *
     * <p>A {@code PostActing} hook may still rewrite the result, so this record is not part of the
     * conversation history: transcript and projection readers ignore its type. The final result is
     * recorded after the hook, and a client reconnecting from the provisional sequence reads the
     * final record.
     *
     * @param message the pre-hook tool result message
     * @return the provisional record sequence
     */
    public synchronized long recordProvisionalToolResult(Msg message) {
        if (message == null) {
            return 0;
        }
        return append("tool/result/provisional", Map.of("message", message), false);
    }

    private void check() {
        if (closed) throw new SessionLogException("Session recorder closed");
        if (failure != null) throw new SessionLogException("Session persistence failed", failure);
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
        String json = JsonUtils.getJsonCodec().toJson(typedPayload(payload));
        int byteLength = json.getBytes(StandardCharsets.UTF_8).length;
        if (pendingBytes + byteLength > 16 * 1024 * 1024)
            throw new SessionLogException("Session pending buffer exhausted");
        long seq = durableSeq + pending.size() + 1;
        var accepted =
                new SessionEvent(
                        1,
                        UUID.randomUUID().toString(),
                        seq,
                        System.currentTimeMillis(),
                        type,
                        runId,
                        turnId,
                        required,
                        json);
        SessionEventTypes.validate(accepted);
        pending.add(accepted);
        pendingBytes += byteLength;
        // Publish only after the event is really pending: a validation or buffer failure must not
        // leave a cursor pointing at a record that does not exist.
        lastAppendedSeq = seq;
        lastAppendedType = type;
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
            return list.stream().map(SessionRecorder::typedPayload).toList();
        return value;
    }

    public Mono<Void> flush() {
        return Mono.<Void>fromRunnable(this::flushNow).subscribeOn(Schedulers.boundedElastic());
    }

    public synchronized void flushNow() {
        check();
        log.renew(writer, LEASE);
        if (pending.isEmpty()) return;
        if (batchId == null) batchId = UUID.randomUUID().toString();
        try {
            var head = log.commit(writer, batchId, durableSeq, List.copyOf(pending));
            durableSeq = head.seq();
            pending.clear();
            pendingBytes = 0;
            batchId = null;
        } catch (RuntimeException e) {
            failure = e;
            throw new SessionLogException("Failed to commit session prefix", e);
        }
        exportCommitted();
    }

    private void exportCommitted() {
        if (exporter == null) return;
        if (!exporting.compareAndSet(false, true)) return;
        Schedulers.boundedElastic()
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
    public synchronized void recordNow(String type, Object payload) {
        append(type, payload);
        flushNow();
    }

    /** Reads a durable domain fact, never an in-flight buffer. */
    public Optional<SessionEvent> findLast(String type, Predicate<SessionEvent> predicate) {
        long cursor = 0;
        SessionEvent found = null;
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
            long messageSeq = append(type, Map.of("message", message));
            recordedMessageSeqs.put(message.getId(), messageSeq);
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
        append(
                "state/checkpoint",
                Map.of("projectorVersion", 1, "stateJson", json, "reason", reason));
        previousState = json;
        previousMessages = messages;
    }

    private static List<String> messageJson(AgentState state) {
        return state.getContext().stream().map(m -> JsonUtils.getJsonCodec().toJson(m)).toList();
    }

    /** Acknowledged checkpoint for synchronous callers already inside this execution. */
    public synchronized void checkpointNow(AgentState state, String reason) {
        captureState(state, reason);
        flushNow();
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
                    if (initial.stateJson() == null)
                        append(
                                "migration/baseline",
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
                    append(resumedTurn ? "turn/resumed" : "turn/start", Map.of("turnId", turnId));
                    if (input != null) for (var message : input) inputMessages.add(message.getId());
                    append("input/received", Map.of("messages", input == null ? List.of() : input));
                    return flush();
                });
    }

    public synchronized void step(int iteration) {
        if (stepId != null) append("step/end", Map.of("stepId", stepId, "status", "continued"));
        stepId = UUID.randomUUID().toString();
        append("step/start", Map.of("stepId", stepId, "iteration", iteration));
    }

    public synchronized void result(Msg result) {
        if (result != null) resultJson = JsonUtils.getJsonCodec().toJson(result);
        if (result != null && result.getGenerateReason() != null) {
            resultReason = result.getGenerateReason().name();
            resultStatus = outcome(result.getGenerateReason());
        }
    }

    /** Logical outcome of a normally returned result, independent of the reactive handle status. */
    public static String outcome(GenerateReason reason) {
        if (reason == null) return "completed";
        return switch (reason) {
            case TOOL_SUSPENDED,
                    PERMISSION_ASKING,
                    REASONING_STOP_REQUESTED,
                    ACTING_STOP_REQUESTED,
                    MIDDLEWARE_STOP_REQUESTED ->
                    "suspended";
            case INTERRUPTED -> "interrupted";
            case MAX_ITERATIONS, ALL_TOOLS_DENIED -> "failed";
            default -> "completed";
        };
    }

    public Mono<Void> finish(AgentState state, String status) {
        return Mono.defer(
                        () -> {
                            synchronized (this) {
                                captureState(state, "run_" + status);
                                String terminal =
                                        "completed".equals(status) && resultStatus != null
                                                ? resultStatus
                                                : status;
                                if (resultJson != null && !terminal.equals("suspended"))
                                    append(
                                            "turn/output",
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
                                outcome.put("reason", resultReason);
                                if (stepId != null) {
                                    append(
                                            "step/end",
                                            Map.of("stepId", stepId, "status", terminal));
                                    stepId = null;
                                }
                                append("turn/" + terminal, outcome);
                                append("run/end", outcome);
                            }
                            return flush();
                        })
                .then(Mono.<Void>fromRunnable(this::close).subscribeOn(Schedulers.boundedElastic()))
                .onErrorResume(
                        error ->
                                Mono.<Void>fromRunnable(this::close)
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .then(Mono.error(error)));
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        heartbeat.dispose();
        try {
            log.release(writer);
        } catch (RuntimeException e) {
            if (failure == null) throw e;
        }
    }

    /** Marks an event as the replay boundary of the record that was just appended. */
    private static void tagBoundary(AgentEvent event, long seq) {
        if (seq <= 0) {
            return;
        }
        event.withMetadataEntry(SESSION_SEQ_KEY, seq);
        event.withMetadataEntry(SESSION_SOURCE_SEQ_KEY, seq);
    }

    /** Capture only runtime facts that are not already captured at the model/tool boundary. */
    public void observe(AgentEvent event) {
        // A forwarded child's requests belong to its own journal, never the parent's inbox.
        if (event.getExecution() != null && !runId.equals(event.getExecution().runId())) return;
        if (event.getSource() != null && !event.getSource().isBlank()) return;
        if (event instanceof RequireUserConfirmEvent confirmation) {
            long seq = 0;
            for (var call : confirmation.getToolCalls())
                seq =
                        append(
                                "interaction/requested",
                                Map.of(
                                        "requestId",
                                        call.getId(),
                                        "kind",
                                        "confirmation",
                                        "call",
                                        call));
            tagBoundary(event, seq);
            return;
        }
        if (event instanceof RequireExternalExecutionEvent external) {
            long seq = 0;
            for (var call : external.getToolCalls())
                seq =
                        append(
                                "interaction/requested",
                                Map.of(
                                        "requestId",
                                        call.getId(),
                                        "kind",
                                        "external_execution",
                                        "call",
                                        call));
            tagBoundary(event, seq);
            return;
        }
        if (event instanceof ExternalExecutionResultEvent external) {
            long seq = 0;
            for (var result : external.getToolResults())
                seq =
                        append(
                                "interaction/resolved",
                                Map.of(
                                        "requestId",
                                        result.getId(),
                                        "kind",
                                        "external_execution",
                                        "result",
                                        result));
            tagBoundary(event, seq);
            return;
        }
        if (event instanceof UserConfirmResultEvent confirmed) {
            long seq = 0;
            for (var result : confirmed.getConfirmResults())
                seq =
                        append(
                                "interaction/resolved",
                                Map.of(
                                        "requestId",
                                        result.getToolCall().getId(),
                                        "kind",
                                        "confirmation",
                                        "result",
                                        result));
            tagBoundary(event, seq);
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
        if (type != null) {
            long seq = append(type, Map.of("event", event), !type.startsWith("extension/"));
            tagBoundary(event, seq);
        }
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
