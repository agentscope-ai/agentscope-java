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

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable resource transitions; no execution lease or HarnessRuntime address is stored here. */
public final class ManagedLifecycles {
    public record Command(
            ManagedScope scope,
            String id,
            String desired,
            long revision,
            String status,
            String failureCode) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ManagedLifecycles(DataSource source) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(10);
    }

    public Command stage(ManagedScope scope, String desired, String actor, String key) {
        if (!List.of("active", "archived", "deleted").contains(desired))
            throw new IllegalArgumentException("Invalid Session lifecycle");
        ManagedScope.requireText(actor, "actor");
        ManagedScope.requireText(key, "idempotency key");
        String id = "lifecycle_" + ManagedScope.fingerprint(scope.messageGroup(), actor, key);
        return transaction(
                () -> {
                    var session = lock(scope);
                    var previous =
                            jdbc.queryForList(
                                    "SELECT * FROM ma.lifecycle_command WHERE scope_key=? AND"
                                            + " command_id=?",
                                    scope.messageGroup(),
                                    id);
                    if (!previous.isEmpty()) {
                        var prior = command(scope, previous.get(0));
                        if (!desired.equals(prior.desired()))
                            throw conflict(
                                    "lifecycle_content_conflict",
                                    "Lifecycle idempotency content differs");
                        return prior;
                    }
                    ManagedCheckpointHolds.requireUnheld(jdbc, scope);
                    if ("deleted".equals(session.get("lifecycle")))
                        throw conflict("session_not_found", "Session has been deleted");
                    if (session.get("lifecycle_command_id") instanceof String active) {
                        var pending = get(scope, active);
                        if (!"completed".equals(pending.status()))
                            throw conflict(
                                    "lifecycle_pending",
                                    "Previous lifecycle cleanup is not complete");
                    }
                    if ("active".equals(desired) && !quiescent(scope))
                        throw conflict(
                                "session_not_quiescent", "Unsettled execution prevents restore");
                    long revision = ((Number) session.get("lifecycle_revision")).longValue() + 1;
                    long now = System.currentTimeMillis();
                    String status =
                            "active".equals(desired) || desired.equals(session.get("lifecycle"))
                                    ? "completed"
                                    : "stopping";
                    jdbc.update(
                            "INSERT INTO"
                                + " ma.lifecycle_command(scope_key,command_id,desired,actor,revision,status,created_at,updated_at)"
                                + " VALUES(?,?,?,?,?,?,?,?)",
                            scope.messageGroup(),
                            id,
                            desired,
                            actor,
                            revision,
                            status,
                            now,
                            now);
                    jdbc.update(
                            "UPDATE ma.session SET"
                                + " lifecycle=?,lifecycle_revision=?,lifecycle_command_id=? WHERE"
                                + " scope_key=?",
                            desired,
                            revision,
                            id,
                            scope.messageGroup());
                    if (ManagedSessionQueue.MODE.equals(session.get("scheduler_mode"))) {
                        if (!"active".equals(desired))
                            ManagedSessionQueue.withdrawForLifecycle(jdbc, scope, actor, id);
                        ManagedSessionQueue.signal(jdbc, scope);
                    }
                    if (!"active".equals(desired)
                            && session.get("current_turn_id") instanceof String turn)
                        ManagedSuspensions.inputReady(jdbc, scope, turn);
                    return get(scope, id);
                });
    }

    public Command get(ManagedScope scope, String id) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM ma.lifecycle_command WHERE scope_key=? AND command_id=?",
                        scope.messageGroup(),
                        id);
        if (rows.isEmpty())
            throw conflict("lifecycle_not_found", "Unknown Session lifecycle command");
        return command(scope, rows.get(0));
    }

    public Command current(ManagedScope scope) {
        var rows =
                jdbc.queryForList(
                        "SELECT lifecycle_command_id FROM ma.session WHERE scope_key=?",
                        scope.messageGroup());
        if (rows.isEmpty()) throw conflict("session_not_found", "Unknown Session");
        Object id = rows.get(0).get("lifecycle_command_id");
        return id == null ? null : get(scope, (String) id);
    }

    public boolean active(ManagedScope scope) {
        return "active"
                .equals(
                        jdbc.queryForObject(
                                "SELECT lifecycle FROM ma.session WHERE scope_key=?",
                                String.class,
                                scope.messageGroup()));
    }

    public boolean quiescent(ManagedScope scope) {
        long turns =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.turn WHERE scope_key=? AND status NOT IN"
                                + " ('succeeded','failed','cancelled')",
                        Long.class,
                        scope.messageGroup());
        long tools =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.tool_task WHERE scope_key=? AND status NOT IN"
                                + " ('succeeded','failed','cancelled')",
                        Long.class,
                        scope.messageGroup());
        return turns == 0 && tools == 0;
    }

    /** Serializes the Agent Service's cancel authorization check with new Turn admission. */
    public void lockForTransition(ManagedScope scope) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Lifecycle authorization requires a transaction");
        lock(scope);
    }

    public List<String> unsettledTurns(ManagedScope scope, int limit) {
        if (limit < 1 || limit > 256) throw new IllegalArgumentException("Invalid Turn scan limit");
        return jdbc.queryForList(
                "SELECT turn_id FROM ma.turn WHERE scope_key=? AND status NOT IN"
                        + " ('succeeded','failed','cancelled') ORDER BY admission_sequence LIMIT ?",
                String.class,
                scope.messageGroup(),
                limit);
    }

    public List<Command> pending(int limit) {
        if (limit < 1 || limit > 256)
            throw new IllegalArgumentException("Invalid lifecycle scan limit");
        return jdbc.query(
                "SELECT c.*,s.tenant,s.namespace,s.session_id FROM ma.lifecycle_command c JOIN"
                        + " ma.session s ON c.scope_key=s.scope_key WHERE c.status IN"
                        + " ('stopping','cleaning') ORDER BY c.updated_at,c.command_id LIMIT ?",
                (row, index) ->
                        new Command(
                                new ManagedScope(
                                        row.getString("tenant"),
                                        row.getString("namespace"),
                                        row.getString("session_id")),
                                row.getString("command_id"),
                                row.getString("desired"),
                                row.getLong("revision"),
                                row.getString("status"),
                                row.getString("failure_code")),
                limit);
    }

    /** Caller holds the Redis cleanup token; restore remains fenced by this unfinished command. */
    public boolean beginCleanup(Command command) {
        return transaction(
                () -> {
                    var session = lock(command.scope());
                    if (!command.id().equals(session.get("lifecycle_command_id"))) return false;
                    if (!quiescent(command.scope())) {
                        jdbc.update(
                                "UPDATE ma.lifecycle_command SET updated_at=? WHERE scope_key=? AND"
                                        + " command_id=?",
                                System.currentTimeMillis(),
                                command.scope().messageGroup(),
                                command.id());
                        return false;
                    }
                    return jdbc.update(
                                    "UPDATE ma.lifecycle_command SET status='cleaning',updated_at=?"
                                            + " WHERE scope_key=? AND command_id=? AND status IN"
                                            + " ('stopping','cleaning')",
                                    System.currentTimeMillis(),
                                    command.scope().messageGroup(),
                                    command.id())
                            == 1;
                });
    }

    public void complete(Command command) {
        transaction(
                () -> {
                    var session = lock(command.scope());
                    if (!command.id().equals(session.get("lifecycle_command_id"))
                            || !quiescent(command.scope()))
                        throw conflict(
                                "lifecycle_changed", "Lifecycle target or execution changed");
                    jdbc.update(
                            "UPDATE ma.lifecycle_command SET"
                                    + " status='completed',failure_code=NULL,updated_at=? WHERE"
                                    + " scope_key=? AND command_id=?",
                            System.currentTimeMillis(),
                            command.scope().messageGroup(),
                            command.id());
                    return null;
                });
    }

    public void failed(Command command, String code) {
        jdbc.update(
                "UPDATE ma.lifecycle_command SET failure_code=?,updated_at=? WHERE scope_key=? AND"
                        + " command_id=? AND status<>'completed'",
                code,
                System.currentTimeMillis(),
                command.scope().messageGroup(),
                command.id());
    }

    private Map<String, Object> lock(ManagedScope scope) {
        var rows =
                jdbc.queryForList(
                        "SELECT"
                            + " lifecycle,lifecycle_revision,lifecycle_command_id,current_turn_id,scheduler_mode"
                            + " FROM ma.session WHERE scope_key=? FOR UPDATE",
                        scope.messageGroup());
        if (rows.isEmpty()) throw conflict("session_not_found", "Unknown Session");
        return rows.get(0);
    }

    private <T> T transaction(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))
            return work.get();
        return tx.execute(ignored -> work.get());
    }

    private static Command command(ManagedScope scope, Map<String, Object> row) {
        return new Command(
                scope,
                (String) row.get("command_id"),
                (String) row.get("desired"),
                ((Number) row.get("revision")).longValue(),
                (String) row.get("status"),
                (String) row.get("failure_code"));
    }

    private static ManagedStateException conflict(String code, String text) {
        return new ManagedStateException(code, text);
    }
}
