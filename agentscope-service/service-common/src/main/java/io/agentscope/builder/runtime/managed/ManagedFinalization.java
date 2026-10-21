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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.agent.AgentFinalization;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.core.session.SessionModelInvocation;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.util.JsonUtils;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Durable finalization phase; no primary inference or completed auxiliary call is repeated. */
final class ManagedFinalization implements AgentFinalization, SessionModelInvocation {
    static final String START = "managed/finalization_started";
    static final String END = "managed/finalization_completed";
    static final String MODEL = "managed/finalization_model";

    private record Frame(String id, long startedAt, String zone, Msg result, String stateJson) {}

    private final ManagedTurnExecution execution;
    private final ObjectMapper mapper;
    private final AtomicInteger modelSequence = new AtomicInteger();
    private volatile boolean active;
    private Frame frame;
    private boolean completed;
    private RuntimeContext context;

    ManagedFinalization(ManagedTurnExecution execution, ObjectMapper mapper) {
        this.execution = execution;
        this.mapper = mapper;
        SessionEvent start = null;
        for (var event : events()) {
            if (START.equals(event.type())) {
                start = event;
                completed = false;
            } else if (END.equals(event.type())
                    && start != null
                    && start.data().get("id").equals(event.data().get("id"))) completed = true;
        }
        if (start != null) {
            var data = execution.log().materialize(start).data();
            frame =
                    new Frame(
                            (String) data.get("id"),
                            ((Number) data.get("startedAt")).longValue(),
                            (String) data.get("zone"),
                            JsonUtils.getJsonCodec().convertValue(data.get("result"), Msg.class),
                            (String) data.get("stateJson"));
        }
    }

    boolean pending() {
        return frame != null && !completed;
    }

    /** Re-run pending hooks from their frozen entry state, not a failed hook's partial state. */
    void restoreEntry() {
        if (!pending()) return;
        String id = "finalization_restore_" + UUID.randomUUID();
        var event =
                new SessionEvent(
                        1,
                        id,
                        execution.head().sequence() + 1,
                        System.currentTimeMillis(),
                        "state/checkpoint",
                        id,
                        execution.turnId(),
                        true,
                        JsonUtils.getJsonCodec()
                                .toJson(
                                        Map.of(
                                                "stateJson",
                                                frame.stateJson(),
                                                "reason",
                                                "managed_finalization_replay")));
        execution.boundary(id, List.of(event), List.of(), List.of(), null);
    }

    @Override
    public Msg resumeResult() {
        return frame == null ? null : frame.result();
    }

    @Override
    public Mono<Void> finish(
            RuntimeContext runtime, Msg result, Supplier<Mono<Void>> continuation) {
        if (!"completed".equals(SessionRecorder.outcome(result.getGenerateReason())))
            return continuation.get();
        return Mono.<Void>defer(
                        () -> {
                            if (completed) return Mono.empty();
                            execution.check();
                            this.context = runtime;
                            var recorder = SessionRecorder.from(runtime);
                            if (frame == null) {
                                frame =
                                        new Frame(
                                                UUID.randomUUID().toString(),
                                                System.currentTimeMillis(),
                                                ZoneId.systemDefault().getId(),
                                                result,
                                                runtime.getAgentState().toJson());
                                recorder.append(
                                        START,
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
                                                frame.stateJson()),
                                        true);
                                recorder.checkpointNow(
                                        runtime.getAgentState(), "managed_finalization_start");
                            }
                            Clock previous = runtime.get(Clock.class);
                            runtime.put(
                                    Clock.class,
                                    Clock.fixed(
                                            Instant.ofEpochMilli(frame.startedAt()),
                                            ZoneId.of(frame.zone())));
                            execution.local().frameworkScope("framework_final_" + frame.id());
                            modelSequence.set(0);
                            active = true;
                            return Mono.defer(continuation)
                                    .then(
                                            Mono.<Void>fromRunnable(
                                                    () -> {
                                                        execution.check();
                                                        recorder.append(
                                                                END,
                                                                Map.of("id", frame.id()),
                                                                true);
                                                        recorder.checkpointNow(
                                                                runtime.getAgentState(),
                                                                "managed_finalization_completed");
                                                        completed = true;
                                                    }))
                                    .doFinally(
                                            signal -> {
                                                active = false;
                                                runtime.put(Clock.class, previous);
                                                execution.local().frameworkScope(null);
                                            });
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<ChatResponse> stream(
            String model,
            String purpose,
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions options,
            Function<String, Flux<ChatResponse>> next) {
        if (!active) return next.apply(UUID.randomUUID().toString());
        return Flux.defer(
                        () -> {
                            execution.check();
                            int slot = modelSequence.getAndIncrement();
                            String requestHash =
                                    requestHash(mapper, model, purpose, messages, tools, options);
                            var history = events();
                            for (var descriptor : history) {
                                if (!MODEL.equals(descriptor.type())
                                        || !frame.id().equals(descriptor.data().get("frame"))
                                        || slot
                                                != ((Number) descriptor.data().get("slot"))
                                                        .intValue()) continue;
                                if (!requestHash.equals(descriptor.data().get("requestHash")))
                                    throw new SessionLogException(
                                            "Finalization replay differs from its frozen request");
                                String id = (String) descriptor.data().get("modelCallId");
                                boolean done =
                                        history.stream()
                                                .anyMatch(
                                                        event ->
                                                                "model/end".equals(event.type())
                                                                        && id.equals(
                                                                                event.data()
                                                                                        .get(
                                                                                                "modelCallId"))
                                                                        && "completed"
                                                                                .equals(
                                                                                        event.data()
                                                                                                .get(
                                                                                                        "status")));
                                if (done) {
                                    var chunks = new ArrayList<ChatResponse>();
                                    for (var event : history)
                                        if ("model/chunk".equals(event.type())
                                                && id.equals(event.data().get("modelCallId")))
                                            chunks.add(
                                                    response(
                                                            (Map<?, ?>) event.data().get("chunk")));
                                    return Flux.fromIterable(chunks);
                                }
                            }
                            String id = UUID.randomUUID().toString();
                            var recorder = SessionRecorder.from(context);
                            recorder.append(
                                    MODEL,
                                    Map.of(
                                            "frame",
                                            frame.id(),
                                            "slot",
                                            slot,
                                            "requestHash",
                                            requestHash,
                                            "modelCallId",
                                            id),
                                    true);
                            recorder.flushNow();
                            return next.apply(id);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    static String requestHash(
            ObjectMapper mapper,
            String model,
            String purpose,
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions options) {
        var normalized = new ArrayList<Object>();
        for (var message : messages) {
            ObjectNode node;
            try {
                node = (ObjectNode) mapper.readTree(JsonUtils.getJsonCodec().toJson(message));
            } catch (JsonProcessingException invalid) {
                throw new IllegalArgumentException("Invalid finalization request", invalid);
            }
            // Envelope identity is regenerated while the same frozen auxiliary prompt is rebuilt.
            normalizeMessage(node);
            normalized.add(node);
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("model", model);
        data.put("purpose", purpose);
        data.put("messages", normalized);
        data.put("tools", tools);
        data.put("options", options);
        try {
            var canonical = mapper.convertValue(mapper.valueToTree(data), Object.class);
            return ManagedScope.fingerprint(
                    mapper.writer()
                            .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                            .writeValueAsString(canonical));
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Cannot freeze finalization request", invalid);
        }
    }

    private static void normalizeMessage(ObjectNode message) {
        message.remove(List.of("id", "timestamp"));
        // Harness retains source system messages inside its compiled system envelope. Their
        // generated envelope identity has the same semantics as the outer message's identity.
        JsonNode originals =
                message.path("metadata").path("agentscope.context.original_system_inputs");
        if (originals.isArray())
            for (JsonNode original : originals)
                if (original instanceof ObjectNode object) normalizeMessage(object);
    }

    private static ChatResponse response(Map<?, ?> chunk) {
        var content = new ArrayList<ContentBlock>();
        if (chunk.get("content") instanceof List<?> blocks)
            for (var block : blocks)
                content.add(JsonUtils.getJsonCodec().convertValue(block, ContentBlock.class));
        @SuppressWarnings("unchecked")
        var metadata = (Map<String, Object>) chunk.get("metadata");
        return ChatResponse.builder()
                .id((String) chunk.get("id"))
                .content(content)
                .usage(
                        chunk.get("usage") == null
                                ? null
                                : JsonUtils.getJsonCodec()
                                        .convertValue(chunk.get("usage"), ChatUsage.class))
                .metadata(metadata)
                .finishReason((String) chunk.get("finishReason"))
                .build();
    }

    private List<SessionEvent> events() {
        var log = execution.log();
        var result = new ArrayList<SessionEvent>();
        for (var event : log.scanMetadata(0, log.head().seq()))
            if (execution.turnId().equals(event.turnId())) result.add(event);
        return result;
    }
}
