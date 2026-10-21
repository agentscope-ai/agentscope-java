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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.builder.portal.persistence.PortalEventEntity;
import io.agentscope.builder.portal.persistence.PortalEventRepository;
import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.portal.persistence.PortalTurnRepository;
import io.agentscope.builder.portal.session.PortalEventReads;
import io.agentscope.builder.portal.session.PortalLifecycles;
import io.agentscope.builder.portal.session.PortalRegistryReads;
import io.agentscope.builder.portal.session.PortalResourceProjection;
import io.agentscope.builder.portal.session.PortalResourceReads;
import io.agentscope.builder.portal.session.PortalSessionPermissions;
import io.agentscope.builder.portal.session.PortalSessionProvisioning;
import io.agentscope.builder.portal.session.PortalTurnAdmission;
import io.agentscope.builder.runtime.protocol.RuntimeSessionAdmission;
import io.agentscope.builder.runtime.protocol.RuntimeSessionBootstrap;
import io.agentscope.builder.web.auth.JwtService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SpringBootTest(
        classes = SessionPortalApp.class,
        properties = {
            "builder.local-dev=false",
            "builder.internal-token=registry-test-internal-token-at-least-32-characters",
            "builder.portal.provisioning-poll-ms=3600000",
            "builder.portal.turn-delivery-poll-ms=3600000",
            "builder.portal.interaction-delivery-poll-ms=3600000",
            "builder.portal.lifecycle-poll-ms=3600000",
            "builder.portal.event-export-slots=1",
            "spring.datasource.url=jdbc:h2:mem:portal-registry;DB_CLOSE_DELAY=-1"
        })
@ActiveProfiles("test")
class PortalRegistryReadsTest extends PortalInfrastructureTestSupport {
    @Autowired PortalRegistryReads registry;
    @Autowired PortalLifecycles lifecycles;
    @Autowired PortalTurnRepository turnRows;
    @Autowired PortalSessionProvisioning provisioning;
    @Autowired PortalSessionPermissions permissions;
    @Autowired PortalSessionRepository sessions;
    @Autowired PortalTurnAdmission admission;
    @Autowired PortalEventReads events;
    @Autowired PortalEventRepository journal;
    @Autowired PortalResourceProjection projection;
    @Autowired PortalResourceReads resources;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt;

    @Test
    void indexedPagesIncludeSharedReadGrantsAndBindCursorsToPrincipalAndFilters() throws Exception {
        String user = "registry-reader";
        String own = ready("registry-visible-a", "owner-a", user, "registry-key");
        String shared = ready("registry-visible-b", "owner-b", user, null);
        ready("registry-hidden", "someone-else", "someone-else", null);
        String archived = ready("registry-archived", "owner-a", user, null);
        tx.executeWithoutResult(
                ignored -> {
                    var row = sessions.lockById(archived).orElseThrow();
                    row.lifecycle = "archived";
                    sessions.saveAndFlush(row);
                });
        var headers = human(user);
        var first = registry.sessions(null, 1, "active", null, headers);
        assertEquals(List.of(shared), ids(first));
        assertEquals(true, first.get("has_more"));
        String cursor = (String) first.get("next_cursor");
        assertEquals(List.of(own), ids(registry.sessions(cursor, 1, "active", null, headers)));
        assertEquals(false, registry.sessions(cursor, 1, "active", null, headers).get("has_more"));
        assertEquals(3, ids(registry.sessions(null, 10, "all", null, headers)).size());
        assertEquals(
                List.of(archived), ids(registry.sessions(null, 10, "archived", null, headers)));
        assertEquals(
                HttpStatus.BAD_REQUEST,
                assertThrows(
                                ResponseStatusException.class,
                                () -> registry.sessions(cursor, 1, "all", null, headers))
                        .getStatusCode());
        assertEquals(
                HttpStatus.BAD_REQUEST,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        registry.sessions(
                                                cursor, 1, "active", null, human("someone-else")))
                        .getStatusCode());
        headers.set("X-AgentScope-Namespace", "another");
        assertTrue(ids(registry.sessions(null, 10, "active", null, headers)).isEmpty());
        headers.remove("X-AgentScope-Namespace");
        var key = new HttpHeaders();
        key.set("X-API-Key", "registry-key");
        assertEquals(List.of(own), ids(registry.sessions(null, 10, "active", null, key)));
        key.set("X-API-Key", "unknown-key");
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                assertThrows(
                                ResponseStatusException.class,
                                () -> registry.sessions(null, 10, "active", null, key))
                        .getStatusCode());
        tx.executeWithoutResult(
                ignored -> {
                    var row = sessions.lockById(shared).orElseThrow();
                    row.policyVersion++;
                    permissions.replace(row, Map.of("enabled", false));
                    sessions.saveAndFlush(row);
                });
        assertEquals(List.of(own), ids(registry.sessions(null, 10, "active", null, headers)));
        assertEquals(
                HttpStatus.BAD_REQUEST,
                assertThrows(
                                ResponseStatusException.class,
                                () -> registry.sessions(null, 201, "active", null, headers))
                        .getStatusCode());
    }

    @Test
    void turnViewsUseSessionCursorsButFilterEventsResourcesAndUsageInStorage() throws Exception {
        String session = ready("registry-turns", "owner", "turn-reader", null);
        var headers = human("turn-reader");
        headers.set("Idempotency-Key", "first");
        String first =
                (String) admission.accept(session, headers, Map.of("message", "first")).get("id");
        headers.set("Idempotency-Key", "second");
        String second =
                (String) admission.accept(session, headers, Map.of("message", "second")).get("id");
        var turns = registry.turns(session, null, 1, headers);
        assertEquals(List.of(first), ids(turns));
        assertEquals(
                List.of(second),
                ids(registry.turns(session, (String) turns.get("next_cursor"), 1, headers)));
        var newest = registry.turns(session, null, 1, "desc", null, headers);
        assertEquals(List.of(second), ids(newest));
        assertEquals(
                List.of(first),
                ids(
                        registry.turns(
                                session,
                                (String) newest.get("next_cursor"),
                                1,
                                "desc",
                                null,
                                headers)));
        assertEquals(
                HttpStatus.BAD_REQUEST,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        registry.turns(
                                                session,
                                                (String) newest.get("next_cursor"),
                                                1,
                                                headers))
                        .getStatusCode());
        assertEquals(
                List.of(second, first),
                ids(registry.turns(session, null, 10, "desc", "queued", headers)));
        assertTrue(ids(registry.turns(session, null, 1, "desc", "running", headers)).isEmpty());
        publish(
                session,
                first,
                "item.completed",
                Map.of("item", Map.of("id", "item-a", "content", "before")));
        publish(
                session,
                second,
                "item.completed",
                Map.of("item", Map.of("id", "item-c", "content", "other")));
        publish(
                session,
                first,
                "item.completed",
                Map.of("item", Map.of("id", "item-b", "content", "second")));
        publish(
                session,
                first,
                "model.completed",
                Map.of(
                        "model_call_id",
                        "model-a",
                        "usage",
                        Map.of("inputTokens", 2, "outputTokens", 3)));
        publish(
                session,
                second,
                "model.completed",
                Map.of(
                        "model_call_id",
                        "model-b",
                        "usage",
                        Map.of("inputTokens", 20, "outputTokens", 30)));
        var snapshot = resources.snapshot(session, first, 1, headers);
        assertEquals("turn_only", ((Map<?, ?>) snapshot.get("usage")).get("scope"));
        assertEquals(
                1, ((Number) ((Map<?, ?>) snapshot.get("usage")).get("model_calls")).intValue());
        assertEquals(
                2,
                ((Number)
                                ((Map<?, ?>) ((Map<?, ?>) snapshot.get("usage")).get("totals"))
                                        .get("inputTokens"))
                        .intValue());
        assertEquals(
                2,
                ((Number)
                                ((Map<?, ?>)
                                                provisioning
                                                        .view(
                                                                sessions.findById(session)
                                                                        .orElseThrow())
                                                        .get("usage"))
                                        .get("model_calls"))
                        .intValue());
        String snapshotId = (String) snapshot.get("snapshot_id");
        var items = (Map<?, ?>) ((Map<?, ?>) snapshot.get("resources")).get("items");
        assertEquals(List.of("item-a"), itemIds(items));
        String itemCursor = (String) items.get("next_cursor");
        publish(
                session,
                first,
                "item.completed",
                Map.of("item", Map.of("id", "item-a", "content", "after")));
        assertEquals(
                List.of("item-b"),
                itemIds(
                        resources.page(
                                session, first, "items", snapshotId, itemCursor, 1, headers)));
        assertEquals(
                "before",
                ((Map<?, ?>)
                                ((Map<?, ?>)
                                                ((Map<?, ?>)
                                                                resources
                                                                        .get(
                                                                                session,
                                                                                first,
                                                                                "items",
                                                                                "item-a",
                                                                                snapshotId,
                                                                                headers)
                                                                        .get("data"))
                                                        .get("data"))
                                        .get("item"))
                        .get("content"));
        assertEquals(
                HttpStatus.BAD_REQUEST,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        resources.page(
                                                session,
                                                second,
                                                "items",
                                                null,
                                                itemCursor,
                                                1,
                                                headers))
                        .getStatusCode());
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(
                                ResponseStatusException.class,
                                () -> resources.snapshot(session, "missing-turn", 1, headers))
                        .getStatusCode());
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        resources.get(
                                                session, first, "items", "item-c", null, headers))
                        .getStatusCode());
        var page = events.page(session, first, null, 2, headers);
        assertTrue(
                ((List<?>) page.get("data"))
                        .stream()
                                .allMatch(
                                        value ->
                                                first.equals(
                                                        ((Map<?, ?>)
                                                                        ((Map<?, ?>) value)
                                                                                .get("data"))
                                                                .get("turn_id"))));
        String after = (String) page.get("next_cursor");
        var rest = events.page(session, first, after, 100, headers);
        assertFalse((Boolean) rest.get("has_more"));
        assertTrue(
                ((List<?>)
                                events.page(
                                                session,
                                                first,
                                                (String) rest.get("next_cursor"),
                                                1,
                                                headers)
                                        .get("data"))
                        .isEmpty());
        var other =
                (Map<?, ?>)
                        ((List<?>) events.page(session, second, null, 1, headers).get("data"))
                                .get(0);
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(
                                ResponseStatusException.class,
                                () -> events.get(session, first, (String) other.get("id"), headers))
                        .getStatusCode());
        StepVerifier.create(events.stream(session, first, null, null, headers).take(2))
                .assertNext(frame -> assertTrue(frame.data().contains(first)))
                .assertNext(frame -> assertTrue(frame.data().contains(first)))
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void publicExportKeepsItsWatermarkAcrossWindowsAndFiltersTurns() throws Exception {
        String session = ready("registry-export", "owner", "export-reader", null);
        var headers = human("export-reader");
        headers.set("Idempotency-Key", "export-turn");
        String turn =
                (String) admission.accept(session, headers, Map.of("message", "export")).get("id");
        for (int index = 0; index < 270; index++)
            publish(session, turn, "step.updated", Map.of("step_id", "export-" + index));
        var expected = (List<?>) events.page(session, null, 1000, headers).get("data");
        var first = new MockServerHttpResponse();
        events.export(session, null, null, null, 2, headers, first).block(Duration.ofSeconds(10));
        String through = first.getHeaders().getFirst("X-Export-Through");
        assertEquals("true", first.getHeaders().getFirst("X-Has-More"));
        assertEquals("2", first.getHeaders().getFirst("X-Export-Count"));
        assertEquals(2, first.getBodyAsString().block().lines().count());
        publish(session, turn, "step.updated", Map.of("step_id", "after-export-watermark"));
        var rest = new MockServerHttpResponse();
        events.export(
                        session,
                        null,
                        first.getHeaders().getFirst("X-Next-Cursor"),
                        through,
                        1000,
                        headers,
                        rest)
                .block(Duration.ofSeconds(10));
        assertEquals("false", rest.getHeaders().getFirst("X-Has-More"));
        assertEquals(through, rest.getHeaders().getFirst("X-Next-Cursor"));
        var actual =
                (first.getBodyAsString().block() + rest.getBodyAsString().block())
                        .lines()
                        .map(
                                line -> {
                                    try {
                                        return mapper.readValue(line, Map.class);
                                    } catch (Exception error) {
                                        throw new AssertionError(error);
                                    }
                                })
                        .toList();
        assertEquals(expected, actual);
        var filtered = new MockServerHttpResponse();
        events.export(session, turn, null, through, 1000, headers, filtered)
                .block(Duration.ofSeconds(10));
        var turnExpected = (List<?>) events.page(session, turn, null, 1000, headers).get("data");
        assertEquals(turnExpected.size() - 1, filtered.getBodyAsString().block().lines().count());
        assertFalse(filtered.getBodyAsString().block().contains("after-export-watermark"));
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        events.export(
                                                        session,
                                                        null,
                                                        null,
                                                        through,
                                                        1,
                                                        human("unrelated-reader"),
                                                        new MockServerHttpResponse())
                                                .block(Duration.ofSeconds(5)))
                        .getStatusCode());
        assertEquals(
                HttpStatus.CONFLICT,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        events.export(
                                                        session,
                                                        null,
                                                        through,
                                                        first.getHeaders()
                                                                .getFirst("X-Next-Cursor"),
                                                        1,
                                                        headers,
                                                        new MockServerHttpResponse())
                                                .block(Duration.ofSeconds(5)))
                        .getStatusCode());
    }

    @Test
    void publicExportRechecksEachBatchAndReleasesCapacityOnCancellation() throws Exception {
        String session = ready("registry-export-revoke", "owner", "export-revoke-reader", null);
        var headers = human("export-revoke-reader");
        headers.set("Idempotency-Key", "export-revoke-turn");
        String turn =
                (String) admission.accept(session, headers, Map.of("message", "export")).get("id");
        for (int index = 0; index < 32; index++)
            publish(session, turn, "step.updated", Map.of("step_id", "export-revoke-" + index));
        var held = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var slow = new MockServerHttpResponse();
        slow.setWriteHandler(body -> Mono.<Void>never().doOnSubscribe(ignored -> held.countDown()));
        var connection =
                events.export(session, null, null, null, 1000, headers, slow)
                        .doFinally(ignored -> cancelled.countDown())
                        .subscribe();
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS));
            assertEquals(
                    HttpStatus.TOO_MANY_REQUESTS,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            events.export(
                                                            session,
                                                            null,
                                                            null,
                                                            null,
                                                            1,
                                                            headers,
                                                            new MockServerHttpResponse())
                                                    .block(Duration.ofSeconds(5)))
                            .getStatusCode());
        } finally {
            connection.dispose();
        }
        assertTrue(cancelled.await(5, TimeUnit.SECONDS));
        var sent = new AtomicInteger();
        var revoked = new MockServerHttpResponse();
        revoked.setWriteHandler(
                body ->
                        body.doOnNext(
                                        buffer -> {
                                            DataBufferUtils.release(buffer);
                                            if (sent.incrementAndGet() == 1)
                                                tx.executeWithoutResult(
                                                        ignored -> {
                                                            var row =
                                                                    sessions.lockById(session)
                                                                            .orElseThrow();
                                                            row.lifecycle = "deleted";
                                                            sessions.saveAndFlush(row);
                                                        });
                                        })
                                .then());
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        events.export(
                                                        session, null, null, null, 1000, headers,
                                                        revoked)
                                                .block(Duration.ofSeconds(5)))
                        .getStatusCode());
        assertEquals(16, sent.get());
    }

    @Test
    void nestedStepsKeepTheirIdentityAndFixedSnapshotAcrossUpdates() throws Exception {
        String session = ready("registry-steps", "owner", "step-reader", null);
        var headers = human("step-reader");
        headers.set("Idempotency-Key", "first");
        String first =
                (String) admission.accept(session, headers, Map.of("message", "first")).get("id");
        headers.set("Idempotency-Key", "second");
        String second =
                (String) admission.accept(session, headers, Map.of("message", "second")).get("id");
        publish(
                session,
                first,
                "step.updated",
                Map.of(
                        "step_id",
                        "gate-a",
                        "graph_id",
                        "root",
                        "name",
                        "gate",
                        "status",
                        "running"));
        publish(
                session,
                first,
                "step.updated",
                Map.of(
                        "step_id",
                        "gate-b",
                        "graph_id",
                        "child",
                        "parent_step_id",
                        "child",
                        "name",
                        "gate",
                        "status",
                        "waiting"));
        publish(
                session,
                second,
                "step.updated",
                Map.of(
                        "step_id",
                        "gate-c",
                        "graph_id",
                        "other",
                        "name",
                        "gate",
                        "status",
                        "running"));
        var snapshot = resources.snapshot(session, first, 1, headers);
        String snapshotId = (String) snapshot.get("snapshot_id");
        var steps = (Map<?, ?>) ((Map<?, ?>) snapshot.get("resources")).get("steps");
        assertEquals("gate-a", stepData(((List<?>) steps.get("data")).get(0)).get("step_id"));
        publish(
                session,
                first,
                "step.updated",
                Map.of(
                        "step_id",
                        "gate-b",
                        "graph_id",
                        "child",
                        "parent_step_id",
                        "child",
                        "name",
                        "gate",
                        "status",
                        "succeeded"));
        var next =
                resources.page(
                        session,
                        first,
                        "steps",
                        snapshotId,
                        (String) steps.get("next_cursor"),
                        1,
                        headers);
        var old = stepData(((List<?>) next.get("data")).get(0));
        assertEquals("gate-b", old.get("step_id"));
        assertEquals("waiting", old.get("status"));
        assertFalse((Boolean) next.get("has_more"));
        var current = resources.get(session, first, "steps", "gate-b", null, headers);
        assertEquals("succeeded", stepData(current.get("data")).get("status"));
        assertEquals("child", stepData(current.get("data")).get("parent_step_id"));
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        resources.get(
                                                session, first, "steps", "gate-c", null, headers))
                        .getStatusCode());
    }

    private Map<?, ?> stepData(Object value) {
        return (Map<?, ?>) ((Map<?, ?>) value).get("data");
    }

    private void publish(String session, String turn, String type, Map<String, Object> data) {
        tx.executeWithoutResult(
                ignored -> {
                    var row = sessions.lockById(session).orElseThrow();
                    var event = new PortalEventEntity();
                    event.id = UUID.randomUUID().toString();
                    event.sessionId = session;
                    event.turnId = turn;
                    event.seq = ++row.nextEventSequence;
                    event.type = type;
                    var payload = new LinkedHashMap<String, Object>(data);
                    payload.put("turn_id", turn);
                    try {
                        event.data = mapper.writeValueAsString(payload);
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                    event.createdAt = System.currentTimeMillis();
                    journal.saveAndFlush(event);
                    projection.apply(row, event);
                    sessions.saveAndFlush(row);
                });
    }

    @Test
    void lifecycleBlocksUnsettledTurnsAndArchivedAdmissionAndRetainsDeletedState()
            throws Exception {
        String session = ready(UUID.randomUUID().toString(), "life-owner", "life-owner", null);
        var headers = human("life-owner");
        headers.set("Idempotency-Key", "life-turn");
        String turn =
                (String) admission.accept(session, headers, Map.of("message", "work")).get("id");
        headers.set("Idempotency-Key", "archive-life");
        assertEquals(
                HttpStatus.CONFLICT,
                assertThrows(
                                ResponseStatusException.class,
                                () -> lifecycles.stage(session, "archived", null, headers))
                        .getStatusCode());
        assertEquals("active", sessions.findById(session).orElseThrow().lifecycle);
        tx.executeWithoutResult(
                ignored -> {
                    var row = turnRows.lockById(turn).orElseThrow();
                    row.status = "completed";
                    row.reservationState = "released";
                    turnRows.saveAndFlush(row);
                });
        var archive = lifecycles.stage(session, "archived", 1L, headers);
        assertEquals(archive, lifecycles.stage(session, "archived", 1L, headers));
        assertEquals(2L, archive.get("lifecycle_revision"));
        assertEquals(
                "archived",
                provisioning.view(sessions.findById(session).orElseThrow()).get("status"));
        headers.set("Idempotency-Key", "new-archived-turn");
        assertEquals(
                HttpStatus.CONFLICT,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        admission.accept(
                                                session, headers, Map.of("message", "blocked")))
                        .getStatusCode());
        headers.set("Idempotency-Key", "restore-life");
        assertEquals(
                HttpStatus.CONFLICT,
                assertThrows(
                                ResponseStatusException.class,
                                () -> lifecycles.stage(session, "active", 1L, headers))
                        .getStatusCode());
        lifecycles.stage(session, "active", 2L, headers);
        assertEquals(
                "active",
                provisioning.view(sessions.findById(session).orElseThrow()).get("status"));
        headers.set("Idempotency-Key", "delete-life");
        var deletion = lifecycles.stage(session, "deleted", 3L, headers);
        assertEquals(deletion, lifecycles.stage(session, "deleted", 3L, headers));
        assertEquals(
                deletion, lifecycles.get(session, (String) deletion.get("command_id"), headers));
        headers.set("Idempotency-Key", "new-deleted-command");
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(
                                ResponseStatusException.class,
                                () -> lifecycles.stage(session, "active", null, headers))
                        .getStatusCode());
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        lifecycles.get(
                                                session,
                                                (String) deletion.get("command_id"),
                                                human("stranger")))
                        .getStatusCode());
        assertEquals("deleted", sessions.findById(session).orElseThrow().lifecycle);
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(
                                ResponseStatusException.class,
                                () -> registry.turns(session, null, 10, headers))
                        .getStatusCode());
    }

    private String ready(String id, String owner, String reader, String key) throws Exception {
        var wire =
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
        ((ObjectNode) wire.get("context")).put("sessionId", id).put("commandId", "bootstrap:" + id);
        ((ObjectNode) wire.get("configuration").get("session")).put("id", id).put("ownerId", owner);
        var policy =
                new LinkedHashMap<String, Object>(
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
                                Map.of(reader, List.of("read", "invoke", "interact")),
                                "application",
                                Map.of(
                                        "id",
                                        "app-1",
                                        "version",
                                        1,
                                        "maxConcurrent",
                                        20,
                                        "tokensUsed",
                                        0,
                                        "tokenBudget",
                                        1000)));
        if (key != null)
            policy.put(
                    "credentials",
                    List.of(
                            Map.of(
                                    "hash",
                                    HexFormat.of()
                                            .formatHex(
                                                    MessageDigest.getInstance("SHA-256")
                                                            .digest(
                                                                    key.getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8))),
                                    "scopes",
                                    List.of("read"))));
        provisioning.stage(
                new RuntimeSessionAdmission(
                        mapper.treeToValue(wire, RuntimeSessionBootstrap.class),
                        policy,
                        Map.of("id", id)));
        tx.executeWithoutResult(
                ignored -> {
                    var row = sessions.lockById(id).orElseThrow();
                    row.state = "ready";
                    row.bootstrapPayload = null;
                    row.createdAt = 1000;
                    sessions.saveAndFlush(row);
                    permissions.replace(row, policy);
                });
        return id;
    }

    private HttpHeaders human(String user) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(jwt.generate(user, "fixture", List.of("user")));
        return headers;
    }

    private static List<?> ids(Map<?, ?> page) {
        return ((List<?>) page.get("data"))
                .stream().map(value -> ((Map<?, ?>) value).get("id")).toList();
    }

    private static List<?> itemIds(Map<?, ?> page) {
        return ((List<?>) page.get("data"))
                .stream()
                        .map(
                                value ->
                                        ((Map<?, ?>)
                                                        ((Map<?, ?>)
                                                                        ((Map<?, ?>) value)
                                                                                .get("data"))
                                                                .get("item"))
                                                .get("id"))
                        .toList();
    }
}
