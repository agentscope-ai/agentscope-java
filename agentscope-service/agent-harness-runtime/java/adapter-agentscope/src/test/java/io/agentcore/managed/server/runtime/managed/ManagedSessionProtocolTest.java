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
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedSessionProtocol.Command;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@Timeout(20)
class ManagedSessionProtocolTest {
    final ObjectMapper mapper = new ObjectMapper();
    final ManagedScope scope = new ManagedScope("tenant", "namespace", "session");
    ManagedAdmissionStore admission;
    ManagedSessionProtocol protocol;
    JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        String postgres = System.getenv("MANAGED_PROTOCOL_TEST_JDBC");
        var data =
                postgres == null
                        ? new DriverManagerDataSource(
                                "jdbc:h2:mem:protocol-"
                                        + UUID.randomUUID()
                                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                                "sa",
                                "")
                        : new DriverManagerDataSource(
                                postgres, "codex_protocol", "protocol_fixture_only");
        if (postgres != null) {
            if (!postgres.matches(
                    "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/managed_protocol_fixture"))
                throw new IllegalArgumentException(
                        "Use the isolated local protocol fixture database");
            new JdbcTemplate(data).execute("DROP SCHEMA IF EXISTS ma CASCADE");
        }
        ManagedAdmissionStore.initialize(data);
        jdbc = new JdbcTemplate(data);
        admission = new ManagedAdmissionStore(data, mapper);
        admission.createSession(scope, "{}");
        protocol = admission.protocol();
    }

    private void project(
            JdbcTemplate target,
            ObjectMapper codec,
            ManagedScope session,
            String id,
            String type,
            Map<String, Object> data,
            long now) {
        new TransactionTemplate(new DataSourceTransactionManager(target.getDataSource()))
                .executeWithoutResult(
                        ignored ->
                                ManagedSessionProtocol.project(
                                        target, codec, session, id, type, data, now));
    }

    Command message(String text) {
        return new Command(
                "user.message",
                List.of(Map.of("type", "text", "text", text)),
                "new_turn",
                null,
                true);
    }

    ManagedSessionProtocol.Acceptance send(String key, Command... commands) {
        return protocol.accept(scope, "actor", key, List.of(commands), () -> {});
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> event(ManagedSessionProtocol.Acceptance result, int index) {
        return (Map<String, Object>) ((List<?>) result.receipt().get("data")).get(index);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> ext(Map<String, Object> event) {
        return (Map<String, Object>) event.get("managed_agents");
    }

    String turn(ManagedSessionProtocol.Acceptance result, int index) {
        return (String) ext(event(result, index)).get("turn_id");
    }

    @Test
    void freezesReceiptAndReplaysBeforeVolatileChecks() {
        var first = send("key", message("keep  spaces\n"));
        jdbc.update(
                "UPDATE ma.turn SET status='succeeded',inputs_open=false WHERE scope_key=?",
                scope.messageGroup());
        jdbc.update(
                "UPDATE ma.session SET lifecycle='archived' WHERE scope_key=?",
                scope.messageGroup());
        var retry =
                protocol.accept(
                        scope,
                        "actor",
                        "key",
                        List.of(message("keep  spaces\n")),
                        () -> fail("Replay must not revalidate mutable state"));
        assertTrue(retry.replayed());
        assertEquals(first.receipt(), retry.receipt());
        assertThrows(ManagedStateException.class, () -> send("key", message("keep spaces")));
        assertEquals(1, admission.turnHead(scope));
    }

    @Test
    void concurrentSameKeyCreatesOneBatchAndStableIds() throws Exception {
        var pool = Executors.newFixedThreadPool(6);
        try {
            var tasks = new ArrayList<Future<ManagedSessionProtocol.Acceptance>>();
            for (int i = 0; i < 6; i++) tasks.add(pool.submit(() -> send("same", message("one"))));
            var first = tasks.get(0).get(10, TimeUnit.SECONDS).receipt();
            for (var task : tasks) assertEquals(first, task.get(10, TimeUnit.SECONDS).receipt());
            assertEquals(1, admission.turnHead(scope));
            assertEquals(
                    1,
                    jdbc.queryForObject("SELECT COUNT(*) FROM ma.protocol_request", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void eventIdentityIncludesTheSessionEvenWhenTheCallerReusesAKey() {
        var otherScope = new ManagedScope("tenant", "namespace", "other");
        admission.createSession(otherScope, "{}");
        var first = send("same", message("one"));
        var other = protocol.accept(otherScope, "actor", "same", List.of(message("one")), () -> {});
        assertTrue(!event(first, 0).get("id").equals(event(other, 0).get("id")));
    }

    @Test
    void malformedHistoryBoundariesAreRejectedAsInvalidCursors() throws Exception {
        send("batch", message("one"), message("two"));
        var query = Map.of("limit", List.of("1"), "types[]", List.of("user.message"));
        var first = protocol.history(scope, "actor", query, false);
        var original =
                mapper.readValue(
                        Base64.getUrlDecoder().decode((String) first.get("next_page")), Map.class);
        for (Object invalid : List.of("missing", 1.25)) {
            var cursor = new LinkedHashMap<>(original);
            if (invalid.equals("missing")) cursor.remove("through");
            else cursor.put("offset", invalid);
            String encoded =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    mapper.writeValueAsString(cursor)
                                            .getBytes(StandardCharsets.UTF_8));
            var next = new LinkedHashMap<>(query);
            next.put("page", List.of(encoded));
            var error =
                    assertThrows(
                            ManagedStateException.class,
                            () -> protocol.history(scope, "actor", next, false));
            assertEquals("invalid_cursor", error.code());
        }
    }

    @Test
    void aFailedItemRollsBackEventsTurnsQuotaAndRequest() {
        var old = send("old", message("old"));
        jdbc.update("UPDATE ma.turn SET inputs_open=false WHERE turn_id=?", turn(old, 0));
        var closed =
                new Command(
                        "user.message",
                        List.of(Map.of("type", "text", "text", "late")),
                        "steer",
                        turn(old, 0),
                        true);
        long head = protocol.head(scope);
        assertThrows(
                ManagedStateException.class, () -> send("batch", message("must rollback"), closed));
        assertEquals(1, admission.turnHead(scope));
        assertEquals(head, protocol.head(scope));
        assertEquals(
                1, jdbc.queryForObject("SELECT COUNT(*) FROM ma.protocol_request", Integer.class));
    }

    @Test
    void publicationOrderIsPreservedWithinOneBatch() {
        var batch = send("batch", message("one"), message("two"));
        assertThrows(
                ManagedStateException.class,
                () -> admission.reservePublication(scope, turn(batch, 1)));
        var first = admission.reservePublication(scope, turn(batch, 0));
        admission.confirmPublication(scope, first.id(), "broker-first");
        var second = admission.reservePublication(scope, turn(batch, 1));
        assertEquals(1L, first.publicationSequence());
        assertEquals(2L, second.publicationSequence());
    }

    @Test
    void pendingHistoryUsesStableInputIdentityAndVersionedPagination() {
        var batch = send("batch", message("one"), message("two"));
        var query = Map.of("limit", List.of("1"), "types[]", List.of("user.message"));
        var page = protocol.history(scope, "actor", query, false);
        assertNotNull(page.get("next_page"));
        var firstEvent = event(batch, 0);
        var item =
                Map.<String, Object>of(
                        "turn_id",
                        turn(batch, 0),
                        "item",
                        Map.of(
                                "role",
                                "user",
                                "message_id",
                                "command_" + turn(batch, 0) + "_0",
                                "content",
                                List.of(Map.of("type", "text", "text", "one"))));
        project(jdbc, mapper, scope, "native-input", "item.completed", item, 1000);
        var next =
                protocol.history(
                        scope,
                        "actor",
                        Map.of(
                                "limit",
                                List.of("1"),
                                "types[]",
                                List.of("user.message"),
                                "page",
                                List.of((String) page.get("next_page"))),
                        false);
        var frozen = (Map<?, ?>) ((List<?>) next.get("data")).get(0);
        assertNull(frozen.get("processed_at"));
        var latest =
                protocol.history(scope, "actor", Map.of("types[]", List.of("user.message")), false);
        assertEquals(2, ((List<?>) latest.get("data")).size());
        assertTrue(
                ((List<?>) latest.get("data"))
                        .stream()
                                .map(value -> (Map<?, ?>) value)
                                .anyMatch(
                                        value ->
                                                value.get("id").equals(firstEvent.get("id"))
                                                        && value.get("processed_at") != null));
        assertThrows(
                ManagedStateException.class,
                () ->
                        protocol.history(
                                scope,
                                "other-actor",
                                Map.of(
                                        "limit",
                                        List.of("1"),
                                        "types[]",
                                        List.of("user.message"),
                                        "page",
                                        List.of((String) page.get("next_page"))),
                                false));
    }

    @Test
    void outputIsIndependentOfSnapshotsAndDoesNotDuplicateAssistantMessage() {
        var batch = send("turn", message("question"));
        String turn = turn(batch, 0);
        assertEquals(false, protocol.output(scope, turn, null, 1).get("output_available"));
        var payload =
                Map.<String, Object>of(
                        "turn_id",
                        turn,
                        "item_id",
                        "answer-item",
                        "item",
                        Map.of(
                                "role",
                                "assistant",
                                "message_id",
                                "answer",
                                "content",
                                List.of(Map.of("type", "text", "text", "result"))));
        project(jdbc, mapper, scope, "message", "item.completed", payload, 2000);
        assertEquals(false, protocol.output(scope, turn, null, 1).get("output_available"));
        project(jdbc, mapper, scope, "final", "item.finalized", payload, 2001);
        ManagedSessionProtocol.sealOutput(jdbc, scope, turn);
        var output = protocol.output(scope, turn, null, 1);
        assertEquals(true, output.get("output_available"));
        var history =
                protocol.history(
                        scope, "actor", Map.of("types[]", List.of("agent.message")), false);
        assertEquals(1, ((List<?>) history.get("data")).size());
        assertEquals(
                ((Map<?, ?>) ((List<?>) history.get("data")).get(0)).get("id"),
                ((Map<?, ?>) ((List<?>) output.get("data")).get(0)).get("id"));
    }

    @Test
    void finalOutputPagesFreezeTheCompleteCollection() {
        var batch = send("turn", message("question"));
        String turn = turn(batch, 0);
        for (int index = 0; index < 3; index++) {
            var item =
                    Map.<String, Object>of(
                            "turn_id",
                            turn,
                            "item",
                            Map.of(
                                    "role",
                                    "assistant",
                                    "message_id",
                                    "answer-" + index,
                                    "content",
                                    List.of(Map.of("type", "text", "text", "part-" + index))));
            project(jdbc, mapper, scope, "final-" + index, "item.finalized", item, 2000 + index);
        }
        assertEquals(false, protocol.output(scope, turn, null, 1).get("output_available"));
        ManagedSessionProtocol.sealOutput(jdbc, scope, turn);
        var first = protocol.output(scope, turn, null, 1);
        assertEquals(true, first.get("has_more"));
        var second = protocol.output(scope, turn, (String) first.get("next_cursor"), 2);
        assertEquals(false, second.get("has_more"));
        assertEquals(2, ((List<?>) second.get("data")).size());
        var late =
                Map.<String, Object>of(
                        "turn_id",
                        turn,
                        "item",
                        Map.of(
                                "role",
                                "assistant",
                                "message_id",
                                "late",
                                "content",
                                List.of(Map.of("type", "text", "text", "late"))));
        assertThrows(
                ManagedStateException.class,
                () -> project(jdbc, mapper, scope, "late", "item.finalized", late, 3000));
        assertEquals(3, ((List<?>) protocol.output(scope, turn, null, 100).get("data")).size());
    }

    @Test
    void eventFiltersApplyBeforeTurnAndInputPagination() {
        var batch = send("batch", message("one"), message("two"));
        String secondTurn = turn(batch, 1), eventId = (String) event(batch, 1).get("id");
        var turns = admission.turns(scope, 0, false, null, 1, eventId);
        assertEquals(1, turns.size());
        assertEquals(secondTurn, turns.get(0).id());
        var inputBatch =
                send(
                        "inputs",
                        new Command(
                                "user.message",
                                List.of(Map.of("type", "text", "text", "one")),
                                "inject",
                                secondTurn,
                                true),
                        new Command(
                                "user.message",
                                List.of(Map.of("type", "text", "text", "two")),
                                "steer",
                                secondTurn,
                                true));
        String secondEvent = (String) event(inputBatch, 1).get("id");
        var inputs = admission.inputsAfter(scope, secondTurn, 0, 1, "accepted", secondEvent);
        assertEquals(1, inputs.size());
        assertEquals(ext(event(inputBatch, 1)).get("input_id"), inputs.get(0).id());
        assertTrue(
                admission.inputsAfter(scope, secondTurn, 0, 1, "applied", secondEvent).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inputNotificationsDeduplicateAcrossSourcesInEitherOrder(boolean receiptFirst) {
        String turn = turn(send("turn", message("question")), 0);
        var inputIds = new ArrayList<String>();
        for (String intent : List.of("inject", "steer", "cancel")) {
            var accepted =
                    send(
                            intent,
                            new Command(
                                    "cancel".equals(intent) ? "user.interrupt" : "user.message",
                                    "cancel".equals(intent)
                                            ? List.of()
                                            : List.of(Map.of("type", "text", "text", intent)),
                                    intent,
                                    turn,
                                    true));
            String input = (String) ext(event(accepted, 0)).get("input_id");
            inputIds.add(input);
            long admitted = protocol.head(scope);
            project(
                    jdbc,
                    mapper,
                    scope,
                    "native-accepted-" + input,
                    "input.accepted",
                    Map.of("input_id", input),
                    1000);
            assertEquals(admitted, protocol.head(scope));
            long beforeApplied = protocol.head(scope);
            for (boolean receipt : List.of(receiptFirst, !receiptFirst)) {
                project(
                        jdbc,
                        mapper,
                        scope,
                        (receipt ? "receipt-" : "native-") + input,
                        "input.applied",
                        receipt
                                ? Map.of("input_id", input)
                                : Map.of("input_ids", List.of(input, input)),
                        2000);
            }
            var changes = protocol.changes(scope, beforeApplied, 100);
            assertEquals(
                    List.of(
                            "cancel".equals(intent) ? "user.interrupt" : "user.message",
                            "managed_agents.input.applied"),
                    changes.stream().map(change -> change.event().get("type")).toList());
        }
        var history =
                protocol.history(
                        scope,
                        "actor",
                        Map.of(
                                "types[]",
                                List.of(
                                        "managed_agents.input.accepted",
                                        "managed_agents.input.applied")),
                        true);
        var notifications = (List<?>) history.get("data");
        assertEquals(6, notifications.size());
        for (String input : inputIds) {
            assertEquals(
                    List.of("managed_agents.input.accepted", "managed_agents.input.applied"),
                    notifications.stream()
                            .map(value -> (Map<String, Object>) value)
                            .filter(value -> input.equals(ext(value).get("input_id")))
                            .map(value -> value.get("type"))
                            // History breaks equal-millisecond timestamps by event ID.
                            .sorted((a, b) -> a.toString().compareTo(b.toString()))
                            .toList());
            assertEquals(
                    List.of("managed_agents.input.accepted", "managed_agents.input.applied"),
                    protocol.changes(scope, 0, 100).stream()
                            .map(ManagedSessionProtocol.Change::event)
                            .filter(value -> input.equals(ext(value).get("input_id")))
                            .filter(
                                    value ->
                                            List.of(
                                                            "managed_agents.input.accepted",
                                                            "managed_agents.input.applied")
                                                    .contains(value.get("type")))
                            .map(value -> value.get("type"))
                            .toList());
        }
    }

    @Test
    void rejectedInputNotificationsRetainDistinctInputsAndReasonsAcrossReplays() {
        String turn = turn(send("turn", message("question")), 0);
        for (String intent : List.of("inject", "steer")) {
            var accepted =
                    send(
                            intent,
                            new Command(
                                    "user.message",
                                    List.of(Map.of("type", "text", "text", intent)),
                                    intent,
                                    turn,
                                    true));
            String input = (String) ext(event(accepted, 0)).get("input_id");
            for (String source : List.of("native-", "receipt-", "replayed-"))
                project(
                        jdbc,
                        mapper,
                        scope,
                        source + input,
                        "input.rejected",
                        Map.of("input_id", input, "reason", intent + "-closed"),
                        2000);
            var pending = protocol.link(scope, turn, input);
            assertNull(pending.get("applied_at"));
        }
        var history =
                protocol.history(
                        scope,
                        "actor",
                        Map.of("types[]", List.of("managed_agents.input.rejected")),
                        true);
        var notifications = (List<?>) history.get("data");
        assertEquals(2, notifications.size());
        assertEquals(
                List.of("inject-closed", "steer-closed"),
                notifications.stream()
                        .map(value -> ext((Map<String, Object>) value).get("rejection_reason"))
                        .toList());
    }

    @Test
    void fractionalTimeFiltersKeepTheOfficialProcessedAtBoundary() {
        var batch = send("turn", message("question"));
        String turn = turn(batch, 0);
        var item =
                Map.<String, Object>of(
                        "turn_id",
                        turn,
                        "item",
                        Map.of(
                                "role",
                                "assistant",
                                "message_id",
                                "at-one-second",
                                "content",
                                List.of(Map.of("type", "text", "text", "answer"))));
        project(jdbc, mapper, scope, "message", "item.completed", item, 1000);
        var excluded =
                protocol.history(
                        scope,
                        "actor",
                        Map.of(
                                "types[]",
                                List.of("agent.message"),
                                "created_at[gte]",
                                List.of("1970-01-01T00:00:01.000001Z")),
                        false);
        assertTrue(((List<?>) excluded.get("data")).isEmpty());
        var included =
                protocol.history(
                        scope,
                        "actor",
                        Map.of(
                                "types[]",
                                List.of("agent.message"),
                                "created_at[lt]",
                                List.of("1970-01-01T00:00:01.000001Z")),
                        false);
        assertEquals(1, ((List<?>) included.get("data")).size());
    }

    @Test
    void standardMessageWhileBusyIsRejectedAndTargetedCancelDoesNotAffectNextTurn() {
        var batch = send("batch", message("T1"), message("T2"));
        jdbc.update(
                "UPDATE ma.session SET current_turn_id=? WHERE scope_key=?",
                turn(batch, 0),
                scope.messageGroup());
        assertThrows(
                ManagedStateException.class,
                () ->
                        send(
                                "ordinary",
                                new Command(
                                        "user.message",
                                        List.of(Map.of("type", "text", "text", "busy")),
                                        "message",
                                        null,
                                        false)));
        var cancel =
                send(
                        "cancel",
                        new Command("user.interrupt", List.of(), "cancel", turn(batch, 0), true));
        assertEquals(turn(batch, 0), ext(event(cancel, 0)).get("turn_id"));
        assertTrue(admission.pendingInputs(scope, turn(batch, 1), 10).isEmpty());
    }

    @Test
    void terminalCancelUsesSpecificConflictButReplaysOriginalReceipt() {
        var batch = send("terminal-batch", message("T1"));
        String turn = turn(batch, 0);
        var command = new Command("user.interrupt", List.of(), "cancel", turn, true);
        var first = send("cancel-original", command);
        jdbc.update(
                "UPDATE ma.turn SET status='succeeded',inputs_open=false WHERE scope_key=? AND"
                        + " turn_id=?",
                scope.messageGroup(),
                turn);
        assertEquals(first.receipt(), send("cancel-original", command).receipt());
        assertEquals(
                "turn_terminal",
                assertThrows(ManagedStateException.class, () -> send("cancel-new", command))
                        .code());
    }

    @Test
    void projectionBatchPreservesReadYourWritesAndRollsBackSequence() {
        var accepted = send("batch-projection", message("one"));
        String turn = turn(accepted, 0);
        long before =
                jdbc.queryForObject(
                        "SELECT sequence FROM ma.protocol_session WHERE scope_key=?",
                        Long.class,
                        scope.messageGroup());
        var transaction =
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        Runnable project =
                () -> {
                    try (var batch = ManagedSessionProtocol.projectionBatch(jdbc, scope)) {
                        for (int repeat = 0; repeat < 2; repeat++) {
                            project(
                                    jdbc,
                                    mapper,
                                    scope,
                                    "model-start",
                                    "model.started",
                                    Map.of("turn_id", turn, "model_call_id", "model-1"),
                                    1000);
                            project(
                                    jdbc,
                                    mapper,
                                    scope,
                                    "model-end",
                                    "model.completed",
                                    Map.of("turn_id", turn, "model_call_id", "model-1"),
                                    2000);
                        }
                    }
                };
        assertThrows(
                IllegalStateException.class,
                () ->
                        transaction.executeWithoutResult(
                                ignored -> {
                                    project.run();
                                    throw new IllegalStateException("rollback fixture");
                                }));
        assertEquals(
                before,
                jdbc.queryForObject(
                        "SELECT sequence FROM ma.protocol_session WHERE scope_key=?",
                        Long.class,
                        scope.messageGroup()));
        transaction.executeWithoutResult(ignored -> project.run());
        assertEquals(
                before + 2,
                jdbc.queryForObject(
                        "SELECT sequence FROM ma.protocol_session WHERE scope_key=?",
                        Long.class,
                        scope.messageGroup()));
        assertEquals(
                List.of(before + 1, before + 2),
                jdbc.queryForList(
                        "SELECT sequence FROM ma.protocol_command_event WHERE scope_key=? AND"
                                + " sequence>? ORDER BY sequence",
                        Long.class,
                        scope.messageGroup(),
                        before));
        transaction.executeWithoutResult(ignored -> project.run());
        assertEquals(
                before + 2,
                jdbc.queryForObject(
                        "SELECT sequence FROM ma.protocol_session WHERE scope_key=?",
                        Long.class,
                        scope.messageGroup()));
        // A later ordinary API write uses the committed watermark, without a leaked batch context.
        send("after-projection", message("two"));
        assertEquals(
                before + 4,
                jdbc.queryForObject(
                        "SELECT sequence FROM ma.protocol_session WHERE scope_key=?",
                        Long.class,
                        scope.messageGroup()));
    }

    @Test
    void projectionBatchRequiresAnActiveDatasourceTransaction() {
        assertThrows(
                IllegalStateException.class,
                () -> ManagedSessionProtocol.projectionBatch(jdbc, scope));
    }

    @Test
    void terminalBoundaryRetainsIdleBeforeNextRunning() {
        var batch = send("batch", message("one"), message("two"));
        project(
                jdbc,
                mapper,
                scope,
                "run1",
                "turn.running",
                Map.of("turn_id", turn(batch, 0)),
                1000);
        project(
                jdbc,
                mapper,
                scope,
                "done1",
                "turn.completed",
                Map.of("turn_id", turn(batch, 0)),
                2000);
        project(
                jdbc,
                mapper,
                scope,
                "run2",
                "turn.running",
                Map.of("turn_id", turn(batch, 1)),
                3000);
        var history =
                protocol.history(
                        scope,
                        "actor",
                        Map.of("types[]", List.of("session.status_running", "session.status_idle")),
                        false);
        var types =
                ((List<?>) history.get("data"))
                        .stream().map(value -> ((Map<?, ?>) value).get("type")).toList();
        assertEquals(
                List.of("session.status_running", "session.status_idle", "session.status_running"),
                types);
    }
}
