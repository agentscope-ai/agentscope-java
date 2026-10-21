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
import io.agentscope.builder.web.managed.CommittedSessionEventProjector;
import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.managed.service.SessionEventNotifier;
import io.agentscope.core.session.SessionEvent;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Atomic native journal, checkpoint, public projection and tool results. */
public final class ManagedJournalStore implements ManagedToolWorkStore {
    private static final Logger log = LoggerFactory.getLogger(ManagedJournalStore.class);

    public record Head(
            long sequence,
            long epoch,
            String token,
            String commitId,
            ManagedCheckpointObjects.Reference checkpoint,
            long checkpointSequence) {}

    public record Receipt(
            String batchId,
            String hash,
            String outcome,
            String errorCode,
            long sequence,
            long epoch) {}

    public record PublicEvent(
            long sequence, String sourceId, String type, String payload, long createdAt) {}

    public record ToolTask(
            String id,
            String turnId,
            String toolCallId,
            String executionId,
            String name,
            String arguments,
            long checkpointSequence,
            String status,
            String processHandle,
            String result,
            String workerToken) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final ManagedBudgetStore budgets;
    private final ManagedPublicResources resources;
    private final ManagedFiles files;
    private final ManagedArtifacts artifacts;
    private final ManagedCheckpoints checkpoints;
    private final ManagedSuspensions suspensions;
    private final ManagedCheckpointObjects objects;
    private final ManagedToolEvents toolEvents;
    private final ManagedToolTransport transport;
    private final SessionEventNotifier notifications;

    public ManagedJournalStore(
            DataSource source, ObjectMapper mapper, ManagedCheckpointObjects objects) {
        this(source, mapper, null, objects);
    }

    public ManagedJournalStore(
            DataSource source,
            ObjectMapper mapper,
            ManagedBudgetStore budgets,
            ManagedCheckpointObjects objects) {
        this(source, mapper, budgets, objects, new SessionEventNotifier(source, 1000));
    }

    public ManagedJournalStore(
            DataSource source,
            ObjectMapper mapper,
            ManagedBudgetStore budgets,
            ManagedCheckpointObjects objects,
            SessionEventNotifier notifications) {
        this.notifications = Objects.requireNonNull(notifications);
        this.objects = Objects.requireNonNull(objects);
        ManagedEventSchemas.ensureRegistered();
        this.budgets = budgets;
        this.resources = new ManagedPublicResources(source, mapper);
        this.files = new ManagedFiles(source, this);
        this.artifacts = new ManagedArtifacts(source, mapper, this);
        this.checkpoints = new ManagedCheckpoints(source, mapper);
        this.jdbc = new JdbcTemplate(source);
        this.jdbc.setQueryTimeout(10);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.tx.setTimeout(10);
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.toolEvents = new ManagedToolEvents(source, this.mapper);
        this.suspensions = new ManagedSuspensions(jdbc, this.mapper);
        this.transport = new ManagedToolTransport(source, this.mapper);
    }

    public ManagedCheckpointObjects objects() {
        return objects;
    }

    public ManagedToolTransport transport() {
        return transport;
    }

    public ManagedToolEvents toolEvents() {
        return toolEvents;
    }

    public ManagedSuspensions suspensions() {
        return suspensions;
    }

    /** Startup metadata; normal accepted model facts advance the call-local anchor. */
    public String modelAnchor(ManagedScope scope, String turn) {
        return jdbc.queryForObject(
                "SELECT model_anchor FROM ma.turn WHERE scope_key=? AND turn_id=?",
                String.class,
                scope.messageGroup(),
                turn);
    }

    public boolean hasSuspension(ManagedScope scope, String turn) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.turn_suspension WHERE scope_key=? AND turn_id=?",
                        Long.class,
                        scope.messageGroup(),
                        turn)
                > 0;
    }

    public List<String> pendingToolIds(ManagedScope scope, String turn) {
        return jdbc.query(
                "SELECT task_id FROM ma.tool_task WHERE scope_key=? AND turn_id=?"
                        + " AND result IS NULL ORDER BY task_id",
                (row, index) -> row.getString(1),
                scope.messageGroup(),
                turn);
    }

    /** One startup read of frozen resources and the input application watermark. */
    Map<String, Object> checkpointContext(ManagedScope scope, String turn) {
        var row =
                jdbc.queryForMap(
                        "SELECT s.configuration,s.lifecycle_revision,t.applied_input_sequence FROM"
                                + " ma.session s JOIN ma.turn t ON t.scope_key=s.scope_key WHERE"
                                + " s.scope_key=? AND t.turn_id=?",
                        scope.messageGroup(),
                        turn);
        var result = new LinkedHashMap<String, Object>();
        result.put("sessionConfiguration", parse((String) row.get("configuration"), Map.class));
        result.put(
                "configurationHash", ManagedScope.fingerprint((String) row.get("configuration")));
        result.put("lifecycleRevision", row.get("lifecycle_revision"));
        result.put("appliedInputSeq", row.get("applied_input_sequence"));
        return result;
    }

    /** Pending operations plus completed partial results belonging to an unreconciled tool call. */
    List<ToolTask> continuationTools(ManagedScope scope, String turn) {
        return jdbc.query(
                "SELECT p.* FROM ma.tool_task p WHERE p.scope_key=? AND p.turn_id=? AND (p.result"
                    + " IS NULL OR EXISTS (SELECT 1 FROM ma.native_tool_call c WHERE"
                    + " c.scope_key=p.scope_key AND c.turn_id=p.turn_id AND"
                    + " c.tool_call_id=p.native_tool_call_id AND c.status='dispatched')) ORDER BY"
                    + " p.task_id",
                this::readToolTask,
                scope.messageGroup(),
                turn);
    }

    /** Committed outcomes repair Redis delivery while their logical Turn remains active. */
    public List<ManagedToolResult> resultsForDelivery(
            String afterScope, String afterTask, int limit) {
        if (limit < 1 || limit > 256)
            throw new IllegalArgumentException("Invalid result relay limit");
        return jdbc.query(
                "SELECT k.*,s.tenant,s.namespace,s.session_id FROM ma.tool_task k JOIN ma.session s"
                    + " ON s.scope_key=k.scope_key JOIN ma.turn t ON t.scope_key=k.scope_key AND"
                    + " t.turn_id=k.turn_id WHERE k.result IS NOT NULL AND k.status IN"
                    + " ('succeeded','failed','cancelled') AND t.status NOT IN"
                    + " ('succeeded','failed','cancelled') AND (k.scope_key>? OR k.scope_key=? AND"
                    + " k.task_id>?) ORDER BY k.scope_key,k.task_id LIMIT ?",
                (row, index) ->
                        new ManagedToolResult(
                                new ManagedScope(
                                        row.getString("tenant"),
                                        row.getString("namespace"),
                                        row.getString("session_id")),
                                row.getString("task_id"),
                                row.getString("execution_id"),
                                row.getString("status"),
                                row.getString("result")),
                afterScope,
                afterScope,
                afterTask,
                limit);
    }

    public String hash(ManagedRuntimeBatch batch) {
        return ManagedScope.fingerprint(json(batch));
    }

    /** Build immutable commands outside the Session transaction; an unconfirmed send aborts the boundary. */
    public Map<String, ManagedToolTransport.Sent> publishTools(ManagedRuntimeBatch batch) {
        if (batch.toolIntents().stream()
                .allMatch(intent -> "subagent.call".equals(intent.toolName()))) return Map.of();
        objects.verify(batch);
        var sent = new LinkedHashMap<String, ManagedToolTransport.Sent>();
        long checkpoint = head(batch.scope()).checkpointSequence();
        for (var event : batch.events())
            if (List.of("state/checkpoint", "migration/baseline", "state/restored")
                    .contains(event.type())) checkpoint = event.seq();
        for (var intent : batch.toolIntents()) {
            if ("subagent.call".equals(intent.toolName())) continue;
            String id =
                    "tool_"
                            + ManagedScope.fingerprint(
                                    batch.scope().messageGroup(),
                                    batch.turnId(),
                                    intent.toolCallId());
            if (tool(batch.scope(), id).isPresent()) continue;
            var task =
                    new ToolTask(
                            id,
                            batch.turnId(),
                            intent.toolCallId(),
                            id,
                            intent.toolName(),
                            intent.arguments(),
                            checkpoint,
                            "ready",
                            null,
                            null,
                            null);
            var command =
                    new ManagedToolCommand(
                            batch.scope(),
                            "execute_" + id,
                            "execute",
                            task,
                            intent.configuration(),
                            new ManagedToolCommand.Submission(
                                    batch.epoch(), batch.executionToken(), batch.batchId()));
            sent.put(id, transport.send(command));
        }
        return sent;
    }

    /** Fence every new commit under the same Session row lock as epoch changes/admission. */
    public Receipt commit(ManagedRuntimeBatch batch) {
        return commit(batch, null);
    }

    public Receipt commit(ManagedRuntimeBatch batch, Map<String, ManagedToolTransport.Sent> sent) {
        var checkpointContexts = objects.verify(batch);
        String hash = hash(batch);
        return tx.execute(
                ignored -> {
                    var scope = batch.scope();
                    var head = lockHead(scope);
                    var turn =
                            jdbc.queryForMap(
                                    "SELECT * FROM ma.turn WHERE scope_key=? AND turn_id=?",
                                    scope.messageGroup(),
                                    batch.turnId());
                    if (head.epoch() != batch.epoch()
                            || !batch.executionToken().equals(head.token())
                            || !batch.turnId().equals(currentTurn(scope)))
                        throw conflict(
                                "stale_execution", "Execution identity is no longer current");
                    if (head.sequence() != batch.expectedSequence()
                            && !(batch.events().isEmpty()
                                    && batch.terminalStatus() == null
                                    && batch.expectedSequence() < head.sequence()))
                        throw conflict(
                                "native_sequence_conflict", "Native committed prefix differs");
                    long sequence = head.sequence();
                    String executionStatus = (String) turn.get("status");
                    ManagedCheckpointObjects.Reference checkpoint = null;
                    Map<String, Object> checkpointContext = Map.of();
                    long checkpointSequence = head.checkpointSequence();
                    for (var event : batch.events()) {
                        nativeToolFact(scope, batch.turnId(), event);
                        if ("model/dispatch".equals(event.type())) {
                            String modelCall = String.valueOf(event.data().get("modelCallId"));
                            ManagedScope.requireText(modelCall, "model call id");
                            jdbc.update(
                                    "UPDATE ma.turn SET model_anchor=? WHERE scope_key=? AND"
                                            + " turn_id=?",
                                    modelCall,
                                    scope.messageGroup(),
                                    batch.turnId());
                        }
                        if (budgets != null) budgets.committed(scope, event);
                        sequence = event.seq();
                        jdbc.update(
                                "INSERT INTO ma.native_event(scope_key,sequence,event_id,payload)"
                                        + " VALUES(?,?,?,?)",
                                scope.messageGroup(),
                                sequence,
                                event.eventId(),
                                json(event));
                        if (List.of("state/checkpoint", "migration/baseline", "state/restored")
                                .contains(event.type())) {
                            checkpoint = objects.reference(scope, event);
                            checkpointContext = checkpointContexts.get(event.seq());
                            checkpointSequence = sequence;
                            checkpoints.capture(scope, event);
                        }
                        if ("interaction/requested".equals(event.type())) {
                            String actionId = String.valueOf(event.data().get("requestId"));
                            var old = action(scope, batch.turnId(), actionId);
                            if (old.isEmpty())
                                jdbc.update(
                                        "INSERT INTO"
                                            + " ma.action(scope_key,turn_id,action_id,kind,payload)"
                                            + " VALUES(?,?,?,?,?)",
                                        scope.messageGroup(),
                                        batch.turnId(),
                                        actionId,
                                        String.valueOf(event.data().get("kind")),
                                        event.payloadJson());
                        } else if ("interaction/resolved".equals(event.type())) {
                            jdbc.update(
                                    "UPDATE ma.action SET status='answered' WHERE scope_key=? AND"
                                            + " turn_id=? AND action_id=?",
                                    scope.messageGroup(),
                                    batch.turnId(),
                                    String.valueOf(event.data().get("requestId")));
                        }
                        for (var projected : CommittedSessionEventProjector.projectAll(event))
                            appendPublic(
                                    scope,
                                    CommittedSessionEventProjector.projectionId(event, projected),
                                    projected.type(),
                                    json(files.publicPayload(scope, projected.payload())));
                        if (List.of("turn/start", "turn/resumed", "turn/suspended")
                                .contains(event.type())) {
                            String status =
                                    "turn/suspended".equals(event.type()) ? "waiting" : "running";
                            jdbc.update(
                                    "UPDATE ma.turn SET status=? WHERE scope_key=? AND turn_id=?",
                                    status,
                                    scope.messageGroup(),
                                    batch.turnId());
                            executionStatus = status;
                            appendPublic(
                                    scope,
                                    event.eventId() + ":status",
                                    "waiting".equals(status)
                                            ? "turn.requires_action"
                                            : "turn.running",
                                    json(Map.of("turn_id", batch.turnId(), "status", status)));
                        }
                    }
                    if ((!batch.toolIntents().isEmpty()
                                    && checkpoint == null
                                    && head.checkpoint() == null)
                            || (batch.inputReceipts().stream()
                                            .anyMatch(input -> "applied".equals(input.status()))
                                    && checkpoint == null))
                        throw conflict(
                                "checkpoint_required",
                                "Effects and applied inputs need an atomic checkpoint");
                    if (batch.events().stream()
                            .anyMatch(event -> "turn/output".equals(event.type())))
                        ManagedSessionProtocol.sealOutput(jdbc, scope, batch.turnId());
                    for (var intent : batch.toolIntents()) {
                        String id =
                                "tool_"
                                        + ManagedScope.fingerprint(
                                                scope.messageGroup(),
                                                batch.turnId(),
                                                intent.toolCallId());
                        var old = tool(scope, id);
                        if (old.isPresent()) {
                            if (!old.get().name().equals(intent.toolName())
                                    || !old.get().arguments().equals(intent.arguments()))
                                throw conflict(
                                        "tool_content_conflict", "Tool call content differs");
                        } else {
                            long pending =
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM ma.tool_task WHERE scope_key=?"
                                                    + " AND result IS NULL",
                                            Long.class,
                                            scope.messageGroup());
                            if (pending >= 128)
                                throw conflict(
                                        "admission_limited",
                                        "Pending Hands ToolTask capacity exceeded");
                            jdbc.update(
                                    "INSERT INTO"
                                        + " ma.tool_task(scope_key,task_id,turn_id,tool_call_id,execution_id,tool_name,arguments,checkpoint_sequence,native_tool_call_id)"
                                        + " VALUES(?,?,?,?,?,?,?,?,?)",
                                    scope.messageGroup(),
                                    id,
                                    batch.turnId(),
                                    intent.toolCallId(),
                                    id,
                                    intent.toolName(),
                                    intent.arguments(),
                                    checkpointSequence,
                                    parse(intent.arguments(), Map.class)
                                            .get("native_tool_call_id"));
                            if (ManagedMcpBatch.OPERATION.equals(intent.toolName())) {
                                int ordinal = 0;
                                for (var call :
                                        ManagedMcpBatch.manifest(intent.arguments()).calls()) {
                                    jdbc.update(
                                            "INSERT INTO"
                                                + " ma.mcp_batch_call(scope_key,turn_id,task_id,parent_task_id,native_tool_call_id,ordinal)"
                                                + " VALUES(?,?,?,?,?,?)",
                                            scope.messageGroup(),
                                            batch.turnId(),
                                            ManagedMcpBatch.childTaskId(id, call.callId()),
                                            id,
                                            call.callId(),
                                            ordinal++);
                                }
                            }
                            if (!"subagent.call".equals(intent.toolName())) {
                                if (sent == null) {
                                    // Persistence-only API, also used to construct pre-upgrade
                                    // fixtures.
                                    transport.stageRecord(
                                            new ManagedToolCommand(
                                                    scope,
                                                    "execute_" + id,
                                                    "execute",
                                                    tool(scope, id).orElseThrow(),
                                                    intent.configuration()));
                                } else {
                                    var publication = sent.get(id);
                                    if (publication == null)
                                        throw conflict(
                                                "tool_publication_missing",
                                                "Tool has no Broker admission");
                                    var command = publication.command();
                                    var published = command.task();
                                    var submission = command.submission();
                                    if (!scope.equals(command.scope())
                                            || !id.equals(published.id())
                                            || !batch.turnId().equals(published.turnId())
                                            || !intent.toolCallId().equals(published.toolCallId())
                                            || !intent.toolName().equals(published.name())
                                            || !intent.arguments().equals(published.arguments())
                                            || checkpointSequence != published.checkpointSequence()
                                            || !Objects.equals(
                                                    intent.configuration(), command.configuration())
                                            || submission == null
                                            || submission.epoch() != batch.epoch()
                                            || !batch.executionToken()
                                                    .equals(submission.executionToken())
                                            || !batch.batchId().equals(submission.batchId()))
                                        throw conflict(
                                                "tool_publication_mismatch",
                                                "Published tool differs from the committing"
                                                        + " boundary");
                                    transport.record(publication);
                                }
                            }
                        }
                    }
                    for (var input : batch.inputReceipts()) {
                        var old =
                                jdbc.queryForMap(
                                        "SELECT status,type,action_id,rejection_reason FROM"
                                                + " ma.input WHERE scope_key=? AND turn_id=? AND"
                                                + " input_id=?",
                                        scope.messageGroup(),
                                        batch.turnId(),
                                        input.inputId());
                        if (!"accepted".equals(old.get("status"))
                                && (!input.status().equals(old.get("status"))
                                        || !Objects.equals(
                                                input.reason(), old.get("rejection_reason"))))
                            throw conflict(
                                    "input_receipt_conflict", "Input already settled differently");
                        jdbc.update(
                                "UPDATE ma.input SET"
                                        + " status=?,rejection_reason=?,delivery_status='delivered'"
                                        + " WHERE scope_key=? AND input_id=?",
                                input.status(),
                                input.reason(),
                                scope.messageGroup(),
                                input.inputId());
                        if ("REPLY".equals(old.get("type")) && "applied".equals(input.status()))
                            jdbc.update(
                                    "UPDATE ma.action SET status='answered' WHERE scope_key=? AND"
                                            + " turn_id=? AND action_id=?",
                                    scope.messageGroup(),
                                    batch.turnId(),
                                    old.get("action_id"));
                        appendPublic(
                                scope,
                                input.inputId() + ":settled",
                                "input." + input.status(),
                                json(
                                        input.reason() == null
                                                ? Map.of(
                                                        "input_id",
                                                        input.inputId(),
                                                        "status",
                                                        input.status())
                                                : Map.of(
                                                        "input_id",
                                                        input.inputId(),
                                                        "status",
                                                        input.status(),
                                                        "reason",
                                                        input.reason())));
                    }
                    // A restored attempt can only settle framework work, without opening another
                    // Core run. Its durable handoff still completes a Service execution interval.
                    if ((batch.suspension() != null || batch.terminalStatus() != null)
                            && "preparing".equals(executionStatus))
                        appendPublic(
                                scope,
                                batch.batchId() + ":running",
                                "turn.running",
                                json(Map.of("turn_id", batch.turnId(), "status", "running")));
                    if (batch.suspension() != null) {
                        suspensions.save(
                                batch,
                                objects.encodeReference(checkpoint),
                                checkpointSequence,
                                checkpointContext);
                        var pendingCalls =
                                jdbc.query(
                                        "SELECT native_tool_call_id FROM ma.tool_task WHERE"
                                            + " scope_key=? AND turn_id=? AND result IS NULL AND"
                                            + " status<>'unknown' AND native_tool_call_id NOT LIKE"
                                            + " 'framework_%' AND tool_name<>'mcp.batch' UNION"
                                            + " SELECT c.native_tool_call_id FROM ma.mcp_batch_call"
                                            + " c JOIN ma.tool_task t ON t.scope_key=c.scope_key"
                                            + " AND t.task_id=c.parent_task_id WHERE c.scope_key=?"
                                            + " AND c.turn_id=? AND t.result IS NULL AND"
                                            + " t.status<>'unknown'",
                                        (row, index) -> row.getString(1),
                                        scope.messageGroup(),
                                        batch.turnId(),
                                        scope.messageGroup(),
                                        batch.turnId());
                        if (!batch.suspension().waitingForInput()
                                || !"waiting".equals(executionStatus))
                            appendPublic(
                                    scope,
                                    batch.suspension().id(),
                                    batch.suspension().waitingForInput()
                                            ? "turn.requires_action"
                                            : "turn.waiting_tool",
                                    json(
                                            Map.of(
                                                    "turn_id",
                                                    batch.turnId(),
                                                    "status",
                                                    batch.suspension().waitingForInput()
                                                            ? "waiting"
                                                            : "waiting_tool",
                                                    batch.suspension().waitingForInput()
                                                            ? "pending_action_ids"
                                                            : "pending_tool_call_ids",
                                                    batch.suspension().waitingForInput()
                                                            ? batch.suspension().actionIds()
                                                            : pendingCalls)));
                    }
                    if (batch.terminalStatus() != null) {
                        if (Boolean.TRUE.equals(turn.get("inputs_open"))
                                || jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM ma.input WHERE scope_key=?"
                                                        + " AND turn_id=? AND status='accepted'",
                                                Long.class,
                                                scope.messageGroup(),
                                                batch.turnId())
                                        != 0)
                            throw conflict(
                                    "inputs_unsettled",
                                    "Terminal boundary has accepted unsettled inputs");
                        if (checkpoint == null && head.checkpoint() == null)
                            throw conflict(
                                    "checkpoint_required", "Terminal boundary needs durable state");
                        if (jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM ma.native_tool_call WHERE scope_key=?"
                                                + " AND turn_id=? AND status='dispatched'",
                                        Long.class,
                                        scope.messageGroup(),
                                        batch.turnId())
                                != 0)
                            throw conflict(
                                    "tool_reconciliation_required",
                                    "Native tool outcomes remain uncertain");
                        if (jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM ma.tool_task WHERE scope_key=? AND"
                                                + " turn_id=? AND status NOT IN"
                                                + " ('succeeded','failed','cancelled')",
                                        Long.class,
                                        scope.messageGroup(),
                                        batch.turnId())
                                != 0)
                            throw conflict(
                                    "tools_unsettled",
                                    "Tool execution has not stopped or its outcome is unknown");
                        jdbc.update(
                                "UPDATE ma.action SET status='closed' WHERE scope_key=? AND"
                                        + " turn_id=? AND status='open'",
                                scope.messageGroup(),
                                batch.turnId());
                        jdbc.update(
                                "UPDATE ma.turn SET status=? WHERE scope_key=? AND turn_id=?",
                                batch.terminalStatus(),
                                scope.messageGroup(),
                                batch.turnId());
                        // The immutable terminal and proactive ACK intent become visible together.
                        // Receipt capture takes the same Session lock, including if it arrives
                        // late.
                        jdbc.update(
                                "UPDATE ma.turn_ack_receipt SET ready=TRUE,next_attempt_at=? WHERE"
                                        + " scope_key=? AND turn_id=?",
                                System.currentTimeMillis(),
                                scope.messageGroup(),
                                batch.turnId());
                        jdbc.update(
                                "UPDATE ma.session SET settled_sequence=?,current_turn_id=NULL"
                                        + " WHERE scope_key=?",
                                turn.get("publication_sequence"),
                                scope.messageGroup());
                        appendPublic(
                                scope,
                                batch.turnId() + ":terminal",
                                "turn." + publicTurnStatus(batch.terminalStatus()),
                                json(
                                        Map.of(
                                                "turn_id",
                                                batch.turnId(),
                                                "status",
                                                publicTurnStatus(batch.terminalStatus()))));
                    }
                    jdbc.update(
                            "UPDATE ma.session SET"
                                + " native_sequence=?,native_commit_id=?,checkpoint=COALESCE(?,checkpoint),checkpoint_sequence=?"
                                + " WHERE scope_key=?",
                            sequence,
                            batch.batchId(),
                            checkpoint == null ? null : objects.encodeReference(checkpoint),
                            checkpointSequence,
                            scope.messageGroup());
                    return new Receipt(
                            batch.batchId(), hash, "committed", null, sequence, batch.epoch());
                });
    }

    public boolean cancelRequested(ManagedScope scope, String turn) {
        var local =
                jdbc.queryForMap(
                        "SELECT (s.lifecycle<>'active' OR EXISTS (SELECT 1 FROM ma.input i WHERE"
                            + " i.scope_key=s.scope_key AND i.turn_id=? AND i.type='CANCEL' AND"
                            + " i.status<>'rejected')) AS cancelled, EXISTS (SELECT 1 FROM"
                            + " ma.subagent_call c WHERE c.child_scope_key=s.scope_key AND"
                            + " c.child_turn_id=?) AS child FROM ma.session s WHERE s.scope_key=?",
                        turn,
                        turn,
                        scope.messageGroup());
        if (Boolean.TRUE.equals(local.get("cancelled"))) return true;
        // Root Turns have no ancestor tree. Avoid rebuilding a recursive query on every tool
        // poll and control tick; the immutable child linkage is already indexed by scope/Turn.
        if (!Boolean.TRUE.equals(local.get("child"))) return false;
        return jdbc.queryForObject(
                        "WITH RECURSIVE lineage(scope_key,turn_id,depth) AS ( SELECT"
                            + " scope_key,CAST(? AS VARCHAR(256)),0 FROM ma.session WHERE"
                            + " scope_key=? UNION ALL SELECT"
                            + " c.parent_scope_key,c.parent_turn_id,l.depth+1 FROM ma.subagent_call"
                            + " c JOIN lineage l ON c.child_scope_key=l.scope_key AND"
                            + " c.child_turn_id=l.turn_id WHERE l.depth<16) SELECT COUNT(*) FROM"
                            + " lineage l JOIN ma.session s ON s.scope_key=l.scope_key WHERE"
                            + " s.lifecycle<>'active' OR EXISTS (SELECT 1 FROM ma.input i WHERE"
                            + " i.scope_key=l.scope_key AND i.turn_id=l.turn_id AND i.type='CANCEL'"
                            + " AND i.status<>'rejected')",
                        Long.class,
                        turn,
                        scope.messageGroup())
                > 0;
    }

    public boolean toolsUnsettled(ManagedScope scope, String turn) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.tool_task WHERE scope_key=? AND turn_id=? AND"
                                + " result IS NULL",
                        Long.class,
                        scope.messageGroup(),
                        turn)
                > 0;
    }

    public long publicHead(ManagedScope scope) {
        return jdbc.queryForObject(
                "SELECT public_sequence FROM ma.session WHERE scope_key=?",
                Long.class,
                scope.messageGroup());
    }

    public Head head(ManagedScope scope) {
        return readHead(scope);
    }

    public ManagedPublicResources resources() {
        return resources;
    }

    public ManagedFiles files() {
        return files;
    }

    public ManagedArtifacts artifacts() {
        return artifacts;
    }

    /** Resource facts are written by Session Portal; execution facts require the fenced Brain commit. */
    public void resourceEvent(
            ManagedScope scope, String id, String type, Map<String, Object> data) {
        if (!List.of(
                        "session.created",
                        "session.updated",
                        "session.archived",
                        "session.deleted",
                        "session.restored",
                        "file.created",
                        "artifact.published",
                        "artifact.deleted",
                        "checkpoint.restored",
                        "session.forked")
                .contains(type))
            throw new IllegalArgumentException("Invalid Session resource event");
        Runnable write =
                () -> {
                    lockHead(scope);
                    appendPublic(scope, id, type, json(data));
                };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) write.run();
        else tx.executeWithoutResult(ignored -> write.run());
    }

    public static String publicTurnStatus(String status) {
        return "succeeded".equals(status)
                ? "completed"
                : "waiting_input".equals(status) ? "waiting" : status;
    }

    public ManagedCheckpoints checkpoints() {
        return checkpoints;
    }

    public List<SessionEvent> nativeEvents(ManagedScope scope, long after, int limit) {
        return nativeManifests(scope, after, limit).stream()
                .map(event -> objects.hydrate(scope, event))
                .toList();
    }

    /** Metadata-only startup replay; checkpoint state bytes remain in private objects. */
    List<SessionEvent> nativeManifests(ManagedScope scope, long after, int limit) {
        bounds(after, limit);
        return jdbc.query(
                "SELECT payload FROM ma.native_event WHERE scope_key=? AND sequence>? ORDER BY"
                        + " sequence LIMIT ?",
                (row, index) -> parse(row.getString(1), SessionEvent.class),
                scope.messageGroup(),
                after,
                limit);
    }

    public List<PublicEvent> publicEvents(ManagedScope scope, long after, int limit) {
        bounds(after, limit);
        return jdbc.query(
                "SELECT * FROM ma.public_event WHERE scope_key=? AND sequence>? ORDER BY sequence"
                        + " LIMIT ?",
                (row, index) ->
                        new PublicEvent(
                                row.getLong("sequence"),
                                row.getString("source_id"),
                                row.getString("type"),
                                row.getString("payload"),
                                row.getLong("created_at")),
                scope.messageGroup(),
                after,
                limit);
    }

    public record PublicEventReference(
            long sequence, String sourceId, String type, long createdAt) {}

    /** Notifications need only the committed identity, never the event's potentially large content. */
    public List<PublicEventReference> publicEventReferences(
            ManagedScope scope, long after, long through, int limit) {
        bounds(after, limit);
        if (through < after) throw new IllegalArgumentException("Invalid public event boundary");
        return jdbc.query(
                "SELECT sequence,source_id,type,created_at FROM ma.public_event WHERE scope_key=?"
                        + " AND sequence>? AND sequence<=? ORDER BY sequence LIMIT ?",
                (row, index) ->
                        new PublicEventReference(
                                row.getLong("sequence"),
                                row.getString("source_id"),
                                row.getString("type"),
                                row.getLong("created_at")),
                scope.messageGroup(),
                after,
                through,
                limit);
    }

    public Optional<ToolTask> tool(ManagedScope scope, String taskId) {
        return jdbc
                .query(
                        "SELECT * FROM ma.tool_task WHERE scope_key=? AND task_id=?",
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

    public List<ToolTask> toolsForNativeCall(ManagedScope scope, String turn, String call) {
        return jdbc.query(
                "SELECT * FROM ma.tool_task WHERE scope_key=? AND turn_id=? AND"
                        + " native_tool_call_id=? ORDER BY task_id",
                this::readToolTask,
                scope.messageGroup(),
                turn,
                call);
    }

    List<ToolTask> mcpBatches(ManagedScope scope, String turn) {
        return jdbc.query(
                "SELECT * FROM ma.tool_task WHERE scope_key=? AND turn_id=? AND"
                        + " tool_name='mcp.batch' ORDER BY task_id",
                this::readToolTask,
                scope.messageGroup(),
                turn);
    }

    public Optional<ToolTask> mcpBatchForCall(ManagedScope scope, String turn, String call) {
        return jdbc
                .query(
                        "SELECT t.* FROM ma.tool_task t JOIN ma.mcp_batch_call c ON"
                                + " c.scope_key=t.scope_key AND c.parent_task_id=t.task_id WHERE"
                                + " c.scope_key=? AND c.turn_id=? AND c.native_tool_call_id=?",
                        this::readToolTask,
                        scope.messageGroup(),
                        turn,
                        call)
                .stream()
                .findFirst();
    }

    public record PublicTools(List<Map<String, Object>> data, String next, boolean hasMore) {}

    /** Excludes maintenance RPCs and all raw arguments, results and worker tokens. */
    public PublicTools publicTools(ManagedScope scope, String turn, String after, int limit) {
        if (limit < 1 || limit > 256) throw new IllegalArgumentException("Invalid tool page size");
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM (SELECT task_id,status,native_tool_call_id,CASE WHEN result"
                            + " IS NULL THEN FALSE ELSE TRUE END AS has_result FROM ma.tool_task"
                            + " WHERE scope_key=? AND turn_id=? AND tool_name<>'mcp.batch' UNION"
                            + " ALL SELECT task_id,status,native_tool_call_id,has_result FROM"
                            + " ma.mcp_batch_call WHERE scope_key=? AND turn_id=?) calls WHERE"
                            + " task_id>? ORDER BY task_id LIMIT ?",
                        scope.messageGroup(),
                        turn,
                        scope.messageGroup(),
                        turn,
                        after == null ? "" : after,
                        limit + 1);
        boolean more = rows.size() > limit;
        var selected = rows.subList(0, Math.min(limit, rows.size()));
        var data = new ArrayList<Map<String, Object>>();
        for (var row : selected) {
            if (!(row.get("native_tool_call_id") instanceof String nativeId)) continue;
            var tools =
                    jdbc.queryForList(
                            "SELECT data FROM ma.public_resource WHERE scope_key=? AND kind='tools'"
                                    + " AND resource_key=?",
                            scope.messageGroup(),
                            turn + ":" + nativeId);
            if (tools.isEmpty()) continue;
            var event = parse((String) tools.get(0).get("data"), Map.class);
            var tool = event.get("data") instanceof Map<?, ?> details ? details : Map.of();
            var view = new LinkedHashMap<String, Object>();
            view.put("task_id", row.get("task_id"));
            view.put("turn_id", turn);
            view.put("tool_call_id", nativeId);
            view.put("tool_name", tool.get("name") instanceof String name ? name : "tool");
            view.put("status", row.get("status"));
            view.put(
                    "result_status",
                    Boolean.TRUE.equals(row.get("has_result")) ? "committed" : "pending");
            data.add(view);
        }
        return new PublicTools(
                List.copyOf(data),
                more ? (String) selected.get(selected.size() - 1).get("task_id") : null,
                more);
    }

    public record Action(String id, String kind, String status, String payload) {}

    public Optional<Action> action(ManagedScope scope, String turn, String id) {
        return jdbc
                .query(
                        "SELECT * FROM ma.action WHERE scope_key=? AND turn_id=? AND action_id=?",
                        (row, index) ->
                                new Action(
                                        row.getString("action_id"),
                                        row.getString("kind"),
                                        row.getString("status"),
                                        row.getString("payload")),
                        scope.messageGroup(),
                        turn,
                        id)
                .stream()
                .findFirst();
    }

    public record ToolClaim(ToolTask task, boolean firstLaunch) {}

    public record ToolReference(ManagedScope scope, String taskId) {}

    /** Short durable launch journal, not a DB execution lease. Recovery must query the same executionId. */
    public ToolClaim claimTool(ManagedScope scope, String id, String token) {
        ManagedScope.requireText(token, "tool worker token");
        return tx.execute(
                ignored -> {
                    lockHead(scope);
                    var task =
                            tool(scope, id)
                                    .orElseThrow(
                                            () ->
                                                    conflict(
                                                            "tool_not_found",
                                                            "Tool task does not exist"));
                    boolean first = "ready".equals(task.status());
                    if (List.of("succeeded", "failed", "cancelled").contains(task.status())
                            || ("result_pending".equals(task.status())
                                    && !toolEvents.unfinished(scope, id)))
                        return new ToolClaim(task, false);
                    jdbc.update(
                            "UPDATE ma.tool_task SET"
                                    + " worker_token=?,last_dispatched_at=?,status=CASE WHEN"
                                    + " status='ready' THEN 'starting' ELSE status END WHERE"
                                    + " scope_key=? AND task_id=?",
                            token,
                            System.currentTimeMillis(),
                            scope.messageGroup(),
                            id);
                    return new ToolClaim(tool(scope, id).orElseThrow(), first);
                });
    }

    public ToolTask recordToolHandle(ManagedScope scope, String id, String token, String handle) {
        return tx.execute(
                ignored -> {
                    lockHead(scope);
                    var task =
                            tool(scope, id)
                                    .orElseThrow(
                                            () ->
                                                    conflict(
                                                            "tool_not_found",
                                                            "Tool task does not exist"));
                    if (!token.equals(task.workerToken()))
                        throw conflict("stale_hands", "Tool worker changed");
                    if (List.of("succeeded", "failed", "cancelled", "result_pending")
                            .contains(task.status())) return task;
                    jdbc.update(
                            "UPDATE ma.tool_task SET status='running',process_handle=? WHERE"
                                    + " scope_key=? AND task_id=?",
                            handle,
                            scope.messageGroup(),
                            id);
                    return tool(scope, id).orElseThrow();
                });
    }

    public void markToolUnknown(ManagedScope scope, String id, String token) {
        jdbc.update(
                "UPDATE ma.tool_task SET status='unknown' WHERE scope_key=? AND task_id=? AND"
                        + " worker_token=? AND status IN ('starting','running','unknown')",
                scope.messageGroup(),
                id,
                token);
    }

    /** Scheduling fairness only: this does not claim, launch or change a tool's execution state. */
    public void deferToolDispatch(ManagedScope scope, String taskId) {
        jdbc.update(
                "UPDATE ma.tool_task SET last_dispatched_at=? WHERE scope_key=? AND task_id=? AND"
                        + " result IS NULL",
                System.currentTimeMillis(),
                scope.messageGroup(),
                taskId);
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
                "SELECT s.tenant,s.namespace,s.session_id,t.task_id FROM ma.tool_task t JOIN"
                        + " ma.session s ON s.scope_key=t.scope_key WHERE t.status IN"
                        + " ('ready','starting','running','unknown','result_pending') AND"
                        + " (t.status<>'result_pending' OR EXISTS (SELECT 1 FROM ma.tool_stream ts"
                        + " WHERE ts.scope_key=t.scope_key AND ts.task_id=t.task_id AND"
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
                    lockHead(scope);
                    var existing =
                            jdbc.queryForList(
                                    "SELECT result_hash,committed_at FROM ma.tool_result_outbox"
                                            + " WHERE scope_key=? AND task_id=?",
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
                                + " ma.tool_result_outbox(scope_key,task_id,execution_id,result_hash,payload)"
                                + " VALUES(?,?,?,?,?)",
                            scope.messageGroup(),
                            result.taskId(),
                            result.executionId(),
                            hash,
                            payload);
                    jdbc.update(
                            "UPDATE ma.tool_task SET status='result_pending' WHERE scope_key=? AND"
                                    + " task_id=?",
                            scope.messageGroup(),
                            result.taskId());
                    return true;
                });
    }

    public Optional<ManagedToolResult> stagedToolResult(ManagedScope scope, String task) {
        return jdbc
                .query(
                        "SELECT payload,result_hash FROM ma.tool_result_outbox WHERE scope_key=?"
                                + " AND task_id=?",
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
                "SELECT s.tenant,s.namespace,s.session_id,o.task_id FROM ma.tool_result_outbox o"
                    + " JOIN ma.session s ON s.scope_key=o.scope_key WHERE o.committed_at IS NULL"
                    + " AND o.next_attempt_at<=? ORDER BY o.next_attempt_at,o.scope_key,o.task_id"
                    + " LIMIT ?",
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
                "UPDATE ma.tool_result_outbox SET next_attempt_at=? WHERE scope_key=? AND task_id=?"
                        + " AND committed_at IS NULL",
                nextAttempt,
                result.scope().messageGroup(),
                result.taskId());
    }

    /** Tool result facts are fenced by stable external execution identity, independent of Brain epoch. */
    public Receipt commitToolResult(ManagedToolResult result) {
        return commitToolResult(result, false);
    }

    private Receipt commitToolResult(ManagedToolResult result, boolean received) {
        String hash = ManagedScope.fingerprint(json(result));
        return tx.execute(
                ignored -> {
                    var scope = result.scope();
                    var head = lockHead(scope);
                    var task =
                            tool(scope, result.taskId())
                                    .orElseThrow(
                                            () ->
                                                    conflict(
                                                            "tool_not_found",
                                                            "Tool task does not exist"));
                    if (!task.executionId().equals(result.executionId()))
                        throw conflict(
                                "tool_execution_mismatch", "Result belongs to another execution");
                    String acceptedHash =
                            jdbc.queryForObject(
                                    "SELECT result_hash FROM ma.tool_task WHERE scope_key=? AND"
                                            + " task_id=?",
                                    String.class,
                                    scope.messageGroup(),
                                    result.taskId());
                    if (acceptedHash != null) {
                        if (!hash.equals(acceptedHash))
                            throw conflict("tool_result_conflict", "Tool outcome differs");
                        return new Receipt(
                                result.batchId(),
                                hash,
                                "committed",
                                null,
                                head.sequence(),
                                head.epoch());
                    }
                    var staged =
                            jdbc.queryForList(
                                    "SELECT result_hash FROM ma.tool_result_outbox WHERE"
                                            + " scope_key=? AND task_id=?",
                                    scope.messageGroup(),
                                    result.taskId());
                    if (!received && staged.size() != 1)
                        throw conflict(
                                "tool_result_not_staged",
                                "Tool event has no durable confirmed outcome");
                    if (!staged.isEmpty() && !hash.equals(staged.get(0).get("result_hash")))
                        throw conflict(
                                "tool_result_conflict",
                                "Tool event differs from its staged outcome");
                    var output = toolEvents.cursor(scope, result.taskId());
                    if (output.isPresent()
                            && (output.get().finalSeq() == null
                                    || output.get().consumedSeq() < output.get().finalSeq()))
                        throw conflict(
                                "tool_output_pending",
                                "Tool result has not consumed its complete source prefix");
                    if (!received && "ready".equals(task.status()))
                        throw conflict(
                                "tool_execution_mismatch",
                                "Result has no matching launched execution");
                    if (List.of("succeeded", "failed", "cancelled").contains(task.status()))
                        throw conflict(
                                "tool_result_conflict",
                                "Tool was already settled by another decision");
                    String committedResult = result.result();
                    String committedStatus = result.status();
                    if (ManagedArtifactTool.OPERATION.equals(task.name())
                            && "succeeded".equals(result.status())) {
                        var artifact = artifacts.capture(scope, task, result.result());
                        committedResult = json(artifact);
                        if (artifact.containsKey("error")) committedStatus = "failed";
                    }
                    if (ManagedMcpBatch.OPERATION.equals(task.name())) {
                        var manifest = ManagedMcpBatch.manifest(task.arguments());
                        var aggregate = ManagedMcpBatch.result(committedResult);
                        if (manifest.calls().size() != aggregate.calls().size())
                            throw conflict(
                                    "mcp_batch_result_mismatch",
                                    "MCP batch completion omits calls");
                        for (var call : manifest.calls()) {
                            var outcome = aggregate.require(call.callId());
                            if (jdbc.update(
                                            "UPDATE ma.mcp_batch_call SET status=?,has_result=TRUE"
                                                    + " WHERE scope_key=? AND parent_task_id=? AND"
                                                    + " native_tool_call_id=?",
                                            outcome.status(),
                                            scope.messageGroup(),
                                            task.id(),
                                            call.callId())
                                    != 1)
                                throw conflict(
                                        "mcp_batch_result_mismatch",
                                        "MCP batch member identity is missing");
                        }
                    }
                    jdbc.update(
                            "UPDATE ma.tool_task SET status=?,result=?,result_hash=? WHERE"
                                    + " scope_key=? AND task_id=?",
                            committedStatus,
                            committedResult,
                            hash,
                            scope.messageGroup(),
                            result.taskId());
                    jdbc.update(
                            "UPDATE ma.tool_result_outbox SET committed_at=? WHERE scope_key=? AND"
                                    + " task_id=?",
                            System.currentTimeMillis(),
                            scope.messageGroup(),
                            result.taskId());
                    suspensions.resultReady(scope, task.turnId());
                    var arguments = parse(task.arguments(), Map.class);
                    boolean privateFramework =
                            arguments.get("native_tool_call_id") instanceof String call
                                    && call.startsWith("framework_");
                    if (!privateFramework) {
                        var operation = new LinkedHashMap<String, Object>();
                        operation.put("turn_id", task.turnId());
                        if (arguments.get("native_tool_call_id") instanceof String nativeCall
                                && !nativeCall.isBlank()) operation.put("tool_call_id", nativeCall);
                        operation.put("operation_tool_call_id", task.toolCallId());
                        operation.put("task_id", task.id());
                        operation.put("operation_execution_id", task.executionId());
                        operation.put("name", task.name());
                        operation.put("status", committedStatus);
                        output.ifPresent(
                                cursor -> {
                                    operation.put("last_chunk_seq", cursor.chunkSeq());
                                    operation.put("output_offset", cursor.offset());
                                    operation.put(
                                            "log_status",
                                            cursor.hasGap()
                                                    ? "gapped"
                                                    : "none".equals(cursor.mode())
                                                            ? "not_supported"
                                                            : "complete");
                                });
                        operation.put(
                                "source", Map.of("kind", "tool", "event_id", result.batchId()));
                        appendPublic(
                                scope,
                                result.batchId(),
                                "tool.operation.completed",
                                json(operation));
                    }
                    return new Receipt(
                            result.batchId(),
                            hash,
                            "committed",
                            null,
                            head.sequence(),
                            head.epoch());
                });
    }

    public record ToolEventsCommit(Receipt receipt, List<ManagedToolResult> results) {}

    /** Tool facts have independent source sequences; only a covered final result wakes Brain. */
    public ToolEventsCommit commitToolEvent(ManagedToolEvent event) {
        var confirmed = new ArrayList<ManagedToolResult>();
        var confirmedTasks = new ArrayList<Map.Entry<ToolTask, String>>();
        toolEvents.receive(
                event,
                next -> {
                    if ("tool.result".equals(next.type())) {
                        var result =
                                mapper.convertValue(
                                        next.data().get("result"), ManagedToolResult.class);
                        if (!next.scope().equals(result.scope())
                                || !next.taskId().equals(result.taskId())
                                || !next.executionId().equals(result.executionId()))
                            throw conflict(
                                    "tool_execution_mismatch",
                                    "Final tool source identity differs");
                        commitToolResult(result, true);
                        var task = tool(result.scope(), result.taskId()).orElseThrow();
                        confirmedTasks.add(Map.entry(task, next.eventId()));
                        confirmed.add(
                                new ManagedToolResult(
                                        result.scope(),
                                        task.id(),
                                        task.executionId(),
                                        task.status(),
                                        task.result()));
                        return;
                    }
                    var task = tool(next.scope(), next.taskId()).orElseThrow();
                    if (!task.executionId().equals(next.executionId()))
                        throw conflict(
                                "tool_execution_mismatch", "Original output execution differs");
                    var arguments = parse(task.arguments(), Map.class);
                    var nativeCall = arguments.get("native_tool_call_id");
                    if (nativeCall instanceof String call && call.startsWith("framework_")) return;
                    var data = new LinkedHashMap<String, Object>();
                    data.put("turn_id", task.turnId());
                    if (nativeCall instanceof String call && !call.isBlank())
                        data.put("tool_call_id", call);
                    data.put("operation_tool_call_id", task.toolCallId());
                    data.put("task_id", task.id());
                    data.put("operation_execution_id", task.executionId());
                    data.put("operation_name", task.name());
                    data.put("source_seq", next.sourceSeq());
                    data.put("source", Map.of("kind", "tool", "event_id", next.eventId()));
                    data.putAll(next.data());
                    if ("tool.delta".equals(next.type())) {
                        byte[] bytes = Base64.getDecoder().decode((String) data.remove("data"));
                        data.put(
                                "output",
                                List.of(
                                        Map.of(
                                                "type",
                                                "text",
                                                "text",
                                                new String(bytes, StandardCharsets.UTF_8))));
                        data.put("encoding", "utf-8-replace");
                    }
                    if ("tool.output_gap".equals(next.type())) data.put("log_status", "gapped");
                    appendPublic(next.scope(), next.eventId(), next.type(), json(data));
                });
        for (var confirmedTask : confirmedTasks)
            ManagedRuntimeLog.tool(
                    log,
                    "hands.tool.result.committed",
                    event.scope(),
                    confirmedTask.getKey(),
                    "status",
                    confirmedTask.getKey().status(),
                    "source_event_id",
                    confirmedTask.getValue());
        var head = head(event.scope());
        return new ToolEventsCommit(
                new Receipt(
                        event.eventId(),
                        toolEvents.hash(event),
                        toolEvents.applied(event) ? "committed" : "buffered",
                        null,
                        head.sequence(),
                        head.epoch()),
                List.copyOf(confirmed));
    }

    private Head lockHead(ManagedScope scope) {
        var locked =
                jdbc.queryForList(
                        "SELECT scope_key FROM ma.session WHERE scope_key=? FOR UPDATE",
                        scope.messageGroup());
        if (locked.isEmpty()) throw conflict("session_not_found", "Session does not exist");
        return readHead(scope);
    }

    private Head readHead(ManagedScope scope) {
        var rows =
                jdbc.query(
                        "SELECT"
                            + " s.native_sequence,s.execution_epoch,s.native_commit_id,s.checkpoint,s.checkpoint_sequence,t.execution_token"
                            + " FROM ma.session s LEFT JOIN ma.turn t ON s.scope_key=t.scope_key"
                            + " AND s.current_turn_id=t.turn_id WHERE s.scope_key=?",
                        (row, index) ->
                                new Head(
                                        row.getLong(1),
                                        row.getLong(2),
                                        row.getString(6),
                                        row.getString(3),
                                        objects.decodeReference(scope, row.getString(4)),
                                        row.getLong(5)),
                        scope.messageGroup());
        if (rows.isEmpty()) throw conflict("session_not_found", "Session does not exist");
        return rows.get(0);
    }

    private String currentTurn(ManagedScope scope) {
        return jdbc.queryForObject(
                "SELECT current_turn_id FROM ma.session WHERE scope_key=?",
                String.class,
                scope.messageGroup());
    }

    private void nativeToolFact(ManagedScope scope, String turn, SessionEvent event) {
        if (!List.of("tool/dispatch", "action/end", "recovery/applied").contains(event.type()))
            return;
        var data = event.data();
        if ("tool/dispatch".equals(event.type())) {
            String call = String.valueOf(data.get("toolCallId"));
            if (jdbc.queryForObject(
                            "SELECT COUNT(*) FROM ma.native_tool_call WHERE scope_key=? AND"
                                    + " turn_id=? AND tool_call_id=?",
                            Long.class,
                            scope.messageGroup(),
                            turn,
                            call)
                    == 0)
                jdbc.update(
                        "INSERT INTO ma.native_tool_call(scope_key,turn_id,tool_call_id,status)"
                                + " VALUES(?,?,?,'dispatched')",
                        scope.messageGroup(),
                        turn,
                        call);
            else
                jdbc.update(
                        "UPDATE ma.native_tool_call SET status='dispatched' WHERE scope_key=? AND"
                                + " turn_id=? AND tool_call_id=?",
                        scope.messageGroup(),
                        turn,
                        call);
        } else if ("action/end".equals(event.type()) && data.get("result") != null) {
            jdbc.update(
                    "UPDATE ma.native_tool_call SET status='resolved' WHERE scope_key=? AND"
                            + " turn_id=? AND tool_call_id=?",
                    scope.messageGroup(),
                    turn,
                    String.valueOf(data.get("toolCallId")));
        } else if ("recovery/applied".equals(event.type())
                && data.get("resolvedToolCallIds") instanceof List<?> calls) {
            for (var call : calls)
                jdbc.update(
                        "UPDATE ma.native_tool_call SET status='resolved' WHERE scope_key=? AND"
                                + " turn_id=? AND tool_call_id=?",
                        scope.messageGroup(),
                        turn,
                        call);
        }
    }

    private void appendPublic(ManagedScope scope, String id, String type, String payload) {
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.public_event WHERE scope_key=? AND source_id=?",
                        Long.class,
                        scope.messageGroup(),
                        id)
                > 0) return;
        long sequence =
                jdbc.queryForObject(
                                "SELECT public_sequence FROM ma.session WHERE scope_key=?",
                                Long.class,
                                scope.messageGroup())
                        + 1;
        long created = System.currentTimeMillis();
        jdbc.update(
                "INSERT INTO ma.public_event(scope_key,sequence,source_id,type,payload,created_at)"
                        + " VALUES(?,?,?,?,?,?)",
                scope.messageGroup(),
                sequence,
                id,
                type,
                payload,
                created);
        ManagedSessionProtocol.project(
                jdbc, mapper, scope, id, type, parse(payload, Map.class), created);
        resources.committed(
                scope,
                new SessionEventDto(
                        id,
                        scope.sessionId(),
                        sequence,
                        type,
                        parse(payload, Map.class),
                        null,
                        created));
        jdbc.update(
                "UPDATE ma.session SET public_sequence=? WHERE scope_key=?",
                sequence,
                scope.messageGroup());
        notifications.committed(scope.messageGroup());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Cannot encode runtime boundary", error);
        }
    }

    private <T> T parse(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Cannot decode runtime boundary", error);
        }
    }

    private static void bounds(long after, int limit) {
        if (after < 0 || limit < 1 || limit > 4096)
            throw new IllegalArgumentException("Invalid event read bounds");
    }

    private static ManagedStateException conflict(String code, String message) {
        return new ManagedStateException(code, message);
    }
}
