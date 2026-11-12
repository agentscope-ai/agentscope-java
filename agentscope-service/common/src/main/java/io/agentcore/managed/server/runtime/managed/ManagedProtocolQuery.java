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
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore.Progress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Signed keyset history and reliable SSE read the same published query prefix. */
final class ManagedProtocolQuery {
    record Cursor(Progress progress, String binding, String position, long expiresAt) {}

    private final ManagedQueryViews views;
    private final SessionProjectionStore store;
    private final ObjectMapper mapper;

    ManagedProtocolQuery(
            ManagedQueryViews views, SessionProjectionStore store, ObjectMapper mapper) {
        this.views = views;
        this.store = store;
        this.mapper = mapper;
    }

    long head(ManagedScope scope) {
        long sequence = views.progress(scope).visibleSequence();
        return sequence == 0 ? 0 : Math.addExact(Math.multiplyExact(sequence, 256), 255);
    }

    String status(ManagedScope scope) {
        return String.valueOf(
                value(views.progress(scope), "protocol_state", "session")
                        .getOrDefault("status", "idle"));
    }

    Map<String, Object> latest(ManagedScope scope, String id) {
        var r = value(views.progress(scope), "protocol_event", id);
        return r.isEmpty() ? null : map(r.get("event"));
    }

    Map<String, Object> turnInfo(ManagedScope scope, String turn) {
        return value(views.progress(scope), "protocol_turn", turn);
    }

    List<Object> outputIds(ManagedScope scope, String turn) {
        var v = value(views.progress(scope), "protocol_output", turn);
        return Boolean.TRUE.equals(v.get("sealed"))
                ? new ArrayList<>((List<?>) v.getOrDefault("ids", List.of()))
                : List.of();
    }

    List<ManagedSessionProtocol.Change> changes(ManagedScope scope, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 256) throw invalid("Invalid SSE cursor");
        var p = views.progress(scope);
        var result = new ArrayList<ManagedSessionProtocol.Change>();
        for (var row :
                store.index(
                        p,
                        "protocol_changes",
                        ManagedProtocolProjection.position(after),
                        false,
                        limit)) {
            var value = value(p, "protocol_change", row.resourceId());
            if (value.isEmpty())
                throw new ManagedStateException("projection_gap", "Protocol change missing");
            result.add(
                    new ManagedSessionProtocol.Change(
                            ((Number) value.get("sequence")).longValue(), map(value.get("event"))));
        }
        return List.copyOf(result);
    }

    Map<String, Object> history(
            ManagedScope scope, String actor, Map<String, List<String>> query, boolean extended) {
        var allowed =
                Set.of(
                        "beta",
                        "page",
                        "limit",
                        "order",
                        "types[]",
                        "created_at[gt]",
                        "created_at[gte]",
                        "created_at[lt]",
                        "created_at[lte]");
        if (!allowed.containsAll(query.keySet())
                || query.containsKey("beta") && !List.of("true").equals(query.get("beta")))
            throw invalid("Invalid history query");
        int limit;
        try {
            limit = Integer.parseInt(first(query, "limit", "100"));
        } catch (Exception e) {
            throw invalid("Invalid limit");
        }
        if (limit < 1 || limit > 256) throw invalid("History limit must be 1..256");
        String order = first(query, "order", "asc");
        if (!Set.of("asc", "desc").contains(order)) throw invalid("Invalid history order");
        boolean descending = order.equals("desc");
        var filters = new LinkedHashMap<>(query);
        filters.remove("page");
        filters.remove("beta");
        filters.remove("limit");
        filters.put("order", List.of(order));
        String binding =
                ManagedScope.fingerprint(
                        scope.messageGroup(), actor, json(filters), String.valueOf(extended));
        var times = new LinkedHashMap<String, Instant>();
        for (String op : List.of("gt", "gte", "lt", "lte")) {
            String value = first(query, "created_at[" + op + "]", null);
            if (value != null)
                try {
                    times.put(op, Instant.parse(value));
                } catch (Exception e) {
                    throw invalid("Invalid history time");
                }
        }
        String page = first(query, "page", null);
        Cursor cursor;
        if (page == null)
            cursor =
                    new Cursor(
                            views.progress(scope),
                            binding,
                            null,
                            System.currentTimeMillis() + 600_000);
        else {
            try {
                cursor = views.verify(page, Cursor.class);
            } catch (Exception e) {
                throw invalid("Invalid signed history cursor");
            }
            if (!scope.equals(cursor.progress().build().scope())
                    || !binding.equals(cursor.binding())
                    || cursor.expiresAt() < System.currentTimeMillis())
                throw invalid("Expired or mismatched history cursor");
        }
        var data = new ArrayList<Map<String, Object>>();
        String position = cursor.position();
        boolean more = false;
        int scanned = 0;
        outer:
        while (scanned < 4096) {
            var rows =
                    store.index(cursor.progress(), "protocol_history", position, descending, 128);
            if (rows.isEmpty()) {
                more = false;
                break;
            }
            for (var row : rows) {
                var value = value(cursor.progress(), "protocol_event", row.resourceId());
                var event = map(value.get("event"));
                boolean matches =
                        row.position().equals(value.get("history_position"))
                                && matches(
                                        event,
                                        query.getOrDefault("types[]", List.of()),
                                        times,
                                        extended);
                if (matches && data.size() == limit) {
                    more = true;
                    break outer;
                }
                position = row.position();
                scanned++;
                if (matches) data.add(ManagedSessionProtocol.represent(event, extended));
            }
            more = rows.size() == 128;
            if (!more) break;
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("data", List.copyOf(data));
        result.put(
                "next_page",
                more
                        ? views.sign(
                                new Cursor(
                                        cursor.progress(), binding, position, cursor.expiresAt()))
                        : null);
        return result;
    }

    private boolean matches(
            Map<String, Object> event,
            List<String> types,
            Map<String, Instant> times,
            boolean extended) {
        String type = String.valueOf(event.get("type"));
        if (!extended && type.startsWith("managed_agents.")
                || !types.isEmpty() && !types.contains(type)) return false;
        for (var filter : times.entrySet()) {
            if (event.get("processed_at") == null) return false;
            int comparison =
                    Instant.parse(String.valueOf(event.get("processed_at")))
                            .compareTo(filter.getValue());
            if (switch (filter.getKey()) {
                case "gt" -> comparison <= 0;
                case "gte" -> comparison < 0;
                case "lt" -> comparison >= 0;
                default -> comparison > 0;
            }) return false;
        }
        return true;
    }

    Map<String, Object> value(Progress p, String kind, String id) {
        var r = store.resource(p, kind, id);
        if (r == null || r.deleted()) return new LinkedHashMap<>();
        try {
            return mapper.readValue(r.payload(), LinkedHashMap.class);
        } catch (Exception e) {
            throw new ManagedStateException(
                    "projection_content_invalid", "Invalid protocol resource");
        }
    }

    private String json(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String first(Map<String, List<String>> query, String key, String fallback) {
        var values = query.get(key);
        if (values == null) return fallback;
        if (values.size() != 1) throw invalid("Repeated query field");
        return values.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    private static ManagedStateException invalid(String message) {
        return new ManagedStateException("invalid_cursor", message);
    }
}
