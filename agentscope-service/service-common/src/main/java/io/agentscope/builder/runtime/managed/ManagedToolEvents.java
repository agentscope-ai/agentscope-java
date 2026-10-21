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
package io.agentscope.builder.runtime.managed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.builder.runtime.managed.ManagedToolExecutor.OutputPage;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable capture cursor, immutable tool source journal and ordered publication/consumption prefix. */
public final class ManagedToolEvents {
    public static final int CHUNK_BYTES = 16 * 1024;
    public static final long OUTPUT_BYTES = 16L * 1024 * 1024;

    public record Cursor(
            String mode,
            long sourceSeq,
            long chunkSeq,
            long offset,
            boolean captureComplete,
            boolean hasGap,
            Long finalSeq,
            long consumedSeq) {}

    private final boolean hands;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    ManagedToolEvents(DataSource source, ObjectMapper mapper) {
        this(source, mapper, false);
    }

    ManagedToolEvents(DataSource source, ObjectMapper mapper, boolean hands) {
        this.hands = hands;
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(10);
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public Optional<Cursor> cursor(ManagedScope scope, String task) {
        return jdbc
                .query(
                        sql("SELECT * FROM ma.tool_stream WHERE scope_key=? AND task_id=?"),
                        (row, index) ->
                                new Cursor(
                                        row.getString("mode"), row.getLong("source_sequence"),
                                        row.getLong("chunk_sequence"), row.getLong("output_offset"),
                                        row.getBoolean("capture_complete"),
                                                row.getBoolean("has_gap"),
                                        row.getObject("final_sequence", Long.class),
                                                row.getLong("consumed_sequence")),
                        scope.messageGroup(),
                        task)
                .stream()
                .findFirst();
    }

    public void open(
            ManagedScope scope, ManagedJournalStore.ToolTask task, String token, boolean output) {
        tx.executeWithoutResult(
                ignored -> {
                    lock(scope, task.id());
                    fence(scope, task.id(), task.executionId(), token);
                    String mode = output ? "replayable" : "none";
                    var prior = cursor(scope, task.id());
                    if (prior.isPresent()) {
                        if (!mode.equals(prior.get().mode()))
                            throw conflict(
                                    "tool_output_mode_changed",
                                    "Original output capability differs");
                        return;
                    }
                    jdbc.update(
                            sql(
                                    "INSERT INTO"
                                        + " ma.tool_stream(scope_key,task_id,execution_id,mode,capture_complete)"
                                        + " VALUES(?,?,?,?,?)"),
                            scope.messageGroup(),
                            task.id(),
                            task.executionId(),
                            mode,
                            !output);
                });
    }

    /** Only the current collector can extend output. A replacement resumes from this durable offset. */
    public Cursor capture(
            ManagedScope scope, ManagedJournalStore.ToolTask task, String token, OutputPage page) {
        return tx.execute(
                ignored -> {
                    lock(scope, task.id());
                    fence(scope, task.id(), task.executionId(), token);
                    var current = cursor(scope, task.id()).orElseThrow();
                    if (!"replayable".equals(current.mode())
                            || current.finalSeq() != null
                            || current.captureComplete())
                        throw conflict("tool_output_closed", "Output capture is already closed");
                    if (page.offset() != current.offset())
                        throw conflict(
                                "tool_output_cursor",
                                "Output capture cursor changed; reload before reading");
                    long sequence = current.sourceSeq();
                    long chunk = current.chunkSeq();
                    boolean gap = current.hasGap();
                    if (sequence == 0) {
                        append(
                                scope,
                                task,
                                ++sequence,
                                "tool.progress",
                                Map.of("status", "running"));
                    }
                    byte[] content = Base64.getDecoder().decode(page.data());
                    if (content.length > 0 && page.nextOffset() > OUTPUT_BYTES)
                        throw new IllegalArgumentException(
                                "Captured output exceeds its byte limit");
                    if (page.gap() != null && !page.terminal())
                        throw new IllegalArgumentException(
                                "A source gap requires a terminal coverage observation");
                    if (content.length > 0) {
                        append(
                                scope,
                                task,
                                ++sequence,
                                "tool.delta",
                                Map.of(
                                        "chunk_seq",
                                        ++chunk,
                                        "from_offset",
                                        page.offset(),
                                        "to_offset",
                                        page.nextOffset(),
                                        "channel",
                                        "combined",
                                        "data",
                                        page.data()));
                    } else if (page.gap() != null) {
                        gap = true;
                        append(
                                scope,
                                task,
                                ++sequence,
                                "tool.output_gap",
                                Map.of(
                                        "from_offset",
                                        page.offset(),
                                        "to_offset",
                                        page.nextOffset(),
                                        "reason",
                                        page.gap()));
                    }
                    boolean complete = page.terminal() && page.nextOffset() == page.size();
                    jdbc.update(
                            sql(
                                    "UPDATE ma.tool_stream SET"
                                        + " source_sequence=?,chunk_sequence=?,output_offset=?,capture_complete=?,has_gap=?"
                                        + " WHERE scope_key=? AND task_id=?"),
                            sequence,
                            chunk,
                            page.nextOffset(),
                            complete,
                            gap,
                            scope.messageGroup(),
                            task.id());
                    return cursor(scope, task.id()).orElseThrow();
                });
    }

    /** An unknown external effect is observable without inventing a terminal tool result. */
    public void unknown(ManagedScope scope, ManagedJournalStore.ToolTask task, String token) {
        tx.executeWithoutResult(
                ignored -> {
                    lock(scope, task.id());
                    fence(scope, task.id(), task.executionId(), token);
                    var current = cursor(scope, task.id()).orElseThrow();
                    if (current.finalSeq() != null) return;
                    long sequence = current.sourceSeq() + 1;
                    append(scope, task, sequence, "tool.progress", Map.of("status", "unknown"));
                    jdbc.update(
                            sql(
                                    "UPDATE ma.tool_stream SET source_sequence=? WHERE scope_key=?"
                                            + " AND task_id=?"),
                            sequence,
                            scope.messageGroup(),
                            task.id());
                });
    }

    /** Raw results are already frozen separately; final publication waits for explicit output coverage. */
    public void finish(ManagedToolResult result) {
        tx.executeWithoutResult(
                ignored -> {
                    var scope = result.scope();
                    lock(scope, result.taskId());
                    var prior = cursor(scope, result.taskId());
                    if (prior.isEmpty()) {
                        jdbc.update(
                                sql(
                                        "INSERT INTO"
                                            + " ma.tool_stream(scope_key,task_id,execution_id,mode,capture_complete)"
                                            + " VALUES(?,?,?,'none',TRUE)"),
                                scope.messageGroup(),
                                result.taskId(),
                                result.executionId());
                    }
                    var current = cursor(scope, result.taskId()).orElseThrow();
                    if (current.finalSeq() != null) return;
                    if (!current.captureComplete())
                        throw conflict(
                                "tool_output_pending",
                                "Confirmed result still needs its output coverage");
                    long sequence = current.sourceSeq() + 1;
                    var event =
                            new ManagedToolEvent(
                                    scope,
                                    result.taskId(),
                                    result.executionId(),
                                    sequence,
                                    "tool.result",
                                    Map.of(
                                            "result",
                                            mapper.convertValue(result, Map.class),
                                            "last_chunk_seq",
                                            current.chunkSeq(),
                                            "output_offset",
                                            current.offset(),
                                            "log_status",
                                            current.hasGap()
                                                    ? "gapped"
                                                    : "none".equals(current.mode())
                                                            ? "not_supported"
                                                            : "complete"));
                    insert(event);
                    jdbc.update(
                            sql(
                                    "UPDATE ma.tool_stream SET source_sequence=?,final_sequence=?"
                                            + " WHERE scope_key=? AND task_id=?"),
                            sequence,
                            sequence,
                            scope.messageGroup(),
                            result.taskId());
                });
    }

    public boolean unfinished(ManagedScope scope, String task) {
        return cursor(scope, task).map(value -> value.finalSeq() == null).orElse(false);
    }

    public Optional<ManagedToolEvent> publication(ManagedScope scope, String task, long now) {
        return jdbc
                .query(
                        sql(
                                "SELECT e.payload FROM ma.tool_event e WHERE e.scope_key=? AND"
                                    + " e.task_id=? AND (e.applied_at IS NULL) AND"
                                    + " e.next_attempt_at<=? AND NOT EXISTS (SELECT 1 FROM"
                                    + " ma.tool_event p WHERE p.scope_key=e.scope_key AND"
                                    + " p.task_id=e.task_id AND p.source_sequence<e.source_sequence"
                                    + " AND p.broker_at IS NULL AND p.applied_at IS NULL) ORDER BY"
                                    + " CASE WHEN e.broker_at IS NULL THEN 0 ELSE 1"
                                    + " END,e.source_sequence LIMIT 1"),
                        (row, index) -> parse(row.getString(1)),
                        scope.messageGroup(),
                        task,
                        now)
                .stream()
                .findFirst();
    }

    public void attempt(ManagedToolEvent event, long next) {
        jdbc.update(
                sql(
                        "UPDATE ma.tool_event SET next_attempt_at=? WHERE scope_key=? AND task_id=?"
                                + " AND source_sequence=? AND applied_at IS NULL"),
                next,
                event.scope().messageGroup(),
                event.taskId(),
                event.sourceSeq());
    }

    public void published(ManagedToolEvent event) {
        jdbc.update(
                sql(
                        "UPDATE ma.tool_event SET broker_at=COALESCE(broker_at,?) WHERE scope_key=?"
                                + " AND task_id=? AND source_sequence=?"),
                System.currentTimeMillis(),
                event.scope().messageGroup(),
                event.taskId(),
                event.sourceSeq());
    }

    void retryFirst(ManagedScope scope, String task) {
        jdbc.update(
                sql(
                        "UPDATE ma.tool_event SET next_attempt_at=0 WHERE scope_key=? AND task_id=?"
                            + " AND source_sequence=(SELECT MIN(source_sequence) FROM ma.tool_event"
                            + " WHERE scope_key=? AND task_id=? AND applied_at IS NULL)"),
                scope.messageGroup(),
                task,
                scope.messageGroup(),
                task);
    }

    public List<ManagedJournalStore.ToolReference> pending(int limit, long now) {
        if (limit < 1 || limit > 256)
            throw new IllegalArgumentException("Invalid tool event publication limit");
        return jdbc.query(
                sql(
                        "SELECT s.tenant,s.namespace,s.session_id,e.task_id,MIN(e.next_attempt_at)"
                                + " AS next_attempt FROM ma.tool_event e JOIN ma.session s ON"
                                + " s.scope_key=e.scope_key WHERE (e.applied_at IS NULL) AND"
                                + " e.next_attempt_at<=? GROUP BY"
                                + " s.tenant,s.namespace,s.session_id,e.task_id ORDER BY"
                                + " next_attempt,e.task_id LIMIT ?"),
                (row, index) ->
                        new ManagedJournalStore.ToolReference(
                                new ManagedScope(
                                        row.getString(1), row.getString(2), row.getString(3)),
                                row.getString(4)),
                now,
                limit);
    }

    /** Future sources are durably buffered and ACKable; a gap must not block the MQ head. */
    void receive(ManagedToolEvent event, Consumer<ManagedToolEvent> apply) {
        try {
            tx.executeWithoutResult(
                    ignored -> {
                        lock(event.scope(), event.taskId());
                        var saved =
                                jdbc.queryForList(
                                        sql(
                                                "SELECT content_hash FROM ma.tool_event WHERE"
                                                        + " scope_key=? AND task_id=? AND"
                                                        + " source_sequence=?"),
                                        event.scope().messageGroup(),
                                        event.taskId(),
                                        event.sourceSeq());
                        String hash = hash(event);
                        if (saved.isEmpty()) {
                            var task =
                                    jdbc.queryForMap(
                                            sql(
                                                    "SELECT execution_id FROM ma.tool_task WHERE"
                                                            + " scope_key=? AND task_id=?"),
                                            event.scope().messageGroup(),
                                            event.taskId());
                            if (!event.executionId().equals(task.get("execution_id")))
                                throw conflict(
                                        "tool_execution_mismatch", "Tool source execution differs");
                            jdbc.update(
                                    sql(
                                            "INSERT INTO"
                                                + " ma.tool_stream(scope_key,task_id,execution_id,mode,capture_complete)"
                                                + " VALUES(?,?,?,'replayable',FALSE) ON CONFLICT DO"
                                                + " NOTHING"),
                                    event.scope().messageGroup(),
                                    event.taskId(),
                                    event.executionId());
                            insert(event);
                            saved = List.of(Map.of("content_hash", hash));
                        }
                        if (!hash.equals(saved.get(0).get("content_hash")))
                            throw conflict(
                                    "tool_event_conflict", "Immutable tool source content differs");
                        jdbc.update(
                                sql(
                                        "UPDATE ma.tool_event SET"
                                            + " received_at=COALESCE(received_at,?) WHERE"
                                            + " scope_key=? AND task_id=? AND source_sequence=?"),
                                System.currentTimeMillis(),
                                event.scope().messageGroup(),
                                event.taskId(),
                                event.sourceSeq());
                        var current = cursor(event.scope(), event.taskId()).orElseThrow();
                        long prefix = current.consumedSeq();
                        while (true) {
                            var rows =
                                    jdbc.queryForList(
                                            sql(
                                                    "SELECT payload,content_hash,received_at FROM"
                                                        + " ma.tool_event WHERE scope_key=? AND"
                                                        + " task_id=? AND source_sequence>? ORDER"
                                                        + " BY source_sequence LIMIT 64"),
                                            event.scope().messageGroup(),
                                            event.taskId(),
                                            prefix);
                            long before = prefix;
                            for (var row : rows) {
                                if (row.get("received_at") == null) break;
                                var next = parse((String) row.get("payload"));
                                if (!hash(next).equals(row.get("content_hash")))
                                    throw conflict(
                                            "tool_event_corrupt",
                                            "Buffered tool source content differs");
                                if (next.sourceSeq() != prefix + 1) break;
                                validateCoverage(next);
                                jdbc.update(
                                        sql(
                                                "UPDATE ma.tool_stream SET consumed_sequence=?"
                                                        + " WHERE scope_key=? AND task_id=?"),
                                        next.sourceSeq(),
                                        event.scope().messageGroup(),
                                        event.taskId());
                                apply.accept(next);
                                jdbc.update(
                                        sql(
                                                "UPDATE ma.tool_event SET applied_at=? WHERE"
                                                        + " scope_key=? AND task_id=? AND"
                                                        + " source_sequence=?"),
                                        System.currentTimeMillis(),
                                        event.scope().messageGroup(),
                                        event.taskId(),
                                        next.sourceSeq());
                                prefix = next.sourceSeq();
                            }
                            if (rows.size() < 64 || prefix == before) break;
                        }
                    });
        } catch (ManagedStateException failure) {
            if ("tool_event_conflict".equals(failure.code())) {
                jdbc.update(
                        "INSERT INTO"
                            + " ma.tool_event_conflict(scope_key,task_id,source_sequence,observed_hash,created_at)"
                            + " VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
                        event.scope().messageGroup(),
                        event.taskId(),
                        event.sourceSeq(),
                        hash(event),
                        System.currentTimeMillis());
            }
            throw failure;
        }
    }

    public boolean applied(ManagedToolEvent event) {
        return cursor(event.scope(), event.taskId())
                .map(value -> value.consumedSeq() >= event.sourceSeq())
                .orElse(false);
    }

    public List<ManagedToolEvent> read(ManagedScope scope, String task, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 256)
            throw new IllegalArgumentException("Invalid source read bounds");
        return jdbc.query(
                sql(
                        "SELECT payload FROM ma.tool_event WHERE scope_key=? AND task_id=? AND"
                                + " source_sequence>?"
                                + (hands ? "" : " AND applied_at IS NOT NULL")
                                + " ORDER BY source_sequence LIMIT ?"),
                (row, index) -> parse(row.getString(1)),
                scope.messageGroup(),
                task,
                after,
                limit);
    }

    private void validateCoverage(ManagedToolEvent event) {
        var row =
                jdbc.queryForMap(
                        sql(
                                "SELECT consumed_chunk,consumed_offset FROM ma.tool_stream WHERE"
                                        + " scope_key=? AND task_id=?"),
                        event.scope().messageGroup(),
                        event.taskId());
        long chunk = ((Number) row.get("consumed_chunk")).longValue();
        long offset = ((Number) row.get("consumed_offset")).longValue();
        var data = event.data();
        if ("tool.delta".equals(event.type()) || "tool.output_gap".equals(event.type())) {
            if (number(data, "from_offset") != offset || number(data, "to_offset") < offset)
                throw conflict("tool_output_gap", "Tool output coverage is discontinuous");
            if ("tool.delta".equals(event.type())) {
                if (number(data, "chunk_seq") != chunk + 1)
                    throw conflict("tool_chunk_gap", "Tool chunk sequence differs");
                chunk++;
            }
            offset = number(data, "to_offset");
            jdbc.update(
                    sql(
                            "UPDATE ma.tool_stream SET consumed_chunk=?,consumed_offset=? WHERE"
                                    + " scope_key=? AND task_id=?"),
                    chunk,
                    offset,
                    event.scope().messageGroup(),
                    event.taskId());
        } else if ("tool.result".equals(event.type())) {
            jdbc.update(
                    sql(
                            "UPDATE ma.tool_stream SET"
                                + " final_sequence=?,source_sequence=?,chunk_sequence=?,output_offset=?,capture_complete=TRUE,has_gap=?,mode=?"
                                + " WHERE scope_key=? AND task_id=?"),
                    event.sourceSeq(),
                    event.sourceSeq(),
                    chunk,
                    offset,
                    "gapped".equals(data.get("log_status")),
                    "not_supported".equals(data.get("log_status")) ? "none" : "replayable",
                    event.scope().messageGroup(),
                    event.taskId());
            if (number(data, "last_chunk_seq") != chunk || number(data, "output_offset") != offset)
                throw conflict(
                        "tool_final_watermark", "Final result does not cover the consumed output");
        }
    }

    private void append(
            ManagedScope scope,
            ManagedJournalStore.ToolTask task,
            long sequence,
            String type,
            Map<String, Object> data) {
        insert(new ManagedToolEvent(scope, task.id(), task.executionId(), sequence, type, data));
    }

    private void insert(ManagedToolEvent event) {
        jdbc.update(
                sql(
                        "INSERT INTO"
                            + " ma.tool_event(scope_key,task_id,execution_id,source_sequence,event_id,content_hash,payload)"
                            + " VALUES(?,?,?,?,?,?,?)"),
                event.scope().messageGroup(),
                event.taskId(),
                event.executionId(),
                event.sourceSeq(),
                event.eventId(),
                hash(event),
                json(event));
    }

    private void fence(ManagedScope scope, String task, String execution, String token) {
        var rows =
                jdbc.queryForList(
                        sql(
                                "SELECT worker_token,execution_id,result FROM ma.tool_task WHERE"
                                        + " scope_key=? AND task_id=?"),
                        scope.messageGroup(),
                        task);
        if (rows.size() != 1
                || !execution.equals(rows.get(0).get("execution_id"))
                || !token.equals(rows.get(0).get("worker_token"))
                || rows.get(0).get("result") != null)
            throw conflict("stale_hands", "Original tool collector ownership changed");
    }

    private String sql(String value) {
        if (!hands) return value;
        value = value.replace("applied_at IS NULL", "broker_at IS NULL");
        return value.replace("ma.tool_stream", "ma.hands_tool_stream")
                .replace("ma.tool_event", "ma.hands_tool_event")
                .replace("ma.tool_task", "ma.hands_execution")
                .replace(
                        "JOIN ma.session s ON s.scope_key=e.scope_key",
                        "JOIN ma.hands_execution s ON s.scope_key=e.scope_key AND"
                                + " s.task_id=e.task_id");
    }

    private void lock(ManagedScope scope, String task) {
        if (hands) {
            jdbc.queryForObject(
                    "SELECT task_id FROM ma.hands_execution WHERE scope_key=? AND task_id=? FOR"
                            + " UPDATE",
                    String.class,
                    scope.messageGroup(),
                    task);
        } else {
            jdbc.queryForObject(
                    "SELECT scope_key FROM ma.session WHERE scope_key=? FOR UPDATE",
                    String.class,
                    scope.messageGroup());
        }
    }

    public String hash(ManagedToolEvent event) {
        return ManagedScope.fingerprint(json(event));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Cannot encode tool source event", failure);
        }
    }

    private ManagedToolEvent parse(String value) {
        try {
            return mapper.readValue(value, ManagedToolEvent.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Cannot decode tool source event", failure);
        }
    }

    private static long number(Map<String, Object> data, String name) {
        if (!(data.get(name) instanceof Number value))
            throw new IllegalArgumentException("Missing tool source position: " + name);
        return value.longValue();
    }

    private static ManagedStateException conflict(String code, String message) {
        return new ManagedStateException(code, message);
    }
}
