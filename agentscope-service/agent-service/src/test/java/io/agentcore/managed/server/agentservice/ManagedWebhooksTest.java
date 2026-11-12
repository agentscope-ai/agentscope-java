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
package io.agentcore.managed.server.agentservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.agentservice.api.AgentServiceWebhooksController;
import io.agentcore.managed.server.agentservice.api.SessionApiController;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceEventEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceEventRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceWebhookRepository;
import io.agentcore.managed.server.agentservice.session.AgentServiceWebhooks;
import io.agentcore.managed.server.agentservice.session.ManagedSessionTurnsApi;
import io.agentcore.managed.server.agentservice.session.SessionApi;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.protocol.RuntimeWebhookDelivery;
import io.agentcore.managed.server.web.auth.JwtService;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@TestPropertySource(locations = "classpath:application-test.properties")
@SpringBootTest(
        classes = AgentServiceApp.class,
        properties = {
            "managed-agents.local-dev=false",
            "managed-agents.internal-token=webhook-test-internal-token-at-least-32-characters",
            "managed-agents.webhooks.allowed-hosts=receiver.example",
            "managed-agents.agent-service.provisioning-poll-ms=3600000",
            "spring.datasource.url=jdbc:h2:mem:managed-webhooks;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        })
@ActiveProfiles("test")
@Timeout(30)
class ManagedWebhooksTest extends AgentServiceInfrastructureTestSupport {
    @Autowired AgentServiceWebhooks webhooks;
    @Autowired AgentServiceWebhookRepository hooks;
    @Autowired AgentServiceSessionRepository sessions;
    @Autowired AgentServiceEventRepository oldEvents;
    @Autowired ManagedAdmissionStore admission;
    @Autowired ManagedJournalStore journal;
    @Autowired ManagedSessionTurnsApi queries;
    @Autowired TransactionTemplate tx;
    @Autowired VaultCrypto crypto;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;

    @Test
    void nativeRouteUsesCommittedScopedEventsAndAFetchableCursorWithoutAnOldJournal()
            throws Exception {
        var scope = ready();
        append(scope, "before", "artifact.published");
        var client = client();
        var registration =
                client.post()
                        .uri("/v1/sessions/{id}/webhooks", scope.sessionId())
                        .headers(value -> value.addAll(headers()))
                        .header("Idempotency-Key", "native")
                        .bodyValue(
                                Map.of(
                                        "url",
                                        "https://receiver.example/events",
                                        "event_types",
                                        List.of("artifact.*")))
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        String id = (String) registration.get("id");
        assertEquals(1, hooks.findById(id).orElseThrow().cursorSeq);
        assertEquals(0, sessions.findById(scope.sessionId()).orElseThrow().nextEventSequence);
        // A stale backend event must never become a native notification.
        var old = new AgentServiceEventEntity();
        old.id = UUID.randomUUID().toString();
        old.sessionId = scope.sessionId();
        old.seq = 1;
        old.type = "artifact.published";
        old.data = "{}";
        old.createdAt = System.currentTimeMillis();
        oldEvents.saveAndFlush(old);
        append(scope, "ignored", "file.created");
        String source = "artifact_published_" + "a".repeat(64);
        append(scope, source, "artifact.published");
        due(id);
        var delivery = claim().deliveries().get(0);
        var payload = mapper.readValue(delivery.payload(), Map.class);
        assertEquals(source, payload.get("id"));
        assertFalse(delivery.payload().contains("fixture-body"));
        assertTrue(((String) payload.get("event_url")).startsWith("/v1/sessions/"));
        // The first public protocol disables Webhooks. Preserve native ledger delivery proofs
        // without treating the legacy cursor URL as a Claude history page.
        String after =
                UriComponentsBuilder.fromUriString((String) payload.get("event_url"))
                        .build()
                        .getQueryParams()
                        .getFirst("after");
        var page = queries.events(scope.sessionId(), after, 1, headers());
        var event = (Map<?, ?>) ((List<?>) page.get("data")).get(0);
        assertEquals(source, event.get("id"));
        assertEquals(payload.get("cursor"), event.get("cursor"));
        assertEquals(
                false,
                ((Map<?, ?>) queries.capabilities(scope.sessionId(), headers()).get("capabilities"))
                        .get("webhooks"));
        assertEquals(
                "delivered",
                webhooks.finish(new RuntimeWebhookDelivery.Receipt(delivery.proof(), 204, ""))
                        .get("status"));
        var receipts =
                (List<?>) webhooks.receipts(scope.sessionId(), id, headers(), null, 10).get("data");
        assertEquals(payload.get("cursor"), ((Map<?, ?>) receipts.get(0)).get("cursor"));
        assertTrue(claim().deliveries().isEmpty());
        var idle = hooks.findById(id).orElseThrow();
        assertEquals("idle", idle.state);
        assertTrue(idle.nextAttemptAt <= System.currentTimeMillis() + 5000);
        assertEquals(3, idle.cursorSeq);
        webhooks.update(scope.sessionId(), id, headers(), true);
    }

    @Test
    void idleRefillRetriesTheSameEventAndRejectsAnExpiredSendOrDeletion() {
        var scope = ready();
        var registered =
                webhooks.register(
                        scope.sessionId(),
                        headers(),
                        "retry",
                        new AgentServiceWebhooks.Registration(
                                "https://receiver.example/events",
                                List.of("artifact.published", "tool.operation.completed")));
        String id = (String) registered.get("id");
        append(scope, "first", "artifact.published");
        due(id); // No Agent Service notification: simulate the normal Tool Worker-only commit path.
        var first = claim().deliveries().get(0);
        assertEquals(true, webhooks.validate(first.proof()).get("allowed"));
        webhooks.finish(new RuntimeWebhookDelivery.Receipt(first.proof(), 503, ""));
        assertEquals(0, hooks.findById(id).orElseThrow().cursorSeq);
        due(id);
        var retry = claim().deliveries().get(0);
        assertEquals(first.deliveryId(), retry.deliveryId());
        assertEquals(first.payload(), retry.payload());
        assertNotEquals(first.claimFence(), retry.claimFence());
        assertEquals(
                HttpStatus.CONFLICT,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        webhooks.finish(
                                                new RuntimeWebhookDelivery.Receipt(
                                                        first.proof(), 204, "")))
                        .getStatusCode());
        var receipt = new RuntimeWebhookDelivery.Receipt(retry.proof(), 204, "");
        webhooks.finish(receipt);
        webhooks.finish(receipt);
        assertEquals(1, hooks.findById(id).orElseThrow().cursorSeq);
        assertTrue(claim().deliveries().isEmpty());
        append(scope, "second", "artifact.published");
        due(id);
        var next = claim().deliveries().get(0);
        webhooks.update(scope.sessionId(), id, headers(), true);
        assertThrows(ResponseStatusException.class, () -> webhooks.validate(next.proof()));
        assertThrows(
                ResponseStatusException.class,
                () -> webhooks.finish(new RuntimeWebhookDelivery.Receipt(next.proof(), 204, "")));
        assertEquals(1, hooks.findById(id).orElseThrow().cursorSeq);
    }

    @Test
    void twoSchedulersClaimOnlyOneNativeDelivery() throws Exception {
        var scope = ready();
        String id =
                (String)
                        webhooks.register(
                                        scope.sessionId(),
                                        headers(),
                                        "concurrent",
                                        new AgentServiceWebhooks.Registration(
                                                "https://receiver.example/events",
                                                List.of("artifact.*")))
                                .get("id");
        append(scope, "concurrent", "artifact.published");
        due(id);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        var deliveries = new ArrayList<RuntimeWebhookDelivery>();
        try {
            var a =
                    pool.submit(
                            () -> {
                                start.await();
                                return claim();
                            });
            var b =
                    pool.submit(
                            () -> {
                                start.await();
                                return claim();
                            });
            start.countDown();
            deliveries.addAll(a.get(10, TimeUnit.SECONDS).deliveries());
            deliveries.addAll(b.get(10, TimeUnit.SECONDS).deliveries());
            assertEquals(1, deliveries.size());
        } finally {
            pool.shutdownNow();
            webhooks.update(scope.sessionId(), id, headers(), true);
        }
    }

    private RuntimeWebhookDelivery.Batch claim() {
        return webhooks.claim(new RuntimeWebhookDelivery.ClaimRequest(1, "scheduler-fixture", 1));
    }

    private void due(String id) {
        tx.executeWithoutResult(
                ignored -> {
                    var row = hooks.lockById(id).orElseThrow();
                    row.nextAttemptAt = 0;
                    hooks.saveAndFlush(row);
                });
    }

    private void append(ManagedScope scope, String id, String type) {
        journal.resourceEvent(
                scope,
                id,
                type,
                Map.of(
                        type.startsWith("artifact.") ? "artifact_id" : "file_id",
                        id,
                        "name",
                        "fixture-body"));
        while (journal.projections().project(scope, 128) > 0) {
            /* Query-side test consumer. */
        }
    }

    private WebTestClient client() {
        return WebTestClient.bindToController(
                        new AgentServiceWebhooksController(webhooks),
                        new SessionApiController(mock(SessionApi.class), queries))
                .build();
    }

    private HttpHeaders headers() {
        var headers = new HttpHeaders();
        headers.setBearerAuth(jwt.generate("owner", "fixture", List.of("user")));
        return headers;
    }

    private ManagedScope ready() {
        var row = new AgentServiceSessionEntity();
        row.id = UUID.randomUUID().toString();
        row.tenant = row.namespace = "fixture";
        row.ownerId = "owner";
        row.runtimeKind = "managed";
        row.commandId = UUID.randomUUID().toString();
        row.revision = "v1";
        row.digest = "0".repeat(64);
        row.state = "created";
        row.lifecycle = "active";
        try {
            row.policy =
                    crypto.encrypt(
                            mapper.writeValueAsString(
                                    Map.of(
                                            "enabled",
                                            true,
                                            "valid_until",
                                            2000000000000L,
                                            "humans",
                                            Map.of("owner", List.of("read", "interact")))));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        row.publicView = "{}";
        row.createdAt = row.updatedAt = System.currentTimeMillis();
        sessions.saveAndFlush(row);
        var scope = new ManagedScope(row.tenant, row.namespace, row.id);
        admission.createSession(scope, "{}");
        return scope;
    }
}
