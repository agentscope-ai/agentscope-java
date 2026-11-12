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
package io.agentcore.managed.server.runtime.managed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.web.managed.AgentSessionView;
import io.agentcore.managed.server.web.managed.SessionEventDto;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Incremental Tool Worker projection and pinned public pages; native recovery state is never exposed. */
public final class ManagedPublicResources {
    public static final List<String> KINDS =
            List.of(
                    "items",
                    "tools",
                    "turns",
                    "runs",
                    "steps",
                    "required_actions",
                    "action_commands",
                    "inputs",
                    "artifacts",
                    "subagents");

    public record Snapshot(
            String id, long sequence, long expiresAt, Map<String, Object> usage, String turnId) {}

    public record Usage(long sequence, Map<String, Object> data) {}

    public record Page(
            Snapshot snapshot,
            List<Map<String, Object>> data,
            String nextCursor,
            boolean hasMore) {}

    record Key(String kind, String id) {}

    DataSource source() {
        return jdbc.getDataSource();
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private ManagedQueryViews queryViews;

    public void queryViews(ManagedQueryViews views) {
        this.queryViews = views;
    }

    public ManagedPublicResources(DataSource source, ObjectMapper mapper) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        this.mapper = mapper;
    }

    @SuppressWarnings("unchecked")
    void accountUsage(ManagedScope scope, SessionEventDto event) {
        if (!Set.of("usage.recorded", "model.completed").contains(event.type())) return;
        String call = text(event.payload().get("model_call_id"));
        if (call == null) return;
        var rows =
                jdbc.queryForList(
                        "SELECT data FROM ma.session_store_usage WHERE scope_key=? AND"
                                + " model_call_id=?",
                        String.class,
                        scope.messageGroup(),
                        call);
        Map<String, Object> prior = rows.isEmpty() ? null : map(parse(rows.get(0)));
        var state = new LinkedHashMap<String, Object>();
        if (prior != null) state.put("model_usage", Map.of(call, prior));
        var reducer = new AgentSessionView(state);
        var data = new LinkedHashMap<String, Object>(event.payload());
        data.putIfAbsent("source", Map.of("kind", "hands"));
        reducer.applyIncremental(
                new SessionEventDto(
                        event.id(),
                        event.sessionId(),
                        event.seq(),
                        event.type(),
                        data,
                        null,
                        event.createdAt()));
        Map<String, Object> next = map(map(reducer.state().get("model_usage")).get(call));
        if (next.isEmpty() || Objects.equals(prior, next)) return;
        usage(scope, prior, next);
        if (rows.isEmpty())
            jdbc.update(
                    "INSERT INTO ma.session_store_usage(scope_key,model_call_id,data)"
                            + " VALUES(?,?,?)",
                    scope.messageGroup(),
                    call,
                    json(next));
        else
            jdbc.update(
                    "UPDATE ma.session_store_usage SET data=? WHERE scope_key=? AND"
                            + " model_call_id=?",
                    json(next),
                    scope.messageGroup(),
                    call);
    }

    private ManagedQueryViews query() {
        if (queryViews == null)
            throw new IllegalStateException("A separate projection query backend is required");
        return queryViews;
    }

    public Snapshot snapshot(ManagedScope scope) {
        return snapshot(scope, null);
    }

    public Snapshot snapshot(ManagedScope scope, String turn) {
        return query().snapshot(scope, turn);
    }

    public Usage usage(ManagedScope scope, String turn) {
        return query().usage(scope, turn);
    }

    public Map<String, Object> usage(ManagedScope scope) {
        return query().usage(scope, null).data();
    }

    public Page page(ManagedScope scope, String snapshot, String kind, String after, int limit) {
        return query().page(scope, snapshot, kind, after, limit);
    }

    public Map<String, Object> resource(
            ManagedScope scope, String snapshot, String kind, String id) {
        return query().resource(scope, snapshot, kind, id);
    }

    public void expireSnapshots(int limit) {
        /* Signed query cursors require no execution-table cleanup. */
    }

    private String turnUsage(ManagedScope scope, String turn) {
        return jdbc.queryForObject(
                "SELECT usage_summary FROM ma.turn WHERE scope_key=? AND turn_id=?",
                String.class,
                scope.messageGroup(),
                turn);
    }

    private void usage(ManagedScope scope, Map<String, Object> before, Map<String, Object> after) {
        var changes = ManagedExecutionChanges.current(jdbc.getDataSource(), scope);
        if (changes != null)
            changes.usage = aggregate(changes.usage, before, after, "session_only");
        else {
            String current =
                    jdbc.queryForObject(
                            "SELECT usage_summary FROM ma.session WHERE scope_key=?",
                            String.class,
                            scope.messageGroup());
            jdbc.update(
                    "UPDATE ma.session SET usage_summary=? WHERE scope_key=?",
                    aggregate(current, before, after, "session_only"),
                    scope.messageGroup());
        }
        String oldTurn = before == null ? null : text(before.get("turn_id"));
        String newTurn = after == null ? null : text(after.get("turn_id"));
        if (Objects.equals(oldTurn, newTurn)) {
            if (newTurn != null) turnUsage(scope, newTurn, before, after);
        } else {
            if (oldTurn != null) turnUsage(scope, oldTurn, before, null);
            if (newTurn != null) turnUsage(scope, newTurn, null, after);
        }
    }

    private void turnUsage(
            ManagedScope scope,
            String turn,
            Map<String, Object> before,
            Map<String, Object> after) {
        var changes = ManagedExecutionChanges.current(jdbc.getDataSource(), scope);
        if (changes != null && changes.turn.equals(turn)) {
            changes.turnChanges.set(
                    "usage_summary",
                    aggregate(
                            (String) changes.turnChanges.get("usage_summary"),
                            before,
                            after,
                            "turn_only"));
            return;
        }
        String current = turnUsage(scope, turn);
        jdbc.update(
                "UPDATE ma.turn SET usage_summary=? WHERE scope_key=? AND turn_id=?",
                aggregate(current, before, after, "turn_only"),
                scope.messageGroup(),
                turn);
    }

    String aggregate(
            String current, Map<String, Object> before, Map<String, Object> after, String label) {
        var summary = new LinkedHashMap<>(map(parse(current)));
        long calls =
                ((Number) summary.getOrDefault("model_calls", 0L)).longValue()
                        + (before == null ? 1 : 0)
                        - (after == null ? 1 : 0);
        var totals = new LinkedHashMap<>(map(summary.get("totals")));
        addUsage(totals, before == null ? Map.of() : map(before.get("usage")), -1);
        addUsage(totals, after == null ? Map.of() : map(after.get("usage")), 1);
        summary.put("scope", label);
        summary.put("model_calls", calls);
        summary.put("totals", totals);
        return json(summary);
    }

    private static void addUsage(Map<String, Object> totals, Map<String, Object> usage, int sign) {
        usage.forEach(
                (key, raw) -> {
                    if (!(raw instanceof Number number)) return;
                    Number prior = (Number) totals.getOrDefault(key, 0L);
                    if (key.equals("time"))
                        totals.put(key, prior.doubleValue() + sign * number.doubleValue());
                    else
                        totals.put(
                                key,
                                Math.addExact(
                                        prior.longValue(),
                                        Math.multiplyExact(sign, number.longValue())));
                });
    }

    static Set<Key> keys(SessionEventDto event, Map<String, Object> data) {
        var keys = new LinkedHashSet<Key>();
        for (var entry :
                Map.of(
                                "turns",
                                "turn_id",
                                "runs",
                                "run_id",
                                "steps",
                                "step_id",
                                "required_actions",
                                "request_id",
                                "action_history",
                                "request_id",
                                "action_commands",
                                "command_id",
                                "inputs",
                                "input_id",
                                "artifacts",
                                "artifact_id",
                                "subagents",
                                "childSessionId")
                        .entrySet()) add(keys, entry.getKey(), data.get(entry.getValue()));
        Object item = data.getOrDefault("item_id", map(data.get("item")).get("id"));
        add(keys, "items", item);
        add(keys, "current_tools", item);
        for (Object id : list(data.get("input_ids"))) add(keys, "inputs", id);
        if (Set.of("usage.recorded", "model.completed").contains(event.type()))
            add(
                    keys,
                    "model_usage",
                    data.getOrDefault(
                            "model_call_id", data.getOrDefault("attempt_id", event.id())));
        tool(keys, data.get("turn_id"), data.get("tool_call_id"));
        tool(keys, data.get("turn_id"), map(data.get("tool_call")).get("id"));
        var blocks = new ArrayList<Object>(list(data.get("content")));
        blocks.addAll(list(map(data.get("item")).get("content")));
        for (Object raw : list(data.get("items"))) {
            add(keys, "items", map(raw).get("id"));
            blocks.addAll(list(map(raw).get("content")));
        }
        for (Object raw : blocks)
            if (Set.of("tool_use", "tool_result").contains(String.valueOf(map(raw).get("type"))))
                tool(keys, data.get("turn_id"), map(raw).get("id"));
        return keys;
    }

    private static void add(Set<Key> keys, String kind, Object id) {
        if (id != null) keys.add(new Key(kind, String.valueOf(id)));
    }

    private static void tool(Set<Key> keys, Object turn, Object id) {
        if (id != null) add(keys, "tools", turn + ":" + id);
    }

    public static String eventCursor(ManagedScope scope, long sequence) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        (scope.messageGroup() + ":" + sequence).getBytes(StandardCharsets.UTF_8));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Cannot encode public resource", invalid);
        }
    }

    private Object parse(String value) {
        try {
            return mapper.readValue(value, Object.class);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Cannot decode public resource", invalid);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
