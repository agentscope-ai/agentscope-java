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
import io.agentcore.managed.harness.runtime.CanonicalJson;
import io.agentcore.managed.harness.runtime.ProtocolSchemas;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedBudgetStore;
import io.agentcore.managed.server.runtime.managed.ManagedCommitPlan;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedStateException;
import io.agentcore.managed.server.runtime.managed.ManagedSuspensions;
import io.agentcore.managed.server.runtime.managed.ManagedToolCommand;
import io.agentcore.managed.server.runtime.managed.ManagedToolTransport;
import io.agentcore.managed.server.runtime.managed.RuntimeObjectStore;
import io.agentcore.managed.server.runtime.managed.SessionCommitReceipt;
import io.agentcore.managed.server.runtime.managed.SessionCommitTransactions;
import io.agentcore.managed.server.runtime.managed.SessionRecoveryHead;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Service-owned transaction boundary. The journal remains the single implementation of effects. */
@Component
public final class SessionServiceStore {
    private final ProtocolSchemas schemas = new ProtocolSchemas();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ManagedJournalStore journal;
    private final ObjectMapper mapper;
    private final RuntimeObjectStore objects;
    private final SessionServiceOwnership ownership;

    public SessionServiceStore(
            DataSource source,
            ManagedJournalStore journal,
            ObjectMapper mapper,
            RuntimeObjectStore objects) {
        this.ownership = new SessionServiceOwnership(source);
        this.jdbc = new JdbcTemplate(source);
        this.jdbc.setQueryTimeout(10);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.tx.setTimeout(15);
        this.journal = journal;
        this.mapper = mapper;
        this.objects = objects;
    }

    public Object object(JsonNode request) {
        String scope = request.required("scopeKey").asText(),
                kind = request.required("kind").asText(),
                key = request.required("key").asText();
        RuntimeObjectStore.objectKey(scope, kind, key);
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.session WHERE scope_key=?", Long.class, scope)
                != 1) throw new IllegalArgumentException("Unknown object scope");
        return switch (request.required("operation").asText()) {
            case "put" ->
                    objects.put(
                            scope,
                            kind,
                            key,
                            Base64.getDecoder().decode(request.required("dataBase64").asText()));
            case "read" ->
                    Map.of(
                            "dataBase64",
                            Base64.getEncoder().encodeToString(objects.read(scope, kind, key)));
            case "exists" -> objects.exists(scope, kind, key);
            default -> throw new IllegalArgumentException("Invalid object operation");
        };
    }

    public Object reserve(
            JsonNode request, ManagedBudgetStore budgets, ManagedAdmissionStore admission) {
        var owner =
                new ManagedJournalStore.ExecutionOwner(
                        mapper.convertValue(request.required("scope"), ManagedScope.class),
                        request.required("turnId").asText(),
                        request.required("epoch").longValue(),
                        request.required("token").asText());
        return ownership.whileCurrent(
                owner,
                () -> {
                    budgets.reserve(
                            owner.scope(),
                            owner.turnId(),
                            owner.epoch(),
                            request.required("modelCallId").asText(),
                            request.required("model").asText(),
                            admission);
                    return Map.of("outcome", "reserved");
                });
    }

    public record PreparedRequest(String protocolVersion, ManagedCommitPlan plan) {
        public PreparedRequest {
            if (!"0.1.0".equals(protocolVersion) || plan == null)
                throw new IllegalArgumentException("Invalid prepared boundary");
        }
    }

    public SessionCommitReceipt commit(PreparedRequest request) {
        schemas.validate("prepared/CommitRequest", mapper.valueToTree(request));
        var plan = request.plan();
        // Hash all explicit effects, not just the original opaque native event batch.
        String hash = CanonicalJson.hash(mapper.valueToTree(request));
        var boundary = plan.boundary();
        var prior = journal.receipt(boundary.scope(), boundary.commitId());
        if (prior != null) return same(prior, hash);
        // Verify staged bytes before entering the SQL transaction; a reference alone isn't a save.
        if (plan.recoverySegment() != null) verify(boundary.scope(), plan.recoverySegment());
        for (var outcome : plan.modelOutcomes()) verify(boundary.scope(), outcome.reference());
        for (var change : plan.changes()) {
            if (change.checkpoint() == null) continue;
            var ref = change.checkpoint().reference();
            verify(
                    boundary.scope(),
                    new RuntimeObjectStore.Reference(
                            ref.backendId(),
                            boundary.scope().messageGroup(),
                            "checkpoint",
                            ref.identity().checkpointId(),
                            ref.contentHash(),
                            ref.sizeBytes()));
        }
        var verified =
                new ManagedCommitPlan(
                        boundary,
                        hash,
                        plan.changes(),
                        plan.toolBindings(),
                        plan.recoverySegment(),
                        plan.modelRequests(),
                        plan.modelOutcomes(),
                        plan.finalizations(),
                        plan.metricKind());
        return ownership.whileCurrent(
                new ManagedJournalStore.ExecutionOwner(
                        boundary.scope(),
                        boundary.turnId(),
                        boundary.epoch(),
                        boundary.executionToken()),
                () -> {
                    var bound =
                            jdbc.queryForList(
                                    "SELECT harness_type FROM ma.harness_binding WHERE scope_key=?",
                                    String.class,
                                    boundary.scope().messageGroup());
                    if (!bound.isEmpty() && !"agentscope-java".equals(bound.get(0)))
                        throw new ManagedStateException(
                                "harness_type_mismatch", "Session belongs to another kernel");
                    if (bound.isEmpty())
                        jdbc.update(
                                "INSERT INTO ma.harness_binding(scope_key,harness_type)"
                                        + " VALUES(?,?)",
                                boundary.scope().messageGroup(),
                                "agentscope-java");
                    var receipt = new SessionCommitTransactions(journal).commitPrepared(verified);
                    for (var model : verified.modelRequests()) {
                        if (model.purpose() == null) continue;
                        jdbc.update(
                                "INSERT INTO"
                                    + " ma.harness_model(scope_key,turn_id,model_call_id,request_sequence,request)"
                                    + " VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
                                boundary.scope().messageGroup(),
                                boundary.turnId(),
                                model.callId(),
                                model.sequence(),
                                mapper.createObjectNode()
                                        .put("purpose", model.purpose())
                                        .toString());
                    }
                    return receipt;
                });
    }

    private void verify(ManagedScope scope, RuntimeObjectStore.Reference reference) {
        if (!scope.messageGroup().equals(reference.scope()))
            throw new IllegalArgumentException("Object scope differs");
        objects.read(reference);
    }

    private SessionCommitReceipt same(SessionCommitReceipt receipt, String hash) {
        if (!hash.equals(receipt.hash()))
            throw new ManagedStateException("commit_id_conflict", "Commit identity was reused");
        return receipt;
    }

    public SessionCommitReceipt receipt(ManagedScope scope, String id) {
        return journal.receipt(scope, id);
    }

    public Object head(ManagedScope scope) {
        return journal.head(scope);
    }

    public record ToolPublication(String protocolVersion, ManagedToolCommand command) {}

    public ManagedToolTransport.Sent publishTool(ToolPublication request) {
        schemas.validate("prepared/ToolPublication", mapper.valueToTree(request));
        var command = request.command();
        ownership.whileCurrent(
                new ManagedJournalStore.ExecutionOwner(
                        command.scope(),
                        command.task().turnId(),
                        command.submission().epoch(),
                        command.submission().executionToken()),
                () -> true);
        // The proposal has no execution authority. Only a later prepared commit authorizes it.
        return journal.transport().send(command);
    }

    public record ResumeDeferral(ManagedSuspensions.Resume resume, long delayMillis) {}

    public Object deferResume(ResumeDeferral request) {
        schemas.validate("prepared/ResumeDeferral", mapper.valueToTree(request));
        if (request.resume() == null || request.delayMillis() < 1)
            throw new IllegalArgumentException("Invalid resume deferral");
        journal.suspensions().defer(request.resume(), Duration.ofMillis(request.delayMillis()));
        return Map.of("outcome", "deferred");
    }

    public record Query(ManagedScope scope, String turnId, String operation, JsonNode arguments) {}

    public Object query(Query query) {
        var scope = query.scope();
        String turn = query.turnId();
        var args = query.arguments();
        // Existence and explicit scope are checked before every private read.
        jdbc.queryForObject(
                "SELECT scope_key FROM ma.session WHERE scope_key=?",
                String.class,
                scope.messageGroup());
        return switch (query.operation()) {
            case "head" -> journal.head(scope);
            case "resolve-input" ->
                    journal.resolveInput(
                            scope, mapper.convertValue(args.required("input"), Map.class));
            case "resume-settled" -> {
                var resume =
                        mapper.convertValue(
                                args.required("resume"), ManagedSuspensions.Resume.class);
                if (!scope.equals(resume.scope()) || !Objects.equals(turn, resume.turnId()))
                    throw new IllegalArgumentException("Resume scope differs");
                yield journal.suspensions().settled(resume);
            }
            case "tool-proposals" -> journal.transport().pending(scope, turn);
            case "mcp-batches" -> journal.mcpBatches(scope, turn);
            case "model-anchor" -> journal.modelAnchor(scope, turn);
            case "suspended" -> journal.hasSuspension(scope, turn);
            case "pending-tools" -> journal.pendingToolIds(scope, turn);
            case "tools-unsettled" -> journal.toolsUnsettled(scope, turn);
            case "cancelled" -> journal.cancelRequested(scope, turn);
            case "tool" -> journal.tool(scope, args.required("taskId").asText()).orElse(null);
            case "native-tools" ->
                    journal.toolsForNativeCall(scope, turn, args.required("call").asText());
            case "mcp-batch" ->
                    journal.mcpBatchForCall(scope, turn, args.required("call").asText())
                            .orElse(null);
            case "action" -> journal.action(scope, turn, args.required("id").asText()).orElse(null);
            case "model" -> journal.modelResult(scope, turn, args.required("modelCallId").asText());
            case "replay-model" ->
                    journal.replayModel(
                            scope,
                            turn,
                            args.required("hash").asText(),
                            args.required("checkpoint").longValue());
            case "model-position" ->
                    journal.modelRequestPosition(scope, args.required("id").asText());
            case "finalization" -> journal.finalization(scope, turn);
            case "finalization-models" ->
                    journal.finalizationModels(
                            scope,
                            turn,
                            args.required("frame").asText(),
                            args.required("slot").intValue());
            case "bootstrap" ->
                    journal.recoveryBootstrap(
                            scope,
                            turn,
                            args.required("first").longValue(),
                            args.required("after").longValue(),
                            args.required("limit").intValue(),
                            mapper.convertValue(args.required("view"), SessionRecoveryHead.class));
            case "recovery" ->
                    journal.recoveryEvents(
                            scope,
                            args.required("after").longValue(),
                            args.required("limit").intValue());
            case "snapshot" -> journal.executionSnapshot(scope, turn);
            case "context" -> journal.checkpointContext(scope, turn);
            case "continuation-tools" -> journal.continuationTools(scope, turn);
            case "receipt" -> journal.receipt(scope, args.required("commitId").asText());
            default -> throw new IllegalArgumentException("Unknown read operation");
        };
    }

    /** Hold the same Session lock as every writer while selecting the bounded recovery prefix. */
    public Map<String, Object> recovery(ManagedScope scope, String turn, long after, int limit) {
        if (limit < 1 || limit > 4096) throw new IllegalArgumentException("Invalid recovery limit");
        return tx.execute(
                ignored -> {
                    jdbc.queryForObject(
                            "SELECT scope_key FROM ma.session WHERE scope_key=? FOR UPDATE",
                            String.class,
                            scope.messageGroup());
                    var head = journal.head(scope);
                    var events = journal.recoveryEvents(scope, after, limit);
                    return Map.of(
                            "head",
                            head,
                            "events",
                            events,
                            "pendingTaskIds",
                            journal.pendingToolIds(scope, turn));
                });
    }
}
