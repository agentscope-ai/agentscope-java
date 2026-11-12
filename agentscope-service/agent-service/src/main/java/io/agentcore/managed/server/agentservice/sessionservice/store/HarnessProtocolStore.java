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
package io.agentcore.managed.server.agentservice.sessionservice.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentcore.managed.harness.runtime.CanonicalJson;
import io.agentcore.managed.harness.runtime.ProtocolSchemas;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedBudgetStore;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedResumeSignals;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedSessionPreviews;
import io.agentcore.managed.server.runtime.managed.ManagedStateException;
import io.agentcore.managed.server.runtime.managed.ManagedSuspensions;
import io.agentcore.managed.server.runtime.managed.ManagedWorkerLeaseStore;
import io.agentcore.managed.server.runtime.managed.RuntimeObjectStore;
import io.agentcore.managed.server.runtime.managed.SessionCommitTransactions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Kernel-neutral API ownership. SQL facts and Outboxes continue to use the existing platform core. */
@Component
public final class HarnessProtocolStore {
    @Value("${managed-agents.runtime.harness-type:agentscope-java}")
    private String harnessType = "agentscope-java";

    public void harnessType(String type) {
        this.harnessType = type;
    }

    private final ProtocolSchemas schemas = new ProtocolSchemas();
    private static final long LEASE_MILLIS = 60_000;
    private final JdbcTemplate jdbc;
    private final ManagedWorkerLeaseStore clock;
    private final TransactionTemplate tx;
    private final ManagedAdmissionStore admission;
    private final ManagedJournalStore journal;
    private final RuntimeObjectStore objects;
    private final ObjectMapper mapper;
    private final HarnessCommitCompiler compiler;
    private final ManagedBudgetStore budgets;
    private ManagedSessionPreviews previews;

    @Autowired(required = false)
    public void previews(ManagedSessionPreviews previews) {
        this.previews = previews;
    }

    public HarnessProtocolStore(
            DataSource source,
            ManagedAdmissionStore admission,
            ManagedJournalStore journal,
            RuntimeObjectStore objects,
            ObjectMapper mapper,
            ManagedBudgetStore budgets) {
        clock = new ManagedWorkerLeaseStore(source);
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(15);
        this.admission = admission;
        this.journal = journal;
        this.objects = objects;
        this.mapper = mapper;
        this.budgets = budgets;
        compiler = new HarnessCommitCompiler(journal, objects, mapper, jdbc);
    }

    public JsonNode invoke(String operation, JsonNode request) {
        schemas.request(operation, request);
        if (!"0.1.0".equals(text(request, "protocolVersion")))
            throw invalid("Unsupported protocol version");
        if ("executions/next".equals(operation)) return next(request);
        ManagedScope scope =
                mapper.convertValue(
                        ("objects/read".equals(operation) ? request.required("ref") : request)
                                .required("scope"),
                        ManagedScope.class);
        return switch (operation) {
            case "previews/interest", "previews/publish" -> preview(scope, operation, request);
            case "executions/claim" -> claim(scope, request);
            case "executions/renew" ->
                    locked(
                            scope,
                            () ->
                                    memo(
                                            scope,
                                            operation,
                                            text(request, "renewalId"),
                                            request,
                                            () -> {
                                                fence(scope, request);
                                                long expires = clock.now() + LEASE_MILLIS;
                                                jdbc.update(
                                                        "UPDATE ma.harness_lease SET expires_at=?"
                                                                + " WHERE scope_key=?",
                                                        expires,
                                                        scope.messageGroup());
                                                var result = response(scope, request);
                                                result.put("renewalId", text(request, "renewalId"));
                                                result.put(
                                                        "serverTime",
                                                        Instant.ofEpochMilli(expires - LEASE_MILLIS)
                                                                .toString());
                                                result.set("fence", request.required("fence"));
                                                result.put(
                                                        "expiresAt",
                                                        Instant.ofEpochMilli(expires).toString());
                                                return result;
                                            }));
            case "executions/release" ->
                    locked(
                            scope,
                            () -> {
                                // Retain the token row: retry is idempotent, and prepared writes
                                // stay fenced.
                                var rows =
                                        jdbc.queryForList(
                                                "SELECT turn_id,epoch,execution_token,owner FROM"
                                                        + " ma.harness_lease WHERE scope_key=?",
                                                scope.messageGroup());
                                var requested = request.required("fence");
                                if (rows.isEmpty()) throw conflict("ownership_lost");
                                var lease = rows.get(0);
                                if (!text(request, "turnId").equals(lease.get("turn_id"))
                                        || uint(requested, "epoch")
                                                != ((Number) lease.get("epoch")).longValue()
                                        || !text(requested, "executionToken")
                                                .equals(lease.get("execution_token"))
                                        || !requested
                                                .required("owner")
                                                .equals(parse((String) lease.get("owner"))))
                                    throw conflict("ownership_lost");
                                jdbc.update(
                                        "UPDATE ma.harness_lease SET expires_at=0 WHERE"
                                                + " scope_key=?",
                                        scope.messageGroup());
                                ManagedResumeSignals.committed(jdbc, scope.messageGroup());
                                return response(scope, request).put("released", true);
                            });
            case "commits" -> commit(scope, request);
            case "commits/receipt" ->
                    receipt(scope, "commits", text(request, "commitId"), text(request, "turnId"));
            case "recovery/read" ->
                    locked(
                            scope,
                            () -> {
                                fence(scope, request);
                                return recovery(scope, request);
                            });
            case "objects/write" -> put(scope, request);
            case "objects/read" -> read(scope, request);
            case "tools/state" -> locked(scope, () -> tool(scope, request));
            case "models/reserve" ->
                    locked(
                            scope,
                            () -> {
                                fence(scope, request);
                                budgets.reserve(
                                        scope,
                                        text(request, "turnId"),
                                        uint(request.required("fence"), "epoch"),
                                        text(request, "modelCallId"),
                                        text(request, "model"),
                                        admission);
                                var result = response(scope, request);
                                result.put("modelCallId", text(request, "modelCallId"));
                                result.put("outcome", "reserved");
                                return result;
                            });
            case "inputs/close" ->
                    locked(
                            scope,
                            () -> {
                                fence(scope, request);
                                long through =
                                        admission.closeInputs(
                                                scope,
                                                text(request, "turnId"),
                                                uint(request.required("fence"), "epoch"));
                                var result = response(scope, request);
                                result.put("throughSequence", Long.toString(through));
                                return result;
                            });
            case "inputs/read" ->
                    locked(
                            scope,
                            () -> {
                                fence(scope, request);
                                return inputs(scope, request);
                            });
            case "deliveries/defer" ->
                    locked(
                            scope,
                            () ->
                                    memo(
                                            scope,
                                            operation,
                                            text(request, "deferralId"),
                                            request,
                                            () -> {
                                                // A retry from an old owner cannot delay a
                                                // replacement owner.
                                                if (request.has("fence")) {
                                                    fence(scope, request);
                                                } else if (jdbc.queryForObject(
                                                                "SELECT COUNT(*) FROM"
                                                                        + " ma.harness_lease WHERE"
                                                                        + " scope_key=? AND"
                                                                        + " expires_at>?",
                                                                Integer.class,
                                                                scope.messageGroup(),
                                                                clock.now())
                                                        > 0) {
                                                    throw conflict("execution_busy");
                                                }
                                                long notBefore =
                                                        clock.now()
                                                                + request.required("retryAfterMs")
                                                                        .longValue();
                                                var trigger = request.required("trigger");
                                                if ("resume".equals(text(trigger, "kind"))) {
                                                    var turn =
                                                            admission.turn(
                                                                    scope, text(request, "turnId"));
                                                    var resume =
                                                            new ManagedSuspensions.Resume(
                                                                    scope,
                                                                    text(trigger, "messageId"),
                                                                    turn.id(),
                                                                    text(trigger, "suspensionId"),
                                                                    uint(
                                                                            trigger,
                                                                            "suspensionEpoch"),
                                                                    turn.publicationSequence());
                                                    journal.suspensions()
                                                            .defer(
                                                                    resume,
                                                                    Duration.ofMillis(
                                                                            request.required(
                                                                                            "retryAfterMs")
                                                                                    .longValue()));
                                                }
                                                // Both HTTP polling and the reliable delivery
                                                // outbox honor this delay.
                                                jdbc.update(
                                                        "UPDATE ma.session_dispatch SET"
                                                            + " next_check_at=?,notification_pending=TRUE"
                                                            + " WHERE scope_key=?",
                                                        notBefore,
                                                        scope.messageGroup());
                                                var result = response(scope, request);
                                                result.put(
                                                        "deferralId", text(request, "deferralId"));
                                                result.put("messageId", text(trigger, "messageId"));
                                                result.put(
                                                        "notBefore",
                                                        Instant.ofEpochMilli(notBefore).toString());
                                                result.put("outcome", "deferred");
                                                return result;
                                            }));
            default -> throw invalid("Unknown Session Service operation");
        };
    }

    /** DB queue is the durable inbox. Polling never deletes or ACKs a Turn. */
    private JsonNode next(JsonNode request) {
        if (!harnessType.equals(text(request, "harnessType")))
            throw conflict("harness_type_mismatch");
        var result = mapper.createObjectNode().put("protocolVersion", "0.1.0");
        var deliveries = result.putArray("deliveries");
        int limit = request.required("limit").intValue();
        // Filter runnable work before LIMIT: an unready suspended Session must not consume
        // the last available worker slot's polling window and starve another Session.
        var scopes =
                jdbc.query(
                        "SELECT s.tenant,s.namespace,s.session_id FROM ma.session s JOIN"
                            + " ma.session_dispatch d ON d.scope_key=s.scope_key LEFT JOIN"
                            + " ma.harness_lease l ON l.scope_key=s.scope_key WHERE"
                            + " s.scheduler_mode='db_queue_v1' AND s.lifecycle='active' AND"
                            + " d.next_check_at<=? AND (l.scope_key IS NULL OR l.expires_at<=?) AND"
                            + " ((s.current_turn_id IS NULL AND EXISTS (SELECT 1 FROM"
                            + " ma.session_queue q WHERE q.scope_key=s.scope_key AND q.state IN"
                            + " ('queued','claimed'))) OR EXISTS (SELECT 1 FROM ma.turn t WHERE"
                            + " t.scope_key=s.scope_key AND t.turn_id=s.current_turn_id AND"
                            + " ((t.resume_id IS NULL AND t.status IN"
                            + " ('accepted','preparing','running')) OR (t.status IN"
                            + " ('waiting_tool','waiting_input','preparing','running') AND EXISTS"
                            + " (SELECT 1 FROM ma.turn_resume r WHERE r.scope_key=t.scope_key AND"
                            + " r.turn_id=t.turn_id AND r.suspension_id=t.suspension_id))))) ORDER"
                            + " BY d.next_check_at,s.scope_key LIMIT ?",
                        (row, index) ->
                                new ManagedScope(
                                        row.getString(1), row.getString(2), row.getString(3)),
                        clock.now(),
                        clock.now(),
                        limit);
        for (var scope : scopes) {
            var item =
                    locked(
                            scope,
                            () -> {
                                String candidate = admission.queue().candidate(scope);
                                if (candidate != null) {
                                    var value = mapper.createObjectNode();
                                    value.set("scope", mapper.valueToTree(scope));
                                    value.put("turnId", candidate);
                                    var trigger = value.putObject("trigger");
                                    trigger.put("kind", "ready");
                                    String revision =
                                            Long.toString(admission.queue().revision(scope));
                                    trigger.put("queueVersion", revision);
                                    trigger.put(
                                            "messageId",
                                            "ready_"
                                                    + ManagedScope.fingerprint(
                                                            scope.messageGroup(), revision));
                                    return value;
                                }
                                // The reliable resume outbox survives Brain exit and
                                // result-consumer ACK. resume_id is assigned on activation, so
                                // discover a not-yet-claimed resume by its current suspension.
                                var rows =
                                        jdbc.queryForList(
                                                "SELECT"
                                                    + " r.resume_id,r.turn_id,r.suspension_id,r.suspension_epoch"
                                                    + " FROM ma.turn_resume r JOIN ma.turn t ON"
                                                    + " t.scope_key=r.scope_key AND"
                                                    + " t.turn_id=r.turn_id WHERE r.scope_key=? AND"
                                                    + " t.status IN"
                                                    + " ('waiting_tool','waiting_input','preparing','running')"
                                                    + " AND t.suspension_id=r.suspension_id ORDER"
                                                    + " BY r.suspension_epoch DESC LIMIT 1",
                                                scope.messageGroup());
                                if (rows.isEmpty()) return null;
                                var row = rows.get(0);
                                var value = mapper.createObjectNode();
                                value.set("scope", mapper.valueToTree(scope));
                                value.put("turnId", row.get("turn_id").toString());
                                value.putObject("trigger")
                                        .put("kind", "resume")
                                        .put("messageId", row.get("resume_id").toString())
                                        .put("suspensionId", row.get("suspension_id").toString())
                                        .put(
                                                "suspensionEpoch",
                                                row.get("suspension_epoch").toString());
                                return value;
                            });
            if (item != null) deliveries.add(item);
        }
        return result;
    }

    private JsonNode claim(ManagedScope scope, JsonNode request) {
        return locked(
                scope,
                () ->
                        memo(
                                scope,
                                "executions/claim",
                                text(request, "claimId"),
                                request,
                                () -> {
                                    String type = text(request, "harnessType");
                                    if (!type.equals(harnessType)
                                            || !List.of(
                                                            "agentscope-java",
                                                            "pi",
                                                            "pi-durable",
                                                            "codex")
                                                    .contains(type))
                                        throw conflict("harness_type_mismatch");
                                    var bound =
                                            jdbc.queryForList(
                                                    "SELECT harness_type FROM ma.harness_binding"
                                                            + " WHERE scope_key=?",
                                                    String.class,
                                                    scope.messageGroup());
                                    if (!bound.isEmpty() && !type.equals(bound.get(0)))
                                        throw conflict("harness_type_mismatch");
                                    if (bound.isEmpty()) {
                                        if (journal.head(scope).sequence() > 0)
                                            throw conflict("native_format_mismatch");
                                        jdbc.update(
                                                "INSERT INTO"
                                                    + " ma.harness_binding(scope_key,harness_type)"
                                                    + " VALUES(?,?)",
                                                scope.messageGroup(),
                                                type);
                                    }
                                    var leases =
                                            jdbc.queryForList(
                                                    "SELECT * FROM ma.harness_lease WHERE"
                                                            + " scope_key=?",
                                                    scope.messageGroup());
                                    if (!leases.isEmpty()
                                            && ((Number) leases.get(0).get("expires_at"))
                                                            .longValue()
                                                    > clock.now()) throw conflict("execution_busy");
                                    var trigger = request.required("trigger");
                                    String turnId =
                                            "ready".equals(text(trigger, "kind"))
                                                    ? admission.queue().candidate(scope)
                                                    : text(request, "turnId");
                                    if (turnId == null) throw conflict("no_eligible_turn");
                                    var turn = admission.turn(scope, turnId);
                                    // Legacy MQ owners and HTTP owners must never run concurrently.
                                    var legacy =
                                            jdbc.queryForList(
                                                    "SELECT t.execution_deadline,l.expires_at FROM"
                                                        + " ma.turn t JOIN ma_worker.lease l ON"
                                                        + " l.incarnation=t.owner_incarnation WHERE"
                                                        + " t.scope_key=? AND t.turn_id=?",
                                                    scope.messageGroup(),
                                                    turnId);
                                    if (!legacy.isEmpty()
                                            && legacy.get(0).get("execution_deadline")
                                                    instanceof Number deadline
                                            && deadline.longValue() > clock.now()
                                            && ((Number) legacy.get(0).get("expires_at"))
                                                            .longValue()
                                                    > clock.now()) throw conflict("execution_busy");

                                    String token = UUID.randomUUID().toString();
                                    long epoch;
                                    if ("resume".equals(text(trigger, "kind"))) {
                                        epoch =
                                                admission.activateResume(
                                                        new ManagedSuspensions.Resume(
                                                                scope,
                                                                text(trigger, "messageId"),
                                                                turnId,
                                                                text(trigger, "suspensionId"),
                                                                uint(trigger, "suspensionEpoch"),
                                                                turn.publicationSequence()),
                                                        token);
                                    } else
                                        epoch =
                                                admission.activate(
                                                        scope,
                                                        turnId,
                                                        turn.publicationSequence(),
                                                        token);
                                    long expires = clock.now() + LEASE_MILLIS;
                                    jdbc.update(
                                            "DELETE FROM ma.harness_lease WHERE scope_key=?",
                                            scope.messageGroup());
                                    jdbc.update(
                                            "INSERT INTO"
                                                + " ma.harness_lease(scope_key,turn_id,epoch,execution_token,owner,expires_at)"
                                                + " VALUES(?,?,?,?,?,?)",
                                            scope.messageGroup(),
                                            turnId,
                                            epoch,
                                            token,
                                            request.required("owner").toString(),
                                            expires);
                                    var result = response(scope, request);
                                    result.put("turnId", turnId);
                                    result.put("claimId", text(request, "claimId"));
                                    result.put(
                                            "expiresAt", Instant.ofEpochMilli(expires).toString());
                                    var fence = result.putObject("fence");
                                    fence.set("owner", request.required("owner"));
                                    fence.put("epoch", Long.toString(epoch));
                                    fence.put("executionToken", token);
                                    return result;
                                }));
    }

    private JsonNode commit(ManagedScope scope, JsonNode request) {
        // Query a durable original receipt before touching native objects or checking a newer
        // fence.
        var prior = findReceipt(scope, "commits", text(request, "commitId"), request);
        if (prior != null) return prior;
        // Byte verification and checkpoint assembly happen before the short metadata transaction.
        var prepared = compiler.prepare(scope, request);
        return locked(
                scope,
                () ->
                        memo(
                                scope,
                                "commits",
                                text(request, "commitId"),
                                request,
                                () -> {
                                    fence(scope, request);
                                    var heads =
                                            jdbc.queryForList(
                                                    "SELECT * FROM ma.harness_head WHERE"
                                                            + " scope_key=?",
                                                    scope.messageGroup());
                                    long revision =
                                            heads.isEmpty()
                                                    ? 0
                                                    : ((Number) heads.get(0).get("revision"))
                                                            .longValue();
                                    if (revision != uint(request, "expectedBrainRevision"))
                                        throw conflict("brain_revision_conflict");
                                    var state = request.required("nativeState");
                                    if (!text(state, "harnessType")
                                            .equals(
                                                    jdbc.queryForObject(
                                                            "SELECT harness_type FROM"
                                                                    + " ma.harness_binding WHERE"
                                                                    + " scope_key=?",
                                                            String.class,
                                                            scope.messageGroup())))
                                        throw conflict("harness_type_mismatch");
                                    if (!heads.isEmpty()
                                            && (!text(state, "harnessType")
                                                            .equals(
                                                                    heads.get(0)
                                                                            .get("harness_type"))
                                                    || !text(state, "stateFormatVersion")
                                                            .equals(
                                                                    heads.get(0)
                                                                            .get("state_format"))))
                                        throw conflict("native_format_mismatch");
                                    compiler.validateResults(
                                            scope,
                                            text(request, "turnId"),
                                            request.required("changes"));
                                    new SessionCommitTransactions(journal).commitPrepared(prepared);
                                    compiler.registerModels(
                                            scope,
                                            text(request, "turnId"),
                                            request.required("changes"),
                                            prepared);
                                    long next = Math.addExact(revision, 1);
                                    if (heads.isEmpty())
                                        jdbc.update(
                                                "INSERT INTO"
                                                    + " ma.harness_head(scope_key,revision,native_state,harness_type,state_format)"
                                                    + " VALUES(?,?,?,?,?)",
                                                scope.messageGroup(),
                                                next,
                                                state.toString(),
                                                text(state, "harnessType"),
                                                text(state, "stateFormatVersion"));
                                    else
                                        jdbc.update(
                                                "UPDATE ma.harness_head SET"
                                                        + " revision=?,native_state=? WHERE"
                                                        + " scope_key=?",
                                                next,
                                                state.toString(),
                                                scope.messageGroup());
                                    jdbc.update(
                                            "INSERT INTO"
                                                + " ma.harness_boundary(scope_key,turn_id,revision,payload)"
                                                + " VALUES(?,?,?,?)",
                                            scope.messageGroup(),
                                            text(request, "turnId"),
                                            next,
                                            request.toString());
                                    var changes = request.required("changes");
                                    if (changes.has("suspension") || changes.has("terminal"))
                                        jdbc.update(
                                                "DELETE FROM ma.harness_lease WHERE scope_key=?",
                                                scope.messageGroup());
                                    var result = response(scope, request);
                                    result.put("commitId", text(request, "commitId"));
                                    result.put("contentHash", CanonicalJson.hash(request));
                                    result.put("outcome", "committed");
                                    result.put("brainRevision", Long.toString(next));
                                    result.put("nativeCursor", text(state, "nativeCursor"));
                                    return result;
                                }));
    }

    private JsonNode recovery(ManagedScope scope, JsonNode request) {
        var result = response(scope, request);

        result.put("capturedAt", Instant.now().toString());
        var heads =
                jdbc.queryForList(
                        "SELECT revision,native_state FROM ma.harness_head WHERE scope_key=?",
                        scope.messageGroup());
        result.put(
                "brainRevision", heads.isEmpty() ? "0" : heads.get(0).get("revision").toString());
        if (!heads.isEmpty())
            result.set("nativeState", parse((String) heads.get(0).get("native_state")));
        long revision = Long.parseLong(result.required("brainRevision").asText());
        boolean paged = request.has("pageSize");
        long after = request.has("cursor") ? uint(request.required("cursor"), "afterRevision") : 0;
        if (request.has("cursor")
                && uint(request.required("cursor"), "throughRevision") != revision)
            throw conflict("brain_revision_conflict");
        if (after > revision) throw invalid("Recovery cursor exceeds head");
        result.put(
                "snapshotId",
                "recovery_"
                        + ManagedScope.fingerprint(
                                scope.messageGroup(),
                                text(request, "turnId"),
                                Long.toString(revision)));
        int limit = paged ? request.required("pageSize").intValue() : 4096;

        for (String field :
                List.of(
                        "inputApplications",
                        "toolCalls",
                        "toolOperations",
                        "resultApplications",
                        "modelUpdates",
                        "interactionUpdates")) result.putArray(field);
        var boundaries =
                jdbc.queryForList(
                        "SELECT revision,payload FROM ma.harness_boundary WHERE scope_key=? AND"
                            + " turn_id=? AND revision>? AND revision<=? ORDER BY revision LIMIT ?",
                        scope.messageGroup(),
                        text(request, "turnId"),
                        after,
                        revision,
                        limit + 1);
        if (!paged && boundaries.size() > limit) throw conflict("recovery_limit_exceeded");
        long through = after;
        for (var row : boundaries.subList(0, Math.min(limit, boundaries.size()))) {
            through = ((Number) row.get("revision")).longValue();
            var changes = parse((String) row.get("payload")).required("changes");
            for (String field :
                    List.of(
                            "inputApplications",
                            "toolCalls",
                            "toolOperations",
                            "resultApplications",
                            "modelUpdates",
                            "interactionUpdates"))
                for (var item : changes.path(field)) {
                    var array = result.withArray(field);
                    if (array.size() >= 4096) throw conflict("recovery_limit_exceeded");
                    array.add(item);
                }
            if (changes.has("suspension")) result.set("suspension", changes.get("suspension"));
            if (changes.has("terminal")) {
                result.remove("suspension");
                result.set("terminal", changes.get("terminal"));
            }
        }
        // A claimed resume consumed the previous suspension; it is historical, not an active wait.
        var turn = admission.turn(scope, text(request, "turnId"));
        if (!List.of("waiting_tool", "waiting_input").contains(turn.status()))
            result.remove("suspension");
        result.set("pendingInputs", inputs(scope, request).required("pendingInputs"));
        var results = result.putArray("acceptedResults");
        for (var operation : result.withArray("toolOperations")) {
            var task = journal.tool(scope, text(operation, "taskId")).orElseThrow();
            if (task.result() != null) results.add(compiler.result(scope, task));
        }
        var envelope = response(scope, request);
        envelope.set("snapshotId", result.required("snapshotId"));
        if (paged && boundaries.size() > limit) {
            envelope.putObject("nextCursor")
                    .put("afterRevision", Long.toString(through))
                    .put("throughRevision", Long.toString(revision));
        }
        byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 512 * 1024) {
            var objectRequest = mapper.createObjectNode();
            objectRequest.put("objectId", RuntimeObjectStore.sha256(bytes));
            objectRequest.put("size", Integer.toString(bytes.length));
            objectRequest.put("mediaType", "application/json");
            objectRequest.put("formatVersion", "recovery-0.1.0");
            objectRequest.put("dataBase64", Base64.getEncoder().encodeToString(bytes));
            envelope.set("bundleRef", put(scope, objectRequest).required("ref"));
        } else envelope.set("bundle", result);
        schemas.validate("recovery/RecoveryResponse", envelope);
        return envelope;
    }

    private ObjectNode inputs(ManagedScope scope, JsonNode request) {
        var response = response(scope, request);
        var inputs = response.putArray("pendingInputs");
        long count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.input WHERE scope_key=? AND turn_id=? AND"
                                + " status='accepted'",
                        Long.class,
                        scope.messageGroup(),
                        text(request, "turnId"));
        if (count > 256) throw conflict("recovery_limit_exceeded");
        var pending = admission.pendingInputs(scope, text(request, "turnId"), 256);
        if (pending.size() > 256) throw conflict("recovery_limit_exceeded");
        for (var input : pending) {
            var item = inputs.addObject();
            item.put("inputId", input.id());
            item.put("sequence", Long.toString(input.sequence()));
            item.put(
                    "type",
                    switch (input.type()) {
                        case CANCEL -> "cancel";
                        case REPLY -> "interaction_response";
                        default -> "message";
                    });
            item.putObject("content").set("inline", parse(input.payload()));
            if (input.actionId() != null) item.put("actionId", input.actionId());
        }
        return response;
    }

    private JsonNode tool(ManagedScope scope, JsonNode request) {
        var task =
                journal.tool(scope, text(request, "taskId"))
                        .orElseThrow(() -> conflict("tool_not_found"));
        if (!task.turnId().equals(text(request, "turnId")))
            throw conflict("tool_identity_conflict");
        var result = response(scope, request);
        result.put("taskId", task.id());
        result.put("executionId", task.executionId());
        if (!task.executionId().equals(text(request, "executionId")))
            throw conflict("tool_identity_conflict");
        result.put("state", "ready".equals(task.status()) ? "authorized" : task.status());
        result.put("observedAt", Instant.now().toString());
        result.put("cancelRequested", journal.cancelRequested(scope, task.turnId()));
        if (task.result() != null) result.set("result", compiler.result(scope, task));
        return result;
    }

    private JsonNode put(ManagedScope scope, JsonNode request) {
        // Uploading immutable unreferenced bytes does not authorize execution.
        admission.session(scope);
        byte[] bytes = Base64.getDecoder().decode(request.required("dataBase64").asText());
        if (bytes.length == 0 || bytes.length > RuntimeObjectStore.MAX_BYTES)
            throw invalid("Object exceeds limit");
        String hash = RuntimeObjectStore.sha256(bytes);
        if (request.has("sha256") && !hash.equals(text(request, "sha256")))
            throw invalid("Object hash differs");
        if (uint(request, "size") != bytes.length) throw invalid("Object size differs");
        String key = text(request, "objectId");
        var ref =
                objects.put(
                        scope.messageGroup(),
                        "harness-object",
                        ManagedScope.fingerprint(key),
                        bytes);
        var result = mapper.createObjectNode().put("protocolVersion", "0.1.0");
        var encoded = result.putObject("ref");
        encoded.set("scope", mapper.valueToTree(scope));
        encoded.put("backendId", ref.backendId());
        encoded.put("key", key);
        encoded.put("sha256", hash);
        encoded.put("size", Long.toString(bytes.length));
        encoded.put("mediaType", text(request, "mediaType"));
        encoded.put("formatVersion", text(request, "formatVersion"));
        return result;
    }

    private JsonNode read(ManagedScope scope, JsonNode request) {
        admission.session(scope);
        var ref = request.required("ref");
        byte[] bytes = compiler.read(scope, ref);
        var result = mapper.createObjectNode().put("protocolVersion", "0.1.0");
        result.set("ref", ref);
        result.put("dataBase64", Base64.getEncoder().encodeToString(bytes));
        return result;
    }

    private JsonNode preview(ManagedScope scope, String operation, JsonNode request) {
        String turn = text(request, "turnId"), call = text(request, "modelCallId");
        locked(
                scope,
                () -> {
                    fence(scope, request);
                    var models =
                            jdbc.queryForList(
                                    "SELECT h.request FROM ma.harness_model h JOIN ma.model_call m"
                                        + " ON m.source_scope=h.scope_key AND m.turn_id=h.turn_id"
                                        + " AND m.call_id=h.model_call_id WHERE h.scope_key=? AND"
                                        + " h.turn_id=? AND h.model_call_id=? AND"
                                        + " m.status='reserved' AND NOT EXISTS (SELECT 1 FROM"
                                        + " ma.model_call_result r WHERE r.scope_key=h.scope_key"
                                        + " AND r.model_call_id=h.model_call_id AND"
                                        + " r.completion_sequence IS NOT NULL)",
                                    String.class,
                                    scope.messageGroup(),
                                    turn,
                                    call);
                    if (models.size() != 1
                            || !"reasoning".equals(parse(models.get(0)).path("purpose").asText()))
                        throw conflict("preview_model_closed");
                    return mapper.nullNode();
                });
        // Redis interest/publish must never hold the authoritative Session row lock.
        // A subsequent takeover is isolated by the checked epoch and reconciliation.
        var response = response(scope, request);
        if ("previews/interest".equals(operation))
            return response.put("interested", previews != null && previews.hasInterest(scope));
        var chunks = new ArrayList<String>();
        request.required("chunks").forEach(value -> chunks.add(value.asText()));
        return response.put(
                "accepted",
                previews != null
                        && previews.publishText(
                                scope,
                                turn,
                                call,
                                uint(request.required("fence"), "epoch"),
                                text(request, "messageId"),
                                uint(request, "sequence"),
                                chunks));
    }

    private void fence(ManagedScope scope, JsonNode request) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM ma.harness_lease WHERE scope_key=?", scope.messageGroup());
        if (rows.size() != 1) throw conflict("stale_execution");
        var row = rows.get(0);
        var fence = request.required("fence");
        if (!text(request, "turnId").equals(row.get("turn_id"))
                || uint(fence, "epoch") != ((Number) row.get("epoch")).longValue()
                || !text(fence, "executionToken").equals(row.get("execution_token"))
                || !fence.required("owner").equals(parse((String) row.get("owner")))
                || ((Number) row.get("expires_at")).longValue() <= clock.now())
            throw conflict("stale_execution");
        admission.requireEpoch(scope, text(request, "turnId"), uint(fence, "epoch"));
    }

    private JsonNode locked(ManagedScope scope, Supplier<JsonNode> work) {
        return tx.execute(
                ignored -> {
                    jdbc.queryForObject(
                            "SELECT scope_key FROM ma.session WHERE scope_key=? FOR UPDATE",
                            String.class,
                            scope.messageGroup());
                    return work.get();
                });
    }

    private JsonNode memo(
            ManagedScope scope,
            String operation,
            String id,
            JsonNode request,
            Supplier<JsonNode> work) {
        var prior = findReceipt(scope, operation, id, request);
        if (prior != null) return prior;
        var response = work.get();
        jdbc.update(
                "INSERT INTO"
                    + " ma.harness_receipt(scope_key,operation,request_id,content_hash,response)"
                    + " VALUES(?,?,?,?,?)",
                scope.messageGroup(),
                operation,
                id,
                CanonicalJson.hash(request),
                response.toString());
        return response;
    }

    private JsonNode findReceipt(
            ManagedScope scope, String operation, String id, JsonNode request) {
        var rows =
                jdbc.queryForList(
                        "SELECT content_hash,response FROM ma.harness_receipt WHERE scope_key=? AND"
                                + " operation=? AND request_id=?",
                        scope.messageGroup(),
                        operation,
                        id);
        if (rows.isEmpty()) return null;
        if (!CanonicalJson.hash(request).equals(rows.get(0).get("content_hash")))
            throw conflict("request_id_conflict");
        return parse((String) rows.get(0).get("response"));
    }

    private JsonNode receipt(ManagedScope scope, String operation, String id, String turn) {
        var rows =
                jdbc.queryForList(
                        "SELECT response FROM ma.harness_receipt WHERE scope_key=? AND operation=?"
                                + " AND request_id=?",
                        String.class,
                        scope.messageGroup(),
                        operation,
                        id);
        if (rows.isEmpty()) throw conflict("receipt_not_found");
        var value = parse(rows.get(0));
        if (!turn.equals(text(value, "turnId"))) throw conflict("receipt_not_found");
        return value;
    }

    private ObjectNode response(ManagedScope scope, JsonNode request) {
        var value = mapper.createObjectNode();
        value.put("protocolVersion", "0.1.0");
        value.set("scope", mapper.valueToTree(scope));
        if (request.has("turnId")) value.set("turnId", request.get("turnId"));
        return value;
    }

    private JsonNode parse(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception invalid) {
            throw invalid("Invalid persisted JSON");
        }
    }

    static String text(JsonNode value, String field) {
        var node = value.required(field);
        if (!node.isTextual() || node.asText().isBlank() || node.asText().length() > 1024)
            throw invalid("Invalid " + field);
        return node.asText();
    }

    static long uint(JsonNode value, String field) {
        String text = text(value, field);
        if (!text.matches("0|[1-9][0-9]{0,18}")) throw invalid("Invalid counter");
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException invalid) {
            throw invalid("Counter overflow");
        }
    }

    static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    static ManagedStateException conflict(String code) {
        return new ManagedStateException(code, code);
    }
}
