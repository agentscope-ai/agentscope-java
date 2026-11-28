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
package io.agentscope.durable.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableSession;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Pages a fixed prefix of the native event log in Durable commit order. */
final class ClaudeEventHistory {
    private static final int READ_BATCH = 128;
    private static final int MAX_READS = 8;

    enum Comparison {
        GT,
        GTE,
        LT,
        LTE
    }

    record Selection(boolean descending, Set<String> types, Map<Comparison, Instant> timeBounds) {
        Selection {
            types = Set.copyOf(types);
            timeBounds = Map.copyOf(timeBounds);
        }

        boolean matches(JsonNode event) {
            if (!types.isEmpty() && !types.contains(event.required("type").asText())) return false;
            if (timeBounds.isEmpty()) return true;
            if (!event.hasNonNull("processed_at")) return false;
            var processed = Instant.parse(event.required("processed_at").asText());
            for (var bound : timeBounds.entrySet()) {
                int compared = processed.compareTo(bound.getValue());
                boolean matches =
                        switch (bound.getKey()) {
                            case GT -> compared > 0;
                            case GTE -> compared >= 0;
                            case LT -> compared < 0;
                            case LTE -> compared <= 0;
                        };
                if (!matches) return false;
            }
            return true;
        }

        String scope(DurableSession session) {
            var times = new TreeMap<String, String>();
            timeBounds.forEach((key, value) -> times.put(key.name(), value.toString()));
            return DurableJson.hash(
                    List.of(session.key(), descending, types.stream().sorted().toList(), times));
        }
    }

    private record Position(String scope, String cursor, long through, int skip) {}

    private ClaudeEventHistory() {}

    static JsonNode list(DurableSession session, String page, int limit, Set<String> types) {
        return list(session, page, limit, new Selection(false, types, Map.of()));
    }

    static JsonNode list(DurableSession session, String page, int limit, Selection selection) {
        if (limit < 1 || limit > 1000)
            throw new DurableException(DurableException.Code.INVALID, "Limit must be 1..1000");
        boolean descending = selection.descending();
        String scope = selection.scope(session);
        var start = page == null ? null : decode(page, scope);
        long through = start == null ? -1 : start.through();
        String cursor = start == null ? null : start.cursor();
        int skip = start == null ? 0 : start.skip();
        var adapter = new ClaudeEventAdapter(session.view().primaryThreadId());
        var items = new ArrayList<JsonNode>(limit);

        for (int read = 0; read < MAX_READS; read++) {
            var nativePage =
                    descending
                            ? session.listEventsBefore(cursor, READ_BATCH)
                            : session.listEvents(cursor, READ_BATCH);
            if (through == -1) through = Long.parseLong(nativePage.highWatermarkSequence());
            for (var event : nativePage.events()) {
                long sequence = Long.parseLong(event.sequence());
                if (sequence > through) return page(items, null);
                var mapped = adapter.committed(event);
                if (descending && mapped.size() > 1) {
                    mapped = new ArrayList<>(mapped);
                    Collections.reverse(mapped);
                }
                if (skip > 0 && skip >= mapped.size()) throw invalidCursor();
                for (int index = skip; index < mapped.size(); index++) {
                    var output = mapped.get(index);
                    if (!selection.matches(output)) continue;
                    items.add(output);
                    if (items.size() == limit) {
                        Position next =
                                index + 1 < mapped.size()
                                        ? new Position(scope, cursor, through, index + 1)
                                        : (descending ? sequence > 1 : sequence < through)
                                                ? new Position(scope, event.cursor(), through, 0)
                                                : null;
                        return page(items, next);
                    }
                }
                skip = 0;
                cursor = event.cursor();
            }
            long scanned = Long.parseLong(nativePage.scannedSequence());
            if (nativePage.events().isEmpty() || (descending ? scanned <= 1 : scanned >= through))
                return page(items, null);
            cursor = nativePage.cursor();
        }
        return page(items, new Position(scope, cursor, through, 0));
    }

    private static JsonNode page(List<JsonNode> items, Position next) {
        var body = (ObjectNode) DurableJson.value(Map.of("data", items));
        if (next == null) body.putNull("next_page");
        else
            body.put(
                    "next_page",
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    DurableJson.value(next)
                                            .toString()
                                            .getBytes(StandardCharsets.UTF_8)));
        return body;
    }

    private static Position decode(String cursor, String scope) {
        try {
            if (cursor.length() > 2048) throw invalidCursor();
            var json =
                    DurableJson.parse(
                            new String(
                                    Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
            var position = DurableJson.convert(json, Position.class);
            if (!scope.equals(position.scope()) || position.through() < 1 || position.skip() < 0)
                throw invalidCursor();
            return position;
        } catch (RuntimeException invalid) {
            throw invalidCursor();
        }
    }

    private static DurableException invalidCursor() {
        return new DurableException(
                DurableException.Code.INVALID,
                "Claude page belongs to another query or is malformed");
    }
}
