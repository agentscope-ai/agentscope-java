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
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.HarnessContext;
import io.agentscope.durable.codec.agentscope.DurableEventSchemas;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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
    private Frame frame;
    private long replayAfter;
    private final Set<String> replayed = new HashSet<>();
    private boolean completed;
    private volatile boolean active;
    private RuntimeContext context;
    private final AtomicInteger slot = new AtomicInteger();
    private final AtomicInteger storageSlot = new AtomicInteger();

    @Override
    public Mono<Void> beforeBoundary(Boundary boundary, RuntimeContext runtime, AgentState state) {
        return Mono.<Void>fromRunnable(
                        () -> {
                            checkControl();
                            if (boundary == Boundary.REASONING) applyInputs(runtime, state);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void checkControl() {
        var turn = durable.frame().turn();
        if (turn.cancellationRequested())
            throw new DurableException(
                    DurableException.Code.FENCED, "Cancellation requests a durable handoff");
        if (turn.status() == TurnStatus.PAUSING) throw new AgentExecutionHost.Paused();
    }

    private void applyInputs(RuntimeContext runtime, AgentState state) {
        var pending =
                durable.frame().signals().stream()
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
            var message = AgentScopeMessages.input(signal.value(), "signal:" + signal.id());
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

    AgentScopeExecutionHost(HarnessContext durable, BackendThreadJournal log) {
        this.durable = durable;
        this.log = log;
        for (var event : log.scan(0, log.head().seq())) {
            if ("state/checkpoint".equals(event.type())) replayAfter = event.seq();
            if (!durable.lease().turnId().equals(event.turnId())) continue;
            var data = event.data();
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
                                if (hash.equals(outcome.get("replayHash"))
                                        && (active
                                                ? id.equals(data.get("modelCallId"))
                                                : event.seq() > checkpoint
                                                        && !replayed.contains(event.eventId()))) {
                                    replayed.add(event.eventId());
                                    return Flux.fromIterable((List<?>) outcome.get("responses"))
                                            .map(value -> response((Map<?, ?>) value));
                                }
                            }
                            String call = id == null ? UUID.randomUUID().toString() : id;
                            log.modelStarted(
                                    call,
                                    new ModelCapture(
                                            model, purpose, messages, tools, options, hash));
                            return Flux.defer(() -> next.apply(call))
                                    .doOnNext(chunk -> log.modelChunk(call, chunk));
                        })
                .subscribeOn(Schedulers.boundedElastic());
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
