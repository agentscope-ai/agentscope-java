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
package io.agentscope.durable.codec.agentscope;

import io.agentscope.core.journal.JournalEvent;
import io.agentscope.core.journal.JournalEventCodecRegistry;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.durable.codec.NativeCheckpointCodec;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stateless AgentScope checkpoint semantics; no Brain, Spring, database or transport dependency. */
public final class AgentScopeCheckpointCodec implements NativeCheckpointCodec {
    /** Native journal/checkpoint format shared by execution and backend-only hosts. */
    public static final String STATE_VERSION = "agentscope-thread-journal-v2";

    private final JournalEventCodecRegistry registry = new JournalEventCodecRegistry();

    public AgentScopeCheckpointCodec() {
        DurableEventSchemas.register(registry);
    }

    public String harnessType() {
        return "agentscope-java";
    }

    public Inspection inspect(Event record) {
        var event =
                new JournalEvent(
                        JournalEvent.SCHEMA_VERSION,
                        "inspection",
                        1,
                        1,
                        record.type(),
                        record.runId(),
                        null,
                        record.required(),
                        record.payloadJson());
        registry.validate(event);
        var data = event.data();
        var opened = new LinkedHashMap<String, List<String>>();
        var closed = new LinkedHashMap<String, List<String>>();
        switch (event.type()) {
            case "tool/dispatch" -> opened.put("tools", List.of((String) data.get("toolCallId")));
            case "action/end" -> {
                if (data.get("result") != null)
                    closed.put("tools", List.of((String) data.get("toolCallId")));
            }
            case "run/start" -> opened.put("runs", List.of(event.executionRunId()));
            case "run/end" -> closed.put("runs", List.of(event.executionRunId()));
            case "interaction/requested" ->
                    opened.put("actions", List.of(String.valueOf(data.get("requestId"))));
            case "interaction/resolved" ->
                    closed.put("actions", List.of(String.valueOf(data.get("requestId"))));
            case "recovery/applied" -> {
                if (data.get("interruptedRuns") instanceof List<?> ids)
                    closed.put("runs", ids.stream().map(String::valueOf).toList());
                if (data.get("resolvedToolCallIds") instanceof List<?> ids)
                    closed.put("tools", ids.stream().map(String::valueOf).toList());
            }
            default -> {}
        }
        boolean checkpoint =
                Set.of("state/checkpoint", "state/restored", "state/initialized")
                        .contains(event.type());
        String state = data.get("stateJson") instanceof String value ? value : null;
        return new Inspection(
                checkpoint,
                "tool/dispatch".equals(event.type()),
                state,
                checkpoint && settled(state),
                Map.copyOf(opened),
                Map.copyOf(closed));
    }

    private boolean settled(String json) {
        try {
            var state = AgentState.fromJsonString(json);
            var uses = new LinkedHashSet<String>();
            var results = new LinkedHashSet<String>();
            for (var message : state.getContext()) {
                for (var use : message.getContentBlocks(ToolUseBlock.class)) uses.add(use.getId());
                for (var result : message.getContentBlocks(ToolResultBlock.class))
                    if (!result.isSuspended()) results.add(result.getId());
            }
            return results.containsAll(uses);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public Baseline baseline(
            String payloadJson,
            String sessionId,
            String threadId,
            String userId,
            boolean fork,
            Map<String, Object> metadata) {
        var payload = JsonUtils.getJsonCodec().fromJson(payloadJson, Map.class);
        @SuppressWarnings("unchecked")
        var state =
                new LinkedHashMap<String, Object>(
                        JsonUtils.getJsonCodec()
                                .fromJson((String) payload.get("stateJson"), Map.class));
        state.put("session_id", sessionId);
        state.put("thread_id", threadId);
        state.put("user_id", userId);
        var data = new LinkedHashMap<String, Object>(metadata);
        data.put("stateJson", JsonUtils.getJsonCodec().toJson(state));
        data.put("projectorVersion", 1);
        data.put("coverage", "baseline_only");
        return new Baseline(
                fork ? "state/initialized" : "state/restored",
                JsonUtils.getJsonCodec().toJson(data));
    }
}
