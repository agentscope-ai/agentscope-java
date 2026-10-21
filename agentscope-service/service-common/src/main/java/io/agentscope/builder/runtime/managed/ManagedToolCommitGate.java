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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared Runtime authorization only; Worker execution facts remain in the Worker store. */
public final class ManagedToolCommitGate {
    public enum Decision {
        COMMITTED,
        CANCELLED,
        PENDING,
        DISCARDED
    }

    private static final List<String> TERMINAL = List.of("succeeded", "failed", "cancelled");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    public ManagedToolCommitGate(DataSource source, ObjectMapper mapper) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(10);
        this.mapper = mapper;
    }

    /** The Session lock serializes this decision with commit AND epoch replacement. */
    public Decision check(ManagedToolCommand command) {
        return tx.execute(
                ignored -> {
                    var scope = command.scope();
                    var sessions =
                            jdbc.queryForList(
                                    "SELECT execution_epoch FROM ma.session WHERE scope_key=? FOR"
                                            + " UPDATE",
                                    scope.messageGroup());
                    if (sessions.isEmpty()) return Decision.PENDING;
                    var receipts =
                            jdbc.queryForList(
                                    "SELECT payload FROM ma.tool_command_outbox WHERE command_id=?"
                                            + " AND scope_key=? AND authorized=TRUE",
                                    String.class,
                                    command.commandId(),
                                    scope.messageGroup());
                    boolean taskCommitted =
                            jdbc.queryForObject(
                                                    "SELECT COUNT(*) FROM ma.tool_task WHERE"
                                                            + " scope_key=? AND task_id=?",
                                                    Long.class,
                                                    scope.messageGroup(),
                                                    command.task().id())
                                            > 0
                                    || jdbc.queryForObject(
                                                    "SELECT COUNT(*) FROM ma.admin_tool_operation"
                                                            + " WHERE scope_key=? AND task_id=?",
                                                    Long.class,
                                                    scope.messageGroup(),
                                                    command.task().id())
                                            > 0;
                    if (!receipts.isEmpty() && taskCommitted) {
                        var accepted = parse(receipts.get(0));
                        if (sameOperation(command, accepted)) {
                            boolean cancelled =
                                    jdbc.queryForObject(
                                                    "SELECT COUNT(*) FROM ma.session s WHERE"
                                                        + " s.scope_key=? AND"
                                                        + " (s.lifecycle<>'active' OR EXISTS(SELECT"
                                                        + " 1 FROM ma.input i WHERE"
                                                        + " i.scope_key=s.scope_key AND i.turn_id=?"
                                                        + " AND i.type='CANCEL' AND"
                                                        + " i.status<>'rejected'))",
                                                    Long.class,
                                                    scope.messageGroup(),
                                                    command.task().turnId())
                                            > 0;
                            return cancelled ? Decision.CANCELLED : Decision.COMMITTED;
                        }
                        // Another fenced attempt committed this identity with a different captured
                        // configuration.
                        if (command.submission() != null
                                && !Objects.equals(command.submission(), accepted.submission()))
                            return Decision.DISCARDED;
                        throw new ManagedStateException(
                                "tool_command_conflict", "Committed tool command differs");
                    }
                    var submission = command.submission();
                    if (submission == null)
                        return Decision.PENDING; // Old in-flight command: never infer rollback from
                    // absence.
                    var turns =
                            jdbc.queryForList(
                                    "SELECT status,execution_epoch,execution_token FROM ma.turn"
                                            + " WHERE scope_key=? AND turn_id=?",
                                    scope.messageGroup(),
                                    command.task().turnId());
                    if (turns.isEmpty()) return Decision.PENDING;
                    var turn = turns.get(0);
                    boolean fenced =
                            ((Number) sessions.get(0).get("execution_epoch")).longValue()
                                            != submission.epoch()
                                    || ((Number) turn.get("execution_epoch")).longValue()
                                            != submission.epoch()
                                    || !submission
                                            .executionToken()
                                            .equals(turn.get("execution_token"));
                    // Expiry alone is not a decision. A new durable fence (or terminal) prevents a
                    // late commit.
                    return fenced || TERMINAL.contains(turn.get("status"))
                            ? Decision.DISCARDED
                            : Decision.PENDING;
                });
    }

    private boolean sameOperation(ManagedToolCommand a, ManagedToolCommand b) {
        var x = a.task();
        var y = b.task();
        return a.scope().equals(b.scope())
                && a.commandId().equals(b.commandId())
                && a.kind().equals(b.kind())
                && x.id().equals(y.id())
                && x.turnId().equals(y.turnId())
                && x.toolCallId().equals(y.toolCallId())
                && x.executionId().equals(y.executionId())
                && x.name().equals(y.name())
                && x.arguments().equals(y.arguments())
                && x.checkpointSequence() == y.checkpointSequence()
                && Objects.equals(a.configuration(), b.configuration());
    }

    private ManagedToolCommand parse(String payload) {
        try {
            return mapper.readValue(payload, ManagedToolCommand.class);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid committed tool command", invalid);
        }
    }
}
