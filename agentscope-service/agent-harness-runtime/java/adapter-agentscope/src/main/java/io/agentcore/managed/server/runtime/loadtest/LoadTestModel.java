/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.loadtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelSchedulers;
import io.agentscope.core.model.ModelUtils;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/** Deterministic, cold streaming model. Recovery decisions use messages, never instance counters. */
public final class LoadTestModel implements Model {
    public static final String REQUEST_PREFIX = "LOADTEST ";
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public record Profile(
            long firstChunkDelayMs,
            long chunkIntervalMs,
            int chunks,
            int chunkChars,
            String toolName,
            Map<String, Object> toolArguments,
            int toolRounds,
            int parallelToolCalls) {
        public Profile {
            if (firstChunkDelayMs < 0
                    || firstChunkDelayMs > 120000
                    || chunkIntervalMs < 0
                    || chunkIntervalMs > 10000
                    || chunks < 1
                    || chunks > 512
                    || chunkChars < 1
                    || (long) chunks * chunkChars > 1048576
                    || toolRounds < 0
                    || toolRounds > 8
                    || parallelToolCalls < 1
                    || parallelToolCalls > 16)
                throw new IllegalArgumentException("Invalid load-test timing/output/tool bounds");
            if (toolRounds > 0 && (toolName == null || !toolName.matches("[A-Za-z0-9_.-]{1,128}")))
                throw new IllegalArgumentException("A configured tool name is required");
            toolArguments = toolArguments == null ? Map.of() : Map.copyOf(toolArguments);
        }
    }

    /** Metrics only; these counters never determine the next reasoning step. */
    public static final class Stats {
        public final AtomicLong requests = new AtomicLong();
        public final AtomicLong active = new AtomicLong();
        public final AtomicLong completed = new AtomicLong();
        public final AtomicLong cancelled = new AtomicLong();
        public final AtomicLong errors = new AtomicLong();
        public final AtomicLong chunks = new AtomicLong();
    }

    private final String name;
    private final Profile profile;
    private final String fingerprint;
    private final Stats stats = new Stats();

    public LoadTestModel(String name, Profile profile) {
        if (name == null || !name.matches("loadtest:[a-z0-9-]{1,64}"))
            throw new IllegalArgumentException("Explicit loadtest:model name is required");
        this.name = name;
        this.profile = profile;
        fingerprint = ManagedScope.fingerprint(name, json(profile));
    }

    public String getModelName() {
        return name;
    }

    public Stats stats() {
        return stats;
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        var source =
                Flux.defer(() -> response(messages, tools))
                        .publishOn(ModelSchedulers.responses(), 1);
        return ModelUtils.applyTimeoutAndRetry(
                        source, options, GenerateOptions.builder().build(), name, "loadtest")
                .doOnSubscribe(
                        ignored -> {
                            stats.requests.incrementAndGet();
                            stats.active.incrementAndGet();
                        })
                .doOnNext(ignored -> stats.chunks.incrementAndGet())
                .doFinally(
                        signal -> {
                            stats.active.decrementAndGet();
                            if (signal == SignalType.CANCEL) stats.cancelled.incrementAndGet();
                            else if (signal == SignalType.ON_ERROR) stats.errors.incrementAndGet();
                            else if (signal == SignalType.ON_COMPLETE)
                                stats.completed.incrementAndGet();
                        });
    }

    private Flux<ChatResponse> response(List<Msg> messages, List<ToolSchema> tools) {
        // Harness memory extraction is a separate call using the same Model. It must not dispatch
        // business tools a second time. Other auxiliary calls without a benchmark request are
        // text-only.
        boolean memory =
                messages.stream()
                        .anyMatch(
                                m ->
                                        m.getRole() == MsgRole.SYSTEM
                                                && m.getTextContent()
                                                        .contains("memory extraction assistant"));
        var requestMessage =
                messages.stream()
                        .filter(
                                m ->
                                        m.getRole() == MsgRole.USER
                                                && m.getTextContent().startsWith(REQUEST_PREFIX))
                        .reduce((a, b) -> b);
        if (memory || requestMessage.isEmpty())
            return emit("auxiliary", "Synthetic load-test auxiliary response.", List.of());
        var request =
                parseRequest(
                        requestMessage.get().getTextContent().substring(REQUEST_PREFIX.length()));
        String requestId = required(request, "request_id");
        String marker = required(request, "marker");
        // Only inspect the current request's history, including its corresponding tool messages.
        int start = messages.indexOf(requestMessage.get());
        var calls = new LinkedHashMap<String, ToolUseBlock>();
        var results = new LinkedHashMap<String, ToolResultBlock>();
        for (var message : messages.subList(start, messages.size())) {
            for (var call : message.getContentBlocks(ToolUseBlock.class))
                calls.put(call.getId(), call);
            for (var result : message.getContentBlocks(ToolResultBlock.class)) {
                var previous = results.putIfAbsent(result.getId(), result);
                if (previous != null && !json(previous).equals(json(result)))
                    throw new IllegalArgumentException("Conflicting replayed tool result");
            }
        }
        var evidence = new ArrayList<String>();
        for (int round = 0; round < profile.toolRounds(); round++) {
            var next = new ArrayList<ContentBlock>();
            for (int call = 0; call < profile.parallelToolCalls(); call++) {
                String id =
                        "lt_"
                                + ManagedScope.fingerprint(
                                        fingerprint, requestId, "" + round, "" + call);
                var result = results.get(id);
                if (result != null) {
                    if (!calls.containsKey(id)
                            || !profile.toolName().equals(calls.get(id).getName()))
                        throw new IllegalArgumentException(
                                "Tool result has no matching persisted call");
                    // SDK ToolResultBlock defaults to RUNNING even for a returned MCP payload.
                    // The Runtime supplies committed results; reject explicit failure/suspension
                    // and absent output instead of treating that default as an external status.
                    if (result.getState() == ToolResultState.ERROR
                            || result.getState() == ToolResultState.DENIED
                            || result.getState() == ToolResultState.INTERRUPTED
                            || result.isSuspended()
                            || result.getOutput().isEmpty())
                        throw new IllegalArgumentException(
                                "Load-test tool returned an error or incomplete result");
                    String output = json(result.getOutput());
                    if (output.length() > 65536)
                        throw new IllegalArgumentException("Load-test tool result too large");
                    evidence.add(output);
                } else {
                    if (calls.containsKey(id))
                        throw new IllegalArgumentException(
                                "Persisted tool call is still unresolved");
                    if (tools == null
                            || tools.stream()
                                    .noneMatch(t -> t.getName().equals(profile.toolName())))
                        throw new IllegalArgumentException(
                                "Configured load-test tool is not available");
                    String toolMarker =
                            profile.toolRounds() == 1 && profile.parallelToolCalls() == 1
                                    ? marker
                                    : marker + "-r" + round + "-c" + call;
                    var arguments = new LinkedHashMap<String, Object>();
                    profile.toolArguments()
                            .forEach(
                                    (key, value) ->
                                            arguments.put(
                                                    key,
                                                    "{{marker}}".equals(value)
                                                            ? toolMarker
                                                            : value));
                    next.add(
                            ToolUseBlock.builder()
                                    .id(id)
                                    .name(profile.toolName())
                                    .input(arguments)
                                    .content(json(arguments))
                                    .build());
                }
            }
            if (!next.isEmpty())
                return emit(requestId + "-r" + round, "Synthetic tool dispatch.", next);
        }
        return emit(
                requestId + "-final",
                "PTS_OK marker=" + marker + " tools=" + json(evidence),
                List.of());
    }

    private Flux<ChatResponse> emit(String identity, String text, List<ContentBlock> toolCalls) {
        String content =
                text
                        + "."
                                .repeat(
                                        Math.max(
                                                0,
                                                profile.chunks() * profile.chunkChars()
                                                        - text.length()));
        int points = content.codePointCount(0, content.length());
        long started = System.nanoTime();
        String responseId = "lt_response_" + ManagedScope.fingerprint(fingerprint, identity);
        return Flux.range(0, profile.chunks())
                .concatMap(
                        index -> {
                            int begin =
                                    content.offsetByCodePoints(
                                            0, points * index / profile.chunks());
                            int end =
                                    content.offsetByCodePoints(
                                            0, points * (index + 1) / profile.chunks());
                            var blocks = new ArrayList<ContentBlock>();
                            blocks.add(
                                    TextBlock.builder()
                                            .text(content.substring(begin, end))
                                            .build());
                            boolean last = index == profile.chunks() - 1;
                            if (last) blocks.addAll(toolCalls);
                            return Mono.delay(
                                            Duration.ofMillis(
                                                    index == 0
                                                            ? profile.firstChunkDelayMs()
                                                            : profile.chunkIntervalMs()))
                                    .map(
                                            ignored ->
                                                    ChatResponse.builder()
                                                            .id(responseId)
                                                            .content(blocks)
                                                            .metadata(
                                                                    Map.of(
                                                                            "synthetic",
                                                                            true,
                                                                            "profile",
                                                                            name))
                                                            .finishReason(
                                                                    last
                                                                            ? (toolCalls.isEmpty()
                                                                                    ? "stop"
                                                                                    : "tool_calls")
                                                                            : null)
                                                            .usage(
                                                                    last
                                                                            ? new ChatUsage(
                                                                                    128,
                                                                                    Math.max(
                                                                                            1,
                                                                                            points
                                                                                                    / 4),
                                                                                    (System
                                                                                                            .nanoTime()
                                                                                                    - started)
                                                                                            / 1_000_000_000.0)
                                                                            : null)
                                                            .build());
                        },
                        1);
    }

    private static Map<String, Object> parseRequest(String value) {
        if (value.length() > 4096)
            throw new IllegalArgumentException("Load-test request too large");
        try {
            return JSON.readValue(
                    value,
                    JSON.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid LOADTEST request", invalid);
        }
    }

    private static String required(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (!(value instanceof String text) || !text.matches("[A-Za-z0-9_.:-]{1,128}"))
            throw new IllegalArgumentException("Invalid load-test " + key);
        return text;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid load-test payload", invalid);
        }
    }
}
