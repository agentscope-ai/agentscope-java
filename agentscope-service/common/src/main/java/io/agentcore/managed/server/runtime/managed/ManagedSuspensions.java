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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Continuation facts and durable wakeup outbox, under the journal's Session transaction. */
public final class ManagedSuspensions {
    private SessionServiceClient remote;

    public void remote(SessionServiceClient client) {
        remote = client;
    }

    public record Resume(
            ManagedScope scope,
            String resumeId,
            String turnId,
            String suspensionId,
            long suspensionEpoch,
            long publicationSequence) {
        public Resume {
            if (scope == null || suspensionEpoch < 1 || publicationSequence < 1)
                throw new IllegalArgumentException("Invalid resume identity");
            ManagedScope.requireText(resumeId, "resume id");
            ManagedScope.requireText(turnId, "Turn id");
            ManagedScope.requireText(suspensionId, "suspension id");
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    ManagedSuspensions(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    void save(
            SessionCommit batch,
            String checkpoint,
            long sequence,
            ManagedCommitPlan.PendingState pending,
            ManagedTurnChanges turnChanges) {
        var suspension = batch.suspension();
        if (checkpoint == null || sequence <= batch.expectedSequence())
            throw conflict("checkpoint_required", "Suspension requires a new complete checkpoint");
        if (!suspension.taskIds().equals(pending == null ? null : pending.taskIds()))
            throw conflict(
                    "checkpoint_pending_differs",
                    "Checkpoint pending operations differ from the handoff");
        if (!suspension.actionIds().equals(pending == null ? null : pending.actionIds()))
            throw conflict(
                    "checkpoint_actions_differ",
                    "Checkpoint pending actions differ from the handoff");
        for (String action : suspension.actionIds()) {
            if (jdbc.queryForObject(
                            "SELECT COUNT(*) FROM ma.action WHERE scope_key=? AND turn_id=? AND"
                                    + " action_id=? AND status='open'",
                            Long.class,
                            batch.scope().messageGroup(),
                            batch.turnId(),
                            action)
                    != 1)
                throw conflict("action_not_pending", "Input wait requires this Turn's open action");
        }
        if (suspension.waitingForInput()
                && jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ma.action WHERE scope_key=? AND turn_id=?"
                                        + " AND status='open'",
                                Long.class,
                                batch.scope().messageGroup(),
                                batch.turnId())
                        != suspension.actionIds().size())
            throw conflict(
                    "checkpoint_actions_differ", "Input wait must capture every open action");
        if (suspension.waitingForInput()
                && jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ma.tool_task WHERE scope_key=? AND turn_id=?"
                                        + " AND status NOT IN ('succeeded','failed','cancelled')",
                                Long.class,
                                batch.scope().messageGroup(),
                                batch.turnId())
                        != 0)
            throw conflict(
                    "tools_unsettled", "Settle active Tool Worker work before waiting for input");
        if (pending != null && pending.operations() != null) {
            var captured = new HashMap<String, ManagedCommitPlan.CapturedOperation>();
            for (var operation : pending.operations()) {
                if (captured.put(operation.taskId(), operation) != null)
                    throw conflict(
                            "checkpoint_tool_conflict", "Invalid checkpoint operation identity");
            }
            for (String task : suspension.taskIds()) {
                var original = captured.get(task);
                var rows =
                        jdbc.queryForList(
                                "SELECT execution_id,tool_call_id,tool_name,arguments FROM"
                                    + " ma.tool_task WHERE scope_key=? AND turn_id=? AND task_id=?",
                                batch.scope().messageGroup(),
                                batch.turnId(),
                                task);
                if (original == null
                        || rows.size() != 1
                        || !rows.get(0).get("execution_id").equals(original.executionId())
                        || !rows.get(0).get("tool_call_id").equals(original.operationId())
                        || !rows.get(0).get("tool_name").equals(original.name())
                        || !rows.get(0).get("arguments").equals(original.arguments()))
                    throw conflict(
                            "checkpoint_tool_conflict",
                            "Checkpoint operation differs from its durable ToolTask");
            }
        }
        for (String task : suspension.taskIds()) {
            if (jdbc.queryForObject(
                            "SELECT COUNT(*) FROM ma.tool_task WHERE scope_key=? AND turn_id=? AND"
                                    + " task_id=?",
                            Long.class,
                            batch.scope().messageGroup(),
                            batch.turnId(),
                            task)
                    != 1)
                throw conflict("tool_not_found", "Suspension references an undispatched task");
        }
        jdbc.update(
                "INSERT INTO ma.turn_suspension"
                    + "(scope_key,turn_id,suspension_id,execution_epoch,checkpoint_sequence,checkpoint,pending_tasks,pending_actions,resume_phase)"
                    + " VALUES(?,?,?,?,?,?,?,?,?)",
                batch.scope().messageGroup(),
                batch.turnId(),
                suspension.id(),
                batch.epoch(),
                sequence,
                checkpoint,
                json(suspension.taskIds()),
                json(suspension.actionIds()),
                suspension.resumePhase());
        turnChanges.set("status", suspension.waitingForInput() ? "waiting_input" : "waiting_tool");
        turnChanges.clearOwnership();
        turnChanges.set("suspension_id", suspension.id());
        turnChanges.flush(jdbc, batch.scope(), batch.turnId());
        // A result can precede this suspension event. The same transaction must not lose that
        // wakeup.
        if (suspension.waitingForInput()) inputReady(jdbc, batch.scope(), batch.turnId());
        else resultReady(batch.scope(), batch.turnId());
    }

    /** Input admission and lifecycle transitions hold the same Session lock as suspension save. */
    static void inputReady(JdbcTemplate jdbc, ManagedScope scope, String turn) {
        var waiting =
                jdbc.queryForList(
                        "SELECT p.* FROM ma.turn_suspension p JOIN ma.turn t"
                                + " ON t.scope_key=p.scope_key AND t.suspension_id=p.suspension_id"
                                + " WHERE p.scope_key=? AND p.turn_id=? AND p.status='waiting'"
                                + " AND p.resume_phase='APPLY_INPUTS'",
                        scope.messageGroup(),
                        turn);
        for (var suspension : waiting) {
            boolean cancelled =
                    jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ma.session WHERE scope_key=? AND"
                                            + " lifecycle<>'active'",
                                    Long.class,
                                    scope.messageGroup())
                            > 0;
            long inputs =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM ma.input i WHERE i.scope_key=? AND i.turn_id=?"
                                + " AND i.status='accepted' AND (i.type='CANCEL' OR (i.type='REPLY'"
                                + " AND EXISTS (SELECT 1 FROM ma.action a WHERE"
                                + " a.scope_key=i.scope_key AND a.turn_id=i.turn_id AND"
                                + " a.action_id=i.action_id AND a.status='open')))",
                            Long.class,
                            scope.messageGroup(),
                            turn);
            if (!cancelled && inputs == 0) continue;
            enqueue(jdbc, scope, turn, suspension);
        }
    }

    void resultReady(ManagedScope scope, String turn) {
        var waiting =
                jdbc.queryForList(
                        "SELECT p.* FROM ma.turn_suspension p JOIN ma.turn t"
                                + " ON t.scope_key=p.scope_key AND t.suspension_id=p.suspension_id"
                                + " WHERE p.scope_key=? AND p.turn_id=? AND p.status='waiting'"
                                + " AND p.resume_phase='APPLY_TOOL_RESULTS'",
                        scope.messageGroup(),
                        turn);
        for (var suspension : waiting) {
            for (String task : tasks((String) suspension.get("pending_tasks"))) {
                if (jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ma.tool_task WHERE scope_key=? AND task_id=?"
                                        + " AND result IS NOT NULL AND status IN"
                                        + " ('succeeded','failed','cancelled')",
                                Long.class,
                                scope.messageGroup(),
                                task)
                        != 1) return;
            }
            enqueue(jdbc, scope, turn, suspension);
        }
    }

    private static void enqueue(
            JdbcTemplate jdbc, ManagedScope scope, String turn, Map<String, Object> suspension) {
        String id = (String) suspension.get("suspension_id");
        String resume = "resume_" + ManagedScope.fingerprint(scope.messageGroup(), id);
        jdbc.update(
                "INSERT INTO ma.turn_resume"
                    + "(scope_key,resume_id,turn_id,suspension_id,suspension_epoch,publication_sequence)"
                    + " SELECT scope_key,?,turn_id,?, ?,publication_sequence FROM ma.turn WHERE"
                    + " scope_key=? AND turn_id=?",
                resume,
                id,
                suspension.get("execution_epoch"),
                scope.messageGroup(),
                turn);
        jdbc.update(
                "UPDATE ma.turn_suspension SET status='ready' WHERE scope_key=? AND"
                        + " suspension_id=?",
                scope.messageGroup(),
                id);
        ManagedResumeSignals.committed(jdbc, scope.messageGroup());
    }

    DataSource dataSource() {
        return jdbc.getDataSource();
    }

    public List<Resume> pending(int limit) {
        return pending(limit, null);
    }

    List<Resume> pending(int limit, String scopeKey) {
        return pending(limit, scopeKey, 0);
    }

    List<Resume> pending(int limit, String scopeKey, int offset) {
        if (limit < 1 || limit > 1000)
            throw new IllegalArgumentException("Invalid resume scan limit");
        return jdbc.query(
                "SELECT r.*,s.tenant,s.namespace,s.session_id FROM ma.turn_resume r JOIN ma.session"
                        + " s ON s.scope_key=r.scope_key WHERE r.delivery_status='pending' AND"
                        + " r.retry_at<=? AND"
                        + (scopeKey == null ? "" : " r.scope_key=? AND")
                        + " 1=1 ORDER BY CASE WHEN EXISTS(SELECT 1 FROM ma.turn_suspension p WHERE"
                        + " p.scope_key=r.scope_key AND p.suspension_id=r.suspension_id AND"
                        + " p.resume_phase='APPLY_INPUTS') THEN 0 ELSE 1 END,"
                        + " r.scope_key,r.resume_id LIMIT ? OFFSET ?",
                (row, index) ->
                        new Resume(
                                new ManagedScope(
                                        row.getString("tenant"),
                                        row.getString("namespace"),
                                        row.getString("session_id")),
                                row.getString("resume_id"),
                                row.getString("turn_id"),
                                row.getString("suspension_id"),
                                row.getLong("suspension_epoch"),
                                row.getLong("publication_sequence")),
                scopeKey == null
                        ? new Object[] {System.currentTimeMillis(), limit, offset}
                        : new Object[] {System.currentTimeMillis(), scopeKey, limit, offset});
    }

    boolean pending(Resume resume) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.turn_resume WHERE scope_key=? AND resume_id=? AND"
                                + " delivery_status='pending' AND retry_at<=?",
                        Long.class,
                        resume.scope().messageGroup(),
                        resume.resumeId(),
                        System.currentTimeMillis())
                == 1;
    }

    long publicationVersion(Resume resume) {
        return ((Number) require(resume).get("publication_version")).longValue();
    }

    OptionalLong publication(Resume resume) {
        var row = require(resume);
        return "pending".equals(row.get("delivery_status"))
                        && ((Number) row.get("retry_at")).longValue() <= System.currentTimeMillis()
                ? OptionalLong.of(((Number) row.get("publication_version")).longValue())
                : OptionalLong.empty();
    }

    public void published(Resume resume, String brokerId) {
        published(resume, brokerId, publicationVersion(resume));
    }

    boolean published(Resume resume, String brokerId, long version) {
        return jdbc.update(
                        "UPDATE ma.turn_resume SET delivery_status='queued',broker_message_id=?"
                                + " WHERE scope_key=? AND resume_id=? AND suspension_id=? AND"
                                + " publication_version=?",
                        brokerId,
                        resume.scope().messageGroup(),
                        resume.resumeId(),
                        resume.suspensionId(),
                        version)
                == 1;
    }

    /** Exceptional contention only. Commit wake responsibility before ACKing the old receipt. */
    public void defer(Resume resume, Duration delay) {
        if (remote != null) {
            ManagedServiceCalls.post(
                    remote,
                    "prepared/resume-defer",
                    Map.of(
                            "resume",
                            resume,
                            "delayMillis",
                            ManagedMessageBus.positiveMillis(delay)));
            return;
        }
        long due =
                Math.addExact(System.currentTimeMillis(), ManagedMessageBus.positiveMillis(delay));
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource()));
        tx.setTimeout(10);
        tx.executeWithoutResult(
                ignored -> {
                    jdbc.queryForMap(
                            "SELECT scope_key FROM ma.session WHERE scope_key=? FOR UPDATE",
                            resume.scope().messageGroup());
                    if (settled(resume)) return;
                    jdbc.update(
                            "UPDATE ma.turn_resume SET"
                                    + " delivery_status='pending',broker_message_id=NULL,"
                                    + " retry_at=?,publication_version=publication_version+1 WHERE"
                                    + " scope_key=? AND resume_id=?",
                            due,
                            resume.scope().messageGroup(),
                            resume.resumeId());
                    ManagedResumeSignals.committed(jdbc, resume.scope().messageGroup() + ":" + due);
                });
    }

    public boolean settled(Resume resume) {
        if (remote != null)
            return ManagedServiceCalls.post(
                            remote,
                            "prepared/query",
                            Map.of(
                                    "scope",
                                    resume.scope(),
                                    "turnId",
                                    resume.turnId(),
                                    "operation",
                                    "resume-settled",
                                    "arguments",
                                    Map.of("resume", resume)))
                    .asBoolean();
        require(resume);
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.turn WHERE scope_key=? AND turn_id=? AND"
                                + " (suspension_id<>? OR status IN"
                                + " ('succeeded','failed','cancelled'))",
                        Long.class,
                        resume.scope().messageGroup(),
                        resume.turnId(),
                        resume.suspensionId())
                > 0;
    }

    /** Admission holds the Session row lock when calling this. */
    void activate(Resume resume) {
        require(resume);
        if (jdbc.update(
                        "UPDATE ma.turn_suspension SET status='resumed' WHERE scope_key=?"
                                + " AND suspension_id=? AND status IN ('ready','resumed')",
                        resume.scope().messageGroup(),
                        resume.suspensionId())
                != 1) throw conflict("resume_not_ready", "Pending results have not settled");
        if (jdbc.update(
                        "UPDATE ma.turn SET resume_id=? WHERE scope_key=? AND turn_id=? AND"
                                + " suspension_id=?",
                        resume.resumeId(),
                        resume.scope().messageGroup(),
                        resume.turnId(),
                        resume.suspensionId())
                != 1)
            throw conflict("stale_resume", "A newer suspension superseded this continuation");
    }

    private Map<String, Object> require(Resume resume) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM ma.turn_resume WHERE scope_key=? AND resume_id=?",
                        resume.scope().messageGroup(),
                        resume.resumeId());
        if (rows.isEmpty()) throw conflict("resume_not_found", "Resume has no durable intent");
        var row = rows.get(0);
        if (!resume.turnId().equals(row.get("turn_id"))
                || !resume.suspensionId().equals(row.get("suspension_id"))
                || resume.suspensionEpoch() != ((Number) row.get("suspension_epoch")).longValue()
                || resume.publicationSequence()
                        != ((Number) row.get("publication_sequence")).longValue())
            throw conflict("resume_identity_mismatch", "Resume differs from its immutable intent");
        return row;
    }

    private List<String> tasks(String value) {
        try {
            return mapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid pending task facts", invalid);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Cannot encode continuation", invalid);
        }
    }

    private static ManagedStateException conflict(String code, String message) {
        return new ManagedStateException(code, message);
    }
}
