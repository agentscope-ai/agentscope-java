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
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Channel;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Direct command publication and durable receipts; no live Execute outbox publisher. */
public final class ManagedToolTransport implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ManagedToolTransport.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final ManagedToolCommitGate confirmation;
    private ScheduledExecutorService controls;
    private volatile ManagedMessageBus directBus;
    private volatile ManagedBatchCodec directCodec;

    public record Sent(ManagedToolCommand command, String brokerId) {}

    /** Broker admission must succeed before the Runtime commits tool authorization. */
    public Sent send(ManagedToolCommand command) {
        var bus = directBus;
        if (bus == null)
            throw new ManagedTransportException("Tool command publisher is not configured");
        ManagedMessageBus.Published receipt;
        try {
            if ("execute".equals(command.kind()) && command.submission() != null) propose(command);
            receipt =
                    bus.publish(
                            "execute".equals(command.kind())
                                    ? Channel.TOOL_EXECUTE
                                    : Channel.TOOL_CONTROL,
                            directCodec.encode(command));
        } catch (ManagedTransportException | DataAccessException unavailable) {
            if (command.submission() != null) throw new ManagedToolPublicationPending(unavailable);
            throw unavailable;
        }
        ManagedRuntimeLog.tool(
                log,
                "tool.command.send.confirmed",
                command.scope(),
                command.task(),
                "command_id",
                command.commandId(),
                "broker_message_id",
                receipt.brokerMessageId());
        return new Sent(command, receipt.brokerMessageId());
    }

    /** Shares the caller's transaction with the task and checkpoint. This is a receipt, not pending work. */
    public void record(Sent sent) {
        stageRecord(sent.command());
        jdbc.update(
                "UPDATE ma.tool_command_outbox SET authorized=TRUE,broker_id=COALESCE(broker_id,?)"
                        + " WHERE command_id=?",
                sent.brokerId(),
                sent.command().commandId());
    }

    /** An approved proposal preserves the exact batch across a pre-commit crash; it is not executable. */
    private void propose(ManagedToolCommand command) {
        tx.executeWithoutResult(
                ignored -> {
                    var scope = command.scope();
                    var submission = command.submission();
                    var session =
                            jdbc.queryForMap(
                                    "SELECT execution_epoch,current_turn_id FROM ma.session WHERE"
                                            + " scope_key=? FOR UPDATE",
                                    scope.messageGroup());
                    var turn =
                            jdbc.queryForMap(
                                    "SELECT execution_token,status FROM ma.turn WHERE scope_key=?"
                                            + " AND turn_id=?",
                                    scope.messageGroup(),
                                    command.task().turnId());
                    if (((Number) session.get("execution_epoch")).longValue() != submission.epoch()
                            || !command.task().turnId().equals(session.get("current_turn_id"))
                            || !submission.executionToken().equals(turn.get("execution_token"))
                            || List.of("succeeded", "failed", "cancelled")
                                    .contains(turn.get("status")))
                        throw new ManagedStateException(
                                "stale_execution", "Proposal writer is no longer current");
                    jdbc.update(
                            "INSERT INTO"
                                + " ma.tool_command_outbox(command_id,scope_key,task_id,kind,payload,authorized)"
                                + " VALUES(?,?,?,?,?,FALSE) ON CONFLICT DO NOTHING",
                            command.commandId(),
                            scope.messageGroup(),
                            command.task().id(),
                            command.kind(),
                            json(command));
                    var row =
                            jdbc.queryForMap(
                                    "SELECT authorized,payload FROM ma.tool_command_outbox WHERE"
                                            + " command_id=?",
                                    command.commandId());
                    if ((Boolean) row.get("authorized"))
                        throw new ManagedStateException(
                                "tool_command_conflict", "Tool command is already authorized");
                    var old = parse((String) row.get("payload"), ManagedToolCommand.class);
                    if (!old.scope().equals(command.scope())
                            || !old.task().turnId().equals(command.task().turnId())
                            || !old.task().toolCallId().equals(command.task().toolCallId())
                            || !old.task().name().equals(command.task().name())
                            || !old.task().arguments().equals(command.task().arguments()))
                        throw new ManagedStateException(
                                "tool_command_conflict", "Original tool proposal differs");
                    jdbc.update(
                            "UPDATE ma.tool_command_outbox SET payload=? WHERE command_id=? AND"
                                    + " authorized=FALSE",
                            json(command),
                            command.commandId());
                });
    }

    /** Targeted recovery of this Turn only. No background publisher consumes proposals. */
    public List<ManagedToolCommand> pending(ManagedScope scope, String turn) {
        return jdbc
                .queryForList(
                        "SELECT payload FROM ma.tool_command_outbox WHERE scope_key=? AND"
                                + " kind='execute' AND authorized=FALSE",
                        String.class,
                        scope.messageGroup())
                .stream()
                .map(value -> parse(value, ManagedToolCommand.class))
                .filter(command -> command.task().turnId().equals(turn))
                .toList();
    }

    /** Wiring is separate from starting cancellation forwarding, including in deterministic tests. */
    public void configure(ManagedMessageBus bus, ManagedBatchCodec codec) {
        directCodec = codec;
        directBus = bus;
    }

    public ManagedToolTransport(DataSource source, ObjectMapper mapper) {
        confirmation = new ManagedToolCommitGate(source, mapper);
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(10);
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public ManagedToolCommitGate.Decision confirm(ManagedToolCommand command) {
        return confirmation.check(command);
    }

    public void stage(ManagedToolCommand command) {
        if (directBus == null) {
            // Persistence-only callers and explicitly drained pre-upgrade publications.
            stageRecord(command);
            return;
        }
        record(send(command));
    }

    void stageRecord(ManagedToolCommand command) {
        String payload = json(command);
        jdbc.update(
                "INSERT INTO ma.tool_command_outbox(command_id,scope_key,task_id,kind,payload)"
                        + " VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
                command.commandId(),
                command.scope().messageGroup(),
                command.task().id(),
                command.kind(),
                payload);
        String old =
                jdbc.queryForObject(
                        "SELECT payload FROM ma.tool_command_outbox WHERE command_id=?",
                        String.class,
                        command.commandId());
        if (!payload.equals(json(parse(old, ManagedToolCommand.class))))
            throw new ManagedStateException(
                    "tool_command_conflict", "Original tool command differs");
    }

    /** Control forwarding also runs while the original Harness is suspended. */
    public void cancellations(int limit) {
        jdbc.queryForList(
                        "SELECT s.tenant,s.namespace,s.session_id,t.task_id FROM ma.tool_task t"
                            + " JOIN ma.session s ON s.scope_key=t.scope_key WHERE t.result IS NULL"
                            + " AND t.tool_name<>'subagent.call' AND NOT EXISTS(SELECT 1 FROM"
                            + " ma.tool_command_outbox c WHERE c.command_id='cancel_' || t.task_id)"
                            + " AND (s.lifecycle<>'active' OR EXISTS(SELECT 1 FROM ma.input i WHERE"
                            + " i.scope_key=t.scope_key AND i.turn_id=t.turn_id AND i.type='CANCEL'"
                            + " AND i.status<>'rejected')) LIMIT ?",
                        limit)
                .forEach(
                        row -> {
                            var scope =
                                    new ManagedScope(
                                            (String) row.get("tenant"),
                                            (String) row.get("namespace"),
                                            (String) row.get("session_id"));
                            String id = (String) row.get("task_id");
                            var original =
                                    jdbc.queryForList(
                                            "SELECT payload FROM ma.tool_command_outbox WHERE"
                                                + " scope_key=? AND task_id=? AND kind='execute'",
                                            String.class,
                                            scope.messageGroup(),
                                            id);
                            if (!original.isEmpty()) {
                                var execute = parse(original.get(0), ManagedToolCommand.class);
                                stage(
                                        new ManagedToolCommand(
                                                scope,
                                                "cancel_" + id,
                                                "cancel",
                                                execute.task(),
                                                null));
                            }
                        });
    }

    public synchronized void start(ManagedMessageBus bus, ManagedBatchCodec codec) {
        if (controls != null) return;
        configure(bus, codec);
        controls =
                Executors.newSingleThreadScheduledExecutor(
                        task -> {
                            var thread = new Thread(task, "tool-cancellation-forwarding");
                            thread.setDaemon(true);
                            return thread;
                        });
        controls.scheduleWithFixedDelay(
                () -> {
                    try {
                        cancellations(128);
                        // Only cancellation intents are reconciled. Execute is sent by its writer.
                    } catch (RuntimeException failure) {
                        log.warn("Tool cancellation forwarding pending: {}", failure.toString());
                    }
                },
                0,
                200,
                TimeUnit.MILLISECONDS);
    }

    /** Upgrade compatibility only: old Worker notices stage Runtime-owned recovery, never execute it. */
    public boolean reconcileLegacyResume(
            ManagedToolResume notification, ManagedJournalStore journal) {
        return tx.execute(
                ignored -> {
                    var scope = notification.scope();
                    jdbc.queryForObject(
                            "SELECT scope_key FROM ma.session WHERE scope_key=? FOR UPDATE",
                            String.class,
                            scope.messageGroup());
                    var task = journal.tool(scope, notification.taskId()).orElseThrow();
                    if (!task.turnId().equals(notification.turnId())
                            || !task.executionId().equals(notification.executionId()))
                        throw new ManagedStateException(
                                "tool_execution_mismatch", "Resume execution differs");
                    var turn =
                            jdbc.queryForMap(
                                    "SELECT * FROM ma.turn WHERE scope_key=? AND turn_id=?",
                                    scope.messageGroup(),
                                    notification.turnId());
                    if (List.of("succeeded", "failed", "cancelled").contains(turn.get("status")))
                        return true;
                    var finalEvent =
                            jdbc.queryForList(
                                    "SELECT payload FROM ma.tool_event WHERE scope_key=? AND"
                                        + " task_id=? AND source_sequence=? AND applied_at IS NOT"
                                        + " NULL",
                                    String.class,
                                    scope.messageGroup(),
                                    notification.taskId(),
                                    notification.finalSequence());
                    if (finalEvent.isEmpty() || task.result() == null) return false;
                    var event = parse(finalEvent.get(0), ManagedToolEvent.class);
                    if (!"tool.result".equals(event.type()))
                        throw new ManagedStateException(
                                "resume_identity_mismatch", "Resume has no final tool event");
                    var result =
                            mapper.convertValue(
                                    event.data().get("result"), ManagedToolResult.class);
                    if (!notification
                            .resultHash()
                            .equals(ManagedScope.fingerprint(result.result())))
                        throw new ManagedStateException(
                                "tool_result_conflict", "Resume result hash differs");
                    journal.suspensions().resultReady(scope, notification.turnId());
                    // The old consumer may have marked this canonical identity queued while
                    // executing its Worker notice. Requeue it for the Runtime publisher instead.
                    jdbc.update(
                            "UPDATE ma.turn_resume SET delivery_status='pending' WHERE scope_key=?"
                                    + " AND turn_id=? AND delivery_status='queued' AND EXISTS"
                                    + " (SELECT 1 FROM ma.turn_suspension p WHERE"
                                    + " p.scope_key=ma.turn_resume.scope_key"
                                    + " AND p.suspension_id=ma.turn_resume.suspension_id"
                                    + " AND p.status='ready')",
                            scope.messageGroup(),
                            notification.turnId());
                    return true;
                });
    }

    public String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot encode tool transport", e);
        }
    }

    private <T> T parse(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot decode tool transport", e);
        }
    }

    @Override
    public synchronized void close() {
        if (controls != null) controls.shutdownNow();
    }
}
