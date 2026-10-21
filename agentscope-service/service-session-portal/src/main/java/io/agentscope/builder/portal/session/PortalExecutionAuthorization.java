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
package io.agentscope.builder.portal.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.portal.persistence.PortalExecutionGrantEntity;
import io.agentscope.builder.portal.persistence.PortalExecutionGrantRepository;
import io.agentscope.builder.portal.persistence.PortalInteractionRepository;
import io.agentscope.builder.portal.persistence.PortalQuotaRepository;
import io.agentscope.builder.portal.persistence.PortalSessionEntity;
import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.portal.persistence.PortalTurnRepository;
import io.agentscope.builder.runtime.protocol.InteractionCommand;
import io.agentscope.builder.runtime.protocol.RuntimeBudgetSettings;
import io.agentscope.builder.runtime.protocol.RuntimeExecutionGrant;
import io.agentscope.builder.runtime.protocol.RuntimeExecutionRequest;
import io.agentscope.builder.runtime.protocol.RuntimePayloadDigest;
import io.agentscope.builder.runtime.protocol.RuntimeRequestContext;
import io.agentscope.builder.runtime.protocol.TurnCommand;
import io.agentscope.builder.web.managed.service.VaultCrypto;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Durable, fenced authorization. Only committed runtime facts can mark a Turn running. */
@Service
public class PortalExecutionAuthorization {
    private final PortalSessionRepository sessions;
    private final PortalTurnRepository turns;
    private final PortalQuotaRepository quotas;
    private final PortalExecutionGrantRepository grants;
    private final PortalInteractionRepository interactions;
    private final PortalSessionAccess access;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final VaultCrypto crypto;
    private final long grantTtl;

    public PortalExecutionAuthorization(
            PortalSessionRepository sessions,
            PortalTurnRepository turns,
            PortalQuotaRepository quotas,
            PortalExecutionGrantRepository grants,
            PortalInteractionRepository interactions,
            PortalSessionAccess access,
            TransactionTemplate tx,
            ObjectMapper mapper,
            VaultCrypto crypto,
            @Value("${builder.portal.execution-grant-ms:30000}") long grantTtl) {
        if (grantTtl <= 0)
            throw new IllegalArgumentException("Execution grant TTL must be positive");
        this.sessions = sessions;
        this.turns = turns;
        this.quotas = quotas;
        this.grants = grants;
        this.interactions = interactions;
        this.access = access;
        this.tx = tx;
        this.mapper = mapper;
        this.crypto = crypto;
        this.grantTtl = grantTtl;
    }

    public RuntimeExecutionGrant authorize(RuntimeExecutionRequest request) {
        return tx.execute(
                ignored -> {
                    var context = request.context();
                    var session =
                            sessions.lockById(context.sessionId())
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND));
                    var turn =
                            turns.lockById(context.turnId())
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND));
                    var original = decode(crypto.decrypt(turn.command), TurnCommand.class);
                    var admitted = original.context();
                    if (!turn.sessionId.equals(session.id)
                            || !admitted.tenant().equals(context.tenant())
                            || !admitted.namespace().equals(context.namespace())
                            || !Objects.equals(admitted.applicationId(), context.applicationId())
                            || !admitted.commandId().equals(context.commandId())
                            || !admitted.actor().equals(context.actor())
                            || !turn.inputDigest.equals(request.inputDigest())
                            || turn.admissionSequence != request.admissionSequence())
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                    if (!"ready".equals(session.state)
                            || !"active".equals(session.lifecycle)
                            || !"active".equals(turn.reservationState)
                            || !Set.of("queued", "running", "requires_action")
                                    .contains(turn.status))
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Turn cannot execute");
                    access.authorizeActor(session, admitted.actor(), "invoke");
                    if (!Objects.equals(turn.activationCommandId, request.activationCommandId())) {
                        var pending = interactions.findById(turn.activationCommandId);
                        if (request.renewal()
                                && pending.isPresent()
                                && "queued".equals(pending.get().nativeStatus)
                                && "pending".equals(pending.get().deliveryStatus)) {
                            var transition =
                                    decode(
                                            crypto.decrypt(pending.get().command),
                                            InteractionCommand.class);
                            if (Set.of("resume", "approval", "tool_result", "signal")
                                            .contains(transition.kind())
                                    && turn.id.equals(transition.context().turnId())
                                    && request.activationCommandId()
                                            .equals(
                                                    transition
                                                            .payload()
                                                            .get("target_activation_command_id"))
                                    && request.executionFence()
                                            .equals(
                                                    transition
                                                            .payload()
                                                            .get("source_execution_fence"))
                                    && transition.payload().get("expected_generation")
                                            instanceof Number generation
                                    && generation.longValue() == request.executionGeneration())
                                throw new ResponseStatusException(
                                        HttpStatus.TOO_EARLY,
                                        "A durable activation command is waiting for runtime"
                                                + " admission");
                        }
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                    }
                    if (!turn.id.equals(turn.activationCommandId)) {
                        var intent =
                                interactions
                                        .findById(turn.activationCommandId)
                                        .filter(
                                                row ->
                                                        row.sessionId.equals(session.id)
                                                                && turn.id.equals(row.turnId))
                                        .orElseThrow(
                                                () ->
                                                        new ResponseStatusException(
                                                                HttpStatus.FORBIDDEN,
                                                                "Activation intent is required"));
                        var operation =
                                decode(crypto.decrypt(intent.command), InteractionCommand.class);
                        if (!Set.of("resume", "approval", "tool_result", "signal")
                                .contains(operation.kind()))
                            throw new ResponseStatusException(
                                    HttpStatus.FORBIDDEN, "Invalid activation intent");
                        access.authorizeInteraction(session, operation);
                    }
                    var quota = quotas.lockById(turn.quotaId).orElseThrow();
                    if (quota.tokenBudget > 0 && quota.tokensUsed >= quota.tokenBudget)
                        throw new ResponseStatusException(
                                HttpStatus.TOO_MANY_REQUESTS,
                                "Application reported token budget exhausted");
                    // The runtime allocates a monotonically increasing activation generation in its
                    // own Session transaction, after acquiring the actual execution lease.
                    if (request.executionGeneration() < turn.executionGeneration
                            || request.executionGeneration() == turn.executionGeneration
                                    && !Objects.equals(
                                            request.executionFence(), turn.executionFence)
                            || request.renewal()
                                    && (request.executionGeneration() != turn.executionGeneration
                                            || !Objects.equals(
                                                    request.executionFence(), turn.executionFence))
                            || request.executionGeneration() > turn.executionGeneration
                                    && !"queued".equals(turn.status))
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Execution grant is stale");
                    Map<String, Object> policy = access.policy(session);
                    long policyVersion =
                            policy.get("policy_version") instanceof Number currentVersion
                                    ? currentVersion.longValue()
                                    : access.policyVersion(session);
                    long now = System.currentTimeMillis();
                    long validUntil =
                            Math.min(
                                    now + grantTtl,
                                    policy.get("valid_until") instanceof Number expiry
                                            ? expiry.longValue()
                                            : 0);
                    if (validUntil <= now)
                        throw new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "Session authorization projection expired");
                    turn.executionGeneration = request.executionGeneration();
                    turn.executionFence = request.executionFence();
                    turn.grantUntil = validUntil;
                    turn.updatedAt = now;
                    turns.saveAndFlush(turn);
                    String grantId =
                            RuntimePayloadDigest.of(
                                    mapper,
                                    Map.of(
                                            "turn",
                                            turn.id,
                                            "generation",
                                            turn.executionGeneration));
                    var history =
                            grants.findById(grantId).orElseGet(PortalExecutionGrantEntity::new);
                    history.id = grantId;
                    history.turnId = turn.id;
                    history.generation = turn.executionGeneration;
                    history.activationCommandId = request.activationCommandId();
                    history.fence = turn.executionFence;
                    history.validUntil = Math.max(history.validUntil, validUntil);
                    grants.saveAndFlush(history);
                    return new RuntimeExecutionGrant(
                            new RuntimeRequestContext(
                                    context.version(),
                                    context.tenant(),
                                    context.namespace(),
                                    context.applicationId(),
                                    context.sessionId(),
                                    context.turnId(),
                                    context.commandId(),
                                    context.traceId(),
                                    admitted.actor(),
                                    Long.toString(policyVersion),
                                    validUntil),
                            turn.executionGeneration,
                            turn.executionFence,
                            validUntil,
                            !request.renewal() && "managed".equals(session.runtimeKind)
                                    ? access.configurationOverlay(session, policyVersion)
                                    : null,
                            "managed".equals(session.runtimeKind) ? budgetSettings(session) : null);
                });
    }

    private RuntimeBudgetSettings budgetSettings(PortalSessionEntity session) {
        try {
            return new RuntimeBudgetSettings(
                    session.budgetRevision,
                    mapper.readValue(
                            session.budgetLimits, new TypeReference<Map<String, Object>>() {}));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot read Session budget settings", error);
        }
    }

    private <T> T decode(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot decode admission", error);
        }
    }
}
