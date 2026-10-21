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
package io.agentscope.builder.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.portal.persistence.PortalEventEntity;
import io.agentscope.builder.portal.persistence.PortalEventRepository;
import io.agentscope.builder.portal.persistence.PortalSessionEntity;
import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.portal.persistence.PortalWebhookRepository;
import io.agentscope.builder.portal.session.PortalEventNotifications;
import io.agentscope.builder.portal.session.PortalWebhooks;
import io.agentscope.builder.runtime.protocol.RuntimeWebhookDelivery;
import io.agentscope.builder.web.auth.JwtService;
import io.agentscope.builder.web.managed.SessionEventCursor;
import io.agentscope.builder.web.managed.service.VaultCrypto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
        classes = SessionPortalApp.class,
        properties = {
            "builder.local-dev=false",
                    "builder.internal-token=webhook-test-internal-token-at-least-32-characters",
            "builder.webhooks.allowed-hosts=receiver.example",
                    "builder.portal.provisioning-poll-ms=3600000",
            "spring.datasource.url=jdbc:h2:mem:portal-webhooks;DB_CLOSE_DELAY=-1"
        })
@ActiveProfiles("test")
class PortalWebhooksTest extends PortalInfrastructureTestSupport {
    @Autowired PortalWebhooks webhooks;
    @Autowired PortalWebhookRepository hooks;
    @Autowired PortalSessionRepository sessions;
    @Autowired PortalEventRepository events;
    @Autowired PortalEventNotifications notifications;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper mapper;
    @Autowired VaultCrypto crypto;
    @Autowired JwtService jwt;

    @Test
    void orderedClaimsSurviveLostAcknowledgementAndRecheckOriginalPermission() throws Exception {
        String session = ready();
        var headers = headers();
        var input = new PortalWebhooks.Registration("https://receiver.example/events", null);
        var registered = webhooks.register(session, headers, "key", input);
        String id = (String) registered.get("id");
        assertEquals(registered, webhooks.register(session, headers, "key", input));
        assertNotEquals(registered.get("signing_secret"), hooks.findById(id).orElseThrow().secret);
        assertFalse(webhooks.list(session, headers, null, 1).toString().contains("signing_secret"));
        assertEquals(
                HttpStatus.CONFLICT,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        webhooks.register(
                                                session,
                                                headers,
                                                "key",
                                                new PortalWebhooks.Registration(
                                                        "https://receiver.example/changed", null)))
                        .getStatusCode());
        assertEquals(
                HttpStatus.BAD_REQUEST,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        webhooks.register(
                                                session,
                                                headers,
                                                "http",
                                                new PortalWebhooks.Registration(
                                                        "http://receiver.example/", null)))
                        .getStatusCode());
        assertTrue(claim().deliveries().isEmpty());
        append(session, "item.updated");
        append(session, "turn.completed");
        var first = claim().deliveries().get(0);
        assertTrue(claim().deliveries().isEmpty());
        assertFalse(first.payload().contains("private-api-key"));
        assertFalse(first.toString().contains(first.signingSecret()));
        assertTrue(first.payload().contains("event_url"));
        assertEquals(true, webhooks.validate(first.proof()).get("allowed"));
        tx.executeWithoutResult(
                ignored -> {
                    var row = hooks.lockById(id).orElseThrow();
                    row.claimUntil = 0;
                    row.nextAttemptAt = 0;
                    hooks.saveAndFlush(row);
                });
        var recovered = claim().deliveries().get(0);
        assertEquals(first.deliveryId(), recovered.deliveryId());
        assertNotEquals(first.claimFence(), recovered.claimFence());
        assertEquals(
                HttpStatus.CONFLICT,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        webhooks.finish(
                                                new RuntimeWebhookDelivery.Receipt(
                                                        first.proof(), 200, "")))
                        .getStatusCode());
        var ack = new RuntimeWebhookDelivery.Receipt(recovered.proof(), 204, "");
        assertEquals("delivered", webhooks.finish(ack).get("status"));
        assertEquals("delivered", webhooks.finish(ack).get("status"));
        assertEquals(
                SessionEventCursor.encode(session, 2),
                ((Map<?, ?>)
                                ((List<?>) webhooks.list(session, headers, null, 1).get("data"))
                                        .get(0))
                        .get("cursor"));
        assertTrue(claim().deliveries().isEmpty());
        assertEquals("idle", hooks.findById(id).orElseThrow().state);
        append(session, "turn.failed");
        var next = claim().deliveries().get(0);
        tx.executeWithoutResult(
                ignored -> {
                    var row = sessions.lockById(session).orElseThrow();
                    row.policy = crypto.encrypt(jsonPolicy(List.of("interact")));
                    sessions.saveAndFlush(row);
                });
        assertEquals(
                HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> webhooks.validate(next.proof()))
                        .getStatusCode());
        assertEquals("suspended", hooks.findById(id).orElseThrow().state);
        assertTrue(claim().deliveries().isEmpty());
    }

    @Test
    void retryExhaustionAndDeletionCannotAdvanceUnacknowledgedCursor() throws Exception {
        String session = ready();
        var headers = headers();
        String id =
                (String)
                        webhooks.register(
                                        session,
                                        headers,
                                        "retry",
                                        new PortalWebhooks.Registration(
                                                "https://receiver.example/", List.of("turn.*")))
                                .get("id");
        append(session, "turn.failed");
        var job = claim().deliveries().get(0);
        webhooks.finish(new RuntimeWebhookDelivery.Receipt(job.proof(), 503, ""));
        assertEquals(0, hooks.findById(id).orElseThrow().cursorSeq);
        tx.executeWithoutResult(
                ignored -> {
                    var row = hooks.lockById(id).orElseThrow();
                    row.attemptCount = 8;
                    row.nextAttemptAt = 0;
                    hooks.saveAndFlush(row);
                });
        assertTrue(claim().deliveries().isEmpty());
        assertEquals("failed", hooks.findById(id).orElseThrow().state);
        assertEquals("active", webhooks.update(session, id, headers, false).get("status"));
        var retried = claim().deliveries().get(0);
        assertEquals(job.deliveryId(), retried.deliveryId());
        webhooks.update(session, id, headers, true);
        assertEquals("", hooks.findById(id).orElseThrow().secret);
        assertEquals(
                HttpStatus.CONFLICT,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        webhooks.finish(
                                                new RuntimeWebhookDelivery.Receipt(
                                                        retried.proof(), 200, "")))
                        .getStatusCode());
        assertTrue(claim().deliveries().isEmpty());
    }

    private RuntimeWebhookDelivery.Batch claim() {
        return webhooks.claim(new RuntimeWebhookDelivery.ClaimRequest(1, "scheduler-fixture", 1));
    }

    private HttpHeaders headers() {
        var headers = new HttpHeaders();
        headers.setBearerAuth(jwt.generate("owner", "fixture", List.of("user")));
        return headers;
    }

    private String jsonPolicy(List<String> scopes) {
        try {
            return mapper.writeValueAsString(
                    Map.of(
                            "enabled",
                            true,
                            "valid_until",
                            2000000000000L,
                            "humans",
                            Map.of("owner", scopes)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String ready() {
        var row = new PortalSessionEntity();
        row.id = UUID.randomUUID().toString();
        row.tenant = "fixture";
        row.namespace = "fixture";
        row.ownerId = "owner";
        row.runtimeKind = "hosted";
        row.commandId = UUID.randomUUID().toString();
        row.revision = "v1";
        row.digest = "0".repeat(64);
        row.state = "ready";
        row.lifecycle = "active";
        row.policy = crypto.encrypt(jsonPolicy(List.of("read", "interact")));
        row.publicView = "{}";
        row.createdAt = row.updatedAt = System.currentTimeMillis();
        sessions.saveAndFlush(row);
        return row.id;
    }

    private void append(String session, String type) {
        tx.executeWithoutResult(
                ignored -> {
                    var root = sessions.lockById(session).orElseThrow();
                    var event = new PortalEventEntity();
                    event.id = UUID.randomUUID().toString();
                    event.sessionId = session;
                    event.seq = ++root.nextEventSequence;
                    event.type = type;
                    event.createdAt = System.currentTimeMillis();
                    event.data = "{\"private\":\"private-api-key\"}";
                    events.save(event);
                    sessions.saveAndFlush(root);
                    notifications.committed(session);
                });
    }
}
