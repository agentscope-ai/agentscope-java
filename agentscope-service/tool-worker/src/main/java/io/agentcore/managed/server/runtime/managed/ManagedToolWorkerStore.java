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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolClaim;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolReference;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolTask;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Tool Worker-owned execution facts. No runtime journal, checkpoint or Session state is written here. */
public final class ManagedToolWorkerStore implements ManagedToolWorkStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final ManagedToolEvents toolEvents;

    public ManagedToolWorkerStore(DataSource source, ObjectMapper mapper) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(10);
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        toolEvents = new ManagedToolEvents(source, this.mapper, true);
    }

    public ManagedToolEvents toolEvents() {
        return toolEvents;
    }

    public ManagedToolCommand command(ManagedScope scope, String task) {
        try {
            return mapper.readValue(
                    jdbc.queryForObject(
                            "SELECT command_payload FROM ma.hands_execution WHERE scope_key=? AND"
                                    + " task_id=?",
                            String.class,
                            scope.messageGroup(),
                            task),
                    ManagedToolCommand.class);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid durable tool command", invalid);
        }
    }

    public void receive(ManagedToolCommand command) {
        // A replay on the independent Control channel may precede its original Execute.
        // Only Execute carries the immutable configuration and can create an execution.
        if ("replay".equals(command.kind())) return;
        tx.executeWithoutResult(
                ignored -> {
                    var scope = command.scope();
                    var task = command.task();
                    if ("cancel".equals(command.kind())) {
                        jdbc.update(
                                "INSERT INTO ma.hands_control(scope_key,task_id,cancel_requested)"
                                        + " VALUES(?,?,TRUE) ON CONFLICT DO NOTHING",
                                scope.messageGroup(),
                                task.id());
                        jdbc.update(
                                "UPDATE ma.hands_control SET cancel_requested=TRUE WHERE"
                                        + " scope_key=? AND task_id=?",
                                scope.messageGroup(),
                                task.id());
                        fact(scope, task.id(), 0, "cancel_requested", Map.of());
                        return;
                    }
                    jdbc.update(
                            "INSERT INTO"
                                + " ma.hands_execution(scope_key,tenant,namespace,session_id,task_id,turn_id,tool_call_id,execution_id,tool_name,arguments,checkpoint_sequence,configuration,last_dispatched_at,command_payload)"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                            scope.messageGroup(),
                            scope.tenant(),
                            scope.namespace(),
                            scope.sessionId(),
                            task.id(),
                            task.turnId(),
                            task.toolCallId(),
                            task.executionId(),
                            task.name(),
                            task.arguments(),
                            task.checkpointSequence(),
                            command.configuration(),
                            System.currentTimeMillis(),
                            json(command));
                    lock(scope, task.id());
                    var old = tool(scope, task.id()).orElseThrow();
                    if (!old.turnId().equals(task.turnId())
                            || !old.executionId().equals(task.executionId())
                            || !old.name().equals(task.name())
                            || !old.arguments().equals(task.arguments()))
                        throw conflict(
                                "tool_content_conflict", "Original Tool Worker command differs");
                    fact(
                            scope,
                            task.id(),
                            0,
                            "received",
                            Map.of("execution_id", task.executionId()));
                });
    }

    /** Child rows never enter the MQ dispatch/result scans; only their parent owns execution. */
    public List<ToolTask> batchCalls(ManagedScope scope, ToolTask parent) {
        return tx.execute(
                ignored -> {
                    lock(scope, parent.id());
                    var owner = tool(scope, parent.id()).orElseThrow();
                    if (!Objects.equals(owner.workerToken(), parent.workerToken()))
                        throw conflict("stale_tool_worker", "MCP batch owner changed");
                    var manifest = ManagedMcpBatch.manifest(parent.arguments());
                    var existing = readBatchCalls(scope, parent.id());
                    if (!existing.isEmpty()) {
                        if (existing.size() != manifest.calls().size())
                            throw conflict(
                                    "mcp_batch_incomplete", "MCP batch child set is incomplete");
                        return existing;
                    }
                    int ordinal = 0;
                    for (var call : manifest.calls()) {
                        String id = ManagedMcpBatch.childTaskId(parent.id(), call.callId());
                        var child =
                                new ToolTask(
                                        id,
                                        parent.turnId(),
                                        call.callId(),
                                        id,
                                        "mcp.call",
                                        json(call.parameters()),
                                        parent.checkpointSequence(),
                                        "ready",
                                        null,
                                        null,
                                        null);
                        receive(
                                new ManagedToolCommand(
                                        scope,
                                        "batch_" + id,
                                        "execute",
                                        child,
                                        configuration(scope, parent.id())));
                        jdbc.update(
                                "UPDATE ma.hands_execution SET batch_parent_id=?,batch_ordinal=?"
                                        + " WHERE scope_key=? AND task_id=? AND (batch_parent_id IS"
                                        + " NULL OR batch_parent_id=?)",
                                parent.id(),
                                ordinal++,
                                scope.messageGroup(),
                                id,
                                parent.id());
                    }
                    return readBatchCalls(scope, parent.id());
                });
    }

    private List<ToolTask> readBatchCalls(ManagedScope scope, String parent) {
        return jdbc.query(
                "SELECT * FROM ma.hands_execution WHERE scope_key=? AND batch_parent_id=? ORDER BY"
                        + " batch_ordinal",
                this::readToolTask,
                scope.messageGroup(),
                parent);
    }

    public ToolClaim claimBatchCall(ManagedScope scope, ToolTask parent, ToolTask child) {
        return tx.execute(
                ignored -> {
                    lock(scope, parent.id());
                    var current = tool(scope, parent.id()).orElseThrow();
                    if (!Objects.equals(current.workerToken(), parent.workerToken()))
                        throw conflict("stale_tool_worker", "MCP batch owner changed");
                    var stored = tool(scope, child.id()).orElseThrow();
                    if (!"ready".equals(stored.status())
                            && Objects.equals(stored.workerToken(), parent.workerToken()))
                        return new ToolClaim(stored, false);
                    return claimTool(scope, child.id(), parent.workerToken());
                });
    }

    private boolean batchChild(ManagedScope scope, String id) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.hands_execution WHERE scope_key=? AND task_id=?"
                                + " AND batch_parent_id IS NOT NULL",
                        Long.class,
                        scope.messageGroup(),
                        id)
                == 1;
    }

    public String configuration(ManagedScope scope) {
        var rows =
                jdbc.queryForList(
                        "SELECT configuration FROM ma.hands_execution WHERE scope_key=? AND"
                                + " configuration IS NOT NULL ORDER BY task_id LIMIT 1",
                        String.class,
                        scope.messageGroup());
        if (rows.isEmpty())
            throw conflict(
                    "execution_configuration_missing",
                    "Tool Worker has no sealed execution configuration");
        return rows.get(0);
    }

    public String configuration(ManagedScope scope, String taskId) {
        return jdbc.queryForObject(
                "SELECT configuration FROM ma.hands_execution WHERE scope_key=? AND task_id=?",
                String.class,
                scope.messageGroup(),
                taskId);
    }

    public boolean cancelRequested(ManagedScope scope, String turn) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.hands_control c JOIN ma.hands_execution e ON"
                            + " e.scope_key=c.scope_key AND e.task_id=c.task_id WHERE e.scope_key=?"
                            + " AND e.turn_id=? AND c.cancel_requested=TRUE",
                        Long.class,
                        scope.messageGroup(),
                        turn)
                > 0;
    }

    @Override
    public boolean cancelRequested(ManagedScope scope, ToolTask task) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.hands_control WHERE scope_key=? AND task_id=? AND"
                                + " cancel_requested=TRUE",
                        Long.class,
                        scope.messageGroup(),
                        task.id())
                > 0;
    }

    public void replay(ManagedScope scope, String task) {
        jdbc.update(
                "UPDATE ma.hands_tool_event SET broker_at=NULL,next_attempt_at=0 WHERE scope_key=?"
                        + " AND task_id=?",
                scope.messageGroup(),
                task);
    }

    @Override
    public boolean usesSessionInputCancellation() {
        return false;
    }

    @Override
    public void complete(ManagedToolResult result) {
        tx.executeWithoutResult(
                ignored -> {
                    lock(result.scope(), result.taskId());
                    var stored = stagedToolResult(result.scope(), result.taskId()).orElseThrow();
                    if (!stored.equals(result))
                        throw conflict("tool_result_conflict", "Original result differs");
                    if (!batchChild(result.scope(), result.taskId())) toolEvents.finish(result);
                    fact(
                            result.scope(),
                            result.taskId(),
                            generation(result.scope(), result.taskId()),
                            result.status(),
                            Map.of("result_hash", ManagedScope.fingerprint(result.result())));
                    jdbc.update(
                            "UPDATE ma.hands_execution SET status=?,result=? WHERE scope_key=? AND"
                                    + " task_id=?",
                            result.status(),
                            result.result(),
                            result.scope().messageGroup(),
                            result.taskId());
                    jdbc.update(
                            "UPDATE ma.hands_tool_result_outbox SET committed_at=? WHERE"
                                    + " scope_key=? AND task_id=?",
                            System.currentTimeMillis(),
                            result.scope().messageGroup(),
                            result.taskId());
                });
    }

    private void lock(ManagedScope scope, String task) {
        jdbc.queryForObject(
                "SELECT task_id FROM ma.hands_execution WHERE scope_key=? AND task_id=? FOR UPDATE",
                String.class,
                scope.messageGroup(),
                task);
    }

    private long generation(ManagedScope scope, String task) {
        return jdbc.queryForObject(
                "SELECT owner_generation FROM ma.hands_execution WHERE scope_key=? AND task_id=?",
                Long.class,
                scope.messageGroup(),
                task);
    }

    private void fact(
            ManagedScope scope,
            String task,
            long generation,
            String phase,
            Map<String, Object> detail) {
        jdbc.update(
                "INSERT INTO"
                    + " ma.hands_execution_event(scope_key,task_id,owner_generation,phase,recorded_at,detail)"
                    + " VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                scope.messageGroup(),
                task,
                generation,
                phase,
                System.currentTimeMillis(),
                json(detail));
    }

    public List<String> history(ManagedScope scope, String task) {
        return jdbc.queryForList(
                "SELECT phase FROM ma.hands_execution_event WHERE scope_key=? AND task_id=? ORDER"
                        + " BY recorded_at,owner_generation,phase",
                String.class,
                scope.messageGroup(),
                task);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot encode Tool Worker fact", e);
        }
    }

    private <T> T parse(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot decode Tool Worker fact", e);
        }
    }

    private static ManagedStateException conflict(String code, String message) {
        return new ManagedStateException(code, message);
    }

    public Optional<ToolTask> tool(ManagedScope scope, String taskId) {
        return jdbc
                .query(
                        "SELECT * FROM ma.hands_execution WHERE scope_key=? AND task_id=?",
                        this::readToolTask,
                        scope.messageGroup(),
                        taskId)
                .stream()
                .findFirst();
    }

    private ToolTask readToolTask(ResultSet row, int index) throws SQLException {
        return new ToolTask(
                row.getString("task_id"),
                row.getString("turn_id"),
                row.getString("tool_call_id"),
                row.getString("execution_id"),
                row.getString("tool_name"),
                row.getString("arguments"),
                row.getLong("checkpoint_sequence"),
                row.getString("status"),
                row.getString("process_handle"),
                row.getString("result"),
                row.getString("worker_token"));
    }

    private ManagedProcessOwnership processOwnership;

    public void processOwnership(ManagedProcessOwnership ownership) {
        this.processOwnership = ownership;
    }

    private <T> T owned(Supplier<T> work) {
        return processOwnership == null ? work.get() : processOwnership.transaction(work);
    }

    @Override
    public Duration toolOwnerLifetime() {
        return processOwnership == null ? null : processOwnership.toolLifetime();
    }

    public ToolClaim claimTool(ManagedScope scope, String id, String token) {
        ManagedScope.requireText(token, "tool worker token");
        return owned(
                () ->
                        tx.execute(
                                ignored -> {
                                    var priorOwner =
                                            processOwnership == null
                                                    ? null
                                                    : processOwnership.guardClaim(
                                                            "ma.hands_execution",
                                                            "task_id",
                                                            scope,
                                                            id,
                                                            token);
                                    lock(scope, id);
                                    if (processOwnership != null)
                                        processOwnership.verifyClaim(
                                                priorOwner,
                                                "ma.hands_execution",
                                                "task_id",
                                                scope,
                                                id);
                                    var task =
                                            tool(scope, id)
                                                    .orElseThrow(
                                                            () ->
                                                                    conflict(
                                                                            "tool_not_found",
                                                                            "Tool task does not"
                                                                                    + " exist"));
                                    boolean first = "ready".equals(task.status());
                                    if (List.of("succeeded", "failed", "cancelled")
                                                    .contains(task.status())
                                            || ("result_pending".equals(task.status())
                                                    && !toolEvents.unfinished(scope, id)))
                                        return new ToolClaim(task, false);
                                    jdbc.update(
                                            "UPDATE ma.hands_execution SET"
                                                + " worker_token=?,owner_incarnation=?,execution_deadline=?,owner_generation=owner_generation+1,last_dispatched_at=?,status=CASE"
                                                + " WHEN status='ready' THEN 'starting' ELSE status"
                                                + " END WHERE scope_key=? AND task_id=?",
                                            token,
                                            processOwnership == null
                                                    ? null
                                                    : processOwnership.incarnation(),
                                            processOwnership == null
                                                    ? null
                                                    : processOwnership.deadline(true),
                                            System.currentTimeMillis(),
                                            scope.messageGroup(),
                                            id);
                                    fact(
                                            scope,
                                            id,
                                            generation(scope, id),
                                            first ? "starting" : "collector_claimed",
                                            Map.of("worker_token", token));
                                    return new ToolClaim(tool(scope, id).orElseThrow(), first);
                                }));
    }

    public ToolTask recordToolHandle(ManagedScope scope, String id, String token, String handle) {
        return owned(
                () ->
                        tx.execute(
                                ignored -> {
                                    lock(scope, id);
                                    if (processOwnership != null)
                                        processOwnership.requireTool(scope, id, token);
                                    var task =
                                            tool(scope, id)
                                                    .orElseThrow(
                                                            () ->
                                                                    conflict(
                                                                            "tool_not_found",
                                                                            "Tool task does not"
                                                                                    + " exist"));
                                    if (!token.equals(task.workerToken()))
                                        throw conflict("stale_tool_worker", "Tool worker changed");
                                    if (List.of(
                                                    "succeeded",
                                                    "failed",
                                                    "cancelled",
                                                    "result_pending")
                                            .contains(task.status())) return task;
                                    jdbc.update(
                                            "UPDATE ma.hands_execution SET"
                                                    + " status='running',process_handle=? WHERE"
                                                    + " scope_key=? AND task_id=?",
                                            handle,
                                            scope.messageGroup(),
                                            id);
                                    fact(
                                            scope,
                                            id,
                                            generation(scope, id),
                                            "running",
                                            Map.of("handle", handle));
                                    return tool(scope, id).orElseThrow();
                                }));
    }

    public void unstarted(ManagedScope scope, ToolTask task) {
        jdbc.update(
                "UPDATE ma.hands_execution SET"
                        + " status='ready',owner_incarnation=NULL,execution_deadline=NULL WHERE"
                        + " scope_key=? AND task_id=? AND worker_token=? AND status='starting' AND"
                        + " process_handle IS NULL",
                scope.messageGroup(),
                task.id(),
                task.workerToken());
    }

    public void markToolUnknown(ManagedScope scope, String id, String token) {
        tx.executeWithoutResult(
                ignored -> {
                    lock(scope, id);
                    if (jdbc.update(
                                    "UPDATE ma.hands_execution SET"
                                        + " status='unknown',owner_incarnation=NULL,execution_deadline=NULL"
                                        + " WHERE scope_key=? AND task_id=? AND worker_token=? AND"
                                        + " status IN ('starting','running')",
                                    scope.messageGroup(),
                                    id,
                                    token)
                            == 1) {
                        fact(scope, id, generation(scope, id), "unknown", Map.of());
                        if (toolEvents.cursor(scope, id).isPresent())
                            toolEvents.unknown(scope, tool(scope, id).orElseThrow(), token);
                    }
                });
    }

    /** Scheduling fairness only: this does not claim, launch or change a tool's execution state. */
    public void deferToolDispatch(ManagedScope scope, String taskId) {
        jdbc.update(
                "UPDATE ma.hands_execution SET last_dispatched_at=? WHERE scope_key=? AND task_id=?"
                        + " AND result IS NULL",
                System.currentTimeMillis(),
                scope.messageGroup(),
                taskId);
    }

    @Override
    public void toolHeartbeat(ManagedScope scope, String taskId, String token, long now) {
        if (processOwnership != null) {
            processOwnership.checkLocal();
            return;
        }
        jdbc.update(
                "UPDATE ma.hands_execution SET last_dispatched_at=? WHERE scope_key=? AND task_id=?"
                        + " AND worker_token=? AND result IS NULL",
                now,
                scope.messageGroup(),
                taskId,
                token);
    }

    /** Indexed recovery scan. Healthy owners refresh last_dispatched_at while renewing their lease. */
    public List<ToolReference> pendingExecutions(
            int limit, long now, long leaseMillis, long retryMillis) {
        if (limit < 1 || limit > 256 || leaseMillis < 1 || retryMillis < 1)
            throw new IllegalArgumentException("Invalid Tool dispatch bounds");
        return jdbc.query(
                "SELECT tenant,namespace,session_id,task_id FROM ma.hands_execution WHERE"
                    + " tool_name<>'subagent.call' AND batch_parent_id IS NULL AND result IS NULL"
                    + " AND (owner_incarnation IS NULL OR execution_deadline<=? OR NOT EXISTS"
                    + " (SELECT 1 FROM ma_worker.lease w WHERE w.incarnation=owner_incarnation AND"
                    + " w.expires_at>?)) AND ((status IN ('ready','unknown') AND"
                    + " last_dispatched_at<=?) OR (status IN"
                    + " ('starting','running','result_pending') AND last_dispatched_at<=?)) ORDER"
                    + " BY last_dispatched_at,task_id LIMIT ?",
                (row, index) ->
                        new ToolReference(
                                new ManagedScope(
                                        row.getString(1), row.getString(2), row.getString(3)),
                                row.getString(4)),
                now,
                now,
                now - retryMillis,
                now - leaseMillis,
                limit);
    }

    /** Reserve a dispatch, not an execution. A crash here is repaired by the same bounded scan. */
    public boolean reserveExecution(
            ManagedScope scope, String taskId, long now, long leaseMillis, long retryMillis) {
        return jdbc.update(
                        "UPDATE ma.hands_execution SET last_dispatched_at=? WHERE scope_key=? AND"
                                + " task_id=? AND batch_parent_id IS NULL AND result IS NULL AND"
                                + " ((status IN ('ready','unknown') AND last_dispatched_at<=?) OR"
                                + " (status IN ('starting','running','result_pending') AND"
                                + " last_dispatched_at<=?))",
                        now,
                        scope.messageGroup(),
                        taskId,
                        now - retryMillis,
                        now - leaseMillis)
                == 1;
    }

    public List<ToolReference> pendingTools(int limit) {
        return pendingTools(limit, null);
    }

    public List<ToolReference> pendingTools(int limit, boolean childCalls) {
        return pendingTools(limit, Boolean.valueOf(childCalls));
    }

    private List<ToolReference> pendingTools(int limit, Boolean childCalls) {
        if (limit < 1 || limit > 256)
            throw new IllegalArgumentException("Invalid pending Tool limit");
        return jdbc.query(
                "SELECT s.tenant,s.namespace,s.session_id,t.task_id FROM ma.hands_execution t JOIN"
                    + " ma.hands_execution s ON s.scope_key=t.scope_key AND s.task_id=t.task_id"
                    + " WHERE t.batch_parent_id IS NULL AND t.status IN"
                    + " ('ready','starting','running','unknown','result_pending') AND"
                    + " (t.status<>'result_pending' OR EXISTS (SELECT 1 FROM ma.hands_tool_stream"
                    + " ts WHERE ts.scope_key=t.scope_key AND ts.task_id=t.task_id AND"
                    + " ts.final_sequence IS NULL))"
                        + (childCalls == null
                                ? ""
                                : " AND t.tool_name"
                                        + (childCalls ? "=" : "<>")
                                        + "'subagent.call'")
                        + " ORDER BY"
                        + " t.last_dispatched_at,t.task_id LIMIT ?",
                (row, index) ->
                        new ToolReference(
                                new ManagedScope(
                                        row.getString(1), row.getString(2), row.getString(3)),
                                row.getString(4)),
                limit);
    }

    /** Freeze the full confirmed outcome before any transport I/O; never overwrite a prior fact. */
    public boolean stageToolResult(ManagedToolResult result) {
        String payload = json(result);
        String hash = ManagedScope.fingerprint(payload);
        return tx.execute(
                ignored -> {
                    var scope = result.scope();
                    lock(scope, result.taskId());
                    var existing =
                            jdbc.queryForList(
                                    "SELECT result_hash,committed_at FROM"
                                            + " ma.hands_tool_result_outbox WHERE scope_key=? AND"
                                            + " task_id=?",
                                    scope.messageGroup(),
                                    result.taskId());
                    if (!existing.isEmpty()) {
                        if (!hash.equals(existing.get(0).get("result_hash")))
                            throw conflict(
                                    "tool_result_conflict", "Confirmed tool outcome differs");
                        return existing.get(0).get("committed_at") == null;
                    }
                    var task =
                            tool(scope, result.taskId())
                                    .orElseThrow(
                                            () ->
                                                    conflict(
                                                            "tool_not_found",
                                                            "Tool task does not exist"));
                    if (!task.executionId().equals(result.executionId())
                            || "ready".equals(task.status()))
                        throw conflict(
                                "tool_execution_mismatch",
                                "Result has no matching launched execution");
                    if (task.result() != null)
                        throw conflict(
                                "tool_result_conflict",
                                "Tool already settled without its staged outcome");
                    jdbc.update(
                            "INSERT INTO"
                                + " ma.hands_tool_result_outbox(scope_key,task_id,execution_id,result_hash,payload)"
                                + " VALUES(?,?,?,?,?)",
                            scope.messageGroup(),
                            result.taskId(),
                            result.executionId(),
                            hash,
                            payload);
                    jdbc.update(
                            "UPDATE ma.hands_execution SET status='result_pending' WHERE"
                                    + " scope_key=? AND task_id=?",
                            scope.messageGroup(),
                            result.taskId());
                    fact(
                            scope,
                            result.taskId(),
                            generation(scope, result.taskId()),
                            "result_pending",
                            Map.of("result_hash", hash));
                    if (batchChild(scope, result.taskId())) complete(result);
                    return true;
                });
    }

    public Optional<ManagedToolResult> stagedToolResult(ManagedScope scope, String task) {
        return jdbc
                .query(
                        "SELECT payload,result_hash FROM ma.hands_tool_result_outbox WHERE"
                                + " scope_key=? AND task_id=?",
                        (row, index) -> {
                            String payload = row.getString(1);
                            if (!ManagedScope.fingerprint(payload).equals(row.getString(2)))
                                throw conflict(
                                        "tool_result_conflict", "Staged tool outcome is corrupt");
                            var result = parse(payload, ManagedToolResult.class);
                            if (!scope.equals(result.scope()) || !task.equals(result.taskId()))
                                throw conflict(
                                        "tool_result_conflict", "Staged tool identity differs");
                            return result;
                        },
                        scope.messageGroup(),
                        task)
                .stream()
                .findFirst();
    }

    /** Repair only: normal confirmed results are sent immediately after staging. */
    public List<ToolReference> pendingToolResultPublications(int limit, long now) {
        if (limit < 1 || limit > 256)
            throw new IllegalArgumentException("Invalid publication limit");
        return jdbc.query(
                "SELECT s.tenant,s.namespace,s.session_id,o.task_id FROM"
                        + " ma.hands_tool_result_outbox o JOIN ma.hands_execution s ON"
                        + " s.scope_key=o.scope_key AND s.task_id=o.task_id WHERE o.committed_at IS"
                        + " NULL AND s.batch_parent_id IS NULL AND o.next_attempt_at<=? ORDER BY"
                        + " o.next_attempt_at,o.scope_key,o.task_id LIMIT ?",
                (row, index) ->
                        new ToolReference(
                                new ManagedScope(
                                        row.getString(1), row.getString(2), row.getString(3)),
                                row.getString(4)),
                now,
                limit);
    }

    public void toolResultPublishAttempt(ManagedToolResult result, long nextAttempt) {
        jdbc.update(
                "UPDATE ma.hands_tool_result_outbox SET next_attempt_at=? WHERE scope_key=? AND"
                        + " task_id=? AND committed_at IS NULL",
                nextAttempt,
                result.scope().messageGroup(),
                result.taskId());
    }
}
