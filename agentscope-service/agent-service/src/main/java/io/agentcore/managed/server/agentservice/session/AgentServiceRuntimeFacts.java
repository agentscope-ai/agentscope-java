/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.agentservice.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceEventEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceEventRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceExecutionGrantRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceFactReceiptEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceFactReceiptRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceFactSourceEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceFactSourceRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceInteractionRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceQuotaEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceQuotaRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceTurnEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceTurnRepository;
import io.agentcore.managed.server.runtime.protocol.InteractionCommand;
import io.agentcore.managed.server.runtime.protocol.RuntimeFact;
import io.agentcore.managed.server.runtime.protocol.RuntimeFactReceipt;
import io.agentcore.managed.server.runtime.protocol.RuntimePayloadDigest;
import io.agentcore.managed.server.runtime.protocol.RuntimeProtocol;
import io.agentcore.managed.server.runtime.protocol.RuntimeRequestContext;
import io.agentcore.managed.server.runtime.protocol.TurnCommand;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Committed backend facts become one ordered public stream. No execution is performed here. */
@Service
public class AgentServiceRuntimeFacts {
    private final AgentServiceSessionRepository sessions;
    private final AgentServiceTurnRepository turns;
    private final AgentServiceQuotaRepository quotas;
    private final AgentServiceExecutionGrantRepository grants;
    private final AgentServiceFactSourceRepository sources;
    private final AgentServiceFactReceiptRepository receipts;
    private final AgentServiceEventRepository events;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final VaultCrypto crypto;
    private final AgentServiceEventNotifications notifications;
    private final AgentServiceResourceProjection projection;
    private final AgentServiceInteractionRepository interactions;
    private final AgentServiceChildProjection children;

    public AgentServiceRuntimeFacts(
            AgentServiceSessionRepository sessions,
            AgentServiceTurnRepository turns,
            AgentServiceQuotaRepository quotas,
            AgentServiceExecutionGrantRepository grants,
            AgentServiceFactSourceRepository sources,
            AgentServiceFactReceiptRepository receipts,
            AgentServiceEventRepository events,
            TransactionTemplate tx,
            ObjectMapper mapper,
            VaultCrypto crypto,
            AgentServiceEventNotifications notifications,
            AgentServiceResourceProjection projection,
            AgentServiceInteractionRepository interactions,
            AgentServiceChildProjection children) {
        this.sessions = sessions;
        this.turns = turns;
        this.quotas = quotas;
        this.grants = grants;
        this.sources = sources;
        this.receipts = receipts;
        this.events = events;
        this.tx = tx;
        this.mapper = mapper;
        this.crypto = crypto;
        this.notifications = notifications;
        this.projection = projection;
        this.interactions = interactions;
        this.children = children;
    }

    public RuntimeFactReceipt accept(RuntimeFact fact) {
        String digest = RuntimePayloadDigest.of(mapper, fact);
        String sourceId =
                RuntimePayloadDigest.of(
                        mapper,
                        Map.of("session", fact.context().sessionId(), "source", fact.source()));
        String id =
                RuntimePayloadDigest.of(
                        mapper, Map.of("source", sourceId, "event", fact.eventId()));
        return tx.execute(
                ignored -> {
                    var session =
                            sessions.lockById(fact.context().sessionId())
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND));
                    var existing = receipts.findById(id);
                    if (existing.isPresent()) {
                        if (!existing.get().digest.equals(digest))
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT, "Fact content changed after commit");
                        return receipt(fact, existing.get());
                    }
                    String expectedSource =
                            switch (session.runtimeKind) {
                                case "managed" -> "managed";
                                case "external", "hosted-runtime", "team", "workflow", "mixed" ->
                                        "orchestration";
                                default -> "unsupported";
                            };
                    if (session.rootSessionId != null || !expectedSource.equals(fact.source()))
                        throw new ResponseStatusException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "Runtime fact source is not connected");
                    var context = fact.context();
                    boolean inputAcceptance =
                            Set.of("input.accepted", "input.rejected").contains(fact.type())
                                    && fact.executionId() == null
                                    && fact.executionFence() == null;
                    AgentServiceTurnEntity turn = null;
                    RuntimeRequestContext original;
                    if (inputAcceptance) {
                        var input =
                                interactions
                                        .lockById(context.commandId())
                                        .filter(row -> row.sessionId.equals(session.id))
                                        .orElseThrow(
                                                () ->
                                                        new ResponseStatusException(
                                                                HttpStatus.NOT_FOUND));
                        if (!List.of("steer", "inject").contains(input.kind)
                                || !input.id.equals(fact.payload().get("input_id"))
                                || !input.kind.equals(fact.payload().get("kind"))
                                || fact.payload().containsKey("input_ids"))
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST, "Invalid input acceptance fact");
                        original =
                                decode(crypto.decrypt(input.command), InteractionCommand.class)
                                        .context();
                    } else {
                        turn =
                                turns.lockById(context.turnId())
                                        .orElseThrow(
                                                () ->
                                                        new ResponseStatusException(
                                                                HttpStatus.NOT_FOUND));
                        if (!turn.sessionId.equals(session.id))
                            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                        original =
                                decode(crypto.decrypt(turn.command), TurnCommand.class).context();
                    }
                    if (!original.tenant().equals(context.tenant())
                            || !original.namespace().equals(context.namespace())
                            || !Objects.equals(original.applicationId(), context.applicationId())
                            || !Objects.equals(original.turnId(), context.turnId())
                            || !original.commandId().equals(context.commandId())
                            || !original.actor().equals(context.actor()))
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                    var source =
                            sources.findById(sourceId).orElseGet(AgentServiceFactSourceEntity::new);
                    source.id = sourceId;
                    if (fact.sourceSequence() != source.lastSequence + 1)
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Runtime fact source has a gap or reused position");
                    boolean current = inputAcceptance || authorizeExecution(fact, turn);
                    Map<String, Object> childSummary = null;
                    if ("subagent.started".equals(fact.type())) children.spawned(session, fact);
                    if ("subagent.event".equals(fact.type()))
                        childSummary = children.record(session, fact);
                    if (!inputAcceptance) {
                        var quota = quotas.lockById(turn.quotaId).orElseThrow();
                        if ("usage.recorded".equals(fact.type()))
                            accountUsage(fact.payload(), quota);
                        if ("subagent.event".equals(fact.type())
                                && "usage.recorded".equals(fact.payload().get("event_type"))
                                && fact.payload().get("event_payload") instanceof Map<?, ?> usage)
                            accountUsage(copy(usage), quota);
                        if (current && fact.type().startsWith("turn.")) reduce(fact, turn, quota);
                        quotas.saveAndFlush(quota);
                        turns.saveAndFlush(turn);
                    }
                    reduceInputs(session.id, fact);
                    resolveAction(session.id, fact);
                    if (current
                            && !inputAcceptance
                            && !turn.id.equals(turn.activationCommandId)
                            && ("turn.running".equals(fact.type())
                                    || Set.of(
                                                    "turn.failed",
                                                    "turn.interrupted",
                                                    "turn.cancelled",
                                                    "turn.completed")
                                            .contains(fact.type()))) {
                        var activation =
                                interactions.lockById(turn.activationCommandId).orElseThrow();
                        if (!Set.of("resume", "approval", "tool_result", "signal")
                                        .contains(activation.kind)
                                || !session.id.equals(activation.sessionId)
                                || !turn.id.equals(activation.turnId))
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST, "Activation proof has wrong scope");
                        boolean rejected =
                                "turn.failed".equals(fact.type())
                                        && "action_rejected".equals(fact.payload().get("reason"))
                                        && Boolean.FALSE.equals(
                                                fact.payload().get("native_started"));
                        if (rejected) activation.nativeStatus = "rejected";
                        else if ("resume".equals(activation.kind))
                            activation.nativeStatus = "applied";
                        else if (!"applied".equals(activation.nativeStatus))
                            activation.nativeStatus =
                                    "turn.running".equals(fact.type())
                                            ? "runtime_accepted"
                                            : "failed";
                        activation.deliveryStatus = "delivered";
                        activation.claimToken = null;
                        activation.claimUntil = 0;
                        activation.failureCode = null;
                        activation.updatedAt = System.currentTimeMillis();
                        interactions.saveAndFlush(activation);
                    }
                    String cancelId =
                            fact.payload().get("cancel_command_id") instanceof String value
                                    ? value
                                    : null;
                    boolean cancellationStopped =
                            Set.of("turn.cancelled", "turn.completed").contains(fact.type())
                                    || (Boolean.TRUE.equals(fact.payload().get("native_stopped"))
                                            && Set.of("turn.failed", "turn.interrupted")
                                                    .contains(fact.type()));
                    if (current && cancellationStopped && cancelId == null)
                        cancelId =
                                interactions
                                        .findFirstByTurnIdAndKindAndNativeStatusInOrderByCreatedAtDesc(
                                                context.turnId(),
                                                "cancel",
                                                List.of("queued", "runtime_accepted"))
                                        .map(row -> row.id)
                                        .orElse(null);
                    if (current && cancellationStopped && cancelId != null) {
                        var cancellation = interactions.lockById(cancelId).orElseThrow();
                        if (!"cancel".equals(cancellation.kind)
                                || !session.id.equals(cancellation.sessionId)
                                || !Objects.equals(context.turnId(), cancellation.turnId))
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST, "Cancellation proof has wrong scope");
                        cancellation.nativeStatus = "applied";
                        cancellation.deliveryStatus = "delivered";
                        cancellation.claimToken = null;
                        cancellation.claimUntil = 0;
                        cancellation.failureCode = null;
                        cancellation.updatedAt = System.currentTimeMillis();
                        interactions.saveAndFlush(cancellation);
                    }
                    var event = new AgentServiceEventEntity();
                    event.id = id;
                    event.sessionId = session.id;
                    event.turnId = context.turnId();
                    event.seq = ++session.nextEventSequence;
                    event.type =
                            childSummary != null
                                    ? "subagent.updated"
                                    : !current && fact.type().startsWith("turn.")
                                            ? "execution." + fact.type().substring(5)
                                            : fact.type();
                    var payload =
                            new LinkedHashMap<>(
                                    childSummary == null ? fact.payload() : childSummary);
                    if (fact.type().startsWith("turn.") && childSummary == null) {
                        String nativeStatus = fact.type().substring(5);
                        // A cancellation or a settled Turn cannot be rewound by a running fact.
                        // The logical lifecycle belongs to Agent Service; retain the native
                        // observation
                        // under execution.* when it no longer matches that lifecycle.
                        if (current && !nativeStatus.equals(turn.status))
                            event.type = "execution." + nativeStatus;
                        payload.put("status", nativeStatus);
                    }
                    payload.remove("control_stop");
                    payload.remove("native_stopped");
                    payload.remove("activation_command_id");
                    payload.remove("cancel_command_id");
                    if (childSummary != null
                            && Set.of("usage.recorded", "model.completed")
                                    .contains(fact.payload().get("event_type"))) {
                        event.type = String.valueOf(fact.payload().get("event_type"));
                        payload.putAll(copy((Map<?, ?>) fact.payload().get("event_payload")));
                        payload.remove("item_id");
                        Object call = payload.getOrDefault("model_call_id", fact.eventId());
                        if (call == null) call = fact.eventId();
                        payload.put("native_model_call_id", call);
                        payload.put(
                                "model_call_id",
                                RuntimePayloadDigest.of(
                                        mapper,
                                        Map.of(
                                                "child",
                                                childSummary.get("childSessionId"),
                                                "call",
                                                call)));
                        payload.put("scope", "descendant_session");
                    }
                    payload.put("schema_version", 1);
                    payload.put("turn_id", context.turnId());
                    var provenance = new LinkedHashMap<String, Object>();
                    provenance.put("backend", fact.source());
                    provenance.put("event_id", fact.eventId());
                    provenance.put("sequence", fact.sourceSequence());
                    provenance.put("execution_id", fact.executionId());
                    payload.put("runtime_source", provenance);
                    payload.remove("session_usage");
                    payload.remove("turn_usage");
                    event.data = json(payload);
                    event.createdAt = System.currentTimeMillis();
                    events.saveAndFlush(event);
                    projection.apply(session, event);
                    if (Set.of("usage.recorded", "model.completed").contains(event.type)) {
                        payload.put("session_usage", projection.read(session.usageSummary));
                        if (event.turnId != null)
                            turns.findById(event.turnId)
                                    .ifPresent(
                                            row ->
                                                    payload.put(
                                                            "turn_usage",
                                                            projection.read(row.usageSummary)));
                        event.data = json(payload);
                        events.saveAndFlush(event);
                    }
                    notifications.committed(event.sessionId);
                    sessions.saveAndFlush(session);
                    source.lastSequence = fact.sourceSequence();
                    sources.saveAndFlush(source);
                    var committed = new AgentServiceFactReceiptEntity();
                    committed.id = id;
                    committed.sourceId = sourceId;
                    committed.sourceSequence = fact.sourceSequence();
                    committed.digest = digest;
                    committed.publicSequence = event.seq;
                    receipts.saveAndFlush(committed);
                    return receipt(fact, committed);
                });
    }

    /** One source prefix commits atomically; duplicate prefixes return their original receipts. */
    public List<RuntimeFactReceipt> acceptBatch(List<RuntimeFact> batch) {
        if (batch == null || batch.isEmpty() || batch.size() > 64)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Fact batch must contain 1..64 events");
        var first = batch.get(0);
        for (int i = 0; i < batch.size(); i++) {
            var fact = batch.get(i);
            if (!first.context().sessionId().equals(fact.context().sessionId())
                    || !first.source().equals(fact.source())
                    || fact.sourceSequence() != first.sourceSequence() + i)
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Fact batch must be one ordered source prefix");
        }
        return tx.execute(ignored -> batch.stream().map(this::accept).toList());
    }

    private void reduceInputs(String session, RuntimeFact fact) {
        if (!Set.of("input.accepted", "input.applied", "input.rejected").contains(fact.type()))
            return;
        var ids = new LinkedHashSet<String>();
        if (fact.payload().get("input_id") instanceof String id) ids.add(id);
        if (fact.payload().get("input_ids") instanceof List<?> values)
            for (Object value : values) if (value instanceof String id) ids.add(id);
        if (ids.size() > 256)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Input fact exceeds batch limit");
        for (String id : ids) {
            var input =
                    interactions
                            .lockById(id)
                            .filter(row -> row.sessionId.equals(session))
                            .orElse(null);
            if (input == null) continue;
            if ("input.accepted".equals(fact.type()) && !"queued".equals(input.nativeStatus))
                continue;
            input.nativeStatus = fact.type().substring(6);
            input.deliveryStatus = "delivered";
            input.claimToken = null;
            input.claimUntil = 0;
            input.failureCode = null;
            input.updatedAt = System.currentTimeMillis();
            interactions.saveAndFlush(input);
        }
    }

    private void resolveAction(String session, RuntimeFact fact) {
        String child = null;
        Map<String, Object> payload = fact.payload();
        String type = fact.type();
        if ("subagent.event".equals(type)
                && payload.get("child_session_id") instanceof String childId
                && payload.get("event_payload") instanceof Map<?, ?> data) {
            child = childId;
            type = String.valueOf(payload.get("event_type"));
            payload = copy(data);
        }
        if (!"required_action.resolved".equals(type)
                || !(fact.payload().get("activation_command_id") instanceof String activation)
                || !(payload.get("request_id") instanceof String request)) return;
        var row =
                interactions
                        .lockById(activation)
                        .filter(
                                value ->
                                        session.equals(value.sessionId)
                                                && Objects.equals(
                                                        fact.context().turnId(), value.turnId)
                                                && Set.of("approval", "tool_result", "signal")
                                                        .contains(value.kind))
                        .orElse(null);
        if (row == null) return;
        var command = decode(crypto.decrypt(row.command), InteractionCommand.class);
        var answers = (List<?>) command.payload().get("answers");
        var expected = new LinkedHashSet<String>();
        boolean matches = false;
        boolean targetMatches = false;
        for (var raw : answers) {
            var answer = (Map<?, ?>) raw;
            if (!Objects.equals(child, answer.get("child_session_id"))) continue;
            targetMatches = true;
            expected.add((String) answer.get("request_id"));
            if (request.equals(answer.get("request_id"))
                    && Objects.equals(child, answer.get("child_session_id"))
                    && Objects.equals(payload.get("kind"), answer.get("kind"))) matches = true;
        }
        // Child completion also resolves its ancestor's agent_spawn call. That closes the
        // parent native request rather than acknowledging the caller's child answer.
        if (!targetMatches || "cancelled".equals(payload.get("resolution_status"))) return;
        if (!matches)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Action resolution does not match this command");
        var applied = new LinkedHashSet<String>();
        for (var resolved : decode(row.appliedRequestIds, List.class))
            if (resolved instanceof String id) applied.add(id);
        applied.add(request);
        row.appliedRequestIds = json(applied);
        if (applied.containsAll(expected)) row.nativeStatus = "applied";
        row.deliveryStatus = "delivered";
        row.claimToken = null;
        row.claimUntil = 0;
        row.failureCode = null;
        row.updatedAt = System.currentTimeMillis();
        interactions.saveAndFlush(row);
    }

    private boolean authorizeExecution(RuntimeFact fact, AgentServiceTurnEntity turn) {
        String activation =
                String.valueOf(
                        fact.payload()
                                .getOrDefault("activation_command_id", fact.context().commandId()));
        if (fact.executionId() == null && fact.executionFence() == null) {
            if ("orchestration".equals(fact.source())
                    && Boolean.TRUE.equals(fact.payload().get("control_stop"))
                    && Boolean.TRUE.equals(fact.payload().get("native_stopped"))
                    && Set.of("turn.failed", "turn.interrupted", "turn.completed")
                            .contains(fact.type())
                    && Objects.equals(activation, turn.activationCommandId)
                    && !activation.equals(turn.id)) {
                var control =
                        interactions
                                .findById(activation)
                                .filter(
                                        row ->
                                                row.sessionId.equals(turn.sessionId)
                                                        && turn.id.equals(row.turnId)
                                                        && Set.of("approval", "signal")
                                                                .contains(row.kind))
                                .orElseThrow(
                                        () ->
                                                new ResponseStatusException(
                                                        HttpStatus.FORBIDDEN,
                                                        "Unknown stopped control activation"));
                var command = decode(crypto.decrypt(control.command), InteractionCommand.class);
                if (!turn.id.equals(command.payload().get("target_command_id"))
                        || !(command.payload().get("expected_generation") instanceof Number prior)
                        || (prior.longValue() != turn.executionGeneration
                                && prior.longValue() + 1 != turn.executionGeneration))
                    throw new ResponseStatusException(
                            HttpStatus.FORBIDDEN, "Stopped control has wrong generation");
                // A runtime reports this only after the original native execution
                // physically stops and final facts drain. No new execution is allowed.
                // It also handles a grant committed by Agent Service whose ACK was lost.
                return true;
            }
            if ("turn.cancelled".equals(fact.type())
                    && fact.payload().get("cancel_command_id") instanceof String id) {
                var cancellation =
                        interactions
                                .lockById(id)
                                .orElseThrow(
                                        () ->
                                                new ResponseStatusException(
                                                        HttpStatus.FORBIDDEN,
                                                        "Cancellation intent is required"));
                var command =
                        decode(crypto.decrypt(cancellation.command), InteractionCommand.class);
                if (!"cancel".equals(cancellation.kind)
                        || !turn.sessionId.equals(cancellation.sessionId)
                        || !turn.id.equals(cancellation.turnId)
                        || !turn.id.equals(command.payload().get("target_command_id"))
                        || !Objects.equals(
                                turn.activationCommandId,
                                command.payload().get("target_activation_command_id"))
                        || !(command.payload().get("expected_generation")
                                instanceof Number generation)
                        || generation.longValue() != turn.executionGeneration)
                    throw new ResponseStatusException(
                            HttpStatus.FORBIDDEN, "Cancellation intent does not match activation");
                cancellation.deliveryStatus = "delivered";
                cancellation.nativeStatus = "runtime_accepted";
                cancellation.claimToken = null;
                cancellation.claimUntil = 0;
                interactions.saveAndFlush(cancellation);
                return true;
            }
            if (Boolean.FALSE.equals(fact.payload().get("native_started"))
                    && Set.of("turn.failed", "turn.interrupted", "turn.cancelled")
                            .contains(fact.type())) {
                if (!activation.equals(turn.id))
                    interactions
                            .findById(activation)
                            .filter(
                                    row ->
                                            row.sessionId.equals(turn.sessionId)
                                                    && turn.id.equals(row.turnId)
                                                    && Set.of(
                                                                    "resume",
                                                                    "approval",
                                                                    "tool_result",
                                                                    "signal")
                                                            .contains(row.kind))
                            .orElseThrow(
                                    () ->
                                            new ResponseStatusException(
                                                    HttpStatus.FORBIDDEN,
                                                    "Unknown activation command"));
                return Objects.equals(activation, turn.activationCommandId);
            }
            if (turn.executionGeneration != 0
                    || !Set.of("turn.failed", "turn.interrupted", "turn.cancelled")
                            .contains(fact.type()))
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Execution grant is required");
            return Objects.equals(activation, turn.activationCommandId);
        }
        long generation;
        try {
            generation = Long.parseLong(fact.executionId());
        } catch (NumberFormatException error) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid execution generation");
        }
        var grantId =
                RuntimePayloadDigest.of(mapper, Map.of("turn", turn.id, "generation", generation));
        var grant =
                grants.findById(grantId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.FORBIDDEN, "Unknown execution grant"));
        if (!Objects.equals(grant.fence, fact.executionFence())
                || !Objects.equals(grant.activationCommandId, activation))
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Execution fact fence does not match grant");
        // Expiry prevents new execution. Previously committed facts still need to drain
        // after an outage, including facts from earlier valid activations.
        return generation == turn.executionGeneration
                && Objects.equals(grant.activationCommandId, turn.activationCommandId);
    }

    private void reduce(
            RuntimeFact fact, AgentServiceTurnEntity turn, AgentServiceQuotaEntity quota) {
        String next = fact.type().substring(5);
        if (Set.of("completed", "cancelled").contains(turn.status)) return;
        Set<String> allowed =
                switch (turn.status) {
                    case "queued" ->
                            Boolean.TRUE.equals(fact.payload().get("control_stop"))
                                    ? Set.of(
                                            "running",
                                            "failed",
                                            "interrupted",
                                            "cancelled",
                                            "completed")
                                    : Set.of("running", "failed", "interrupted", "cancelled");
                    case "running" ->
                            Set.of(
                                    "requires_action",
                                    "completed",
                                    "failed",
                                    "interrupted",
                                    "cancelled");
                    case "requires_action" ->
                            Boolean.TRUE.equals(fact.payload().get("native_stopped"))
                                    ? Set.of(
                                            "running",
                                            "completed",
                                            "failed",
                                            "interrupted",
                                            "cancelled")
                                    : Set.of("running", "failed", "interrupted", "cancelled");
                    case "cancel_requested" ->
                            Set.of(
                                    "running",
                                    "requires_action",
                                    "completed",
                                    "cancelled",
                                    "interrupted",
                                    "failed");
                    case "failed", "interrupted" ->
                            "managed".equals(fact.source())
                                            && "completed".equals(next)
                                            && fact.payload().get("native_event_id")
                                                    instanceof String event
                                            && !event.isBlank()
                                            && fact.payload().get("native_sequence")
                                                    instanceof Number sequence
                                            && sequence.longValue() > 0
                                            && fact.payload().get("run_id") instanceof String run
                                            && !run.isBlank()
                                    ? Set.of("cancelled", "completed")
                                    : Set.of("cancelled");
                    default -> Set.of();
                };
        if (!next.equals(turn.status) && !allowed.contains(next))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Invalid runtime Turn transition");
        if (next.equals("running") && turn.queuedReservation) dequeue(turn, quota);
        if (Set.of("completed", "cancelled", "failed", "interrupted").contains(next)
                && "active".equals(turn.reservationState)) {
            if (turn.queuedReservation) dequeue(turn, quota);
            if (quota.activeCount <= 0)
                throw new IllegalStateException("Missing active quota reservation");
            quota.activeCount--;
            turn.reservationState = "released";
            turn.releaseReceipt = fact.eventId();
        }
        if (!"cancel_requested".equals(turn.status)
                || !Set.of("running", "requires_action").contains(next)) turn.status = next;
        turn.updatedAt = System.currentTimeMillis();
    }

    private void dequeue(AgentServiceTurnEntity turn, AgentServiceQuotaEntity quota) {
        if (quota.queuedCount <= 0)
            throw new IllegalStateException("Missing queued quota reservation");
        quota.queuedCount--;
        turn.queuedReservation = false;
    }

    private void accountUsage(Map<String, Object> payload, AgentServiceQuotaEntity quota) {
        if (!(payload.get("usage") instanceof Map<?, ?> usage)) return;
        long input = usage.get("inputTokens") instanceof Number n ? n.longValue() : 0;
        long output = usage.get("outputTokens") instanceof Number n ? n.longValue() : 0;
        if (input < 0 || output < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Negative reported usage");
        quota.tokensUsed = Math.addExact(quota.tokensUsed, Math.addExact(input, output));
    }

    private static Map<String, Object> copy(Map<?, ?> data) {
        var result = new LinkedHashMap<String, Object>();
        data.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private RuntimeFactReceipt receipt(RuntimeFact fact, AgentServiceFactReceiptEntity receipt) {
        return new RuntimeFactReceipt(
                RuntimeProtocol.VERSION,
                fact.context().sessionId(),
                fact.source(),
                fact.eventId(),
                receipt.sourceSequence,
                receipt.publicSequence);
    }

    private <T> T decode(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot decode Turn command", error);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode public fact", error);
        }
    }
}
