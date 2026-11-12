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
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import io.agentcore.managed.server.web.managed.service.SessionEventNotifier;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
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

    private SessionServiceClient remoteReads;

    public void remoteReads(SessionServiceClient client) {
        this.remoteReads = client;
        transport.remote(client);
        suspensions.remote(client);
    }

    private <T> T remote(
            String operation,
            ManagedScope scope,
            String turn,
            Map<String, Object> arguments,
            Class<T> type) {
        var request = new LinkedHashMap<String, Object>();
        request.put("scope", scope);
        request.put("turnId", turn);
        request.put("operation", operation);
        request.put("arguments", arguments);
        return mapper.convertValue(
                ManagedServiceCalls.post(remoteReads, "prepared/query", request), type);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveInput(ManagedScope scope, Map<String, Object> input) {
        return remoteReads == null
                ? files.resolveInput(scope, input)
                : remote("resolve-input", scope, null, Map.of("input", input), Map.class);
    }

    private <T> List<T> remoteList(
            String operation,
            ManagedScope scope,
            String turn,
            Map<String, Object> arguments,
            Class<T> type) {
        var request = new LinkedHashMap<String, Object>();
        request.put("scope", scope);
        request.put("turnId", turn);
        request.put("operation", operation);
        request.put("arguments", arguments);
        return mapper.convertValue(
                ManagedServiceCalls.post(remoteReads, "prepared/query", request),
                mapper.getTypeFactory().constructCollectionType(List.class, type));
    }

    final String executionLock;
    final JdbcTemplate jdbc;
    final TransactionTemplate tx;
    final ObjectMapper mapper;
    final ManagedBudgetStore budgets;
    final ManagedPublicResources resources;
    final ManagedSessionProjections projections;
    private final ThreadLocal<Long> resourceTime = new ThreadLocal<>();
    final ManagedFiles files;
    final ManagedArtifacts artifacts;
    final ManagedCheckpoints checkpoints;
    final ManagedSuspensions suspensions;
    final ManagedCheckpointObjects objects;
    private final ManagedToolEvents toolEvents;
    final ManagedToolTransport transport;
    final SessionEventNotifier notifications;
    final RuntimeObjectStore content;

    /** Remote read facade: does not construct JDBC, transactions or local projection stores. */
    public ManagedJournalStore(
            SessionServiceClient client,
            ObjectMapper mapper,
            ManagedBudgetStore budgets,
            ManagedCheckpointObjects objects) {
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.budgets = budgets;
        this.objects = Objects.requireNonNull(objects);
        this.content = objects.backend();
        this.executionLock = null;
        this.jdbc = null;
        this.tx = null;
        this.resources = null;
        this.projections = null;
        this.files = null;
        this.artifacts = null;
        this.checkpoints = null;
        this.toolEvents = null;
        this.notifications = null;
        this.transport = new ManagedToolTransport(client, this.mapper);
        this.suspensions = new ManagedSuspensions(null, this.mapper);
        remoteReads(client);
    }

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
        this.content = objects.backend();
        this.budgets = budgets;
        this.resources = new ManagedPublicResources(source, mapper);
        this.projections =
                new ManagedSessionProjections(source, mapper, notifications, objects.backend());
        this.files = new ManagedFiles(source, this);
        this.artifacts = new ManagedArtifacts(source, mapper, this);
        this.checkpoints = new ManagedCheckpoints(source, mapper);
        this.executionLock = ManagedDatabaseLocks.execution(source);
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

    @FunctionalInterface
    public interface PreviewFactory {
        Consumer<ManagedSessionPreviews.TextDelta> begin(
                ManagedScope scope, String turn, String call, long epoch, String purpose);
    }

    private PreviewFactory previewFactory;

    public void previewFactory(PreviewFactory factory) {
        this.previewFactory = factory;
    }

    private ManagedSessionPreviews previews;

    public void previews(ManagedSessionPreviews value) {
        previews = value;
    }

    Consumer<ManagedSessionPreviews.TextDelta> preview(
            ManagedScope scope, String turn, String call, long epoch, String purpose) {
        if (previewFactory != null) return previewFactory.begin(scope, turn, call, epoch, purpose);
        return previews == null ? null : previews.begin(scope, turn, call, epoch, purpose);
    }

    private ManagedSessionLogArchive archive;

    public void archive(ManagedSessionLogArchive value) {
        archive = value;
    }

    void archive(SessionEvent event, ManagedScope scope) {
        if (archive != null) archive.offer(scope, event);
    }

    public ManagedToolEvents toolEvents() {
        return toolEvents;
    }

    public ManagedSuspensions suspensions() {
        return suspensions;
    }

    /** Startup metadata; normal accepted model facts advance the call-local anchor. */
    public String modelAnchor(ManagedScope scope, String turn) {
        if (remoteReads != null) return remote("model-anchor", scope, turn, Map.of(), String.class);
        return jdbc.queryForObject(
                "SELECT model_anchor FROM ma.turn WHERE scope_key=? AND turn_id=?",
                String.class,
                scope.messageGroup(),
                turn);
    }

    public record ExecutionPosition(
            int iteration, boolean reasoningCompleted, long firstSequence) {}

    ExecutionPosition executionPosition(ManagedScope scope, String turn) {
        return jdbc.queryForObject(
                "SELECT iteration_position,reasoning_completed,first_recovery_sequence FROM ma.turn"
                        + " WHERE scope_key=? AND turn_id=?",
                (row, index) ->
                        new ExecutionPosition(row.getInt(1), row.getBoolean(2), row.getLong(3)),
                scope.messageGroup(),
                turn);
    }

    public record ExecutionSnapshot(
            SessionRecoveryHead head,
            String modelAnchor,
            Map<String, Object> context,
            ExecutionPosition position) {}

    /** One statement snapshot per fragment; never reused as commit authority. */
    public ExecutionSnapshot executionSnapshot(ManagedScope scope, String turn) {
        if (remoteReads != null)
            return remote("snapshot", scope, turn, Map.of(), ExecutionSnapshot.class);
        var row =
                jdbc.queryForMap(
                        "SELECT"
                            + " s.native_sequence,s.execution_epoch,s.native_commit_id,s.checkpoint,"
                            + "s.checkpoint_sequence,s.configuration,s.lifecycle_revision,"
                            + "t.execution_token,t.model_anchor,t.applied_input_sequence,"
                            + "t.iteration_position,t.reasoning_completed,t.first_recovery_sequence"
                            + " FROM ma.session s JOIN ma.turn t ON t.scope_key=s.scope_key WHERE"
                            + " s.scope_key=? AND t.turn_id=?",
                        scope.messageGroup(),
                        turn);
        return new ExecutionSnapshot(
                sessionHead(scope, row, row),
                (String) row.get("model_anchor"),
                checkpointContext(row),
                new ExecutionPosition(
                        ((Number) row.get("iteration_position")).intValue(),
                        Boolean.TRUE.equals(row.get("reasoning_completed")),
                        row.get("first_recovery_sequence") == null
                                ? 0
                                : ((Number) row.get("first_recovery_sequence")).longValue()));
    }

    public ModelCallResult replayModel(
            ManagedScope scope, String turn, String hash, long checkpoint) {
        if (remoteReads != null)
            return remote(
                    "replay-model",
                    scope,
                    turn,
                    Map.of("hash", hash, "checkpoint", checkpoint),
                    ModelCallResult.class);
        var calls =
                jdbc.queryForList(
                        "SELECT model_call_id FROM ma.model_call_result WHERE scope_key=? AND"
                                + " turn_id=? AND replay_hash=? AND status='completed' AND"
                                + " completion_sequence>? ORDER BY completion_sequence LIMIT 1",
                        String.class,
                        scope.messageGroup(),
                        turn,
                        hash,
                        checkpoint);
        return calls.isEmpty() ? null : modelResult(scope, turn, calls.get(0));
    }

    public boolean hasSuspension(ManagedScope scope, String turn) {
        if (remoteReads != null) return remote("suspended", scope, turn, Map.of(), Boolean.class);
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.turn_suspension WHERE scope_key=? AND turn_id=?",
                        Long.class,
                        scope.messageGroup(),
                        turn)
                > 0;
    }

    public List<String> pendingToolIds(ManagedScope scope, String turn) {
        if (remoteReads != null)
            return remoteList("pending-tools", scope, turn, Map.of(), String.class);
        return jdbc.query(
                "SELECT task_id FROM ma.tool_task WHERE scope_key=? AND turn_id=?"
                        + " AND result IS NULL ORDER BY task_id",
                (row, index) -> row.getString(1),
                scope.messageGroup(),
                turn);
    }

    /** One startup read of frozen resources and the input application watermark. */
    public Map<String, Object> checkpointContext(ManagedScope scope, String turn) {
        if (remoteReads != null) return remote("context", scope, turn, Map.of(), Map.class);
        var row =
                jdbc.queryForMap(
                        "SELECT s.configuration,s.lifecycle_revision,t.applied_input_sequence FROM"
                                + " ma.session s JOIN ma.turn t ON t.scope_key=s.scope_key WHERE"
                                + " s.scope_key=? AND t.turn_id=?",
                        scope.messageGroup(),
                        turn);
        return checkpointContext(row);
    }

    private Map<String, Object> checkpointContext(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("sessionConfiguration", parse((String) row.get("configuration"), Map.class));
        result.put(
                "configurationHash", ManagedScope.fingerprint((String) row.get("configuration")));
        result.put("lifecycleRevision", row.get("lifecycle_revision"));
        result.put("appliedInputSeq", row.get("applied_input_sequence"));
        return result;
    }

    /** Pending operations plus completed partial results belonging to an unreconciled tool call. */
    public List<ToolTask> continuationTools(ManagedScope scope, String turn) {
        if (remoteReads != null)
            return remoteList("continuation-tools", scope, turn, Map.of(), ToolTask.class);
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

    /** Native preparation belongs to the AgentScope caller, outside the platform transaction. */
    public ManagedModelResults.RequestPosition modelRequestPosition(ManagedScope scope, String id) {
        if (remoteReads != null)
            return remote(
                    "model-position",
                    scope,
                    null,
                    Map.of("id", id),
                    ManagedModelResults.RequestPosition.class);
        return new ManagedModelResults(jdbc, mapper, objects.backend()).requestPosition(scope, id);
    }

    ManagedProcessOwnership processOwnership;

    public void processOwnership(ManagedProcessOwnership ownership) {
        this.processOwnership = ownership;
    }

    <T> T owned(Supplier<T> work) {
        return processOwnership == null ? work.get() : processOwnership.transaction(work);
    }

    public boolean cancelRequested(ManagedScope scope, String turn) {
        if (remoteReads != null) return remote("cancelled", scope, turn, Map.of(), Boolean.class);
        return cancelRequested(jdbc, scope, turn);
    }

    static boolean cancelRequested(JdbcTemplate jdbc, ManagedScope scope, String turn) {
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
        if (remoteReads != null)
            return remote("tools-unsettled", scope, turn, Map.of(), Boolean.class);
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.tool_task WHERE scope_key=? AND turn_id=? AND"
                                + " result IS NULL",
                        Long.class,
                        scope.messageGroup(),
                        turn)
                > 0;
    }

    public long publicHead(ManagedScope scope) {
        if (queryViews == null)
            throw new IllegalStateException("A separate projection query backend is required");
        return queryViews.progress(scope).visibleSequence();
    }

    public SessionRecoveryHead head(ManagedScope scope) {
        if (remoteReads != null)
            return remote("head", scope, null, Map.of(), SessionRecoveryHead.class);
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

    private <T> T retryPreparation(Supplier<T> action) {
        for (int attempt = 0; ; attempt++) {
            try {
                return action.get();
            } catch (ManagedStateException changed) {
                if (!"projection_preparation_changed".equals(changed.code())) throw changed;
                if (attempt >= 2)
                    throw new ManagedTransportException(
                            "Concurrent fact preparation; retry delivery", changed);
            }
        }
    }

    long resourceTimestamp() {
        var time = resourceTime.get();
        return time == null ? System.currentTimeMillis() : time;
    }

    private <T> T preparedToolFacts(
            ManagedScope scope,
            List<ManagedFactBundles.Content> facts,
            long time,
            Supplier<T> action) {
        Long previous = resourceTime.get();
        resourceTime.set(time);
        try {
            return projections.prepared(scope, facts, action);
        } finally {
            if (previous == null) resourceTime.remove();
            else resourceTime.set(previous);
        }
    }

    /** Freeze resource content before its caller's transaction; identities may be assigned under lock. */
    public <T> T prepareResourceEvent(
            ManagedScope scope, String type, Map<String, Object> data, Supplier<T> action) {
        if (projections.preparing()) return action.get();
        return projections.prepared(
                scope, List.of(new ManagedFactBundles.Content(type, json(data))), action);
    }

    /** Resource facts are written by Agent Service; execution facts require the fenced HarnessRuntime commit. */
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
        Supplier<Object> action =
                () -> {
                    Runnable write =
                            () -> {
                                lockHead(scope);
                                appendPublic(scope, id, type, json(data));
                            };
                    if (TransactionSynchronizationManager.isActualTransactionActive()
                            && TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))
                        write.run();
                    else tx.executeWithoutResult(ignored -> write.run());
                    return null;
                };
        if (projections.preparing()) action.get();
        else prepareResourceEvent(scope, type, data, action);
    }

    public static String publicTurnStatus(String status) {
        return "succeeded".equals(status)
                ? "completed"
                : "waiting_input".equals(status) ? "waiting" : status;
    }

    public ManagedCheckpoints checkpoints() {
        return checkpoints;
    }

    public List<SessionEvent> recoveryEvents(ManagedScope scope, long after, int limit) {
        if (remoteReads != null)
            return remoteList(
                    "recovery",
                    scope,
                    null,
                    Map.of("after", after, "limit", limit),
                    SessionEvent.class);
        return recoveryManifests(scope, after, limit).stream()
                .map(event -> objects.hydrate(scope, event))
                .toList();
    }

    /** Kernel bootstrap never traverses completed Turns or obsolete checkpoint bodies. */
    public List<SessionEvent> recoveryBootstrap(
            ManagedScope scope,
            String turn,
            long first,
            long after,
            int limit,
            SessionRecoveryHead view) {
        if (remoteReads != null)
            return remoteList(
                    "bootstrap",
                    scope,
                    turn,
                    Map.of("first", first, "after", after, "limit", limit, "view", view),
                    SessionEvent.class);
        bounds(after, limit);
        if (after >= view.sequence()) return List.of();
        var result = new ArrayList<SessionEvent>();
        long checkpoint = view.checkpointSequence();
        long scanned = after;
        long expected = Math.max(after + 1, first);
        var rows =
                first == 0
                        ? List.<Map<String, Object>>of()
                        : jdbc.queryForList(
                                "SELECT first_sequence,last_sequence,recovery_ref AS object_ref"
                                    + " FROM ma.runtime_commit WHERE scope_key=? AND recovery_ref"
                                    + " IS NOT NULL AND turn_id=? AND last_sequence>? AND"
                                    + " first_sequence<=? ORDER BY first_sequence LIMIT 128",
                                scope.messageGroup(),
                                turn,
                                after,
                                view.sequence());
        if (view.checkpoint() != null && checkpoint > after && (first == 0 || checkpoint < first))
            result.add(recoveryBaseline(view.checkpoint()));
        for (var row : rows) {
            long start = ((Number) row.get("first_sequence")).longValue();
            long end = ((Number) row.get("last_sequence")).longValue();
            if (start > expected || end < expected)
                throw conflict(
                        "recovery_sequence_conflict", "Required execution fact segment is missing");
            var ref = parse((String) row.get("object_ref"), RuntimeObjectStore.Reference.class);
            if (!scope.messageGroup().equals(ref.scope()) || !"recovery".equals(ref.kind()))
                throw conflict("runtime_object_invalid", "Recovery scope differs");
            var events =
                    parse(
                            new String(content.read(ref), StandardCharsets.UTF_8),
                            SessionEvent[].class);
            long sequence = start;
            for (var event : events) {
                if (event.seq() != sequence++ || !turn.equals(event.turnId()))
                    throw conflict(
                            "recovery_sequence_conflict", "Recovery segment contents differ");
                if (event.seq() <= after || event.seq() > view.sequence()) continue;
                if (event.seq() == checkpoint) result.add(recoveryBaseline(view.checkpoint()));
                else if (event.seq() > checkpoint
                        || !List.of("state/checkpoint", "migration/baseline", "state/restored")
                                .contains(event.type())) result.add(event);
            }
            if (sequence - 1 != end)
                throw conflict("recovery_sequence_conflict", "Recovery segment is incomplete");
            scanned = Math.min(end, view.sequence());
            expected = scanned + 1;
            if (result.size() >= limit) break;
        }
        boolean exhausted = rows.size() < 128 && result.size() < limit;
        if (first > 0 && exhausted && scanned < view.sequence())
            throw conflict("recovery_sequence_conflict", "Required execution fact tail is missing");
        long marker = first == 0 ? view.sequence() : scanned;
        if (marker > after && result.stream().noneMatch(event -> event.seq() == marker))
            result.add(
                    new SessionEvent(
                            1,
                            "recovery_view_" + marker,
                            marker,
                            1,
                            "managed/recovery_view",
                            null,
                            null,
                            false,
                            "{}"));
        return result.stream()
                .sorted(Comparator.comparingLong(SessionEvent::seq))
                .limit(limit)
                .toList();
    }

    SessionEvent recoveryBaseline(ManagedCheckpointObjects.Reference reference) {
        var id = reference.identity();
        return new SessionEvent(
                1,
                "kernel_snapshot_" + id.checkpointId(),
                id.nativeSequence(),
                1,
                "migration/baseline",
                id.runId(),
                id.turnId(),
                true,
                JsonUtils.getJsonCodec()
                        .toJson(Map.of(ManagedCheckpointObjects.REFERENCE, reference)));
    }

    /** Metadata-only startup replay; checkpoint state bytes remain in private objects. */
    List<SessionEvent> recoveryManifests(ManagedScope scope, long after, int limit) {
        bounds(after, limit);
        long target =
                Math.min(
                        Math.addExact(after, limit),
                        jdbc.queryForObject(
                                "SELECT native_sequence FROM ma.session WHERE scope_key=?",
                                Long.class,
                                scope.messageGroup()));
        var refs =
                jdbc.queryForList(
                        "SELECT recovery_ref FROM ma.runtime_commit WHERE scope_key=? AND"
                            + " recovery_ref IS NOT NULL AND last_sequence>? AND first_sequence<=?"
                            + " ORDER BY first_sequence",
                        String.class,
                        scope.messageGroup(),
                        after,
                        Math.addExact(after, limit));
        var result = new ArrayList<SessionEvent>();
        for (String value : refs) {
            var ref = parse(value, RuntimeObjectStore.Reference.class);
            if (!ref.scope().equals(scope.messageGroup()) || !"recovery".equals(ref.kind()))
                throw conflict("runtime_object_invalid", "Native scope differs");
            var events =
                    parse(
                            new String(content.read(ref), StandardCharsets.UTF_8),
                            SessionEvent[].class);
            for (var event : events)
                if (event.seq() > after && event.seq() <= target) {
                    if (event.seq() != after + result.size() + 1)
                        throw conflict("native_sequence_conflict", "Recovery prefix is incomplete");
                    result.add(event);
                }
        }
        if (result.size() != Math.max(0, target - after))
            throw conflict("native_sequence_conflict", "Committed native segment is missing");
        return List.copyOf(result);
    }

    private ManagedQueryViews queryViews;

    public void queryViews(ManagedQueryViews views) {
        this.queryViews = views;
        resources.queryViews(views);
        projections.queryViews(views);
    }

    public List<PublicEvent> publicEvents(ManagedScope scope, long after, int limit) {
        if (queryViews != null)
            return queryViews.events(scope, after, limit).stream()
                    .map(
                            e ->
                                    new PublicEvent(
                                            e.sequence(),
                                            e.sourceId(),
                                            e.type(),
                                            e.payload(),
                                            e.occurredAt()))
                    .toList();
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
        if (queryViews != null)
            return queryViews.events(scope, after, limit).stream()
                    .filter(e -> e.sequence() <= through)
                    .map(
                            e ->
                                    new PublicEventReference(
                                            e.sequence(), e.sourceId(), e.type(), e.occurredAt()))
                    .toList();
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
        if (remoteReads != null)
            return Optional.ofNullable(
                    remote("tool", scope, null, Map.of("taskId", taskId), ToolTask.class));
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
        if (remoteReads != null)
            return remoteList("native-tools", scope, turn, Map.of("call", call), ToolTask.class);
        return jdbc.query(
                "SELECT * FROM ma.tool_task WHERE scope_key=? AND turn_id=? AND"
                        + " native_tool_call_id=? ORDER BY task_id",
                this::readToolTask,
                scope.messageGroup(),
                turn,
                call);
    }

    public List<ToolTask> mcpBatches(ManagedScope scope, String turn) {
        if (remoteReads != null)
            return remoteList("mcp-batches", scope, turn, Map.of(), ToolTask.class);
        return jdbc.query(
                "SELECT * FROM ma.tool_task WHERE scope_key=? AND turn_id=? AND"
                        + " tool_name='mcp.batch' ORDER BY task_id",
                this::readToolTask,
                scope.messageGroup(),
                turn);
    }

    public Optional<ToolTask> mcpBatchForCall(ManagedScope scope, String turn, String call) {
        if (remoteReads != null)
            return Optional.ofNullable(
                    remote("mcp-batch", scope, turn, Map.of("call", call), ToolTask.class));
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
            Map<String, Object> event;
            try {
                event = resources.resource(scope, null, "tools", turn + ":" + nativeId);
            } catch (ManagedStateException absent) {
                if ("resource_not_found".equals(absent.code())) continue;
                throw absent;
            }
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
        if (remoteReads != null)
            return Optional.ofNullable(
                    remote("action", scope, turn, Map.of("id", id), Action.class));
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
                        throw conflict("stale_tool_worker", "Tool worker changed");
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

    /** Tool result facts are fenced by stable external execution identity, independent of HarnessRuntime epoch. */
    public SessionCommitReceipt commitToolResult(ManagedToolResult result) {
        return retryPreparation(() -> commitToolResultPrepared(result));
    }

    private SessionCommitReceipt commitToolResultPrepared(ManagedToolResult result) {
        var task =
                tool(result.scope(), result.taskId())
                        .orElseThrow(() -> conflict("tool_not_found", "Tool task does not exist"));
        if (task.result() != null) return commitToolResult(result, false);
        var metadata = new LinkedHashMap<String, Object>();
        toolEvents
                .cursor(result.scope(), result.taskId())
                .ifPresent(
                        cursor -> {
                            metadata.put("last_chunk_seq", cursor.chunkSeq());
                            metadata.put("output_offset", cursor.offset());
                            metadata.put(
                                    "log_status",
                                    cursor.hasGap()
                                            ? "gapped"
                                            : "none".equals(cursor.mode())
                                                    ? "not_supported"
                                                    : "complete");
                        });
        long time = System.currentTimeMillis();
        var facts = new ArrayList<ManagedFactBundles.Content>();
        facts.addAll(artifacts.captureFacts(result, task, time));
        var fact = operationFact(result, task, result.status(), metadata);
        if (fact != null) facts.add(fact);
        if (ManagedToolOperations.EXPORT_ARTIFACT.equals(task.name())) {
            fact = operationFact(result, task, "failed", metadata);
            if (fact != null) facts.add(fact);
        }
        return preparedToolFacts(
                result.scope(), facts, time, () -> commitToolResult(result, false));
    }

    private SessionCommitReceipt commitToolResult(ManagedToolResult result, boolean received) {
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
                        return new SessionCommitReceipt(
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
                    if (ManagedToolOperations.EXPORT_ARTIFACT.equals(task.name())
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
                    var metadata = new LinkedHashMap<String, Object>();
                    output.ifPresent(
                            cursor -> {
                                metadata.put("last_chunk_seq", cursor.chunkSeq());
                                metadata.put("output_offset", cursor.offset());
                                metadata.put(
                                        "log_status",
                                        cursor.hasGap()
                                                ? "gapped"
                                                : "none".equals(cursor.mode())
                                                        ? "not_supported"
                                                        : "complete");
                            });
                    var operation = operationFact(result, task, committedStatus, metadata);
                    if (operation != null)
                        appendPublic(
                                scope, result.batchId(), operation.type(), operation.payload());
                    return new SessionCommitReceipt(
                            result.batchId(),
                            hash,
                            "committed",
                            null,
                            head.sequence(),
                            head.epoch());
                });
    }

    private ManagedFactBundles.Content operationFact(
            ManagedToolResult result, ToolTask task, String status, Map<String, Object> metadata) {
        var arguments = parse(task.arguments(), Map.class);
        var nativeCall = arguments.get("native_tool_call_id");
        if (nativeCall instanceof String call && call.startsWith("framework_")) return null;
        var data = new LinkedHashMap<String, Object>();
        data.put("turn_id", task.turnId());
        if (nativeCall instanceof String call && !call.isBlank()) data.put("tool_call_id", call);
        data.put("operation_tool_call_id", task.toolCallId());
        data.put("task_id", task.id());
        data.put("operation_execution_id", task.executionId());
        data.put("name", task.name());
        data.put("status", status);
        for (String field : List.of("last_chunk_seq", "output_offset", "log_status"))
            if (metadata.containsKey(field)) data.put(field, metadata.get(field));
        data.put("source", Map.of("kind", "tool", "event_id", result.batchId()));
        return new ManagedFactBundles.Content("tool.operation.completed", json(data));
    }

    private ManagedFactBundles.Content toolFact(ManagedToolEvent event, ToolTask task) {
        var arguments = parse(task.arguments(), Map.class);
        var nativeCall = arguments.get("native_tool_call_id");
        if (nativeCall instanceof String call && call.startsWith("framework_")) return null;
        var data = new LinkedHashMap<String, Object>();
        data.put("turn_id", task.turnId());
        if (nativeCall instanceof String call && !call.isBlank()) data.put("tool_call_id", call);
        data.put("operation_tool_call_id", task.toolCallId());
        data.put("task_id", task.id());
        data.put("operation_execution_id", task.executionId());
        data.put("operation_name", task.name());
        data.put("source_seq", event.sourceSeq());
        data.put("source", Map.of("kind", "tool", "event_id", event.eventId()));
        data.putAll(event.data());
        if ("tool.delta".equals(event.type())) {
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
        if ("tool.output_gap".equals(event.type())) data.put("log_status", "gapped");
        return new ManagedFactBundles.Content(event.type(), json(data));
    }

    public record ToolEventsCommit(SessionCommitReceipt receipt, List<ManagedToolResult> results) {}

    /** Tool facts have independent source sequences; only a covered final result wakes HarnessRuntime. */
    public ToolEventsCommit commitToolEvent(ManagedToolEvent event) {
        return retryPreparation(() -> commitToolEventPrepared(event));
    }

    private ToolEventsCommit commitToolEventPrepared(ManagedToolEvent event) {
        if (toolEvents.applied(event)) return applyToolEvent(event);
        var task =
                tool(event.scope(), event.taskId())
                        .orElseThrow(() -> conflict("tool_not_found", "Tool task does not exist"));
        long time = System.currentTimeMillis();
        var facts = new ArrayList<ManagedFactBundles.Content>();
        for (var candidate : toolEvents.candidates(event)) {
            if ("tool.result".equals(candidate.type())) {
                var result =
                        mapper.convertValue(
                                candidate.data().get("result"), ManagedToolResult.class);
                facts.addAll(artifacts.captureFacts(result, task, time));
                var fact = operationFact(result, task, result.status(), candidate.data());
                if (fact != null) facts.add(fact);
                if (ManagedToolOperations.EXPORT_ARTIFACT.equals(task.name())) {
                    fact = operationFact(result, task, "failed", candidate.data());
                    if (fact != null) facts.add(fact);
                }
            } else {
                var fact = toolFact(candidate, task);
                if (fact != null) facts.add(fact);
            }
        }
        return preparedToolFacts(event.scope(), facts, time, () -> applyToolEvent(event));
    }

    private ToolEventsCommit applyToolEvent(ManagedToolEvent event) {
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
                    var fact = toolFact(next, task);
                    if (fact != null)
                        appendPublic(next.scope(), next.eventId(), fact.type(), fact.payload());
                });
        for (var confirmedTask : confirmedTasks)
            ManagedRuntimeLog.tool(
                    log,
                    "tool_worker.tool.result.committed",
                    event.scope(),
                    confirmedTask.getKey(),
                    "status",
                    confirmedTask.getKey().status(),
                    "source_event_id",
                    confirmedTask.getValue());
        var head = head(event.scope());
        return new ToolEventsCommit(
                new SessionCommitReceipt(
                        event.eventId(),
                        toolEvents.hash(event),
                        toolEvents.applied(event) ? "committed" : "buffered",
                        null,
                        head.sequence(),
                        head.epoch()),
                List.copyOf(confirmed));
    }

    Map<String, Object> lockSessionHead(ManagedScope scope) {
        var rows =
                jdbc.queryForList(
                        "SELECT"
                            + " native_sequence,execution_epoch,native_commit_id,checkpoint,checkpoint_sequence,current_turn_id,accepted_sequence,commit_version,usage_summary"
                            + " FROM ma.session WHERE scope_key=?"
                                + executionLock,
                        scope.messageGroup());
        if (rows.isEmpty()) throw conflict("session_not_found", "Session does not exist");
        return rows.get(0);
    }

    SessionRecoveryHead sessionHead(
            ManagedScope scope, Map<String, Object> session, Map<String, Object> turn) {
        return new SessionRecoveryHead(
                ((Number) session.get("native_sequence")).longValue(),
                ((Number) session.get("execution_epoch")).longValue(),
                (String) turn.get("execution_token"),
                (String) session.get("native_commit_id"),
                objects.decodeReference(scope, (String) session.get("checkpoint")),
                ((Number) session.get("checkpoint_sequence")).longValue());
    }

    private SessionRecoveryHead lockHead(ManagedScope scope) {
        var locked =
                jdbc.queryForList(
                        "SELECT scope_key FROM ma.session WHERE scope_key=?" + executionLock,
                        scope.messageGroup());
        if (locked.isEmpty()) throw conflict("session_not_found", "Session does not exist");
        return readHead(scope);
    }

    private SessionRecoveryHead readHead(ManagedScope scope) {
        var rows =
                jdbc.query(
                        "SELECT"
                            + " s.native_sequence,s.execution_epoch,s.native_commit_id,s.checkpoint,s.checkpoint_sequence,t.execution_token"
                            + " FROM ma.session s LEFT JOIN ma.turn t ON s.scope_key=t.scope_key"
                            + " AND s.current_turn_id=t.turn_id WHERE s.scope_key=?",
                        (row, index) ->
                                new SessionRecoveryHead(
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

    void nativeToolFact(ManagedScope scope, String turn, ManagedCommitPlan.ToolCall call) {
        if (call.dispatched()) {
            if (jdbc.queryForObject(
                            "SELECT COUNT(*) FROM ma.native_tool_call WHERE scope_key=? AND"
                                    + " turn_id=? AND tool_call_id=?",
                            Long.class,
                            scope.messageGroup(),
                            turn,
                            call.callId())
                    == 0)
                jdbc.update(
                        "INSERT INTO ma.native_tool_call(scope_key,turn_id,tool_call_id,status)"
                                + " VALUES(?,?,?,'dispatched')",
                        scope.messageGroup(),
                        turn,
                        call.callId());
            else
                jdbc.update(
                        "UPDATE ma.native_tool_call SET status='dispatched' WHERE scope_key=? AND"
                                + " turn_id=? AND tool_call_id=?",
                        scope.messageGroup(),
                        turn,
                        call.callId());
        } else
            jdbc.update(
                    "UPDATE ma.native_tool_call SET status='resolved' WHERE scope_key=? AND"
                            + " turn_id=? AND tool_call_id=?",
                    scope.messageGroup(),
                    turn,
                    call.callId());
    }

    public record FinalizationState(Map<String, Object> data, boolean completed) {}

    public record FinalizationModel(String modelCallId, String requestHash) {}

    boolean workspaceDataSource(DataSource source) {
        return jdbc.getDataSource() == source;
    }

    public record ExecutionOwner(ManagedScope scope, String turnId, long epoch, String token) {}

    private Map<String, Object> finalizationIo(
            ExecutionOwner execution, String frame, int slot, String hash) {
        var rows =
                jdbc.queryForList(
                        "SELECT turn_id,request_hash,read_ref,write_result FROM ma.finalization_io"
                                + " WHERE scope_key=? AND frame_id=? AND slot=?",
                        execution.scope().messageGroup(),
                        frame,
                        slot);
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        if (!execution.turnId().equals(row.get("turn_id")) || !hash.equals(row.get("request_hash")))
            throw conflict(
                    "finalization_io_conflict",
                    "Private storage operation differs from its frozen identity");
        return row;
    }

    private <T> T finalizationTransaction(
            ExecutionOwner execution, String frame, Supplier<T> action) {
        return owned(
                () ->
                        tx.execute(
                                ignored -> {
                                    var head = lockHead(execution.scope());
                                    if (head.epoch() != execution.epoch()
                                            || !execution.token().equals(head.token())
                                            || !execution
                                                    .turnId()
                                                    .equals(currentTurn(execution.scope())))
                                        throw conflict(
                                                "stale_execution",
                                                "Private storage owner is no longer current");
                                    if (processOwnership != null)
                                        processOwnership.requireTurn(
                                                execution.scope(), execution.turnId());
                                    if (jdbc.queryForObject(
                                                    "SELECT count(*) FROM ma.finalization_frame"
                                                        + " WHERE scope_key=? AND turn_id=? AND"
                                                        + " frame_id=? AND completion_sequence IS"
                                                        + " NULL",
                                                    Integer.class,
                                                    execution.scope().messageGroup(),
                                                    execution.turnId(),
                                                    frame)
                                            != 1)
                                        throw conflict(
                                                "finalization_conflict",
                                                "Private storage has no open frame");
                                    return action.get();
                                }));
    }

    public String finalizationRead(
            ExecutionOwner execution, String frame, int slot, String hash, Supplier<String> read) {
        var existing = finalizationIo(execution, frame, slot, hash);
        if (existing == null) {
            // Read and object upload are outside the short execution metadata transaction.
            byte[] content = read.get().getBytes(StandardCharsets.UTF_8);
            var reference =
                    objects.backend()
                            .put(
                                    execution.scope().messageGroup(),
                                    "harness-read",
                                    ManagedScope.fingerprint(
                                            execution.turnId(),
                                            frame,
                                            String.valueOf(slot),
                                            hash,
                                            RuntimeObjectStore.sha256(content)),
                                    content);
            existing =
                    finalizationTransaction(
                            execution,
                            frame,
                            () -> {
                                var prior = finalizationIo(execution, frame, slot, hash);
                                if (prior != null) return prior;
                                String encoded = json(reference);
                                jdbc.update(
                                        "INSERT INTO"
                                            + " ma.finalization_io(scope_key,turn_id,frame_id,slot,request_hash,read_ref)"
                                            + " VALUES(?,?,?,?,?,?)",
                                        execution.scope().messageGroup(),
                                        execution.turnId(),
                                        frame,
                                        slot,
                                        hash,
                                        encoded);
                                return Map.<String, Object>of("read_ref", encoded);
                            });
        }
        if (!(existing.get("read_ref") instanceof String encoded))
            throw conflict("finalization_io_conflict", "Expected a private read outcome");
        var reference = parse(encoded, RuntimeObjectStore.Reference.class);
        if (!execution.scope().messageGroup().equals(reference.scope())
                || !"harness-read".equals(reference.kind()))
            throw conflict("finalization_io_conflict", "Private read belongs to another scope");
        return new String(objects.backend().read(reference), StandardCharsets.UTF_8);
    }

    public String finalizationWrite(
            ExecutionOwner execution,
            String frame,
            int slot,
            String hash,
            Supplier<String> mutation) {
        // The workspace adapter must enlist its JDBC connection in this exact transaction.
        return finalizationTransaction(
                execution,
                frame,
                () -> {
                    var prior = finalizationIo(execution, frame, slot, hash);
                    if (prior != null) {
                        if (!(prior.get("write_result") instanceof String outcome))
                            throw conflict(
                                    "finalization_io_conflict", "Expected a private write outcome");
                        return outcome;
                    }
                    String outcome = mutation.get();
                    jdbc.update(
                            "INSERT INTO"
                                + " ma.finalization_io(scope_key,turn_id,frame_id,slot,request_hash,write_result)"
                                + " VALUES(?,?,?,?,?,?)",
                            execution.scope().messageGroup(),
                            execution.turnId(),
                            frame,
                            slot,
                            hash,
                            outcome);
                    return outcome;
                });
    }

    public FinalizationState finalization(ManagedScope scope, String turn) {
        if (remoteReads != null)
            return remote("finalization", scope, turn, Map.of(), FinalizationState.class);
        var rows =
                jdbc.queryForList(
                        "SELECT frame_id,start_sequence,capture_ref,completion_sequence FROM"
                                + " ma.finalization_frame WHERE scope_key=? AND turn_id=? ORDER BY"
                                + " start_sequence DESC LIMIT 1",
                        scope.messageGroup(),
                        turn);
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        var ref = parse((String) row.get("capture_ref"), ManagedCheckpointObjects.Reference.class);
        if (!scope.equals(ref.identity().scope())
                || !turn.equals(ref.identity().turnId())
                || ref.identity().nativeSequence()
                        != ((Number) row.get("start_sequence")).longValue())
            throw conflict("finalization_conflict", "Frame capture identity differs");
        var image = objects.read(ref);
        var data = parse(image.nativePayloadJson(), Map.class);
        if (!row.get("frame_id").equals(data.get("id")))
            throw conflict("finalization_conflict", "Frozen frame identity differs");
        return new FinalizationState(data, row.get("completion_sequence") != null);
    }

    public List<FinalizationModel> finalizationModels(
            ManagedScope scope, String turn, String frame, int slot) {
        if (remoteReads != null)
            return remoteList(
                    "finalization-models",
                    scope,
                    turn,
                    Map.of("frame", frame, "slot", slot),
                    FinalizationModel.class);
        return jdbc.query(
                "SELECT model_call_id,request_hash FROM ma.finalization_model WHERE scope_key=? AND"
                        + " turn_id=? AND frame_id=? AND slot=? ORDER BY sequence",
                (row, index) -> new FinalizationModel(row.getString(1), row.getString(2)),
                scope.messageGroup(),
                turn,
                frame,
                slot);
    }

    public ModelCallResult modelResult(ManagedScope scope, String turn, String modelCallId) {
        if (remoteReads != null)
            return remote(
                    "model",
                    scope,
                    turn,
                    Map.of("modelCallId", modelCallId),
                    ModelCallResult.class);
        return new ManagedModelResults(jdbc, mapper, objects.backend())
                .read(scope, turn, modelCallId);
    }

    public ManagedSessionProjections projections() {
        return projections;
    }

    void appendPublic(ManagedScope scope, String id, String type, String payload) {
        projections.capture(scope, id, type, payload, System.currentTimeMillis());
    }

    public SessionCommitReceipt receipt(ManagedScope scope, String id) {
        if (remoteReads != null)
            return remote(
                    "receipt", scope, null, Map.of("commitId", id), SessionCommitReceipt.class);
        var rows =
                jdbc.queryForList(
                        "SELECT receipt FROM ma.runtime_commit WHERE scope_key=? AND commit_id=?",
                        String.class,
                        scope.messageGroup(),
                        id);
        return rows.isEmpty() ? null : parse(rows.get(0), SessionCommitReceipt.class);
    }

    static void verifyReceipt(SessionCommitReceipt receipt, String hash) {
        if (!receipt.hash().equals(hash))
            throw conflict(
                    "batch_content_conflict", "Commit identity was reused with different content");
    }

    RuntimeObjectStore.Reference stageNative(ManagedScope scope, List<SessionEvent> events) {
        if (events.isEmpty()) return null;
        byte[] bytes = json(events).getBytes(StandardCharsets.UTF_8);
        return content.put(
                scope.messageGroup(), "recovery", RuntimeObjectStore.sha256(bytes), bytes);
    }

    void registerRecoveryImport(
            ManagedScope scope,
            String id,
            long epoch,
            List<SessionEvent> events,
            RuntimeObjectStore.Reference ref) {
        tx.executeWithoutResult(
                ignored -> {
                    var version = projections.boundary(scope);
                    long through = events.get(events.size() - 1).seq();
                    var receipt =
                            new SessionCommitReceipt(
                                    id, ref.sha256(), "committed", null, through, epoch);
                    jdbc.update(
                            "INSERT INTO"
                                + " ma.runtime_commit(scope_key,commit_id,content_hash,receipt,turn_id,epoch,first_sequence,last_sequence,recovery_ref,projection_version)"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                            scope.messageGroup(),
                            id,
                            ref.sha256(),
                            json(receipt),
                            events.get(0).turnId() == null
                                    ? "checkpoint-import"
                                    : events.get(0).turnId(),
                            epoch,
                            events.get(0).seq(),
                            through,
                            json(ref),
                            version);
                });
    }

    String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Cannot encode runtime boundary", error);
        }
    }

    <T> T parse(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Cannot decode runtime boundary", error);
        }
    }

    static void bounds(long after, int limit) {
        if (after < 0 || limit < 1 || limit > 4096)
            throw new IllegalArgumentException("Invalid event read bounds");
    }

    static ManagedStateException conflict(String code, String message) {
        return new ManagedStateException(code, message);
    }
}
