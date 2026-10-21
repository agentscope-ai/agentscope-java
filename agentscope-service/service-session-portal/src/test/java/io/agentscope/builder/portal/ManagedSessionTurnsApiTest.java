/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.control.SessionResolveResult;
import io.agentscope.builder.portal.api.SessionApiController;
import io.agentscope.builder.portal.persistence.PortalSessionEntity;
import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.portal.session.ManagedSessionEventStreams;
import io.agentscope.builder.portal.session.ManagedSessionTurnsApi;
import io.agentscope.builder.portal.session.PortalSessionAccess;
import io.agentscope.builder.portal.session.PortalTurnAdmission;
import io.agentscope.builder.portal.session.SessionApi;
import io.agentscope.builder.runtime.ManagedCatalog;
import io.agentscope.builder.runtime.managed.ManagedAdmissionStore;
import io.agentscope.builder.runtime.managed.ManagedCheckpointObjects;
import io.agentscope.builder.runtime.managed.ManagedInputDelivery;
import io.agentscope.builder.runtime.managed.ManagedInputInbox.Type;
import io.agentscope.builder.runtime.managed.ManagedJournalStore;
import io.agentscope.builder.runtime.managed.ManagedRuntimeBatch;
import io.agentscope.builder.runtime.managed.ManagedRuntimeBatch.InputReceipt;
import io.agentscope.builder.runtime.managed.ManagedScope;
import io.agentscope.builder.runtime.managed.ManagedStateException;
import io.agentscope.builder.runtime.managed.ManagedTurnPublisher;
import io.agentscope.builder.web.managed.service.SessionEventNotifier;
import io.agentscope.core.session.SessionEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

@Timeout(15)
class ManagedSessionTurnsApiTest {
    @TempDir private Path managedCheckpointRoot;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpHeaders headers = new HttpHeaders();
    private final ManagedScope scope = new ManagedScope("t", "n", "session");
    private ManagedAdmissionStore admission;
    private ManagedJournalStore journal;
    private ManagedSessionTurnsApi api;
    private PortalSessionRepository resources;
    private PortalSessionAccess access;
    private SessionEventNotifier notifications;
    private ManagedSessionEventStreams eventStreams;

    @BeforeEach
    void setup() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:queries-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        admission = new ManagedAdmissionStore(source, mapper);
        journal =
                new ManagedJournalStore(
                        source,
                        mapper,
                        new ManagedCheckpointObjects(mapper, managedCheckpointRoot));
        resources = mock(PortalSessionRepository.class);
        access = mock(PortalSessionAccess.class);
        notifications = new SessionEventNotifier(source, 1000);
        resource(scope);
        eventStreams = new ManagedSessionEventStreams(journal, notifications, 15000, 50000, 30);
        api = api(10);
    }

    @Test
    void turnPagesAreStableAndAdmissionCursorDoesNotChangeBrokerOrder() {
        var first = admission.acceptTurn(scope, "actor", "first", "{}");
        var second = admission.acceptTurn(scope, "actor", "second", "{}");
        assertEquals(1, admission.reservePublication(scope, second.id()).publicationSequence());
        admission.confirmPublication(scope, second.id(), "broker");
        assertEquals(2, admission.reservePublication(scope, first.id()).publicationSequence());
        var page = api.turns(scope.sessionId(), null, "desc", null, 1, headers);
        assertEquals(second.id(), rows(page).get(0).get("id"));
        assertEquals(2L, rows(page).get(0).get("admission_sequence"));
        assertNotNull(rows(page).get(0).get("created_at"));
        assertTrue((Boolean) page.get("has_more"));
        String cursor = (String) page.get("next_cursor");
        admission.acceptTurn(scope, "actor", "third", "{}");
        var next = api.turns(scope.sessionId(), cursor, "desc", null, 1, headers);
        assertEquals(first.id(), rows(next).get(0).get("id"));
        assertFalse((Boolean) next.get("has_more"));
        assertNull(next.get("next_cursor"));
        assertEquals(
                first.createdAt(), admission.acceptTurn(scope, "actor", "first", "{}").createdAt());
        assertEquals(3, admission.turnHead(scope));
    }

    @Test
    void cursorsCannotMoveBetweenScopesQueriesAndTurns() {
        var first = admission.acceptTurn(scope, "actor", "first", "{}");
        var second = admission.acceptTurn(scope, "actor", "second", "{}");
        String cursor =
                (String)
                        api.turns(scope.sessionId(), null, "asc", null, 1, headers)
                                .get("next_cursor");
        assertThrows(
                ResponseStatusException.class,
                () -> api.turns(scope.sessionId(), cursor, "desc", null, 1, headers));
        assertThrows(
                ResponseStatusException.class,
                () -> api.turns(scope.sessionId(), cursor, "asc", "running", 1, headers));
        var other = new ManagedScope("t", "n", "other");
        resource(other);
        assertThrows(
                ResponseStatusException.class,
                () -> api.turns(other.sessionId(), cursor, "asc", null, 1, headers));
        admission.acceptInput(scope, first.id(), "actor", "one", Type.INJECT, null, "{}");
        admission.acceptInput(scope, first.id(), "actor", "two", Type.STEER, null, "{}");
        String inputCursor =
                (String)
                        api.inputs(scope.sessionId(), first.id(), null, 1, headers)
                                .get("next_cursor");
        assertThrows(
                ResponseStatusException.class,
                () -> api.inputs(scope.sessionId(), second.id(), inputCursor, 1, headers));
        assertThrows(
                ResponseStatusException.class,
                () -> api.events(scope.sessionId(), cursor, 1, headers));
        assertThrows(
                ResponseStatusException.class,
                () -> api.turns(scope.sessionId(), null, "asc", null, 257, headers));
        assertThrows(
                ResponseStatusException.class,
                () -> api.events(scope.sessionId(), null, 0, headers));
    }

    @Test
    void inputPagesReadCommittedReceiptsAndReasons() {
        String turn = running();
        var first = admission.acceptInput(scope, turn, "actor", "one", Type.INJECT, null, "{}");
        var second = admission.acceptInput(scope, turn, "actor", "two", Type.STEER, null, "{}");
        journal.commit(
                journal.objects()
                        .prepare(
                                new ManagedRuntimeBatch(
                                        scope,
                                        turn,
                                        1,
                                        "token",
                                        "writer",
                                        "receipt",
                                        0,
                                        List.of(
                                                new SessionEvent(
                                                        1,
                                                        "checkpoint",
                                                        1,
                                                        1,
                                                        "state/checkpoint",
                                                        "run",
                                                        turn,
                                                        true,
                                                        "{\"projectorVersion\":1,\"stateJson\":\"{\\\"context\\\":[]}\"}")),
                                        List.of(),
                                        List.of(
                                                new InputReceipt(first.id(), "applied", null),
                                                new InputReceipt(
                                                        second.id(), "rejected", "turn_finished")),
                                        null)));
        var page = api.inputs(scope.sessionId(), turn, null, 2, headers);
        assertFalse((Boolean) page.get("has_more"));
        assertEquals("applied", rows(page).get(0).get("status"));
        assertEquals("delivered", rows(page).get(0).get("delivery_status"));
        assertEquals("turn_finished", rows(page).get(1).get("rejection_reason"));
    }

    @Test
    void restAndTwoSseSubscribersShareEnvelopeAndReconnectCursor() throws Exception {
        String turn = running();
        var ready = new CountDownLatch(2);
        var first =
                api.stream(scope.sessionId(), null, headers)
                        .doOnNext(
                                frame -> {
                                    if ("connected".equals(frame.comment())) ready.countDown();
                                })
                        .filter(frame -> frame.data() != null)
                        .next()
                        .toFuture();
        var second =
                api.stream(scope.sessionId(), null, headers)
                        .doOnNext(
                                frame -> {
                                    if ("connected".equals(frame.comment())) ready.countDown();
                                })
                        .filter(frame -> frame.data() != null)
                        .next()
                        .toFuture();
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        messages(turn);
        var a = first.get(5, TimeUnit.SECONDS);
        var b = second.get(5, TimeUnit.SECONDS);
        assertEquals(a.data(), b.data());
        assertNull(a.id());
        assertEquals("agent.message", a.event());
        assertFalse(a.data().contains("private-thinking"));
        var page =
                api.history(
                        scope.sessionId(), Map.of("types[]", List.of("agent.message")), headers);
        assertEquals(mapper.readValue(a.data(), Map.class).get("id"), rows(page).get(0).get("id"));
        assertThrows(
                ResponseStatusException.class,
                () -> api.stream(scope.sessionId(), "old-cursor", headers));
    }

    @Test
    void sseCapacityIsReleasedOnDisconnect() {
        var limited = api(1);
        var held = limited.stream(scope.sessionId(), null, headers).subscribe();
        StepVerifier.create(limited.stream(scope.sessionId(), null, headers))
                .expectErrorMatches(
                        error ->
                                error instanceof ManagedStateException state
                                        && "admission_limited".equals(state.code()))
                .verify(Duration.ofSeconds(5));
        held.dispose();
        StepVerifier.create(limited.stream(scope.sessionId(), null, headers))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void idleSseHeartbeatDoesNotAdvanceTheDurableCursor() {
        var idle =
                api(1, 25).stream(scope.sessionId(), null, headers)
                        .filter(frame -> "keepalive".equals(frame.comment()))
                        .next()
                        .block(Duration.ofSeconds(5));
        assertNotNull(idle);
        assertEquals("keepalive", idle.comment());
        assertNull(idle.id());
        assertNull(idle.data());
        assertEquals(0, journal.publicHead(scope));
    }

    @Test
    void idleConnectionRechecksItsOwnAuthorizationWithoutReadingEvents() {
        var checks = new AtomicInteger();
        doAnswer(
                        call -> {
                            if (checks.incrementAndGet() > 1)
                                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
                            return null;
                        })
                .when(access)
                .authorize(scope.sessionId(), headers, "read");
        ReflectionTestUtils.setField(api, "authorization", Duration.ofMillis(100));
        StepVerifier.create(
                        api.stream(scope.sessionId(), null, headers)
                                .filter(frame -> frame.data() != null))
                .expectErrorMatches(
                        error ->
                                error instanceof ResponseStatusException status
                                        && status.getStatusCode().value() == 403)
                .verify(Duration.ofSeconds(3));
        assertEquals(2, checks.get());
    }

    @Test
    void resourceSnapshotAndPagesUseTheSamePublicCursorAndOmitPrivateContent() {
        String turn = running();
        messages(turn);
        var snapshot = api.snapshot(scope.sessionId(), 1, headers);
        var pages = cast(snapshot.get("resources"));
        var items = cast(pages.get("items"));
        assertEquals(snapshot.get("as_of"), items.get("as_of"));
        assertTrue((Boolean) items.get("has_more"));
        assertFalse(items.toString().contains("private-thinking"));
        assertEquals(
                snapshot.get("as_of"),
                api.events(scope.sessionId(), null, 2, headers).get("next_cursor"));
        journal.commit(
                journal.objects()
                        .prepare(
                                new ManagedRuntimeBatch(
                                        scope,
                                        turn,
                                        1,
                                        "token",
                                        "writer",
                                        "update",
                                        2,
                                        List.of(
                                                new SessionEvent(
                                                        1,
                                                        "event-3",
                                                        3,
                                                        1,
                                                        "message/user",
                                                        "run",
                                                        turn,
                                                        true,
                                                        "{\"message\":{\"id\":\"user\",\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"updated\"}]}}")),
                                        List.of(),
                                        List.of(),
                                        null)));
        var second =
                api.resources(
                        scope.sessionId(),
                        (String) snapshot.get("snapshot_id"),
                        "items",
                        (String) items.get("next_cursor"),
                        1,
                        headers);
        assertEquals(snapshot.get("as_of"), second.get("as_of"));
        assertTrue(second.toString().contains("hello"));
        assertFalse(second.toString().contains("updated"));
    }

    @Test
    void terminalQueriesEventsSnapshotAndCapabilitiesAgree() {
        String turn = running();
        assertTrue(
                ((List<?>)
                                api.capabilities(scope.sessionId(), turn, headers)
                                        .get("available_commands"))
                        .contains("cancel"));
        admission.closeInputs(scope, turn, 1);
        journal.commit(
                journal.objects()
                        .prepare(
                                new ManagedRuntimeBatch(
                                        scope,
                                        turn,
                                        1,
                                        "token",
                                        "writer",
                                        "terminal",
                                        0,
                                        List.of(
                                                new SessionEvent(
                                                        1,
                                                        "checkpoint",
                                                        1,
                                                        1,
                                                        "state/checkpoint",
                                                        "run",
                                                        turn,
                                                        true,
                                                        "{\"projectorVersion\":1,\"stateJson\":\"{\\\"context\\\":[]}\"}")),
                                        List.of(),
                                        List.of(),
                                        "succeeded")));
        assertEquals("completed", api.turn(scope.sessionId(), turn, headers).get("status"));
        assertEquals(
                List.of("turn.running", "turn.completed"),
                rows(api.events(scope.sessionId(), null, 10, headers)).stream()
                        .map(event -> event.get("type"))
                        .toList());
        assertEquals(
                turn,
                rows(api.turns(scope.sessionId(), null, "desc", "completed", 10, headers))
                        .get(0)
                        .get("id"));
        var snapshot = api.snapshot(scope.sessionId(), 10, headers);
        assertEquals(
                "turn.completed",
                rows(cast(cast(snapshot.get("resources")).get("turns"))).get(0).get("type"));
        assertTrue(
                ((List<?>)
                                api.capabilities(scope.sessionId(), turn, headers)
                                        .get("available_commands"))
                        .isEmpty());
        var capabilities = cast(api.capabilities(scope.sessionId(), headers).get("capabilities"));
        assertEquals(false, capabilities.get("files"));
        assertEquals(false, capabilities.get("file_input"));
        assertEquals(false, capabilities.get("artifacts"));
        assertEquals(false, capabilities.get("checkpoint_restore"));
        assertEquals(false, capabilities.get("fork"));
        assertEquals(false, capabilities.get("budget"));
    }

    @Test
    void deploymentWithoutUserFilesPreservesCheckpointCapabilities() {
        ReflectionTestUtils.setField(api, "userFilesEnabled", false);
        var capabilities = cast(api.capabilities(scope.sessionId(), headers).get("capabilities"));
        assertEquals(false, capabilities.get("files"));
        assertEquals(false, capabilities.get("file_input"));
        assertEquals(false, capabilities.get("artifacts"));
        assertEquals(false, capabilities.get("checkpoints"));
        assertEquals(false, capabilities.get("checkpoint_restore"));
        assertEquals(false, capabilities.get("fork"));
    }

    @Test
    void childSessionCapabilitiesDoNotAdvertiseRootOnlyOperations() {
        resources.findById(scope.sessionId()).orElseThrow().rootSessionId = "parent";
        var capabilities = cast(api.capabilities(scope.sessionId(), headers).get("capabilities"));
        assertEquals(false, capabilities.get("checkpoint_restore"));
        assertEquals(false, capabilities.get("fork"));
        assertEquals(false, capabilities.get("webhooks"));
        assertEquals(false, capabilities.get("checkpoints"));
        assertEquals(false, capabilities.get("files"));
    }

    @Test
    void canonicalHttpRoutesUseTheNewLedgerAndStrictRequestShapes() throws Exception {
        var client =
                WebTestClient.bindToController(
                                new SessionApiController(mock(SessionApi.class), api))
                        .build();
        client.post()
                .uri("/v1/sessions/session/events")
                .header("Idempotency-Key", "request-one")
                .bodyValue(
                        Map.of(
                                "events",
                                List.of(
                                        Map.of(
                                                "type",
                                                "user.message",
                                                "content",
                                                List.of(Map.of("type", "text", "text", "hello")),
                                                "agentscope_service",
                                                Map.of("intent", "new_turn")))))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data[0].agentscope_service.receipt.status")
                .isEqualTo("accepted");
        client.post()
                .uri("/v1/sessions/session/turns")
                .bodyValue(Map.of())
                .exchange()
                .expectStatus()
                .isEqualTo(405);
        client.get()
                .uri("/v1/sessions/session/capabilities")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.extensions.durable-events-v1")
                .isEqualTo(false);
        assertEquals(1, admission.turnHead(scope));
    }

    private ManagedSessionTurnsApi api(int connections) {
        return api(connections, 15000);
    }

    private ManagedSessionTurnsApi api(int connections, long heartbeatMillis) {
        var catalog = mock(ManagedCatalog.class);
        when(catalog.resolve(any(ManagedScope.class)))
                .thenReturn(
                        new SessionResolveResult(
                                null, Map.of(), null, null, null, Map.of(), null, List.of(),
                                List.of(), null, null));
        return new ManagedSessionTurnsApi(
                resources,
                access,
                mock(PortalTurnAdmission.class),
                admission,
                catalog,
                mock(ManagedTurnPublisher.class),
                mock(ManagedInputDelivery.class),
                journal,
                mapper,
                eventStreams,
                connections,
                15000,
                heartbeatMillis);
    }

    private void resource(ManagedScope selected) {
        admission.createSession(selected, "{}");
        var row = new PortalSessionEntity();
        row.id = selected.sessionId();
        row.tenant = selected.tenant();
        row.namespace = selected.namespace();
        row.runtimeKind = "managed";
        row.lifecycle = "active";
        when(resources.findById(row.id)).thenReturn(Optional.of(row));
    }

    @Test
    void durableInputWaitHasThePublicWaitingStateAndIsIncludedInWaitingQueries() {
        String turn = running();
        var request =
                new SessionEvent(
                        1,
                        "requested",
                        1,
                        1,
                        "interaction/requested",
                        "run",
                        turn,
                        true,
                        "{\"requestId\":\"action\",\"kind\":\"confirmation\",\"call\":{\"type\":\"tool_use\",\"id\":\"action\",\"name\":\"execute\",\"input\":{}}}");
        var checkpoint =
                new SessionEvent(
                        1,
                        "wait-state",
                        2,
                        1,
                        "state/checkpoint",
                        "run",
                        turn,
                        true,
                        "{\"stateJson\":\"{}\"}");
        journal.commit(
                journal.objects()
                        .prepare(
                                new ManagedRuntimeBatch(
                                        scope,
                                        turn,
                                        1,
                                        "token",
                                        "writer",
                                        "input-wait",
                                        0,
                                        List.of(request, checkpoint),
                                        List.of(),
                                        List.of(),
                                        null,
                                        ManagedRuntimeBatch.Suspension.inputs(
                                                "input-suspension", List.of("action")))));
        assertEquals("waiting_input", admission.turn(scope, turn).status());
        assertEquals("waiting", api.turn(scope.sessionId(), turn, headers).get("status"));
        var waiting = rows(api.turns(scope.sessionId(), null, "asc", "waiting", 10, headers));
        assertEquals(List.of(turn), waiting.stream().map(row -> row.get("id")).toList());
        assertEquals("waiting", waiting.get(0).get("status"));
        var snapshot = api.snapshot(scope.sessionId(), 10, headers);
        assertEquals(
                "turn.requires_action",
                rows(cast(cast(snapshot.get("resources")).get("turns"))).get(0).get("type"));
        assertEquals(1, rows(cast(cast(snapshot.get("resources")).get("required_actions"))).size());
    }

    private String running() {
        String turn = admission.acceptTurn(scope, "actor", "turn", "{}").id();
        admission.reservePublication(scope, turn);
        admission.confirmPublication(scope, turn, "broker");
        admission.activate(scope, turn, 1, "token");
        return turn;
    }

    private void messages(String turn) {
        journal.commit(
                journal.objects()
                        .prepare(
                                new ManagedRuntimeBatch(
                                        scope,
                                        turn,
                                        1,
                                        "token",
                                        "writer",
                                        "messages",
                                        0,
                                        List.of(
                                                new SessionEvent(
                                                        1,
                                                        "event-1",
                                                        1,
                                                        1,
                                                        "message/user",
                                                        "run",
                                                        turn,
                                                        true,
                                                        "{\"message\":{\"id\":\"user\",\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}"
                                                            + " }"),
                                                new SessionEvent(
                                                        1,
                                                        "event-2",
                                                        2,
                                                        1,
                                                        "message/assistant",
                                                        "run",
                                                        turn,
                                                        true,
                                                        "{\"message\":{\"id\":\"assistant\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"world\"},{\"type\":\"thinking\",\"text\":\"private-thinking\"}]}"
                                                            + " }")),
                                        List.of(),
                                        List.of(),
                                        null)));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
}
