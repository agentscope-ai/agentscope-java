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
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.portal.persistence.PortalEventEntity;
import io.agentscope.builder.portal.persistence.PortalEventRepository;
import io.agentscope.builder.portal.persistence.PortalLifecycleEntity;
import io.agentscope.builder.portal.persistence.PortalLifecycleRepository;
import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.portal.persistence.PortalTurnRepository;
import io.agentscope.builder.runtime.protocol.RuntimeLifecycleDigest;
import io.agentscope.builder.runtime.protocol.RuntimeLifecycleReceipt;
import io.agentscope.builder.runtime.protocol.RuntimePayloadDigest;
import io.agentscope.builder.runtime.protocol.RuntimeProtocol;
import io.agentscope.builder.runtime.protocol.RuntimeRequestContext;
import io.agentscope.builder.runtime.protocol.RuntimeSessionLifecycle;
import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import io.agentscope.builder.web.managed.service.VaultCrypto;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Lifecycle admission changes desired state atomically; runtime mirroring uses a separate outbox. */
@Service
public class PortalLifecycles {
    private final PortalSessionRepository sessions;
    private final PortalTurnRepository turns;
    private final PortalLifecycleRepository commands;
    private final PortalEventRepository events;
    private final PortalSessionAccess access;
    private final PortalEventNotifications notifications;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final VaultCrypto crypto;
    private final PortalRuntimeBackends backends;
    private final String token;
    private final Semaphore slots = new Semaphore(8);

    public PortalLifecycles(
            PortalSessionRepository sessions,
            PortalTurnRepository turns,
            PortalLifecycleRepository commands,
            PortalEventRepository events,
            PortalSessionAccess access,
            PortalEventNotifications notifications,
            TransactionTemplate tx,
            ObjectMapper mapper,
            VaultCrypto crypto,
            PortalRuntimeBackends backends,
            @Value("${builder.internal-token:${BUILDER_INTERNAL_TOKEN:}}") String token) {
        this.sessions = sessions;
        this.turns = turns;
        this.commands = commands;
        this.events = events;
        this.access = access;
        this.notifications = notifications;
        this.tx = tx;
        this.mapper = mapper;
        this.crypto = crypto;
        this.token = token;
        this.backends = backends;
    }

    public Map<String, Object> stage(
            String sessionId, String desired, Long expectedRevision, HttpHeaders headers) {
        var actor =
                access.authorizeLifecycle(
                        sessionId, headers, "active".equals(desired) ? "invoke" : "interact");
        return stageActor(
                sessionId, desired, expectedRevision, actor, headers.getFirst("Idempotency-Key"));
    }

    public Map<String, Object> stageActor(
            String sessionId,
            String desired,
            Long expectedRevision,
            Map<String, Object> actor,
            String key) {
        if (!Set.of("active", "archived", "deleted").contains(desired))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Session lifecycle");
        if (key == null || key.isBlank()) key = UUID.randomUUID().toString();
        if (key.length() > 256)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Idempotency-Key exceeds 256 characters");
        var selected = sessions.findById(sessionId).orElseThrow();
        String principal =
                String.valueOf(actor.get("key_hash")).isBlank()
                        ? "user:" + actor.get("user")
                        : "application:" + selected.applicationId;
        String id =
                RuntimePayloadDigest.of(
                        mapper,
                        Map.of(
                                "session",
                                sessionId,
                                "principal",
                                principal,
                                "kind",
                                "lifecycle",
                                "key",
                                key));
        String digest =
                RuntimePayloadDigest.of(
                        mapper,
                        Map.of(
                                "lifecycle",
                                desired,
                                "expected_revision",
                                expectedRevision == null ? 0 : expectedRevision));
        return tx.execute(
                ignored -> {
                    var session = sessions.lockById(sessionId).orElseThrow();
                    access.authorizeLifecycleActor(
                            session, actor, "active".equals(desired) ? "invoke" : "interact");
                    var authorized = actor;
                    var prior = commands.findById(id);
                    if (prior.isPresent()) {
                        if (!prior.get().inputDigest.equals(digest))
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT, "Lifecycle idempotency key conflicts");
                        return view(prior.get());
                    }
                    if ("deleted".equals(session.lifecycle))
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                    if (!"ready".equals(session.state))
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Session is not ready");
                    if (expectedRevision != null && expectedRevision != session.lifecycleRevision)
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Session lifecycle revision changed");
                    if (turns.countUnsettled(sessionId) > 0)
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Session has unsettled Turns; cancel and await their terminal"
                                        + " states first");
                    long now = System.currentTimeMillis();
                    session.lifecycle = desired;
                    session.lifecycleRevision++;
                    session.updatedAt = now;
                    var policy = access.policy(session);
                    var context =
                            new RuntimeRequestContext(
                                    RuntimeProtocol.VERSION,
                                    session.tenant,
                                    session.namespace,
                                    session.applicationId,
                                    sessionId,
                                    null,
                                    id,
                                    UUID.randomUUID().toString(),
                                    authorized,
                                    Long.toString(access.policyVersion(session)),
                                    ((Number) policy.get("valid_until")).longValue());
                    var intent =
                            new RuntimeSessionLifecycle(
                                    context, session.lifecycleRevision, desired);
                    var row = new PortalLifecycleEntity();
                    row.id = id;
                    row.sessionId = sessionId;
                    row.lifecycle = desired;
                    row.revision = session.lifecycleRevision;
                    row.inputDigest = digest;
                    row.payloadDigest = RuntimeLifecycleDigest.of(mapper, intent);
                    row.payload = crypto.encrypt(json(intent));
                    row.createdAt = now;
                    row.updatedAt = now;
                    commands.saveAndFlush(row);
                    var event = new PortalEventEntity();
                    event.id = RuntimePayloadDigest.of(mapper, Map.of("lifecycle_command", id));
                    event.sessionId = sessionId;
                    event.seq = ++session.nextEventSequence;
                    event.type =
                            "active".equals(desired) ? "session.restored" : "session." + desired;
                    event.createdAt = now;
                    event.data =
                            json(
                                    Map.of(
                                            "session_id",
                                            sessionId,
                                            "status",
                                            desired,
                                            "lifecycle_revision",
                                            row.revision,
                                            "command_id",
                                            id));
                    events.saveAndFlush(event);
                    sessions.saveAndFlush(session);
                    notifications.committed(sessionId);
                    return view(row);
                });
    }

    public Map<String, Object> get(String session, String command, HttpHeaders headers) {
        access.authorizeLifecycle(session, headers, "read");
        return view(
                commands.findById(command)
                        .filter(row -> session.equals(row.sessionId))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    public Map<String, Object> view(PortalLifecycleEntity row) {
        return Map.of(
                "command_id",
                row.id,
                "session_id",
                row.sessionId,
                "status",
                row.lifecycle,
                "lifecycle_revision",
                row.revision,
                "command_status",
                row.status,
                "updated_at",
                row.updatedAt);
    }

    @Scheduled(
            fixedDelayString = "${builder.portal.lifecycle-poll-ms:1000}",
            initialDelayString = "${builder.portal.lifecycle-poll-ms:1000}",
            scheduler = "portalLifecycleScheduler")
    public void deliverDue() {
        for (String id : commands.findDue(System.currentTimeMillis(), PageRequest.of(0, 8)))
            deliver(id);
    }

    public void deliver(String id) {
        if (!slots.tryAcquire()) return;
        PortalLifecycleEntity claim = null;
        try {
            claim =
                    tx.execute(
                            ignored -> {
                                var row = commands.lockById(id).orElseThrow();
                                long now = System.currentTimeMillis();
                                if (!"pending".equals(row.status)
                                        || row.claimUntil > now
                                        || row.nextAttemptAt > now) return null;
                                row.claimToken = UUID.randomUUID().toString();
                                row.claimUntil = now + 15000;
                                row.attemptCount++;
                                commands.saveAndFlush(row);
                                return row;
                            });
            if (claim == null) return;
            var request = decode(claim.payload);
            var receipt =
                    backends.session(claim.sessionId)
                            .post()
                            .uri("/api/internal/runtime/sessions/lifecycle")
                            .header(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, token)
                            .bodyValue(request)
                            .retrieve()
                            .bodyToMono(RuntimeLifecycleReceipt.class)
                            .block(Duration.ofSeconds(5));
            if (receipt == null
                    || receipt.version() != RuntimeProtocol.VERSION
                    || !claim.sessionId.equals(receipt.sessionId())
                    || !claim.id.equals(receipt.commandId())
                    || claim.revision != receipt.lifecycleRevision()
                    || !claim.lifecycle.equals(receipt.lifecycle())
                    || !claim.payloadDigest.equals(receipt.payloadDigest()))
                throw new IllegalStateException("Lifecycle receipt conflicts");
            var accepted = claim;
            tx.executeWithoutResult(
                    ignored -> {
                        var row = commands.lockById(id).orElseThrow();
                        if (!Objects.equals(row.claimToken, accepted.claimToken)
                                || row.claimUntil <= System.currentTimeMillis()) return;
                        row.status = receipt.status();
                        row.claimToken = null;
                        row.claimUntil = 0;
                        row.updatedAt = System.currentTimeMillis();
                        commands.saveAndFlush(row);
                    });
        } catch (Exception failure) {
            if (claim != null) {
                var failed = claim;
                tx.executeWithoutResult(
                        ignored -> {
                            var row = commands.lockById(id).orElseThrow();
                            if (!Objects.equals(row.claimToken, failed.claimToken)) return;
                            row.claimToken = null;
                            row.claimUntil = 0;
                            row.nextAttemptAt =
                                    System.currentTimeMillis()
                                            + Math.min(
                                                    20000, 1000L << Math.min(row.attemptCount, 5))
                                            + ThreadLocalRandom.current().nextLong(500);
                            row.updatedAt = System.currentTimeMillis();
                            commands.saveAndFlush(row);
                        });
            }
        } finally {
            slots.release();
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
    }

    private RuntimeSessionLifecycle decode(String payload) {
        try {
            return mapper.readValue(crypto.decrypt(payload), RuntimeSessionLifecycle.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
    }
}
