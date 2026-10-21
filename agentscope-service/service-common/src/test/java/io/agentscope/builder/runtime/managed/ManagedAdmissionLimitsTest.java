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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.managed.ManagedInputInbox.Type;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ManagedAdmissionLimitsTest {
    @Test
    void groupQueueAndRateCapsAreSharedByPortalStoresAndCancellationHasASlot() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:limits-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        var mapper = new ObjectMapper();
        var limits = new ManagedAdmissionStore.Limits(1, 1, 1, 1000);
        var first = new ManagedAdmissionStore(source, mapper, limits);
        var second = new ManagedAdmissionStore(source, mapper, limits);
        var a = new ManagedScope("t", "n", "a");
        var b = new ManagedScope("t", "n", "b");
        first.createSession(a, "{\"application_id\":\"app\"}");
        second.createSession(b, "{\"application_id\":\"app\"}");
        String turn = first.acceptTurn(a, "u", "one", "{}").id();
        assertEquals(
                "admission_limited",
                assertThrows(
                                ManagedStateException.class,
                                () -> second.acceptTurn(b, "u", "two", "{}"))
                        .code());
        assertEquals(turn, second.acceptTurn(a, "u", "one", "{}").id());
        first.acceptInput(a, turn, "u", "inject", Type.INJECT, null, "{}");
        assertEquals(
                "admission_limited",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        second.acceptInput(
                                                a, turn, "u", "overflow", Type.INJECT, null, "{}"))
                        .code());
        first.acceptInput(a, turn, "u", "cancel", Type.CANCEL, null, "{}");
        assertEquals(2, first.pendingInputs(a, turn, 256).size());
        assertEquals(
                "cancel_already_requested",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        second.acceptInput(
                                                a,
                                                turn,
                                                "u",
                                                "cancel-again",
                                                Type.CANCEL,
                                                null,
                                                "{}"))
                        .code());
    }

    @Test
    void pendingReadSkipsAnAlreadySettledPrefix() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:prefix-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        var store =
                new ManagedAdmissionStore(
                        source,
                        new ObjectMapper(),
                        new ManagedAdmissionStore.Limits(1000, 1000, 1000, 1000));
        var scope = new ManagedScope("t", "n", "s");
        store.createSession(scope, "{}");
        var turn = store.acceptTurn(scope, "u", "key", "{}");
        for (int i = 0; i < 300; i++)
            store.acceptInput(scope, turn.id(), "u", "input-" + i, Type.INJECT, null, "{}");
        new JdbcTemplate(source)
                .update("UPDATE ma.input SET status='applied' WHERE input_sequence<=256");
        assertEquals(44, store.pendingInputs(scope, turn.id(), 256).size());
        assertEquals(257, store.pendingInputs(scope, turn.id(), 256).get(0).sequence());
    }

    @Test
    void applicationConcurrencyAndTokenCapsAreNotLostOnAdmission() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:app-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        var store = new ManagedAdmissionStore(source, new ObjectMapper());
        var a = new ManagedScope("t", "n", "a");
        var b = new ManagedScope("t", "n", "b");
        store.createSession(a, "{\"application_id\":\"app\"}");
        store.createSession(b, "{\"application_id\":\"app\"}");
        store.applicationPolicy(a, Map.of("version", 1, "maxConcurrent", 1));
        String first = store.acceptTurn(a, "u", "one", "{}").id();
        assertEquals(
                "admission_limited",
                assertThrows(
                                ManagedStateException.class,
                                () -> store.acceptTurn(b, "u", "two", "{}"))
                        .code());
        new JdbcTemplate(source)
                .update("UPDATE ma.turn SET status='succeeded' WHERE turn_id=?", first);
        store.applicationPolicy(
                b, Map.of("version", 2, "maxConcurrent", 1, "tokenBudget", 10, "tokensUsed", 10));
        assertEquals(
                "admission_limited",
                assertThrows(
                                ManagedStateException.class,
                                () -> store.acceptTurn(b, "u", "two", "{}"))
                        .code());
        store.applicationPolicy(b, Map.of("version", 1, "maxConcurrent", 0, "tokenBudget", 0));
        assertThrows(ManagedStateException.class, () -> store.acceptTurn(b, "u", "two", "{}"));
    }
}
