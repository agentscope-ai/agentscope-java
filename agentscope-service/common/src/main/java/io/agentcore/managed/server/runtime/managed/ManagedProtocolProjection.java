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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore.IndexEntry;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore.Progress;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore.Resource;
import io.micrometer.core.instrument.Metrics;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Pure public protocol projection. Command links and catalog descriptors come from committed facts. */
final class ManagedProtocolProjection implements AutoCloseable {
    private static final ThreadLocal<ManagedProtocolProjection> CURRENT = new ThreadLocal<>();
    private final SessionProjectionStore store;
    private final ObjectMapper mapper;
    private final Progress progress;
    private final ManagedSessionProjections.Fact fact;
    private final Map<String, Resource> overlay;
    private final List<Resource> resources;
    private final List<IndexEntry> indexes;
    private int ordinal;

    ManagedProtocolProjection(
            SessionProjectionStore store,
            ObjectMapper mapper,
            Progress progress,
            ManagedSessionProjections.Fact fact,
            Map<String, Resource> overlay,
            List<Resource> resources,
            List<IndexEntry> indexes) {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested protocol projection");
        this.store = store;
        this.mapper = mapper;
        this.progress = progress;
        this.fact = fact;
        this.overlay = overlay;
        this.resources = resources;
        this.indexes = indexes;
        CURRENT.set(this);
    }

    long createdAt() {
        return fact.createdAt();
    }

    static ManagedProtocolProjection current() {
        return CURRENT.get();
    }

    @Override
    public void close() {
        CURRENT.remove();
    }

    void apply(JdbcTemplate jdbc) {
        var data = parse(fact.payload());
        if (fact.type().equals("protocol.catalog")) {
            value("protocol_catalog", String.valueOf(data.get("turn_id")), data);
            return;
        }
        if (fact.type().equals("protocol.event")) {
            put(String.valueOf(data.get("id")), data, null);
            return;
        }
        if (Set.of("turn.completed", "turn.failed", "turn.cancelled").contains(fact.type()))
            seal(String.valueOf(data.get("turn_id")));
        ManagedSessionProtocol.project(
                jdbc,
                mapper,
                progress.build().scope(),
                fact.source(),
                fact.type(),
                data,
                fact.createdAt());
    }

    Map<String, Object> value(String kind, String id) {
        String key = kind + "/" + id;
        if (!overlay.containsKey(key)) {
            Metrics.counter("managed.agents.projection.resource.read", "source", "backend")
                    .increment();
            overlay.put(key, store.resource(progress, kind, id));
        } else
            Metrics.counter("managed.agents.projection.resource.read", "source", "local")
                    .increment();
        var r = overlay.get(key);
        return r == null || r.deleted() ? new LinkedHashMap<>() : parse(r.payload());
    }

    void value(String kind, String id, Map<String, Object> value) {
        String key = kind + "/" + id;
        if (!overlay.containsKey(key)) {
            Metrics.counter("managed.agents.projection.resource.read", "source", "backend")
                    .increment();
            overlay.put(key, store.resource(progress, kind, id));
        } else
            Metrics.counter("managed.agents.projection.resource.read", "source", "local")
                    .increment();
        var old = overlay.get(key);
        var r =
                new Resource(
                        kind,
                        id,
                        fact.sequence(),
                        old == null ? fact.sequence() : old.createdSequence(),
                        false,
                        json(value));
        overlay.put(key, r);
        resources.removeIf(
                previous ->
                        previous.kind().equals(kind)
                                && previous.id().equals(id)
                                && previous.sequence() == fact.sequence());
        resources.add(r);
    }

    Map<String, Object> latest(String id) {
        var value = value("protocol_event", id);
        return value.isEmpty() ? null : map(value.get("event"));
    }

    List<String> prior(String nativeKey) {
        var id = value("protocol_native", ManagedScope.fingerprint(nativeKey)).get("id");
        return id == null ? List.of() : List.of(String.valueOf(id));
    }

    void put(String id, Map<String, Object> event, String nativeKey) {
        if (++ordinal >= 256)
            throw new IllegalArgumentException("Protocol event fanout exceeds 255");
        long cursor = Math.addExact(Math.multiplyExact(fact.sequence(), 256), ordinal);
        String position = position(cursor);
        long time =
                event.get("processed_at") == null
                        ? 0
                        : Math.addExact(
                                Instant.parse(String.valueOf(event.get("processed_at")))
                                        .toEpochMilli(),
                                1);
        String history = position(time) + ":" + position;
        value("protocol_event", id, Map.of("event", event, "history_position", history));
        value("protocol_change", position, Map.of("event", event, "sequence", cursor));
        indexes.add(new IndexEntry("protocol_history", history, id, fact.sequence()));
        indexes.add(new IndexEntry("protocol_changes", position, position, fact.sequence()));
        if (nativeKey != null)
            value("protocol_native", ManagedScope.fingerprint(nativeKey), Map.of("id", id));
        indexCommandLinks(id, event);
        String type = String.valueOf(event.get("type"));
        if (type.startsWith("managed_agents.turn.")) {
            var ext = map(event.get("managed_agents"));
            String turn = String.valueOf(ext.get("turn_id"));
            var info = value("protocol_turn", turn);
            info.put("updated_at", event.get("processed_at"));
            if (!info.containsKey("started_at")
                    && Set.of("managed_agents.turn.preparing", "managed_agents.turn.running")
                            .contains(type)) info.put("started_at", event.get("processed_at"));
            if (Set.of(
                            "managed_agents.turn.completed",
                            "managed_agents.turn.failed",
                            "managed_agents.turn.cancelled")
                    .contains(type)) info.put("completed_at", event.get("processed_at"));
            if (ext.get("error") != null) info.put("error", ext.get("error"));
            value("protocol_turn", turn, info);
        }
    }

    private void indexCommandLinks(String id, Map<String, Object> event) {
        String type = String.valueOf(event.get("type"));
        var ext = map(event.get("managed_agents"));
        Object turn = ext.get("turn_id"), input = ext.get("input_id");
        if ("user.queue.convert_to_steer".equals(type)) {
            var source = value("protocol_turn_link", String.valueOf(turn));
            if (input != null && !source.isEmpty()) {
                var link =
                        Map.<String, Object>of(
                                "event",
                                source.get("event"),
                                "turn",
                                ext.get("target_turn_id"),
                                "intent",
                                "steer",
                                "input",
                                input);
                value("protocol_input_link", String.valueOf(input), link);
                value("protocol_message_link", "command_" + input + "_0", link);
            }
            return;
        }
        if (!Set.of("user.message", "user.interrupt").contains(type) || turn == null) return;
        var link = new LinkedHashMap<String, Object>();
        link.put("event", id);
        link.put("turn", turn);
        link.put("intent", ext.getOrDefault("input_type", ext.getOrDefault("intent", "new_turn")));
        if (input != null) {
            link.put("input", input);
            value("protocol_input_link", String.valueOf(input), link);
        } else value("protocol_turn_link", String.valueOf(turn), link);
        if ("user.message".equals(type))
            value(
                    "protocol_message_link",
                    "command_" + (input == null ? turn : input) + "_0",
                    link);
    }

    Map<String, Object> messageLink(String message) {
        return value("protocol_message_link", message);
    }

    List<Map<String, String>> inputLinks(String input) {
        var link = value("protocol_input_link", input);
        return link.isEmpty()
                ? List.of()
                : List.of(
                        Map.of(
                                "event",
                                String.valueOf(link.get("event")),
                                "turn",
                                String.valueOf(link.get("turn")),
                                "intent",
                                String.valueOf(link.get("intent"))));
    }

    List<String> catalogs(String turn) {
        return List.of(json(value("protocol_catalog", turn)));
    }

    Map<String, Object> output(String turn) {
        return value("protocol_output", turn);
    }

    void appendOutput(String turn, String id) {
        var output = output(turn);
        var ids = new ArrayList<Object>((List<?>) output.getOrDefault("ids", List.of()));
        if (ids.contains(id)) return;
        if (Boolean.TRUE.equals(output.get("sealed")))
            throw new ManagedStateException("output_conflict", "Output already sealed");
        ids.add(id);
        output.put("ids", ids);
        value("protocol_output", turn, output);
    }

    void seal(String turn) {
        var output = output(turn);
        output.put("sealed", true);
        value("protocol_output", turn, output);
    }

    String status() {
        return String.valueOf(value("protocol_state", "session").getOrDefault("status", "idle"));
    }

    void status(String status) {
        value("protocol_state", "session", Map.of("status", status));
    }

    static String position(long value) {
        return String.format(Locale.ROOT, "%019d", value);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String value) {
        try {
            return mapper.readValue(value, LinkedHashMap.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : new LinkedHashMap<>();
    }
}
