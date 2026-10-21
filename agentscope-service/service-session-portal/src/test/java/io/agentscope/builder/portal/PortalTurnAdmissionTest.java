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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.builder.portal.persistence.PortalEventRepository;
import io.agentscope.builder.portal.persistence.PortalQuotaRepository;
import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.portal.persistence.PortalSnapshotRepository;
import io.agentscope.builder.portal.persistence.PortalTurnRepository;
import io.agentscope.builder.portal.session.PortalEventNotifications;
import io.agentscope.builder.portal.session.PortalEventReads;
import io.agentscope.builder.portal.session.PortalExecutionAuthorization;
import io.agentscope.builder.portal.session.PortalInputAdmission;
import io.agentscope.builder.portal.session.PortalResourceProjection;
import io.agentscope.builder.portal.session.PortalResourceReads;
import io.agentscope.builder.portal.session.PortalRuntimeFacts;
import io.agentscope.builder.portal.session.PortalSessionAccess;
import io.agentscope.builder.portal.session.PortalSessionProvisioning;
import io.agentscope.builder.portal.session.PortalTurnAdmission;
import io.agentscope.builder.portal.session.PortalTurnControl;
import io.agentscope.builder.runtime.protocol.RuntimeExecutionRequest;
import io.agentscope.builder.runtime.protocol.RuntimeFact;
import io.agentscope.builder.runtime.protocol.RuntimeRequestContext;
import io.agentscope.builder.runtime.protocol.RuntimeSessionAdmission;
import io.agentscope.builder.runtime.protocol.RuntimeSessionBootstrap;
import io.agentscope.builder.runtime.protocol.TurnCommand;
import io.agentscope.builder.web.auth.JwtService;
import io.agentscope.builder.web.managed.AgentSessionInput;
import io.agentscope.builder.web.managed.SessionEventCursor;
import io.agentscope.builder.web.managed.service.VaultCrypto;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
            "builder.internal-token=admission-test-internal-token-at-least-32-characters",
            "builder.portal.provisioning-poll-ms=3600000",
            "builder.portal.turn-delivery-poll-ms=3600000",
            "builder.portal.interaction-delivery-poll-ms=3600000",
            "spring.datasource.url=jdbc:h2:mem:portal-admission;DB_CLOSE_DELAY=-1"
        })
@ActiveProfiles("test")
class PortalTurnAdmissionTest extends PortalInfrastructureTestSupport {
    @Autowired PortalTurnAdmission admission;
    @Autowired PortalSessionProvisioning provisioning;
    @Autowired PortalSessionAccess access;
    @Autowired PortalSessionRepository sessions;
    @Autowired PortalTurnRepository turns;
    @Autowired PortalQuotaRepository quotas;
    @Autowired PortalEventRepository events;
    @Autowired ObjectMapper mapper;
    @Autowired VaultCrypto crypto;
    @Autowired TransactionTemplate tx;
    @Autowired JwtService jwt;
    @Autowired PortalExecutionAuthorization execution;
    @Autowired PortalRuntimeFacts facts;
    @Autowired PortalEventNotifications notifications;
    @Autowired PortalEventReads reads;
    @Autowired PortalResourceProjection projection;
    @Autowired PortalResourceReads resourceReads;
    @Autowired PortalSnapshotRepository snapshots;
    @Autowired PortalInputAdmission inputs;
    @Autowired PortalTurnControl controls;

    @Test
    void replicasShareOneIdempotentReservationAndRejectASecondApplicationSlot() throws Exception {
        String firstSession = readySession();
        String secondSession = readySession();
        var headers = new HttpHeaders();
        headers.setBearerAuth(jwt.generate("user-1", "fixture", List.of("user")));
        headers.set("Idempotency-Key", "same-request");
        var anotherSessionPortal =
                new PortalTurnAdmission(
                        sessions,
                        turns,
                        quotas,
                        events,
                        access,
                        tx,
                        mapper,
                        crypto,
                        notifications,
                        projection,
                        1000,
                        128,
                        100);
        var workers = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var first =
                    workers.submit(
                            () -> {
                                ready.countDown();
                                assertTrue(start.await(5, TimeUnit.SECONDS));
                                return admission.accept(
                                        firstSession, headers, Map.of("message", "hello"));
                            });
            var second =
                    workers.submit(
                            () -> {
                                ready.countDown();
                                assertTrue(start.await(5, TimeUnit.SECONDS));
                                return anotherSessionPortal.accept(
                                        firstSession, headers, Map.of("message", "hello"));
                            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var accepted = first.get(10, TimeUnit.SECONDS);
            assertEquals(accepted, second.get(10, TimeUnit.SECONDS));
            assertEquals("queued", accepted.get("status"));
            assertEquals(1, turns.count());
            assertEquals(1, events.count());
            var row = turns.findById(String.valueOf(accepted.get("id"))).orElseThrow();
            var quota = quotas.findById(row.quotaId).orElseThrow();
            assertEquals(1, quota.activeCount);
            assertEquals(1, quota.queuedCount);
            assertEquals("active", row.reservationState);
            var command = mapper.readValue(crypto.decrypt(row.command), TurnCommand.class);
            assertEquals("user-1", command.context().actor().get("user"));
            assertEquals(1, command.admissionSequence());
            // Delivery and authorization do not fabricate a started event or release quota.
            var activation =
                    new RuntimeExecutionRequest(
                            command.context(),
                            command.inputDigest(),
                            command.admissionSequence(),
                            1,
                            "runtime/lease-1",
                            false);
            var grant = execution.authorize(activation);
            assertEquals("runtime/lease-1", grant.executionFence());
            assertTrue(grant.validUntil() > System.currentTimeMillis());
            assertTrue(grant.validUntil() <= System.currentTimeMillis() + 30000);
            assertEquals("queued", turns.findById(row.id).orElseThrow().status);
            assertEquals(1, events.count());
            assertEquals(1, quotas.findById(row.quotaId).orElseThrow().activeCount);
            assertEquals(1, quotas.findById(row.quotaId).orElseThrow().queuedCount);
            assertEquals(
                    HttpStatus.CONFLICT,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            execution.authorize(
                                                    new RuntimeExecutionRequest(
                                                            command.context(),
                                                            command.inputDigest(),
                                                            command.admissionSequence(),
                                                            1,
                                                            "runtime/stale-lease",
                                                            true)))
                            .getStatusCode());
            var original = command.context();
            var forged =
                    new RuntimeRequestContext(
                            original.version(),
                            original.tenant(),
                            original.namespace(),
                            original.applicationId(),
                            original.sessionId(),
                            original.turnId(),
                            original.commandId(),
                            original.traceId(),
                            Map.of("user", "user-1", "key_hash", "", "internal", true),
                            original.policyVersion(),
                            original.permissionExpiresAt());
            assertEquals(
                    HttpStatus.NOT_FOUND,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            execution.authorize(
                                                    new RuntimeExecutionRequest(
                                                            forged,
                                                            command.inputDigest(),
                                                            command.admissionSequence(),
                                                            1,
                                                            "runtime/lease-1",
                                                            true)))
                            .getStatusCode());
            // A new activation fences delayed renewals from its predecessor.
            execution.authorize(
                    new RuntimeExecutionRequest(
                            command.context(),
                            command.inputDigest(),
                            command.admissionSequence(),
                            2,
                            "runtime/lease-2",
                            false));
            assertEquals(
                    HttpStatus.CONFLICT,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            execution.authorize(
                                                    new RuntimeExecutionRequest(
                                                            command.context(),
                                                            command.inputDigest(),
                                                            command.admissionSequence(),
                                                            1,
                                                            "runtime/lease-1",
                                                            true)))
                            .getStatusCode());
            tx.executeWithoutResult(
                    ignored -> {
                        var q = quotas.lockById(row.quotaId).orElseThrow();
                        q.tokensUsed = q.tokenBudget;
                        quotas.saveAndFlush(q);
                    });
            assertEquals(
                    HttpStatus.TOO_MANY_REQUESTS,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            execution.authorize(
                                                    new RuntimeExecutionRequest(
                                                            command.context(),
                                                            command.inputDigest(),
                                                            command.admissionSequence(),
                                                            2,
                                                            "runtime/lease-2",
                                                            true)))
                            .getStatusCode());
            tx.executeWithoutResult(
                    ignored -> {
                        var q = quotas.lockById(row.quotaId).orElseThrow();
                        q.tokensUsed = 0;
                        quotas.saveAndFlush(q);
                    });
            assertEquals(
                    HttpStatus.CONFLICT,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            admission.accept(
                                                    firstSession,
                                                    headers,
                                                    Map.of("message", "changed")))
                            .getStatusCode());
            assertEquals(
                    HttpStatus.TOO_MANY_REQUESTS,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            anotherSessionPortal.accept(
                                                    secondSession,
                                                    headers,
                                                    Map.of("message", "hello")))
                            .getStatusCode());
            assertEquals(1, turns.count());
            assertEquals(1, events.count());
            assertEquals(0, sessions.findById(secondSession).orElseThrow().nextTurnSequence);
            assertEquals(1, quotas.findById(row.quotaId).orElseThrow().activeCount);
            var started =
                    new RuntimeFact(
                            command.context(),
                            "managed",
                            "started",
                            1,
                            "2",
                            "runtime/lease-2",
                            "turn.running",
                            Map.of("status", "running"));
            assertEquals(2, facts.accept(started).publicSequence());
            assertEquals("running", turns.findById(row.id).orElseThrow().status);
            assertEquals(0, quotas.findById(row.quotaId).orElseThrow().queuedCount);
            var usage =
                    new RuntimeFact(
                            command.context(),
                            "managed",
                            "usage",
                            2,
                            "2",
                            "runtime/lease-2",
                            "usage.recorded",
                            Map.of(
                                    "usage",
                                    Map.of("inputTokens", 7, "outputTokens", 3),
                                    "session_usage",
                                    Map.of("model_calls", 9999)));
            var receipt = facts.accept(usage);
            assertEquals(receipt, facts.accept(usage));
            var usagePage =
                    reads.page(
                            command.context().sessionId(),
                            SessionEventCursor.encode(
                                    command.context().sessionId(), receipt.publicSequence() - 1),
                            1,
                            headers);
            var usageEvent = (Map<?, ?>) ((List<?>) usagePage.get("data")).get(0);
            var usageData = (Map<?, ?>) usageEvent.get("data");
            var sessionUsage = (Map<?, ?>) usageData.get("session_usage");
            assertEquals(1, ((Number) sessionUsage.get("model_calls")).intValue());
            assertEquals(
                    7,
                    ((Number) ((Map<?, ?>) sessionUsage.get("totals")).get("inputTokens"))
                            .intValue());
            assertEquals(
                    sessionUsage.get("totals"),
                    ((Map<?, ?>) usageData.get("turn_usage")).get("totals"));
            assertEquals(10, quotas.findById(row.quotaId).orElseThrow().tokensUsed);
            assertEquals(
                    HttpStatus.CONFLICT,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            facts.accept(
                                                    new RuntimeFact(
                                                            command.context(),
                                                            "managed",
                                                            "usage",
                                                            2,
                                                            "2",
                                                            "runtime/lease-2",
                                                            "usage.recorded",
                                                            Map.of(
                                                                    "usage",
                                                                    Map.of(
                                                                            "inputTokens",
                                                                            70,
                                                                            "outputTokens",
                                                                            3)))))
                            .getStatusCode());
            assertEquals(
                    HttpStatus.CONFLICT,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            facts.accept(
                                                    new RuntimeFact(
                                                            command.context(),
                                                            "managed",
                                                            "gap",
                                                            4,
                                                            "2",
                                                            "runtime/lease-2",
                                                            "turn.completed",
                                                            Map.of())))
                            .getStatusCode());
            var completed =
                    new RuntimeFact(
                            command.context(),
                            "managed",
                            "completed",
                            3,
                            "2",
                            "runtime/lease-2",
                            "turn.completed",
                            Map.of("status", "completed"));
            assertEquals(facts.accept(completed), facts.accept(completed));
            assertEquals("completed", turns.findById(row.id).orElseThrow().status);
            assertEquals("released", turns.findById(row.id).orElseThrow().reservationState);
            assertEquals(0, quotas.findById(row.quotaId).orElseThrow().activeCount);
            assertEquals(0, quotas.findById(row.quotaId).orElseThrow().queuedCount);
            // Committed history from an older authorized run still drains, without
            // rewinding the current Turn or releasing its reservation a second time.
            facts.accept(
                    new RuntimeFact(
                            command.context(),
                            "managed",
                            "old-run",
                            4,
                            "1",
                            "runtime/lease-1",
                            "turn.failed",
                            Map.of("status", "failed")));
            assertEquals("completed", turns.findById(row.id).orElseThrow().status);
            assertEquals(0, quotas.findById(row.quotaId).orElseThrow().activeCount);
            assertEquals(10, quotas.findById(row.quotaId).orElseThrow().tokensUsed);
            // The whole batch rolls back when a later fact has an invalid generation.
            var extraUsage =
                    new RuntimeFact(
                            command.context(),
                            "managed",
                            "usage-extra",
                            5,
                            "2",
                            "runtime/lease-2",
                            "usage.recorded",
                            Map.of("usage", Map.of("inputTokens", 1, "outputTokens", 1)));
            var invalidFence =
                    new RuntimeFact(
                            command.context(),
                            "managed",
                            "bad-fence",
                            6,
                            "2",
                            "runtime/another-lease",
                            "item.completed",
                            Map.of());
            assertThrows(
                    ResponseStatusException.class,
                    () -> facts.acceptBatch(List.of(extraUsage, invalidFence)));
            assertEquals(10, quotas.findById(row.quotaId).orElseThrow().tokensUsed);
            assertEquals(5, events.highWatermark(firstSession));
            var extraItem =
                    new RuntimeFact(
                            command.context(),
                            "managed",
                            "item-extra",
                            6,
                            "2",
                            "runtime/lease-2",
                            "item.completed",
                            Map.of("item", Map.of("id", "item-1")));
            var prefix = List.of(extraUsage, extraItem);
            assertEquals(facts.acceptBatch(prefix), facts.acceptBatch(prefix));
            assertEquals(12, quotas.findById(row.quotaId).orElseThrow().tokensUsed);
            var firstPage = reads.page(firstSession, null, 2, headers);
            assertEquals(2, ((List<?>) firstPage.get("data")).size());
            assertEquals(true, firstPage.get("has_more"));
            assertEquals(
                    2,
                    SessionEventCursor.decode(firstSession, (String) firstPage.get("next_cursor")));
            var remaining =
                    reads.stream(firstSession, (String) firstPage.get("next_cursor"), null, headers)
                            .filter(event -> event.data() != null)
                            .take(5)
                            .collectList()
                            .block(Duration.ofSeconds(5));
            assertEquals(5, remaining.size());
            assertEquals(3, SessionEventCursor.decode(firstSession, remaining.get(0).id()));
            assertEquals(7, SessionEventCursor.decode(firstSession, remaining.get(4).id()));
            assertEquals(
                    HttpStatus.BAD_REQUEST,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            reads.page(
                                                    secondSession,
                                                    (String) firstPage.get("next_cursor"),
                                                    10,
                                                    headers))
                            .getStatusCode());
            assertEquals(
                    HttpStatus.CONFLICT,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            reads.page(
                                                    firstSession,
                                                    SessionEventCursor.encode(firstSession, 100),
                                                    10,
                                                    headers))
                            .getStatusCode());
            // A live snapshot keeps only resource versions changed after its fixed cursor.
            facts.accept(
                    new RuntimeFact(
                            command.context(),
                            "managed",
                            "item-a",
                            7,
                            "2",
                            "runtime/lease-2",
                            "item.started",
                            Map.of(
                                    "item_id",
                                    "a",
                                    "run_id",
                                    "run-1",
                                    "item",
                                    Map.of(
                                            "id",
                                            "a",
                                            "status",
                                            "in_progress",
                                            "content",
                                            List.of(Map.of("type", "text", "text", "A"))))));
            facts.accept(
                    new RuntimeFact(
                            command.context(),
                            "managed",
                            "item-b",
                            8,
                            "2",
                            "runtime/lease-2",
                            "item.started",
                            Map.of(
                                    "item_id",
                                    "b",
                                    "run_id",
                                    "run-1",
                                    "item",
                                    Map.of(
                                            "id",
                                            "b",
                                            "status",
                                            "in_progress",
                                            "content",
                                            List.of(Map.of("type", "text", "text", "B"))))));
            var snapshot = resourceReads.snapshot(firstSession, 1, headers);
            String snapshotId = (String) snapshot.get("snapshot_id");
            assertEquals(
                    9, SessionEventCursor.decode(firstSession, (String) snapshot.get("as_of")));
            assertEquals(
                    2,
                    ((Number) ((Map<?, ?>) snapshot.get("usage")).get("model_calls")).intValue());
            var itemPage = (Map<?, ?>) ((Map<?, ?>) snapshot.get("resources")).get("items");
            assertEquals(true, itemPage.get("has_more"));
            facts.accept(
                    new RuntimeFact(
                            command.context(),
                            "managed",
                            "delta-b",
                            9,
                            "2",
                            "runtime/lease-2",
                            "item.delta",
                            Map.of(
                                    "item_id",
                                    "b",
                                    "run_id",
                                    "run-1",
                                    "content",
                                    List.of(Map.of("type", "text", "text", "+")))));
            facts.accept(
                    new RuntimeFact(
                            command.context(),
                            "managed",
                            "run-ended",
                            10,
                            "2",
                            "runtime/lease-2",
                            "run.ended",
                            Map.of("run_id", "run-1", "status", "completed")));
            var fixedPage =
                    resourceReads.page(
                            firstSession,
                            "items",
                            snapshotId,
                            (String) itemPage.get("next_cursor"),
                            1,
                            headers);
            var fixed =
                    (Map<?, ?>)
                            ((Map<?, ?>)
                                            ((Map<?, ?>) ((List<?>) fixedPage.get("data")).get(0))
                                                    .get("data"))
                                    .get("item");
            assertEquals("b", fixed.get("id"));
            assertEquals("in_progress", fixed.get("status"));
            assertEquals("B", ((Map<?, ?>) ((List<?>) fixed.get("content")).get(0)).get("text"));
            var currentSnapshot = resourceReads.snapshot(firstSession, 100, headers);
            var currentPage =
                    (Map<?, ?>) ((Map<?, ?>) currentSnapshot.get("resources")).get("items");
            var current =
                    (Map<?, ?>)
                            ((Map<?, ?>)
                                            ((Map<?, ?>) ((List<?>) currentPage.get("data")).get(1))
                                                    .get("data"))
                                    .get("item");
            assertEquals("completed", current.get("status"));
            assertEquals("B+", ((Map<?, ?>) ((List<?>) current.get("content")).get(0)).get("text"));
            assertEquals(
                    HttpStatus.BAD_REQUEST,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            resourceReads.page(
                                                    secondSession,
                                                    "items",
                                                    null,
                                                    (String) itemPage.get("next_cursor"),
                                                    1,
                                                    headers))
                            .getStatusCode());
            assertEquals(
                    HttpStatus.BAD_REQUEST,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            resourceReads.page(
                                                    firstSession,
                                                    "tools",
                                                    null,
                                                    (String) itemPage.get("next_cursor"),
                                                    1,
                                                    headers))
                            .getStatusCode());
            long beforeRead = snapshots.count();
            for (int i = 0; i < 20; i++) {
                var live = resourceReads.get(firstSession, "items", "b", null, headers);
                assertEquals(false, live.containsKey("snapshot_id"));
                assertEquals(
                        currentSnapshot.get("usage"),
                        resourceReads.usage(firstSession, null, headers).get("data"));
                assertEquals(
                        mapper.readValue(
                                turns.findById(row.id).orElseThrow().usageSummary, Map.class),
                        resourceReads.usage(firstSession, row.id, headers).get("data"));
            }
            assertEquals(beforeRead, snapshots.count());
            facts.accept(
                    new RuntimeFact(
                            command.context(),
                            "managed",
                            "artifact-published",
                            11,
                            "2",
                            "runtime/lease-2",
                            "artifact.published",
                            Map.of("artifact_id", "artifact-1")));
            var artifactSnapshot = resourceReads.snapshot(firstSession, 10, headers);
            String artifactSnapshotId = (String) artifactSnapshot.get("snapshot_id");
            facts.accept(
                    new RuntimeFact(
                            command.context(),
                            "managed",
                            "artifact-deleted",
                            12,
                            "2",
                            "runtime/lease-2",
                            "artifact.deleted",
                            Map.of("artifact_id", "artifact-1")));
            assertEquals(
                    1,
                    ((List<?>)
                                    resourceReads
                                            .page(
                                                    firstSession,
                                                    "artifacts",
                                                    artifactSnapshotId,
                                                    null,
                                                    10,
                                                    headers)
                                            .get("data"))
                            .size());
            assertEquals(
                    HttpStatus.NOT_FOUND,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            resourceReads.get(
                                                    firstSession,
                                                    "artifacts",
                                                    "artifact-1",
                                                    null,
                                                    headers))
                            .getStatusCode());
            tx.executeWithoutResult(
                    ignored -> {
                        var expired = snapshots.findById(artifactSnapshotId).orElseThrow();
                        expired.expiresAt = 0;
                        snapshots.saveAndFlush(expired);
                    });
            assertEquals(
                    HttpStatus.GONE,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            resourceReads.page(
                                                    firstSession,
                                                    "artifacts",
                                                    artifactSnapshotId,
                                                    null,
                                                    10,
                                                    headers))
                            .getStatusCode());
            var injected =
                    inputs.stage(
                            firstSession,
                            null,
                            "inject",
                            new AgentSessionInput("persistent context", null),
                            headers);
            assertEquals("queued", inputs.get(firstSession, injected.id, headers).get("status"));
            var acceptedInput =
                    new RuntimeFact(
                            inputs.command(injected).context(),
                            "managed",
                            "input-accepted:" + injected.id,
                            13,
                            null,
                            null,
                            "input.accepted",
                            Map.of(
                                    "input_id",
                                    injected.id,
                                    "kind",
                                    "inject",
                                    "status",
                                    "accepted"));
            var acceptedReceipt = facts.accept(acceptedInput);
            assertEquals(acceptedReceipt, facts.accept(acceptedInput));
            assertEquals(
                    "accepted", inputs.get(firstSession, injected.id, headers).get("input_status"));
            assertEquals("delivered", inputs.row(injected.id).deliveryStatus);
            assertEquals(0, quotas.findById(row.quotaId).orElseThrow().activeCount);
            facts.accept(
                    new RuntimeFact(
                            command.context(),
                            "managed",
                            "input-applied",
                            14,
                            "2",
                            "runtime/lease-2",
                            "input.applied",
                            Map.of("input_ids", List.of(injected.id))));
            assertEquals(
                    "applied", inputs.get(firstSession, injected.id, headers).get("input_status"));
            facts.accept(
                    new RuntimeFact(
                            inputs.command(injected).context(),
                            "managed",
                            "late-input-accepted",
                            15,
                            null,
                            null,
                            "input.accepted",
                            Map.of(
                                    "input_id",
                                    injected.id,
                                    "kind",
                                    "inject",
                                    "status",
                                    "accepted")));
            assertEquals(
                    "applied", inputs.get(firstSession, injected.id, headers).get("input_status"));
            var queuedHeaders = new HttpHeaders();
            queuedHeaders.putAll(headers);
            queuedHeaders.set("Idempotency-Key", "queued-cancel-turn");
            var queuedTurn =
                    admission.accept(
                            secondSession,
                            queuedHeaders,
                            Map.of("message", "cancel before execution"));
            String previousPolicy = sessions.findById(secondSession).orElseThrow().policy;
            tx.executeWithoutResult(
                    ignored -> {
                        var session = sessions.lockById(secondSession).orElseThrow();
                        ObjectNode policy;
                        try {
                            policy = (ObjectNode) mapper.readTree(crypto.decrypt(session.policy));
                        } catch (Exception error) {
                            throw new IllegalStateException(error);
                        }
                        policy.set(
                                "humans",
                                mapper.valueToTree(Map.of("user-1", List.of("read", "cancel"))));
                        session.policy = crypto.encrypt(policy.toString());
                        sessions.saveAndFlush(session);
                    });
            assertEquals(
                    HttpStatus.NOT_FOUND,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            admission.accept(
                                                    secondSession,
                                                    queuedHeaders,
                                                    Map.of("message", "invoke was revoked")))
                            .getStatusCode());
            var cancellation =
                    controls.cancel(secondSession, (String) queuedTurn.get("id"), queuedHeaders);
            assertEquals(cancellation.id, inputs.forDelivery(cancellation).context().commandId());
            assertEquals(
                    cancellation.id,
                    controls.cancel(secondSession, cancellation.turnId, queuedHeaders).id);
            assertEquals(
                    "cancel_requested",
                    admission.get(secondSession, cancellation.turnId, headers).get("status"));
            assertEquals(1, quotas.findById(row.quotaId).orElseThrow().activeCount);
            var queuedCommand =
                    mapper.readValue(
                            crypto.decrypt(
                                    turns.findById(cancellation.turnId).orElseThrow().command),
                            TurnCommand.class);
            var cancelledBeforeExecution =
                    new RuntimeFact(
                            queuedCommand.context(),
                            "managed",
                            "cancelled-before-start",
                            1,
                            null,
                            null,
                            "turn.cancelled",
                            Map.of("cancel_command_id", cancellation.id, "status", "cancelled"));
            facts.accept(cancelledBeforeExecution);
            assertEquals(
                    "cancelled",
                    admission.get(secondSession, cancellation.turnId, headers).get("status"));
            assertEquals(0, quotas.findById(row.quotaId).orElseThrow().activeCount);
            assertEquals(0, quotas.findById(row.quotaId).orElseThrow().queuedCount);
            assertEquals(
                    "applied",
                    controls.get(secondSession, cancellation.id, headers).get("command_status"));
            tx.executeWithoutResult(
                    ignored -> {
                        var session = sessions.lockById(secondSession).orElseThrow();
                        session.policy = previousPolicy;
                        sessions.saveAndFlush(session);
                    });
            queuedHeaders.set("Idempotency-Key", "completion-cancel-race");
            var completing =
                    admission.accept(
                            firstSession, queuedHeaders, Map.of("message", "completion wins"));
            String completingId = (String) completing.get("id");
            var completingCommand =
                    mapper.readValue(
                            crypto.decrypt(turns.findById(completingId).orElseThrow().command),
                            TurnCommand.class);
            execution.authorize(
                    new RuntimeExecutionRequest(
                            completingCommand.context(),
                            completingCommand.inputDigest(),
                            completingCommand.admissionSequence(),
                            1,
                            "completion-race-fence",
                            false));
            facts.accept(
                    new RuntimeFact(
                            completingCommand.context(),
                            "managed",
                            "completion-race-start",
                            16,
                            "1",
                            "completion-race-fence",
                            "turn.running",
                            Map.of("status", "running")));
            var completionCancellation = controls.cancel(firstSession, completingId, queuedHeaders);
            // Native completion can have committed before the runtime sees the cancellation.
            facts.accept(
                    new RuntimeFact(
                            completingCommand.context(),
                            "managed",
                            "completion-race-end",
                            17,
                            "1",
                            "completion-race-fence",
                            "turn.completed",
                            Map.of()));
            assertEquals(
                    "completed", admission.get(firstSession, completingId, headers).get("status"));
            var completedResource =
                    (Map<?, ?>)
                            resourceReads
                                    .get(firstSession, "turns", completingId, null, headers)
                                    .get("data");
            assertEquals("turn.completed", completedResource.get("type"));
            assertEquals("completed", ((Map<?, ?>) completedResource.get("data")).get("status"));
            assertEquals(
                    "applied",
                    controls.get(firstSession, completionCancellation.id, headers)
                            .get("command_status"));
            assertEquals(0, quotas.findById(row.quotaId).orElseThrow().activeCount);
            queuedHeaders.set("Idempotency-Key", "explicit-resume-target");
            var resumable =
                    admission.accept(firstSession, queuedHeaders, Map.of("message", "recover"));
            String resumeTurnId = (String) resumable.get("id");
            var resumeOriginal =
                    mapper.readValue(
                            crypto.decrypt(turns.findById(resumeTurnId).orElseThrow().command),
                            TurnCommand.class);
            facts.accept(
                    new RuntimeFact(
                            resumeOriginal.context(),
                            "managed",
                            "resume-prestart-failure",
                            18,
                            null,
                            null,
                            "turn.failed",
                            Map.of(
                                    "status",
                                    "failed",
                                    "native_started",
                                    false,
                                    "activation_command_id",
                                    resumeTurnId)));
            assertEquals(0, quotas.findById(row.quotaId).orElseThrow().activeCount);
            var resumeCommand = controls.resume(firstSession, resumeTurnId, queuedHeaders);
            assertEquals(
                    resumeCommand.id,
                    controls.resume(firstSession, resumeTurnId, queuedHeaders).id);
            assertEquals(
                    "queued", admission.get(firstSession, resumeTurnId, headers).get("status"));
            assertEquals(1, quotas.findById(row.quotaId).orElseThrow().activeCount);
            assertEquals(1, quotas.findById(row.quotaId).orElseThrow().queuedCount);
            facts.accept(
                    new RuntimeFact(
                            resumeOriginal.context(),
                            "managed",
                            "resume-old-failure",
                            19,
                            null,
                            null,
                            "turn.failed",
                            Map.of(
                                    "status",
                                    "failed",
                                    "native_started",
                                    false,
                                    "activation_command_id",
                                    resumeTurnId)));
            assertEquals(
                    "queued", admission.get(firstSession, resumeTurnId, headers).get("status"));
            assertEquals(1, quotas.findById(row.quotaId).orElseThrow().activeCount);
            execution.authorize(
                    new RuntimeExecutionRequest(
                            resumeOriginal.context(),
                            resumeOriginal.inputDigest(),
                            resumeOriginal.admissionSequence(),
                            1,
                            "resume-fence",
                            false,
                            resumeCommand.id));
            facts.accept(
                    new RuntimeFact(
                            resumeOriginal.context(),
                            "managed",
                            "resume-started",
                            20,
                            "1",
                            "resume-fence",
                            "turn.running",
                            Map.of(
                                    "status",
                                    "running",
                                    "activation_command_id",
                                    resumeCommand.id)));
            assertEquals(
                    "applied",
                    controls.get(firstSession, resumeCommand.id, headers).get("command_status"));
            assertEquals(0, quotas.findById(row.quotaId).orElseThrow().queuedCount);
            facts.accept(
                    new RuntimeFact(
                            resumeOriginal.context(),
                            "managed",
                            "resume-ended",
                            21,
                            "1",
                            "resume-fence",
                            "turn.completed",
                            Map.of(
                                    "status",
                                    "completed",
                                    "activation_command_id",
                                    resumeCommand.id)));
            assertEquals(0, quotas.findById(row.quotaId).orElseThrow().activeCount);
            queuedHeaders.set("Idempotency-Key", "resume-terminal-rejection");
            assertEquals(
                    HttpStatus.CONFLICT,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            controls.resume(
                                                    firstSession, resumeTurnId, queuedHeaders))
                            .getStatusCode());
            queuedHeaders.set("Idempotency-Key", "action-target");
            var actionTurn =
                    admission.accept(
                            firstSession, queuedHeaders, Map.of("message", "answer actions"));
            String actionTurnId = (String) actionTurn.get("id");
            var actionOriginal =
                    mapper.readValue(
                            crypto.decrypt(turns.findById(actionTurnId).orElseThrow().command),
                            TurnCommand.class);
            execution.authorize(
                    new RuntimeExecutionRequest(
                            actionOriginal.context(),
                            actionOriginal.inputDigest(),
                            actionOriginal.admissionSequence(),
                            1,
                            "action-first-fence",
                            false));
            facts.accept(
                    new RuntimeFact(
                            actionOriginal.context(),
                            "managed",
                            "actions-started",
                            22,
                            "1",
                            "action-first-fence",
                            "turn.running",
                            Map.of("status", "running")));
            var confirmationCall =
                    Map.of(
                            "id",
                            "confirmation-request",
                            "name",
                            "tool",
                            "type",
                            "tool_use",
                            "input",
                            Map.of("value", "original"));
            facts.accept(
                    new RuntimeFact(
                            actionOriginal.context(),
                            "managed",
                            "confirmation-requested",
                            23,
                            "1",
                            "action-first-fence",
                            "required_action.created",
                            Map.of(
                                    "request_id",
                                    "confirmation-request",
                                    "kind",
                                    "confirmation",
                                    "tool_call",
                                    confirmationCall,
                                    "status",
                                    "pending")));
            facts.accept(
                    new RuntimeFact(
                            actionOriginal.context(),
                            "managed",
                            "actions-paused",
                            24,
                            "1",
                            "action-first-fence",
                            "turn.requires_action",
                            Map.of("status", "requires_action")));
            var approvalAnswers =
                    List.<Map<String, Object>>of(
                            Map.of("request_id", "confirmation-request", "allow", true));
            assertEquals(
                    HttpStatus.NOT_FOUND,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            controls.actions(
                                                    firstSession,
                                                    actionTurnId,
                                                    queuedHeaders,
                                                    approvalAnswers))
                            .getStatusCode());
            String actionKey = "action-test-api-key";
            String actionKeyHash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(actionKey.getBytes(StandardCharsets.UTF_8)));
            tx.executeWithoutResult(
                    ignored -> {
                        var session = sessions.lockById(firstSession).orElseThrow();
                        var policy = (ObjectNode) mapper.valueToTree(Map.of());
                        try {
                            policy = (ObjectNode) mapper.readTree(crypto.decrypt(session.policy));
                        } catch (Exception error) {
                            throw new IllegalStateException(error);
                        }
                        policy.set(
                                "humans",
                                mapper.valueToTree(
                                        Map.of(
                                                "user-1",
                                                List.of(
                                                        "invoke",
                                                        "read",
                                                        "cancel",
                                                        "approve",
                                                        "interact"))));
                        policy.set(
                                "credentials",
                                mapper.valueToTree(
                                        List.of(
                                                Map.of(
                                                        "hash",
                                                        actionKeyHash,
                                                        "scopes",
                                                        List.of("read", "interact", "invoke")))));
                        session.policy = crypto.encrypt(policy.toString());
                        sessions.saveAndFlush(session);
                    });
            var keyHeaders = new HttpHeaders();
            keyHeaders.set("X-API-Key", actionKey);
            keyHeaders.set("Idempotency-Key", "key-cannot-approve");
            assertEquals(
                    HttpStatus.FORBIDDEN,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            controls.actions(
                                                    firstSession,
                                                    actionTurnId,
                                                    keyHeaders,
                                                    approvalAnswers))
                            .getStatusCode());
            var approval =
                    controls.actions(firstSession, actionTurnId, queuedHeaders, approvalAnswers);
            assertEquals("approval", approval.kind);
            assertEquals(
                    approval.id,
                    controls.actions(firstSession, actionTurnId, queuedHeaders, approvalAnswers)
                            .id);
            assertEquals(
                    HttpStatus.CONFLICT,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            controls.actions(
                                                    firstSession,
                                                    actionTurnId,
                                                    queuedHeaders,
                                                    List.of(
                                                            Map.of(
                                                                    "request_id",
                                                                    "confirmation-request",
                                                                    "allow",
                                                                    false))))
                            .getStatusCode());
            assertEquals(1, quotas.findById(row.quotaId).orElseThrow().activeCount);
            assertEquals(1, quotas.findById(row.quotaId).orElseThrow().queuedCount);
            execution.authorize(
                    new RuntimeExecutionRequest(
                            actionOriginal.context(),
                            actionOriginal.inputDigest(),
                            actionOriginal.admissionSequence(),
                            2,
                            "approval-fence",
                            false,
                            approval.id));
            facts.accept(
                    new RuntimeFact(
                            actionOriginal.context(),
                            "managed",
                            "approval-started",
                            25,
                            "2",
                            "approval-fence",
                            "turn.running",
                            Map.of("status", "running", "activation_command_id", approval.id)));
            assertEquals(
                    "runtime_accepted",
                    controls.get(firstSession, approval.id, headers).get("command_status"));
            facts.accept(
                    new RuntimeFact(
                            actionOriginal.context(),
                            "managed",
                            "confirmation-resolved",
                            26,
                            "2",
                            "approval-fence",
                            "required_action.resolved",
                            Map.of(
                                    "request_id",
                                    "confirmation-request",
                                    "kind",
                                    "confirmation",
                                    "status",
                                    "resolved",
                                    "activation_command_id",
                                    approval.id)));
            assertEquals(
                    "applied",
                    controls.get(firstSession, approval.id, headers).get("command_status"));
            var externalCall =
                    Map.of(
                            "id",
                            "external-request",
                            "name",
                            "external",
                            "type",
                            "tool_use",
                            "input",
                            Map.of());
            facts.accept(
                    new RuntimeFact(
                            actionOriginal.context(),
                            "managed",
                            "external-requested",
                            27,
                            "2",
                            "approval-fence",
                            "required_action.created",
                            Map.of(
                                    "request_id",
                                    "external-request",
                                    "kind",
                                    "external_execution",
                                    "tool_call",
                                    externalCall,
                                    "status",
                                    "pending",
                                    "activation_command_id",
                                    approval.id)));
            facts.accept(
                    new RuntimeFact(
                            actionOriginal.context(),
                            "managed",
                            "external-second-requested",
                            28,
                            "2",
                            "approval-fence",
                            "required_action.created",
                            Map.of(
                                    "request_id",
                                    "external-second",
                                    "kind",
                                    "external_execution",
                                    "tool_call",
                                    Map.of(
                                            "id",
                                            "external-second",
                                            "name",
                                            "external",
                                            "type",
                                            "tool_use",
                                            "input",
                                            Map.of()),
                                    "status",
                                    "pending",
                                    "activation_command_id",
                                    approval.id)));
            facts.accept(
                    new RuntimeFact(
                            actionOriginal.context(),
                            "managed",
                            "external-paused",
                            29,
                            "2",
                            "approval-fence",
                            "turn.requires_action",
                            Map.of(
                                    "status",
                                    "requires_action",
                                    "activation_command_id",
                                    approval.id)));
            keyHeaders.set("Idempotency-Key", "external-result");
            var resultAnswers =
                    List.<Map<String, Object>>of(
                            Map.of(
                                    "request_id",
                                    "external-request",
                                    "output",
                                    "result",
                                    "is_error",
                                    false),
                            Map.of(
                                    "request_id",
                                    "external-second",
                                    "output",
                                    "second",
                                    "is_error",
                                    true));
            var resultCommand =
                    controls.actions(firstSession, actionTurnId, keyHeaders, resultAnswers);
            assertEquals("tool_result", resultCommand.kind);
            assertEquals(
                    resultCommand.id,
                    controls.actions(firstSession, actionTurnId, keyHeaders, resultAnswers).id);
            execution.authorize(
                    new RuntimeExecutionRequest(
                            actionOriginal.context(),
                            actionOriginal.inputDigest(),
                            actionOriginal.admissionSequence(),
                            3,
                            "result-fence",
                            false,
                            resultCommand.id));
            facts.accept(
                    new RuntimeFact(
                            actionOriginal.context(),
                            "managed",
                            "result-started",
                            30,
                            "3",
                            "result-fence",
                            "turn.running",
                            Map.of(
                                    "status",
                                    "running",
                                    "activation_command_id",
                                    resultCommand.id)));
            facts.accept(
                    new RuntimeFact(
                            actionOriginal.context(),
                            "managed",
                            "external-resolved",
                            31,
                            "3",
                            "result-fence",
                            "required_action.resolved",
                            Map.of(
                                    "request_id",
                                    "external-request",
                                    "kind",
                                    "external_execution",
                                    "status",
                                    "resolved",
                                    "activation_command_id",
                                    resultCommand.id)));
            assertEquals(
                    "runtime_accepted",
                    controls.get(firstSession, resultCommand.id, headers).get("command_status"));
            facts.accept(
                    new RuntimeFact(
                            actionOriginal.context(),
                            "managed",
                            "external-second-resolved",
                            32,
                            "3",
                            "result-fence",
                            "required_action.resolved",
                            Map.of(
                                    "request_id",
                                    "external-second",
                                    "kind",
                                    "external_execution",
                                    "status",
                                    "resolved",
                                    "activation_command_id",
                                    resultCommand.id)));
            assertEquals(
                    "applied",
                    controls.get(firstSession, resultCommand.id, headers).get("command_status"));
            facts.accept(
                    new RuntimeFact(
                            actionOriginal.context(),
                            "managed",
                            "actions-completed",
                            33,
                            "3",
                            "result-fence",
                            "turn.completed",
                            Map.of(
                                    "status",
                                    "completed",
                                    "activation_command_id",
                                    resultCommand.id)));
            assertEquals(
                    "applied",
                    controls.get(firstSession, resultCommand.id, headers).get("command_status"));
            assertEquals(0, quotas.findById(row.quotaId).orElseThrow().activeCount);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private String readySession() throws Exception {
        ObjectNode wire =
                (ObjectNode)
                        mapper.readTree(
                                Files.readString(
                                        Path.of(
                                                "..",
                                                "service-common",
                                                "src",
                                                "test",
                                                "resources",
                                                "runtime-protocol",
                                                "v1",
                                                "bootstrap.json")));
        String id = UUID.randomUUID().toString();
        ((ObjectNode) wire.get("context")).put("sessionId", id);
        ((ObjectNode) wire.get("context")).put("commandId", UUID.randomUUID().toString());
        ((ObjectNode) wire.get("configuration").get("session")).put("id", id);
        provisioning.stage(
                new RuntimeSessionAdmission(
                        mapper.treeToValue(wire, RuntimeSessionBootstrap.class),
                        Map.of(
                                "tenant",
                                "tenant-1",
                                "namespace",
                                "default",
                                "enabled",
                                true,
                                "valid_until",
                                2000000000000L,
                                "humans",
                                Map.of("user-1", List.of("invoke", "read", "cancel")),
                                "application",
                                Map.of(
                                        "id",
                                        "app-1",
                                        "maxConcurrent",
                                        1,
                                        "tokensUsed",
                                        0,
                                        "tokenBudget",
                                        1000,
                                        "version",
                                        1)),
                        Map.of("id", id)));
        tx.executeWithoutResult(
                ignored -> {
                    var row = sessions.lockById(id).orElseThrow();
                    row.state = "ready";
                    row.bootstrapPayload = null;
                    sessions.saveAndFlush(row);
                });
        return id;
    }
}
