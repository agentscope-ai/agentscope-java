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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.agent.RunOutcome;
import io.agentscope.core.journal.JournalEvent;
import io.agentscope.core.journal.JournalEventTypes;
import io.agentscope.core.journal.ThreadJournal;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.AppliedResult;
import io.agentscope.durable.DurableTypes.Commit;
import io.agentscope.durable.DurableTypes.EventInput;
import io.agentscope.durable.DurableTypes.JournalAppend;
import io.agentscope.durable.DurableTypes.LiveEventInput;
import io.agentscope.durable.DurableTypes.RunChange;
import io.agentscope.durable.DurableTypes.RunStatus;
import io.agentscope.durable.DurableTypes.RunStepRef;
import io.agentscope.durable.DurableTypes.StepChange;
import io.agentscope.durable.DurableTypes.StepPhase;
import io.agentscope.durable.DurableTypes.StepStatus;
import io.agentscope.durable.DurableTypes.TokenUsage;
import io.agentscope.durable.DurableTypes.ToolCall;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.HarnessContext;
import io.agentscope.durable.ToolBinding;
import io.agentscope.durable.codec.agentscope.AgentScopeCheckpointCodec;
import io.agentscope.durable.codec.agentscope.DurableEventSchemas;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Native journal and durable effects share one fenced backend transaction. No second scheduler. */
final class BackendThreadJournal implements ThreadJournal {
    private final HarnessContext context;
    private final Map<String, String> executors;
    private final Map<String, ToolBinding> bindings;
    private final Set<String> sealed = new HashSet<>();
    private final List<String> signalsToApply = new ArrayList<>();
    private String owner;
    private final Map<String, ModelCapture> captures = new LinkedHashMap<>();
    private ObjectNode cached;
    private AgentScopeMessageEvents messages = new AgentScopeMessageEvents();
    private AgentScopeToolEvents toolEvents = new AgentScopeToolEvents();

    BackendThreadJournal(HarnessContext context, Map<String, String> executors) {
        this(context, executors, Map.of());
    }

    BackendThreadJournal(
            HarnessContext context,
            Map<String, String> executors,
            Map<String, ToolBinding> bindings) {
        this.bindings = bindings;
        DurableEventSchemas.ensureRegistered();
        this.context = context;
        this.executors = executors;
        var frame = context.frame();
        var checkpoint = frame.checkpoint();
        if (!checkpoint.isNull()
                && !AgentScopeCheckpointCodec.STATE_VERSION.equals(
                        checkpoint.path("format").asText()))
            throw new DurableException(
                    DurableException.Code.INCOMPATIBLE, "Unknown native checkpoint format");
        cached =
                (ObjectNode)
                        DurableJson.value(
                                Map.of("format", AgentScopeCheckpointCodec.STATE_VERSION));
        cached.putArray("events");
        cached.putObject("receipts");
        cached.put("lastCommit", checkpoint.path("lastCommit").asText(null));
        long position = 0;
        while (true) {
            var page = context.journal(position, 1000);
            for (var entry : page) {
                var event = DurableJson.convert(entry, JournalEvent.class);
                JournalEventTypes.validate(event);
                if (event.seq() != ++position)
                    throw new DurableException(
                            DurableException.Code.CONFLICT, "Native journal is discontinuous");
                cached.withArray("events").add(entry);
                messages.accept(event);
                toolEvents.accept(event, messages);
            }
            if (page.size() < 1000) break;
        }
        if (checkpoint.path("position").asLong() != position)
            throw new DurableException(
                    DurableException.Code.CONFLICT, "Native checkpoint and journal disagree");
    }

    synchronized void modelStarted(String id, ModelCapture capture) {
        captures.put(id, capture);
    }

    synchronized void modelChunk(String id, ChatResponse chunk) {
        captures.get(id).accept(chunk);
    }

    private JournalEvent capture(JournalEvent event) {
        if ("model/request_started".equals(event.type())) {
            var capture = captures.get(String.valueOf(event.data().get("modelCallId")));
            if (capture != null) capture.nextAttempt();
        }
        if (!"model/end".equals(event.type())) return event;
        var capture = captures.get(String.valueOf(event.data().get("modelCallId")));
        if (capture == null) return event;
        var data = new LinkedHashMap<String, Object>(event.data());
        data.put(ModelCapture.OUTCOME, capture.freeze(String.valueOf(data.get("status"))));
        return new JournalEvent(
                event.schemaVersion(),
                event.eventId(),
                event.seq(),
                event.occurredAt(),
                event.type(),
                event.executionRunId(),
                event.turnId(),
                event.required(),
                JsonUtils.getJsonCodec().toJson(data));
    }

    synchronized void consuming(List<String> signals) {
        signalsToApply.addAll(signals);
    }

    private ObjectNode checkpoint() {
        return cached.deepCopy();
    }

    @Override
    public synchronized Head head() {
        return head(checkpoint());
    }

    private Head head(ObjectNode state) {
        return new Head(
                FORMAT_VERSION,
                state.path("events").size(),
                context.lease().epoch(),
                owner,
                context.lease().expiresAt(),
                state.path("lastCommit").asText(null));
    }

    @Override
    public synchronized Writer acquire(String requested, Duration lease) {
        context.frame();
        if (sealed.contains(requested) || (owner != null && !owner.equals(requested)))
            throw new DurableException(
                    DurableException.Code.FENCED, "Native writer is unavailable");
        owner = requested;
        return new Writer(owner, context.lease().epoch());
    }

    @Override
    public synchronized Head sealWriter(String requested) {
        sealed.add(requested);
        if (requested.equals(owner)) owner = null;
        return head();
    }

    private void check(Writer writer) {
        context.frame();
        if (!writer.owner().equals(owner)
                || writer.epoch() != context.lease().epoch()
                || sealed.contains(writer.owner()))
            throw new DurableException(DurableException.Code.FENCED, "Stale native writer");
    }

    @Override
    public synchronized void renew(Writer writer, Duration lease) {
        check(writer); // Outer runtime exclusively owns lease renewal.
    }

    @Override
    public synchronized void release(Writer writer) {
        if (writer.owner().equals(owner)) owner = null;
    }

    @Override
    public synchronized Head commit(
            Writer writer, String batchId, long expected, List<JournalEvent> events) {
        return commit(writer, batchId, expected, events, TurnStatus.RUNNING, null, true);
    }

    private Head commit(
            Writer writer,
            String batchId,
            long expected,
            List<JournalEvent> events,
            TurnStatus turnStatus,
            String terminalError,
            boolean invocationFacts) {
        events = events.stream().map(this::capture).toList();
        var state = checkpoint();
        String digest =
                DurableJson.hash(
                        Map.of(
                                "expected",
                                expected,
                                "events",
                                events,
                                "status",
                                turnStatus,
                                "error",
                                DurableJson.value(terminalError)));
        var previous = state.path("receipts").get(batchId);
        if (previous != null) {
            if (!digest.equals(previous.path("digest").asText()))
                throw new DurableException(
                        DurableException.Code.CONFLICT, "Native batch content changed");
            return DurableJson.convert(previous.path("head"), Head.class);
        }
        check(writer);
        if (state.path("events").size() != expected)
            throw new DurableException(DurableException.Code.CONFLICT, "Native sequence changed");
        var tools = new ArrayList<ToolCall>();
        var publicEvents = new ArrayList<EventInput>();
        var liveEvents = new ArrayList<LiveEventInput>();
        var nextMessages = new AgentScopeMessageEvents(messages);
        var nextToolEvents = new AgentScopeToolEvents(toolEvents);
        RunChange runChange = null;
        var stepChanges = new ArrayList<StepChange>();
        var appliedCalls = new LinkedHashMap<String, RunStepRef>();
        boolean hasCheckpoint = false;
        long seq = expected;
        for (var event : events) {
            int firstPublicEvent = publicEvents.size();
            JournalEventTypes.validate(event);
            if (event.seq() != ++seq)
                throw new DurableException(
                        DurableException.Code.CONFLICT, "Non-contiguous native batch");
            state.withArray("events").add(DurableJson.value(event));
            var data = event.data();
            var modelEvent = modelEvent(event);
            if (modelEvent != null) publicEvents.add(modelEvent);
            if ("run/start".equals(event.type())) {
                runChange = new RunChange(event.executionRunId(), RunStatus.RUNNING, null, null);
            } else if ("run/end".equals(event.type())) {
                var outcome = JsonUtils.getJsonCodec().convertValue(data, RunOutcome.class);
                RunStatus status =
                        switch (outcome.status()) {
                            case COMPLETED -> RunStatus.COMPLETED;
                            case FAILED -> RunStatus.FAILED;
                            case SUSPENDED, INTERRUPTED, CANCELLED -> RunStatus.STOPPED;
                        };
                runChange =
                        new RunChange(
                                event.executionRunId(),
                                status,
                                outcome.status().value(),
                                outcome.reason());
            } else if (Set.of("step/start", "step/phase", "step/end").contains(event.type())) {
                String outcome = (String) data.get("status");
                StepStatus status =
                        !"step/end".equals(event.type())
                                ? StepStatus.RUNNING
                                : Set.of("continued", "completed").contains(outcome)
                                        ? StepStatus.COMPLETED
                                        : "failed".equals(outcome)
                                                ? StepStatus.FAILED
                                                : StepStatus.STOPPED;
                stepChanges.add(
                        new StepChange(
                                event.executionRunId(),
                                (String) data.get("stepId"),
                                status,
                                StepPhase.valueOf((String) data.get("phase")),
                                (String) data.get("resumedFromStepId"),
                                outcome));
            }
            if ("interaction/requested".equals(event.type())
                    && "external_execution".equals(data.get("kind"))) {
                var call =
                        JsonUtils.getJsonCodec().convertValue(data.get("call"), ToolUseBlock.class);
                String executor = executors.get(call.getName());
                if (executor != null)
                    tools.add(
                            new ToolCall(
                                    call.getId(),
                                    executor,
                                    call.getName(),
                                    DurableJson.value(call.getInput()),
                                    bindings.containsKey(call.getName())
                                            ? bindings.get(call.getName()).digest()
                                            : null,
                                    new RunStepRef(
                                            event.executionRunId(), (String) data.get("stepId"))));
            }
            if ("interaction/requested".equals(event.type())
                    || "interaction/resolved".equals(event.type())) {
                String requestId = String.valueOf(data.get("requestId"));
                var view = new LinkedHashMap<String, Object>();
                view.put("id", DurableJson.hash(List.of(context.lease().turnId(), requestId)));
                view.put("requestId", requestId);
                view.put("turnId", context.lease().turnId());
                view.put("key", "interaction:" + requestId);
                view.put("kind", String.valueOf(data.get("kind")));
                boolean requested = "interaction/requested".equals(event.type());
                view.put(
                        "status",
                        requested
                                ? "pending"
                                : "cancelled".equals(data.get("status"))
                                        ? "cancelled"
                                        : "resolved");
                if (requested) {
                    var call =
                            JsonUtils.getJsonCodec()
                                    .convertValue(data.get("call"), ToolUseBlock.class);
                    view.put("call", Map.of("name", call.getName(), "arguments", call.getInput()));
                    view.put(
                            "automatic",
                            "external_execution".equals(data.get("kind"))
                                    && executors.containsKey(call.getName()));
                }
                publicEvents.add(
                        new EventInput(
                                event.eventId(),
                                requested ? "interaction.requested" : "interaction.resolved",
                                DurableJson.value(view)));
            }
            if ("state/checkpoint".equals(event.type())) {
                hasCheckpoint = true;
                var nativeState = AgentState.fromJsonString((String) data.get("stateJson"));
                for (var message : nativeState.getContext())
                    for (var result : message.getContentBlocks(ToolResultBlock.class))
                        if (!result.isSuspended())
                            appliedCalls.putIfAbsent(
                                    result.getId(),
                                    invocationFacts
                                            ? new RunStepRef(
                                                    event.executionRunId(),
                                                    (String) data.get("stepId"))
                                            : null);
            }
            var message = nextMessages.accept(event);
            if (message.durable() != null) publicEvents.add(message.durable());
            liveEvents.addAll(message.live());
            publicEvents.addAll(nextToolEvents.accept(event, nextMessages));
            if (data.get("stepId") instanceof String stepId) {
                for (int i = firstPublicEvent; i < publicEvents.size(); i++) {
                    var output = publicEvents.get(i);
                    publicEvents.set(
                            i,
                            new EventInput(
                                    output.eventId(),
                                    output.type(),
                                    output.data(),
                                    event.executionRunId(),
                                    stepId));
                }
            }
        }
        state.put("lastCommit", batchId);
        Head result = head(state);
        state.withObject("/receipts")
                .set(batchId, DurableJson.value(Map.of("digest", digest, "head", result)));
        var frame = context.frame();
        var applied =
                frame.tools().stream()
                        .filter(
                                t ->
                                        !t.applied()
                                                && t.result().state().terminal()
                                                && appliedCalls.containsKey(
                                                        t.request().call().callId()))
                        .map(
                                t ->
                                        new AppliedResult(
                                                t.request().executionId(),
                                                appliedCalls.get(t.request().call().callId())))
                        .toList();
        var consumed = hasCheckpoint ? List.copyOf(signalsToApply) : List.<String>of();
        commitNative(
                new Commit(
                        "native:" + batchId,
                        frame.turn().revision(),
                        DurableJson.value(
                                Map.of(
                                        "format",
                                        AgentScopeCheckpointCodec.STATE_VERSION,
                                        "position",
                                        seq,
                                        "lastCommit",
                                        batchId)),
                        tools,
                        applied,
                        consumed,
                        publicEvents,
                        turnStatus,
                        null,
                        null,
                        terminalError,
                        new JournalAppend(
                                expected, events.stream().map(DurableJson::value).toList()),
                        runChange,
                        stepChanges));
        cached = state;
        messages = nextMessages;
        toolEvents = nextToolEvents;
        if (hasCheckpoint) signalsToApply.clear();
        context.publish(liveEvents);
        return result;
    }

    private static TokenUsage usage(JsonNode value) {
        if (value == null || value.isNull()) return null;
        var details = new LinkedHashMap<String, Long>();
        // Core's optional primitive breakdowns use zero for unreported values; do not claim
        // these are known zeros. Main input/output totals are defined when ChatUsage is present.
        for (String name :
                List.of(
                        "cachedTokens",
                        "cacheCreationTokens",
                        "reasoningTokens",
                        "toolUsePromptTokens"))
            if (value.path(name).asLong() > 0) details.put(name, value.path(name).asLong());
        return new TokenUsage(
                value.required("inputTokens").asLong(),
                value.required("outputTokens").asLong(),
                details);
    }

    private static EventInput modelEvent(JournalEvent event) {
        String type =
                switch (event.type()) {
                    case "request/prepared" -> "model.call.started";
                    case "model/end" -> "model.call.ended";
                    case "model/request_started" -> "model.request.started";
                    case "model/request_usage" -> "model.request.usage";
                    case "model/request_ended" -> "model.request.ended";
                    default -> null;
                };
        if (type == null) return null;
        var nativeData = DurableJson.value(event.data());
        var data = new LinkedHashMap<String, Object>();
        data.put("modelCallId", nativeData.required("modelCallId").asText());
        if (type.equals("model.call.started")) {
            data.put("model", nativeData.required("model").asText());
            data.put("purpose", nativeData.required("purpose").asText());
            data.put("iteration", nativeData.required("iteration").asInt());
            data.put("startedAt", event.occurredAt());
        } else if (type.equals("model.call.ended")) {
            data.put("status", nativeData.required("status").asText().toUpperCase(Locale.ROOT));
            data.put("endedAt", event.occurredAt());
            data.put("usage", usage(nativeData.get("usage")));
        } else {
            data.put("requestId", nativeData.required("requestId").asText());
            if (type.equals("model.request.started")) {
                data.put("provider", nativeData.required("provider").asText());
                data.put("attempt", nativeData.required("attempt").asInt());
                data.put("startedAt", nativeData.required("startedAt").asLong());
            } else {
                data.put("usage", usage(nativeData.get("usage")));
                if (type.equals("model.request.ended")) {
                    data.put(
                            "status",
                            nativeData.required("status").asText().toUpperCase(Locale.ROOT));
                    data.put("endedAt", nativeData.required("endedAt").asLong());
                    data.put("durationMillis", nativeData.required("durationMillis").asLong());
                    data.put("usageComplete", nativeData.required("usageComplete").asBoolean());
                    data.put("errorType", nativeData.path("errorType").asText(null));
                }
            }
        }
        return new EventInput(
                event.eventId(),
                type,
                DurableJson.value(data),
                event.executionRunId(),
                nativeData.path("stepId").asText(null));
    }

    private void commitNative(Commit command) {
        try {
            context.commit(command);
        } catch (DurableException conflict) {
            if (conflict.code() != DurableException.Code.CONFLICT) throw conflict;
            var current = context.frame();
            if (current.turn().status() != TurnStatus.PAUSING
                    || current.turn().revision() == command.expectedRevision()) throw conflict;
            // A pause changes the control revision without invalidating this native checkpoint.
            // The journal position, ownership, inputs and effects are still checked atomically.
            context.commit(
                    new Commit(
                            command.commitId(),
                            current.turn().revision(),
                            command.checkpoint(),
                            command.tools(),
                            command.appliedResults(),
                            command.consumedSignals(),
                            command.events(),
                            command.status(),
                            command.waiting(),
                            command.output(),
                            command.error(),
                            command.journal(),
                            command.run(),
                            command.steps()));
        }
    }

    synchronized String messageId(String turnId, Msg message) {
        return messages.messageId(turnId, message);
    }

    @Override
    public synchronized List<JournalEvent> readAfter(long after, int limit) {
        if (after < 0 || limit < 1) throw new IllegalArgumentException("Invalid native range");
        var events = checkpoint().path("events");
        var result = new ArrayList<JournalEvent>();
        for (long i = after; i < events.size() && result.size() < limit; i++)
            result.add(DurableJson.convert(events.get((int) i), JournalEvent.class));
        return List.copyOf(result);
    }

    void append(String turnId, List<Map.Entry<String, Object>> facts) {
        append(turnId, facts, TurnStatus.RUNNING, null);
    }

    /** Native settlement and the terminal task outcome share one Backend transaction. */
    synchronized void append(
            String turnId, List<Map.Entry<String, Object>> facts, TurnStatus status, String error) {
        var writer = acquire("adapter:" + UUID.randomUUID(), Duration.ofMinutes(1));
        try {
            long sequence = head().seq();
            var events = new ArrayList<JournalEvent>();
            for (var fact : facts)
                events.add(
                        new JournalEvent(
                                JournalEvent.SCHEMA_VERSION,
                                UUID.randomUUID().toString(),
                                ++sequence,
                                context.frame().now(),
                                fact.getKey(),
                                writer.owner(),
                                turnId,
                                true,
                                JsonUtils.getJsonCodec().toJson(fact.getValue())));
            commit(
                    writer,
                    UUID.randomUUID().toString(),
                    sequence - events.size(),
                    events,
                    status,
                    error,
                    false);
        } finally {
            release(writer);
        }
    }
}
