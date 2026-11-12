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
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Mutable admission queue. Every mutation is serialized with activation by the Session row. */
public final class ManagedSessionQueue {
    public static final String MODE = "db_queue_v1";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ManagedAdmissionStore admission;
    private SessionServiceClient remote;
    private ObjectMapper mapper;

    ManagedSessionQueue(DataSource source, ManagedAdmissionStore admission) {
        this.jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(10);
        this.admission = admission;
    }

    ManagedSessionQueue(
            SessionServiceClient remote, ObjectMapper mapper, ManagedAdmissionStore admission) {
        this.jdbc = null;
        this.tx = null;
        this.remote = remote;
        this.mapper = mapper;
        this.admission = admission;
    }

    public boolean enabled(ManagedScope scope) {
        if (remote != null)
            return ManagedServiceCalls.coordinate(remote, "queue-enabled", Map.of("scope", scope))
                    .booleanValue();
        return MODE.equals(
                jdbc.queryForObject(
                        "SELECT scheduler_mode FROM ma.session WHERE scope_key=?",
                        String.class,
                        scope.messageGroup()));
    }

    /** Only the creation transaction may select this mode, before admitting any Turn. */
    public void enable(ManagedScope scope) {
        tx.executeWithoutResult(
                ignored -> {
                    lock(scope);
                    if (enabled(scope)) return;
                    long admitted =
                            jdbc.queryForObject(
                                    "SELECT turn_sequence FROM ma.session WHERE scope_key=?",
                                    Long.class,
                                    scope.messageGroup());
                    if (admitted != 0)
                        throw error(
                                "scheduler_mode_frozen",
                                "An existing queue cannot change scheduler mode");
                    jdbc.update(
                            "UPDATE ma.session SET scheduler_mode=? WHERE scope_key=?",
                            MODE,
                            scope.messageGroup());
                });
    }

    // Called only inside admission's transaction, holding the Session row.
    void enqueue(ManagedScope scope, String turn, long sequence) {
        if (!enabled(scope)) return;
        long now = System.currentTimeMillis();
        jdbc.update(
                "INSERT INTO"
                    + " ma.session_queue(scope_key,entry_id,turn_id,enqueue_sequence,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?)",
                scope.messageGroup(),
                "queue_" + turn,
                turn,
                sequence,
                now,
                now);
        // Stable logical identity for native/tool/checkpoint contracts, never a broker receipt.
        jdbc.update(
                "UPDATE ma.turn SET publication_sequence=?,delivery_status='db_queued' WHERE"
                        + " scope_key=? AND turn_id=?",
                sequence,
                scope.messageGroup(),
                turn);
        changed(jdbc, scope);
        signal(jdbc, scope);
    }

    void submission(ManagedScope scope, String turn, String event) {
        jdbc.update(
                "UPDATE ma.session_queue SET submission_id=? WHERE scope_key=? AND turn_id=?",
                event,
                scope.messageGroup(),
                turn);
    }

    /** Must join activation's transaction and Session lock; no SKIP LOCKED within one queue. */
    void claim(ManagedScope scope, String turn) {
        if (!enabled(scope)) return;
        var session = admission.session(scope);
        var rows =
                jdbc.queryForList(
                        "SELECT turn_id,state FROM ma.session_queue WHERE scope_key=? AND state IN"
                                + " ('queued','claimed') ORDER BY enqueue_sequence LIMIT 1",
                        scope.messageGroup());
        if (rows.isEmpty() || !turn.equals(rows.get(0).get("turn_id")))
            throw error("missing_predecessor", "A prior queue entry has not settled");
        if (session.currentTurnId() != null && !turn.equals(session.currentTurnId()))
            throw error("missing_predecessor", "A current Turn still owns this Session");
        if (jdbc.update(
                        "UPDATE ma.session_queue SET state='claimed',version=version+1,updated_at=?"
                                + " WHERE scope_key=? AND turn_id=? AND state='queued'",
                        System.currentTimeMillis(),
                        scope.messageGroup(),
                        turn)
                == 1) changed(jdbc, scope);
    }

    public String candidate(ManagedScope scope) {
        if (remote != null)
            return mapper.convertValue(
                    ManagedServiceCalls.coordinate(
                            remote, "queue-candidate", Map.of("scope", scope)),
                    String.class);
        var session = admission.session(scope);
        if (!"active".equals(session.lifecycle()) && session.currentTurnId() == null) return null;
        if (session.currentTurnId() != null) {
            var turn = admission.turn(scope, session.currentTurnId());
            return turn.resumeId() == null
                            && List.of("accepted", "preparing", "running").contains(turn.status())
                    ? turn.id()
                    : null;
        }
        var turns =
                jdbc.queryForList(
                        "SELECT turn_id FROM ma.session_queue WHERE scope_key=? AND state IN"
                                + " ('queued','claimed') ORDER BY enqueue_sequence LIMIT 1",
                        String.class,
                        scope.messageGroup());
        return turns.isEmpty() ? null : turns.get(0);
    }

    public record Mutation(
            String entryId, Long expectedVersion, String targetTurnId, boolean convert) {}

    Map<String, Object> mutate(
            ManagedScope scope, String actor, String request, String commandId, Mutation mutation) {
        if (!enabled(scope))
            throw error("unsupported_capability", "Session does not use session_queue_v1");
        var entry = entry(scope, mutation.entryId());
        if (!"queued".equals(entry.get("state")))
            throw error("queue_entry_not_queued", "The queue entry can no longer be changed");
        if (mutation.expectedVersion() == null
                || ((Number) entry.get("version")).longValue() != mutation.expectedVersion())
            throw error("queue_version_conflict", "Queue entry version differs");
        String turn = (String) entry.get("turn_id");
        String input = null;
        if (mutation.convert()) {
            var session = admission.session(scope);
            if (mutation.targetTurnId() == null
                    || !mutation.targetTurnId().equals(session.currentTurnId()))
                throw error("target_not_active", "Conversion requires the concrete current Turn");
            var target = admission.turn(scope, mutation.targetTurnId());
            if (!target.inputsOpen()
                    || !List.of("preparing", "running", "waiting_tool").contains(target.status()))
                throw error("input_closed", "The target Turn cannot accept steering");
            var source = admission.turn(scope, turn);
            input =
                    admission
                            .acceptInput(
                                    scope,
                                    mutation.targetTurnId(),
                                    source.actor(),
                                    request + ":conversion",
                                    ManagedInputInbox.Type.STEER,
                                    null,
                                    source.input())
                            .id();
        }
        String state = mutation.convert() ? "converted" : "withdrawn";
        long now = System.currentTimeMillis();
        jdbc.update(
                "UPDATE ma.session_queue SET"
                    + " state=?,version=version+1,converted_input_id=?,conversion_command_id=?,updated_at=?"
                    + " WHERE scope_key=? AND entry_id=?",
                state,
                input,
                commandId,
                now,
                scope.messageGroup(),
                mutation.entryId());
        jdbc.update(
                "UPDATE ma.turn SET status='cancelled',inputs_open=FALSE WHERE scope_key=? AND"
                        + " turn_id=? AND status='accepted'",
                scope.messageGroup(),
                turn);
        changed(jdbc, scope);
        long revision = revision(scope);
        jdbc.update(
                "INSERT INTO"
                    + " ma.session_command(scope_key,command_id,command_sequence,kind,actor,source_entry_id,target_turn_id,effect_input_id,status,created_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                scope.messageGroup(),
                commandId,
                revision,
                mutation.convert() ? "convert_to_steer" : "withdraw",
                actor,
                mutation.entryId(),
                mutation.targetTurnId(),
                input,
                "applied",
                now);
        signal(jdbc, scope);
        var result = new LinkedHashMap<String, Object>();
        result.put("command_id", commandId);
        result.put("queue_entry_id", mutation.entryId());
        result.put("turn_id", turn);
        result.put("queue_disposition", state);
        result.put("execution_started", false);
        result.put("queue_revision", revision);
        result.put("status", "applied");
        if (input != null) {
            result.put("input_id", input);
            result.put("target_turn_id", mutation.targetTurnId());
        }
        return result;
    }

    void recordInput(
            ManagedScope scope,
            String actor,
            String command,
            String kind,
            String target,
            String input) {
        if (!enabled(scope)) return;
        changed(jdbc, scope);
        jdbc.update(
                "INSERT INTO"
                    + " ma.session_command(scope_key,command_id,command_sequence,kind,actor,target_turn_id,effect_input_id,status,created_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?)",
                scope.messageGroup(),
                command,
                revision(scope),
                kind,
                actor,
                target,
                input,
                "accepted",
                System.currentTimeMillis());
    }

    public Map<String, Object> entry(ManagedScope scope, String id) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM ma.session_queue WHERE scope_key=? AND entry_id=?",
                        scope.messageGroup(),
                        id);
        if (rows.isEmpty())
            throw error("queue_entry_not_found", "Queue entry does not exist in this Session");
        return clean(rows.get(0));
    }

    public long revision(ManagedScope scope) {
        return jdbc.queryForObject(
                "SELECT queue_revision FROM ma.session WHERE scope_key=?",
                Long.class,
                scope.messageGroup());
    }

    public Map<String, Object> page(ManagedScope scope, boolean commands, String after, int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be 1..200");
        if (!enabled(scope))
            throw error("unsupported_capability", "Session does not use session_queue_v1");
        // A short repeatable snapshot via the same Session lock used by all queue writers.
        return tx.execute(
                ignored -> {
                    lock(scope);
                    String table = commands ? "session_command" : "session_queue";
                    String sequence = commands ? "command_sequence" : "enqueue_sequence";
                    long cursor = 0;
                    if (after != null) {
                        var ids =
                                jdbc.queryForList(
                                        "SELECT "
                                                + sequence
                                                + " FROM ma."
                                                + table
                                                + " WHERE scope_key=? AND "
                                                + (commands ? "command_id" : "entry_id")
                                                + "=?",
                                        Long.class,
                                        scope.messageGroup(),
                                        after);
                        if (ids.isEmpty())
                            throw error("invalid_cursor", "Cursor does not belong to this Session");
                        cursor = ids.get(0);
                    }
                    var rows =
                            jdbc.queryForList(
                                    (commands
                                            ? "SELECT c.*,i.status AS"
                                                  + " input_status,i.rejection_reason AS"
                                                  + " resolution_reason FROM ma.session_command c"
                                                  + " LEFT JOIN ma.input i ON"
                                                  + " i.scope_key=c.scope_key AND"
                                                  + " i.input_id=c.effect_input_id WHERE"
                                                  + " c.scope_key=? AND c.command_sequence>? ORDER"
                                                  + " BY c.command_sequence LIMIT ?"
                                            : "SELECT * FROM ma.session_queue WHERE scope_key=? AND"
                                                  + " enqueue_sequence>? ORDER BY enqueue_sequence"
                                                  + " LIMIT ?"),
                                    scope.messageGroup(),
                                    cursor,
                                    limit + 1);
                    boolean more = rows.size() > limit;
                    var data = new ArrayList<Map<String, Object>>();
                    for (var row : rows.subList(0, Math.min(rows.size(), limit)))
                        data.add(commands ? commandView(row) : clean(row));
                    var result = new LinkedHashMap<String, Object>();
                    result.put("data", data);
                    result.put("has_more", more);
                    result.put("queue_revision", revision(scope));
                    result.put(
                            "next_cursor",
                            more
                                    ? data.get(data.size() - 1)
                                            .get(commands ? "command_id" : "entry_id")
                                    : null);
                    return result;
                });
    }

    public Map<String, Object> command(ManagedScope scope, String id) {
        var rows =
                jdbc.queryForList(
                        "SELECT c.*,i.status AS input_status,i.rejection_reason AS"
                            + " resolution_reason FROM ma.session_command c LEFT JOIN ma.input i ON"
                            + " i.scope_key=c.scope_key AND i.input_id=c.effect_input_id WHERE"
                            + " c.scope_key=? AND c.command_id=?",
                        scope.messageGroup(),
                        id);
        if (rows.isEmpty())
            throw error("command_not_found", "Command does not exist in this Session");
        return commandView(rows.get(0));
    }

    private Map<String, Object> commandView(Map<String, Object> row) {
        var value = clean(row);
        if (value.get("input_status") != null && !"convert_to_steer".equals(value.get("kind")))
            value.put("status", value.get("input_status"));
        return value;
    }

    static void withdrawForLifecycle(
            JdbcTemplate jdbc, ManagedScope scope, String actor, String lifecycle) {
        var mapper = new ObjectMapper();
        var entries =
                jdbc.queryForList(
                        "SELECT entry_id,turn_id FROM ma.session_queue WHERE scope_key=? AND"
                                + " state='queued' ORDER BY enqueue_sequence",
                        scope.messageGroup());
        for (var entry : entries) {
            String id =
                    "queue_command_"
                            + ManagedScope.fingerprint(lifecycle, (String) entry.get("entry_id"));
            long now = System.currentTimeMillis();
            jdbc.update(
                    "UPDATE ma.session_queue SET"
                        + " state='withdrawn',version=version+1,conversion_command_id=?,updated_at=?"
                        + " WHERE scope_key=? AND entry_id=?",
                    id,
                    now,
                    scope.messageGroup(),
                    entry.get("entry_id"));
            jdbc.update(
                    "UPDATE ma.turn SET status='cancelled',inputs_open=FALSE WHERE scope_key=? AND"
                            + " turn_id=?",
                    scope.messageGroup(),
                    entry.get("turn_id"));
            changed(jdbc, scope);
            jdbc.update(
                    "INSERT INTO"
                        + " ma.session_command(scope_key,command_id,command_sequence,kind,actor,source_entry_id,status,created_at)"
                        + " SELECT scope_key,?,queue_revision,'withdraw',?,?,'applied',? FROM"
                        + " ma.session WHERE scope_key=?",
                    id,
                    actor,
                    entry.get("entry_id"),
                    now,
                    scope.messageGroup());
            ManagedSessionProtocol.closeUnstarted(
                    jdbc, mapper, scope, (String) entry.get("turn_id"), id, "withdrawn", true);
        }
    }

    private static Map<String, Object> clean(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        row.forEach(
                (key, value) -> {
                    if (!"scope_key".equalsIgnoreCase(key))
                        result.put(key.toLowerCase(Locale.ROOT), value);
                });
        return result;
    }

    void lock(ManagedScope scope) {
        jdbc.queryForObject(
                "SELECT scope_key FROM ma.session WHERE scope_key=? FOR UPDATE",
                String.class,
                scope.messageGroup());
    }

    static void changed(JdbcTemplate jdbc, ManagedScope scope) {
        jdbc.update(
                "UPDATE ma.session SET queue_revision=queue_revision+1 WHERE scope_key=?",
                scope.messageGroup());
    }

    static void signal(JdbcTemplate jdbc, ManagedScope scope) {
        jdbc.update(
                "INSERT INTO ma.session_dispatch(scope_key,next_check_at) VALUES(?,?) ON CONFLICT"
                        + " DO NOTHING",
                scope.messageGroup(),
                System.currentTimeMillis());
        jdbc.update(
                "UPDATE ma.session_dispatch SET"
                    + " requested_generation=requested_generation+1,notification_pending=TRUE,next_check_at=?"
                    + " WHERE scope_key=?",
                System.currentTimeMillis(),
                scope.messageGroup());
        ManagedResumeSignals.committed(jdbc, scope.messageGroup());
    }

    static void consumed(JdbcTemplate jdbc, ManagedScope scope, String turn) {
        if (jdbc.update(
                        "UPDATE ma.session_queue SET"
                            + " state='consumed',version=version+1,updated_at=? WHERE scope_key=?"
                            + " AND turn_id=? AND state='claimed'",
                        System.currentTimeMillis(),
                        scope.messageGroup(),
                        turn)
                == 1) {
            changed(jdbc, scope);
            signal(jdbc, scope);
        }
    }

    public record Dispatch(ManagedScope scope, String token, long generation) {}

    public List<ManagedScope> due(int limit) {
        if (remote != null)
            return mapper.convertValue(
                    ManagedServiceCalls.coordinate(remote, "queue-due", Map.of("limit", limit)),
                    mapper.getTypeFactory()
                            .constructCollectionType(List.class, ManagedScope.class));
        long now = System.currentTimeMillis();
        return jdbc.query(
                "SELECT s.tenant,s.namespace,s.session_id FROM ma.session_dispatch d JOIN"
                    + " ma.session s ON s.scope_key=d.scope_key WHERE"
                    + " s.scheduler_mode='db_queue_v1' AND d.next_check_at<=? AND d.claim_until<=?"
                    + " AND NOT EXISTS (SELECT 1 FROM ma.turn t WHERE t.scope_key=s.scope_key AND"
                    + " t.turn_id=s.current_turn_id AND t.resume_id IS NOT NULL AND t.status NOT IN"
                    + " ('succeeded','failed','cancelled')) ORDER BY d.next_check_at LIMIT ?",
                (row, index) ->
                        new ManagedScope(row.getString(1), row.getString(2), row.getString(3)),
                now,
                now,
                limit);
    }

    public Dispatch acquire(ManagedScope scope) {
        if (remote != null)
            return mapper.convertValue(
                    ManagedServiceCalls.coordinate(remote, "queue-acquire", Map.of("scope", scope)),
                    Dispatch.class);
        return tx.execute(
                ignored -> {
                    long now = System.currentTimeMillis();
                    String token = UUID.randomUUID().toString();
                    if (jdbc.update(
                                    "UPDATE ma.session_dispatch SET claim_token=?,claim_until=?"
                                            + " WHERE scope_key=? AND claim_until<=? AND"
                                            + " next_check_at<=?",
                                    token,
                                    now + 30000,
                                    scope.messageGroup(),
                                    now,
                                    now)
                            != 1) return null;
                    return new Dispatch(
                            scope,
                            token,
                            jdbc.queryForObject(
                                    "SELECT requested_generation FROM ma.session_dispatch WHERE"
                                            + " scope_key=?",
                                    Long.class,
                                    scope.messageGroup()));
                });
    }

    public void release(Dispatch dispatch) {
        if (remote != null) {
            ManagedServiceCalls.coordinate(remote, "queue-release", Map.of("dispatch", dispatch));
            return;
        }
        // Never clear a generation submitted after this worker acquired its durable handoff.
        jdbc.update(
                "UPDATE ma.session_dispatch SET"
                    + " handled_generation=?,claim_token=NULL,claim_until=0,next_check_at=CASE WHEN"
                    + " requested_generation>? THEN ? ELSE ? END WHERE scope_key=? AND"
                    + " claim_token=?",
                dispatch.generation(),
                dispatch.generation(),
                System.currentTimeMillis(),
                (candidate(dispatch.scope()) == null
                        ? Long.MAX_VALUE
                        : System.currentTimeMillis() + 1000),
                dispatch.scope().messageGroup(),
                dispatch.token());
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Ready(
            int schemaVersion,
            ManagedScope scope,
            String schedulerMode,
            long schedulerGeneration,
            long requestedGeneration,
            String notificationId) {}

    public Ready notification(ManagedScope scope) {
        var rows =
                jdbc.query(
                        "SELECT s.scheduler_generation,d.requested_generation FROM"
                            + " ma.session_dispatch d JOIN ma.session s ON s.scope_key=d.scope_key"
                            + " WHERE d.scope_key=? AND"
                            + " d.notified_generation<d.requested_generation AND"
                            + " s.scheduler_mode='db_queue_v1' AND d.next_check_at<=?",
                        (row, index) ->
                                new Ready(
                                        1,
                                        scope,
                                        MODE,
                                        row.getLong(1),
                                        row.getLong(2),
                                        "ready_"
                                                + ManagedScope.fingerprint(
                                                        scope.messageGroup(),
                                                        String.valueOf(row.getLong(2)))),
                        scope.messageGroup(),
                        System.currentTimeMillis());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Ready> notifications(int limit) {
        return jdbc.query(
                "SELECT"
                    + " s.tenant,s.namespace,s.session_id,s.scheduler_generation,d.requested_generation"
                    + " FROM ma.session_dispatch d JOIN ma.session s ON s.scope_key=d.scope_key"
                    + " WHERE d.notification_pending=TRUE AND"
                    + " d.notified_generation<d.requested_generation AND"
                    + " s.scheduler_mode='db_queue_v1' AND d.next_check_at<=? AND"
                    + " ((s.current_turn_id IS NULL AND s.lifecycle='active' AND EXISTS (SELECT 1"
                    + " FROM ma.session_queue q WHERE q.scope_key=s.scope_key AND q.state IN"
                    + " ('queued','claimed'))) OR EXISTS (SELECT 1 FROM ma.turn t WHERE"
                    + " t.scope_key=s.scope_key AND t.turn_id=s.current_turn_id AND t.resume_id IS"
                    + " NULL AND t.execution_token IS NULL AND t.status IN"
                    + " ('accepted','preparing','running'))) ORDER BY d.next_check_at,d.scope_key"
                    + " LIMIT ?",
                (row, index) -> {
                    var scope =
                            new ManagedScope(row.getString(1), row.getString(2), row.getString(3));
                    long generation = row.getLong(5);
                    return new Ready(
                            1,
                            scope,
                            MODE,
                            row.getLong(4),
                            generation,
                            "ready_"
                                    + ManagedScope.fingerprint(
                                            scope.messageGroup(), String.valueOf(generation)));
                },
                System.currentTimeMillis(),
                limit);
    }

    public void notified(Ready ready) {
        // Late publication receipts cannot clear a newer generation or reopen an already sent one.
        jdbc.update(
                "UPDATE ma.session_dispatch SET notified_generation=CASE WHEN notified_generation<?"
                    + " THEN ? ELSE notified_generation"
                    + " END,notification_pending=(requested_generation>? AND"
                    + " requested_generation>notified_generation),next_check_at=CASE WHEN"
                    + " requested_generation=? THEN GREATEST(next_check_at,?) ELSE next_check_at"
                    + " END WHERE scope_key=?",
                ready.requestedGeneration(),
                ready.requestedGeneration(),
                ready.requestedGeneration(),
                ready.requestedGeneration(),
                System.currentTimeMillis(),
                ready.scope().messageGroup());
    }

    /** Low-frequency repair of sent but unclaimed wakeups; never activates a Turn. */
    int renotifyUnclaimed(long now, Duration age, int limit) {
        long cutoff = now - ManagedMessageBus.positiveMillis(age);
        // A new generation prevents a late receipt for the old send from clearing this repair.
        // Recheck the dispatch predicates outside the subquery for concurrent repair workers.
        return jdbc.update(
                "UPDATE ma.session_dispatch SET requested_generation=requested_generation+1,"
                    + " notification_pending=TRUE,next_check_at=? WHERE notification_pending=FALSE"
                    + " AND notified_generation>=requested_generation AND next_check_at<=? AND"
                    + " claim_until<=? AND scope_key IN (SELECT d.scope_key FROM"
                    + " ma.session_dispatch d JOIN ma.session s ON s.scope_key=d.scope_key WHERE"
                    + " d.notification_pending=FALSE AND"
                    + " d.notified_generation>=d.requested_generation AND d.next_check_at<=? AND"
                    + " d.claim_until<=? AND s.scheduler_mode='db_queue_v1' AND"
                    + " s.lifecycle='active' AND ((s.current_turn_id IS NULL AND EXISTS (SELECT 1"
                    + " FROM ma.session_queue q WHERE q.scope_key=s.scope_key AND q.state IN"
                    + " ('queued','claimed'))) OR EXISTS (SELECT 1 FROM ma.turn t WHERE"
                    + " t.scope_key=s.scope_key AND t.turn_id=s.current_turn_id AND t.resume_id IS"
                    + " NULL AND t.execution_token IS NULL AND t.status IN"
                    + " ('accepted','preparing','running'))) ORDER BY d.next_check_at,d.scope_key"
                    + " LIMIT ?)",
                now,
                cutoff,
                now,
                cutoff,
                now,
                limit);
    }

    public void validate(Ready ready) {
        if (remote != null) {
            ManagedServiceCalls.coordinate(remote, "queue-validate", Map.of("ready", ready));
            return;
        }
        if (ready.schemaVersion() != 1
                || !MODE.equals(ready.schedulerMode())
                || !enabled(ready.scope()))
            throw error("scheduler_mode_mismatch", "Invalid Session-ready envelope");
        long generation =
                jdbc.queryForObject(
                        "SELECT scheduler_generation FROM ma.session WHERE scope_key=?",
                        Long.class,
                        ready.scope().messageGroup());
        if (generation != ready.schedulerGeneration())
            throw error("scheduler_generation_mismatch", "Stale Session-ready envelope");
    }

    public void observe(Ready ready) {
        if (remote != null) {
            ManagedServiceCalls.coordinate(remote, "queue-observe", Map.of("ready", ready));
            return;
        }
        validate(ready);
        jdbc.update(
                "UPDATE ma.session_dispatch SET next_check_at=? WHERE scope_key=?",
                System.currentTimeMillis(),
                ready.scope().messageGroup());
    }

    public void defer(Ready ready, Duration delay) {
        if (remote != null) {
            ManagedServiceCalls.coordinate(
                    remote, "queue-defer", Map.of("ready", ready, "delayMillis", delay.toMillis()));
            return;
        }
        validate(ready);
        jdbc.update(
                "UPDATE ma.session_dispatch SET"
                    + " requested_generation=requested_generation+1,notification_pending=TRUE,next_check_at=?"
                    + " WHERE scope_key=?",
                System.currentTimeMillis() + delay.toMillis(),
                ready.scope().messageGroup());
    }

    private static ManagedStateException error(String code, String message) {
        return new ManagedStateException(code, message);
    }
}
