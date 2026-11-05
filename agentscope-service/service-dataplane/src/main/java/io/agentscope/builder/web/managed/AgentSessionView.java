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

import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Incremental public resource reducer; serializable state is independent of a worker JVM. */
@SuppressWarnings("unchecked")
public final class AgentSessionView {
    private final Map<String, Object> state;

    public AgentSessionView(Map<String, Object> state) {
        this.state =
                new LinkedHashMap<>(
                        JsonUtils.getJsonCodec()
                                .fromJson(JsonUtils.getJsonCodec().toJson(state), Map.class));
    }

    public Map<String, Object> state() {
        return state;
    }

    public long sequence() {
        return ((Number) state.getOrDefault("sequence", 0L)).longValue();
    }

    private Map<String, Object> index(String name) {
        return (Map<String, Object>) state.computeIfAbsent(name, ignored -> new LinkedHashMap<>());
    }

    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static List<Object> list(Object value) {
        return value instanceof List<?> ? (List<Object>) value : List.of();
    }

    private static String key(Map<String, Object> data, String field) {
        return String.valueOf(data.get(field));
    }

    public static boolean isPublic(SessionEventDto event) {
        return event.payload() != null
                && (event.payload().containsKey("source")
                        || List.of(
                                        "required_action.",
                                        "artifact.",
                                        "turn.",
                                        "input.",
                                        "file.",
                                        "budget.",
                                        "session.status_",
                                        "session.restored",
                                        "session.forked")
                                .stream()
                                .anyMatch(event.type()::startsWith)
                        || event.type().equals("session.error"));
    }

    public static Map<String, Object> envelope(SessionEventDto event) {
        var data = new LinkedHashMap<>(event.payload());
        data.remove("source");
        var result = new LinkedHashMap<String, Object>();
        result.put("schema_version", 1);
        result.put("id", event.id());
        result.put("type", event.type());
        result.put("session_id", event.sessionId());
        result.put("created_at", event.createdAt());
        result.put("cursor", SessionEventCursor.encode(event.sessionId(), event.seq()));
        result.put("data", data);
        return result;
    }

    public void apply(SessionEventDto event) {
        if (event.seq() <= sequence()) return;
        state.put("sequence", event.seq());
        if (!isPublic(event)) return;
        var envelope = envelope(event);
        var data = map(envelope.get("data"));
        String type = event.type();
        if (type.equals("session.context_initialized")) {
            for (var raw : list(data.get("items"))) {
                var item = map(raw);
                putItem(envelope, item);
                for (var block : list(item.get("content"))) {
                    var value = map(block);
                    if ("tool_use".equals(value.get("type")))
                        tool(data, value, "requested", envelope);
                    if ("tool_result".equals(value.get("type")))
                        tool(data, value, "completed", envelope);
                }
            }
        } else if (type.equals("item.started") || type.equals("item.completed")) {
            String id = key(data, "item_id");
            index("items").put(id, envelope);
            for (var block : list(map(data.get("item")).get("content"))) {
                var value = map(block);
                if ("tool_use".equals(value.get("type"))) tool(data, value, "requested", envelope);
                if ("tool_result".equals(value.get("type")))
                    tool(data, value, "completed", envelope);
            }
        } else if (type.equals("item.delta")) {
            String id = key(data, "item_id");
            var previous = map(index("items").get(id));
            var item = new LinkedHashMap<>(map(map(previous.get("data")).get("item")));
            if (Set.of("completed", "incomplete").contains(String.valueOf(item.get("status"))))
                return;
            var content = new ArrayList<>(list(item.get("content")));
            for (var raw : list(data.get("content"))) {
                var block = map(raw);
                if ("text".equals(block.get("type"))) {
                    if (!content.isEmpty()
                            && "text".equals(map(content.get(content.size() - 1)).get("type"))) {
                        var tail = new LinkedHashMap<>(map(content.remove(content.size() - 1)));
                        tail.put(
                                "text",
                                String.valueOf(tail.getOrDefault("text", ""))
                                        + block.getOrDefault("text", ""));
                        content.add(tail);
                    } else content.add(new LinkedHashMap<>(block));
                } else if ("tool_use".equals(block.get("type"))) {
                    var call = new LinkedHashMap<>(block);
                    if (call.get("id") != null && !String.valueOf(call.get("id")).isBlank())
                        index("current_tools").put(id, call.get("id"));
                    else call.put("id", index("current_tools").get(id));
                    if (call.get("id") != null) tool(data, call, "generating_arguments", envelope);
                } else content.add(raw);
            }
            item.put("id", id);
            item.put("type", "message");
            item.put("role", "assistant");
            item.put("status", "in_progress");
            item.put("content", content);
            if (index("current_tools").get(id) != null)
                data.put("active_tool_call_id", index("current_tools").get(id));
            putItem(envelope, item);
        } else if (type.startsWith("tool.")) {
            var call = new LinkedHashMap<>(data);
            call.put("id", data.get("tool_call_id"));
            if (data.get("result") instanceof Map<?, ?>) call.putAll(map(data.get("result")));
            tool(
                    data,
                    call,
                    switch (type) {
                        case "tool.requested" -> "requested";
                        case "tool.dispatched", "tool.delta" -> "running";
                        default ->
                                String.valueOf(data.getOrDefault("status", "completed"))
                                        .toLowerCase();
                    },
                    envelope);
        } else if (type.startsWith("run.")) {
            index("runs").put(key(data, "run_id"), envelope);
            if (type.equals("run.ended")) settleRun(envelope);
        } else if (Set.of(
                        "turn.accepted",
                        "turn.queued",
                        "turn.running",
                        "turn.completed",
                        "turn.failed",
                        "turn.cancelled",
                        "turn.interrupted",
                        "turn.requires_action",
                        "turn.cancel_requested")
                .contains(type)) {
            index("turns").put(key(data, "turn_id"), envelope);
        } else if (type.startsWith("required_action.")) {
            String id = key(data, "request_id");
            if (type.equals("required_action.created")) {
                index("action_history").put(id, envelope);
                index("required_actions").put(id, envelope);
                if (data.get("tool_call") instanceof Map<?, ?>)
                    tool(data, map(data.get("tool_call")), "suspended", envelope);
            } else if (type.equals("required_action.accepted")
                    || type.equals("required_action.resolved")) {
                index("required_actions").remove(id);
                if (type.equals("required_action.resolved"))
                    index("action_history").put(id, envelope);
            } else if (type.equals("required_action.rejected")) {
                var prior = map(index("action_history").get(id));
                if ("required_action.resolved".equals(prior.get("type"))
                        || Boolean.FALSE.equals(data.get("pending"))) {
                    if (data.get("command_id") != null)
                        index("action_commands").put(key(data, "command_id"), envelope);
                    return;
                }
                var restored = new LinkedHashMap<>(envelope);
                var details = new LinkedHashMap<>(map(prior.get("data")));
                details.putAll(data);
                restored.put("data", details);
                index("required_actions").put(id, restored);
            }
            if (data.get("command_id") != null)
                index("action_commands").put(key(data, "command_id"), envelope);
        } else if (type.startsWith("input.")) {
            var ids = new ArrayList<>(list(data.get("input_ids")));
            if (data.get("input_id") != null) ids.add(data.get("input_id"));
            for (Object id : ids) {
                var prior = map(index("inputs").get(String.valueOf(id)));
                // Inbox exports can arrive after execution exports. Applied/rejected is monotonic.
                if (type.equals("input.accepted")
                        && Set.of("input.applied", "input.rejected")
                                .contains(String.valueOf(prior.get("type")))) continue;
                var input = new LinkedHashMap<>(envelope);
                var details = new LinkedHashMap<>(map(prior.get("data")));
                details.putAll(data);
                details.put("input_id", id);
                input.put("data", details);
                index("inputs").put(String.valueOf(id), input);
            }
        } else if (type.equals("artifact.published"))
            index("artifacts").put(key(data, "artifact_id"), envelope);
        else if (type.equals("artifact.deleted"))
            index("artifacts").remove(key(data, "artifact_id"));
        else if (type.equals("usage.recorded") || type.equals("model.completed")) {
            settleModel(envelope);
            index("model_usage")
                    .put(
                            String.valueOf(
                                    data.getOrDefault(
                                            "model_call_id",
                                            data.getOrDefault("attempt_id", event.id()))),
                            data);
        } else if (type.startsWith("subagent."))
            index("subagents").put(key(data, "childSessionId"), envelope);
    }

    private void putItem(Map<String, Object> envelope, Map<String, Object> item) {
        var result = new LinkedHashMap<>(envelope);
        var details = new LinkedHashMap<>(map(envelope.get("data")));
        details.remove("content");
        details.put("item", item);
        details.put("item_id", item.get("id"));
        result.put("type", "item.updated");
        result.put("data", details);
        index("items").put(String.valueOf(item.get("id")), result);
    }

    private void tool(
            Map<String, Object> identity,
            Map<String, Object> call,
            String status,
            Map<String, Object> envelope) {
        if (call.get("id") == null) return;
        String id = identity.get("turn_id") + ":" + call.get("id");
        var previous = map(index("tools").get(id));
        var details = new LinkedHashMap<>(map(previous.get("data")));
        for (String field : List.of("turn_id", "run_id", "item_id", "model_call_id"))
            if (identity.get(field) != null) details.put(field, identity.get(field));
        details.put("tool_call_id", call.get("id"));
        if (call.get("name") != null
                && !String.valueOf(call.get("name")).isBlank()
                && !String.valueOf(call.get("name")).startsWith("__"))
            details.put("name", call.get("name"));
        if (call.get("input") instanceof Map<?, ?> input && !input.isEmpty()) {
            var merged = new LinkedHashMap<>(map(details.get("input")));
            input.forEach((key, value) -> merged.put(String.valueOf(key), value));
            details.put("input", merged);
        }
        if (call.get("content") instanceof String fragment)
            details.put(
                    "arguments",
                    "item.delta".equals(envelope.get("type"))
                            ? details.getOrDefault("arguments", "") + fragment
                            : fragment);
        if (call.get("output") != null) {
            if ("tool.delta".equals(envelope.get("type"))) {
                var progress = new ArrayList<>(list(details.get("progress")));
                progress.addAll(list(call.get("output")));
                details.put("progress", progress);
            } else details.put("output", call.get("output"));
        }
        String next =
                ("tool_result".equals(call.get("type"))
                                        || "tool.completed".equals(envelope.get("type")))
                                && call.get("state") != null
                        ? String.valueOf(call.get("state")).toLowerCase()
                        : status;
        if (!(Set.of("completed", "success", "error", "denied", "interrupted", "failed")
                        .contains(String.valueOf(details.get("status")))
                && Set.of("requested", "generating_arguments").contains(next)))
            details.put("status", next);
        var result = new LinkedHashMap<>(envelope);
        result.put("type", "tool.updated");
        result.put("data", details);
        index("tools").put(id, result);
    }

    private void settleModel(Map<String, Object> envelope) {
        var end = map(envelope.get("data"));
        if (!Set.of("failed", "cancelled", "interrupted")
                        .contains(String.valueOf(end.get("status")))
                || end.get("item_id") == null) return;
        String id = key(end, "item_id");
        var previous = map(index("items").get(id));
        var item = new LinkedHashMap<>(map(map(previous.get("data")).get("item")));
        if ("in_progress".equals(item.get("status"))) {
            item.put("status", "incomplete");
            var update = new LinkedHashMap<>(envelope);
            update.put("data", previous.get("data"));
            putItem(update, item);
        }
        for (var raw : index("tools").values()) {
            var data = map(map(raw).get("data"));
            if (id.equals(data.get("item_id")) && "generating_arguments".equals(data.get("status")))
                data.put("status", "unknown");
        }
    }

    private void settleRun(Map<String, Object> envelope) {
        var end = map(envelope.get("data"));
        for (var raw : new ArrayList<>(index("items").values())) {
            var entry = map(raw);
            var data = map(entry.get("data"));
            var item = new LinkedHashMap<>(map(data.get("item")));
            if (Objects.equals(end.get("run_id"), data.get("run_id"))
                    && "in_progress".equals(item.get("status"))) {
                item.put(
                        "status",
                        "completed".equals(end.get("status")) ? "completed" : "incomplete");
                var update = new LinkedHashMap<>(envelope);
                update.put("data", data);
                putItem(update, item);
            }
        }
        for (var raw : index("tools").values()) {
            var data = map(map(raw).get("data"));
            if (Objects.equals(end.get("run_id"), data.get("run_id"))
                    && Set.of("running", "requested", "generating_arguments")
                            .contains(String.valueOf(data.get("status"))))
                data.put("status", "unknown");
        }
    }

    public Map<String, Object> resources(String session) {
        var result = new LinkedHashMap<String, Object>();
        result.put("as_of", SessionEventCursor.encode(session, sequence()));
        for (String name :
                List.of(
                        "items",
                        "tools",
                        "turns",
                        "runs",
                        "required_actions",
                        "action_commands",
                        "inputs",
                        "artifacts",
                        "subagents")) result.put(name, List.copyOf(index(name).values()));
        var totals = new LinkedHashMap<String, Number>();
        for (var raw : index("model_usage").values())
            map(map(raw).get("usage"))
                    .forEach(
                            (key, value) -> {
                                if (value instanceof Number n) {
                                    if (key.equals("time"))
                                        totals.merge(
                                                key,
                                                n.doubleValue(),
                                                (a, b) -> a.doubleValue() + b.doubleValue());
                                    else
                                        totals.merge(
                                                key,
                                                n.longValue(),
                                                (a, b) -> a.longValue() + b.longValue());
                                }
                            });
        result.put(
                "usage",
                Map.of(
                        "scope",
                        "session_only",
                        "model_calls",
                        index("model_usage").size(),
                        "totals",
                        totals,
                        "models",
                        List.copyOf(index("model_usage").values())));
        return result;
    }
}
