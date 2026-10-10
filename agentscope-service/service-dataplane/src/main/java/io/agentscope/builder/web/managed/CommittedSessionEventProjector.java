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
package io.agentscope.builder.web.managed;

import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Public committed facts. Prompts, checkpoints and private thinking stay in the native journal. */
public final class CommittedSessionEventProjector {
    private CommittedSessionEventProjector() {}

    public record Event(String type, Map<String, Object> payload) {}

    public static List<Object> publicContent(Object raw) {
        if (!(raw instanceof List<?> blocks)) return List.of();
        var content = new ArrayList<Object>();
        for (var block : blocks) {
            if (!(block instanceof Map<?, ?> value)) continue;
            String type = String.valueOf(value.get("type"));
            if (Set.of("text", "image", "audio", "video", "file", "data").contains(type)) {
                var copy = new LinkedHashMap<String, Object>();
                value.forEach(
                        (k, v) -> {
                            if (!"metadata".equals(k)) copy.put(String.valueOf(k), v);
                        });
                content.add(copy);
            } else if (type.equals("tool_use") || type.equals("tool_result")) {
                var copy = new LinkedHashMap<String, Object>();
                for (String key : List.of("type", "id", "name", "state", "input", "content"))
                    if (value.get(key) != null) copy.put(key, value.get(key));
                if (type.equals("tool_result")) {
                    copy.put("status", "completed");
                    copy.put("output", publicContent(value.get("output")));
                }
                content.add(copy);
            }
        }
        return List.copyOf(content);
    }

    public static Optional<Event> project(SessionEvent event) {
        var data = event.data();
        String type =
                switch (event.type()) {
                    case "run/start" -> "run.started";
                    case "run/end" -> "run.ended";
                    case "request/prepared" -> "item.started";
                    case "model/chunk" -> "item.delta";
                    case "message/user", "message/assistant", "tool/result", "turn/output" ->
                            "item.completed";
                    case "model/end" ->
                            data.get("usage") == null ? "model.completed" : "usage.recorded";
                    case "input/applied", "inbox/applied" -> "input.applied";
                    case "inbox/accepted" -> "input.accepted";
                    case "inbox/rejected" -> "input.rejected";
                    case "tool/requested" -> "tool.requested";
                    case "tool/dispatch" -> "tool.dispatched";
                    case "tool/chunk" -> "tool.delta";
                    case "action/end" -> "tool.completed";
                    case "interaction/requested" -> "required_action.created";
                    case "interaction/resolved" -> "required_action.resolved";
                    case "context/replaced" -> "context.compacted";
                    case "migration/baseline" -> "session.context_initialized";
                    case "state/restored" -> "session.restored";
                    case "subagent/spawned" -> "subagent.started";
                    case "subagent/completed" -> "subagent.completed";
                    case "presentation/hint" -> "session.hint";
                    default -> null;
                };
        if (type == null) return Optional.empty();
        if ((type.equals("item.started") || type.equals("item.delta"))
                && !"REASONING".equals(data.get("purpose"))) return Optional.empty();
        if (type.equals("input.accepted") && !Set.of("steer", "inject").contains(data.get("kind")))
            return Optional.empty();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("schema_version", 1);
        if (event.turnId() != null) payload.put("turn_id", event.turnId());
        if (event.executionRunId() != null) payload.put("run_id", event.executionRunId());
        var source = new LinkedHashMap<String, Object>();
        source.put("native_event_id", event.eventId());
        source.put("native_seq", event.seq());
        if (event.executionRunId() != null) source.put("execution_run_id", event.executionRunId());
        payload.put("source", source);
        if (type.equals("item.completed")) {
            if (!(data.get("message") instanceof Map<?, ?> message)) return Optional.empty();
            if ("system".equalsIgnoreCase(String.valueOf(message.get("role"))))
                return Optional.empty();
            String itemId =
                    data.get("modelCallId") == null
                            ? "item_" + message.get("id")
                            : "item_model_" + data.get("modelCallId");
            var item = new LinkedHashMap<String, Object>();
            item.put("id", itemId);
            item.put("message_id", message.get("id"));
            item.put("type", event.type().equals("tool/result") ? "tool_result" : "message");
            item.put("role", message.get("role"));
            item.put("content", publicContent(message.get("content")));
            item.put("status", data.getOrDefault("status", "completed"));
            payload.put("item_id", itemId);
            payload.put("item", item);
            if (data.get("modelCallId") != null)
                payload.put("model_call_id", data.get("modelCallId"));
            if (event.type().equals("turn/output")) payload.put("final_output", true);
        } else if (type.equals("item.started") || type.equals("item.delta")) {
            String itemId = "item_model_" + data.get("modelCallId");
            payload.put("item_id", itemId);
            payload.put("model_call_id", data.get("modelCallId"));
            payload.put("position", event.seq());
            if (type.equals("item.started")) {
                payload.put(
                        "item",
                        Map.of(
                                "id",
                                itemId,
                                "type",
                                "message",
                                "role",
                                "assistant",
                                "content",
                                List.of(),
                                "status",
                                "in_progress"));
            } else if (data.get("chunk") instanceof Map<?, ?> chunk) {
                payload.put("content", publicContent(chunk.get("content")));
                payload.put("message_id", chunk.get("id"));
            }
        } else if (type.equals("usage.recorded") || type.equals("model.completed")) {
            payload.put("model_call_id", data.get("modelCallId"));
            payload.put("model", data.get("model"));
            payload.put("status", data.get("status"));
            if ("REASONING".equals(data.get("purpose")))
                payload.put("item_id", "item_model_" + data.get("modelCallId"));
            if (data.get("usage") != null) payload.put("usage", data.get("usage"));
            payload.put("scope", "session_only");
        } else if (type.startsWith("input.")) {
            payload.put("status", type.substring(6));
            Object id =
                    data.getOrDefault("inputId", data.getOrDefault("commandId", data.get("id")));
            if (id != null) payload.put("input_id", id);
            if (data.get("commandIds") != null) payload.put("input_ids", data.get("commandIds"));
            if (data.get("kind") != null) payload.put("kind", data.get("kind"));
            if (data.get("reason") != null) payload.put("reason", data.get("reason"));
        } else if (type.startsWith("tool.")) {
            payload.put("tool_call_id", data.get("toolCallId"));
            if (data.get("call") instanceof Map<?, ?> call) {
                payload.put("name", call.get("name"));
                payload.put("input", call.get("input"));
            }
            for (String field : List.of("name", "arguments"))
                if (data.get(field) != null)
                    payload.put(field.equals("arguments") ? "input" : field, data.get(field));
            if (data.get("chunk") instanceof Map<?, ?> chunk)
                payload.put("output", publicContent(chunk.get("output")));
            if (data.get("result") instanceof Map<?, ?> result)
                payload.put(
                        "result",
                        publicContent(List.of(result)).stream().findFirst().orElse(Map.of()));
            if (data.get("observation") instanceof Map<?, ?> observation)
                payload.put("status", observation.get("status"));
        } else if (type.equals("session.context_initialized")) {
            var items = new ArrayList<Map<String, Object>>();
            if (data.get("stateJson") instanceof String json)
                for (var message : AgentState.fromJsonString(json).getContext()) {
                    if (message.getRole().name().equals("SYSTEM")) continue;
                    var encoded = JsonUtils.getJsonCodec().convertValue(message, Map.class);
                    items.add(
                            Map.of(
                                    "id",
                                    "item_" + message.getId(),
                                    "message_id",
                                    message.getId(),
                                    "type",
                                    "message",
                                    "role",
                                    message.getRole().name().toLowerCase(),
                                    "status",
                                    "completed",
                                    "content",
                                    publicContent(encoded.get("content"))));
                }
            payload.put("items", items);
            payload.put("coverage", "baseline_only");
        } else if (type.equals("session.restored")) {
            for (String key : List.of("checkpoint_id", "operation_id", "reason"))
                if (data.get(key) != null) payload.put(key, data.get(key));
        } else if (type.equals("context.compacted")) {
            payload.put("reason", data.get("reason"));
        } else if (type.startsWith("required_action.")) {
            payload.put("request_id", data.getOrDefault("requestId", event.eventId()));
            payload.put("kind", data.getOrDefault("kind", "external_execution"));
            payload.put("status", type.endsWith("created") ? "pending" : "resolved");
            if (data.containsKey("call")) payload.put("tool_call", data.get("call"));
            if (data.containsKey("result")) payload.put("result", data.get("result"));
        } else if (type.startsWith("run.")) {
            if (data.containsKey("status")) payload.put("status", data.get("status"));
            if (data.containsKey("reason")) payload.put("reason", data.get("reason"));
        } else payload.putAll(data);
        return Optional.of(new Event(type, Collections.unmodifiableMap(payload)));
    }
}
