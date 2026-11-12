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
package io.agentscope.durable.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.AppliedResult;
import io.agentscope.durable.DurableTypes.Commit;
import io.agentscope.durable.DurableTypes.ExecutionReconciliation;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.OutputPage;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.SessionSpec;
import io.agentscope.durable.DurableTypes.ThreadBinding;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.ToolCall;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.ExecutionMessages;
import io.agentscope.durable.ExecutionMessages.Command;
import io.agentscope.durable.ExecutionMessages.Event;
import io.agentscope.durable.ExecutionMessages.Kind;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.TransactionalSessionBackend;
import io.agentscope.extensions.durable.jdbc.JdbcRecordStore;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueuedExecutorTest {
    @TempDir Path directory;
    private final String postgresUrl = System.getProperty("durable.test.jdbcUrl");
    private final Map<Path, String> schemas = new HashMap<>();

    private JdbcRecordStore open(Path file) {
        if (postgresUrl == null) return JdbcRecordStore.sqlite(file);
        var schema =
                schemas.computeIfAbsent(
                        file,
                        ignored -> {
                            String name =
                                    "durable_queue_"
                                            + UUID.randomUUID().toString().replace("-", "");
                            try (var connection = DriverManager.getConnection(postgresUrl);
                                    var statement = connection.createStatement()) {
                                statement.execute("CREATE SCHEMA " + name);
                            } catch (Exception error) {
                                throw new IllegalStateException(error);
                            }
                            return name;
                        });
        var properties = new Properties();
        properties.setProperty("currentSchema", schema);
        return JdbcRecordStore.postgres(postgresUrl, properties);
    }

    @AfterEach
    void cleanupSchemas() throws Exception {
        if (postgresUrl != null)
            try (var connection = DriverManager.getConnection(postgresUrl);
                    var statement = connection.createStatement()) {
                for (var schema : schemas.values())
                    statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
    }

    final ExecutionRequest request =
            new ExecutionRequest(
                    "execution",
                    new ThreadKey(new SessionKey("tenant", "session"), "main"),
                    "turn",
                    new ToolCall("call", "hands", "echo", DurableJson.value(Map.of("value", 7))));
    final ExecutionResult completed =
            new ExecutionResult(
                    ExecutionState.SUCCEEDED, "execution", DurableJson.value("done"), null);

    @Test
    void verifiedUnknownOutcomeFlowsThroughTheOriginalOutboxAndResultApplication() {
        try (var records = open(directory.resolve("reconciliation.db"))) {
            var backend = new TransactionalSessionBackend(records);
            backend.create(
                    new SessionSpec(
                            request.session(),
                            new ThreadBinding("independent-harness", "v1", "1"),
                            DurableJson.value(null)));
            var turn = backend.submit(request.thread(), "input", DurableJson.value("input"));
            var brain =
                    backend.claim(request.thread(), "brain", Duration.ofSeconds(30)).orElseThrow();
            backend.commit(
                    brain,
                    new Commit(
                            "intent",
                            0,
                            DurableJson.value("native-private"),
                            List.of(request.call()),
                            List.of(),
                            List.of(),
                            List.of(),
                            TurnStatus.WAITING,
                            null,
                            null,
                            null));
            brain =
                    backend.claim(request.thread(), "dispatch", Duration.ofSeconds(30))
                            .orElseThrow();
            var admitted = backend.frame(brain).tools().get(0).request();
            backend.beginDispatch(brain, admitted.executionId());
            backend.release(brain, Duration.ZERO);
            var hands = new TransactionalExecutionStore(records, "reconciliation");
            var queue =
                    new QueuedExecutor(
                            "hands", records, backend, hands::receive, Duration.ofMillis(1));
            queue.startExecution(admitted);
            queue.tick(error -> fail(error));
            var key = hands.keys(null, 1).get(0);
            var original = hands.claim(key, "original", Duration.ofSeconds(30)).orElseThrow();
            hands.configureOutput(original, true);
            hands.begin(original);
            hands.appendOutput(original, new OutputPage(0, 3, "YWJj", 3, false, null));
            hands.update(original, ExecutionResult.unknown("Provider result was lost"));
            hands.release(original, Duration.ZERO);
            var operator =
                    hands.claim(admitted, "operator:verified", Duration.ofSeconds(30))
                            .orElseThrow();
            var resolution =
                    new ExecutionReconciliation("audit-1", completed, "audit://verified/1");
            var reconciled = hands.reconcile(operator, resolution);
            assertEquals(ExecutionState.SUCCEEDED, reconciled.state());
            assertEquals(reconciled, hands.reconcile(operator, resolution));
            assertEquals(
                    DurableException.Code.FENCED,
                    assertThrows(
                                    DurableException.class,
                                    () ->
                                            hands.update(
                                                    original,
                                                    ExecutionResult.unknown("late owner")))
                            .code());
            assertEquals(
                    DurableException.Code.CONFLICT,
                    assertThrows(
                                    DurableException.class,
                                    () ->
                                            hands.reconcile(
                                                    operator,
                                                    new ExecutionReconciliation(
                                                            "audit-1",
                                                            completed,
                                                            "audit://changed")))
                            .code());
            var reopened = new TransactionalExecutionStore(records, "reconciliation");
            assertEquals(resolution, reopened.reconciliation(admitted, "audit-1").orElseThrow());
            assertTrue(reopened.claim(key, "replacement", Duration.ofSeconds(30)).isEmpty());
            var source = new ArrayList<Event>();
            while (reopened.pendingEvent(key).isPresent()) {
                var event = reopened.pendingEvent(key).orElseThrow();
                source.add(event);
                queue.receive(event);
                reopened.published(event);
            }
            assertTrue(source.get(source.size() - 2).output().terminal());
            assertEquals(
                    "Output unavailable after external reconciliation",
                    source.get(source.size() - 2).output().gap());
            assertEquals(ExecutionState.SUCCEEDED, source.get(source.size() - 1).result().state());
            assertEquals(
                    reconciled,
                    backend.execution(admitted.thread(), admitted.executionId()).result());
            assertEquals(reconciled, queue.query(admitted, null));
            assertTrue(
                    backend.events(admitted.session(), null, 100).events().stream()
                            .anyMatch(event -> event.type().equals("tool.output.gap")));
            var replacement =
                    backend.claim(admitted.thread(), "replacement-brain", Duration.ofSeconds(30))
                            .orElseThrow();
            var frame = backend.frame(replacement);
            backend.commit(
                    replacement,
                    new Commit(
                            "apply",
                            frame.turn().revision(),
                            DurableJson.value("applied-native"),
                            List.of(),
                            List.of(new AppliedResult(admitted.executionId(), null)),
                            List.of(),
                            List.of(),
                            TurnStatus.SUCCEEDED,
                            null,
                            reconciled.result(),
                            null));
            assertEquals(
                    TurnStatus.SUCCEEDED, backend.turn(admitted.thread(), turn.turnId()).status());
            assertTrue(backend.execution(admitted.thread(), admitted.executionId()).applied());
        }
    }

    @Test
    void reconciliationCannotTurnPendingWorkIntoAVerifiedEffect() {
        try (var records = open(directory.resolve("unstarted.db"))) {
            var store = new TransactionalExecutionStore(records);
            store.admit(request);
            var key = store.keys(null, 1).get(0);
            var work = store.claim(key, "operator", Duration.ofSeconds(30)).orElseThrow();
            var resolution = new ExecutionReconciliation("audit", completed, "audit://verified");
            assertEquals(
                    DurableException.Code.CONFLICT,
                    assertThrows(DurableException.class, () -> store.reconcile(work, resolution))
                            .code());
            assertTrue(store.reconciliation(request, "audit").isEmpty());
            assertEquals(ExecutionState.PENDING, store.query(request).state());
            assertThrows(
                    DurableException.class,
                    () ->
                            new ExecutionReconciliation(
                                    "audit",
                                    ExecutionResult.unknown("still unknown"),
                                    "audit://verified"));
            assertThrows(
                    DurableException.class,
                    () -> new ExecutionReconciliation("audit", completed, " "));
        }
    }

    @Test
    void protocolRejectsTamperedIdentityUnknownVersionsAndInvalidEvents() {
        var command = ExecutionMessages.command(Kind.EXECUTE, request);
        ExecutionMessages.validate(command);
        assertEquals(command, DurableJson.convert(DurableJson.value(command), Command.class));
        assertThrows(
                DurableException.class,
                () ->
                        ExecutionMessages.validate(
                                new Command(
                                        "v0",
                                        command.commandId(),
                                        command.requestDigest(),
                                        command.kind(),
                                        request)));
        assertThrows(
                DurableException.class,
                () ->
                        ExecutionMessages.validate(
                                new Command(
                                        command.version(),
                                        "wrong",
                                        command.requestDigest(),
                                        command.kind(),
                                        request)));
        var event = ExecutionMessages.event(request, 1, completed);
        ExecutionMessages.validate(event);
        assertEquals(event, DurableJson.convert(DurableJson.value(event), Event.class));
        assertThrows(
                DurableException.class,
                () -> ExecutionMessages.validate(ExecutionMessages.event(request, 0, completed)));
        assertThrows(
                DurableException.class,
                () ->
                        ExecutionMessages.validate(
                                ExecutionMessages.event(request, 1, ExecutionResult.missing())));
    }

    @Test
    void cancelBeforeExecuteLeavesPersistentTombstoneAndQueriesNeverAdmit() {
        Path file = directory.resolve("worker.db");
        try (var records = open(file)) {
            var store = new TransactionalExecutionStore(records);
            assertThrows(
                    DurableException.class,
                    () -> store.receive(ExecutionMessages.command(Kind.QUERY, request)));
            assertTrue(store.keys(null, 10).isEmpty());
            store.receive(ExecutionMessages.command(Kind.CANCEL, request));
        }
        try (var records = open(file)) {
            var store = new TransactionalExecutionStore(records);
            store.receive(ExecutionMessages.command(Kind.EXECUTE, request));
            store.receive(ExecutionMessages.command(Kind.EXECUTE, request));
            assertEquals(ExecutionState.CANCELLED, store.query(request).state());
            var key = store.keys(null, 10).get(0);
            assertTrue(store.claim(key, "worker", Duration.ofSeconds(1)).isEmpty());
            store.published(store.pendingEvent(key).orElseThrow());
            assertEquals(
                    ExecutionState.CANCELLED,
                    store.pendingEvent(key).orElseThrow().result().state());
            var changed =
                    new ExecutionRequest(
                            request.executionId(),
                            request.thread(),
                            request.turnId(),
                            new ToolCall("call", "hands", "echo", DurableJson.value("changed")));
            assertThrows(
                    DurableException.class,
                    () -> store.receive(ExecutionMessages.command(Kind.EXECUTE, changed)));
        }
    }

    @Test
    void publicationReceiptCannotEraseNewOutcomeAndQueryRepublishesWithoutReexecution() {
        try (var records = open(directory.resolve("worker.db"))) {
            var store = new TransactionalExecutionStore(records);
            store.receive(ExecutionMessages.command(Kind.EXECUTE, request));
            var key = store.keys(null, 10).get(0);
            var pending = store.pendingEvent(key).orElseThrow();
            var work = store.claim(key, "worker", Duration.ofSeconds(5)).orElseThrow();
            store.begin(work);
            store.update(work, completed);
            store.published(pending);
            var terminal = store.pendingEvent(key).orElseThrow();
            assertTrue(terminal.sequence() > pending.sequence());
            assertEquals(completed, terminal.result());
            store.published(terminal);
            assertTrue(store.pendingEvent(key).isEmpty());
            store.receive(ExecutionMessages.command(Kind.QUERY, request, terminal.sequence() - 1));
            assertEquals(terminal, store.pendingEvent(key).orElseThrow());
            assertTrue(store.claim(key, "other", Duration.ofSeconds(5)).isEmpty());
        }
    }

    @Test
    void inboxSurvivesSessionCommitFailureAndRestartWithoutMqRedelivery() {
        Path file = directory.resolve("brain.db");
        var backend = mock(SessionBackend.class);
        doThrow(new IllegalStateException("Session storage unavailable"))
                .when(backend)
                .acceptExecutionEvent(any());
        try (var records = open(file)) {
            var executor =
                    new QueuedExecutor(
                            "hands",
                            records,
                            backend,
                            command -> fail("No send expected"),
                            Duration.ofSeconds(1));
            executor.startExecution(request);
            assertThrows(
                    IllegalStateException.class,
                    () -> executor.receive(ExecutionMessages.event(request, 1, completed)));
            assertEquals(ExecutionState.PENDING, executor.query(request, null).state());
        }
        var recovered = mock(SessionBackend.class);
        try (var records = open(file)) {
            var executor =
                    new QueuedExecutor(
                            "hands",
                            records,
                            recovered,
                            command -> fail("Terminal work resent"),
                            Duration.ofSeconds(1));
            executor.tick(error -> fail(error));
            executor.tick(error -> fail(error));
            verify(recovered, times(1))
                    .acceptExecutionEvent(ExecutionMessages.event(request, 1, completed));
            executor.receive(
                    ExecutionMessages.event(request, 1, completed)); // duplicate after lost ACK
            verify(recovered, times(1)).acceptExecutionEvent(any());
            assertEquals(completed, executor.query(request, null));
            assertThrows(
                    DurableException.class,
                    () ->
                            executor.receive(
                                    ExecutionMessages.event(
                                            request,
                                            1,
                                            new ExecutionResult(
                                                    ExecutionState.FAILED,
                                                    "execution",
                                                    null,
                                                    "changed"))));
            assertThrows(
                    DurableException.class,
                    () ->
                            executor.receive(
                                    ExecutionMessages.event(
                                            request,
                                            3,
                                            new ExecutionResult(
                                                    ExecutionState.FAILED,
                                                    "execution",
                                                    null,
                                                    "changed"))));
        }
    }

    @Test
    void failedSendRetriesSameIdentityAcrossRestartThenQueriesAcceptedWork() throws Exception {
        Path file = directory.resolve("brain.db");
        var sent = new ArrayList<Command>();
        var failures = new AtomicInteger();
        try (var records = open(file)) {
            var executor =
                    new QueuedExecutor(
                            "hands",
                            records,
                            mock(SessionBackend.class),
                            command -> {
                                sent.add(command);
                                throw new IllegalStateException(
                                        "Broker accepted but response lost");
                            },
                            Duration.ofMillis(1));
            assertEquals(ExecutionState.NOT_FOUND, executor.query(request, null).state());
            executor.startExecution(request);
            executor.tick(error -> failures.incrementAndGet());
        }
        Thread.sleep(5);
        try (var records = open(file)) {
            var executor =
                    new QueuedExecutor(
                            "hands",
                            records,
                            mock(SessionBackend.class),
                            sent::add,
                            Duration.ofMillis(1));
            executor.tick(error -> fail(error));
            assertEquals(1, failures.get());
            assertEquals(2, sent.size());
            assertEquals(sent.get(0), sent.get(1));
            executor.receive(
                    ExecutionMessages.event(
                            request,
                            1,
                            new ExecutionResult(ExecutionState.RUNNING, "execution", null, null)));
            Thread.sleep(5);
            executor.tick(error -> fail(error));
            assertEquals(Kind.QUERY, sent.get(2).kind());
            executor.cancel(request, null);
            executor.tick(error -> fail(error));
            assertEquals(Kind.CANCEL, sent.get(3).kind());
        }
    }

    @Test
    void sessionAcceptanceFailureDoesNotBlockCancellationDelivery() {
        var backend = mock(SessionBackend.class);
        doThrow(new IllegalStateException("Session storage unavailable"))
                .when(backend)
                .acceptExecutionEvent(any());
        var sent = new ArrayList<Command>();
        var errors = new AtomicInteger();
        try (var records = open(directory.resolve("brain.db"))) {
            var executor =
                    new QueuedExecutor("hands", records, backend, sent::add, Duration.ofSeconds(1));
            executor.startExecution(request);
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            executor.receive(
                                    ExecutionMessages.event(
                                            request,
                                            1,
                                            new ExecutionResult(
                                                    ExecutionState.RUNNING,
                                                    "execution",
                                                    null,
                                                    null))));
            executor.cancel(request, null);
            executor.tick(error -> errors.incrementAndGet());
            assertEquals(1, errors.get());
            assertEquals(1, sent.size());
            assertEquals(Kind.CANCEL, sent.get(0).kind());
        }
    }

    @Test
    void outOfOrderOutputWaitsForPrefixAndTerminalSealThenSurvivesRestart() {
        Path file = directory.resolve("ordered.db");
        var backend = mock(SessionBackend.class);
        var sent = new ArrayList<Command>();
        var pending =
                ExecutionMessages.event(
                        request,
                        1,
                        new ExecutionResult(ExecutionState.RUNNING, "execution", null, null));
        var output =
                ExecutionMessages.event(
                        request, 2, null, new OutputPage(0, 3, "YWJj", 3, true, null));
        var terminal = ExecutionMessages.event(request, 3, completed);
        try (var records = open(file)) {
            var executor =
                    new QueuedExecutor("hands", records, backend, sent::add, Duration.ofSeconds(1));
            executor.startExecution(request);
            executor.receive(terminal);
            executor.receive(output);
            assertEquals(ExecutionState.PENDING, executor.query(request, null).state());
            verify(backend, times(0)).acceptExecutionEvent(any());
            executor.tick(error -> fail(error));
            assertEquals(Kind.QUERY, sent.get(0).kind());
            assertEquals(0, sent.get(0).afterSequence());
            executor.receive(pending);
            executor.receive(output);
            verify(backend, times(3)).acceptExecutionEvent(any());
            assertEquals(completed, executor.query(request, null));
        }
        try (var records = open(file)) {
            var executor =
                    new QueuedExecutor("hands", records, backend, sent::add, Duration.ofSeconds(1));
            assertEquals("YWJj", executor.output(request, 0).data());
            assertTrue(executor.output(request, 3).terminal());
            executor.tick(error -> fail(error));
            verify(backend, times(3)).acceptExecutionEvent(any());
        }
    }

    @Test
    void independentPoolsDoNotConsumeEachOthersLedgerOrAcceptUnsolicitedResults() {
        try (var records = open(directory.resolve("shared.db"))) {
            var a = new TransactionalExecutionStore(records, "hands");
            var b = new TransactionalExecutionStore(records, "browser");
            a.receive(ExecutionMessages.command(Kind.EXECUTE, request));
            assertEquals(1, a.keys(null, 10).size());
            assertTrue(b.keys(null, 10).isEmpty());
            var sent = new ArrayList<Command>();
            var browser =
                    new QueuedExecutor(
                            "browser",
                            records,
                            mock(SessionBackend.class),
                            sent::add,
                            Duration.ofSeconds(1));
            assertThrows(DurableException.class, () -> browser.startExecution(request));
            browser.tick(error -> fail(error));
            assertTrue(sent.isEmpty());
            var hands =
                    new QueuedExecutor(
                            "hands",
                            records,
                            mock(SessionBackend.class),
                            sent::add,
                            Duration.ofSeconds(1));
            assertThrows(
                    DurableException.class,
                    () -> hands.receive(ExecutionMessages.event(request, 1, completed)));
        }
    }
}
