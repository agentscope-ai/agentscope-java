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
package io.agentscope.builder.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.managed.ManagedAdmissionStore.Input;
import io.agentscope.builder.runtime.managed.ManagedInputInbox.Signal;
import io.agentscope.builder.runtime.managed.ManagedInputInbox.Type;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Timeout(15)
class ManagedAdmissionStoreTest {
    private final ManagedScope scope =
            new ManagedScope("tenant", "namespace", "session-" + UUID.randomUUID());
    private DataSource data;
    private ManagedAdmissionStore store;

    @BeforeEach
    void createDatabase() {
        data = database();
        ManagedAdmissionStore.initialize(data);
        store = new ManagedAdmissionStore(data, new ObjectMapper());
        store.createSession(scope, "{\"agentId\":\"agent\",\"environmentId\":\"environment\"}");
    }

    protected DataSource database() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:ma-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    @Test
    void idempotentTurnsUseCanonicalContentAndActorAndScope() {
        var first = store.acceptTurn(scope, "actor", "key", "{\"a\":1,\"b\":2}");
        assertEquals(first.id(), store.acceptTurn(scope, "actor", "key", "{\"b\":2,\"a\":1}").id());
        assertNotEquals(
                first.id(), store.acceptTurn(scope, "other", "key", "{\"a\":1,\"b\":2}").id());
        assertEquals(
                "turn_content_conflict",
                assertThrows(
                                ManagedStateException.class,
                                () -> store.acceptTurn(scope, "actor", "key", "{\"a\":2}"))
                        .code());
        assertEquals(
                "turn_not_found",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        store.turn(
                                                new ManagedScope("other", "namespace", "session"),
                                                first.id()))
                        .code());
    }

    @Test
    void actualPublicationReservationRatherThanAcceptanceOrderDeterminesSequence() {
        var admittedFirst = store.acceptTurn(scope, "actor", "first", "{}");
        var publishedFirst = store.acceptTurn(scope, "actor", "second", "{}");
        assertEquals(
                1L, store.reservePublication(scope, publishedFirst.id()).publicationSequence());
        assertEquals(
                "publication_pending",
                assertThrows(
                                ManagedStateException.class,
                                () -> store.reservePublication(scope, admittedFirst.id()))
                        .code());
        store.confirmPublication(scope, publishedFirst.id(), "broker-first");
        assertEquals(2L, store.reservePublication(scope, admittedFirst.id()).publicationSequence());
        assertEquals(
                "missing_predecessor",
                assertThrows(
                                ManagedStateException.class,
                                () -> store.activate(scope, admittedFirst.id(), 2, "token"))
                        .code());
        assertEquals(0, store.session(scope).executionEpoch());
        assertEquals(1, store.activate(scope, publishedFirst.id(), 1, "token"));
        store.confirmPublication(scope, publishedFirst.id(), "duplicate-confirmation");
        assertEquals("preparing", store.turn(scope, publishedFirst.id()).status());
        assertEquals("queued", store.turn(scope, publishedFirst.id()).deliveryStatus());
    }

    @Test
    void reacquisitionIncrementsEpochButSameOwnerReplayDoesNot() {
        var turn = store.acceptTurn(scope, "actor", "key", "{}");
        store.reservePublication(scope, turn.id());
        store.confirmPublication(scope, turn.id(), "broker");
        long first = store.activate(scope, turn.id(), 1, "first-owner");
        assertEquals(first, store.activate(scope, turn.id(), 1, "first-owner"));
        long second = store.activate(scope, turn.id(), 1, "new-owner");
        assertEquals(first + 1, second);
        assertEquals(
                "stale_execution",
                assertThrows(
                                ManagedStateException.class,
                                () -> store.closeInputs(scope, turn.id(), first))
                        .code());
        assertTrue(store.turn(scope, turn.id()).inputsOpen());
        store.requireEpoch(scope, turn.id(), second);
    }

    @Test
    void inputClosePreservesAcceptedCutoffAndIdempotentReplay() {
        var turn = runningTurn();
        var accepted = store.acceptInput(scope, turn, "actor", "key", Type.INJECT, null, "{}");
        assertEquals(1, store.closeInputs(scope, turn, 1));
        assertEquals(
                accepted, store.acceptInput(scope, turn, "actor", "key", Type.INJECT, null, "{}"));
        assertEquals(
                "input_closed",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        store.acceptInput(
                                                scope,
                                                turn,
                                                "actor",
                                                "later",
                                                Type.INJECT,
                                                null,
                                                "{}"))
                        .code());
        assertEquals(1, store.inputsAfter(scope, turn, 0, 20).size());
    }

    @Test
    void closingExecutionInputsValidatesEpochFromOneLockedSessionRead() throws Exception {
        var turn = runningTurn();
        store.acceptInput(scope, turn, "actor", "key", Type.INJECT, null, "{}");
        var sessionReads = new ArrayList<String>();
        var counted = spy(data);
        doAnswer(
                        call -> {
                            var connection = spy((Connection) call.callRealMethod());
                            doAnswer(
                                            statement -> {
                                                String sql = statement.getArgument(0);
                                                if (sql.startsWith("SELECT * FROM ma.session "))
                                                    sessionReads.add(sql);
                                                return statement.callRealMethod();
                                            })
                                    .when(connection)
                                    .prepareStatement(anyString());
                            return connection;
                        })
                .when(counted)
                .getConnection();
        var guard = mock(ManagedExecutionGuard.class);
        var execution =
                new ManagedTurnExecution(
                        scope,
                        turn,
                        1,
                        guard,
                        new ManagedAdmissionStore(counted, new ObjectMapper()),
                        mock(ManagedJournalStore.class),
                        mock(ManagedRuntimeWriter.class),
                        mock(ManagedLocalJournal.class));

        assertEquals(1, execution.closeInputs());
        assertEquals(
                List.of("SELECT * FROM ma.session WHERE scope_key=? FOR UPDATE"), sessionReads);
        verify(guard).check();
    }

    @Test
    void durableInputOrderRefillsLostOrOutOfOrderRedisCopies() {
        var turn = runningTurn();
        var first = store.acceptInput(scope, turn, "actor", "one", Type.INJECT, null, "{}");
        var second = store.acceptInput(scope, turn, "actor", "two", Type.STEER, null, "{}");
        var inbox = new InMemoryManagedInputInbox(System::currentTimeMillis);
        inbox.append(scope, turn, signal(second));
        inbox.append(scope, turn, signal(first));
        assertEquals(
                2, inbox.read(scope, turn, "0-0", 20, Duration.ZERO).get(0).signal().sequence());
        inbox.clear();
        for (var input : store.inputsAfter(scope, turn, 0, 20))
            inbox.append(scope, turn, signal(input));
        assertEquals(
                List.of(1L, 2L),
                inbox.read(scope, turn, "0-0", 20, Duration.ZERO).stream()
                        .map(entry -> entry.signal().sequence())
                        .toList());
    }

    @Test
    void concurrentAdmissionAndCloseCannotLoseAnAcceptedInput() throws Exception {
        var turn = runningTurn();
        var executor = Executors.newFixedThreadPool(12);
        var start = new CountDownLatch(1);
        try {
            var accepted = new ArrayList<Future<Input>>();
            for (int index = 0; index < 32; index++) {
                String key = "input-" + index;
                accepted.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    try {
                                        return store.acceptInput(
                                                scope, turn, "actor", key, Type.INJECT, null, "{}");
                                    } catch (ManagedStateException closed) {
                                        if (!closed.code().equals("input_closed")) throw closed;
                                        return null;
                                    }
                                }));
            }
            var close =
                    executor.submit(
                            () -> {
                                start.await();
                                return store.closeInputs(scope, turn, 1);
                            });
            start.countDown();
            long cutoff = close.get(5, TimeUnit.SECONDS);
            long count = 0;
            for (var pending : accepted) {
                var input = pending.get(5, TimeUnit.SECONDS);
                if (input != null) {
                    count++;
                    assertTrue(input.sequence() <= cutoff);
                }
            }
            assertEquals(count, cutoff);
            assertEquals(count, store.inputsAfter(scope, turn, 0, 128).size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSessionCreationReturnsSameDurableConfiguration() throws Exception {
        var other = new ManagedScope("tenant", "namespace", "new-session");
        var executor = Executors.newFixedThreadPool(8);
        try {
            var calls = new ArrayList<Future<ManagedAdmissionStore.Session>>();
            for (int index = 0; index < 32; index++)
                calls.add(
                        executor.submit(
                                () -> store.createSession(other, "{\"agentId\":\"agent\"}")));
            for (var call : calls) assertEquals(other, call.get(5, TimeUnit.SECONDS).scope());
        } finally {
            executor.shutdownNow();
        }
    }

    private String runningTurn() {
        var turn = store.acceptTurn(scope, "actor", "key", "{}");
        store.reservePublication(scope, turn.id());
        store.confirmPublication(scope, turn.id(), "broker");
        store.activate(scope, turn.id(), 1, "owner");
        return turn.id();
    }

    private static Signal signal(Input input) {
        return new Signal(input.id(), input.sequence(), input.type(), input.actionId(), input.id());
    }
}
