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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.harness.runtime.NativeCheckpointCodec;
import io.agentscope.core.session.SessionEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable, bounded validation. A validated command is not a committed restore or workspace copy. */
public final class ManagedCheckpointOperations {
    public record Operation(ManagedScope source, ManagedScope target, String id, String actor) {}

    public record WorkspaceTask(
            ManagedScope source,
            String operationId,
            String phase,
            ManagedScope sandbox,
            String status,
            ManagedJournalStore.ToolTask task) {}

    public record Receipt(
            String id,
            String status,
            String errorCode,
            long scanSequence,
            boolean workspaceRequired) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final ManagedJournalStore journal;
    private final ManagedExecutionTargets targets;
    private final NativeCheckpointCodec nativeCodec;

    public ManagedCheckpointOperations(
            DataSource source,
            ObjectMapper mapper,
            ManagedJournalStore journal,
            NativeCheckpointCodec nativeCodec) {
        this(source, mapper, journal, nativeCodec, null);
    }

    public ManagedCheckpointOperations(
            DataSource source,
            ObjectMapper mapper,
            ManagedJournalStore journal,
            NativeCheckpointCodec nativeCodec,
            ManagedExecutionTargets targets) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(10);
        this.mapper = mapper;
        this.journal = journal;
        this.targets = targets;
        this.nativeCodec = Objects.requireNonNull(nativeCodec);
    }

    public Receipt prepare(
            ManagedScope source, ManagedScope target, String actor, String key, String checkpoint) {
        ManagedScope.requireText(actor, "actor");
        ManagedScope.requireText(key, "idempotency key");
        ManagedScope.requireText(checkpoint, "checkpoint ID");
        if (key.length() > 256 || checkpoint.length() > 256)
            throw new IllegalArgumentException("Checkpoint request exceeds its identifier limit");
        if (!source.tenant().equals(target.tenant())
                || !source.namespace().equals(target.namespace()))
            throw conflict(
                    "checkpoint_scope_differs",
                    "Checkpoint operations require the same resource scope");
        String id = "checkpoint_op_" + ManagedScope.fingerprint(source.messageGroup(), actor, key);
        String digest = ManagedScope.fingerprint(target.messageGroup(), checkpoint);
        return tx.execute(
                ignored -> {
                    var sessions = lockSessions(source, target);
                    for (var scope : scopes(source, target)) {
                        var bindings =
                                jdbc.queryForList(
                                        "SELECT harness_type FROM ma.harness_binding WHERE"
                                                + " scope_key=?",
                                        String.class,
                                        scope.messageGroup());
                        if (!bindings.isEmpty()
                                && !nativeCodec.harnessType().equals(bindings.get(0)))
                            throw conflict(
                                    "checkpoint_harness_unsupported",
                                    "Native checkpoint codec is unavailable for this kernel");
                    }
                    var existing = rows(source, id);
                    if (!existing.isEmpty()) {
                        if (!digest.equals(existing.get(0).get("content_hash")))
                            throw conflict(
                                    "checkpoint_content_conflict",
                                    "Checkpoint retry content differs");
                        return receipt(existing.get(0));
                    }
                    ManagedCheckpointHolds.requireUnheld(jdbc, source);
                    ManagedCheckpointHolds.requireUnheld(jdbc, target);
                    requireIdle(source);
                    requireIdle(target);
                    for (var row : sessions)
                        if (!"active".equals(row.get("lifecycle")))
                            throw conflict(
                                    "session_inactive",
                                    "Checkpoint operations require active Sessions");
                    var sourceRow = session(source);
                    var targetRow = session(target);
                    if (targets != null) {
                        String sourceSandbox =
                                ManagedToolTarget.existingSandboxIdentity(targets.resolve(source));
                        String targetSandbox =
                                ManagedToolTarget.existingSandboxIdentity(targets.resolve(target));
                        if (!source.equals(target)
                                && sourceSandbox != null
                                && sourceSandbox.equals(targetSandbox))
                            throw conflict(
                                    "borrowed_sandbox_busy",
                                    "Fork requires independent Sandbox instances");
                    }
                    try {
                        var sourceConfig = mapper.readTree((String) sourceRow.get("configuration"));
                        var targetConfig = mapper.readTree((String) targetRow.get("configuration"));
                        Object sourceAgent =
                                targets == null
                                        ? sourceConfig.path("agentSnapshot")
                                        : targets.resolve(source).agentSnapshot();
                        Object targetAgent =
                                targets == null
                                        ? targetConfig.path("agentSnapshot")
                                        : targets.resolve(target).agentSnapshot();
                        if (!sourceAgent.equals(targetAgent))
                            throw conflict(
                                    "agent_definition_differs",
                                    "Checkpoint Agent definitions differ");
                    } catch (IOException invalid) {
                        throw new IllegalStateException(
                                "Invalid stored Session configuration", invalid);
                    }
                    if (!source.equals(target)
                            && ((Number) targetRow.get("native_sequence")).longValue() != 0)
                        throw conflict("fork_target_not_empty", "Fork target has native history");
                    var checkpointRows =
                            jdbc.queryForList(
                                    "SELECT native_sequence FROM ma.state_checkpoint WHERE"
                                            + " scope_key=? AND checkpoint_id=?",
                                    source.messageGroup(),
                                    checkpoint);
                    if (checkpointRows.isEmpty())
                        throw conflict(
                                "checkpoint_not_found",
                                "Checkpoint does not exist in this Session");
                    String progress =
                            json(
                                    Map.of(
                                            "tools", List.of(), "runs", List.of(), "actions",
                                            List.of()));
                    jdbc.update(
                            "INSERT INTO"
                                + " ma.checkpoint_operation(scope_key,operation_id,target_scope,actor,content_hash,checkpoint_id,checkpoint_sequence,source_head,target_head,validation,created_at)"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                            source.messageGroup(),
                            id,
                            target.messageGroup(),
                            actor,
                            digest,
                            checkpoint,
                            checkpointRows.get(0).get("native_sequence"),
                            sourceRow.get("native_sequence"),
                            targetRow.get("native_sequence"),
                            progress,
                            System.currentTimeMillis());
                    for (var scope : scopes(source, target))
                        jdbc.update(
                                "INSERT INTO"
                                    + " ma.checkpoint_hold(scope_key,operation_scope,operation_id)"
                                    + " VALUES(?,?,?)",
                                scope.messageGroup(),
                                source.messageGroup(),
                                id);
                    return receipt(row(source, id));
                });
    }

    private List<SessionEvent> validationEvents(
            ManagedScope source, Map<String, Object> operation) {
        long cursor = ((Number) operation.get("scan_sequence")).longValue();
        long head = ((Number) operation.get("source_head")).longValue();
        var events = new ArrayList<SessionEvent>();
        long bytes = 0;
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (events.size() < 128 && cursor < head) {
            var next = journal.recoveryEvents(source, cursor, 1);
            if (next.isEmpty()) break;
            var event = next.get(0);
            events.add(event);
            cursor = event.seq();
            bytes += event.payloadJson().getBytes(StandardCharsets.UTF_8).length;
            if (bytes >= 32L * 1024 * 1024 || System.nanoTime() >= deadline) break;
        }
        return events;
    }

    public Receipt validate(ManagedScope source, String id) {
        var before = row(source, id);
        var target = scope((String) before.get("target_scope"));
        if (!"validating".equals(before.get("status"))) return receipt(before);
        var preparedEvents = validationEvents(source, before);
        return tx.execute(
                ignored -> {
                    lockSessions(source, target);
                    var operation = row(source, id);
                    if (!"validating".equals(operation.get("status"))) return receipt(operation);
                    if (!held(source, id, target) || !headsMatch(source, target, operation))
                        return reject(source, id, "native_prefix_changed");
                    var state = progress((String) operation.get("validation"));
                    long cursor = ((Number) operation.get("scan_sequence")).longValue();
                    if (cursor != ((Number) before.get("scan_sequence")).longValue())
                        return receipt(operation);
                    if (preparedEvents.isEmpty()
                            && cursor < ((Number) operation.get("source_head")).longValue())
                        return reject(source, id, "native_prefix_missing");
                    long head = ((Number) operation.get("source_head")).longValue();
                    long selected = ((Number) operation.get("checkpoint_sequence")).longValue();
                    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                    long bytes = 0;
                    for (var event : preparedEvents) {
                        if (cursor >= head) break;
                        if (event.seq() != cursor + 1)
                            return reject(source, id, "native_prefix_missing");
                        int size = event.payloadJson().getBytes(StandardCharsets.UTF_8).length;
                        if (size > 16 * 1024 * 1024)
                            return reject(source, id, "native_record_too_large");
                        bytes += size;
                        var inspection = inspect(event);
                        inspection.opened().forEach((key, ids) -> state.get(key).addAll(ids));
                        inspection.closed().forEach((key, ids) -> state.get(key).removeAll(ids));
                        if (event.seq() > selected && inspection.toolDispatch())
                            state.computeIfAbsent("workspace_changed", key -> new LinkedHashSet<>())
                                    .add("changed");
                        cursor = event.seq();
                        if (cursor == selected) {
                            if (!inspection.checkpoint())
                                return reject(source, id, "checkpoint_not_found");
                            if (!state.get("tools").isEmpty() || !state.get("actions").isEmpty())
                                return reject(source, id, "checkpoint_has_unresolved_effects");
                            if (!inspection.settled())
                                return reject(source, id, "checkpoint_state_unsettled");
                            String hash =
                                    jdbc.queryForObject(
                                            "SELECT state_hash FROM ma.state_checkpoint"
                                                    + " WHERE scope_key=? AND native_sequence=?",
                                            String.class,
                                            source.messageGroup(),
                                            selected);
                            if (!ManagedScope.fingerprint(inspection.stateJson()).equals(hash))
                                return reject(source, id, "checkpoint_integrity_mismatch");
                        }
                        if (state.values().stream().mapToInt(Set::size).sum() > 4096)
                            return reject(source, id, "validation_capacity_exceeded");
                        if (bytes >= 32L * 1024 * 1024 || System.nanoTime() >= deadline) break;
                    }
                    String encoded = json(state);
                    if (encoded.getBytes(StandardCharsets.UTF_8).length > 256 * 1024)
                        return reject(source, id, "validation_capacity_exceeded");
                    if (cursor == head
                            && List.of("tools", "runs", "actions").stream()
                                    .anyMatch(key -> !state.get(key).isEmpty()))
                        return reject(source, id, "unresolved_native_execution");
                    jdbc.update(
                            "UPDATE ma.checkpoint_operation SET"
                                    + " scan_sequence=?,validation=?,status=? WHERE scope_key=? AND"
                                    + " operation_id=?",
                            cursor,
                            encoded,
                            cursor == head ? "validated" : "validating",
                            source.messageGroup(),
                            id);
                    return receipt(row(source, id));
                });
    }

    /** Cancellation is immediate only while validation is effect-free. */
    public Receipt cancel(ManagedScope source, String id, String actor) {
        var before = row(source, id);
        var target = scope((String) before.get("target_scope"));
        return tx.execute(
                ignored -> {
                    lockSessions(source, target);
                    var operation = row(source, id);
                    if (!actor.equals(operation.get("actor")))
                        throw conflict(
                                "checkpoint_actor_differs",
                                "Checkpoint cancellation Actor differs");
                    if (!List.of("validating", "validated", "snapshotting")
                            .contains(operation.get("status"))) return receipt(operation);
                    jdbc.update(
                            "UPDATE ma.checkpoint_operation SET status='cancelled' WHERE"
                                    + " scope_key=? AND operation_id=?",
                            source.messageGroup(),
                            id);
                    release(source, id);
                    return receipt(row(source, id));
                });
    }

    public Receipt get(ManagedScope source, String id) {
        return receipt(row(source, id));
    }

    public boolean exists(ManagedScope source, String id) {
        return !rows(source, id).isEmpty();
    }

    public Map<String, Object> view(ManagedScope source, String id) {
        var operation = row(source, id);
        var target = scope((String) operation.get("target_scope"));
        var receipt = receipt(operation);
        var value = new LinkedHashMap<String, Object>();
        value.put("command_id", id);
        value.put("operation_id", id);
        value.put("source_session_id", source.sessionId());
        value.put("session_id", target.sessionId());
        value.put("checkpoint_id", operation.get("checkpoint_id"));
        value.put("kind", source.equals(target) ? "checkpoint_restore" : "fork");
        value.put("status", receipt.status());
        value.put("command_status", receipt.status());
        value.put("created_at", operation.get("created_at"));
        value.put("scan_sequence", receipt.scanSequence());
        value.put("workspace_required", receipt.workspaceRequired());
        if (receipt.errorCode() != null) value.put("error", Map.of("code", receipt.errorCode()));
        return value;
    }

    public List<Operation> pending(int limit) {
        if (limit < 1 || limit > 256)
            throw new IllegalArgumentException("Invalid checkpoint scan limit");
        return jdbc.query(
                "SELECT * FROM ma.checkpoint_operation WHERE status IN"
                        + " ('validating','validated','snapshotting','restoring','workspace_ready')"
                        + " ORDER BY created_at,operation_id LIMIT ?",
                (row, index) ->
                        new Operation(
                                scope(row.getString("scope_key")),
                                scope(row.getString("target_scope")),
                                row.getString("operation_id"),
                                row.getString("actor")),
                limit);
    }

    /** Persist intent before any external start. Replacement workers only query started intents. */
    public WorkspaceTask workspaceTask(ManagedScope source, String id) {
        var before = row(source, id);
        var target = scope((String) before.get("target_scope"));
        return tx.execute(
                ignored -> {
                    lockSessions(source, target);
                    var operation = row(source, id);
                    if (!List.of("validated", "snapshotting", "restoring")
                            .contains(operation.get("status"))) return null;
                    if (!held(source, id, target) || !headsMatch(source, target, operation))
                        throw conflict(
                                "native_prefix_changed", "Workspace operation prefix changed");
                    requireIdle(source);
                    requireIdle(target);
                    long selected = ((Number) operation.get("checkpoint_sequence")).longValue();
                    var images = workspaceRows(source, selected);
                    String phase;
                    ManagedScope sandbox;
                    String arguments;
                    if (images.isEmpty()) {
                        if (!hasSandbox(source)) {
                            if (hasSandbox(target))
                                throw conflict(
                                        "checkpoint_workspace_unavailable",
                                        "Source has no Workspace image");
                            return null;
                        }
                        if (jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM ma.tool_task WHERE"
                                                        + " scope_key=? AND checkpoint_sequence>=?",
                                                Long.class,
                                                source.messageGroup(),
                                                selected)
                                        != 0
                                || !progress((String) operation.get("validation"))
                                        .getOrDefault("workspace_changed", Set.of())
                                        .isEmpty()) {
                            reject(source, id, "checkpoint_workspace_unavailable");
                            return null;
                        }
                        phase = "snapshot";
                        sandbox = source;
                        arguments = "{}";
                    } else {
                        if (!source.equals(target)
                                && jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM ma.file WHERE scope_key=?",
                                                Long.class,
                                                target.messageGroup())
                                        != 0) {
                            rejectBeforeImport(source, id, "fork_target_files_not_empty");
                            return null;
                        }
                        if (jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM ma.file f JOIN ma.checkpoint_file c"
                                            + " ON c.scope_key=f.scope_key AND c.file_id=f.file_id"
                                            + " WHERE c.scope_key=? AND c.native_sequence=? AND"
                                            + " f.content IS NULL",
                                        Long.class,
                                        source.messageGroup(),
                                        selected)
                                != 0) {
                            rejectBeforeImport(source, id, "checkpoint_file_content_removed");
                            return null;
                        }
                        phase = "restore";
                        sandbox = target;
                        arguments = json(Map.of("snapshot", workspacePayload(images.get(0))));
                    }
                    var steps = stepRows(source, id, phase);
                    if (steps.isEmpty()) {
                        String execution =
                                "workspace_"
                                        + ManagedScope.fingerprint(
                                                source.messageGroup(), id, phase);
                        jdbc.update(
                                "INSERT INTO"
                                    + " ma.checkpoint_workspace_step(operation_scope,operation_id,phase,sandbox_scope,execution_id)"
                                    + " VALUES(?,?,?,?,?)",
                                source.messageGroup(),
                                id,
                                phase,
                                sandbox.messageGroup(),
                                execution);
                        jdbc.update(
                                "UPDATE ma.checkpoint_operation SET status=? WHERE scope_key=? AND"
                                        + " operation_id=?",
                                phase.equals("snapshot") ? "snapshotting" : "restoring",
                                source.messageGroup(),
                                id);
                        steps = stepRows(source, id, phase);
                    }
                    var step = steps.get(0);
                    String execution = (String) step.get("execution_id");
                    var task =
                            new ManagedJournalStore.ToolTask(
                                    execution,
                                    null,
                                    execution,
                                    execution,
                                    "workspace." + phase,
                                    arguments,
                                    selected,
                                    (String) step.get("status"),
                                    null,
                                    null,
                                    null);
                    return new WorkspaceTask(
                            source, id, phase, sandbox, (String) step.get("status"), task);
                });
    }

    public boolean startWorkspaceTask(WorkspaceTask task) {
        var target = scope((String) row(task.source(), task.operationId()).get("target_scope"));
        return tx.execute(
                ignored -> {
                    lockSessions(task.source(), target);
                    var operation = row(task.source(), task.operationId());
                    String expected =
                            task.phase().equals("snapshot") ? "snapshotting" : "restoring";
                    if (!expected.equals(operation.get("status"))) return false;
                    if (!held(task.source(), task.operationId(), target)
                            || !headsMatch(task.source(), target, operation))
                        throw conflict(
                                "native_prefix_changed", "Workspace operation prefix changed");
                    return jdbc.update(
                                    "UPDATE ma.checkpoint_workspace_step SET status='started' WHERE"
                                        + " operation_scope=? AND operation_id=? AND phase=? AND"
                                        + " execution_id=? AND status='prepared'",
                                    task.source().messageGroup(),
                                    task.operationId(),
                                    task.phase(),
                                    task.task().executionId())
                            == 1;
                });
    }

    public Receipt workspaceResult(WorkspaceTask task, ManagedToolExecutor.Outcome outcome) {
        var source = task.source();
        var target = scope((String) row(source, task.operationId()).get("target_scope"));
        return tx.execute(
                ignored -> {
                    lockSessions(source, target);
                    var operation = row(source, task.operationId());
                    if (List.of("completed", "cancelled", "rejected")
                            .contains(operation.get("status"))) return receipt(operation);
                    var step = stepRows(source, task.operationId(), task.phase()).get(0);
                    if (!task.task().executionId().equals(step.get("execution_id")))
                        throw conflict(
                                "workspace_execution_differs",
                                "Workspace execution identity differs");
                    if ("succeeded".equals(step.get("status"))) {
                        if (outcome.state() == ManagedToolExecutor.State.SUCCEEDED
                                && !ManagedScope.fingerprint(outcome.result())
                                        .equals(step.get("result_hash")))
                            throw conflict(
                                    "workspace_result_conflict", "Workspace result retry differs");
                        return receipt(operation);
                    }
                    if ("prepared".equals(step.get("status")))
                        throw conflict("workspace_not_started", "Workspace intent has not started");
                    if (!held(source, task.operationId(), target)
                            || !headsMatch(source, target, operation))
                        throw conflict(
                                "native_prefix_changed", "Workspace operation prefix changed");
                    String state =
                            outcome.state() == ManagedToolExecutor.State.SUCCEEDED
                                    ? "succeeded"
                                    : outcome.terminal() ? "failed" : "unknown";
                    if ("succeeded".equals(state)) {
                        long selected = ((Number) operation.get("checkpoint_sequence")).longValue();
                        try {
                            if (outcome.result().length() > 46 * 1024 * 1024)
                                throw conflict(
                                        "workspace_image_too_large",
                                        "Workspace image exceeds its limit");
                            var payload = mapper.readTree(outcome.result());
                            if ("snapshot".equals(task.phase())) {
                                byte[] content =
                                        Base64.getDecoder()
                                                .decode(payload.path("content").asText());
                                String sha = payload.path("sha256").asText();
                                String manifest = payload.path("manifest").toString();
                                if (content.length > 32 * 1024 * 1024
                                        || !sha.equals(sha256(content))
                                        || !payload.path("manifest").isObject()
                                        || payload.path("manifest").size() > 4096
                                        || manifest.getBytes(StandardCharsets.UTF_8).length
                                                > 1024 * 1024)
                                    throw conflict(
                                            "workspace_image_invalid",
                                            "Workspace image integrity differs");
                                jdbc.update(
                                        "INSERT INTO"
                                            + " ma.checkpoint_workspace(scope_key,native_sequence,sha256,manifest,content)"
                                            + " VALUES(?,?,?,?,?)",
                                        source.messageGroup(),
                                        selected,
                                        sha,
                                        manifest,
                                        content);
                                jdbc.update(
                                        "UPDATE ma.checkpoint_operation SET status='validated'"
                                                + " WHERE scope_key=? AND operation_id=?",
                                        source.messageGroup(),
                                        task.operationId());
                            } else {
                                var image = workspaceRows(source, selected).get(0);
                                if (!image.get("sha256").equals(payload.path("sha256").asText())
                                        || !mapper.readTree((String) image.get("manifest"))
                                                .equals(payload.path("manifest")))
                                    throw conflict(
                                            "workspace_confirmation_differs",
                                            "Workspace import confirmation differs");
                                jdbc.update(
                                        "UPDATE ma.checkpoint_operation SET"
                                                + " status='workspace_ready',error_code=NULL WHERE"
                                                + " scope_key=? AND operation_id=?",
                                        source.messageGroup(),
                                        task.operationId());
                            }
                        } catch (IOException invalid) {
                            throw conflict(
                                    "workspace_image_invalid", "Workspace response is invalid");
                        }
                    } else if ("failed".equals(state) && "snapshot".equals(task.phase())) {
                        return reject(source, task.operationId(), "workspace_snapshot_failed");
                    } else if ("restore".equals(task.phase())) {
                        jdbc.update(
                                "UPDATE ma.checkpoint_operation SET"
                                    + " error_code='workspace_import_unconfirmed' WHERE scope_key=?"
                                    + " AND operation_id=?",
                                source.messageGroup(),
                                task.operationId());
                    }
                    jdbc.update(
                            "UPDATE ma.checkpoint_workspace_step SET status=?,result_hash=? WHERE"
                                    + " operation_scope=? AND operation_id=? AND phase=?",
                            state,
                            outcome.result() == null
                                    ? null
                                    : ManagedScope.fingerprint(outcome.result()),
                            source.messageGroup(),
                            task.operationId(),
                            task.phase());
                    return receipt(row(source, task.operationId()));
                });
    }

    private List<Map<String, Object>> workspaceRows(ManagedScope source, long sequence) {
        return jdbc.queryForList(
                "SELECT * FROM ma.checkpoint_workspace WHERE scope_key=? AND native_sequence=?",
                source.messageGroup(),
                sequence);
    }

    private Map<String, Object> workspacePayload(Map<String, Object> image) {
        try {
            byte[] content = (byte[]) image.get("content");
            if (!image.get("sha256").equals(sha256(content)))
                throw conflict(
                        "workspace_image_invalid", "Stored Workspace image integrity differs");
            return Map.of(
                    "content",
                    Base64.getEncoder().encodeToString(content),
                    "sha256",
                    image.get("sha256"),
                    "manifest",
                    mapper.readTree((String) image.get("manifest")));
        } catch (IOException invalid) {
            throw conflict("workspace_image_invalid", "Stored Workspace manifest is invalid");
        }
    }

    private List<Map<String, Object>> stepRows(ManagedScope source, String id, String phase) {
        return jdbc.queryForList(
                "SELECT * FROM ma.checkpoint_workspace_step WHERE operation_scope=? AND"
                        + " operation_id=? AND phase=?",
                source.messageGroup(),
                id,
                phase);
    }

    private boolean hasSandbox(ManagedScope scope) {
        if (targets != null && ManagedToolTarget.existingSandboxId(targets.resolve(scope)) != null)
            return true;
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.tool_task WHERE scope_key=? AND"
                                + " (tool_name LIKE 'sandbox.%' OR tool_name LIKE 'fs.%' OR"
                                + " tool_name LIKE 'workspace.%' OR tool_name='artifact.publish')",
                        Long.class, scope.messageGroup())
                != 0;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private Receipt rejectBeforeImport(ManagedScope source, String id, String code) {
        var steps = stepRows(source, id, "restore");
        if (steps.stream().anyMatch(step -> !"prepared".equals(step.get("status"))))
            throw conflict(code, "Workspace import prevents releasing the operation barrier");
        return reject(source, id, code);
    }

    /** Atomic recovery baseline, only after a required Workspace import has been confirmed. */
    public Receipt commitBaseline(
            ManagedScope source, String id, String actor, String targetOwner) {
        ManagedScope.requireText(targetOwner, "target owner");
        var before = row(source, id);
        var target = scope((String) before.get("target_scope"));
        if (!actor.equals(before.get("actor")))
            throw conflict("checkpoint_actor_differs", "Checkpoint commit Actor differs");
        try {
            String storedOwner =
                    mapper.readTree((String) session(target).get("configuration"))
                            .path("owner_id")
                            .asText();
            if (!storedOwner.isEmpty() && !storedOwner.equals(targetOwner))
                throw conflict("checkpoint_owner_differs", "Target Session owner differs");
        } catch (IOException invalid) {
            throw new IllegalStateException("Invalid Session configuration", invalid);
        }
        if ("completed".equals(before.get("status"))) return receipt(before);
        if (!List.of("validated", "workspace_ready").contains(before.get("status")))
            throw conflict("checkpoint_not_validated", "Checkpoint validation has not completed");
        long originalSequence = ((Number) before.get("checkpoint_sequence")).longValue();
        // Immutable object I/O precedes row locks; commit rechecks the held prefix and epoch.
        var baselineEvents = journal.recoveryEvents(source, originalSequence - 1, 1);
        if (baselineEvents.size() != 1 || baselineEvents.get(0).seq() != originalSequence)
            throw conflict("checkpoint_not_found", "Selected checkpoint is unavailable");
        var preparedBaseline =
                prepareBaseline(source, target, id, before, targetOwner, baselineEvents.get(0));
        var baselineSegment = journal.stageNative(target, List.of(preparedBaseline));
        return journal.prepareResourceEvent(
                target,
                source.equals(target) ? "checkpoint.restored" : "session.forked",
                Map.of(
                        "checkpoint_id",
                        before.get("checkpoint_id"),
                        "source_session_id",
                        source.sessionId(),
                        "operation_id",
                        id),
                () ->
                        tx.execute(
                                ignored -> {
                                    lockSessions(source, target);
                                    var operation = row(source, id);
                                    if (!actor.equals(operation.get("actor")))
                                        throw conflict(
                                                "checkpoint_actor_differs",
                                                "Checkpoint commit Actor differs");
                                    try {
                                        String storedOwner =
                                                mapper.readTree(
                                                                (String)
                                                                        session(target)
                                                                                .get(
                                                                                        "configuration"))
                                                        .path("owner_id")
                                                        .asText();
                                        if (!storedOwner.isEmpty()
                                                && !storedOwner.equals(targetOwner))
                                            throw conflict(
                                                    "checkpoint_owner_differs",
                                                    "Target Session owner differs");
                                    } catch (IOException invalid) {
                                        throw new IllegalStateException(
                                                "Invalid Session configuration", invalid);
                                    }
                                    if ("completed".equals(operation.get("status")))
                                        return receipt(operation);
                                    if (!List.of("validated", "workspace_ready")
                                            .contains(operation.get("status")))
                                        throw conflict(
                                                "checkpoint_not_validated",
                                                "Checkpoint validation has not completed");
                                    if (!held(source, id, target)
                                            || !headsMatch(source, target, operation)) {
                                        if ("workspace_ready".equals(operation.get("status")))
                                            throw conflict(
                                                    "native_prefix_changed",
                                                    "Imported Workspace prefix changed");
                                        return rejectBeforeImport(
                                                source, id, "native_prefix_changed");
                                    }
                                    long selected =
                                            ((Number) operation.get("checkpoint_sequence"))
                                                    .longValue();
                                    boolean needsWorkspace =
                                            hasSandbox(source)
                                                    || hasSandbox(target)
                                                    || !workspaceRows(source, selected).isEmpty();
                                    if (needsWorkspace
                                            && (!"workspace_ready".equals(operation.get("status"))
                                                    || stepRows(source, id, "restore").stream()
                                                            .noneMatch(
                                                                    step ->
                                                                            "succeeded"
                                                                                    .equals(
                                                                                            step
                                                                                                    .get(
                                                                                                            "status")))))
                                        throw conflict(
                                                "workspace_snapshot_required",
                                                "A confirmed independent Workspace snapshot is"
                                                        + " required");
                                    requireIdle(source);
                                    requireIdle(target);
                                    var events = baselineEvents;
                                    if (events.size() != 1 || events.get(0).seq() != selected)
                                        return rejectBeforeImport(
                                                source, id, "checkpoint_not_found");
                                    String original =
                                            (String) events.get(0).data().get("stateJson");
                                    String expected =
                                            jdbc.queryForObject(
                                                    "SELECT state_hash FROM ma.state_checkpoint"
                                                            + " WHERE scope_key=? AND"
                                                            + " native_sequence=?",
                                                    String.class,
                                                    source.messageGroup(),
                                                    selected);
                                    if (original == null
                                            || !ManagedScope.fingerprint(original).equals(expected))
                                        return rejectBeforeImport(
                                                source, id, "checkpoint_integrity_mismatch");
                                    if (!source.equals(target)) {
                                        if (jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM ma.file WHERE"
                                                                + " scope_key=?",
                                                        Long.class,
                                                        target.messageGroup())
                                                != 0)
                                            return rejectBeforeImport(
                                                    source, id, "fork_target_files_not_empty");
                                        if (jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM ma.file f JOIN"
                                                            + " ma.checkpoint_file c ON"
                                                            + " c.scope_key=f.scope_key AND"
                                                            + " c.file_id=f.file_id WHERE"
                                                            + " c.scope_key=? AND"
                                                            + " c.native_sequence=? AND f.content"
                                                            + " IS NULL",
                                                        Long.class,
                                                        source.messageGroup(),
                                                        selected)
                                                != 0)
                                            return rejectBeforeImport(
                                                    source, id, "checkpoint_file_content_removed");
                                        jdbc.update(
                                                "INSERT INTO"
                                                    + " ma.file(scope_key,file_id,name,media_type,size,sha256,content,created_at)"
                                                    + " SELECT"
                                                    + " ?,f.file_id,f.name,f.media_type,f.size,f.sha256,f.content,f.created_at"
                                                    + " FROM ma.file f JOIN ma.checkpoint_file c ON"
                                                    + " c.scope_key=f.scope_key AND"
                                                    + " c.file_id=f.file_id WHERE c.scope_key=? AND"
                                                    + " c.native_sequence=?",
                                                target.messageGroup(),
                                                source.messageGroup(),
                                                selected);
                                    }
                                    try {
                                        var event = preparedBaseline;
                                        long sequence = event.seq();
                                        var reference = journal.objects().reference(target, event);
                                        if (((Number) session(target).get("execution_epoch"))
                                                                .longValue()
                                                        + 1
                                                != reference.identity().epoch())
                                            throw conflict(
                                                    "native_prefix_changed",
                                                    "Target execution epoch changed");
                                        journal.registerRecoveryImport(
                                                target,
                                                id,
                                                reference.identity().epoch(),
                                                List.of(event),
                                                baselineSegment);
                                        jdbc.update(
                                                "UPDATE ma.session SET"
                                                    + " native_sequence=?,native_commit_id=?,checkpoint=?,checkpoint_sequence=?,execution_epoch=execution_epoch+1"
                                                    + " WHERE scope_key=?",
                                                sequence,
                                                id,
                                                journal.objects().encodeReference(reference),
                                                sequence,
                                                target.messageGroup());
                                        journal.checkpoints()
                                                .capture(
                                                        target,
                                                        new ManagedCommitPlan.Checkpoint(
                                                                reference,
                                                                event.occurredAt(),
                                                                null));
                                        if (needsWorkspace)
                                            jdbc.update(
                                                    "INSERT INTO ma.checkpoint_workspace"
                                                        + "(scope_key,native_sequence,sha256,manifest,content)"
                                                        + " SELECT ?,?,sha256,manifest,content FROM"
                                                        + " ma.checkpoint_workspace WHERE"
                                                        + " scope_key=? AND native_sequence=?",
                                                    target.messageGroup(),
                                                    sequence,
                                                    source.messageGroup(),
                                                    selected);
                                        journal.resourceEvent(
                                                target,
                                                id,
                                                source.equals(target)
                                                        ? "checkpoint.restored"
                                                        : "session.forked",
                                                Map.of(
                                                        "checkpoint_id",
                                                        operation.get("checkpoint_id"),
                                                        "source_session_id",
                                                        source.sessionId(),
                                                        "operation_id",
                                                        id));
                                        jdbc.update(
                                                "INSERT INTO"
                                                    + " ma.checkpoint_baseline(operation_scope,operation_id,target_scope,native_sequence)"
                                                    + " VALUES(?,?,?,?)",
                                                source.messageGroup(),
                                                id,
                                                target.messageGroup(),
                                                sequence);
                                        jdbc.update(
                                                "UPDATE ma.checkpoint_operation SET"
                                                    + " status='completed' WHERE scope_key=? AND"
                                                    + " operation_id=?",
                                                source.messageGroup(),
                                                id);
                                        release(source, id);
                                        return receipt(row(source, id));
                                    } catch (IllegalArgumentException invalid) {
                                        throw new IllegalStateException(
                                                "Invalid checkpoint state", invalid);
                                    }
                                }));
    }

    private SessionEvent prepareBaseline(
            ManagedScope source,
            ManagedScope target,
            String id,
            Map<String, Object> operation,
            String owner,
            SessionEvent selected) {
        try {
            var baseline =
                    nativeCodec.baseline(
                            selected.payloadJson(),
                            target.sessionId(),
                            owner,
                            !source.equals(target),
                            Map.of(
                                    "operation_id",
                                    id,
                                    "checkpoint_id",
                                    operation.get("checkpoint_id"),
                                    "source_session_id",
                                    source.sessionId()));
            var event =
                    new SessionEvent(
                            1,
                            "restore_" + ManagedScope.fingerprint(id),
                            ((Number) operation.get("target_head")).longValue() + 1,
                            ((Number) operation.get("created_at")).longValue(),
                            baseline.type(),
                            null,
                            null,
                            true,
                            baseline.payloadJson());
            inspect(event);
            return journal.objects()
                    .externalize(
                            target,
                            ((Number) session(target).get("execution_epoch")).longValue() + 1,
                            event,
                            Map.of(
                                    "operationId",
                                    id,
                                    "sourceScope",
                                    source,
                                    "sourceCheckpointId",
                                    operation.get("checkpoint_id"),
                                    "resumePhase",
                                    "BASELINE"));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Invalid checkpoint state", invalid);
        }
    }

    private NativeCheckpointCodec.Inspection inspect(SessionEvent event) {
        return nativeCodec.inspect(
                new NativeCheckpointCodec.Event(
                        event.type(),
                        event.executionRunId(),
                        event.required(),
                        event.payloadJson()));
    }

    private void requireIdle(ManagedScope scope) {
        if (jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ma.turn WHERE scope_key=? AND status NOT IN"
                                        + " ('succeeded','failed','cancelled')",
                                Long.class,
                                scope.messageGroup())
                        != 0
                || jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ma.tool_task WHERE scope_key=? AND status NOT"
                                        + " IN ('succeeded','failed','cancelled')",
                                Long.class,
                                scope.messageGroup())
                        != 0
                || jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ma.input WHERE scope_key=? AND"
                                        + " status='accepted'",
                                Long.class,
                                scope.messageGroup())
                        != 0
                || jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ma.native_tool_call WHERE scope_key=? AND"
                                        + " status='dispatched'",
                                Long.class,
                                scope.messageGroup())
                        != 0)
            throw conflict(
                    "session_not_quiescent", "Checkpoint operation requires settled execution");
    }

    private List<Map<String, Object>> lockSessions(ManagedScope source, ManagedScope target) {
        var values = new ArrayList<Map<String, Object>>();
        for (var scope : scopes(source, target)) {
            var rows =
                    jdbc.queryForList(
                            "SELECT * FROM ma.session WHERE scope_key=? FOR UPDATE",
                            scope.messageGroup());
            if (rows.isEmpty())
                throw conflict("session_not_found", "Checkpoint Session does not exist");
            values.add(rows.get(0));
        }
        return values;
    }

    private List<ManagedScope> scopes(ManagedScope source, ManagedScope target) {
        return source.equals(target)
                ? List.of(source)
                : source.messageGroup().compareTo(target.messageGroup()) < 0
                        ? List.of(source, target)
                        : List.of(target, source);
    }

    private Map<String, Object> session(ManagedScope scope) {
        return jdbc.queryForMap("SELECT * FROM ma.session WHERE scope_key=?", scope.messageGroup());
    }

    private ManagedScope scope(String key) {
        var row =
                jdbc.queryForMap(
                        "SELECT tenant,namespace,session_id FROM ma.session WHERE scope_key=?",
                        key);
        return new ManagedScope(
                (String) row.get("tenant"),
                (String) row.get("namespace"),
                (String) row.get("session_id"));
    }

    private List<Map<String, Object>> rows(ManagedScope source, String id) {
        return jdbc.queryForList(
                "SELECT * FROM ma.checkpoint_operation WHERE scope_key=? AND operation_id=?",
                source.messageGroup(),
                id);
    }

    private Map<String, Object> row(ManagedScope source, String id) {
        var values = rows(source, id);
        if (values.isEmpty())
            throw conflict("checkpoint_operation_not_found", "Checkpoint operation does not exist");
        return values.get(0);
    }

    private boolean headsMatch(
            ManagedScope source, ManagedScope target, Map<String, Object> operation) {
        return session(source).get("native_sequence").equals(operation.get("source_head"))
                && session(target).get("native_sequence").equals(operation.get("target_head"));
    }

    private boolean held(ManagedScope source, String id, ManagedScope target) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.checkpoint_hold WHERE operation_scope=? AND"
                                + " operation_id=?",
                        Long.class,
                        source.messageGroup(),
                        id)
                == scopes(source, target).size();
    }

    private Receipt reject(ManagedScope source, String id, String code) {
        jdbc.update(
                "UPDATE ma.checkpoint_operation SET status='rejected',error_code=? WHERE"
                        + " scope_key=? AND operation_id=?",
                code,
                source.messageGroup(),
                id);
        release(source, id);
        return receipt(row(source, id));
    }

    private void release(ManagedScope source, String id) {
        jdbc.update(
                "DELETE FROM ma.checkpoint_hold WHERE operation_scope=? AND operation_id=?",
                source.messageGroup(),
                id);
    }

    private Receipt receipt(Map<String, Object> row) {
        boolean workspace =
                hasSandbox(scope((String) row.get("scope_key")))
                        || hasSandbox(scope((String) row.get("target_scope")))
                        || jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM ma.checkpoint_workspace WHERE"
                                                + " scope_key=? AND native_sequence=?",
                                        Long.class,
                                        row.get("scope_key"),
                                        row.get("checkpoint_sequence"))
                                != 0;
        return new Receipt(
                (String) row.get("operation_id"),
                (String) row.get("status"),
                (String) row.get("error_code"),
                ((Number) row.get("scan_sequence")).longValue(),
                workspace);
    }

    private Map<String, Set<String>> progress(String value) {
        try {
            Map<String, List<String>> stored = mapper.readValue(value, new TypeReference<>() {});
            var result = new LinkedHashMap<String, Set<String>>();
            for (String key : List.of("tools", "runs", "actions", "workspace_changed"))
                result.put(key, new LinkedHashSet<>(stored.getOrDefault(key, List.of())));
            return result;
        } catch (Exception invalid) {
            throw new IllegalStateException("Invalid checkpoint validation progress", invalid);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    private static ManagedStateException conflict(String code, String message) {
        return new ManagedStateException(code, message);
    }
}
