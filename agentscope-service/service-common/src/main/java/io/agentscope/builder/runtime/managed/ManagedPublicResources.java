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
package io.agentscope.builder.runtime.managed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.web.managed.AgentSessionView;
import io.agentscope.builder.web.managed.SessionEventDto;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Incremental Hands projection and pinned public pages; native recovery state is never exposed. */
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

    private record Key(String kind, String id) {}

    private record Prior(long from, String data, String turnId) {}

    private record Cursor(String scope, String snapshot, String kind, String after) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final LongSupplier clock;
    private final long lifetime;
    private final int maxSnapshots;

    public ManagedPublicResources(DataSource source, ObjectMapper mapper) {
        this(source, mapper, System::currentTimeMillis, Duration.ofMinutes(10), 32);
    }

    ManagedPublicResources(
            DataSource source,
            ObjectMapper mapper,
            LongSupplier clock,
            Duration lifetime,
            int maxSnapshots) {
        this.jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(10);
        this.mapper = mapper;
        this.clock = clock;
        this.lifetime = ManagedMessageBus.positiveMillis(lifetime);
        if (maxSnapshots < 1)
            throw new IllegalArgumentException("Snapshot capacity must be positive");
        this.maxSnapshots = maxSnapshots;
    }

    /** Called inside the journal transaction, while its Session write lock is held. */
    void committed(ManagedScope scope, SessionEventDto event) {
        var data = new LinkedHashMap<>(event.payload());
        data.putIfAbsent("source", Map.of("kind", "hands"));
        var keys = keys(event, data);
        var before = new LinkedHashMap<Key, Prior>();
        var state = new LinkedHashMap<String, Object>();
        for (var key : keys) load(scope, key, before, state);
        String item = text(data.getOrDefault("item_id", map(data.get("item")).get("id")));
        Object current = item == null ? null : map(state.get("current_tools")).get(item);
        if (current != null)
            load(scope, new Key("tools", data.get("turn_id") + ":" + current), before, state);
        var reducer = new AgentSessionView(state);
        reducer.applyIncremental(
                new SessionEventDto(
                        event.id(),
                        event.sessionId(),
                        event.seq(),
                        event.type(),
                        data,
                        null,
                        event.createdAt()));
        var after = reducer.state();
        var changed = new LinkedHashSet<>(before.keySet());
        after.forEach(
                (kind, value) -> {
                    if (value instanceof Map<?, ?> index)
                        for (Object id : index.keySet())
                            changed.add(new Key(kind, String.valueOf(id)));
                });
        for (var key : changed) {
            checkKey(key.id());
            var prior = before.get(key);
            Object next = map(after.get(key.kind())).get(key.id());
            if (Objects.equals(
                    prior == null ? null : prior.data(), next == null ? null : json(next)))
                continue;
            if (next instanceof Map<?, ?> raw && raw.get("schema_version") != null) {
                var envelope = new LinkedHashMap<>(map(next));
                envelope.put("cursor", eventCursor(scope, event.seq()));
                next = envelope;
            }
            String encoded = next == null ? null : json(next);
            if (Objects.equals(prior == null ? null : prior.data(), encoded)) continue;
            if (key.kind().equals("model_usage"))
                usage(
                        scope,
                        prior == null ? null : map(parse(prior.data())),
                        next == null ? null : map(next));
            if (prior != null) {
                Long expires =
                        jdbc.queryForObject(
                                "SELECT MAX(expires_at) FROM ma.snapshot WHERE scope_key=? AND"
                                        + " expires_at>? AND as_of>=? AND as_of<?",
                                Long.class,
                                scope.messageGroup(),
                                clock.getAsLong(),
                                prior.from(),
                                event.seq());
                if (expires != null)
                    jdbc.update(
                            "INSERT INTO"
                                + " ma.public_resource_version(scope_key,kind,resource_key,valid_from,valid_through,expires_at,data,turn_id)"
                                + " VALUES(?,?,?,?,?,?,?,?)",
                            scope.messageGroup(),
                            key.kind(),
                            key.id(),
                            prior.from(),
                            event.seq() - 1,
                            expires,
                            prior.data(),
                            prior.turnId());
                jdbc.update(
                        "DELETE FROM ma.public_resource WHERE scope_key=? AND kind=? AND"
                                + " resource_key=?",
                        scope.messageGroup(),
                        key.kind(),
                        key.id());
            }
            if (next != null)
                jdbc.update(
                        "INSERT INTO"
                            + " ma.public_resource(scope_key,kind,resource_key,valid_from,data,turn_id)"
                            + " VALUES(?,?,?,?,?,?)",
                        scope.messageGroup(),
                        key.kind(),
                        key.id(),
                        event.seq(),
                        encoded,
                        text(
                                map(next)
                                        .getOrDefault(
                                                "turn_id",
                                                map(map(next).get("data"))
                                                        .getOrDefault(
                                                                "turn_id",
                                                                event.payload().get("turn_id")))));
        }
    }

    public Snapshot snapshot(ManagedScope scope) {
        return snapshot(scope, null);
    }

    public Snapshot snapshot(ManagedScope scope, String turn) {
        return tx.execute(
                ignored -> {
                    var row = lock(scope);
                    String usage = (String) row.get("usage_summary");
                    if (turn != null) usage = turnUsage(scope, turn);
                    expire(scope);
                    long sequence = ((Number) row.get("public_sequence")).longValue();
                    var reusable =
                            jdbc.queryForList(
                                    "SELECT snapshot_id FROM ma.snapshot WHERE scope_key=? AND"
                                            + " as_of=? AND COALESCE(turn_id,'')=?"
                                            + " AND expires_at>? ORDER BY expires_at DESC LIMIT"
                                            + " 1",
                                    scope.messageGroup(),
                                    sequence,
                                    turn == null ? "" : turn,
                                    Math.addExact(clock.getAsLong(), lifetime / 2));
                    if (!reusable.isEmpty())
                        return requireSnapshot(scope, (String) reusable.get(0).get("snapshot_id"));
                    long count =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ma.snapshot WHERE scope_key=?",
                                    Long.class,
                                    scope.messageGroup());
                    if (count >= maxSnapshots)
                        throw new ManagedStateException(
                                "admission_limited", "Too many active Session snapshots");
                    String id = "snapshot_" + UUID.randomUUID().toString().replace("-", "");
                    long expires = Math.addExact(clock.getAsLong(), lifetime);
                    jdbc.update(
                            "INSERT INTO"
                                + " ma.snapshot(scope_key,snapshot_id,as_of,expires_at,usage,turn_id)"
                                + " VALUES(?,?,?,?,?,?)",
                            scope.messageGroup(),
                            id,
                            sequence,
                            expires,
                            usage,
                            turn);
                    return new Snapshot(id, sequence, expires, map(parse(usage)), turn);
                });
    }

    public Usage usage(ManagedScope scope, String turn) {
        return tx.execute(
                ignored -> {
                    var row = lock(scope);
                    String value =
                            turn == null
                                    ? (String) row.get("usage_summary")
                                    : turnUsage(scope, turn);
                    return new Usage(
                            ((Number) row.get("public_sequence")).longValue(), map(parse(value)));
                });
    }

    private String turnUsage(ManagedScope scope, String turn) {
        var rows =
                jdbc.queryForList(
                        "SELECT usage_summary FROM ma.turn WHERE scope_key=? AND turn_id=?",
                        scope.messageGroup(),
                        turn);
        if (rows.isEmpty())
            throw new ManagedStateException("turn_not_found", "Unknown Turn in Session");
        return (String) rows.get(0).get("usage_summary");
    }

    public Map<String, Object> usage(ManagedScope scope) {
        return map(
                parse(
                        jdbc.queryForObject(
                                "SELECT usage_summary FROM ma.session WHERE scope_key=?",
                                String.class,
                                scope.messageGroup())));
    }

    public Page page(ManagedScope scope, String snapshotId, String kind, String after, int limit) {
        if (!KINDS.contains(kind))
            throw new IllegalArgumentException("Unknown public resource kind");
        if (limit < 1 || limit > 256)
            throw new IllegalArgumentException("Resource limit must be 1..256");
        String key = cursor(scope, snapshotId, kind, after);
        return tx.execute(
                ignored -> {
                    lock(scope);
                    var snapshot = requireSnapshot(scope, snapshotId);
                    String filter = snapshot.turnId() == null ? "" : " AND turn_id=?";
                    String sql =
                            "SELECT resource_key,data FROM (SELECT resource_key,data FROM"
                                    + " ma.public_resource WHERE scope_key=? AND kind=? AND"
                                    + " valid_from<=?"
                                    + filter
                                    + " UNION ALL SELECT resource_key,data FROM"
                                    + " ma.public_resource_version WHERE scope_key=? AND kind=? AND"
                                    + " valid_from<=? AND valid_through>=?"
                                    + filter
                                    + ") resources WHERE resource_key>? ORDER BY resource_key LIMIT"
                                    + " ?";
                    var parameters = new ArrayList<Object>();
                    parameters.addAll(List.of(scope.messageGroup(), kind, snapshot.sequence()));
                    if (snapshot.turnId() != null) parameters.add(snapshot.turnId());
                    parameters.addAll(
                            List.of(
                                    scope.messageGroup(),
                                    kind,
                                    snapshot.sequence(),
                                    snapshot.sequence()));
                    if (snapshot.turnId() != null) parameters.add(snapshot.turnId());
                    parameters.add(key);
                    parameters.add(limit + 1);
                    var rows = jdbc.queryForList(sql, parameters.toArray());
                    boolean more = rows.size() > limit;
                    var selected = rows.subList(0, Math.min(limit, rows.size()));
                    var data = new ArrayList<Map<String, Object>>();
                    var runs = new LinkedHashMap<String, Map<String, Object>>();
                    for (var row : selected)
                        data.add(
                                settle(
                                        scope,
                                        snapshot,
                                        kind,
                                        map(parse((String) row.get("data"))),
                                        runs));
                    String next =
                            more
                                    ? encode(
                                            new Cursor(
                                                    scope.messageGroup(),
                                                    snapshotId,
                                                    kind,
                                                    (String)
                                                            selected.get(selected.size() - 1)
                                                                    .get("resource_key")))
                                    : null;
                    return new Page(snapshot, List.copyOf(data), next, more);
                });
    }

    /** One resource at the current cursor or a pinned snapshot, without reading a whole page. */
    public Map<String, Object> resource(
            ManagedScope scope, String snapshotId, String kind, String id) {
        if (!KINDS.contains(kind))
            throw new IllegalArgumentException("Unknown public resource kind");
        checkKey(id);
        return tx.execute(
                ignored -> {
                    lock(scope);
                    Snapshot snapshot =
                            snapshotId == null ? null : requireSnapshot(scope, snapshotId);
                    var parameters = new ArrayList<Object>(List.of(scope.messageGroup(), kind, id));
                    String sql =
                            "SELECT data FROM ma.public_resource WHERE scope_key=? AND kind=? AND"
                                    + " resource_key=?";
                    if (snapshot != null) {
                        sql += " AND valid_from<=?";
                        parameters.add(snapshot.sequence());
                        if (snapshot.turnId() != null) {
                            sql += " AND turn_id=?";
                            parameters.add(snapshot.turnId());
                        }
                        sql +=
                                " UNION ALL SELECT data FROM ma.public_resource_version WHERE"
                                    + " scope_key=? AND kind=? AND resource_key=? AND valid_from<=?"
                                    + " AND valid_through>=?";
                        parameters.addAll(
                                List.of(
                                        scope.messageGroup(),
                                        kind,
                                        id,
                                        snapshot.sequence(),
                                        snapshot.sequence()));
                        if (snapshot.turnId() != null) {
                            sql += " AND turn_id=?";
                            parameters.add(snapshot.turnId());
                        }
                    }
                    var rows = jdbc.queryForList(sql, parameters.toArray());
                    if (rows.isEmpty())
                        throw new ManagedStateException(
                                "resource_not_found", "Public resource not found");
                    if (rows.size() != 1)
                        throw new IllegalStateException("Public resource versions overlap");
                    return map(parse((String) rows.get(0).get("data")));
                });
    }

    /** Bounded cleanup also covers Sessions that never request another snapshot. */
    public void expireSnapshots(int limit) {
        if (limit < 1 || limit > 256)
            throw new IllegalArgumentException("Invalid snapshot cleanup limit");
        var scopes =
                jdbc.queryForList(
                        "SELECT tenant,namespace,session_id FROM ma.session WHERE scope_key IN"
                            + " (SELECT scope_key FROM ma.snapshot WHERE expires_at<=? UNION SELECT"
                            + " scope_key FROM ma.public_resource_version WHERE expires_at<=?)"
                            + " ORDER BY scope_key LIMIT ?",
                        clock.getAsLong(),
                        clock.getAsLong(),
                        limit);
        for (var row : scopes) {
            var scope =
                    new ManagedScope(
                            (String) row.get("tenant"),
                            (String) row.get("namespace"),
                            (String) row.get("session_id"));
            tx.executeWithoutResult(
                    ignored -> {
                        lock(scope);
                        expire(scope);
                    });
        }
    }

    private Map<String, Object> settle(
            ManagedScope scope,
            Snapshot snapshot,
            String kind,
            Map<String, Object> event,
            Map<String, Map<String, Object>> runs) {
        if (!Set.of("items", "tools").contains(kind)) return event;
        var data = map(event.get("data"));
        String run = text(data.get("run_id"));
        if (run == null) return event;
        var ended = runs.computeIfAbsent(run, id -> frozen(scope, snapshot.sequence(), "runs", id));
        if (!"run.ended".equals(ended.get("type"))) return event;
        if (kind.equals("items") && "in_progress".equals(map(data.get("item")).get("status"))) {
            var item = new LinkedHashMap<>(map(data.get("item")));
            item.put(
                    "status",
                    "completed".equals(map(ended.get("data")).get("status"))
                            ? "completed"
                            : "incomplete");
            data.put("item", item);
        } else if (kind.equals("tools")
                && Set.of("running", "requested", "generating_arguments")
                        .contains(String.valueOf(data.get("status"))))
            data.put("status", "unknown");
        return event;
    }

    private Map<String, Object> frozen(ManagedScope scope, long sequence, String kind, String key) {
        var rows =
                jdbc.queryForList(
                        "SELECT data FROM ma.public_resource WHERE scope_key=? AND kind=? AND"
                                + " resource_key=? AND valid_from<=? UNION ALL SELECT data FROM"
                                + " ma.public_resource_version WHERE scope_key=? AND kind=? AND"
                                + " resource_key=? AND valid_from<=? AND valid_through>=?",
                        scope.messageGroup(),
                        kind,
                        key,
                        sequence,
                        scope.messageGroup(),
                        kind,
                        key,
                        sequence,
                        sequence);
        if (rows.size() > 1)
            throw new IllegalStateException("Overlapping public resource versions");
        return rows.isEmpty() ? Map.of() : map(parse((String) rows.get(0).get("data")));
    }

    private Snapshot requireSnapshot(ManagedScope scope, String id) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM ma.snapshot WHERE scope_key=? AND snapshot_id=?",
                        scope.messageGroup(),
                        id);
        if (rows.isEmpty())
            throw new ManagedStateException("snapshot_not_found", "Unknown Session snapshot");
        var row = rows.get(0);
        long expires = ((Number) row.get("expires_at")).longValue();
        if (expires <= clock.getAsLong())
            throw new ManagedStateException(
                    "snapshot_expired", "Session snapshot expired; create another snapshot");
        return new Snapshot(
                id,
                ((Number) row.get("as_of")).longValue(),
                expires,
                map(parse((String) row.get("usage"))),
                (String) row.get("turn_id"));
    }

    private Map<String, Object> lock(ManagedScope scope) {
        var rows =
                jdbc.queryForList(
                        "SELECT public_sequence,usage_summary FROM ma.session WHERE scope_key=? FOR"
                                + " UPDATE",
                        scope.messageGroup());
        if (rows.isEmpty()) throw new ManagedStateException("session_not_found", "Unknown Session");
        return rows.get(0);
    }

    private void expire(ManagedScope scope) {
        jdbc.update(
                "DELETE FROM ma.snapshot WHERE scope_key=? AND expires_at<=?",
                scope.messageGroup(),
                clock.getAsLong());
        jdbc.update(
                "DELETE FROM ma.public_resource_version WHERE scope_key=? AND expires_at<=?",
                scope.messageGroup(),
                clock.getAsLong());
    }

    private void load(
            ManagedScope scope, Key key, Map<Key, Prior> before, Map<String, Object> state) {
        if (before.containsKey(key)) return;
        var rows =
                jdbc.queryForList(
                        "SELECT valid_from,data,turn_id FROM ma.public_resource WHERE scope_key=?"
                                + " AND kind=? AND resource_key=?",
                        scope.messageGroup(),
                        key.kind(),
                        key.id());
        before.put(key, null);
        if (rows.isEmpty()) return;
        var row = rows.get(0);
        String data = (String) row.get("data");
        before.put(
                key,
                new Prior(
                        ((Number) row.get("valid_from")).longValue(),
                        data,
                        (String) row.get("turn_id")));
        var index = map(state.computeIfAbsent(key.kind(), ignored -> new LinkedHashMap<>()));
        index.put(key.id(), parse(data));
    }

    private void usage(ManagedScope scope, Map<String, Object> before, Map<String, Object> after) {
        String current =
                jdbc.queryForObject(
                        "SELECT usage_summary FROM ma.session WHERE scope_key=?",
                        String.class,
                        scope.messageGroup());
        jdbc.update(
                "UPDATE ma.session SET usage_summary=? WHERE scope_key=?",
                aggregate(current, before, after, "session_only"),
                scope.messageGroup());
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
        String current = turnUsage(scope, turn);
        jdbc.update(
                "UPDATE ma.turn SET usage_summary=? WHERE scope_key=? AND turn_id=?",
                aggregate(current, before, after, "turn_only"),
                scope.messageGroup(),
                turn);
    }

    private String aggregate(
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

    private static Set<Key> keys(SessionEventDto event, Map<String, Object> data) {
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

    private String cursor(ManagedScope scope, String snapshot, String kind, String value) {
        if (value == null || value.isBlank()) return "";
        try {
            if (value.length() > 4096)
                throw new IllegalArgumentException("Invalid resource cursor");
            var cursor = mapper.readValue(Base64.getUrlDecoder().decode(value), Cursor.class);
            if (!scope.messageGroup().equals(cursor.scope())
                    || !snapshot.equals(cursor.snapshot())
                    || !kind.equals(cursor.kind()))
                throw new IllegalArgumentException(
                        "Resource cursor belongs to a different snapshot or query");
            checkKey(cursor.after());
            return cursor.after();
        } catch (IOException malformed) {
            throw new IllegalArgumentException("Invalid resource cursor", malformed);
        }
    }

    public static String eventCursor(ManagedScope scope, long sequence) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        (scope.messageGroup() + ":" + sequence).getBytes(StandardCharsets.UTF_8));
    }

    private String encode(Cursor cursor) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json(cursor).getBytes(StandardCharsets.UTF_8));
    }

    private static void checkKey(String key) {
        if (key == null || key.isBlank() || key.length() > 512)
            throw new IllegalArgumentException("Invalid public resource key");
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
