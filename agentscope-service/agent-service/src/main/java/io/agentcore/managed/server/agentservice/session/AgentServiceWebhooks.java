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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceEventRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceWebhookDeliveryEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceWebhookDeliveryRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceWebhookEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceWebhookRepository;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.PublicEventReference;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.protocol.RuntimePayloadDigest;
import io.agentcore.managed.server.runtime.protocol.RuntimeWebhookDelivery;
import io.agentcore.managed.server.runtime.protocol.WebhookDestination;
import io.agentcore.managed.server.web.managed.SessionEventCursor;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

/** Agent Service owns subscription, event cursor, claims and receipts; Scheduler owns network I/O. */
@Service
public class AgentServiceWebhooks {
    private static final long NATIVE_IDLE_RECHECK_MS = 5000;
    private final AgentServiceWebhookRepository hooks;
    private final AgentServiceWebhookDeliveryRepository deliveries;
    private final AgentServiceSessionRepository sessions;
    private final AgentServiceEventRepository events;
    private final ManagedJournalStore journal;
    private final AgentServiceSessionAccess access;
    private final VaultCrypto crypto;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final WebhookDestination destinations;
    private final int maxHooks;

    public AgentServiceWebhooks(
            AgentServiceWebhookRepository hooks,
            AgentServiceWebhookDeliveryRepository deliveries,
            AgentServiceSessionRepository sessions,
            AgentServiceEventRepository events,
            ManagedJournalStore journal,
            AgentServiceSessionAccess access,
            VaultCrypto crypto,
            ObjectMapper mapper,
            TransactionTemplate tx,
            @Value("${managed-agents.webhooks.allowed-hosts:}") String allowedHosts,
            @Value("${managed-agents.webhooks.allow-loopback-http:false}")
                    boolean allowLoopbackHttp,
            @Value("${managed-agents.agent-service.max-webhooks-per-session:64}") int maxHooks) {
        if (maxHooks < 1 || maxHooks > 256)
            throw new IllegalArgumentException("Invalid subscription limit");
        this.hooks = hooks;
        this.deliveries = deliveries;
        this.sessions = sessions;
        this.events = events;
        this.journal = journal;
        this.access = access;
        this.crypto = crypto;
        this.mapper = mapper;
        this.tx = tx;
        this.destinations = new WebhookDestination(allowedHosts, allowLoopbackHttp);
        this.maxHooks = maxHooks;
    }

    public record Registration(String url, List<String> event_types) {}

    public Map<String, Object> register(
            String session, HttpHeaders headers, String key, Registration input) {
        var actor = access.authorize(session, headers, "webhooks:write");
        access.authorize(session, headers, "read");
        if (key == null || key.isBlank() || key.length() > 256 || input == null)
            throw bad("A valid key and registration are required");
        destination(input.url());
        var types =
                input.event_types() == null
                        ? List.of(
                                "turn.completed",
                                "turn.failed",
                                "turn.cancelled",
                                "turn.requires_action",
                                "turn.interrupted")
                        : input.event_types();
        if (types.isEmpty()
                || types.size() > 100
                || types.stream()
                        .anyMatch(
                                type ->
                                        type == null
                                                || !type.matches(
                                                        "[a-z_]+\\.(?:[a-z_]+\\.)*(?:[a-z_]+|\\*)")))
            throw bad("Invalid event_types");
        types = types.stream().distinct().sorted().toList();
        String id =
                RuntimePayloadDigest.of(
                        mapper, Map.of("session", session, "actor", actor, "key", key));
        String digest = RuntimePayloadDigest.of(mapper, Map.of("url", input.url(), "types", types));
        var retainedTypes = types;
        return tx.execute(
                ignored -> {
                    var root = sessions.lockById(session).orElseThrow();
                    access.authorizeActor(root, actor, "webhooks:write");
                    access.authorizeActor(root, actor, "read");
                    if (root.rootSessionId != null)
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Subscriptions use the root Session");
                    var row = hooks.findById(id).orElse(null);
                    if (row == null) {
                        if (hooks.countBySessionIdAndStateIn(
                                        session, List.of("active", "idle", "failed", "suspended"))
                                >= maxHooks)
                            throw new ResponseStatusException(
                                    HttpStatus.TOO_MANY_REQUESTS, "Session webhook limit reached");
                        row = new AgentServiceWebhookEntity();
                        row.id = id;
                        row.sessionId = session;
                        row.url = input.url();
                        byte[] secret = new byte[32];
                        new SecureRandom().nextBytes(secret);
                        row.secret =
                                crypto.encrypt(
                                        Base64.getUrlEncoder()
                                                .withoutPadding()
                                                .encodeToString(secret));
                        row.actor = crypto.encrypt(json(actor));
                        row.eventTypes = json(retainedTypes);
                        row.inputDigest = digest;
                        row.cursorSeq = head(root);
                        row.state = "idle";
                        row.nextAttemptAt = idleRecheck(root, System.currentTimeMillis());
                        row.createdAt = row.updatedAt = System.currentTimeMillis();
                        hooks.saveAndFlush(row);
                    } else if (!digest.equals(row.inputDigest))
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Webhook key reused with different registration");
                    var result = view(root, row);
                    if (!"deleted".equals(row.state))
                        result.put("signing_secret", crypto.decrypt(row.secret));
                    return result;
                });
    }

    public Map<String, Object> list(String session, HttpHeaders headers, String after, int limit) {
        access.authorize(session, headers, "read");
        checkPage(after, limit);
        var root = sessions.findById(session).orElseThrow();
        var rows =
                hooks.findBySessionIdAndIdGreaterThanOrderByIdAsc(
                        session, after == null ? "" : after, PageRequest.of(0, limit + 1));
        var data = rows.stream().limit(limit).map(row -> view(root, row)).toList();
        return page(
                data,
                rows.size() > limit,
                data.isEmpty() ? null : String.valueOf(data.get(data.size() - 1).get("id")));
    }

    public Map<String, Object> update(
            String session, String id, HttpHeaders headers, boolean delete) {
        var actor = access.authorize(session, headers, "webhooks:write");
        return tx.execute(
                ignored -> {
                    var root = sessions.lockById(session).orElseThrow();
                    access.authorizeActor(root, actor, "webhooks:write");
                    var row =
                            hooks.lockById(id)
                                    .filter(w -> session.equals(w.sessionId))
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND));
                    if (delete) {
                        row.state = "deleted";
                        row.secret = "";
                        row.actor = "";
                        row.claimToken = null;
                        row.claimUntil = 0;
                        row.nextAttemptAt = Long.MAX_VALUE;
                    } else {
                        if (!List.of("failed", "suspended").contains(row.state))
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "Only failed or suspended webhooks can be retried");
                        access.authorizeActor(root, readActor(row), "read");
                        destination(row.url);
                        row.state = "active";
                        row.attemptCount = 0;
                        row.claimToken = null;
                        row.claimUntil = 0;
                        row.nextAttemptAt = System.currentTimeMillis();
                        row.failureCode = null;
                    }
                    row.updatedAt = System.currentTimeMillis();
                    hooks.saveAndFlush(row);
                    return view(root, row);
                });
    }

    public Map<String, Object> receipts(
            String session, String id, HttpHeaders headers, String after, int limit) {
        access.authorize(session, headers, "read");
        checkPage(after, limit);
        var root = sessions.findById(session).orElseThrow();
        hooks.findById(id)
                .filter(w -> session.equals(w.sessionId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var rows =
                deliveries.findByWebhookIdAndIdGreaterThanOrderByIdAsc(
                        id, after == null ? "" : after, PageRequest.of(0, limit + 1));
        var data =
                rows.stream()
                        .limit(limit)
                        .map(
                                row ->
                                        Map.<String, Object>of(
                                                "id",
                                                row.id,
                                                "event_id",
                                                row.eventId,
                                                "cursor",
                                                cursor(root, row.eventSeq),
                                                "status",
                                                row.status,
                                                "attempts",
                                                row.attempts,
                                                "http_status",
                                                row.httpStatus,
                                                "error_code",
                                                row.errorCode,
                                                "created_at",
                                                row.createdAt,
                                                "updated_at",
                                                row.updatedAt))
                        .toList();
        return page(
                data,
                rows.size() > limit,
                data.isEmpty() ? null : String.valueOf(data.get(data.size() - 1).get("id")));
    }

    public RuntimeWebhookDelivery.Batch claim(RuntimeWebhookDelivery.ClaimRequest request) {
        var result = new ArrayList<RuntimeWebhookDelivery>();
        for (String id :
                hooks.findDue(System.currentTimeMillis(), PageRequest.of(0, request.limit() * 8))) {
            var delivery = tx.execute(ignored -> claimOne(id));
            if (delivery != null) result.add(delivery);
            if (result.size() == request.limit()) break;
        }
        return new RuntimeWebhookDelivery.Batch(1, result);
    }

    private RuntimeWebhookDelivery claimOne(String id) {
        var session = hooks.sessionById(id).orElse(null);
        if (session == null) return null;
        var root = sessions.lockById(session).orElseThrow();
        var row = hooks.lockById(id).orElseThrow();
        long now = System.currentTimeMillis();
        if (!List.of("active", "idle").contains(row.state)
                || row.claimUntil > now
                || row.nextAttemptAt > now) return null;
        row.state = "active";
        if (!allowed(root, row)) return null;
        if (row.pendingEvent == null) {
            var types = readTypes(row);
            long through = head(root);
            var batch = eventReferences(root, row.cursorSeq, through, 256);
            for (var event : batch) {
                if (types.stream()
                        .anyMatch(
                                type ->
                                        type.equals(event.type())
                                                || type.endsWith(".*")
                                                        && event.type()
                                                                .startsWith(
                                                                        type.substring(
                                                                                0,
                                                                                type.length()
                                                                                        - 1)))) {
                    row.pendingEvent = event.sourceId();
                    row.pendingSeq = event.sequence();
                    row.deliveryId =
                            RuntimePayloadDigest.of(
                                    mapper, Map.of("hook", row.id, "event", event.sourceId()));
                    break;
                }
                row.cursorSeq = event.sequence();
            }
            if (row.pendingEvent == null) {
                row.nextAttemptAt = now;
                if (row.cursorSeq >= through || batch.isEmpty()) {
                    row.state = "idle";
                    row.nextAttemptAt = idleRecheck(root, now);
                }
                row.updatedAt = now;
                hooks.saveAndFlush(row);
                return null;
            }
        }
        if (row.attemptCount >= 8) {
            row.state = "failed";
            row.failureCode = "attempts_exhausted";
            row.claimToken = null;
            row.claimUntil = 0;
            row.nextAttemptAt = Long.MAX_VALUE;
            var previous = deliveries.findById(row.deliveryId).orElseThrow();
            previous.status = "failed";
            previous.errorCode = "lease_expired";
            previous.updatedAt = now;
            deliveries.save(previous);
            hooks.saveAndFlush(row);
            return null;
        }
        var event =
                eventReferences(root, row.pendingSeq - 1, row.pendingSeq, 1).stream()
                        .filter(value -> value.sourceId().equals(row.pendingEvent))
                        .findFirst()
                        .orElseThrow();
        row.claimToken = UUID.randomUUID().toString();
        row.claimFence++;
        row.claimUntil = now + 60000;
        row.nextAttemptAt = row.claimUntil;
        row.attemptCount++;
        row.updatedAt = now;
        var receipt =
                deliveries
                        .findById(row.deliveryId)
                        .orElseGet(AgentServiceWebhookDeliveryEntity::new);
        receipt.id = row.deliveryId;
        receipt.webhookId = row.id;
        receipt.eventId = event.sourceId();
        receipt.eventSeq = event.sequence();
        if (receipt.createdAt == 0) receipt.createdAt = now;
        receipt.updatedAt = now;
        receipt.status = "delivering";
        receipt.attempts++;
        receipt.httpStatus = 0;
        receipt.errorCode = "";
        receipt.claimFence = row.claimFence;
        receipt.claimToken = row.claimToken;
        deliveries.save(receipt);
        hooks.saveAndFlush(row);
        String eventUrl =
                (managed(root) ? "/v1/sessions/" : "/api/v1/agent-sessions/")
                        + UriUtils.encodePathSegment(row.sessionId, StandardCharsets.UTF_8)
                        + (managed(root)
                                ? "/events?after=" + cursor(root, event.sequence() - 1) + "&limit=1"
                                : "/events/" + event.sourceId());
        String payload =
                json(
                        Map.of(
                                "id",
                                event.sourceId(),
                                "type",
                                event.type(),
                                "session_id",
                                row.sessionId,
                                "cursor",
                                cursor(root, event.sequence()),
                                "created_at",
                                Instant.ofEpochMilli(event.createdAt()).toString(),
                                "event_url",
                                eventUrl));
        return new RuntimeWebhookDelivery(
                1,
                row.id,
                row.deliveryId,
                row.sessionId,
                row.claimToken,
                row.claimFence,
                row.claimUntil,
                row.url,
                crypto.decrypt(row.secret),
                payload);
    }

    /** Recheck immediately before physical send; policy refresh failure cannot authorize a send. */
    public Map<String, Object> validate(RuntimeWebhookDelivery.Proof proof) {
        Map<String, Object> result =
                tx.execute(
                        ignored -> {
                            var session =
                                    hooks.sessionById(proof.webhookId())
                                            .orElseThrow(
                                                    () ->
                                                            new ResponseStatusException(
                                                                    HttpStatus.NOT_FOUND));
                            var root = sessions.lockById(session).orElseThrow();
                            var row = hooks.lockById(proof.webhookId()).orElseThrow();
                            checkProof(row, proof);
                            if (!allowed(root, row)) return null;
                            return Map.of(
                                    "version",
                                    1,
                                    "deliveryId",
                                    proof.deliveryId(),
                                    "claimFence",
                                    proof.claimFence(),
                                    "allowed",
                                    true);
                        });
        if (result == null)
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Webhook send is not authorized");
        return result;
    }

    public Map<String, Object> finish(RuntimeWebhookDelivery.Receipt input) {
        var proof = input.proof();
        return tx.execute(
                ignored -> {
                    var session =
                            hooks.sessionById(proof.webhookId())
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND));
                    sessions.lockById(session).orElseThrow();
                    var row = hooks.lockById(proof.webhookId()).orElseThrow();
                    var receipt =
                            deliveries
                                    .findById(proof.deliveryId())
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND));
                    if (!row.id.equals(receipt.webhookId)
                            || receipt.claimFence != proof.claimFence()
                            || !Objects.equals(receipt.claimToken, proof.claimToken()))
                        throw conflict();
                    if (!"delivering".equals(receipt.status)) {
                        if (receipt.httpStatus != input.httpStatus()
                                || !receipt.errorCode.equals(input.errorCode())) throw conflict();
                        return Map.of(
                                "version", 1, "deliveryId", receipt.id, "status", receipt.status);
                    }
                    checkProof(row, proof);
                    long now = System.currentTimeMillis();
                    boolean delivered =
                            input.httpStatus() >= 200
                                    && input.httpStatus() < 300
                                    && input.errorCode().isEmpty();
                    receipt.httpStatus = input.httpStatus();
                    receipt.errorCode = input.errorCode();
                    receipt.updatedAt = now;
                    row.claimToken = null;
                    row.claimUntil = 0;
                    row.updatedAt = now;
                    if (delivered) {
                        receipt.status = "delivered";
                        row.cursorSeq = row.pendingSeq;
                        row.pendingEvent = null;
                        row.pendingSeq = 0;
                        row.deliveryId = null;
                        row.attemptCount = 0;
                        row.failureCode = null;
                        row.nextAttemptAt = now;
                    } else if (row.attemptCount >= 8 || "blocked".equals(input.errorCode())) {
                        receipt.status = "failed";
                        row.state = "failed";
                        row.failureCode =
                                "blocked".equals(input.errorCode())
                                        ? "invalid_destination"
                                        : "attempts_exhausted";
                        row.nextAttemptAt = Long.MAX_VALUE;
                    } else {
                        receipt.status = "retrying";
                        row.nextAttemptAt =
                                now
                                        + Math.min(
                                                300000, 1000L << Math.min(18, row.attemptCount - 1))
                                        + ThreadLocalRandom.current().nextLong(500);
                    }
                    deliveries.save(receipt);
                    hooks.saveAndFlush(row);
                    return Map.of("version", 1, "deliveryId", receipt.id, "status", receipt.status);
                });
    }

    private void checkProof(AgentServiceWebhookEntity row, RuntimeWebhookDelivery.Proof proof) {
        if (!"active".equals(row.state)
                || row.claimUntil <= System.currentTimeMillis()
                || row.claimFence != proof.claimFence()
                || !Objects.equals(row.claimToken, proof.claimToken())
                || !Objects.equals(row.deliveryId, proof.deliveryId())) throw conflict();
    }

    private boolean allowed(AgentServiceSessionEntity root, AgentServiceWebhookEntity row) {
        try {
            access.authorizeActor(root, readActor(row), "read");
            destination(row.url);
            return true;
        } catch (ResponseStatusException error) {
            row.claimToken = null;
            row.claimUntil = 0;
            row.updatedAt = System.currentTimeMillis();
            if (List.of(400, 401, 403, 404).contains(error.getStatusCode().value())) {
                row.state = "suspended";
                row.failureCode = "authorization_or_destination";
                row.nextAttemptAt = Long.MAX_VALUE;
            } else row.nextAttemptAt = row.updatedAt + 3000;
            if (row.deliveryId != null)
                deliveries
                        .findById(row.deliveryId)
                        .ifPresent(
                                receipt -> {
                                    if ("delivering".equals(receipt.status)) {
                                        receipt.status =
                                                "suspended".equals(row.state)
                                                        ? "suspended"
                                                        : "retrying";
                                        receipt.errorCode = "authorization_or_destination";
                                        receipt.updatedAt = row.updatedAt;
                                        deliveries.save(receipt);
                                    }
                                });
            hooks.saveAndFlush(row);
            return false;
        }
    }

    private long head(AgentServiceSessionEntity root) {
        return managed(root) ? journal.publicHead(scope(root)) : root.nextEventSequence;
    }

    private List<PublicEventReference> eventReferences(
            AgentServiceSessionEntity root, long after, long through, int limit) {
        if (managed(root)) return journal.publicEventReferences(scope(root), after, through, limit);
        return events
                .findBySessionIdAndSeqGreaterThanAndSeqLessThanEqualOrderBySeqAsc(
                        root.id, after, through, PageRequest.of(0, limit))
                .stream()
                .map(
                        event ->
                                new PublicEventReference(
                                        event.seq, event.id, event.type, event.createdAt))
                .toList();
    }

    private static boolean managed(AgentServiceSessionEntity root) {
        return "managed".equals(root.runtimeKind);
    }

    private static ManagedScope scope(AgentServiceSessionEntity root) {
        return new ManagedScope(root.tenant, root.namespace, root.id);
    }

    private static String cursor(AgentServiceSessionEntity root, long sequence) {
        return managed(root)
                ? ManagedSessionTurnsApi.encode(scope(root), sequence)
                : SessionEventCursor.encode(root.id, sequence);
    }

    private static long idleRecheck(AgentServiceSessionEntity root, long now) {
        return managed(root) ? now + NATIVE_IDLE_RECHECK_MS : Long.MAX_VALUE;
    }

    private Map<String, Object> view(
            AgentServiceSessionEntity root, AgentServiceWebhookEntity row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", row.id);
        result.put("session_id", row.sessionId);
        result.put("url", row.url);
        result.put("event_types", readTypes(row));
        result.put("status", row.state);
        result.put("enabled", List.of("active", "idle").contains(row.state));
        result.put("cursor", cursor(root, row.cursorSeq));
        result.put("attempt", row.attemptCount);
        result.put("created_at", row.createdAt);
        result.put("updated_at", row.updatedAt);
        result.put("failure_code", row.failureCode);
        return result;
    }

    private List<String> readTypes(AgentServiceWebhookEntity row) {
        try {
            return mapper.readValue(row.eventTypes, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot decode webhook filter", e);
        }
    }

    private Map<String, Object> readActor(AgentServiceWebhookEntity row) {
        try {
            return mapper.readValue(crypto.decrypt(row.actor), new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot decode webhook actor", e);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode webhook", e);
        }
    }

    private void destination(String url) {
        try {
            destinations.validate(url);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
    }

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Webhook claim is stale");
    }

    private static void checkPage(String after, int limit) {
        if (limit < 1 || limit > 100 || after != null && !after.matches("[a-f0-9]{64}"))
            throw bad("Invalid page");
    }

    private static Map<String, Object> page(
            List<Map<String, Object>> data, boolean more, String after) {
        var result = new LinkedHashMap<String, Object>();
        result.put("data", data);
        result.put("has_more", more);
        result.put("next_after", more ? after : null);
        return result;
    }
}
