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
import io.agentcore.managed.server.agentservice.persistence.AgentServiceQuotaEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceQuotaRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceTurnEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceTurnRepository;
import io.agentcore.managed.server.runtime.protocol.RuntimePayloadDigest;
import io.agentcore.managed.server.runtime.protocol.RuntimeProtocol;
import io.agentcore.managed.server.runtime.protocol.RuntimeRequestContext;
import io.agentcore.managed.server.runtime.protocol.TurnCommand;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import io.agentscope.core.tool.ToolValidator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** One transaction owns the public Turn, reservation, accepted event and durable outbox. */
@Service
public class AgentServiceTurnAdmission {
    private final AgentServiceSessionRepository sessions;
    private final AgentServiceTurnRepository turns;
    private final AgentServiceQuotaRepository quotas;
    private final AgentServiceEventRepository events;
    private final AgentServiceSessionAccess access;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final VaultCrypto crypto;
    private final AgentServiceEventNotifications notifications;
    private final AgentServiceResourceProjection projection;
    private final int maxQueued;
    private final int maxSessionQueued;
    private final int maxRequestsPerSecond;

    public AgentServiceTurnAdmission(
            AgentServiceSessionRepository sessions,
            AgentServiceTurnRepository turns,
            AgentServiceQuotaRepository quotas,
            AgentServiceEventRepository events,
            AgentServiceSessionAccess access,
            TransactionTemplate tx,
            ObjectMapper mapper,
            VaultCrypto crypto,
            AgentServiceEventNotifications notifications,
            AgentServiceResourceProjection projection,
            @Value("${managed-agents.agent-service.max-queued-per-application:1000}") int maxQueued,
            @Value("${managed-agents.agent-service.max-queued-per-session:128}")
                    int maxSessionQueued,
            @Value("${managed-agents.agent-service.requests-per-second:100}")
                    int maxRequestsPerSecond) {
        this.sessions = sessions;
        this.turns = turns;
        this.quotas = quotas;
        this.events = events;
        this.access = access;
        this.tx = tx;
        this.mapper = mapper;
        this.crypto = crypto;
        this.notifications = notifications;
        this.projection = projection;
        if (maxQueued <= 0 || maxSessionQueued <= 0 || maxRequestsPerSecond <= 0)
            throw new IllegalArgumentException("Agent Service admission limits must be positive");
        this.maxQueued = maxQueued;
        this.maxSessionQueued = maxSessionQueued;
        this.maxRequestsPerSecond = maxRequestsPerSecond;
    }

    public Map<String, Object> accept(
            String sessionId, HttpHeaders headers, Map<String, Object> input) {
        var actor = access.authorize(sessionId, headers, "invoke");
        return acceptActor(sessionId, actor, input, headers.getFirst("Idempotency-Key"));
    }

    /** Used by the durable public event inbox; current grants are checked again on dispatch. */
    public Map<String, Object> acceptActor(
            String sessionId, Map<String, Object> actor, Map<String, Object> input, String key) {
        var session = sessions.findById(sessionId).orElseThrow();
        access.authorizeActor(session, actor, "invoke");
        if (key == null || key.isBlank()) key = UUID.randomUUID().toString();
        if (key.length() > 256)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Idempotency-Key exceeds 256 characters");
        String principal =
                String.valueOf(actor.get("key_hash")).isBlank()
                        ? "user:" + actor.get("user")
                        : "application:" + session.applicationId;
        String id =
                RuntimePayloadDigest.of(
                        mapper,
                        Map.of(
                                "tenant",
                                session.tenant,
                                "namespace",
                                session.namespace,
                                "session",
                                sessionId,
                                "principal",
                                principal,
                                "key",
                                key));
        String inputDigest = RuntimePayloadDigest.of(mapper, input);
        String quotaId =
                RuntimePayloadDigest.of(
                        mapper,
                        Map.of(
                                "tenant",
                                session.tenant,
                                "namespace",
                                session.namespace,
                                "principal",
                                session.applicationId == null
                                        ? "user:" + actor.get("user")
                                        : "application:" + session.applicationId));
        ensureQuota(session, quotaId);
        String admissionKey = key;
        return tx.execute(
                ignored -> {
                    var current = sessions.lockById(sessionId).orElseThrow();
                    access.authorizeActor(current, actor, "invoke");
                    var authorized = actor;
                    var previous = turns.findById(id);
                    if (previous.isPresent()) {
                        if (!previous.get().inputDigest.equals(inputDigest))
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "Idempotency-Key was already used with different input");
                        return view(previous.get());
                    }
                    if (!"ready".equals(current.state) || !"active".equals(current.lifecycle))
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Session is not ready and active");
                    var policy = policy(current);
                    validateInput(policy, input);
                    var quota = quotas.lockById(quotaId).orElseThrow();
                    reserve(current, quota, true);
                    long now = System.currentTimeMillis();
                    var row = new AgentServiceTurnEntity();
                    row.id = id;
                    row.activationCommandId = id;
                    row.sessionId = sessionId;
                    row.quotaId = quotaId;
                    row.inputDigest = inputDigest;
                    row.admissionSequence = ++current.nextTurnSequence;
                    row.status = "queued";
                    row.reservationState = "active";
                    row.queuedReservation = true;
                    row.permissionUntil = number(policy.get("valid_until"));
                    var context =
                            new RuntimeRequestContext(
                                    RuntimeProtocol.VERSION,
                                    current.tenant,
                                    current.namespace,
                                    current.applicationId,
                                    sessionId,
                                    id,
                                    id,
                                    UUID.randomUUID().toString(),
                                    authorized,
                                    Long.toString(access.policyVersion(current)),
                                    row.permissionUntil);
                    row.command =
                            crypto.encrypt(
                                    json(
                                            new TurnCommand(
                                                    context,
                                                    admissionKey,
                                                    inputDigest,
                                                    row.admissionSequence,
                                                    input)));
                    row.deliveryStatus = "pending";
                    row.createdAt = now;
                    row.updatedAt = now;
                    turns.saveAndFlush(row);
                    var event = new AgentServiceEventEntity();
                    event.id = id;
                    event.sessionId = sessionId;
                    event.turnId = id;
                    event.seq = ++current.nextEventSequence;
                    event.type = "turn.accepted";
                    event.data =
                            json(
                                    Map.of(
                                            "turn_id",
                                            id,
                                            "status",
                                            "queued",
                                            "source",
                                            Map.of("kind", "portal", "command_id", id)));
                    event.createdAt = now;
                    events.saveAndFlush(event);
                    projection.apply(current, event);
                    notifications.committed(event.sessionId);
                    current.updatedAt = now;
                    sessions.saveAndFlush(current);
                    return view(row);
                });
    }

    public Map<String, Object> get(String sessionId, String turnId, HttpHeaders headers) {
        access.authorize(sessionId, headers, "read");
        var row =
                turns.findById(turnId)
                        .filter(turn -> turn.sessionId.equals(sessionId))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return view(row);
    }

    /** Called with Session and Turn locks held, before committing a new activation intent. */
    void reserveResume(AgentServiceSessionEntity session, AgentServiceTurnEntity turn) {
        if (!"ready".equals(session.state) || !"active".equals(session.lifecycle))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Session is not ready and active");
        try {
            var original = mapper.readValue(crypto.decrypt(turn.command), TurnCommand.class);
            access.authorizeActor(session, original.context().actor(), "invoke");
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot read original Turn actor", error);
        }
        var quota = quotas.lockById(turn.quotaId).orElseThrow();
        reserve(session, quota, !"active".equals(turn.reservationState));
        turn.reservationState = "active";
        turn.queuedReservation = true;
        turn.releaseReceipt = null;
        turn.permissionUntil = number(policy(session).get("valid_until"));
        turn.status = "queued";
        turn.executionFence = null;
        turn.grantUntil = 0;
    }

    private void reserve(
            AgentServiceSessionEntity current,
            AgentServiceQuotaEntity quota,
            boolean acquireActive) {
        var policy = policy(current);
        var application = map(policy.get("application"));
        long policyVersion = number(application.get("version"));
        if (policyVersion >= quota.configurationVersion) {
            quota.maxConcurrent = number(application.get("maxConcurrent"));
            quota.tokenBudget = number(application.get("tokenBudget"));
            quota.configurationVersion = policyVersion;
        }
        if (acquireActive && quota.maxConcurrent > 0 && quota.activeCount >= quota.maxConcurrent)
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "Application concurrency limit exceeded");
        if (quota.tokenBudget > 0 && quota.tokensUsed >= quota.tokenBudget)
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "Application token budget exhausted");
        if (quota.queuedCount >= maxQueued
                || turns.countBySessionIdAndStatus(current.id, "queued") >= maxSessionQueued)
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "Turn queue limit exceeded");
        long now = System.currentTimeMillis();
        if (now - quota.windowStart >= 1000) {
            quota.windowStart = now;
            quota.windowCount = 0;
        }
        if (quota.windowCount >= maxRequestsPerSecond)
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "Turn admission rate exceeded");
        quota.windowCount++;
        if (acquireActive) quota.activeCount++;
        quota.queuedCount++;
        quotas.saveAndFlush(quota);
    }

    private void ensureQuota(AgentServiceSessionEntity session, String id) {
        if (quotas.existsById(id)) return;
        var application = map(policy(session).get("application"));
        var row = new AgentServiceQuotaEntity();
        row.id = id;
        row.tenant = session.tenant;
        row.namespace = session.namespace;
        row.applicationId = session.applicationId;
        row.maxConcurrent = number(application.get("maxConcurrent"));
        row.tokenBudget = number(application.get("tokenBudget"));
        row.tokensUsed = number(application.get("tokensUsed"));
        row.configurationVersion = number(application.get("version"));
        try {
            tx.executeWithoutResult(ignored -> quotas.saveAndFlush(row));
        } catch (DataIntegrityViolationException race) {
            if (!quotas.existsById(id)) throw race;
        }
    }

    void validateInput(Map<String, Object> policy, Map<String, Object> input) {
        for (String reserved :
                List.of("actor", "runtimeContext", "executionFence", "sessionId", "turnId"))
            if (input.containsKey(reserved))
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Runtime identity cannot be supplied in Turn input");
        Object schema = policy.get("input_schema");
        if (Boolean.FALSE.equals(schema))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Input schema rejects this Turn");
        validateLocalReferences(schema);
        String error =
                ToolValidator.validateInput(
                        json(
                                input.containsKey("message")
                                        ? input.get("message")
                                        : input.get("input")),
                        map(schema));
        if (error != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error);
    }

    private static void validateLocalReferences(Object value) {
        if (value instanceof Map<?, ?> object) {
            for (var entry : object.entrySet()) {
                if ("$ref".equals(entry.getKey())
                        && entry.getValue() instanceof String reference
                        && !reference.startsWith("#"))
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Input schema must use local references");
                validateLocalReferences(entry.getValue());
            }
        } else if (value instanceof List<?> list) {
            list.forEach(AgentServiceTurnAdmission::validateLocalReferences);
        }
    }

    public Map<String, Object> view(AgentServiceTurnEntity row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", row.id);
        result.put("sessionId", row.sessionId);
        result.put("status", row.status);
        result.put("deliveryStatus", row.deliveryStatus);
        result.put("admissionSequence", row.admissionSequence);
        result.put("createdAt", row.createdAt);
        result.put("updatedAt", row.updatedAt);
        result.put("usage", projection.read(row.usageSummary));
        if (row.failureCode != null) result.put("deliveryError", row.failureCode);
        return result;
    }

    private Map<String, Object> policy(AgentServiceSessionEntity session) {
        return access.policy(session);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode Turn admission", error);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : new LinkedHashMap<>();
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }
}
