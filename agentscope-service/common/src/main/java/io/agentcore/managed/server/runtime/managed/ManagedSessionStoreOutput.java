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
package io.agentcore.managed.server.runtime.managed;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Authoritative final output is readable before the asynchronous history catches up. */
public final class ManagedSessionStoreOutput {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ManagedSessionStoreOutput(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void capture(ManagedScope scope, String type, Map<String, Object> data, long now) {
        if (!List.of(
                        "item.completed",
                        "item.finalized",
                        "turn.completed",
                        "turn.failed",
                        "turn.cancelled")
                .contains(type)) return;
        String turn = (String) data.get("turn_id");
        if (turn == null) return;
        jdbc.update(
                "INSERT INTO ma.session_store_output(scope_key,turn_id) VALUES(?,?) ON CONFLICT DO"
                        + " NOTHING",
                scope.messageGroup(),
                turn);
        if (List.of("item.completed", "item.finalized").contains(type)
                && data.get("item") instanceof Map<?, ?> item
                && "assistant".equalsIgnoreCase(String.valueOf(item.get("role")))) {
            List<Object> content = new ArrayList<>();
            if (item.get("content") instanceof List<?> blocks)
                for (Object raw : blocks)
                    if (raw instanceof Map<?, ?> block && "text".equals(block.get("type")))
                        content.add(block);
            if (content.isEmpty()) return;
            String nativeKey = "message:" + turn + ":" + item.get("message_id");
            String id = "evt_" + ManagedScope.fingerprint(scope.messageGroup(), nativeKey);
            var event = new LinkedHashMap<String, Object>();
            event.put("id", id);
            event.put("type", "agent.message");
            event.put("processed_at", Instant.ofEpochMilli(now).toString());
            event.put("content", content);
            var extension = new LinkedHashMap<String, Object>();
            extension.put("schema_version", 1);
            extension.put("session_id", scope.sessionId());
            extension.put("turn_id", turn);
            if (data.get("item_id") != null) extension.put("item_id", data.get("item_id"));
            event.put(ManagedSessionProtocol.EXTENSION, extension);
            long ordinal =
                    jdbc.queryForObject(
                            "SELECT COALESCE(MAX(ordinal),0)+1 FROM ma.session_store_output_event"
                                    + " WHERE scope_key=? AND turn_id=?",
                            Long.class,
                            scope.messageGroup(),
                            turn);
            jdbc.update(
                    "INSERT INTO"
                        + " ma.session_store_output_event(scope_key,turn_id,event_id,ordinal,payload,finalized)"
                        + " VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                    scope.messageGroup(),
                    turn,
                    id,
                    ordinal,
                    json(event),
                    "item.finalized".equals(type));
            if ("item.finalized".equals(type))
                jdbc.update(
                        "UPDATE ma.session_store_output_event SET finalized=TRUE WHERE scope_key=?"
                                + " AND turn_id=? AND event_id=?",
                        scope.messageGroup(),
                        turn,
                        id);
        }
        if (List.of("turn.completed", "turn.failed", "turn.cancelled").contains(type))
            jdbc.update(
                    "UPDATE ma.session_store_output SET sealed=TRUE WHERE scope_key=? AND"
                            + " turn_id=?",
                    scope.messageGroup(),
                    turn);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> event(ManagedScope scope, String id) {
        var rows =
                jdbc.queryForList(
                        "SELECT payload FROM ma.session_store_output_event WHERE scope_key=? AND"
                                + " event_id=?",
                        String.class,
                        scope.messageGroup(),
                        id);
        if (rows.isEmpty()) return null;
        try {
            return mapper.readValue(rows.get(0), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid output", e);
        }
    }

    public boolean exists(ManagedScope scope, String turn) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.session_store_output WHERE scope_key=? AND"
                                + " turn_id=?",
                        Long.class,
                        scope.messageGroup(),
                        turn)
                > 0;
    }

    public List<Object> ids(ManagedScope scope, String turn) {
        return new ArrayList<>(
                jdbc.queryForList(
                        "SELECT e.event_id FROM ma.session_store_output_event e JOIN"
                                + " ma.session_store_output o ON o.scope_key=e.scope_key AND"
                                + " o.turn_id=e.turn_id WHERE e.scope_key=? AND e.turn_id=? AND"
                                + " e.finalized=TRUE AND o.sealed=TRUE ORDER BY e.ordinal",
                        String.class,
                        scope.messageGroup(),
                        turn));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid output", e);
        }
    }
}
