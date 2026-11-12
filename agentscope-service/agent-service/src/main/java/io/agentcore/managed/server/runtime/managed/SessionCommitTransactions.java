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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Session Service owns native-independent commit validation, SQL atomicity and publication readiness. */
public final class SessionCommitTransactions implements PreparedCommitter {
    private final ManagedJournalStore journal;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final ManagedBudgetStore budgets;
    private final ManagedSessionProjections projections;
    private final ManagedCheckpointObjects objects;
    private final ManagedSuspensions suspensions;
    private final ManagedToolTransport transport;
    private final ManagedFiles files;
    private final ManagedCheckpoints checkpoints;
    private final RuntimeObjectStore content;

    public SessionCommitTransactions(ManagedJournalStore journal) {
        this.journal = journal;
        this.jdbc = journal.jdbc;
        this.tx = journal.tx;
        this.mapper = journal.mapper;
        this.budgets = journal.budgets;
        this.projections = journal.projections;
        this.objects = journal.objects;
        this.suspensions = journal.suspensions;
        this.transport = journal.transport;
        this.files = journal.files;
        this.checkpoints = journal.checkpoints;
        this.content = journal.content;
    }

    /** Public platform transaction entry; never decodes native event payloads. */
    public SessionCommitReceipt commitPrepared(ManagedCommitPlan plan) {
        return commitPlan(plan, new ManagedModelResults(jdbc, mapper, objects.backend()));
    }

    /** Trusted adapter output; all authoritative state checks still occur under the Session lock. */
    SessionCommitReceipt commitPlan(ManagedCommitPlan plan, ManagedModelResults modelResults) {
        return projections.prepared(
                plan.boundary().scope(),
                runtimeFacts(plan),
                () ->
                        ManagedBoundaryMetrics.measure(
                                ManagedBoundaryMetrics.Phase.METADATA_TRANSACTION,
                                () -> commitMetadata(plan, modelResults)));
    }

    private SessionCommitReceipt commitMetadata(
            ManagedCommitPlan plan, ManagedModelResults modelResults) {
        var batch = plan.boundary();
        var hash = plan.contentHash();
        var segment = plan.recoverySegment();
        return journal.owned(
                () ->
                        tx.execute(
                                ignored -> {
                                    var scope = batch.scope();
                                    // Lock the Session first, then read the Turn with a fresh
                                    // statement snapshot. A locking JOIN can retain an old Turn
                                    // snapshot while waiting for a concurrent owner to commit.
                                    var session = journal.lockSessionHead(scope);
                                    var replay = journal.receipt(scope, batch.commitId());
                                    if (replay != null) {
                                        journal.verifyReceipt(replay, hash);
                                        return replay;
                                    }
                                    var turn =
                                            jdbc.queryForMap(
                                                    "SELECT * FROM ma.turn WHERE scope_key=? AND"
                                                            + " turn_id=?",
                                                    scope.messageGroup(),
                                                    batch.turnId());
                                    var head = journal.sessionHead(scope, session, turn);
                                    var turnChanges = new ManagedTurnChanges(turn);
                                    projections.lockedHead(scope, session);
                                    var executionChanges =
                                            ManagedExecutionChanges.begin(
                                                    jdbc.getDataSource(),
                                                    scope,
                                                    batch.turnId(),
                                                    session,
                                                    turnChanges);
                                    if (head.epoch() != batch.epoch()
                                            || !batch.executionToken().equals(head.token())
                                            || !batch.turnId()
                                                    .equals(session.get("current_turn_id")))
                                        throw journal.conflict(
                                                "stale_execution",
                                                "Execution identity is no longer current");
                                    if (journal.processOwnership != null)
                                        journal.processOwnership.requireTurn(turn);
                                    if (head.sequence() != batch.expectedSequence()
                                            && !(batch.recoveryFacts().isEmpty()
                                                    && batch.terminalStatus() == null
                                                    && batch.expectedSequence() < head.sequence()))
                                        throw journal.conflict(
                                                "native_sequence_conflict",
                                                "Native committed prefix differs");
                                    if (!batch.recoveryFacts().isEmpty()
                                            && turn.get("first_recovery_sequence") == null)
                                        turnChanges.set(
                                                "first_recovery_sequence",
                                                batch.recoveryFacts().get(0).sequence());
                                    long sequence = head.sequence();
                                    String executionStatus = (String) turn.get("status");
                                    ManagedCheckpointObjects.Reference checkpoint = null;
                                    ManagedCommitPlan.PendingState checkpointPending = null;
                                    long checkpointSequence = head.checkpointSequence();
                                    String modelAnchor = null;
                                    Object iterationPosition = null;
                                    Boolean reasoningCompleted = null;
                                    boolean statusChanged = false;
                                    for (var change : plan.changes()) {
                                        for (var call : change.toolCalls())
                                            journal.nativeToolFact(scope, batch.turnId(), call);
                                        if (change.modelAnchor() != null)
                                            modelAnchor = change.modelAnchor();
                                        if (change.iterationPosition() != null)
                                            iterationPosition = change.iterationPosition();
                                        if (change.reasoningCompleted() != null)
                                            reasoningCompleted = change.reasoningCompleted();
                                        if (budgets != null && change.modelUsage() != null)
                                            budgets.committed(
                                                    scope, batch.turnId(), change.modelUsage());
                                        sequence = change.sequence();
                                        if (change.checkpoint() != null) {
                                            checkpoint = change.checkpoint().reference();
                                            checkpointPending = change.checkpoint().pending();
                                            checkpointSequence = sequence;
                                            checkpoints.capture(scope, change.checkpoint());
                                        }
                                        var interaction = change.interaction();
                                        if (interaction != null) {
                                            if (interaction.requested()) {
                                                if (journal.action(
                                                                scope,
                                                                batch.turnId(),
                                                                interaction.id())
                                                        .isEmpty())
                                                    jdbc.update(
                                                            "INSERT INTO"
                                                                + " ma.action(scope_key,turn_id,action_id,kind,payload)"
                                                                + " VALUES(?,?,?,?,?)",
                                                            scope.messageGroup(),
                                                            batch.turnId(),
                                                            interaction.id(),
                                                            interaction.kind(),
                                                            interaction.payload());
                                            } else
                                                jdbc.update(
                                                        "UPDATE ma.action SET status='answered'"
                                                            + " WHERE scope_key=? AND turn_id=? AND"
                                                            + " action_id=?",
                                                        scope.messageGroup(),
                                                        batch.turnId(),
                                                        interaction.id());
                                        }
                                        for (var fact : change.publicFacts())
                                            journal.appendPublic(
                                                    scope,
                                                    fact.sourceId(),
                                                    fact.type(),
                                                    journal.json(
                                                            files.publicPayload(
                                                                    scope, fact.payload())));
                                        if (change.executionStatus() != null) {
                                            statusChanged = true;
                                            executionStatus = change.executionStatus();
                                        }
                                    }
                                    if (modelAnchor != null)
                                        turnChanges.set("model_anchor", modelAnchor);
                                    if (iterationPosition != null)
                                        turnChanges.set("iteration_position", iterationPosition);
                                    if (reasoningCompleted != null)
                                        turnChanges.set("reasoning_completed", reasoningCompleted);
                                    if (statusChanged) turnChanges.set("status", executionStatus);
                                    if ((!batch.toolIntents().isEmpty()
                                                    && checkpoint == null
                                                    && head.checkpoint() == null)
                                            || (batch.inputReceipts().stream()
                                                            .anyMatch(
                                                                    input ->
                                                                            "applied"
                                                                                    .equals(
                                                                                            input
                                                                                                    .status()))
                                                    && checkpoint == null))
                                        throw journal.conflict(
                                                "checkpoint_required",
                                                "Effects and applied inputs need an atomic"
                                                        + " checkpoint");
                                    for (int intentIndex = 0;
                                            intentIndex < batch.toolIntents().size();
                                            intentIndex++) {
                                        var intent = batch.toolIntents().get(intentIndex);
                                        var binding = plan.toolBindings().get(intentIndex);
                                        String id =
                                                "tool_"
                                                        + ManagedScope.fingerprint(
                                                                scope.messageGroup(),
                                                                batch.turnId(),
                                                                intent.toolCallId());
                                        var old = journal.tool(scope, id);
                                        if (old.isPresent()) {
                                            if (!old.get().name().equals(intent.toolName())
                                                    || !old.get()
                                                            .arguments()
                                                            .equals(intent.arguments()))
                                                throw journal.conflict(
                                                        "tool_content_conflict",
                                                        "Tool call content differs");
                                        } else {
                                            if (budgets != null)
                                                budgets.admitTools(
                                                        scope,
                                                        !binding.batchCalls().isEmpty()
                                                                ? binding.batchCalls().size()
                                                                : binding.nativeCallId()
                                                                                        instanceof
                                                                                        String call
                                                                                && !call.startsWith(
                                                                                        "framework_")
                                                                        ? 1
                                                                        : 0);
                                            long pending =
                                                    jdbc.queryForObject(
                                                            "SELECT COUNT(*) FROM ma.tool_task"
                                                                + " WHERE scope_key=? AND result IS"
                                                                + " NULL",
                                                            Long.class,
                                                            scope.messageGroup());
                                            if (pending >= 128)
                                                throw journal.conflict(
                                                        "admission_limited",
                                                        "Pending Tool Worker ToolTask capacity"
                                                                + " exceeded");
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
                                                    binding.nativeCallId());
                                            for (var call : binding.batchCalls())
                                                jdbc.update(
                                                        "INSERT INTO"
                                                            + " ma.mcp_batch_call(scope_key,turn_id,task_id,parent_task_id,native_tool_call_id,ordinal)"
                                                            + " VALUES(?,?,?,?,?,?)",
                                                        scope.messageGroup(),
                                                        batch.turnId(),
                                                        call.taskId(),
                                                        id,
                                                        call.nativeCallId(),
                                                        call.ordinal());
                                            if (!"subagent.call".equals(intent.toolName())) {
                                                transport.stageRecord(
                                                        new ManagedToolCommand(
                                                                scope,
                                                                "execute_" + id,
                                                                "execute",
                                                                journal.tool(scope, id)
                                                                        .orElseThrow(),
                                                                intent.configuration(),
                                                                new ManagedToolCommand.Submission(
                                                                        batch.epoch(),
                                                                        batch.executionToken(),
                                                                        batch.commitId())));
                                            }
                                        }
                                    }
                                    for (var input : batch.inputReceipts()) {
                                        var old =
                                                jdbc.queryForMap(
                                                        "SELECT"
                                                            + " status,type,action_id,rejection_reason"
                                                            + " FROM ma.input WHERE scope_key=? AND"
                                                            + " turn_id=? AND input_id=?",
                                                        scope.messageGroup(),
                                                        batch.turnId(),
                                                        input.inputId());
                                        if (!"accepted".equals(old.get("status"))
                                                && (!input.status().equals(old.get("status"))
                                                        || !Objects.equals(
                                                                input.reason(),
                                                                old.get("rejection_reason"))))
                                            throw journal.conflict(
                                                    "input_receipt_conflict",
                                                    "Input already settled differently");
                                        jdbc.update(
                                                "UPDATE ma.input SET"
                                                    + " status=?,rejection_reason=?,delivery_status='delivered'"
                                                    + " WHERE scope_key=? AND input_id=?",
                                                input.status(),
                                                input.reason(),
                                                scope.messageGroup(),
                                                input.inputId());
                                        if ("REPLY".equals(old.get("type"))
                                                && "applied".equals(input.status()))
                                            jdbc.update(
                                                    "UPDATE ma.action SET status='answered' WHERE"
                                                            + " scope_key=? AND turn_id=? AND"
                                                            + " action_id=?",
                                                    scope.messageGroup(),
                                                    batch.turnId(),
                                                    old.get("action_id"));
                                        journal.appendPublic(
                                                scope,
                                                input.inputId() + ":settled",
                                                "input." + input.status(),
                                                journal.json(
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
                                    // A restored attempt can only settle framework work, without
                                    // opening another
                                    // Core run. Its durable handoff still completes a Service
                                    // execution interval.
                                    if ((batch.suspension() != null
                                                    || batch.terminalStatus() != null)
                                            && "preparing".equals(executionStatus))
                                        journal.appendPublic(
                                                scope,
                                                batch.commitId() + ":running",
                                                "turn.running",
                                                journal.json(
                                                        Map.of(
                                                                "turn_id",
                                                                batch.turnId(),
                                                                "status",
                                                                "running")));
                                    if (batch.suspension() != null) {
                                        suspensions.save(
                                                batch,
                                                objects.encodeReference(checkpoint),
                                                checkpointSequence,
                                                checkpointPending,
                                                turnChanges);
                                        var pendingCalls =
                                                pendingNativeCalls(scope, batch.turnId());
                                        if (!batch.suspension().waitingForInput()
                                                || !"waiting".equals(executionStatus))
                                            journal.appendPublic(
                                                    scope,
                                                    batch.suspension().id(),
                                                    batch.suspension().waitingForInput()
                                                            ? "turn.requires_action"
                                                            : "turn.waiting_tool",
                                                    journal.json(
                                                            Map.of(
                                                                    "turn_id",
                                                                    batch.turnId(),
                                                                    "status",
                                                                    batch.suspension()
                                                                                    .waitingForInput()
                                                                            ? "waiting"
                                                                            : "waiting_tool",
                                                                    batch.suspension()
                                                                                    .waitingForInput()
                                                                            ? "pending_action_ids"
                                                                            : "pending_tool_call_ids",
                                                                    batch.suspension()
                                                                                    .waitingForInput()
                                                                            ? batch.suspension()
                                                                                    .actionIds()
                                                                            : pendingCalls)));
                                    }
                                    if (batch.terminalStatus() != null) {
                                        if (Boolean.TRUE.equals(turn.get("inputs_open"))
                                                || jdbc.queryForObject(
                                                                "SELECT COUNT(*) FROM ma.input"
                                                                        + " WHERE scope_key=? AND"
                                                                        + " turn_id=? AND"
                                                                        + " status='accepted'",
                                                                Long.class,
                                                                scope.messageGroup(),
                                                                batch.turnId())
                                                        != 0)
                                            throw journal.conflict(
                                                    "inputs_unsettled",
                                                    "Terminal boundary has accepted unsettled"
                                                            + " inputs");
                                        if (checkpoint == null && head.checkpoint() == null)
                                            throw journal.conflict(
                                                    "checkpoint_required",
                                                    "Terminal boundary needs durable state");
                                        if (jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM ma.native_tool_call"
                                                            + " WHERE scope_key=? AND turn_id=? AND"
                                                            + " status='dispatched'",
                                                        Long.class,
                                                        scope.messageGroup(),
                                                        batch.turnId())
                                                != 0)
                                            throw journal.conflict(
                                                    "tool_reconciliation_required",
                                                    "Native tool outcomes remain uncertain");
                                        if (jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM ma.tool_task WHERE"
                                                            + " scope_key=? AND turn_id=? AND"
                                                            + " status NOT IN"
                                                            + " ('succeeded','failed','cancelled')",
                                                        Long.class,
                                                        scope.messageGroup(),
                                                        batch.turnId())
                                                != 0)
                                            throw journal.conflict(
                                                    "tools_unsettled",
                                                    "Tool execution has not stopped or its outcome"
                                                            + " is unknown");
                                        jdbc.update(
                                                "UPDATE ma.action SET status='closed' WHERE"
                                                        + " scope_key=? AND turn_id=? AND"
                                                        + " status='open'",
                                                scope.messageGroup(),
                                                batch.turnId());
                                        turnChanges.set("status", batch.terminalStatus());
                                        turnChanges.clearOwnership();
                                        turnChanges.flush(jdbc, scope, batch.turnId());
                                        ManagedSessionQueue.consumed(jdbc, scope, batch.turnId());
                                        // The immutable terminal and proactive ACK intent become
                                        // visible together.
                                        // SessionCommitReceipt capture takes the same Session lock,
                                        // including if
                                        // it arrives
                                        // late.
                                        jdbc.update(
                                                "UPDATE ma.turn_ack_receipt SET"
                                                        + " ready=TRUE,next_attempt_at=? WHERE"
                                                        + " scope_key=? AND turn_id=?",
                                                System.currentTimeMillis(),
                                                scope.messageGroup(),
                                                batch.turnId());
                                        journal.appendPublic(
                                                scope,
                                                batch.turnId() + ":terminal",
                                                "turn."
                                                        + journal.publicTurnStatus(
                                                                batch.terminalStatus()),
                                                journal.json(
                                                        Map.of(
                                                                "turn_id",
                                                                batch.turnId(),
                                                                "status",
                                                                journal.publicTurnStatus(
                                                                        batch.terminalStatus()))));
                                    }
                                    if (batch.suspension() == null
                                            && batch.terminalStatus() == null)
                                        turnChanges.flush(jdbc, scope, batch.turnId());
                                    var publicCommit = projections.attach(scope);
                                    String finalFields =
                                            batch.terminalStatus() == null
                                                    ? ""
                                                    : ",settled_sequence=?,current_turn_id=NULL";
                                    if (publicCommit != null)
                                        finalFields += ",accepted_sequence=?,commit_version=?";
                                    if (executionChanges.usageChanged())
                                        finalFields += ",usage_summary=?";
                                    var headArgs = new ArrayList<Object>();
                                    headArgs.add(sequence);
                                    headArgs.add(batch.commitId());
                                    headArgs.add(
                                            checkpoint == null
                                                    ? null
                                                    : objects.encodeReference(checkpoint));
                                    headArgs.add(checkpointSequence);
                                    if (batch.terminalStatus() != null)
                                        headArgs.add(turn.get("publication_sequence"));
                                    if (publicCommit != null) {
                                        headArgs.add(publicCommit.through());
                                        headArgs.add(publicCommit.version());
                                    }
                                    if (executionChanges.usageChanged())
                                        headArgs.add(executionChanges.usage);
                                    headArgs.add(scope.messageGroup());
                                    jdbc.update(
                                            "UPDATE ma.session SET"
                                                + " native_sequence=?,native_commit_id=?,checkpoint=COALESCE(?,checkpoint),checkpoint_sequence=?"
                                                    + finalFields
                                                    + " WHERE scope_key=?",
                                            headArgs.toArray());
                                    modelResults.register(
                                            batch,
                                            plan.modelRequests(),
                                            plan.modelOutcomes(),
                                            plan.finalizations(),
                                            head.checkpointSequence());
                                    var receipt =
                                            new SessionCommitReceipt(
                                                    batch.commitId(),
                                                    hash,
                                                    "committed",
                                                    null,
                                                    sequence,
                                                    batch.epoch());
                                    jdbc.update(
                                            "INSERT INTO"
                                                + " ma.runtime_commit(scope_key,commit_id,content_hash,receipt,turn_id,epoch,first_sequence,last_sequence,recovery_ref,checkpoint_ref,projection_version,public_commit_version,projection_manifest,publication_state,next_attempt_at)"
                                                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                            scope.messageGroup(),
                                            batch.commitId(),
                                            hash,
                                            journal.json(receipt),
                                            batch.turnId(),
                                            batch.epoch(),
                                            batch.recoveryFacts().isEmpty()
                                                    ? null
                                                    : batch.recoveryFacts().get(0).sequence(),
                                            sequence,
                                            segment == null ? null : journal.json(segment),
                                            checkpoint == null
                                                    ? null
                                                    : objects.encodeReference(checkpoint),
                                            publicCommit == null
                                                    ? ((Number) session.get("commit_version"))
                                                            .longValue()
                                                    : publicCommit.version(),
                                            publicCommit == null ? null : publicCommit.version(),
                                            publicCommit == null ? null : publicCommit.manifest(),
                                            publicCommit == null ? "sent" : "pending",
                                            System.currentTimeMillis());
                                    TransactionSynchronizationManager.registerSynchronization(
                                            new TransactionSynchronization() {
                                                @Override
                                                public void afterCommit() {
                                                    ManagedBoundaryMetrics.committed(
                                                            plan.metricKind(),
                                                            batch.recoveryFacts().size());
                                                }
                                            });
                                    return receipt;
                                }));
    }

    private List<String> pendingNativeCalls(ManagedScope scope, String turn) {
        return jdbc.query(
                "SELECT native_tool_call_id FROM ma.tool_task WHERE scope_key=? AND turn_id=? AND"
                    + " result IS NULL AND status<>'unknown' AND native_tool_call_id NOT LIKE"
                    + " 'framework_%' AND tool_name<>'mcp.batch' UNION SELECT c.native_tool_call_id"
                    + " FROM ma.mcp_batch_call c JOIN ma.tool_task t ON t.scope_key=c.scope_key AND"
                    + " t.task_id=c.parent_task_id WHERE c.scope_key=? AND c.turn_id=? AND t.result"
                    + " IS NULL AND t.status<>'unknown' ORDER BY native_tool_call_id",
                (row, index) -> row.getString(1),
                scope.messageGroup(),
                turn,
                scope.messageGroup(),
                turn);
    }

    private List<ManagedFactBundles.Content> runtimeFacts(ManagedCommitPlan plan) {
        var batch = plan.boundary();
        var facts = new ArrayList<ManagedFactBundles.Content>();
        for (var change : plan.changes())
            for (var fact : change.publicFacts())
                facts.add(
                        new ManagedFactBundles.Content(
                                fact.type(),
                                journal.json(files.publicPayload(batch.scope(), fact.payload()))));
        if (batch.suspension() != null
                || batch.terminalStatus() != null
                || plan.changes().stream().anyMatch(change -> change.executionStatus() != null)) {
            for (String status : List.of("running", "waiting"))
                facts.add(
                        new ManagedFactBundles.Content(
                                "waiting".equals(status) ? "turn.requires_action" : "turn.running",
                                journal.json(Map.of("turn_id", batch.turnId(), "status", status))));
        }
        for (var input : batch.inputReceipts()) {
            var data = new LinkedHashMap<String, Object>();
            data.put("input_id", input.inputId());
            data.put("status", input.status());
            if (input.reason() != null) data.put("reason", input.reason());
            facts.add(
                    new ManagedFactBundles.Content("input." + input.status(), journal.json(data)));
        }
        if (batch.suspension() != null) {
            var pending = new TreeSet<String>(pendingNativeCalls(batch.scope(), batch.turnId()));
            for (var binding : plan.toolBindings()) {
                if (!binding.batchCalls().isEmpty()) {
                    for (var call : binding.batchCalls()) pending.add(call.nativeCallId());
                } else if (binding.nativeCallId() instanceof String call
                        && !call.startsWith("framework_")) pending.add(call);
            }
            boolean input = batch.suspension().waitingForInput();
            facts.add(
                    new ManagedFactBundles.Content(
                            input ? "turn.requires_action" : "turn.waiting_tool",
                            journal.json(
                                    Map.of(
                                            "turn_id",
                                            batch.turnId(),
                                            "status",
                                            input ? "waiting" : "waiting_tool",
                                            input ? "pending_action_ids" : "pending_tool_call_ids",
                                            input
                                                    ? batch.suspension().actionIds()
                                                    : List.copyOf(pending)))));
        }
        if (batch.terminalStatus() != null)
            facts.add(
                    new ManagedFactBundles.Content(
                            "turn." + journal.publicTurnStatus(batch.terminalStatus()),
                            journal.json(
                                    Map.of(
                                            "turn_id",
                                            batch.turnId(),
                                            "status",
                                            journal.publicTurnStatus(batch.terminalStatus())))));
        return facts;
    }
}
