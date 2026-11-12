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
package io.agentcore.managed.server.agentservice.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceEventEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceResourceEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceResourceRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceResourceVersionEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceResourceVersionRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSnapshotRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceTurnRepository;
import io.agentcore.managed.server.runtime.protocol.RuntimePayloadDigest;
import io.agentcore.managed.server.web.managed.AgentSessionView;
import io.agentcore.managed.server.web.managed.SessionEventDto;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Reduces only resources touched by one event. The caller holds the Session write lock. */
@Service
public class AgentServiceResourceProjection {
    public static final List<String> PUBLIC_KINDS =
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
                    "subagents",
                    "model_usage");
    private final AgentServiceResourceRepository resources;
    private final AgentServiceResourceVersionRepository versions;
    private final AgentServiceSnapshotRepository snapshots;
    private final ObjectMapper mapper;
    private final AgentServiceTurnRepository turns;

    public AgentServiceResourceProjection(
            AgentServiceResourceRepository resources,
            AgentServiceResourceVersionRepository versions,
            AgentServiceSnapshotRepository snapshots,
            ObjectMapper mapper,
            AgentServiceTurnRepository turns) {
        this.resources = resources;
        this.versions = versions;
        this.snapshots = snapshots;
        this.mapper = mapper;
        this.turns = turns;
    }

    public void apply(AgentServiceSessionEntity session, AgentServiceEventEntity event) {
        var data = read(event.data);
        data.putIfAbsent("source", Map.of("kind", "portal"));
        var keys = keys(event, data);
        var before = new LinkedHashMap<Key, AgentServiceResourceEntity>();
        var state = new LinkedHashMap<String, Object>();
        var ids = new LinkedHashMap<String, Key>();
        for (var key : keys) {
            before.put(key, null);
            ids.put(resourceId(session.id, key.kind, key.id), key);
        }
        for (var row : resources.findAllById(ids.keySet())) {
            var key = ids.get(row.id);
            before.put(key, row);
            mapIndex(state, key.kind).put(key.id, decode(row.data));
        }
        String item = string(data.getOrDefault("item_id", map(data.get("item")).get("id")));
        if (item != null) {
            Object current = map(state.get("current_tools")).get(item);
            if (current != null)
                load(session.id, new Key("tools", event.turnId + ":" + current), before, state);
        }
        var view = new AgentSessionView(state);
        view.applyIncremental(
                new SessionEventDto(
                        event.id,
                        event.sessionId,
                        event.seq,
                        event.type,
                        data,
                        null,
                        event.createdAt));
        var after = view.state();
        var changed = new LinkedHashSet<Key>(before.keySet());
        for (var entry : after.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> index)
                for (var id : index.keySet())
                    changed.add(new Key(entry.getKey(), String.valueOf(id)));
        }
        for (var key : changed) {
            var prior = before.get(key);
            Object next = map(after.get(key.kind)).get(key.id);
            String encoded = next == null ? null : json(next);
            if (Objects.equals(prior == null ? null : prior.data, encoded)) continue;
            if (key.kind.equals("model_usage"))
                updateUsage(
                        session,
                        event.turnId,
                        prior == null ? Map.of() : read(prior.data),
                        map(next),
                        prior == null,
                        next == null);
            retain(session.id, event.seq, prior);
            if (next == null) {
                if (prior != null) resources.delete(prior);
                continue;
            }
            var row = prior == null ? new AgentServiceResourceEntity() : prior;
            row.id = resourceId(session.id, key.kind, key.id);
            row.sessionId = session.id;
            row.turnId = prior == null ? event.turnId : prior.turnId;
            row.kind = key.kind;
            row.resourceKey = key.id;
            row.validFrom = event.seq;
            row.data = encoded;
            resources.save(row);
        }
        session.updatedAt = event.createdAt;
    }

    private void load(
            String session,
            Key key,
            Map<Key, AgentServiceResourceEntity> before,
            Map<String, Object> state) {
        if (before.containsKey(key)) return;
        var row = resources.findById(resourceId(session, key.kind, key.id)).orElse(null);
        before.put(key, row);
        if (row == null) return;
        mapIndex(state, key.kind).put(key.id, decode(row.data));
    }

    private void retain(String session, long through, AgentServiceResourceEntity prior) {
        if (prior == null) return;
        Long expires =
                snapshots.retention(session, System.currentTimeMillis(), prior.validFrom, through);
        if (expires == null) return;
        var row = new AgentServiceResourceVersionEntity();
        row.id =
                RuntimePayloadDigest.of(
                        mapper, Map.of("resource", prior.id, "from", prior.validFrom));
        row.sessionId = session;
        row.turnId = prior.turnId;
        row.kind = prior.kind;
        row.resourceKey = prior.resourceKey;
        row.validFrom = prior.validFrom;
        row.validThrough = through;
        row.expiresAt = expires;
        row.data = prior.data;
        versions.save(row);
    }

    private void updateUsage(
            AgentServiceSessionEntity session,
            String turn,
            Map<String, Object> before,
            Map<String, Object> after,
            boolean created,
            boolean removed) {
        session.usageSummary =
                aggregateUsage(
                        session.usageSummary, "session_only", before, after, created, removed);
        if (turn != null)
            turns.findById(turn)
                    .filter(row -> row.sessionId.equals(session.id))
                    .ifPresent(
                            row -> {
                                row.usageSummary =
                                        aggregateUsage(
                                                row.usageSummary,
                                                "turn_only",
                                                before,
                                                after,
                                                created,
                                                removed);
                                turns.save(row);
                            });
    }

    private String aggregateUsage(
            String summary,
            String scope,
            Map<String, Object> before,
            Map<String, Object> after,
            boolean created,
            boolean removed) {
        var aggregate = read(summary);
        if (after.get("childSessionId") != null
                || String.valueOf(aggregate.get("scope")).endsWith("_subtree"))
            scope = scope.startsWith("turn") ? "turn_subtree" : "session_subtree";
        long calls = ((Number) aggregate.getOrDefault("model_calls", 0)).longValue();
        if (created) calls++;
        if (removed) calls--;
        var totals = new LinkedHashMap<>(map(aggregate.get("totals")));
        add(totals, map(before.get("usage")), -1);
        add(totals, map(after.get("usage")), 1);
        return json(Map.of("scope", scope, "model_calls", calls, "totals", totals));
    }

    private static void add(Map<String, Object> totals, Map<String, Object> usage, int sign) {
        usage.forEach(
                (key, value) -> {
                    if (!(value instanceof Number number)) return;
                    Number previous = (Number) totals.getOrDefault(key, 0L);
                    if (key.equals("time"))
                        totals.put(key, previous.doubleValue() + sign * number.doubleValue());
                    else
                        totals.put(
                                key,
                                Math.addExact(
                                        previous.longValue(),
                                        Math.multiplyExact(sign, number.longValue())));
                });
    }

    private Set<Key> keys(AgentServiceEventEntity event, Map<String, Object> data) {
        var keys = new LinkedHashSet<Key>();
        add(keys, "turns", data.get("turn_id"));
        add(keys, "runs", data.get("run_id"));
        add(keys, "steps", data.get("step_id"));
        String item = string(data.getOrDefault("item_id", map(data.get("item")).get("id")));
        add(keys, "items", item);
        add(keys, "current_tools", item);
        add(keys, "required_actions", data.get("request_id"));
        add(keys, "action_history", data.get("request_id"));
        add(keys, "action_commands", data.get("command_id"));
        add(keys, "inputs", data.get("input_id"));
        for (Object id : list(data.get("input_ids"))) add(keys, "inputs", id);
        add(keys, "artifacts", data.get("artifact_id"));
        add(keys, "subagents", data.get("childSessionId"));
        if (Set.of("usage.recorded", "model.completed").contains(event.type))
            add(
                    keys,
                    "model_usage",
                    data.getOrDefault("model_call_id", data.getOrDefault("attempt_id", event.id)));
        addTool(keys, event.turnId, data.get("tool_call_id"));
        addTool(keys, event.turnId, map(data.get("tool_call")).get("id"));
        for (Object raw : list(data.get("items"))) {
            var initialized = map(raw);
            add(keys, "items", initialized.get("id"));
            for (Object block : list(initialized.get("content")))
                toolBlock(keys, event.turnId, block);
        }
        for (Object block : list(data.get("content"))) toolBlock(keys, event.turnId, block);
        for (Object block : list(map(data.get("item")).get("content")))
            toolBlock(keys, event.turnId, block);
        return keys;
    }

    private static void toolBlock(Set<Key> keys, String turn, Object raw) {
        var block = map(raw);
        if ("tool_use".equals(block.get("type")) || "tool_result".equals(block.get("type")))
            addTool(keys, turn, block.get("id"));
    }

    private static void addTool(Set<Key> keys, String turn, Object id) {
        if (id != null) add(keys, "tools", turn + ":" + id);
    }

    private static void add(Set<Key> keys, String kind, Object id) {
        if (id != null) keys.add(new Key(kind, String.valueOf(id)));
    }

    String resourceId(String session, String kind, String key) {
        if (key.isBlank() || key.length() > 512)
            throw new IllegalArgumentException("Invalid public resource key");
        return RuntimePayloadDigest.of(
                mapper, Map.of("session", session, "kind", kind, "key", key));
    }

    Map<String, Object> read(String value) {
        try {
            return mapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot decode resource", error);
        }
    }

    private Object decode(String value) {
        try {
            return mapper.readValue(value, Object.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot decode resource value", error);
        }
    }

    String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode resource", error);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapIndex(Map<String, Object> state, String kind) {
        return (Map<String, Object>) state.computeIfAbsent(kind, ignored -> new LinkedHashMap<>());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record Key(String kind, String id) {}
}
