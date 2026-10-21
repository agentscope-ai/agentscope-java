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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.session.SessionEvent;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Indexed metadata and immutable file membership; native recovery state stays private. */
public final class ManagedCheckpoints {
    private record Cursor(String kind, String scope, long position, long through) {}

    private record Entry(long sequence, Map<String, Object> metadata) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    ManagedCheckpoints(DataSource source, ObjectMapper mapper) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        this.mapper = mapper;
    }

    /** Called under Hands' Session row lock and transaction, after the native event is inserted. */
    void capture(ManagedScope scope, SessionEvent event) {
        var reference =
                mapper.convertValue(
                        event.data().get(ManagedCheckpointObjects.REFERENCE),
                        ManagedCheckpointObjects.Reference.class);
        String id = "checkpoint_" + ManagedScope.fingerprint(scope.messageGroup(), event.eventId());
        jdbc.update(
                "INSERT INTO"
                    + " ma.state_checkpoint(scope_key,native_sequence,checkpoint_id,state_hash,turn_id,run_id,created_at,public_sequence)"
                    + " SELECT ?,?,?,?,?,?,?,public_sequence FROM ma.session WHERE scope_key=?",
                scope.messageGroup(),
                event.seq(),
                id,
                reference.stateHash(),
                event.turnId(),
                event.executionRunId(),
                event.occurredAt(),
                scope.messageGroup());
        jdbc.update(
                "INSERT INTO ma.checkpoint_file(scope_key,native_sequence,file_id) SELECT"
                        + " scope_key,?,file_id FROM ma.file WHERE scope_key=?",
                event.seq(),
                scope.messageGroup());
    }

    public Map<String, Object> list(ManagedScope scope, String after, String through, int limit) {
        if (limit < 1 || limit > 100)
            throw new IllegalArgumentException("Checkpoint limit must be 1..100");
        long head = head(scope);
        long watermark =
                through == null ? head : decode(scope, through, "checkpoint_head").through();
        long position = 0;
        if (after != null) {
            var cursor = decode(scope, after, "checkpoint_page");
            if (through != null && watermark != cursor.through())
                throw new IllegalArgumentException("Checkpoint watermark differs");
            watermark = cursor.through();
            position = cursor.position();
        }
        if (position < 0 || watermark < position || watermark > head)
            throw new IllegalArgumentException("Checkpoint cursor exceeds native head");
        var rows =
                entries(
                        scope,
                        "c.native_sequence>? AND c.native_sequence<=? ORDER BY c.native_sequence"
                                + " LIMIT ?",
                        position,
                        watermark,
                        limit + 1);
        boolean more = rows.size() > limit;
        var selected = rows.subList(0, Math.min(limit, rows.size()));
        var result = new LinkedHashMap<String, Object>();
        result.put("data", selected.stream().map(Entry::metadata).toList());
        result.put("as_of", encode(scope, "checkpoint_head", watermark, watermark));
        result.put(
                "next_cursor",
                more
                        ? encode(
                                scope,
                                "checkpoint_page",
                                selected.get(selected.size() - 1).sequence(),
                                watermark)
                        : null);
        result.put("has_more", more);
        return result;
    }

    public Map<String, Object> metadata(ManagedScope scope, String id) {
        ManagedScope.requireText(id, "checkpoint ID");
        if (id.length() > 256) throw new IllegalArgumentException("Invalid checkpoint ID");
        head(scope);
        var values = entries(scope, "c.checkpoint_id=?", id);
        if (values.isEmpty())
            throw new ManagedStateException(
                    "checkpoint_not_found", "Checkpoint does not exist in this Session");
        return values.get(0).metadata();
    }

    public List<String> files(ManagedScope scope, String id) {
        metadata(scope, id);
        return jdbc.queryForList(
                "SELECT f.file_id FROM ma.checkpoint_file f JOIN ma.state_checkpoint c ON"
                        + " c.scope_key=f.scope_key AND c.native_sequence=f.native_sequence WHERE"
                        + " c.scope_key=? AND c.checkpoint_id=? ORDER BY f.file_id",
                String.class,
                scope.messageGroup(),
                id);
    }

    private List<Entry> entries(ManagedScope scope, String predicate, Object... args) {
        Object[] parameters = new Object[args.length + 1];
        parameters[0] = scope.messageGroup();
        System.arraycopy(args, 0, parameters, 1, args.length);
        return jdbc.query(
                "SELECT c.*,(SELECT COUNT(*) FROM ma.checkpoint_file f WHERE"
                        + " f.scope_key=c.scope_key AND f.native_sequence=c.native_sequence) AS"
                        + " file_count,EXISTS(SELECT 1 FROM ma.checkpoint_workspace w WHERE"
                        + " w.scope_key=c.scope_key AND w.native_sequence=c.native_sequence) AS"
                        + " workspace_available FROM ma.state_checkpoint c WHERE c.scope_key=? AND "
                        + predicate,
                (row, index) -> {
                    var value = new LinkedHashMap<String, Object>();
                    value.put("checkpoint_id", row.getString("checkpoint_id"));
                    value.put("created_at", row.getLong("created_at"));
                    if (row.getString("turn_id") != null)
                        value.put("turn_id", row.getString("turn_id"));
                    if (row.getString("run_id") != null)
                        value.put("run_id", row.getString("run_id"));
                    value.put("file_count", row.getLong("file_count"));
                    value.put("workspace_available", row.getBoolean("workspace_available"));
                    return new Entry(row.getLong("native_sequence"), value);
                },
                parameters);
    }

    private long head(ManagedScope scope) {
        var rows =
                jdbc.queryForList(
                        "SELECT native_sequence FROM ma.session WHERE scope_key=?",
                        Long.class,
                        scope.messageGroup());
        if (rows.isEmpty())
            throw new ManagedStateException("session_not_found", "Session does not exist");
        return rows.get(0);
    }

    private String encode(ManagedScope scope, String kind, long position, long through) {
        try {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            mapper.writeValueAsBytes(
                                    new Cursor(kind, scope.messageGroup(), position, through)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Cursor decode(ManagedScope scope, String value, String kind) {
        try {
            if (value.length() > 2048) throw new IllegalArgumentException();
            var cursor = mapper.readValue(Base64.getUrlDecoder().decode(value), Cursor.class);
            if (!kind.equals(cursor.kind())
                    || !scope.messageGroup().equals(cursor.scope())
                    || cursor.position() < 0
                    || cursor.through() < cursor.position()) throw new IllegalArgumentException();
            if ("checkpoint_head".equals(kind) && cursor.position() != cursor.through())
                throw new IllegalArgumentException();
            return cursor;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid checkpoint cursor", invalid);
        }
    }
}
