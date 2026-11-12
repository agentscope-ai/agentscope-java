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
import io.agentcore.managed.server.agentservice.persistence.AgentServiceInteractionEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceInteractionRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceTurnRepository;
import io.agentcore.managed.server.runtime.protocol.InteractionCommand;
import io.agentcore.managed.server.runtime.protocol.RuntimePayloadDigest;
import io.agentcore.managed.server.runtime.protocol.RuntimeProtocol;
import io.agentcore.managed.server.runtime.protocol.RuntimeRequestContext;
import io.agentcore.managed.server.web.managed.AgentSessionInput;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Persists an input intent, without claiming that the native runtime has received it. */
@Service
public class AgentServiceInputAdmission {
    private final AgentServiceSessionRepository sessions;
    private final AgentServiceTurnRepository turns;
    private final AgentServiceInteractionRepository interactions;
    private final AgentServiceSessionAccess access;
    private final AgentServiceTurnAdmission validation;
    private final ObjectMapper mapper;
    private final VaultCrypto crypto;
    private final TransactionTemplate tx;
    private final int maxPending;

    public AgentServiceInputAdmission(
            AgentServiceSessionRepository sessions,
            AgentServiceTurnRepository turns,
            AgentServiceInteractionRepository interactions,
            AgentServiceSessionAccess access,
            AgentServiceTurnAdmission validation,
            ObjectMapper mapper,
            VaultCrypto crypto,
            TransactionTemplate tx,
            @Value("${managed-agents.agent-service.max-pending-inputs-per-session:128}")
                    int maxPending) {
        if (maxPending < 1)
            throw new IllegalArgumentException("Input queue limit must be positive");
        this.sessions = sessions;
        this.turns = turns;
        this.interactions = interactions;
        this.access = access;
        this.validation = validation;
        this.mapper = mapper;
        this.crypto = crypto;
        this.tx = tx;
        this.maxPending = maxPending;
    }

    public AgentServiceInteractionEntity stage(
            String session,
            String turn,
            String kind,
            AgentSessionInput input,
            HttpHeaders headers) {
        if (!List.of("steer", "inject").contains(kind)
                || ("inject".equals(kind) && turn != null)
                || ("steer".equals(kind) && (turn == null || turn.isBlank())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid input scope");
        var actor = access.authorize(session, headers, "invoke");
        var payload = input.normalized();
        String key = headers.getFirst("Idempotency-Key");
        if (key == null || key.isBlank()) key = UUID.randomUUID().toString();
        if (key.length() > 256)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Idempotency-Key exceeds 256 characters");
        var registry = sessions.findById(session).orElseThrow();
        var scope = new LinkedHashMap<String, Object>();
        scope.put("tenant", registry.tenant);
        scope.put("namespace", registry.namespace);
        scope.put("session", session);
        scope.put("turn", turn);
        scope.put("kind", kind);
        scope.put(
                "principal",
                String.valueOf(actor.get("key_hash")).isBlank()
                        ? "user:" + actor.get("user")
                        : "application:" + registry.applicationId);
        scope.put("key", key);
        String id = RuntimePayloadDigest.of(mapper, scope);
        String digest = RuntimePayloadDigest.of(mapper, payload);
        return tx.execute(
                ignored -> {
                    var current = sessions.lockById(session).orElseThrow();
                    var authorized = access.authorize(session, headers, "invoke");
                    var previous = interactions.findById(id);
                    if (previous.isPresent()) {
                        if (!previous.get().payloadDigest.equals(digest))
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "Idempotency-Key was already used with different input");
                        return previous.get();
                    }
                    if (!"managed".equals(current.runtimeKind))
                        throw new ResponseStatusException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "Backend input capability is not connected");
                    if (!"ready".equals(current.state) || !"active".equals(current.lifecycle))
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Session is not ready and active");
                    if ("steer".equals(kind)) {
                        var target =
                                turns.findById(turn)
                                        .filter(value -> value.sessionId.equals(session))
                                        .orElseThrow(
                                                () ->
                                                        new ResponseStatusException(
                                                                HttpStatus.NOT_FOUND));
                        if (!"running".equals(target.status))
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT, "Turn is not running");
                    }
                    var policy = access.policy(current);
                    validation.validateInput(policy, payload);
                    if (interactions.countBySessionIdAndKindInAndNativeStatusIn(
                                    session,
                                    List.of("steer", "inject"),
                                    List.of("queued", "accepted"))
                            >= maxPending)
                        throw new ResponseStatusException(
                                HttpStatus.TOO_MANY_REQUESTS, "Pending input limit reached");
                    long now = System.currentTimeMillis();
                    long until = ((Number) policy.get("valid_until")).longValue();
                    if ("steer".equals(kind)) until = Math.min(until, now + 10000);
                    var context =
                            new RuntimeRequestContext(
                                    RuntimeProtocol.VERSION,
                                    current.tenant,
                                    current.namespace,
                                    current.applicationId,
                                    session,
                                    turn,
                                    id,
                                    UUID.randomUUID().toString(),
                                    authorized,
                                    Long.toString(access.policyVersion(current)),
                                    until);
                    var row = new AgentServiceInteractionEntity();
                    row.id = id;
                    row.sessionId = session;
                    row.turnId = turn;
                    row.kind = kind;
                    row.payloadDigest = digest;
                    row.command =
                            crypto.encrypt(json(new InteractionCommand(context, kind, payload)));
                    row.deliveryStatus = "pending";
                    row.nativeStatus = "queued";
                    row.acceptUntil = until;
                    row.createdAt = now;
                    row.updatedAt = now;
                    return interactions.saveAndFlush(row);
                });
    }

    public Map<String, Object> get(String session, String id, HttpHeaders headers) {
        access.authorize(session, headers, "read");
        return view(
                interactions
                        .findById(id)
                        .filter(row -> row.sessionId.equals(session))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    public Map<String, Object> view(AgentServiceInteractionEntity row) {
        var result = new LinkedHashMap<String, Object>();
        boolean input = List.of("steer", "inject").contains(row.kind);
        result.put(input ? "input_id" : "command_id", row.id);
        result.put("session_id", row.sessionId);
        result.put("turn_id", row.turnId);
        result.put("kind", row.kind);
        result.put("status", "queued".equals(row.nativeStatus) ? "queued" : "runtime_accepted");
        result.put(input ? "input_status" : "command_status", row.nativeStatus);
        result.put("created_at", row.createdAt);
        return result;
    }

    public InteractionCommand command(AgentServiceInteractionEntity row) {
        try {
            return mapper.readValue(crypto.decrypt(row.command), InteractionCommand.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot decode input intent", error);
        }
    }

    /** Refreshes only transport admission; the durable input identity and original caller stay fixed. */
    public InteractionCommand forDelivery(AgentServiceInteractionEntity input) {
        return tx.execute(
                ignored -> {
                    var session = sessions.lockById(input.sessionId).orElseThrow();
                    var original = command(input);
                    long until;
                    try {
                        if (!"ready".equals(session.state) || !"active".equals(session.lifecycle))
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT, "Session is not active");
                        access.authorizeInteraction(session, original);
                        until = ((Number) access.policy(session).get("valid_until")).longValue();
                        if ("steer".equals(input.kind)) until = Math.min(until, input.acceptUntil);
                    } catch (ResponseStatusException denied) {
                        if (!"managed".equals(session.runtimeKind)
                                || !List.of("steer", "inject").contains(input.kind)
                                || !List.of(401, 403, 404, 409)
                                        .contains(denied.getStatusCode().value())) throw denied;
                        // Native acceptance wins; otherwise persist rejection of this input ID.
                        until = 1;
                    }
                    var context = original.context();
                    return new InteractionCommand(
                            new RuntimeRequestContext(
                                    context.version(),
                                    context.tenant(),
                                    context.namespace(),
                                    context.applicationId(),
                                    context.sessionId(),
                                    context.turnId(),
                                    context.commandId(),
                                    context.traceId(),
                                    context.actor(),
                                    Long.toString(access.policyVersion(session)),
                                    until),
                            original.kind(),
                            original.payload());
                });
    }

    public AgentServiceInteractionEntity row(String id) {
        return interactions
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode input intent", error);
        }
    }
}
